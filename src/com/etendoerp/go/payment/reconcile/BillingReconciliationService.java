/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static com.etendoerp.go.payment.reconcile.ReconciliationResult.shortRequest;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.itemFailed;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.rollbackQuietly;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.summary;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.LoggingOnboardingProgressSink;
import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.CheckoutRequestWrites;
import com.etendoerp.go.payment.HttpUrlConnectionStripeApiClient;
import com.etendoerp.go.payment.PausedSubscriptionAlert;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeJson;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.StripeTransportException;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.StripeEventTypes;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Phase;
import com.etendoerp.go.rest.PaidProvisioningService;
import com.etendoerp.go.rest.PaidReactivationService;
import com.etendoerp.go.schemaforge.data.BillingEvent;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

/**
 * ETP-5048 — makes money taken and service delivered agree, without a human reading Stripe.
 *
 * <p>One run, five phases, in this order (design of record:
 * {@code docs/plans/2026-10-18-etp-5048-billing-reconciliation-design.md} §5):
 * <ol>
 *   <li><b>Checkout recovery</b> — a {@code CREATED} request whose provider session completed and
 *       paid is recorded as paid (the webhook was lost); one whose session expired, past the
 *       abandoned window, is closed as {@code EXPIRED}.</li>
 *   <li><b>Provisioning repair</b> — a paid request with no environment (the browser gave up, or
 *       an attempt crashed or failed) is provisioned through {@link PaidProvisioningService},
 *       fenced by {@code claimForProvisioning}. A failure no retry can fix goes to review once. A
 *       paid re-subscription (ETP-5742, a request naming its canceled environment) is applied
 *       through {@link PaidReactivationService}, the payment webhook's own path.</li>
 *   <li><b>No-session-id review</b> — a {@code CREATING} request never received a provider session
 *       id. It is never closed automatically; it is alerted once and then counted as awaiting
 *       review.</li>
 *   <li><b>Subscriptions</b> — the real Stripe status of every locally active subscription, on the
 *       row route and on the preference route, applied through the webhook's own
 *       {@link SubscriptionLifecycleApplier} only when it changes the stored state. An absent
 *       subscription (404) is closed only behind the circuit breaker.</li>
 *   <li><b>Stuck events</b> — a provider event a crash left {@code RECEIVED} is superseded, never
 *       replayed, but only when this run re-read the state it could have moved: its family's
 *       phases completed without an error, a failed read or a cap, and its own request or
 *       subscription was among what they read. A stuck event the run cannot vouch for is parked
 *       for review, once; one whose phase was incomplete is left for a later run. Alert-only
 *       types (a dispute) are never swept.</li>
 * </ol>
 *
 * <p><b>A consistent dataset gets zero writes and zero alerts.</b> Every repair compares with the
 * stored state first, and every alert has a deterministic billing-event id, so a second run over
 * the same drift finds the row and counts it as awaiting review instead of alerting again.
 *
 * <p><b>Foreign subscriptions are never touched.</b> Only ids a local row names are acted on, and
 * a subscription without our {@code metadata.request_id} is skipped even then.
 */
public class BillingReconciliationService {

  private static final Logger log = LogManager.getLogger(BillingReconciliationService.class);

  static final String KIND_PAYMENT_RECOVERED = "payment-recovered";
  static final String KIND_CHECKOUT_EXPIRED = "checkout-expired";
  static final String KIND_PROVISIONED = "provisioned";
  static final String KIND_PROVISIONING_FAILED = "provisioning-failed";
  static final String KIND_NEEDS_REVIEW = ReviewQueue.KIND;
  static final String KIND_RETRY_LATER = "retry-later";
  static final String KIND_REACTIVATION_APPLIED = "reactivation-applied";

  static final String CODE_NO_SESSION_ID = "NO_SESSION_ID";
  static final String CODE_DETERMINISTIC_FAILURE = "DETERMINISTIC_FAILURE";
  static final String CODE_ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";

