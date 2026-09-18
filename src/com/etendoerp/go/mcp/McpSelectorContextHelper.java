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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Builds MCP selector context parameters and explains empty selector responses.
 */
final class McpSelectorContextHelper {

  private static final DateTimeFormatter CLASSIC_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_PARAM = "param";
  private static final String KEY_FIELD = "field";
  private static final String PARAM_IS_SO_TRX = "IsSOTrx";
  private static final String PARAM_IS_SO_TRX_LOWER = "isSOTrx";
  private static final String PARAM_BPARTNER = "C_BPartner_ID";
  private static final String PARAM_BPARTNER_LOCATION = "C_BPartner_Location_ID";
  private static final String PARAM_IS_CUSTOMER = "isCustomer";
  private static final String PARAM_IS_VENDOR = "isVendor";
  private static final String PARAM_DATE_INVOICED = "DateInvoiced";
  private static final String PARAM_DATE_ORDERED = "DateOrdered";
  private static final String PARAM_PRICE_LIST = "priceList";
  private static final String PARAM_PRICE_LIST_CLASSIC = "PriceList";
  private static final String PARAM_PRICE_LIST_ID = "M_PriceList_ID";
  private static final String PARAM_COUNTRY = "C_Country_ID";
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_REGION = "region";
  private static final String COLUMN_REGION = "C_Region_ID";

  private McpSelectorContextHelper() {
  }

  static Map<String, String> buildSelectorContextParams(JSONObject args, Tab adTab) {
    Map<String, String> contextParams = new HashMap<>();

    if (args != null) {
      copySelectorContext(args.optJSONObject(McpConstants.PARAM_PARENT_CONTEXT), contextParams);
      copySelectorContext(args.optJSONObject(McpConstants.PARAM_RECORD_CONTEXT), contextParams);
      addParentId(args, contextParams);
    }
    addWindowSalesContext(adTab, contextParams);
    addBusinessPartnerRoleContext(adTab, contextParams);

    return contextParams;
  }

