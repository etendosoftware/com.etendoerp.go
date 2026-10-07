/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for the stored subscription projection the lifecycle applier decides against.
 *
 * <p>The DAL is mocked: the integration harness currently fails to boot locally
 * ({@code MappingNotFoundException} on {@code Table.hbm.xml}), so a real round trip through
 * {@code AD_PREFERENCE} is not added here.
 */
public class TenantEnvironmentLifecycleServiceSubscriptionStateTest {

  private static final String CLIENT_ID = "client-1";
  private static final Instant DUE_AT = Instant.parse("2026-10-31T00:00:00Z");
  private static final Instant EVENT_AT = Instant.parse("2026-11-01T12:30:00Z");

  /**
   * A tenant with no open subscription row, so these specs exercise the preference projection.
   * A mocked {@code findOpen} answers {@code Optional.empty()} by default; the row route has its
   * own specs.
   */
  private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
  private final TenantEnvironmentLifecycleService service =
      new TenantEnvironmentLifecycleService(mock(TenantPlanService.class), subscriptionService);

  @Test
  public void blankClientReadsAsTheEmptyProjectionWithoutTouchingTheDal() {
    // readPreference enters admin mode, which needs no real session in a unit test.
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      assertSame(SubscriptionLifecycleApplier.StoredState.NONE,
          service.readSubscriptionState(null));
      assertSame(SubscriptionLifecycleApplier.StoredState.NONE,
          service.readSubscriptionState("  "));
      dal.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void storedPreferencesAreReadBackAsTheProjection() {
    Map<String, String> stored = new HashMap<>();
    stored.put(TenantEnvironmentLifecycleService.SUBSCRIPTION_STATUS_ATTRIBUTE, "PAST_DUE");
    stored.put(TenantEnvironmentLifecycleService.SUBSCRIPTION_DUE_AT_ATTRIBUTE, DUE_AT.toString());
    stored.put(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE,
        EVENT_AT.toString());
    OBDal dalInstance = preferencesDal(stored);

    // readPreference enters admin mode, which needs no real session in a unit test.
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      SubscriptionLifecycleApplier.StoredState state = service.readSubscriptionState(CLIENT_ID);

      assertEquals(EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE, state.status());
      assertEquals(DUE_AT, state.dueAt());
      assertEquals(EVENT_AT, state.lastEventAt());
    }
  }

