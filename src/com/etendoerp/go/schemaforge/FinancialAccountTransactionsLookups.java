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

import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_ACTIVITY;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_BPARTNER;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_CAMPAIGN;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_COSTCENTER;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_ORGANIZATION;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_PRODUCT;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_PROJECT;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_SALESREGION;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_USER1;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.DIM_USER2;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsHandler.nullSafeBigDecimal;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.bpartnerRoleFilter;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.daysUntil;
import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.formatDmy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Read-only lookup endpoints backing the financial-account movement UI (business-partner, G/L item
 * and accounting-dimension pickers, plus outstanding-invoice search). Extracted from
 * {@link FinancialAccountTransactionsHandler} to keep that class below Sonar's per-class method
 * threshold; the handler routes the matching {@code GET ?action=...} requests here.
 */
final class FinancialAccountTransactionsLookups {

  private static final Logger log = LogManager.getLogger(FinancialAccountTransactionsLookups.class);

  private static final int LOOKUP_LIMIT = 25;
  private static final String KEY_RESPONSE = "response";
  private static final String FIELD_DESCRIPTION = "description";

  /** Dimension key → {table, id column} for the dimension-values lookup. */
  private static final Map<String, String[]> DIM_VALUE_TABLE = Map.ofEntries(
      Map.entry(DIM_ORGANIZATION, new String[] { "ad_org", "ad_org_id" }),
      Map.entry(DIM_BPARTNER, new String[] { "c_bpartner", "c_bpartner_id" }),
      Map.entry(DIM_PRODUCT, new String[] { "m_product", "m_product_id" }),
      Map.entry(DIM_PROJECT, new String[] { "c_project", "c_project_id" }),
      Map.entry(DIM_COSTCENTER, new String[] { "c_costcenter", "c_costcenter_id" }),
      Map.entry(DIM_ACTIVITY, new String[] { "c_activity", "c_activity_id" }),
      Map.entry(DIM_CAMPAIGN, new String[] { "c_campaign", "c_campaign_id" }),
      Map.entry(DIM_SALESREGION, new String[] { "c_salesregion", "c_salesregion_id" }),
      Map.entry(DIM_USER1, new String[] { DIM_USER1, "user1_id" }),
      Map.entry(DIM_USER2, new String[] { DIM_USER2, "user2_id" }));

  /**
   * Outstanding (unpaid) invoice payment-schedule details for a business partner. The {@code amount}
   * of a {@code FIN_Payment_ScheduleDetail} whose {@code fin_payment_detail_id} is NULL is the
   * amount still pending payment; those rows are exactly what Classic's "Add Payment" grid shows.
   */
  private static final String OUTSTANDING_INVOICES_SQL =
      "SELECT psd.fin_payment_scheduledetail_id AS id,"
          + "       i.documentno AS doc_no,"
          + "       COALESCE(i.description, '') AS descr,"
          + "       bp.name AS bpartner,"
          + "       i.dateinvoiced AS invoice_date,"
          + "       ps.duedate AS due_date,"
          + "       COALESCE(pm.name, '') AS payment_method,"
          + "       COALESCE(proj.name, '') AS project,"
          + "       COALESCE(o.documentno, '') AS order_no,"
          + "       cur.iso_code AS currency_iso,"
          + "       i.grandtotal AS invoiced_amount,"
          + "       ps.amount AS expected_amount,"
          + "       psd.amount AS outstanding_amount"
          + "  FROM fin_payment_scheduledetail psd"
          + "  JOIN fin_payment_schedule ps ON ps.fin_payment_schedule_id = psd.fin_payment_schedule_invoice"
          + "  JOIN c_invoice i ON i.c_invoice_id = ps.c_invoice_id"
          + "  JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id"
          + "  JOIN c_currency cur ON cur.c_currency_id = i.c_currency_id"
          + "  LEFT JOIN fin_paymentmethod pm ON pm.fin_paymentmethod_id = COALESCE(ps.fin_paymentmethod_id, i.fin_paymentmethod_id)"
          + "  LEFT JOIN c_project proj ON proj.c_project_id = i.c_project_id"
          + "  LEFT JOIN c_order o ON o.c_order_id = i.c_order_id"
          + " WHERE psd.fin_payment_detail_id IS NULL"
          + "   AND psd.isactive = 'Y'"
          + "   AND i.docstatus = 'CO'"
          + "   AND i.issotrx = ?"
          + "   AND i.ad_client_id IN (?, ?)";

  /** Optional clause: scope to a single business partner when one is given. */
  private static final String OUTSTANDING_INVOICES_BP_CLAUSE = "   AND i.c_bpartner_id = ?";
  private static final String OUTSTANDING_INVOICES_TAIL =
      " ORDER BY ps.duedate ASC, i.documentno ASC LIMIT 500";

  private FinancialAccountTransactionsLookups() {
  }

  /**
   * Handles {@code GET ?action=bpartner-lookup&q=...&role=customer|vendor} —
   * fuzzy search over {@code c_bpartner.name}, scoped to the current client +
   * system records. When {@code role=customer} only customers are returned;
   * when {@code role=vendor} only vendors; otherwise all active bpartners.
   */
  static NeoResponse bpartnerLookup(NeoContext context) {
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    String role = context.getQueryParams() != null ? context.getQueryParams().getOrDefault("role", "") : "";
    return runLookup(
        "SELECT c_bpartner_id AS id, name FROM c_bpartner"
            + " WHERE isactive='Y' AND ad_client_id IN (?, ?)"
            + bpartnerRoleFilter(role)
            + "   AND LOWER(name) LIKE ?"
            + " ORDER BY name ASC"
            + " LIMIT " + LOOKUP_LIMIT,
        q, "bpartners");
  }

