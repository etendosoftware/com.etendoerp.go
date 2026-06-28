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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
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

  private static final String TYPE_BANK = "B";
  private static final String TYPE_CASH = "C";
  private static final String TYPE_CARD = "CA";
  private static final int NAME_MAX_LENGTH = 60;
  private static final int IBAN_MAX_LENGTH = 34;
  private static final int SWIFT_MAX_LENGTH = 20;

  /** Reconciliation document statuses considered closed (not "open"). */
  private static final List<String> CLOSED_RECONCILIATION_STATUSES = Arrays.asList("CO", "CL");

  // Default payment methods seeded by the onboarding dataset (GOClient sampledata),
  // matched by name. it1 has no payment-method management screen and exactly these
  // four fixed methods, so name matching is acceptable; if methods ever become
  // localizable or renameable this must migrate to a stable key.
  private static final String METHOD_CASH = "Efectivo";
  private static final String METHOD_TRANSFER = "Transferencia bancaria";
  private static final String METHOD_CHECK = "Cheque";
  private static final String METHOD_CARD = "Tarjeta";

  /**
   * Maps each financial-account type to the payment methods that must be auto-assigned
   * on creation. The first method in each list becomes the account's default. Mirrors
   * the static links shipped in the onboarding dataset for the seeded accounts.
   */
  private static final Map<String, List<String>> PAYMENT_METHODS_BY_TYPE = buildMethodsByType();

  private static Map<String, List<String>> buildMethodsByType() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put(TYPE_CASH, Arrays.asList(METHOD_CASH));
    map.put(TYPE_BANK, Arrays.asList(METHOD_TRANSFER, METHOD_CHECK, METHOD_CARD));
    map.put(TYPE_CARD, Arrays.asList(METHOD_CARD));
    return map;
  }

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
   * payment methods that correspond to its type ({@link #PAYMENT_METHODS_BY_TYPE}),
   * so a Cash/Bank/Card account is usable for receipts/payments without manual
   * setup. Idempotent: existing links are left untouched. Failures here never
   * break account creation — the account is already committed and the assignment
   * is best-effort.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName()) || !METHOD_POST.equals(context.getHttpMethod())) {
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
        assignDefaultPaymentMethods(account);
      }
    } catch (Exception e) {
      log.error("financial-account afterHandle: failed to assign default payment methods", e);
    } finally {
      exitAdminMode();
    }
    // Keep the original CRUD response untouched.
    return null;
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

  void assignDefaultPaymentMethods(FIN_FinancialAccount account) {
    List<String> methodNames = PAYMENT_METHODS_BY_TYPE.get(account.getType());
    if (methodNames == null || methodNames.isEmpty()) {
      return;
    }
    boolean created = false;
    for (int i = 0; i < methodNames.size(); i++) {
      String methodName = methodNames.get(i);
      FIN_PaymentMethod method = findPaymentMethodByName(methodName);
      if (method == null) {
        log.warn("financial-account afterHandle: payment method '{}' not found; skipping", methodName);
      } else if (!linkExists(account, method)) {
        createLink(account, method, i == 0);
        created = true;
      }
    }
    if (created) {
      OBDal.getInstance().flush();
    }
  }

  FIN_PaymentMethod findPaymentMethodByName(String name) {
    OBCriteria<FIN_PaymentMethod> criteria =
        OBDal.getInstance().createCriteria(FIN_PaymentMethod.class);
    criteria.add(Restrictions.eq(FIN_PaymentMethod.PROPERTY_NAME, name));
    criteria.add(Restrictions.eq(FIN_PaymentMethod.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (FIN_PaymentMethod) criteria.uniqueResult();
  }

  boolean linkExists(FIN_FinancialAccount account, FIN_PaymentMethod method) {
    OBCriteria<FinAccPaymentMethod> criteria =
        OBDal.getInstance().createCriteria(FinAccPaymentMethod.class);
    criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD, method));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  void createLink(FIN_FinancialAccount account, FIN_PaymentMethod method, boolean isDefault) {
    FinAccPaymentMethod link = OBProvider.getInstance().get(FinAccPaymentMethod.class);
    link.setClient(account.getClient());
    link.setOrganization(account.getOrganization());
    link.setAccount(account);
    link.setPaymentMethod(method);
    link.setDefault(isDefault);
    // payinAllow/payoutAllow (true), execution type ("M") and the invoice-paid
    // statuses come from the entity's column defaults — Manual, allowing both
    // receipts and payments, matching the onboarding dataset.
    OBDal.getInstance().save(link);
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
