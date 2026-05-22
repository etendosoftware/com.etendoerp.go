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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that powers the Cuentas (Financial Accounts) landing page introduced
 * by ETP-4095. Returns the active financial accounts visible to the current user
 * together with the aggregated summary widgets shown in the sidebar.
 *
 * <p>URL: {@code GET /sws/neo/financial-accounts-page}
 *
 * <p>Response shape:
 * <pre>
 * {
 *   "response": {
 *     "data": {
 *       "accounts": [
 *         {
 *           "id": "94EAA455D2644E04AB25D93BE5157B6D",
 *           "name": "BBVA Principal",
 *           "type": "B",
 *           "currentBalance": 12345.67,
 *           "currencyId": "102",
 *           "currencyIso": "EUR",
 *           "iban": "ES12...",
 *           "isDefault": true,
 *           "pendingCount": 4
 *         },
 *         ...
 *       ],
 *       "summary": {
 *         "totalBalance": 54321.00,
 *         "byCurrency": [{"currencyIso": "EUR", "total": 32000.00}, ...],
 *         "pending": {
 *           "accountsWithPending": 3,
 *           "suggestionsReady": 0,
 *           "byRule": 0
 *         }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>Filters accounts by the current client and the user's accessible organization
 * tree through {@link OrganizationStructureProvider}. Pending counters for
 * {@code suggestionsReady} and {@code byRule} return {@code 0} in T1 because the
 * matching-rule tables (ETBR_*) are introduced by ETP-4099.
 */
@Named("financial-accounts-page")
public class FinancialAccountsPageHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountsPageHandler.class);

  private static final String METHOD_GET = "GET";

  private static final String ACCOUNTS_SQL =
      "SELECT fa.fin_financial_account_id, fa.name, fa.type, fa.currentbalance, "
          + "       fa.c_currency_id, cur.iso_code, fa.iban, fa.isdefault "
          + "  FROM fin_financial_account fa "
          + "  JOIN c_currency cur ON cur.c_currency_id = fa.c_currency_id "
          + " WHERE fa.isactive = 'Y' "
          + "   AND fa.ad_client_id = ? "
          + "   AND fa.ad_org_id = ANY (?) "
          + " ORDER BY fa.isdefault DESC, fa.name ASC";

  private static final String PENDING_BY_ACCOUNT_SQL =
      "SELECT bs.fin_financial_account_id, COUNT(bsl.*) AS pending_lines "
          + "  FROM fin_bankstatementline bsl "
          + "  JOIN fin_bankstatement bs ON bs.fin_bankstatement_id = bsl.fin_bankstatement_id "
          + " WHERE bsl.fin_finacc_transaction_id IS NULL "
          + "   AND bsl.isactive = 'Y' "
          + "   AND bs.isactive = 'Y' "
          + "   AND bs.ad_client_id = ? "
          + "   AND bs.ad_org_id = ANY (?) "
          + " GROUP BY bs.fin_financial_account_id";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!METHOD_GET.equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed. Use GET.");
    }

    try {
      OBContext.setAdminMode(true);
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
      Set<String> orgs = accessibleOrgs(orgId);
      return buildPayload(clientId, orgs);
    } catch (Exception e) {
      log.error("Error building financial-accounts-page payload", e);
      return NeoResponse.error(500, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Resolves the accessible organization tree for the given root organization
   * via {@link OrganizationStructureProvider}. Exposed package-private so unit
   * tests can stub the result without instantiating an OB security context.
   *
   * @param orgId the root organization id
   * @return the set of organization ids visible from {@code orgId} (inclusive)
   */
  Set<String> accessibleOrgs(String orgId) {
    return new OrganizationStructureProvider().getChildTree(orgId, true);
  }

  /**
   * Orchestration seam: loads accounts and pending counters and assembles the
   * NEO response envelope. Exposed package-private so the unit tests can stub
   * the data loaders without touching {@link OBContext} static calls.
   *
   * @param clientId the AD_Client_ID filter
   * @param orgs the accessible organization tree
   * @return a 200 OK NeoResponse with the {@code accounts + summary} payload
   * @throws Exception if a downstream loader fails
   */
  NeoResponse buildPayload(String clientId, Set<String> orgs) throws Exception {
    List<AccountRow> accounts = loadAccounts(clientId, orgs);
    Map<String, Integer> pendingByAccount = loadPendingByAccount(clientId, orgs);

    JSONObject data = new JSONObject();
    data.put("accounts", buildAccountsArray(accounts, pendingByAccount));
    data.put("summary", buildSummary(accounts, pendingByAccount));

    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject envelope = new JSONObject();
    envelope.put("response", responseData);
    return NeoResponse.ok(envelope);
  }

  // ---------------------------------------------------------------------------
  // Data loaders
  // ---------------------------------------------------------------------------

  List<AccountRow> loadAccounts(String clientId, Set<String> orgs) throws Exception {
    List<AccountRow> rows = new ArrayList<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(ACCOUNTS_SQL)) {
      ps.setString(1, clientId);
      ps.setArray(2, conn.createArrayOf("varchar", orgs.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(new AccountRow(
              rs.getString(1),
              StringUtils.trimToEmpty(rs.getString(2)),
              StringUtils.trimToEmpty(rs.getString(3)),
              nullSafeBigDecimal(rs.getBigDecimal(4)),
              rs.getString(5),
              StringUtils.trimToEmpty(rs.getString(6)),
              StringUtils.trimToEmpty(rs.getString(7)),
              "Y".equals(rs.getString(8))));
        }
      }
    }
    return rows;
  }

  Map<String, Integer> loadPendingByAccount(String clientId, Set<String> orgs) throws Exception {
    Map<String, Integer> result = new LinkedHashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(PENDING_BY_ACCOUNT_SQL)) {
      ps.setString(1, clientId);
      ps.setArray(2, conn.createArrayOf("varchar", orgs.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), rs.getInt(2));
        }
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Response builders (package-private to allow unit tests to drive directly)
  // ---------------------------------------------------------------------------

  JSONArray buildAccountsArray(List<AccountRow> accounts, Map<String, Integer> pendingByAccount)
      throws JSONException {
    JSONArray arr = new JSONArray();
    for (AccountRow account : accounts) {
      JSONObject json = new JSONObject();
      json.put("id", account.id);
      json.put("name", account.name);
      json.put("type", account.type);
      json.put("currentBalance", account.currentBalance);
      json.put("currencyId", account.currencyId);
      json.put("currencyIso", account.currencyIso);
      json.put("iban", account.iban);
      json.put("isDefault", account.isDefault);
      json.put("pendingCount", pendingByAccount.getOrDefault(account.id, 0));
      arr.put(json);
    }
    return arr;
  }

  JSONObject buildSummary(List<AccountRow> accounts, Map<String, Integer> pendingByAccount)
      throws JSONException {
    JSONObject summary = new JSONObject();

    BigDecimal total = BigDecimal.ZERO;
    Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
    int accountsWithPending = 0;

    for (AccountRow account : accounts) {
      total = total.add(account.currentBalance);
      byCurrency.merge(account.currencyIso, account.currentBalance, BigDecimal::add);
      if (pendingByAccount.getOrDefault(account.id, 0) > 0) {
        accountsWithPending++;
      }
    }

    summary.put("totalBalance", total);

    JSONArray currencyArr = new JSONArray();
    for (Map.Entry<String, BigDecimal> entry : byCurrency.entrySet()) {
      JSONObject c = new JSONObject();
      c.put("currencyIso", entry.getKey());
      c.put("total", entry.getValue());
      currencyArr.put(c);
    }
    summary.put("byCurrency", currencyArr);

    JSONObject pending = new JSONObject();
    pending.put("accountsWithPending", accountsWithPending);
    // Suggestion engine (ETBR_Match_Suggestion) lands in ETP-4099 (T5).
    pending.put("suggestionsReady", 0);
    pending.put("byRule", 0);
    summary.put("pending", pending);

    return summary;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Returns {@link BigDecimal#ZERO} when {@code value} is {@code null}, otherwise
   * returns the value as-is. Package-private to allow direct coverage in tests.
   *
   * @param value the value to normalise (may be {@code null})
   * @return a non-null BigDecimal
   */
  static BigDecimal nullSafeBigDecimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Plain DTO for an account row read from the database. Package-private so the
   * unit test can construct fixtures without going through the DB layer.
   */
  static class AccountRow {
    final String id;
    final String name;
    final String type;
    final BigDecimal currentBalance;
    final String currencyId;
    final String currencyIso;
    final String iban;
    final boolean isDefault;

    AccountRow(String id, String name, String type, BigDecimal currentBalance,
        String currencyId, String currencyIso, String iban, boolean isDefault) {
      this.id = id;
      this.name = name;
      this.type = type;
      this.currentBalance = currentBalance;
      this.currencyId = currencyId;
      this.currencyIso = currencyIso;
      this.iban = iban;
      this.isDefault = isDefault;
    }
  }
}
