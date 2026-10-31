/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.itemFailed;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.rollbackQuietly;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.summary;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.CancelAtPeriodEnd;
import com.etendoerp.go.payment.CheckoutConfiguration;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;
import com.etendoerp.go.payment.PausedSubscriptionAlert;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeCurrencyScale;
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
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Phase;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5048 — phase 4 of the billing reconciliation: the real Stripe status of every locally
 * active subscription, local to provider.
 *
 * <p>The candidates are the open rows naming a Stripe subscription, plus
 * (ETP-5046-TRANSITIONAL-FALLBACK) the provisioned purchases whose tenant has no active row at
 * all. One paginated sweep of {@code /v1/subscriptions} reads them in bulk; an id the sweep did
 * not return is read directly, and only a direct 404 makes a subscription <em>absent</em>.
 *
 * <p>A status is applied through the webhook's own {@link SubscriptionLifecycleApplier}, and only
 * when it changes the stored state (the no-op diff). {@code paused} is alert-only and
 * {@code past_due}/{@code unpaid}/{@code incomplete} are left alone. Absent subscriptions are
 * closed only behind the circuit breaker. A subscription without our {@code metadata.request_id}
 * is foreign and never touched.
 */
final class SubscriptionReconciler {

  private static final Logger log = LogManager.getLogger(SubscriptionReconciler.class);

  static final String KIND_SUBSCRIPTION_CLOSED = "subscription-closed";
  static final String KIND_SUBSCRIPTION_CURRENT = "subscription-current";
  static final String KIND_SUBSCRIPTION_PRICE = "subscription-price";
  static final String KIND_PAUSE_COLLECTION = PausedSubscriptionAlert.KIND_PAUSE_COLLECTION;
  static final String KIND_BREAKER_TRIPPED = "breaker-tripped";

  private static final String SUBSCRIPTION_UPDATED = "customer.subscription.updated";
  private static final String STATUS_ACTIVE = "active";
  private static final String STATUS_TRIALING = "trialing";
  private static final String STATUS_PAUSED = "paused";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_PAUSE_COLLECTION = "pause_collection";
  private static final String FIELD_UNIT_AMOUNT = "unit_amount";
  private static final String FIELD_METADATA = "metadata";
  private static final String FIELD_REQUEST_ID = "request_id";
  private static final String SUMMARY_SUBSCRIPTION = "subscription";
  private static final String ITEM_SUBSCRIPTION = "subscription ";
  private static final String CHECKOUT_PROVISIONED = "PROVISIONED";
  private static final String MODE_LIVE = "live";
  private static final String MODE_TEST = "test";
  private static final int SUBSCRIPTION_PAGES = 100;
  /** {@code expand[]=}, URL-encoded. */
  private static final String EXPAND_LATEST_INVOICE_PREFIX = "expand%5B%5D=";
  private static final String FIELD_LATEST_INVOICE = "latest_invoice";

  private final ReconciliationSettings settings;
  private final StripeApiClient stripe;
  private final CheckoutRequestStore checkoutStore;
  private final SubscriptionLifecycleApplier applier;
  private final TenantEnvironmentLifecycleService lifecycle;
  private final PausedSubscriptionAlert pausedAlert;
  private final Clock clock;

  SubscriptionReconciler(ReconciliationSettings settings, StripeApiClient stripe,
      CheckoutRequestStore checkoutStore, SubscriptionLifecycleApplier applier,
      TenantEnvironmentLifecycleService lifecycle, PausedSubscriptionAlert pausedAlert,
      Clock clock) {
    this.settings = settings;
    this.stripe = stripe;
    this.checkoutStore = checkoutStore;
    this.applier = applier;
    this.lifecycle = lifecycle;
    this.pausedAlert = pausedAlert;
    this.clock = clock;
  }

