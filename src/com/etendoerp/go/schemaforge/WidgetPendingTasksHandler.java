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
import java.util.List;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * NeoHandler that returns pending tasks and alerts for the dashboard widget.
 * Queries real Etendo data: overdue invoices, pending confirmations,
 * pending shipments, and low stock alerts.
 */
@Named("widgetPendingTasksHandler")
public class WidgetPendingTasksHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetPendingTasksHandler.class);

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String JSON_COUNT = "count";
  private static final String JSON_TASK_KEY = "taskKey";
  private static final String JSON_TYPE = "type";
  private static final String JSON_TEXT = "text";
  private static final String JSON_LINK = "link";

  private static final String TYPE_WARNING = "warning";
  private static final String TYPE_INFO = "info";
  private static final String JSON_NAVIGATION = "navigation";
  private static final String NAVIGATION_TYPE_LIST = "list";
  private static final String FILTER_OVERDUE = "overdue";
  private static final String FILTER_COLLECTIONS_DUE_TODAY = "collectionsDueToday";
  private static final String FILTER_PAYMENTS_DUE_TODAY = "paymentsDueToday";
  private static final String FILTER_PAYMENTS_DUE = "paymentsDue";
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
        addCollectionsDueToday(data, clientId);
        addPaymentsDue(data, clientId);
        addPendingReceptions(data);
        addPendingSalesDeliveries(data);
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
   * Overdue invoices: completed sales invoices with outstanding amount > 0
   * whose due date has already passed (ETP-5012 — a future due date must
   * count as pending, not overdue). etgo_get_due_date() is the same function
   * backing the em_etgo_due_date virtual column the frontend list filters
   * and displays on, so the counter and the drill-down list stay in sync.
   */
  private void addOverdueInvoices(JSONArray data, String clientId) throws Exception {
    String sql = "SELECT COUNT(*), COALESCE(SUM(outstandingamt), 0)"
        + " FROM c_invoice ci"
        + " WHERE ci.issotrx = 'Y' AND ci.docstatus = 'CO' AND ci.outstandingamt > 0"
        + "   AND ci.ad_client_id = :clientId"
        + "   AND etgo_get_due_date(ci.c_invoice_id) < CURRENT_DATE";

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

    JSONObject task = buildTask(TYPE_WARNING,
        count + " overdue invoice" + (count != 1 ? "s" : ""),
        "sales-invoice",
        FILTER_OVERDUE,
        "/sales-invoice?filter=" + FILTER_OVERDUE,
        count,
        count > 1 ? "overdueInvoices_plural" : "overdueInvoices");
    task.put("amount", totalAmount);
    data.put(task);
  }

  /**
   * Collections due today: sales invoices with a payment schedule entry due today or earlier with outstanding > 0.
   */
  private void addCollectionsDueToday(JSONArray data, String clientId) throws Exception {
    addDueTodayInvoicesTask(data, clientId, "Y", "collection", "sales-invoice",
        FILTER_COLLECTIONS_DUE_TODAY,
        "/sales-invoice?filter=" + FILTER_COLLECTIONS_DUE_TODAY, FILTER_COLLECTIONS_DUE_TODAY);
  }

  /**
   * Payments due: unpaid purchase invoices whose due date has already arrived — both already
   * overdue and due exactly today (ETP-5017). Before this, only "due today" was reported, so the
   * card vanished the day after the due date even though the payment was still outstanding.
   *
   * <p>The reported count is the combined total; the badge state is communicated through the
   * taskKey, with "overdue" taking precedence over "due today" as the more critical state.
   *
   * <p>Both states drill down into the same {@code paymentsDue} list filter so the list always
   * shows exactly the rows this card counts.
   *
   * <p>Due dates come from {@code etgo_get_due_date()} — the function backing the
   * {@code em_etgo_due_date} virtual column the frontend list filters on — so the counter and the
   * drill-down can never disagree. It resolves to the earliest unpaid installment, which also
   * means a multi-installment invoice with one overdue and one due-today installment is correctly
   * reported as overdue.
   */
  private void addPaymentsDue(JSONArray data, String clientId) throws Exception {
    // Single query for both counts: two queries could straddle midnight and disagree.
    String sql = "SELECT COUNT(*),"
        + "   COALESCE(SUM(CASE WHEN etgo_get_due_date(ci.c_invoice_id) < CURRENT_DATE"
        + "                     THEN 1 ELSE 0 END), 0)"
        + " FROM c_invoice ci"
        + " WHERE ci.issotrx = 'N'"
        + "   AND ci.docstatus = 'CO'"
        + "   AND ci.outstandingamt > 0"
        + "   AND ci.ad_client_id = :clientId"
        + "   AND etgo_get_due_date(ci.c_invoice_id) <= CURRENT_DATE";

    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_CLIENT_ID, clientId);
    Object[] row = query.uniqueResult();

    long count = ((Number) row[0]).longValue();
    if (count == 0) {
      return;
    }
    long overdueCount = ((Number) row[1]).longValue();

    String taskKeyBase = overdueCount > 0 ? "paymentsOverdue" : FILTER_PAYMENTS_DUE_TODAY;
    String state = overdueCount > 0 ? " overdue" : " due today";

    data.put(buildTask(TYPE_WARNING,
        count + " payment" + (count != 1 ? "s" : "") + state,
        "purchase-invoice",
        FILTER_PAYMENTS_DUE,
        "/purchase-invoice?filter=" + FILTER_PAYMENTS_DUE,
        count,
        count > 1 ? taskKeyBase + "_plural" : taskKeyBase));
  }

  private void addDueTodayInvoicesTask(JSONArray data, String clientId, String isSalesTransaction,
      String entityLabel, String window, String filter, String link, String taskKeyBase) throws Exception {
    String sql = "SELECT COUNT(*)"
        + " FROM c_invoice ci"
        + " WHERE ci.issotrx = :isSalesTransaction"
        + "   AND ci.docstatus = 'CO'"
        + "   AND ci.outstandingamt > 0"
        + "   AND ci.ad_client_id = :clientId"
        + "   AND EXISTS ("
        + "     SELECT 1 FROM fin_payment_schedule fps"
        + "     WHERE fps.c_invoice_id = ci.c_invoice_id"
        + "       AND fps.duedate = CURRENT_DATE"
        + "   )";

    NativeQuery<Object> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(PARAM_CLIENT_ID, clientId);
    query.setParameter("isSalesTransaction", isSalesTransaction);
    long count = ((Number) query.uniqueResult()).longValue();
    if (count == 0) {
      return;
    }

    data.put(buildTask(TYPE_WARNING,
        count + " " + entityLabel + (count != 1 ? "s" : "") + " due today",
        window,
        filter,
        link,
        count,
        count > 1 ? taskKeyBase + "_plural" : taskKeyBase));
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

    JSONObject task = buildTask(TYPE_WARNING,
        count + " low stock alert" + (count != 1 ? "s" : ""),
        null,
        null,
        "/physical-inventory",
        count,
        count > 1 ? "lowStockAlerts_plural" : "lowStockAlerts");
    if (count == 1) {
      task.put("detail", (String) rows.get(0)[0]);
    }
    data.put(task);
  }

  /**
   * Pending sales deliveries: goods shipments (M_InOut) in Draft status awaiting processing.
   * Uses OBDal criteria so organization and client security filters are applied automatically.
   */
  private void addPendingSalesDeliveries(JSONArray data) throws Exception {
    OBCriteria<ShipmentInOut> crit = OBDal.getInstance().createCriteria(ShipmentInOut.class);
    crit.add(Restrictions.eq(ShipmentInOut.PROPERTY_SALESTRANSACTION, true));
    crit.add(Restrictions.eq(ShipmentInOut.PROPERTY_DOCUMENTSTATUS, "DR"));
    long count = ((Number) crit.setProjection(Projections.rowCount()).uniqueResult()).longValue();

    if (count == 0) {
      return;
    }

    JSONObject params = new JSONObject();
    params.put("DocStatus", "DR");
    data.put(buildTaskWithParams(TYPE_INFO,
        count + " goods shipment" + (count != 1 ? "s" : "") + " pending",
        "goods-shipment",
        params,
        "/goods-shipment?DocStatus=DR",
        count,
        count > 1 ? "pendingSalesDeliveries_plural" : "pendingSalesDeliveries"));
  }

  /**
   * Pending receptions: goods receipts (M_InOut) in Draft status awaiting processing.
   * Uses OBDal criteria so organization and client security filters are applied automatically.
   */
  private void addPendingReceptions(JSONArray data) throws Exception {
    OBCriteria<ShipmentInOut> crit = OBDal.getInstance().createCriteria(ShipmentInOut.class);
    crit.add(Restrictions.eq(ShipmentInOut.PROPERTY_SALESTRANSACTION, false));
    crit.add(Restrictions.eq(ShipmentInOut.PROPERTY_DOCUMENTSTATUS, "DR"));
    long count = ((Number) crit.setProjection(Projections.rowCount()).uniqueResult()).longValue();

    if (count == 0) {
      return;
    }

    JSONObject params = new JSONObject();
    params.put("DocStatus", "DR");
    data.put(buildTaskWithParams(TYPE_INFO,
        count + " goods receipt" + (count != 1 ? "s" : "") + " pending",
        "goods-receipt",
        params,
        "/goods-receipt?DocStatus=DR",
        count,
        count > 1 ? "pendingReceptions_plural" : "pendingReceptions"));
  }

  private JSONObject buildTaskWithParams(String type, String text, String window,
      JSONObject params, String link, long count, String taskKey) throws Exception {
    JSONObject task = new JSONObject();
    task.put(JSON_TYPE, type);
    task.put(JSON_TEXT, text);
    task.put(JSON_NAVIGATION, navigationParams(window, params));
    task.put(JSON_LINK, link);
    task.put(JSON_COUNT, count);
    task.put(JSON_TASK_KEY, taskKey);
    return task;
  }

  private JSONObject buildTask(String type, String text, String window, String filter, String link,
      long count, String taskKey) throws Exception {
    JSONObject task = new JSONObject();
    task.put(JSON_TYPE, type);
    task.put(JSON_TEXT, text);
    if (window != null && filter != null) {
      task.put(JSON_NAVIGATION, navigationFilter(window, filter));
    }
    task.put(JSON_LINK, link);
    task.put(JSON_COUNT, count);
    task.put(JSON_TASK_KEY, taskKey);
    return task;
  }

  private JSONObject navigationFilter(String window, String filter) throws Exception {
    JSONObject navigation = new JSONObject();
    navigation.put(JSON_TYPE, NAVIGATION_TYPE_LIST);
    navigation.put("window", window);
    navigation.put("filter", filter);
    return navigation;
  }

  private JSONObject navigationParams(String window, JSONObject params) throws Exception {
    JSONObject navigation = new JSONObject();
    navigation.put(JSON_TYPE, NAVIGATION_TYPE_LIST);
    navigation.put("window", window);
    navigation.put("params", params);
    return navigation;
  }
}
