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
package com.etendoerp.go.payment.reconcile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.CheckoutRequestWrites;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;
import com.etendoerp.go.payment.LifecycleTargetFixtures;
import com.etendoerp.go.payment.PausedSubscriptionAlert;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier.StoredState;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.rest.PaidProvisioningService;
import com.etendoerp.go.rest.PaidReactivationService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

/**
 * Real-database specs for the candidate queries of the billing reconciliation, the HQL its mocked
 * specs only see as text:
 *
 * <ul>
 *   <li>{@link BillingReconciliationService}, phases A (checkout recovery) and B (provisioning
 *       repair): a request with a fresh {@code reconcile:retry-later} marker is left out of the
 *       batch, and is back once the marker is older than the back-off. Phase C (no session id): a
 *       request parked for review is left out, and is back once released.</li>
 *   <li>{@link SubscriptionReconciler}: of two provisioned purchases of one tenant only the one with
 *       the later {@code coalesce(paidAt, created)} is a preference candidate, and a tenant with an
 *       active subscription row is not one at all (it is a row candidate instead).</li>
 * </ul>
 *
 * <p><b>Nothing is written by the code under test.</b> Every run is a dry run, the provider is a
 * mock (any unstubbed read answers 500), and the lifecycle service of the subscription phase is a
 * mock. What a phase selected is read from what it then did: the session it asked the provider
 * for, the dry-run line it logged, the subscription it reconciled.
 *
 * <p><b>Fixtures and thresholds.</b> Checkout rows are created now and never backdated. The run
 * sees them as old through its clock, set 20 minutes (phases A, B) or 49 hours (phase C) ahead, so
 * a live run never finds them past its grace. Only the back-off marker row's own timestamp is
 * moved, and a marker row is never a candidate of anything. Every row carries the
 * {@code etp5048-cnd-} marker (tenants {@code ETP5048CND-}), uses fake provider ids, and is deleted
 * in {@link #cleanUp()}.
 *
 * @covers com.etendoerp.go.payment.reconcile.BillingReconciliationService
 * @covers com.etendoerp.go.payment.reconcile.SubscriptionReconciler
 */
public class ReconciliationCandidateQueriesIntegrationTest extends OBBaseTest {

  private static final String MARKER = "etp5048-cnd-";
  private static final String TENANT_MARKER = "ETP5048CND-";
  private static final String ZERO = "0";
  private static final String LEGACY_PLAN_ID = "219D5C8E15C64E97B2F553B228D30DD0";
  private static final String LIST_PATH =
      "/v1/subscriptions?status=all&expand%5B%5D=data.latest_invoice&limit=100";
  private static final StoredState CURRENT = new StoredState(SubscriptionStatus.CURRENT, null,
      null);

  private final CheckoutRequestStore requestStore = new CheckoutRequestStore();
  private final BillingEventStore eventStore = new BillingEventStore();
  private StripeApiClient stripe;
  private TestLogCapture auditLog;

  @Before
  public void setUp() {
    stripe = mock(StripeApiClient.class, call -> new StripeResponse(500, ""));
    auditLog = TestLogCapture.attachTo(ReconciliationAudit.class, Level.INFO);
  }

