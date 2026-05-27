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
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

/**
 * NeoHandler that powers the offline management of financial accounts introduced
 * by ETP-4096 (create / archive) plus a defaults endpoint for the New Account form.
 *
 * <p>It is a report-style spec ({@code SPEC_TYPE=R}), so {@link NeoContext#getEndpointType()}
 * is {@code null} and the handler routes purely on the HTTP method plus an {@code action}
 * query parameter:
 *
 * <ul>
 *   <li>{@code POST /sws/neo/financial-account} — create an account from the JSON body</li>
 *   <li>{@code POST /sws/neo/financial-account?action=update&id=<id>} — edit general data</li>
 *   <li>{@code POST /sws/neo/financial-account?action=archive&id=<id>} — soft-delete an account</li>
 *   <li>{@code GET  /sws/neo/financial-account?action=defaults} — session currency + currency list</li>
 * </ul>
 *
 * <p>Create body: {@code { "name", "currencyId", "type"?, "iban"?, "swiftCode"? }}.
 * {@code type} is {@code 'B'} (Bank, default) or {@code 'C'} (Cash); {@code iban}/{@code swiftCode}
 * are optional and only used for bank accounts. PSD2 / "Con conexión" wiring is out of scope (T3).
 */
@Named("financial-account")
public class FinancialAccountHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountHandler.class);

  private static final String SPEC = "financial-account";
  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";
  private static final String PARAM_ACTION = "action";
  private static final String PARAM_ID = "id";
  private static final String ACTION_DEFAULTS = "defaults";
  private static final String ACTION_ARCHIVE = "archive";
  private static final String ACTION_UPDATE = "update";

  private static final String TYPE_BANK = "B";
  private static final String TYPE_CASH = "C";
  private static final int NAME_MAX_LENGTH = 60;
  private static final int IBAN_MAX_LENGTH = 34;
  private static final int SWIFT_MAX_LENGTH = 20;

  private static final String FIELD_SWIFT_CODE = "swiftCode";

  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";

  /** Reconciliation document statuses considered closed (not "open"). */
  private static final List<String> CLOSED_RECONCILIATION_STATUSES = Arrays.asList("CO", "CL");

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName())) {
      return null;
    }
    String method = context.getHttpMethod();
    Map<String, String> params = context.getQueryParams();
    String action = params != null ? params.get(PARAM_ACTION) : null;

    try {
      enterAdminMode();
      if (METHOD_GET.equals(method) && ACTION_DEFAULTS.equals(action)) {
        return buildDefaults();
      }
      if (METHOD_POST.equals(method) && ACTION_ARCHIVE.equals(action)) {
        return archive(params != null ? params.get(PARAM_ID) : null);
      }
      if (METHOD_POST.equals(method) && ACTION_UPDATE.equals(action)) {
        return update(params != null ? params.get(PARAM_ID) : null, context.getRequestBody());
      }
      if (METHOD_POST.equals(method)) {
        return create(context.getRequestBody());
      }
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    } catch (OBException e) {
      doRollbackAndClose();
      log.warn("financial-account handler business error: {}", e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      doRollbackAndClose();
      log.error("financial-account handler error", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      exitAdminMode();
    }
  }

  // ---------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------

  NeoResponse create(JSONObject body) throws JSONException {
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing request body");
    }
    String name = body.optString("name", "").trim();
    String currencyId = body.optString("currencyId", "").trim();
    String type = normalizeType(body.optString("type", TYPE_BANK).trim());
    String iban = body.optString("iban", "").trim();
    String swift = body.optString(FIELD_SWIFT_CODE, "").trim();

    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is required");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is too long");
    }
    if (StringUtils.isBlank(currencyId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Currency is required");
    }
    if (iban.length() > IBAN_MAX_LENGTH || swift.length() > SWIFT_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "IBAN or BIC/SWIFT is too long");
    }

    Currency currency = loadCurrency(currencyId);
    if (currency == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid currency");
    }
    if (nameExists(name, null)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "An account with this name already exists");
    }

    List<MatchingAlgorithm> algorithms = listMatchingAlgorithms();
    MatchingAlgorithm defaultAlgorithm = algorithms.isEmpty() ? null : algorithms.get(0);

    FIN_FinancialAccount account = persist(name, type, currency, iban, swift, defaultAlgorithm);

    JSONObject data = new JSONObject();
    data.put("id", account.getId());
    data.put("name", account.getName());
    return NeoResponse.createdWithData(data);
  }

  /**
   * Persists a new {@link FIN_FinancialAccount} under the current OBContext client
   * and organization. Exposed package-private so unit tests can stub the OBDal layer.
   */
  FIN_FinancialAccount persist(String name, String type, Currency currency, String iban,
      String swift, MatchingAlgorithm algorithm) {
    FIN_FinancialAccount account = OBProvider.getInstance().get(FIN_FinancialAccount.class);
    account.setClient(OBContext.getOBContext().getCurrentClient());
    account.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    account.setActive(true);
    account.setName(name);
    account.setType(type);
    account.setCurrency(currency);
    if (StringUtils.isNotBlank(iban)) {
      account.setIBAN(iban);
    }
    if (StringUtils.isNotBlank(swift)) {
      account.setSwiftCode(swift);
    }
    if (algorithm != null) {
      account.setMatchingAlgorithm(algorithm);
    }
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    return account;
  }

  // ---------------------------------------------------------------------------
  // Update (general data only — PSD2 connection is out of scope, T3)
  // ---------------------------------------------------------------------------

  NeoResponse update(String id, JSONObject body) throws JSONException {
    if (StringUtils.isBlank(id)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing account id");
    }
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing request body");
    }
    FIN_FinancialAccount account = loadAccount(id);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Account not found");
    }

    String name = body.optString("name", "").trim();
    String currencyId = body.optString("currencyId", "").trim();
    String iban = body.optString("iban", "").trim();
    String swift = body.optString(FIELD_SWIFT_CODE, "").trim();

    if (StringUtils.isBlank(name)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is required");
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Name is too long");
    }
    if (iban.length() > IBAN_MAX_LENGTH || swift.length() > SWIFT_MAX_LENGTH) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "IBAN or BIC/SWIFT is too long");
    }
    if (nameExists(name, account.getId())) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "An account with this name already exists");
    }

    account.setName(name);
    if (StringUtils.isNotBlank(currencyId)) {
      Currency currency = loadCurrency(currencyId);
      if (currency == null) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid currency");
      }
      account.setCurrency(currency);
    }
    // Only touch IBAN / BIC when the caller sent the key, so editing the general
    // data (which omits BIC) does not wipe a stored value.
    if (body.has("iban")) {
      account.setIBAN(StringUtils.trimToNull(iban));
    }
    if (body.has(FIELD_SWIFT_CODE)) {
      account.setSwiftCode(StringUtils.trimToNull(swift));
    }
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();

    JSONObject data = new JSONObject();
    data.put("id", account.getId());
    data.put("name", account.getName());
    JSONObject responseData = new JSONObject();
    responseData.put(KEY_DATA, data);
    JSONObject envelope = new JSONObject();
    envelope.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(envelope);
  }

  // ---------------------------------------------------------------------------
  // Archive
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
  // Defaults
  // ---------------------------------------------------------------------------

  NeoResponse buildDefaults() throws JSONException {
    String orgId = OBContext.getOBContext().getCurrentOrganization().getId();
    Currency defaultCurrency = resolveDefaultCurrency(orgId);

    JSONObject data = new JSONObject();
    if (defaultCurrency != null) {
      data.put("defaultCurrencyId", defaultCurrency.getId());
      data.put("defaultCurrencyIso", StringUtils.trimToEmpty(defaultCurrency.getISOCode()));
    }

    JSONArray currencies = new JSONArray();
    for (Currency currency : listCurrencies()) {
      JSONObject entry = new JSONObject();
      entry.put("id", currency.getId());
      entry.put("iso", StringUtils.trimToEmpty(currency.getISOCode()));
      entry.put("symbol", StringUtils.trimToEmpty(currency.getSymbol()));
      currencies.put(entry);
    }
    data.put("currencies", currencies);

    JSONObject responseData = new JSONObject();
    responseData.put(KEY_DATA, data);
    JSONObject envelope = new JSONObject();
    envelope.put(KEY_RESPONSE, responseData);
    return NeoResponse.ok(envelope);
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
    return TYPE_CASH.equals(type) ? TYPE_CASH : TYPE_BANK;
  }

  Currency loadCurrency(String currencyId) {
    return OBDal.getInstance().get(Currency.class, currencyId);
  }

  FIN_FinancialAccount loadAccount(String accountId) {
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
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
    return !criteria.list().isEmpty();
  }

  Currency resolveDefaultCurrency(String orgId) {
    String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    return currencyId != null ? OBDal.getInstance().get(Currency.class, currencyId) : null;
  }

  List<Currency> listCurrencies() {
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(Currency.PROPERTY_ISOCODE, true);
    return criteria.list();
  }

  List<MatchingAlgorithm> listMatchingAlgorithms() {
    OBCriteria<MatchingAlgorithm> criteria =
        OBDal.getInstance().createCriteria(MatchingAlgorithm.class);
    criteria.add(Restrictions.eq(MatchingAlgorithm.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(MatchingAlgorithm.PROPERTY_NAME, true);
    return criteria.list();
  }
}
