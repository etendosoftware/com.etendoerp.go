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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;
import com.etendoerp.psd2.bank.integration.utils.SaltEdgeAccountLinkHelper;

final class FinancialAccountBankConnectionSupport {

  private static final Logger log = LogManager.getLogger(FinancialAccountBankConnectionSupport.class);

  private static final String KEY_DATA = "data";
  private static final String KEY_LOGO_URL = "logo_url";
  /** Salt Edge middleware path prefix of the provider details endpoint ({@code providers/{code}}). */
  private static final String SALT_EDGE_PROVIDERS_PATH = "providers/";
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

  /**
   * Looks up a {@link Provider} by its Salt Edge code (admin mode, first match), or {@code null}
   * when the code is blank or unknown. Package-visible so {@link FinancialAccountHandler} can
   * check for an existing catalog row before an offline create writes a logo (ETP-5521).
   */
  static Provider findProviderByCode(String code) {
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

  /**
   * The maximum number of days of history the connection's provider declares it will serve, or
   * null when there is no limit worth advising against.
   *
   * Returns an {@code Integer}, not the DAL's raw {@code BigDecimal}, on purpose: the column is
   * {@code DECIMAL(10,0)} so the value is whole days by definition, and the conversion belongs
   * here — once, beside the guards that already validate it — rather than in every caller. A
   * caller that forgot it would serialize {@code 90.0}, which jettison's {@code getInt()}
   * silently unwraps, so the mistake would pass an int-shaped assertion and only surface as
   * "90.0 días" in the UI.
   *
   * Resolved from the CONNECTION's {@code providerCode}, deliberately NOT from
   * {@code FIN_FinancialAccount.psd2Provider}. The FK is provider *memory*: it is written when an
   * account is created offline with a bank chosen, and it survives a reconnect to a different
   * bank until the account is relinked. Reading it here would let the field advisory name a
   * different number than the warning the synchronization prints — the very class of bug this is
   * meant to close. Going through the provider code reproduces exactly what
   * {@code SaltEdgeConnectionHelper.findProviderMaxFetchInterval} does at sync time, so the two
   * can never disagree.
   *
   * The guards below mirror {@code getProviderCodesForAccount}'s own filters, cheapest first, so
   * a connection that could not produce a warning anyway costs no query at all.
   *
   * A null return means "say nothing". It is never coerced to 90: the fallback in
   * {@link #fetchAndRegisterProvider} already invents a 90 when the provider details call fails,
   * and a second invented default would make an undeclared limit indistinguishable from a real
   * one.
   */
  static Integer maxFetchIntervalOf(FinaccConnection connection) {
    if (connection == null) {
      return null;
    }
    // Boxed Boolean on the DAL getter, so an explicit TRUE test — not a bare negation, which
    // would NPE on a null column.
    if (!Boolean.TRUE.equals(connection.isHandlesTransactions())) {
      return null;
    }
    return maxFetchIntervalOfProvider(connection.getProviderCode());
  }

  /**
   * The maximum number of days of history the provider with this Salt Edge code declares, read
   * from its {@code PSD2_PROVIDER.MAX_FETCH_INTERVAL} row, or null when the code is blank, the
   * provider is not registered yet, or it declares no limit. Same "say nothing" contract and the
   * same {@code Integer} conversion as {@link #maxFetchIntervalOf}, which delegates here once its
   * connection guards pass.
   *
   * <p>ETP-6151: also used by {@code GET accounts}, where no {@link FinaccConnection} exists yet —
   * only the provider code of the Salt Edge connection being linked — so the selection modal can
   * warn about the history limit before the user confirms the link.
   *
   * @param providerCode the Salt Edge provider code, possibly blank
   * @return the limit in whole days, or {@code null} when there is nothing to advise
   */
  static Integer maxFetchIntervalOfProvider(String providerCode) {
    if (StringUtils.isBlank(providerCode)) {
      return null;
    }
    Provider provider = findProviderByCode(providerCode);
    if (provider == null) {
      return null;
    }
    BigDecimal maxFetchInterval = provider.getMaxFetchInterval();
    // A stored 0 means "not declared", never a zero-day limit — same test the PSD2 module applies
    // before it warns.
    if (maxFetchInterval == null || maxFetchInterval.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return maxFetchInterval.intValue();
  }

  /**
   * Total form of {@link #maxFetchIntervalOfProvider} for {@code GET accounts} (ETP-6151, through
   * {@link #fetchProviderDisplay}): the history limit there is a non-critical extra, like the
   * provider logo, so a failed lookup is logged and answered as "unknown" instead of failing the
   * account list the user is choosing from.
   *
   * @param providerCode the Salt Edge provider code, possibly blank
   * @return the limit in whole days, or {@code null} when unknown or the lookup failed
   */
  static Integer maxFetchIntervalOfProviderOrNull(String providerCode) {
    try {
      return maxFetchIntervalOfProvider(providerCode);
    } catch (Exception e) {
      log.warn("Could not resolve the max fetch interval of provider {}: {}", providerCode,
          e.getMessage());
      return null;
    }
  }

  /** An import-from date older than the bank's history: the limit in days and the earliest usable day. */
  static final class FetchIntervalExceeded {
    private final int days;
    private final LocalDate earliestDate;

    FetchIntervalExceeded(int days, LocalDate earliestDate) {
      this.days = days;
      this.earliestDate = earliestDate;
    }

    int getDays() {
      return days;
    }

    LocalDate getEarliestDate() {
      return earliestDate;
    }
  }

  /**
   * ETP-5805. The single rule behind the "this bank only provides the last N days" advisory,
   * shared by the PUT/PATCH hook and {@code POST import-settings}. The limit N comes from
   * {@link #maxFetchIntervalOf} on the account's active connection, the same source the bank
   * connection {@code status} action exposes.
   *
   * Strict calendar-day comparison: {@code importFrom < today - N}. The boundary day itself does
   * not warn, matching the SPA and the PSD2 module's {@code daysDiff > maxInterval}. Returns null
   * ("say nothing") for a null date, no active connection or no declared limit. Total: never throws.
   */
  static FetchIntervalExceeded findImportFromBeyondFetchInterval(FIN_FinancialAccount finAcc,
      Date importFrom) {
    return findImportFromBeyondFetchInterval(finAcc, importFrom, LocalDate.now());
  }

  static FetchIntervalExceeded findImportFromBeyondFetchInterval(FIN_FinancialAccount finAcc,
      Date importFrom, LocalDate today) {
    if (importFrom == null) {
      return null;
    }
    try {
      // Import dates are stored as UTC midnight (see parseDate), so read the day in UTC.
      return findImportFromBeyondFetchInterval(finAcc,
          new Date(importFrom.getTime()).toInstant().atZone(ZoneOffset.UTC).toLocalDate(), today);
    } catch (Exception e) {
      log.warn("[ETP-5805] Could not evaluate the import-from fetch interval: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Calendar-day form of the rule: the single strict {@code importFrom < today - N} comparison both
   * channels share. Callers that already hold the day (the PUT/PATCH hook reads it from the request
   * string, because the DAL stores that value at local midnight) use this one so no time zone is
   * involved.
   */
  static FetchIntervalExceeded findImportFromBeyondFetchInterval(FIN_FinancialAccount finAcc,
      LocalDate importFromDay, LocalDate today) {
    try {
      if (finAcc == null || importFromDay == null) {
        return null;
      }
      Integer days = maxFetchIntervalOf(SaltEdgeAccountLinkHelper.getActiveConnectionForFinAcc(finAcc));
      if (days == null || days <= 0) {
        return null;
      }
      LocalDate earliest = today.minusDays(days);
      return importFromDay.isBefore(earliest) ? new FetchIntervalExceeded(days, earliest) : null;
    } catch (Exception e) {
      log.warn("[ETP-5805] Could not evaluate the import-from fetch interval: {}", e.getMessage());
      return null;
    }
  }

  /** The translated advisory for {@code exceeded}, dates formatted as the language writes them. */
  static String fetchIntervalExceededText(FetchIntervalExceeded exceeded, String language) {
    String pattern = NeoHints.isSpanish(language) ? "dd/MM/yyyy" : "yyyy-MM-dd";
    return NeoHints.text("psd2ImportFromBeyondFetchInterval", language, String.valueOf(exceeded.getDays()),
        exceeded.getEarliestDate().format(java.time.format.DateTimeFormatter.ofPattern(pattern)));
  }

  /**
   * ETP-6151. Adds the non-blocking "this bank only provides the last N days" advisory to a write
   * response when {@code importFrom} is older than the history of the account's active connection.
   * One place for the envelope, so {@code import-settings}, {@code link} and {@code createAndLink}
   * answer it with the very same key and shape: a {@code messages} entry
   * {@code {type: "warning", text}} added by {@link NeoHints#addMessage} (top level for the SPA,
   * inside {@code response} for an MCP caller). Says nothing when the rule says nothing, and never
   * throws (see {@link #findImportFromBeyondFetchInterval(FIN_FinancialAccount, Date)}).
   *
   * @param response   the successful response to annotate
   * @param context    the request, for its origin (SPA or MCP)
   * @param finAcc     the financial account whose active connection gives the limit
   * @param importFrom the import-from date that was saved, possibly {@code null}
   */
  static void addFetchIntervalAdvisory(NeoResponse response, NeoContext context,
      FIN_FinancialAccount finAcc, Date importFrom) {
    FetchIntervalExceeded exceeded = findImportFromBeyondFetchInterval(finAcc, importFrom);
    if (exceeded != null) {
      NeoHints.addMessage(response.getBody(), context.isMcpOrigin(), NeoHints.TYPE_WARNING,
          fetchIntervalExceededText(exceeded, NeoLanguage.currentCode()));
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
      String endpoint = BankIntegrationConstants.SALT_EDGE_MIDDLEWARE_URL + SALT_EDGE_PROVIDERS_PATH
          + providerCode
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

  /**
   * The provider logo URL for {@code GET accounts} when the ETP-6151 flag is off: one Salt Edge
   * call to {@code providers/{code}}, empty when the call fails or carries no logo. Never throws.
   *
   * @param providerCode the Salt Edge provider code
   * @param apiKey       the PSD2 API key the call runs under
   * @return the logo URL, empty when unknown
   */
  static String fetchProviderLogo(String providerCode, String apiKey) {
    try {
      String endpoint = BankIntegrationConstants.SALT_EDGE_MIDDLEWARE_URL + SALT_EDGE_PROVIDERS_PATH
          + providerCode;
      JSONObject response = BankIntegrationUtils.makeSaltEdgeRequest("GET", null, endpoint, apiKey);
      JSONObject data = response.optJSONObject(KEY_DATA);
      return data != null ? data.optString(KEY_LOGO_URL, "") : "";
    } catch (Exception e) {
      log.warn("Could not fetch provider logo for {}: {}", providerCode, e.getMessage());
      return "";
    }
  }

  /**
   * What {@code GET accounts} shows about the bank of the connection being linked: its logo and
   * the days of history it serves (ETP-6151).
   */
  static final class ProviderDisplay {
    private final String logoUrl;
    private final Integer maxFetchInterval;

    /**
     * @param logoUrl          the provider logo URL, empty when unknown
     * @param maxFetchInterval the history limit in whole days, or {@code null} when unknown
     */
    ProviderDisplay(String logoUrl, Integer maxFetchInterval) {
      this.logoUrl = logoUrl;
      this.maxFetchInterval = maxFetchInterval;
    }

    /** @return the provider logo URL, never {@code null} (empty when unknown) */
    String getLogoUrl() {
      return logoUrl;
    }

    /** @return the history limit in whole days, or {@code null} when unknown */
    Integer getMaxFetchInterval() {
      return maxFetchInterval;
    }
  }

  /**
   * Resolves the provider logo and its history limit for {@code GET accounts} with ONE Salt Edge
   * call to {@code providers/{code}} (the call that used to serve the logo alone).
   *
   * <p>The limit comes from the registered {@code PSD2_PROVIDER.MAX_FETCH_INTERVAL} first — the
   * same source the {@code status} action and the synchronization's own warning read, so all of
   * them name the same number — and falls back to that answer's {@code max_fetch_interval} only
   * when the row is missing or declares no limit (a bank never linked on this instance has no row
   * yet: it is registered at link time). Total: a failed call or lookup leaves the field unknown,
   * it never fails the account list the user is choosing from.
   *
   * @param providerCode the Salt Edge provider code, not blank
   * @param apiKey       the PSD2 API key the call runs under
   * @return the logo (empty when unknown) and the limit ({@code null} when unknown)
   */
  static ProviderDisplay fetchProviderDisplay(String providerCode, String apiKey) {
    JSONObject data = fetchSaltEdgeProvider(providerCode, apiKey);
    String logoUrl = data != null ? data.optString(KEY_LOGO_URL, "") : "";
    Integer maxFetchInterval = maxFetchIntervalOfProviderOrNull(providerCode);
    if (maxFetchInterval == null && data != null) {
      long fromSaltEdge = data.optLong(BankIntegrationConstants.MAX_FETCH_INTERVAL, 0);
      // Same "0 or less means not declared" rule as the registered value.
      if (fromSaltEdge > 0 && fromSaltEdge <= Integer.MAX_VALUE) {
        maxFetchInterval = (int) fromSaltEdge;
      }
    }
    return new ProviderDisplay(logoUrl, maxFetchInterval);
  }

  /**
   * The {@code data} object of the Salt Edge middleware's {@code providers/{code}} answer, or
   * {@code null} when the call fails or the answer carries none (logged, never thrown).
   *
   * <p>Requested with {@code include_ais_fields=true}: {@code max_fetch_interval} is an AIS field,
   * so without it the answer omits the limit and the {@link #fetchProviderDisplay} fallback would
   * silently find nothing. Same query the PSD2 module's own readers use
   * ({@code AisConnectionCallback}, {@code SyncBankProviders}); the logo is in the same answer. The
   * code is URL-encoded, as the callback does.
   *
   * @param providerCode the Salt Edge provider code
   * @param apiKey       the PSD2 API key the call runs under
   * @return the provider object, or {@code null}
   */
  private static JSONObject fetchSaltEdgeProvider(String providerCode, String apiKey) {
    try {
      String endpoint = BankIntegrationConstants.SALT_EDGE_MIDDLEWARE_URL + SALT_EDGE_PROVIDERS_PATH
          + URLEncoder.encode(providerCode, StandardCharsets.UTF_8) + "?include_ais_fields=true";
      JSONObject response = BankIntegrationUtils.makeSaltEdgeRequest("GET", null, endpoint, apiKey);
      return response != null ? response.optJSONObject(KEY_DATA) : null;
    } catch (Exception e) {
      log.warn("Could not fetch provider details for {}: {}", providerCode, e.getMessage());
      return null;
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
