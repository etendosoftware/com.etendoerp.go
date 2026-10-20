/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.LoggingOnboardingProgressSink;
import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CheckoutConfiguration;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;
import com.etendoerp.go.payment.HttpUrlConnectionStripeApiClient;
import com.etendoerp.go.payment.PausedSubscriptionAlert;
import com.etendoerp.go.payment.ProvisioningFailureReason;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeCurrencyScale;
import com.etendoerp.go.payment.StripeJson;
import com.etendoerp.go.payment.StripeListPager;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.StripeTransportException;
import com.etendoerp.go.payment.SubscriptionEventOutcome;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService.LifecycleTarget;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.rest.PaidProvisioningService;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Subscription;

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
 *       fenced by {@code claimForProvisioning}. A failure no retry can fix goes to review once.</li>
 *   <li><b>No-session-id review</b> — a {@code CREATING} request never received a provider session
 *       id. It is never closed automatically; it is alerted once and then counted as awaiting
 *       review.</li>
 *   <li><b>Subscriptions</b> — the real Stripe status of every locally active subscription, on the
 *       row route and on the preference route, applied through the webhook's own
 *       {@link SubscriptionLifecycleApplier} only when it changes the stored state. An absent
 *       subscription (404) is closed only behind the circuit breaker.</li>
 *   <li><b>Stuck events</b> — a provider event a crash left {@code RECEIVED} is superseded, never
 *       replayed: phases 1–4 have just re-read the provider state it could have moved.</li>
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
  static final String KIND_NEEDS_REVIEW = "needs-review";
  static final String KIND_SUBSCRIPTION_CLOSED = "subscription-closed";
  static final String KIND_SUBSCRIPTION_CURRENT = "subscription-current";
  static final String KIND_SUBSCRIPTION_PRICE = "subscription-price";
  static final String KIND_PAUSE_COLLECTION = "pause-collection";

  static final String CODE_NO_SESSION_ID = "NO_SESSION_ID";
  static final String CODE_DETERMINISTIC_FAILURE = "DETERMINISTIC_FAILURE";
  static final String CODE_ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";

  private static final String SUBSCRIPTION_UPDATED = "customer.subscription.updated";
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
  private static final String MODE_LIVE = "live";
  private static final String MODE_TEST = "test";
  private static final int SUBSCRIPTION_PAGES = 100;
  private static final long MILLIS_PER_MINUTE = 60_000L;

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
  private final SubscriptionLifecycleApplier applier;
  private final TenantEnvironmentLifecycleService lifecycle;
  private final PausedSubscriptionAlert pausedAlert;
  private final Clock clock;

  /** Production wiring. */
  public BillingReconciliationService() {
    this(ReconciliationSettings.fromConfiguration(), new HttpUrlConnectionStripeApiClient(),
        new CheckoutRequestStore(), new BillingEventStore(), new PaidProvisioningService(),
        new SubscriptionLifecycleApplier(), new TenantEnvironmentLifecycleService(),
        new PausedSubscriptionAlert(), Clock.systemUTC());
  }

  BillingReconciliationService(ReconciliationSettings settings, StripeApiClient stripe,
      CheckoutRequestStore checkoutStore, BillingEventStore eventStore,
      PaidProvisioningService provisioning, SubscriptionLifecycleApplier applier,
      TenantEnvironmentLifecycleService lifecycle, PausedSubscriptionAlert pausedAlert,
      Clock clock) {
    this.settings = settings;
    this.stripe = stripe;
    this.checkoutStore = checkoutStore;
    this.eventStore = eventStore;
    this.provisioning = provisioning;
    this.applier = applier;
    this.lifecycle = lifecycle;
    this.pausedAlert = pausedAlert;
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
    ReconciliationAudit audit = new ReconciliationAudit(eventStore, dryRun);
    Set<String> recovered = new HashSet<>();
    runPhase("checkout recovery", result, () -> recoverCheckouts(result, audit, recovered));
    runPhase("provisioning repair", result, () -> repairProvisioning(result, audit, recovered));
    runPhase("no-session-id review", result, () -> reviewSessionlessCheckouts(result, audit));
    runPhase("subscriptions", result, () -> reconcileSubscriptions(result, audit));
    runPhase("stuck events", result, () -> sweepStaleEvents(result, audit));
    return result;
  }

  private static void runPhase(String name, ReconciliationResult result, Runnable phase) {
    try {
      phase.run();
    } catch (RuntimeException e) {
      log.error("{} Billing reconciliation phase '{}' failed", ReconciliationAudit.LOG_PREFIX,
          name, e);
      rollbackQuietly();
      result.error(name + " failed: " + e.getClass().getSimpleName());
    }
  }

  // ── Phase 1: checkout recovery (provider → local) ───────────────────────────────────────

  private void recoverCheckouts(ReconciliationResult result, ReconciliationAudit audit,
      Set<String> recovered) {
    Date graceBefore = minutesAgo(settings.getPaidGraceMinutes());
    Date abandonedBefore = minutesAgo(settings.getAbandonedHours() * 60L);
    for (CheckoutRow row : checkoutRows("cr.checkoutRequestStatus = :created"
        + " and cr.stripeSession is not null and cr.createdAt <= :before",
        Map.of("created", STATUS_CREATED, "before", graceBefore))) {
      result.increment(Counter.CHECKOUTS_CHECKED);
      try {
        recoverCheckout(row, abandonedBefore, result, audit, recovered);
      } catch (RuntimeException e) {
        itemFailed(result, "checkout " + row.requestId, e);
      }
    }
  }

  private void recoverCheckout(CheckoutRow row, Date abandonedBefore, ReconciliationResult result,
      ReconciliationAudit audit, Set<String> recovered) {
    JSONObject session = retrieve("/v1/checkout/sessions/", row.sessionId, result);
    if (session == null) {
      return;
    }
    if (!belongsTo(session, row.requestId)) {
      result.error("checkout " + row.requestId + ": its provider session names another request");
      return;
    }
    String status = session.optString(FIELD_STATUS, "");
    String paymentStatus = session.optString("payment_status", "");
    if ("complete".equals(status)
        && ("paid".equals(paymentStatus) || "no_payment_required".equals(paymentStatus))) {
      String customer = StripeJson.text(session, "customer");
      String subscription = StripeJson.text(session, "subscription");
      if (!audit.isDryRun()) {
        checkoutStore.recordPaid(row.requestId, customer, subscription);
      }
      audit.record(ReconciliationAudit.eventId(KIND_PAYMENT_RECOVERED, row.requestId),
          KIND_PAYMENT_RECOVERED, row.requestId, BillingEventStore.RESULT_APPLIED,
          "Payment confirmed by re-reading the provider; the webhook never applied it",
          summary("session", row.sessionId, "customer", customer, "subscription", subscription));
      audit.alert("Recovered a paid checkout the webhook never applied: request {} session {}",
          row.requestId, row.sessionId);
      result.increment(Counter.PAYMENTS_RECOVERED);
      recovered.add(row.requestId);
    } else if ("expired".equals(status) && row.createdAt != null
        && !row.createdAt.after(abandonedBefore)) {
      boolean closed = audit.isDryRun() || checkoutStore.recordExpired(row.requestId);
      if (!closed) {
        return; // a payment raced the reconciliation and won; nothing to close
      }
      audit.record(ReconciliationAudit.eventId(KIND_CHECKOUT_EXPIRED, row.requestId),
          KIND_CHECKOUT_EXPIRED, row.requestId, BillingEventStore.RESULT_APPLIED,
          "Abandoned checkout closed after the provider confirmed its session expired",
          summary("session", row.sessionId));
      audit.repaired("closed abandoned checkout {} (session {} expired)", row.requestId,
          row.sessionId);
      result.increment(Counter.CHECKOUTS_EXPIRED);
    }
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
      Set<String> recovered) {
    Date graceBefore = minutesAgo(settings.getPaidGraceMinutes());
    List<CheckoutRow> rows = checkoutRows("cr.checkoutRequestStatus in (:paid, :provisioning)"
        + " and cr.createdClient is null",
        Map.of("paid", STATUS_PAID, "provisioning", STATUS_PROVISIONING));
    // A payment recovered by phase 1 in a dry run is still CREATED in the database.
    if (audit.isDryRun()) {
      for (String requestId : recovered) {
        rows.add(new CheckoutRow(requestId, null, DERIVED_PAID, null, null, 0L, null, true));
      }
    }
    for (CheckoutRow row : rows) {
      try {
        repairOne(row, graceBefore, result, audit, recovered.contains(row.requestId));
      } catch (RuntimeException e) {
        itemFailed(result, "provisioning of " + row.requestId, e);
      }
    }
  }

  private void repairOne(CheckoutRow row, Date graceBefore, ReconciliationResult result,
      ReconciliationAudit audit, boolean justRecovered) {
    if (audit.alreadyRecorded(ReconciliationAudit.eventId(KIND_NEEDS_REVIEW, row.requestId))) {
      result.increment(Counter.AWAITING_REVIEW);
      return;
    }
    String derived = row.derivedStatus;
    if (DERIVED_PAID.equals(derived)) {
      if (!justRecovered && (row.paidAt == null || row.paidAt.after(graceBefore))) {
        return; // the browser normally provisions it within the grace
      }
    } else if (DERIVED_FAILED.equals(derived)) {
      String code = ProvisioningFailureReason.codeOf(row.failureReason);
      if (!row.retryAllowed) {
        needsReview(row.requestId, code, "The recorded failure cannot succeed on a retry",
            result, audit);
        return;
      }
      if (isDeterministic(row.failureReason)) {
        needsReview(row.requestId, CODE_DETERMINISTIC_FAILURE,
            "The recorded failure is a unique-key collision, which a retry repeats", result,
            audit);
        return;
      }
    } else if (!DERIVED_STALLED.equals(derived)) {
      return; // an attempt holds a live lease
    }
    if (row.attempts >= settings.getMaxProvisioningAttempts()) {
      needsReview(row.requestId, CODE_ATTEMPTS_EXHAUSTED,
          row.attempts + " provisioning attempts did not complete", result, audit);
      return;
    }
    if (audit.isDryRun()) {
      audit.repaired("provision paid purchase {} ({})", row.requestId, derived);
      result.increment(Counter.PROVISIONED);
      return;
    }
    PaidProvisioningService.Outcome outcome = provisioning.provision(row.requestId,
        new LoggingOnboardingProgressSink(ReconciliationAudit.LOG_PREFIX + " " + row.requestId));
    recordProvisioningOutcome(row.requestId, outcome, result, audit);
  }

  private void recordProvisioningOutcome(String requestId, PaidProvisioningService.Outcome outcome,
      ReconciliationResult result, ReconciliationAudit audit) {
    switch (outcome.status()) {
      case PROVISIONED:
        audit.record(ReconciliationAudit.eventId(KIND_PROVISIONED, requestId), KIND_PROVISIONED,
            requestId, BillingEventStore.RESULT_APPLIED,
            "Environment provisioned by the reconciliation; the browser never completed it",
            summary("attempt", String.valueOf(outcome.attempt())));
        audit.alert("Provisioned a paid purchase with no environment: request {} (attempt {})",
            requestId, outcome.attempt());
        result.increment(Counter.PROVISIONED);
        break;
      case FAILED:
        audit.record(ReconciliationAudit.eventId(KIND_PROVISIONING_FAILED, requestId,
            String.valueOf(outcome.attempt())), KIND_PROVISIONING_FAILED, requestId,
            BillingEventStore.RESULT_FAILED,
            "Provisioning attempt failed: " + outcome.code(), summary("attempt",
                String.valueOf(outcome.attempt())));
        audit.alert("Provisioning of paid purchase {} failed (attempt {}, code {})", requestId,
            outcome.attempt(), outcome.code());
        result.increment(Counter.PROVISIONING_FAILED);
        break;
      case NEEDS_REVIEW:
      case NOT_PAID:
        needsReview(requestId, StringUtils.defaultIfBlank(outcome.code(), "NOT_PAID"),
            outcome.detail(), result, audit);
        break;
      default:
        // NOT_CLAIMED: another attempt holds it, or it finished meanwhile; nothing to record.
        break;
    }
  }

  private static boolean isDeterministic(String failureReason) {
    String reason = StringUtils.defaultString(failureReason).toLowerCase(Locale.ROOT);
    return reason.contains(UNIQUE_VIOLATION_TEXT) || reason.contains(UNIQUE_VIOLATION_SQLSTATE);
  }

  private void needsReview(String requestId, String code, String detail,
      ReconciliationResult result, ReconciliationAudit audit) {
    String eventId = ReconciliationAudit.eventId(KIND_NEEDS_REVIEW, requestId);
    if (audit.alreadyRecorded(eventId)) {
      result.increment(Counter.AWAITING_REVIEW);
      return;
    }
    audit.record(eventId, KIND_NEEDS_REVIEW, requestId, BillingEventStore.RESULT_FAILED,
        "Needs manual review (" + code + "): " + StringUtils.defaultString(detail), null);
    audit.alert("Purchase {} needs manual review ({}): {}", requestId, code, detail);
    result.increment(Counter.NEEDS_REVIEW);
  }

  // ── Phase 3: no-session-id review ───────────────────────────────────────────────────────

  private void reviewSessionlessCheckouts(ReconciliationResult result,
      ReconciliationAudit audit) {
    Date abandonedBefore = minutesAgo(settings.getAbandonedHours() * 60L);
    for (CheckoutRow row : checkoutRows("cr.checkoutRequestStatus = :creating"
        + " and cr.stripeSession is null and cr.creatingAt <= :before",
        Map.of("creating", STATUS_CREATING, "before", abandonedBefore))) {
      try {
        needsReview(row.requestId, CODE_NO_SESSION_ID, "The checkout never received a provider "
            + "session id, so the provider cannot confirm it was not paid; it is never closed "
            + "automatically", result, audit);
      } catch (RuntimeException e) {
        itemFailed(result, "checkout " + row.requestId, e);
      }
    }
  }

  // ── Phase 4: subscriptions (local → provider) ───────────────────────────────────────────

  private void reconcileSubscriptions(ReconciliationResult result, ReconciliationAudit audit) {
    List<SubscriptionCandidate> candidates = subscriptionCandidates();
    if (candidates.isEmpty()) {
      return;
    }
    Set<String> wanted = new HashSet<>();
    for (SubscriptionCandidate candidate : candidates) {
      wanted.add(candidate.subscriptionId);
    }
    Map<String, JSONObject> listed = new HashMap<>();
    new StripeListPager(stripe, StripeListPager.MAX_PAGE_SIZE, SUBSCRIPTION_PAGES)
        .forEach("/v1/subscriptions?status=all", subscription -> {
          String id = subscription.optString("id", "");
          if (wanted.contains(id)) {
            listed.put(id, subscription);
          }
        });
    int directReads = 0;
    List<SubscriptionCandidate> absent = new ArrayList<>();
    for (SubscriptionCandidate candidate : candidates) {
      JSONObject subscription = listed.get(candidate.subscriptionId);
      if (subscription == null) {
        if (directReads >= settings.getBatchSize()) {
          continue; // checked on a later run
        }
        directReads++;
        ProviderRead read = readSubscription(candidate.subscriptionId);
        if (read.absent) {
          countChecked(candidate, result);
          absent.add(candidate);
          continue;
        }
        if (read.object == null) {
          result.increment(Counter.PROVIDER_ERRORS);
          continue;
        }
        subscription = read.object;
      }
      countChecked(candidate, result);
      try {
        reconcileSubscription(candidate, subscription, result, audit);
      } catch (RuntimeException e) {
        rollbackQuietly();
        itemFailed(result, "subscription " + candidate.subscriptionId, e);
      }
    }
    closeAbsent(absent, result, audit);
  }

  private static void countChecked(SubscriptionCandidate candidate, ReconciliationResult result) {
    result.increment(Counter.SUBSCRIPTIONS_CHECKED);
    if (candidate.preferenceRoute) {
      // ETP-5046-TRANSITIONAL-FALLBACK
      result.increment(Counter.PREFERENCE_ROUTE_CHECKED);
    }
  }

  private void reconcileSubscription(SubscriptionCandidate candidate, JSONObject subscription,
      ReconciliationResult result, ReconciliationAudit audit) {
    if (!hasOurMetadata(subscription)) {
      log.debug("{} Subscription {} carries no request_id metadata; foreign, not touched",
          ReconciliationAudit.LOG_PREFIX, candidate.subscriptionId);
      return;
    }
    String status = subscription.optString(FIELD_STATUS, "");
    if (("active".equals(status) || "trialing".equals(status))
        && subscription.has("pause_collection") && !subscription.isNull("pause_collection")) {
      alertPauseCollection(candidate, subscription, result, audit);
    }
    switch (status) {
      case "active":
      case "trialing":
      case "canceled":
      case "incomplete_expired":
        break;
      case "paused":
        // Alert only, never a state change (decided 2026-10-20): see PausedSubscriptionAlert.
        alertPaused(candidate, result, audit);
        return;
      default:
        // past_due / unpaid stay with the webhook's grace logic; incomplete is still being paid.
        return;
    }
    Instant now = clock.instant();
    SystemContext.run("reconciling a subscription", () -> {
      LifecycleTarget target = resolveTarget(candidate);
      if (target == null || target.isIgnored()) {
        return;
      }
      SubscriptionEventOutcome outcome = applier.evaluate(SUBSCRIPTION_UPDATED,
          syntheticEvent(subscription, now), target.storedState());
      boolean changed = !outcome.isIgnored() && changesStoredState(target, outcome);
      if (changed) {
        applyOutcome(candidate, target, outcome, now, "provider status " + status, result, audit);
      } else if (!outcome.isIgnored() && outcome.status() != SubscriptionStatus.EXPIRED
          && target.subscription() != null) {
        fillMissingPrice(candidate, target.subscription(), subscription, result, audit);
      }
    });
  }

  private void alertPaused(SubscriptionCandidate candidate, ReconciliationResult result,
      ReconciliationAudit audit) {
    if (audit.alreadyRecorded(PausedSubscriptionAlert.eventIdOf(candidate.subscriptionId))) {
      return; // raised before, by this job or by the webhook
    }
    if (audit.isDryRun()) {
      audit.alert("Subscription {} is paused at the provider and needs review",
          candidate.subscriptionId);
    } else if (!pausedAlert.raiseOnce(ReconciliationAudit.LOG_PREFIX, candidate.subscriptionId,
        candidate.clientId, candidate.requestId, "reconciliation")) {
      return;
    }
    result.increment(Counter.PAUSED_ALERTS);
  }

  private void alertPauseCollection(SubscriptionCandidate candidate, JSONObject subscription,
      ReconciliationResult result, ReconciliationAudit audit) {
    String eventId = ReconciliationAudit.eventId(KIND_PAUSE_COLLECTION, candidate.subscriptionId);
    if (audit.alreadyRecorded(eventId)) {
      return;
    }
    JSONObject pause = subscription.optJSONObject("pause_collection");
    String behavior = pause == null ? "-" : pause.optString("behavior", "-");
    audit.record(eventId, KIND_PAUSE_COLLECTION, candidate.requestId,
        BillingEventStore.RESULT_IGNORED,
        "Collection is paused at the provider (" + behavior + "); no access change was made",
        summary("subscription", candidate.subscriptionId, "behavior", behavior));
    audit.alert("Subscription {} has collection paused at the provider ({}); review it in the "
        + "Stripe dashboard", candidate.subscriptionId, behavior);
    result.increment(Counter.PAUSE_COLLECTION_ALERTS);
  }

  /**
   * The no-op diff: only a status change or a close is a repair. A refreshed billing period or
   * event watermark alone is never written, so a consistent subscription costs nothing.
   */
  private static boolean changesStoredState(LifecycleTarget target,
      SubscriptionEventOutcome outcome) {
    SubscriptionStatus stored = target.storedState().status();
    if (outcome.closesSubscription() && target.subscription() != null) {
      return true; // a row target is an open row by construction
    }
    return effective(stored) != effective(outcome.status());
  }

  /** "No status yet" and the legacy entitlement both read as paying, like the access policy. */
  private static SubscriptionStatus effective(SubscriptionStatus status) {
    if (status == null || status == SubscriptionStatus.LEGACY_ENTITLEMENT
        || status == SubscriptionStatus.NONE) {
      return SubscriptionStatus.CURRENT;
    }
    return status;
  }

  private void applyOutcome(SubscriptionCandidate candidate, LifecycleTarget target,
      SubscriptionEventOutcome outcome, Instant now, String why, ReconciliationResult result,
      ReconciliationAudit audit) {
    boolean closes = outcome.status() == SubscriptionStatus.EXPIRED;
    String kind = closes ? KIND_SUBSCRIPTION_CLOSED : KIND_SUBSCRIPTION_CURRENT;
    String eventId = closes
        ? ReconciliationAudit.eventId(kind, candidate.subscriptionId)
        : ReconciliationAudit.eventId(kind, candidate.subscriptionId, result.getRunId());
    if (!audit.isDryRun()) {
      if (!lifecycle.applySubscriptionEvent(target, outcome, now)) {
        rollbackQuietly();
        result.error("subscription " + candidate.subscriptionId + ": the correction could not "
            + "be stored");
        return;
      }
      if (!audit.record(eventId, kind, candidate.requestId, BillingEventStore.RESULT_APPLIED,
          "Corrected from the provider (" + why + ")", summary("subscription",
              candidate.subscriptionId, "client", target.clientId()))) {
        return; // the same correction was already recorded; the insert rolled this one back
      }
    }
    audit.repaired("{} subscription {} of client {} ({}{})", closes ? "closed" : "set current",
        candidate.subscriptionId, target.clientId(), why,
        candidate.preferenceRoute ? ", preference route" : "");
    result.increment(closes ? Counter.SUBSCRIPTIONS_CLOSED : Counter.SUBSCRIPTIONS_CURRENT);
    if (candidate.preferenceRoute) {
      // ETP-5046-TRANSITIONAL-FALLBACK
      result.increment(Counter.PREFERENCE_ROUTE_CORRECTED);
    }
  }

  /**
   * The R37 adoption step (register §2.4): an open row without a recorded price, amount or
   * currency gets them from the subscription's first item price, only where they are NULL.
   */
  private void fillMissingPrice(SubscriptionCandidate candidate, Subscription row,
      JSONObject subscription, ReconciliationResult result, ReconciliationAudit audit) {
    if (row.getProviderPriceID() != null && row.getSnapshotAmount() != null
        && row.getSnapshotCurrency() != null) {
      return;
    }
    JSONObject price = firstItemPrice(subscription);
    if (price == null) {
      return;
    }
    String priceId = StringUtils.trimToNull(price.optString("id", ""));
    String currency = StringUtils.upperCase(StringUtils.trimToNull(price.optString("currency",
        "")), Locale.ROOT);
    BigDecimal amount = price.has("unit_amount") && !price.isNull("unit_amount") && currency != null
        ? BigDecimal.valueOf(price.optLong("unit_amount", 0L))
            .movePointLeft(StripeCurrencyScale.exponent(currency))
        : null;
    boolean fills = (row.getProviderPriceID() == null && priceId != null)
        || (row.getSnapshotAmount() == null && amount != null)
        || (row.getSnapshotCurrency() == null && currency != null);
    if (!fills) {
      return;
    }
    if (!audit.isDryRun()) {
      if (row.getProviderPriceID() == null) row.setProviderPriceID(priceId);
      if (row.getSnapshotAmount() == null) row.setSnapshotAmount(amount);
      if (row.getSnapshotCurrency() == null) row.setSnapshotCurrency(currency);
      OBDal.getInstance().save(row);
      if (!audit.record(ReconciliationAudit.eventId(KIND_SUBSCRIPTION_PRICE,
          candidate.subscriptionId), KIND_SUBSCRIPTION_PRICE, candidate.requestId,
          BillingEventStore.RESULT_APPLIED, "Missing price filled from the provider",
          summary("subscription", candidate.subscriptionId, "price", priceId, "currency",
              currency))) {
        return;
      }
    }
    audit.repaired("filled the price of subscription {} ({} {} {})", candidate.subscriptionId,
        priceId, amount, currency);
    result.increment(Counter.PRICES_FILLED);
  }

  private static JSONObject firstItemPrice(JSONObject subscription) {
    JSONObject items = subscription.optJSONObject("items");
    JSONArray data = items == null ? null : items.optJSONArray("data");
    JSONObject first = data == null || data.length() == 0 ? null : data.optJSONObject(0);
    return first == null ? null : first.optJSONObject("price");
  }

  /**
   * Closes the subscriptions the provider answered 404 for — unless the circuit breaker trips, in
   * which case none of them is closed in this run. It trips when the absent share exceeds the
   * configured maximum, or when the key's test/live mode differs from the mode an absent
   * subscription was bought in (a wrong key answers 404 for everything it does not own).
   */
  private void closeAbsent(List<SubscriptionCandidate> absent, ReconciliationResult result,
      ReconciliationAudit audit) {
    if (absent.isEmpty()) {
      return;
    }
    for (int i = 0; i < absent.size(); i++) {
      result.increment(Counter.ABSENT_SUBSCRIPTIONS);
    }
    String breaker = breakerReason(absent, result.get(Counter.SUBSCRIPTIONS_CHECKED));
    if (breaker != null) {
      result.tripBreaker(breaker);
      audit.alert("Absent-subscription breaker tripped, nothing closed: {}", breaker);
      return;
    }
    Instant now = clock.instant();
    for (SubscriptionCandidate candidate : absent) {
      try {
        SystemContext.run("closing an absent subscription", () -> {
          LifecycleTarget target = resolveTarget(candidate);
          if (target == null || target.isIgnored()) {
            return;
          }
          SubscriptionEventOutcome outcome = SubscriptionEventOutcome.apply(
              SubscriptionStatus.EXPIRED, null).closing(now);
          if (changesStoredState(target, outcome)) {
            applyOutcome(candidate, target, outcome, now, "absent at the provider", result,
                audit);
          }
        });
      } catch (RuntimeException e) {
        rollbackQuietly();
        itemFailed(result, "absent subscription " + candidate.subscriptionId, e);
      }
    }
  }

  private String breakerReason(List<SubscriptionCandidate> absent, int checked) {
    String keyMode = modeOfKey(CheckoutConfiguration.secretKey());
    for (SubscriptionCandidate candidate : absent) {
      String dataMode = modeOfSession(candidate.sessionId);
      if (dataMode != null && !dataMode.equals(keyMode)) {
        return "the configured key is " + StringUtils.defaultString(keyMode, "of unknown mode")
            + " but subscription " + candidate.subscriptionId + " was bought in " + dataMode
            + " mode";
      }
    }
    int allowed = Math.max(1, (int) Math.floor(settings.getAbsentMaxShare() * checked));
    if (absent.size() > allowed) {
      return absent.size() + " of " + checked + " subscriptions are absent, more than the "
          + allowed + " allowed";
    }
    return null;
  }

  static String modeOfKey(String key) {
    String trimmed = StringUtils.trimToEmpty(key);
    if (trimmed.startsWith("sk_live_") || trimmed.startsWith("rk_live_")) return MODE_LIVE;
    if (trimmed.startsWith("sk_test_") || trimmed.startsWith("rk_test_")) return MODE_TEST;
    return null;
  }

  static String modeOfSession(String sessionId) {
    String trimmed = StringUtils.trimToEmpty(sessionId);
    if (trimmed.startsWith("cs_live_")) return MODE_LIVE;
    if (trimmed.startsWith("cs_test_")) return MODE_TEST;
    return null;
  }

  private LifecycleTarget resolveTarget(SubscriptionCandidate candidate) {
    if (candidate.preferenceRoute) {
      // ETP-5046-TRANSITIONAL-FALLBACK — the tenant the creating request names, never one found
      // by Stripe customer: a customer can own several environments.
      return lifecycle.targetForTenant(candidate.clientId, candidate.subscriptionId);
    }
    return lifecycle.targetForSubscription(candidate.subscriptionId);
  }

  private static JSONObject syntheticEvent(JSONObject subscription, Instant now) {
    try {
      JSONObject data = new JSONObject();
      data.put("object", subscription);
      JSONObject event = new JSONObject();
      event.put("type", SUBSCRIPTION_UPDATED);
      event.put("created", now.getEpochSecond());
      event.put("data", data);
      return event;
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the reconciliation event", e);
    }
  }

  private static boolean hasOurMetadata(JSONObject subscription) {
    JSONObject metadata = subscription.optJSONObject(FIELD_METADATA);
    return metadata != null
        && StringUtils.isNotBlank(metadata.optString(FIELD_REQUEST_ID, ""));
  }

  /**
   * The locally active subscriptions: open rows naming a Stripe subscription, then
   * (ETP-5046-TRANSITIONAL-FALLBACK) provisioned purchases whose tenant has no active row at all
   * and whose preference projection is not already expired.
   */
  private List<SubscriptionCandidate> subscriptionCandidates() {
    return SystemContext.call("listing the subscriptions to reconcile", () -> {
      Map<String, SubscriptionCandidate> bySubscription = new LinkedHashMap<>();
      for (Subscription row : OBDal.getInstance().createQuery(Subscription.class,
              "as sub where sub.endDate is null and sub.active = true"
                  + " and sub.stripeSubscription is not null"
                  + " and sub.subscriptionStatus in (:active, :pastDue)")
          .setNamedParameter("active", SubscriptionService.STATUS_ACTIVE)
          .setNamedParameter("pastDue", SubscriptionService.STATUS_PAST_DUE)
          .setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .list()) {
        String subscriptionId = row.getStripeSubscription().trim();
        CheckoutRequest purchase = checkoutStore.findByStripeSubscription(subscriptionId);
        bySubscription.putIfAbsent(subscriptionId, new SubscriptionCandidate(subscriptionId,
            row.getEnvironmentClient() == null ? null : row.getEnvironmentClient().getId(),
            purchase == null ? null : purchase.getRequest(),
            purchase == null ? null : purchase.getStripeSession(), false));
      }
      for (CheckoutRequest purchase : OBDal.getInstance().createQuery(CheckoutRequest.class,
              "as cr where cr.checkoutRequestStatus = :provisioned"
                  + " and cr.createdClient is not null and cr.stripeSubscription is not null"
                  + " and not exists (select 1 from " + Subscription.ENTITY_NAME + " sub"
                  + "   where sub.environmentClient = cr.createdClient and sub.active = true)")
          .setNamedParameter("provisioned", STATUS_PROVISIONED)
          .setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .list()) {
        String subscriptionId = purchase.getStripeSubscription().trim();
        String clientId = purchase.getCreatedClient().getId();
        LifecycleTarget target = lifecycle.targetForTenant(clientId, subscriptionId);
        if (target == null || target.isIgnored() || target.subscription() != null
            || target.storedState().status() == SubscriptionStatus.EXPIRED) {
          continue;
        }
        bySubscription.putIfAbsent(subscriptionId, new SubscriptionCandidate(subscriptionId,
            clientId, purchase.getRequest(), purchase.getStripeSession(), true));
      }
      return new ArrayList<>(bySubscription.values());
    });
  }

  private ProviderRead readSubscription(String subscriptionId) {
    try {
      StripeResponse response = stripe.get("/v1/subscriptions/"
          + URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8));
      if (response.status() == 404) {
        return new ProviderRead(null, true);
      }
      if (!response.isSuccess()) {
        log.warn("{} Reading subscription {} answered {}", ReconciliationAudit.LOG_PREFIX,
            subscriptionId, response.status());
        return new ProviderRead(null, false);
      }
      return new ProviderRead(response.json(), false);
    } catch (StripeTransportException e) {
      log.warn("{} Could not read subscription {}: {}", ReconciliationAudit.LOG_PREFIX,
          subscriptionId, e.getMessage());
      return new ProviderRead(null, false);
    }
  }

  // ── Phase 5: stuck RECEIVED events ──────────────────────────────────────────────────────

  private void sweepStaleEvents(ReconciliationResult result, ReconciliationAudit audit) {
    Date before = minutesAgo(settings.getReceivedStaleMinutes());
    for (BillingEventStore.StaleEvent event : eventStore.findStaleReceived(before,
        settings.getBatchSize())) {
      String type = StringUtils.defaultString(event.eventType());
      boolean subscriptionEvent = type.startsWith("customer.subscription.")
          || type.startsWith("invoice.");
      if (subscriptionEvent && result.getBreakerReason() != null) {
        continue; // the subscription state was not re-read in full this run
      }
      if (!audit.isDryRun()) {
        eventStore.markIgnored(event.eventId(), "Superseded by billing reconciliation "
            + result.getRunId() + ": the provider state was re-read, the event not replayed");
      }
      audit.alert("Stuck provider event {} ({}) superseded; the provider state was re-read",
          event.eventId(), type);
      result.increment(Counter.STALE_EVENTS_SWEPT);
    }
  }

  // ── Shared ──────────────────────────────────────────────────────────────────────────────

  private JSONObject retrieve(String pathPrefix, String id, ReconciliationResult result) {
    try {
      StripeResponse response = stripe.get(pathPrefix
          + URLEncoder.encode(id, StandardCharsets.UTF_8));
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
        rows.add(new CheckoutRow(request.getRequest(), request.getStripeSession(),
            checkoutStore.deriveProvisioningStatus(request), request.getFailureReason(),
            request.getPaidAt(), request.getProvisioningAttempts() == null ? 0L
                : request.getProvisioningAttempts(),
            request.getCreatedAt(), checkoutStore.isProvisioningRetryAllowed(request)));
      }
      return rows;
    });
  }

  private Date minutesAgo(long minutes) {
    return new Date(clock.millis() - minutes * MILLIS_PER_MINUTE);
  }

  private static String summary(String... keysAndValues) {
    JSONObject summary = new JSONObject();
    try {
      for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
        if (keysAndValues[i + 1] != null) {
          summary.put(keysAndValues[i], keysAndValues[i + 1]);
        }
      }
    } catch (JSONException e) {
      return null;
    }
    return summary.toString();
  }

  private static void itemFailed(ReconciliationResult result, String item, RuntimeException e) {
    log.error("{} Reconciling {} failed", ReconciliationAudit.LOG_PREFIX, item, e);
    rollbackQuietly();
    result.error(item + ": " + e.getClass().getSimpleName());
  }

  private static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.debug("Rollback after a failed reconciliation step also failed", e);
    }
  }

  /** The facts of one checkout row a phase needs, read in one transaction. */
  private static final class CheckoutRow {
    private final String requestId;
    private final String sessionId;
    private final String derivedStatus;
    private final String failureReason;
    private final Date paidAt;
    private final long attempts;
    private final Date createdAt;
    private final boolean retryAllowed;

    private CheckoutRow(String requestId, String sessionId, String derivedStatus,
        String failureReason, Date paidAt, long attempts, Date createdAt, boolean retryAllowed) {
      this.requestId = requestId;
      this.sessionId = sessionId;
      this.derivedStatus = derivedStatus;
      this.failureReason = failureReason;
      this.paidAt = paidAt;
      this.attempts = attempts;
      this.createdAt = createdAt;
      this.retryAllowed = retryAllowed;
    }
  }

  /** One locally active subscription and where its state lives. */
  private static final class SubscriptionCandidate {
    private final String subscriptionId;
    private final String clientId;
    private final String requestId;
    private final String sessionId;
    private final boolean preferenceRoute;

    private SubscriptionCandidate(String subscriptionId, String clientId, String requestId,
        String sessionId, boolean preferenceRoute) {
      this.subscriptionId = subscriptionId;
      this.clientId = clientId;
      this.requestId = requestId;
      this.sessionId = sessionId;
      this.preferenceRoute = preferenceRoute;
    }
  }

  /** A direct read of one subscription: the object, or absent (404), or neither (failed). */
  private static final class ProviderRead {
    private final JSONObject object;
    private final boolean absent;

    private ProviderRead(JSONObject object, boolean absent) {
      this.object = object;
      this.absent = absent;
    }
  }
}
