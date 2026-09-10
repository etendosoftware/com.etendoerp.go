/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Durable persistence for hosted-checkout requests, replacing the correlation half of the
 * in-memory {@link CheckoutPaymentRegistry}.
 *
 * <p>Every method opens its own system context ({@code "0","0","0","0"} plus admin mode) and
 * restores it in a {@code finally}. This is deliberate rather than delegated to callers: the
 * webhook handler is matched before the authentication chain and therefore has no
 * {@link OBContext} at all, while the status and paywall callers already hold one. Opening it
 * here makes the store safe from both.
 *
 * <p>Rows are stored at client and organization {@code 0}, matching {@code ETGO_ACCOUNT} and the
 * table's {@code ACCESSLEVEL=4}. That also means DAL row-level security has nothing to filter on,
 * so every read disables the readable-client and readable-organization filters explicitly and
 * carries its own account predicate instead.
 *
 * <p><b>The lifecycle only ever moves forward.</b> Statuses are ranked and a transition to an
 * equal or lower rank is silently ignored as a duplicate, never applied. Phase timestamps are first-write-wins
 * for the same reason: {@code PAID_AT} must keep meaning "when the payment was confirmed", not
 * "when we last heard about it", or the staleness thresholds behind {@code DERIVED_STATUS} become
 * meaningless. Stripe re-delivers events freely and the browser can re-enter the flow on reload,
 * so both are ordinary occurrences rather than error cases.
 */
public class CheckoutRequestStore {
  private static final Logger log = LogManager.getLogger();

  private static final String ZERO_ID = "0";

  static final String STATUS_CREATING = "CREATING";
  static final String STATUS_CREATED = "CREATED";
  static final String STATUS_PAID = "PAID";
  static final String STATUS_PROVISIONING = "PROVISIONING";
  static final String STATUS_PROVISIONED = "PROVISIONED";

  /** Lifecycle order. A request may only move to a strictly later element. */
  private static final List<String> LIFECYCLE = Arrays.asList(STATUS_CREATING, STATUS_CREATED,
      STATUS_PAID, STATUS_PROVISIONING, STATUS_PROVISIONED);