  /**
   * Derives selector context from an in-flight write body, on top of a base context (IMP-22).
   * <p>
   * The read path gets its context handed to it: {@code neo_selectors} takes an explicit
   * {@code recordContext}, so resolving {@code partnerAddress} against a parent
   * {@code businessPartner} works. The write path has no such argument — the sibling values are
   * simply the other keys of the body being created — so before this method the write-path resolver
   * ran with tab-derived context only, and a selector whose candidate set exists only relative to a
   * parent field found nothing. That is IMP-22: {@code neo_create} rejected the byte-identical
   * {@code $_identifier} that {@code neo_selectors} had just returned.
   * <p>
   * <b>Why the exclusion list is the whole design.</b> A body key is only usable as context once its
   * value is a real record id. A key still holding a human search string would be copied into
   * {@code C_BPartner_ID} verbatim and silently narrow the candidate set to nothing — turning a
   * resolvable field into a {@code not_found} and making the fix worse than the bug. So the caller
   * passes the keys it has NOT yet settled, and everything else in the body — resolved FKs, plus the
   * primitives {@code copySelectorContext} already knows how to use, such as {@code orderDate} for a
   * tax selector — becomes context.
   *
   * @param baseContext     tab-derived context (sales/purchase, business-partner role); may be null
   * @param body            the write body, keyed by canonical DAL property name
   * @param excludedKeys    body keys whose value is not (yet) a usable record id
   * @return a new map; never the instance passed in
   */
  static Map<String, String> withBodyContext(Map<String, String> baseContext, JSONObject body,
      Collection<String> excludedKeys) throws JSONException {
    Map<String, String> merged = new HashMap<>();
    if (baseContext != null) {
      merged.putAll(baseContext);
    }
    if (body == null) {
      return merged;
    }
    JSONObject usable = new JSONObject();
    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (excludedKeys == null || !excludedKeys.contains(key)) {
        usable.put(key, body.opt(key));
      }
    }
    copySelectorContext(usable, merged);
    return merged;
  }

  static NeoResponse withDiagnostics(NeoResponse neoResponse, String columnName,
      Map<String, String> contextParams) throws JSONException {
    if (neoResponse.getHttpStatus() >= 400 || neoResponse.getBody() == null) {
      return neoResponse;
    }

    JSONObject body = neoResponse.getBody();
    if (!isEmptySelectorResponse(body)) {
      return neoResponse;
    }

    JSONArray missingContext = buildMissingContext(columnName, contextParams);
    String resolution = resolveEmptyAnswerHint(columnName, contextParams);
    if (missingContext.length() == 0 && resolution == null) {
      return neoResponse;
    }

    JSONObject diagnostics = new JSONObject();
    diagnostics.put("column", columnName);
    diagnostics.put(KEY_MESSAGE, resolution != null
        ? resolution
        : "No selector results found. This may be due to missing context parameters.");
    if (missingContext.length() > 0) {
      diagnostics.put("missingContext", missingContext);
    }
    body.put("diagnostics", diagnostics);
    return NeoResponse.ok(body);
  }

  /**
   * Explains an empty answer that is CORRECT — where no argument is missing and retrying cannot
   * help, because the candidate set is genuinely empty for this context.
   *
   * <p>ETP-5368, found in live verification. The region selector for Argentina returns zero rows
   * and is right to: {@code C_Country.HasRegion} is {@code 'N'} there and no {@code C_Region} row
   * exists. Answering that with a bare empty list is the exact silent shape this ticket was raised
   * for — ETP-4997 already recorded that a caller which cannot tell "no provinces exist" from
   * "the lookup failed" quietly drops the province and saves an address without one, and nothing
   * tells the user. {@code C_Location.RegionName} is Etendo's own home for that case, so name it.
   *
   * @return the explanation, or {@code null} when the empty answer needs no special account
   */
  private static String resolveEmptyAnswerHint(String columnName, Map<String, String> contextParams) {
    String countryId = contextParams.get(PARAM_COUNTRY);
    if (!isRegionColumn(columnName) || StringUtils.isBlank(countryId)) {
      return null;
    }
    try {
      Country country = OBDal.getInstance().get(Country.class, countryId);
      if (country == null || Boolean.TRUE.equals(country.isHasRegions())) {
        return null;
      }
      return "This country (" + country.getName() + ") does not model regions, so this selector is "
          + "correctly empty and retrying will not change it. Send the province as free text in "
          + "'regionName' instead of 'region'.";
    } catch (Exception e) {
      // A diagnostic must never be the reason a working selector fails.
      return null;
    }
  }

  static void copyContextIfPresent(JSONObject recordContext, String sourceKey,
      Map<String, String> contextParams, String targetKey) {
    String value = recordContext.optString(sourceKey, null);
    if (StringUtils.isNotBlank(value)) {
      contextParams.put(targetKey, value);
    }
  }

  private static void copySelectorContext(JSONObject context, Map<String, String> contextParams) {
    if (context == null) {
      return;
    }

    copyContextIfPresent(context, "businessPartner", contextParams, PARAM_BPARTNER);
    copyContextIfPresent(context, PARAM_BPARTNER, contextParams, PARAM_BPARTNER);
    copyContextIfPresent(context, "partnerAddress", contextParams, PARAM_BPARTNER_LOCATION);
    copyContextIfPresent(context, "invoiceAddress", contextParams, PARAM_BPARTNER_LOCATION);
    copyContextIfPresent(context, PARAM_BPARTNER_LOCATION, contextParams,
        PARAM_BPARTNER_LOCATION);
    copyPriceListContext(context, contextParams);
    copySalesContext(context, contextParams);
    copyDateContext(context, contextParams);
    copyCountryContext(context, contextParams);
  }

  /**
   * Carries the country into the region selector's validation rule (ETP-5368).
   *
   * <p>{@code C_Location.C_Region_ID} is validated by {@code C_Region.C_Country_ID=@C_Country_ID@}
   * — the candidate provinces exist only relative to a country, so without it the rule has nothing
   * to resolve. The SPA passes the country on the query string and gets Spain's 104 provinces; the
   * MCP had no mapping for it at all, so the same selector came back unfiltered. Accepted under the
   * DAL property name the wrapper's own schema publishes ({@code country}) and under the classic
   * column name, matching how businessPartner and priceList are already taken.
   */
  private static void copyCountryContext(JSONObject context, Map<String, String> contextParams) {
    String country = firstNonBlank(context, FIELD_COUNTRY, PARAM_COUNTRY);
    if (StringUtils.isNotBlank(country)) {
      contextParams.put(PARAM_COUNTRY, country);
    }
  }

  private static void copyPriceListContext(JSONObject context, Map<String, String> contextParams) {
    String priceList = firstNonBlank(context, PARAM_PRICE_LIST, PARAM_PRICE_LIST_CLASSIC,
        PARAM_PRICE_LIST_ID);
    if (StringUtils.isNotBlank(priceList)) {
      contextParams.put(PARAM_PRICE_LIST, priceList);
      contextParams.put(PARAM_PRICE_LIST_CLASSIC, priceList);
      contextParams.put(PARAM_PRICE_LIST_ID, priceList);
    }
  }

  private static void copySalesContext(JSONObject context, Map<String, String> contextParams) {
    String isSOTrx = firstNonBlank(context, PARAM_IS_SO_TRX_LOWER, PARAM_IS_SO_TRX);
    if (StringUtils.isNotBlank(isSOTrx)) {
      contextParams.put(PARAM_IS_SO_TRX_LOWER, isSOTrx);
      contextParams.put(PARAM_IS_SO_TRX, isSOTrx);
    }
    copyContextIfPresent(context, PARAM_IS_CUSTOMER, contextParams, PARAM_IS_CUSTOMER);
    copyContextIfPresent(context, PARAM_IS_VENDOR, contextParams, PARAM_IS_VENDOR);
  }

  private static void copyDateContext(JSONObject context, Map<String, String> contextParams) {
    copyClassicDate(context, contextParams, PARAM_DATE_INVOICED, "invoiceDate", "dateInvoiced");
    copyClassicDate(context, contextParams, PARAM_DATE_ORDERED, "orderDate", "dateOrdered");
  }

  private static void copyClassicDate(JSONObject context, Map<String, String> contextParams,
      String classicKey, String primaryKey, String lowerKey) {
    String sourceValue = firstNonBlank(context, primaryKey, classicKey, lowerKey);
    if (StringUtils.isBlank(sourceValue)) {
      return;
    }
    String classicDate = formatClassicDate(sourceValue);
    contextParams.put(classicKey, classicDate);
    contextParams.put(lowerKey, classicDate);
  }

  private static String firstNonBlank(JSONObject context, String... keys) {
    for (String key : keys) {
      String value = context.optString(key, null);
      if (StringUtils.isNotBlank(value)) {
        return value;
      }
    }
    return null;
  }

  private static String formatClassicDate(String value) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null || trimmed.matches("\\d{2}-\\d{2}-\\d{4}")) {
      return trimmed;
    }
    String isoDate = trimmed.length() >= 10 ? trimmed.substring(0, 10) : trimmed;
    try {
      return LocalDate.parse(isoDate).format(CLASSIC_DATE_FORMATTER);
    } catch (DateTimeParseException ignored) {
      return trimmed;
    }
  }

  private static void addWindowSalesContext(Tab adTab, Map<String, String> contextParams) {
    if (contextParams.containsKey(PARAM_IS_SO_TRX)
        || contextParams.containsKey(PARAM_IS_SO_TRX_LOWER)) {
      return;
    }
    String isSOTrx = resolveIsSOTrxFromTab(adTab);
    if (isSOTrx != null) {
      contextParams.put(PARAM_IS_SO_TRX, isSOTrx);
      contextParams.put(PARAM_IS_SO_TRX_LOWER, isSOTrx);
    }
  }

  private static String resolveIsSOTrxFromTab(Tab adTab) {
    if (adTab == null) {
      return null;
    }
    Window window = adTab.getWindow();
    if (window == null || window.isSalesTransaction() == null) {
      return null;
    }
    return window.isSalesTransaction() ? "Y" : "N";
  }

  private static void addBusinessPartnerRoleContext(Tab adTab,
      Map<String, String> contextParams) {
    String isSOTrx = StringUtils.defaultIfBlank(contextParams.get(PARAM_IS_SO_TRX_LOWER),
        contextParams.get(PARAM_IS_SO_TRX));
    if (StringUtils.isBlank(isSOTrx)) {
      isSOTrx = resolveIsSOTrxFromTab(adTab);
    }
    addRoleFlag(isSOTrx, contextParams);
  }

  private static void addRoleFlag(String isSOTrx, Map<String, String> contextParams) {
    if ("Y".equalsIgnoreCase(isSOTrx)) {
      contextParams.putIfAbsent(PARAM_IS_CUSTOMER, "Y");
    } else if ("N".equalsIgnoreCase(isSOTrx)) {
      contextParams.putIfAbsent(PARAM_IS_VENDOR, "Y");
    }
  }

  private static void addParentId(JSONObject args, Map<String, String> contextParams) {
    String parentId = args.optString(McpConstants.PARAM_PARENT_ID, null);
    if (StringUtils.isNotBlank(parentId)) {
      contextParams.put("parentId", parentId);
    }
  }

  private static boolean isEmptySelectorResponse(JSONObject body) {
    JSONArray items = body.optJSONArray("items");
    long totalCount = body.optLong("totalCount", 0);
    return (items == null || items.length() == 0) && totalCount == 0;
  }

  private static JSONArray buildMissingContext(String columnName, Map<String, String> contextParams)
      throws JSONException {
    JSONArray missingContext = new JSONArray();
    addBusinessPartnerDiagnostic(columnName, contextParams, missingContext);
    addSalesDiagnostic(contextParams, missingContext);
    addDateDiagnostic(columnName, contextParams, missingContext);
    addCountryDiagnostic(columnName, contextParams, missingContext);
    return missingContext;
  }

  /**
   * ETP-5368. An unfiltered region selector is the failure this ticket was raised for, and its
   * empty answer said nothing about why. Name the one argument that fixes it.
   */
  private static void addCountryDiagnostic(String columnName, Map<String, String> contextParams,
      JSONArray missingContext) throws JSONException {
    if (contextParams.containsKey(PARAM_COUNTRY) || !isRegionColumn(columnName)) {
      return;
    }
    JSONObject missing = new JSONObject();
    missing.put(KEY_PARAM, PARAM_COUNTRY);
    missing.put(KEY_FIELD, FIELD_COUNTRY);
    missing.put(KEY_MESSAGE,
        "Provide country in recordContext to resolve " + columnName
            + ": region names exist only relative to a country.");
    missingContext.put(missing);
  }

  private static boolean isRegionColumn(String columnName) {
    return COLUMN_REGION.equalsIgnoreCase(columnName) || FIELD_REGION.equalsIgnoreCase(columnName);
  }

  private static void addBusinessPartnerDiagnostic(String columnName,
      Map<String, String> contextParams, JSONArray missingContext) throws JSONException {
    if (contextParams.containsKey(PARAM_BPARTNER) || !isBusinessPartnerLocationColumn(columnName)) {
      return;
    }
    JSONObject missing = new JSONObject();
    missing.put(KEY_PARAM, PARAM_BPARTNER);
    missing.put(KEY_FIELD, "businessPartner");
    missing.put(KEY_MESSAGE, "Provide businessPartner in recordContext to resolve " + columnName);
    missingContext.put(missing);
  }

  private static boolean isBusinessPartnerLocationColumn(String columnName) {
    return StringUtils.contains(columnName, "BPartner_Location")
        || StringUtils.contains(columnName, "BillTo")
        || "partnerAddress".equalsIgnoreCase(columnName)
        || "invoiceAddress".equalsIgnoreCase(columnName);
  }

  private static void addSalesDiagnostic(Map<String, String> contextParams,
      JSONArray missingContext) throws JSONException {
    if (contextParams.containsKey(PARAM_IS_SO_TRX)
        || contextParams.containsKey(PARAM_IS_SO_TRX_LOWER)) {
      return;
    }
    JSONObject missing = new JSONObject();
    missing.put(KEY_PARAM, PARAM_IS_SO_TRX);
    missing.put("source", "windowCategory");
    missing.put(KEY_MESSAGE,
        "isSOTrx not resolved from window context. Verify the window is flagged as sales or purchase.");
    missingContext.put(missing);
  }

  private static void addDateDiagnostic(String columnName, Map<String, String> contextParams,
      JSONArray missingContext) throws JSONException {
    if (!isTaxColumn(columnName) || contextParams.containsKey(PARAM_DATE_INVOICED)
        || contextParams.containsKey(PARAM_DATE_ORDERED)) {
      return;
    }
    JSONObject missing = new JSONObject();
    missing.put(KEY_PARAM, PARAM_DATE_INVOICED);
    missing.put(KEY_FIELD, "invoiceDate or orderDate");
    missing.put(KEY_MESSAGE, "Provide invoiceDate or orderDate in recordContext to resolve tax selector");
    missingContext.put(missing);
  }

  private static boolean isTaxColumn(String columnName) {
    return "C_Tax_ID".equals(columnName) || StringUtils.contains(columnName, "Tax");
  }
}
