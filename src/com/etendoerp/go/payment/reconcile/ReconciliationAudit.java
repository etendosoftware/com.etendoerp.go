/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.payment.BillingEventStore;

/**
 * ETP-5048 — where the billing reconciliation records what it did: one {@code ETGO_BILLING_EVENT}
 * row per repair or alert ({@code PROVIDER = 'reconciler'}, deterministic {@code EVENT_ID}), and a
 * log line with the stable {@value #LOG_PREFIX} prefix.
 *
 * <p>A dry run reaches this class too, so the log says what would have been done; it writes no row.
 */
final class ReconciliationAudit {

  /** The stable prefix of every reconciliation alert and repair log line. */
  static final String LOG_PREFIX = "[BILLING-RECONCILE]";
  /** Prefix of every {@code EVENT_TYPE} the reconciliation writes. */
  static final String EVENT_TYPE_PREFIX = "reconciliation.";
  private static final String EVENT_ID_PREFIX = "reconcile:";

  private static final Logger log = LogManager.getLogger(ReconciliationAudit.class);

  private final BillingEventStore store;
  private final boolean dryRun;

  ReconciliationAudit(BillingEventStore store, boolean dryRun) {
    this.store = store;
    this.dryRun = dryRun;
  }

  /**
   * Builds a deterministic event id: {@code reconcile:<kind>:<part>[:<part>…]}.
   *
   * @param kind the repair kind, e.g. {@code checkout-expired}
   * @param parts the identifying parts, e.g. the request id
   * @return the event id
   */
  static String eventId(String kind, String... parts) {
    StringBuilder id = new StringBuilder(EVENT_ID_PREFIX).append(kind);
    for (String part : parts) {
      id.append(':').append(part);
    }
    return id.toString();
  }

  /**
   * @param eventId a deterministic id
   * @return whether that repair or alert is already recorded
   */
  boolean alreadyRecorded(String eventId) {
    return store.exists(eventId);
  }

  /**
   * Records one repair or alert, committing the caller's pending repair with it (see
   * {@link BillingEventStore#recordReconciliation}).
   *
   * @param eventId deterministic id
   * @param kind the repair kind; the event type is {@code reconciliation.<kind>}
   * @param requestId the checkout correlation id, or null
   * @param result the event result
   * @param reason operationally safe explanation
   * @param summary provider ids and statuses, never customer data
   * @return whether the row was written (false in a dry run and when it already existed)
   */
  boolean record(String eventId, String kind, String requestId, String result, String reason,
      String summary) {
    if (dryRun) {
      return false;
    }
    return store.recordReconciliation(eventId, EVENT_TYPE_PREFIX + kind, requestId, result,
        reason, summary);
  }

  /**
   * Logs a repair (INFO) with the stable prefix.
   *
   * @param message the line after the prefix, with {@code {}} placeholders
   * @param arguments the placeholder values
   */
  void repaired(String message, Object... arguments) {
    log.info(LOG_PREFIX + (dryRun ? " [dry run] would: " : " ") + message, arguments);
  }

  /**
   * Logs an alert (WARN) with the stable prefix.
   *
   * @param message the line after the prefix, with {@code {}} placeholders
   * @param arguments the placeholder values
   */
  void alert(String message, Object... arguments) {
    log.warn(LOG_PREFIX + (dryRun ? " [dry run] " : " ") + message, arguments);
  }

  /** @return whether nothing is written */
  boolean isDryRun() {
    return dryRun;
  }
}
