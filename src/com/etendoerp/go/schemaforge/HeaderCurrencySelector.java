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

package com.etendoerp.go.schemaforge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * The SELECTOR of a document header's {@code currency} (ETP-6365): the same currencies
 * {@code currencyOptions} lists — the organization currency first, then by ISO code every active
 * currency with a conversion range against it on any date — in the shape of the generic selector
 * ({@code {items:[{id, label, isoCode, name}], columns, totalCount, hasMore}}; {@code label} is the
 * ISO code, the identifier of {@code C_Currency}).
 *
 * <p><b>Why.</b> The app picks a header's currency from {@code currencyOptions}; the generic
 * {@code Currency} selector keeps its EUR/USD/GBP allowlist ({@code CurrencyIsoAllowlistSelectorPolicy})
 * for every other picker. An agent asking {@code etendo_selectors} for the header currency, or
 * naming it in a write ({@code currency: "CHF"}), went through the generic selector and was told a
 * manual currency did not exist. The header customizations that route {@code currencyOptions}
 * serve this list on their SELECTOR surface, so every channel — REST {@code .../selectors/currency},
 * MCP {@code etendo_selectors}, the MCP name resolution and the write check — reads one list.
 *
 * <p>The list does not depend on a date (a currency is offered when it has a range on any date),
 * so a selector call needs no reference date. Scope: the {@code organization} the request carries
 * when the role can read it, else the session organization; the session client and System.
 * Search ({@code q}) matches the ISO code or the name (request language), case-insensitive.
 */
final class HeaderCurrencySelector {

  private static final Logger log = LogManager.getLogger(HeaderCurrencySelector.class);

  private static final String COLUMN_CURRENCY = "C_Currency_ID";
  private static final String PARAM_SEARCH = "q";
  private static final String PARAM_LIMIT = "limit";
  private static final String PARAM_OFFSET = "offset";
  private static final String[] ORGANIZATION_PARAMS = { "organization", "AD_Org_ID" };
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 1000;
  private static final String KEY_ID = "id";
  private static final String KEY_ISO = "isoCode";
  private static final String KEY_NAME = "name";
  private static final String KEY_LABEL = "label";

  private HeaderCurrencySelector() {
  }

