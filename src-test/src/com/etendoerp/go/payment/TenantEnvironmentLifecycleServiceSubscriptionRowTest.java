/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Specs for where a Stripe lifecycle outcome lands and is read back from (ETP-5046).
 *
 * <p><b>Two stores, chosen per tenant.</b> A tenant with an open {@code ETGO_SUBSCRIPTION} row has
 * its status and grace anchor written onto that row and read back from it; a tenant the R37
 * backfill has not reached keeps the {@code ETGO_SubscriptionStatus} / {@code ETGO_SubscriptionDueAt}
 * preferences. Writing to one store and reading from the other is the regression these specs
 * exist for: it is silent, and it makes a paying tenant's access decision disagree with the plan
 * catalog.
 *
 * <p>The ordering watermark ({@code ETGO_SubscriptionEventAt}) stays a preference on <em>both</em>
 * routes, so a stale event is still rejected after a tenant moves onto a row.
 *
 * <p>A real {@link SubscriptionService} runs against a mocked DAL whose preference and
 * subscription queries — and subscription saves — fail unless admin mode is active, the way
 * {@code OBDal} behaves for a role that cannot read {@code AD_Preference} / {@code ETGO_SUBSCRIPTION}
 * (same technique as {@link TenantEnvironmentLifecycleServiceAdminModeTest}, ETP-5488).
 */
