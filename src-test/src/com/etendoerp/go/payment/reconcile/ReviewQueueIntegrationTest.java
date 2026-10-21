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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Real-database specs for {@link ReviewQueue}: parking a purchase for review, and the W1 release
 * procedure of design §8 — an operator sets the review row to {@code IGNORED} and the purchase is
 * a candidate of the reconciliation's batches again, with its attempt budget counted from where it
 * was parked.
 *
 * <p>The mocked {@code BillingReconciliationServiceTest} only sees the {@link ReviewQueue#NOT_PARKED}
 * HQL as text. Whether it really leaves a parked request out, and lets a released one back in, is
 * a property of the query against committed rows, so it is run here against the real tables.
 *
 * <p><b>Cleanup is explicit:</b> every store call commits. Requests and accounts carry the
 * {@code etp5045-rvq-} marker, and their review rows ({@code reconcile:needs-review:<request>}) are
 * deleted by that marker in {@link #cleanUp()}. The audit is built without an internal-alert
 * channel, so no alert leaves the test. The fixtures are fresh {@code CREATING} requests, which the
 * reconciliation only reaches after 48 hours.
 *
 * @covers com.etendoerp.go.payment.reconcile.ReviewQueue
 */
public class ReviewQueueIntegrationTest extends OBBaseTest {

  private static final String MARKER = "etp5045-rvq-";
  private static final String ZERO = "0";

  private final BillingEventStore store = new BillingEventStore();
  private final CheckoutRequestStore requestStore = new CheckoutRequestStore();
  private final ReviewQueue reviews = new ReviewQueue(store);

  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  @Test
  public void aParkedPurchaseLeavesTheBatchAndAReleasedOneComesBack() {
    String requestId = createRequest();
    assertTrue("Precondition: a fresh purchase is a candidate", isCandidate(requestId));

    park(requestId, 5L);

    assertFalse("A parked purchase is out of every batch", isCandidate(requestId));
    assertEquals(BillingEventStore.RESULT_FAILED, reviewResult(reviewId(requestId)));

    store.markIgnored(reviewId(requestId), "Released by the operator after fixing the cause");

    assertTrue("Setting the review row to IGNORED makes the purchase eligible again",
        isCandidate(requestId));
    assertEquals("The released purchase counts its budget from where it was parked", 5L,
        reviews.attemptsBaseline(requestId));
  }

  @Test
  public void aPurchaseParkedAgainAfterItsReleaseGetsANewRow() {
    String requestId = createRequest();
    park(requestId, 5L);
    store.markIgnored(reviewId(requestId), "Released");

    park(requestId, 10L);

    assertFalse(isCandidate(requestId));
    assertEquals(BillingEventStore.RESULT_FAILED, reviewResult(reviewId(requestId) + ":2"));
    assertEquals("The first row stays as history", BillingEventStore.RESULT_IGNORED,
        reviewResult(reviewId(requestId)));
    assertEquals("The baseline is the released row's", 5L, reviews.attemptsBaseline(requestId));
  }

  @Test
  public void parkingAnAlreadyParkedPurchaseWritesNothing() {
    String requestId = createRequest();
    park(requestId, 5L);
    int awaiting = reviews.countAwaiting();

    ReconciliationResult again = park(requestId, 6L);

    assertEquals(1, store.countReconciliations(ReviewQueue.EVENT_TYPE, requestId));
    assertEquals(0, again.get(ReconciliationResult.Counter.NEEDS_REVIEW));
    assertEquals(awaiting, reviews.countAwaiting());
  }

  @Test
  public void aPurchaseNeverParkedHasNoBaseline() {
    assertEquals(0L, reviews.attemptsBaseline(createRequest()));
  }

  // ── fixtures ──────────────────────────────────────────────────────────────────────────────

  private ReconciliationResult park(String requestId, long attempts) {
    ReconciliationResult result = new ReconciliationResult(false);
    reviews.park(requestId, "ATTEMPTS_EXHAUSTED", attempts + " attempts did not complete",
        attempts, result, new ReconciliationAudit(store, false));
    return result;
  }

  private static String reviewId(String requestId) {
    return ReconciliationAudit.eventId(ReviewQueue.KIND, requestId);
  }

  /** Runs {@link ReviewQueue#NOT_PARKED} for one request, as the candidate queries do. */
  private boolean isCandidate(String requestId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Map<String, Object> parameters = new HashMap<>();
      ReviewQueue.bind(parameters);
      OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as cr where cr.request = :requestId and " + ReviewQueue.NOT_PARKED);
      query.setNamedParameter("requestId", requestId);
      parameters.forEach(query::setNamedParameter);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      return !query.list().isEmpty();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("rawtypes")
  private String reviewResult(String eventId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(
          "SELECT EVENT_RESULT FROM ETGO_BILLING_EVENT WHERE EVENT_ID = :eventId");
      query.setParameter("eventId", eventId);
      List<?> rows = query.list();
      return rows.isEmpty() ? null : (String) rows.get(0);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

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
      account.setName("ETP-5048 review queue fixture");
      account.setStatus("active");
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      accountId = account.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
    String requestId = MARKER + UUID.randomUUID().toString().replace("-", "");
    requestStore.recordRequested(requestId, accountId, email, "ETP-5048 Review Queue",
        new CheckoutRequestStore.RequestOptions(null, false, false, false, null));
    return requestId;
  }

  /** Review rows first (they carry the FK to the request), then requests, then accounts. */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      for (String[] statement : new String[][] {
          { "DELETE FROM ETGO_BILLING_EVENT WHERE REQUEST_ID LIKE :marker", MARKER + "%" },
          { "DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker", MARKER + "%" },
          { "DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE :marker", MARKER + "%" } }) {
        NativeQuery delete = OBDal.getInstance().getSession().createNativeQuery(statement[0]);
        delete.setParameter("marker", statement[1]);
        delete.executeUpdate();
      }
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