  /**
   * Runs phase 4.
   *
   * @param result the run's result
   * @param audit the run's audit
   */
  void run(ReconciliationResult result, ReconciliationAudit audit) {
    List<SubscriptionCandidate> candidates = candidates();
    if (candidates.isEmpty()) {
      return;
    }
    Map<String, JSONObject> listed = new HashMap<>();
    StripeListPager.Sweep sweep = sweep(candidates, listed);
    if (!sweep.isComplete()) {
      result.markIncomplete(Phase.SUBSCRIPTIONS,
          "the subscription list sweep stopped: " + sweep.getFailure());
    }
    int directReads = 0;
    List<SubscriptionCandidate> absent = new ArrayList<>();
    for (SubscriptionCandidate candidate : candidates) {
      JSONObject subscription = listed.get(candidate.subscriptionId);
      if (subscription == null && directReads >= settings.getBatchSize()) {
        result.markIncomplete(Phase.SUBSCRIPTIONS, "direct reads capped at "
            + settings.getBatchSize() + "; the rest is read by a later run");
      } else if (subscription == null) {
        directReads++;
        subscription = readDirectly(candidate, result, absent);
      }
      if (subscription != null) {
        countChecked(candidate, result);
        reconcileSafely(candidate, subscription, result, audit);
      }
    }
    if (!absent.isEmpty() && !sweep.isComplete()) {
      // Review F2: a 404 is trusted only next to a complete list sweep. Without it there is no
      // evidence the key reads our account (the breaker's floor would let one 404 close the only
      // candidate), so absent subscriptions are counted, left open, and the phase is incomplete.
      absent.forEach(candidate -> {
        result.increment(Counter.ABSENT_SUBSCRIPTIONS);
        result.item(candidate.subscriptionId, candidate.clientName, "leave open",
            "404 at the provider, unverified: the list sweep did not complete");
      });
      result.markIncomplete(Phase.SUBSCRIPTIONS, absent.size() + " subscription(s) answered 404 "
          + "but were not closed: the list sweep did not complete, so the absence is unverified");
      audit.alert("{} absent subscription(s) left open: the list sweep did not complete",
          absent.size());
      return;
    }
    closeAbsent(absent, sweep.isComplete() && listed.isEmpty(), result, audit);
  }

  /**
   * Pages through every subscription of the account, keeping the candidates it returns by id in
   * {@code listed}. The latest invoice is expanded so a correction can be ordered on the provider's
   * clock ({@link SubscriptionLifecycleApplier#providerStateAt}).
   *
   * @return how the sweep ended
   */
  private StripeListPager.Sweep sweep(List<SubscriptionCandidate> candidates,
      Map<String, JSONObject> listed) {
    Set<String> wanted = new HashSet<>();
    for (SubscriptionCandidate candidate : candidates) {
      wanted.add(candidate.subscriptionId);
    }
    return new StripeListPager(stripe, StripeListPager.MAX_PAGE_SIZE, SUBSCRIPTION_PAGES)
        .forEach("/v1/subscriptions?status=all&" + EXPAND_LATEST_INVOICE_PREFIX + "data."
            + FIELD_LATEST_INVOICE, subscription -> {
              String id = subscription.optString("id", "");
              if (wanted.contains(id)) {
                listed.put(id, subscription);
              }
            });
  }

  /**
   * Reads one subscription the sweep did not return. A 404 is counted and collected as absent; a
   * failed read is counted as a provider error.
   *
   * @return the subscription, or null when it is absent or could not be read
   */
  private JSONObject readDirectly(SubscriptionCandidate candidate, ReconciliationResult result,
      List<SubscriptionCandidate> absent) {
    ProviderRead read = readSubscription(candidate.subscriptionId);
    if (read.absent) {
      countChecked(candidate, result);
      absent.add(candidate);
    } else if (read.object == null) {
      result.increment(Counter.PROVIDER_ERRORS);
    }
    return read.object;
  }

  private void reconcileSafely(SubscriptionCandidate candidate, JSONObject subscription,
      ReconciliationResult result, ReconciliationAudit audit) {
    try {
      if (reconcile(candidate, subscription, result, audit)) {
        result.noteSubscriptionReread(candidate.subscriptionId,
            subscription.optString(FIELD_STATUS, ""));
      }
    } catch (RuntimeException e) {
      itemFailed(result, ITEM_SUBSCRIPTION + candidate.subscriptionId, e);
    }
  }

