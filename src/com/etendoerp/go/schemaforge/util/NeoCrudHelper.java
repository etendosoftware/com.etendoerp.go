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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Helper class for CRUD operations in NeoServlet.
 * Extracted to reduce method count in the main servlet class.
 */
public class NeoCrudHelper {

  private static final Logger log = LogManager.getLogger(NeoCrudHelper.class);
  private static final String PARENT_ID_KEY = "parentId";
  private static final String NEO_ERROR_PREFIX = "__NEO_ERROR__:";

  private NeoCrudHelper() {
  }

  /**
   * Handle the default CRUD request lifecycle: build params, dispatch, parse response.
   *
   * @param context the NEO request context containing entity, method, body and query params
   * @return a NeoResponse with the result or an error
   */
  public static NeoResponse handleDefault(NeoContext context) {
    try {
      Tab adTab = context.getAdTab();
      if (adTab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "No AD_Tab linked to entity: " + context.getEntityName());
      }

      String dalEntityName = adTab.getTable().getName();
      DefaultJsonDataService jsonService = DefaultJsonDataService.getInstance();
      NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(
          context.getSfEntity(), dalEntityName);

      Map<String, String> params = buildBaseParams(context, adTab, dalEntityName);
      buildWhereClause(params, adTab, context);
      applyPaginationDefaults(params);

      String result = dispatchCrudMethod(context, adTab, dalEntityName,
          jsonService, fieldFilter, params);
      if (result == null) {
        return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Unsupported method: " + context.getHttpMethod());
      }

      if (result.startsWith(NEO_ERROR_PREFIX)) {
        String[] parts = result.substring(NEO_ERROR_PREFIX.length()).split(":", 2);
        return NeoResponse.error(Integer.parseInt(parts[0]), parts[1]);
      }

      return buildCrudResponse(result, context, fieldFilter);
    } catch (Exception e) {
      log.error("Error in default handler for {} {}", context.getHttpMethod(), context.getEntityName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Build the base parameter map for DefaultJsonDataService operations.
   */
  static Map<String, String> buildBaseParams(NeoContext context, Tab adTab, String dalEntityName) {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, dalEntityName);
    params.put(JsonConstants.TAB_PARAMETER, adTab.getId());
    params.put(JsonConstants.WINDOW_ID, adTab.getWindow().getId());
    params.put(JsonConstants.NO_ACTIVE_FILTER, "true");

    if (context.getRecordId() != null) {
      params.put(JsonConstants.ID, context.getRecordId());
    }

    if (context.getQueryParams() != null) {
      for (Map.Entry<String, String> entry : context.getQueryParams().entrySet()) {
        params.put(entry.getKey(), entry.getValue());
      }
    }
    return params;
  }

  private static final String NEO_WHERE_PARAM = "_neoWhere";

  /**
   * Build and apply the where clause (tab HQL + parent filter + client base filter) to the params map.
   * Supports a special {@code _neoWhere} query parameter that injects an additional HQL predicate,
   * merged with the tab's own HQL filter clause and any parent filter.
   */
  static void buildWhereClause(Map<String, String> params, Tab adTab, NeoContext context) {
    StringBuilder whereClause = new StringBuilder();

    String parentId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARENT_ID_KEY)
        : null;

    String tabWhere = adTab.getHqlwhereclause();
    if (StringUtils.isNotBlank(tabWhere)) {
      if (parentId != null && tabWhere.contains("@")) {
        tabWhere = tabWhere.replaceAll("@[A-Za-z_]+@", "'" + parentId.replace("'", "''") + "'");
      }
      whereClause.append("(").append(tabWhere).append(")");
    }
    if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      String parentFilter = NeoTypeCoercionHelper.buildParentWhereClause(adTab, parentId);
      if (StringUtils.isNotBlank(parentFilter)) {
        if (whereClause.length() > 0) {
          whereClause.append(" and ");
        }
        whereClause.append("(").append(parentFilter).append(")");
      }
    }

    String neoWhere = params.remove(NEO_WHERE_PARAM);
    if (StringUtils.isNotBlank(neoWhere)) {
      if (whereClause.length() > 0) {
        whereClause.append(" and ");
      }
      whereClause.append("(").append(neoWhere).append(")");
    }

