/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;

/**
 * The lifecycle preferences are system state read on every NEO request, as the calling user. A
 * role without read access on {@code AD_Preference} (any non-admin role) must still get an access
 * decision: production answered 401 to every NEO request of such users, because the DAL threw
 * "Entity ADPreference is not readable" and nothing caught it.
 */
public class TenantEnvironmentLifecycleServiceAdminModeTest {

  private static final String CLIENT_ID = "client-1";
  private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

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

  /**
   * Runs {@code body} with a DAL that rejects preference queries unless admin mode is active, the
   * way {@code OBDal} does for a role without read access on {@code AD_Preference}.
   */
  private static void runAsNonAdmin(Map<String, String> stored, Runnable body) {
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

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
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
