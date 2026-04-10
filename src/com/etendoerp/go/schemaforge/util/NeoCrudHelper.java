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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
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
  /**
   * Internal-only HQL predicate — stripped from HTTP request params in {@link #buildBaseParams}
   * to prevent HQL injection. Trusted internal code (e.g. hooks) may still inject it into the
   * params map after {@code buildBaseParams} returns, and the where-clause builder will consume it.
   */
  public static final String NEO_WHERE_PARAM = "_neoWhere";

  private NeoCrudHelper() {
  }

  /**
   * Builds the base parameter map required by {@code DefaultJsonDataService} operations.
   * Includes entity name, tab ID, window ID, and active-record filter; also copies any
   * additional query parameters (filters, pagination, sorting) from the request context.
   *
   * @param context        the current NEO request context, used to read the record ID and query params
   * @param adTab          the AD_Tab linked to the entity, used to resolve tab and window IDs
   * @param dalEntityName  the DAL entity name (e.g. {@code "Order"}) required by DefaultJsonDataService
   * @return a mutable parameter map ready to be passed to {@code DefaultJsonDataService} fetch/add/update/remove
   */
  public static Map<String, String> buildBaseParams(NeoContext context, Tab adTab, String dalEntityName) {
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
        // _neoWhere is an internal-only predicate injected by hooks/handlers after params are built.
        // Stripping it here prevents HQL injection via HTTP request parameters.
        if (!NEO_WHERE_PARAM.equals(entry.getKey())) {
          params.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return params;
  }

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
        tabWhere = tabWhere.replaceAll("@[A-Za-z_.]+@", "'" + parentId.replace("'", "''") + "'");
      }
      whereClause.append("(").append(tabWhere).append(")");
    }
    if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      NeoTypeCoercionHelper.ParentFilter parentFilter =
          NeoTypeCoercionHelper.buildParentWhereClause(adTab, parentId);
      if (parentFilter != null) {
        if (whereClause.length() > 0) {
          whereClause.append(" and ");
        }
        whereClause.append("(").append(parentFilter.resolveForStringApi()).append(")");
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
   * Resolves the {@code parentId} field from the request body and maps it to the actual
   * DAL FK property name of the child entity (e.g. {@code parentId} → {@code salesOrder}
   * for {@code C_OrderLine}).
   *
   * <p>The generic {@code "parentId"} key is removed from {@code requestBody} and replaced
   * with the property name resolved by scanning the tab's link-to-parent column. If the tab
   * is a header (level 0) or no matching column is found, only the removal is performed.
   *
   * @param requestBody the mutable request JSON; may be {@code null} (returns {@code null})
   * @param adTab       the AD_Tab of the child entity; must not be {@code null}
   * @return the raw parentId string extracted from the body, or {@code null} if absent
   * @throws OBException  if {@code adTab} is {@code null}
   * @throws JSONException if the JSON body cannot be read or written
   */
  public static String resolveAndMapParentId(JSONObject requestBody, Tab adTab) throws JSONException {
    if (adTab == null) {
      throw new OBException("resolveAndMapParentId: adTab must not be null");
    }
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
      Entity dalEnt, String parentIdValue) throws JSONException {
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
   * Execute the callout cascade for POST requests on header tabs (tabLevel == 0) only.
   * Line tabs are excluded because the cascade does not have selector auxiliary values
   * (e.g., product_PSTD, product_PLIST) available at save time, which would cause
   * price callouts like SL_Order_Product to return 0 and overwrite the user-entered price.
   * The frontend callout (triggered on field change) handles price auto-fill for lines.
   */
  static void executePostCalloutCascade(JSONObject filteredBody, Tab adTab,
      NeoContext context, String parentIdValue) {
    if (adTab == null || adTab.getTabLevel() == null || adTab.getTabLevel() != 0) {
      return;
    }
    // Detect sequence preview fields already in the body (values wrapped in angle brackets,
    // e.g. "<1000371>"). The callout cascade must not overwrite these — the real sequence
    // number is consumed by DefaultJsonDataService.add() when it detects the brackets.
    Set<String> seqFields = new HashSet<>();
    Iterator<String> bodyKeys = filteredBody.keys();
    while (bodyKeys.hasNext()) {
      String key = bodyKeys.next();
      Object val = filteredBody.opt(key);
      if (val instanceof String) {
        String s = (String) val;
        if (s.startsWith("<") && s.endsWith(">") && s.length() > 2) {
          seqFields.add(key);
        }
      }
    }
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
