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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;

/**
 * NeoHandler that powers the financial-account window as a generic W (CRUD) spec
 * (ETP-4239, converting the former report-style spec from ETP-4096).
 *
 * <p>It is registered against the {@code account} header entity of the
 * {@code financial-account} spec via {@code ETGO_SF_ENTITY.Java_Qualifier =
 * "financialAccountHeaderHandler"}, so it runs as a pre/post hook around the
 * generic CRUD persistence — the same way {@code SalesInvoiceHeaderHandler}
 * does. Both the HTTP CRUD path ({@code NeoCrudHandler.handleWithHooks}) and the
 * MCP write path ({@code McpToolRouter}) invoke {@link #handle(NeoContext)}.
 *
 * <p>Request body uses the DAL property names of {@code FIN_Financial_Account}:
 * {@code { "name", "currency", "type"?, "iBAN"?, "swiftCode"? }}.
 * {@code type} is {@code 'B'} (Bank, default), {@code 'C'} (Cash) or
 * {@code 'CA'} (Card). Bank connection / "Con conexión" wiring is out of scope (T3).
 *
 * <p>The pre-hook does NOT persist the record itself: on create/update it
 * validates and then <b>mutates the request body</b> (injecting {@code country}
 * derived from the IBAN and a default {@code matchingAlgorithm}) and returns
 * {@code null}, letting the generic CRUD service persist within its single
 * transaction. Injecting {@code country} before the insert is mandatory because
 * the row-level trigger {@code FIN_FINANCIAL_ACCOUNT_TRG2} ({@code @COUNTRY_IBAN@})
 * rejects a bank account that carries an IBAN without a country.
 *
 * <p>ETP-4871: DELETE now attempts a real hard delete (see {@link #deleteAccount}) —
 * every FK from another table into {@code FIN_Financial_Account} is RESTRICT (no
 * cascade), so the delete is only allowed once {@link FinancialAccountDeleteSupport#findDeleteBlockers} proves
 * nothing depends on the account. The former soft-archive ({@code IsActive='N'})
 * semantics still exist but moved onto the update path: a {@code PATCH
 * {"active": false}} runs the same open-reconciliations guard the old DELETE-based
 * archive used to run (see {@link #validateAndEnrichUpdate}).
 */
