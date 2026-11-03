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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Unit specs for {@link PublicApiUsageCounter}: bucketing, the bucket cap, the flush and its
 * retry rules, shutdown, the opt-out and the shape of the rows written. Built with the
 * package-private constructor and {@code scheduled=false}, so no thread runs and every flush is
 * driven by the test; {@code getDefault()} is not used because it stays stopped once
 * {@code NeoServlet.destroy()} ran in the same JVM.
 *
 * @covers com.etendoerp.go.usageevents.PublicApiUsageCounter
 */
class PublicApiUsageCounterTest {

  private static final String SECRET_LOOKING = "s3cr3t-value";
  private static final PublicApiCaller CALLER =
      new PublicApiCaller("CLIENT1", "ORG1", "USER1", "ROLE1", "key-public-id");

  /** A clock the test moves by hand. */
  private static final class TestClock extends Clock {
    private Instant now;
    private final ZoneId zone;

    TestClock(Instant start, ZoneId zone) {
      this.now = start;
      this.zone = zone;
    }

    void set(Instant instant) {
      this.now = instant;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId z) {
      return new TestClock(now, z);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private MockedStatic<OBPropertiesProvider> propertiesStatic;
  private final Properties props = new Properties();
  private final List<UsageEvent> written = new ArrayList<>();
  private final AtomicInteger sinkCalls = new AtomicInteger();
  private PublicApiUsageCounter.Sink sink = rows -> {
    written.addAll(rows);
    boolean[] all = new boolean[rows.size()];
    java.util.Arrays.fill(all, true);
    return all;
  };
  private TestClock clock;
  private PublicApiUsageCounter counter;

  @BeforeEach
  void setUp() {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(props);
    propertiesStatic = mockStatic(OBPropertiesProvider.class);
    propertiesStatic.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    clock = new TestClock(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    counter = new PublicApiUsageCounter(rows -> {
      sinkCalls.incrementAndGet();
      return sink.write(rows);
    }, clock, false);
  }

  @AfterEach
  void tearDown() {
    propertiesStatic.close();
  }

  private static PublicApiCall call(String spec, String method, int status) {
    return PublicApiCall.of(spec, "order", null, method, status);
  }

  private static long countOf(UsageEvent row) throws Exception {
    return new JSONObject(row.properties()).getLong("count");
  }

  private static long total(List<UsageEvent> rows) throws Exception {
    long sum = 0;
    for (UsageEvent row : rows) {
      sum += countOf(row);
    }
    return sum;
  }

  // ── bucketing ──────────────────────────────────────────────────────────

  @Test
  void sameBucketMergesIntoOneRowWithTheCount() throws Exception {
    counter.record(CALLER, call("sales-order", "get", 200));
    counter.record(CALLER, call("sales-order", "GET", 200));
    counter.record(CALLER, call("sales-order", "GET", 200));

    assertEquals(3, counter.pendingRequests());
    counter.flush();

    assertEquals(1, written.size());
    assertEquals(3, countOf(written.get(0)));
    assertEquals(0, counter.pendingRequests());
  }

  @Test
  void differentStatusMethodAndKeySplitBuckets() throws Exception {
    counter.record(CALLER, call("sales-order", "GET", 200));
    counter.record(CALLER, call("sales-order", "GET", 404));
    counter.record(CALLER, call("sales-order", "POST", 200));
    counter.record(new PublicApiCaller("CLIENT1", "ORG1", "USER1", "ROLE1", "other-key"),
        call("sales-order", "GET", 200));

    counter.flush();

    assertEquals(4, written.size());
    assertEquals(4, total(written));
  }

  @Test
  void aNewLocalDayStartsANewBucket() throws Exception {
    counter.record(CALLER, call("sales-order", "GET", 200));
    clock.set(Instant.parse("2026-10-11T00:00:01Z"));
    counter.record(CALLER, call("sales-order", "GET", 200));

    counter.flush();

    assertEquals(2, written.size());
  }

  @Test
  void aNullCallerOrCallIsIgnored() {
    counter.record(null, call("sales-order", "GET", 200));
    counter.record(CALLER, null);
    assertEquals(0, counter.pendingRequests());
  }

  // ── bucket cap ─────────────────────────────────────────────────────────

  @Test
  void pastTheCapANewKeyGoesToTheCallersOverflowBucket() throws Exception {
    for (int i = 0; i < PublicApiUsageCounter.MAX_BUCKETS; i++) {
      counter.record(CALLER, call("spec" + i, "GET", 200));
    }
    counter.record(CALLER, call("brand-new", "GET", 200));
    counter.record(CALLER, call("brand-new-too", "GET", 200));
    // An existing bucket still merges at the cap, it is not diverted.
    counter.record(CALLER, call("spec0", "GET", 200));

    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 3L, counter.pendingRequests());
    counter.flush();

    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 1, written.size());
    UsageEvent overflow = written.stream()
        .filter(r -> PublicApiCall.OVERFLOW.equals(r.target())).findFirst().orElseThrow();
    assertEquals(2, countOf(overflow));
    assertEquals(PublicApiCall.OVERFLOW, new JSONObject(overflow.properties()).getString("entity"));
    assertEquals("CLIENT1", overflow.clientId());
    assertEquals("key-public-id",
        new JSONObject(overflow.properties()).getString("keyId"));
    UsageEvent spec0 = written.stream().filter(r -> "spec0".equals(r.target())).findFirst()
        .orElseThrow();
    assertEquals(2, countOf(spec0));
    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 3L, total(written));
  }