public class TenantEnvironmentLifecycleServiceSubscriptionRowTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final Instant ANCHOR = Instant.parse("2026-10-31T00:00:00Z");
  private static final Instant EVENT_AT = Instant.parse("2026-11-01T12:30:00Z");

  private final TenantPlanService tenantPlanService = mock(TenantPlanService.class);
  private final TenantEnvironmentLifecycleService service =
      new TenantEnvironmentLifecycleService(tenantPlanService, new SubscriptionService());

  // ===================== write routing =====================

  @Test
  public void aTenantWithARowGetsCurrentWrittenAsActiveOnTheRowAndNoPreference() {
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR);

    assertTrue(fixture.run(() -> service.updateSubscriptionStatus(CLIENT_ID,
        EnvironmentAccessPolicy.SubscriptionStatus.CURRENT, null)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_ACTIVE);
    // null clears the anchor, exactly as it clears the preference projection's due date.
    verify(fixture.row).setCurrentPeriodEnd(null);
    assertTrue("the row route must not also write the preference projection",
        fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTenantWithARowGetsPastDueAndItsGraceAnchorOnTheRow() {
    Fixture fixture = new Fixture().withOpenRow("active", null);

    assertTrue(fixture.run(() -> service.updateSubscriptionStatus(CLIENT_ID,
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_PAST_DUE);
    verify(fixture.row).setCurrentPeriodEnd(Date.from(ANCHOR));
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTenantWithARowGetsExpiredAsCanceledWithoutClosingTheRow() {
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR);

    assertTrue(fixture.run(() -> service.updateSubscriptionStatus(CLIENT_ID,
        EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, null)));

    verify(fixture.row).setSubscriptionStatus(SubscriptionService.STATUS_CANCELED);
    // Decided behaviour: canceled leaves END_DATE null — closing a row is what a plan change does.
    verify(fixture.row, never()).setEndDate(any());
    assertTrue(fixture.savedPreferenceAttributes.isEmpty());
  }

  @Test
  public void aTenantWithoutARowKeepsThePreferenceRoute() {
    Fixture fixture = new Fixture().asWebhook();

    assertTrue(fixture.run(() -> service.updateSubscriptionStatus(CLIENT_ID,
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)));

    assertEquals(List.of(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE,
        TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE),
        fixture.savedPreferenceAttributes);
    assertEquals(List.of("PAST_DUE", ANCHOR.toString()), fixture.savedPreferenceValues);
    verify(fixture.dal, never()).save(any(Subscription.class));
  }

  // ===================== read routing =====================

  @Test
  public void aTenantWithARowIsReadBackFromTheRowNotFromStalePreferences() {
    // The preferences still hold what the tenant had before it moved onto a row. They must lose.
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            "2020-01-01T00:00:00Z")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());

    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(() -> service.readSubscriptionState(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, state.status());
    assertEquals(ANCHOR, state.dueAt());
    // ...but the watermark is a preference on both routes: the row has no column for it.
    assertEquals(EVENT_AT, state.lastEventAt());
  }

  @Test
  public void aCanceledRowIsReadBackAsExpiredWithAClearedAnchor() {
    Fixture fixture = new Fixture().withOpenRow("canceled", null);

    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(() -> service.readSubscriptionState(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED, state.status());
    assertNull(state.dueAt());
  }

  @Test
  public void aTenantWithoutARowIsReadBackFromThePreferences() {
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString());

    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(() -> service.readSubscriptionState(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, state.status());
    assertEquals(ANCHOR, state.dueAt());
  }

  @Test
  public void theProductiveSnapshotReadsTheRowTheWebhookWrote() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    Fixture fixture = new Fixture().withOpenRow("past_due", ANCHOR)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT");

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> service.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, snapshot.getType());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        snapshot.getSubscriptionStatus());
    assertEquals(ANCHOR, snapshot.getRenewalDueAt());
  }

  @Test
  public void theProductiveSnapshotOfATenantWithoutARowReadsThePreferences() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    Fixture fixture = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE")
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE,
            ANCHOR.toString());

    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        fixture.run(() -> service.resolve(CLIENT_ID));

    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        snapshot.getSubscriptionStatus());
    assertEquals(ANCHOR, snapshot.getRenewalDueAt());
  }

  // ===================== ordering watermark on the row route =====================

  @Test
  public void anOlderEventIsStillRejectedAsStaleOnceTheTenantHasARow() throws Exception {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());
    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(() -> service.readSubscriptionState(CLIENT_ID));

    // A late payment_failed created before the invoice.paid already applied.
    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate(
        SubscriptionLifecycleApplier.INVOICE_PAYMENT_FAILED,
        paymentFailed(EVENT_AT.minusSeconds(60L)), state);

    assertTrue(outcome.isIgnored());
    assertEquals("stale event", outcome.reason());
  }

  @Test
  public void aNewerEventIsAppliedOnTheRowRoute() throws Exception {
    Fixture fixture = new Fixture().withOpenRow("active", null)
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
            EVENT_AT.toString());
    SubscriptionLifecycleApplier.StoredState state =
        fixture.run(() -> service.readSubscriptionState(CLIENT_ID));

    SubscriptionEventOutcome outcome = new SubscriptionLifecycleApplier().evaluate(
        SubscriptionLifecycleApplier.INVOICE_PAYMENT_FAILED,
        paymentFailed(EVENT_AT.plusSeconds(60L)), state);

    assertTrue(outcome.isApplied());
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, outcome.status());
  }

  @Test
  public void theWatermarkIsRecordedAsAPreferenceEvenForATenantWithARow() {
    Fixture fixture = new Fixture().withOpenRow("active", null).asWebhook();

    fixture.run(() -> {
      service.recordSubscriptionEventAt(CLIENT_ID, EVENT_AT);
      return null;
    });

    assertEquals(List.of(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE),
        fixture.savedPreferenceAttributes);
  }

  // ===================== admin mode =====================

  @Test
  public void aNonAdminCallerStillReadsBothRoutes() {
    // The Fixture's DAL refuses every preference/subscription query outside admin mode, so any
    // of these reads leaking out of admin mode would come back empty (or throw) here.
    Fixture withRow = new Fixture().withOpenRow("past_due", ANCHOR);
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE,
        withRow.run(() -> service.readSubscriptionState(CLIENT_ID)).status());

    Fixture withoutRow = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "EXPIRED");
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED,
        withoutRow.run(() -> service.readSubscriptionState(CLIENT_ID)).status());
  }

  @Test
  public void aNonAdminCallerStillGetsTheProductiveSnapshotOnBothRoutes() {
    when(tenantPlanService.resolvePlan(CLIENT_ID)).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);

    Fixture withRow = new Fixture().withOpenRow("canceled", null);
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED,
        withRow.run(() -> service.resolve(CLIENT_ID)).getSubscriptionStatus());

    Fixture withoutRow = new Fixture()
        .withPreference(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "CURRENT");
    assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.CURRENT,
        withoutRow.run(() -> service.resolve(CLIENT_ID)).getSubscriptionStatus());
  }

  @Test
  public void theRowRouteWritesInAdminMode() {
    // The subscription row lives at client 0; saving it outside admin mode is refused by the DAL
    // (the Fixture throws), which updateSubscriptionStatus would swallow into a false.
    Fixture fixture = new Fixture().withOpenRow("active", null);

    assertTrue(fixture.run(() -> service.updateSubscriptionStatus(CLIENT_ID,
        EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, ANCHOR)));

    verify(fixture.dal).save(fixture.row);
  }

  // ===================== fixture =====================

  private static JSONObject paymentFailed(Instant createdAt) throws Exception {
    return new JSONObject().put("created", createdAt.getEpochSecond()).put("data",
        new JSONObject().put("object", new JSONObject().put("id", "in_1")
            .put("subscription", "sub_1").put("period_end", ANCHOR.getEpochSecond())));
  }

  /**
   * A DAL holding at most one open subscription row and some lifecycle preferences, which refuses
   * every read (and every subscription save) made outside admin mode.
   */
  private static final class Fixture {
    final OBDal dal = mock(OBDal.class);
    final Map<String, String> preferences = new HashMap<>();
    final List<String> savedPreferenceAttributes = new ArrayList<>();
    final List<String> savedPreferenceValues = new ArrayList<>();
    final AtomicInteger adminDepth = new AtomicInteger();
    Subscription row;
    boolean systemCaller;

    /**
     * The webhook path: it reaches the lifecycle service holding the system context the checkout
     * request store left behind, so its writes are not refused whatever the admin-mode state.
     */
    Fixture asWebhook() {
      systemCaller = true;
      return this;
    }

    Fixture withOpenRow(String status, Instant periodEnd) {
      row = mock(Subscription.class);
      when(row.getSubscriptionStatus()).thenReturn(status);
      when(row.getCurrentPeriodEnd()).thenReturn(periodEnd == null ? null : Date.from(periodEnd));
      return this;
    }

    Fixture withPreference(String attribute, String value) {
      preferences.put(attribute, value);
      return this;
    }

    @SuppressWarnings("unchecked")
    <T> T run(java.util.function.Supplier<T> body) {
      OBQuery<Preference> preferenceQuery = mock(OBQuery.class);
      AtomicReference<String> attribute = new AtomicReference<>();
      doAnswer(invocation -> {
        if ("attribute".equals(invocation.getArgument(0))) {
          attribute.set(invocation.getArgument(1));
        }
        return preferenceQuery;
      }).when(preferenceQuery).setNamedParameter(anyString(), any());
      // Built up front: stubbing a mock from inside another mock's answer is not reliable.
      Map<String, Preference> stored = new HashMap<>();
      for (Map.Entry<String, String> entry : preferences.entrySet()) {
        Preference preference = mock(Preference.class);
        when(preference.getSearchKey()).thenReturn(entry.getValue());
        stored.put(entry.getKey(), preference);
      }
      when(preferenceQuery.uniqueResult()).thenAnswer(invocation -> stored.get(attribute.get()));
      OBQuery<Subscription> subscriptionQuery = mock(OBQuery.class);
      when(subscriptionQuery.uniqueResult()).thenAnswer(invocation -> row);
      when(dal.createQuery(eq(Preference.class), anyString())).thenAnswer(invocation -> {
        requireAdmin("ADPreference");
        return preferenceQuery;
      });
      when(dal.createQuery(eq(Subscription.class), anyString())).thenAnswer(invocation -> {
        requireAdmin("ETGO_Subscription");
        return subscriptionQuery;
      });
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(CLIENT_ID);
      when(dal.get(Client.class, CLIENT_ID)).thenReturn(client);
      doAnswer(invocation -> {
        Object saved = invocation.getArgument(0);
        if (saved instanceof Subscription) {
          requireAdmin("ETGO_Subscription");
        }
        return null;
      }).when(dal).save(any());
      OBProvider provider = mock(OBProvider.class);
      List<Preference> created = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        Preference preference = mock(Preference.class);
        doAnswer(set -> {
          savedPreferenceAttributes.add(set.getArgument(0));
          return null;
        }).when(preference).setAttribute(anyString());
        doAnswer(set -> {
          savedPreferenceValues.add(set.getArgument(0));
          return null;
        }).when(preference).setSearchKey(anyString());
        created.add(preference);
      }
      AtomicInteger nextCreated = new AtomicInteger();
      when(provider.get(Preference.class))
          .thenAnswer(invocation -> created.get(nextCreated.getAndIncrement()));

      try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
          MockedStatic<OBProvider> providerStatic = mockStatic(OBProvider.class);
          MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
        dalStatic.when(OBDal::getInstance).thenReturn(dal);
        providerStatic.when(OBProvider::getInstance).thenReturn(provider);
        context.when(OBContext::setAdminMode).thenAnswer(invocation -> adminDepth.incrementAndGet());
        context.when(() -> OBContext.setAdminMode(anyBoolean()))
            .thenAnswer(invocation -> adminDepth.incrementAndGet());
        context.when(OBContext::restorePreviousMode)
            .thenAnswer(invocation -> adminDepth.decrementAndGet());
        T result = body.get();
        assertEquals("admin mode must be balanced", 0, adminDepth.get());
        return result;
      }
    }

    private void requireAdmin(String entity) {
      if (!systemCaller && adminDepth.get() == 0) {
        throw new OBSecurityException("Entity " + entity + " is not readable by the user U1");
      }
    }
  }

  @Test
  public void theFixtureReallyRefusesNonAdminReads() {
    // Guard against a fixture that silently lets everything through, which would make every
    // admin-mode assertion above vacuous.
    Fixture fixture = new Fixture();
    boolean refused = fixture.run(() -> {
      try {
        OBDal.getInstance().createQuery(Preference.class, "as pref");
        return false;
      } catch (OBSecurityException expected) {
        return true;
      }
    });
    assertTrue(refused);
    assertFalse(fixture.savedPreferenceAttributes.contains("anything"));
  }
}
