/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.email.InternalAlertEvent;
import com.etendoerp.go.schemaforge.email.InternalAlertService;

/**
 * ETP-5048 — the one review alert for a Stripe subscription in status {@code paused}.
 *
 * <p>Decided by Martin on 2026-10-20: a paused subscription is <b>alert-only</b>. It never closes
 * the row and never changes any state, on any path — the {@code customer.subscription.updated}
 * webhook, the dedicated {@code customer.subscription.paused} event, and the billing
 * reconciliation. Our checkout sets no trial, so {@code paused} means somebody configured a trial
 * or a pause in Stripe by hand, and a paused subscription can be resumed; a person has to look.
 *
 * <p><b>Raised once per subscription.</b> The alert is recorded as an {@code ETGO_BILLING_EVENT}
 * row with a deterministic id ({@code reconcile:subscription-paused:<sub>}), shared by every path,
 * and only the call that writes it logs the WARN and sends the internal alert. A redelivered
 * webhook or a second reconciliation run over the same paused subscription finds the row and stays
 * silent — the zero-alerts criterion for a consistent dataset.
 */
public class PausedSubscriptionAlert {

  private static final Logger log = LogManager.getLogger(PausedSubscriptionAlert.class);

  /** The deterministic event-id kind, shared with the reconciliation's own ids. */
  public static final String KIND = "subscription-paused";
  static final String EVENT_ID_PREFIX = "reconcile:" + KIND + ":";
  static final String EVENT_TYPE = "reconciliation." + KIND;
  private static final String ALERT_EVENT = "subscription-paused-review";
  private static final String ALERT_STAGE = "trial-or-pause-configured-in-stripe";
  private static final String ALERT_CATEGORY = "REVIEW_REQUIRED";
  private static final String ALERT_ENVIRONMENT = "PRODUCTIVE";
  private static final String ALERT_PATH = "STRIPE";

  private final BillingEventStore store;
  private final InternalAlertService internalAlerts;

  /** Production wiring. */
  public PausedSubscriptionAlert() {
    this(new BillingEventStore(), new InternalAlertService());
  }

  /**
   * Wiring with explicit collaborators, for tests.
   *
   * @param store where the alert row is recorded
   * @param internalAlerts where the internal alert is sent
   */
  public PausedSubscriptionAlert(BillingEventStore store, InternalAlertService internalAlerts) {
    this.store = store;
    this.internalAlerts = internalAlerts;
  }

  /**
   * Raises the review alert for a paused subscription, unless it was raised before.
   *
   * <p>Commits the caller's pending changes with the alert row (see
   * {@link BillingEventStore#recordReconciliation}); call it where nothing else is pending.
   *
   * @param logPrefix the caller's stable log prefix, e.g. {@code [BILLING-RECONCILE]}
   * @param subscriptionId the paused {@code sub_...}
   * @param clientId the environment it belongs to, or null when not resolved
   * @param requestId the checkout request that bought it, or null
   * @param source who noticed: {@code webhook} or {@code reconciliation}
   * @return whether this call raised the alert
   */
  public boolean raiseOnce(String logPrefix, String subscriptionId, String clientId,
      String requestId, String source) {
    String id = StringUtils.trimToNull(subscriptionId);
    if (id == null) {
      return false;
    }
    String eventId = eventIdOf(id);
    if (store.exists(eventId)) {
      return false;
    }
    boolean written = store.recordReconciliation(eventId, EVENT_TYPE, requestId,
        BillingEventStore.RESULT_IGNORED,
        "Subscription paused at the provider: a trial or a pause was configured in Stripe and "
            + "needs review. No access change was made (noticed by the " + source + ")",
        "{\"subscription\":\"" + id + "\",\"source\":\"" + source + "\"}");
    if (!written) {
      return false;
    }
    log.warn("{} Subscription {} (client {}) is paused at the provider: a trial or a pause was "
        + "configured in Stripe and needs review. No access change was made.", logPrefix, id,
        StringUtils.defaultIfBlank(clientId, "-"));
    sendInternalAlert(id, clientId);
    return true;
  }

  /**
   * Builds the deterministic event id of a subscription's paused-review alert row, the id every
   * path checks before alerting.
   *
   * @param subscriptionId a {@code sub_...}
   * @return the event id
   */
  public static String eventIdOf(String subscriptionId) {
    return EVENT_ID_PREFIX + StringUtils.trimToEmpty(subscriptionId);
  }

  private void sendInternalAlert(String subscriptionId, String clientId) {
    try {
      internalAlerts.sendAfterTransaction(new InternalAlertEvent(ALERT_EVENT,
          InternalAlertEvent.Status.ERROR, subscriptionId,
          new InternalAlertEvent.Target(ALERT_ENVIRONMENT, StringUtils.trimToNull(clientId),
              ALERT_PATH),
          ALERT_STAGE, ALERT_CATEGORY));
    } catch (RuntimeException e) {
      // The alert row is already committed; a failed email must not undo or repeat it.
      log.warn("Internal alert for paused subscription {} could not be sent ({})", subscriptionId,
          e.getClass().getSimpleName());
    }
  }
}
