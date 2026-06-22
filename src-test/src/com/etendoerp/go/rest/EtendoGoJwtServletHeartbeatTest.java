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
   * A heartbeat must emit a self-describing {@code type=heartbeat} NDJSON object that keeps
   * the connection alive yet stays invisible to the client: the frontend only reacts to
   * {@code type=progress} and {@code type=result} (onboardingApi onMessage), so a heartbeat
   * is parsed and then ignored. Being a real object (not a blank line) makes it visible in
   * raw stream captures and logs for debugging.
   */
  @Test
  public void sendHeartbeatEmitsIgnorableHeartbeatObject() throws Exception {
    StringWriter sink = new StringWriter();
    PrintWriter writer = new PrintWriter(sink);

    servlet.sendHeartbeat(writer);

    String output = sink.toString().trim();
    assertFalse("heartbeat must push bytes to keep the connection alive", output.isEmpty());
    JSONObject parsed = new JSONObject(output); // throws if the heartbeat is not valid JSON
    assertEquals("heartbeat must be a self-describing object", "heartbeat", parsed.getString("type"));
    assertFalse("heartbeat must not be treated as a result by the frontend",
        "result".equals(parsed.optString("type")));
    assertFalse("heartbeat must not be treated as progress by the frontend",
        "progress".equals(parsed.optString("type")));
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

  /**
   * Heartbeats interleaved with real progress lines must not corrupt the stream: every line
   * stays parseable NDJSON, the heartbeat lines carry {@code type=heartbeat} (ignored by the
   * frontend), and the progress/result lines remain intact. This mirrors the production
   * stream where a slow step emits a heartbeat between two progress events.
   */
  @Test
  public void heartbeatsInterleavedWithProgressKeepStreamValidNdjson() throws Exception {
    StringWriter sink = new StringWriter();
    PrintWriter writer = new PrintWriter(sink);

    servlet.sendProgress(writer, "organization", "in_progress", "Creating organization...");
    servlet.sendHeartbeat(writer);
    servlet.sendProgress(writer, "dataset", "in_progress", "Loading company data...");
    servlet.sendHeartbeat(writer);
    servlet.sendFinalResult(writer, true, "Environment created successfully");

    int heartbeatLines = 0;
    int progressLines = 0;
    boolean sawResult = false;
    for (String line : sink.toString().split("\n")) {
      if (line.trim().isEmpty()) {
        continue;
      }
      JSONObject parsed = new JSONObject(line); // throws if a heartbeat broke the JSON framing
      String type = parsed.optString("type");
      if ("heartbeat".equals(type)) {
        heartbeatLines++;
      } else if ("progress".equals(type)) {
        progressLines++;
      } else if ("result".equals(type)) {
        sawResult = true;
      }
    }

    assertEquals("both heartbeats must surface as ignorable heartbeat objects", 2, heartbeatLines);
    assertEquals("both progress events must remain parseable", 2, progressLines);
    assertTrue("the final result line must remain parseable", sawResult);
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
