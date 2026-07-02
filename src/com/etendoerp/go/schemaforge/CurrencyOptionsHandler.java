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

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;

/**
 * Returns the set of currencies that have a defined conversion to/from the order's org currency,
 * scoped to the order's own client and org, for the order's date period.
 *
 * <p>Exposed as an ACTION on sales-order, purchase-order, sales-invoice and purchase-invoice
 * header entities:
 * <pre>GET /sws/neo/sales-order/header/{orderId}/action/currencyOptions</pre>
 * <pre>GET /sws/neo/purchase-order/header/{orderId}/action/currencyOptions</pre>
 * <pre>GET /sws/neo/sales-invoice/header/{invoiceId}/action/currencyOptions</pre>
 * <pre>GET /sws/neo/purchase-invoice/header/{invoiceId}/action/currencyOptions</pre>
 *
 * <p>Response:
 * <pre>[
 *   { "id": "&lt;C_Currency_ID&gt;", "isoCode": "USD", "rate": 1.1523 },
 *   { "id": "&lt;C_Currency_ID&gt;", "isoCode": "EUR", "rate": 0.9200 }
 * ]</pre>
 *
 * <p>The org currency itself is always included with {@code rate = 1.0}.
 * Currencies without a rate for the order's date period are excluded.
 *
 * <p>Filtering policy:
 * <ul>
 *   <li><b>Client</b>: {@code order.getClient().getId()} — never the session client.
 *       Prevents cross-client rate leakage (e.g. rates created by the admin user
 *       under a different client context).</li>
 *   <li><b>Org</b>: {@code IN (order.getOrganization().getId(), '0')} — includes
 *       shared/global rates (org '*') as standard Etendo pattern.</li>
 * </ul>
 */