  private static final String STATUS_CREATED = "CREATED";
  private static final String STATUS_CREATING = "CREATING";
  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_PROVISIONING = "PROVISIONING";
  private static final String STATUS_PROVISIONED = "PROVISIONED";
  private static final String DERIVED_PAID = "paid";
  private static final String DERIVED_STALLED = CheckoutRequestStore.DERIVED_STATUS_STALLED;
  private static final String DERIVED_FAILED =
      CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED;
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_METADATA = "metadata";
  private static final String FIELD_REQUEST_ID = "request_id";
  private static final String FIELD_SUBSCRIPTION = "subscription";
  /** Expands the latest invoice, as phase 4 reads it, so the provider instant is the same. */
  private static final String EXPAND_LATEST_INVOICE = "?expand%5B%5D=latest_invoice";
  private static final String ITEM_CHECKOUT = "checkout ";
  private static final String ACTION_PROVISION = "provision the environment";
  static final String KIND_STALE_EVENT_REVIEW = "stale-event-review";
  /** Prefix of the review row of a stuck event: {@code reconcile:stale-event-review:<event>}. */
  static final String STALE_REVIEW_ID_PREFIX =
      ReconciliationAudit.eventId(KIND_STALE_EVENT_REVIEW) + ":";
  /** At most this many pages of stuck events per run, so events left for later cannot loop. */
  private static final int STALE_EVENT_PAGES = 10;
  /** The provider statuses phase 4 applies or alerts on; the rest it leaves to the webhook. */
  private static final List<String> STATUSES_PHASE_4_APPLIES = List.of("active", "trialing",
      "canceled", "incomplete_expired", "paused");
  private static final long MILLIS_PER_MINUTE = 60_000L;
  /**
   * How long a request whose item failed (a provider read, an exception) is left out of the
   * batch, so rows that fail on every run cannot fill it and starve the rows behind them.
   */
  static final long RETRY_BACKOFF_MINUTES = 6 * 60L;
  /**
   * HQL condition, on a checkout request aliased {@code cr}: no item of it failed within the
   * back-off. The marker is one {@code reconciliation.retry-later} row per request, refreshed on
   * each failure ({@link BillingEventStore#touchReconciliation}).
   */
  private static final String NOT_BACKING_OFF = "not exists (select 1 from "
      + BillingEvent.ENTITY_NAME + " rl where rl.requestID = cr.request"
      + " and rl.provider = :retryProvider and rl.eventType = :retryType"
      + " and coalesce(rl.lastDuplicateAt, rl.receivedAt) > :retryAfter)";

  /**
   * The text of a unique-constraint violation in a persisted failure reason. A unique key that is
   * already taken fails the same way on every retry (ETP-5737, the {@code ad_client_name}
   * collision, is one), whatever constraint it names — this is structure, not an entity.
   */
  private static final String UNIQUE_VIOLATION_TEXT = "duplicate key value violates unique";
  private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

  private final ReconciliationSettings settings;
  private final StripeApiClient stripe;
  private final CheckoutRequestStore checkoutStore;
  private final BillingEventStore eventStore;
  private final PaidProvisioningService provisioning;
  private final SubscriptionReconciler subscriptions;
  private final Clock clock;
  /** Closes abandoned requests; package-visible so tests can substitute it. */
  CheckoutRequestWrites checkoutWrites = new CheckoutRequestWrites();
  /** Applies paid re-subscriptions; package-visible so tests can substitute it. */
  PaidReactivationService reactivations = new PaidReactivationService();
  /** Where review alerts go; package-visible so tests can substitute it. */
  InternalAlertService internalAlerts = new InternalAlertService();
  private ReviewQueue reviews;

  /** Production wiring. */
  public BillingReconciliationService() {
    this(ReconciliationSettings.fromConfiguration(), new HttpUrlConnectionStripeApiClient(),
        new CheckoutRequestStore(), Clock.systemUTC());
  }

  private BillingReconciliationService(ReconciliationSettings settings, StripeApiClient stripe,
      CheckoutRequestStore checkoutStore, Clock clock) {
    this(settings, stripe, checkoutStore, new BillingEventStore(), new PaidProvisioningService(),
        new SubscriptionReconciler(settings, stripe, checkoutStore,
            new SubscriptionLifecycleApplier(), new TenantEnvironmentLifecycleService(),
            new PausedSubscriptionAlert(), clock),
        clock);
  }

  BillingReconciliationService(ReconciliationSettings settings, StripeApiClient stripe,
      CheckoutRequestStore checkoutStore, BillingEventStore eventStore,
      PaidProvisioningService provisioning, SubscriptionReconciler subscriptions, Clock clock) {
    this.settings = settings;
    this.stripe = stripe;
    this.checkoutStore = checkoutStore;
    this.eventStore = eventStore;
    this.provisioning = provisioning;
    this.subscriptions = subscriptions;
    this.clock = clock;
  }

  /**
   * Runs every phase once.
   *
   * @param dryRun read and decide everything, write nothing
   * @return what the run did
   */
  public ReconciliationResult run(boolean dryRun) {
    ReconciliationResult result = new ReconciliationResult(dryRun);
    ReconciliationAudit audit = new ReconciliationAudit(eventStore, dryRun, internalAlerts);
    reviews = new ReviewQueue(eventStore);
    Map<String, CheckoutRow> recovered = new HashMap<>();
    runPhase(Phase.CHECKOUT_RECOVERY.label(), Phase.CHECKOUT_RECOVERY, result,
        () -> recoverCheckouts(result, audit, recovered));
    runPhase(Phase.PROVISIONING_REPAIR.label(), Phase.PROVISIONING_REPAIR, result,
        () -> repairProvisioning(result, audit, recovered));
    runPhase("no-session-id review", null, result,
        () -> reviewSessionlessCheckouts(result, audit));
    runPhase(Phase.SUBSCRIPTIONS.label(), Phase.SUBSCRIPTIONS, result,
        () -> subscriptions.run(result, audit));
    runPhase("stuck events", null, result, () -> sweepStaleEvents(result, audit));
    return result;
  }

