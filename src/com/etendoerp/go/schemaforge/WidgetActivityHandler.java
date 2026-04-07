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
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
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
 * NeoHandler that returns recent activity from transactional documents
 * (invoices, orders, shipments) for the dashboard widget. Queries the 10 most
 * recently updated records across c_invoice, c_order, and m_inout.
 */
@Named("widgetActivityHandler")
public class WidgetActivityHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetActivityHandler.class);

  private static final String STATUS_COMPLETED_SUFFIX = " completed";
  private static final String STATUS_CREATED_SUFFIX = " created";

  private static final SimpleDateFormat ISO_FORMAT =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

  private static final String ACTIVITY_QUERY =
      "WITH recent_activity AS ( "
    + "  (SELECT 'invoice' AS doc_type, i.documentno, i.docstatus, i.grandtotal, "
    + "          i.updated AS event_time, u.name AS user_name, i.issotrx, "
    + "          i.c_invoice_id AS record_id "
    + "   FROM c_invoice i "
    + "   JOIN ad_user u ON u.ad_user_id = i.updatedby "
    + "   WHERE i.ad_client_id = :clientId "
    + "   ORDER BY i.updated DESC LIMIT 5) "
    + "  UNION ALL "
    + "  (SELECT 'order' AS doc_type, o.documentno, o.docstatus, o.grandtotal, "
    + "          o.updated AS event_time, u.name AS user_name, o.issotrx, "
    + "          o.c_order_id AS record_id "
    + "   FROM c_order o "
    + "   JOIN ad_user u ON u.ad_user_id = o.updatedby "
    + "   WHERE o.ad_client_id = :clientId "
    + "   ORDER BY o.updated DESC LIMIT 5) "
    + "  UNION ALL "
    + "  (SELECT 'shipment' AS doc_type, s.documentno, s.docstatus, "
    + "          NULL AS grandtotal, "
    + "          s.updated AS event_time, u.name AS user_name, s.issotrx, "
    + "          s.m_inout_id AS record_id "
    + "   FROM m_inout s "
    + "   JOIN ad_user u ON u.ad_user_id = s.updatedby "
    + "   WHERE s.ad_client_id = :clientId "
    + "   ORDER BY s.updated DESC LIMIT 5) "
    + ") "
    + "SELECT doc_type, documentno, docstatus, grandtotal, event_time, "
    + "       user_name, issotrx, record_id "
    + "FROM recent_activity "
    + "ORDER BY event_time DESC "
    + "LIMIT 10";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        @SuppressWarnings("unchecked")
        NativeQuery<Object[]> query = OBDal.getInstance()
            .getSession()
            .createNativeQuery(ACTIVITY_QUERY);
        query.setParameter("clientId", clientId);

        List<Object[]> rows = query.list();

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          String docType   = String.valueOf(row[0]);
          String documentNo = String.valueOf(row[1]);
          String docStatus = String.valueOf(row[2]);
          BigDecimal amount = (BigDecimal) row[3]; // may be null for shipments
          Timestamp eventTime = (Timestamp) row[4];
          String userName  = String.valueOf(row[5]);
          String isSoTrx   = String.valueOf(row[6]);
          String recordId  = String.valueOf(row[7]);

          String text = buildDescription(docType, documentNo, docStatus, amount, isSoTrx);
          String type = "CO".equals(docStatus) ? "system" : "note";

          JSONObject entry = new JSONObject();
          entry.put("id", recordId);
          entry.put("author", userName);
          entry.put("text", text);
          entry.put("timestamp", formatTimestamp(eventTime));
          entry.put("type", type);
          data.put(entry);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        responseData.put("count", data.length());

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.ok(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error building activity data", e);
      return NeoResponse.error(500, "Activity handler failed: " + e.getMessage());
    }
  }

  /**
   * Builds a human-readable description from the document metadata.
   */
  private static String buildDescription(String docType, String documentNo,
      String docStatus, BigDecimal amount, String isSoTrx) {
    boolean isSales = "Y".equals(isSoTrx);
    boolean isCompleted = "CO".equals(docStatus);
    String formattedAmount = amount != null ? " \u2014 " + formatAmount(amount) : "";

    switch (docType) {
      case "invoice":
        return buildDescription(
            isCompleted,
            isSales ? "Invoice" : "Purchase invoice",
            isSales ? "Draft invoice" : "Draft purchase invoice",
            documentNo,
            formattedAmount);

      case "order":
        return buildDescription(
            isCompleted,
            isSales ? "Sales order" : "Purchase order",
            isSales ? "Draft sales order" : "Purchase order",
            documentNo,
            formattedAmount);

      case "shipment":
        return buildDescription(
            isCompleted,
            "Shipment",
            "Draft shipment",
            documentNo,
            formattedAmount);

      default:
        return docType + " " + documentNo + " updated";
    }
  }

  private static String buildDescription(boolean isCompleted, String completedPrefix,
      String createdPrefix, String documentNo, String formattedAmount) {
    String prefix = isCompleted ? completedPrefix : createdPrefix;
    String suffix = isCompleted ? STATUS_COMPLETED_SUFFIX : STATUS_CREATED_SUFFIX;
    return prefix + " " + documentNo + suffix + formattedAmount;
  }

  /**
   * Formats a BigDecimal amount as "$1,234" with comma thousands separators.
   */
  private static String formatAmount(BigDecimal amount) {
    NumberFormat nf = NumberFormat.getInstance(Locale.US);
    nf.setMaximumFractionDigits(0);
    nf.setGroupingUsed(true);
    return "$" + nf.format(amount);
  }

  /**
   * Formats a Timestamp as ISO 8601 (without timezone).
   */
  private static String formatTimestamp(Timestamp ts) {
    if (ts == null) {
      return "";
    }
    synchronized (ISO_FORMAT) {
      return ISO_FORMAT.format(ts);
    }
  }
}