  @After
  public void cleanUp() {
    try {
      auditLog.detach();
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ── Phases A and B: the back-off marker ──────────────────────────────────────────────────

  @Test
  public void aCheckoutBackingOffIsLeftOutOfRecoveryUntilItsMarkerIsOlderThanTheBackOff()
      throws Exception {
    String requestId = createRequest();
    String session = "cs_test_" + requestId;
    requestStore.recordSessionCreated(requestId, session);
    String sessionPath = "/v1/checkout/sessions/" + session;
    doReturn(new StripeResponse(200, "{\"id\":\"" + session + "\",\"status\":\"open\","
        + "\"payment_status\":\"unpaid\",\"metadata\":{\"request_id\":\"" + requestId + "\"}}"))
        .when(stripe).get(sessionPath);
    String marker = touchRetryLater(requestId);

    runService(Duration.ofMinutes(20));
    verify(stripe, never()).get(sessionPath);

    moveMarkerBack(marker, Duration.ofHours(7));
    runService(Duration.ofMinutes(20));
    verify(stripe, times(1)).get(sessionPath);

    // A failure since then refreshes the marker: the back-off counts from the refresh.
    touchRetryLater(requestId);
    runService(Duration.ofMinutes(20));
    verify(stripe, times(1)).get(sessionPath);
  }

  @Test
  public void aPaidPurchaseBackingOffIsLeftOutOfRepairUntilItsMarkerIsOlderThanTheBackOff() {
    String requestId = createRequest();
    requestStore.recordSessionCreated(requestId, "cs_test_" + requestId);
    requestStore.recordPaid(requestId, "cus_" + requestId, null);
    String marker = touchRetryLater(requestId);

    runService(Duration.ofMinutes(20));
    assertFalse(dryRunLines().toString(), wouldProvision(requestId));

    moveMarkerBack(marker, Duration.ofHours(7));
    runService(Duration.ofMinutes(20));
    assertTrue(dryRunLines().toString(), wouldProvision(requestId));
  }

  // ── Phase C: the review queue ────────────────────────────────────────────────────────────

  @Test
  public void aSessionlessCheckoutParkedForReviewIsLeftOutUntilReleased() {
    String requestId = createRequest();
    String review = ReconciliationAudit.eventId(ReviewQueue.KIND, requestId);
    assertTrue(eventStore.recordReconciliation(review, ReviewQueue.EVENT_TYPE, requestId,
        BillingEventStore.RESULT_FAILED, "parked by the test", null));

    runService(Duration.ofHours(49));
    assertFalse(auditLog.messagesAt(Level.WARN).toString(), reviewedAgain(requestId));

    eventStore.markIgnored(review, "Released by the test");
    runService(Duration.ofHours(49));
    assertTrue(auditLog.messagesAt(Level.WARN).toString(), reviewedAgain(requestId));
  }

  // ── Phase D: the subscription candidates ─────────────────────────────────────────────────

  /**
   * Two provisioned purchases of one rowless tenant: the one created first was paid later, so by
   * {@code coalesce(paidAt, created)} it is the latest and the only one read.
   */
  @Test
  public void onlyTheLatestPurchaseOfATenantIsAPreferenceCandidate() throws Exception {
    String tenantId = createTenant("latest");
    String earlierCreated = "sub_" + createRequest();
    String laterCreated = "sub_" + createRequest();
    provision(requestOf(earlierCreated), tenantId, earlierCreated, Duration.ofHours(1));
    provision(requestOf(laterCreated), tenantId, laterCreated, null);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.targetForTenant(eq(tenantId), anyString()))
        .thenReturn(LifecycleTargetFixtures.preferences(tenantId, CURRENT));
    listSubscriptions(earlierCreated, laterCreated);

    ReconciliationResult result = runSubscriptions(lifecycle);

    assertEquals("active", result.rereadSubscriptionStatus(earlierCreated));
    assertNull(result.rereadSubscriptionStatus(laterCreated));
    verify(lifecycle, never()).targetForTenant(tenantId, laterCreated);
  }

  @Test
  public void withNoPaymentTheLaterCreatedPurchaseIsTheCandidate() throws Exception {
    String tenantId = createTenant("created");
    String first = "sub_" + createRequest();
    String second = "sub_" + createRequest();
    provision(requestOf(first), tenantId, first, null);
    provision(requestOf(second), tenantId, second, null);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.targetForTenant(eq(tenantId), anyString()))
        .thenReturn(LifecycleTargetFixtures.preferences(tenantId, CURRENT));
    listSubscriptions(first, second);

    ReconciliationResult result = runSubscriptions(lifecycle);

    assertEquals("active", result.rereadSubscriptionStatus(second));
    assertNull(result.rereadSubscriptionStatus(first));
  }

  /** A tenant with an active row is read on the row route, never on the preference route. */
  @Test
  public void aTenantWithAnActiveSubscriptionRowIsNotAPreferenceCandidate() throws Exception {
    String tenantId = createTenant("row");
    String purchaseSub = "sub_" + createRequest();
    provision(requestOf(purchaseSub), tenantId, purchaseSub, null);
    String rowSub = "sub_" + MARKER + "row-" + UUID.randomUUID().toString().replace("-", "");
    createActiveSubscriptionRow(tenantId, rowSub);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.targetForTenant(eq(tenantId), anyString()))
        .thenReturn(LifecycleTargetFixtures.preferences(tenantId, CURRENT));
    when(lifecycle.targetForSubscription(rowSub)).thenReturn(LifecycleTargetFixtures.row(tenantId,
        mock(com.etendoerp.go.schemaforge.data.Subscription.class), CURRENT));
    listSubscriptions(purchaseSub, rowSub);