  /**
   * Runs one phase so that a failure never stops the next one. A phase that throws, records an
   * error or meets a failed provider read is marked incomplete: it did not re-read everything,
   * and the stuck-event sweep must not supersede anything on its strength.
   */
  private static void runPhase(String name, Phase phase, ReconciliationResult result,
      Runnable body) {
    int errorsBefore = result.getErrors().size();
    int providerErrorsBefore = result.get(Counter.PROVIDER_ERRORS);
    try {
      body.run();
    } catch (RuntimeException e) {
      log.error("{} Billing reconciliation phase '{}' failed", ReconciliationAudit.LOG_PREFIX,
          name, e);
      rollbackQuietly();
      result.error(name + " failed: " + e.getClass().getSimpleName());
    }
    if (phase == null) {
      return;
    }
    if (result.getErrors().size() > errorsBefore) {
      result.markIncomplete(phase, "it met errors");
    } else if (result.get(Counter.PROVIDER_ERRORS) > providerErrorsBefore) {
      result.markIncomplete(phase, "provider reads failed");
    }
  }

  // ── Phase 1: checkout recovery (provider → local) ───────────────────────────────────────

  private void recoverCheckouts(ReconciliationResult result, ReconciliationAudit audit,
      Map<String, CheckoutRow> recovered) {
    Date graceBefore = minutesAgo(settings.getPaidGraceMinutes());
    Date abandonedBefore = minutesAgo(settings.getAbandonedHours() * 60L);
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("created", STATUS_CREATED);
    parameters.put("before", graceBefore);
    for (CheckoutRow row : checkoutRows("cr.checkoutRequestStatus = :created"
        + " and cr.stripeSession is not null and cr.createdAt <= :before"
        + " and " + NOT_BACKING_OFF, withBackOff(parameters))) {
      result.increment(Counter.CHECKOUTS_CHECKED);
      try {
        recoverCheckout(row, abandonedBefore, result, audit, recovered);
      } catch (RuntimeException e) {
        itemFailed(result, ITEM_CHECKOUT + row.requestId, e);
        retryLater(row.requestId, e.getClass().getSimpleName(), audit);
      }
    }
  }

  private void recoverCheckout(CheckoutRow row, Date abandonedBefore, ReconciliationResult result,
      ReconciliationAudit audit, Map<String, CheckoutRow> recovered) {
    JSONObject session = retrieve("/v1/checkout/sessions/", row.sessionId, result);
    if (session == null) {
      retryLater(row.requestId, "the provider session could not be read", audit);
      return;
    }
    if (!belongsTo(session, row.requestId)) {
      result.error(ITEM_CHECKOUT + row.requestId
          + ": its provider session names another request");
      retryLater(row.requestId, "the provider session names another request", audit);
      return;
    }
    result.noteCheckoutReread(row.requestId);
    String status = session.optString(FIELD_STATUS, "");
    String paymentStatus = session.optString("payment_status", "");
    if ("complete".equals(status)
        && ("paid".equals(paymentStatus) || "no_payment_required".equals(paymentStatus))) {
      recoverPayment(row, session, result, audit, recovered);
    } else if ("expired".equals(status) && row.createdAt != null
        && !row.createdAt.after(abandonedBefore)) {
      closeAbandoned(row, result, audit);
    }
  }

  /** The provider session is paid but the webhook never applied it: record the payment. */
  private void recoverPayment(CheckoutRow row, JSONObject session, ReconciliationResult result,
      ReconciliationAudit audit, Map<String, CheckoutRow> recovered) {
    String customer = StripeJson.text(session, "customer");
    String subscription = StripeJson.text(session, FIELD_SUBSCRIPTION);
    // The payment and its audit row commit together (the write is left pending until the row
    // is inserted): a payment is never recorded without the row that explains it.
    if (!audit.isDryRun() && !checkoutWrites.recordPaidPending(row.requestId, customer,
        subscription)) {
      rollbackQuietly();
      result.error(ITEM_CHECKOUT + row.requestId + ": the request vanished before its "
          + "recovered payment could be recorded");
      return;
    }
    String eventId = ReconciliationAudit.eventId(KIND_PAYMENT_RECOVERED, row.requestId);
    if (!audit.write(eventId, KIND_PAYMENT_RECOVERED, row.requestId,
        BillingEventStore.RESULT_APPLIED,
        "Payment confirmed by re-reading the provider; the webhook never applied it",
        summary("session", row.sessionId, "customer", customer, FIELD_SUBSCRIPTION,
            subscription))) {
      auditRowMissing(eventId, true, result, audit);
      if (!audit.isDryRun()) {
        return;
      }
    }
    audit.alert("Recovered a paid checkout the webhook never applied: request {} session {}",
        row.requestId, row.sessionId);
    result.increment(Counter.PAYMENTS_RECOVERED);
    result.item(shortRequest(row.requestId), row.clientName,
        "record the payment the webhook lost", "the provider session is paid");
    recovered.put(row.requestId, CheckoutRow.recovered(row, subscription));
  }