  /**
   * Serves the selector of the header {@code currency}, or answers {@code null} when the request
   * is not that selector, or when the organization or its currency cannot be resolved (the generic
   * selector then answers, as before).
   *
   * @param context the request context
   * @return the selector response, or {@code null}
   */
  static NeoResponse handleIfCurrencySelector(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.SELECTOR
        || !isCurrencyField(context.getFieldName())) {
      return null;
    }
    OBContext obCtx = OBContext.getOBContext();
    String orgId = CurrencyOptionsHandler.readableOrganization(organizationParam(context), obCtx);
    String clientId = obCtx != null && obCtx.getCurrentClient() != null
        ? obCtx.getCurrentClient().getId() : null;
    String orgCurrencyId = orgId == null ? null : OBCurrencyUtils.getOrgCurrency(orgId);
    if (orgCurrencyId == null || clientId == null) {
      return null;
    }
    try {
      List<JSONObject> matching = filter(options(orgCurrencyId, clientId, orgId),
          param(context, PARAM_SEARCH));
      int limit = intParam(context, PARAM_LIMIT, DEFAULT_LIMIT, 1, MAX_LIMIT);
      int offset = intParam(context, PARAM_OFFSET, 0, 0, Integer.MAX_VALUE);
      return NeoResponse.ok(page(matching, limit, offset));
    } catch (SQLException | JSONException e) {
      log.error("[ETP-6365] header currency selector failed: {}", e.getMessage(), e);
      return NeoResponse.error(500, "Error querying the document currencies");
    }
  }

  /**
   * The selectors a document header serves itself, in order: the payment method
   * ({@link PaymentMethodSelectorSupport}, direction from the window) and the currency
   * ({@link #handleIfCurrencySelector}). One call for the header customizations, so each keeps a
   * single early return.
   *
   * @param context the request context
   * @return the selector response, or {@code null} when the request is neither selector
   */
  static NeoResponse handleIfHeaderSelector(NeoContext context) {
    NeoResponse paymentMethod = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    return paymentMethod != null ? paymentMethod : handleIfCurrencySelector(context);
  }

  private static boolean isCurrencyField(String fieldName) {
    return CurrencyOptionsHandler.FIELD_CURRENCY.equals(fieldName)
        || COLUMN_CURRENCY.equalsIgnoreCase(fieldName);
  }

  /** The {@code currencyOptions} currencies, organization currency first, with their names. */
  private static List<JSONObject> options(String orgCurrencyId, String clientId, String orgId)
      throws SQLException, JSONException {
    Connection conn = OBDal.getInstance().getConnection();
    List<String> ids = new ArrayList<>();
    ids.add(orgCurrencyId);
    for (String id : CurrencyOptionsHandler.selectableCurrencies(conn, orgCurrencyId, clientId,
        orgId).keySet()) {
      if (!id.equals(orgCurrencyId)) {
        ids.add(id);
      }
    }
    Map<String, String[]> named = isoAndNames(conn, ids);
    List<JSONObject> items = new ArrayList<>();
    for (String id : ids) {
      String[] isoAndName = named.get(id);
      if (isoAndName != null) {
        items.add(new JSONObject().put(KEY_ID, id).put(KEY_LABEL, isoAndName[0])
            .put(KEY_ISO, isoAndName[0]).put(KEY_NAME, isoAndName[1]));
      }
    }
    return items;
  }

  /** ISO code and name (request language) of the active currencies among {@code ids}. */
  private static Map<String, String[]> isoAndNames(Connection conn, List<String> ids)
      throws SQLException {
    Map<String, String[]> named = new HashMap<>();
    String sql = "SELECT c.c_currency_id, c.iso_code, COALESCE(t.description, c.description)"
        + " FROM c_currency c LEFT JOIN c_currency_trl t ON t.c_currency_id = c.c_currency_id"
        + " AND t.ad_language = ? WHERE c.isactive = 'Y' AND c.c_currency_id = ANY(?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, language());
      ps.setArray(2, conn.createArrayOf("varchar", ids.toArray(new String[0])));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          named.put(rs.getString(1), new String[] { StringUtils.trim(rs.getString(2)),
              StringUtils.defaultString(rs.getString(3)) });
        }
      }
    }
    return named;
  }

  /**
   * The items whose ISO code, name or label contains {@code search}, ignoring case; all when blank,
   * none (an empty page, {@code totalCount} 0) when nothing matches.
   */
  private static List<JSONObject> filter(List<JSONObject> items, String search) {
    if (StringUtils.isBlank(search)) {
      return items;
    }
    String needle = search.trim().toLowerCase(Locale.ROOT);
    List<JSONObject> matching = new ArrayList<>();
    for (JSONObject item : items) {
      if (contains(item.optString(KEY_ISO), needle) || contains(item.optString(KEY_NAME), needle)
          || contains(item.optString(KEY_LABEL), needle)) {
        matching.add(item);
      }
    }
    return matching;
  }

  private static boolean contains(String text, String needle) {
    return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static JSONObject page(List<JSONObject> matching, int limit, int offset)
      throws JSONException {
    JSONArray items = new JSONArray();
    int end = (int) Math.min((long) offset + limit, matching.size());
    for (int i = Math.min(offset, matching.size()); i < end; i++) {
      items.put(matching.get(i));
    }
    return new JSONObject().put("items", items).put("columns", new JSONArray())
        .put("totalCount", matching.size()).put("hasMore", end < matching.size());
  }

  private static String organizationParam(NeoContext context) {
    for (String name : ORGANIZATION_PARAMS) {
      String value = param(context, name);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /**
   * A parameter of the context — MCP {@code etendo_selectors} puts its {@code query} there as
   * {@code q}, the write check and the name resolution their synthetic one — else of the current
   * HTTP request (REST {@code ?q=}, read by {@code NeoSelectorEndpoint}).
   */
  private static String param(NeoContext context, String name) {
    Map<String, String> query = context.getQueryParams();
    String value = query != null ? StringUtils.trimToNull(query.get(name)) : null;
    if (value != null) {
      return value;
    }
    HttpServletRequest request = RequestContext.get() != null ? RequestContext.get().getRequest()
        : null;
    return request != null ? StringUtils.trimToNull(request.getParameter(name)) : null;
  }

  private static int intParam(NeoContext context, String name, int fallback, int min, int max) {
    String raw = param(context, name);
    if (raw == null) {
      return fallback;
    }
    try {
      return Math.max(min, Math.min(max, Integer.parseInt(raw)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** The request language (as the conversion-rate reads), else the session's, else en_US. */
  private static String language() {
    String code = NeoLanguage.requestCode();
    if (code == null) {
      code = NeoLanguage.currentCode();
    }
    return code != null ? code : "en_US";
  }
}
