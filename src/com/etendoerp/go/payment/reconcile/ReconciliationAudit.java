/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.payment.BillingEventStore;
import com.etendoerp.go.schemaforge.email.InternalAlertEvent;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

/**
 * ETP-5048 — where the billing reconciliation records what it did: one {@code ETGO_BILLING_EVENT}
 * row per repair or alert ({@code PROVIDER = 'reconciler'}, deterministic {@code EVENT_ID}), and a
 * log line with the stable {@value #LOG_PREFIX} prefix.
 *
 * <p>A dry run reaches this class too, so the log says what would have been done; it writes no row
 * and sends no internal alert.
 *
 * <p>Internal alerts (ETP-5048 S5) go to the same channel as the provisioning alerts, for what a
 * person must act on: a purchase parked for review and a tripped breaker. Each is sent only by the
 * call that wrote its deterministic row, so a re-run over the same state stays silent.
 */
final class ReconciliationAudit {

  /** The stable prefix of every reconciliation alert and repair log line. */
  static final String LOG_PREFIX = "[BILLING-RECONCILE]";
  /** Prefix of every {@code EVENT_TYPE} the reconciliation writes. */
  static final String EVENT_TYPE_PREFIX = "reconciliation.";
  private static final String EVENT_ID_PREFIX = "reconcile:";
  /** What {@link InternalAlertEvent} accepts as a token. */
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

  private static final Logger log = LogManager.getLogger(ReconciliationAudit.class);

  private static final String ALERT_ENVIRONMENT = "PRODUCTIVE";
  private static final String ALERT_PATH = "STRIPE";
  private static final String ALERT_CATEGORY = "REVIEW_REQUIRED";
  private static final String UNKNOWN_SUBJECT = "unknown";

  private final BillingEventStore store;
  private final boolean dryRun;
  private final InternalAlertService internalAlerts;

  ReconciliationAudit(BillingEventStore store, boolean dryRun) {
    this(store, dryRun, null);
  }

  /**
   * @param store where the rows go
   * @param dryRun write nothing and send nothing
   * @param internalAlerts where review alerts go, or null to send none
   */
  ReconciliationAudit(BillingEventStore store, boolean dryRun,
      InternalAlertService internalAlerts) {
    this.store = store;
    this.dryRun = dryRun;
    this.internalAlerts = internalAlerts;
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
  boolean write(String eventId, String kind, String requestId, String result, String reason,
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

  /**
   * Sends one internal review alert. Never in a dry run; never throws, since an alert must not
   * change the outcome it reports. Operational tokens only: an id that is not one is sent as
   * {@code unknown}.
   *
   * @param event the alert event token, e.g. {@code billing-reconcile-needs-review}
   * @param subjectId what it is about, e.g. the request id or the run id
   * @param stage a short reason token, e.g. the review code
   * @param clientId the environment, or null
   */
  void internalAlert(String event, String subjectId, String stage, String clientId) {
    if (dryRun || internalAlerts == null) {
      return;
    }
    try {
      internalAlerts.sendAfterTransaction(new InternalAlertEvent(event,
          InternalAlertEvent.Status.ERROR, tokenOr(subjectId),
          new InternalAlertEvent.Target(ALERT_ENVIRONMENT, tokenOrNull(clientId), ALERT_PATH),
          tokenOr(stage), ALERT_CATEGORY));
    } catch (RuntimeException e) {
      log.warn("{} Internal alert {} for {} could not be sent ({})", LOG_PREFIX, event,
          subjectId, e.getClass().getSimpleName());
    }
  }

  private static String tokenOr(String value) {
    String token = tokenOrNull(value);
    return token == null ? UNKNOWN_SUBJECT : token;
  }

  private static String tokenOrNull(String value) {
    return value != null && TOKEN.matcher(value).matches() ? value : null;
  }

  /** @return whether nothing is written */
  boolean isDryRun() {
    return dryRun;
  }
}