  /** The provider session expired and the request is old enough: close it as abandoned. */
  private void closeAbandoned(CheckoutRow row, ReconciliationResult result,
      ReconciliationAudit audit) {
    // The close and its audit row commit together, as for a recovered payment.
    boolean closed = audit.isDryRun() || checkoutWrites.recordExpiredPending(row.requestId);
    if (!closed) {
      rollbackQuietly();
      return; // a payment raced the reconciliation and won; nothing to close
    }
    String eventId = ReconciliationAudit.eventId(KIND_CHECKOUT_EXPIRED, row.requestId);
    if (!audit.write(eventId, KIND_CHECKOUT_EXPIRED, row.requestId,
        BillingEventStore.RESULT_APPLIED,
        "Abandoned checkout closed after the provider confirmed its session expired",
        summary("session", row.sessionId)) && !audit.isDryRun()) {
      auditRowMissing(eventId, true, result, audit);
      return;
    }
    audit.repaired("closed abandoned checkout {} (session {} expired)", row.requestId,
        row.sessionId);
    result.increment(Counter.CHECKOUTS_EXPIRED);
    result.item(shortRequest(row.requestId), row.clientName, "close abandoned checkout",
        "the provider session expired");
  }

  private static boolean belongsTo(JSONObject session, String requestId) {
    JSONObject metadata = session.optJSONObject(FIELD_METADATA);
    String fromMetadata = metadata == null ? null
        : StringUtils.trimToNull(metadata.optString(FIELD_REQUEST_ID, ""));
    String reference = StringUtils.trimToNull(session.optString("client_reference_id", ""));
    return requestId.equals(fromMetadata) || requestId.equals(reference);
  }

  // ── Phase 2: provisioning repair ────────────────────────────────────────────────────────

  private void repairProvisioning(ReconciliationResult result, ReconciliationAudit audit,
      Map<String, CheckoutRow> recovered) {
    Date graceBefore = minutesAgo(settings.getPaidGraceMinutes());
    // A new purchase is paid and has no environment yet; a re-subscription (ETP-5742) names its
    // environment from the start, so it is the paid request that does.
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("paid", STATUS_PAID);
    parameters.put("provisioning", STATUS_PROVISIONING);
    ReviewQueue.bind(parameters);
    List<CheckoutRow> rows = checkoutRows("cr.checkoutRequestStatus in (:paid, :provisioning)"
        + " and (cr.createdClient is null or cr.checkoutRequestStatus = :paid)"
        + " and " + ReviewQueue.NOT_PARKED + " and " + NOT_BACKING_OFF,
        withBackOff(parameters));
    // A payment recovered by phase 1 in a dry run is still CREATED in the database.
    if (audit.isDryRun()) {
      rows.addAll(recovered.values());
    }
    for (CheckoutRow row : rows) {
      try {
        if (row.reactivation) {
          applyReactivation(row, graceBefore, result, audit,
              recovered.containsKey(row.requestId));
          continue;
        }
        repairOne(row, graceBefore, result, audit, recovered.containsKey(row.requestId));
      } catch (RuntimeException e) {
        itemFailed(result, "provisioning of " + row.requestId, e);
        retryLater(row.requestId, e.getClass().getSimpleName(), audit);
      }
    }
  }

  private void repairOne(CheckoutRow row, Date graceBefore, ReconciliationResult result,
      ReconciliationAudit audit, boolean justRecovered) {
    String derived = row.derivedStatus;
    if (DERIVED_PAID.equals(derived)) {
      if (!justRecovered && isInsideGrace(row, graceBefore)) {
        return; // the browser normally provisions it within the grace
      }
    } else if (DERIVED_FAILED.equals(derived)) {
      String code = ProvisioningFailureReason.codeOf(row.failureReason);
      if (!row.retryAllowed) {
        reviews.park(row.requestId, row.clientName, code,
            "The recorded failure cannot succeed on a retry", row.attempts, result, audit);
        return;
      }
      if (isDeterministic(row.failureReason)) {
        reviews.park(row.requestId, row.clientName, CODE_DETERMINISTIC_FAILURE,
            "The recorded failure is a unique-key collision, which a retry repeats",
            row.attempts, result, audit);
        return;
      }
    } else if (!DERIVED_STALLED.equals(derived)) {
      return; // an attempt holds a live lease
    }
    if (attemptsExhausted(row)) {
      reviews.park(row.requestId, row.clientName, CODE_ATTEMPTS_EXHAUSTED,
          row.attempts + " provisioning attempts did not complete", row.attempts, result, audit);
      return;
    }
    if (audit.isDryRun()) {
      audit.repaired("provision paid purchase {} ({})", row.requestId, derived);
      result.increment(Counter.PROVISIONED);
      result.item(shortRequest(row.requestId), row.clientName, ACTION_PROVISION,
          "paid with no environment (" + derived + ")");
      return;
    }
    PaidProvisioningService.Outcome outcome = provisioning.provision(row.requestId,
        new LoggingOnboardingProgressSink(ReconciliationAudit.LOG_PREFIX + " " + row.requestId));
    recordProvisioningOutcome(row, outcome, result, audit);
  }

