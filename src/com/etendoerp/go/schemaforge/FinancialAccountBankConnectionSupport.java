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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

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
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;

final class FinancialAccountBankConnectionSupport {

  private static final Logger log = LogManager.getLogger(FinancialAccountBankConnectionSupport.class);

  private static final String KEY_DATA = "data";
  private static final String KEY_LOGO_URL = "logo_url";
  private static final int NAME_MAX_LENGTH = 60;
  private static final String NAME_SEPARATOR = " - ";

  private FinancialAccountBankConnectionSupport() {
  }

  static String extractFetchScopes(JSONObject details) {
    JSONObject lastAttempt = details.optJSONObject(BankIntegrationConstants.LAST_ATTEMPT);
    if (lastAttempt != null) {
      return lastAttempt.optString(BankIntegrationConstants.FETCH_SCOPES, "");
    }
    return "";
  }

  static Provider resolveProvider(String providerCode, String providerName, String apiKey) {
    if (StringUtils.isBlank(providerCode)) {
      return null;
    }
    Provider existing = findProviderByCode(providerCode);
    return existing != null ? existing : fetchAndRegisterProvider(providerCode, providerName, apiKey);
  }

  private static Provider findProviderByCode(String code) {
    if (StringUtils.isBlank(code)) {
      return null;
    }
    try {
      OBContext.setAdminMode();
      OBCriteria<Provider> criteria = OBDal.getInstance().createCriteria(Provider.class);
      criteria.add(Restrictions.eq(Provider.PROPERTY_PROVIDERCODE, code));
      criteria.setMaxResults(1);
      return (Provider) criteria.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static Provider fetchAndRegisterProvider(String providerCode, String providerName,
      String apiKey) {
    String name = StringUtils.defaultIfBlank(providerName, providerCode);
    BigDecimal maxFetchInterval = BigDecimal.valueOf(90);
    // The provider details response already carries logo_url alongside name/max_fetch_interval —
    // reading it here means the logo is persisted the moment an account connects, without
    // waiting for the scheduled catalog sync (SyncBankProviders, in the psd2 module) to run.
    String logoUrl = null;
    try {
      String endpoint = BankIntegrationConstants.SALT_EDGE_MIDDLEWARE_URL + "providers/" + providerCode
          + "?include_ais_fields=true&include_pis_fields=true&include_credentials_fields=false"
          + "&include_sandboxes=" + BankIntegrationUtils.isFakeProvidersEnabled();
      JSONObject response = BankIntegrationUtils.makeSaltEdgeRequest("GET", null, endpoint, apiKey);
      JSONObject data = response.optJSONObject(BankIntegrationConstants.DATA);
      if (data != null) {
        name = data.optString(BankIntegrationConstants.NAME, providerCode);
        long interval = data.optLong(BankIntegrationConstants.MAX_FETCH_INTERVAL, 0);
        if (interval > 0) {
          maxFetchInterval = BigDecimal.valueOf(interval);
        }
        logoUrl = data.optString(BankIntegrationConstants.LOGO_URL, null);
      }
    } catch (Exception e) {
      log.warn("Could not fetch provider {} from Salt Edge, registering with fallback values: {}",
          providerCode, e.getMessage());
    }
    return ProviderCatalogUtils.upsertProvider(providerCode, name, maxFetchInterval, logoUrl);
  }

  static String connectedAccountName(String providerName, JSONObject node, String currencyCode) {
    String accountName = StringUtils.trimToEmpty(node.optString(BankIntegrationConstants.NAME, ""));
    boolean hasProvider = StringUtils.isNotBlank(providerName);
    if (hasProvider && StringUtils.isNotBlank(accountName)) {
      return combineWithinLimit(providerName, accountName);
    }
    if (hasProvider) {
      return truncateToLimit(providerName);
    }
    if (StringUtils.isNotBlank(accountName)) {
      return truncateToLimit(accountName);
    }
    return truncateToLimit(currencyCode + " account");
  }

  /**
   * Builds "{providerName} - {accountName}" bounded to {@link #NAME_MAX_LENGTH}. The account
   * name/IBAN is the more uniquely identifying part (Salt Edge often returns the IBAN itself as
   * the account name), so the provider name is the one trimmed first when the combination is too
   * long; if the account name alone still doesn't fit, it is truncated too.
   */
  private static String combineWithinLimit(String providerName, String accountName) {
    int available = NAME_MAX_LENGTH - NAME_SEPARATOR.length() - accountName.length();
    if (available < 1) {
      return truncateToLimit(accountName);
    }
    String trimmedProvider = providerName.length() > available ? providerName.substring(0, available)
        : providerName;
    return trimmedProvider + NAME_SEPARATOR + accountName;
  }

  private static String truncateToLimit(String value) {
    return value.length() > NAME_MAX_LENGTH ? value.substring(0, NAME_MAX_LENGTH) : value;
  }

  static String fetchProviderLogo(String providerCode, String apiKey) {
    try {
      String endpoint = BankIntegrationConstants.SALT_EDGE_MIDDLEWARE_URL + "providers/" + providerCode;
      JSONObject response = BankIntegrationUtils.makeSaltEdgeRequest("GET", null, endpoint, apiKey);
      JSONObject data = response.optJSONObject(KEY_DATA);
      return data != null ? data.optString(KEY_LOGO_URL, "") : "";
    } catch (Exception e) {
      log.warn("Could not fetch provider logo for {}: {}", providerCode, e.getMessage());
      return "";
    }
  }

  static JSONObject findAccountNode(JSONArray accounts, String saltEdgeAccountId) {
    for (int i = 0; i < accounts.length(); i++) {
      JSONObject node = accounts.optJSONObject(i);
      if (node != null && StringUtils.equals(saltEdgeAccountId,
          node.optString(BankIntegrationConstants.ID, ""))) {
        return node;
      }
    }
    return null;
  }

  static String ibanOf(JSONObject node) {
    JSONObject extra = node.optJSONObject("extra");
    return extra != null ? extra.optString(BankIntegrationConstants.IBAN, "") : "";
  }

  static JSONArray defaultFetchScopes() {
    JSONArray scopes = new JSONArray();
    scopes.put("accounts");
    scopes.put("balance");
    scopes.put(BankIntegrationConstants.TRANSACTIONS);
    scopes.put(BankIntegrationConstants.HOLDER_INFO);
    return scopes;
  }

  static String originFromReferer(String referer) {
    if (StringUtils.isBlank(referer)) {
      return null;
    }
    int schemeEnd = referer.indexOf("://");
    if (schemeEnd < 0) {
      return null;
    }
    int pathStart = referer.indexOf('/', schemeEnd + 3);
    return pathStart < 0 ? referer : referer.substring(0, pathStart);
  }

  static NeoResponse okData(JSONObject data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    JSONObject envelope = new JSONObject();
    envelope.put("response", responseData);
    return NeoResponse.ok(envelope);
  }

  static String queryParam(NeoContext context, String key) {
    Map<String, String> params = context.getQueryParams();
    return params != null ? params.get(key) : null;
  }

  static JSONObject requireBody(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      throw new OBException("Missing request body");
    }
    return body;
  }

  static String bodyString(JSONObject body, String key) {
    if (body == null || !body.has(key) || body.isNull(key)) {
      return null;
    }
    return StringUtils.trimToNull(body.optString(key, ""));
  }

  static Date parseDate(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    return Date.from(LocalDate.parse(value.trim()).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  /**
   * True when both import bounds are set and the "from" date is strictly after the "to" date.
   *
   * The SPA blocks this range before it can be saved (ETP-5104), so reaching here means an
   * API/MCP caller bypassed the form. Guarding at the bridge matters because an inverted range is
   * accepted silently by the setters and only fails much later, on the next synchronization:
   * {@code SaltEdgeConnectionHelper.validateDateRange} throws inside
   * {@code processProviderTransactions}, whose catch re-wraps it into
   * {@code PSD2_ErrorRetrievingRransactionsForTheAccount} — an error the user cannot act on,
   * reported far from the setting that caused it.
   *
   * A null bound means "no limit" and is always valid; a range where both ends are the same day is
   * valid too, matching the PSD2 module's own {@code compareTo(...) > 0} test.
   */
  static boolean isImportRangeInvalid(Date importFrom, Date importTo) {
    return importFrom != null && importTo != null && importFrom.compareTo(importTo) > 0;
  }

  static String formatDate(Date date) {
    if (date == null) {
      return null;
    }
    return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  static String formatInstant(Date date) {
    return date != null ? date.toInstant().toString() : null;
  }

  static Long daysUntil(Date date) {
    if (date == null) {
      return null;
    }
    return ChronoUnit.DAYS.between(Instant.now(), date.toInstant());
  }
}
