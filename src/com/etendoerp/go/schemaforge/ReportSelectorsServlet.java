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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.common.CorsUtils;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Report Selectors Servlet.
 *
 * Mapped to /sws/report-selectors/* via AD_MODEL_OBJECT registration.
 * Provides searchable selector data for report parameter inputs (currency,
 * bpartner, product, warehouse, org, account, acctschema, year, tax, project).
 *
 * URL:
 *   GET /sws/report-selectors/{type}?q=&limit=20&offset=0
 *
 * Optional query params (type-specific):
 *   selectedOrgId         — filter warehouse/product/year by org
 *   selectedAcctSchemaId  — filter account by accounting schema
 *   warehouseIds          — comma-separated warehouse IDs to filter product stock
 *   roleOrgIds            — comma-separated org IDs accessible to the role
 *
 * Response:
 *   { "items": [ { "id": "...", "name": "...", "label": "..." } ],
 *     "totalCount": N, "hasMore": true/false }
 */
public class ReportSelectorsServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(ReportSelectorsServlet.class);

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final String ORDER_BY_NAME = "ORDER BY name";
  private static final String PARAM_SEARCH = "search";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_SELECTED_ORG_ID = "selectedOrgId";
  private static final String PARAM_SELECTED_ACCT_SCHEMA_ID = "selectedAcctSchemaId";
  private static final String PARAM_WAREHOUSE_IDS = "warehouseIds";
  private static final String PARAM_ROLE_ORG_IDS = "roleOrgIds";
  private static final String ACTIVE_CLIENT_NAME_SEARCH = " AND ad_client_id = :"
      + PARAM_CLIENT_ID + " AND name ILIKE :" + PARAM_SEARCH;

  // ---------------------------------------------------------------------------
  // Value objects
  // ---------------------------------------------------------------------------

  /** Groups all filter parameters for a selector request. */
  private final class SelectorRequest {
    final String q;
    final int limit;
    final int offset;
    final String clientId;
    final String selectedOrgId;
    final String selectedAcctSchemaId;
    final List<String> warehouseIds;
    final List<String> roleOrgIds;

    SelectorRequest(HttpServletRequest request, String clientId) {
      this.q = StringUtils.trimToEmpty(request.getParameter("q"));
      this.limit = parseIntParam(request.getParameter("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT);
      this.offset = parseIntParam(request.getParameter("offset"), 0, 0, Integer.MAX_VALUE);
      this.clientId = clientId;
      this.selectedOrgId = safeId(request.getParameter(PARAM_SELECTED_ORG_ID));
      this.selectedAcctSchemaId = safeId(request.getParameter(PARAM_SELECTED_ACCT_SCHEMA_ID));
      this.warehouseIds = parseSafeIdList(request.getParameter(PARAM_WAREHOUSE_IDS));
      this.roleOrgIds = parseSafeIdList(request.getParameter(PARAM_ROLE_ORG_IDS));
    }

    private List<String> parseSafeIdList(String param) {
      if (StringUtils.isBlank(param)) return List.of();
      return Arrays.stream(param.split(","))
          .map(String::trim)
          .map(ReportSelectorsServlet.this::safeId)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }

    private int parseIntParam(String value, int defaultVal, int min, int max) {
      if (StringUtils.isBlank(value)) return defaultVal;
      try {
        return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
      } catch (NumberFormatException e) {
        return defaultVal;
      }
    }
  }

  /** Holds the SQL parts produced by a query builder method. */
  private static final class SelectorQuery {
    final String select;
    final StringBuilder fromWhere;
    final String orderBy;
    /**
     * When true, :clientId must be bound on the data query.
     * If :clientId also appears in fromWhere it is bound on the count query too;
     * if it only appears in orderBy it is bound on the data query only.
     */
    final boolean bindClient;

    SelectorQuery(String select, StringBuilder fromWhere, String orderBy, boolean bindClient) {
      this.select = select;
      this.fromWhere = fromWhere;
      this.orderBy = orderBy;
      this.bindClient = bindClient;
    }
  }

  // ---------------------------------------------------------------------------
  // HTTP entry points
  // ---------------------------------------------------------------------------

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, OPTIONS", "Authorization, Content-Type", null, false);

    try {
      authenticateJwt(request);
    } catch (OBException e) {
      log.warn("Unauthorized ReportSelectors request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    } catch (Exception e) {
      log.warn("Unauthorized ReportSelectors request: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    String pathInfo = request.getPathInfo();
    if (pathInfo == null || pathInfo.equals("/")) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Selector type is required");
      return;
    }
    String type = pathInfo.replaceFirst("^/", "").split("/")[0];
    if (StringUtils.isBlank(type)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Selector type is required");
      return;
    }

    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    SelectorRequest req = new SelectorRequest(request, clientId);

    try {
      OBContext.setAdminMode();
      JSONObject result = executeSelector(type, req);
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(result.toString());
    } catch (IllegalArgumentException e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error in ReportSelectorsServlet for type '{}': {}", type, e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while processing the selector request.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, OPTIONS", "Authorization, Content-Type", null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  // ---------------------------------------------------------------------------
  // Selector execution
  // ---------------------------------------------------------------------------

  @SuppressWarnings({"unchecked", "rawtypes"})
  private JSONObject executeSelector(String type, SelectorRequest req) throws JSONException {
    SelectorQuery sq = buildQuery(type, req);

    String fromWhereStr = sq.fromWhere.toString();
    String countSql = "SELECT COUNT(*) " + fromWhereStr;
    String dataSql  = sq.select + " " + fromWhereStr + " " + sq.orderBy;

    NativeQuery countQuery = OBDal.getInstance().getSession().createNativeQuery(countSql);
    NativeQuery dataQuery  = OBDal.getInstance().getSession().createNativeQuery(dataSql);

    bindSearchParameter(countQuery, dataQuery, req.q);
    bindClientParameter(countQuery, dataQuery, fromWhereStr, sq.bindClient, req.clientId);
    bindOptionalParameter(countQuery, dataQuery, fromWhereStr, PARAM_SELECTED_ORG_ID,
        req.selectedOrgId);
    bindOptionalParameter(countQuery, dataQuery, fromWhereStr, PARAM_SELECTED_ACCT_SCHEMA_ID,
        req.selectedAcctSchemaId);
    bindOptionalListParameter(countQuery, dataQuery, fromWhereStr, PARAM_WAREHOUSE_IDS,
        req.warehouseIds);
    bindOptionalListParameter(countQuery, dataQuery, fromWhereStr, PARAM_ROLE_ORG_IDS,
        req.roleOrgIds);

    Number countResult = (Number) countQuery.uniqueResult();
    int totalCount = countResult != null ? countResult.intValue() : 0;

    dataQuery.setMaxResults(req.limit);
    dataQuery.setFirstResult(req.offset);
    List<Object[]> rows = dataQuery.list();

    JSONArray items = toJsonItems(rows);

    JSONObject result = new JSONObject();
    result.put("items", items);
    result.put("totalCount", totalCount);
    result.put("hasMore", req.offset + rows.size() < totalCount);
    return result;
  }

  // ---------------------------------------------------------------------------
  // Query builders — one method per selector type
  // ---------------------------------------------------------------------------

  private SelectorQuery buildQuery(String type, SelectorRequest req) {
    switch (type) {
      case "bpartner":   return buildBpartnerQuery();
      case "product":    return buildProductQuery(req);
      case "warehouse":  return buildWarehouseQuery(req);
      case "project":    return buildProjectQuery();
      case "org":        return buildOrgQuery();
      case "account":    return buildAccountQuery(req);
      case "accounting":
      case "acctschema": return buildAcctschemaQuery();
      case "year":       return buildYearQuery(req);
      case "currency":   return buildCurrencyQuery();
      case "tax":        return buildTaxQuery();
      default: throw new IllegalArgumentException("Unknown selector type: " + type);
    }
  }

  private SelectorQuery buildBpartnerQuery() {
    return new SelectorQuery(
        "SELECT c_bpartner_id AS id, name, name AS label",
        new StringBuilder("FROM c_bpartner WHERE isactive='Y'"
            + ACTIVE_CLIENT_NAME_SEARCH),
        ORDER_BY_NAME, true);
  }

  private SelectorQuery buildProductQuery(SelectorRequest req) {
    StringBuilder fromWhere = new StringBuilder(
        "FROM m_product WHERE isactive='Y' AND ad_client_id = :clientId"
        + " AND (name ILIKE :search OR value ILIKE :search)");
    if (!req.warehouseIds.isEmpty()) {
      fromWhere.append(" AND EXISTS (SELECT 1 FROM m_storage_detail sd"
          + " JOIN m_locator l ON l.m_locator_id = sd.m_locator_id"
          + " WHERE sd.m_product_id = m_product.m_product_id"
          + " AND l.m_warehouse_id IN (:warehouseIds))");
    }
    if (req.selectedOrgId != null) {
      fromWhere.append(" AND EXISTS (SELECT 1 FROM m_storage_detail sd"
          + " JOIN m_locator l ON l.m_locator_id = sd.m_locator_id"
          + " WHERE sd.m_product_id = m_product.m_product_id"
          + " AND ad_isorgincluded(l.ad_org_id, :selectedOrgId, m_product.ad_client_id) <> -1)");
    }
    if (!req.roleOrgIds.isEmpty()) {
      fromWhere.append(" AND EXISTS (SELECT 1 FROM m_storage_detail sd"
          + " JOIN m_locator l ON l.m_locator_id = sd.m_locator_id"
          + " WHERE sd.m_product_id = m_product.m_product_id"
          + " AND l.ad_org_id IN (:roleOrgIds))");
    }
    return new SelectorQuery(
        "SELECT m_product_id AS id, name, value || ' - ' || name AS label",
        fromWhere, "ORDER BY value, name", true);
  }

  private SelectorQuery buildWarehouseQuery(SelectorRequest req) {
    StringBuilder fromWhere = new StringBuilder(
        "FROM m_warehouse WHERE isactive='Y'" + ACTIVE_CLIENT_NAME_SEARCH);
    if (req.selectedOrgId != null) {
      fromWhere.append(" AND EXISTS (SELECT 1 FROM ad_org_warehouse ow"
          + " WHERE ow.m_warehouse_id = m_warehouse.m_warehouse_id"
          + " AND ow.ad_org_id = :selectedOrgId)");
    }
    if (!req.roleOrgIds.isEmpty()) {
      fromWhere.append(" AND EXISTS (SELECT 1 FROM ad_org_warehouse ow"
          + " WHERE ow.m_warehouse_id = m_warehouse.m_warehouse_id"
          + " AND ow.ad_org_id IN (:roleOrgIds))");
    }
    return new SelectorQuery(
        "SELECT m_warehouse_id AS id, name, name AS label",
        fromWhere, ORDER_BY_NAME, true);
  }

  private SelectorQuery buildProjectQuery() {
    return new SelectorQuery(
        "SELECT c_project_id AS id, name, name AS label",
        new StringBuilder("FROM c_project WHERE isactive='Y'"
            + ACTIVE_CLIENT_NAME_SEARCH),
        ORDER_BY_NAME, true);
  }

  private SelectorQuery buildOrgQuery() {
    return new SelectorQuery(
        "SELECT ad_org_id AS id, name, name AS label",
        new StringBuilder("FROM ad_org WHERE isactive='Y' AND ad_org_id != '0'"
            + ACTIVE_CLIENT_NAME_SEARCH),
        ORDER_BY_NAME, true);
  }

  private SelectorQuery buildAccountQuery(SelectorRequest req) {
    StringBuilder fromWhere = new StringBuilder(
        "FROM c_elementvalue ev WHERE ev.isactive='Y' AND ev.issummary='N'"
        + " AND ev.ad_client_id = :clientId"
        + " AND (ev.value ILIKE :search OR ev.name ILIKE :search)");
    if (req.selectedAcctSchemaId != null) {
      fromWhere.append(" AND ev.c_element_id IN"
          + " (SELECT c_element_id FROM c_acctschema_element"
          + " WHERE c_acctschema_id = :selectedAcctSchemaId AND c_element_id IS NOT NULL)");
    }
    return new SelectorQuery(
        "SELECT ev.value AS id, ev.value || ' - ' || ev.name AS name,"
        + " ev.value || ' - ' || ev.name AS label",
        fromWhere, "ORDER BY ev.value", true);
  }

  private SelectorQuery buildAcctschemaQuery() {
    return new SelectorQuery(
        "SELECT c_acctschema_id AS id, name, name AS label",
        new StringBuilder("FROM c_acctschema WHERE isactive='Y'"
            + ACTIVE_CLIENT_NAME_SEARCH),
        ORDER_BY_NAME, true);
  }

  private SelectorQuery buildYearQuery(SelectorRequest req) {
    StringBuilder fromWhere = new StringBuilder(
        "FROM c_year y JOIN c_calendar c ON c.c_calendar_id = y.c_calendar_id"
        + " WHERE y.isactive='Y' AND y.ad_client_id = :clientId"
        + " AND (y.year || ' (' || c.name || ')') ILIKE :search");
    if (req.selectedOrgId != null) {
      fromWhere.append(" AND EXISTS (SELECT 1 FROM ad_org o"
          + " WHERE o.c_calendar_id = c.c_calendar_id AND o.ad_org_id = :selectedOrgId)");
    }
    return new SelectorQuery(
        "SELECT y.c_year_id AS id,"
        + " y.year || ' (' || c.name || ')' AS name,"
        + " y.year || ' (' || c.name || ')' AS label",
        fromWhere, "ORDER BY y.year DESC", true);
  }

  private SelectorQuery buildCurrencyQuery() {
    // Currency is a global (cross-client) table; no ad_client_id filter in fromWhere.
    // :clientId is used only in the ORDER BY subquery to sort the client's default currency first.
    // executeSelector binds :clientId on the data query only (count has no ORDER BY).
    return new SelectorQuery(
        "SELECT c_currency_id AS id, iso_code AS name,"
        + " iso_code || ' - ' || description AS label",
        new StringBuilder("FROM c_currency WHERE isactive='Y'"
            + " AND (iso_code ILIKE :search OR description ILIKE :search)"),
        "ORDER BY (CASE WHEN c_currency_id ="
        + " (SELECT c_currency_id FROM ad_client WHERE ad_client_id = :clientId)"
        + " THEN 0 ELSE 1 END), iso_code",
        true);
  }

  private SelectorQuery buildTaxQuery() {
    return new SelectorQuery(
        "SELECT c_tax_id AS id, name, name AS label",
        new StringBuilder("FROM c_tax WHERE isactive='Y'"
            + ACTIVE_CLIENT_NAME_SEARCH),
        ORDER_BY_NAME, true);
  }

  // ---------------------------------------------------------------------------
  // JWT authentication — same pattern as NeoServlet, no shared state modified
  // ---------------------------------------------------------------------------

  private void authenticateJwt(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decoded = SecureWebServicesUtils.decodeToken(token);

    String userId      = decoded.getClaim("user").asString();
    String roleId      = decoded.getClaim("role").asString();
    String orgId       = decoded.getClaim("organization").asString();
    String warehouseId = decoded.getClaim("warehouse").asString();
    String clientId    = decoded.getClaim("client").asString();

    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId, clientId);
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void bindSearchParameter(NativeQuery countQuery, NativeQuery dataQuery, String q) {
    String searchValue = "%" + q + "%";
    countQuery.setParameter(PARAM_SEARCH, searchValue);
    dataQuery.setParameter(PARAM_SEARCH, searchValue);
  }

  private void bindClientParameter(NativeQuery countQuery, NativeQuery dataQuery,
      String fromWhereStr, boolean bindClient, String clientId) {
    if (!bindClient) {
      return;
    }
    if (fromWhereStr.contains(sqlParameter(PARAM_CLIENT_ID))) {
      countQuery.setParameter(PARAM_CLIENT_ID, clientId);
    }
    dataQuery.setParameter(PARAM_CLIENT_ID, clientId);
  }

  private void bindOptionalParameter(NativeQuery countQuery, NativeQuery dataQuery,
      String fromWhereStr, String parameterName, String parameterValue) {
    if (parameterValue == null || !fromWhereStr.contains(sqlParameter(parameterName))) {
      return;
    }
    countQuery.setParameter(parameterName, parameterValue);
    dataQuery.setParameter(parameterName, parameterValue);
  }

  private void bindOptionalListParameter(NativeQuery countQuery, NativeQuery dataQuery,
      String fromWhereStr, String parameterName, List<String> parameterValues) {
    if (parameterValues.isEmpty() || !fromWhereStr.contains(sqlParameter(parameterName))) {
      return;
    }
    countQuery.setParameterList(parameterName, parameterValues);
    dataQuery.setParameterList(parameterName, parameterValues);
  }

  private JSONArray toJsonItems(List<Object[]> rows) throws JSONException {
    JSONArray items = new JSONArray();
    for (Object[] row : rows) {
      JSONObject item = new JSONObject();
      item.put("id", toText(row[0]));
      item.put("name", toText(row[1]));
      item.put("label", toText(row[2]));
      items.put(item);
    }
    return items;
  }

  private String toText(Object value) {
    return value != null ? value.toString() : "";
  }

  private String sqlParameter(String parameterName) {
    return ":" + parameterName;
  }

  /**
   * Returns the ID only if it is a valid Etendo hex/UUID string, null otherwise.
   */
  private String safeId(String id) {
    if (id == null) return null;
    String trimmed = id.trim();
    return (trimmed.matches("[0-9A-Fa-f\\-]+") && trimmed.length() <= 36) ? trimmed : null;
  }

  private void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", message);
      response.setStatus(status);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(body.toString());
    } catch (JSONException ex) {
      log.error("Failed to write error response", ex);
    }
  }
}
