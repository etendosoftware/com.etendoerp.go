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
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.CorsUtils;

/**
 * Custom product selector for Physical Inventory lines.
 *
 * <p>The standard NEO product selector returns global stock data (across all warehouses),
 * which caused wrong {@code bookQuantity} values when the user's inventory was for a
 * warehouse other than the product's primary stock location. This servlet replaces it by
 * scoping the on-hand quantity to the warehouse of the given inventory header.
 *
 * <p>Registered at {@code /sws/neo/physical-inventory/inventoryLine/selectors/M_Product_ID}
 * via AD_MODEL_OBJECT / AD_MODEL_OBJECT_MAPPING. The exact-path mapping takes precedence over
 * the NeoServlet wildcard ({@code /sws/neo/*}), so this servlet handles the product selector
 * for this window only.
 *
 * <p>GET /sws/neo/physical-inventory/inventoryLine/selectors/M_Product_ID
 *   ?parentId={m_inventory_id}
 *   &q={searchText}
 *   &limit=20
 *   &offset=0
 *
 * <p>Response items: { id, label, searchKey,
 *   product_LOC, product_QTY, product_quantityOnHand,
 *   product_warehouse, product_storageBin }
 */
public class InventoryProductSelectorServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(InventoryProductSelectorServlet.class);
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 200;

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, OPTIONS", "Authorization, Content-Type", null, false);

    try {
      authenticateJwt(request);
    } catch (Exception e) {
      log.warn("[InventoryProductSelector] Auth failed: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
      return;
    }

    String parentId = StringUtils.trimToEmpty(request.getParameter("parentId"));
    if (parentId.isEmpty()) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "parentId is required");
      return;
    }

    String id = StringUtils.trimToEmpty(request.getParameter("id"));
    String q = StringUtils.trimToEmpty(
        StringUtils.defaultIfEmpty(request.getParameter("_q"), request.getParameter("q")));

    int startRow = Math.max(0, parseIntParam(request.getParameter("_startRow"), 0));
    int limit = parseIntParam(request.getParameter("limit"), 0);
    if (limit <= 0) {
      int endRow = parseIntParam(request.getParameter("_endRow"), startRow + DEFAULT_LIMIT - 1);
      limit = Math.max(0, Math.min(endRow - startRow + 1, MAX_LIMIT));
    }
    int offset = parseIntParam(request.getParameter("offset"), startRow);

    try {
      OBContext.setAdminMode();
      String clientId = OBContext.getOBContext().getCurrentClient().getId();

      InventoryLineHandler.LocatorInfo locInfo =
          InventoryLineHandler.resolveDefaultLocatorInfo(parentId);
      if (locInfo == null) {
        sendError(response, HttpServletResponse.SC_NOT_FOUND,
            "No default locator found for this inventory's warehouse");
        return;
      }

      JSONObject result = buildResponse(clientId, locInfo, id, q, limit, offset);
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(result.toString());

    } catch (Exception e) {
      log.error("[InventoryProductSelector] Error for parentId={}: {}", parentId, e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, "GET, OPTIONS", "Authorization, Content-Type", null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private JSONObject buildResponse(String clientId, InventoryLineHandler.LocatorInfo locInfo,
      String id, String q, int limit, int offset) throws Exception {

    boolean byId = !id.isEmpty();
    String searchPattern = "%" + q + "%";

    String idFilter = byId ? " AND p.m_product_id = ?" : "";
    String countSql = "SELECT COUNT(*) FROM m_product p"
        + " WHERE p.isactive = 'Y' AND p.ad_client_id = ?"
        + "   AND (p.name ILIKE ? OR p.value ILIKE ?)"
        + idFilter;

    String dataSql = "SELECT p.m_product_id, p.value, p.name,"
        + " COALESCE(SUM(sd.qtyonhand), 0) AS stock_qty"
        + " FROM m_product p"
        + " LEFT JOIN m_storage_detail sd"
        + "   ON sd.m_product_id = p.m_product_id"
        + "   AND sd.m_locator_id IN ("
        + "     SELECT m_locator_id FROM m_locator"
        + "     WHERE m_warehouse_id = ? AND isactive = 'Y')"
        + " WHERE p.isactive = 'Y' AND p.ad_client_id = ?"
        + "   AND (p.name ILIKE ? OR p.value ILIKE ?)"
        + idFilter
        + " GROUP BY p.m_product_id, p.value, p.name"
        + " ORDER BY p.value, p.name"
        + " LIMIT ? OFFSET ?";

    int totalCount;
    try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(countSql)) {
      ps.setString(1, clientId);
      ps.setString(2, searchPattern);
      ps.setString(3, searchPattern);
      if (byId) ps.setString(4, id);
      try (ResultSet rs = ps.executeQuery()) {
        totalCount = rs.next() ? rs.getInt(1) : 0;
      }
    }

    JSONArray items = new JSONArray();
    try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(dataSql)) {
      int i = 1;
      ps.setString(i++, locInfo.warehouseId);
      ps.setString(i++, clientId);
      ps.setString(i++, searchPattern);
      ps.setString(i++, searchPattern);
      if (byId) ps.setString(i++, id);
      ps.setInt(i++, limit);
      ps.setInt(i, offset);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String productId = rs.getString(1);
          String value     = rs.getString(2);
          String name      = rs.getString(3);
          double stockQty  = rs.getDouble(4);

          JSONObject item = new JSONObject();
          item.put("id",        productId);
          item.put("label",     name);
          item.put("searchKey", value);
          // Aux suffixes match Etendo selector convention: DataTable prefixes key name
          // → stored as product_QTY, product_LOC in snapshot → sent as auxiliaryValues
          // → CalloutRequestBuilder maps to inpmProductId_QTY, inpmProductId_LOC
          // → SL_Inventory_Product reads inpmProductId_QTY for qtybook/qtycount
          //   and inpmProductId_LOC for the default storage bin.
          item.put("QTY",  String.valueOf(stockQty));
          item.put("LOC",  locInfo.locatorId);
          item.put("PQTY", "0");
          items.put(item);
        }
      }
    }

    JSONObject result = new JSONObject();
    result.put("items",      items);
    result.put("columns",    new JSONArray());
    result.put("totalCount", totalCount);
    result.put("limit",      limit);
    result.put("offset",     offset);
    result.put("hasMore",    offset + items.length() < totalCount);
    return result;
  }

  private void authenticateJwt(HttpServletRequest request) throws Exception {
    NeoServletSupport.authenticateJwt(request);
  }

  private void sendError(HttpServletResponse response, int status, String message) throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", message);
      response.setStatus(status);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(body.toString());
    } catch (Exception ex) {
      log.error("[InventoryProductSelector] Failed to write error response", ex);
    }
  }

  private int parseIntParam(String value, int defaultVal) {
    if (StringUtils.isBlank(value)) return defaultVal;
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return defaultVal;
    }
  }
}
