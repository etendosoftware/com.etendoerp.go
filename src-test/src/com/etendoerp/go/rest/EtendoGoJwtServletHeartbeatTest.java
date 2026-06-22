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
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for the onboarding NDJSON keepalive heartbeat and the client-disconnect
 * detection added to {@link EtendoGoJwtServlet}.
 *
 * <p>These tests exercise the streaming-resilience mechanism in isolation (no DB / no
 * DAL): the heartbeat keeps bytes flowing so a slow onboarding step never trips the
 * CloudFront/proxy inter-byte timeout, and a dropped client is detected via
 * {@link PrintWriter#checkError()} instead of being silently swallowed.
 */
public class EtendoGoJwtServletHeartbeatTest {

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();

  /**
   * A heartbeat must emit a line that keeps the connection alive but that the frontend
   * discards. The frontend skips empty lines (onboardingApi processLines:
   * {@code if (!line.trim()) continue}), so the heartbeat line must be blank once trimmed.
   */
  @Test
  public void sendHeartbeatWritesFrontendSafeBlankLine() {
    StringWriter sink = new StringWriter();
    PrintWriter writer = new PrintWriter(sink);

    servlet.sendHeartbeat(writer);

    String output = sink.toString();
    assertFalse("heartbeat must push bytes to keep the connection alive", output.isEmpty());
    assertTrue("heartbeat line must be blank so the frontend skips it", output.trim().isEmpty());
  }

  /**
   * The scheduler must keep emitting heartbeats while the onboarding runs and MUST stop
   * once shutdownNow() is called — i.e. it never lingers after the request finishes.
   */
  @Test
  public void heartbeatRunsWhileActiveThenStopsAfterShutdown() throws Exception {
    CountingWriter counter = new CountingWriter();
    PrintWriter writer = new PrintWriter(counter);

    // Tiny cadence so the test does not wait the production 10s interval.
    ScheduledExecutorService heartbeat =
        servlet.startOnboardingHeartbeat(writer, 20, TimeUnit.MILLISECONDS);
    try {
      waitUntil(() -> counter.flushes.get() >= 2, 2000);
      assertTrue("heartbeat should emit repeatedly while active", counter.flushes.get() >= 2);
    } finally {
      heartbeat.shutdownNow();
    }

    assertTrue("scheduler must terminate promptly after shutdownNow",
        heartbeat.awaitTermination(1, TimeUnit.SECONDS));

    int afterShutdown = counter.flushes.get();
    Thread.sleep(120); // longer than the heartbeat interval
    assertEquals("no heartbeats may be emitted after shutdown", afterShutdown, counter.flushes.get());
  }

  /**
   * When the client connection is gone (broken pipe), PrintWriter swallows the
   * IOException and sets its error flag. The send helpers must not throw, and the error
   * must be observable via checkError() — that flag is what the servlet logs on.
   */
  @Test
  public void disconnectedClientIsDetectedAndDoesNotThrow() {
    PrintWriter writer = new PrintWriter(new FailingWriter());

    servlet.sendProgress(writer, "setup", "in_progress", "Setting up...");
    servlet.sendFinalResult(writer, true, "Environment created successfully");
    servlet.sendHeartbeat(writer);

    assertTrue("a broken pipe must be detectable via checkError()", writer.checkError());
  }

  /**
   * On a healthy connection the stream stays valid NDJSON: one parseable JSON object per
   * line, with the final result line carrying type=result and the success flag.
   */
  @Test
  public void progressAndResultEmitWellFormedNdjsonWhenConnected() throws Exception {
    StringWriter sink = new StringWriter();
    PrintWriter writer = new PrintWriter(sink);

    servlet.sendProgress(writer, "client", "in_progress", "Creating client...");
    servlet.sendFinalResult(writer, true, "Environment created successfully");

    String[] lines = sink.toString().split("\n");
    JSONObject progress = null;
    JSONObject result = null;
    for (String line : lines) {
      if (line.trim().isEmpty()) {
        continue;
      }
      JSONObject parsed = new JSONObject(line); // throws if not valid JSON
      if ("result".equals(parsed.optString("type"))) {
        result = parsed;
      } else if ("progress".equals(parsed.optString("type"))) {
        progress = parsed;
      }
    }

    assertTrue("a progress line must be emitted", progress != null);
    assertEquals("client", progress.getString("step"));
    assertTrue("a result line must be emitted", result != null);
    assertTrue("result must report success", result.getBoolean("success"));
  }

  private static void waitUntil(BooleanSupplierWithTimeout condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.get()) {
        return;
      }
      Thread.sleep(10);
    }
  }

  @FunctionalInterface
  private interface BooleanSupplierWithTimeout {
    boolean get();
  }

  /** Thread-safe writer that counts flush() calls (one per heartbeat). */
  private static final class CountingWriter extends Writer {
    final AtomicInteger flushes = new AtomicInteger();
    private final StringBuffer buffer = new StringBuffer();

    @Override
    public void write(char[] cbuf, int off, int len) {
      buffer.append(cbuf, off, len);
    }

    @Override
    public void flush() {
      flushes.incrementAndGet();
    }

    @Override
    public void close() {
      // no-op
    }
  }

  /** Writer that always fails, simulating a dropped client (broken pipe). */
  private static final class FailingWriter extends Writer {
    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
      throw new IOException("broken pipe");
    }

    @Override
    public void flush() throws IOException {
      throw new IOException("broken pipe");
    }

    @Override
    public void close() {
      // no-op
    }
  }
}