    ReconciliationResult result = runSubscriptions(lifecycle);

    verify(lifecycle, never()).targetForTenant(eq(tenantId), anyString());
    verify(lifecycle).targetForSubscription(rowSub);
    assertNull(result.rereadSubscriptionStatus(purchaseSub));
    assertEquals("The row is a row candidate", "active",
        result.rereadSubscriptionStatus(rowSub));
  }

  // ── runs ─────────────────────────────────────────────────────────────────────────────────

  /** One dry run of the whole service, its clock {@code ahead} of now; phase D is a mock. */
  private void runService(Duration ahead) {
    BillingReconciliationService service = new BillingReconciliationService(
        ReconciliationSettings.defaults(), stripe, requestStore, eventStore,
        mock(PaidProvisioningService.class), mock(SubscriptionReconciler.class),
        Clock.fixed(Instant.now().plus(ahead), ZoneOffset.UTC));
    service.checkoutWrites = mock(CheckoutRequestWrites.class);
    service.reactivations = mock(PaidReactivationService.class);
    service.internalAlerts = mock(InternalAlertService.class);
    service.run(true);
    OBDal.getInstance().rollbackAndClose();
  }

  /** One dry run of phase D over the real candidate queries. */
  private ReconciliationResult runSubscriptions(TenantEnvironmentLifecycleService lifecycle) {
    ReconciliationResult result = new ReconciliationResult(true);
    new SubscriptionReconciler(ReconciliationSettings.defaults(), stripe, requestStore,
        new SubscriptionLifecycleApplier(), lifecycle, mock(PausedSubscriptionAlert.class),
        Clock.systemUTC()).run(result, new ReconciliationAudit(eventStore, true));
    OBDal.getInstance().rollbackAndClose();
    return result;
  }

  private void listSubscriptions(String... ids) throws Exception {
    StringBuilder data = new StringBuilder();
    for (String id : ids) {
      data.append(data.length() == 0 ? "" : ",").append("{\"id\":\"").append(id)
          .append("\",\"status\":\"active\",\"metadata\":{\"request_id\":\"")
          .append(requestOf(id)).append("\"}}");
    }
    doReturn(new StripeResponse(200, "{\"has_more\":false,\"data\":[" + data + "]}"))
        .when(stripe).get(LIST_PATH);
  }

  private List<String> dryRunLines() {
    return auditLog.messagesAt(Level.INFO);
  }

  private boolean wouldProvision(String requestId) {
    return dryRunLines().stream().anyMatch(line -> line.contains(
        "[dry run] would: provision paid purchase " + requestId + " "));
  }

  private boolean reviewedAgain(String requestId) {
    return auditLog.messagesAt(Level.WARN).stream().anyMatch(line -> line.contains(
        "Purchase " + requestId + " needs manual review (NO_SESSION_ID)"));
  }

  // ── fixtures ─────────────────────────────────────────────────────────────────────────────

  private static String requestOf(String subscriptionId) {
    return subscriptionId.substring("sub_".length());
  }

  private String touchRetryLater(String requestId) {
    String marker = ReconciliationAudit.eventId(BillingReconciliationService.KIND_RETRY_LATER,
        requestId);
    eventStore.touchReconciliation(marker, ReconciliationAudit.EVENT_TYPE_PREFIX
        + BillingReconciliationService.KIND_RETRY_LATER, requestId, "set by the test");
    return marker;
  }

  /** Moves only the marker row back; its refresh timestamp, if any, is cleared with it. */
  private void moveMarkerBack(String markerId, Duration by) {
    nativeUpdate("UPDATE ETGO_BILLING_EVENT SET RECEIVED_AT = now() - cast(:by as interval), "
        + "LAST_DUPLICATE_AT = NULL WHERE EVENT_ID = :id", "by", by.toMinutes() + " minutes",
        "id", markerId);
  }

  /** A checkout request in {@code CREATING}, created now, of a fresh marker account. */
  private String createRequest() {
    String email = MARKER + UUID.randomUUID().toString().replace("-", "") + "@example.test";
    String accountId;
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Account account = OBProvider.getInstance().get(Account.class);
      account.setNewOBObject(true);
      account.setClient(OBDal.getInstance().get(Client.class, ZERO));
      account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
      account.setActive(true);
      account.setEmail(email);
      account.setName("ETP-5048 candidate query fixture");
      account.setStatus("active");
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      accountId = account.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
    String requestId = MARKER + UUID.randomUUID().toString().replace("-", "");
    requestStore.recordRequested(requestId, accountId, email, "ETP-5048 Candidates",
        new CheckoutRequestStore.RequestOptions(null, false, false, false, null));
    return requestId;
  }

  /**
   * Marks a request provisioned into {@code tenantId} with a fake subscription id. A
   * {@code paidAhead} payment instant in the future orders it after a later-created purchase
   * without backdating anything.
   */
  private void provision(String requestId, String tenantId, String subscriptionId,
      Duration paidAhead) {
    nativeUpdate("UPDATE ETGO_CHECKOUT_REQUEST SET CHECKOUT_STATUS = 'PROVISIONED', "
        + "CREATED_CLIENT_ID = :tenant, STRIPE_SUBSCRIPTION_ID = :sub, PAID_AT = "
        + (paidAhead == null ? "NULL" : "now() + cast(:ahead as interval)")
        + " WHERE REQUEST_ID = :id", paidAhead == null
            ? new Object[] { "tenant", tenantId, "sub", subscriptionId, "id", requestId }
            : new Object[] { "tenant", tenantId, "sub", subscriptionId, "id", requestId,
                "ahead", paidAhead.toMinutes() + " minutes" });
  }

  private String createTenant(String label) {
    String id = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    nativeUpdate("INSERT INTO AD_CLIENT (AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, CREATEDBY, "
            + "UPDATED, UPDATEDBY, VALUE, NAME) VALUES (:id, '0', 'Y', now(), '0', now(), '0', "
            + ":value, :name)",
        "id", id, "value", TENANT_MARKER + label, "name", TENANT_MARKER + label + " " + id);
    return id;
  }

  private void createActiveSubscriptionRow(String tenantId, String subscriptionId) {
    nativeUpdate("INSERT INTO ETGO_SUBSCRIPTION (ETGO_SUBSCRIPTION_ID, AD_CLIENT_ID, AD_ORG_ID, "
            + "ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, ENVIRONMENT_CLIENT_ID, "
            + "ETGO_PLAN_ID, STATUS, START_DATE, STRIPE_SUBSCRIPTION_ID) VALUES (:id, '0', '0', "
            + "'Y', now(), '0', now(), '0', :tenant, :plan, 'active', now(), :sub)",
        "id", UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
        "tenant", tenantId, "plan", LEGACY_PLAN_ID, "sub", subscriptionId);
  }

  @SuppressWarnings("rawtypes")
  private static void nativeUpdate(String sql, Object... nameValuePairs) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
      for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
        query.setParameter((String) nameValuePairs[i], nameValuePairs[i + 1]);
      }
      query.executeUpdate();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * By marker, in FK order: billing events (by request, or by an id naming a marker request or
   * subscription), checkout requests, the tenants' subscription rows, preferences and sequences,
   * the tenants, then the accounts.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    String tenants = "(SELECT AD_CLIENT_ID FROM AD_CLIENT WHERE VALUE LIKE :tenantMarker)";
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      for (String sql : new String[] {
          "DELETE FROM ETGO_BILLING_EVENT WHERE REQUEST_ID LIKE :marker OR EVENT_ID LIKE "
              + ":eventMarker",
          "DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker",
          "DELETE FROM ETGO_SUBSCRIPTION WHERE ENVIRONMENT_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_PREFERENCE WHERE AD_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_PREFERENCE WHERE VISIBLEAT_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_SEQUENCE WHERE AD_CLIENT_ID IN " + tenants,
          "DELETE FROM AD_CLIENT WHERE VALUE LIKE :tenantMarker",
          "DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE :marker" }) {
        NativeQuery delete = OBDal.getInstance().getSession().createNativeQuery(sql);
        if (sql.contains(":marker")) {
          delete.setParameter("marker", MARKER + "%");
        }
        if (sql.contains(":eventMarker")) {
          delete.setParameter("eventMarker", "reconcile:%" + MARKER + "%");
        }
        if (sql.contains(":tenantMarker")) {
          delete.setParameter("tenantMarker", TENANT_MARKER + "%");
        }
        delete.executeUpdate();
      }
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
