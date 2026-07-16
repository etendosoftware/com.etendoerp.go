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

import java.util.List;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * NeoHandler for the {@code accountingConfiguration} entity of the {@code financial-account} spec
 * (ETP-4530 — Tab Contabilidad of the account edit form).
 *
 * <p>{@code FIN_Financial_Account_Acct} (AD tab "Accounting Configuration") is a per-ledger row:
 * one financial account can have one configuration row per active {@link AcctSchema}. This
 * handler resolves the account's <b>own organization's</b> general ledger (mirroring
 * {@code GeneralLedgerConfigurationHandler}) and always works with the single row for that
 * (account, ledger) pair — finding it on GET, finding-or-creating it on save — so the frontend
 * never has to know whether the row already exists.
 *
 * <p>Only the two fields the ticket requires are exposed for write: {@code fINAssetAcct}
 * ("Cuenta bancaria", required) and {@code fINTransitoryAcct} ("Cuenta transitoria", optional).
 * The remaining accounting-configuration columns (deposit/withdrawal/credit/debit/etc.) stay
 * {@code discarded} in {@code decisions.json} — out of scope for this ticket.
 *
 * <p>Registered via {@code entities.accountingConfiguration.javaQualifier =
 * "financialAccountAccountingHandler"} in {@code artifacts/financial-account/decisions.json}.
 * Both GET and POST/PUT/PATCH are fully intercepted (the generic CRUD never runs for this
 * entity), so the request/response shape below is owned entirely by this handler:
 *
 * <pre>
 * GET  /sws/neo/financial-account/accountingConfiguration?financialAccountId={id}
 *   → { id, financialAccountId, fINAssetAcct, fINAssetAcct$_identifier,
 *       fINTransitoryAcct, fINTransitoryAcct$_identifier, ledgerConfigured,
 *       catalogs: { accounts: [{ id, code, name }, ...] } }
 *
 * POST/PUT /sws/neo/financial-account/accountingConfiguration
 *   body: { financialAccountId, fINAssetAcct, fINTransitoryAcct? }
 *   → same shape as GET, reflecting the persisted row
 * </pre>
 */
