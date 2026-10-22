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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Lists the currencies a document header can be switched to, each with its org→document rate for
 * the document's reference date (ETP-4027; reshaped by ETP-5840).
 *
 * <p>Exposed as an ACTION on the sales-quotation, sales-order, purchase-order, sales-invoice and
 * purchase-invoice header entities:
 * <pre>GET /sws/neo/{spec}/{entity}/{id|new}/action/currencyOptions[?date=yyyy-MM-dd]</pre>
 *
 * <p>Response ({@code response.data}):
 * <pre>[
 *   { "id": "&lt;C_Currency_ID&gt;", "isoCode": "EUR", "rate": 1.0,    "isOrgCurrency": true },
 *   { "id": "&lt;C_Currency_ID&gt;", "isoCode": "USD", "rate": 1.1523, "isOrgCurrency": false },
 *   { "id": "&lt;C_Currency_ID&gt;", "isoCode": "GBP", "rate": null,   "isOrgCurrency": false }
 * ]</pre>
 * The organization currency comes first with {@code rate = 1}; then EVERY active currency of the
 * client (or system), by ISO code — a currency without a rate for the date is listed with
 * {@code rate: null} (ETP-5840; before, it was left out and could not be picked at all).
 *
 * <p><b>Reference date.</b> The {@code date} parameter when present (query string; an MCP call may
 * pass it in the action body) — for a new record and for a saved one alike, so the form can ask for
 * the date it is editing before saving. Without it: the saved document's reference date (the one its
 * customization declares — {@code DateOrdered} for orders and quotations, {@code DateAcct} for
 * invoices), or today for a new record.
 *
 * <p><b>Which document.</b> The header customization that routes the action here publishes its
 * {@link DocumentCurrencyRate.RateType} on the context ({@link DocumentCurrencyRate#ATTR_RATE_TYPE});
 * this handler never decides it from the spec or entity name.
 *
 * <p>Filtering policy:
 * <ul>
 *   <li><b>Client</b>: the document's own client — never the session client — plus the system
 *       client. Prevents cross-client rate leakage.</li>
 *   <li><b>Org</b>: {@code IN (document org, '0')}; a new record uses the {@code organization}
 *       parameter when given, else the session organization.</li>
 * </ul>
 */
@Named("currencyOptionsHandler")
public class CurrencyOptionsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(CurrencyOptionsHandler.class);
  private static final String ACTION_NAME = "currencyOptions";
  private static final String PARAM_DATE = "date";
  private static final String PARAM_ORGANIZATION = "organization";
  private static final String KEY_RATE = "rate";

  /**
   * The contract a document header that routes {@code currencyOptions} here publishes for agents
   * (ETP-5558). Served over {@code GET} only — {@link #handle} refuses any other method — so the
   * contract says so, and the MCP calls it that way.
   */
  static final NeoActionContract CONTRACT = NeoActionContract.read(ACTION_NAME,
      "Lists every currency this document can use with its conversion rate from the organization "
          + "currency on the document's reference date: [{id, isoCode, rate, isOrgCurrency}]. "
          + "rate is null when no rate is configured for that date; the organization currency has "
          + "rate 1. Optional 'date' (yyyy-MM-dd) asks for another date. Use it to find "
          + "registerPayment's conversionRate when the account currency differs from the invoice "
          + "currency.")
      .withIdDescription("the id of the document whose date and organization scope the rates")
      .withHttpMethod("GET");

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
      RequestContext ctx = resolveRequestContext(context, recordId, isNew);

      String orgCurrencyId = OBCurrencyUtils.getOrgCurrency(ctx.orgId());
      if (orgCurrencyId == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Could not resolve org currency for org " + ctx.orgId());
      }

      JSONArray result = buildCurrencyOptions(orgCurrencyId, ctx.orgId(), ctx.clientId(), ctx.orderDate());
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", new JSONObject().put("data", result));
      return NeoResponse.ok(wrapper);

    } catch (RecordNotFoundException e) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
    } catch (IllegalArgumentException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("[ETP-4027] currencyOptions failed for record {}: {}", recordId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Internal error resolving currency options");
    }
  }

  /** Org/client/date needed to resolve currency options, whichever document type it comes from. */
  private record RequestContext(String orgId, String clientId, LocalDate orderDate) {}

  private static class RecordNotFoundException extends RuntimeException {
    RecordNotFoundException(String message) {
      super(message);
    }
  }

  private RequestContext resolveRequestContext(NeoContext context, String recordId, boolean isNew) {
    LocalDate requested = requestedDate(context);
    OBContext obCtx = OBContext.getOBContext();
    DocumentCurrencyRate.RateType type =
        (DocumentCurrencyRate.RateType) context.getAttribute(DocumentCurrencyRate.ATTR_RATE_TYPE);
    if (isNew || type == null) {
      String orgId = StringUtils.defaultIfBlank(param(context, PARAM_ORGANIZATION),
          obCtx.getCurrentOrganization().getId());
      return new RequestContext(orgId, obCtx.getCurrentClient().getId(),
          requested != null ? requested : LocalDate.now());
    }
    BaseOBObject doc = (BaseOBObject) OBDal.getInstance().get(type.entityName(), recordId);
    if (doc == null) {
      throw new RecordNotFoundException(type.entityName() + " not found: " + recordId);
    }
    LocalDate date = requested;
    if (date == null) {
      date = new DocumentCurrencyRate(type).dateOf(doc);
    }
    String orgId = ((BaseOBObject) doc.get("organization")).getId().toString();
    String clientId = ((BaseOBObject) doc.get("client")).getId().toString();
    return new RequestContext(orgId, clientId, date != null ? date : LocalDate.now());
  }

  /** The {@code date} parameter, or {@code null}; an unparseable one is the caller's error. */
  private static LocalDate requestedDate(NeoContext context) {
    String raw = param(context, PARAM_DATE);
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    LocalDate date = DocumentCurrencyRate.parseDate(raw);
    if (date == null) {
      throw new IllegalArgumentException("Invalid 'date' parameter, expected yyyy-MM-dd: " + raw);
    }
    return date;
  }

  /** A parameter from the query string (REST) or the action body (MCP). */
  private static String param(NeoContext context, String name) {
    Map<String, String> query = context.getQueryParams();
    String value = query != null ? query.get(name) : null;
    if (StringUtils.isBlank(value) && context.getRequestBody() != null) {
      value = context.getRequestBody().optString(name, null);
    }
    return StringUtils.trimToNull(value);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * Builds the option list: the org currency first ({@code rate = 1}, {@code isOrgCurrency: true}),
   * then every active currency of the client or the system by ISO code, with the rate
   * {@code C_Conversion_Rate} gives for {@code orderDate} (direct, else the inverse of the reverse
   * rate), or {@code rate: null} when there is none.
   */
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
    orgEntry.put(KEY_RATE, 1.0);
    orgEntry.put("isOrgCurrency", true);
    arr.put(orgEntry);

    for (Map.Entry<String, String> currency : selectableCurrencies(conn, clientId).entrySet()) {
      String cid = currency.getKey();
      if (cid.equals(orgCurrencyId)) {
        continue; // already added above
      }
      double[] data = rateMap.get(cid);
      JSONObject item = new JSONObject();
      item.put("id", cid);
      item.put("isoCode", currency.getValue() != null ? currency.getValue() : cid);
      item.put(KEY_RATE, data != null ? data[0] : JSONObject.NULL);
      item.put("isOrgCurrency", false);
      arr.put(item);
    }

    return arr;
  }

  /** Every active currency of the system or of {@code clientId}: id → ISO code, by ISO code. */
  private Map<String, String> selectableCurrencies(Connection conn, String clientId)
      throws java.sql.SQLException {
    Map<String, String> currencies = new LinkedHashMap<>();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT c_currency_id, iso_code FROM c_currency"
      + " WHERE isactive = 'Y' AND ad_client_id IN ('0', ?) ORDER BY iso_code")) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          currencies.put(rs.getString(1), rs.getString(2));
        }
      }
    }
    return currencies;
  }

  private Map<String, double[]> queryDirectRates(Connection conn, String orgCurrencyId,
      String clientId, String orgId, java.sql.Date sqlDate) throws java.sql.SQLException {
    Map<String, double[]> rateMap = new LinkedHashMap<>();
    // Include the client's own rates and the shared system ('0') rates. Rows are collected into a
    // map keyed by currency (last write wins), so ad_client_id ASC writes the '0' row first and the
    // client-specific row last, letting a tenant rate override the system rate for the same currency.
    String sql =
        "SELECT cr.c_currency_id_to AS cid, cr.multiplyrate"
      + " FROM c_conversion_rate cr"
      + " JOIN c_currency c ON c.c_currency_id = cr.c_currency_id_to"
      + " WHERE cr.c_currency_id = ?"
      + " AND cr.ad_client_id IN ('0', ?)"
      + " AND (cr.ad_org_id = '0' OR cr.ad_org_id = ?)"
      + " AND cr.isactive = 'Y'"
      + " AND c.isactive = 'Y'"
      + " AND cr.validfrom <= ?"
      + " AND (cr.validto IS NULL OR cr.validto >= ?)"
      // Last row per currency wins: the client's own over the system's, then the latest validfrom —
      // the precedence NeoExchangeRateService#queryRate applies (ETP-5840: the list and the saved
      // rate must agree).
      + " ORDER BY c.iso_code, cr.ad_client_id ASC, cr.validfrom ASC";
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
    // Include the client's own rates and the shared system ('0') rates; ad_client_id ASC lets a
    // tenant rate override the system rate for the same currency (map keyed by currency, last wins).
    String sql =
        "SELECT cr.c_currency_id AS cid, cr.multiplyrate AS inv_rate"
      + " FROM c_conversion_rate cr"
      + " JOIN c_currency c ON c.c_currency_id = cr.c_currency_id"
      + " WHERE cr.c_currency_id_to = ?"
      + " AND cr.ad_client_id IN ('0', ?)"
      + " AND (cr.ad_org_id = '0' OR cr.ad_org_id = ?)"
      + " AND cr.isactive = 'Y'"
      + " AND c.isactive = 'Y'"
      + " AND cr.validfrom <= ?"
      + " AND (cr.validto IS NULL OR cr.validto >= ?)"
      + " ORDER BY c.iso_code, cr.ad_client_id ASC, cr.validfrom ASC";
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
