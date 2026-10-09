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
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
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
    AtomicInteger saved = new AtomicInteger();
    TenantEnvironmentLifecycleService withRealPlan =
        new TenantEnvironmentLifecycleService(new TenantPlanService());
    withTransitionActivation(TRANSITION_ACTIVATION, () -> runAsNonAdmin(stored, saved,
        () -> assertEquals(EnvironmentAccessPolicy.Decision.ALLOWED,
            withRealPlan.evaluateAccess(CLIENT_ID, true, NOW))));
    assertEquals("a productive tenant gets no transition start", 0, saved.get());
  }

  /** The legacy-transition start is a write on the same no-context path; it needs admin mode too. */
  @Test
  public void aFreeTenantGetsItsTransitionStartWithoutPreferenceAccess() {
    AtomicInteger saved = new AtomicInteger();
    TenantEnvironmentLifecycleService withRealPlan =
        new TenantEnvironmentLifecycleService(new TenantPlanService());
    withTransitionActivation(TRANSITION_ACTIVATION, () -> runAsNonAdmin(new HashMap<>(), saved,
        () -> assertEquals(EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED,
            withRealPlan.evaluateAccess(CLIENT_ID, true, NOW))));
    assertEquals("the transition start is persisted once", 1, saved.get());
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
    runAsNonAdmin(stored, new AtomicInteger(), body);
  }

  /**
   * Runs {@code body} with a DAL that rejects preference queries and saves unless admin mode is
   * active, the way {@code OBDal} does for a role without read access on {@code AD_Preference}
   * and, with no OBContext at all, for every caller. {@code saved} counts the accepted saves.
   */
  private static void runAsNonAdmin(Map<String, String> stored, AtomicInteger saved,
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
      return saved.incrementAndGet();
    }).when(dalInstance).save(any());
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
