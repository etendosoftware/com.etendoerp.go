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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Public entry point for recording a usage event into {@code ETGO_USAGE_EVENT}.
 *
 * <pre>{@code
 * UsageEventRecorder.record(UsageEvent.builder()
 *     .fromContext()
 *     .eventType(UsageEventTypes.AI_SUPPORT_MESSAGE)
 *     .sessionKey(conversationId)
 *     .property("model", model)
 *     .property("inputTokens", inputTokens)
 *     .build());
 * }</pre>
 *
 * <h2>Recording must never break or slow the caller</h2>
 *
 * <p>{@link #record(UsageEvent)} returns immediately and never throws: it validates, then hands the
 * event to {@link UsageEventWriter}, whose thread does the INSERT on its own connection in its own
 * transaction. Call it <b>after</b> the business transaction has committed, so a rolled-back
 * operation is not recorded as done — or record it with {@link UsageEvent#OUTCOME_ERROR}.</p>
 *
 * <h2>Unknown event types are dropped, loudly (D4)</h2>
 *
 * <p>An event whose type is not in {@link UsageEventTypes}, or whose source the table's check
 * constraint would reject, is dropped here rather than sent to the database. Both are programming or
 * version-skew errors, so they are logged at ERROR — throttled, first occurrence then at most one
 * line per {@value UsageEventWriter#DROP_WARN_INTERVAL_MS} ms — but never raised: an old or new
 * caller must degrade to "not recorded", not fail. Rejecting them here also protects the batch: one
 * row that violates a constraint would otherwise force the writer onto its row-by-row fallback.</p>
 *
 * <h2>Opt-out</h2>
 *
 * <p>On by default. An instance opts out with {@code usage.events.enabled=false} in
 * {@code Openbravo.properties} — a per-instance switch that needs neither an {@code OBContext} nor a
 * database read on the hot path, read exactly as {@code McpUsageLogger} reads its own flag.</p>
 */
public final class UsageEventRecorder {

  private static final Logger log = LogManager.getLogger(UsageEventRecorder.class);

  /** Per-instance opt-out. Absent or unparseable means enabled. */
  static final String PROP_ENABLED = "usage.events.enabled";

  /** Events rejected before reaching the writer (unknown type, invalid source). */
  private static final LogThrottle rejected = new LogThrottle(
      UsageEventWriter.DROP_WARN_INTERVAL_MS, System::currentTimeMillis);

  /**
   * The writer. Started lazily on the first recorded event, so an instance that never records
   * anything never starts a thread. Package-private and settable so a test can inject one built
   * over a capturing sink.
   */
  private static volatile UsageEventWriter writer;

  private UsageEventRecorder() {
  }

  /**
   * Record one event asynchronously. Returns immediately and never throws.
   *
   * @param event the event, built on the calling thread; ignored when null or when this instance has
   *              opted out
   */
  public static void record(UsageEvent event) {
    try {
      if (event == null || !isEnabled()) {
        return;
      }
      if (!UsageEventTypes.isKnown(event.eventType())) {
        noteRejected("unknown event type", event);
        return;
      }
      if (!UsageEvent.isValidSource(event.source())) {
        noteRejected("invalid source", event);
        return;
      }
      writer().offer(event);
    } catch (Throwable t) { // NOSONAR — usage recording must not escalate anything to the caller.
      log.debug("Could not record a usage event.", t);
    }
  }

  /** @return false when this instance has opted out of usage events */
  static boolean isEnabled() {
    try {
      String raw = OBPropertiesProvider.getInstance()
          .getOpenbravoProperties()
          .getProperty(PROP_ENABLED);
      // Default ON: only an explicit, well-formed "false" turns it off.
      return !StringUtils.equalsIgnoreCase(StringUtils.trimToNull(raw), "false");
    } catch (Exception e) {
      log.debug("Could not read {}; keeping it enabled.", PROP_ENABLED, e);
      return true;
    }
  }

  /**
   * Stop the writer, draining with a grace period and reporting what is lost. Called from
   * {@code NeoServlet.destroy()}, i.e. on undeploy or container shutdown. Never throws; events
   * recorded afterwards are counted as lost.
   */
  public static void shutdown() {
    try {
      UsageEventWriter local = writer;
      if (local != null) {
        local.shutdown();
      }
    } catch (Throwable t) { // NOSONAR — shutdown must not fail undeploy.
      log.debug("Could not stop the usage event writer.", t);
    }
  }

  /** @return events rejected before reaching the writer since startup */
  static long getRejectedEvents() {
    return rejected.total();
  }

  /** @return events the writer failed to persist since it started, or 0 when it never started */
  static long getDroppedRows() {
    UsageEventWriter local = writer;
    return local == null ? 0L : local.getDroppedRows();
  }

  /** Package-private seam for tests; pass null to go back to a lazily started production writer. */
  static void setWriter(UsageEventWriter testWriter) {
    writer = testWriter;
  }

  private static UsageEventWriter writer() {
    UsageEventWriter local = writer;
    if (local == null) {
      synchronized (UsageEventRecorder.class) {
        local = writer;
        if (local == null) {
          local = UsageEventWriter.start();
          writer = local;
        }
      }
    }
    return local;
  }

  private static void noteRejected(String reason, UsageEvent event) {
    try {
      if (rejected.note(1)) {
        log.error("Usage event dropped ({}: type '{}', source '{}'). Add the type to "
            + "UsageEventTypes if it is a new, agreed event. {} event(s) rejected since startup.",
            reason, printable(event.eventType()), printable(event.source()), rejected.total());
      }
    } catch (Throwable t) { // NOSONAR — accounting for a rejection must not fail the caller.
      log.debug("Could not account for a rejected usage event.", t);
    }
  }

  /**
   * The type and source may come from a browser: strip line breaks (log forging) and clip, so a
   * rejected value cannot shape or flood the log line that reports it.
   */
  static String printable(String value) {
    if (value == null) {
      return null;
    }
    return StringUtils.abbreviate(value.replaceAll("[\\r\\n\\t]", "_"), 80);
  }
}
