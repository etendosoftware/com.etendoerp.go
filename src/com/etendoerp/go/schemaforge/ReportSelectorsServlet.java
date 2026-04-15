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

    String q = StringUtils.trimToEmpty(request.getParameter("q"));
    int limit = parseIntParam(request.getParameter("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT);
    int offset = parseIntParam(request.getParameter("offset"), 0, 0, Integer.MAX_VALUE);
    String selectedOrgId = safeId(request.getParameter("selectedOrgId"));
    String selectedAcctSchemaId = safeId(request.getParameter("selectedAcctSchemaId"));
    List<String> warehouseIds = parseSafeIdList(request.getParameter("warehouseIds"));
    List<String> roleOrgIds = parseSafeIdList(request.getParameter("roleOrgIds"));

    String clientId = OBContext.getOBContext().getCurrentClient().getId();

    try {
      OBContext.setAdminMode();
      JSONObject result = executeSelector(type, q, limit, offset,
          clientId, selectedOrgId, selectedAcctSchemaId, warehouseIds, roleOrgIds);
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(result.toString());
    } catch (IllegalArgumentException e) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("Error in ReportSelectorsServlet for type '{}': {}", type, e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Selector query failed: " + e.getMessage());
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
  // Selector dispatcher
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private JSONObject executeSelector(String type, String q, int limit, int offset,
      String clientId, String selectedOrgId, String selectedAcctSchemaId,
      List<String> warehouseIds, List<String> roleOrgIds) throws JSONException {

    // Each case builds: selectClause, fromWhere (may grow with optional filters), orderBy.
    // Named params :clientId and :search are bound after building the SQL.
    // Array-like optional conditions use pre-validated inlined IDs (safe hex strings).

    String selectClause;
    StringBuilder fromWhere;
    String orderBy;
    boolean useSearch = true;   // whether :search param is needed
    boolean useClient = true;   // whether :clientId param is needed

    switch (type) {

      case "bpartner":
        selectClause = "SELECT c_bpartner_id AS id, name, name AS label";
        fromWhere = new StringBuilder(
            "FROM c_bpartner WHERE isactive='Y' AND ad_client_id = :clientId AND name ILIKE :search");
        orderBy = "ORDER BY name";
        break;

      case "product":
        selectClause = "SELECT m_product_id AS id, name, value || ' - ' || name AS label";
        fromWhere = new StringBuilder(
            "FROM m_product WHERE isactive='Y' AND ad_client_id = :clientId"
            + " AND (name ILIKE :search OR value ILIKE :search)");
        orderBy = "ORDER BY value, name";
        if (!warehouseIds.isEmpty()) {
          fromWhere.append(
              " AND EXISTS (SELECT 1 FROM m_storage_detail sd"
              + " JOIN m_locator l ON l.m_locator_id = sd.m_locator_id"
              + " WHERE sd.m_product_id = m_product.m_product_id"
              + " AND l.m_warehouse_id IN (").append(buildInList(warehouseIds)).append("))");
        }
        if (selectedOrgId != null) {
          fromWhere.append(
              " AND EXISTS (SELECT 1 FROM m_storage_detail sd"
              + " JOIN m_locator l ON l.m_locator_id = sd.m_locator_id"
              + " WHERE sd.m_product_id = m_product.m_product_id"
              + " AND ad_isorgincluded(l.ad_org_id, '").append(selectedOrgId).append("'")
              .append(", m_product.ad_client_id) <> -1)");
        }
        if (!roleOrgIds.isEmpty()) {
          fromWhere.append(
              " AND EXISTS (SELECT 1 FROM m_storage_detail sd"
              + " JOIN m_locator l ON l.m_locator_id = sd.m_locator_id"
              + " WHERE sd.m_product_id = m_product.m_product_id"
              + " AND l.ad_org_id IN (").append(buildInList(roleOrgIds)).append("))");
        }
        break;

      case "warehouse":
        selectClause = "SELECT m_warehouse_id AS id, name, name AS label";
        fromWhere = new StringBuilder(
            "FROM m_warehouse WHERE isactive='Y' AND ad_client_id = :clientId AND name ILIKE :search");
        orderBy = "ORDER BY name";
        if (selectedOrgId != null) {
          fromWhere.append(
              " AND EXISTS (SELECT 1 FROM ad_org_warehouse ow"
              + " WHERE ow.m_warehouse_id = m_warehouse.m_warehouse_id"
              + " AND ow.ad_org_id = '").append(selectedOrgId).append("')");
        }
        if (!roleOrgIds.isEmpty()) {
          fromWhere.append(
              " AND EXISTS (SELECT 1 FROM ad_org_warehouse ow"
              + " WHERE ow.m_warehouse_id = m_warehouse.m_warehouse_id"
              + " AND ow.ad_org_id IN (").append(buildInList(roleOrgIds)).append("))");
        }
        break;

      case "project":
        selectClause = "SELECT c_project_id AS id, name, name AS label";
        fromWhere = new StringBuilder(
            "FROM c_project WHERE isactive='Y' AND ad_client_id = :clientId AND name ILIKE :search");
        orderBy = "ORDER BY name";
        break;

      case "org":
        selectClause = "SELECT ad_org_id AS id, name, name AS label";
        fromWhere = new StringBuilder(
            "FROM ad_org WHERE isactive='Y' AND ad_org_id != '0'"
            + " AND ad_client_id = :clientId AND name ILIKE :search");
        orderBy = "ORDER BY name";
        break;

      case "account":
        selectClause = "SELECT ev.value AS id,"
            + " ev.value || ' - ' || ev.name AS name,"
            + " ev.value || ' - ' || ev.name AS label";
        fromWhere = new StringBuilder(
            "FROM c_elementvalue ev WHERE ev.isactive='Y' AND ev.issummary='N'"
            + " AND ev.ad_client_id = :clientId"
            + " AND (ev.value ILIKE :search OR ev.name ILIKE :search)");
        orderBy = "ORDER BY ev.value";
        if (selectedAcctSchemaId != null) {
          fromWhere.append(
              " AND ev.c_element_id IN (SELECT c_element_id FROM c_acctschema_element"
              + " WHERE c_acctschema_id = '").append(selectedAcctSchemaId).append("'")
              .append(" AND c_element_id IS NOT NULL)");
        }
        break;

      case "accounting":
      case "acctschema":
        selectClause = "SELECT c_acctschema_id AS id, name, name AS label";
        fromWhere = new StringBuilder(
            "FROM c_acctschema WHERE isactive='Y' AND ad_client_id = :clientId AND name ILIKE :search");
        orderBy = "ORDER BY name";
        break;

      case "year":
        selectClause = "SELECT y.c_year_id AS id,"
            + " y.year || ' (' || c.name || ')' AS name,"
            + " y.year || ' (' || c.name || ')' AS label";
        fromWhere = new StringBuilder(
            "FROM c_year y JOIN c_calendar c ON c.c_calendar_id = y.c_calendar_id"
            + " WHERE y.isactive='Y' AND y.ad_client_id = :clientId"
            + " AND (y.year || ' (' || c.name || ')') ILIKE :search");
        orderBy = "ORDER BY y.year DESC";
        if (selectedOrgId != null) {
          fromWhere.append(
              " AND EXISTS (SELECT 1 FROM ad_org o"
              + " WHERE o.c_calendar_id = c.c_calendar_id AND o.ad_org_id = '")
              .append(selectedOrgId).append("')");
        }
        break;

      case "currency":
        // Currency is a global (cross-client) table; no ad_client_id filter.
        // The client's own currency is sorted first.
        // clientId is inlined (validated JWT claim) so COUNT and data queries
        // share the same fromWhere without needing a :clientId bound param.
        selectClause = "SELECT c_currency_id AS id, iso_code AS name,"
            + " iso_code || ' - ' || description AS label";
        fromWhere = new StringBuilder(
            "FROM c_currency WHERE isactive='Y'"
            + " AND (iso_code ILIKE :search OR description ILIKE :search)");
        orderBy = "ORDER BY (CASE WHEN c_currency_id = "
            + "(SELECT c_currency_id FROM ad_client WHERE ad_client_id = '"
            + clientId + "') THEN 0 ELSE 1 END), iso_code";
        useClient = false;  // clientId inlined above, not a bound param
        break;

      case "tax":
        selectClause = "SELECT c_tax_id AS id, name, name AS label";
        fromWhere = new StringBuilder(
            "FROM c_tax WHERE isactive='Y' AND ad_client_id = :clientId AND name ILIKE :search");
        orderBy = "ORDER BY name";
        break;

      default:
        throw new IllegalArgumentException("Unknown selector type: " + type);
    }

    String countSql = "SELECT COUNT(*) " + fromWhere;
    String dataSql  = selectClause + " " + fromWhere + " " + orderBy;

    // Count
    NativeQuery<Object> countQuery = OBDal.getInstance().getSession().createNativeQuery(countSql);
    bindParams(countQuery, useSearch, useClient, q, clientId, type);
    Number countResult = (Number) countQuery.uniqueResult();
    int totalCount = countResult != null ? countResult.intValue() : 0;

    // Data
    NativeQuery<Object[]> dataQuery = OBDal.getInstance().getSession().createNativeQuery(dataSql);
    bindParams(dataQuery, useSearch, useClient, q, clientId, type);
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);
    List<Object[]> rows = dataQuery.list();

    JSONArray items = new JSONArray();
    for (Object[] row : rows) {
      JSONObject item = new JSONObject();
      item.put("id",    row[0] != null ? row[0].toString() : "");
      item.put("name",  row[1] != null ? row[1].toString() : "");
      item.put("label", row[2] != null ? row[2].toString() : "");
      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put("items", items);
    result.put("totalCount", totalCount);
    result.put("hasMore", offset + rows.size() < totalCount);
    return result;
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

  @SuppressWarnings("rawtypes")
  private void bindParams(NativeQuery query, boolean useSearch, boolean useClient,
      String q, String clientId, String type) {
    if (useSearch) {
      query.setParameter("search", "%" + q + "%");
    }
    if (useClient) {
      query.setParameter("clientId", clientId);
    }
  }

  /**
   * Builds a safe SQL IN-list from a list of Etendo IDs.
   * Only IDs that match the hex/UUID pattern are included.
   */
  private String buildInList(List<String> ids) {
    return ids.stream()
        .filter(id -> id != null && id.matches("[0-9A-Fa-f\\-]+") && id.length() <= 36)
        .map(id -> "'" + id + "'")
        .collect(Collectors.joining(","));
  }

  /**
   * Returns the ID only if it is a valid Etendo hex/UUID string, null otherwise.
   */
  private String safeId(String id) {
    if (id == null) return null;
    String trimmed = id.trim();
    return (trimmed.matches("[0-9A-Fa-f\\-]+") && trimmed.length() <= 36) ? trimmed : null;
  }

  /**
   * Parses a comma-separated list of IDs, filtering to safe values only.
   */
  private List<String> parseSafeIdList(String param) {
    if (StringUtils.isBlank(param)) return List.of();
    return Arrays.stream(param.split(","))
        .map(String::trim)
        .map(this::safeId)
        .filter(id -> id != null)
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