  /**
   * Whether the attempt budget is spent: counted from the attempts a released review was parked
   * at, so a purchase an operator released after fixing the cause gets the full budget again.
   */
  private boolean attemptsExhausted(CheckoutRow row) {
    int max = settings.getMaxProvisioningAttempts();
    return row.attempts >= max && row.attempts - reviews.attemptsBaseline(row.requestId) >= max;
  }

  /**
   * Applies a paid re-subscription the payment webhook never applied (ETP-5742), through the
   * webhook's own fenced path. Without it the payment is recorded and the customer stays blocked,
   * because a re-subscription is never provisioned.
   *
   * <p>The preference route's watermark moves to the new subscription's start on the provider's
   * clock, as the webhook moves it to its event's: read from the provider, and the attempt is left
   * for a later run when that read fails.
   */
  private void applyReactivation(CheckoutRow row, Date graceBefore, ReconciliationResult result,
      ReconciliationAudit audit, boolean justRecovered) {
    if (!justRecovered && isInsideGrace(row, graceBefore)) {
      return; // the payment webhook applies it right after recording the payment
    }
    Instant paidAt = null;
    if (row.subscriptionId != null) {
      JSONObject subscription = retrieve("/v1/subscriptions/", row.subscriptionId,
          EXPAND_LATEST_INVOICE, result);
      if (subscription == null) {
        retryLater(row.requestId, "the new subscription could not be read", audit);
        return; // counted as a provider error
      }
      paidAt = SubscriptionLifecycleApplier.providerStateAt(subscription);
    }
    if (audit.isDryRun()) {
      audit.repaired("apply paid re-subscription {} to its environment", row.requestId);
      result.increment(Counter.REACTIVATIONS_APPLIED);
      result.item(shortRequest(row.requestId), row.clientName, "apply the re-subscription",
          "its payment webhook was never applied");
      return;
    }
    if (!reactivations.applyIfPending(row.requestId, paidAt)) {
      return; // applied meanwhile by the webhook, or no longer pending
    }
    String eventId = ReconciliationAudit.eventId(KIND_REACTIVATION_APPLIED, row.requestId);
    if (!audit.write(eventId, KIND_REACTIVATION_APPLIED, row.requestId,
        BillingEventStore.RESULT_APPLIED,
        "Re-subscription applied by the reconciliation; its payment webhook never applied it",
        summary(FIELD_SUBSCRIPTION, row.subscriptionId))) {
      auditRowMissing(eventId, false, result, audit);
    }
    audit.alert("Applied a paid re-subscription the webhook never applied: request {}",
        row.requestId);
    result.increment(Counter.REACTIVATIONS_APPLIED);
    result.item(shortRequest(row.requestId), row.clientName, "apply the re-subscription",
        "its payment webhook was never applied");
  }

  private void recordProvisioningOutcome(CheckoutRow row, PaidProvisioningService.Outcome outcome,
      ReconciliationResult result, ReconciliationAudit audit) {
    String requestId = row.requestId;
    switch (outcome.status()) {
      case PROVISIONED:
        String provisionedId = ReconciliationAudit.eventId(KIND_PROVISIONED, requestId);
        if (!audit.write(provisionedId, KIND_PROVISIONED, requestId,
            BillingEventStore.RESULT_APPLIED,
            "Environment provisioned by the reconciliation; the browser never completed it",
            summary("attempt", String.valueOf(outcome.attempt())))) {
          auditRowMissing(provisionedId, false, result, audit);
        }
        audit.alert("Provisioned a paid purchase with no environment: request {} (attempt {})",
            requestId, outcome.attempt());
        result.increment(Counter.PROVISIONED);
        result.item(shortRequest(requestId), row.clientName, ACTION_PROVISION,
            "paid with no environment (attempt " + outcome.attempt() + ")");
        break;
      case FAILED:
        audit.write(ReconciliationAudit.eventId(KIND_PROVISIONING_FAILED, requestId,
            String.valueOf(outcome.attempt())), KIND_PROVISIONING_FAILED, requestId,
            BillingEventStore.RESULT_FAILED,
            "Provisioning attempt failed: " + outcome.code(), summary("attempt",
                String.valueOf(outcome.attempt())));
        audit.alert("Provisioning of paid purchase {} failed (attempt {}, code {})", requestId,
            outcome.attempt(), outcome.code());
        result.increment(Counter.PROVISIONING_FAILED);
        result.item(shortRequest(requestId), row.clientName, ACTION_PROVISION,
            "the attempt failed (" + outcome.code() + ")");
        break;
      case NEEDS_REVIEW:
      case NOT_PAID:
        reviews.park(requestId, row.clientName,
            StringUtils.defaultIfBlank(outcome.code(), "NOT_PAID"),
            outcome.detail(), row.attempts, result, audit);
        break;
      default:
        // NOT_CLAIMED: another attempt holds it, or it finished meanwhile; nothing to record.
        break;
    }
  }

  /** Paid too recently for anyone but its own browser or webhook to act on it yet. */
  private static boolean isInsideGrace(CheckoutRow row, Date graceBefore) {
    return row.paidAt == null || row.paidAt.after(graceBefore);
  }

