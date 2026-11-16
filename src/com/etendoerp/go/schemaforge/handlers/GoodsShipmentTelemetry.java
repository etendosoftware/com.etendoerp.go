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

package com.etendoerp.go.schemaforge.handlers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoBatchScope;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.ProductMovementsPolicy;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Sales goods shipment completion KPI telemetry (ETP-5908): {@code backend_goods_shipment_completed}.
 *
 * <p>Called by {@code GoodsShipmentHeaderHandler}, the only customization of the goods shipment
 * header. The pre-hook, run right before NEO completes the document natively, remembers the stored
 * {@code DocStatus}. The post-hook re-reads the row and emits only when this very request moved the
 * shipment into {@code CO} (it was not {@code CO} before), the shipment is a sales shipment
 * ({@code IsSOTrx='Y'} and its document type is not a return, the rule of the
 * {@code goods-shipment} bucket of {@code ProductTransactionsHandler}) and the action did not
 * fail. A draft, a void, a failed completion and a re-run on a completed document therefore send
 * nothing. Clones ({@code CloneShipmentHook}) are created as drafts and complete through this same
 * action.</p>
 *
 * <p>The event is handed to {@link NeoBatchScope#runAfterCommit}, so it is only sent for a
 * completion that is durable: inside a batch it is queued until the batch commits. Sending happens
 * on one bounded background thread (queue 1000, dropped when full), once, with no retry; the
 * Mixpanel sink adds {@code $insert_id} and {@code environment}. Telemetry never throws into the
 * request. <b>No record identifier leaves this class</b>: the only id is the tenant as
 * {@code account_id}.</p>
 */
public final class GoodsShipmentTelemetry {

  private static final Logger log = LogManager.getLogger(GoodsShipmentTelemetry.class);

  static final String ATTR_STATUS_BEFORE = "etgo.goodsShipmentTelemetry.statusBefore";
  static final String STATUS_COMPLETE = "CO";

  private static final String SHIPMENT_STATE_SQL =
      "SELECT io.docstatus, io.issotrx, dt.isreturn FROM m_inout io "
          + "LEFT JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id WHERE io.m_inout_id = ?";
  private static final String FLAG_MEMO_KEY = GoodsShipmentTelemetry.class.getName() + "|flag|";
  private static final int QUEUE_CAPACITY = 1000;

  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  private GoodsShipmentTelemetry() {
  }

  /** The stored columns the event decision is made from. */
  static final class ShipmentState {
    final String docStatus;
    final boolean sales;

    ShipmentState(String docStatus, boolean sales) {
      this.docStatus = docStatus;
      this.sales = sales;
    }
  }

  /**
   * Pre-hook of the {@code documentAction} POST: remembers the stored document status. Never
   * throws.
   *
   * @param ctx the request context the status is stored on; carries the shipment's id
   */
  public static void beforeAction(NeoContext ctx) {
    try {
      ShipmentState before = queryState(ctx.getRecordId());
      if (before != null) {
        ctx.setAttribute(ATTR_STATUS_BEFORE, before.docStatus == null ? "" : before.docStatus);
      }
    } catch (Exception e) {
      log.debug("Goods shipment telemetry: could not snapshot the status: {}", e.getMessage());
    }
  }

  /**
   * Post-hook of the {@code documentAction} POST: schedules the event for after commit when the
   * request completed a sales shipment. Never throws.
   *
   * @param ctx the request context holding the pre-hook status and the action's result
   */
  public static void afterAction(NeoContext ctx) {
    try {
      Object before = ctx.getAttribute(ATTR_STATUS_BEFORE);
      NeoResponse result = ctx.getPreviousResult();
      if (!(before instanceof String) || STATUS_COMPLETE.equals(before)
          || (result != null && result.getHttpStatus() >= 400)) {
        return;
      }
      ShipmentState now = queryState(ctx.getRecordId());
      if (now == null || !STATUS_COMPLETE.equals(now.docStatus) || !now.sales) {
        return;
      }
      String accountId = accountId(ctx);
      Map<String, Object> properties = properties(accountId, flagEnabled(accountId));
      NeoBatchScope.runAfterCommit(() -> send(properties, accountId));
    } catch (Exception e) {
      log.debug("Goods shipment telemetry: could not build the event: {}", e.getMessage());
    }
  }

  static Map<String, Object> properties(String accountId, boolean flagEnabled) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (accountId != null) {
      properties.put("account_id", accountId);
    }
    properties.put("flag_enabled", flagEnabled);
    return properties;
  }

  /** {@code product-movements-tab} for the request's account; a failed evaluation counts as off. */
  private static boolean flagEnabled(String accountId) {
    try {
      // Resolved once per batch (NeoBatchScope memo, keyed by tenant: the flag cannot change inside
      // one batch); outside a batch memoize() just evaluates, once per completion.
      return NeoBatchScope.memoize(FLAG_MEMO_KEY + accountId, ProductMovementsPolicy::isEnabled);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String accountId(NeoContext ctx) {
    OBContext obContext = ctx.getObContext();
    if (obContext == null) {
      obContext = OBContext.getOBContext();
    }
    return obContext != null && obContext.getCurrentClient() != null
        ? obContext.getCurrentClient().getId()
        : null;
  }

  static ShipmentState queryState(String shipmentId) throws SQLException {
    if (StringUtils.isBlank(shipmentId)) {
      return null;
    }
    Connection conn = OBDal.getInstance().getConnection();
    // On PostgreSQL a failed statement aborts the surrounding business transaction. The read runs
    // under a savepoint so a failure here is rolled back to it and cannot poison the completion.
    Savepoint savepoint = conn.setSavepoint();
    try {
      ShipmentState state = readState(conn, shipmentId);
      conn.releaseSavepoint(savepoint);
      return state;
    } catch (SQLException | RuntimeException e) {
      try {
        conn.rollback(savepoint);
      } catch (SQLException rollbackFailure) {
        log.debug("Goods shipment telemetry: savepoint rollback failed: {}",
            rollbackFailure.getMessage());
      }
      throw e;
    }
  }

  private static ShipmentState readState(Connection conn, String shipmentId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SHIPMENT_STATE_SQL)) {
      ps.setString(1, shipmentId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        boolean sales = "Y".equals(rs.getString(2)) && "N".equals(rs.getString(3));
        return new ShipmentState(rs.getString(1), sales);
      }
    }
  }

  private static void send(Map<String, Object> properties, String clientId) {
    try {
      SENDER.execute(() -> {
        try {
          telemetry().emit(NeoTelemetryEvents.BACKEND_GOODS_SHIPMENT_COMPLETED, properties,
              clientId);
        } catch (RuntimeException e) {
          log.debug("Goods shipment telemetry: emit failed: {}", e.getMessage());
        }
      });
    } catch (RejectedExecutionException e) {
      log.debug("Goods shipment telemetry queue full; dropping the event");
    }
  }

  /** Package-private seam for tests; pass {@code null} to restore the runtime service. */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (GoodsShipmentTelemetry.class) {
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
          Thread thread = new Thread(runnable, "goods-shipment-kpi-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
