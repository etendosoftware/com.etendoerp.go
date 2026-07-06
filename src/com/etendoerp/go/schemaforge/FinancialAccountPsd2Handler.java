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

import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.hibernate.criterion.Restrictions;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper.LinkAccountData;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * NeoHandler that bridges the Etendo Go Accounts UI to the existing PSD2 / Salt Edge integration
 * module ({@code com.etendoerp.psd2.bank.integration}) introduced by ETP-4097 (task T3).
 *
 * <p>The PSD2 module drives Salt Edge through classic Smart Client / SMF action handlers and a
 * server-side callback that renders its own HTML pages. The Etendo Go SPA cannot use those, so
 * this bridge re-exposes the same protocol as headless NEO actions that the SPA orchestrates,
 * reusing the PSD2 module's public helpers. The account selection and success UI are native to
 * the app-shell; the only browser popup is the Salt Edge bank login itself.
 *
 * <p>URL base: {@code /sws/neo/financial-account-psd2}. Actions (via {@code ?action=}):
 * <ul>
 *   <li>{@code GET status&financialAccountId=} — connection status for the Edit panel / kebab.</li>
 *   <li>{@code GET accounts&connectionId=&type=[&financialAccountId=]} — bank accounts found for a
 *       connection, filtered by FA type (and by the FA currency when an account id is given).</li>
 *   <li>{@code POST connect} — returns the Salt Edge connect URL. Same for both entry points; the
 *       account need not exist yet (case 2). The {@code return_to} is built server-side from the
 *       request {@code Origin} header so the popup returns to the SPA callback route.</li>
 *   <li>{@code POST link {financialAccountId, connectionId, saltEdgeAccountId}} — case 1: link the
 *       chosen Salt Edge account to an existing FA.</li>
 *   <li>{@code POST createAndLink {type, connectionId, saltEdgeAccountId}} — case 2: create the FA
 *       from the chosen Salt Edge account (name, currency) plus the chosen {@code type}, then link.</li>
 *   <li>{@code POST reconnect {financialAccountId}} — returns the Salt Edge reconnect URL.</li>
 *   <li>{@code POST disconnect {financialAccountId}} — unlinks (and deletes the Salt Edge
 *       connection when not shared).</li>
 *   <li>{@code POST sync {financialAccountId}} — imports bank statements for the account.</li>
 *   <li>{@code POST import-settings {financialAccountId, importFromDate?, importToDate?,
 *       statementGrouping?}} — updates the import configuration on the FA.</li>
 * </ul>
 */
