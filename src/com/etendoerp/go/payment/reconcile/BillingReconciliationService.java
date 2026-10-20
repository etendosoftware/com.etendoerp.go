/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.itemFailed;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.rollbackQuietly;
import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.summary;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.rest.PaidProvisioningService;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

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

  static final String CODE_NO_SESSION_ID = "NO_SESSION_ID";
  static final String CODE_DETERMINISTIC_FAILURE = "DETERMINISTIC_FAILURE";
  static final String CODE_ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";

  private static final String STATUS_CREATED = "CREATED";
  private static final String STATUS_CREATING = "CREATING";
  private static final String STATUS_PAID = "PAID";
  private static final String STATUS_PROVISIONING = "PROVISIONING";
  private static final String DERIVED_PAID = "paid";
  private static final String DERIVED_STALLED = CheckoutRequestStore.DERIVED_STATUS_STALLED;
  private static final String DERIVED_FAILED =
      CheckoutRequestStore.DERIVED_STATUS_PROVISIONING_FAILED;
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_METADATA = "metadata";
  private static final String FIELD_REQUEST_ID = "request_id";
  private static final String ITEM_CHECKOUT = "checkout ";
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
  private final SubscriptionReconciler subscriptions;
  private final Clock clock;
  /** Closes abandoned requests; package-visible so tests can substitute it. */
  CheckoutRequestWrites checkoutWrites = new CheckoutRequestWrites();

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
    ReconciliationAudit audit = new ReconciliationAudit(eventStore, dryRun);
    Set<String> recovered = new HashSet<>();
    runPhase("checkout recovery", result, () -> recoverCheckouts(result, audit, recovered));
    runPhase("provisioning repair", result, () -> repairProvisioning(result, audit, recovered));
    runPhase("no-session-id review", result, () -> reviewSessionlessCheckouts(result, audit));
    runPhase("subscriptions", result, () -> subscriptions.run(result, audit));
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
        itemFailed(result, ITEM_CHECKOUT + row.requestId, e);
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
      result.error(ITEM_CHECKOUT + row.requestId
          + ": its provider session names another request");
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
      audit.write(ReconciliationAudit.eventId(KIND_PAYMENT_RECOVERED, row.requestId),
          KIND_PAYMENT_RECOVERED, row.requestId, BillingEventStore.RESULT_APPLIED,
          "Payment confirmed by re-reading the provider; the webhook never applied it",
          summary("session", row.sessionId, "customer", customer, "subscription", subscription));
      audit.alert("Recovered a paid checkout the webhook never applied: request {} session {}",
          row.requestId, row.sessionId);
      result.increment(Counter.PAYMENTS_RECOVERED);
      recovered.add(row.requestId);
    } else if ("expired".equals(status) && row.createdAt != null
        && !row.createdAt.after(abandonedBefore)) {
      boolean closed = audit.isDryRun() || checkoutWrites.recordExpired(row.requestId);
      if (!closed) {
        return; // a payment raced the reconciliation and won; nothing to close
      }
      audit.write(ReconciliationAudit.eventId(KIND_CHECKOUT_EXPIRED, row.requestId),
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
        rows.add(CheckoutRow.recoveredInDryRun(requestId));
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
        audit.write(ReconciliationAudit.eventId(KIND_PROVISIONED, requestId), KIND_PROVISIONED,
            requestId, BillingEventStore.RESULT_APPLIED,
            "Environment provisioned by the reconciliation; the browser never completed it",
            summary("attempt", String.valueOf(outcome.attempt())));
        audit.alert("Provisioned a paid purchase with no environment: request {} (attempt {})",
            requestId, outcome.attempt());
        result.increment(Counter.PROVISIONED);
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
    audit.write(eventId, KIND_NEEDS_REVIEW, requestId, BillingEventStore.RESULT_FAILED,
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
        itemFailed(result, ITEM_CHECKOUT + row.requestId, e);
      }
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
        rows.add(new CheckoutRow(request, checkoutStore.deriveProvisioningStatus(request),
            checkoutStore.isProvisioningRetryAllowed(request)));
      }
      return rows;
    });
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

    private CheckoutRow() {
    }

    private CheckoutRow(CheckoutRequest request, String derivedStatus, boolean retryAllowed) {
      this.requestId = request.getRequest();
      this.sessionId = request.getStripeSession();
      this.derivedStatus = derivedStatus;
      this.failureReason = request.getFailureReason();
      this.paidAt = request.getPaidAt();
      this.attempts = request.getProvisioningAttempts() == null ? 0L
          : request.getProvisioningAttempts();
      this.createdAt = request.getCreatedAt();
      this.retryAllowed = retryAllowed;
    }

    /** A payment phase 1 recovered in a dry run: still {@code CREATED} in the database. */
    static CheckoutRow recoveredInDryRun(String requestId) {
      CheckoutRow row = new CheckoutRow();
      row.requestId = requestId;
      row.derivedStatus = DERIVED_PAID;
      row.retryAllowed = true;
      return row;
    }
  }

}
