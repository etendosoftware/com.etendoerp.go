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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
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
 * e-mail, and {@link #deleteCommittedFixtures()} removes them with DAL in FK order
 * (requests first, then accounts).
 *
 * <p><b>Two consequences of committing that shape the code below.</b> A commit closes the
 * Hibernate session, so any entity held across a store call is detached — ids and e-mails are
 * captured into local {@code String}s before the first call rather than read back off an entity.
 * And a store call leaves the Hibernate session closed, so each fixture helper below opens the
 * {@code (0,0,0,0)} context it needs rather than assuming one is still installed.
 *
 * <p><b>Group 6 pins the fourth property: the caller's {@link OBContext} survives a store call.</b>
 * It did not always. Every method swapped in the system context and unwound with
 * {@link OBContext#restorePreviousMode()} alone, which pops the admin-mode stack and leaves the
 * context swap in place — so the caller silently continued as system. Harmless while the callers
 * were the webhook (no context to lose) and the status endpoint (finished when the store returns),
 * and not harmless at all for {@code applyPaidUpgradeSideEffects}, which calls in from the middle
 * of onboarding and whose every later step depends on the context it established.
 *
 * <p>Assertions on committed values load fresh DAL entities rather than retaining objects from a
 * previous session, so they read committed state after bulk HQL updates.
 *
 * <p><b>Group 7 (ETP-5048) — the two writes the billing reconciliation added</b>, kept in
 * {@link CheckoutRequestWrites} beside the store: the conditional {@code CREATED → EXPIRED} close
 * (a bulk update whose row count is the answer) and the first-write-wins onboarding inputs, plus
 * the store's guard that keeps {@code EXPIRED} terminal.
 *
 * <p><b>Group 8 (ETP-5048) — the fence of a paid re-subscription.</b> The payment webhook and the
 * billing reconciliation can both reach one paid re-subscription. {@code findPaidReactivation}
 * locks the row ({@code SELECT ... FOR UPDATE}) and re-reads it, so of two racing callers exactly
 * one applies it; the other waits for that commit and then finds nothing to do. Proven with two
 * threads, each with its own DAL session, against the real row lock; every wait is bounded.
 *
 * <p><b>Group 9 (ETP-5051) — one environment's own subscription.</b> An account that owns several
 * environments has one billable purchase per environment, and
 * {@link CheckoutRequestStore#findSubscriptionForAccountClient} must pick the one of the asked
 * environment — never the account's newest of another — and must find nothing for an environment
 * whose purchase belongs to another account. Like {@code find}, its rows sit at client
 * {@code 0} with the readable filters off, so only the query's own predicates hold this.
 *
 * @covers com.etendoerp.go.payment.CheckoutRequestStore
 * @covers com.etendoerp.go.payment.CheckoutRequestWrites
 */
public class CheckoutRequestStoreIntegrationTest extends OBBaseTest {

  /** Prefix on every fixture request id and account e-mail, so cleanup can be exact. */
  private static final String MARKER = "etp5045-it-";

  private static final String ZERO = "0";

  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_PROVISIONING = "PROVISIONING";
  private static final String STATUS_PROVISIONED = "PROVISIONED";

  private static final String ENVIRONMENT = "ETP-5045 Integration Environment";

  /**
   * A 32-character {@code ETGO_ACCOUNT_ID} that names no account. {@code ETGO_ACCOUNT_ID} is NOT
   * NULL with an FK, so recording a request against it fails on flush — which is how Group 6 gets
   * a store call to throw from inside, past the point where it has already swapped the context.
   */
  private static final String UNKNOWN_ACCOUNT_ID = "ETP5045NOSUCHACCOUNT000000000000";

  /**
   * An instant no write by the code under test can ever produce, so a "first-write-wins" column
   * forced to it is either still it or was overwritten — with no clock resolution in between.
   */
  private static final Timestamp DISTANT_PAST = Timestamp.valueOf("2020-01-01 00:00:00");

  private final CheckoutRequestStore store = new CheckoutRequestStore();
  private final CheckoutRequestWrites writes = new CheckoutRequestWrites();

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
   * already paired in a {@code finally}, so no frame is ever left open. The context still needs
   * clearing, but the store is no longer what leaves one behind — the fixture helpers in this
   * class install {@code (0,0,0,0)} and do not restore it. Clearing it leaves the thread clean for
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
   * The chosen demo is durable checkout intent: after Stripe redirects back and onboarding claims
   * the payment (including a later retry), the source must remain the same client selected before
   * checkout. Re-discovering a free client from account e-mail can choose a different tenant when
   * the account owns more than one trial.
   */
  @Test
  public void testSelectedDemoClientIdSurvivesProvisioningRetryAndIsAccountScoped() {
    String email = newEmail("selected-demo-owner");
    String accountId = createAccount(email);
    String requestId = newRequestId();
    String selectedDemoClientId = ZERO;
    store.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(selectedDemoClientId, true, true, false, null));
    store.recordSessionCreated(requestId, "cs_" + requestId);
    CheckoutRequest retriedCheckout = store.findActiveForAccountAndClientName(
        accountId, email, ENVIRONMENT);
    assertNotNull("Retry by the same environment name must resolve the original purchase",
        retriedCheckout);
    assertEquals("Retry reuses the original purchase id", requestId, retriedCheckout.getRequest());
    store.recordSessionCreated(requestId, "cs_duplicate_" + requestId);
    assertEquals("Repeated checkout callbacks must not replace the Stripe session",
        "cs_" + requestId, store.find(requestId, accountId, email).getStripeSession());
    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);

    assertEquals("The stored selection is returned only for the authenticated account",
        selectedDemoClientId, store.findDemoClientId(requestId, accountId, email));
    assertTrue("A retry may continue only with the demo selected before checkout",
        store.matchesDemoSelection(requestId, accountId, email, selectedDemoClientId));
    assertFalse("A later conflicting demo choice must not replace the checkout selection",
        store.matchesDemoSelection(requestId, accountId, email, "TRIAL-OTHER"));
    CheckoutRequestStore.TransferSelection originalTransfer = store.findTransferSelection(
        requestId, accountId, email);
    assertTrue("Product transfer intent is stored with the purchase", originalTransfer.isProducts());
    assertFalse("Contact transfer intent is stored with the purchase", originalTransfer.isContacts());

    assertTrue("The paid request can be claimed for its first provisioning attempt",
        store.claimForProvisioning(requestId, accountId, email));
    assertEquals("Claiming provisioning must not replace the checkout's selected source",
        selectedDemoClientId, store.findDemoClientId(requestId, accountId, email));
    store.recordFailureReason(requestId, "transient provisioning failure");
    assertTrue("A failed paid attempt can be retried",
        store.claimForProvisioning(requestId, accountId, email));
    assertEquals("A retry must recover the exact source selected before checkout",
        selectedDemoClientId, store.findDemoClientId(requestId, accountId, email));
    CheckoutRequestStore.TransferSelection retriedTransfer = store.findTransferSelection(
        requestId, accountId, email);
    assertTrue("Retry preserves the products transfer choice", retriedTransfer.isProducts());
    assertFalse("Retry preserves the contacts transfer choice", retriedTransfer.isContacts());
    assertNull("Another account cannot read the selected source for this payment",
        store.findDemoClientId(requestId, createAccount(newEmail("selected-demo-intruder")), email));
  }

  /** A productive-origin checkout persists an explicit empty source, not a legacy missing value. */
  @Test
  public void testRecordedEmptyDemoSelectionStaysEmptyAcrossProvisioningRetries() {
    String email = newEmail("empty-demo-owner");
    String accountId = createAccount(email);
    String requestId = newRequestId();
    store.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(null, true, false, false, null));
    store.recordSessionCreated(requestId, "cs_" + requestId);
    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);

    assertTrue("An empty selection must be distinguishable from a legacy request",
        store.hasRecordedDemoSelection(requestId, accountId, email));
    assertNull("The productive checkout has no source demo", store.findDemoClientId(
        requestId, accountId, email));
    assertTrue("The original empty selection is a valid retry identity",
        store.matchesDemoSelection(requestId, accountId, email, null));
    assertFalse("A demo added after checkout cannot become a retry source",
        store.matchesDemoSelection(requestId, accountId, email, "TRIAL-ADDED-LATER"));
    CheckoutRequestStore.TransferSelection originalTransfer = store.findTransferSelection(
        requestId, accountId, email);
    assertFalse(originalTransfer.isProducts());
    assertFalse(originalTransfer.isContacts());

    assertTrue("The paid request can be claimed for its first attempt",
        store.claimForProvisioning(requestId, accountId, email));
    store.recordFailureReason(requestId, "transient provisioning failure");
    assertTrue("A failed paid request can be claimed for retry",
        store.claimForProvisioning(requestId, accountId, email));

    assertTrue("Retry preserves the explicit-empty marker",
        store.hasRecordedDemoSelection(requestId, accountId, email));
    assertNull("Retry must not infer any of the account's later demos", store.findDemoClientId(
        requestId, accountId, email));
    assertFalse("Retry cannot substitute a newly available demo",
        store.matchesDemoSelection(requestId, accountId, email, "TRIAL-ADDED-LATER"));
    CheckoutRequestStore.TransferSelection retriedTransfer = store.findTransferSelection(
        requestId, accountId, email);
    assertFalse("Productive-origin retry keeps product transfer disabled",
        retriedTransfer.isProducts());
    assertFalse("Productive-origin retry keeps contact transfer disabled",
        retriedTransfer.isContacts());
  }

  @Test
  public void testStripePriceIsDurableAndAStaleRetryCannotReplaceItsSession() {
    String email = newEmail("stripe-price-cas");
    String accountId = createAccount(email);
    String requestId = newRequestId();
    String savedPriceId = "price_etp5463_saved";
    store.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(null, true, false, false, savedPriceId));

    assertEquals("The configured provider price must survive the committed write",
        savedPriceId, store.findStripePriceId(requestId));

    store.recordSessionCreated(requestId, "cs_initial");
    try {
      store.recordSessionCreated(requestId, "cs_stale", "cs_replacement");
      org.junit.Assert.fail(
          "A stale retry must not replace a session created by another attempt");
    } catch (IllegalStateException expected) {
      // The compare-and-set mismatch is the behavior under test; the message is not API.
    }

    assertEquals("The first session remains the purchase's correlation anchor",
        "cs_initial", store.findStripeSessionId(requestId));
    assertEquals("A failed compare-and-set leaves the saved price unchanged",
        savedPriceId, store.findStripePriceId(requestId));
  }

  /** Unpaid rows must be filtered before applying the billing overview's 20-row limit. */
  @Test
  public void testFindForAccountFiltersUnpaidRowsBeforeApplyingRecentPurchaseLimit() {
    String email = newEmail("overview-status-filter");
    String accountId = createAccount(email);
    String olderPaidRequestId = createPaidRequest(accountId, email);
    List<String> newerUnpaidRequestIds = new java.util.ArrayList<>();

    for (int index = 0; index < 21; index++) {
      String unpaidRequestId = createRequest(accountId, email);
      store.recordSessionCreated(unpaidRequestId, "cs_" + unpaidRequestId);
      newerUnpaidRequestIds.add(unpaidRequestId);
    }

    List<CheckoutRequest> purchases = store.findForAccount(accountId, email);

    assertTrue("An older confirmed purchase must remain visible when newer unpaid attempts exceed "
        + "the page limit", purchases.stream().anyMatch(purchase -> olderPaidRequestId
            .equals(purchase.getRequest())));
    for (String unpaidRequestId : newerUnpaidRequestIds) {
      assertFalse("Unpaid checkout attempts must not appear in billing overview",
          purchases.stream().anyMatch(purchase -> unpaidRequestId.equals(purchase.getRequest())));
    }
  }

  @Test
  public void testLegacyRequestWithoutSelectionMarkerIsNotMistakenForExplicitEmpty() {
    String email = newEmail("legacy-demo-owner");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);

    assertFalse("Legacy requests have no recorded demo selection",
        store.hasRecordedDemoSelection(requestId, accountId, email));
    assertFalse("A missing selection marker must not be read as explicit empty",
        store.matchesDemoSelection(requestId, accountId, email, null));
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
  public void testFindRequiresTheImmutableAccountIdInAdditionToTheEmail() {
    String ownerEmail = newEmail("same-email-owner");
    String ownerId = createAccount(ownerEmail);
    String requestId = createRequest(ownerId, ownerEmail);
    String differentAccountId = createAccount(newEmail("same-email-intruder"));

    assertNull("A matching email must not compensate for a different authenticated account id",
        store.find(requestId, differentAccountId, ownerEmail));
  }

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
   * {@code PROVISIONING_AT} answers "since when has this been stuck", so an ordinary duplicate
   * retry must not reset it. A stale lease is the explicit exception and is covered below. The
   * retry count lives in {@code PROVISIONING_ATTEMPTS}, which is the value that moves for a retry.
   *
   * <p>The row is pushed back to {@code PAID} through DAL because that is the only way to reach
   * the "a second claim actually matches" state deterministically — in production it is a failed
   * provisioning run that puts it back.
   */
  @Test
  public void testProvisioningAtIsFirstWriteWinsWhileTheAttemptCounterAdvances() throws Exception {
    String email = newEmail("claim-retry");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue(store.claimForProvisioning(requestId, email));
    assertNotNull("Sanity: the first claim must have stamped PROVISIONING_AT",
        rawTimestamp(requestId, "PROVISIONING_AT"));

    // The stamp is moved to an instant no later write could ever produce, so the assertion below
    // is about coalesce() and not about two writes landing in the same millisecond. A sleep would
    // only make those two instants likely to differ; this makes them certain to.
    forceTimestamp(requestId, "PROVISIONING_AT", DISTANT_PAST);
    forceStatus(requestId, STATUS_PAID);

    assertTrue("A request pushed back to PAID must be claimable again",
        store.claimForProvisioning(requestId, email));

    assertEquals("The retry count is the value that moves", 2L, rawAttempts(requestId));
    assertEquals("PROVISIONING_AT is first-write-wins via coalesce — a retry must not reset the "
        + "clock the staleness thresholds are measured against", DISTANT_PAST,
        rawTimestamp(requestId, "PROVISIONING_AT"));
  }

  /** A crashed worker leaves a lease that a later retry may reclaim after the configured window. */
  @Test
  public void testAStaleProvisioningClaimCanBeReclaimedWithANewAttemptToken() {
    String email = newEmail("claim-stale");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue(store.claimForProvisioning(requestId, email));
    forceTimestamp(requestId, "PROVISIONING_AT", DISTANT_PAST);

    assertTrue("A stale provisioning lease must be recoverable after a process interruption",
        store.claimForProvisioning(requestId, email));
    assertEquals("Reclaiming must advance the fencing token", 2L, rawAttempts(requestId));
    assertTrue("Reclaiming must renew the lease timestamp",
        rawTimestamp(requestId, "PROVISIONING_AT").after(DISTANT_PAST));
  }

  /** A failed provisioning run must be retryable immediately, without waiting for the lease. */
  @Test
  public void testFailedProvisioningClaimCanBeReclaimedBeforeTheLeaseExpires() {
    String email = newEmail("claim-failed-retry");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue(store.claimForProvisioning(requestId, email));
    forceFailureReason(requestId, "Provisioning failed");

    assertTrue("A recorded provisioning failure must be retryable immediately",
        store.claimForProvisioning(requestId, email));
    assertEquals("A failed retry must claim the request again", 2L, rawAttempts(requestId));
  }

  /**
   * ETP-5548: a deterministic failure is never reclaimed, even by a caller that ignores the
   * {@code retryAllowed} flag of the status endpoint and posts onboarding directly.
   */
  @Test
  public void testDeterministicFailureIsNeverReclaimed() {
    String email = newEmail("claim-name-in-use");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue(store.claimForProvisioning(requestId, email));
    forceFailureReason(requestId, ProvisioningFailureReason.encode(
        ProvisioningFailureReason.CODE_CLIENT_NAME_IN_USE, "The company name is taken"));

    assertFalse("A name collision fails identically on every attempt",
        store.claimForProvisioning(requestId, email));
    assertEquals("The refused retry must not consume an attempt", 1L, rawAttempts(requestId));
  }

  /** ETP-5548: only unfinished paid purchases hide the environment they are building. */
  @Test
  public void testUnfinishedPaidClientNamesSkipProvisionedPurchases() {
    String email = newEmail("unfinished-names");
    String accountId = createAccount(email);
    String requestId = createPaidRequest(accountId, email);

    assertTrue("A paid purchase that has not provisioned is unfinished",
        store.findUnfinishedPaidClientNames(accountId, email)
            .containsKey(ENVIRONMENT.toLowerCase(java.util.Locale.ROOT)));

    forceStatus(requestId, STATUS_PROVISIONED);

    assertTrue("A provisioned purchase no longer hides its environment",
        store.findUnfinishedPaidClientNames(accountId, email).isEmpty());
    assertTrue("Another account never sees this account's purchases",
        store.findUnfinishedPaidClientNames(createAccount(newEmail("unfinished-intruder")), email)
            .isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Group 3 — restart survival, the acceptance criterion of ETP-5045
  // ---------------------------------------------------------------------------------------------

  /**
   * ETP-5045 replaced the in-memory {@code CheckoutPaymentRegistry} (since deleted; its two halves
   * are now {@code ETGO_CHECKOUT_REQUEST} and {@code ETGO_BILLING_EVENT}) because a Tomcat
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

    // Compared with the first stamp rather than a backdated one: a PAID row backdated past the
    // paid grace would be a provisioning candidate for a live billing reconciliation run. The
    // redelivery is a full DB round trip later, so a rewritten stamp would differ from the first.
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
  // Group 5 — recordPaid reports whether the correlation id named a request at all
  // ---------------------------------------------------------------------------------------------

  /**
   * ETP-5045 review follow-up. {@code recordPaid} used to return {@code void}, so the webhook
   * handler could not tell "the payment was recorded" from "that correlation id means nothing
   * here" — it marked the billing event {@code APPLIED} either way. In a shared Stripe test
   * account an event referencing a request this instance never issued is ordinary traffic, so the
   * audit row would routinely claim a payment had been applied when nothing had been. The boolean
   * is what lets the handler mark those {@code IGNORED} instead.
   *
   * <p>"Found", not "advanced": a redelivery of a payment already recorded still returns
   * {@code true}, because the request <em>is</em> known. Only an unknown correlation id is
   * {@code false}. Conflating the two would make every Stripe retry of a genuine payment look
   * like an unknown request.
   */
  @Test
  public void testRecordPaidReportsWhetherTheCorrelationIdNamedAKnownRequest() {
    String email = newEmail("record-paid");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);
    store.recordSessionCreated(requestId, "cs_" + requestId);

    assertTrue("A known request must report the payment as recorded",
        store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId));
    assertEquals(STATUS_PAID, rawStatus(requestId));

    assertTrue("A redelivery of a known request is still 'found' — the status simply does not "
        + "advance a second time", store.recordPaid(requestId, "cus_" + requestId,
        "sub_" + requestId));
    assertEquals(STATUS_PAID, rawStatus(requestId));

    String unknown = MARKER + "never-issued-" + UUID.randomUUID();
    assertFalse("An unknown correlation id must be reported, not silently treated as applied",
        store.recordPaid(unknown, "cus_x", "sub_x"));
    assertNull("And it must certainly not have created a request", rawStatus(unknown));

    assertFalse("A null correlation id names nothing", store.recordPaid(null, "cus_x", "sub_x"));
    assertFalse("A blank correlation id names nothing", store.recordPaid("   ", "cus_x",
        "sub_x"));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 6 — the caller's execution context survives a store call
  // ---------------------------------------------------------------------------------------------

  /**
   * The leak, stated as the property that was missing: a caller that hands the store a context
   * gets that same context back.
   *
   * <p>Asserted with {@code assertSame} rather than by comparing client or user ids, because
   * identity is the only assertion the old code could not have satisfied by accident: the store
   * installs {@code (0,0,0,0)} through
   * {@link OBContext#setOBContext(String, String, String, String)}, which builds a <em>new</em>
   * {@link OBContext} every time. An equal-looking context would therefore still be the wrong
   * object, and a caller whose context happened to be {@code (0,0,0,0)} too would make an
   * id-based assertion pass over a store that restored nothing.
   *
   * <p>All seven entry points are exercised in one test on purpose. The capture/restore lives in a
   * single wrapper, so seven separate tests would assert the same line seven times; what is worth
   * pinning is that no entry point was left out of the wrapper.
   */
  @Test
  public void testEveryStoreMethodGivesTheCallersContextBack() {
    String email = newEmail("ctx-roundtrip");
    String accountId = createAccount(email);
    String requestId = newRequestId();

    setTestUserContext();
    OBContext caller = OBContext.getOBContext();
    assertNotNull("Sanity: the caller must actually hold a context to lose", caller);

    // No plan: this fixture exercises context restoration, which does not read one.
    store.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(null, false, false, false, null));
    assertSame("recordRequested must give the caller's context back", caller,
        OBContext.getOBContext());

    store.recordSessionCreated(requestId, "cs_" + requestId);
    assertSame("recordSessionCreated must give the caller's context back", caller,
        OBContext.getOBContext());

    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);
    assertSame("recordPaid must give the caller's context back", caller,
        OBContext.getOBContext());

    store.find(requestId, email);
    assertSame("find must give the caller's context back", caller, OBContext.getOBContext());

    store.findForAccount(email);
    assertSame("findForAccount must give the caller's context back", caller,
        OBContext.getOBContext());

    store.findActiveForAccountAndClientName(email, ENVIRONMENT);
    assertSame("findActiveForAccountAndClientName must give the caller's context back", caller,
        OBContext.getOBContext());

    store.isPaidFor(requestId, email, ENVIRONMENT);
    assertSame("isPaidFor must give the caller's context back", caller,
        OBContext.getOBContext());

    store.claimForProvisioning(requestId, email);
    assertSame("claimForProvisioning must give the caller's context back", caller,
        OBContext.getOBContext());

    store.findProvisioningAttempt(requestId, email);
    assertSame("findProvisioningAttempt must give the caller's context back", caller,
        OBContext.getOBContext());

    store.recordProvisioned(requestId, null);
    assertSame("recordProvisioned must give the caller's context back", caller,
        OBContext.getOBContext());

    store.recordFailureReason(requestId, "ETP-5045 context round-trip");
    assertSame("recordFailureReason must give the caller's context back", caller,
        OBContext.getOBContext());

    assertEquals("Sanity: giving the context back must not have stopped the work happening as "
        + "system — the request still walked the whole lifecycle", STATUS_PROVISIONED,
        rawStatus(requestId));
  }

  /**
   * {@code null} is a real previous context, not a missing one, and restoring it means restoring
   * <em>no</em> context.
   *
   * <p>This is not a contrived state: the Stripe webhook endpoint is matched before the
   * authentication chain, so on the one path the store was written for there is genuinely no
   * {@link OBContext} on the thread. A restore that turned that into a system context would leave
   * the thread more privileged after the call than before it — the leak inverted, and strictly
   * worse than the leak, because it would look deliberate.
   */
  @Test
  public void testAContextlessCallerIsLeftContextlessRatherThanSystem() {
    String email = newEmail("ctx-none");
    String accountId = createAccount(email);
    String requestId = createRequest(accountId, email);

    OBContext.setOBContext((OBContext) null);
    assertNull("Sanity: this test is about a caller with no context at all",
        OBContext.getOBContext());

    assertNotNull("The store must still do its work without a caller context",
        store.find(requestId, email));
    assertNull("A read path must not hand a contextless caller a system context",
        OBContext.getOBContext());

    store.recordSessionCreated(requestId, "cs_" + requestId);
    assertNull("A write path must not hand a contextless caller a system context either",
        OBContext.getOBContext());

    assertEquals("Sanity: the contextless write must still have been applied", "CREATED",
        rawStatus(requestId));
  }

  /**
   * The restore lives in a {@code finally}, so it must hold on the failure path too — and that is
   * the path where it matters most. A caller whose store call throws goes on to handle the
   * failure: it rolls back, annotates the request, answers the customer. Doing that as system
   * instead of as itself is how a recoverable error turns into a second, unrelated one.
   *
   * <p>Also pins that the restore cannot mask the failure: the exception the body raised is the
   * one that reaches the caller.
   */
  @Test
  public void testTheCallersContextIsRestoredWhenTheStoreCallThrows() {
    setTestUserContext();
    OBContext caller = OBContext.getOBContext();
    assertNotNull("Sanity: the caller must actually hold a context to lose", caller);

    RuntimeException failure = null;
    try {
      // No plan: the call is expected to fail on the unknown account before reaching it.
      store.recordRequested(newRequestId(), UNKNOWN_ACCOUNT_ID, newEmail("ctx-throwing"),
          ENVIRONMENT, new CheckoutRequestStore.RequestOptions(null, false, false, false, null));
    } catch (RuntimeException e) {
      failure = e;
    }
    // Read before cleaning up: rollbackAndClose() must not be what puts the context right.
    OBContext contextAfterFailure = OBContext.getOBContext();

    // The failed flush leaves the session unusable, and @After needs a working one to delete its
    // fixtures. Nothing committed, so this discards the half-written request and nothing else.
    OBDal.getInstance().rollbackAndClose();

    assertNotNull("The fixture must genuinely fail, or this test asserts nothing at all", failure);
    assertSame("A store call that throws must still give the caller's context back — the caller "
        + "handles the failure next, and it must do so as itself", caller, contextAfterFailure);
  }

  // ---------------------------------------------------------------------------------------------
  // Group 7 — ETP-5048: EXPIRED and the onboarding inputs (CheckoutRequestWrites)
  // ---------------------------------------------------------------------------------------------

  /** A request at {@code CREATED} with a fake test-mode session id, as the real flow leaves it. */
  private String createCreatedRequest(String accountId, String email) {
    String requestId = createRequest(accountId, email);
    store.recordSessionCreated(requestId, "cs_test_" + requestId);
    return requestId;
  }

  @Test
  public void testRecordExpiredClosesACreatedRequest() {
    String email = newEmail("expire-created");
    String requestId = createCreatedRequest(createAccount(email), email);

    assertTrue("The conditional update must report that it closed the request",
        writes.recordExpired(requestId));

    CheckoutRequest closed = findCommittedRequest(requestId);
    assertEquals(CheckoutRequestStore.STATUS_EXPIRED, closed.getCheckoutRequestStatus());
    assertNotNull("EXPIRED_AT is stamped", closed.getExpiredAt());
    assertEquals(CheckoutRequestStore.DERIVED_STATUS_EXPIRED,
        store.deriveProvisioningStatus(closed));
  }

  /** The webhook racing the reconciliation: a payment recorded first is never overwritten. */
  @Test
  public void testRecordExpiredNeverClosesAPaidRequest() {
    String email = newEmail("expire-paid");
    String requestId = createPaidRequest(createAccount(email), email);

    assertFalse(writes.recordExpired(requestId));

    assertEquals(STATUS_PAID, rawStatus(requestId));
    assertNull(findCommittedRequest(requestId).getExpiredAt());
  }

  @Test
  public void testRecordExpiredOfAnUnknownRequestClosesNothing() {
    assertFalse(writes.recordExpired(newRequestId()));
  }

  /**
   * EXPIRED sits outside the forward-only rank, so without the guard in {@code advance} every
   * later step would look "forward" of it. A late payment or a provisioning claim must not reopen
   * a closed request.
   */
  /**
   * Review F1: the reconciliation's repairs are left pending, so the commit of their audit row
   * commits them and a failed audit insert (a rollback) discards them. Nothing commits on its own.
   */
  @Test
  public void testPendingRepairsCommitOnlyWithTheCallerAndARollbackDiscardsThem() {
    String email = newEmail("pending-repairs");
    String requestId = createCreatedRequest(createAccount(email), email);

    assertTrue(writes.recordPaidPending(requestId, "cus_" + requestId, "sub_" + requestId));
    OBDal.getInstance().rollbackAndClose();
    assertEquals("A rolled-back payment leaves no trace", "CREATED", rawStatus(requestId));

    assertTrue(writes.recordExpiredPending(requestId));
    OBDal.getInstance().rollbackAndClose();
    assertEquals("A rolled-back close leaves no trace", "CREATED", rawStatus(requestId));

    assertTrue(writes.recordPaidPending(requestId, "cus_" + requestId, "sub_" + requestId));
    OBDal.getInstance().commitAndClose();
    CheckoutRequest paid = findCommittedRequest(requestId);
    assertEquals("PAID", paid.getCheckoutRequestStatus());
    assertNotNull(paid.getPaidAt());
    assertEquals("sub_" + requestId, paid.getStripeSubscription());
    assertFalse("No row, nothing recorded", writes.recordPaidPending(newRequestId(), null, null));
  }

  /** The pending close commits with the caller's commit, and closes a request only once. */
  @Test
  public void testAPendingCloseCommitsWithTheCallerAndOnlyOnce() {
    String email = newEmail("pending-close");
    String requestId = createCreatedRequest(createAccount(email), email);

    assertTrue(writes.recordExpiredPending(requestId));
    OBDal.getInstance().commitAndClose();

    CheckoutRequest closed = findCommittedRequest(requestId);
    assertEquals(CheckoutRequestStore.STATUS_EXPIRED, closed.getCheckoutRequestStatus());
    assertNotNull(closed.getExpiredAt());
    OBDal.getInstance().rollbackAndClose();
    assertFalse("Already closed: nothing to close", writes.recordExpiredPending(requestId));
    OBDal.getInstance().rollbackAndClose();
  }

  /** The shared payment write keeps EXPIRED terminal on the reconciliation's path too. */
  @Test
  public void testAPendingPaymentLeavesAnExpiredRequestExpired() {
    String email = newEmail("pending-paid-expired");
    String requestId = createCreatedRequest(createAccount(email), email);
    assertTrue(writes.recordExpired(requestId));

    assertTrue("The request exists", writes.recordPaidPending(requestId, "cus_" + requestId,
        null));
    OBDal.getInstance().commitAndClose();

    CheckoutRequest request = findCommittedRequest(requestId);
    assertEquals(CheckoutRequestStore.STATUS_EXPIRED, request.getCheckoutRequestStatus());
    assertNull(request.getPaidAt());
  }

  /**
   * A payment recorded twice through the pending path, as a re-run would, keeps its first
   * {@code PAID_AT} and the provider ids it captured first.
   */
  @Test
  public void testAPendingPaymentRecordedTwiceKeepsItsFirstPaidAtAndIds() {
    String email = newEmail("pending-paid-twice");
    String requestId = createCreatedRequest(createAccount(email), email);
    assertTrue(writes.recordPaidPending(requestId, "cus_first", "sub_first_" + requestId));
    OBDal.getInstance().commitAndClose();
    Date firstPaidAt = findCommittedRequest(requestId).getPaidAt();
    assertNotNull(firstPaidAt);
    OBDal.getInstance().rollbackAndClose();

    assertTrue(writes.recordPaidPending(requestId, "cus_second", "sub_second_" + requestId));
    OBDal.getInstance().commitAndClose();

    CheckoutRequest paid = findCommittedRequest(requestId);
    assertEquals(STATUS_PAID, paid.getCheckoutRequestStatus());
    assertEquals(firstPaidAt.getTime(), paid.getPaidAt().getTime());
    assertEquals("cus_first", paid.getStripeCustomer());
    assertEquals("sub_first_" + requestId, paid.getStripeSubscription());
  }

  /** The webhook's recordPaid now writes through the same code: CREATING advances to PAID. */
  @Test
  public void testRecordPaidAdvancesARequestThatNeverGotItsSessionRecorded() {
    String email = newEmail("paid-from-creating");
    String requestId = createRequest(createAccount(email), email);

    assertTrue(store.recordPaid(requestId, "cus_" + requestId, null));

    CheckoutRequest paid = findCommittedRequest(requestId);
    assertEquals(STATUS_PAID, paid.getCheckoutRequestStatus());
    assertNotNull(paid.getPaidAt());
    assertEquals("cus_" + requestId, paid.getStripeCustomer());
  }

  @Test
  public void testAnExpiredRequestIsTerminal() {
    String email = newEmail("expired-terminal");
    String accountId = createAccount(email);
    String requestId = createCreatedRequest(accountId, email);
    assertTrue(writes.recordExpired(requestId));

    store.recordPaid(requestId, "cus_" + requestId, "sub_" + requestId);
    assertEquals("A late payment must not reopen it", CheckoutRequestStore.STATUS_EXPIRED,
        rawStatus(requestId));
    assertNull(findCommittedRequest(requestId).getPaidAt());

    assertFalse("An expired request cannot be claimed",
        store.claimForProvisioning(requestId, accountId, email));
    assertEquals(CheckoutRequestStore.STATUS_EXPIRED, rawStatus(requestId));
    assertFalse("A second close finds nothing to close", writes.recordExpired(requestId));
  }

  @Test
  public void testOnboardingInputsAreRecordedNormalized() {
    String email = newEmail("inputs-normalized");
    String requestId = createCreatedRequest(createAccount(email), email);

    writes.recordOnboardingInputs(requestId, " es_ES ", "usd", "ar");

    CheckoutRequest request = findCommittedRequest(requestId);
    assertEquals("es_ES", request.getLanguage());
    assertEquals("USD", request.getCurrency());
    assertEquals("AR", request.getCountry());
    assertEquals("Recording inputs never changes the status", "CREATED",
        request.getCheckoutRequestStatus());
  }

  @Test
  public void testTheFirstWriteOfAnOnboardingInputWins() {
    String email = newEmail("inputs-first-wins");
    String requestId = createCreatedRequest(createAccount(email), email);

    writes.recordOnboardingInputs(requestId, "es_ES", null, null);
    writes.recordOnboardingInputs(requestId, "fr_FR", "GBP", "FR");

    CheckoutRequest request = findCommittedRequest(requestId);
    assertEquals("The first language stays", "es_ES", request.getLanguage());
    assertEquals("An empty column is filled by a later write", "GBP", request.getCurrency());
    assertEquals("FR", request.getCountry());
  }

  @Test
  public void testMalformedOnboardingInputsAreDropped() {
    String email = newEmail("inputs-malformed");
    String requestId = createCreatedRequest(createAccount(email), email);

    TestLogCapture log = TestLogCapture.attachTo(CheckoutRequestWrites.class, Level.WARN);
    try {
      writes.recordOnboardingInputs(requestId, "Spanish", "EURO", "ESP");
      writes.recordOnboardingInputs(requestId, "ES_es", "€", "1");

      // Never silent, and never the raw value: the field and the length only.
      List<String> warnings = log.messagesAt(Level.WARN);
      assertEquals(warnings.toString(), 6, warnings.size());
      assertTrue(warnings.get(0), warnings.get(0).startsWith(
          "[BILLING-RECONCILE] Checkout request " + requestId
              + ": the language sent with the purchase is malformed (7 characters)"));
      assertTrue(warnings.toString(), warnings.stream().noneMatch(line -> line.contains("Spanish")
          || line.contains("EURO") || line.contains("€")));
    } finally {
      log.detach();
    }

    CheckoutRequest request = findCommittedRequest(requestId);
    assertNull(request.getLanguage());
    assertNull(request.getCurrency());
    assertNull(request.getCountry());
  }

  @Test
  public void testOnboardingInputsForAnUnknownRequestAreANoOp() {
    // Best effort on the purchase path: it must neither throw nor create a row, but the row was
    // created moments earlier in the same purchase, so not finding it is logged.
    String requestId = newRequestId();
    TestLogCapture log = TestLogCapture.attachTo(CheckoutRequestWrites.class, Level.WARN);
    try {
      writes.recordOnboardingInputs(requestId, "es_ES", "EUR", "ES");

      assertEquals(List.of("[BILLING-RECONCILE] Checkout request " + requestId + " not found "
          + "while recording its onboarding inputs; a server-side provisioning will fall back to "
          + "the draft or the defaults"), log.messagesAt(Level.WARN));
    } finally {
      log.detach();
    }
    assertNull(findCommittedRequest(requestId));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 8 — ETP-5048: the fence of a paid re-subscription
  // ---------------------------------------------------------------------------------------------

  /** How often the main thread looks whether the second caller is blocked yet. */
  private static final long POLL_MILLIS = 50L;
  /** Every wait of the race is bounded by this, so a regression cannot hang the build. */
  private static final long BOUND_SECONDS = 60L;

  /** A re-subscription of client {@code 0}, paid and not applied: what the webhook would apply. */
  private String createPaidReactivation(String accountId, String email) {
    String requestId = newRequestId();
    store.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(null, false, false, false, null, ZERO));
    store.recordSessionCreated(requestId, "cs_test_" + requestId);
    // No subscription id: a provisioned row without one is never a reconciliation candidate.
    store.recordPaid(requestId, "cus_" + requestId, null);
    return requestId;
  }

  /** Runs {@code body} in this thread's own DAL session, closed and cleared afterwards. */
  private static <T> T inOwnSession(Callable<T> body) throws Exception {
    try {
      return body.call();
    } finally {
      try {
        OBDal.getInstance().rollbackAndClose();
      } finally {
        OBContext.setOBContext((OBContext) null);
      }
    }
  }

  /** Bounds this transaction's wait for a row lock, so a broken fence fails instead of hanging. */
  @SuppressWarnings("rawtypes")
  private static void boundTheLockWait() {
    NativeQuery statement = OBDal.getInstance().getSession()
        .createNativeQuery("SET LOCAL lock_timeout = '" + BOUND_SECONDS + "s'");
    statement.executeUpdate();
  }

  @Test
  public void testAPaidReactivationIsFoundOnlyWhileItIsPending() {
    String email = newEmail("reactivation-pending");
    String accountId = createAccount(email);
    String requestId = createPaidReactivation(accountId, email);
    String newPurchase = createPaidRequest(accountId, email);

    CheckoutRequest found = store.findPaidReactivation(requestId);

    assertNotNull(found);
    assertEquals(requestId, found.getRequest());
    OBDal.getInstance().rollbackAndClose(); // releases the lock the lookup took
    assertNull("A new purchase is provisioned, never applied", store.findPaidReactivation(
        newPurchase));
    OBDal.getInstance().rollbackAndClose();
    store.recordProvisioned(requestId, ZERO);
    assertNull("Once applied it is no longer pending", store.findPaidReactivation(requestId));
  }

  /** @return the PostgreSQL backend of this thread's DAL session */
  @SuppressWarnings("rawtypes")
  private static int backendPid() {
    NativeQuery query = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT pg_backend_pid()");
    return ((Number) query.uniqueResult()).intValue();
  }

  /**
   * @return whether that backend is waiting on a lock now, read in a fresh transaction each time:
   *     PostgreSQL keeps one snapshot of {@code pg_stat_activity} per transaction
   */
  @SuppressWarnings("rawtypes")
  private static boolean isWaitingOnALock(int pid) {
    try {
      NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(
          "SELECT count(*) FROM pg_stat_activity WHERE pid = :pid AND wait_event_type = 'Lock'");
      query.setParameter("pid", pid);
      return ((Number) query.uniqueResult()).longValue() > 0L;
    } finally {
      OBDal.getInstance().rollbackAndClose();
    }
  }

  /**
   * Waits, bounded, until the second caller's backend is blocked on a lock. Polls with a bounded
   * {@code Future.get}, which also fails the test at once if that caller returned without waiting.
   */
  private static void awaitBlockedOnTheLock(Future<?> contender, int pid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
    while (!isWaitingOnALock(pid)) {
      assertTrue("The second caller never blocked on the row lock",
          System.nanoTime() < deadline);
      try {
        contender.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
        fail("The second caller returned while the first still held the row lock");
      } catch (TimeoutException stillRunning) {
        // not blocked yet, or blocked and not yet visible: look again
      }
    }
  }

  /**
   * The webhook and the reconciliation race on one paid re-subscription. The first takes the row
   * lock and holds it; the second is shown, in {@code pg_stat_activity}, to be blocked on it. Only
   * then does the first apply the re-subscription and commit, and the second re-reads the row as
   * {@code PROVISIONED} and gets nothing to apply. No step rests on a sleep: each hand-over is a
   * latch or a database state, and every wait is bounded.
   */
  @Test
  public void testTwoCallersRacingOnAPaidReactivationApplyItExactlyOnce() throws Exception {
    String email = newEmail("reactivation-race");
    String accountId = createAccount(email);
    String requestId = createPaidReactivation(accountId, email);
    assertEquals(STATUS_PAID, rawStatus(requestId));
    OBDal.getInstance().rollbackAndClose();

    CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
    CountDownLatch secondIsBlocked = new CountDownLatch(1);
    CountDownLatch secondHasItsBackend = new CountDownLatch(1);
    AtomicInteger secondPid = new AtomicInteger();
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> webhook = callers.submit(() -> inOwnSession(() -> {
        boundTheLockWait();
        CheckoutRequest found = store.findPaidReactivation(requestId);
        firstHoldsTheLock.countDown();
        if (found == null) {
          return false;
        }
        assertTrue("The main thread never saw the second caller blocked",
            secondIsBlocked.await(BOUND_SECONDS, TimeUnit.SECONDS));
        store.recordProvisioned(requestId, ZERO); // commits, releasing the lock
        return true;
      }));
      assertTrue("The first caller must take the lock",
          firstHoldsTheLock.await(BOUND_SECONDS, TimeUnit.SECONDS));
      Future<Boolean> reconciler = callers.submit(() -> inOwnSession(() -> {
        boundTheLockWait();
        secondPid.set(backendPid());
        secondHasItsBackend.countDown();
        // Null only once it reads PROVISIONED, which exists only after the first commit.
        return store.findPaidReactivation(requestId) == null;
      }));
      assertTrue(secondHasItsBackend.await(BOUND_SECONDS, TimeUnit.SECONDS));

      awaitBlockedOnTheLock(reconciler, secondPid.get());
      secondIsBlocked.countDown();

      assertTrue("The first caller applies it", webhook.get(BOUND_SECONDS, TimeUnit.SECONDS));
      assertTrue("The second caller finds it applied and does nothing",
          reconciler.get(BOUND_SECONDS, TimeUnit.SECONDS));
    } finally {
      secondIsBlocked.countDown();
      callers.shutdownNow();
      callers.awaitTermination(BOUND_SECONDS, TimeUnit.SECONDS);
    }
    assertEquals(STATUS_PROVISIONED, rawStatus(requestId));
  }

  // ---------------------------------------------------------------------------------------------
  // Group 9 — ETP-5051: findSubscriptionForAccountClient, one environment's own subscription
  // ---------------------------------------------------------------------------------------------

  /**
   * Two billable purchases of one account, each provisioning its own environment, plus an
   * unpaid one for the first environment recorded after both: each environment resolves to its own
   * paid purchase, whichever was recorded last.
   */
  @Test
  public void testClientLookupFindsTheAskedEnvironmentsOwnPurchase() {
    String[] clients = twoExistingTenantClientIds();
    String email = newEmail("multi-env");
    String accountId = createAccount(email);
    String firstEnvPurchase = createPaidRequest(accountId, email);
    forceCreatedClient(firstEnvPurchase, clients[0]);
    String secondEnvPurchase = createPaidRequest(accountId, email);
    forceCreatedClient(secondEnvPurchase, clients[1]);
    String unpaid = createRequest(accountId, email);
    forceCreatedClient(unpaid, clients[0]);

    CheckoutRequest first = store.findSubscriptionForAccountClient(accountId, email, clients[0]);
    assertNotNull("The first environment's own purchase must be found", first);
    assertEquals(firstEnvPurchase, first.getRequest());
    assertEquals("cus_" + firstEnvPurchase, first.getStripeCustomer());

    CheckoutRequest second = store.findSubscriptionForAccountClient(accountId,
        email.toUpperCase(), clients[1]);
    assertNotNull("The second environment's own purchase must be found", second);
    assertEquals(secondEnvPurchase, second.getRequest());
  }

  /**
   * A client id that is another account's environment finds nothing, even though that
   * environment has a billable purchase: the account predicates stay in the query.
   */
  @Test
  public void testClientLookupNeverResolvesAnotherAccountsEnvironment() {
    String[] clients = twoExistingTenantClientIds();
    String ownerEmail = newEmail("env-owner");
    String ownerId = createAccount(ownerEmail);
    String ownerPurchase = createPaidRequest(ownerId, ownerEmail);
    forceCreatedClient(ownerPurchase, clients[0]);
    String otherEmail = newEmail("env-other");
    String otherId = createAccount(otherEmail);
    String otherPurchase = createPaidRequest(otherId, otherEmail);
    forceCreatedClient(otherPurchase, clients[1]);

    assertNull("Another account's environment must resolve to nothing",
        store.findSubscriptionForAccountClient(otherId, otherEmail, clients[0]));
    assertNull("The owner's id with another account's e-mail must resolve to nothing",
        store.findSubscriptionForAccountClient(ownerId, otherEmail, clients[0]));
    CheckoutRequest own = store.findSubscriptionForAccountClient(ownerId, ownerEmail, clients[0]);
    assertNotNull("Baseline: the owner resolves its own environment", own);
    assertEquals(ownerPurchase, own.getRequest());
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
    // No plan: these specs exercise the lifecycle transitions, which do not read one.
    store.recordRequested(requestId, accountId, email, ENVIRONMENT,
        new CheckoutRequestStore.RequestOptions(null, false, false, false, null));
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
  // Committed-state readers and cleanup (DAL — never a native query)
  // ---------------------------------------------------------------------------------------------

  /**
   * @param requestId correlation id
   * @return the committed {@code CHECKOUT_STATUS}
   */
  private String rawStatus(String requestId) {
    CheckoutRequest request = findCommittedRequest(requestId);
    return request == null ? null : request.getCheckoutRequestStatus();
  }

  /**
   * @param requestId correlation id
   * @return the committed {@code PROVISIONING_ATTEMPTS}
   */
  private long rawAttempts(String requestId) {
    CheckoutRequest request = findCommittedRequest(requestId);
    return request == null || request.getProvisioningAttempts() == null ? 0L
        : request.getProvisioningAttempts();
  }

  /**
   * @param requestId correlation id
   * @param column a timestamp column name
   * @return the committed value, or null
   */
  private Timestamp rawTimestamp(String requestId, String column) {
    CheckoutRequest request = findCommittedRequest(requestId);
    if (request == null) {
      return null;
    }
    Date value = "PAID_AT".equals(column) ? request.getPaidAt() : request.getProvisioningAt();
    return value == null ? null : new Timestamp(value.getTime());
  }

  private CheckoutRequest findCommittedRequest(String requestId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as request where request.request = :requestId");
      query.setNamedParameter("requestId", requestId);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Backdates one committed timestamp column, so a first-write-wins assertion has an expected
   * value that "now" can never coincide with. Commits and closes the session for the same reason
   * {@link #forceStatus} does: the next store call must load the forced value rather than the
   * entity this session still holds.
   *
   * @param requestId correlation id
   * @param column a timestamp column name; always a literal from this class
   * @param value the instant to force
   */
  private void forceTimestamp(String requestId, String column, Timestamp value) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findCommittedRequest(requestId);
      if ("PAID_AT".equals(column)) {
        request.setPaidAt(value);
      } else {
        request.setProvisioningAt(value);
      }
      OBDal.getInstance().save(request);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
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
  private void forceStatus(String requestId, String status) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findCommittedRequest(requestId);
      request.setCheckoutRequestStatus(status);
      OBDal.getInstance().save(request);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Two existing tenant clients (never System) to point fixture purchases at. Only referenced by
   * the purchase's {@code AD_CLIENT_CREATED_ID}, never written, so cleanup leaves them untouched.
   */
  private String[] twoExistingTenantClientIds() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      OBQuery<Client> query = OBDal.getInstance().createQuery(Client.class,
          "as client where client.id <> :system order by client.id");
      query.setNamedParameter("system", ZERO);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setFilterOnActive(false);
      query.setMaxResult(2);
      List<Client> clients = query.list();
      assertEquals("The fixture needs two tenant clients in the database", 2, clients.size());
      return new String[] { clients.get(0).getId(), clients.get(1).getId() };
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Points a committed request at the environment it provisioned, as onboarding does once the
   * client exists. Commits and closes for the same reason {@link #forceStatus} does.
   */
  private void forceCreatedClient(String requestId, String clientId) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findCommittedRequest(requestId);
      request.setCreatedClient(OBDal.getInstance().get(Client.class, clientId));
      OBDal.getInstance().save(request);
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void forceFailureReason(String requestId, String reason) {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findCommittedRequest(requestId);
      request.setFailureReason(reason);
      OBDal.getInstance().save(request);
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
  private void deleteCommittedFixtures() {
    OBContext.setOBContext(ZERO, ZERO, ZERO, ZERO);
    OBContext.setAdminMode(true);
    try {
      OBQuery<CheckoutRequest> requests = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as request where request.request like :marker");
      requests.setNamedParameter("marker", MARKER + "%");
      requests.setFilterOnReadableClients(false);
      requests.setFilterOnReadableOrganization(false);
      List<CheckoutRequest> requestRows = requests.list();
      for (CheckoutRequest request : requestRows) {
        OBDal.getInstance().remove(request);
      }

      OBQuery<Account> accounts = OBDal.getInstance().createQuery(Account.class,
          "as account where account.email like :marker");
      accounts.setNamedParameter("marker", MARKER + "%");
      accounts.setFilterOnReadableClients(false);
      accounts.setFilterOnReadableOrganization(false);
      for (Account account : accounts.list()) {
        OBDal.getInstance().remove(account);
      }

      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