@Named("financialAccountAccountingHandler")
public class FinancialAccountAccountingHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountAccountingHandler.class);

  private static final String SPEC = "financial-account";
  private static final String ENTITY = "accountingConfiguration";
  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  private static final String PARAM_FINANCIAL_ACCOUNT_ID = "financialAccountId";
  private static final String FIELD_ID = "id";
  private static final String FIELD_FINANCIAL_ACCOUNT_ID = "financialAccountId";
  private static final String FIELD_ASSET_ACCT = "fINAssetAcct";
  private static final String FIELD_TRANSITORY_ACCT = "fINTransitoryAcct";
  private static final String FIELD_LEDGER_CONFIGURED = "ledgerConfigured";
  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName()) || !ENTITY.equals(context.getEntityName())) {
      return null;
    }
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    try {
      OBContext.setAdminMode(true);
      if (METHOD_GET.equals(method)) {
        return handleGet(context);
      }
      if (METHOD_POST.equals(method) || METHOD_PUT.equals(method) || METHOD_PATCH.equals(method)) {
        return handleSave(context);
      }
      return null;
    } catch (OBException e) {
      log.warn("financial-account accountingConfiguration business error: {}", e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("financial-account accountingConfiguration error", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse handleGet(NeoContext context) throws JSONException {
    String accountId = resolveAccountId(context.getQueryParams(), null);
    FIN_FinancialAccount account = loadAccount(accountId);
    AcctSchema ledger = resolveOwnLedger(account);
    if (ledger == null) {
      return NeoResponse.ok(wrapSingle(buildUnconfiguredRow(account)));
    }
    FIN_FinancialAccountAccounting row = findRow(account, ledger);
    return NeoResponse.ok(wrapSingle(buildRow(account, ledger, row)));
  }

  private NeoResponse handleSave(NeoContext context) throws JSONException {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing request body");
    }
    String accountId = resolveAccountId(context.getQueryParams(), body);
    FIN_FinancialAccount account = loadAccount(accountId);
    AcctSchema ledger = resolveOwnLedger(account);
    if (ledger == null) {
      throw new OBException("The account's organization has no general ledger configured");
    }

    String assetAcctId = StringUtils.trimToNull(body.optString(FIELD_ASSET_ACCT, null));
    if (assetAcctId == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Cuenta bancaria is required");
    }
    AccountingCombination assetAcct = resolveCombination(assetAcctId, ledger);
    AccountingCombination transitoryAcct = null;
    if (body.has(FIELD_TRANSITORY_ACCT) && !body.isNull(FIELD_TRANSITORY_ACCT)) {
      String transitoryId = StringUtils.trimToNull(body.optString(FIELD_TRANSITORY_ACCT, null));
      if (transitoryId != null) {
        transitoryAcct = resolveCombination(transitoryId, ledger);
      }
    }

    FIN_FinancialAccountAccounting row = findOrCreateRow(account, ledger);
    row.setFINAssetAcct(assetAcct);
    row.setFINTransitoryAcct(transitoryAcct);
    // Classic's bank-statement accounting engine only reads these two accounts when this flag is
    // enabled — auto-enable it here so saving from this tab has an observable effect (ETP-4530
    // does not otherwise expose this flag; see delivery notes).
    row.setEnablebankstatement(true);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();

    return NeoResponse.ok(wrapSingle(buildRow(account, ledger, row)));
  }

  // ---------------------------------------------------------------------------
  // Row lookup / creation
  // ---------------------------------------------------------------------------

  private FIN_FinancialAccountAccounting findRow(FIN_FinancialAccount account, AcctSchema ledger) {
    OBCriteria<FIN_FinancialAccountAccounting> criteria =
        OBDal.getInstance().createCriteria(FIN_FinancialAccountAccounting.class);
    criteria.add(Restrictions.eq(FIN_FinancialAccountAccounting.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FIN_FinancialAccountAccounting.PROPERTY_ACCOUNTINGSCHEMA, ledger));
    criteria.addOrder(Order.desc(FIN_FinancialAccountAccounting.PROPERTY_UPDATED));
    criteria.setMaxResults(1);
    return criteria.uniqueResult();
  }

  private FIN_FinancialAccountAccounting findOrCreateRow(FIN_FinancialAccount account, AcctSchema ledger) {
    FIN_FinancialAccountAccounting row = findRow(account, ledger);
    if (row != null) {
      return row;
    }
    row = OBProvider.getInstance().get(FIN_FinancialAccountAccounting.class);
    row.setNewOBObject(true);
    row.setClient(account.getClient());
    row.setOrganization(account.getOrganization());
    row.setAccount(account);
    row.setAccountingSchema(ledger);
    return row;
  }

  // ---------------------------------------------------------------------------
  // Resolution helpers
  // ---------------------------------------------------------------------------

  private String resolveAccountId(java.util.Map<String, String> queryParams, JSONObject body) {
    String id = queryParams != null ? StringUtils.trimToNull(queryParams.get(PARAM_FINANCIAL_ACCOUNT_ID)) : null;
    if (id == null && body != null) {
      id = StringUtils.trimToNull(body.optString(FIELD_FINANCIAL_ACCOUNT_ID, null));
    }
    if (id == null) {
      throw new OBException("financialAccountId is required");
    }
    return id;
  }

  FIN_FinancialAccount loadAccount(String accountId) {
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
    if (account == null) {
      throw new OBException("Financial account not found: " + accountId);
    }
    return account;
  }

  /**
   * Resolves the general ledger of the account's <b>own</b> organization — not the caller's
   * session organization — since the accounting configuration belongs to the account's org tree
   * regardless of which org the editing user is currently working in.
   *
   * @return the ledger, or {@code null} when the org has none configured (soft — GET degrades to
   *     an "unconfigured" row instead of failing the whole edit modal)
   */
  private AcctSchema resolveOwnLedger(FIN_FinancialAccount account) {
    Organization org = account.getOrganization();
    return org != null ? org.getGeneralLedger() : null;
  }

  private AccountingCombination resolveCombination(String id, AcctSchema ledger) {
    AccountingCombination combo = OBDal.getInstance().get(AccountingCombination.class, id);
    if (combo == null) {
      throw new OBException("Accounting combination not found: " + id);
    }
    if (combo.getAccountingSchema() != null && ledger != null
        && !combo.getAccountingSchema().getId().equals(ledger.getId())) {
      throw new OBException("Accounting combination does not belong to the account's ledger: " + id);
    }
    return combo;
  }

  // ---------------------------------------------------------------------------
  // Response building
  // ---------------------------------------------------------------------------

  private JSONObject wrapSingle(JSONObject row) throws JSONException {
    JSONArray data = new JSONArray();
    data.put(row);
    JSONObject response = new JSONObject();
    response.put("data", data);
    response.put("count", 1);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", response);
    return wrapper;
  }

  private JSONObject buildUnconfiguredRow(FIN_FinancialAccount account) throws JSONException {
    JSONObject out = new JSONObject();
    out.put(FIELD_ID, JSONObject.NULL);
    out.put(FIELD_FINANCIAL_ACCOUNT_ID, account.getId());
    out.put(FIELD_ASSET_ACCT, JSONObject.NULL);
    out.put(FIELD_TRANSITORY_ACCT, JSONObject.NULL);
    out.put(FIELD_LEDGER_CONFIGURED, false);
    out.put("catalogs", buildCatalogs(null));
    return out;
  }

  private JSONObject buildRow(FIN_FinancialAccount account, AcctSchema ledger,
      FIN_FinancialAccountAccounting row) throws JSONException {
    JSONObject out = new JSONObject();
    out.put(FIELD_ID, row != null ? row.getId() : JSONObject.NULL);
    out.put(FIELD_FINANCIAL_ACCOUNT_ID, account.getId());
    putCombination(out, FIELD_ASSET_ACCT, row != null ? row.getFINAssetAcct() : null);
    putCombination(out, FIELD_TRANSITORY_ACCT, row != null ? row.getFINTransitoryAcct() : null);
    out.put(FIELD_LEDGER_CONFIGURED, true);
    out.put("catalogs", buildCatalogs(ledger));
    return out;
  }

  private void putCombination(JSONObject out, String key, AccountingCombination combo) throws JSONException {
    if (combo == null) {
      out.put(key, JSONObject.NULL);
      out.put(key + IDENTIFIER_SUFFIX, JSONObject.NULL);
      return;
    }
    out.put(key, combo.getId());
    out.put(key + IDENTIFIER_SUFFIX, resolveCombinationLabel(combo));
  }

  private JSONObject buildCatalogs(AcctSchema ledger) throws JSONException {
    JSONObject out = new JSONObject();
    out.put("accounts", buildAccountOptions(ledger));
    return out;
  }

  /**
   * Active accounting combinations for the ledger, as a flat {id, code, name} list — the
   * frontend filters this client-side (same pattern already used by
   * {@code GeneralLedgerConfigurationHandler.buildAccountOptions}).
   */
  private JSONArray buildAccountOptions(AcctSchema ledger) throws JSONException {
    JSONArray out = new JSONArray();
    if (ledger == null) {
      return out;
    }
    OBCriteria<AccountingCombination> criteria = OBDal.getInstance().createCriteria(AccountingCombination.class);
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNTINGSCHEMA, ledger));
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(AccountingCombination.PROPERTY_COMBINATION));
    @SuppressWarnings("unchecked")
    List<AccountingCombination> rows = criteria.list();

    for (AccountingCombination combo : rows) {
      JSONObject item = new JSONObject();
      item.put(FIELD_ID, combo.getId());
      item.put("code", resolveCombinationCode(combo));
      item.put("name", resolveCombinationLabel(combo));
      out.put(item);
    }
    return out;
  }

  private String resolveCombinationCode(AccountingCombination combo) {
    ElementValue account = combo.getAccount();
    if (account != null && StringUtils.isNotBlank(account.getSearchKey())) {
      return account.getSearchKey();
    }
    return combo.getCombination();
  }

  private String resolveCombinationLabel(AccountingCombination combo) {
    String code = resolveCombinationCode(combo);
    ElementValue account = combo.getAccount();
    String name = account != null && StringUtils.isNotBlank(account.getName())
        ? account.getName()
        : combo.getDescription();
    if (StringUtils.isBlank(name)) {
      return code;
    }
    return StringUtils.isNotBlank(code) ? code + " — " + name : name;
  }
}
