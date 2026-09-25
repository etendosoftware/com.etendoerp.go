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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit specs for the queue, batching and shutdown behaviour of {@link UsageEventWriter} (ETP-5462),
 * over an injected sink and clock — no database.
 *
 * <p>Nothing here sleeps to "let the thread run". Every point where the writer thread's progress
 * matters is pinned with a latch the sink controls (a sink that blocks until released is how the
 * queue is filled deterministically), and every wait is bounded. The one unavoidable real wait is
 * {@link UsageEventWriter#shutdown(long)}, which joins the thread: the loop re-checks its stop flag
 * once per poll timeout, so a clean shutdown takes up to about one second.</p>
 */
class UsageEventWriterTest {

  private static final long AWAIT_S = 10L;
  private static final long GRACE_MS = 5_000L;
  private static final long T0 = 1_000_000L;

  private final AtomicLong clock = new AtomicLong(T0);
  private final List<UsageEventWriter> started = new ArrayList<>();

  @AfterEach
  void stopWriters() {
    started.forEach(w -> w.shutdown(100L));
  }

  private UsageEventWriter start(int capacity, UsageEventWriter.Sink sink) {
    UsageEventWriter writer = UsageEventWriter.start(capacity, sink, clock::get);
    started.add(writer);
    return writer;
  }

  private static UsageEvent event(String target) {
    return UsageEvent.builder().eventType(UsageEventTypes.AI_AGENT_MESSAGE).target(target).build();
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(AWAIT_S, TimeUnit.SECONDS), "timed out waiting for the writer thread");
  }

  /**
   * A sink whose first call blocks until {@link #release} — ignoring interrupts, like a JDBC call
   * stuck on the network — then records every batch.
   */
  private static final class GatedSink implements UsageEventWriter.Sink {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final List<List<UsageEvent>> batches = new CopyOnWriteArrayList<>();

    @Override
    public int write(List<UsageEvent> batch) {
      batches.add(batch);
      if (batches.size() == 1) {
        entered.countDown();
        awaitReleaseIgnoringInterrupts();
      }
      return batch.size();
    }

    private void awaitReleaseIgnoringInterrupts() {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S);
      boolean wasInterrupted = false;
      while (release.getCount() > 0 && System.nanoTime() < deadline) {
        try {
          release.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          wasInterrupted = true;
          interrupted.countDown();
        }
      }
      if (wasInterrupted) {
        Thread.currentThread().interrupt();
      }
    }

    int rows() {
      return batches.stream().mapToInt(List::size).sum();
    }
  }

  @Test
  void offeredEventsReachTheSink() throws Exception {
    BlockingQueue<UsageEvent> seen = new LinkedBlockingQueue<>();
    UsageEventWriter writer = start(10, batch -> {
      seen.addAll(batch);
      return batch.size();
    });
    UsageEvent e = event("one");

    assertTrue(writer.offer(e));

    assertEquals(e, seen.poll(AWAIT_S, TimeUnit.SECONDS));
    assertEquals(0L, writer.getDroppedRows());
  }

  @Test
  void fullQueueDropsCountsAndWarnsThrottled() throws Exception {
    GatedSink sink = new GatedSink();
    UsageEventWriter writer = start(1, sink);
    try (LogCapture logs = LogCapture.of(UsageEventWriter.class)) {
      assertTrue(writer.offer(event("in-flight")));
      await(sink.entered); // the thread holds it; the queue is empty again
      assertTrue(writer.offer(event("buffered")));
      assertEquals(1, writer.getQueuedRows());

      assertFalse(writer.offer(event("drop-1")));
      assertEquals(1, logs.messages(Level.WARN).size(), "first loss warns immediately");
      assertFalse(writer.offer(event("drop-2")));
      assertEquals(1, logs.messages(Level.WARN).size(), "second loss inside the interval is quiet");

      clock.addAndGet(UsageEventWriter.DROP_WARN_INTERVAL_MS);
      assertFalse(writer.offer(event("drop-3")));

      List<String> warns = logs.messages(Level.WARN);
      assertEquals(2, warns.size(), "one more line once the interval has elapsed");
      assertTrue(warns.get(0).contains("queue full"), warns.get(0));
      assertTrue(warns.get(1).contains("3 row(s) lost"), warns.get(1));
      assertEquals(3L, writer.getDroppedRows());
    } finally {
      sink.release.countDown();
    }
  }

  @Test
  void aNullEventIsCountedNotThrown() {
    UsageEventWriter writer = start(10, List::size);
    assertFalse(writer.offer(null));
    assertEquals(1L, writer.getDroppedRows());
  }

  @Test
  void drainsInBatchesOfAtMostTheBatchSize() throws Exception {
    GatedSink sink = new GatedSink();
    UsageEventWriter writer = start(1_000, sink);

    assertTrue(writer.offer(event("first")));
    await(sink.entered);
    for (int i = 0; i < 250; i++) {
      assertTrue(writer.offer(event("e" + i)));
    }
    sink.release.countDown();
    writer.shutdown(GRACE_MS);

    List<Integer> sizes = sink.batches.stream().map(List::size).toList();
    assertEquals(List.of(1, UsageEventWriter.BATCH_SIZE, UsageEventWriter.BATCH_SIZE, 50), sizes);
    assertEquals(251, sink.rows());
    assertEquals("e0", sink.batches.get(1).get(0).target(), "FIFO order is preserved");
    assertEquals(0L, writer.getDroppedRows());
  }

  @Test
  void theSinkGetsAnImmutableCopyThatOutlivesTheWritersBuffer() throws Exception {
    GatedSink sink = new GatedSink();
    UsageEventWriter writer = start(100, sink);

    writer.offer(event("a"));
    await(sink.entered);
    writer.offer(event("b"));
    writer.offer(event("c"));
    sink.release.countDown();
    writer.shutdown(GRACE_MS);

    List<UsageEvent> first = sink.batches.get(0);
    List<UsageEvent> second = sink.batches.get(1);
    assertEquals(1, first.size(), "the writer clears its buffer; the copy must not follow");
    assertEquals("a", first.get(0).target());
    assertEquals(List.of("b", "c"), second.stream().map(UsageEvent::target).toList());
    assertThrows(UnsupportedOperationException.class, () -> first.add(event("x")));
  }

  @Test
  void aThrowingSinkLosesTheWholeBatchAndTheThreadKeepsGoing() throws Exception {
    GatedSink gate = new GatedSink();
    CountDownLatch thrown = new CountDownLatch(1);
    CountDownLatch survivorWritten = new CountDownLatch(1);
    List<List<UsageEvent>> calls = new CopyOnWriteArrayList<>();
    UsageEventWriter writer = start(100, batch -> {
      calls.add(batch);
      if (calls.size() == 1) {
        return gate.write(batch); // blocks so the next three queue into one batch
      }
      if (calls.size() == 2) {
        thrown.countDown();
        throw new IllegalStateException("database down");
      }
      survivorWritten.countDown();
      return batch.size();
    });

    try (LogCapture logs = LogCapture.of(UsageEventWriter.class)) {
      writer.offer(event("warmup"));
      await(gate.entered);
      writer.offer(event("x1"));
      writer.offer(event("x2"));
      writer.offer(event("x3"));
      gate.release.countDown();
      await(thrown);
      writer.offer(event("survivor"));
      await(survivorWritten);

      assertEquals(3, calls.get(1).size());
      assertEquals(List.of("survivor"), calls.get(2).stream().map(UsageEvent::target).toList());
      assertEquals(3L, writer.getDroppedRows());
      List<String> warns = logs.messages(Level.WARN);
      assertEquals(1, warns.size());
      assertTrue(warns.get(0).contains("insert failed"), warns.get(0));
    }
  }

  @Test
  void aSinkReportingFewerRowsCountsTheDifference() throws Exception {
    GatedSink gate = new GatedSink();
    CountDownLatch partialDone = new CountDownLatch(1);
    List<Integer> answers = new CopyOnWriteArrayList<>(List.of(1, 2, -1));
    UsageEventWriter writer = start(100, batch -> {
      if (gate.batches.isEmpty()) {
        gate.write(batch);
      }
      int answer = answers.remove(0);
      if (answers.size() == 1) {
        partialDone.countDown();
      }
      return answer;
    });

    writer.offer(event("first")); // written: 1 of 1
    await(gate.entered);
    for (int i = 0; i < 5; i++) {
      writer.offer(event("b" + i)); // written: 2 of 5, so 3 lost
    }
    gate.release.countDown();
    await(partialDone);
    writer.offer(event("negative")); // written: -1 of 1, so 1 lost, never a negative loss
    writer.shutdown(GRACE_MS);

    assertTrue(answers.isEmpty());
    assertEquals(4L, writer.getDroppedRows());
  }

  @Test
  void shutdownDrainsWithinTheGracePeriod() {
    List<UsageEvent> seen = new CopyOnWriteArrayList<>();
    UsageEventWriter writer = start(100, batch -> {
      seen.addAll(batch);
      return batch.size();
    });
    try (LogCapture logs = LogCapture.of(UsageEventWriter.class)) {
      writer.offer(event("a"));
      writer.offer(event("b"));
      writer.offer(event("c"));

      writer.shutdown(GRACE_MS);

      assertEquals(3, seen.size());
      assertEquals(0, writer.getQueuedRows());
      assertEquals(0L, writer.getDroppedRows());
      assertTrue(logs.messages(Level.WARN).isEmpty(), "a clean, lossless stop says nothing");
    }
  }

  @Test
  void aSlowSinkPastTheGraceLeavesPendingRowsCountedAndWarned() throws Exception {
    GatedSink sink = new GatedSink();
    UsageEventWriter writer = start(100, sink);
    try (LogCapture logs = LogCapture.of(UsageEventWriter.class)) {
      writer.offer(event("stuck"));
      await(sink.entered);
      writer.offer(event("p1"));
      writer.offer(event("p2"));

      writer.shutdown(50L);

      await(sink.interrupted); // the thread is interrupted once the grace has passed
      assertEquals(2L, writer.getDroppedRows());
      assertEquals(0, writer.getQueuedRows());
      List<String> warns = logs.messages(Level.WARN);
      assertEquals(1, warns.size());
      assertTrue(warns.get(0).contains("2 row(s) still queued"), warns.get(0));
    } finally {
      sink.release.countDown();
    }
  }

  @Test
  void shutdownIsIdempotentAndLaterOffersAreDropped() {
    UsageEventWriter writer = start(100, List::size);
    writer.shutdown(GRACE_MS);

    try (LogCapture logs = LogCapture.of(UsageEventWriter.class)) {
      writer.shutdown(GRACE_MS);
      writer.shutdown();
      assertTrue(logs.messages(Level.WARN).isEmpty(), "a second shutdown does nothing");

      assertFalse(writer.offer(event("late")));
      assertEquals(1L, writer.getDroppedRows());
      assertTrue(logs.messages(Level.WARN).get(0).contains("writer stopped"));
    }
  }

  @Test
  void aCleanStopStillReportsEarlierLosses() {
    UsageEventWriter writer = start(100, List::size);
    writer.offer(null); // one counted loss
    try (LogCapture logs = LogCapture.of(UsageEventWriter.class)) {
      writer.shutdown(GRACE_MS);
      List<String> warns = logs.messages(Level.WARN);
      assertEquals(1, warns.size());
      assertTrue(warns.get(0).contains("stopped cleanly. 1 row(s)"), warns.get(0));
    }
  }
}
