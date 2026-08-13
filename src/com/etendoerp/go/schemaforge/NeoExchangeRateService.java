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

import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Validates whether a {@code C_Conversion_Rate} row exists for the given currency pair
 * and document date, returning the rate value when found.
 *
 * <p>Exposed at:
 * <pre>GET /sws/neo/validate-exchange-rate?fromCurrency={idOrIso}&amp;toCurrency={idOrIso}&amp;date={YYYY-MM-DD}</pre>
 *
 * <p>Both {@code fromCurrency} and {@code toCurrency} accept either the internal DB record ID
 * ({@code C_Currency_ID}) or the ISO 4217 code (e.g. {@code "USD"}, {@code "EUR"}).
 * ISO codes are unique by the ISO 4217 standard, so the lookup is unambiguous.
 *
 * <p>Response:
 * <pre>{FIELD_HAS_RATE: true,  "rate": 1.09}</pre>
 * <pre>{FIELD_HAS_RATE: false}</pre>
 *
 * <p>If no direct {@code FROM→TO} rate is configured, the endpoint falls back to the
 * inverse direction ({@code TO→FROM}) and returns {@code 1/rate}.  This mirrors the
 * standard Etendo behaviour: configuring EUR→USD at 1.16 implicitly covers USD→EUR
 * at 0.862, so clients do not need to register both directions.
 *
 * <p>This endpoint allows the frontend to validate the existence of an exchange rate when the
 * user changes the document currency. Since ETP-4027 the backend no longer blocks completion on
 * a missing rate — the validation lives entirely in the frontend at currency-change time.
 */
class NeoExchangeRateService {

  private static final Logger log = LogManager.getLogger(NeoExchangeRateService.class);

  private static final String PARAM_FROM_CURRENCY = "fromCurrency";
  private static final String PARAM_TO_CURRENCY   = "toCurrency";
  private static final String PARAM_DATE          = "date";
  private static final String FIELD_HAS_RATE      = "hasRate";

  private NeoExchangeRateService() {
  }

  static NeoResponse handleValidateExchangeRate(HttpServletRequest request) {
    String fromParam = request.getParameter(PARAM_FROM_CURRENCY);
    String toParam   = request.getParameter(PARAM_TO_CURRENCY);
    String dateStr   = request.getParameter(PARAM_DATE);

    if (fromParam == null || toParam == null || dateStr == null) {
      return NeoResponse.error(400,
          "Required parameters: fromCurrency, toCurrency, date (YYYY-MM-DD)");
    }

    try {
      java.time.LocalDate localDate = java.time.LocalDate.parse(dateStr.substring(0, 10));
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String orgId    = OBContext.getOBContext().getCurrentOrganization().getId();

      Connection conn = OBDal.getInstance().getConnection();

      // Resolve ISO codes or DB IDs to canonical DB record IDs
      String fromCurrencyId = resolveToDbId(fromParam, conn);
      String toCurrencyId   = resolveToDbId(toParam, conn);

      if (fromCurrencyId.equals(toCurrencyId)) {
        JSONObject body = new JSONObject();
        body.put(FIELD_HAS_RATE, true);
        body.put("rate", 1.0);
        return NeoResponse.ok(body);
      }

      // Try direct rate first; fall back to the inverse direction.
      // Mirrors standard Etendo behaviour: configuring EUR→USD at X implicitly
      // covers USD→EUR at 1/X — callers need only register one direction.
      Double directRate  = queryRate(conn, fromCurrencyId, toCurrencyId, clientId, orgId, localDate);
      if (directRate != null) {
        JSONObject body = new JSONObject();
        body.put(FIELD_HAS_RATE, true);
        body.put("rate", directRate);
        return NeoResponse.ok(body);
      }

      Double inverseRate = queryRate(conn, toCurrencyId, fromCurrencyId, clientId, orgId, localDate);
      JSONObject body = new JSONObject();
      if (inverseRate != null) {
        body.put(FIELD_HAS_RATE, true);
        body.put("rate", inverseRate != 0 ? 1.0 / inverseRate : 0);
      } else {
        body.put(FIELD_HAS_RATE, false);
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.warn("[ETP-4027] validate-exchange-rate failed: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Internal error checking exchange rate");
    }
  }

  /**
   * Returns the {@code multiplyrate} for the given currency pair on {@code date},
   * or {@code null} when no active rate row is found.
   */
  private static Double queryRate(
      Connection conn,
      String fromId, String toId,
      String clientId, String orgId,
      java.time.LocalDate date) throws java.sql.SQLException {

    // Include the client's own rates and the shared system ('0') rates. A client-specific rate
    // wins over the system rate (ad_client_id DESC picks the tenant row before '0' under LIMIT 1).
    String sql =
        "SELECT multiplyrate FROM c_conversion_rate"
      + " WHERE c_currency_id = ?"
      + " AND c_currency_id_to = ?"
      + " AND isactive = 'Y'"
      + " AND ad_client_id IN ('0', ?)"
      + " AND (ad_org_id = '0' OR ad_org_id = ?)"
      + " AND validfrom <= ?"
      + " AND (validto IS NULL OR validto >= ?)"
      + " ORDER BY ad_client_id DESC, validfrom DESC"
      + " LIMIT 1";

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, fromId);
      ps.setString(2, toId);
      ps.setString(3, clientId);
      ps.setString(4, orgId);
      ps.setDate(5, java.sql.Date.valueOf(date));
      ps.setDate(6, java.sql.Date.valueOf(date));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getDouble("multiplyrate") : null;
      }
    }
  }

  /**
   * Resolves a currency value to its canonical {@code C_Currency_ID}.
   *
   * Accepts either an existing DB record ID or an ISO 4217 code — matching
   * {@code c_currency_id = ?} OR {@code iso_code = ?}. ISO 4217 guarantees
   * uniqueness, so the lookup is unambiguous.
   *
   * Falls back to the original value unchanged for backwards compatibility
   * when no match is found (e.g. legacy callers passing raw IDs not in the DB).
   */
  private static String resolveToDbId(String currencyOrIso, Connection conn) throws java.sql.SQLException {
    String sql = "SELECT c_currency_id FROM c_currency WHERE (c_currency_id = ? OR iso_code = ?) AND isactive = 'Y' LIMIT 1";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, currencyOrIso);
      ps.setString(2, currencyOrIso);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
      }
    }
    return currencyOrIso;
  }
}
