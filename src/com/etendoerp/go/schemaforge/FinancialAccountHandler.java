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

import java.util.ArrayList;
import java.util.Arrays;
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
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;

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
 * {@code 'CA'} (Card). PSD2 / "Con conexión" wiring is out of scope (T3).
 *
 * <p>The pre-hook does NOT persist the record itself: on create/update it
 * validates and then <b>mutates the request body</b> (injecting {@code country}
 * derived from the IBAN and a default {@code matchingAlgorithm}) and returns
 * {@code null}, letting the generic CRUD service persist within its single
 * transaction. Injecting {@code country} before the insert is mandatory because
 * the row-level trigger {@code FIN_FINANCIAL_ACCOUNT_TRG2} ({@code @COUNTRY_IBAN@})
 * rejects a bank account that carries an IBAN without a country. On DELETE the
 * hook short-circuits with a soft-archive ({@code IsActive='N'}) to preserve the
 * former archive semantics and avoid FK violations from a hard delete.
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
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_IBAN = "iBAN";
  private static final String FIELD_SWIFT_CODE = "swiftCode";
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_MATCHING_ALGORITHM = "matchingAlgorithm";
  /** Salt Edge provider chosen at offline creation (optional); persisted so a later PSD2 connect
   *  can preselect that bank. {@link #FIELD_PSD2_PROVIDER} is the DAL FK property the generic CRUD
   *  resolves by id (mirrors how {@link #FIELD_COUNTRY} is injected). */
  private static final String FIELD_PROVIDER_CODE = "providerCode";
  private static final String FIELD_PROVIDER_NAME = "providerName";
  private static final String FIELD_PSD2_PROVIDER = "psd2Provider";
  /** Computed flag (ETP-4530): {@code true} when the account has at least one active
   *  {@link FIN_FinaccTransaction}. Injected into every GET row so the frontend can lock the
   *  Currency field once real movements exist — a different, stricter condition than
   *  {@code psd2Connected} (which only reflects bank-linkage, not transaction history). Not
   *  backed by any AD column, so it is injected here (post-hook, after NeoFieldFilter already ran
   *  on the generic CRUD response) rather than declared in decisions.json — the same technique
   *  {@code SalesInvoiceHeaderHandler} uses for {@code arInvoiceSubtype}. */
  private static final String FIELD_HAS_TRANSACTIONS = "hasTransactions";

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
  private static final String FIELD_PSD2_CONNECTED = "psd2Connected";
  /** Reserved for the sync badge; never computed server-side (mirrors the R spec's constant false). */
  private static final String FIELD_PSD2_PENDING = "psd2Pending";
  /** Currency ISO code, from the {@code c_currency} join. The contract only carries the FK. */
  private static final String FIELD_CURRENCY_ISO = "currencyIso";
  private static final String FIELD_CURRENCY_ID = "currencyId";
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
        return archive(context.getRecordId());
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
      return injectClientCurrencyDefault(context);
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
   * Overwrites the generic {@code defaults} response's {@code currency} with the client's
   * accounting-schema currency (e.g. EUR for GOClient).
   *
   * <p>The {@code C_Currency_ID} column has no AD default-value expression, so
   * {@code NeoDefaultsService}'s generic fallback ({@code resolveFirstComboOption}) picks
   * whichever active currency sorts first alphabetically (AED) — a real, non-null value, so it
   * is never left blank for the New Account Wizard to fill in itself. That column is shared by
   * many core windows, so fixing this dictionary-wide would risk unrelated side effects; scoping
   * the fix to this handler's post-hook only affects the financial-account spec's own defaults.
   */
  private NeoResponse injectClientCurrencyDefault(NeoContext context) {
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      enterAdminMode();
      OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
      crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id",
          OBContext.getOBContext().getCurrentClient().getId()));
      crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
      crit.setMaxResults(1);
      AcctSchema schema = (AcctSchema) crit.uniqueResult();
      Currency clientCurrency = schema != null ? schema.getCurrency() : null;
      if (clientCurrency == null) {
        return null;
      }
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject("defaults");
      if (defaults == null) {
        defaults = new JSONObject();
        body.put("defaults", defaults);
      }
      defaults.put(FIELD_CURRENCY, clientCurrency.getId());
      defaults.put(FIELD_CURRENCY + "$_identifier", clientCurrency.getISOCode());
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("financial-account afterHandle: failed to inject client currency default", e);
      return null;
    } finally {
      exitAdminMode();
    }
  }

  /**
   * Injects {@link #FIELD_HAS_TRANSACTIONS} into every row of a GET (list/getById) response —
   * {@code true} when the account has at least one active {@link FIN_FinaccTransaction}. The
   * frontend uses this (not {@code psd2Connected}) to lock the Currency field once the account has
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
   * <p>All three data loaders run <b>once</b> for the whole page (a Map/Set lookup per row),
   * reusing {@link FinancialAccountsPageHandler}'s SQL verbatim. The previous implementation
   * issued two queries <i>per row</i> just for {@code hasTransactions}.
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

    Set<String> visibleIds = new java.util.LinkedHashSet<>();
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject rec = dataArr.getJSONObject(i);
      String id = StringUtils.trimToNull(rec.optString("id", null));
      // A row with no id cannot be correlated with the loaders; keep the historical
      // contract (the flag is always present, defaulting to false) instead of omitting it.
      rec.put(FIELD_HAS_TRANSACTIONS, id != null && withTransactions.contains(id));
      if (id == null) {
        continue;
      }
      rec.put(FIELD_PENDING_COUNT, pendingByAccount.getOrDefault(id, 0));
      // isNull() first: optString() on a JSON null yields the literal "null" string,
      // which the list would render as text under the account type.
      rec.put(FIELD_IBAN_ALIAS, rec.isNull(FIELD_IBAN) ? "" : rec.optString(FIELD_IBAN, ""));

      FinancialAccountsPageHandler.AccountRow row = byId.get(id);
      if (row == null) {
        continue;
      }
      rec.put(FIELD_PSD2_CONNECTED, row.psd2Connected);
      rec.put(FIELD_PSD2_PENDING, row.psd2Pending);
      rec.put(FIELD_CURRENCY_ISO, row.currency.iso);
      rec.put(FIELD_CURRENCY_ID, row.currency.id);
      rec.put(FIELD_IS_DEFAULT, row.isDefault);
      rec.put(FIELD_MASKED_PAN, row.maskedPan);
      rec.put(FIELD_ACTIVE, row.active);
      visibleIds.add(id);
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
   * Seam for the SQL loaders shared with the accounts-page handler. Package-private and
   * overridable so unit tests can stub the three queries without a live connection —
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
    String iban = body.optString(FIELD_IBAN, "").trim();
    String swift = body.optString(FIELD_SWIFT_CODE, "").trim();

    NeoResponse lengthError = validateLengths(name, iban, swift);
    if (lengthError != null) {
      return lengthError;
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
    // this is metadata only — but a later PSD2 connect can then preselect that provider.
    enrichProvider(body, type);

    // Inject the country derived from the IBAN before the insert — the trigger
    // FIN_FINANCIAL_ACCOUNT_TRG2 rejects a bank account with an IBAN but no country.
    if (StringUtils.isNotBlank(iban)) {
      Country country = resolveCountryFromIban(iban);
      if (country != null) {
        body.put(FIELD_COUNTRY, country.getId());
      }
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
      Provider provider = BankIntegrationUtils.upsertProvider(providerCode, providerName, null);
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
    String name = body.has(FIELD_NAME) ? body.optString(FIELD_NAME, "").trim() : null;
    String iban = body.optString(FIELD_IBAN, "").trim();
    String swift = body.optString(FIELD_SWIFT_CODE, "").trim();

    if (name != null) {
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
    }
    if (iban.length() > IBAN_MAX_LENGTH || swift.length() > SWIFT_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "IBAN or BIC/SWIFT is too long");
    }
    // Keep the country in sync with the IBAN whenever the caller sends an IBAN.
    if (body.has(FIELD_IBAN) && StringUtils.isNotBlank(iban)) {
      Country country = resolveCountryFromIban(iban);
      if (country != null) {
        body.put(FIELD_COUNTRY, country.getId());
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Delete (short-circuit with a soft-archive)
  // ---------------------------------------------------------------------------

  NeoResponse archive(String id) {
    if (StringUtils.isBlank(id)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing account id");
    }
    FIN_FinancialAccount account = loadAccount(id);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Account not found");
    }
    if (hasOpenReconciliations(account)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "Cannot archive an account with open reconciliations");
    }
    account.setActive(false);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return NeoResponse.noContent();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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

  /**
   * Resolves the {@link Country} an IBAN belongs to from its first two characters
   * (the ISO 3166-1 alpha-2 code, e.g. {@code ES} -> Spain). The financial account
   * trigger requires the country to be set whenever a bank account stores an IBAN.
   *
   * @return the matching country, or {@code null} when the IBAN is too short or no
   *         active country matches the ISO prefix.
   */
  Country resolveCountryFromIban(String iban) {
    if (iban == null || iban.trim().length() < 2) {
      return null;
    }
    String isoCode = iban.trim().substring(0, 2).toUpperCase();
    OBCriteria<Country> criteria = OBDal.getInstance().createCriteria(Country.class);
    // Countries are standard master data (usually Client 0 / Org 0); disable the
    // readable client/org filters so the lookup is not empty in a specific
    // client/org context, and only consider active records.
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Country.PROPERTY_ISOCOUNTRYCODE, isoCode));
    criteria.add(Restrictions.eq(Country.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (Country) criteria.uniqueResult();
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