@Named("financial-account-psd2")
public class FinancialAccountPsd2Handler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(FinancialAccountPsd2Handler.class);

  /**
   * Provider catalog cache keyed by {@code clientId|country}, so the bank picker does not hit the
   * Salt Edge middleware on every open. Bank lists change rarely, so a 24h TTL is ample; the value
   * is the serialized provider array.
   */
  private static final Cache<String, String> PROVIDERS_CACHE = CacheBuilder.newBuilder()
      .maximumSize(100)
      .expireAfterWrite(24, TimeUnit.HOURS)
      .build();

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";

  private static final String PARAM_ACTION = "action";
  private static final String PARAM_ACCOUNT_ID = "financialAccountId";
  private static final String PARAM_CONNECTION_ID = "connectionId";
  private static final String PARAM_SALT_EDGE_ACCOUNT_ID = "saltEdgeAccountId";
  private static final String PARAM_TYPE = "type";
  private static final String PARAM_COUNTRY = "country";
  private static final String PARAM_Q = "q";

  private static final String ACTION_STATUS = "status";
  private static final String ACTION_ACCOUNTS = "accounts";
  private static final String ACTION_PROVIDERS = "providers";
  private static final String ACTION_CONNECT = "connect";
  private static final String ACTION_LINK = "link";
  private static final String ACTION_CREATE_AND_LINK = "createAndLink";
  private static final String ACTION_RECONNECT = "reconnect";
  private static final String ACTION_DISCONNECT = "disconnect";
  private static final String ACTION_SYNC = "sync";
  private static final String ACTION_IMPORT_SETTINGS = "import-settings";

  /** SPA route the Salt Edge popup returns to; it relays the connection id and closes itself. */
  private static final String CALLBACK_PATH = "/financial-account/psd2-callback";

  // Response / body JSON keys (extracted to satisfy Sonar S1192).
  private static final String KEY_CONNECT_URL = "connectUrl";
  private static final String KEY_RECONNECT_URL = "reconnectUrl";
  private static final String KEY_ACCOUNTS = "accounts";
  private static final String KEY_SALT_EDGE_ACCOUNT_ID = "saltEdgeAccountId";
  private static final String KEY_NAME = "name";
  private static final String KEY_IBAN = "iban";
  private static final String KEY_CURRENCY = "currency";
  private static final String KEY_NATURE = "nature";
  private static final String KEY_WARNING = "warning";
  private static final String KEY_PROVIDER_NAME = "providerName";
  private static final String KEY_IMPORT_FROM_DATE = "importFromDate";
  private static final String KEY_IMPORT_TO_DATE = "importToDate";
  private static final String KEY_STATEMENT_GROUPING = "statementGrouping";
  private static final String KEY_PROVIDER_LOGO = "providerLogoUrl";
  private static final String KEY_LOGO_URL = "logo_url";
  private static final String KEY_DATA = "data";
  private static final String KEY_CODE = "code";
  private static final String KEY_PROVIDERS = "providers";
  private static final String DEFAULT_PROVIDER_COUNTRY = "ES";
  private static final String MSG_ACCOUNT_NOT_FOUND = "Financial account not found";
  private static final String MSG_MISSING = "Missing required parameter: ";

  @Override
  public NeoResponse handle(NeoContext context) {
    String method = context.getHttpMethod();
    String action = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_ACTION)
        : null;
    try {
      OBContext.setAdminMode(true);
      if (METHOD_GET.equals(method)) {
        if (ACTION_STATUS.equals(action)) {
          return handleStatus(context);
        }
        if (ACTION_ACCOUNTS.equals(action)) {
          return handleAccounts(context);
        }
        if (ACTION_PROVIDERS.equals(action)) {
          return handleProviders(context);
        }
        return NeoResponse.error(400, MSG_MISSING + PARAM_ACTION);
      }
      if (METHOD_POST.equals(method)) {
        return handlePost(action, context);
      }
      return NeoResponse.error(405, "Method not allowed.");
    } catch (OBException e) {
      doRollbackAndClose();
      log.warn("financial-account-psd2 business error ({}): {}", action, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      doRollbackAndClose();
      log.error("financial-account-psd2 error ({})", action, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse handlePost(String action, NeoContext context) throws JSONException {
    if (ACTION_CONNECT.equals(action)) {
      return handleConnect(context);
    }
    if (ACTION_LINK.equals(action)) {
      return handleLink(context);
    }
    if (ACTION_CREATE_AND_LINK.equals(action)) {
      return handleCreateAndLink(context);
    }
    if (ACTION_RECONNECT.equals(action)) {
      return handleReconnect(context);
    }
    if (ACTION_DISCONNECT.equals(action)) {
      return handleDisconnect(context);
    }
    if (ACTION_SYNC.equals(action)) {
      return handleSync(context);
    }
    if (ACTION_IMPORT_SETTINGS.equals(action)) {
      return handleImportSettings(context);
    }
    return NeoResponse.error(400, MSG_MISSING + PARAM_ACTION);
  }

  // ---------------------------------------------------------------------------
  // GET status
  // ---------------------------------------------------------------------------

  private NeoResponse handleStatus(NeoContext context) throws JSONException {
    String accountId = FinancialAccountPsd2Support.queryParam(context, PARAM_ACCOUNT_ID);
    FIN_FinancialAccount finAcc = loadAccount(accountId);
    if (finAcc == null) {
      return NeoResponse.error(404, MSG_ACCOUNT_NOT_FOUND);
    }
    FinaccConnection connection = SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc);
    JSONObject data = new JSONObject();
    boolean connected = BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED
        .equals(finAcc.getPSD2ConnectionStatus());
    data.put("connected", connected);
    data.put(KEY_SALT_EDGE_ACCOUNT_ID, finAcc.getPSD2SaltEdgeAccountID());
    data.put("maskedPan", finAcc.getPSD2CardNumber());
    data.put(KEY_IMPORT_FROM_DATE,
        FinancialAccountPsd2Support.formatDate(finAcc.getPSD2ImportFromDate()));
    data.put(KEY_IMPORT_TO_DATE,
        FinancialAccountPsd2Support.formatDate(finAcc.getPSD2ImportToDate()));
    data.put(KEY_STATEMENT_GROUPING, finAcc.getPSD2StatementFrequency());
    if (connection != null) {
      Date expiresAt = connection.getConsentExpiresAt();
      data.put(ACTION_STATUS, connection.getConnectionStatus());
      data.put(KEY_PROVIDER_NAME, connection.getProviderName());
      data.put("scopes", connection.getFetchScopes());
      data.put("consentExpiresAt", FinancialAccountPsd2Support.formatInstant(expiresAt));
      data.put("daysUntilExpires", FinancialAccountPsd2Support.daysUntil(expiresAt));
    }
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // GET accounts (found bank accounts for a connection, filtered for the SPA modal)
  // ---------------------------------------------------------------------------

  private NeoResponse handleAccounts(NeoContext context) throws JSONException {
    String connectionId = FinancialAccountPsd2Support.queryParam(context, PARAM_CONNECTION_ID);
    String type = FinancialAccountPsd2Support.queryParam(context, PARAM_TYPE);
    String accountId = FinancialAccountPsd2Support.queryParam(context, PARAM_ACCOUNT_ID);
    if (StringUtils.isBlank(connectionId)) {
      return NeoResponse.error(400, MSG_MISSING + PARAM_CONNECTION_ID);
    }

    FIN_FinancialAccount finAcc = StringUtils.isNotBlank(accountId) ? loadAccount(accountId) : null;
    String apiKey = finAcc != null
        ? SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc)
        : BankIntegrationUtils.getPsd2ApiKey(currentClient());
    String faType = finAcc != null ? finAcc.getType() : StringUtils.defaultIfBlank(type,
        BankIntegrationConstants.FA_TYPE_BANK);

    JSONArray accounts = BankIntegrationUtils.getSaltEdgeAccountsForConnection(connectionId, apiKey);
    accounts = SaltEdgeAccountLinkHelper.filterAccountsByFAType(accounts, faType);
    accounts = SaltEdgeAccountLinkHelper.filterUnlinkedAccounts(accounts,
        finAcc != null ? finAcc.getId() : null);
    if (finAcc != null) {
      // Case 1: the FA already has a currency — only its matching accounts are linkable.
      accounts = SaltEdgeAccountLinkHelper.filterAccountsByCurrency(accounts, finAcc);
    }

    JSONArray out = new JSONArray();
    for (int i = 0; i < accounts.length(); i++) {
      JSONObject node = accounts.optJSONObject(i);
      if (node == null) {
        continue;
      }
      JSONObject row = new JSONObject();
      row.put(KEY_SALT_EDGE_ACCOUNT_ID, node.optString(BankIntegrationConstants.ID, ""));
      row.put(KEY_NAME, node.optString(BankIntegrationConstants.NAME, ""));
      row.put(KEY_CURRENCY, node.optString(BankIntegrationConstants.CURRENCY_CODE, ""));
      row.put(KEY_NATURE, node.optString(BankIntegrationConstants.NATURE, ""));
      row.put(KEY_IBAN, FinancialAccountPsd2Support.ibanOf(node));
      out.put(row);
    }
    JSONObject data = new JSONObject();
    data.put(KEY_ACCOUNTS, out);
    // Include the bank/provider name so the selection modal can show which bank these
    // accounts belong to. Only resolved when there is something to select.
    if (out.length() > 0) {
      JSONObject details = BankIntegrationUtils.getSaltEdgeConnectionDetails(connectionId, apiKey);
      data.put(KEY_PROVIDER_NAME, details.optString(BankIntegrationConstants.PROVIDER_NAME, ""));
      String providerCode = details.optString(BankIntegrationConstants.PROVIDER_CODE, "");
      if (StringUtils.isNotBlank(providerCode)) {
        data.put(KEY_PROVIDER_LOGO,
            FinancialAccountPsd2Support.fetchProviderLogo(providerCode, apiKey));
      }
    }
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // GET providers (Salt Edge bank catalog by country, for the bank picker)
  // ---------------------------------------------------------------------------

  private NeoResponse handleProviders(NeoContext context) throws JSONException {
    String country = StringUtils.defaultIfBlank(
        FinancialAccountPsd2Support.queryParam(context, PARAM_COUNTRY),
        DEFAULT_PROVIDER_COUNTRY);
    String q = StringUtils.trimToNull(FinancialAccountPsd2Support.queryParam(context, PARAM_Q));

    JSONArray all = cachedProviders(country);
    JSONArray providers = new JSONArray();
    for (int i = 0; i < all.length(); i++) {
      JSONObject row = all.optJSONObject(i);
      boolean matchesQuery = q == null || StringUtils.containsIgnoreCase(
          row != null ? row.optString(KEY_NAME, "") : "", q);
      if (row != null && matchesQuery) {
        providers.put(row);
      }
    }

    JSONObject out = new JSONObject();
    out.put(KEY_PROVIDERS, providers);
    out.put(PARAM_COUNTRY, country);
    return FinancialAccountPsd2Support.okData(out);
  }

  /**
   * Returns the full Salt Edge provider catalog for a country, cached per client+country with a
   * 24h TTL so the bank picker does not hit the middleware on every open. The free-text filter is
   * applied by the caller (not cached). Empty results are NOT cached, so a transient middleware
   * outage (or a not-yet-configured API key) retries on the next request instead of caching a hole.
   */
  private JSONArray cachedProviders(String country) throws JSONException {
    String cacheKey = currentClient().getId() + "|" + country;
    String cached = PROVIDERS_CACHE.getIfPresent(cacheKey);
    if (cached != null) {
      return new JSONArray(cached);
    }
    JSONArray fetched = fetchProvidersFromMiddleware(country);
    if (fetched.length() > 0) {
      PROVIDERS_CACHE.put(cacheKey, fetched.toString());
    }
    return fetched;
  }

  /**
   * Fetches and maps the provider catalog for a country from the Salt Edge middleware. Returns an
   * empty array when the client has no PSD2 API key (the SPA then falls back to its static list).
   */
  private JSONArray fetchProvidersFromMiddleware(String country) throws JSONException {
    JSONArray providers = new JSONArray();
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(currentClient());
    if (StringUtils.isBlank(apiKey)) {
      return providers;
    }
    String endpoint = BankIntegrationConstants.SALT_EDGE_MIDDLEWARE_URL
        + "providers?include_ais_fields=true&exclude_inactive=true&per_page=1000&country_code=" + country;
    JSONObject response = BankIntegrationUtils.makeSaltEdgeRequest("GET", null, endpoint, apiKey);
    JSONArray data = response.optJSONArray(KEY_DATA);
    if (data != null) {
      for (int i = 0; i < data.length(); i++) {
        JSONObject p = data.optJSONObject(i);
        if (p == null) {
          continue;
        }
        String code = p.optString(KEY_CODE, "");
        String name = p.optString(KEY_NAME, "");
        if (!StringUtils.isBlank(code) && !StringUtils.isBlank(name)) {
          JSONObject row = new JSONObject();
          row.put(KEY_CODE, code);
          row.put(KEY_NAME, name);
          row.put("logoUrl", p.optString(KEY_LOGO_URL, ""));
          providers.put(row);
        }
      }
    }
    return providers;
  }

  // ---------------------------------------------------------------------------
  // POST connect (returns the Salt Edge connect URL; works for both cases)
  // ---------------------------------------------------------------------------

  private NeoResponse handleConnect(NeoContext context) throws JSONException {
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(currentClient());
    String returnTo = resolveAppShellOrigin() + CALLBACK_PATH;
    // Case 1 (existing account): if the account already knows its bank — e.g. the provider was
    // chosen when it was created offline — preselect it so the Salt Edge widget skips the bank
    // picker and opens that provider's login directly. Otherwise show the full provider selection.
    String accountId = FinancialAccountPsd2Support.bodyString(
        context.getRequestBody(), PARAM_ACCOUNT_ID);
    Provider provider = null;
    if (accountId != null) {
      FIN_FinancialAccount finAcc = loadAccount(accountId);
      if (finAcc != null) {
        provider = finAcc.getPsd2Provider();
      }
    }
    // provider may be null (no bank remembered) → buildAndConnect shows the full provider picker.
    String connectUrl = BankIntegrationUtils.createSaltEdgeConnection(apiKey, returnTo, provider);
    JSONObject data = new JSONObject();
    data.put(KEY_CONNECT_URL, connectUrl);
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // POST link (case 1 — existing FA)
  // ---------------------------------------------------------------------------

  private NeoResponse handleLink(NeoContext context) throws JSONException {
    JSONObject body = FinancialAccountPsd2Support.requireBody(context);
    String accountId = FinancialAccountPsd2Support.bodyString(body, PARAM_ACCOUNT_ID);
    String connectionId = FinancialAccountPsd2Support.bodyString(body, PARAM_CONNECTION_ID);
    String saltEdgeAccountId = FinancialAccountPsd2Support.bodyString(
        body, PARAM_SALT_EDGE_ACCOUNT_ID);
    if (StringUtils.isAnyBlank(accountId, connectionId, saltEdgeAccountId)) {
      return NeoResponse.error(400, MSG_MISSING + "financialAccountId/connectionId/saltEdgeAccountId");
    }
    FIN_FinancialAccount finAcc = loadAccount(accountId);
    if (finAcc == null) {
      return NeoResponse.error(404, MSG_ACCOUNT_NOT_FOUND);
    }
    String apiKey = SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc);
    JSONArray accounts = BankIntegrationUtils.getSaltEdgeAccountsForConnection(connectionId, apiKey);
    JSONObject node = FinancialAccountPsd2Support.findAccountNode(accounts, saltEdgeAccountId);
    if (node == null) {
      return NeoResponse.error(404, "Selected bank account not found in the connection");
    }
    JSONObject details = BankIntegrationUtils.getSaltEdgeConnectionDetails(connectionId, apiKey);
    String warning = linkAccount(finAcc, connectionId, saltEdgeAccountId, node, details, apiKey);
    JSONObject data = new JSONObject();
    data.put("linked", true);
    data.put(PARAM_ACCOUNT_ID, finAcc.getId());
    data.put(KEY_WARNING, warning);
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // POST createAndLink (case 2 — create the FA from the chosen Salt Edge account, then link)
  // ---------------------------------------------------------------------------

  private NeoResponse handleCreateAndLink(NeoContext context) throws JSONException {
    JSONObject body = FinancialAccountPsd2Support.requireBody(context);
    String type = FinancialAccountPsd2Support.bodyString(body, PARAM_TYPE);
    String connectionId = FinancialAccountPsd2Support.bodyString(body, PARAM_CONNECTION_ID);
    String saltEdgeAccountId = FinancialAccountPsd2Support.bodyString(
        body, PARAM_SALT_EDGE_ACCOUNT_ID);
    if (StringUtils.isAnyBlank(type, connectionId, saltEdgeAccountId)) {
      return NeoResponse.error(400, MSG_MISSING + "type/connectionId/saltEdgeAccountId");
    }

    String apiKey = BankIntegrationUtils.getPsd2ApiKey(currentClient());
    JSONArray accounts = BankIntegrationUtils.getSaltEdgeAccountsForConnection(connectionId, apiKey);
    JSONObject node = FinancialAccountPsd2Support.findAccountNode(accounts, saltEdgeAccountId);
    if (node == null) {
      return NeoResponse.error(404, "Selected bank account not found in the connection");
    }

    String currencyCode = node.optString(BankIntegrationConstants.CURRENCY_CODE, "");
    Currency currency = FinancialAccountSupport.findCurrencyByIsoCode(currencyCode);
    if (currency == null) {
      return NeoResponse.error(400, "Unsupported currency: " + currencyCode);
    }
    JSONObject details = BankIntegrationUtils.getSaltEdgeConnectionDetails(connectionId, apiKey);
    String name = FinancialAccountPsd2Support.connectedAccountName(
        details.optString(BankIntegrationConstants.PROVIDER_NAME, null), node, currencyCode);

    FIN_FinancialAccount finAcc = FinancialAccountSupport.createAccount(currentClient(),
        OBContext.getOBContext().getCurrentOrganization(), currency, name, type);
    // Mirrors the manual "sin conexión" creation flow (FinancialAccountHandler.afterHandle):
    // a Salt Edge-created account must also come pre-wired with the payment methods that
    // correspond to its type, with one marked as default.
    FinancialAccountSupport.assignDefaultPaymentMethods(finAcc);

    String warning = linkAccount(finAcc, connectionId, saltEdgeAccountId, node, details, apiKey);
    JSONObject data = new JSONObject();
    data.put(PARAM_ACCOUNT_ID, finAcc.getId());
    data.put(KEY_NAME, finAcc.getName());
    data.put(KEY_WARNING, warning);
    return NeoResponse.createdWithData(data);
  }

  // ---------------------------------------------------------------------------
  // POST reconnect
  // ---------------------------------------------------------------------------

  private NeoResponse handleReconnect(NeoContext context) throws JSONException {
    JSONObject body = FinancialAccountPsd2Support.requireBody(context);
    String accountId = FinancialAccountPsd2Support.bodyString(body, PARAM_ACCOUNT_ID);
    FIN_FinancialAccount finAcc = loadAccount(accountId);
    if (finAcc == null) {
      return NeoResponse.error(404, MSG_ACCOUNT_NOT_FOUND);
    }
    FinaccConnection connection = anyConnectionFor(finAcc);
    if (connection == null) {
      return NeoResponse.error(400, "No connection to reconnect for this account");
    }
    String apiKey = SaltEdgeAccountLinkHelper.getApiKeyForFinAcc(finAcc);
    String returnTo = resolveAppShellOrigin() + CALLBACK_PATH;
    JSONArray scopes = FinancialAccountPsd2Support.defaultFetchScopes();
    String reconnectUrl = BankIntegrationUtils.reconnectSaltEdgeConnection(
        connection.getSaltEdgeConnection(), apiKey, returnTo, scopes);
    JSONObject data = new JSONObject();
    data.put(KEY_RECONNECT_URL, reconnectUrl);
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // POST disconnect
  // ---------------------------------------------------------------------------

  private NeoResponse handleDisconnect(NeoContext context) throws JSONException {
    JSONObject body = FinancialAccountPsd2Support.requireBody(context);
    String accountId = FinancialAccountPsd2Support.bodyString(body, PARAM_ACCOUNT_ID);
    FIN_FinancialAccount finAcc = loadAccount(accountId);
    if (finAcc == null) {
      return NeoResponse.error(404, MSG_ACCOUNT_NOT_FOUND);
    }
    boolean disconnected = SaltEdgeAccountLinkHelper.disconnectFinancialAccount(finAcc);
    JSONObject data = new JSONObject();
    data.put("disconnected", disconnected);
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // POST sync (import bank statements)
  // ---------------------------------------------------------------------------

  private NeoResponse handleSync(NeoContext context) throws JSONException {
    JSONObject body = FinancialAccountPsd2Support.requireBody(context);
    String accountId = FinancialAccountPsd2Support.bodyString(body, PARAM_ACCOUNT_ID);
    FIN_FinancialAccount finAcc = loadAccount(accountId);
    if (finAcc == null) {
      return NeoResponse.error(404, MSG_ACCOUNT_NOT_FOUND);
    }
    StringBuilder messages = new StringBuilder();
    // Single source of truth: the same per-account fetch behind the Classic "Get Bank Statement"
    // button (GetTransactions action) — both delegate to fetchAccountTransactions.
    String status = SaltEdgeAccountLinkHelper.fetchAccountTransactions(finAcc, messages);
    JSONObject data = new JSONObject();
    data.put(ACTION_STATUS, status);
    data.put("message", messages.toString().trim());
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // POST import-settings
  // ---------------------------------------------------------------------------

  private NeoResponse handleImportSettings(NeoContext context) throws JSONException {
    JSONObject body = FinancialAccountPsd2Support.requireBody(context);
    String accountId = FinancialAccountPsd2Support.bodyString(body, PARAM_ACCOUNT_ID);
    FIN_FinancialAccount finAcc = loadAccount(accountId);
    if (finAcc == null) {
      return NeoResponse.error(404, MSG_ACCOUNT_NOT_FOUND);
    }
    if (body.has(KEY_IMPORT_FROM_DATE)) {
      finAcc.setPSD2ImportFromDate(FinancialAccountPsd2Support.parseDate(
          FinancialAccountPsd2Support.bodyString(body, KEY_IMPORT_FROM_DATE)));
    }
    if (body.has(KEY_IMPORT_TO_DATE)) {
      finAcc.setPSD2ImportToDate(FinancialAccountPsd2Support.parseDate(
          FinancialAccountPsd2Support.bodyString(body, KEY_IMPORT_TO_DATE)));
    }
    if (body.has(KEY_STATEMENT_GROUPING)) {
      finAcc.setPSD2StatementFrequency(
          FinancialAccountPsd2Support.bodyString(body, KEY_STATEMENT_GROUPING));
    }
    OBDal.getInstance().save(finAcc);
    OBDal.getInstance().flush();
    JSONObject data = new JSONObject();
    data.put("saved", true);
    return FinancialAccountPsd2Support.okData(data);
  }

  // ---------------------------------------------------------------------------
  // Shared linking + helpers
  // ---------------------------------------------------------------------------

  private String linkAccount(FIN_FinancialAccount finAcc, String connectionId,
      String saltEdgeAccountId, JSONObject node, JSONObject details, String apiKey) {
    String providerCode = details.optString(BankIntegrationConstants.PROVIDER_CODE, null);
    String providerName = details.optString(BankIntegrationConstants.PROVIDER_NAME, null);
    LinkAccountData data = new LinkAccountData(
        providerCode,
        providerName,
        FinancialAccountPsd2Support.extractFetchScopes(details),
        details.optString(BankIntegrationConstants.STATUS, null),
        SaltEdgeAccountLinkHelper.resolveConsentExpiresAt(details, apiKey));
    String warning = SaltEdgeAccountLinkHelper.linkAccountToFinancialAccount(finAcc, saltEdgeAccountId,
        connectionId, node, data);

    // Mirror the classic AisConnectionCallback flow: resolve (or fetch + register) the bank
    // Provider and set it on the financial account. Without this the "Bank Provider" field stays
    // empty for accounts connected from the SPA, unlike the classic connect flow. See ETP-4097.
    com.etendoerp.psd2.bank.integration.data.Provider provider =
        FinancialAccountPsd2Support.resolveProvider(providerCode, providerName, apiKey);
    if (provider != null) {
      finAcc.setPsd2Provider(provider);
      OBDal.getInstance().save(finAcc);
    }
    return warning;
  }

  /**
   * Builds the app-shell origin the Salt Edge popup must return to, from the request {@code Origin}
   * header (falling back to {@code Referer}). The popup must land on the same origin that opened
   * it so the {@code /financial-account/psd2-callback} route can relay the connection id back to
   * the opener; therefore the origin is read from the request, never supplied by the client body.
   */
  private String resolveAppShellOrigin() {
    HttpServletRequest request = RequestContext.get() != null
        ? RequestContext.get().getRequest() : null;
    if (request == null) {
      throw new OBException("Cannot resolve the application origin for the PSD2 callback");
    }
    String origin = StringUtils.trimToNull(request.getHeader("Origin"));
    if (origin == null) {
      origin = FinancialAccountPsd2Support.originFromReferer(request.getHeader("Referer"));
    }
    if (origin == null) {
      throw new OBException("Missing Origin header for the PSD2 callback");
    }
    return StringUtils.removeEnd(origin, "/");
  }

  private FinaccConnection anyConnectionFor(FIN_FinancialAccount finAcc) {
    OBCriteria<FinaccConnection> criteria = OBDal.getInstance().createCriteria(FinaccConnection.class);
    criteria.add(Restrictions.eq(FinaccConnection.PROPERTY_FINANCIALACCOUNT, finAcc));
    criteria.addOrderBy(FinaccConnection.PROPERTY_CREATIONDATE, false);
    criteria.setMaxResults(1);
    return (FinaccConnection) criteria.uniqueResult();
  }

  private Client currentClient() {
    return OBContext.getOBContext().getCurrentClient();
  }

  FIN_FinancialAccount loadAccount(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return null;
    }
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
  }

  void doRollbackAndClose() {
    OBDal.getInstance().rollbackAndClose();
  }
}