  // ── kinds and callers without a tenant ────────────────────────────────

  @Test
  void callsOfDifferentKindsNeverMergeIntoOneBucket() throws Exception {
    counter.record(CALLER, PublicApiCall.of("sales-order", "order", null, "POST", 200));
    counter.record(CALLER, PublicApiCall.batchOperation("sales-order", "order", 200));
    counter.record(CALLER, PublicApiCall.batchOperation("sales-order", "order", 200));

    counter.flush();

    assertEquals(2, written.size());
    for (UsageEvent row : written) {
      String kind = new JSONObject(row.properties()).getString("kind");
      assertEquals(PublicApiCall.KIND_BATCH_OPERATION.equals(kind) ? 2 : 1, countOf(row), kind);
    }
  }

  @Test
  void aBatchOperationAndATokenExchangeAreEachKeptWithTheirKind() throws Exception {
    counter.record(CALLER, PublicApiCall.batchOperation("sales-order", "order", 200));
    counter.record(CALLER, PublicApiCall.tokenExchange(401));

    counter.flush();

    List<String> kinds = new ArrayList<>();
    for (UsageEvent row : written) {
      kinds.add(new JSONObject(row.properties()).getString("kind"));
    }
    assertTrue(kinds.containsAll(List.of("batchOperation", "tokenExchange")), kinds.toString());
  }

  @Test
  void theOverflowBucketKeepsTheKindOfTheCallThatOverflowed() throws Exception {
    for (int i = 0; i < PublicApiUsageCounter.MAX_BUCKETS; i++) {
      counter.record(CALLER, call("spec" + i, "GET", 200));
    }
    counter.record(CALLER, PublicApiCall.batchOperation("fresh", "entity", 200));

    counter.flush();

    UsageEvent overflow = written.stream()
        .filter(r -> PublicApiCall.OVERFLOW.equals(r.target())).findFirst().orElseThrow();
    assertEquals(PublicApiCall.KIND_BATCH_OPERATION,
        new JSONObject(overflow.properties()).getString("kind"));
  }