  private static boolean isDeterministic(String failureReason) {
    String reason = StringUtils.defaultString(failureReason).toLowerCase(Locale.ROOT);
    return reason.contains(UNIQUE_VIOLATION_TEXT) || reason.contains(UNIQUE_VIOLATION_SQLSTATE);
  }

  /**
   * A repair whose audit row was not written is an error of the run, never silent. Most repairs
   * are committed together with their row ({@code rolledBack}): the failed insert rolled the
   * repair back too, and a later run retries it. A provisioning or a re-subscription commits in
   * its own transaction (the whole onboarding, the webhook's own path), so there the repair stands
   * without its row and the error says so.
   */
  private static void auditRowMissing(String eventId, boolean rolledBack,
      ReconciliationResult result, ReconciliationAudit audit) {
    if (audit.isDryRun()) {
      return;
    }
    String consequence = rolledBack ? "the repair was rolled back with it and is retried"
        : "the repair stands without its audit row";
    result.error("audit row " + eventId + " could not be written; " + consequence);
    audit.alert("Audit row {} could not be written; {}", eventId, consequence);
  }

  /**
   * Leaves a request out of the next runs' batches for {@link #RETRY_BACKOFF_MINUTES}, after one
   * of its items failed. Best effort: a marker that cannot be written only costs the back-off.
   */
  private void retryLater(String requestId, String why, ReconciliationAudit audit) {
    if (audit.isDryRun()) {
      return;
    }
    try {
      eventStore.touchReconciliation(ReconciliationAudit.eventId(KIND_RETRY_LATER, requestId),
          ReconciliationAudit.EVENT_TYPE_PREFIX + KIND_RETRY_LATER, requestId,
          "Reconciliation could not complete this request (" + why + "); retried after "
              + RETRY_BACKOFF_MINUTES + " minutes");
    } catch (RuntimeException e) {
      log.warn("{} Could not record the back-off of request {}", ReconciliationAudit.LOG_PREFIX,
          requestId, e);
      rollbackQuietly();
    }
  }

  private Map<String, Object> withBackOff(Map<String, Object> parameters) {
    parameters.put("retryProvider", BillingEventStore.PROVIDER_RECONCILER);
    parameters.put("retryType", ReconciliationAudit.EVENT_TYPE_PREFIX + KIND_RETRY_LATER);
    parameters.put("retryAfter", minutesAgo(RETRY_BACKOFF_MINUTES));
    return parameters;
  }

  // ── Phase 3: no-session-id review ───────────────────────────────────────────────────────

  private void reviewSessionlessCheckouts(ReconciliationResult result,
      ReconciliationAudit audit) {
    Date abandonedBefore = minutesAgo(settings.getAbandonedHours() * 60L);
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("creating", STATUS_CREATING);
    parameters.put("before", abandonedBefore);
    ReviewQueue.bind(parameters);
    for (CheckoutRow row : checkoutRows("cr.checkoutRequestStatus = :creating"
        + " and cr.stripeSession is null and cr.creatingAt <= :before"
        + " and " + ReviewQueue.NOT_PARKED, parameters)) {
      try {
        reviews.park(row.requestId, row.clientName, CODE_NO_SESSION_ID,
            "The checkout never received a provider "
            + "session id, so the provider cannot confirm it was not paid; it is never closed "
            + "automatically", row.attempts, result, audit);
      } catch (RuntimeException e) {
        itemFailed(result, ITEM_CHECKOUT + row.requestId, e);
      }
    }
    // Parked purchases are out of every batch; the run still says how many wait for a person.
    result.add(Counter.AWAITING_REVIEW, reviews.countAwaiting());
  }

  // ── Phase 5: stuck RECEIVED events ──────────────────────────────────────────────────────

  /**
   * Supersedes the stuck events whose state this run re-read, parks for review those it cannot
   * vouch for, and leaves the rest for a later run. A family is swept only when the phases that
   * re-read its state completed: payments (phases 1 and 2) and subscriptions (phase 4).
   */
  private void sweepStaleEvents(ReconciliationResult result, ReconciliationAudit audit) {
    List<String> types = sweptTypes(result);
    if (types.isEmpty()) {
      return;
    }
    Date before = minutesAgo(settings.getReceivedStaleMinutes());
    int batch = settings.getBatchSize();
    int handled = 0;
    int offset = 0;
    boolean more = true;
    for (int page = 0; more && page < STALE_EVENT_PAGES && handled < batch; page++) {
      List<BillingEventStore.StaleEvent> events = eventStore.findStaleReceived(before, types,
          STALE_REVIEW_ID_PREFIX, offset, batch);
      int resolved = sweepPage(events, result, audit);
      handled += resolved;
      // A resolved event leaves the query (superseded or parked), except in a dry run.
      offset += audit.isDryRun() ? events.size() : events.size() - resolved;
      more = events.size() == batch;
    }
  }

