/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import static com.etendoerp.go.payment.reconcile.ReconciliationSupport.summary;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.payment.reconcile.ReconciliationResult.Counter;
import com.etendoerp.go.schemaforge.data.BillingEvent;

/**
 * ETP-5048 — the purchases the billing reconciliation parked for a person: one
 * {@code reconciliation.needs-review} row per parking, {@code FAILED} while it is open.
 *
 * <p><b>A parked purchase leaves the batch.</b> The candidate queries exclude it
 * ({@link #NOT_PARKED}), so rows no run can repair never fill the fixed-size batch and starve the
 * rows behind them. The run still reports how many wait ({@link #countAwaiting}).
 *
 * <p><b>Release.</b> Once the cause is fixed, an operator sets the open row to {@code IGNORED}
 * ({@code docs/billing-reconciliation.md} §7.1). The next run picks the purchase up again. A
 * parking for exhausted attempts records the attempt count it was parked at, and a released
 * purchase gets the full attempt budget again from there ({@link #attemptsBaseline}); a second
 * parking writes a new row ({@code reconcile:needs-review:<request>:2}, …), so the history
 * stays.
 */
final class ReviewQueue {

  static final String KIND = "needs-review";
  static final String EVENT_TYPE = ReconciliationAudit.EVENT_TYPE_PREFIX + KIND;
  private static final String SUMMARY_ATTEMPTS = "attempts";
  private static final String ALERT_EVENT = "billing-reconcile-needs-review";

  /** Review rows this run wrote; the queue lives for one run. */
  private int writtenThisRun;

  /**
   * HQL condition, on a checkout request aliased {@code cr}: it has no open review row. Binds
   * {@code :reviewProvider}, {@code :reviewType} and {@code :reviewOpen} ({@link #bind}).
   */
  static final String NOT_PARKED = "not exists (select 1 from " + BillingEvent.ENTITY_NAME + " rv"
      + " where rv.requestID = cr.request and rv.provider = :reviewProvider"
      + " and rv.eventType = :reviewType and rv.eventResult = :reviewOpen)";

  private final BillingEventStore store;

  ReviewQueue(BillingEventStore store) {
    this.store = store;
  }

  /**
   * Adds the parameters {@link #NOT_PARKED} binds.
   *
   * @param parameters a query's named parameters
   */
  static void bind(Map<String, Object> parameters) {
    parameters.put("reviewProvider", BillingEventStore.PROVIDER_RECONCILER);
    parameters.put("reviewType", EVENT_TYPE);
    parameters.put("reviewOpen", BillingEventStore.RESULT_FAILED);
  }

  /**
   * {@link #park(String, String, String, String, long, ReconciliationResult, ReconciliationAudit)}
   * for a purchase whose company name is not at hand.
   */
  void park(String requestId, String code, String detail, long attempts,
      ReconciliationResult result, ReconciliationAudit audit) {
    park(requestId, null, code, detail, attempts, result, audit);
  }

  /**
   * Parks one purchase for review, once: a purchase already parked is left as it is. Writes the
   * row, logs the alert and, when the row was written, sends the internal alert.
   *
   * @param requestId the purchase
   * @param clientName the company name, for the run's item list; may be null
   * @param code a stable reason code
   * @param detail an operationally safe explanation
   * @param attempts the provisioning attempts made so far, recorded for a later release
   * @param result the run's result
   * @param audit the run's audit
   */
  void park(String requestId, String clientName, String code, String detail, long attempts,
      ReconciliationResult result, ReconciliationAudit audit) {
    if (store.hasOpenReconciliation(EVENT_TYPE, requestId)) {
      return; // parked before; counted by countAwaiting
    }
    int earlier = store.countReconciliations(EVENT_TYPE, requestId);
    String eventId = earlier == 0 ? ReconciliationAudit.eventId(KIND, requestId)
        : ReconciliationAudit.eventId(KIND, requestId, String.valueOf(earlier + 1));
    boolean written = audit.write(eventId, KIND, requestId, BillingEventStore.RESULT_FAILED,
        "Needs manual review (" + code + "): " + StringUtils.defaultString(detail),
        summary("code", code, SUMMARY_ATTEMPTS, String.valueOf(attempts)));
    audit.alert("Purchase {} needs manual review ({}): {}", requestId, code, detail);
    if (written) {
      writtenThisRun++;
      audit.internalAlert(ALERT_EVENT, requestId, code, null);
    }
    result.increment(Counter.NEEDS_REVIEW);
    result.item(ReconciliationResult.shortRequest(requestId), clientName, "park for review",
        code);
  }

  /**
   * The attempt count a released purchase was parked at, from which it gets the full attempt
   * budget again.
   *
   * @param requestId the purchase
   * @return the count, or 0 when it was never parked and released
   */
  long attemptsBaseline(String requestId) {
    String released = store.latestReconciliationSummary(EVENT_TYPE, requestId,
        BillingEventStore.RESULT_IGNORED);
    if (StringUtils.isBlank(released)) {
      return 0L;
    }
    try {
      return Long.parseLong(new JSONObject(released).optString(SUMMARY_ATTEMPTS, "0"));
    } catch (JSONException | NumberFormatException e) {
      return 0L;
    }
  }

  /**
   * The purchases parked by earlier runs and still open. A row this run parked is already counted
   * as a new review, so it is left out here: one parked purchase never reads as two.
   *
   * @return how many purchases earlier runs parked that are not yet released
   */
  int countAwaiting() {
    return Math.max(0, store.countOpenReconciliations(EVENT_TYPE) - writtenThisRun);
  }
}
