/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

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
 * <p><b>Raised once per pause</b> (decided by Martin, 2026-10-21; it was once per subscription).
 * The open episode is an {@code ETGO_BILLING_EVENT} row with a deterministic id
 * ({@code reconcile:subscription-paused:<sub>}), shared by every path, and only the call that
 * writes it logs the WARN and sends the internal alert. A redelivered webhook or a second
 * reconciliation run within the same pause finds the row and stays silent — the zero-alerts
 * criterion for a consistent dataset.
 *
 * <p><b>The episode ends when the subscription is seen not paused</b> ({@link #endResolved}): the
 * webhook on {@code customer.subscription.updated} and {@code customer.subscription.resumed}, and
 * the reconciliation on every re-read. Ending renames the open row to
 * {@code <id>:ended:<epoch millis>}, which keeps it as history and frees the id, so the next pause
 * writes it again and alerts again. The same holds for paused <em>collection</em>
 * ({@code reconcile:pause-collection:<sub>}, raised by the reconciliation), which ends when
 * {@code pause_collection} is cleared.
 *
 * <p>Why ending on an observed resume rather than an id derived from the pause's start: Stripe
 * gives a {@code paused} status no start instant on the subscription, and {@code pause_collection}
 * none at all, while the reconciliation, unlike the webhook, has no event time to use. A resume
 * missed by every path (the subscription paused, resumed and paused again between two reads, and
 * the webhooks lost) folds two pauses into one alert: one alert too few, never one too many.
 */
public class PausedSubscriptionAlert {

  private static final Logger log = LogManager.getLogger(PausedSubscriptionAlert.class);

  /** The deterministic event-id kind, shared with the reconciliation's own ids. */
  public static final String KIND = "subscription-paused";
  static final String EVENT_ID_PREFIX = "reconcile:" + KIND + ":";
  /** The paused-collection episode kind, raised by the reconciliation. */
  public static final String KIND_PAUSE_COLLECTION = "pause-collection";
  private static final String STATUS_PAUSED = "paused";
  private static final String FIELD_PAUSE_COLLECTION = "pause_collection";
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

  /**
   * Builds the deterministic event id of a subscription's open paused-collection episode.
   *
   * @param subscriptionId a {@code sub_...}
   * @return the event id
   */
  public static String pauseCollectionEventIdOf(String subscriptionId) {
    return "reconcile:" + KIND_PAUSE_COLLECTION + ":" + StringUtils.trimToEmpty(subscriptionId);
  }

  /**
   * Ends the open pause episodes the subscription has left: the paused one when its status is not
   * {@code paused}, the paused-collection one when {@code pause_collection} is cleared. A
   * subscription with no open episode costs one lookup per kind and no write.
   *
   * <p>Commits on its own; call it where nothing else is pending.
   *
   * @param subscriptionId the {@code sub_...}
   * @param subscription the subscription as the provider reports it now
   * @return how many episodes this call ended
   */
  public int endResolved(String subscriptionId, JSONObject subscription) {
    String id = StringUtils.trimToNull(subscriptionId);
    if (id == null || subscription == null) {
      return 0;
    }
    int ended = 0;
    if (!STATUS_PAUSED.equals(subscription.optString("status", ""))
        && endEpisode(eventIdOf(id), "resumed")) {
      ended++;
    }
    if ((!subscription.has(FIELD_PAUSE_COLLECTION) || subscription.isNull(FIELD_PAUSE_COLLECTION))
        && endEpisode(pauseCollectionEventIdOf(id), "collection resumed")) {
      ended++;
    }
    return ended;
  }

  /**
   * {@link #endResolved}, best effort: a failure is logged as a WARN and reads as "nothing
   * ended". For a webhook, which must not fail the whole lifecycle event over a marker rename
   * (Stripe would redeliver it again and again). An episode left open only costs a missed
   * re-alert on the next pause of the same subscription; the next resume observed retries it.
   *
   * @param subscriptionId the {@code sub_...}
   * @param subscription the subscription as the provider reports it now
   * @return how many episodes this call ended, 0 when it failed
   */
  public int endResolvedBestEffort(String subscriptionId, JSONObject subscription) {
    try {
      return endResolved(subscriptionId, subscription);
    } catch (RuntimeException e) {
      log.warn("Pause episodes of subscription {} could not be ended ({}); the event goes on",
          subscriptionId, e.getClass().getSimpleName());
      return 0;
    }
  }

  private boolean endEpisode(String eventId, String how) {
    if (!store.exists(eventId)) {
      return false;
    }
    // The renamed row keeps its event type: a future count by type must exclude ":ended:" ids.
    boolean ended = store.renameReconciliationMarker(eventId,
        eventId + ":ended:" + System.currentTimeMillis(), "Pause ended: " + how);
    if (ended) {
      log.info("Pause episode {} ended ({}); the next pause alerts again", eventId, how);
    }
    return ended;
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