  private static void countChecked(SubscriptionCandidate candidate, ReconciliationResult result) {
    result.increment(Counter.SUBSCRIPTIONS_CHECKED);
    if (candidate.preferenceRoute) {
      // ETP-5046-TRANSITIONAL-FALLBACK
      result.increment(Counter.PREFERENCE_ROUTE_CHECKED);
    }
  }

  /**
   * Reconciles one subscription with what the provider says.
   *
   * @return whether the stored state now agrees with the provider, or the status is one this phase
   *     deliberately leaves alone (alert-only, or the webhook's grace logic): only then may a stuck
   *     event of this subscription be superseded on its strength. False for a foreign
   *     subscription, for a target the webhook would also ignore, for a correction ignored as
   *     stale, and for a correction that could not be stored
   */
  private boolean reconcile(SubscriptionCandidate candidate, JSONObject subscription,
      ReconciliationResult result, ReconciliationAudit audit) {
    if (!hasOurMetadata(subscription)) {
      log.debug("{} Subscription {} carries no request_id metadata; foreign, not touched",
          ReconciliationAudit.LOG_PREFIX, candidate.subscriptionId);
      return false;
    }
    if (!audit.isDryRun()) {
      // A pause alerts once per episode: an episode the provider shows has ended is closed here,
      // so the next pause alerts again (PausedSubscriptionAlert#endResolved).
      // Must run before every "return true": BillingReconciliationService#subscriptionEventVerdict
      // supersedes a stuck resumed event on the strength of it.
      pausedAlert.endResolved(candidate.subscriptionId, subscription);
    }
    String status = subscription.optString(FIELD_STATUS, "");
    boolean paying = STATUS_ACTIVE.equals(status) || STATUS_TRIALING.equals(status);
    if (paying && subscription.has(FIELD_PAUSE_COLLECTION)
        && !subscription.isNull(FIELD_PAUSE_COLLECTION)) {
      alertPauseCollection(candidate, subscription, result, audit);
    }
    if (STATUS_PAUSED.equals(status)) {
      // Alert only, never a state change (decided 2026-10-20): see PausedSubscriptionAlert.
      alertPaused(candidate, result, audit);
      return true;
    }
    if (!paying && !"canceled".equals(status) && !"incomplete_expired".equals(status)) {
      // past_due / unpaid stay with the webhook's grace logic; incomplete is still being paid.
      return true;
    }
    // The provider's clock, never this server's: see SubscriptionLifecycleApplier#providerStateAt.
    Instant providerAt = SubscriptionLifecycleApplier.providerStateAt(subscription);
    return SystemContext.call("reconciling a subscription",
        () -> applyProviderStatus(candidate, subscription, status, providerAt, result, audit));
  }

  /** @return whether the stored state agrees with the provider afterwards; see reconcile */
  private boolean applyProviderStatus(SubscriptionCandidate candidate, JSONObject subscription,
      String status, Instant providerAt, ReconciliationResult result,
      ReconciliationAudit audit) {
    LifecycleTarget target = resolveTarget(candidate);
    if (target == null || target.isIgnored()) {
      return false;
    }
    SubscriptionEventOutcome outcome = applier.evaluate(SUBSCRIPTION_UPDATED,
        syntheticEvent(subscription, providerAt), target.storedState());
    if (outcome.isIgnored()) {
      // Typically stale: the stored watermark is newer than what the provider asserted. The stored
      // state may still disagree (a PAST_DUE row the provider reports active), so nothing is
      // vouched for and a stuck event of this subscription is parked rather than superseded.
      return false;
    }
    if (changesStoredState(target, outcome)) {
      return applyOutcome(candidate, target, outcome, providerAt, "provider status " + status,
          result, audit);
    }
    // Only a still-open row gets here: every EXPIRED outcome closes the row, and a close is a
    // change (changesStoredState), so it already took the applyOutcome path above.
    if (target.subscription() != null) {
      fillMissingPrice(candidate, target.subscription(), subscription, result, audit);
      syncCancelAtPeriodEnd(candidate, target.subscription(), outcome, result, audit);
    }
    return true;
  }

