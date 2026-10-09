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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

/**
 * The lifecycle preferences are system state read on every NEO request, as the calling user. A
 * role without read access on {@code AD_Preference} (any non-admin role) must still get an access
 * decision: production answered 401 to every NEO request of such users, because the DAL threw
 * "Entity ADPreference is not readable" and nothing caught it.
 *
 * @covers com.etendoerp.go.payment.TenantEnvironmentLifecycleService
 */
public class TenantEnvironmentLifecycleServiceAdminModeTest {

  private static final String CLIENT_ID = "client-1";
  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
  private static final String TRANSITION_ACTIVATION = "2026-01-01T00:00:00Z";

  private final TenantEnvironmentLifecycleService service =
      new TenantEnvironmentLifecycleService(mock(TenantPlanService.class));

  @Test
  public void aRoleThatCannotReadPreferencesStillGetsTheLegacyDecision() {
    runAsNonAdmin(new HashMap<>(), () -> assertNull(service.evaluateAccess(CLIENT_ID, true, NOW)));
  }

  /** The demo-revocation marker must be honored for non-admin users too, not silently skipped. */
  @Test
  public void anAssociatedDemoIsRevokedForARoleThatCannotReadPreferences() {
    Map<String, String> stored = new HashMap<>();
    stored.put(TenantEnvironmentLifecycleService.ASSOCIATED_PRODUCTIVE_ATTRIBUTE, "productive-1");
    runAsNonAdmin(stored, () -> assertEquals(
        EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED,
        service.evaluateAccess(CLIENT_ID, true, NOW)));
  }

  /** ETP-5548: the purchase checks read the association marker as the caller, too. */
  @Test
  public void aDemoWithAnAssociationMarkerIsNoLongerAPurchaseSource() {
    Map<String, String> stored = new HashMap<>();
    stored.put(TenantEnvironmentLifecycleService.ASSOCIATED_PRODUCTIVE_ATTRIBUTE, "productive-1");
    runAsNonAdmin(stored, () -> assertTrue(service.isAssociatedWithProductive(CLIENT_ID)));
  }

  @Test
  public void aDemoWithoutAssociationMarkerIsStillAPurchaseSource() {
    runAsNonAdmin(new HashMap<>(), () -> assertFalse(service.isAssociatedWithProductive(CLIENT_ID)));
    assertFalse(service.isAssociatedWithProductive(" "));
  }

  /**
   * ETP-5640 (bug from ETP-5642): the MCP commercial-block check runs before any OBContext exists,
   * where the DAL refuses a non-admin query just as it does for a role without preference access.
   * A tenant that is productive only through its plan marker must still resolve as productive, and
   * must never get a legacy-transition start written for it.
   */
  @Test
  public void aTenantProductiveOnlyByItsPlanIsAllowedWithoutPreferenceAccess() {
    Map<String, String> stored = new HashMap<>();
    stored.put(TenantPlanService.PREFERENCE_ATTRIBUTE, TenantPlanService.PLAN_PRODUCTIVE);
    Writes writes = new Writes();
    TenantEnvironmentLifecycleService withRealPlan =
        new TenantEnvironmentLifecycleService(new TenantPlanService());
    withTransitionActivation(TRANSITION_ACTIVATION, () -> runAsNonAdmin(stored, writes,
        () -> assertEquals(EnvironmentAccessPolicy.Decision.ALLOWED,
            withRealPlan.evaluateAccess(CLIENT_ID, true, NOW))));
    assertEquals("a productive tenant gets no transition start", 0, writes.saved.size());
  }

  /** The legacy-transition start is a write on the same no-context path; it needs admin mode too. */
  @Test
  public void aFreeTenantGetsItsTransitionStartWithoutPreferenceAccess() {
    Writes writes = new Writes();
    TenantEnvironmentLifecycleService withRealPlan =
        new TenantEnvironmentLifecycleService(new TenantPlanService());
    withTransitionActivation(TRANSITION_ACTIVATION, () -> runAsNonAdmin(new HashMap<>(), writes,
        () -> assertEquals(EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED,
            withRealPlan.evaluateAccess(CLIENT_ID, true, NOW))));
    assertEquals("the transition start is persisted once", 1, writes.saved.size());
    assertEquals("and flushed while admin mode is active", 1, writes.flushed.get());
  }