  /** The event types whose state this run re-read in full: those of its complete phases. */
  private static List<String> sweptTypes(ReconciliationResult result) {
    List<String> types = new ArrayList<>();
    if (result.isComplete(Phase.CHECKOUT_RECOVERY)
        && result.isComplete(Phase.PROVISIONING_REPAIR)) {
      types.addAll(StripeEventTypes.CHECKOUT_PAID);
    }
    if (result.isComplete(Phase.SUBSCRIPTIONS)) {
      types.addAll(StripeEventTypes.SUBSCRIPTION_LIFECYCLE);
      types.add(StripeEventTypes.SUBSCRIPTION_PAUSED);
    }
    return types;
  }

  /** @return how many events of the page were resolved (superseded or parked) */
  private int sweepPage(List<BillingEventStore.StaleEvent> events, ReconciliationResult result,
      ReconciliationAudit audit) {
    int resolved = 0;
    for (BillingEventStore.StaleEvent event : events) {
      if (sweepOne(event, result, audit)) {
        resolved++;
      }
    }
    return resolved;
  }

  /** @return whether the event was resolved (superseded or parked) rather than left */
  private boolean sweepOne(BillingEventStore.StaleEvent event, ReconciliationResult result,
      ReconciliationAudit audit) {
    String type = StringUtils.defaultString(event.eventType());
    StaleVerdict verdict = StripeEventTypes.CHECKOUT_PAID.contains(type)
        ? checkoutEventVerdict(event, result) : subscriptionEventVerdict(event, result);
    if (verdict == StaleVerdict.LEAVE) {
      return false;
    }
    if (verdict == StaleVerdict.SUPERSEDE) {
      if (!audit.isDryRun()) {
        eventStore.markIgnored(event.eventId(), "Superseded by billing reconciliation "
            + result.getRunId() + ": the provider state was re-read, the event not replayed");
      }
      audit.alert("Stuck provider event {} ({}) superseded; the provider state was re-read",
          event.eventId(), type);
      result.increment(Counter.STALE_EVENTS_SWEPT);
      result.item("event " + event.eventId(), null, "supersede stuck " + type,
          "its provider state was re-read");
      return true;
    }
    if (audit.write(STALE_REVIEW_ID_PREFIX + event.eventId(), KIND_STALE_EVENT_REVIEW,
        event.requestId(), BillingEventStore.RESULT_FAILED, "Stuck provider event " + type
            + " could not be vouched for by a re-read of the provider; review it in the Stripe "
            + "dashboard and set it to IGNORED or replay it", summary("event", event.eventId()))) {
      audit.internalAlert("billing-reconcile-stale-event", event.eventId(), KIND_STALE_EVENT_REVIEW,
          null);
    }
    audit.alert("Stuck provider event {} ({}) needs review: the run could not vouch for it",
        event.eventId(), type);
    result.increment(Counter.STALE_EVENTS_PARKED);
    result.item("event " + event.eventId(), null, "park stuck " + type + " for review",
        "no re-read could vouch for it");
    return true;
  }

  /**
   * A payment event is superseded when its request is past {@code PAID} and nothing of it is
   * pending. It is left for a later run while its request is {@code CREATED} and phase 1 has not
   * read it yet, or is a paid re-subscription phase 2 has not applied yet. Anything else is parked
   * for review once: it names no known request, the provider contradicts it (its session was read
   * and is unpaid), or its request is {@code CREATING} or {@code EXPIRED}, which a payment event
   * should never find and no later run will change.
   */
  private StaleVerdict checkoutEventVerdict(BillingEventStore.StaleEvent event,
      ReconciliationResult result) {
    String requestId = StringUtils.trimToNull(event.requestId());
    CheckoutRow row = requestId == null ? null : checkoutRow(requestId);
    if (row == null) {
      return StaleVerdict.REVIEW;
    }
    boolean paid = STATUS_PROVISIONED.equals(row.status)
        || STATUS_PROVISIONING.equals(row.status)
        || (STATUS_PAID.equals(row.status) && !row.reactivation);
    if (paid) {
      return StaleVerdict.SUPERSEDE;
    }
    if (STATUS_CREATED.equals(row.status)) {
      // Read this run and still unpaid: the provider contradicts the event. Otherwise phase 1
      // reaches it in a later run.
      return result.wasCheckoutReread(requestId) ? StaleVerdict.REVIEW : StaleVerdict.LEAVE;
    }
    if (STATUS_PAID.equals(row.status)) {
      return StaleVerdict.LEAVE; // a re-subscription phase 2 applies
    }
    return StaleVerdict.REVIEW;
  }

  /**
   * A subscription event is superseded when this run read its subscription and the provider
   * status is one phase 4 applies (or alerts on); it is parked when the status is one phase 4
   * leaves to the webhook ({@code past_due}, {@code unpaid}, {@code incomplete}), or when the
   * subscription was not read at all (no locally active row names it).
   */
  private static StaleVerdict subscriptionEventVerdict(BillingEventStore.StaleEvent event,
      ReconciliationResult result) {
    String subscriptionId = subscriptionIdOf(event);
    String status = subscriptionId == null ? null
        : result.rereadSubscriptionStatus(subscriptionId);
    return status != null && STATUSES_PHASE_4_APPLIES.contains(status)
        ? StaleVerdict.SUPERSEDE : StaleVerdict.REVIEW;
  }

