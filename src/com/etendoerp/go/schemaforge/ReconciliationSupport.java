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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Stateless helpers shared by {@link ReconciliationHandler}: the NEO response envelope, date /
 * amount formatting, optional SQL date-range binding and small JSON / entity utilities. Extracted
 * so the handler stays focused on routing + business flow (and under the method-count limit).
 */
final class ReconciliationSupport {

  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";
  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private ReconciliationSupport() {
  }

  /** Wraps {@code data} in the standard {@code { response: { data } }} NEO envelope. */
  static NeoResponse envelope(JSONObject data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put(KEY_DATA, data);
    JSONObject wrapper = new JSONObject();
    wrapper.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(wrapper);
  }

  /** Formats a timestamp as an ISO-8601 UTC string ({@code ""} when null). */
  static String formatDate(Timestamp ts) {
    return ts == null ? "" : ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  /** Returns {@code value}, or {@link BigDecimal#ZERO} when null. */
  static BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Binds the four parameters of the two optional date-range clauses
   * ({@code (CAST(? AS date) IS NULL OR col >= ?)} and the {@code <=} twin): dateFrom, dateFrom,
   * dateTo, dateTo. Blank bounds are bound as SQL NULL, which makes the clause a no-op.
   *
   * @return the next free parameter index
   */
  static int bindDateRange(PreparedStatement ps, int idx, String dateFrom, String dateTo)
      throws SQLException {
    setDateOrNull(ps, idx++, dateFrom);
    setDateOrNull(ps, idx++, dateFrom);
    setDateOrNull(ps, idx++, dateTo);
    setDateOrNull(ps, idx++, dateTo);
    return idx;
  }

  private static void setDateOrNull(PreparedStatement ps, int idx, String date) throws SQLException {
    if (StringUtils.isBlank(date)) {
      ps.setNull(idx, Types.DATE);
    } else {
      ps.setDate(idx, Date.valueOf(date));
    }
  }

  /** Maps the UI docType filter to the {@code FIN_Payment.isreceipt} flag (payments → 'N'). */
  static String docTypeToIsReceipt(String docType) {
    return "payments".equalsIgnoreCase(docType) ? "N" : "Y";
  }

  /** Reads the {@code operationIds} string array from a request body (blanks skipped). */
  static List<String> readOperationIds(JSONObject body) throws JSONException {
    List<String> ids = new ArrayList<>();
    JSONArray arr = body.optJSONArray("operationIds");
    if (arr == null) {
      return ids;
    }
    for (int i = 0; i < arr.length(); i++) {
      if (arr.isNull(i)) {
        continue;
      }
      String id = arr.optString(i, null);
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  /** True when the statement line's bank statement belongs to the given financial account. */
  static boolean belongsToAccount(FIN_BankStatementLine line, String accountId) {
    return line.getBankStatement() != null
        && line.getBankStatement().getAccount() != null
        && accountId.equals(line.getBankStatement().getAccount().getId());
  }

  /** Signed amount of a transaction: {@code depositAmount - paymentAmount}. */
  static BigDecimal signedAmount(FIN_FinaccTransaction trx) {
    return nullSafe(trx.getDepositAmount()).subtract(nullSafe(trx.getPaymentAmount()));
  }

  /** Organizations accessible from {@code orgId} (its child tree, including itself). */
  static Set<String> accessibleOrgs(String orgId) {
    return new OrganizationStructureProvider().getChildTree(orgId, true);
  }
}
