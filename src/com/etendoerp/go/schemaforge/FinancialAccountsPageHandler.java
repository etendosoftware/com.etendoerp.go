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
 * by ETP-4095. Returns the financial accounts visible to the current user (both
 * active and archived, each flagged via {@code active}) together with the
 * aggregated summary widgets shown in the sidebar (computed over active accounts
 * only). The UI lists archived accounts behind a dedicated "inactive" filter.
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
  private static final String SQL_TYPE_VARCHAR = "varchar";

  private static final String ACCOUNTS_SQL =
      "SELECT fa.fin_financial_account_id, fa.name, fa.type, fa.currentbalance, "
          + "       fa.c_currency_id, cur.iso_code, fa.iban, fa.isdefault, fa.isactive, "
          + "       fa.em_psd2_masked_pan, fa.em_psd2_connection_status, "
          + "       COALESCE(fa.em_etgo_date_tolerance, 3), "
          + "       COALESCE(fa.em_etgo_amount_tolerance, 0), "
          + "       fa.em_psd2_salt_edge_account_id "
          + "  FROM fin_financial_account fa "
          + "  JOIN c_currency cur ON cur.c_currency_id = fa.c_currency_id "
          + " WHERE fa.ad_client_id = ? "
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

  /**
   * Accounts with at least one active transaction (ETP-4530). Used by the frontend to lock the
   * Currency field on the edit form once real movement history exists — a stricter, different
   * condition than {@code bankConnected} (bank-linkage only, no bearing on transaction history).
   */
  private static final String TRANSACTIONS_BY_ACCOUNT_SQL =
      "SELECT DISTINCT ft.fin_financial_account_id "
          + "  FROM fin_finacc_transaction ft "
          + " WHERE ft.isactive = 'Y' "
          + "   AND ft.ad_client_id = ? "
          + "   AND ft.ad_org_id = ANY (?)";

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
    Set<String> accountsWithTransactions = loadAccountsWithTransactions(clientId, orgs);

    JSONObject data = new JSONObject();
    data.put("accounts", buildAccountsArray(accounts, pendingByAccount, accountsWithTransactions));
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
      ps.setArray(2, conn.createArrayOf(SQL_TYPE_VARCHAR, orgs.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          AccountRow row = new AccountRow(
              rs.getString(1),
              StringUtils.trimToEmpty(rs.getString(2)),
              StringUtils.trimToEmpty(rs.getString(3)),
              nullSafeBigDecimal(rs.getBigDecimal(4)),
              new Currency(rs.getString(5), StringUtils.trimToEmpty(rs.getString(6))),
              StringUtils.trimToEmpty(rs.getString(7)),
              "Y".equals(rs.getString(8)));
          row.active = "Y".equals(rs.getString(9));
          row.maskedPan = StringUtils.trimToEmpty(rs.getString(10));
          row.bankConnected = "CO".equals(rs.getString(11));
          row.dateTolerance = rs.getInt(12);
          BigDecimal amtTol = rs.getBigDecimal(13);
          row.amountTolerance = amtTol != null ? amtTol : BigDecimal.ZERO;
          row.bankReconnectable = !row.bankConnected
              && StringUtils.isNotBlank(rs.getString(14));
          rows.add(row);
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
      ps.setArray(2, conn.createArrayOf(SQL_TYPE_VARCHAR, orgs.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString(1), rs.getInt(2));
        }
      }
    }
    return result;
  }

  /** Ids of accounts (within scope) that have at least one active transaction (ETP-4530). */
  Set<String> loadAccountsWithTransactions(String clientId, Set<String> orgs) throws Exception {
    Set<String> result = new java.util.LinkedHashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(TRANSACTIONS_BY_ACCOUNT_SQL)) {
      ps.setString(1, clientId);
      ps.setArray(2, conn.createArrayOf(SQL_TYPE_VARCHAR, orgs.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(rs.getString(1));
        }
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Response builders (package-private to allow unit tests to drive directly)
  // ---------------------------------------------------------------------------

  JSONArray buildAccountsArray(List<AccountRow> accounts, Map<String, Integer> pendingByAccount,
      Set<String> accountsWithTransactions) throws JSONException {
    JSONArray arr = new JSONArray();
    for (AccountRow account : accounts) {
      JSONObject json = new JSONObject();
      json.put("id", account.id);
      json.put("name", account.name);
      json.put("type", account.type);
      json.put("currentBalance", account.currentBalance);
      json.put("currencyId", account.currency.id);
      json.put("currencyIso", account.currency.iso);
      json.put("iban", account.iban);
      json.put("maskedPan", account.maskedPan);
      json.put("bankConnected", account.bankConnected);
      json.put("bankReconnectable", account.bankReconnectable);
      json.put("bankConnectionPending", account.bankConnectionPending);
      json.put("isDefault", account.isDefault);
      json.put("active", account.active);
      json.put("pendingCount", pendingByAccount.getOrDefault(account.id, 0));
      json.put("dateTolerance", account.dateTolerance);
      json.put("amountTolerance", account.amountTolerance);
      json.put("hasTransactions", accountsWithTransactions.contains(account.id));
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
      // Sidebar widgets aggregate only active accounts; archived ones are
      // listed apart (via the "inactive" filter) and must not skew the totals.
      if (!account.active) {
        continue;
      }
      total = total.add(account.currentBalance);
      byCurrency.merge(account.currency.iso, account.currentBalance, BigDecimal::add);
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
    final Currency currency;
    final String iban;
    final boolean isDefault;
    /** Whether the account is active; archived accounts have {@code false}. Set by the loader. */
    boolean active = true;
    /** PSD2 masked card number (column {@code EM_PSD2_Masked_Pan}); blank for non-card accounts. Set by the loader. */
    String maskedPan = "";
    /** Whether the account has an active bank connection ({@code EM_PSD2_Connection_Status = 'CO'}). Set by the loader. */
    boolean bankConnected = false;
    /**
     * Whether the account was soft-disconnected and can be revived through the reconnect flow:
     * not currently connected, yet still holding its Salt Edge link
     * ({@code EM_PSD2_Salt_Edge_Account_ID} is set). A permanent deletion clears that column, so
     * this stays {@code false} there. Kept as its own flag rather than turning
     * {@code bankConnected} into a tri-state, because the SPA checks
     * {@code bankConnected === true} in several places. Set by the loader.
     */
    boolean bankReconnectable = false;
    /** Whether a bank sync is pending. Not tracked server-side yet; reserved for the list sync badge. */
    boolean bankConnectionPending = false;
    /** Days of margin allowed between bank line and transaction dates. Default 3. */
    int dateTolerance = 3;
    /** Maximum % difference allowed when matching amounts. Default 0 (exact match). */
    BigDecimal amountTolerance = BigDecimal.ZERO;

    AccountRow(String id, String name, String type, BigDecimal currentBalance,
        Currency currency, String iban, boolean isDefault) {
      this.id = id;
      this.name = name;
      this.type = type;
      this.currentBalance = currentBalance;
      this.currency = currency;
      this.iban = iban;
      this.isDefault = isDefault;
    }
  }

  /**
   * Currency descriptor co-located with {@link AccountRow}. Groups the id and
   * ISO code into a single value to keep the row constructor below Sonar's
   * 7-parameter ceiling and to make the dependency explicit in the SQL layer.
   */
  static class Currency {
    final String id;
    final String iso;

    Currency(String id, String iso) {
      this.id = id;
      this.iso = iso;
    }
  }
}