@Named("currencyOptionsHandler")
public class CurrencyOptionsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CurrencyOptionsHandler.class);
  private static final String ACTION_NAME = "currencyOptions";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    if (!ACTION_NAME.equals(context.getFieldName())) {
      return null;
    }
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "currencyOptions only supports GET");
    }

    String recordId = context.getRecordId();
    boolean isNew = recordId == null || recordId.isEmpty() || "new".equals(recordId);

    try {
      String orgId;
      String clientId;
      LocalDate orderDate;

      if (isNew) {
        // New record: fall back to session context (org/client not yet bound to a document)
        OBContext obCtx = OBContext.getOBContext();
        orgId    = obCtx.getCurrentOrganization().getId();
        clientId = obCtx.getCurrentClient().getId();
        orderDate = LocalDate.now();
      } else {
        String specName = context.getSpecName();
        if (specName != null && specName.contains("invoice")) {
          Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);
          if (invoice == null) {
            return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Invoice not found: " + recordId);
          }
          orgId    = invoice.getOrganization().getId();
          clientId = invoice.getClient().getId();
          orderDate = invoice.getInvoiceDate() != null
              ? java.time.Instant.ofEpochMilli(invoice.getInvoiceDate().getTime())
                  .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
              : LocalDate.now();
        } else {
          Order order = OBDal.getInstance().get(Order.class, recordId);
          if (order == null) {
            return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Order not found: " + recordId);
          }
          orgId    = order.getOrganization().getId();
          clientId = order.getClient().getId();
          orderDate = order.getOrderDate() != null
              ? java.time.Instant.ofEpochMilli(order.getOrderDate().getTime())
                  .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
              : LocalDate.now();
        }
      }

      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(orgId);
      if (orgCurrencyId == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Could not resolve org currency for org " + orgId);
      }

      JSONArray result = buildCurrencyOptions(orgCurrencyId, orgId, clientId, orderDate);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", new JSONObject().put("data", result));
      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("[ETP-4027] currencyOptions failed for record {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Internal error resolving currency options");
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * Queries {@code C_Conversion_Rate} for all currencies reachable from {@code orgCurrencyId}
   * (direct and inverse directions), filtered by client and org, valid on {@code orderDate}.
   * The org currency itself is prepended with rate 1.0.
   */
  // placeholders are bound via PreparedStatement — no injection risk
  @SuppressWarnings("java:S2077")
  private JSONArray buildCurrencyOptions(
      String orgCurrencyId, String orgId, String clientId, LocalDate orderDate) throws Exception {

    Connection conn = OBDal.getInstance().getConnection();
    java.sql.Date sqlDate = java.sql.Date.valueOf(orderDate);

    Map<String, double[]> rateMap = queryDirectRates(conn, orgCurrencyId, clientId, orgId, sqlDate);
    mergeInverseRates(conn, orgCurrencyId, rateMap, clientId, orgId, sqlDate);

    JSONArray arr = new JSONArray();
    String orgIsoCode = resolveIsoCode(conn, orgCurrencyId);
    JSONObject orgEntry = new JSONObject();
    orgEntry.put("id", orgCurrencyId);
    orgEntry.put("isoCode", orgIsoCode != null ? orgIsoCode : orgCurrencyId);
    orgEntry.put("rate", 1.0);
    arr.put(orgEntry);

    for (Map.Entry<String, double[]> e : rateMap.entrySet()) {
      String cid = e.getKey();
      if (cid.equals(orgCurrencyId)) continue; // already added above
      double[] data = e.getValue();
      String isoCode = resolveIsoCode(conn, cid);
      JSONObject item = new JSONObject();
      item.put("id", cid);
      item.put("isoCode", isoCode != null ? isoCode : cid);
      item.put("rate", data[0]);
      arr.put(item);
    }

    return arr;
  }

  private Map<String, double[]> queryDirectRates(Connection conn, String orgCurrencyId,
      String clientId, String orgId, java.sql.Date sqlDate) throws java.sql.SQLException {
    Map<String, double[]> rateMap = new LinkedHashMap<>();
    String sql =
        "SELECT cr.c_currency_id_to AS cid, cr.multiplyrate"
      + " FROM c_conversion_rate cr"
      + " JOIN c_currency c ON c.c_currency_id = cr.c_currency_id_to"
      + " WHERE cr.c_currency_id = ?"
      + " AND cr.ad_client_id = ?"
      + " AND (cr.ad_org_id = '0' OR cr.ad_org_id = ?)"
      + " AND cr.isactive = 'Y'"
      + " AND c.isactive = 'Y'"
      + " AND cr.validfrom <= ?"
      + " AND (cr.validto IS NULL OR cr.validto >= ?)"
      + " ORDER BY c.iso_code";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, orgCurrencyId);
      ps.setString(2, clientId);
      ps.setString(3, orgId);
      ps.setDate(4, sqlDate);
      ps.setDate(5, sqlDate);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rateMap.put(rs.getString("cid"), new double[]{ rs.getDouble("multiplyrate") });
        }
      }
    }
    return rateMap;
  }

  private void mergeInverseRates(Connection conn, String orgCurrencyId,
      Map<String, double[]> rateMap, String clientId, String orgId,
      java.sql.Date sqlDate) throws java.sql.SQLException {
    Map<String, double[]> inverseMap = new LinkedHashMap<>();
    String sql =
        "SELECT cr.c_currency_id AS cid, cr.multiplyrate AS inv_rate"
      + " FROM c_conversion_rate cr"
      + " JOIN c_currency c ON c.c_currency_id = cr.c_currency_id"
      + " WHERE cr.c_currency_id_to = ?"
      + " AND cr.ad_client_id = ?"
      + " AND (cr.ad_org_id = '0' OR cr.ad_org_id = ?)"
      + " AND cr.isactive = 'Y'"
      + " AND c.isactive = 'Y'"
      + " AND cr.validfrom <= ?"
      + " AND (cr.validto IS NULL OR cr.validto >= ?)"
      + " ORDER BY c.iso_code";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, orgCurrencyId);
      ps.setString(2, clientId);
      ps.setString(3, orgId);
      ps.setDate(4, sqlDate);
      ps.setDate(5, sqlDate);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String cid = rs.getString("cid");
          if (!rateMap.containsKey(cid)) {
            double invRate = rs.getDouble("inv_rate");
            inverseMap.put(cid, new double[]{ invRate != 0 ? 1.0 / invRate : 0.0 });
          }
        }
      }
    }
    if (!inverseMap.isEmpty()) {
      resolveIsoCodesForInverse(conn, inverseMap, rateMap);
    }
  }

  private void resolveIsoCodesForInverse(Connection conn, Map<String, double[]> inverseMap,
      Map<String, double[]> rateMap) throws java.sql.SQLException {
    String isoSql = "SELECT c_currency_id, iso_code FROM c_currency WHERE c_currency_id = ANY(?) AND isactive = 'Y'";
    String[] ids = inverseMap.keySet().toArray(new String[0]);
    try (PreparedStatement ps = conn.prepareStatement(isoSql)) {
      ps.setArray(1, conn.createArrayOf("text", ids));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String cid = rs.getString(1);
          double[] entry = inverseMap.get(cid);
          if (entry != null) {
            rateMap.put(cid, entry);
          }
        }
      }
    }
  }

  private String resolveIsoCode(Connection conn, String currencyId) {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT iso_code FROM c_currency WHERE c_currency_id = ? AND isactive = 'Y' LIMIT 1")) {
      ps.setString(1, currencyId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (Exception e) {
      return null;
    }
  }
}