    if (whereClause.length() > 0) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, whereClause.toString());
      params.put(JsonConstants.USE_ALIAS, "true");
    }
  }

  /**
   * Apply default pagination parameters if not provided by the client.
   */
  static void applyPaginationDefaults(Map<String, String> params) {
    if (!params.containsKey(JsonConstants.STARTROW_PARAMETER)) {
      params.put(JsonConstants.STARTROW_PARAMETER, "0");
    }
    if (!params.containsKey(JsonConstants.ENDROW_PARAMETER)) {
      params.put(JsonConstants.ENDROW_PARAMETER, "100");
    }
  }

  /**
   * Dispatch to the appropriate CRUD operation based on the HTTP method.
   * Returns the JSON result string, or null for unsupported methods.
   * Returns a special "__NEO_ERROR__:status:message" string for validation errors.
   */
  static String dispatchCrudMethod(NeoContext context, Tab adTab, String dalEntityName,
      DefaultJsonDataService jsonService, NeoFieldFilter fieldFilter,
      Map<String, String> params) throws Exception {
    switch (context.getHttpMethod()) {
      case "GET":
        return jsonService.fetch(params);
      case "POST":
        return handlePost(context, adTab, dalEntityName, jsonService, fieldFilter, params);
      case "PUT":
      case "PATCH":
        return handlePutOrPatch(context, dalEntityName, jsonService, fieldFilter, params);
      case "DELETE":
        return jsonService.remove(params);
      default:
        return null;
    }
  }

  /**
   * Handle POST (create) request: resolve parentId, filter body, inject defaults,
   * execute callout cascade, and wrap for SmartClient.
   */
  static String handlePost(NeoContext context, Tab adTab, String dalEntityName,
      DefaultJsonDataService jsonService, NeoFieldFilter fieldFilter,
      Map<String, String> params) throws Exception {
    if (context.getRecordId() != null) {
      return NEO_ERROR_PREFIX + HttpServletResponse.SC_BAD_REQUEST
          + ":POST (create) must not include a record ID in the URL";
    }

    JSONObject requestBody = context.getRequestBody();
    String parentIdValue = resolveAndMapParentId(requestBody, adTab);

    JSONObject filteredBody = fieldFilter.filterCreateRequest(requestBody);
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);

    executePostCalloutCascade(filteredBody, adTab, context, parentIdValue);

    String wrappedBody = NeoTypeCoercionHelper.wrapForSmartclient(
        filteredBody, dalEntityName, null);
    return jsonService.add(params, wrappedBody);
  }

  /**
   * Resolve the parentId from the request body and map it to the actual FK property name.
   */
  static String resolveAndMapParentId(JSONObject requestBody, Tab adTab) throws Exception {
    if (requestBody == null || !requestBody.has(PARENT_ID_KEY)) {
      return null;
    }
    String parentIdValue = requestBody.getString(PARENT_ID_KEY);
    requestBody.remove(PARENT_ID_KEY);

    if (adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      Entity dalEnt = ModelProvider.getInstance()
          .getEntityByTableName(adTab.getTable().getDBTableName());
      if (dalEnt != null) {
        mapParentIdToFkColumn(requestBody, adTab, dalEnt, parentIdValue);
      }
    }
    return parentIdValue;
  }

  /**
   * Find the link-to-parent column and set the parentId value on its DAL property name.
   */
  static void mapParentIdToFkColumn(JSONObject requestBody, Tab adTab,
      Entity dalEnt, String parentIdValue) throws Exception {
    for (Column col : adTab.getTable().getADColumnList()) {
      if (!col.isLinkToParentColumn() || !col.isActive()) {
        continue;
      }
      for (Property prop : dalEnt.getProperties()) {
        if (StringUtils.equalsIgnoreCase(prop.getColumnName(), col.getDBColumnName())) {
          requestBody.put(prop.getName(), parentIdValue);
          return;
        }
      }
    }
  }

  /**
   * Execute the callout cascade for POST requests on header tabs (level 0).
   */
  static void executePostCalloutCascade(JSONObject filteredBody, Tab adTab,
      NeoContext context, String parentIdValue) {
    if (adTab == null || adTab.getTabLevel() == null || adTab.getTabLevel() != 0) {
      return;
    }
    Set<String> seqFields = new HashSet<>();
    NeoDefaultsService.executeCalloutCascade(context, adTab, filteredBody, seqFields);
    NeoDefaultsService.reapplyDocTypeFromTabFilter(filteredBody, adTab, context);
    NeoDefaultsService.removeEmptyFkValues(filteredBody, adTab);
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
  }

  /**
   * Handle PUT/PATCH (update) request: validate recordId, filter body, and wrap for SmartClient.
   */
  static String handlePutOrPatch(NeoContext context, String dalEntityName,
      DefaultJsonDataService jsonService, NeoFieldFilter fieldFilter,
      Map<String, String> params) throws Exception {
    if (context.getRecordId() == null) {
      return NEO_ERROR_PREFIX + HttpServletResponse.SC_BAD_REQUEST
          + ":" + context.getHttpMethod() + " requires a record ID in the URL";
    }
    JSONObject filteredBody = fieldFilter.filterWriteRequest(context.getRequestBody());
    String wrappedBody = NeoTypeCoercionHelper.wrapForSmartclient(
        filteredBody, dalEntityName, context.getRecordId());
    return jsonService.update(params, wrappedBody);
  }

  /**
   * Build the final NeoResponse from the raw JSON result string.
   * Checks for error/validation responses and applies field filtering.
   *
   * @param result      the raw JSON string returned by DefaultJsonDataService
   * @param context     the NEO request context
   * @param fieldFilter the field filter to apply to the response
   * @return a NeoResponse with filtered data or an error
   * @throws Exception if JSON parsing fails
   */
  public static NeoResponse buildCrudResponse(String result, NeoContext context,
      NeoFieldFilter fieldFilter) throws Exception {
    JSONObject responseJson = new JSONObject(result);

    NeoResponse errorResponse = checkForServiceErrors(responseJson);
    if (errorResponse != null) {
      return errorResponse;
    }

    fieldFilter.filterGetResponse(responseJson);

    if ("GET".equals(context.getHttpMethod()) && context.getSfEntity() != null) {
      NeoListIdentifierHelper.enrichListIdentifiers(responseJson, context.getSfEntity());
    }

    return NeoResponse.ok(responseJson);
  }

  /**
   * Check the DefaultJsonDataService response for error or validation error status.
   * Returns an error NeoResponse if found, null otherwise.
   */
  static NeoResponse checkForServiceErrors(JSONObject responseJson) throws Exception {
    JSONObject innerResponse = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (innerResponse == null) {
      return null;
    }
    int status = innerResponse.optInt(JsonConstants.RESPONSE_STATUS, 0);
    if (status == JsonConstants.RPCREQUEST_STATUS_FAILURE) {
      String errMsg = innerResponse.has(JsonConstants.RESPONSE_ERROR)
          ? innerResponse.getJSONObject(JsonConstants.RESPONSE_ERROR)
              .optString("message", "Write operation failed")
          : "Write operation failed";
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          OBMessageUtils.messageBD(errMsg));
    }
    if (status == JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, responseJson);
    }
    return null;
  }
}
