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

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.hubspot.HubSpotEventSender.Outcome;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.OpenDemo;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PaidCheckout;
import com.etendoerp.go.hubspot.HubSpotOutboxStore.PendingEvent;

/**
 * One pass of the HubSpot sync: detects what no business code reports, then sends what is pending.
 *
 * <ol>
 *   <li><b>Detect.</b> For every demo reported ready whose deal is still open: its first entry
 *       (a session or a login usage event on the environment), a payment (a paid checkout of the
 *       account since the demo), or the end of its trial (the access policy). Each becomes an
 *       outbox row, at most once per account.</li>
 *   <li><b>Send.</b> Pending rows, oldest first. An account's rows go in order: one that cannot
 *       be sent yet holds back the later ones, so a deal is never won before it exists.</li>
 * </ol>
 *
 * <p>Each row is committed on its own. A failed send is retried with a growing delay, and given
 * up after {@value #MAX_ATTEMPTS} attempts, left {@code FAILED} with HubSpot's answer in
 * {@code LAST_ERROR}. Without a token the pass does nothing at all: events keep accumulating
 * {@code PENDING} and go out once one is configured.
 */
public class HubSpotSyncService {

  private static final Logger log = LogManager.getLogger(HubSpotSyncService.class);

  static final int MAX_ATTEMPTS = 12;
  static final int BATCH_SIZE = 25;
  static final long MAX_DELAY_MINUTES = 360;

  private final HubSpotConfiguration configuration;
  private final HubSpotOutboxStore store;
  private final HubSpotEventSender sender;
  private final DemoTrials trials;

  /** The sync as configured on this server. */
  public HubSpotSyncService() {
    this(HubSpotConfiguration.fromRuntime(), new DalHubSpotOutboxStore(), new DemoTrials());
  }

  private HubSpotSyncService(HubSpotConfiguration configuration, HubSpotOutboxStore store,
      DemoTrials trials) {
    this(configuration, store, trials,
        new HubSpotEventSender(new HttpHubSpotClient(configuration), configuration, store,
            trials));
  }

  HubSpotSyncService(HubSpotConfiguration configuration, HubSpotOutboxStore store,
      DemoTrials trials, HubSpotEventSender sender) {
    this.configuration = configuration;
    this.store = store;
    this.trials = trials;
    this.sender = sender;
  }

  /**
   * Runs one pass.
   *
   * @param now the instant of this pass
   * @return what the pass did
   */
  public Result run(Instant now) {
    Result result = new Result();
    if (!configuration.isEnabled()) {
      result.disabled = true;
      return result;
    }
    detect(now, result);
    send(now, result);
    if (result.detected + result.sent + result.retried + result.failed + result.skipped > 0) {
      log.info("HubSpot sync: {}", result);
    } else {
      // Every minute, so a quiet pass is not worth an INFO line.
      log.debug("HubSpot sync: {}", result);
    }
    return result;
  }

  private void detect(Instant now, Result result) {
    for (OpenDemo demo : store.findOpenDemos()) {
      try {
        detect(demo, now, result);
        store.commit();
      } catch (RuntimeException e) {
        store.rollback();
        log.warn("HubSpot sync could not inspect the demo {} of account {}; retried next pass",
            demo.environmentId(), demo.accountId(), e);
      }
    }
  }

  private void detect(OpenDemo demo, Instant now, Result result) {
    if (!demo.entered() && store.hasEnteredEnvironment(demo.environmentId())
        && store.enqueue(demo.accountId(), HubSpotOutboxStore.DEMO_ENTERED,
            demo.environmentId(), null)) {
      result.detected++;
    }
    Optional<PaidCheckout> paid = demo.readyAt() == null ? Optional.empty()
        : store.findPaidCheckout(demo.accountId(), demo.readyAt());
    if (paid.isPresent()) {
      if (store.enqueue(demo.accountId(), HubSpotOutboxStore.PAID, demo.environmentId(),
          planPayload(paid.get().planKey()))) {
        result.detected++;
      }
      return;
    }
    if (trials.find(demo.environmentId(), now).map(DemoTrials.Trial::expired).orElse(false)
        && store.enqueue(demo.accountId(), HubSpotOutboxStore.DEMO_EXPIRED,
            demo.environmentId(), null)) {
      result.detected++;
    }
  }