  /**
   * ETP-5051 (O-1b) — keeps the display-only {@code CANCEL_AT_PERIOD_END} of a consistent open row
   * in step with the provider. A status repair already carries the flag through the outcome
   * ({@code applyLifecycleOutcome}); this covers the no-op diff, which writes nothing else. It is
   * also how rows created before the column existed (all 'N') pick up a scheduled cancellation:
   * every run re-reads each paying subscription anyway, so the backfill costs no extra call.
   * No audit row: the flag decides no access, grace or status, and it may legitimately toggle;
   * the change is logged like any other repair ({@code audit.repaired}).
   */
  private static void syncCancelAtPeriodEnd(SubscriptionCandidate candidate, Subscription row,
      SubscriptionEventOutcome outcome, ReconciliationResult result, ReconciliationAudit audit) {
    CancelAtPeriodEnd cancel = outcome.cancelAtPeriodEnd();
    if (cancel.leavesUnchanged(row.isCancelAtPeriodEnd())) {
      return;
    }
    if (!audit.isDryRun()) {
      SubscriptionService.writeCancelAtPeriodEnd(row, cancel);
      OBDal.getInstance().save(row);
    }
    audit.repaired("synced the cancel-at-period-end flag of subscription {} to {}",
        candidate.subscriptionId, cancel.isSet() ? "Y" : "N");
    result.increment(Counter.CANCEL_FLAGS_SYNCED);
    result.item(candidate.subscriptionId, candidate.clientName,
        "sync the cancel-at-period-end flag", cancel.isSet() ? "Y" : "N");
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
    result.item(candidate.subscriptionId, candidate.clientName, "raise the paused review alert",
        "paused at the provider; access unchanged");
  }

  private static void alertPauseCollection(SubscriptionCandidate candidate,
      JSONObject subscription, ReconciliationResult result, ReconciliationAudit audit) {
    String eventId = PausedSubscriptionAlert.pauseCollectionEventIdOf(candidate.subscriptionId);
    if (audit.alreadyRecorded(eventId)) {
      return;
    }
    JSONObject pause = subscription.optJSONObject(FIELD_PAUSE_COLLECTION);
    String behavior = pause == null ? "-" : pause.optString("behavior", "-");
    audit.write(eventId, KIND_PAUSE_COLLECTION, candidate.requestId,
        BillingEventStore.RESULT_IGNORED,
        "Collection is paused at the provider (" + behavior + "); no access change was made",
        summary(SUMMARY_SUBSCRIPTION, candidate.subscriptionId, "behavior", behavior));
    audit.alert("Subscription {} has collection paused at the provider ({}); review it in the "
        + "Stripe dashboard", candidate.subscriptionId, behavior);
    result.increment(Counter.PAUSE_COLLECTION_ALERTS);
    result.item(candidate.subscriptionId, candidate.clientName,
        "raise the paused-collection alert", "collection paused (" + behavior + ")");
  }

  /**
   * The no-op diff: only a status change or a close is a repair. A refreshed billing period or
   * event watermark alone is never written, so a consistent subscription costs nothing.
   */
  private static boolean changesStoredState(LifecycleTarget target,
      SubscriptionEventOutcome outcome) {
    if (outcome.closesSubscription() && target.subscription() != null) {
      return true; // a row target is an open row by construction
    }
    return effective(target.storedState().status()) != effective(outcome.status());
  }

  /** "No status yet" and the legacy entitlement both read as paying, like the access policy. */
  private static SubscriptionStatus effective(SubscriptionStatus status) {
    if (status == null || status == SubscriptionStatus.LEGACY_ENTITLEMENT
        || status == SubscriptionStatus.NONE) {
      return SubscriptionStatus.CURRENT;
    }
    return status;
  }