  static NeoResponse glItemLookup(NeoContext context) {
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    return runLookup(
        "SELECT c_glitem_id AS id, name FROM c_glitem"
            + " WHERE isactive='Y' AND ad_client_id IN (?, ?)"
            + "   AND LOWER(name) LIKE ?"
            + " ORDER BY name ASC"
            + " LIMIT " + LOOKUP_LIMIT,
        q, "glItems");
  }

  /**
   * Handles {@code GET ?action=dimension-values&dimension=<key>&q=...} — returns
   * the selectable values for an accounting dimension (organizations, projects,
   * cost centers, …), scoped to the current client + system records. The table
   * and id column come from a fixed whitelist, never from user input.
   */
  static NeoResponse dimensionValues(NeoContext context) {
    String dim = context.getQueryParams() != null ? context.getQueryParams().get("dimension") : null;
    String[] meta = dim != null ? DIM_VALUE_TABLE.get(dim) : null;
    if (meta == null) {
      return NeoResponse.error(400, "Unknown or unsupported dimension: " + dim);
    }
    String q = context.getQueryParams() != null ? context.getQueryParams().get("q") : "";
    String sql = "SELECT " + meta[1] + " AS id, name FROM " + meta[0]
        + " WHERE isactive = 'Y' AND " + meta[1] + " <> '0' AND ad_client_id IN (?, ?)"
        + "   AND LOWER(name) LIKE ?"
        + " ORDER BY name ASC"
        + " LIMIT 200";
    return runLookup(sql, q, "values");
  }

  /**
   * Handles {@code GET ?action=outstanding-invoices&bpartnerId=...&doc=in|out} —
   * returns the unpaid invoices scoped by direction ({@code doc=in} → sales /
   * cobro, {@code doc=out} → purchase / pago). When {@code bpartnerId} is blank
   * the invoices of ALL business partners are returned (so the user can allocate
   * a payment to any contact); when given, they are scoped to that partner.
   */
  static NeoResponse outstandingInvoices(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String bpartnerId = qp != null ? qp.get("bpartnerId") : null;
    boolean hasBp = StringUtils.isNotBlank(bpartnerId);
    String doc = qp != null ? qp.getOrDefault("doc", "in") : "in";
    String isSotrx = "out".equals(doc) ? "N" : "Y";
    String sql = OUTSTANDING_INVOICES_SQL
        + (hasBp ? OUTSTANDING_INVOICES_BP_CLAUSE : "")
        + OUTSTANDING_INVOICES_TAIL;
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      LocalDate today = LocalDate.now();
      JSONArray arr = new JSONArray();
      try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
        ps.setString(1, isSotrx);
        ps.setString(2, "0");
        ps.setString(3, clientId);
        if (hasBp) ps.setString(4, bpartnerId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            arr.put(marshalOutstandingInvoice(rs, today));
          }
        }
      }
      JSONObject data = new JSONObject();
      data.put("invoices", arr);
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject envelope = new JSONObject();
      envelope.put(KEY_RESPONSE, responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Outstanding invoices lookup failed for bpartner {}", bpartnerId, e);
      return NeoResponse.error(500, "Outstanding invoices lookup failed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Maps one outstanding-invoice row to the JSON shape the payment UI expects. */
  private static JSONObject marshalOutstandingInvoice(ResultSet rs, LocalDate today) throws Exception {
    java.sql.Date invoiceDate = rs.getDate("invoice_date");
    java.sql.Date dueDate = rs.getDate("due_date");
    JSONObject row = new JSONObject();
    row.put("id", rs.getString("id"));
    row.put("no", StringUtils.trimToEmpty(rs.getString("doc_no")));
    row.put(FIELD_DESCRIPTION, StringUtils.trimToEmpty(rs.getString("descr")));
    row.put("bp", StringUtils.trimToEmpty(rs.getString(DIM_BPARTNER)));
    row.put("fecha", formatDmy(invoiceDate));
    row.put("venc", formatDmy(dueDate));
    row.put("dias", daysUntil(dueDate, today));
    row.put("metodo", StringUtils.trimToEmpty(rs.getString("payment_method")));
    row.put("proyecto", StringUtils.trimToEmpty(rs.getString(DIM_PROJECT)));
    row.put("orderNo", StringUtils.trimToEmpty(rs.getString("order_no")));
    row.put("cc", "");
    row.put("mon", StringUtils.trimToEmpty(rs.getString("currency_iso")));
    row.put("total", nullSafeBigDecimal(rs.getBigDecimal("invoiced_amount")));
    row.put("expected", nullSafeBigDecimal(rs.getBigDecimal("expected_amount")));
    row.put("pend", nullSafeBigDecimal(rs.getBigDecimal("outstanding_amount")));
    return row;
  }

  private static NeoResponse runLookup(String sql, String q, String resultKey) {
    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String pattern = "%" + (q == null ? "" : q.toLowerCase()) + "%";
      JSONArray arr = new JSONArray();
      try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
        ps.setString(1, "0");
        ps.setString(2, clientId);
        ps.setString(3, pattern);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            JSONObject row = new JSONObject();
            row.put("id", rs.getString("id"));
            row.put("name", StringUtils.trimToEmpty(rs.getString("name")));
            arr.put(row);
          }
        }
      }
      JSONObject data = new JSONObject();
      data.put(resultKey, arr);
      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      JSONObject envelope = new JSONObject();
      envelope.put(KEY_RESPONSE, responseData);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Lookup failed for query '{}'", q, e);
      return NeoResponse.error(500, "Lookup failed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