  /**
   * An existing blank transition row is updated in place. The update is dirty-checked at flush,
   * and the end-of-request flush runs after admin mode was restored — with no OBContext on MCP,
   * where the DAL interceptor fails and the request's transaction rolls back. So the write must
   * be flushed while admin mode is still active.
   */
  @Test
  public void aBlankTransitionRowIsUpdatedAndFlushedUnderAdminMode() {
    Map<String, String> stored = new HashMap<>();
    stored.put(TenantEnvironmentLifecycleService.LEGACY_TRANSITION_STARTED_ATTRIBUTE, " ");
    Writes writes = new Writes();
    TenantEnvironmentLifecycleService withRealPlan =
        new TenantEnvironmentLifecycleService(new TenantPlanService());
    withTransitionActivation(TRANSITION_ACTIVATION, () -> runAsNonAdmin(stored, writes,
        () -> assertEquals(EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED,
            withRealPlan.evaluateAccess(CLIENT_ID, true, NOW))));
    assertEquals(1, writes.saved.size());
    verify(writes.saved.get(0)).setSearchKey(TRANSITION_ACTIVATION);
    assertEquals("the update is flushed while admin mode is active", 1, writes.flushed.get());
  }

  /** What the harness let through: the saved rows and the flushes, both under admin mode. */
  private static final class Writes {
    private final List<Preference> saved = new ArrayList<>();
    private final AtomicInteger flushed = new AtomicInteger();
  }

  private static void withTransitionActivation(String activation, Runnable body) {
    String property = TenantEnvironmentLifecycleService.LEGACY_TRANSITION_ACTIVATION_PROPERTY;
    String previous = System.getProperty(property);
    System.setProperty(property, activation);
    try {
      body.run();
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  private static void runAsNonAdmin(Map<String, String> stored, Runnable body) {
    runAsNonAdmin(stored, new Writes(), body);
  }

  /**
   * Runs {@code body} with a DAL that rejects preference queries and saves unless admin mode is
   * active, the way {@code OBDal} does for a role without read access on {@code AD_Preference}
   * and, with no OBContext at all, for every caller. A flush outside admin mode fails too: with
   * no OBContext the DAL interceptor cannot stamp the row. {@code writes} records what passed.
   */
  private static void runAsNonAdmin(Map<String, String> stored, Writes writes,
      Runnable body) {
    AtomicInteger adminDepth = new AtomicInteger();
    OBDal dalInstance = mock(OBDal.class);
    OBQuery<Preference> query = mock(OBQuery.class);
    AtomicReference<String> attribute = new AtomicReference<>();
    doAnswer(invocation -> {
      if ("attribute".equals(invocation.getArgument(0))) {
        attribute.set(invocation.getArgument(1));
      }
      return query;
    }).when(query).setNamedParameter(anyString(), any());
    Map<String, Preference> preferences = new HashMap<>();
    for (Map.Entry<String, String> entry : stored.entrySet()) {
      Preference preference = mock(Preference.class);
      when(preference.getSearchKey()).thenReturn(entry.getValue());
      preferences.put(entry.getKey(), preference);
    }
    when(query.uniqueResult()).thenAnswer(invocation -> preferences.get(attribute.get()));
    when(dalInstance.createQuery(eq(Preference.class), anyString())).thenAnswer(invocation -> {
      if (adminDepth.get() == 0) {
        throw new OBSecurityException("Entity ADPreference is not readable by the user U1");
      }
      return query;
    });
    when(dalInstance.get(Client.class, CLIENT_ID)).thenReturn(mock(Client.class));
    doAnswer(invocation -> {
      if (adminDepth.get() == 0) {
        throw new OBSecurityException("Entity ADPreference is not writable by the user U1");
      }
      return writes.saved.add(invocation.getArgument(0));
    }).when(dalInstance).save(any());
    doAnswer(invocation -> {
      if (adminDepth.get() == 0) {
        throw new NullPointerException("OBContext is null at flush");
      }
      return writes.flushed.incrementAndGet();
    }).when(dalInstance).flush();
    OBProvider provider = mock(OBProvider.class);
    when(provider.get(Preference.class)).thenAnswer(invocation -> mock(Preference.class));

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providers = mockStatic(OBProvider.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      providers.when(OBProvider::getInstance).thenReturn(provider);
      context.when(OBContext::setAdminMode).thenAnswer(invocation -> adminDepth.incrementAndGet());
      context.when(() -> OBContext.setAdminMode(anyBoolean()))
          .thenAnswer(invocation -> adminDepth.incrementAndGet());
      context.when(OBContext::restorePreviousMode)
          .thenAnswer(invocation -> adminDepth.decrementAndGet());
      body.run();
      assertEquals("admin mode must be balanced", 0, adminDepth.get());
    }
  }
}