  /**
   * Stores one correction with its audit row.
   *
   * @param eventAt the provider instant the correction is ordered at, stored as the watermark;
   *     null leaves the watermark as it is (an absent subscription has no provider instant)
   */
  private boolean applyOutcome(SubscriptionCandidate candidate, LifecycleTarget target,
      SubscriptionEventOutcome outcome, Instant eventAt, String why, ReconciliationResult result,
      ReconciliationAudit audit) {
    boolean closes = outcome.status() == SubscriptionStatus.EXPIRED;
    String kind = closes ? KIND_SUBSCRIPTION_CLOSED : KIND_SUBSCRIPTION_CURRENT;
    String eventId = closes
        ? ReconciliationAudit.eventId(kind, candidate.subscriptionId)
        : ReconciliationAudit.eventId(kind, candidate.subscriptionId, result.getRunId());
    if (!audit.isDryRun()) {
      if (!lifecycle.applySubscriptionEvent(target, outcome, eventAt)) {
        rollbackQuietly();
        result.error(ITEM_SUBSCRIPTION + candidate.subscriptionId + ": the correction could not "
            + "be stored");
        return false;
      }
      if (!audit.write(eventId, kind, candidate.requestId, BillingEventStore.RESULT_APPLIED,
          "Corrected from the provider (" + why + ")", summary(SUMMARY_SUBSCRIPTION,
              candidate.subscriptionId, "client", target.clientId()))) {
        // The insert rolled the correction back with it. A close is recorded once per
        // subscription, so finding it again means the subscription was closed by an earlier run
        // and is open again: a person has to look, it is never closed a second time silently.
        result.error(ITEM_SUBSCRIPTION + candidate.subscriptionId + ": correction " + eventId
            + " is already recorded, so it was not applied again; review it");
        audit.alert("Subscription {} of client {} needs review: correction {} is already "
            + "recorded and was not applied again", candidate.subscriptionId, target.clientId(),
            eventId);
        return false;
      }
    }
    audit.repaired("{} subscription {} of client {} ({}{})", closes ? "closed" : "set current",
        candidate.subscriptionId, target.clientId(), why,
        candidate.preferenceRoute ? ", preference route" : "");
    result.increment(closes ? Counter.SUBSCRIPTIONS_CLOSED : Counter.SUBSCRIPTIONS_CURRENT);
    result.item(candidate.subscriptionId, candidate.clientName,
        closes ? "close (expire) the subscription" : "set the subscription back to current",
        why);
    if (candidate.preferenceRoute) {
      // ETP-5046-TRANSITIONAL-FALLBACK
      result.increment(Counter.PREFERENCE_ROUTE_CORRECTED);
    }
    return true;
  }

  /**
   * The R37 adoption step (register §2.3): an open row without a recorded price, amount or
   * currency gets them from the subscription's first item price, only where they are NULL.
   */
  private void fillMissingPrice(SubscriptionCandidate candidate, Subscription row,
      JSONObject subscription, ReconciliationResult result, ReconciliationAudit audit) {
    ProviderPrice price = ProviderPrice.of(subscription);
    if (price == null || !price.fillsSomethingOn(row)) {
      return;
    }
    if (!audit.isDryRun()) {
      price.applyTo(row);
      OBDal.getInstance().save(row);
      if (!audit.write(ReconciliationAudit.eventId(KIND_SUBSCRIPTION_PRICE,
          candidate.subscriptionId), KIND_SUBSCRIPTION_PRICE, candidate.requestId,
          BillingEventStore.RESULT_APPLIED, "Missing price filled from the provider",
          summary(SUMMARY_SUBSCRIPTION, candidate.subscriptionId, "price", price.priceId,
              "currency", price.currency))) {
        // The failed insert rolled the fill back with it; a later run retries it.
        result.error(ITEM_SUBSCRIPTION + candidate.subscriptionId + ": the price fill was rolled "
            + "back because its audit row could not be written");
        return;
      }
    }
    audit.repaired("filled the price of subscription {} ({} {} {})", candidate.subscriptionId,
        price.priceId, price.amount, price.currency);
    result.increment(Counter.PRICES_FILLED);
    result.item(candidate.subscriptionId, candidate.clientName, "fill the missing price",
        price.priceId);
  }

