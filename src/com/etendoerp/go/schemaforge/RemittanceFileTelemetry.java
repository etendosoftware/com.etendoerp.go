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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.module.remittance.Remittance;

import com.etendoerp.go.schemaforge.telemetry.NeoRoleGroups;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * ETP-6247 — {@code backend_remittance_file_generated}: a remittance's SEPA file was generated and
 * stored, by «Confirmar» ({@code is_regeneration: false}) or «Regenerar fichero»
 * ({@code is_regeneration: true}).
 *
 * <p><b>Properties.</b> {@code account_id} (the tenant, {@code AD_Client_ID}), {@code direction}
 * ({@code AR} collection, {@code AP} payment), {@code invoice_count} (distinct invoices of the
 * remittance), {@code is_regeneration}, {@code channel} ({@code ui} or {@code mcp}),
 * {@code flag_enabled} (always {@code true}: only reached with {@code sepa-remittances} on) and
 * {@code role_groups} (the SPA's role segmentation, {@link NeoRoleGroups}). No record id, amount or
 * name ever leaves this class; {@link NeoTelemetryService} drops any key it does not allow.
 *
 * <p><b>Best effort, once the action succeeds.</b> The properties are read on the request
 * thread and the event is handed to {@link NeoBatchScope#runAfterCommit}, which outside a batch
 * runs it at once — when the action has succeeded but BEFORE the request's own commit, so a commit
 * that failed afterwards would still have emitted it — and inside a batch only after the batch
 * commits (never when it rolls back). It is sent on one bounded background thread, the pattern of
 * {@link BankBounceKpiTelemetry}. Never throws into the action.
 */
final class RemittanceFileTelemetry {

  private static final Logger log = LogManager.getLogger(RemittanceFileTelemetry.class);

  static final String PROP_ACCOUNT_ID = "account_id";
  static final String PROP_DIRECTION = "direction";
  static final String PROP_INVOICE_COUNT = "invoice_count";
  static final String PROP_IS_REGENERATION = "is_regeneration";
  static final String PROP_CHANNEL = "channel";
  static final String PROP_FLAG_ENABLED = "flag_enabled";
  static final String PROP_ROLE_GROUPS = "role_groups";
  static final String DIRECTION_AR = "AR";
  static final String DIRECTION_AP = "AP";

  private static final int QUEUE_CAPACITY = 1000;
  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  private RemittanceFileTelemetry() {
  }

  /**
   * Schedules the event for a stored file. Never throws.
   *
   * @param remittance   the remittance
   * @param collection   whether it collects (CORE) rather than pays (SCT)
   * @param invoiceCount its distinct invoices
   * @param regeneration whether the file replaced an earlier one
   */
  static void fileGenerated(Remittance remittance, boolean collection, int invoiceCount,
      boolean regeneration) {
    try {
      Map<String, Object> properties = properties(
          remittance.getClient() != null ? remittance.getClient().getId() : null, collection,
          invoiceCount, regeneration, BankBounceKpiTelemetry.channel(), NeoRoleGroups.current());
      String clientId = (String) properties.get(PROP_ACCOUNT_ID);
      NeoBatchScope.runAfterCommit(() -> send(properties, clientId));
    } catch (RuntimeException e) {
      log.debug("Remittance file telemetry skipped: {}", e.getMessage());
    }
  }

  /**
   * The event's properties.
   *
   * @param accountId    the tenant
   * @param collection   collection (AR) or payment (AP)
   * @param invoiceCount distinct invoices
   * @param regeneration whether the file replaced an earlier one
   * @param channel      {@code ui} or {@code mcp}
   * @param roleGroups   the role groups; omitted when empty
   * @return the properties, in a stable order
   */
  static Map<String, Object> properties(String accountId, boolean collection, int invoiceCount,
      boolean regeneration, String channel, List<String> roleGroups) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(PROP_ACCOUNT_ID, accountId);
    properties.put(PROP_DIRECTION, collection ? DIRECTION_AR : DIRECTION_AP);
    properties.put(PROP_INVOICE_COUNT, invoiceCount);
    properties.put(PROP_IS_REGENERATION, regeneration);
    properties.put(PROP_CHANNEL, channel);
    properties.put(PROP_FLAG_ENABLED, true);
    if (roleGroups != null && !roleGroups.isEmpty()) {
      properties.put(PROP_ROLE_GROUPS, roleGroups);
    }
    return properties;
  }

  private static void send(Map<String, Object> properties, String clientId) {
    try {
      NeoTelemetryService service = telemetry();
      SENDER.execute(() -> service.emit(NeoTelemetryEvents.BACKEND_REMITTANCE_FILE_GENERATED,
          properties, clientId));
    } catch (RejectedExecutionException e) {
      log.debug("Remittance file telemetry queue full; dropping the event");
    }
  }

  /**
   * Package-private seam for tests; pass {@code null} to restore the runtime service.
   *
   * @param service the service events are emitted through, or {@code null}
   */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (RemittanceFileTelemetry.class) {
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
          Thread thread = new Thread(runnable, "remittance-file-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
