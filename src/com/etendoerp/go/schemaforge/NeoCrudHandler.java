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

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.NeoListIdentifierHelper;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * Handles all CRUD operations for NEO window entity endpoints.
 *
 * <p>Extracted from NeoServlet to keep that class within SonarQube's method-count limit.
 * Receives a reference to the owning servlet for shared infrastructure
 * (hook dispatch, entity lookup, request/response helpers).</p>
 */
class NeoCrudHandler {

  private static final Logger log = LogManager.getLogger(NeoCrudHandler.class);

  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_PATCH = "PATCH";
  private static final String PARAM_PARENT_ID = "parentId";
  private static final Set<String> CONTACTS_PRECREATE_BILLING_FIELDS = new HashSet<>(
      Arrays.asList(
          "priceList",
          "paymentMethod",
          "paymentTerms",
          "account",
          "customerBlocking",
          "purchasePricelist",
          "pOPaymentMethod",
          "pOPaymentTerms",
          "pOFinancialAccount",
          "vendorBlocking"));

  private final NeoServlet servlet;

  NeoCrudHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Handles CRUD operations on a window entity: resolves the entity, validates the method,
   * builds context (including request body), and dispatches to default or hooked handler.
   */
  void handleWindowEntityCrud(SFSpec spec, NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    SFEntity entity = servlet.findEntity(spec.getId(), pathInfo.entityName);
    if (entity == null) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Entity not found in spec: " + pathInfo.entityName);
      return;
    }
    boolean methodEnabled = isMethodEnabled(method, entity);
    if (!methodEnabled) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          method + " not enabled for " + pathInfo.entityName);
      return;
    }
    Tab adTab = entity.getADTab();
    Map<String, String> queryParams = servlet.extractQueryParams(request);
    NeoContext neoContext = NeoContext.builder()
        .specName(pathInfo.specName)
        .entityName(pathInfo.entityName)
        .httpMethod(method)
        .recordId(pathInfo.recordId)
        .queryParams(queryParams)
        .adTab(adTab)
        .sfEntity(entity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();
    if ("POST".equals(method) || "PUT".equals(method) || METHOD_PATCH.equals(method)) {
      neoContext = parseAndAttachRequestBody(neoContext, request, response);
      if (neoContext == null) {
        return;
      }
    }
    NeoResponse neoResponse = dispatchCrudRequest(entity, neoContext, request, response);
    if (neoResponse != null) {
      servlet.writeResponse(response, neoResponse);
    }
  }

  /**
   * Reads the HTTP request body, parses it as JSON, and returns a new NeoContext with the body
   * attached. Returns null and sends a 400 error response if the body is malformed.
   */
  private NeoContext parseAndAttachRequestBody(NeoContext neoContext,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (StringUtils.isBlank(bodyStr)) {
        return neoContext;
      }
      return NeoContext.builder()
          .specName(neoContext.getSpecName())
          .entityName(neoContext.getEntityName())
          .httpMethod(neoContext.getHttpMethod())
          .recordId(neoContext.getRecordId())
          .requestBody(new JSONObject(bodyStr))
          .queryParams(neoContext.getQueryParams())
          .adTab(neoContext.getAdTab())
          .sfEntity(neoContext.getSfEntity())
          .obContext(neoContext.getObContext())
          .endpointType(neoContext.getEndpointType())
          .build();
    } catch (Exception e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return null;
    }
  }

  /**
   * Dispatches a CRUD request to the appropriate handler (hooked or default).
   */
  private NeoResponse dispatchCrudRequest(SFEntity entity, NeoContext neoContext,
      HttpServletRequest request, HttpServletResponse response) {
    String javaQualifier = entity.getJavaQualifier();
    if (StringUtils.isNotBlank(javaQualifier)) {
      return servlet.handleWithHooks(javaQualifier, neoContext, request, response);
    }
    return handleDefault(neoContext);
  }

  /**
   * Returns true if the given HTTP method is enabled on the entity's configuration.
   */
  private boolean isMethodEnabled(String method, SFEntity entity) {
    if ("GET".equals(method)) {
      return Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID());
    } else if ("POST".equals(method)) {
      return Boolean.TRUE.equals(entity.isPost());
    } else if ("PUT".equals(method)) {
      return Boolean.TRUE.equals(entity.isPut());
    } else if (METHOD_PATCH.equals(method)) {
      return Boolean.TRUE.equals(entity.isPatch());
    } else if (METHOD_DELETE.equals(method)) {
      return Boolean.TRUE.equals(entity.isDelete());
    }
    return false;
  }

  /**
   * Executes the default DAL-based handler for a CRUD request.
   */
  NeoResponse handleDefault(NeoContext context) {
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
      Map<String, String> params = buildDalParams(context, adTab, dalEntityName);

      return executeJsonServiceAndBuildResponse(
          context, adTab, dalEntityName, fieldFilter, jsonService, params);
    } catch (Exception e) {
      log.error("Error in default handler for {} {}", context.getHttpMethod(), context.getEntityName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Builds the DAL parameter map for a request, including tab metadata,
   * record ID, query params, where clause, and pagination defaults.
   */
  private Map<String, String> buildDalParams(NeoContext context, Tab adTab, String dalEntityName) {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, dalEntityName);
    params.put(JsonConstants.TAB_PARAMETER, adTab.getId());
    params.put(JsonConstants.WINDOW_ID, adTab.getWindow().getId());
    params.put(JsonConstants.NO_ACTIVE_FILTER, "true");

    if (context.getRecordId() != null) {
      params.put(JsonConstants.ID, context.getRecordId());
    }
    if (context.getQueryParams() != null) {
      params.putAll(context.getQueryParams());
    }

    String parentId = context.getQueryParams() != null
        ? context.getQueryParams().get(PARAM_PARENT_ID)
        : null;

    applyWhereClause(params, adTab, parentId);
    applyPaginationDefaults(params);
    return params;
  }

  /**
   * Builds the HQL where clause from the tab filter and parent filter, and adds it to params.
   */
  private void applyWhereClause(Map<String, String> params, Tab adTab, String parentId) {
    StringBuilder where = new StringBuilder();
    String tabWhere = adTab.getHqlwhereclause();
    if (StringUtils.isNotBlank(tabWhere)) {
      if (parentId != null && tabWhere.contains("@")) {
        tabWhere = tabWhere.replaceAll("@[A-Za-z_.]+@", "'" + parentId.replace("'", "''") + "'");
      }
      where.append("(").append(tabWhere).append(")");
    }
    if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      String parentFilter = resolveParentFilter(adTab, parentId);
      if (StringUtils.isNotBlank(parentFilter)) {
        if (where.length() > 0) {
          where.append(" and ");
        }
        where.append("(").append(parentFilter).append(")");
      }
    }
    String neoWhere = params.remove(NeoCrudHelper.NEO_WHERE_PARAM);
    if (StringUtils.isNotBlank(neoWhere)) {
      if (where.length() > 0) {
        where.append(" and ");
      }
      where.append("(").append(neoWhere).append(")");
    }
    if (where.length() > 0) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, where.toString());
      params.put(JsonConstants.USE_ALIAS, "true");
    }
  }

  /**
   * Applies default pagination parameters if not already provided by the caller.
   */
  private void applyPaginationDefaults(Map<String, String> params) {
    if (!params.containsKey(JsonConstants.STARTROW_PARAMETER)) {
      params.put(JsonConstants.STARTROW_PARAMETER, "0");
    }
    if (!params.containsKey(JsonConstants.ENDROW_PARAMETER)) {
      params.put(JsonConstants.ENDROW_PARAMETER, "100");
    }
  }

  /**
   * Executes the appropriate JSON service operation (fetch/add/update/remove),
   * checks the response for errors, filters the response fields, and returns the NeoResponse.
   */
  private NeoResponse executeJsonServiceAndBuildResponse(NeoContext context, Tab adTab,
      String dalEntityName, NeoFieldFilter fieldFilter,
      DefaultJsonDataService jsonService, Map<String, String> params) throws Exception {
    String result;
    switch (context.getHttpMethod()) {
      case "GET":
        result = jsonService.fetch(params);
        break;
      case "POST": {
        NeoResponse earlyError = validatePostRequest(context);
        if (earlyError != null) {
          return earlyError;
        }
        result = executePostCreate(context, adTab, dalEntityName, fieldFilter, jsonService, params);
        break;
      }
      case "PUT":
      case METHOD_PATCH: {
        NeoResponse earlyError = validateUpdateRequest(context);
        if (earlyError != null) {
          return earlyError;
        }
        result = executeUpdate(context, dalEntityName, fieldFilter, jsonService, params);
        break;
      }
      case METHOD_DELETE:
        result = jsonService.remove(params);
        break;
      default:
        return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Unsupported method: " + context.getHttpMethod());
    }

    JSONObject responseJson = new JSONObject(result);
    NeoResponse errorResponse = checkJsonServiceResponse(responseJson);
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
   * Validates a POST request. Returns an error NeoResponse if invalid, null if the request is OK.
   */
  private NeoResponse validatePostRequest(NeoContext context) {
    if (context.getRecordId() != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "POST (create) must not include a record ID in the URL");
    }
    return null;
  }

  /**
   * Validates a PUT/PATCH request. Returns an error NeoResponse if invalid, null if OK.
   */
  private NeoResponse validateUpdateRequest(NeoContext context) {
    if (context.getRecordId() == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          context.getHttpMethod() + " requires a record ID in the URL");
    }
    return null;
  }

  /**
   * Validates the response for error/validation-error status codes.
   * Returns an error NeoResponse if a failure is detected, or null if the response is OK.
   */
  private NeoResponse checkJsonServiceResponse(JSONObject responseJson) throws Exception {
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

  /**
   * Executes the POST (create) JSON service operation and returns the raw result string.
   */
  private String executePostCreate(NeoContext context, Tab adTab, String dalEntityName,
      NeoFieldFilter fieldFilter, DefaultJsonDataService jsonService,
      Map<String, String> params) throws Exception {
    JSONObject requestBody = context.getRequestBody();
    String parentIdValue = null;
    if (requestBody != null && requestBody.has(PARAM_PARENT_ID)) {
      parentIdValue = requestBody.getString(PARAM_PARENT_ID);
      requestBody.remove(PARAM_PARENT_ID);
      injectParentIdAsProperty(adTab, requestBody, parentIdValue);
    }
    // Use filterCreateRequest (not filterWriteRequest): readOnly fields must pass through
    // on create because they often carry values set by callouts or defaults (e.g., taxCategory
    // populated from productCategory). filterWriteRequest is for PATCH/PUT only.
    JSONObject filteredBody = fieldFilter.filterCreateRequest(requestBody);
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
    executePostCalloutCascade(filteredBody, adTab, context, parentIdValue);
    NeoDefaultsService.injectProductDerivedUomIfMissing(filteredBody);
    NeoDefaultsService.injectGrossAmountIfMissing(filteredBody);
    // TODO move this compatibility rule into the shared create-defaults helper once the merge settles.
    stripContactsPreCreateBillingDefaults(filteredBody, context, adTab);
    // Coerce String primitives to correct Java types. Utility.getDefault() always returns String,
    // but JsonToDataConverter has no String→BigDecimal/Integer/Boolean path — it falls through to
    // return value, causing OBDal to reject the String with a type mismatch error.
    coerceBodyTypes(filteredBody, dalEntityName);
    // Remove 'id' after mandatory-default injection — injectMandatoryDefaults may accidentally
    // inject a fallback FK record ID for the PK column, which would cause DefaultJsonDataService
    // to UPDATE an existing record instead of INSERT a new one.
    if (filteredBody != null) {
      filteredBody.remove("id");
    }
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, null);
    return jsonService.add(params, wrappedBody);
  }

  private void executePostCalloutCascade(JSONObject filteredBody, Tab adTab,
      NeoContext context, String parentIdValue) {
    if (adTab == null || adTab.getTabLevel() == null || adTab.getTabLevel() != 0) {
      return;
    }

    Set<String> seqFields = new HashSet<>();
    Iterator<String> bodyKeys = filteredBody.keys();
    while (bodyKeys.hasNext()) {
      String key = bodyKeys.next();
      Object value = filteredBody.opt(key);
      if (value instanceof String) {
        String stringValue = (String) value;
        if (stringValue.startsWith("<") && stringValue.endsWith(">") && stringValue.length() > 2) {
          seqFields.add(key);
        }
      }
    }

    NeoDefaultsCascadeHelper.executeCalloutCascade(context, adTab, filteredBody, seqFields);
    DocTypeResolver.reapplyDocTypeFromTabFilter(filteredBody, adTab, context);
    NeoDefaultsCascadeHelper.removeEmptyFkValues(filteredBody, adTab);
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue);
  }

  /**
   * Maps the generic "parentId" value to the actual FK property name on the child entity.
   */
  private void injectParentIdAsProperty(Tab adTab, JSONObject requestBody, String parentIdValue) {
    if (adTab.getTabLevel() == null || adTab.getTabLevel() <= 0) {
      return;
    }
    Entity dalEnt = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEnt == null) {
      return;
    }
    for (Column col : adTab.getTable().getADColumnList()) {
      if (col.isLinkToParentColumn() && col.isActive()) {
        try {
          Property prop = dalEnt.getPropertyByColumnName(col.getDBColumnName());
          if (prop != null) {
            requestBody.put(prop.getName(), parentIdValue);
            break;
          }
        } catch (Exception ex) {
          log.debug("Could not resolve parent column for '{}': {}", col.getDBColumnName(), ex.getMessage());
        }
      }
    }
  }

  private void stripContactsPreCreateBillingDefaults(JSONObject body, NeoContext context, Tab adTab) {
    if (body == null || context == null || adTab == null) {
      return;
    }
    if (!("contacts".equalsIgnoreCase(context.getSpecName())
        && "businessPartner".equals(context.getEntityName())
        && adTab.getTabLevel() != null
        && adTab.getTabLevel() == 0)) {
      return;
    }

    for (String key : CONTACTS_PRECREATE_BILLING_FIELDS) {
      body.remove(key);
      body.remove(key + "$_identifier");
    }
  }

  /**
   * Executes the PUT/PATCH (update) JSON service operation and returns the raw result string.
   */
  private String executeUpdate(NeoContext context, String dalEntityName,
      NeoFieldFilter fieldFilter, DefaultJsonDataService jsonService,
      Map<String, String> params) throws Exception {
    JSONObject filteredBody = fieldFilter.filterWriteRequest(context.getRequestBody());
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
    return jsonService.update(params, wrappedBody);
  }

  /**
   * Coerces String values in the body to the correct Java primitive types based on the DAL entity.
   *
   * <p>{@code Utility.getDefault()} always returns String regardless of the column type.
   * {@code JsonToDataConverter.convertJsonToPropertyValue()} has no String→BigDecimal path — it
   * falls through to {@code return value}, causing OBDal to reject the String with a type mismatch.
   * This method converts String "0", "1", etc. to the proper type (Double for BigDecimal columns,
   * Integer for Long/Integer columns, Boolean for Boolean columns) so that
   * {@code JsonToDataConverter} can coerce them correctly.</p>
   */
  @SuppressWarnings("unchecked")
  private void coerceBodyTypes(JSONObject body, String dalEntityName) {
    if (body == null || dalEntityName == null) {
      return;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntity(dalEntityName);
      if (entity == null) {
        return;
      }
      Map<String, Object> replacements = new HashMap<>();
      Iterator<String> keys = body.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object raw = body.opt(key);
        if (!(raw instanceof String)) {
          continue; // already a typed value — no coercion needed
        }
        String strVal = (String) raw;
        if (strVal.isEmpty()) {
          continue;
        }
        Property prop = null;
        try {
          prop = entity.getProperty(key);
        } catch (Exception e) {
          continue; // not a known property (metadata key, etc.)
        }
        if (prop == null || !prop.isPrimitive()) {
          continue;
        }
        Class<?> type = prop.getPrimitiveObjectType();
        if (type == BigDecimal.class) {
          // Send as Double — JsonToDataConverter line 195 converts Double→BigDecimal.valueOf(d).
          try {
            replacements.put(key, Double.parseDouble(strVal));
          } catch (NumberFormatException e) {
            log.debug("Cannot coerce '{}' to Double for property {}", strVal, key);
          }
        } else if (type == Long.class) {
          try {
            replacements.put(key, Long.parseLong(strVal));
          } catch (NumberFormatException e) {
            log.debug("Cannot coerce '{}' to Long for property {}", strVal, key);
          }
        } else if (type == Integer.class) {
          try {
            replacements.put(key, Integer.parseInt(strVal));
          } catch (NumberFormatException e) {
            log.debug("Cannot coerce '{}' to Integer for property {}", strVal, key);
          }
        } else if (type == Boolean.class) {
          replacements.put(key, "Y".equals(strVal) || "true".equalsIgnoreCase(strVal));
        }
      }
      for (Map.Entry<String, Object> entry : replacements.entrySet()) {
        body.put(entry.getKey(), entry.getValue());
      }
    } catch (Exception e) {
      log.debug("Error coercing body types for entity {}: {}", dalEntityName, e.getMessage());
    }
  }

  /**
   * Wraps a flat JSON body into the structure expected by DefaultJsonDataService:
   * {@code {"data": {fields..., "_entityName": dalEntityName, "id": recordId}}}
   *
   * <p>DefaultJsonDataService.getContentAsJSON() calls jsonObject.get("data"),
   * so the content MUST be wrapped in a "data" envelope. Additionally,
   * the "_entityName" property is required for OBDal to resolve the entity,
   * and "id" is required for updates.</p>
   *
   * @param filteredBody the filtered request body (flat JSON from client)
   * @param dalEntityName the DAL entity name (e.g. "Order")
   * @param recordId the record ID for updates, or null for creates
   * @return the wrapped JSON string ready for DefaultJsonDataService
   */
  private String wrapForSmartclient(JSONObject filteredBody, String dalEntityName,
      String recordId) {
    return NeoTypeCoercionHelper.wrapForSmartclient(filteredBody, dalEntityName, recordId);
  }

  /**
   * Resolves the HQL filter expression that constrains child tab records by parent record ID.
   */
  private String resolveParentFilter(Tab childTab, String parentId) {
    try {
      NeoTypeCoercionHelper.ParentFilter parentFilter =
          NeoTypeCoercionHelper.buildParentWhereClause(childTab, parentId);
      return parentFilter != null ? parentFilter.resolveForStringApi() : null;
    } catch (Exception e) {
      log.error("Error resolving parent filter for tab '{}': {}", childTab.getName(), e.getMessage(), e);
      return null;
    }
  }
}