@Named("financialAccountHeaderHandler")
public class FinancialAccountHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountHandler.class);

  private static final String SPEC = "financial-account";
  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_DELETE = "DELETE";

  private static final String FIELD_NAME = "name";
  private static final String FIELD_CURRENCY = "currency";
  /** Package-private (not private) so {@link FinancialAccountCountrySupport}'s
   *  {@code rawEffectiveType}/{@code effectiveIban} resolvers read the same body keys this class
   *  writes, instead of re-declaring the literals on their side. */
  static final String FIELD_TYPE = "type";
  static final String FIELD_IBAN = "iBAN";
  private static final String FIELD_SWIFT_CODE = "swiftCode";
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_MATCHING_ALGORITHM = "matchingAlgorithm";
  /** DAL property of {@code EM_ETGO_Amount_Tolerance} — Etendo drops the "EM_" module prefix. */
  private static final String FIELD_AMOUNT_TOLERANCE = "eTGOAmountTolerance";
  /** A tolerance is a percentage OF the statement line, so beyond 100 % it stops meaning anything. */
  private static final int AMOUNT_TOLERANCE_MAX_PCT = 100;
  /** Salt Edge provider chosen at offline creation (optional); persisted so a later bank connect
   *  can preselect that bank. {@link #FIELD_PSD2_PROVIDER} is the DAL FK property the generic CRUD
   *  resolves by id (mirrors how {@link #FIELD_COUNTRY} is injected). */
  private static final String FIELD_PROVIDER_CODE = "providerCode";
  private static final String FIELD_PROVIDER_NAME = "providerName";
  private static final String FIELD_PSD2_PROVIDER = "psd2Provider";
  /** Computed flag (ETP-4530): {@code true} when the account has at least one active
   *  {@link FIN_FinaccTransaction}. Injected into every GET row so the frontend can lock the
   *  Currency field once real movements exist — a different, stricter condition than
   *  {@code bankConnected} (which only reflects bank-linkage, not transaction history). Not
   *  backed by any AD column, so it is injected here (post-hook, after NeoFieldFilter already ran
   *  on the generic CRUD response) rather than declared in decisions.json — the same technique
   *  {@code SalesInvoiceHeaderHandler} uses for {@code arInvoiceSubtype}. */
  private static final String FIELD_HAS_TRANSACTIONS = "hasTransactions";
  /** {@code true} when {@link FinancialAccountDeleteSupport#findDeleteBlockers} finds nothing depending on the account
   *  (ETP-4871). Injected the same way as {@link #FIELD_HAS_TRANSACTIONS} — post-hook, batched
   *  per page via {@code FinancialAccountsPageHandler#loadDeleteBlockersByAccount}. */
  private static final String FIELD_DELETABLE = "deletable";
  /** Human-readable reason(s) blocking a hard delete; only present when
   *  {@link #FIELD_DELETABLE} is {@code false}. */
  private static final String FIELD_DELETE_BLOCKED_REASON = "deleteBlockedReason";

  /* ---------------------------------------------------------------------------
   * Derived list fields (ETP-4658 follow-up): the accounts list used to be served
   * by the bespoke `financial-accounts-page` R spec, which computed these in SQL
   * while the generic W CRUD knew nothing about them. That split meant any other
   * consumer of the standard spec got incomplete data. They are injected here so
   * the W spec is the single source of truth; the loaders are reused verbatim from
   * {@link FinancialAccountsPageHandler} (same package) rather than duplicating SQL.
   *
   * All of these MUST be injected post-hook: `NeoFieldFilter` strips every key that
   * is not a declared field, and it runs before afterHandle.
   * --------------------------------------------------------------------------- */
  /** Unreconciled statement lines for the account — drives the "Por conciliar (N)" pill. */
  private static final String FIELD_PENDING_COUNT = "pendingCount";
  /** {@code EM_PSD2_Connection_Status = 'CO'} — drives the "Sincronizado / Sin conexión" badge. */
  private static final String FIELD_BANK_CONNECTED = "bankConnected";
  /** Soft-disconnected but still linked to Salt Edge — drives the "Reconectar" action. */
  private static final String FIELD_BANK_RECONNECTABLE = "bankReconnectable";
  /** {@code PSD2_Provider.Logo_Url} of the connected provider; blank when there is none. */
  private static final String FIELD_PROVIDER_LOGO_URL = "providerLogoUrl";
  /** Reserved for the sync badge; never computed server-side (mirrors the R spec's constant false). */
  private static final String FIELD_BANK_CONNECTION_PENDING = "bankConnectionPending";
  /** Currency ISO code, from the {@code c_currency} join. The contract only carries the FK. */
  private static final String FIELD_CURRENCY_ISO = "currencyIso";
  private static final String FIELD_CURRENCY_ID = "currencyId";
  /** Country of the account (ETP-4896), from the {@code c_country} join — mirrors the
   *  currencyId/currencyIso pair above and is identical in shape to the R spec's row so
   *  EditAccountModal reads one shape regardless of which endpoint it came from. */
  private static final String FIELD_COUNTRY_ID = "countryId";
  private static final String FIELD_COUNTRY_ISO = "countryIso";
  private static final String FIELD_COUNTRY_NAME = "countryName";
  private static final String FIELD_IS_DEFAULT = "isDefault";
  private static final String FIELD_MASKED_PAN = "maskedPan";
  /** Archived-vs-active flag. {@code Isactive} has no ETGO_SF_FIELD row on this entity, so the
   *  generic CRUD response would not carry it — but the list's "Inactivas" filter needs it. */
  private static final String FIELD_ACTIVE = "active";
  /** Lowercase alias of the contract's {@code iBAN}. The contract name is a mechanical
   *  derivation of the AD column ({@code Iban} → {@code iBAN}) and cannot be overridden from
   *  decisions.json, so the list-friendly spelling is aliased here. */
  private static final String FIELD_IBAN_ALIAS = "iban";
  /** Collection-level aggregates for the list sidebar, attached as a sibling of `response.data`. */
  private static final String FIELD_SUMMARY = "summary";
  /** Entity name of the header entity this handler is registered against (ETP-4896: used to scope
   *  the new defaults keys, since {@code afterHandle}'s DEFAULTS branch has no entity guard). */
  private static final String ENTITY_ACCOUNT = "account";
  private static final String KEY_DEFAULTS = "defaults";
  private static final String SUFFIX_IDENTIFIER = "$_identifier";
  /** The &le;45-country IBAN-metadata catalog (ETP-4896), attached as a sibling of {@code
   *  defaults} on the {@code account} entity's defaults response — see
   *  {@link FinancialAccountCountrySupport#buildIbanRules}. */
  private static final String FIELD_COUNTRY_IBAN_RULES = "countryIbanRules";

  private static final String TYPE_BANK = "B";
  private static final String TYPE_CASH = "C";
  private static final String TYPE_CARD = "CA";
  private static final int NAME_MAX_LENGTH = 60;
  private static final int IBAN_MAX_LENGTH = 34;
  private static final int SWIFT_MAX_LENGTH = 20;

  /** Reconciliation document statuses considered closed (not "open"). */
  private static final List<String> CLOSED_RECONCILIATION_STATUSES = Arrays.asList("CO", "CL");

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName())) {
      return null;
    }
    String method = context.getHttpMethod();
    try {
      enterAdminMode();
      if (METHOD_POST.equals(method)) {
        return validateAndEnrichCreate(context.getRequestBody());
      }
      if (METHOD_PUT.equals(method) || METHOD_PATCH.equals(method)) {
        return validateAndEnrichUpdate(context.getRecordId(), context.getRequestBody());
      }
      if (METHOD_DELETE.equals(method)) {
        return deleteAccount(context.getRecordId());
      }
      // GET (list / getById) flows straight through to the generic service.
      return null;
    } catch (OBException e) {
      // The pre-hook short-circuits on error, so rolling back here is safe: the
      // generic CRUD never runs and the archive write (the only persist in this
      // hook) must not survive.
      doRollbackAndClose();
      log.warn("financial-account hook business error: {}", e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      doRollbackAndClose();
      log.error("financial-account hook error", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      exitAdminMode();
    }
  }

  // ---------------------------------------------------------------------------
  // Post-hook: auto-assign default payment methods by account type on create
  // ---------------------------------------------------------------------------

  /**
   * After the generic CRUD persists a new financial account, link the default
   * payment methods that correspond to its type via
   * {@link FinancialAccountSupport#assignDefaultPaymentMethods}, so a Cash/Bank/Card
   * account is usable for receipts/payments without manual setup. Idempotent:
   * existing links are left untouched. Failures here never break account creation —
   * the account is already committed and the assignment is best-effort.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName())) {
      return null;
    }
    if (NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return injectAccountDefaults(context);
    }
    if (METHOD_GET.equals(context.getHttpMethod()) && NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return injectHasTransactions(context);
    }
    if (!METHOD_POST.equals(context.getHttpMethod())) {
      return null;
    }
    try {
      enterAdminMode();
      String accountId = extractCreatedId(context);
      if (StringUtils.isBlank(accountId)) {
        return null;
      }
      FIN_FinancialAccount account = loadAccount(accountId);
      if (account != null) {
        FinancialAccountSupport.assignDefaultPaymentMethods(account);
      }
    } catch (Exception e) {
      log.error("financial-account afterHandle: failed to assign default payment methods", e);
    } finally {
      exitAdminMode();
    }
    // Keep the original CRUD response untouched.
    return null;
  }

  /**
   * Populates the generic {@code defaults} response for the New/Edit Account forms and attaches
   * the {@code countryIbanRules} catalog as a sibling of {@code defaults} (ETP-4896) — a catalog
   * is not itself the default of any one field, so it does not belong inside that node.
   *
   * <p>Scoped to the {@code account} entity via an explicit guard: this branch of {@code
   * afterHandle} does not check the entity name, so the pre-existing currency injection already
   * fires for {@code transaction}/{@code importedBankStatements}/{@code bankStatementLines}
   * defaults too. That leak predates this change and is left alone (removing it could break a
   * frontend already relying on it) — but the two NEW keys ({@code country},
   * {@code countryIbanRules}) must not repeat it.
   */
  private NeoResponse injectAccountDefaults(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      enterAdminMode();
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject(KEY_DEFAULTS);
      if (defaults == null) {
        defaults = new JSONObject();
        body.put(KEY_DEFAULTS, defaults);
      }
      applyClientCurrencyDefault(defaults);
      if (ENTITY_ACCOUNT.equals(context.getEntityName())) {
        applyOrgCountryDefault(defaults);
        body.put(FIELD_COUNTRY_IBAN_RULES, FinancialAccountCountrySupport.buildIbanRules());
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("financial-account afterHandle: failed to inject account defaults", e);
      return null;
    } finally {
      exitAdminMode();
    }
  }

  /**
   * Overwrites {@code defaults.currency} with the client's accounting-schema currency (e.g. EUR
   * for GOClient). Verbatim behavior moved out of the former {@code injectClientCurrencyDefault}.
   *
   * <p>The {@code C_Currency_ID} column has no AD default-value expression, so
   * {@code NeoDefaultsService}'s generic fallback ({@code resolveFirstComboOption}) picks
   * whichever active currency sorts first alphabetically (AED) — a real, non-null value, so it
   * is never left blank for the New Account Wizard to fill in itself. That column is shared by
   * many core windows, so fixing this dictionary-wide would risk unrelated side effects; scoping
   * the fix to this handler's post-hook only affects the financial-account spec's own defaults.
   */
  private void applyClientCurrencyDefault(JSONObject defaults) throws JSONException {
    OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id",
        OBContext.getOBContext().getCurrentClient().getId()));
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    AcctSchema schema = (AcctSchema) crit.uniqueResult();
    Currency clientCurrency = schema != null ? schema.getCurrency() : null;
    if (clientCurrency == null) {
      return;
    }
    defaults.put(FIELD_CURRENCY, clientCurrency.getId());
    defaults.put(FIELD_CURRENCY + SUFFIX_IDENTIFIER, clientCurrency.getISOCode());
  }

  /**
   * Sets {@code defaults.country} to the active organization's country (ETP-4896 requirement 1),
   * mirroring the {@code currency} / {@code currency$_identifier} pair above so the frontend's
   * defaults consumer needs no new shape. Never emits the AD-seeded {@code ISDEFAULT='Y'} country
   * (United States, no IBAN metadata): {@link #resolveOrgCountry} only returns a usable value or
   * {@code null}, and {@code null} here means the key is simply omitted rather than written as a
   * plausible-but-wrong default.
   */
  private void applyOrgCountryDefault(JSONObject defaults) throws JSONException {
    Country orgCountry = resolveOrgCountry();
    if (orgCountry == null) {
      return;
    }
    defaults.put(FIELD_COUNTRY, orgCountry.getId());
    defaults.put(FIELD_COUNTRY + SUFFIX_IDENTIFIER, orgCountry.getName());
  }

  /**
   * Injects {@link #FIELD_HAS_TRANSACTIONS} into every row of a GET (list/getById) response —
   * {@code true} when the account has at least one active {@link FIN_FinaccTransaction}. The
   * frontend uses this (not {@code bankConnected}) to lock the Currency field once the account has
   * real movement history (ETP-4530).
   */
  private NeoResponse injectHasTransactions(NeoContext context) {
    JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
    if (dataArr == null) {
      return null;
    }
    try {
      enterAdminMode();
      injectDerivedFields(context, dataArr);
      return NeoResponse.ok(context.getPreviousResult().getBody());
    } catch (Exception e) {
      log.error("financial-account afterHandle: failed to inject derived list fields", e);
      return null;
    } finally {
      exitAdminMode();
    }
  }

  /**
   * Enriches every GET row with the fields the accounts list needs but no AD column provides,
   * and attaches the collection-level {@code summary} used by the list sidebar.
   *
   * <p>All four data loaders run <b>once</b> for the whole page (a Map/Set lookup per row),
   * reusing {@link FinancialAccountsPageHandler}'s SQL verbatim. The previous implementation
   * issued two queries <i>per row</i> just for {@code hasTransactions}; {@code deletable} /
   * {@code deleteBlockedReason} (ETP-4871) follow the same rule — one batched query for the
   * whole page, never {@link FinancialAccountDeleteSupport#findDeleteBlockers} called per row.
   *
   * <p>The summary deliberately aggregates only the rows present in <b>this</b> response rather
   * than the loader's own universe: the generic CRUD already applied the role's readable-org and
   * window-access filters, so aggregating anything wider would show totals for accounts the caller
   * cannot see.
   */
  private void injectDerivedFields(NeoContext context, JSONArray dataArr) throws Exception {
    FinancialAccountsPageHandler loaders = pageLoaders();
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    Set<String> orgs = loaders.accessibleOrgs(OBContext.getOBContext().getCurrentOrganization().getId());

    Map<String, FinancialAccountsPageHandler.AccountRow> byId = new LinkedHashMap<>();
    for (FinancialAccountsPageHandler.AccountRow row : loaders.loadAccounts(clientId, orgs)) {
      byId.put(row.id, row);
    }
    Map<String, Integer> pendingByAccount = loaders.loadPendingByAccount(clientId, orgs);
    Set<String> withTransactions = loaders.loadAccountsWithTransactions(clientId, orgs);
    Map<String, List<String>> deleteBlockersByAccount = loaders.loadDeleteBlockersByAccount(clientId, orgs);

    Set<String> visibleIds = new java.util.LinkedHashSet<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String correlatedId = enrichRecord(dataArr.getJSONObject(i), byId, pendingByAccount, withTransactions,
          deleteBlockersByAccount);
      if (correlatedId != null) {
        visibleIds.add(correlatedId);
      }
    }

    // Iterate `byId` (loader order: isdefault DESC, name ASC) rather than the CRUD's
    // row order, so `summary.byCurrency` keeps the same sequence the accounts-page
    // handler produced and the sidebar's currency list does not visibly reorder.
    List<FinancialAccountsPageHandler.AccountRow> visible = new ArrayList<>();
    for (Map.Entry<String, FinancialAccountsPageHandler.AccountRow> entry : byId.entrySet()) {
      if (visibleIds.contains(entry.getKey())) {
        visible.add(entry.getValue());
      }
    }

    JSONObject envelope = context.getPreviousResult().getBody().optJSONObject("response");
    if (envelope != null) {
      envelope.put(FIELD_SUMMARY, loaders.buildSummary(visible, pendingByAccount));
    }
  }

  /**
   * Enriches ONE GET row with the derived list fields, using the already-loaded lookups.
   *
   * <p>Extracted from {@link #injectDerivedFields}'s loop so the two ways a row can fail to
   * correlate — no id at all, or an id the account loader does not know — read as guard
   * clauses instead of the two {@code continue} statements they used to be (Sonar S135
   * allows at most one per loop).
   *
   * @return the account id when the row correlated with the loaders and therefore counts
   *         towards {@code summary}, or {@code null} when it did not.
   */
  private String enrichRecord(JSONObject rec, Map<String, FinancialAccountsPageHandler.AccountRow> byId,
      Map<String, Integer> pendingByAccount, Set<String> withTransactions,
      Map<String, List<String>> deleteBlockersByAccount) throws JSONException {
    String id = StringUtils.trimToNull(rec.optString("id", null));
    // A row with no id cannot be correlated with the loaders; keep the historical
    // contract (the flag is always present, defaulting to false) instead of omitting it.
    rec.put(FIELD_HAS_TRANSACTIONS, id != null && withTransactions.contains(id));
    if (id == null) {
      return null;
    }
    rec.put(FIELD_PENDING_COUNT, pendingByAccount.getOrDefault(id, 0));
    // isNull() first: optString() on a JSON null yields the literal "null" string,
    // which the list would render as text under the account type.
    rec.put(FIELD_IBAN_ALIAS, rec.isNull(FIELD_IBAN) ? "" : rec.optString(FIELD_IBAN, ""));

    List<String> blockers = deleteBlockersByAccount.getOrDefault(id, Collections.emptyList());
    rec.put(FIELD_DELETABLE, blockers.isEmpty());
    if (!blockers.isEmpty()) {
      rec.put(FIELD_DELETE_BLOCKED_REASON, String.join(" ", blockers));
    }

    FinancialAccountsPageHandler.AccountRow row = byId.get(id);
    if (row == null) {
      return null;
    }
    rec.put(FIELD_BANK_CONNECTED, row.bankConnected);
    rec.put(FIELD_BANK_RECONNECTABLE, row.bankReconnectable);
    rec.put(FIELD_PROVIDER_LOGO_URL, row.providerLogoUrl);
    rec.put(FIELD_BANK_CONNECTION_PENDING, row.bankConnectionPending);
    rec.put(FIELD_CURRENCY_ISO, row.currency.iso);
    rec.put(FIELD_CURRENCY_ID, row.currency.id);
    rec.put(FIELD_COUNTRY_ID, row.country != null ? row.country.id : "");
    rec.put(FIELD_COUNTRY_ISO, row.country != null ? row.country.iso : "");
    rec.put(FIELD_COUNTRY_NAME, row.country != null ? row.country.name : "");
    rec.put(FIELD_IS_DEFAULT, row.isDefault);
    rec.put(FIELD_MASKED_PAN, row.maskedPan);
    rec.put(FIELD_ACTIVE, row.active);
    return id;
  }

  /**
   * Seam for the SQL loaders shared with the accounts-page handler. Package-private and
   * overridable so unit tests can stub the four queries without a live connection —
   * same convention as {@link #loadAccount(String)} and {@code hasOpenReconciliations}.
   */
  FinancialAccountsPageHandler pageLoaders() {
    return new FinancialAccountsPageHandler();
  }

  /** {@code true} when the account has at least one active transaction registered against it. */
  boolean hasTransactions(FIN_FinancialAccount account) {
    OBCriteria<FIN_FinaccTransaction> criteria =
        OBDal.getInstance().createCriteria(FIN_FinaccTransaction.class);
    criteria.add(Restrictions.eq(FIN_FinaccTransaction.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FIN_FinaccTransaction.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  /** Reads the persisted record id from the generic CRUD response envelope. */
  String extractCreatedId(NeoContext context) {
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    JSONObject response = prev.getBody().optJSONObject("response");
    if (response == null) {
      return null;
    }
    Object data = response.opt("data");
    if (data instanceof JSONArray) {
      JSONArray arr = (JSONArray) data;
      JSONObject first = arr.length() > 0 ? arr.optJSONObject(0) : null;
      return first == null ? null : StringUtils.trimToNull(first.optString("id", null));
    }
    if (data instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) data).optString("id", null));
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Create (pre-hook: validate + enrich body, then let generic CRUD persist)
  // ---------------------------------------------------------------------------

  NeoResponse validateAndEnrichCreate(JSONObject body) throws JSONException {
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing request body");
    }
    String name = body.optString(FIELD_NAME, "").trim();
    String currencyId = body.optString(FIELD_CURRENCY, "").trim();
    String iban = StringUtils.trimToEmpty(FinancialAccountCountrySupport.bodyString(body, FIELD_IBAN));
    String swift = body.optString(FIELD_SWIFT_CODE, "").trim();

    NeoResponse lengthError = validateLengths(name, iban, swift);
    if (lengthError != null) {
      return lengthError;
    }
    NeoResponse toleranceError = validateAmountTolerance(body);
    if (toleranceError != null) {
      return toleranceError;
    }
    if (StringUtils.isBlank(currencyId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Currency is required");
    }
    if (loadCurrency(currencyId) == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid currency");
    }
    if (nameExists(name, null)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "An account with this name already exists");
    }

    // Normalize the account type (defaults to Bank) so the generic service
    // always persists one of the allowed values.
    String type = normalizeType(body.optString(FIELD_TYPE, TYPE_BANK).trim());
    body.put(FIELD_TYPE, type);

    // Persist the chosen Salt Edge provider (offline "with bank selected" flow): upsert the
    // provider and inject the FK so the account remembers its bank. The account stays offline —
    // this is metadata only — but a later bank connect can then preselect that provider.
    enrichProvider(body, type);

    // Validates the (IBAN, country) pair and injects/normalizes both in the body before the
    // insert — the trigger FIN_FINANCIAL_ACCOUNT_TRG2 rejects a bank account with an IBAN but no
    // country, and a mismatched pair would otherwise surface as a raw 500 (see
    // FinancialAccountCountrySupport#validateIbanCountryPair).
    NeoResponse countryError = validateCountryAndIban(body, null);
    if (countryError != null) {
      return countryError;
    }
    // Inject a default matching algorithm when the caller did not provide one,
    // so reconciliation has an algorithm to work with.
    injectDefaultMatchingAlgorithm(body);

    return null;
  }

  /**
   * When the offline create carries a Salt Edge provider (bank accounts only), upsert the provider
   * record and inject its id under the {@code psd2Provider} FK property so the generic CRUD links
   * it — same mechanism used for {@code country}. The transient {@code providerCode}/
   * {@code providerName} keys are removed so they are not treated as entity properties.
   */
  private void enrichProvider(JSONObject body, String type) throws JSONException {
    String providerCode = body.optString(FIELD_PROVIDER_CODE, "").trim();
    if (TYPE_BANK.equals(type) && StringUtils.isNotBlank(providerCode)) {
      String providerName = body.optString(FIELD_PROVIDER_NAME, providerCode).trim();
      Provider provider = ProviderCatalogUtils.upsertProvider(providerCode, providerName, null);
      OBDal.getInstance().flush();
      body.put(FIELD_PSD2_PROVIDER, provider.getId());
    }
    body.remove(FIELD_PROVIDER_CODE);
    body.remove(FIELD_PROVIDER_NAME);
  }

  // ---------------------------------------------------------------------------
  // Update (pre-hook: validate + keep country in sync with the IBAN)
  // ---------------------------------------------------------------------------

  NeoResponse validateAndEnrichUpdate(String id, JSONObject body) throws JSONException {
    if (body == null) {
      return null;
    }
    // Archive guard moved here from the old DELETE-based archive() (ETP-4871): the frontend now
    // archives via PATCH {"active": false} instead of DELETE, so the open-reconciliations check
    // that used to gate the soft-archive must gate this instead, before the generic CRUD persists
    // the flip. Reuses the same has()/isNull() body-inspection idiom as the IBAN check below.
    if (isArchivingRequest(body)) {
      NeoResponse archiveGuardError = guardArchive(id);
      if (archiveGuardError != null) {
        return archiveGuardError;
      }
    }
    String name = body.has(FIELD_NAME) ? body.optString(FIELD_NAME, "").trim() : null;
    // isNull-aware: optString() on a JSON null would yield the literal "null" string, which a
    // PATCH {"iBAN": null} (clearing the IBAN) used to feed straight into the country-derivation
    // check below as if it were a real, non-blank IBAN.
    String iban = StringUtils.trimToEmpty(FinancialAccountCountrySupport.bodyString(body, FIELD_IBAN));
    String swift = body.optString(FIELD_SWIFT_CODE, "").trim();

    NeoResponse nameError = validateRenamedName(name, id);
    if (nameError != null) {
      return nameError;
    }
    if (iban.length() > IBAN_MAX_LENGTH || swift.length() > SWIFT_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "IBAN or BIC/SWIFT is too long");
    }
    NeoResponse toleranceError = validateAmountTolerance(body);
    if (toleranceError != null) {
      return toleranceError;
    }
    return validateCountryAndIban(body, id);
  }

  // ---------------------------------------------------------------------------
  // Country + IBAN validation (ETP-4896) — shared by create and update
  // ---------------------------------------------------------------------------

  /**
   * Resolves the (IBAN, country) pair that will actually be persisted and rejects it with a
   * friendly 400 before {@code FIN_FINANCIAL_ACCOUNT_TRG2} can raise {@code @COUNTRY_IBAN@} /
   * {@code @20257@} / {@code @20259@} — which {@code NeoErrorSanitizer} would otherwise flatten
   * into a 500 "Service temporarily unavailable".
   *
   * <p>Precedence: a country present in the body WINS. IBAN-derivation from the prefix
   * ({@link #resolveCountryFromIban}) is only a fallback for a body that carries no country at
   * all, so the SPA's country picker is authoritative while older API/MCP callers that only ever
   * sent an IBAN keep working unchanged.
   *
   * <p>Neither field touched by the body is treated as a no-op — deliberately mirroring the
   * trigger's own {@code COALESCE(:OLD…)<>COALESCE(:NEW…)} guard, which does not re-validate
   * either. This matters for legacy or externally-imported rows whose stored pair may already be
   * inconsistent: an unrelated edit (renaming the account, say) must not suddenly reject them. It
   * is also why the account is loaded LAZILY, only when the body actually touches {@code iBAN} or
   * {@code country} — the overwhelming majority of partial updates (rename, tolerances,
   * accounting config, …) never reach a DAL call here at all.
   *
   * @param accountId the record id on update, {@code null}/blank on create (nothing to load).
   */
  NeoResponse validateCountryAndIban(JSONObject body, String accountId) throws JSONException {
    boolean bodyHasIban = body.has(FIELD_IBAN);
    boolean bodyHasCountry = body.has(FIELD_COUNTRY);
    if (!bodyHasIban && !bodyHasCountry) {
      return null;
    }

    FIN_FinancialAccount stored = StringUtils.isNotBlank(accountId) ? loadAccount(accountId) : null;
    // On create, validateAndEnrichCreate already normalized and wrote FIELD_TYPE into the body
    // before calling here, so the body branch always wins there; the stored-type fallback only
    // ever applies on update. normalizeType maps a null (neither source had one) to Bank, and is
    // the identity for every value the AD "Financial account type" reference allows — the stored
    // type now goes through it too, which only matters for a DB value outside {B, C, CA}.
    String effectiveType = normalizeType(FinancialAccountCountrySupport.rawEffectiveType(body, stored));
    if (!TYPE_BANK.equals(effectiveType)) {
      // The trigger's IBAN branch only runs for TYPE='B'; mirroring that avoids rejecting a
      // Cash/Card account that happens to carry a stale IBAN.
      return null;
    }

    String effectiveIban = FinancialAccountCountrySupport.effectiveIban(body, bodyHasIban, stored);
    if (StringUtils.isBlank(effectiveIban)) {
      // The trigger's own guard is IF (:NEW.IBAN IS NOT NULL) — a bank account with a country and
      // no IBAN is legal, so there is nothing to validate.
      return null;
    }

    if (bodyHasCountry && FinancialAccountCountrySupport.isExplicitClear(body, FIELD_COUNTRY)) {
      // Do not silently re-derive here: that would contradict "the user's choice wins" and hide
      // the user's own action of clearing the field.
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "A bank account with an IBAN must have a country.");
    }

    Country effectiveCountry = resolveEffectiveCountry(body, bodyHasCountry, stored, effectiveIban);
    // Only meaningful when the body supplied one: there, null means the id does not resolve to a
    // country at all. When it did not, null just means "nothing to derive from the IBAN either",
    // which validateIbanCountryPair reports with its own, more specific message.
    if (bodyHasCountry && effectiveCountry == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid country");
    }

    String pairError = FinancialAccountCountrySupport.validateIbanCountryPair(effectiveIban, effectiveCountry);
    if (pairError != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, pairError);
    }
    // Always write back what was actually validated, so the generic CRUD persists exactly that —
    // not a caller-supplied IBAN with stray separators, nor a country resolved here but never
    // reflected in the body.
    body.put(FIELD_IBAN, effectiveIban);
    if (effectiveCountry != null) {
      body.put(FIELD_COUNTRY, effectiveCountry.getId());
    }
    return null;
  }

  /**
   * The country the write will end up with, in strict precedence order (ETP-4896):
   *
   * <ol>
   *   <li>the one the body carries — the SPA's picker is authoritative, so it wins outright and
   *       {@link #resolveCountryFromIban} is not even consulted;</li>
   *   <li>the stored account's, when the body is silent on the field;</li>
   *   <li>derived from the IBAN prefix — the pre-ETP-4896 behavior, kept only as a fallback for
   *       API/MCP callers that send an IBAN and no country at all.</li>
   * </ol>
   *
   * <p>Returns {@code null} when nothing resolves; the caller decides what that means, since it
   * reads differently per branch (an invalid id the caller sent vs. an unrecognized IBAN prefix).
   *
   * <p>Kept in this class rather than {@link FinancialAccountCountrySupport} — unlike the two
   * resolvers there, this one goes through the {@link #loadCountry} / {@link #resolveCountryFromIban}
   * seams, which the unit tests spy on to run without a database.</p>
   */
  private Country resolveEffectiveCountry(JSONObject body, boolean bodyHasCountry,
      FIN_FinancialAccount stored, String effectiveIban) {
    if (bodyHasCountry) {
      return loadCountry(FinancialAccountCountrySupport.bodyString(body, FIELD_COUNTRY));
    }
    if (stored != null && stored.getCountry() != null) {
      return stored.getCountry();
    }
    return resolveCountryFromIban(effectiveIban);
  }

  // ---------------------------------------------------------------------------
  // Archive guard (moved here from the former DELETE-based archive(); ETP-4871)
  // ---------------------------------------------------------------------------

  /** {@code true} when the incoming body explicitly sets {@code active} to {@code false}. */
  private boolean isArchivingRequest(JSONObject body) {
    return body.has(FIELD_ACTIVE) && !body.isNull(FIELD_ACTIVE) && !body.optBoolean(FIELD_ACTIVE, true);
  }

  /**
   * Blocks an archive (soft-delete via {@code active=false}) the same way the old DELETE-based
   * {@code archive()} used to: an account with open reconciliations cannot be archived. Loads the
   * account itself here (the rest of {@link #validateAndEnrichUpdate} does not need it unless the
   * body also touches IBAN/country — see {@link #validateCountryAndIban}) so a missing id/account
   * still gets the same 400 the old DELETE path returned.
   */
  private NeoResponse guardArchive(String id) {
    FIN_FinancialAccount account = loadAccount(id);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Account not found");
    }
    if (hasOpenReconciliations(account)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Cannot archive an account with open reconciliations");
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Delete (hard delete, ETP-4871: every FK into FIN_Financial_Account is RESTRICT)
  // ---------------------------------------------------------------------------

  /**
   * Hard-deletes the account once nothing depends on it via a foreign key. Every FK from another
   * table into {@code FIN_Financial_Account} is {@code RESTRICT} (no cascade), so a bare delete
   * would fail at the DB level regardless — {@link FinancialAccountDeleteSupport#findDeleteBlockers}
   * proves in advance that it will not, and returns a 409 naming every blocker instead of surfacing
   * a raw constraint violation. The account's own auto-created configuration rows (accounting
   * setup, default payment methods, matching rules, PSD2 sync log) are swept first via
   * {@link FinancialAccountDeleteSupport#sweepOwnConfig} — those are not blockers, every account
   * has them from creation.
   *
   * <p>The blocker checks and the config sweep live in {@link FinancialAccountDeleteSupport}
   * (extracted purely to keep this class under the Sonar method-count threshold, java:S1448) —
   * this method is the only remaining caller in this class.
   */
  NeoResponse deleteAccount(String id) {
    if (StringUtils.isBlank(id)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing account id");
    }
    FIN_FinancialAccount account = loadAccount(id);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Account not found");
    }
    List<String> blockers = FinancialAccountDeleteSupport.findDeleteBlockers(account, hasTransactions(account));
    if (!blockers.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Cannot delete this account. " + String.join(" ", blockers));
    }
    FinancialAccountDeleteSupport.sweepOwnConfig(account);
    OBDal.getInstance().remove(account);
    OBDal.getInstance().flush();
    return NeoResponse.noContent();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Rejects an amount tolerance outside 0…100. Returns {@code null} when the body does not carry the
   * field at all, so a partial update that never mentions it is untouched.
   *
   * <p>Enforced here and not only in the edit modal because this is a generic W spec: anything
   * holding a token can PUT {@code eTGOAmountTolerance} straight at the entity. The value is read as
   * a PERCENTAGE of the statement line by both the automatch engine
   * ({@code AutoMatchSupport.computeAmountTolerance}) and the difference posting
   * ({@code ReconciliationDifferenceSupport.differenceLimit}); at 100 % or more the latter's gate
   * would authorise posting an entire statement line of any size to a G/L item, so this is a
   * boundary, not a nicety.
   */
  private NeoResponse validateAmountTolerance(JSONObject body) {
    if (body == null || !body.has(FIELD_AMOUNT_TOLERANCE)
        || body.isNull(FIELD_AMOUNT_TOLERANCE)) {
      return null;
    }
    String raw = StringUtils.trimToEmpty(body.optString(FIELD_AMOUNT_TOLERANCE, ""));
    if (raw.isEmpty()) {
      return null;
    }
    BigDecimal pct;
    try {
      pct = new BigDecimal(raw);
    } catch (NumberFormatException e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Amount tolerance must be a number: " + raw);
    }
    if (pct.signum() < 0
        || pct.compareTo(BigDecimal.valueOf(AMOUNT_TOLERANCE_MAX_PCT)) > 0) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Amount tolerance must be a percentage between 0 and " + AMOUNT_TOLERANCE_MAX_PCT
              + " (received " + pct.toPlainString() + ").");
    }
    return null;
  }

  /**
   * Validates the name an update is trying to set. A {@code null} name means the caller never sent
   * the field, so a partial update that does not rename the account skips these checks entirely —
   * which is why this cannot reuse {@link #validateLengths}, whose blank check is unconditional.
   */
  private NeoResponse validateRenamedName(String name, String id) {
    if (name == null) {
      return null;
    }
    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is required");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is too long");
    }
    if (nameExists(name, id)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "An account with this name already exists");
    }
    return null;
  }

  private NeoResponse validateLengths(String name, String iban, String swift) {
    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is required");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is too long");
    }
    if (iban.length() > IBAN_MAX_LENGTH || swift.length() > SWIFT_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "IBAN or BIC/SWIFT is too long");
    }
    return null;
  }

  void injectDefaultMatchingAlgorithm(JSONObject body) throws JSONException {
    if (StringUtils.isNotBlank(body.optString(FIELD_MATCHING_ALGORITHM, ""))) {
      return;
    }
    List<MatchingAlgorithm> algorithms = listMatchingAlgorithms();
    if (!algorithms.isEmpty()) {
      body.put(FIELD_MATCHING_ALGORITHM, algorithms.get(0).getId());
    }
  }

  // ---------------------------------------------------------------------------
  // Seams (package-private to allow unit tests to stub the DAL layer)
  // ---------------------------------------------------------------------------

  void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  void doRollbackAndClose() {
    OBDal.getInstance().rollbackAndClose();
  }

  String normalizeType(String type) {
    if (TYPE_CASH.equals(type)) {
      return TYPE_CASH;
    }
    if (TYPE_CARD.equals(type)) {
      return TYPE_CARD;
    }
    return TYPE_BANK;
  }

  Currency loadCurrency(String currencyId) {
    return OBDal.getInstance().get(Currency.class, currencyId);
  }

  FIN_FinancialAccount loadAccount(String accountId) {
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
  }

  Country loadCountry(String countryId) {
    return OBDal.getInstance().get(Country.class, countryId);
  }

  /** The active organization's country (ETP-4896), or {@code null} when it cannot be resolved
   *  even via the ES fallback — see {@link FinancialAccountCountrySupport#resolveOrganizationCountry}. */
  Country resolveOrgCountry() {
    return FinancialAccountCountrySupport.resolveOrganizationCountry(
        OBContext.getOBContext().getCurrentOrganization().getId());
  }

  /**
   * Resolves the {@link Country} an IBAN belongs to from its first two characters
   * (the ISO 3166-1 alpha-2 code, e.g. {@code ES} -> Spain). The financial account
   * trigger requires the country to be set whenever a bank account stores an IBAN.
   *
   * <p>Delegates to {@link FinancialAccountCountrySupport#resolveCountryForIbanPrefix}, which
   * prefers a match on {@code IBANCODE} over the plain ISO code (ETP-4896): only ~45 of 243
   * seeded countries carry IBAN metadata, and matching on the ISO code alone can return one of
   * the other ~198, which {@code FIN_FINANCIAL_ACCOUNT_TRG2} then rejects.
   *
   * @return the matching country, or {@code null} when the IBAN is too short or no
   *         active country matches the prefix either way.
   */
  Country resolveCountryFromIban(String iban) {
    return FinancialAccountCountrySupport.resolveCountryForIbanPrefix(
        FinancialAccountCountrySupport.normalizeIban(iban));
  }

  boolean nameExists(String name, String excludeId) {
    OBCriteria<FIN_FinancialAccount> criteria =
        OBDal.getInstance().createCriteria(FIN_FinancialAccount.class);
    criteria.add(Restrictions.eq(FIN_FinancialAccount.PROPERTY_NAME, name));
    criteria.add(Restrictions.eq(FIN_FinancialAccount.PROPERTY_ORGANIZATION,
        OBContext.getOBContext().getCurrentOrganization()));
    criteria.add(Restrictions.eq(FIN_FinancialAccount.PROPERTY_ACTIVE, true));
    if (StringUtils.isNotBlank(excludeId)) {
      criteria.add(Restrictions.ne(FIN_FinancialAccount.PROPERTY_ID, excludeId));
    }
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  boolean hasOpenReconciliations(FIN_FinancialAccount account) {
    OBCriteria<FIN_Reconciliation> criteria =
        OBDal.getInstance().createCriteria(FIN_Reconciliation.class);
    criteria.add(Restrictions.eq(FIN_Reconciliation.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FIN_Reconciliation.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.not(
        Restrictions.in(FIN_Reconciliation.PROPERTY_DOCUMENTSTATUS, CLOSED_RECONCILIATION_STATUSES)));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  List<MatchingAlgorithm> listMatchingAlgorithms() {
    OBCriteria<MatchingAlgorithm> criteria =
        OBDal.getInstance().createCriteria(MatchingAlgorithm.class);
    criteria.add(Restrictions.eq(MatchingAlgorithm.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(MatchingAlgorithm.PROPERTY_NAME, true);
    return criteria.list();
  }
}