  /**
   * Records the intent to start a checkout, before the provider is contacted.
   *
   * <p>Commits on purpose: the row must survive a crash during the outbound Stripe call, otherwise
   * a session can exist at the provider that nothing on this side can name. A row left in
   * {@code CREATING} is always safe to expire, because the {@code checkoutUrl} only reaches the
   * browser after this method returns — nobody can have paid for it.
   *
   * @param requestId server-generated correlation id, the {@code REQUEST_ID} column and not the
   *     primary key
   * @param accountId {@code ETGO_ACCOUNT_ID} of the authenticated account
   * @param accountEmail authenticated account email, denormalised for the tenancy check
   * @param clientName requested environment name
   */
  public void recordRequested(String requestId, String accountId, String accountEmail,
      String clientName) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = OBProvider.getInstance().get(CheckoutRequest.class);
      request.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
      request.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
      request.setRequest(StringUtils.trimToEmpty(requestId));
      request.setEtendoGoAccount(OBDal.getInstance().get(Account.class, accountId));
      request.setAccountEmail(StringUtils.trimToEmpty(accountEmail));
      request.setClientName(StringUtils.trimToEmpty(clientName));
      request.setCheckoutRequestStatus(STATUS_CREATING);
      request.setCreatingAt(new Date());
      request.setProvisioningAttempts(0L);
      OBDal.getInstance().save(request);
      flushAndCommit();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Attaches the provider session id once Stripe has accepted the request.
   *
   * <p>The session id is recorded even when the status has already moved past {@code CREATED} —
   * it is the reconciliation anchor and losing it would be worse than a stale status. Only the
   * status transition itself is guarded.
   *
   * @param requestId server-generated correlation id
   * @param stripeSessionId the {@code cs_...} Checkout Session id
   */
  public void recordSessionCreated(String requestId, String stripeSessionId) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findByRequestId(requestId);
      if (request == null) {
        log.error("No checkout request found for '{}' while recording the provider session",
            requestId);
        return;
      }
      if (request.getStripeSession() == null && StringUtils.isNotBlank(stripeSessionId)) {
        request.setStripeSession(stripeSessionId);
      }
      if (advance(request, STATUS_CREATED) && request.getCreatedAt() == null) {
        request.setCreatedAt(new Date());
      }
      OBDal.getInstance().save(request);
      flushAndCommit();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Confirms payment from a signature-verified webhook, capturing the provider customer and
   * subscription ids at the one moment they arrive alongside the correlation id.
   *
   * <p>Idempotent by construction: a Stripe retry, or a late
   * {@code checkout.session.async_payment_succeeded} arriving after provisioning already ran,
   * leaves the status and {@code PAID_AT} untouched.
   *
   * @param requestId correlation id read from {@code metadata[request_id]}
   * @param stripeCustomerId {@code cus_...}, or null in payment mode
   * @param stripeSubscriptionId {@code sub_...}, or null in payment mode
   */
  public void recordPaid(String requestId, String stripeCustomerId, String stripeSubscriptionId) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findByRequestId(requestId);
      if (request == null) {
        log.error("No checkout request found for '{}' while recording a confirmed payment",
            requestId);
        return;
      }
      // Captured regardless of the status transition: these are the join keys every future
      // subscription and invoice event arrives on, and only this event carries them.
      if (request.getStripeCustomer() == null && StringUtils.isNotBlank(stripeCustomerId)) {
        request.setStripeCustomer(stripeCustomerId);
      }
      if (request.getStripeSubscription() == null && StringUtils.isNotBlank(stripeSubscriptionId)) {
        request.setStripeSubscription(stripeSubscriptionId);
      }
      if (advance(request, STATUS_PAID) && request.getPaidAt() == null) {
        request.setPaidAt(new Date());
      }
      OBDal.getInstance().save(request);
      flushAndCommit();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Finds a checkout request only when its recorded account email matches the caller's.
   *
   * <p>That comparison is the only tenancy boundary in the payment subsystem and guards two
   * separate properties: non-disclosure at the status endpoint, and payment authorisation at the
   * paywall. It is expressed in the query rather than checked afterwards, so a mismatch is
   * indistinguishable from an unknown request id.
   *
   * @param requestId server-generated correlation id
   * @param accountEmail authenticated account email
   * @return the matching request, or null
   */
  public CheckoutRequest find(String requestId, String accountEmail) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      if (StringUtils.isBlank(requestId) || StringUtils.isBlank(accountEmail)) {
        return null;
      }
      OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as cr where cr.request = :requestId and lower(cr.accountEmail) = lower(:accountEmail)");
      query.setNamedParameter("requestId", StringUtils.trimToEmpty(requestId));
      query.setNamedParameter("accountEmail", StringUtils.trimToEmpty(accountEmail));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Returns whether a confirmed payment backs this request, account and environment name.
   *
   * <p>Accepts every status at or past {@code PAID}, so a resumed onboarding still passes the
   * paywall rather than being asked to pay a second time for an environment already bought.
   *
   * @param requestId correlation id supplied as the onboarding payment token
   * @param accountEmail authenticated account email
   * @param clientName requested environment name, when available
   * @return true when a paid request matches all three
   */
  public boolean isPaidFor(String requestId, String accountEmail, String clientName) {
    CheckoutRequest request = find(requestId, accountEmail);
    if (request == null) {
      return false;
    }
    boolean paid = rank(request.getCheckoutRequestStatus()) >= rank(STATUS_PAID);
    return paid && (StringUtils.isBlank(clientName)
        || StringUtils.equalsIgnoreCase(request.getClientName(),
            StringUtils.trimToEmpty(clientName)));
  }

