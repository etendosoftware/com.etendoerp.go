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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ETP-6057 — counts public API requests in memory and writes them to {@code ETGO_USAGE_EVENT} as
 * one {@value UsageEventTypes#PUBLIC_API_REQUEST} row per bucket and minute, never one row per
 * request. Full design: {@code docs/public-api-usage.md}.
 *
 * <h2>On the request thread: one map merge</h2>
 *
 * <p>{@link #count(PublicApiCaller, PublicApiCall)} adds one to the bucket of the request —
 * tenant, key, spec, entity, action, HTTP method, status, kind and local day — with a
 * {@link ConcurrentHashMap#merge}: no I/O, no lock held for more than the merge, and it never
 * throws. The first recorded request starts the flush thread, so an instance with no public API
 * traffic starts none. The opt-out is the one every usage event honours,
 * {@value UsageEventRecorder#PROP_ENABLED}.</p>
 *
 * <h2>The flush: every minute and on undeploy, nothing lost on a failed one</h2>
 *
 * <p>Every {@value #FLUSH_INTERVAL_MS} ms the counter's own daemon thread takes each bucket out of
 * the map (an atomic remove, so a request counted meanwhile lands in a fresh entry) and inserts the
 * rows synchronously through {@link UsageEventWriter#insertEach(List)} — its own pooled
 * connection, its own transaction. It does <b>not</b> go through the asynchronous
 * {@link UsageEventWriter} queue on purpose: that queue cannot tell its producer that an insert
 * failed, and a count handed to it would be lost on the first database error. Here every row that
 * was not committed is merged back into the map and goes out with the next flush:</p>
 *
 * <ul>
 *   <li>an <b>outage</b> — the sink throws (no pool, no connection, the connection lost): every count
 *       of the chunk is kept and no attempt is charged, however many minutes it takes;</li>
 *   <li>a <b>row refusal</b> — the sink answers, and some rows (possibly all) are {@code false}: the
 *       database refused them on a working connection (an FK to a deleted tenant, say). Each refused
 *       row is charged an attempt, kept, and dropped with a WARN after {@value #MAX_ROW_ATTEMPTS}, so
 *       a poisoned bucket cannot be retried forever, alone in its chunk or not.</li>
 * </ul>
 *
 * <p>{@link #shutdown()} — called by {@code NeoServlet.destroy()} before the usage event writer is
 * stopped — stops the thread and runs one last flush on the caller's thread. On a normal undeploy
 * nothing is lost; on a hard crash (kill -9, OOM) at most the last minute is.</p>
 *
 * <p>A caller without a tenant — none resolved, or the System client {@code 0} — is not counted:
 * its row would be billed to nobody. It is reported at WARN (throttled) with the other losses.</p>
 *
 * <h2>Bounded memory</h2>
 *
 * <p>The path segments a bucket is keyed by are normalized by {@link PublicApiCall#of}, and the map
 * holds at most {@value #MAX_BUCKETS} buckets: past that, a request whose bucket is new is counted
 * in the overflow bucket of its caller ({@value PublicApiCall#OVERFLOW} spec and entity), so
 * counts stay right per tenant and key even when a client walks random paths.</p>
 */
public final class PublicApiUsageCounter {

  /**
   * Persists one chunk of rows and says which were committed. The production sink is
   * {@link UsageEventWriter#insertEach(List)}; tests inject their own. Throwing means an outage: the
   * whole chunk is kept and no row is charged an attempt. A {@code false} is a row the database
   * refused on its own.
   */
  @FunctionalInterface
  interface Sink {
    boolean[] write(List<UsageEvent> rows) throws Exception; // NOSONAR — any failure keeps counts.
  }

  private static final Logger log = LogManager.getLogger(PublicApiUsageCounter.class);

  /** How often the buckets are written. */
  static final long FLUSH_INTERVAL_MS = 60_000L;

  /** Most buckets held at once; see the class javadoc. */
  static final int MAX_BUCKETS = 10_000;

  /** Rows per insert transaction. */
  static final int CHUNK_SIZE = 500;

  /** Flushes a row the database refuses on its own is retried before it is dropped. */
  static final int MAX_ROW_ATTEMPTS = 5;

  /** How long {@link #shutdown()} waits for a flush already running. */
  static final long SHUTDOWN_WAIT_MS = 5_000L;

  static final String PROP_COUNT = "count";
  static final String PROP_STATUS = "status";
  static final String PROP_KEY_ID = "keyId";
  static final String PROP_ENTITY = "entity";
  static final String PROP_ACTION_NAME = "actionName";
  static final String PROP_KIND = "kind";

  /** The System client: never a tenant to count for. */
  static final String SYSTEM_CLIENT = "0";

  private static final PublicApiUsageCounter DEFAULT =
      new PublicApiUsageCounter(UsageEventWriter::insertEach, Clock.systemDefaultZone(), true);

  /** One counted dimension set. The day keeps a bucket from straddling local midnight. */
  record Bucket(PublicApiCaller caller, PublicApiCall call, LocalDate day) {
    Bucket overflow() {
      return new Bucket(caller, call.toOverflow(), day);
    }
  }

  private final Sink sink;
  private final Clock clock;
  private final boolean scheduled;
  private final ConcurrentHashMap<Bucket, Long> counts = new ConcurrentHashMap<>();
  /** Failed attempts of rows the database refused on their own; touched under the flush lock. */
  private final Map<Bucket, Integer> rowAttempts = new ConcurrentHashMap<>();
  private final Object flushLock = new Object();
  private final LogThrottle losses;
  private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>();
  private volatile boolean stopped;

  PublicApiUsageCounter(Sink sink, Clock clock, boolean scheduled) {
    this.sink = sink;
    this.clock = clock;
    this.scheduled = scheduled;
    this.losses = new LogThrottle(UsageEventWriter.DROP_WARN_INTERVAL_MS, clock::millis);
  }

  /** @return the counter every production caller records into */
  public static PublicApiUsageCounter getDefault() {
    return DEFAULT;
  }

  /**
   * Count one public API request. Returns at once and never throws.
   *
   * @param caller the tenant and key; ignored when null
   * @param call   what was asked and how it was answered; ignored when null
   */
  public void count(PublicApiCaller caller, PublicApiCall call) {
    try {
      if (caller == null || call == null || !UsageEventRecorder.isEnabled()) {
        return;
      }
      if (stopped) {
        noteLoss("counter stopped", 1L);
        return;
      }
      if (StringUtils.isBlank(caller.clientId()) || SYSTEM_CLIENT.equals(caller.clientId())) {
        // No tenant (none resolved, or the System client "0" of a System-role key on org *): the
        // row would be billed to nobody. Ignored and reported (WARN, throttled) instead.
        noteLoss("caller without a tenant", 1L);
        return;
      }
      add(new Bucket(caller, call, LocalDate.now(clock)), 1L);
      startIfNeeded();
    } catch (Throwable t) { // NOSONAR — counting must never reach the request.
      log.debug("Could not count a public API request.", t);
    }
  }

  /**
   * Write every bucket now. Runs on the flush thread, or on the caller's thread from
   * {@link #shutdown()}; never on a request thread. Never throws.
   */
  void flush() {
    synchronized (flushLock) {
      try {
        List<Bucket> taken = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        takeAll(taken, amounts);
        if (taken.isEmpty() || !UsageEventRecorder.isEnabled()) {
          return;
        }
        for (int from = 0; from < taken.size(); from += CHUNK_SIZE) {
          int to = Math.min(taken.size(), from + CHUNK_SIZE);
          writeChunk(taken.subList(from, to), amounts.subList(from, to));
        }
      } catch (Throwable t) { // NOSONAR — the flush thread must survive anything.
        log.debug("Unexpected failure flushing public API usage.", t);
      }
    }
  }

  /**
   * Stop the flush thread and write what is counted, on the calling thread. Called from
   * {@code NeoServlet.destroy()} before the usage event writer stops. Idempotent, never throws.
   * Requests counted afterwards are reported as lost.
   */
  public void shutdown() {
    try {
      if (stopped) {
        return;
      }
      stopped = true;
      ScheduledExecutorService local = scheduler.get();
      if (local != null) {
        local.shutdown();
        local.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Throwable t) { // NOSONAR — shutdown must not fail undeploy.
      log.debug("Could not stop the public API usage flush thread.", t);
    }
    flush();
    reportLeftover();
  }

  /** @return requests counted and not yet written; for tests and diagnostics */
  long pendingRequests() {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }

  // ── internals ──────────────────────────────────────────────────────────

  private void takeAll(List<Bucket> taken, List<Long> amounts) {
    for (Bucket bucket : new ArrayList<>(counts.keySet())) {
      Long amount = counts.remove(bucket);
      if (amount != null && amount > 0L) {
        taken.add(bucket);
        amounts.add(amount);
      }
    }
  }

  private void writeChunk(List<Bucket> buckets, List<Long> amounts) {
    Instant now = clock.instant();
    List<UsageEvent> rows = new ArrayList<>(buckets.size());
    for (int i = 0; i < buckets.size(); i++) {
      rows.add(toEvent(buckets.get(i), amounts.get(i), now));
    }
    boolean[] written;
    try {
      written = sink.write(rows);
    } catch (Throwable t) { // NOSONAR — an outage keeps every count, charging no attempt.
      log.debug("Could not write {} public API usage row(s); keeping them.", rows.size(), t);
      written = null;
    }
    if (written == null || written.length != rows.size()) {
      restoreAll(buckets, amounts);
      return;
    }
    for (int i = 0; i < buckets.size(); i++) {
      settle(buckets.get(i), amounts.get(i), written[i]);
    }
  }

  /** A row the sink answered for: settled, kept for a retry, or dropped after the last attempt. */
  private void settle(Bucket bucket, long amount, boolean written) {
    if (written) {
      rowAttempts.remove(bucket);
      return;
    }
    int attempts = rowAttempts.merge(bucket, 1, Integer::sum);
    if (attempts >= MAX_ROW_ATTEMPTS) {
      rowAttempts.remove(bucket);
      noteLoss("row refused " + attempts + " times", amount);
    } else {
      add(bucket, amount);
    }
  }

  private void restoreAll(List<Bucket> buckets, List<Long> amounts) {
    for (int i = 0; i < buckets.size(); i++) {
      add(buckets.get(i), amounts.get(i));
    }
  }

  /**
   * Add to a bucket, under the bucket cap: a bucket not in the map while it is full is folded into
   * its caller's overflow bucket. Used by {@link #count} and by every restore after a failed write,
   * so a long outage cannot grow the map past the cap either.
   */
  private void add(Bucket bucket, long amount) {
    Bucket target = counts.size() >= MAX_BUCKETS && !counts.containsKey(bucket)
        ? bucket.overflow()
        : bucket;
    counts.merge(target, amount, Long::sum);
  }

  UsageEvent toEvent(Bucket bucket, long amount, Instant now) {
    PublicApiCaller caller = bucket.caller();
    PublicApiCall call = bucket.call();
    return UsageEvent.builder()
        .clientId(caller.clientId())
        .orgId(caller.orgId())
        .userId(caller.userId())
        .roleId(caller.roleId())
        .eventType(UsageEventTypes.PUBLIC_API_REQUEST)
        .source(UsageEvent.SOURCE_BACKEND)
        .target(call.target())
        .action(call.method())
        .outcome(call.isError() ? UsageEvent.OUTCOME_ERROR : UsageEvent.OUTCOME_OK)
        .errorCode(call.isError() ? String.valueOf(call.status()) : null)
        .occurredAt(occurredAt(bucket.day(), now))
        .property(PROP_COUNT, amount)
        .property(PROP_STATUS, call.status())
        .property(PROP_KEY_ID, caller.keyId())
        .property(PROP_ENTITY, call.entity())
        .property(PROP_ACTION_NAME, call.actionName())
        .property(PROP_KIND, call.kind())
        .build();
  }

  /**
   * The flush time, clamped to the bucket's own day: a bucket of yesterday written after midnight
   * (a retried one) is dated at the last millisecond of yesterday, so the daily total never moves a
   * count to another day.
   */
  private Instant occurredAt(LocalDate day, Instant now) {
    ZoneId zone = clock.getZone();
    if (!LocalDate.ofInstant(now, zone).isAfter(day)) {
      return now;
    }
    return day.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
  }

  private void startIfNeeded() {
    if (!scheduled || scheduler.get() != null) {
      return;
    }
    synchronized (this) {
      if (scheduler.get() != null || stopped) {
        return;
      }
      ScheduledExecutorService local = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "etgo-publicapi-usage");
        thread.setDaemon(true);
        return thread;
      });
      local.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS,
          TimeUnit.MILLISECONDS);
      scheduler.set(local);
    }
  }

  private void reportLeftover() {
    long left = pendingRequests();
    if (left > 0L) {
      counts.clear();
      losses.add(left);
      log.warn("Public API usage: {} request(s) could not be written on shutdown and are lost. "
          + "{} lost since startup.", left, losses.total());
    }
  }

  private void noteLoss(String reason, long requests) {
    try {
      if (losses.note(requests)) {
        log.warn("Public API usage: {} request(s) not recorded ({}). {} lost since startup — "
            + "ETGO_USAGE_EVENT undercounts this period.", requests, reason, losses.total());
      }
    } catch (Throwable t) { // NOSONAR — accounting for a loss must not fail anything.
      log.debug("Could not account for lost public API usage.", t);
    }
  }
}
