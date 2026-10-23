/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.hubspot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The {@code ETGO_HUBSPOT_OUTBOX} table, plus the facts the sync reads to detect the events no
 * business code reports: a first entry into the demo, a payment, an expired trial.
 *
 * <p>A row is unique per account, event type and event key (unique index), so detecting or
 * reporting an event twice never sends it twice. The demo events happen once per account and use
 * {@link #ONCE}; the billing events are keyed by the Stripe invoice, event or subscription.
 */
interface HubSpotOutboxStore {

  /** Event types, in the order a deal moves through them. */
  String ACCOUNT_CREATED = "ACCOUNT_CREATED";
  String DEMO_READY = "DEMO_READY";
  String DEMO_ENTERED = "DEMO_ENTERED";
  String PAID = "PAID";
  String DEMO_EXPIRED = "DEMO_EXPIRED";

  /** Billing events, from the Stripe webhook; each lands on the deal of its environment. */
  String PAYMENT = "PAYMENT";
  String PAYMENT_FAILED = "PAYMENT_FAILED";
  String CANCEL_REQUESTED = "CANCEL_REQUESTED";
  String CANCEL_REVERTED = "CANCEL_REVERTED";
  String CANCELED = "CANCELED";

  /**
   * Not an event: which deal a productive environment's billing events land on, recorded
   * {@code SENT} with the deal's id once the sync has decided it.
   */
  String PRODUCTIVE_DEAL = "PRODUCTIVE_DEAL";

  /** The key of an event that happens at most once per account. */
  String ONCE = "-";

  String STATUS_PENDING = "PENDING";
  String STATUS_SENT = "SENT";
  String STATUS_FAILED = "FAILED";
  String STATUS_SKIPPED = "SKIPPED";

  /**
   * Adds an event, unless the account already has one of that type. Joins the caller's
   * transaction and does not commit.
   *
   * @param accountId     the {@code ETGO_ACCOUNT_ID}
   * @param eventType     one of the event types
   * @param environmentId the {@code AD_Client_ID} the event is about, or {@code null}
   * @param payload       the event's data as JSON text, or {@code null}
   * @return whether a row was added
   */
  boolean enqueue(String accountId, String eventType, String environmentId, String payload);

  /**
   * Finds where a subscription's billing events land.
   *
   * @param subscriptionId the Stripe subscription
   * @return the productive environment it pays for, or empty while none is provisioned
   */
  Optional<SubscriptionEnvironment> findSubscriptionEnvironment(String subscriptionId);

  /**
   * Records the environment a billing event turned out to be about.
   *
   * @param id            the row's id
   * @param environmentId the environment's {@code AD_Client_ID}
   */
  void assignEnvironment(String id, String environmentId);

  /**
   * Tells whether an environment already reported a newer billing event, which a late one must
   * not overwrite: Stripe does not deliver in order.
   *
   * @param accountId     the account
   * @param environmentId the environment
   * @param occurredAt    when the event being sent happened at Stripe
   * @return whether a billing event that happened later was already sent
   */
  boolean hasNewerBillingEvent(String accountId, String environmentId, Instant occurredAt);

  /**
   * Finds the deal of a productive environment.
   *
   * @param accountId     the account
   * @param environmentId the environment
   * @return the deal, or empty while it is not decided
   */
  Optional<EnvironmentDeal> findEnvironmentDeal(String accountId, String environmentId);

  /**
   * Counts the productive environments of an account that already have a deal.
   *
   * @param accountId the account
   * @return how many
   */
  int countEnvironmentDeals(String accountId);

  /**
   * Records the deal of a productive environment.
   *
   * @param accountId     the account
   * @param environmentId the environment
   * @param deal          its deal
   */
  void recordEnvironmentDeal(String accountId, String environmentId, EnvironmentDeal deal);

  /**
   * Lists the demos whose events the sync still detects.
   *
   * @return every account whose demo was reported ready and whose deal is not closed yet
   */
  List<OpenDemo> findOpenDemos();

  /**
   * Tells whether a demo was entered: a session on it, or a login usage event.
   *
   * @param environmentId the demo's {@code AD_Client_ID}
   * @return whether anybody has entered that environment
   */
  boolean hasEnteredEnvironment(String environmentId);

  /**
   * Finds a paid checkout of an account.
   *
   * @param accountId the account
   * @param since     only payments at or after this instant count
   * @return the account's latest payment since then, if any
   */
  Optional<PaidCheckout> findPaidCheckout(String accountId, Instant since);

  /**
   * Lists the rows waiting to be sent.
   *
   * @param limit the maximum number of rows
   * @return the pending rows, oldest first, due or not
   */
  List<PendingEvent> findPending(int limit);

  /**
   * Finds the HubSpot deal of an account, which the demo event created.
   *
   * @param accountId the account
   * @return the id of the deal its demo created, or {@code null} while there is none
   */
  String findDealId(String accountId);

  /**
   * Finds the status of one of an account's events.
   *
   * @param accountId the account
   * @param eventType one of the event types
   * @return the status of the account's row of that type, or {@code null} when it has none
   */
  String findStatus(String accountId, String eventType);

  /**
   * Records a row as sent.
   *
   * @param id              the row's id
   * @param hubSpotObjectId the HubSpot object it created or moved, or {@code null}
   */
  void markSent(String id, String hubSpotObjectId);

  /**
   * Leaves a row pending, to be sent again later.
   *
   * @param id            the row's id
   * @param attemptCount  how many sends have failed, this one included
   * @param nextAttemptAt when it may be sent again
   * @param error         why this send failed
   */
  void markRetry(String id, int attemptCount, Instant nextAttemptAt, String error);

  /**
   * Gives a row up.
   *
   * @param id           the row's id
   * @param attemptCount how many sends have failed, this one included
   * @param error        HubSpot's answer, or why it was given up
   */
  void markFailed(String id, int attemptCount, String error);

  /**
   * Records that a row has nothing to be sent to.
   *
   * @param id     the row's id
   * @param reason why
   */
  void markSkipped(String id, String reason);

  /** Commits what was written so far. */
  void commit();

  /** Discards what was written since the last commit. */
  void rollback();

  /**
   * A demo reported ready whose deal is still open.
   *
   * @param accountId     the account
   * @param environmentId the demo's {@code AD_Client_ID}
   * @param readyAt       when the demo was reported ready
   * @param entered       whether its first entry was already detected
   */
  record OpenDemo(String accountId, String environmentId, Instant readyAt, boolean entered) {
  }

  /**
   * A paid checkout.
   *
   * @param planKey the {@code ETGO_PLAN.VALUE} chosen, or {@code null} when it names no plan
   */
  record PaidCheckout(String planKey) {
  }

  /**
   * The productive environment a Stripe subscription pays for.
   *
   * @param environmentId its {@code AD_Client_ID}
   * @param name          the name it was bought with
   * @param planKey       the {@code ETGO_PLAN.VALUE} bought, or {@code null}
   */
  record SubscriptionEnvironment(String environmentId, String name, String planKey) {
  }

  /**
   * The deal of a productive environment.
   *
   * @param dealId the HubSpot deal
   * @param owned  whether the sync created it, and so moves its stage; a deal marketing created
   *               only gets its properties written
   */
  record EnvironmentDeal(String dealId, boolean owned) {
  }

  /**
   * A row waiting to be sent, with the account it is about.
   *
   * @param id            the row's id
   * @param accountId     the account
   * @param eventType     one of the event types
   * @param environmentId the environment, or {@code null}
   * @param payload       the event's data as JSON text, or {@code null}
   * @param attemptCount  how many sends already failed
   * @param nextAttemptAt when it may be sent, or {@code null} for now
   * @param email         the account's address
   * @param name          the account's name
   * @param occurredAt    when the event happened at Stripe, or {@code null} for the demo events
   */
  record PendingEvent(String id, String accountId, String eventType, String environmentId,
      String payload, int attemptCount, Instant nextAttemptAt, String email, String name,
      Instant occurredAt) {
  }
}