  /**
   * Atomically claims a paid request for provisioning.
   *
   * <p>The one method that does not read-then-write: a conditional bulk update is what makes the
   * claim atomic without an explicit row lock, and the affected-row count is the answer. Two
   * concurrent onboarding calls for the same request — the reload-during-provisioning case — mean
   * exactly one caller sees {@code true}.
   *
   * <p>The HQL names the entity as {@link CheckoutRequest#ENTITY_NAME} rather than the Java simple
   * name. Etendo registers module entities under their table name, so {@code update CheckoutRequest}
   * refers to nothing and throws at runtime — which lets both callers fall through into
   * provisioning and deadlock against each other, the exact outcome this method exists to prevent.
   *
   * <p>The {@code = PAID} predicate is itself the monotonic guard: a request already
   * {@code PROVISIONING} or {@code PROVISIONED} matches nothing and cannot be re-claimed.
   * {@code PROVISIONING_AT} is first-write-wins via {@code coalesce} so it keeps meaning "since
   * when has this been stuck"; {@code PROVISIONING_ATTEMPTS} carries the retry count instead.
   *
   * @param requestId correlation id
   * @param accountEmail authenticated account email
   * @return true when this caller won the claim
   */
  public boolean claimForProvisioning(String requestId, String accountEmail) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      if (StringUtils.isBlank(requestId) || StringUtils.isBlank(accountEmail)) {
        return false;
      }
      int claimed = OBDal.getInstance()
          .getSession()
          .createQuery("update " + CheckoutRequest.ENTITY_NAME + " cr"
              + "   set cr.checkoutRequestStatus = :provisioning,"
              + "       cr.provisioningAt = coalesce(cr.provisioningAt, :now),"
              + "       cr.provisioningAttempts = cr.provisioningAttempts + 1,"
              + "       cr.updated = :now"
              + " where cr.request = :requestId"
              + "   and lower(cr.accountEmail) = lower(:accountEmail)"
              + "   and cr.checkoutRequestStatus = :paid")
          .setParameter("provisioning", STATUS_PROVISIONING)
          .setParameter("paid", STATUS_PAID)
          .setParameter("now", new Date())
          .setParameter("requestId", StringUtils.trimToEmpty(requestId))
          .setParameter("accountEmail", StringUtils.trimToEmpty(accountEmail))
          .executeUpdate();
      flushAndCommit();
      return claimed == 1;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Marks a request as fully provisioned and links the environment it produced.
   *
   * @param requestId correlation id
   * @param createdClientId {@code AD_CLIENT_ID} of the provisioned environment
   */
  public void recordProvisioned(String requestId, String createdClientId) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findByRequestId(requestId);
      if (request == null) {
        log.error("No checkout request found for '{}' while recording a provisioned environment",
            requestId);
        return;
      }
      if (request.getCreatedClient() == null && StringUtils.isNotBlank(createdClientId)) {
        request.setCreatedClient(OBDal.getInstance().get(Client.class, createdClientId));
      }
      if (advance(request, STATUS_PROVISIONED) && request.getProvisionedAt() == null) {
        request.setProvisionedAt(new Date());
      }
      OBDal.getInstance().save(request);
      flushAndCommit();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Best-effort diagnostic annotation.
   *
   * <p>Deliberately never changes the status and never propagates: the caller is already on a
   * failure path, and a failed annotation must not turn a recoverable problem into a second one.
   * A request that never receives a reason is still visible through {@code DERIVED_STATUS}, which
   * derives staleness from the absence of forward progress rather than from anything written here.
   *
   * @param requestId correlation id
   * @param reason operationally safe failure reason, truncated to the column width
   */
  public void recordFailureReason(String requestId, String reason) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      CheckoutRequest request = findByRequestId(requestId);
      if (request == null) {
        return;
      }
      request.setFailureReason(StringUtils.abbreviate(StringUtils.trimToEmpty(reason), 255));
      OBDal.getInstance().save(request);
      flushAndCommit();
    } catch (RuntimeException e) {
      log.error("Could not record the failure reason for checkout request '{}'", requestId, e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Applies a status transition only when it moves the request strictly forward.
   *
   * <p>A target the request has already reached means the same step arrived twice — Stripe
   * redelivering an event, or the browser re-entering the upgrade flow on reload. Both are
   * ordinary occurrences on this path, so the transition is silently ignored rather than logged:
   * the first delivery already did the work, and warning about the rest would only add noise to
   * the very case the design expects.
   *
   * @param request the request being advanced
   * @param target the status to move to
   * @return true when the transition was applied
   */
  private boolean advance(CheckoutRequest request, String target) {
    if (rank(target) <= rank(request.getCheckoutRequestStatus())) {
      return false;
    }
    request.setCheckoutRequestStatus(target);
    return true;
  }

  /**
   * Position of a status in the lifecycle, or -1 when unknown.
   *
   * @param status a checkout status
   * @return its lifecycle rank
   */
  private int rank(String status) {
    return LIFECYCLE.indexOf(StringUtils.trimToEmpty(status));
  }

  /**
   * Looks a request up by correlation id alone, for the write paths that have already established
   * who the caller is. Read paths must use {@link #find(String, String)} instead, which carries the
   * account predicate.
   *
   * <p>{@code REQUEST_ID} is a unique column and NOT the primary key, so this is a query and never
   * {@code OBDal.get(...)} — a primary-key lookup would compare a 36-character hyphenated UUID
   * against a 32-character Etendo id column and silently return null every time.
   *
   * @param requestId server-generated correlation id
   * @return the matching request, or null
   */
  private CheckoutRequest findByRequestId(String requestId) {
    if (StringUtils.isBlank(requestId)) {
      return null;
    }
    OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
        "as cr where cr.request = :requestId");
    query.setNamedParameter("requestId", StringUtils.trimToEmpty(requestId));
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private void flushAndCommit() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }
}