  /**
   * Closes the subscriptions the provider answered 404 for — unless the circuit breaker trips, in
   * which case none of them is closed in this run. It trips when the absent share exceeds the
   * configured maximum, or when the key's test/live mode differs from the mode an absent
   * subscription was bought in (a wrong key answers 404 for everything it does not own).
   */
  private void closeAbsent(List<SubscriptionCandidate> absent, boolean sweepFoundNone,
      ReconciliationResult result, ReconciliationAudit audit) {
    if (absent.isEmpty()) {
      return;
    }
    absent.forEach(candidate -> result.increment(Counter.ABSENT_SUBSCRIPTIONS));
    String breaker = breakerReason(absent, sweepFoundNone,
        result.get(Counter.SUBSCRIPTIONS_CHECKED));
    if (breaker != null) {
      result.tripBreaker(breaker);
      result.item("breaker", null, "close no absent subscription", breaker);
      result.markIncomplete(Phase.SUBSCRIPTIONS, "the absent-subscription breaker tripped");
      audit.alert("Absent-subscription breaker tripped, nothing closed: {}", breaker);
      alertBreakerOncePerDay(breaker, result, audit);
      return;
    }
    Instant now = clock.instant();
    for (SubscriptionCandidate candidate : absent) {
      try {
        SystemContext.run("closing an absent subscription",
            () -> closeOneAbsent(candidate, now, result, audit));
      } catch (RuntimeException e) {
        itemFailed(result, "absent subscription " + candidate.subscriptionId, e);
      }
    }
  }

  /**
   * Sends the internal alert of a tripped breaker once per UTC day: the breaker keeps tripping on
   * every run until a person fixes the key, and one alert a day says so without flooding.
   */
  private void alertBreakerOncePerDay(String breaker, ReconciliationResult result,
      ReconciliationAudit audit) {
    String day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
    if (audit.write(ReconciliationAudit.eventId(KIND_BREAKER_TRIPPED, day), KIND_BREAKER_TRIPPED,
        null, BillingEventStore.RESULT_IGNORED,
        "Absent-subscription breaker tripped, nothing closed: " + breaker,
        summary("run", result.getRunId()))) {
      audit.internalAlert("billing-reconcile-breaker-tripped", result.getRunId(),
          "absent-subscription-breaker", null);
    }
  }

  private void closeOneAbsent(SubscriptionCandidate candidate, Instant now,
      ReconciliationResult result, ReconciliationAudit audit) {
    LifecycleTarget target = resolveTarget(candidate);
    if (target == null || target.isIgnored()) {
      return;
    }
    // An absent subscription has no provider instant: the row is closed now, and the watermark
    // is left as it is rather than moved to this server's clock.
    SubscriptionEventOutcome outcome = SubscriptionEventOutcome.apply(
        SubscriptionStatus.EXPIRED, null).closing(now);
    if (changesStoredState(target, outcome)) {
      applyOutcome(candidate, target, outcome, null, "absent at the provider", result, audit);
    }
  }

  private String breakerReason(List<SubscriptionCandidate> absent, boolean sweepFoundNone,
      int checked) {
    String keyMode = modeOfKey(CheckoutConfiguration.secretKey());
    for (SubscriptionCandidate candidate : absent) {
      String dataMode = modeOfSession(candidate.sessionId);
      if (dataMode != null && !dataMode.equals(keyMode)) {
        return "the configured key is " + StringUtils.defaultString(keyMode, "of unknown mode")
            + " but subscription " + candidate.subscriptionId + " was bought in " + dataMode
            + " mode";
      }
    }
    if (sweepFoundNone) {
      // A complete sweep of the account that returned none of our subscriptions: the key reads
      // an account that is not ours, even in the same mode. Stripe lists canceled subscriptions
      // too, so on the right account every id we hold is listed. Without this, the floor of one
      // allowed absence below would let a foreign key close a tenant that is the only one.
      return "the provider's subscription list returned none of the local subscriptions; the "
          + "configured key reads another account";
    }
    int allowed = Math.max(1, (int) Math.floor(settings.getAbsentMaxShare() * checked));
    if (absent.size() > allowed) {
      return ReconciliationResult.counted(absent.size(), "%d of ")
          + ReconciliationResult.counted(checked, "%d {subscription|subscriptions} ")
          + ReconciliationResult.counted(absent.size(), "{is|are} absent, more than the ")
          + allowed + " allowed";
    }
    return null;
  }