  @Test
  void aCallerWithANullOrBlankTenantIsNotCountedAndWarnsOnce() {
    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      counter.record(new PublicApiCaller(null, "ORG1", "USER1", "ROLE1", "k"),
          call("a", "GET", 200));
      counter.record(new PublicApiCaller("  ", "ORG1", "USER1", "ROLE1", "k"),
          call("a", "GET", 200));
      counter.record(new PublicApiCaller("", "ORG1", "USER1", "ROLE1", "k"),
          call("a", "GET", 200));

      assertEquals(0, counter.pendingRequests());
      assertEquals(1, log.messages(Level.WARN).size(), "throttled to one line");
      assertTrue(log.messages(Level.WARN).get(0).contains("caller without a tenant"));
    }
  }

  @Test
  void aValidCallerStillCountsAfterATenantlessOne() {
    counter.record(new PublicApiCaller(null, "ORG1", "USER1", "ROLE1", "k"), call("a", "GET", 200));

    counter.record(CALLER, call("a", "GET", 200));

    assertEquals(1, counter.pendingRequests());
  }

  // ── flush: failures keep the counts ────────────────────────────────────

  @Test
  void aSinkThatThrowsKeepsEveryCount() throws Exception {
    counter.record(CALLER, call("a", "GET", 200));
    counter.record(CALLER, call("b", "GET", 200));
    sink = rows -> {
      throw new IllegalStateException("db down");
    };

    counter.flush();
    assertEquals(2, counter.pendingRequests());

    sink = rows -> {
      written.addAll(rows);
      return new boolean[] { true, true };
    };
    counter.flush();
    assertEquals(0, counter.pendingRequests());
    assertEquals(2, written.size());
  }

  @Test
  void aSinkThatRefusesTheOnlyRowKeepsItAtFirst() {
    counter.record(CALLER, call("a", "GET", 200));
    counter.record(CALLER, call("a", "GET", 200));
    sink = rows -> new boolean[rows.size()];

    counter.flush();

    assertEquals(2, counter.pendingRequests());
  }

  @Test
  void aBucketRefusedAloneInItsChunkIsDroppedAfterFiveFlushesWithOneWarn() {
    sink = rows -> new boolean[] { false };
    counter.record(CALLER, call("bad", "GET", 200));
    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      for (int flush = 1; flush < PublicApiUsageCounter.MAX_ROW_ATTEMPTS; flush++) {
        counter.flush();
        assertEquals(1, counter.pendingRequests(), "kept after flush " + flush);
      }
      assertTrue(log.messages(Level.WARN).isEmpty());

      counter.flush();

      assertEquals(0, counter.pendingRequests());
      assertEquals(1, log.messages(Level.WARN).size());
      assertTrue(log.messages(Level.WARN).get(0).contains("row refused 5 times"));
    }
    assertEquals(PublicApiUsageCounter.MAX_ROW_ATTEMPTS, sinkCalls.get());
  }

  @Test
  void aSinkThatThrowsOnEveryFlushNeverDropsAndNeverWarns() {
    sink = rows -> {
      throw new IllegalStateException("db down");
    };
    counter.record(CALLER, call("a", "GET", 200));
    counter.record(CALLER, call("a", "GET", 200));
    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      for (int flush = 0; flush < 4 * PublicApiUsageCounter.MAX_ROW_ATTEMPTS; flush++) {
        counter.flush();
      }

      assertEquals(2, counter.pendingRequests());
      assertTrue(log.messages(Level.WARN).isEmpty());
    }
  }

  @Test
  void aWrongLengthResultKeepsEverything() {
    counter.record(CALLER, call("a", "GET", 200));
    counter.record(CALLER, call("b", "GET", 200));
    sink = rows -> new boolean[] { true };

    counter.flush();

    assertEquals(2, counter.pendingRequests());
  }

  @Test
  void aNullResultKeepsEverything() {
    counter.record(CALLER, call("a", "GET", 200));
    sink = rows -> null;

    counter.flush();

    assertEquals(1, counter.pendingRequests());
  }

  @Test
  void aPartialWriteKeepsTheFailedRowAndDropsItAfterFiveAttemptsWithAWarn() throws Exception {
    sink = rows -> {
      boolean[] ok = new boolean[rows.size()];
      for (int i = 0; i < ok.length; i++) {
        ok[i] = !"bad".equals(rows.get(i).target());
        if (ok[i]) {
          written.add(rows.get(i));
        }
      }
      return ok;
    };
    counter.record(CALLER, call("bad", "GET", 200));
    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      for (int attempt = 1; attempt < PublicApiUsageCounter.MAX_ROW_ATTEMPTS; attempt++) {
        counter.record(CALLER, call("good" + attempt, "GET", 200));
        counter.flush();
        assertEquals(1, counter.pendingRequests(), "the refused row is kept, attempt " + attempt);
      }
      assertTrue(log.messages(Level.WARN).isEmpty());

      counter.record(CALLER, call("good-last", "GET", 200));
      counter.flush();

      assertEquals(0, counter.pendingRequests());
      List<String> warns = log.messages(Level.WARN);
      assertEquals(1, warns.size());
      assertTrue(warns.get(0).contains("row refused " + PublicApiUsageCounter.MAX_ROW_ATTEMPTS),
          warns.get(0));
    }
    assertEquals(PublicApiUsageCounter.MAX_ROW_ATTEMPTS, written.size());
    assertTrue(written.stream().noneMatch(r -> "bad".equals(r.target())));
  }

  // ── concurrency and multi-chunk outage ─────────────────────────────────

  @Test
  void requestsRecordedWhileAFlushIsBlockedInTheSinkAreNeitherLostNorDoubled() throws Exception {
    final int threads = 4;
    final int each = 1_000;
    java.util.concurrent.CountDownLatch inSink = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    sink = rows -> {
      synchronized (written) {
        written.addAll(rows);
      }
      inSink.countDown();
      if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new IllegalStateException("flush was never released");
      }
      boolean[] all = new boolean[rows.size()];
      java.util.Arrays.fill(all, true);
      return all;
    };
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
        .newFixedThreadPool(threads);
    try {
      runRecorders(pool, threads, each);

      Thread flusher = new Thread(counter::flush, "test-flusher");
      flusher.start();
      assertTrue(inSink.await(10, java.util.concurrent.TimeUnit.SECONDS));
      runRecorders(pool, threads, each); // while the first flush holds its rows in the sink
      release.countDown();
      flusher.join(10_000);
      counter.flush();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }

    assertEquals(0, counter.pendingRequests());
    assertEquals(2L * threads * each, total(written));
  }

  private void runRecorders(java.util.concurrent.ExecutorService pool, int threads, int each)
      throws Exception {
    List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final String spec = "spec" + (t % 2);
      futures.add(pool.submit(() -> {
        for (int i = 0; i < each; i++) {
          counter.record(CALLER, call(spec, "GET", 200));
        }
      }));
    }
    for (java.util.concurrent.Future<?> future : futures) {
      future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  void anOutageOnTheSecondChunkKeepsOnlyThatChunkAndNeverRewritesTheFirst() throws Exception {
    int buckets = PublicApiUsageCounter.CHUNK_SIZE + 200;
    for (int i = 0; i < buckets; i++) {
      counter.record(CALLER, call("spec" + i, "GET", 200));
    }
    AtomicInteger chunk = new AtomicInteger();
    sink = rows -> {
      if (chunk.incrementAndGet() == 2) {
        throw new IllegalStateException("db down on chunk 2");
      }
      written.addAll(rows);
      boolean[] all = new boolean[rows.size()];
      java.util.Arrays.fill(all, true);
      return all;
    };

    counter.flush();

    assertEquals(PublicApiUsageCounter.CHUNK_SIZE, written.size(), "chunk 1 committed once");
    assertEquals(200, counter.pendingRequests(), "only chunk 2 is kept");

    sink = rows -> {
      written.addAll(rows);
      boolean[] all = new boolean[rows.size()];
      java.util.Arrays.fill(all, true);
      return all;
    };
    counter.flush();

    assertEquals(0, counter.pendingRequests());
    assertEquals(buckets, written.size());
    assertEquals(buckets, written.stream().map(UsageEvent::target).distinct().count(),
        "no bucket written twice");
    assertEquals(buckets, total(written));
  }

  // ── bucket cap during an outage ────────────────────────────────────────

  private static final PublicApiCaller FILLER =
      new PublicApiCaller("CLIENT2", "ORG2", "USER2", "ROLE2", "filler-key");

  private void fillTheMapToTheCap() {
    for (int i = 0; i < PublicApiUsageCounter.MAX_BUCKETS; i++) {
      counter.record(FILLER, call("f" + i, "GET", 200));
    }
  }

  private PublicApiUsageCounter.Sink workingSink() {
    return rows -> {
      written.addAll(rows);
      boolean[] all = new boolean[rows.size()];
      java.util.Arrays.fill(all, true);
      return all;
    };
  }

  private UsageEvent overflowRowOf(PublicApiCaller caller) {
    return written.stream()
        .filter(r -> PublicApiCall.OVERFLOW.equals(r.target()))
        .filter(r -> caller.clientId().equals(r.clientId()))
        .findFirst().orElseThrow();
  }

  @Test
  void anOutageRestoreOfABucketNotInAFullMapLandsInTheCallersOverflowBucket() throws Exception {
    for (int i = 0; i < 3; i++) {
      counter.record(CALLER, call("victim", "GET", 200));
    }
    AtomicInteger calls = new AtomicInteger();
    sink = rows -> {
      if (calls.incrementAndGet() == 1) {
        fillTheMapToTheCap(); // the map fills while the victim bucket is out for writing
      }
      throw new IllegalStateException("db down");
    };

    counter.flush();

    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 3L, counter.pendingRequests());
    sink = workingSink();
    counter.flush();

    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 3L, total(written), "nothing lost");
    UsageEvent overflow = overflowRowOf(CALLER);
    assertEquals(3, countOf(overflow), "the victim's counts are kept in its caller's overflow");
    assertEquals("key-public-id", new JSONObject(overflow.properties()).getString("keyId"));
    assertTrue(written.stream().noneMatch(
        r -> PublicApiCall.OVERFLOW.equals(r.target()) && "CLIENT2".equals(r.clientId())),
        "the filler caller never overflowed");
  }

  @Test
  void aRefusedRowRestoredWhileTheMapIsFullAlsoGoesToOverflow() throws Exception {
    for (int i = 0; i < 2; i++) {
      counter.record(CALLER, call("victim", "GET", 200));
    }
    AtomicInteger calls = new AtomicInteger();
    sink = rows -> {
      if (calls.incrementAndGet() == 1) {
        fillTheMapToTheCap();
      }
      return new boolean[rows.size()]; // every row refused by the database
    };

    counter.flush();

    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 2L, counter.pendingRequests());
    sink = workingSink();
    counter.flush();

    assertEquals(PublicApiUsageCounter.MAX_BUCKETS + 2L, total(written));
    assertEquals(2, countOf(overflowRowOf(CALLER)));
  }

  @Test
  void aCallerWithTheSystemClientIsNotCountedAndWarnsOnce() {
    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      counter.record(new PublicApiCaller("0", "ORG1", "USER1", "ROLE1", "k"), call("a", "GET", 200));
      counter.record(new PublicApiCaller("0", "ORG1", "USER1", "ROLE1", "k"), call("a", "GET", 200));

      assertEquals(0, counter.pendingRequests());
      assertEquals(1, log.messages(Level.WARN).size(), "throttled to one line");
      assertTrue(log.messages(Level.WARN).get(0).contains("caller without a tenant"));
    }
    counter.record(CALLER, call("a", "GET", 200));
    assertEquals(1, counter.pendingRequests());
  }

  // ── shutdown ───────────────────────────────────────────────────────────

  @Test
  void shutdownFlushesWhatIsPending() throws Exception {
    counter.record(CALLER, call("a", "GET", 200));
    counter.record(CALLER, call("a", "GET", 200));

    counter.shutdown();

    assertEquals(1, written.size());
    assertEquals(2, countOf(written.get(0)));
    assertEquals(0, counter.pendingRequests());
  }

  @Test
  void leftoversAfterAFailedFinalFlushAreWarnedAndCountedLost() {
    counter.record(CALLER, call("a", "GET", 200));
    counter.record(CALLER, call("b", "GET", 200));
    sink = rows -> {
      throw new IllegalStateException("db down");
    };

    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      counter.shutdown();

      List<String> warns = log.messages(Level.WARN);
      assertEquals(1, warns.size());
      assertTrue(warns.get(0).contains("2 request(s) could not be written on shutdown"),
          warns.get(0));
      assertTrue(warns.get(0).contains("2 lost since startup"), warns.get(0));
    }
    assertEquals(0, counter.pendingRequests());
  }

  @Test
  void recordsAfterShutdownAreIgnoredAndReportedLost() {
    counter.shutdown();

    try (LogCapture log = LogCapture.of(PublicApiUsageCounter.class)) {
      counter.record(CALLER, call("a", "GET", 200));

      assertEquals(0, counter.pendingRequests());
      assertEquals(1, log.messages(Level.WARN).size());
      assertTrue(log.messages(Level.WARN).get(0).contains("counter stopped"));
    }
  }

  @Test
  void shutdownIsIdempotent() {
    counter.record(CALLER, call("a", "GET", 200));
    counter.shutdown();
    int calls = sinkCalls.get();

    counter.shutdown();

    assertEquals(calls, sinkCalls.get());
  }

  // ── opt-out ────────────────────────────────────────────────────────────

  @Test
  void whenUsageEventsAreDisabledNothingIsCounted() {
    props.setProperty(UsageEventRecorder.PROP_ENABLED, "false");

    counter.record(CALLER, call("a", "GET", 200));

    assertEquals(0, counter.pendingRequests());
  }

  @Test
  void whenDisabledAfterCountingTheFlushDiscardsWithoutWriting() {
    counter.record(CALLER, call("a", "GET", 200));
    props.setProperty(UsageEventRecorder.PROP_ENABLED, "false");

    counter.flush();

    assertEquals(0, sinkCalls.get());
    assertEquals(0, counter.pendingRequests());
  }

  // ── row shape ──────────────────────────────────────────────────────────

  @Test
  void aRowCarriesTheBucketDimensionsAndNoSecret() throws Exception {
    counter.record(CALLER, PublicApiCall.of("sales-order", "order", "complete", "post", 403));

    counter.flush();

    UsageEvent row = written.get(0);
    assertEquals(UsageEventTypes.PUBLIC_API_REQUEST, row.eventType());
    assertEquals(UsageEvent.SOURCE_BACKEND, row.source());
    assertEquals("CLIENT1", row.clientId());
    assertEquals("ORG1", row.orgId());
    assertEquals("USER1", row.userId());
    assertEquals("ROLE1", row.roleId());
    assertEquals("sales-order", row.target());
    assertEquals("POST", row.action());
    assertEquals(UsageEvent.OUTCOME_ERROR, row.outcome());
    assertEquals("403", row.errorCode());
    assertEquals(clock.instant(), row.occurredAt());
    JSONObject json = new JSONObject(row.properties());
    assertEquals(1, json.getLong("count"));
    assertEquals(403, json.getInt("status"));
    assertEquals("key-public-id", json.getString("keyId"));
    assertEquals("order", json.getString("entity"));
    assertEquals("complete", json.getString("actionName"));
    assertEquals(PublicApiCall.KIND_REQUEST, json.getString("kind"));
    assertEquals(6, json.length());
    assertFalse(row.properties().contains(SECRET_LOOKING));
  }

  @Test
  void aSuccessRowHasOkOutcomeAndNoErrorCode() {
    counter.record(CALLER, call("sales-order", "GET", 204));

    counter.flush();

    assertEquals(UsageEvent.OUTCOME_OK, written.get(0).outcome());
    assertNull(written.get(0).errorCode());
  }

  @Test
  void aBucketOfAPastDayIsDatedAtTheEndOfThatDay() {
    counter.record(CALLER, call("sales-order", "GET", 200));
    clock.set(Instant.parse("2026-10-11T00:30:00Z"));

    counter.flush();

    UsageEvent row = written.get(0);
    assertNotNull(row.occurredAt());
    assertEquals(Instant.parse("2026-10-10T23:59:59.999Z"), row.occurredAt());
  }

  @Test
  void aBucketOfTheSameDayIsDatedAtTheFlushTime() {
    counter.record(CALLER, call("sales-order", "GET", 200));
    clock.set(Instant.parse("2026-10-10T12:05:00Z"));

    counter.flush();

    assertEquals(Instant.parse("2026-10-10T12:05:00Z"), written.get(0).occurredAt());
  }
}
