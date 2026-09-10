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

import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * ETP-5045 — real-database specs for {@link CheckoutRequestStore}, the durable half of the hosted
 * checkout flow.
 *
 * <p>Everything here needs a real database on purpose. The three properties this class guards are
 * all properties of what is <em>committed</em>, and none of them can be observed against a mock:
 *
 * <ul>
 *   <li><strong>The tenancy boundary.</strong> {@link CheckoutRequestStore#find} is the only
 *       isolation the payment subsystem has. Its rows live at client and organization {@code 0}
 *       and the query explicitly turns the readable-client and readable-organization filters off,
 *       so DAL row-level security has nothing to catch a regression with — if the account
 *       predicate is ever dropped or weakened, one account can read (and pay against) another
 *       account's checkout. Only an end-to-end query proves it still holds.</li>
 *   <li><strong>The single-claim guarantee.</strong>
 *       {@link CheckoutRequestStore#claimForProvisioning} is a conditional bulk update whose
 *       affected-row count <em>is</em> the answer. Its atomicity is a database property; a stubbed
 *       store would return whatever the stub was told to.</li>
 *   <li><strong>Monotonicity across redeliveries.</strong> Stripe redelivers events freely and the
 *       browser re-enters the flow on reload, so "the same step arrived twice" is an ordinary
 *       occurrence. If a duplicate could move {@code PAID_AT} forward or rewind
 *       {@code CHECKOUT_STATUS}, the staleness thresholds behind {@code DERIVED_STATUS} would stop
 *       meaning anything and an already-provisioned environment could be re-provisioned.</li>
 * </ul>
 *
 * <p><b>Cleanup is explicit, not a rollback.</b> Every write path of the store ends in
 * {@code flush()} + {@code commitAndClose()} — deliberately, so a crash mid-Stripe-call cannot
 * leave a session at the provider that nothing on this side can name. The consequence for tests is
 * that {@link OBDal#rollbackAndClose()} cleans up nothing: the rows are already durable. Every
 * fixture therefore carries the {@code etp5045-it-} marker in its {@code REQUEST_ID} and account
 * e-mail, and {@link #deleteCommittedFixtures()} removes them with native SQL in FK order
 * (requests first, then accounts).
 *
 * <p><b>Two consequences of committing that shape the code below.</b> A commit closes the
 * Hibernate session, so any entity held across a store call is detached — ids and e-mails are
 * captured into local {@code String}s before the first call rather than read back off an entity.
 * And every store method installs a {@code (0,0,0,0)} {@link OBContext} and never restores the
 * caller's, so no test may assume its own context survived a store call; each one re-establishes
 * the context it needs.
 *
 * <p>Assertions on committed values go through native SQL rather than DAL getters, so they read
 * the database instead of Hibernate's first-level cache — which has no way to know a bulk HQL
 * update changed rows out from under it.
 */
public class CheckoutRequestStoreIntegrationTest extends OBBaseTest {

  /** Prefix on every fixture request id and account e-mail, so cleanup can be exact. */
  private static final String MARKER = "etp5045-it-";

  private static final String ZERO = "0";

  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_PROVISIONING = "PROVISIONING";
  private static final String STATUS_PROVISIONED = "PROVISIONED";

  private static final String ENVIRONMENT = "ETP-5045 Integration Environment";

  private final CheckoutRequestStore store = new CheckoutRequestStore();

  /**
   * Deletes the committed fixtures, closes the session, and clears the thread context.
   *
   * <p><b>Deliberately not the admin-mode unwind loop the other DB tests in this module use.</b>
   * That idiom — {@code while (isInAdministratorMode()) restorePreviousMode();} — cannot
   * terminate here. Every {@link CheckoutRequestStore} method installs the system context
   * ({@code OBContext.setOBContext("0","0","0","0")}), and
   * {@link OBContext#isInAdministratorMode()} returns true when the <em>role</em> is System
   * Administrator, not only when an admin-mode frame is open. So once the stack is drained the
   * condition is still true, {@code restorePreviousMode()} keeps logging "Unbalanced calls to
   * setAdminMode and restorePreviousMode", and the loop spins forever.
   *
   * <p>The loop is also unnecessary: every {@code setAdminMode} in this class and in the store is
   * already paired in a {@code finally}, so no frame is ever left open. What does need undoing is
   * the context the store replaced and never restored — clearing it leaves the thread clean for
   * {@code OBBaseTest}'s own post-test check.
   */
  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Group 1 — the tenancy boundary of find(requestId, accountEmail)
  // ---------------------------------------------------------------------------------------------

  /**
   * The baseline the other three cases are measured against: the owning account can read its own
   * request. Without this, a green "returns null" suite would prove only that the query is broken.
   */
  @Test
  public void testFindReturnsTheRequestForItsOwnAccountEmail() {
    String email = newEmail("owner");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);

    CheckoutRequest found = store.find(requestId, email);

    assertNotNull("The owning account must be able to read its own checkout request", found);
    assertEquals(requestId, found.getRequest());
    assertEquals(email, found.getAccountEmail());
  }

  /**
   * E-mail is a case-insensitive identifier in practice — a browser autofills what the user typed
   * at signup, which is not necessarily how the account was stored. If the {@code lower()} on both
   * sides of the predicate is ever dropped, a paying customer who typed {@code Owner@…} instead of
   * {@code owner@…} is told their payment does not exist and is asked to pay again.
   */
  @Test
  public void testFindMatchesTheAccountEmailCaseInsensitively() {
    String email = newEmail("mixedcase");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);

    CheckoutRequest found = store.find(requestId, email.toUpperCase());

    assertNotNull("An e-mail differing only in case must resolve to the same request", found);
    assertEquals(requestId, found.getRequest());
  }

  /**
   * The regression that matters most: the rows sit at client 0 with both readable-client and
   * readable-organization filters switched off, so the account predicate is the <em>only</em>
   * thing standing between one account and another's checkout — and its Stripe customer and
   * subscription ids.
   */
  @Test
  public void testFindReturnsNullForADifferentAccountsEmail() {
    String ownerEmail = newEmail("victim");
    String ownerId = createAccount(ownerEmail);
    String requestId = createRequest(ownerId, ownerEmail);
    String intruderEmail = newEmail("intruder");
    createAccount(intruderEmail);

    assertNull("A foreign account must never resolve another account's checkout request",
        store.find(requestId, intruderEmail));
  }

  /** A correlation id that was never issued must resolve to nothing at all. */
  @Test
  public void testFindReturnsNullForAnUnknownRequestId() {
    String email = newEmail("unknown");
    createAccount(email);

    assertNull("An unissued correlation id must not resolve to any request",
        store.find(MARKER + "never-issued-" + UUID.randomUUID(), email));
  }

  /**
   * Non-disclosure, stated as the property it actually is: a caller must not be able to tell
   * "this request exists but is not yours" from "this request does not exist". Distinguishing the
   * two would turn the status endpoint into an oracle for enumerating valid correlation ids, which
   * is why the account check lives inside the query instead of being applied to its result.
   */
  @Test
  public void testAForeignRequestIsIndistinguishableFromAnUnknownOne() {
    String ownerEmail = newEmail("oracle-owner");
    String ownerId = createAccount(ownerEmail);
    String realRequestId = createRequest(ownerId, ownerEmail);
    String intruderEmail = newEmail("oracle-intruder");
    createAccount(intruderEmail);

    CheckoutRequest foreignRequest = store.find(realRequestId, intruderEmail);
    CheckoutRequest unknownRequest = store.find(MARKER + "absent-" + UUID.randomUUID(),
        intruderEmail);

    assertNull("A real request seen by the wrong account must read as absent", foreignRequest);
    assertNull("An absent request must read as absent", unknownRequest);
    assertEquals("Both answers must be the same shape — otherwise the endpoint leaks which "
        + "correlation ids are real", foreignRequest, unknownRequest);
  }

  /**
   * Missing input is answered, not queried. A blank e-mail reaching the query would compare
   * {@code lower('')} against stored values and could only ever be a bug; refusing it up front
   * keeps the "no identity, no answer" rule independent of whatever the predicate happens to do.
   */
  @Test
  public void testFindReturnsNullForBlankOrNullIdentifiers() {
    String email = newEmail("blank");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);

    assertNotNull("Sanity: the fixture itself must be findable", store.find(requestId, email));
    assertNull("A null request id has no answer", store.find(null, email));
    assertNull("A blank request id has no answer", store.find("   ", email));
    assertNull("A null account e-mail has no answer", store.find(requestId, null));
    assertNull("A blank account e-mail has no answer", store.find(requestId, "   "));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 2 — claimForProvisioning: exactly one caller may provision
  // ---------------------------------------------------------------------------------------------

  /**
   * The happy path of the claim, asserted on the committed row rather than on an entity: the
   * status moves to {@code PROVISIONING}, the attempt counter opens at 1 and the "stuck since"
   * timestamp is stamped. All three are what the operations view reads to tell a wedged
   * provisioning apart from a slow one.
   */
  @Test
  public void testAPaidRequestIsClaimedOnceAndStampsItsProvisioningState() throws Exception {
    String email = newEmail("claim-happy");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue("A PAID request must be claimable", store.claimForProvisioning(requestId, email));

    assertEquals(STATUS_PROVISIONING, rawStatus(requestId));
    assertEquals("The first claim opens the retry counter at 1", 1L, rawAttempts(requestId));
    assertNotNull("PROVISIONING_AT must be stamped so staleness can be measured",
        rawTimestamp(requestId, "PROVISIONING_AT"));
  }

  /**
   * The reload-during-provisioning guard, and the reason the claim is a conditional bulk update
   * instead of a read-then-write. A user refreshing the onboarding page fires a second claim while
   * the first is still running; if both won, two provisioning runs would race to create the same
   * environment. The {@code and cr.checkoutRequestStatus = :paid} predicate is what makes the
   * second one match zero rows.
   */
  @Test
  public void testAnImmediateSecondClaimOfTheSameRequestIsRefused() {
    String email = newEmail("claim-double");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue("The first caller wins the claim", store.claimForProvisioning(requestId, email));
    assertFalse("A second concurrent caller must lose — two provisioning runs for one payment "
        + "would race to create the same environment",
        store.claimForProvisioning(requestId, email));

    assertEquals("The refused claim must not have touched the status", STATUS_PROVISIONING,
        rawStatus(requestId));
    assertEquals("A refused claim matches no rows, so it cannot increment the counter", 1L,
        rawAttempts(requestId));
  }

  /**
   * Provisioning is what the customer paid for, so nothing before {@code PAID} may reach it. A
   * request still at {@code CREATED} has a Stripe session open and no confirmed payment; claiming
   * it would hand out an environment for free.
   */
  @Test
  public void testARequestThatIsNotYetPaidCannotBeClaimed() {
    String email = newEmail("claim-unpaid");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);
    store.recordSessionCreated(requestId, "cs_" + requestId);

    assertFalse("Only a confirmed payment may be claimed for provisioning",
        store.claimForProvisioning(requestId, email));
    assertEquals("CREATED", rawStatus(requestId));
    assertEquals(0L, rawAttempts(requestId));
  }

  /**
   * The claim carries the same account predicate as {@link CheckoutRequestStore#find}, and for the
   * same reason: this is the write side of the tenancy boundary. Without it, knowing someone
   * else's correlation id would be enough to consume their payment.
   */
  @Test
  public void testAForeignAccountEmailCannotClaimAPaidRequest() {
    String ownerEmail = newEmail("claim-owner");
    String ownerId = createAccount(ownerEmail);
    String requestId = createPaidRequest(ownerId, ownerEmail);
    String intruderEmail = newEmail("claim-intruder");
    createAccount(intruderEmail);

    assertFalse("A foreign account must not be able to consume another account's payment",
        store.claimForProvisioning(requestId, intruderEmail));
    assertEquals("The refused claim must leave the request payable by its owner", STATUS_PAID,
        rawStatus(requestId));
  }

  /**
   * {@code PROVISIONING_AT} answers "since when has this been stuck", so a retry must not reset
   * it — if it did, a request wedged for an hour would look freshly started at every retry and no
   * staleness threshold could ever fire. The retry count lives in {@code PROVISIONING_ATTEMPTS}
   * instead, which is the value that is supposed to move.
   *
   * <p>The row is pushed back to {@code PAID} with native SQL because that is the only way to
   * reach the "a second claim actually matches" state deterministically — in production it is a
   * failed provisioning run that puts it back.
   */
  @Test
  public void testProvisioningAtIsFirstWriteWinsWhileTheAttemptCounterAdvances() throws Exception {
    String email = newEmail("claim-retry");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue(store.claimForProvisioning(requestId, email));
    Timestamp firstProvisioningAt = rawTimestamp(requestId, "PROVISIONING_AT");
    assertNotNull("Sanity: the first claim must have stamped PROVISIONING_AT",
        firstProvisioningAt);

    // Guarantees the second claim's own "now" is a different instant, so the assertion below is
    // about coalesce() and not about two writes landing in the same millisecond.
    Thread.sleep(10);
    forceStatus(requestId, STATUS_PAID);

    assertTrue("A request pushed back to PAID must be claimable again",
        store.claimForProvisioning(requestId, email));

    assertEquals("The retry count is the value that moves", 2L, rawAttempts(requestId));
    assertEquals("PROVISIONING_AT is first-write-wins via coalesce — a retry must not reset the "
        + "clock the staleness thresholds are measured against", firstProvisioningAt,
        rawTimestamp(requestId, "PROVISIONING_AT"));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 3 — restart survival, the acceptance criterion of ETP-5045
  // ---------------------------------------------------------------------------------------------

  /**
   * ETP-5045 replaced the in-memory {@code CheckoutPaymentRegistry} correlation because a Tomcat
   * restart between Stripe's webhook and the customer's return wiped the payment: the customer had
   * been charged and the paywall no longer knew it.
   *
   * <p><b>Honest statement of the proxy:</b> a JUnit test cannot restart Tomcat, and this test does
   * not pretend to. What it pins is the property that makes a restart survivable — the confirmed
   * payment lives in the <em>database</em> and not in the process. The payment is written through
   * one {@link CheckoutRequestStore} instance; the session is then committed and closed, and the
   * answer is read back through a <em>brand-new</em> instance, so nothing carried over in Java
   * object state or in the Hibernate first-level cache. A regression that reintroduced any
   * in-process memoisation would still pass a same-instance test and fail this one.
   */
  @Test
  public void testAConfirmedPaymentIsReadableThroughABrandNewStoreInstance() {
    String email = newEmail("restart");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    // Drop every trace of this JVM's view of the row: the store already committed, and this
    // closes whatever session the test itself still holds.
    OBDal.getInstance().commitAndClose();

    CheckoutRequestStore storeAfterRestart = new CheckoutRequestStore();

    assertTrue("A confirmed payment must survive in the database, independently of the store "
        + "instance (and therefore of the process) that recorded it",
        storeAfterRestart.isPaidFor(requestId, email, ENVIRONMENT));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 4 — monotonic, idempotent duplicates (Stripe redelivery)
  // ---------------------------------------------------------------------------------------------

  /**
   * Stripe redelivers {@code checkout.session.completed} on its own schedule, so a second
   * {@code recordPaid} for the same request is routine rather than an error. {@code PAID_AT} must
   * keep meaning "when the payment was confirmed" — if a redelivery moved it, it would degrade
   * into "when we last heard about it" and every staleness window computed from it would silently
   * widen.
   */
  @Test
  public void testARedeliveredPaymentDoesNotMovePaidAt() throws Exception {
    String email = newEmail("redelivery");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    Timestamp firstPaidAt = rawTimestamp(requestId, "PAID_AT");
    assertNotNull("Sanity: the first payment must have stamped PAID_AT", firstPaidAt);

    // Same reason as in the retry test: makes the assertion about the guard, not about clock
    // resolution.
    Thread.sleep(10);
    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);

    assertEquals("A redelivered webhook must leave PAID_AT at the moment the payment was "
        + "actually confirmed", firstPaidAt, rawTimestamp(requestId, "PAID_AT"));
    assertEquals("A redelivery must not change the status either", STATUS_PAID,
        rawStatus(requestId));
  }

  /**
   * The lifecycle only ever moves forward. A late
   * {@code checkout.session.async_payment_succeeded} can arrive after provisioning has already
   * finished; if it rewound the status to {@code PAID}, the request would become claimable again
   * and the customer would get a second environment for one payment.
   */
  @Test
  public void testALatePaymentEventDoesNotRewindAProvisionedRequest() {
    String email = newEmail("late-event");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    store.recordProvisioned(requestId, null);
    assertEquals("Sanity: the fixture must have reached PROVISIONED", STATUS_PROVISIONED,
        rawStatus(requestId));

    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);

    assertEquals("A late payment event must never rewind an already provisioned request — that "
        + "would make it claimable a second time", STATUS_PROVISIONED, rawStatus(requestId));
    assertFalse("And it must therefore stay unclaimable",
        store.claimForProvisioning(requestId, email));
  }

  // ---------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------

  /** A marker-carrying, globally unique correlation id. */
  private String newRequestId() {
    return MARKER + UUID.randomUUID().toString().replace("-", "");
  }

  /**
   * A marker-carrying, globally unique account e-mail. {@code ETGO_ACCOUNT.EMAIL} is unique, so a
   * fixed address would make the second test in a run fail on a constraint.
   *
   * @param label short human hint about which test owns the row
   * @return the fixture e-mail
   */
  private String newEmail(String label) {
    return MARKER + label + "-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
  }

  /**
   * Creates the {@code ETGO_ACCOUNT} row every checkout request needs — {@code ETGO_ACCOUNT_ID} is
   * NOT NULL with an FK. Built at client and organization {@code 0} to match the store's own rows
   * and the table's {@code ACCESSLEVEL}; a test-client account would fail the security check on
   * the store's first flush.
   *
   * @param email the account e-mail, which is also the tenancy key the store compares against
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
      account.setName("ETP-5045 integration fixture");
      account.setStatus("active");
      OBDal.getInstance().save(account);
      OBDal.getInstance().flush();
      // Read now, while the object is still managed: the first store call commits and closes the
      // session, after which this instance is detached.
      return account.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Records a checkout request in {@code CREATING}, which is where the real flow starts.
   *
   * @param accountId owning {@code ETGO_ACCOUNT_ID}
   * @param email owning account e-mail, denormalised onto the request
   * @return the correlation id
   */
  private String createRequest(String accountId, String email) {
    String requestId = newRequestId();
    store.recordRequested(requestId, accountId, email, ENVIRONMENT);
    return requestId;
  }

  /**
   * Drives a request through the full real sequence up to a confirmed payment:
   * {@code CREATING → CREATED → PAID}. The intermediate step is not skipped because
   * {@code recordPaid} only advances the status when the transition moves strictly forward, so a
   * fixture that jumped straight to it would be testing a state the flow never produces.
   *
   * @param accountId owning {@code ETGO_ACCOUNT_ID}
   * @param email owning account e-mail
   * @return the correlation id of a request at {@code PAID}
   */
  private String createPaidRequest(String accountId, String email) {
    String requestId = createRequest(accountId, email);
    // STRIPE_SESSION_ID is unique, so it is derived from the already-unique correlation id.
    store.recordSessionCreated(requestId, "cs_" + requestId);
    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);
    return requestId;
  }

  // ---------------------------------------------------------------------------------------------
  // Committed-state readers and cleanup (native SQL — never the first-level cache)
  // ---------------------------------------------------------------------------------------------

  /**
   * @param requestId correlation id
   * @return the committed {@code CHECKOUT_STATUS}
   */
  private String rawStatus(String requestId) {
    return (String) rawColumn(requestId, "CHECKOUT_STATUS");
  }

  /**
   * @param requestId correlation id
   * @return the committed {@code PROVISIONING_ATTEMPTS}
   */
  private long rawAttempts(String requestId) {
    return ((Number) rawColumn(requestId, "PROVISIONING_ATTEMPTS")).longValue();
  }

  /**
   * @param requestId correlation id
   * @param column a timestamp column name
   * @return the committed value, or null
   */
  private Timestamp rawTimestamp(String requestId, String column) {
    return (Timestamp) rawColumn(requestId, column);
  }

  /**
   * Reads one committed column straight from the database. Deliberately native SQL: after a bulk
   * HQL update Hibernate's first-level cache still holds the pre-update entity, so a DAL getter
   * could report the value the test wrote rather than the value the store committed.
   *
   * @param requestId correlation id
   * @param column column name; always a literal from this class, never test input
   * @return the raw column value
   */
  @SuppressWarnings("rawtypes")
  private Object rawColumn(String requestId, String column) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery query = OBDal.getInstance()
          .getSession()
          .createNativeQuery("SELECT " + column + " FROM ETGO_CHECKOUT_REQUEST "
              + "WHERE REQUEST_ID = :requestId");
      query.setParameter("requestId", requestId);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Pushes a committed request back to an earlier status, which the store itself refuses to do.
   * Only used to reconstruct the "a previous provisioning run failed" state that makes a second
   * claim meaningful.
   *
   * @param requestId correlation id
   * @param status the status to force
   */
  @SuppressWarnings("rawtypes")
  private void forceStatus(String requestId, String status) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery update = OBDal.getInstance()
          .getSession()
          .createNativeQuery("UPDATE ETGO_CHECKOUT_REQUEST SET CHECKOUT_STATUS = :status, "
              + "UPDATED = :now WHERE REQUEST_ID = :requestId");
      update.setParameter("status", status);
      update.setParameter("now", new Date());
      update.setParameter("requestId", requestId);
      update.executeUpdate();
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Removes everything this class committed. Rollback cannot do it — the store commits on purpose,
   * so by the time a test returns its rows are durable.
   *
   * <p>Matching is by the {@code etp5045-it-} marker rather than by a collected id list, so rows
   * left behind by a test that died mid-way are cleaned up too. Requests go first: they carry the
   * FK to {@code ETGO_ACCOUNT}.
   */
  @SuppressWarnings("rawtypes")
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      NativeQuery deleteRequests = OBDal.getInstance()
          .getSession()
          .createNativeQuery(
              "DELETE FROM ETGO_CHECKOUT_REQUEST WHERE REQUEST_ID LIKE :marker");
      deleteRequests.setParameter("marker", MARKER + "%");
      deleteRequests.executeUpdate();

      NativeQuery deleteAccounts = OBDal.getInstance()
          .getSession()
          .createNativeQuery("DELETE FROM ETGO_ACCOUNT WHERE EMAIL LIKE :marker");
      deleteAccounts.setParameter("marker", MARKER + "%");
      deleteAccounts.executeUpdate();

      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
