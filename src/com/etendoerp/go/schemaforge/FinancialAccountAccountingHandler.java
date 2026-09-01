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
 * (ETP-4530 — Tab Contabilidad of the account edit form; extended by ETP-4872 to the full,
 * account-type-dependent field set).
 *
 * <p>{@code FIN_Financial_Account_Acct} (AD tab "Accounting Configuration") is a per-ledger row:
 * one financial account can have one configuration row per active {@link AcctSchema}. This
 * handler resolves the account's <b>own organization's</b> general ledger (mirroring
 * {@code GeneralLedgerConfigurationHandler}) and always works with the single row for that
 * (account, ledger) pair — finding it on GET, finding-or-creating it on save — so the frontend
 * never has to know whether the row already exists.
 *
 * <p>Nine fields are exposed for read/write, covering the Banco/Caja/Tarjeta account types
 * combined: {@code fINBankrevaluationgainAcct}, {@code fINBankrevaluationlossAcct},
 * {@code fINBankfeeAcct} (Banco only), and {@code inTransitPaymentAccountIN},
 * {@code depositAccount}, {@code clearedPaymentAccount}, {@code fINOutIntransitAcct},
 * {@code withdrawalAccount}, {@code clearedPaymentAccountOUT} (all three types). None of the
 * nine is required — whichever subset the active account type's form renders is whatever the
 * frontend sends; this handler has no notion of account type and always reads/writes exactly
 * what the request body contains (PATCH-like semantics, see {@link #applyCombination}).
 * {@code fINAssetAcct} / {@code fINTransitoryAcct} (the original ETP-4530 pair) are retired and
 * no longer read or written here. The remaining accounting-configuration columns
 * (receive/make payment, credit/debit, enablebankstatement) stay {@code discarded} in
 * {@code decisions.json} — out of scope.
 *
 * <p>Registered via {@code entities.accountingConfiguration.javaQualifier =
 * "financialAccountAccountingHandler"} in {@code artifacts/financial-account/decisions.json}.
 * Both GET and POST/PUT/PATCH are fully intercepted (the generic CRUD never runs for this
 * entity), so the request/response shape below is owned entirely by this handler:
 *
 * <pre>
 * GET  /sws/neo/financial-account/accountingConfiguration?financialAccountId={id}
 *   → { id, financialAccountId,
 *       fINBankrevaluationgainAcct, fINBankrevaluationgainAcct$_identifier,
 *       fINBankrevaluationlossAcct, fINBankrevaluationlossAcct$_identifier,
 *       fINBankfeeAcct, fINBankfeeAcct$_identifier,
 *       inTransitPaymentAccountIN, inTransitPaymentAccountIN$_identifier,
 *       depositAccount, depositAccount$_identifier,
 *       clearedPaymentAccount, clearedPaymentAccount$_identifier,
 *       fINOutIntransitAcct, fINOutIntransitAcct$_identifier,
 *       withdrawalAccount, withdrawalAccount$_identifier,
 *       clearedPaymentAccountOUT, clearedPaymentAccountOUT$_identifier,
 *       ledgerConfigured, catalogs: { accounts: [{ id, code, name }, ...] } }
 *
 * POST/PUT /sws/neo/financial-account/accountingConfiguration
 *   body: { financialAccountId, <any subset of the 9 fields above> }
 *   → same shape as GET, reflecting the persisted row. A field key omitted from the body
 *     leaves the stored value untouched; a field present with a null/blank value clears it.
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
  private static final String FIELD_BANK_REVAL_GAIN_ACCT = "fINBankrevaluationgainAcct";
  private static final String FIELD_BANK_REVAL_LOSS_ACCT = "fINBankrevaluationlossAcct";
  private static final String FIELD_BANK_FEE_ACCT = "fINBankfeeAcct";
  private static final String FIELD_IN_TRANSIT_IN_ACCT = "inTransitPaymentAccountIN";
  private static final String FIELD_DEPOSIT_ACCT = "depositAccount";
  private static final String FIELD_CLEARED_PAYMENT_ACCT_IN = "clearedPaymentAccount";
  private static final String FIELD_IN_TRANSIT_OUT_ACCT = "fINOutIntransitAcct";
  private static final String FIELD_WITHDRAWAL_ACCT = "withdrawalAccount";
  private static final String FIELD_CLEARED_PAYMENT_ACCT_OUT = "clearedPaymentAccountOUT";
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

    FIN_FinancialAccountAccounting row = findOrCreateRow(account, ledger);
    applyCombination(row::setFINBankrevaluationgainAcct, body, FIELD_BANK_REVAL_GAIN_ACCT, ledger);
    applyCombination(row::setFINBankrevaluationlossAcct, body, FIELD_BANK_REVAL_LOSS_ACCT, ledger);
    applyCombination(row::setFINBankfeeAcct, body, FIELD_BANK_FEE_ACCT, ledger);
    applyCombination(row::setInTransitPaymentAccountIN, body, FIELD_IN_TRANSIT_IN_ACCT, ledger);
    applyCombination(row::setDepositAccount, body, FIELD_DEPOSIT_ACCT, ledger);
    applyCombination(row::setClearedPaymentAccount, body, FIELD_CLEARED_PAYMENT_ACCT_IN, ledger);
    applyCombination(row::setFINOutIntransitAcct, body, FIELD_IN_TRANSIT_OUT_ACCT, ledger);
    applyCombination(row::setWithdrawalAccount, body, FIELD_WITHDRAWAL_ACCT, ledger);
    applyCombination(row::setClearedPaymentAccountOUT, body, FIELD_CLEARED_PAYMENT_ACCT_OUT, ledger);
    // WARNING (ETP-4530): this flips EnableBankStatement to Y on EVERY Contabilidad save, not just
    // the fields this tab visually presents — Classic's bank-statement accounting engine only
    // reads this row's accounting fields when this flag is Y, so without it the save would have
    // no observable effect in Classic. The flag itself is NOT exposed as an editable field here
    // (out of scope for this ticket), so a user who later opens the equivalent Classic window will
    // find it pre-checked without having touched it directly — see financial-account.md, "Not
    // implemented yet", for the full note.
    row.setEnablebankstatement(true);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();

    return NeoResponse.ok(wrapSingle(buildRow(account, ledger, row)));
  }

  /**
   * Resolves and sets one optional {@link AccountingCombination} field. A field key omitted
   * entirely from the request body leaves the stored value untouched (PATCH-like semantics, so
   * a per-account-type partial form never accidentally nulls out fields it doesn't render); a
   * field present with a null/blank value explicitly clears it. No field is required — see the
   * class Javadoc above.
   */
  private void applyCombination(java.util.function.Consumer<AccountingCombination> setter,
      JSONObject body, String field, AcctSchema ledger) {
    if (!body.has(field)) {
      return;
    }
    String id = StringUtils.trimToNull(body.optString(field, null));
    setter.accept(id != null ? resolveCombination(id, ledger) : null);
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
    return FIN_FinancialAccountAccounting.class.cast(criteria.uniqueResult());
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
    putCombination(out, FIELD_BANK_REVAL_GAIN_ACCT, null);
    putCombination(out, FIELD_BANK_REVAL_LOSS_ACCT, null);
    putCombination(out, FIELD_BANK_FEE_ACCT, null);
    putCombination(out, FIELD_IN_TRANSIT_IN_ACCT, null);
    putCombination(out, FIELD_DEPOSIT_ACCT, null);
    putCombination(out, FIELD_CLEARED_PAYMENT_ACCT_IN, null);
    putCombination(out, FIELD_IN_TRANSIT_OUT_ACCT, null);
    putCombination(out, FIELD_WITHDRAWAL_ACCT, null);
    putCombination(out, FIELD_CLEARED_PAYMENT_ACCT_OUT, null);
    out.put(FIELD_LEDGER_CONFIGURED, false);
    out.put("catalogs", buildCatalogs(null));
    return out;
  }

  private JSONObject buildRow(FIN_FinancialAccount account, AcctSchema ledger,
      FIN_FinancialAccountAccounting row) throws JSONException {
    JSONObject out = new JSONObject();
    out.put(FIELD_ID, row != null ? row.getId() : JSONObject.NULL);
    out.put(FIELD_FINANCIAL_ACCOUNT_ID, account.getId());
    putCombination(out, FIELD_BANK_REVAL_GAIN_ACCT, row != null ? row.getFINBankrevaluationgainAcct() : null);
    putCombination(out, FIELD_BANK_REVAL_LOSS_ACCT, row != null ? row.getFINBankrevaluationlossAcct() : null);
    putCombination(out, FIELD_BANK_FEE_ACCT, row != null ? row.getFINBankfeeAcct() : null);
    putCombination(out, FIELD_IN_TRANSIT_IN_ACCT, row != null ? row.getInTransitPaymentAccountIN() : null);
    putCombination(out, FIELD_DEPOSIT_ACCT, row != null ? row.getDepositAccount() : null);
    putCombination(out, FIELD_CLEARED_PAYMENT_ACCT_IN, row != null ? row.getClearedPaymentAccount() : null);
    putCombination(out, FIELD_IN_TRANSIT_OUT_ACCT, row != null ? row.getFINOutIntransitAcct() : null);
    putCombination(out, FIELD_WITHDRAWAL_ACCT, row != null ? row.getWithdrawalAccount() : null);
    putCombination(out, FIELD_CLEARED_PAYMENT_ACCT_OUT, row != null ? row.getClearedPaymentAccountOUT() : null);
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
