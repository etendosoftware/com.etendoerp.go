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

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns pending tasks and alerts for the dashboard widget.
 * Queries real Etendo data: overdue invoices, pending shipments, purchase orders
 * to confirm, and low stock alerts.
 */
@Named("widgetPendingTasksHandler")
public class WidgetPendingTasksHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetPendingTasksHandler.class);

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String JSON_COUNT = "count";
  private static final String JSON_TASK_KEY = "taskKey";

  private static final String TYPE_WARNING = "warning";
  private static final String TYPE_INFO = "info";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();
        JSONArray data = new JSONArray();

        addOverdueInvoices(data, clientId);
        addPendingShipments(data, clientId);
        addPurchaseOrdersToConfirm(data, clientId);
        addLowStockAlerts(data, clientId);

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        responseData.put(JSON_COUNT, data.length());

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.ok(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error building pending tasks data", e);
      return NeoResponse.error(500, "Pending tasks handler failed: " + e.getMessage());
    }
  }

  /**
   * Overdue invoices: completed sales invoices with outstanding amount > 0.
   */
  private void addOverdueInvoices(JSONArray data, String clientId) throws Exception {
    String sql = "SELECT COUNT(*), COALESCE(SUM(outstandingamt), 0)"
        + " FROM c_invoice"
        + " WHERE issotrx = 'Y' AND docstatus = 'CO' AND outstandingamt > 0"
        + " AND ad_client_id = :clientId";

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_CLIENT_ID, clientId);
    Object[] row = query.uniqueResult();

    long count = ((Number) row[0]).longValue();
    if (count == 0) {
      return;
    }

    BigDecimal totalAmount = row[1] instanceof BigDecimal
        ? (BigDecimal) row[1]
        : new BigDecimal(row[1].toString());

    JSONObject task = new JSONObject();
    task.put("type", TYPE_WARNING);
    task.put("text", count + " overdue invoice" + (count != 1 ? "s" : ""));
    task.put("link", "/sales-invoice?filter=overdue");
    task.put(JSON_COUNT, count);
    task.put(JSON_TASK_KEY, count > 1 ? "overdueInvoices_plural" : "overdueInvoices");
    task.put("amount", formatCurrency(totalAmount));
    data.put(task);
  }

  /**
   * Pending shipments: draft goods shipments for sales.
   */
  private void addPendingShipments(JSONArray data, String clientId) throws Exception {
    String sql = "SELECT COUNT(*)"
        + " FROM m_inout"
        + " WHERE issotrx = 'Y' AND docstatus = 'DR'"
        + " AND ad_client_id = :clientId";

    NativeQuery<Object> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_CLIENT_ID, clientId);
    long count = ((Number) query.uniqueResult()).longValue();

    if (count == 0) {
      return;
    }

    JSONObject task = new JSONObject();
    task.put("type", TYPE_INFO);
    task.put("text", count + " order" + (count != 1 ? "s" : "") + " pending shipment");
    task.put("link", "/goods-shipment?DocStatus=DR");
    task.put(JSON_COUNT, count);
    task.put(JSON_TASK_KEY, count > 1 ? "pendingShipments_plural" : "pendingShipments");
    data.put(task);
  }

  /**
   * Purchase orders to confirm: draft purchase orders.
   */
  private void addPurchaseOrdersToConfirm(JSONArray data, String clientId) throws Exception {
    String sql = "SELECT COUNT(*)"
        + " FROM c_order"
        + " WHERE issotrx = 'N' AND docstatus = 'DR'"
        + " AND ad_client_id = :clientId";

    NativeQuery<Object> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_CLIENT_ID, clientId);
    long count = ((Number) query.uniqueResult()).longValue();

    if (count == 0) {
      return;
    }

    JSONObject task = new JSONObject();
    task.put("type", TYPE_INFO);
    task.put("text", count + " purchase order" + (count != 1 ? "s" : "") + " to confirm");
    task.put("link", "/purchase-order?DocStatus=DR");
    task.put(JSON_COUNT, count);
    task.put(JSON_TASK_KEY, count > 1 ? "purchaseOrdersToConfirm_plural" : "purchaseOrdersToConfirm");
    data.put(task);
  }

  /**
   * Low stock alerts: stocked products where on-hand quantity is below the minimum.
   */
  @SuppressWarnings("unchecked")
  private void addLowStockAlerts(JSONArray data, String clientId) throws Exception {
    String sql = "SELECT p.name, SUM(sd.qtyonhand) AS qty, p.stock_min"
        + " FROM m_storage_detail sd"
        + " JOIN m_product p ON p.m_product_id = sd.m_product_id"
        + " WHERE p.isstocked = 'Y' AND p.stock_min > 0 AND p.ad_client_id = :clientId"
        + " GROUP BY p.m_product_id, p.name, p.stock_min"
        + " HAVING SUM(sd.qtyonhand) < p.stock_min";

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_CLIENT_ID, clientId);
    List<Object[]> rows = query.list();

    int count = rows.size();
    if (count == 0) {
      return;
    }

    JSONObject task = new JSONObject();
    task.put("type", TYPE_WARNING);
    task.put("text", count + " low stock alert" + (count != 1 ? "s" : ""));
    task.put("link", "/physical-inventory");
    task.put(JSON_COUNT, count);
    task.put(JSON_TASK_KEY, count > 1 ? "lowStockAlerts_plural" : "lowStockAlerts");
    if (count == 1) {
      task.put("detail", (String) rows.get(0)[0]);
    }
    data.put(task);
  }

  /**
   * Formats a BigDecimal as a currency string with $ prefix and comma thousands separator.
   */
  private static String formatCurrency(BigDecimal amount) {
    DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
    DecimalFormat fmt = new DecimalFormat("$#,##0", symbols);
    return fmt.format(amount);
  }
}
