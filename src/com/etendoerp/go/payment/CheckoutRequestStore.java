/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

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
import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Durable persistence for hosted-checkout requests. Together with {@link BillingEventStore}
 * ({@code ETGO_BILLING_EVENT}) it replaces the in-memory {@code CheckoutPaymentRegistry} that
 * ETP-5045 retired: this table holds the payment correlation, that one the webhook idempotency.
 *
 * <p>Every method runs its body through {@link #runAsSystem}, which opens the system context
 * ({@code "0","0","0","0"} plus admin mode) and gives the caller's own context back in a
 * {@code finally}. Opening it here is deliberate rather than delegated to callers: the webhook
 * handler is matched before the authentication chain and therefore has no {@link OBContext} at
 * all, while the status and paywall callers already hold one. Giving it back is what makes the
 * store safe to call from the middle of another unit of work: {@code applyPaidUpgradeSideEffects}
 * calls in after onboarding has installed its provisioning context, and every later step still
 * depends on that context.
 *
 * <p>Rows are stored at client and organization {@code 0}, matching {@code ETGO_ACCOUNT} and the
 * table's {@code ACCESSLEVEL=4}. That also means DAL row-level security has nothing to filter on,
 * so every read disables the readable-client and readable-organization filters explicitly and
 * carries its own account predicate instead.
 *
 * <p><b>The lifecycle only ever moves forward, except for a fenced provisioning retry.</b> Statuses
 * are ranked and a transition to an equal or lower rank is silently ignored as a duplicate. A
 * stale {@code PROVISIONING} claim may be renewed in place, but its attempt token changes before
 * any retry can complete. Phase timestamps are first-write-wins except for that lease renewal:
 * {@code PAID_AT} must keep meaning "when the payment was confirmed", while
 * {@code PROVISIONING_AT} identifies the current lease. Stripe re-delivers events freely and the
 * browser can re-enter the flow on reload, so both are ordinary occurrences rather than errors.
 */
public class CheckoutRequestStore {
  private static final Logger log = LogManager.getLogger();

  private static final String ZERO_ID = "0";
  /** Name of the correlation-id parameter bound by every query keyed on {@code REQUEST_ID}. */
  private static final String PARAM_REQUEST_ID = "requestId";
  private static final String PARAM_ACCOUNT_EMAIL = "accountEmail";
  private static final String HQL_UPDATE = "update ";
  private static final String HQL_PROVISIONING = "provisioning";

  static final String STATUS_CREATING = "CREATING";
  static final String STATUS_CREATED = "CREATED";
  static final String STATUS_PAID = "PAID";
  static final String STATUS_PROVISIONING = "PROVISIONING";
  static final String STATUS_PROVISIONED = "PROVISIONED";

  private static final String PROVISIONING_LEASE_MINUTES_PROPERTY =
      "etendo.go.billing.provisioning.lease.minutes";
  private static final String PROVISIONING_LEASE_MINUTES_ENV =
      "ETGO_BILLING_PROVISIONING_LEASE_MINUTES";
  private static final long DEFAULT_PROVISIONING_LEASE_MINUTES = 30L;

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
   * @param plan the catalog row being bought, kept so the subscription opened after payment
   *     records the plan the buyer actually saw rather than whatever is current by then
   */
  public void recordRequested(String requestId, String accountId, String accountEmail,
      String clientName, Plan plan) {
    runAsSystem(() -> {
      CheckoutRequest request = OBProvider.getInstance().get(CheckoutRequest.class);
      request.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
      request.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
      request.setRequest(StringUtils.trimToEmpty(requestId));
      request.setEtendoGoAccount(OBDal.getInstance().get(Account.class, accountId));
      request.setAccountEmail(StringUtils.trimToEmpty(accountEmail));
      request.setClientName(StringUtils.trimToEmpty(clientName));
      request.setPlan(plan);
      request.setCheckoutRequestStatus(STATUS_CREATING);
      request.setCreatingAt(new Date());
      request.setProvisioningAttempts(0L);
      OBDal.getInstance().save(request);
      flushAndCommit();
    });
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
    runAsSystem(() -> {
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
    });
  }

  /**
   * Confirms payment from a signature-verified webhook, capturing the provider customer and
   * subscription ids at the one moment they arrive alongside the correlation id.
   *
   * <p>Idempotent by construction: a Stripe retry, or a late
   * {@code checkout.session.async_payment_succeeded} arriving after provisioning already ran,
   * leaves the status and {@code PAID_AT} untouched.
   *
   * <p>Returns whether the correlation id named a request at all. An event that references a
   * request this instance never issued is not an error here — it is ordinary in a shared Stripe
   * test account — but the caller must be able to tell it apart from a payment actually recorded,
   * or it would mark the event applied while nothing was.
   *
   * @param requestId correlation id read from {@code metadata[request_id]}
   * @param stripeCustomerId {@code cus_...}, or null in payment mode
   * @param stripeSubscriptionId {@code sub_...}, or null in payment mode
   * @return true when the request was found and the payment recorded on it
   */
  public boolean recordPaid(String requestId, String stripeCustomerId,
      String stripeSubscriptionId) {
    return runAsSystem(() -> {
      CheckoutRequest request = findByRequestId(requestId);
      if (request == null) {
        log.error("No checkout request found for '{}' while recording a confirmed payment",
            requestId);
        return false;
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
      return true;
    });
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
    return runAsSystem(() -> {
      if (StringUtils.isBlank(requestId) || StringUtils.isBlank(accountEmail)) {
        return null;
      }
      OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as cr where cr.request = :requestId and lower(cr.accountEmail) = lower(:accountEmail)");
      query.setNamedParameter(PARAM_REQUEST_ID, StringUtils.trimToEmpty(requestId));
      query.setNamedParameter(PARAM_ACCOUNT_EMAIL, StringUtils.trimToEmpty(accountEmail));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return query.uniqueResult();
    });
  }

  /**
   * Lists recent purchase attempts for one account without exposing provider fields.
   * @param accountEmail authenticated account email
   * @return recent checkout requests for the account
   */
  public List<CheckoutRequest> findForAccount(String accountEmail) {
    return runAsSystem(() -> {
      if (StringUtils.isBlank(accountEmail)) {
        return List.of();
      }
      OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as cr where lower(cr.accountEmail) = lower(:" + PARAM_ACCOUNT_EMAIL + ") order by cr.creationDate desc");
      query.setNamedParameter(PARAM_ACCOUNT_EMAIL, StringUtils.trimToEmpty(accountEmail));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(20);
      return query.list();
    });
  }

  /**
   * Finds an unfinished or paid purchase for the same account and environment name.
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @return the newest matching request, or null when none exists
   */
  public CheckoutRequest findActiveForAccountAndClientName(String accountEmail, String clientName) {
    return runAsSystem(() -> {
      if (StringUtils.isBlank(accountEmail) || StringUtils.isBlank(clientName)) {
        return null;
      }
      OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as cr where lower(cr.accountEmail) = lower(:accountEmail)"
              + " and lower(cr.clientName) = lower(:clientName)"
              + " and cr.checkoutRequestStatus in ('CREATING', 'CREATED', 'PAID', 'PROVISIONING')"
              + " order by cr.creationDate desc");
      query.setNamedParameter(PARAM_ACCOUNT_EMAIL, StringUtils.trimToEmpty(accountEmail));
      query.setNamedParameter("clientName", StringUtils.trimToEmpty(clientName));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return query.uniqueResult();
    });
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
   * <p>The {@code = PAID} predicate is the normal monotonic guard. A request already
   * {@code PROVISIONED} matches nothing; a {@code PROVISIONING} request matches only when its
   * lease is stale. {@code PROVISIONING_AT} is first-write-wins for the initial claim and is
   * renewed only by that stale-lease branch; {@code PROVISIONING_ATTEMPTS} carries the fencing
   * token.
   *
   * @param requestId correlation id
   * @param accountEmail authenticated account email
   * @return true when this caller won the claim
   */
  public boolean claimForProvisioning(String requestId, String accountEmail) {
    return runAsSystem(() -> {
      if (StringUtils.isBlank(requestId) || StringUtils.isBlank(accountEmail)) {
        return false;
      }
      Date now = new Date();
      int claimed = OBDal.getInstance()
          .getSession()
          .createQuery(HQL_UPDATE + CheckoutRequest.ENTITY_NAME + " cr"
              + "   set cr.checkoutRequestStatus = :" + HQL_PROVISIONING + ","
              + "       cr.provisioningAt = coalesce(cr.provisioningAt, :now),"
              + "       cr.provisioningAttempts = cr.provisioningAttempts + 1,"
              + "       cr.updated = :now"
              + " where cr.request = :requestId"
              + "   and lower(cr.accountEmail) = lower(:" + PARAM_ACCOUNT_EMAIL + ")"
              + "   and cr.checkoutRequestStatus = :paid")
          .setParameter(HQL_PROVISIONING, STATUS_PROVISIONING)
          .setParameter("paid", STATUS_PAID)
          .setParameter("now", now)
          .setParameter(PARAM_REQUEST_ID, StringUtils.trimToEmpty(requestId))
          .setParameter(PARAM_ACCOUNT_EMAIL, StringUtils.trimToEmpty(accountEmail))
          .executeUpdate();
      if (claimed == 0) {
        Date staleBefore = new Date(now.getTime() - provisioningLeaseMillis());
        claimed = OBDal.getInstance().getSession()
            .createQuery(HQL_UPDATE + CheckoutRequest.ENTITY_NAME + " cr"
                + "   set cr.provisioningAt = :now,"
                + "       cr.provisioningAttempts = cr.provisioningAttempts + 1,"
                + "       cr.updated = :now"
                + " where cr.request = :requestId"
                + "   and lower(cr.accountEmail) = lower(:" + PARAM_ACCOUNT_EMAIL + ")"
                + "   and cr.checkoutRequestStatus = :" + HQL_PROVISIONING
                + "   and cr.provisioningAt <= :staleBefore")
            .setParameter(HQL_PROVISIONING, STATUS_PROVISIONING)
            .setParameter("now", now)
            .setParameter("staleBefore", staleBefore)
            .setParameter(PARAM_REQUEST_ID, StringUtils.trimToEmpty(requestId))
            .setParameter(PARAM_ACCOUNT_EMAIL, StringUtils.trimToEmpty(accountEmail))
            .executeUpdate();
      }
      flushAndCommit();
      return claimed == 1;
    });
  }

  /**
   * Returns the current fencing token for a provisioning claim owned by the account.
   * @param requestId checkout request id
   * @param accountEmail authenticated account email
   * @return current claim attempt, or null when no active claim exists
   */
  public Long findProvisioningAttempt(String requestId, String accountEmail) {
    return runAsSystem(() -> {
      CheckoutRequest request = find(requestId, accountEmail);
      if (request == null || !StringUtils.equals(STATUS_PROVISIONING,
          request.getCheckoutRequestStatus())) {
        return null;
      }
      return request.getProvisioningAttempts();
    });
  }

  /**
   * Marks a request as fully provisioned and links the environment it produced.
   *
   * @param requestId correlation id
   * @param createdClientId {@code AD_CLIENT_ID} of the provisioned environment
   */
  public void recordProvisioned(String requestId, String createdClientId) {
    recordProvisioned(requestId, createdClientId, null);
  }

  /**
   * Records completion only for the claim that performed the work.
   * @param requestId checkout request id
   * @param createdClientId provisioned client id
   * @param claimAttempt fencing token that performed the work
   */
  public void recordProvisioned(String requestId, String createdClientId, Long claimAttempt) {
    runAsSystem(() -> {
      CheckoutRequest request = findByRequestId(requestId);
      if (request == null) {
        log.error("No checkout request found for '{}' while recording a provisioned environment",
            requestId);
        return;
      }
      if (claimAttempt != null) {
        int completed = OBDal.getInstance().getSession()
            .createQuery(HQL_UPDATE + CheckoutRequest.ENTITY_NAME + " cr"
                + "   set cr.checkoutRequestStatus = :provisioned,"
                + "       cr.createdClient = :createdClient,"
                + "       cr.provisionedAt = :now,"
                + "       cr.updated = :now"
                + " where cr.request = :requestId"
                + "   and cr.checkoutRequestStatus = :" + HQL_PROVISIONING
                + "   and cr.provisioningAttempts = :claimAttempt")
            .setParameter("provisioned", STATUS_PROVISIONED)
            .setParameter(HQL_PROVISIONING, STATUS_PROVISIONING)
            .setParameter("createdClient", StringUtils.isBlank(createdClientId)
                ? null : OBDal.getInstance().get(Client.class, createdClientId))
            .setParameter("now", new Date())
            .setParameter(PARAM_REQUEST_ID, StringUtils.trimToEmpty(requestId))
            .setParameter("claimAttempt", claimAttempt)
            .executeUpdate();
        if (completed != 1) {
          log.warn("Ignoring stale provisioning completion for checkout request '{}'", requestId);
          return;
        }
        flushAndCommit();
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
    });
  }

  private long provisioningLeaseMillis() {
    String configured = StringUtils.trimToNull(System.getProperty(PROVISIONING_LEASE_MINUTES_PROPERTY));
    if (configured == null) {
      configured = StringUtils.trimToNull(System.getenv(PROVISIONING_LEASE_MINUTES_ENV));
    }
    try {
      long minutes = configured == null ? DEFAULT_PROVISIONING_LEASE_MINUTES : Long.parseLong(configured);
      return Math.max(1L, minutes) * 60_000L;
    } catch (NumberFormatException e) {
      return DEFAULT_PROVISIONING_LEASE_MINUTES * 60_000L;
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
    runAsSystem(() -> {
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
      }
    });
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
   * account predicate. Package-private so {@link BillingEventStore} can link an event to the
   * request it references with the same lookup, inside its own session and context.
   *
   * <p>{@code REQUEST_ID} is a unique column and NOT the primary key, so this is a query and never
   * {@code OBDal.get(...)} — a primary-key lookup would compare a 36-character hyphenated UUID
   * against a 32-character Etendo id column and silently return null every time.
   *
   * @param requestId server-generated correlation id
   * @return the matching request, or null
   */
  static CheckoutRequest findByRequestId(String requestId) {
    if (StringUtils.isBlank(requestId)) {
      return null;
    }
    OBQuery<CheckoutRequest> query = OBDal.getInstance().createQuery(CheckoutRequest.class,
        "as cr where cr.request = :requestId");
    query.setNamedParameter(PARAM_REQUEST_ID, StringUtils.trimToEmpty(requestId));
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  /**
   * Runs {@code body} as the system user ({@code "0","0","0","0"}) with admin mode on, and hands
   * the caller back exactly the execution context it arrived with.
   *
   * <p>Restoring is the part that is easy to get wrong, and this class got it wrong until
   * ETP-5045: {@link OBContext#restorePreviousMode()} pops the <em>admin-mode stack</em>, it does
   * not undo {@link OBContext#setOBContext(String, String, String, String)}. So every method used
   * to leave the system context installed on the calling thread. That was invisible while the only
   * callers were the webhook (which has no context to lose) and the status endpoint (which is done
   * when the store returns, near the end of a request). It stopped being invisible as soon as a
   * caller in the middle of a unit of work started using the store: everything it ran afterwards
   * silently continued as system instead of as the identity it had established.
   *
   * <p><b>{@code null} is a legitimate previous context, not a missing one.</b> The webhook handler
   * is matched before the authentication chain and genuinely has none, so "no context" must be
   * restored as no context. {@link OBContext#setOBContext(OBContext)} clears the thread-local when
   * handed {@code null}, which is precisely the wanted behaviour — substituting a system context
   * for it would leave the thread more privileged than it was found.
   *
   * @param body the work to run as system
   * @param <T> the body's result type
   * @return whatever the body returned
   */
  private <T> T runAsSystem(Supplier<T> body) {
    OBContext previousContext = OBContext.getOBContext();
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return body.get();
    } finally {
      // Order is load-bearing. Admin mode was entered on top of the system context, so it has to
      // be left before that context is taken away: restorePreviousMode() pops the admin-mode stack
      // and then looks at whichever context is current at that moment, clearing it outright when
      // the stack empties on the shared admin context. Putting the caller's context back first
      // would expose that context to the check and could null it out — reintroducing, from the
      // other end, the very leak this method exists to close.
      exitAdminModeQuietly();
      restoreContextQuietly(previousContext);
    }
  }

  /**
   * Void form of {@link #runAsSystem(Supplier)}, for the methods that only write.
   *
   * @param body the work to run as system
   */
  private void runAsSystem(Runnable body) {
    runAsSystem(() -> {
      body.run();
      return null;
    });
  }

  /**
   * Leaves admin mode without ever throwing: this runs in a {@code finally}, and an exception here
   * would replace the real failure from the body with a misleading one.
   */
  private void exitAdminModeQuietly() {
    try {
      OBContext.restorePreviousMode();
    } catch (RuntimeException e) {
      log.error("Could not leave admin mode after a checkout-request store operation", e);
    }
  }

  /**
   * Reinstates the caller's context without ever throwing, for the same reason as
   * {@link #exitAdminModeQuietly()}.
   *
   * @param previousContext the context captured on entry; {@code null} is a real value and is
   *     restored as "no context"
   */
  private void restoreContextQuietly(OBContext previousContext) {
    try {
      OBContext.setOBContext(previousContext);
    } catch (RuntimeException e) {
      log.error("Could not restore the caller's OBContext after a checkout-request store operation",
          e);
    }
  }

  private void flushAndCommit() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }
}