  private static String subscriptionIdOf(BillingEventStore.StaleEvent event) {
    if (StringUtils.isBlank(event.summary())) {
      return null;
    }
    try {
      JSONObject summary = new JSONObject(event.summary());
      String key = StringUtils.startsWith(event.eventType(), "customer.subscription.")
          ? "id" : FIELD_SUBSCRIPTION;
      return StringUtils.trimToNull(summary.optString(key, ""));
    } catch (JSONException e) {
      return null;
    }
  }

  /** What the sweep does with one stuck event. */
  private enum StaleVerdict {
    SUPERSEDE, REVIEW, LEAVE
  }

  // ── Shared ──────────────────────────────────────────────────────────────────────────────

  private JSONObject retrieve(String pathPrefix, String id, ReconciliationResult result) {
    return retrieve(pathPrefix, id, "", result);
  }

  /** @param query appended after the id, e.g. {@code ?expand%5B%5D=latest_invoice}; may be empty */
  private JSONObject retrieve(String pathPrefix, String id, String query,
      ReconciliationResult result) {
    try {
      StripeResponse response = stripe.get(pathPrefix
          + URLEncoder.encode(id, StandardCharsets.UTF_8) + query);
      if (!response.isSuccess()) {
        log.warn("{} Reading {}{} answered {} ({})", ReconciliationAudit.LOG_PREFIX, pathPrefix,
            id, response.status(), response.errorCode());
        result.increment(Counter.PROVIDER_ERRORS);
        return null;
      }
      return response.json();
    } catch (StripeTransportException e) {
      log.warn("{} Could not read {}{}: {}", ReconciliationAudit.LOG_PREFIX, pathPrefix, id,
          e.getMessage());
      result.increment(Counter.PROVIDER_ERRORS);
      return null;
    }
  }

  private List<CheckoutRow> checkoutRows(String where, Map<String, Object> parameters) {
    return SystemContext.call("listing checkout requests to reconcile", () -> {
      List<CheckoutRow> rows = new ArrayList<>();
      var query = OBDal.getInstance().createQuery(CheckoutRequest.class,
          "as cr where " + where + " order by cr.creationDate asc");
      parameters.forEach(query::setNamedParameter);
      for (CheckoutRequest request : query.setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .setMaxResult(settings.getBatchSize())
          .list()) {
        rows.add(new CheckoutRow(request, checkoutStore.deriveProvisioningStatus(request),
            checkoutStore.isProvisioningRetryAllowed(request)));
      }
      return rows;
    });
  }

  /** The facts of one checkout request, or null when there is none. */
  private CheckoutRow checkoutRow(String requestId) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("requestId", requestId);
    List<CheckoutRow> rows = checkoutRows("cr.request = :requestId", parameters);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Date minutesAgo(long minutes) {
    return new Date(clock.millis() - minutes * MILLIS_PER_MINUTE);
  }

  /** The facts of one checkout row a phase needs, read in one transaction. */
  private static final class CheckoutRow {
    private String requestId;
    private String sessionId;
    private String derivedStatus;
    private String failureReason;
    private Date paidAt;
    private long attempts;
    private Date createdAt;
    private boolean retryAllowed;
    private boolean reactivation;
    private String subscriptionId;
    private String status;
    private String clientName;

    private CheckoutRow() {
    }

    private CheckoutRow(CheckoutRequest request, String derivedStatus, boolean retryAllowed) {
      this.requestId = request.getRequest();
      this.sessionId = request.getStripeSession();
      this.derivedStatus = derivedStatus;
      this.failureReason = request.getFailureReason();
      // A row paid before PAID_AT existed has none: its creation stands in, so it still leaves
      // the browser's grace instead of waiting for a payment instant it will never get.
      this.paidAt = request.getPaidAt() != null ? request.getPaidAt() : request.getCreationDate();
      this.attempts = request.getProvisioningAttempts() == null ? 0L
          : request.getProvisioningAttempts();
      this.createdAt = request.getCreatedAt();
      this.retryAllowed = retryAllowed;
      this.reactivation = request.getCreatedClient() != null
          && !STATUS_PROVISIONED.equals(request.getCheckoutRequestStatus());
      this.status = request.getCheckoutRequestStatus();
      this.clientName = request.getClientName();
      this.subscriptionId = StringUtils.trimToNull(request.getStripeSubscription());
    }

    /**
     * A payment phase 1 recovered: what phase 2 needs of it in a dry run, where the database
     * still holds it as {@code CREATED}.
     */
    static CheckoutRow recovered(CheckoutRow created, String subscriptionId) {
      CheckoutRow row = new CheckoutRow();
      row.requestId = created.requestId;
      row.derivedStatus = DERIVED_PAID;
      row.retryAllowed = true;
      row.reactivation = created.reactivation;
      row.subscriptionId = StringUtils.trimToNull(subscriptionId);
      row.paidAt = created.createdAt;
      row.clientName = created.clientName;
      return row;
    }
  }

}
