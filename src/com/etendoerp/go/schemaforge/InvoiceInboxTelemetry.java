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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * ETP-6051 — the KPI event of the invoice inbox, {@code backend_invoice_inbox_document_confirmed}.
 *
 * <p>Sent by {@link InvoiceInboxConfirm} after the confirmation is durable (the invoice
 * completion committed), only on the first transition of the document to {@code CONFIRMED}: an
 * idempotent confirm and a failed one send nothing. It carries the {@code adjusted} flag the row had
 * (the confirm never writes it) and the tenant as {@code account_id}; <b>no record identifier
 * leaves this class</b>. Sending is on one bounded background thread (queue 1000, dropped when full),
 * once, with no retry, and never throws into the request. The row stays the source of truth of the
 * KPI ({@code status = CONFIRMED}, {@code adjusted}); the event is a convenience that can be
 * re-derived if it is lost.</p>
 */
final class InvoiceInboxTelemetry {

  private static final Logger log = LogManager.getLogger(InvoiceInboxTelemetry.class);
  private static final int QUEUE_CAPACITY = 1000;
  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  private InvoiceInboxTelemetry() {
  }

  /**
   * Emits the event. Never throws.
   *
   * @param adjusted  the {@code ADJUSTED} flag of the document at the confirmation
   * @param accountId the tenant ({@code AD_Client_ID}), may be {@code null}
   */
  static void documentConfirmed(boolean adjusted, String accountId) {
    try {
      Map<String, Object> properties = properties(adjusted, accountId);
      // The service (test seam or runtime) is resolved on the request thread, so a queued event
      // never drifts to a later seam. The runtime config reads properties only, no DAL.
      NeoTelemetryService service = telemetry();
      // ETP-6228: the sender has no session; the identity is captured here (ad_user_id).
      NeoTelemetryIdentity identity = service.captureIdentity(accountId, null);
      SENDER.execute(() -> {
        try {
          NeoTelemetryIdentity.runWith(identity, () -> service.emit(
              NeoTelemetryEvents.BACKEND_INVOICE_INBOX_DOCUMENT_CONFIRMED, properties, accountId));
        } catch (RuntimeException e) {
          log.debug("Invoice inbox telemetry: emit failed: {}", e.getMessage());
        }
      });
    } catch (RejectedExecutionException e) {
      log.debug("Invoice inbox telemetry queue full; dropping the event");
    } catch (RuntimeException e) {
      log.debug("Invoice inbox telemetry: could not build the event: {}", e.getMessage());
    }
  }

  static Map<String, Object> properties(boolean adjusted, String accountId) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (accountId != null) {
      properties.put("account_id", accountId);
    }
    properties.put("adjusted", adjusted);
    return properties;
  }

  /** Package-private seam for tests; pass {@code null} to restore the runtime service. */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (InvoiceInboxTelemetry.class) {
        local = TELEMETRY_SERVICE.get();
        if (local == null) {
          local = NeoTelemetryService.runtime();
          TELEMETRY_SERVICE.set(local);
        }
      }
    }
    return local;
  }

  private static ThreadPoolExecutor buildSender() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "invoice-inbox-kpi-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
