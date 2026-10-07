/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

/**
 * ETP-5046-TRANSITIONAL-FALLBACK — the retired {@code ETGO_TenantPlan} marker is read while
 * serving a request as the calling user, and a role that cannot read {@code AD_Preference} (any
 * non-admin role) must still see a paying, not-yet-backfilled tenant as productive.
 *
 * <p>Both methods swallow a failure into "not productive" by contract, so a read that escaped
 * admin mode would not throw — it would silently downgrade a paying tenant to free. The DAL here
 * refuses the query outside admin mode, the way {@code OBDal} does for such a role (same technique
 * as {@link TenantEnvironmentLifecycleServiceAdminModeTest}, ETP-5488).
 */
public class TenantPlanPreferenceFallbackAdminModeTest {

  private static final String CLIENT_ID = "48F0981053084BC49CCEEFEC296E2A3D";

  private final TenantPlanPreferenceFallback fallback = new TenantPlanPreferenceFallback();

  @Test
  public void aNonAdminCallerStillSeesTheProductiveMarker() {
    assertTrue(runAsNonAdmin(() -> fallback.isProductive(CLIENT_ID)));
  }

  @Test
  public void aNonAdminCallerStillSeesTheProductiveMarkerInBulk() {
    assertEquals(Set.of(CLIENT_ID),
        runAsNonAdmin(() -> fallback.productiveAmong(List.of(CLIENT_ID, "OTHER"))));
  }

  @Test
  public void withoutAdminModeTheSameReadWouldHaveDegradedToFree() {
    // Proves the DAL double really refuses: bypass the service's own admin mode and the read that
    // would otherwise succeed comes back "not productive".
    AtomicInteger depth = new AtomicInteger();
    OBDal dal = productiveMarkerDal(depth);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      // setAdminMode is a no-op here, so depth stays 0.
      assertFalse(fallback.isProductive(CLIENT_ID));
      assertTrue(fallback.productiveAmong(List.of(CLIENT_ID)).isEmpty());
    }
  }

  private static <T> T runAsNonAdmin(Supplier<T> body) {
    AtomicInteger depth = new AtomicInteger();
    OBDal dal = productiveMarkerDal(depth);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      context.when(OBContext::setAdminMode).thenAnswer(invocation -> depth.incrementAndGet());
      context.when(() -> OBContext.setAdminMode(anyBoolean()))
          .thenAnswer(invocation -> depth.incrementAndGet());
      context.when(OBContext::restorePreviousMode)
          .thenAnswer(invocation -> depth.decrementAndGet());
      T result = body.get();
      assertEquals("admin mode must be balanced", 0, depth.get());
      return result;
    }
  }

  /** A DAL holding one productive marker for {@link #CLIENT_ID}, readable in admin mode only. */
  @SuppressWarnings("unchecked")
  private static OBDal productiveMarkerDal(AtomicInteger depth) {
    Client tenant = mock(Client.class);
    when(tenant.getId()).thenReturn(CLIENT_ID);
    Preference marker = mock(Preference.class);
    when(marker.getSearchKey()).thenReturn(TenantPlanService.PLAN_PRODUCTIVE);
    when(marker.getVisibleAtClient()).thenReturn(tenant);
    OBQuery<Preference> query = mock(OBQuery.class);
    when(query.uniqueResult()).thenReturn(marker);
    when(query.list()).thenReturn(List.of(marker));
    OBDal dal = mock(OBDal.class);
    when(dal.createQuery(eq(Preference.class), anyString())).thenAnswer(invocation -> {
      if (depth.get() == 0) {
        throw new OBSecurityException("Entity ADPreference is not readable by the user U1");
      }
      return query;
    });
    return dal;
  }
}
