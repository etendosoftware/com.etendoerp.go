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

/**
 * Central registry for widget query policy templates.
 */
final class WidgetQueryPolicyRegistry {

  static final class WidgetQueryPolicy {
    final String fallbackSql;
    final String rangedSql;

    WidgetQueryPolicy(String fallbackSql, String rangedSql) {
      this.fallbackSql = fallbackSql;
      this.rangedSql = rangedSql;
    }
  }

  private WidgetQueryPolicyRegistry() {
  }

  static WidgetQueryPolicy bestProducts() {
    return new WidgetQueryPolicy(
        "WITH all_data AS ("
            + "  SELECT p.m_product_id, p.name, SUM(il.qtyinvoiced) AS qty, SUM(il.linenetamt) AS amount"
            + "  FROM c_invoiceline il"
            + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  JOIN m_product p ON p.m_product_id = il.m_product_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "  GROUP BY p.m_product_id, p.name"
            + "), curr_period AS ("
            + "  SELECT il.m_product_id, SUM(il.linenetamt) AS amount"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = ("
            + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId)"
            + "  GROUP BY il.m_product_id"
            + "), prev_period AS ("
            + "  SELECT il.m_product_id, SUM(il.linenetamt) AS amount"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = ("
            + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId"
            + "        AND date_trunc('month', dateinvoiced) < ("
            + "          SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "          WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId))"
            + "  GROUP BY il.m_product_id"
            + ")"
            + " SELECT all_data.m_product_id AS id, all_data.name, all_data.qty, all_data.amount,"
            + "  CASE WHEN prev_period.amount IS NOT NULL AND prev_period.amount > 0"
            + "    THEN ROUND(((COALESCE(curr_period.amount, 0) - prev_period.amount) / prev_period.amount) * 100)"
            + "    ELSE NULL END AS trend_pct"
            + " FROM all_data"
            + " LEFT JOIN curr_period ON all_data.m_product_id = curr_period.m_product_id"
            + " LEFT JOIN prev_period ON all_data.m_product_id = prev_period.m_product_id"
            + " ORDER BY all_data.amount DESC LIMIT 10",
        "WITH all_data AS ("
            + "  SELECT p.m_product_id, p.name, SUM(il.qtyinvoiced) AS qty, SUM(il.linenetamt) AS amount"
            + "  FROM c_invoiceline il"
            + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  JOIN m_product p ON p.m_product_id = il.m_product_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND i.dateinvoiced >= %s"
            + "  GROUP BY p.m_product_id, p.name"
            + "), curr_period AS ("
            + "  SELECT il.m_product_id, SUM(il.linenetamt) AS amount"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW())"
            + "  GROUP BY il.m_product_id"
            + "), prev_period AS ("
            + "  SELECT il.m_product_id, SUM(il.linenetamt) AS amount"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = ("
            + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId"
            + "        AND date_trunc('month', dateinvoiced) < date_trunc('month', NOW()))"
            + "  GROUP BY il.m_product_id"
            + ")"
            + " SELECT all_data.m_product_id AS id, all_data.name, all_data.qty, all_data.amount,"
            + "  CASE WHEN prev_period.amount IS NOT NULL AND prev_period.amount > 0"
            + "    THEN ROUND(((COALESCE(curr_period.amount, 0) - prev_period.amount) / prev_period.amount) * 100)"
            + "    ELSE NULL END AS trend_pct"
            + " FROM all_data"
            + " LEFT JOIN curr_period ON all_data.m_product_id = curr_period.m_product_id"
            + " LEFT JOIN prev_period ON all_data.m_product_id = prev_period.m_product_id"
            + " ORDER BY all_data.amount DESC LIMIT 10");
  }

  static WidgetQueryPolicy bestSellers() {
    return new WidgetQueryPolicy(
        "WITH all_data AS ("
            + "  SELECT p.m_product_id, p.name, SUM(il.qtyinvoiced) AS qty, COALESCE(uom.name, '') AS uom"
            + "  FROM c_invoiceline il"
            + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  JOIN m_product p ON p.m_product_id = il.m_product_id"
            + "  LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "  GROUP BY p.m_product_id, p.name, uom.name"
            + "), curr_period AS ("
            + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = ("
            + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId)"
            + "  GROUP BY il.m_product_id"
            + "), prev_period AS ("
            + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = ("
            + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId"
            + "        AND date_trunc('month', dateinvoiced) < ("
            + "          SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "          WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId))"
            + "  GROUP BY il.m_product_id"
            + ")"
            + " SELECT all_data.m_product_id AS id, all_data.name, all_data.qty, all_data.uom,"
            + "  CASE WHEN prev_period.qty IS NOT NULL AND prev_period.qty > 0"
            + "    THEN ROUND(((COALESCE(curr_period.qty, 0) - prev_period.qty) / prev_period.qty) * 100)"
            + "    ELSE NULL END AS trend_pct"
            + " FROM all_data"
            + " LEFT JOIN curr_period ON all_data.m_product_id = curr_period.m_product_id"
            + " LEFT JOIN prev_period ON all_data.m_product_id = prev_period.m_product_id"
            + " ORDER BY all_data.qty DESC LIMIT 10",
        "WITH all_data AS ("
            + "  SELECT p.m_product_id, p.name, SUM(il.qtyinvoiced) AS qty, COALESCE(uom.name, '') AS uom"
            + "  FROM c_invoiceline il"
            + "  JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  JOIN m_product p ON p.m_product_id = il.m_product_id"
            + "  LEFT JOIN c_uom uom ON uom.c_uom_id = p.c_uom_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND i.dateinvoiced >= %s"
            + "  GROUP BY p.m_product_id, p.name, uom.name"
            + "), curr_period AS ("
            + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = date_trunc('month', NOW())"
            + "  GROUP BY il.m_product_id"
            + "), prev_period AS ("
            + "  SELECT il.m_product_id, SUM(il.qtyinvoiced) AS qty"
            + "  FROM c_invoiceline il JOIN c_invoice i ON i.c_invoice_id = il.c_invoice_id"
            + "  WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') AND i.ad_client_id = :clientId"
            + "    AND date_trunc('month', i.dateinvoiced) = ("
            + "      SELECT date_trunc('month', MAX(dateinvoiced)) FROM c_invoice"
            + "      WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId"
            + "        AND date_trunc('month', dateinvoiced) < date_trunc('month', NOW()))"
            + "  GROUP BY il.m_product_id"
            + ")"
            + " SELECT all_data.m_product_id AS id, all_data.name, all_data.qty, all_data.uom,"
            + "  CASE WHEN prev_period.qty IS NOT NULL AND prev_period.qty > 0"
            + "    THEN ROUND(((COALESCE(curr_period.qty, 0) - prev_period.qty) / prev_period.qty) * 100)"
            + "    ELSE NULL END AS trend_pct"
            + " FROM all_data"
            + " LEFT JOIN curr_period ON all_data.m_product_id = curr_period.m_product_id"
            + " LEFT JOIN prev_period ON all_data.m_product_id = prev_period.m_product_id"
            + " ORDER BY all_data.qty DESC LIMIT 10");
  }

  static WidgetQueryPolicy recentInvoices() {
    return new WidgetQueryPolicy(
        "WITH max_date AS ( "
            + "  SELECT MAX(dateinvoiced) AS last_date "
            + "  FROM c_invoice "
            + "  WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId "
            + ") "
            + "SELECT i.c_invoice_id, i.documentno, bp.name AS client, "
            + "  TO_CHAR(i.dateinvoiced, 'DD-MM-YYYY') AS date, i.grandtotal AS amount, i.docstatus AS status "
            + "FROM c_invoice i "
            + "JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id "
            + "WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') "
            + "  AND i.ad_client_id = :clientId "
            + "  AND i.dateinvoiced > (SELECT last_date - CAST('30 days' AS interval) FROM max_date) "
            + "ORDER BY i.dateinvoiced DESC LIMIT 5",
        "SELECT i.c_invoice_id, i.documentno, bp.name AS client, "
            + "  TO_CHAR(i.dateinvoiced, 'DD-MM-YYYY') AS date, i.grandtotal AS amount, i.docstatus AS status "
            + "FROM c_invoice i "
            + "JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id "
            + "WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') "
            + "  AND i.ad_client_id = :clientId "
            + "  AND i.dateinvoiced >= %s "
            + "ORDER BY i.dateinvoiced DESC LIMIT 5");
  }

  static WidgetQueryPolicy topClients() {
    return new WidgetQueryPolicy(
        "WITH max_date AS ( "
            + "  SELECT MAX(dateinvoiced) AS last_date "
            + "  FROM c_invoice "
            + "  WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId "
            + ") "
            + "SELECT bp.name, SUM(i.grandtotal) AS total "
            + "FROM c_invoice i "
            + "JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id "
            + "WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') "
            + "  AND i.ad_client_id = :clientId "
            + "  AND i.dateinvoiced > (SELECT last_date - CAST('12 months' AS interval) FROM max_date) "
            + "GROUP BY bp.c_bpartner_id, bp.name "
            + "ORDER BY SUM(i.grandtotal) DESC "
            + "LIMIT 10",
        "SELECT bp.name, SUM(i.grandtotal) AS total "
            + "FROM c_invoice i "
            + "JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id "
            + "WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') "
            + "  AND i.ad_client_id = :clientId "
            + "  AND i.dateinvoiced >= %s "
            + "GROUP BY bp.c_bpartner_id, bp.name "
            + "ORDER BY SUM(i.grandtotal) DESC "
            + "LIMIT 10");
  }
}