  /** A malformed instant must degrade to "unknown", never make every later event stale. */
  @Test
  public void missingOrMalformedPreferencesReadAsNull() {
    Map<String, String> stored = new HashMap<>();
    stored.put(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE, "not-an-instant");
    OBDal dalInstance = preferencesDal(stored);

    // readPreference enters admin mode, which needs no real session in a unit test.
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      SubscriptionLifecycleApplier.StoredState state = service.readSubscriptionState(CLIENT_ID);

      assertNull(state.status());
      assertNull(state.dueAt());
      assertNull(state.lastEventAt());
    }
  }

  @Test
  public void recordingWithoutAnInstantOrClientWritesNothing() {
    // readPreference enters admin mode, which needs no real session in a unit test.
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      service.recordSubscriptionEventAt(CLIENT_ID, null);
      service.recordSubscriptionEventAt(null, EVENT_AT);
      service.recordSubscriptionEventAt(" ", EVENT_AT);
      dal.verify(OBDal::getInstance, never());
    }
  }

  @Test(expected = IllegalStateException.class)
  public void recordingForAMissingClientFails() {
    OBDal dalInstance = mock(OBDal.class);
    when(dalInstance.get(Client.class, CLIENT_ID)).thenReturn(null);
    // readPreference enters admin mode, which needs no real session in a unit test.
    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      service.recordSubscriptionEventAt(CLIENT_ID, EVENT_AT);
    }
  }

  /** The write is left for the caller to commit together with the status write. */
  @Test
  public void recordingStoresTheInstantWithoutCommitting() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    OBDal dalInstance = preferencesDal(new HashMap<>());
    when(dalInstance.get(Client.class, CLIENT_ID)).thenReturn(client);
    Preference created = mock(Preference.class);
    OBProvider provider = mock(OBProvider.class);
    when(provider.get(Preference.class)).thenReturn(created);

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
         MockedStatic<OBProvider> providerStatic = mockStatic(OBProvider.class);
         MockedStatic<org.openbravo.dal.core.OBContext> context =
             mockStatic(org.openbravo.dal.core.OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      providerStatic.when(OBProvider::getInstance).thenReturn(provider);

      service.recordSubscriptionEventAt(CLIENT_ID, EVENT_AT);

      // The Stripe webhook has no user context of its own: the write runs in admin mode and
      // leaves it again, rather than depending on a context an earlier call leaked.
      context.verify(org.openbravo.dal.core.OBContext::setAdminMode);
      context.verify(org.openbravo.dal.core.OBContext::restorePreviousMode);
    }

    verify(created).setAttribute(TenantEnvironmentLifecycleService.SUBSCRIPTION_EVENT_AT_ATTRIBUTE);
    verify(created).setClient(client);
    verify(created).setSearchKey(EVENT_AT.toString());
    verify(dalInstance).save(created);
    verify(dalInstance, never()).commitAndClose();
    verify(dalInstance, never()).flush();
  }

  @Test
  public void demoAssociationUsesCrossClientAdminModeAndRestoresItAfterBothWrites() {
    AtomicBoolean adminMode = new AtomicBoolean(false);
    AtomicBoolean writesWereAdmin = new AtomicBoolean(true);
    OBDal dalInstance = associationDal(adminMode, writesWereAdmin, false);

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> provider = mockStatic(OBProvider.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      OBProvider providerInstance = mock(OBProvider.class);
      when(providerInstance.get(Preference.class)).thenAnswer(invocation -> mock(Preference.class));
      provider.when(OBProvider::getInstance).thenReturn(providerInstance);
      context.when(org.openbravo.dal.core.OBContext::setAdminMode)
          .thenAnswer(invocation -> { adminMode.set(true); return null; });
      context.when(org.openbravo.dal.core.OBContext::restorePreviousMode)
          .thenAnswer(invocation -> { adminMode.set(false); return null; });

      assertTrue(service.associateDemoWithProductive("demo-client", "paid-client"));

      assertTrue("Both ADPreference saves span different clients and require admin mode",
          writesWereAdmin.get());
      assertFalse("The prior non-admin mode is restored after successful association",
          adminMode.get());
      context.verify(org.openbravo.dal.core.OBContext::setAdminMode);
      context.verify(() -> org.openbravo.dal.core.OBContext.setAdminMode(true), never());
      context.verify(org.openbravo.dal.core.OBContext::restorePreviousMode);
      org.mockito.Mockito.verify(dalInstance, times(2)).save(any(Preference.class));
    }
  }

  @Test
  public void demoAssociationRestoresPriorModeWhenSecondPreferenceSaveFails() {
    AtomicBoolean adminMode = new AtomicBoolean(true);
    AtomicBoolean previousAdminMode = new AtomicBoolean(true);
    AtomicBoolean writesWereAdmin = new AtomicBoolean(true);
    OBDal dalInstance = associationDal(adminMode, writesWereAdmin, true);

    try (MockedStatic<OBDal> dal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> provider = mockStatic(OBProvider.class);
        MockedStatic<org.openbravo.dal.core.OBContext> context =
            mockStatic(org.openbravo.dal.core.OBContext.class)) {
      dal.when(OBDal::getInstance).thenReturn(dalInstance);
      OBProvider providerInstance = mock(OBProvider.class);
      when(providerInstance.get(Preference.class)).thenAnswer(invocation -> mock(Preference.class));
      provider.when(OBProvider::getInstance).thenReturn(providerInstance);
      context.when(org.openbravo.dal.core.OBContext::setAdminMode)
          .thenAnswer(invocation -> {
            previousAdminMode.set(adminMode.get());
            adminMode.set(true);
            return null;
          });
      context.when(org.openbravo.dal.core.OBContext::restorePreviousMode)
          .thenAnswer(invocation -> { adminMode.set(previousAdminMode.get()); return null; });

      assertFalse("A failed preference write is reported without leaking admin mode",
          service.associateDemoWithProductive("demo-client", "paid-client"));

      assertTrue("Writes are attempted only after entering cross-client admin mode",
          writesWereAdmin.get());
      assertTrue("The prior admin mode is restored after the write exception", adminMode.get());
      context.verify(org.openbravo.dal.core.OBContext::setAdminMode);
      context.verify(() -> org.openbravo.dal.core.OBContext.setAdminMode(true), never());
      context.verify(org.openbravo.dal.core.OBContext::restorePreviousMode);
      org.mockito.Mockito.verify(dalInstance, times(2)).save(any(Preference.class));
    }
  }

  /** Builds the DAL used by association and records the context active at each preference save. */
  private static OBDal associationDal(AtomicBoolean adminMode, AtomicBoolean writesWereAdmin,
      boolean failOnSecondWrite) {
    OBDal dalInstance = mock(OBDal.class);
    Client demo = mock(Client.class);
    Client productive = mock(Client.class);
    when(demo.getId()).thenReturn("demo-client");
    when(productive.getId()).thenReturn("paid-client");
    when(dalInstance.get(Client.class, "demo-client")).thenReturn(demo);
    when(dalInstance.get(Client.class, "paid-client")).thenReturn(productive);
    OBQuery<Preference> query = mock(OBQuery.class);
    when(dalInstance.createQuery(eq(Preference.class), anyString())).thenReturn(query);
    doAnswer(invocation -> query).when(query).setNamedParameter(anyString(), any());
    doAnswer(invocation -> query).when(query).setFilterOnReadableClients(false);
    doAnswer(invocation -> query).when(query).setFilterOnReadableOrganization(false);
    doAnswer(invocation -> query).when(query).setMaxResult(1);
    when(query.uniqueResult()).thenReturn(null);
    AtomicInteger writes = new AtomicInteger();
    doAnswer(invocation -> {
      writesWereAdmin.compareAndSet(true, adminMode.get());
      if (failOnSecondWrite && writes.incrementAndGet() == 2) {
        throw new IllegalStateException("simulated preference write failure");
      }
      return null;
    }).when(dalInstance).save(any(Preference.class));
    return dalInstance;
  }

  /**
   * An {@link OBDal} whose preference queries answer from {@code stored}, keyed by the attribute
   * the query was bound to.
   */
  @SuppressWarnings("unchecked")
  private static OBDal preferencesDal(Map<String, String> stored) {
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
    when(dalInstance.createQuery(eq(Preference.class), anyString())).thenReturn(query);
    return dalInstance;
  }
}
