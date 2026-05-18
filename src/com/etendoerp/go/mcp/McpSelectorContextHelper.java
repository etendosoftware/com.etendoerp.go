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
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.ui.Tab;
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

  private McpSelectorContextHelper() {
  }

  static Map<String, String> buildSelectorContextParams(JSONObject args, Tab adTab) {
    Map<String, String> contextParams = new HashMap<>();

    copySelectorContext(args.optJSONObject(McpConstants.PARAM_PARENT_CONTEXT), contextParams);
    copySelectorContext(args.optJSONObject(McpConstants.PARAM_RECORD_CONTEXT), contextParams);
    addWindowSalesContext(adTab, contextParams);
    addBusinessPartnerRoleContext(adTab, contextParams);
    addParentId(args, contextParams);

    return contextParams;
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
    if (missingContext.length() == 0) {
      return neoResponse;
    }

    JSONObject diagnostics = new JSONObject();
    diagnostics.put("column", columnName);
    diagnostics.put(KEY_MESSAGE,
        "No selector results found. This may be due to missing context parameters.");
    diagnostics.put("missingContext", missingContext);
    body.put("diagnostics", diagnostics);
    return NeoResponse.ok(body);
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
    return missingContext;
  }

  private static void addBusinessPartnerDiagnostic(String columnName,
      Map<String, String> contextParams, JSONArray missingContext) throws JSONException {
    if (contextParams.containsKey(PARAM_BPARTNER) || !isBusinessPartnerLocationColumn(columnName)) {
      return;
    }
    JSONObject missing = new JSONObject();
    missing.put(KEY_PARAM, PARAM_BPARTNER);
    missing.put("field", "businessPartner");
    missing.put(KEY_MESSAGE, "Provide businessPartner in recordContext to resolve " + columnName);
    missingContext.put(missing);
  }

  private static boolean isBusinessPartnerLocationColumn(String columnName) {
    return columnName.contains("BPartner_Location") || columnName.contains("BillTo")
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
    missing.put("field", "invoiceDate or orderDate");
    missing.put(KEY_MESSAGE, "Provide invoiceDate or orderDate in recordContext to resolve tax selector");
    missingContext.put(missing);
  }

  private static boolean isTaxColumn(String columnName) {
    return "C_Tax_ID".equals(columnName) || columnName.contains("Tax");
  }
}