  /**
   * The test/live mode of a Stripe secret or restricted key.
   *
   * @param key the configured key
   * @return {@code live}, {@code test}, or null when the key has neither prefix
   */
  static String modeOfKey(String key) {
    String trimmed = StringUtils.trimToEmpty(key);
    if (trimmed.startsWith("sk_live_") || trimmed.startsWith("rk_live_")) {
      return MODE_LIVE;
    }
    if (trimmed.startsWith("sk_test_") || trimmed.startsWith("rk_test_")) {
      return MODE_TEST;
    }
    return null;
  }

  /**
   * The test/live mode a Checkout Session was created in, read from its id.
   *
   * @param sessionId a {@code cs_...} id
   * @return {@code live}, {@code test}, or null when the id has neither prefix
   */
  static String modeOfSession(String sessionId) {
    String trimmed = StringUtils.trimToEmpty(sessionId);
    if (trimmed.startsWith("cs_live_")) {
      return MODE_LIVE;
    }
    if (trimmed.startsWith("cs_test_")) {
      return MODE_TEST;
    }
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

  /** The re-read subscription as a {@code customer.subscription.updated} event; see the applier. */
  private static JSONObject syntheticEvent(JSONObject subscription, Instant providerAt) {
    return SubscriptionLifecycleApplier.syntheticSubscriptionUpdated(subscription, providerAt);
  }

  private static boolean hasOurMetadata(JSONObject subscription) {
    JSONObject metadata = subscription.optJSONObject(FIELD_METADATA);
    return metadata != null
        && StringUtils.isNotBlank(metadata.optString(FIELD_REQUEST_ID, ""));
  }

  /** The locally active subscriptions, on both routes, one candidate per Stripe id. */
  private List<SubscriptionCandidate> candidates() {
    return SystemContext.call("listing the subscriptions to reconcile", () -> {
      Map<String, SubscriptionCandidate> bySubscription = new LinkedHashMap<>();
      addRowCandidates(bySubscription);
      addPreferenceCandidates(bySubscription);
      return new ArrayList<>(bySubscription.values());
    });
  }

  /** Open rows naming a Stripe subscription. Caller provides the system context. */
  private void addRowCandidates(Map<String, SubscriptionCandidate> bySubscription) {
    for (Subscription row : OBDal.getInstance().createQuery(Subscription.class,
            "as sub where sub.endDate is null and sub.active = true"
                + " and sub.stripeSubscription is not null"
                + " and sub.subscriptionStatus in (:activeStatus, :pastDueStatus)")
        .setNamedParameter("activeStatus", SubscriptionService.STATUS_ACTIVE)
        .setNamedParameter("pastDueStatus", SubscriptionService.STATUS_PAST_DUE)
        .setFilterOnReadableClients(false)
        .setFilterOnReadableOrganization(false)
        .list()) {
      String subscriptionId = row.getStripeSubscription().trim();
      CheckoutRequest purchase = checkoutStore.findByStripeSubscription(subscriptionId);
      bySubscription.putIfAbsent(subscriptionId, new SubscriptionCandidate(subscriptionId,
          row.getEnvironmentClient() == null ? null : row.getEnvironmentClient().getId(),
          purchase, false));
    }
  }

  /**
   * ETP-5046-TRANSITIONAL-FALLBACK — provisioned purchases whose tenant has no active row at all
   * and whose preference projection is not already expired. Caller provides the system context.
   *
   * <p><b>Only the latest purchase of each tenant.</b> The preference projection holds no
   * subscription id, so {@code targetForTenant} answers the same projection for every purchase of
   * the tenant. A tenant re-subscribed after a cancellation (ETP-5742) has two provisioned
   * purchases: the canceled one and the one it pays now. Reading the canceled one would expire a
   * paying tenant, so a purchase a later purchase of the same tenant supersedes is never a
   * candidate.
   */
  private void addPreferenceCandidates(Map<String, SubscriptionCandidate> bySubscription) {
    Set<String> tenantsSeen = new HashSet<>();
    for (CheckoutRequest purchase : OBDal.getInstance().createQuery(CheckoutRequest.class,
            "as cr where cr.checkoutRequestStatus = :provisionedStatus"
                + " and cr.createdClient is not null and cr.stripeSubscription is not null"
                + " and not exists (select 1 from " + Subscription.ENTITY_NAME + " sub"
                + "   where sub.environmentClient = cr.createdClient and sub.active = true)"
                + " order by coalesce(cr.paidAt, cr.creationDate) desc, cr.creationDate desc")
        .setNamedParameter("provisionedStatus", CHECKOUT_PROVISIONED)
        .setFilterOnReadableClients(false)
        .setFilterOnReadableOrganization(false)
        .list()) {
      String subscriptionId = purchase.getStripeSubscription().trim();
      String clientId = purchase.getCreatedClient().getId();
      if (!tenantsSeen.add(clientId)) {
        continue; // superseded by a later purchase of the same tenant
      }
      LifecycleTarget target = lifecycle.targetForTenant(clientId, subscriptionId);
      boolean onPreferences = target != null && !target.isIgnored()
          && target.subscription() == null
          && target.storedState().status() != SubscriptionStatus.EXPIRED;
      if (onPreferences) {
        bySubscription.putIfAbsent(subscriptionId,
            new SubscriptionCandidate(subscriptionId, clientId, purchase, true));
      }
    }
  }

  private ProviderRead readSubscription(String subscriptionId) {
    try {
      StripeResponse response = stripe.get("/v1/subscriptions/"
          + URLEncoder.encode(subscriptionId, StandardCharsets.UTF_8) + "?"
          + EXPAND_LATEST_INVOICE_PREFIX + FIELD_LATEST_INVOICE);
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

  /** One locally active subscription and where its state lives. */
  private static final class SubscriptionCandidate {
    private final String subscriptionId;
    private final String clientId;
    private final String requestId;
    private final String sessionId;
    private final boolean preferenceRoute;
    private final String clientName;

    private SubscriptionCandidate(String subscriptionId, String clientId,
        CheckoutRequest purchase, boolean preferenceRoute) {
      this.subscriptionId = subscriptionId;
      this.clientId = clientId;
      this.clientName = purchase == null ? null : purchase.getClientName();
      this.requestId = purchase == null ? null : purchase.getRequest();
      this.sessionId = purchase == null ? null : purchase.getStripeSession();
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

  /** The price of a subscription's first item, as the row records it. */
  private static final class ProviderPrice {
    private final String priceId;
    private final String currency;
    private final BigDecimal amount;

    private ProviderPrice(String priceId, String currency, BigDecimal amount) {
      this.priceId = priceId;
      this.currency = currency;
      this.amount = amount;
    }

    static ProviderPrice of(JSONObject subscription) {
      JSONObject items = subscription.optJSONObject("items");
      JSONArray data = items == null ? null : items.optJSONArray("data");
      JSONObject first = data == null || data.length() == 0 ? null : data.optJSONObject(0);
      JSONObject price = first == null ? null : first.optJSONObject("price");
      if (price == null) {
        return null;
      }
      String currency = StringUtils.upperCase(StringUtils.trimToNull(price.optString("currency",
          "")), Locale.ROOT);
      BigDecimal amount = null;
      if (currency != null && price.has(FIELD_UNIT_AMOUNT) && !price.isNull(FIELD_UNIT_AMOUNT)) {
        amount = BigDecimal.valueOf(price.optLong(FIELD_UNIT_AMOUNT, 0L))
            .movePointLeft(StripeCurrencyScale.exponent(currency));
      }
      return new ProviderPrice(StringUtils.trimToNull(price.optString("id", "")), currency,
          amount);
    }

    boolean fillsSomethingOn(Subscription row) {
      return (row.getProviderPriceID() == null && priceId != null)
          || (row.getSnapshotAmount() == null && amount != null)
          || (row.getSnapshotCurrency() == null && currency != null);
    }

    void applyTo(Subscription row) {
      if (row.getProviderPriceID() == null) {
        row.setProviderPriceID(priceId);
      }
      if (row.getSnapshotAmount() == null) {
        row.setSnapshotAmount(amount);
      }
      if (row.getSnapshotCurrency() == null) {
        row.setSnapshotCurrency(currency);
      }
    }
  }
}