  private void send(Instant now, Result result) {
    Set<String> heldBack = new HashSet<>();
    for (PendingEvent event : store.findPending(BATCH_SIZE)) {
      if (!heldBack.contains(event.accountId()) && !sendOne(event, now, result)) {
        // An account's events go in order: one still pending holds back the later ones.
        heldBack.add(event.accountId());
      }
    }
  }

  /** @return whether the event left {@code PENDING} */
  private boolean sendOne(PendingEvent event, Instant now, Result result) {
    if (event.nextAttemptAt() != null && now.isBefore(event.nextAttemptAt())) {
      return false;
    }
    Outcome outcome;
    try {
      outcome = sender.send(event, now);
    } catch (RuntimeException e) {
      store.rollback();
      outcome = Outcome.retry(String.valueOf(e));
    }
    try {
      boolean done = recordOutcome(event, outcome, now, result);
      store.commit();
      return done;
    } catch (RuntimeException e) {
      store.rollback();
      log.error("HubSpot sync could not record the outcome of event {}", event.id(), e);
      return false;
    }
  }

  /** @return whether the row left {@code PENDING} */
  private boolean recordOutcome(PendingEvent event, Outcome outcome, Instant now, Result result) {
    switch (outcome.kind()) {
      case SENT:
        store.markSent(event.id(), outcome.hubSpotObjectId());
        result.sent++;
        return true;
      case SKIPPED:
        store.markSkipped(event.id(), outcome.detail());
        result.skipped++;
        return true;
      case FAILED:
        store.markFailed(event.id(), event.attemptCount() + 1, outcome.detail());
        log.warn("HubSpot refused event {} ({}) of account {}: {}", event.id(),
            event.eventType(), event.accountId(), outcome.detail());
        result.failed++;
        return true;
      case DEFERRED:
        return false;
      case RETRY:
      default:
        int attempts = event.attemptCount() + 1;
        if (attempts >= MAX_ATTEMPTS) {
          store.markFailed(event.id(), attempts, outcome.detail());
          log.warn("HubSpot event {} ({}) of account {} given up after {} attempts: {}",
              event.id(), event.eventType(), event.accountId(), attempts, outcome.detail());
          result.failed++;
          return true;
        }
        store.markRetry(event.id(), attempts, now.plus(delayAfter(attempts)), outcome.detail());
        result.retried++;
        return false;
    }
  }

  /** 1, 2, 4… minutes, capped at {@value #MAX_DELAY_MINUTES}: about a day and a half in all. */
  static Duration delayAfter(int attempts) {
    long minutes = 1L << Math.min(Math.max(attempts - 1, 0), 20);
    return Duration.ofMinutes(Math.min(minutes, MAX_DELAY_MINUTES));
  }

  static String planPayload(String planKey) {
    if (planKey == null) {
      return null;
    }
    try {
      return new JSONObject().put("plan", planKey).toString();
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the payment event payload", e);
    }
  }

  /** Counters of one pass. */
  public static final class Result {
    private boolean disabled;
    private int detected;
    private int sent;
    private int retried;
    private int failed;
    private int skipped;

    public boolean isDisabled() {
      return disabled;
    }

    public int getDetected() {
      return detected;
    }

    public int getSent() {
      return sent;
    }

    public int getRetried() {
      return retried;
    }

    public int getFailed() {
      return failed;
    }

    public int getSkipped() {
      return skipped;
    }

    @Override
    public String toString() {
      if (disabled) {
        return "no HubSpot token configured; nothing sent";
      }
      return detected + " event(s) detected, " + sent + " sent, " + retried + " to retry, "
          + failed + " failed, " + skipped + " skipped";
    }
  }
}
