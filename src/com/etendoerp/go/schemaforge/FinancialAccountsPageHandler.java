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
          + "       COALESCE(fa.em_aprm_glitem_diff, ''), "
          + "       COALESCE(gli.name, ''), "
          + "       fa.em_psd2_salt_edge_account_id, prov.logo_url, "
          + "       fa.writeofflimit, "
          // Appended at the END on purpose (ETP-4896): loadAccounts() below reads every column by
          // POSITION, and so does FinancialAccountsPageHandlerTest's ResultSet stubbing — inserting
          // these in the middle would silently shift every existing column index.
          + "       fa.c_country_id, ctry.countrycode, ctry.name, "
          // Stored computed column (EPL-1807 engine). Replaces the per-request
          // PENDING_BY_ACCOUNT_SQL aggregate this class used to run: the value is now a real,
          // sortable column the engine recomputes whenever a statement, statement line,
          // transaction or the account type changes. COALESCE because the column is nullable —
          // it stays NULL on a row the engine has not populated yet (a fresh account before its
          // first dependency write), and the list reads that as zero pending.
          + "       COALESCE(fa.em_etgo_pending_count, 0) "
          + "  FROM fin_financial_account fa "
          + "  JOIN c_currency cur ON cur.c_currency_id = fa.c_currency_id "
          + "  LEFT JOIN c_glitem gli ON gli.c_glitem_id = fa.em_aprm_glitem_diff "
          // LEFT JOIN: most accounts have no bank provider at all (cash, or never connected).
          // Reads the logo straight from the already-synced provider catalog — no live Salt Edge
          // call per row, unlike the connect-flow bank picker / account selector.
          + "  LEFT JOIN psd2_provider prov ON prov.psd2_provider_id = fa.em_psd2_provider_id "
          // LEFT JOIN: c_country_id is nullable and Cash accounts never carry one.
          + "  LEFT JOIN c_country ctry ON ctry.c_country_id = fa.c_country_id "
          + " WHERE fa.ad_client_id = ? "
          + "   AND fa.ad_org_id = ANY (?) "
          + " ORDER BY fa.isdefault DESC, fa.name ASC";


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

  /**
   * One reason code per row that would block a hard delete of the account (ETP-4871), across
   * every table {@code FinancialAccountDeleteSupport.findDeleteBlockers} checks — a single {@code UNION
   * ALL} for the whole page, same performance rule as the other loaders in this class (never N
   * calls to {@code findDeleteBlockers} per row). Deliberately does NOT filter on {@code isactive}
   * for any branch except {@code TRANSACTIONS} (mirroring {@code hasTransactions}'s own filter):
   * a {@code RESTRICT} FK blocks a hard delete regardless of whether the referencing row is itself
   * soft-deleted. {@code BPARTNER_DEFAULT} appears twice because a business partner can default to
   * this account either as its regular or its PO financial account (two separate FK columns on the
   * same table). Bind order: (clientId, orgs) repeated once per branch, in source order.
   */
  private static final String DELETE_BLOCKERS_BY_ACCOUNT_SQL =
      "SELECT ft.fin_financial_account_id, 'TRANSACTIONS' AS reason "
          + "  FROM fin_finacc_transaction ft "
          + " WHERE ft.isactive = 'Y' AND ft.ad_client_id = ? AND ft.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT r.fin_financial_account_id, 'RECONCILIATIONS' "
          + "  FROM fin_reconciliation r "
          + " WHERE r.ad_client_id = ? AND r.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT bs.fin_financial_account_id, 'BANK_STATEMENTS' "
          + "  FROM fin_bankstatement bs "
          + " WHERE bs.ad_client_id = ? AND bs.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT p.fin_financial_account_id, 'PAYMENTS' "
          + "  FROM fin_payment p "
          + " WHERE p.ad_client_id = ? AND p.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT pp.fin_financial_account_id, 'PAYMENT_PROPOSALS' "
          + "  FROM fin_payment_proposal pp "
          + " WHERE pp.ad_client_id = ? AND pp.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT gl.fin_financial_account_id, 'JOURNAL_LINES' "
          + "  FROM gl_journalline gl "
          + " WHERE gl.ad_client_id = ? AND gl.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT bfe.fin_financial_account_id, 'BANK_FILE_EXCEPTIONS' "
          + "  FROM fin_bankfile_exception bfe "
          + " WHERE bfe.ad_client_id = ? AND bfe.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT bp.fin_financial_account_id, 'BPARTNER_DEFAULT' "
          + "  FROM c_bpartner bp "
          + " WHERE bp.fin_financial_account_id IS NOT NULL "
          + "   AND bp.ad_client_id = ? AND bp.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT bp.po_financial_account_id, 'BPARTNER_DEFAULT' "
          + "  FROM c_bpartner bp "
          + " WHERE bp.po_financial_account_id IS NOT NULL "
          + "   AND bp.ad_client_id = ? AND bp.ad_org_id = ANY (?) "
          + " UNION ALL "
          + "SELECT c.fin_financial_account_id, 'BANK_CONNECTION' "
          + "  FROM psd2_finacc_connection c "
          + " WHERE c.ad_client_id = ? AND c.ad_org_id = ANY (?)";

  /** Number of {@code UNION ALL} branches in {@link #DELETE_BLOCKERS_BY_ACCOUNT_SQL}, each bound
   *  with (clientId, orgs) — kept in sync manually with the SQL above. */
  private static final int DELETE_BLOCKERS_SQL_BRANCH_COUNT = 10;

  /** Maps each {@code DELETE_BLOCKERS_BY_ACCOUNT_SQL} reason code to the exact wording
   *  {@code FinancialAccountDeleteSupport.findDeleteBlockers} uses for the same check, so the
   *  DELETE 409 message and this list-view field never drift apart. */
  private static final Map<String, String> DELETE_BLOCKER_REASON_BY_CODE = new LinkedHashMap<>();

  static {
    DELETE_BLOCKER_REASON_BY_CODE.put("TRANSACTIONS", FinancialAccountDeleteSupport.REASON_TRANSACTIONS);
    DELETE_BLOCKER_REASON_BY_CODE.put("RECONCILIATIONS", FinancialAccountDeleteSupport.REASON_RECONCILIATIONS);
    DELETE_BLOCKER_REASON_BY_CODE.put("BANK_STATEMENTS", FinancialAccountDeleteSupport.REASON_BANK_STATEMENTS);
    DELETE_BLOCKER_REASON_BY_CODE.put("PAYMENTS", FinancialAccountDeleteSupport.REASON_PAYMENTS);
    DELETE_BLOCKER_REASON_BY_CODE.put("PAYMENT_PROPOSALS",
        FinancialAccountDeleteSupport.REASON_PAYMENT_PROPOSALS);
    DELETE_BLOCKER_REASON_BY_CODE.put("JOURNAL_LINES", FinancialAccountDeleteSupport.REASON_JOURNAL_LINES);
    DELETE_BLOCKER_REASON_BY_CODE.put("BANK_FILE_EXCEPTIONS",
        FinancialAccountDeleteSupport.REASON_BANK_FILE_EXCEPTIONS);
    DELETE_BLOCKER_REASON_BY_CODE.put("BPARTNER_DEFAULT", FinancialAccountDeleteSupport.REASON_BPARTNER_DEFAULT);
    DELETE_BLOCKER_REASON_BY_CODE.put("BANK_CONNECTION", FinancialAccountDeleteSupport.REASON_BANK_CONNECTION);
  }

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
    Set<String> accountsWithTransactions = loadAccountsWithTransactions(clientId, orgs);

    JSONObject data = new JSONObject();
    data.put("accounts", buildAccountsArray(accounts, accountsWithTransactions));
    data.put("summary", buildSummary(accounts));
    // Sibling of accounts/summary, not a per-account field (ETP-4896). It is the same catalog that
    // the W-spec defaults response carries (see the injectAccountDefaults method over in
    // FinancialAccountHandler), and this R spec is what the accounts list and the edit modal
    // actually load today.
    data.put("countryIbanRules", FinancialAccountCountrySupport.buildIbanRules());

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
          row.glItemDifferenceId = StringUtils.trimToEmpty(rs.getString(14));
          row.glItemDifferenceName = StringUtils.trimToEmpty(rs.getString(15));
          row.bankReconnectable = !row.bankConnected
              && StringUtils.isNotBlank(rs.getString(16));
          row.providerLogoUrl = StringUtils.trimToEmpty(rs.getString(17));
          // Left NULL on purpose when unset: null means "no limit", which is not the same as a
          // configured 0. See the serialiser and ReconciliationHandler.assertWithinWriteoffLimit.
          row.writeoffLimit = rs.getBigDecimal(18);
          // Null-safe by construction (ETP-4896): an account with no C_Country_ID yields a null
          // column 19, so row.country stays null and the JSON serialiser emits "" — never "null".
          String countryId = rs.getString(19);
          if (countryId != null) {
            row.country = new CountryRef(countryId, StringUtils.trimToEmpty(rs.getString(20)),
                StringUtils.trimToEmpty(rs.getString(21)));
          }
          // COALESCEd in SQL, so getInt never sees a NULL and 0 is a real zero, not a
          // "was null" artefact.
          row.pendingCount = rs.getInt(22);
          rows.add(row);
        }
      }
    }
    return rows;
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

  /**
   * Reasons (already translated to their human-readable wording) a hard delete would fail on,
   * per account id, for the whole page in one query (ETP-4871). Used to drive the accounts list's
   * {@code deletable} / {@code deleteBlockedReason} fields — see
   * {@code FinancialAccountHandler#injectDerivedFields}.
   */
  Map<String, List<String>> loadDeleteBlockersByAccount(String clientId, Set<String> orgs) throws Exception {
    Map<String, java.util.LinkedHashSet<String>> reasonsByAccount = new LinkedHashMap<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(DELETE_BLOCKERS_BY_ACCOUNT_SQL)) {
      java.sql.Array orgArray = conn.createArrayOf(SQL_TYPE_VARCHAR, orgs.toArray(new String[0]));
      int paramIndex = 1;
      for (int branch = 0; branch < DELETE_BLOCKERS_SQL_BRANCH_COUNT; branch++) {
        ps.setString(paramIndex++, clientId);
        ps.setArray(paramIndex++, orgArray);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String accountId = rs.getString(1);
          String reason = DELETE_BLOCKER_REASON_BY_CODE.get(rs.getString(2));
          if (accountId == null || reason == null) {
            continue;
          }
          reasonsByAccount.computeIfAbsent(accountId, k -> new java.util.LinkedHashSet<>()).add(reason);
        }
      }
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map.Entry<String, java.util.LinkedHashSet<String>> entry : reasonsByAccount.entrySet()) {
      result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Response builders (package-private to allow unit tests to drive directly)
  // ---------------------------------------------------------------------------

  JSONArray buildAccountsArray(List<AccountRow> accounts,
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
      json.put("countryId", account.country != null ? account.country.id : "");
      json.put("countryIso", account.country != null ? account.country.iso : "");
      json.put("countryName", account.country != null ? account.country.name : "");
      json.put("iban", account.iban);
      json.put("maskedPan", account.maskedPan);
      json.put("bankConnected", account.bankConnected);
      json.put("bankReconnectable", account.bankReconnectable);
      json.put("providerLogoUrl", account.providerLogoUrl);
      json.put("bankConnectionPending", account.bankConnectionPending);
      json.put("isDefault", account.isDefault);
      json.put("active", account.active);
      // Key stays `pendingCount` even though the column is now EM_ETGO_Pending_Count: this R
      // spec hand-builds its JSON, and the detail view (useFinancialAccount) plus the funds
      // transfer picker (useFinancialAccounts) read this flat name. Only the W spec's generic
      // CRUD, which derives its keys from the AD column, exposes it as `eTGOPendingCount`.
      json.put("pendingCount", account.pendingCount);
      json.put("dateTolerance", account.dateTolerance);
      json.put("amountTolerance", account.amountTolerance);
      // JSONObject.put(String, Object) with null REMOVES the key, which is exactly what we want:
      // the UI distinguishes "no limit configured" (absent) from a configured value.
      json.put("writeoffLimit", account.writeoffLimit);
      json.put("glItemDifferenceId", account.glItemDifferenceId);
      json.put("glItemDifferenceName", account.glItemDifferenceName);
      json.put("hasTransactions", accountsWithTransactions.contains(account.id));
      arr.put(json);
    }
    return arr;
  }

  JSONObject buildSummary(List<AccountRow> accounts) throws JSONException {
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
      if (account.pendingCount > 0) {
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
    /**
     * The connected provider's logo image URL ({@code PSD2_Provider.Logo_Url}), or blank when the
     * account has no bank provider or the provider has none on record yet. Read from the provider
     * catalog via a join, not from a live Salt Edge call — that is the whole point of persisting
     * it instead of fetching it per row like the connect-flow bank picker does. Set by the loader.
     */
    String providerLogoUrl = "";
    /** Whether a bank sync is pending. Not tracked server-side yet; reserved for the list sync badge. */
    boolean bankConnectionPending = false;
    /**
     * Items still awaiting reconciliation, from the {@code EM_ETGO_Pending_Count} stored computed
     * column. Unmatched bank-statement lines for bank/card accounts, plus unreconciled processed
     * transactions for cash accounts (ETP-4795) — the engine's function sums both branches. Set by
     * the loader; 0 both when nothing is pending and when the engine has not populated the row yet.
     */
    int pendingCount = 0;
    /** Days of margin allowed between bank line and transaction dates. Default 3. */
    int dateTolerance = 3;
    /** Maximum % difference allowed when matching amounts. Default 0 (exact match). */
    BigDecimal amountTolerance = BigDecimal.ZERO;
    /**
     * Largest difference the user may write off when settling an invoice (ETP-4797), from
     * {@code FIN_Financial_Account.Writeofflimit}. {@code null} when unset, which this feature
     * reads as "no limit" — see {@code ReconciliationHandler.assertWithinWriteoffLimit} for why
     * that diverges from Classic.
     */
    BigDecimal writeoffLimit = null;
    /** GL item the cash-close/reconciliation difference is posted to (ETP-4795). Blank if unset. */
    String glItemDifferenceId = "";
    /** Display name of {@link #glItemDifferenceId}, resolved server-side. Blank if unset. */
    String glItemDifferenceName = "";
    /** Country of the account (ETP-4896) — set on Bank accounts that carry an IBAN, {@code null}
     *  otherwise (Cash accounts never have one; a Bank account may not yet). Set by the loader,
     *  not the constructor: {@link Currency}'s javadoc explains why the constructor stays capped
     *  at 7 parameters, and every existing fixture already calls it with exactly that many. */
    CountryRef country = null;

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

  /**
   * Country descriptor co-located with {@link AccountRow} (ETP-4896), mirroring {@link Currency}.
   * {@code name} is included because this R spec has no {@code $_identifier} machinery (only the
   * W spec's {@code NeoFieldFilter} produces one), so the edit modal needs a label without a
   * second round-trip.
   */
  static class CountryRef {
    final String id;
    final String iso;
    final String name;

    CountryRef(String id, String iso, String name) {
      this.id = id;
      this.iso = iso;
      this.name = name;
    }
  }
}
