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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import javax.persistence.PersistenceException;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-5045 — real-database specs for {@link BillingEventStore}, the durable webhook idempotency
 * gate over {@code ETGO_BILLING_EVENT}.
 *
 * <p>Everything about the claim is a property of what is <em>committed</em>, so a mock could not
 * observe any of it: the claim is the unique constraint {@code ETGO_BILLEVT_EVENT_UQ}, the
 * duplicate counter and the FAILED re-claim are conditional bulk updates whose affected-row count
 * is the answer, and restart survival is precisely the absence of any in-process state.
 *
 * <p><b>Cleanup is explicit, not a rollback.</b> Every write path of the store ends in
 * {@code flush()} + {@code commitAndClose()}, so by the time a test returns its rows are durable.
 * Every event id carries the {@code evt_etp5045it_} marker and every checkout-request fixture the
 * {@code etp5045-bev-} marker; {@link #deleteCommittedFixtures()} removes them with native SQL in
 * FK order (events, then requests, then accounts).
 *
 * <p>Assertions on committed values go through native SQL rather than DAL getters: after a bulk
 * HQL update Hibernate's first-level cache still holds the pre-update entity, so a getter could
 * report what the test wrote rather than what the store committed.
 *
 * <p>{@link BillingEventStore#summarize} and {@link BillingEventStore#isUniqueEventViolation} are
 * pure functions and need no database, but they are the store's two guards (what may reach the
 * summary column, what counts as a duplicate) and belong next to the behaviour they protect.
 */
public class BillingEventStoreIntegrationTest extends OBBaseTest {

  /** Prefix on every fixture event id, so cleanup can be exact. */
  private static final String EVENT_MARKER = "evt_etp5045it_";
  /** Prefix on every fixture request id and account e-mail. */
  private static final String REQUEST_MARKER = "etp5045-bev-";
  /**
   * Prefix the synthetic-failure trigger fires on. Deliberately narrower than
   * {@link #EVENT_MARKER} so the trigger cannot affect any other spec, and impossible for a real
   * provider event id — but still inside the {@code evt_etp5045} family the cleanup deletes.
   */
  private static final String TRIGGER_MARKER = "evt_etp5045trg_";

  private static final String TRIGGER_NAME = "etp5045_fake_violation_trg";
  private static final String TRIGGER_FUNCTION = "etp5045_fake_violation";

  private static final String ZERO = "0";

  private static final String RECEIVED = "RECEIVED";
  private static final String APPLIED = "APPLIED";
  private static final String IGNORED = "IGNORED";
  private static final String FAILED = "FAILED";

  private static final String COMPLETED = "checkout.session.completed";
  private static final String ENVIRONMENT = "ETP-5045 Billing Event Environment";

  private final BillingEventStore store = new BillingEventStore();
  private final CheckoutRequestStore requestStore = new CheckoutRequestStore();

  /**
   * Same shape as {@code CheckoutRequestStoreIntegrationTest#cleanUp}: the store installs the
   * system context and never restores the caller's, so the thread context is cleared rather than
   * unwound (the admin-mode unwind loop cannot terminate under a System Administrator role).
   */
  @After
  public void cleanUp() {
    try {
      // Unconditional: the trigger only exists during one spec, but a spec that died between
      // creating and dropping it must not leave it behind on a shared database.
      dropFakeViolationTrigger();
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 1 — the first claim: one RECEIVED row carrying what was received
  // ---------------------------------------------------------------------------------------------

  /**
   * The baseline every other spec is measured against: the first delivery wins and the row records
   * the audit columns in the same write as the claim. {@code PROCESSED_AT} stays null because no
   * result has been decided yet; {@code DUPLICATE_COUNT} opens at 0 because nobody has knocked
   * twice.
   */
  @Test
  public void testTheFirstClaimInsertsAReceivedRowWithTheAuditColumns() {
    String eventId = newEventId();
    String requestId = REQUEST_MARKER + "never-issued-" + UUID.randomUUID();
    String summary = "{\"id\":\"cs_test_first_claim\"}";

    assertTrue("The first delivery of an event id must win the claim",
        store.claim(eventId, COMPLETED, requestId, summary));

    assertEquals(1L, rawCount(eventId));
    assertEquals(RECEIVED, rawResult(eventId));
    assertEquals(COMPLETED, rawColumn(eventId, "EVENT_TYPE"));
    assertEquals("stripe", rawColumn(eventId, "PROVIDER"));
    assertEquals(summary, rawColumn(eventId, "PAYLOAD_SUMMARY"));
    assertEquals("The raw correlation id is stored even when no request matches it", requestId,
        rawColumn(eventId, "REQUEST_ID"));
    assertNotNull("RECEIVED_AT must be stamped", rawTimestamp(eventId, "RECEIVED_AT"));
    assertNull("Nothing has been decided yet", rawTimestamp(eventId, "PROCESSED_AT"));
    assertNull(rawTimestamp(eventId, "LAST_DUPLICATE_AT"));
    assertNull(rawColumn(eventId, "FAILURE_REASON"));
    assertEquals(0L, rawDuplicateCount(eventId));
  }

  /**
   * The id-only form of the {@link CheckoutWebhookProcessor.EventStore} contract still has to
   * satisfy the NOT NULL {@code EVENT_TYPE} column, so it records the documented placeholder and
   * leaves every optional audit column empty.
   */
  @Test
  public void testTheIdOnlyClaimRecordsTheUnknownEventType() {
    String eventId = newEventId();

    assertTrue(store.claim(eventId));

    assertEquals(BillingEventStore.EVENT_TYPE_UNKNOWN, rawColumn(eventId, "EVENT_TYPE"));
    assertEquals("unknown", rawColumn(eventId, "EVENT_TYPE"));
    assertEquals(RECEIVED, rawResult(eventId));
    assertNull(rawColumn(eventId, "REQUEST_ID"));
    assertNull(rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertNull(rawColumn(eventId, "PAYLOAD_SUMMARY"));
  }

  /** A blank event type through the rich form is the same case as the id-only form. */
  @Test
  public void testABlankEventTypeIsRecordedAsUnknown() {
    String eventId = newEventId();

    assertTrue(store.claim(eventId, "   ", null, null));

    assertEquals("unknown", rawColumn(eventId, "EVENT_TYPE"));
  }

  /**
   * The row is the idempotency gate, so a caller reaching the store without an id is a bug that
   * must surface, not a delivery that quietly looks like a duplicate (or, worse, claims a blank
   * key that every other id-less event would then collide with).
   */
  @Test
  public void testABlankEventIdIsRefusedAndInsertsNothing() {
    long before = rawTotalCount();

    for (String blank : new String[] { null, "", "   ", "\t\n" }) {
      try {
        store.claim(blank, COMPLETED, null, null);
        fail("A blank event id must be refused: [" + blank + "]");
      } catch (IllegalArgumentException expected) {
        // the documented contract
      }
      try {
        store.claim(blank);
        fail("A blank event id must be refused by the id-only form too: [" + blank + "]");
      } catch (IllegalArgumentException expected) {
        // the documented contract
      }
    }

    assertEquals("A refused claim must not have inserted a row", before, rawTotalCount());
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — linking the event to the checkout request it references
  // ---------------------------------------------------------------------------------------------

  /**
   * When the correlation id names a request this instance issued, the link is resolved at claim
   * time: the FK is what the Classic window and any later reconciliation join on.
   */
  @Test
  public void testAClaimWithAKnownRequestIdIsLinkedToThatCheckoutRequest() {
    String email = newEmail("linked");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);
    String requestPk = rawRequestPk(requestId);
    assertNotNull("Sanity: the checkout request fixture must exist", requestPk);
    String eventId = newEventId();

    assertTrue(store.claim(eventId, COMPLETED, requestId, null));

    assertEquals("The event must be linked to the request it references", requestPk,
        rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertEquals("The raw correlation id is stored alongside the link", requestId,
        rawColumn(eventId, "REQUEST_ID"));
  }

  /**
   * An event may legitimately reference a request this instance never issued (another
   * environment, a session created out of band). The raw id is kept for the operator; the FK has
   * nothing to point at.
   */
  @Test
  public void testAClaimWithAnUnknownRequestIdKeepsTheRawIdWithoutALink() {
    String eventId = newEventId();
    String requestId = REQUEST_MARKER + "unknown-" + UUID.randomUUID();

    assertTrue(store.claim(eventId, COMPLETED, requestId, null));

    assertNull("No request matches, so there is nothing to link",
        rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertEquals(requestId, rawColumn(eventId, "REQUEST_ID"));
  }

  /** Events unrelated to a checkout (invoice, customer) carry no correlation at all. */
  @Test
  public void testAClaimWithoutARequestIdStoresNeitherTheIdNorALink() {
    for (String requestId : new String[] { null, "", "   " }) {
      String eventId = newEventId();

      assertTrue(store.claim(eventId, "invoice.paid", requestId, null));

      assertNull("[" + requestId + "] must be stored as null",
          rawColumn(eventId, "REQUEST_ID"));
      assertNull(rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 3 — redelivery: no second row, a counter on the first
  // ---------------------------------------------------------------------------------------------

  /**
   * Stripe retries until it gets a 2xx and on its own schedule after that, so a redelivery is
   * ordinary traffic. It must be dismissed, counted on the row that won, and must not rewrite
   * anything that row recorded — even when the redelivered payload differs, the first delivery is
   * the record.
   */
  @Test
  public void testARedeliveryIsDismissedAndCountedOnTheRowThatWon() {
    String email = newEmail("redelivery");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);
    String eventId = newEventId();
    assertTrue(store.claim(eventId, COMPLETED, requestId, "{\"id\":\"cs_first\"}"));
    Timestamp receivedAt = rawTimestamp(eventId, "RECEIVED_AT");
    String linkedPk = (String) rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID");
    assertNotNull("Sanity: the first claim must have linked the request", linkedPk);

    assertFalse("The second delivery must be dismissed",
        store.claim(eventId, "checkout.session.async_payment_succeeded",
            REQUEST_MARKER + "other-" + UUID.randomUUID(), "{\"id\":\"cs_second\"}"));

    assertEquals("Redeliveries never add rows", 1L, rawCount(eventId));
    assertEquals(1L, rawDuplicateCount(eventId));
    assertNotNull("LAST_DUPLICATE_AT must be stamped on the first redelivery",
        rawTimestamp(eventId, "LAST_DUPLICATE_AT"));
    assertEquals("A duplicate must not change the result", RECEIVED, rawResult(eventId));
    assertEquals("A duplicate must not move RECEIVED_AT", receivedAt,
        rawTimestamp(eventId, "RECEIVED_AT"));
    assertEquals("A duplicate must not overwrite the event type", COMPLETED,
        rawColumn(eventId, "EVENT_TYPE"));
    assertEquals("A duplicate must not overwrite the correlation id", requestId,
        rawColumn(eventId, "REQUEST_ID"));
    assertEquals("A duplicate must not overwrite the link", linkedPk,
        rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertEquals("A duplicate must not overwrite the summary", "{\"id\":\"cs_first\"}",
        rawColumn(eventId, "PAYLOAD_SUMMARY"));

    assertFalse("The third delivery must be dismissed too",
        store.claim(eventId, COMPLETED, requestId, null));
    assertEquals("Every redelivery counts", 2L, rawDuplicateCount(eventId));
    assertEquals(1L, rawCount(eventId));
  }

  /**
   * The acceptance criterion of ETP-5045. A JUnit test cannot restart Tomcat; what it pins is the
   * property that makes a restart survivable — the claim lives in the database, not in the
   * process. The event is claimed through one store instance, the session is committed and
   * closed, and a brand-new instance is asked again, so nothing carried over in Java object state
   * or in the Hibernate first-level cache. A regression that reintroduced any in-process
   * memoisation (the retired {@code CheckoutPaymentRegistry}) would pass a same-instance test and
   * fail this one.
   */
  @Test
  public void testAClaimedEventIsStillADuplicateThroughABrandNewStoreInstance() {
    String eventId = newEventId();
    assertTrue(store.claim(eventId, COMPLETED, null, null));

    OBDal.getInstance().commitAndClose();
    BillingEventStore storeAfterRestart = new BillingEventStore();

    assertFalse("A claimed event id must survive in the database, independently of the store "
        + "instance (and therefore of the process) that claimed it",
        storeAfterRestart.claim(eventId, COMPLETED, null, null));
    assertEquals(1L, rawDuplicateCount(eventId));
    assertEquals(1L, rawCount(eventId));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 4 — results: APPLIED and IGNORED are terminal, FAILED is re-claimable
  // ---------------------------------------------------------------------------------------------

  /**
   * The self-healing path. A transient handler failure (database hiccup, provisioning timeout)
   * marks the row {@code FAILED}; Stripe's own retry must then be treated as a fresh claim so the
   * event is applied without anyone touching the database. The re-claim clears the failure
   * annotation and the decision timestamp, is not counted as a duplicate, and leaves what the
   * first delivery recorded (correlation, link, type) untouched.
   */
  @Test
  public void testAFailedEventIsReclaimedByTheNextDelivery() {
    String email = newEmail("reclaim");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);
    String eventId = newEventId();
    assertTrue(store.claim(eventId, COMPLETED, requestId, null));
    String linkedPk = (String) rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID");
    assertNotNull("Sanity: the first claim must have linked the request", linkedPk);

    store.markFailed(eventId, "provisioning timed out");
    assertEquals("Sanity: the row must be FAILED", FAILED, rawResult(eventId));
    assertEquals("provisioning timed out", rawColumn(eventId, "FAILURE_REASON"));
    assertNotNull(rawTimestamp(eventId, "PROCESSED_AT"));

    assertTrue("The next delivery of a FAILED event must win a fresh claim",
        store.claim(eventId, "checkout.session.async_payment_succeeded",
            REQUEST_MARKER + "other-" + UUID.randomUUID(), "{\"id\":\"cs_retry\"}"));

    assertEquals(RECEIVED, rawResult(eventId));
    assertNull("The failure annotation belongs to the previous attempt",
        rawColumn(eventId, "FAILURE_REASON"));
    assertNull("PROCESSED_AT must be cleared so the new decision gets its own timestamp",
        rawTimestamp(eventId, "PROCESSED_AT"));
    assertEquals("A re-claim is not a duplicate", 0L, rawDuplicateCount(eventId));
    assertNull(rawTimestamp(eventId, "LAST_DUPLICATE_AT"));
    assertEquals("A re-claim never touches the correlation id", requestId,
        rawColumn(eventId, "REQUEST_ID"));
    assertEquals("A re-claim never touches the link", linkedPk,
        rawColumn(eventId, "ETGO_CHECKOUT_REQUEST_ID"));
    assertEquals("A re-claim never touches the event type", COMPLETED,
        rawColumn(eventId, "EVENT_TYPE"));
    assertNull("A re-claim never touches the summary", rawColumn(eventId, "PAYLOAD_SUMMARY"));
    assertEquals("Still one row per event id", 1L, rawCount(eventId));
  }

  /**
   * {@code APPLIED} is strictly once-only. {@code PROCESSED_AT} keeps meaning "when the result was
   * first decided" (first-write-wins via {@code coalesce}), and a delivery arriving after the
   * result was recorded is a duplicate that must not disturb it — that is the whole point of
   * keeping the claim in the database.
   */
  @Test
  public void testMarkAppliedIsTerminalAndKeepsTheFirstProcessedAt() throws Exception {
    String eventId = newEventId();
    assertTrue(store.claim(eventId, COMPLETED, null, null));

    store.markApplied(eventId);
    Timestamp firstProcessedAt = rawTimestamp(eventId, "PROCESSED_AT");
    assertNotNull("Sanity: the first markApplied must have stamped PROCESSED_AT",
        firstProcessedAt);
    assertEquals(APPLIED, rawResult(eventId));

    // Guarantees the second call's own "now" is a different instant, so the assertion is about
    // coalesce() and not about two writes landing in the same millisecond.
    Thread.sleep(10);
    store.markApplied(eventId);

    assertEquals("PROCESSED_AT is first-write-wins", firstProcessedAt,
        rawTimestamp(eventId, "PROCESSED_AT"));

    assertFalse("An APPLIED event must never be claimable again",
        store.claim(eventId, COMPLETED, null, null));
    assertEquals("A late delivery must not disturb the result", APPLIED, rawResult(eventId));
    assertEquals(firstProcessedAt, rawTimestamp(eventId, "PROCESSED_AT"));
    assertEquals(1L, rawDuplicateCount(eventId));
  }

  /**
   * {@code IGNORED} carries its reason so the operator can see why an event was deliberately not
   * acted on, and the reason is bounded by the column width rather than failing the write — a
   * long exception message must not turn a clean "ignored" into a constraint error.
   */
  @Test
  public void testMarkIgnoredWritesTheResultAndABoundedReason() {
    String eventId = newEventId();
    assertTrue(store.claim(eventId, "customer.created", null, null));

    store.markIgnored(eventId, "not a checkout event");

    assertEquals(IGNORED, rawResult(eventId));
    assertEquals("not a checkout event", rawColumn(eventId, "FAILURE_REASON"));
    assertNotNull(rawTimestamp(eventId, "PROCESSED_AT"));

    String longEventId = newEventId();
    assertTrue(store.claim(longEventId, "customer.created", null, null));
    StringBuilder longReason = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      longReason.append("0123456789");
    }
    assertTrue("Sanity: the fixture reason must exceed the column", longReason.length() > 255);

    store.markIgnored(longEventId, longReason.toString());

    String stored = (String) rawColumn(longEventId, "FAILURE_REASON");
    assertNotNull(stored);
    assertTrue("The reason must be truncated to the column width, got " + stored.length(),
        stored.length() <= 255);
    assertTrue("The stored reason must be the head of the original",
        longReason.toString().startsWith(stored.substring(0, 200)));
    assertEquals(IGNORED, rawResult(longEventId));

    assertFalse("An IGNORED event must never be claimable again",
        store.claim(eventId, "customer.created", null, null));
    assertEquals(IGNORED, rawResult(eventId));
  }

  /**
   * {@code markFailed} runs on a failure path already, and an unknown id (a row deleted by an
   * operator, a caller bug) must not turn that into a second failure. Nothing is created either:
   * a result without a claim would be a row nobody ever received.
   */
  @Test
  public void testMarkingAnUnknownEventDoesNotThrowAndCreatesNoRow() {
    String eventId = newEventId();

    store.markFailed(eventId, "nothing to mark");
    store.markApplied(eventId);
    store.markIgnored(eventId, "nothing to mark");
    store.markFailed(null, "nothing to mark");
    store.markFailed("   ", "nothing to mark");

    assertEquals("A result write must never create a row", 0L, rawCount(eventId));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 5 — summarize(): an allow-list, never the payload
  // ---------------------------------------------------------------------------------------------

  /**
   * The summary column is the only place provider data lands, and it is an allow-list: card data,
   * {@code payment_method_details} and anything not named may not reach it, wherever they sit in
   * the payload. An expanded {@code customer} object is reduced to its id (its e-mail and payment
   * method go with it), and the correlation id is carried because it is what the operator looks
   * an event up by.
   */
  @Test
  public void testSummarizeKeepsOnlyTheAllowListedFields() throws Exception {
    JSONObject event = new JSONObject("{"
        + "\"id\":\"evt_summary\",\"type\":\"checkout.session.completed\","
        + "\"card\":{\"last4\":\"1111\"},"
        + "\"data\":{\"object\":{"
        + "  \"id\":\"cs_summary\","
        + "  \"customer\":{\"id\":\"cus_summary\",\"email\":\"holder@example.test\","
        + "               \"card\":{\"number\":\"4242424242424242\",\"last4\":\"4242\"}},"
        + "  \"subscription\":\"sub_summary\","
        + "  \"livemode\":false,"
        + "  \"payment_status\":\"paid\","
        + "  \"amount_total\":4900,"
        + "  \"currency\":\"eur\","
        + "  \"mode\":\"subscription\","
        + "  \"number\":\"4242424242424242\","
        + "  \"payment_method_details\":{\"card\":{\"last4\":\"4242\",\"number\":\"4242\"}},"
        + "  \"customer_details\":{\"email\":\"holder@example.test\"},"
        + "  \"metadata\":{\"request_id\":\"  req_summary  \",\"card\":\"4242\"}"
        + "}}}");

    String summary = BillingEventStore.summarize(event);

    assertNotNull(summary);
    for (String forbidden : new String[] { "card", "payment_method_details", "number", "last4",
        "4242", "1111", "holder@example.test", "customer_details" }) {
      assertFalse("The summary must not contain '" + forbidden + "': " + summary,
          summary.contains(forbidden));
    }
    JSONObject parsed = new JSONObject(summary);
    assertEquals("cs_summary", parsed.getString("id"));
    assertEquals("An expanded customer is reduced to its id", "cus_summary",
        parsed.getString("customer"));
    assertEquals("sub_summary", parsed.getString("subscription"));
    assertFalse(parsed.getBoolean("livemode"));
    assertEquals("paid", parsed.getString("payment_status"));
    assertEquals(4900, parsed.getInt("amount_total"));
    assertEquals("eur", parsed.getString("currency"));
    assertEquals("subscription", parsed.getString("mode"));
    assertEquals("The correlation id is carried, trimmed", "req_summary",
        parsed.getJSONObject("metadata").getString("request_id"));
    assertEquals("metadata carries only request_id", 1,
        parsed.getJSONObject("metadata").length());
    assertEquals("Exactly the allow-listed keys and metadata, nothing else", 9, parsed.length());
  }

  /** Nothing allow-listed, nothing to store — null, not an empty object. */
  @Test
  public void testSummarizeReturnsNullWhenThereIsNothingToKeep() throws Exception {
    assertNull(BillingEventStore.summarize(null));
    assertNull("No data at all", BillingEventStore.summarize(new JSONObject(
        "{\"id\":\"evt_x\",\"type\":\"checkout.session.completed\"}")));
    assertNull("data without object", BillingEventStore.summarize(new JSONObject(
        "{\"id\":\"evt_x\",\"data\":{\"previous_attributes\":{\"id\":\"cs_x\"}}}")));
    assertNull("data.object that is not an object", BillingEventStore.summarize(new JSONObject(
        "{\"id\":\"evt_x\",\"data\":{\"object\":\"cs_x\"}}")));
    assertNull("An object with nothing allow-listed", BillingEventStore.summarize(new JSONObject(
        "{\"id\":\"evt_x\",\"data\":{\"object\":{\"card\":{\"last4\":\"4242\"},"
            + "\"metadata\":{\"other\":\"x\"}}}}")));
    assertNull("Arrays and JSON nulls are dropped, not kept", BillingEventStore.summarize(
        new JSONObject("{\"id\":\"evt_x\",\"data\":{\"object\":{\"customer\":[\"cus_a\"],"
            + "\"subscription\":null,\"metadata\":{\"request_id\":\"   \"}}}}")));
  }

  /** The column is 2000 wide; a pathological payload is abbreviated rather than refused. */
  @Test
  public void testSummarizeIsBoundedByTheColumnWidth() throws Exception {
    StringBuilder hugeId = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      hugeId.append('x');
    }
    JSONObject event = new JSONObject();
    event.put("id", "evt_huge");
    event.put("data", new JSONObject().put("object",
        new JSONObject().put("id", hugeId.toString()).put("currency", "eur")));

    String summary = BillingEventStore.summarize(event);

    assertNotNull(summary);
    assertTrue("The summary must fit the column, got " + summary.length(),
        summary.length() <= 2000);
  }

  // ---------------------------------------------------------------------------------------------
  // Group 6 — isUniqueEventViolation(): what counts as "the row already exists"
  // ---------------------------------------------------------------------------------------------

  /**
   * The driver's unique violation arrives wrapped differently depending on which layer caught it
   * first; every one of those shapes must read as a duplicate, or the second delivery of a payment
   * escalates to a 500 and Stripe retries forever.
   */
  @Test
  public void testTheEventIdUniqueViolationIsRecognisedWhereverItIsWrapped() {
    SQLException unique = new SQLException("duplicate key value", "23505");

    assertTrue("Nested under OBException and PersistenceException, constraint named in lower case",
        BillingEventStore.isUniqueEventViolation(new OBException("save failed",
            new PersistenceException("flush failed",
                new ConstraintViolationException("insert", unique, "etgo_billevt_event_uq")))));
    assertTrue("Constraint named in upper case",
        BillingEventStore.isUniqueEventViolation(
            new ConstraintViolationException("insert", unique, "ETGO_BILLEVT_EVENT_UQ")));
    assertTrue("Hibernate could not extract the constraint name",
        BillingEventStore.isUniqueEventViolation(new OBException("save failed",
            new ConstraintViolationException("insert", unique, null))));
    assertTrue("A bare SQLSTATE 23505 somewhere in the chain",
        BillingEventStore.isUniqueEventViolation(
            new RuntimeException("wrapped", new IllegalStateException("again", unique))));
  }

  /**
   * A violation of a different constraint on the same table is a real error, not a duplicate —
   * even when the driver's SQLSTATE underneath happens to be the unique one. Treating it as a
   * duplicate would answer 200 to Stripe and silently drop the event.
   */
  @Test
  public void testOtherFailuresAreNotMistakenForADuplicate() {
    assertFalse("A different constraint, FK SQLSTATE",
        BillingEventStore.isUniqueEventViolation(new OBException("save failed",
            new ConstraintViolationException("insert",
                new SQLException("fk violated", "23503"), "ETGO_BILLEVT_CHKREQ_FK"))));
    assertFalse("A different constraint wins over a unique SQLSTATE underneath it",
        BillingEventStore.isUniqueEventViolation(
            new ConstraintViolationException("insert",
                new SQLException("duplicate key value", "23505"), "ETGO_BILLEVT_PK")));
    assertFalse("An SQLException with another SQLSTATE",
        BillingEventStore.isUniqueEventViolation(
            new RuntimeException("wrapped", new SQLException("deadlock", "40P01"))));
    assertFalse("An unrelated runtime failure",
        BillingEventStore.isUniqueEventViolation(new IllegalStateException("boom")));
    assertFalse("No failure at all", BillingEventStore.isUniqueEventViolation(null));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 7 — an insert failure that is not a duplicate must never be reported as one
  // ---------------------------------------------------------------------------------------------

  /**
   * Alex's S1, and the reason {@code insertReceived} hands its failure to {@code recordDuplicate}
   * instead of swallowing it.
   *
   * <p>{@link BillingEventStore#isUniqueEventViolation} deliberately accepts a
   * {@code ConstraintViolationException} whose constraint name the driver did not report, because
   * Hibernate does not always extract one. But {@code ETGO_BILLEVT_CHKREQ_FK} and
   * {@code ETGO_BILLEVT_RESULT_CHK} can produce that same nameless shape, and in those cases
   * <em>nothing was inserted</em>. The old code answered {@code false} — "duplicate" — so the
   * servlet returned 200, the provider never retried, and a real payment event was lost with no
   * row anywhere to show for it. The claim must fail loudly instead.
   *
   * <p><b>How the failure is provoked.</b> There is no input to {@code claim} that reaches a
   * non-unique constraint on this table: {@code EVENT_RESULT} is always written as
   * {@code RECEIVED}, the checkout-request FK is resolved from a live query (so it is either a
   * real row or null), and client and organization are both {@code 0}. Rather than weaken
   * production visibility or reach in with reflection, the database is made to produce the exact
   * failure shape: a trigger scoped to a marker no other spec and no real event uses, raising
   * {@code unique_violation} with no constraint name. The claim then travels the real path —
   * insert fails, {@code reclaimFailed} matches nothing, {@code recordDuplicate} matches
   * nothing — and the assertion is on what the store does at the end of it.
   */
  @Test
  public void testAnInsertFailureThatIsNotADuplicateIsRethrownAndLeavesNoRow() {
    String eventId = TRIGGER_MARKER + UUID.randomUUID().toString().replace("-", "");
    createFakeViolationTrigger();
    try {
      RuntimeException thrown = null;
      try {
        store.claim(eventId, COMPLETED, null, null);
      } catch (RuntimeException e) {
        thrown = e;
      }

      assertNotNull("A failure that inserted no row must not be reported as a duplicate — the "
          + "servlet would answer 200 and the provider would never retry the event", thrown);
      assertTrue("Sanity: the provoked failure must be one isUniqueEventViolation accepts, "
          + "otherwise this spec would be pinning the wrong branch",
          BillingEventStore.isUniqueEventViolation(thrown));
      assertEquals("The claim failed, so there must be no row at all", 0L, rawCount(eventId));
    } finally {
      dropFakeViolationTrigger();
    }

    // The failed claim must not have left the session or the store unusable: the very next
    // delivery, of an unrelated event, still claims normally.
    String healthy = newEventId();
    assertTrue("A failed claim must not wedge the store", store.claim(healthy, COMPLETED, null,
        null));
    assertEquals(RECEIVED, rawResult(healthy));
  }

  /**
   * The same rule reached without any test scaffolding at all, through the one input that can
   * still break the insert: {@code EVENT_ID} is {@code VARCHAR(255)} and the claim does not
   * abbreviate it, unlike every other column it writes.
   *
   * <p>This failure is not a constraint violation, so it propagates straight out of the insert
   * rather than through {@code recordDuplicate}. Both routes must reach the same place: an
   * exception, and no row. A silent {@code false} here would be the same lost event.
   */
  @Test
  public void testAnEventIdTooLongForTheColumnFailsRatherThanLookingLikeADuplicate() {
    StringBuilder overlong = new StringBuilder(EVENT_MARKER);
    while (overlong.length() <= 255) {
      overlong.append('x');
    }
    String eventId = overlong.toString();

    RuntimeException thrown = null;
    try {
      store.claim(eventId, COMPLETED, null, null);
    } catch (RuntimeException e) {
      thrown = e;
    }

    assertNotNull("An event id the column cannot hold must fail the claim", thrown);
    assertFalse("It is not a unique violation, so it must propagate directly from the insert",
        BillingEventStore.isUniqueEventViolation(thrown));
    assertEquals("Nothing may have been inserted", 0L, rawCount(eventId));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 8 — APPLIED is terminal; IGNORED is terminal by intent only
  // ---------------------------------------------------------------------------------------------

  /**
   * Alex's S2. {@code APPLIED} records that a payment was actually applied, so no later write may
   * move a row out of it. The reachable case is the servlet's own failure path: a redelivery of an
   * already-applied event that throws while re-checking it would mark the row {@code FAILED},
   * and a {@code FAILED} row is re-claimable — so the next delivery would apply the payment a
   * second time. The {@code eventResult <> 'APPLIED'} guard is what closes that loop.
   */
  @Test
  public void testAppliedIsTerminalAgainstALaterFailureOrIgnore() {
    String eventId = newEventId();
    assertTrue(store.claim(eventId, COMPLETED, null, null));
    store.markApplied(eventId);
    Timestamp appliedAt = rawTimestamp(eventId, "PROCESSED_AT");
    assertNotNull("Sanity: markApplied must have stamped PROCESSED_AT", appliedAt);

    store.markFailed(eventId, "a later redelivery threw");

    assertEquals("An applied payment must never be rewritten to FAILED — a FAILED row is "
        + "re-claimable, which would apply the payment twice", APPLIED, rawResult(eventId));
    assertNull("The refused write must not have annotated the row",
        rawColumn(eventId, "FAILURE_REASON"));
    assertEquals(appliedAt, rawTimestamp(eventId, "PROCESSED_AT"));

    store.markIgnored(eventId, "a later redelivery decided to ignore it");

    assertEquals("An applied payment must never be rewritten to IGNORED either", APPLIED,
        rawResult(eventId));
    assertNull(rawColumn(eventId, "FAILURE_REASON"));
    assertEquals(appliedAt, rawTimestamp(eventId, "PROCESSED_AT"));

    assertFalse("And it must still be undelivered-proof", store.claim(eventId, COMPLETED, null,
        null));
    assertEquals(APPLIED, rawResult(eventId));
  }

  /**
   * The deliberate asymmetry, stated as the Javadoc states it: {@code IGNORED} is terminal by
   * intent but <em>not</em> locked, because a later delivery of the same id may legitimately carry
   * the correlation the ignored one lacked — an event ignored as "unknown checkout request" is
   * exactly that case. So a later {@code markFailed} does overwrite it, and the row becomes
   * re-claimable, which is the whole point of not locking it.
   *
   * <p>{@code PROCESSED_AT} still does not move: it keeps meaning "when the result was first
   * decided".
   */
  @Test
  public void testIgnoredIsNotLockedTheWayAppliedIs() {
    String eventId = newEventId();
    assertTrue(store.claim(eventId, COMPLETED, null, null));
    store.markIgnored(eventId, "unknown checkout request");
    Timestamp decidedAt = rawTimestamp(eventId, "PROCESSED_AT");
    assertNotNull("Sanity: markIgnored must have stamped PROCESSED_AT", decidedAt);

    store.markFailed(eventId, "the retry could not be applied either");

    assertEquals("IGNORED is not locked — a later delivery may still fail it", FAILED,
        rawResult(eventId));
    assertEquals("the retry could not be applied either", rawColumn(eventId, "FAILURE_REASON"));
    assertEquals("PROCESSED_AT still records when the result was first decided", decidedAt,
        rawTimestamp(eventId, "PROCESSED_AT"));

    assertTrue("And the row is re-claimable again, which is why it is not locked",
        store.claim(eventId, COMPLETED, null, null));
    assertEquals(RECEIVED, rawResult(eventId));
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  /** A marker-carrying, globally unique event id. */
  private String newEventId() {
    return EVENT_MARKER + UUID.randomUUID().toString().replace("-", "");
  }

  /** A marker-carrying, globally unique account e-mail ({@code ETGO_ACCOUNT.EMAIL} is unique). */
  private String newEmail(String label) {
    return REQUEST_MARKER + label + "-" + UUID.randomUUID().toString().replace("-", "")
        + "@example.test";
  }

  /**
   * Creates the {@code ETGO_ACCOUNT} row every checkout request needs, at client and organization
   * {@code 0} to match the store's own rows.
   *
   * @param email the account e-mail
   * @return the new {@code ETGO_ACCOUNT_ID}
   */
  private String createAccount(String email) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      Account account = OBProvider.getInstance().get(Account.class);
      account.setNewOBObject(true);
      account.setClient(OBDal.getInstance().get(Client.class, ZERO));
      account.setOrganization(OBDal.getInstance().get(Organization.class, ZERO));
      account.setActive(true);
      account.setEmail(email);
      account.setName("ETP-5045 billing event fixture");
      account.setStatus("active");
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      return account.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Records a checkout request through the real store, which is the only writer of
   * {@code ETGO_CHECKOUT_REQUEST}.
   *
   * @param accountId owning {@code ETGO_ACCOUNT_ID}
   * @param email owning account e-mail
   * @return the correlation id
   */
  private String createRequest(String accountId, String email) {
    String requestId = REQUEST_MARKER + UUID.randomUUID().toString().replace("-", "");
    requestStore.recordRequested(requestId, accountId, email, ENVIRONMENT);
    return requestId;
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers and cleanup (native SQL — never the first-level cache)
  // ---------------------------------------------------------------------------------------------

  private String rawResult(String eventId) {
    return (String) rawColumn(eventId, "EVENT_RESULT");
  }

  private long rawDuplicateCount(String eventId) {
    return ((Number) rawColumn(eventId, "DUPLICATE_COUNT")).longValue();
  }

  private Timestamp rawTimestamp(String eventId, String column) {
    return (Timestamp) rawColumn(eventId, column);
  }

  /**
   * Reads one committed column straight from the database.
   *
   * @param eventId provider event id
   * @param column column name; always a literal from this class, never test input
   * @return the raw column value
   */
  @SuppressWarnings("rawtypes")
  private Object rawColumn(String eventId, String column) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT " + column + " FROM ETGO_BILLING_EVENT "
              + "WHERE EVENT_ID = :eventId");
      query.setParameter("eventId", eventId);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** @return how many rows carry this event id — the unique constraint says at most one */
  @SuppressWarnings("rawtypes")
  private long rawCount(String eventId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT COUNT(*) FROM ETGO_BILLING_EVENT WHERE EVENT_ID = :eventId");
      query.setParameter("eventId", eventId);
      return ((Number) query.uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** @return the number of rows in the whole table */
  @SuppressWarnings("rawtypes")
  private long rawTotalCount() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT COUNT(*) FROM ETGO_BILLING_EVENT");
      return ((Number) query.uniqueResult()).longValue();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * @param requestId correlation id
   * @return the primary key of the checkout request, which is what the event's FK stores
   */
  @SuppressWarnings("rawtypes")
  private String rawRequestPk(String requestId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT ETGO_CHECKOUT_REQUEST_ID FROM ETGO_CHECKOUT_REQUEST "
              + "WHERE REQUEST_ID = :requestId");
      query.setParameter("requestId", requestId);
      return (String) query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Installs a trigger that makes an insert of a {@link #TRIGGER_MARKER} event id fail with
   * {@code unique_violation} and <em>no constraint name</em> — the shape
   * {@link BillingEventStore#isUniqueEventViolation} accepts permissively, and which a violation
   * of {@code ETGO_BILLEVT_CHKREQ_FK} or {@code ETGO_BILLEVT_RESULT_CHK} can also produce.
   *
   * <p>Scoped by a {@code WHEN} clause to the marker, so live traffic and every other spec insert
   * normally while it exists. Dropped by the spec's own {@code finally} and again in
   * {@link #cleanUp()}.
   */
  private void createFakeViolationTrigger() {
    dropFakeViolationTrigger();
    executeDdl("CREATE FUNCTION " + TRIGGER_FUNCTION + "() RETURNS trigger AS $$ "
        + "BEGIN RAISE EXCEPTION 'ETP-5045 synthetic constraint failure' "
        + "USING ERRCODE = 'unique_violation'; END; $$ LANGUAGE plpgsql");
    executeDdl("CREATE TRIGGER " + TRIGGER_NAME + " BEFORE INSERT ON ETGO_BILLING_EVENT "
        + "FOR EACH ROW WHEN (NEW.EVENT_ID LIKE '" + TRIGGER_MARKER + "%') "
        + "EXECUTE PROCEDURE " + TRIGGER_FUNCTION + "()");
  }

  /** Removes the synthetic-failure trigger. Safe to call when it was never created. */
  private void dropFakeViolationTrigger() {
    executeDdl("DROP TRIGGER IF EXISTS " + TRIGGER_NAME + " ON ETGO_BILLING_EVENT");
    executeDdl("DROP FUNCTION IF EXISTS " + TRIGGER_FUNCTION + "()");
  }

  /**
   * Runs one DDL statement on its own committed transaction.
   *
   * @param ddl a statement literal from this class, never test input
   */
  @SuppressWarnings("rawtypes")
  private void executeDdl(String ddl) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery statement = OBDal.getInstance().getSession().createNativeQuery(ddl);
      statement.executeUpdate();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Removes everything this class committed, by marker rather than by collected id so rows left
   * behind by a test that died mid-way are cleaned up too. Events go first (they carry the FK to
   * the request), then requests (FK to the account), then accounts.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery deleteEvents = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_BILLING_EVENT WHERE EVENT_ID LIKE :marker");
      // Covers both EVENT_MARKER and TRIGGER_MARKER: the trigger spec expects no row, but a
      // regression that inserted one must not leave it behind for the next run to trip over.
      deleteEvents.setParameter("marker", "evt_etp5045%");
      deleteEvents.executeUpdate();

      NativeQuery deleteRequests = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker");
      deleteRequests.setParameter("marker", REQUEST_MARKER + "%");
      deleteRequests.executeUpdate();

      NativeQuery deleteAccounts = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE :marker");
      deleteAccounts.setParameter("marker", REQUEST_MARKER + "%");
      deleteAccounts.executeUpdate();

      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
