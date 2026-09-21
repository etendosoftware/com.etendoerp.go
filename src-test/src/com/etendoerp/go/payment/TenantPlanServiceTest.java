/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Unit specs for {@link TenantPlanService} (ETP-4966).
 *
 * <p>This class is the spec the {@code paid-second-tenant} registry entry declared as accepted debt
 * and never wrote. The note attached to that decision predicted the outcome exactly: "the only
 * covered branch is the one that hides failures — exactly how a paying tenant would silently appear
 * free". That is the bug ETP-4966 reports, so the debt is being paid rather than re-accepted.
 *
 * <p>Two properties matter here beyond ordinary coverage:
 * <ul>
 *   <li><strong>Productive means an open subscription row exists — nothing else.</strong> Since
 *       ETP-5046 {@code resolvePlan} reads {@code ETGO_SUBSCRIPTION}, not the preference. It must
 *       NOT key on the plan carrying a provider price id: the grandfathered
 *       {@code legacy-productive} plan has none by design, so that would flip every backfilled
 *       tenant to free the instant it shipped.</li>
 *   <li><strong>The preference is the SAFETY NET, not a parallel truth.</strong> The paid-upgrade
 *       path writes it only when the subscription write failed, and retires it
 *       ({@code retireProductivePreference}, specified in its own nested class) on success. That
 *       makes the cutover a per-tenant state transition whose end condition is a query —
 *       {@code select count(*) from ad_preference where attribute='ETGO_TenantPlan'} reaching 0 —
 *       rather than a judgement call.</li>
 *   <li><strong>A failed marker must be reportable.</strong> Marking stays best-effort — a
 *       commercial marker must not roll back a provisioned environment — but the caller has to be
 *       able to tell that it failed, otherwise "paid but demo" is indistinguishable from success.
 *       </li>
 *   <li><strong>TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK).</strong> Until the R37 backfill has
 *       run, a tenant with no subscription row at all falls back to the retired preference, so
 *       already-paying tenants do not silently drop to free between the deploy and the backfill.
 *       The {@code PreferenceFallback} nested class specifies it — including the one mistake that
 *       must never recur: the preference is scoped by {@code VISIBLEAT_CLIENT_ID}, never by
 *       {@code AD_CLIENT_ID}. Delete that nested class in Phase F with the fallback itself.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantPlanServiceTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";
  private static final String ORG_ID = "9F5511B92BD0465FA678F75278FA9C3A";
  private static final String OTHER_CLIENT_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";

  @Mock private OBDal obDal;
  @Mock private SubscriptionService subscriptionService;
  @Mock private TenantPlanPreferenceFallback preferenceFallback;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<Preferences> preferencesMock;

  private final TenantPlanService service = new TenantPlanService();

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    preferencesMock = mockStatic(Preferences.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    service.subscriptionService = subscriptionService;
    // ETP-5046-TRANSITIONAL-FALLBACK — substituted so the subscription specs below stay pure unit
    // tests. Its own behaviour is specified in the PreferenceFallback nested class.
    service.preferenceFallback = preferenceFallback;
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (preferencesMock != null) {
      preferencesMock.close();
    }
  }

  /** Stubs the subscription lookup {@code resolvePlan} performs with an open row. */
  private void givenOpenSubscription(String status) {
    Subscription subscription = mock(Subscription.class);
    when(subscription.getSubscriptionStatus()).thenReturn(status);
    when(subscriptionService.findOpen(CLIENT_ID)).thenReturn(Optional.of(subscription));
  }

  /** Stubs the subscription lookup with no open row — a closed one, or none at all. */
  private void givenNoOpenSubscription() {
    when(subscriptionService.findOpen(CLIENT_ID)).thenReturn(Optional.empty());
  }

  @Nested
  class MarkProductive {

    @Test
    void writesTheProductiveMarkerAgainstTheTenantAndItsStarOrganisation() {
      Client client = mock(Client.class);
      Organization organization = mock(Organization.class);
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
      when(obDal.get(Organization.class, ORG_ID)).thenReturn(organization);

      boolean marked = service.markProductive(CLIENT_ID, ORG_ID);

      assertTrue(marked, "a successful write must be reported as such");
      // isListProperty=false is what routes the key to AD_Preference.Attribute, which is the
      // column resolvePlan queries. Asserted literally so a future edit cannot flip it silently.
      preferencesMock.verify(() -> Preferences.setPreferenceValue(
          TenantPlanService.PREFERENCE_ATTRIBUTE, TenantPlanService.PLAN_PRODUCTIVE, false,
          client, organization, null, null, null, null));
    }

    @Test
    void writesTheMarkerWithNoOrganisationWhenNoneWasResolved() {
      Client client = mock(Client.class);
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);

      boolean marked = service.markProductive(CLIENT_ID, null);

      assertTrue(marked);
      preferencesMock.verify(() -> Preferences.setPreferenceValue(
          TenantPlanService.PREFERENCE_ATTRIBUTE, TenantPlanService.PLAN_PRODUCTIVE, false,
          client, null, null, null, null, null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void reportsFailureAndWritesNothingForABlankClientId(String clientId) {
      boolean marked = service.markProductive(clientId, ORG_ID);

      assertFalse(marked, "there is no tenant to mark, so this is not a success");
      preferencesMock.verifyNoInteractions();
    }

    @Test
    void reportsFailureWhenTheTenantDoesNotExist() {
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(null);

      boolean marked = service.markProductive(CLIENT_ID, ORG_ID);

      assertFalse(marked, "a paid environment that could not be marked must not report success");
      preferencesMock.verifyNoInteractions();
    }

    @Test
    void reportsFailureWithoutPropagatingWhenTheWriteBlowsUp() {
      Client client = mock(Client.class);
      when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
      preferencesMock.when(() -> Preferences.setPreferenceValue(anyString(), anyString(),
          eq(false), any(), any(), any(), any(), any(), any()))
          .thenThrow(new IllegalStateException("preference write failed"));

      boolean marked = service.markProductive(CLIENT_ID, ORG_ID);

      // Best-effort stays best-effort: a commercial marker must never roll back an otherwise
      // complete environment. What changes is that the caller can now see it happened.
      assertFalse(marked);
    }
  }

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — specs for the per-tenant retirement of the
   * legacy {@code ETGO_TenantPlan} marker. Delete this nested class in Phase F with the method.
   *
   * <p>This is the runtime twin of statement 3 of the R37 backfill data-fix: the backfill retires
   * the marker of tenants that predate the subscription model, this method retires it for a tenant
   * that has just paid, and between them the fleet converges from both ends onto ONE observable end
   * condition — {@code select count(*) from ad_preference where attribute='ETGO_TenantPlan'}
   * reaching 0. Both must therefore share the same scoping and the same "retire everything" policy,
   * which is what these specs pin.
   */
  @Nested
  class RetireProductivePreference {

    @Mock private OBQuery<Preference> preferenceQuery;

    private Preference storedPreference() {
      return mock(Preference.class);
    }

    private String capturedHql() {
      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(obDal).createQuery(eq(Preference.class), hql.capture());
      return hql.getValue();
    }

    @Test
    void scopesTheLookupByVisibleAtClientAndNeverByAdClient() {
      // The same mistake the fallback's own spec guards, on the write side this time — and with a
      // worse failure mode: a removal filtered on AD_CLIENT_ID matches ZERO rows for every tenant,
      // so the retirement would be a silent no-op, the end-condition count would never reach 0,
      // and Phase F would be blocked forever with nothing reporting why. Verified on the live
      // database: via_visibleat=6, via_adclient=0.
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.list()).thenReturn(List.of(storedPreference()));

      assertTrue(service.retireProductivePreference(CLIENT_ID));

      String hql = capturedHql();
      assertTrue(hql.contains("pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id"),
          "the tenant lives in VISIBLEAT_CLIENT_ID: " + hql);
      assertFalse(hql.contains("pref." + Preference.PROPERTY_CLIENT + "."),
          "AD_CLIENT_ID is '0' for every one of these rows, so filtering on it matches nothing: "
              + hql);
      verify(preferenceQuery).setNamedParameter("clientId", CLIENT_ID);
      verify(preferenceQuery)
          .setNamedParameter("attribute", TenantPlanService.PREFERENCE_ATTRIBUTE);
      verify(preferenceQuery).setFilterOnReadableClients(false);
      verify(preferenceQuery).setFilterOnReadableOrganization(false);
    }

    @Test
    void removesEveryMatchingRowWhateverItsValueOrActiveFlag() {
      // Deliberate, and mirrored in the R37 SQL: once the tenant has an open subscription, any
      // surviving marker is a second answer to a question that now has one authority. A leftover
      // inactive or non-'productive' row would also keep the end-condition count above zero
      // forever, which is the one property that makes the per-tenant design worth having — so the
      // query must not filter on the value or on isactive.
      Preference first = storedPreference();
      Preference second = storedPreference();
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.list()).thenReturn(List.of(first, second));

      assertTrue(service.retireProductivePreference(CLIENT_ID));

      String hql = capturedHql();
      assertFalse(hql.contains(Preference.PROPERTY_ACTIVE),
          "an inactive leftover is still a leftover: " + hql);
      assertFalse(hql.contains(Preference.PROPERTY_SEARCHKEY),
          "the stored value is irrelevant once a subscription exists: " + hql);
      verify(obDal).remove(first);
      verify(obDal).remove(second);
      verify(obDal).flush();
    }

    @Test
    void reportsNothingRetiredWhenTheTenantCarriesNoMarker() {
      // The ordinary steady state after the cutover, and not a failure: a tenant onboarded after
      // the write path was retired never had the preference in the first place.
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.list()).thenReturn(List.of());

      assertFalse(service.retireProductivePreference(CLIENT_ID));

      verify(obDal, never()).remove(any());
      verify(obDal, never()).flush();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void retiresNothingForABlankClientIdWithoutQuerying(String clientId) {
      assertFalse(service.retireProductivePreference(clientId));

      verify(obDal, never()).createQuery(eq(Preference.class), anyString());
    }

    @Test
    void neverPropagatesAFailure() {
      // A failed retirement is harmless — the fallback keeps answering and R37 retires the row
      // later — so it must never be able to abort a paid upgrade. Reported as false all the same,
      // and logged by the method itself.
      when(obDal.createQuery(eq(Preference.class), anyString()))
          .thenThrow(new IllegalStateException("no session"));

      assertFalse(service.retireProductivePreference(CLIENT_ID));
    }

    @Test
    void neverPropagatesAFailureOfTheRemovalItself() {
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.list()).thenReturn(List.of(storedPreference()));
      doThrow(new IllegalStateException("constraint violation")).when(obDal).remove(any());

      assertFalse(service.retireProductivePreference(CLIENT_ID));
    }
  }

  @Nested
  class ResolvePlan {

    @Test
    void readsBackProductiveForAnActiveSubscription() {
      givenOpenSubscription(SubscriptionService.STATUS_ACTIVE);

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void keepsAPastDueTenantProductiveWhileTheProviderIsStillRetrying() {
      // An invoice has failed but the subscription is live and Stripe is still retrying. Cutting
      // access off mid-dunning punishes a customer whose card merely expired.
      givenOpenSubscription(SubscriptionService.STATUS_PAST_DUE);

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeForACanceledSubscription() {
      givenOpenSubscription(SubscriptionService.STATUS_CANCELED);

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeWhenTheTenantHasNoOpenSubscription() {
      // findOpen already filters on END_DATE IS NULL, so a closed row — the previous row of a plan
      // change, or a tenant that churned — never reaches this method and reads as free.
      givenNoOpenSubscription();

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeForAnUnrecognisedStatus() {
      givenOpenSubscription("trialing");

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void acceptsAStoredStatusInAnyCase() {
      givenOpenSubscription("ACTIVE");

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void doesNotKeyProductiveOnThePlanCarryingAProviderPrice() {
      // THE most dangerous possible mis-implementation of ETP-5046. The grandfathered
      // legacy-productive plan has no PROVIDER_PRICE_ID by design, so a resolvePlan that looked at
      // the price would flip every backfilled tenant to free the instant it shipped — silently.
      // Productive means an open subscription row exists, full stop; this spec passes a
      // subscription whose plan is never even consulted.
      Subscription subscription = mock(Subscription.class);
      when(subscription.getSubscriptionStatus()).thenReturn(SubscriptionService.STATUS_ACTIVE);
      when(subscriptionService.findOpen(CLIENT_ID)).thenReturn(Optional.of(subscription));

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));

      verify(subscription, never()).getProviderPriceID();
      verify(subscription, never()).getPlan();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void readsBackFreeForABlankClientIdWithoutQuerying(String clientId) {
      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(clientId));

      verify(subscriptionService, never()).findOpen(anyString());
    }

    @Test
    void readsBackFreeWhenTheLookupFails() {
      // Contractual: never null, never throws, degrades to free.
      // OnboardingForceTestModeService compares the result to PLAN_FREE with no null guard.
      when(subscriptionService.findOpen(CLIENT_ID))
          .thenThrow(new IllegalStateException("no session"));

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeForASubscriptionWithNoStatusAtAll() {
      givenOpenSubscription(null);

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void neverReachesThePreferenceFallbackWhenTheTenantHasAnOpenSubscription() {
      // ETP-5046-TRANSITIONAL-FALLBACK — the fallback must not add a query per tenant in the
      // normal case. An open row answers on its own; the preference is not even consulted.
      givenOpenSubscription(SubscriptionService.STATUS_ACTIVE);

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));

      verify(preferenceFallback, never()).isProductive(anyString());
    }

    @Test
    void neverReachesThePreferenceFallbackForAnOpenButCanceledSubscription() {
      // An open row exists and says the entitlement is gone. That IS the subscription's answer,
      // so the retired preference must not resurrect a tenant that the new source of truth has
      // already closed.
      givenOpenSubscription(SubscriptionService.STATUS_CANCELED);

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));

      verify(preferenceFallback, never()).isProductive(anyString());
    }

    @Test
    void fallsBackToTheRetiredPreferenceWhenTheTenantHasNoSubscriptionAtAll() {
      // ETP-5046-TRANSITIONAL-FALLBACK — the whole point: a tenant the R37 backfill has not
      // reached still resolves as productive instead of silently losing its paid plan.
      givenNoOpenSubscription();
      when(preferenceFallback.isProductive(CLIENT_ID)).thenReturn(true);

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void fallsBackToTheRetiredPreferenceWhenTheOnlySubscriptionRowIsClosed() {
      // findOpen filters on END_DATE IS NULL, so a closed row — a churned tenant, or the previous
      // row of a plan change — is indistinguishable here from having no row at all, and takes the
      // same transitional path.
      givenNoOpenSubscription();
      when(preferenceFallback.isProductive(CLIENT_ID)).thenReturn(true);

      assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));

      verify(preferenceFallback).isProductive(CLIENT_ID);
    }

    @Test
    void readsBackFreeWhenNeitherTheSubscriptionNorThePreferenceSaysProductive() {
      givenNoOpenSubscription();
      when(preferenceFallback.isProductive(CLIENT_ID)).thenReturn(false);

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }

    @Test
    void readsBackFreeWhenTheFallbackItselfBlowsUp() {
      // Contractual: never null, never throws, degrades to free. A transitional workaround must
      // not be able to break OnboardingForceTestModeService, which compares to PLAN_FREE with no
      // null guard.
      givenNoOpenSubscription();
      when(preferenceFallback.isProductive(CLIENT_ID))
          .thenThrow(new IllegalStateException("preference query failed"));

      assertEquals(TenantPlanService.PLAN_FREE, service.resolvePlan(CLIENT_ID));
    }
  }

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — specs for
   * {@link TenantPlanPreferenceFallback}, the read-side tolerance for the window between deploying
   * the subscription rewrite and running the R37 backfill. Delete this whole nested class in Phase
   * F together with {@code markProductive} and {@code PREFERENCE_ATTRIBUTE}.
   */
  @Nested
  class PreferenceFallback {

    @Mock private OBQuery<Preference> preferenceQuery;

    private final TenantPlanPreferenceFallback fallback = new TenantPlanPreferenceFallback();

    private CapturingAppender warnings;

    @BeforeEach
    void captureWarnings() {
      warnings = CapturingAppender.attachTo(TenantPlanPreferenceFallback.class);
    }

    @AfterEach
    void releaseWarnings() {
      if (warnings != null) {
        warnings.detach();
      }
    }

    private Preference preferenceFor(String clientId, String value) {
      Client tenant = mock(Client.class);
      when(tenant.getId()).thenReturn(clientId);
      Preference preference = mock(Preference.class);
      when(preference.getVisibleAtClient()).thenReturn(tenant);
      when(preference.getSearchKey()).thenReturn(value);
      return preference;
    }

    private String capturedHql() {
      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(obDal).createQuery(eq(Preference.class), hql.capture());
      return hql.getValue();
    }

    @Test
    void scopesTheLookupByVisibleAtClientAndNeverByAdClient() {
      // THE mistake this spec exists to stop recurring. Preferences.setPreferenceValue stores the
      // row at AD_CLIENT_ID = '0' and puts the tenant in VISIBLEAT_CLIENT_ID only, so a query
      // filtered on AD_CLIENT_ID matches ZERO rows for EVERY tenant — silently, with every paying
      // tenant reading back as free. That exact mistake nearly shipped in the R37 backfill SQL.
      Preference stored = preferenceFor(CLIENT_ID, TenantPlanService.PLAN_PRODUCTIVE);
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.uniqueResult()).thenReturn(stored);

      assertTrue(fallback.isProductive(CLIENT_ID));

      String hql = capturedHql();
      assertTrue(hql.contains("pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id"),
          "the tenant lives in VISIBLEAT_CLIENT_ID: " + hql);
      assertFalse(hql.contains("pref." + Preference.PROPERTY_CLIENT + "."),
          "AD_CLIENT_ID is '0' for every one of these rows, so filtering on it matches nothing: "
              + hql);
      verify(preferenceQuery).setNamedParameter("clientId", CLIENT_ID);
      verify(preferenceQuery)
          .setNamedParameter("attribute", TenantPlanService.PREFERENCE_ATTRIBUTE);
      verify(preferenceQuery).setFilterOnReadableClients(false);
      verify(preferenceQuery).setFilterOnReadableOrganization(false);
    }

    @Test
    void warnsEveryTimeItAnswersForATenant() {
      // The fallback is otherwise invisible, and a silent fallback would let the backfill be
      // forgotten forever. This WARN is the signal that says when the fallback is safe to delete:
      // when it stops appearing, every tenant has a subscription row.
      Preference stored = preferenceFor(CLIENT_ID, TenantPlanService.PLAN_PRODUCTIVE);
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.uniqueResult()).thenReturn(stored);

      assertTrue(fallback.isProductive(CLIENT_ID));

      List<String> warned = warnings.messagesAt(Level.WARN);
      assertEquals(1, warned.size(), "one line per resolution, not one per comparison: " + warned);
      assertTrue(warned.get(0).contains(CLIENT_ID), "the tenant must be named: " + warned.get(0));
      assertTrue(warned.get(0).contains("ETP-5046"),
          "the line must point at the backfill that closes the gap: " + warned.get(0));
    }

    @Test
    void readsFreeAndStaysSilentWhenTheTenantHasNoPreferenceAtAll() {
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.uniqueResult()).thenReturn(null);

      assertFalse(fallback.isProductive(CLIENT_ID));
      assertTrue(warnings.messagesAt(Level.WARN).isEmpty(),
          "nothing fell back, so nothing should be reported");
    }

    @Test
    void readsFreeForAPreferenceHoldingSomethingOtherThanProductive() {
      Preference stored = preferenceFor(CLIENT_ID, "demo");
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.uniqueResult()).thenReturn(stored);

      assertFalse(fallback.isProductive(CLIENT_ID));
    }

    @Test
    void acceptsTheStoredValueTrimmedAndInAnyCase() {
      Preference stored = preferenceFor(CLIENT_ID, "  PRODUCTIVE ");
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.uniqueResult()).thenReturn(stored);

      assertTrue(fallback.isProductive(CLIENT_ID));
    }

    @Test
    void readsFreeWithoutPropagatingWhenTheQueryBlowsUp() {
      when(obDal.createQuery(eq(Preference.class), anyString()))
          .thenThrow(new IllegalStateException("no session"));

      assertFalse(fallback.isProductive(CLIENT_ID));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void readsFreeForABlankTenantWithoutQuerying(String clientId) {
      assertFalse(fallback.isProductive(clientId));

      verify(obDal, never()).createQuery(eq(Preference.class), anyString());
    }

    @Test
    void resolvesSeveralTenantsInExactlyOneQuery() {
      List<Preference> stored = List.of(
          preferenceFor(CLIENT_ID, TenantPlanService.PLAN_PRODUCTIVE),
          preferenceFor(OTHER_CLIENT_ID, "demo"));
      when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
      when(preferenceQuery.list()).thenReturn(stored);

      Set<String> productive =
          fallback.productiveAmong(List.of(CLIENT_ID, OTHER_CLIENT_ID, "MISSING-TENANT"));

      assertEquals(Set.of(CLIENT_ID), productive);
      // The N+1 this whole bulk path exists to avoid: one query for the batch, never one each.
      verify(obDal, times(1)).createQuery(eq(Preference.class), anyString());
      assertTrue(capturedHql().contains("pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id in"));
      assertEquals(1, warnings.messagesAt(Level.WARN).size(),
          "one line per tenant actually served by the fallback");
    }

    @Test
    void issuesNoQueryForAnEmptyOrAllBlankBatch() {
      assertTrue(fallback.productiveAmong(null).isEmpty());
      assertTrue(fallback.productiveAmong(List.of()).isEmpty());
      assertTrue(fallback.productiveAmong(List.of("", "   ")).isEmpty());

      verify(obDal, never()).createQuery(eq(Preference.class), anyString());
    }

    @Test
    void readsEveryoneAsFreeWithoutPropagatingWhenTheBatchQueryBlowsUp() {
      when(obDal.createQuery(eq(Preference.class), anyString()))
          .thenThrow(new IllegalStateException("no session"));

      assertTrue(fallback.productiveAmong(List.of(CLIENT_ID, OTHER_CLIENT_ID)).isEmpty());
    }
  }

  /**
   * Collects the log events of one logger so a spec can assert on them.
   *
   * <p>Log4j2's default configuration is ERROR-only, so the level is pinned before attaching and
   * restored afterwards; without that the WARN this fallback exists to emit would never reach an
   * appender and the spec would pass vacuously.
   */
  private static final class CapturingAppender extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();
    private final String loggerName;
    private final Level previousLevel;

    private CapturingAppender(String loggerName, Level previousLevel) {
      super("Etp5046FallbackCapture", (Filter) null, (Layout<? extends Serializable>) null, true,
          new Property[0]);
      this.loggerName = loggerName;
      this.previousLevel = previousLevel;
    }

    static CapturingAppender attachTo(Class<?> type) {
      String name = type.getName();
      Level previous = LogManager.getLogger(name).getLevel();
      Configurator.setLevel(name, Level.WARN);
      CapturingAppender appender = new CapturingAppender(name, previous);
      appender.start();
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(name)).addAppender(appender);
      return appender;
    }

    void detach() {
      ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName))
          .removeAppender(this);
      stop();
      Configurator.setLevel(loggerName, previousLevel);
    }

    List<String> messagesAt(Level level) {
      List<String> messages = new ArrayList<>();
      for (LogEvent event : events) {
        if (level.equals(event.getLevel())) {
          messages.add(event.getMessage().getFormattedMessage());
        }
      }
      return messages;
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }
  }

  @Test
  void theSafetyNetStillWritesAndStillAnswersUntilTheBackfillHasRun() {
    // ETP-5046-TRANSITIONAL-FALLBACK — end to end, the one path that still needs the preference.
    // The paid-upgrade flow writes the marker ONLY when the subscription write failed; this spec
    // is that tenant. It has no open subscription, so resolvePlan falls through to the fallback
    // and reads the marker back as productive — which is the whole reason neither the write path
    // nor the read may be deleted before every tenant has a subscription row.
    Client client = mock(Client.class);
    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
    assertTrue(service.markProductive(CLIENT_ID, null));

    givenNoOpenSubscription();
    when(preferenceFallback.isProductive(CLIENT_ID)).thenReturn(true);

    assertEquals(TenantPlanService.PLAN_PRODUCTIVE, service.resolvePlan(CLIENT_ID));
  }
}
