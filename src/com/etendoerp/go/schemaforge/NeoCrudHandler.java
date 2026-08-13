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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.NeoDistinctFetchSupport;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;
import com.etendoerp.go.schemaforge.util.NeoListIdentifierHelper;
import com.etendoerp.go.schemaforge.util.NeoLocatorIdentifierHelper;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;
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
  private static final String CRITERIA_PARAM = "criteria";
  private static final String HQL_AND_OPERATOR = " and ";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
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
  private final NeoTelemetryService telemetryService;

  NeoCrudHandler(NeoServlet servlet) {
    this(servlet, NeoTelemetryService.runtime());
  }

  NeoCrudHandler(NeoServlet servlet, NeoTelemetryService telemetryService) {
    this.servlet = servlet;
    this.telemetryService = telemetryService == null ? NeoTelemetryService.runtime()
        : telemetryService;
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
    if (!NeoMethodPolicy.isMethodEnabled(entity, method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          NeoMethodPolicy.buildNotEnabledMessage(method, pathInfo.entityName));
      return;
    }
    Tab adTab = entity.getADTab();
    Map<String, String> queryParams = servlet.extractQueryParams(request);

    // Short-circuit for `?_distinct=<field>` on a list GET: returns the
    // paginated set of distinct values for the given scalar field, so UI
    // filter selectors can show all possible options without loading the
    // entire dataset or inventing them client-side. Runs through the same
    // tab-where + parent-filter + readable-client/org filtering as the
    // standard list fetch, just with a different projection.
    if ("GET".equals(method) && queryParams != null
        && StringUtils.isNotBlank(queryParams.get(JsonConstants.DISTINCT_PARAMETER))) {
      servlet.writeResponse(response, handleDistinctFetch(adTab, queryParams));
      return;
    }

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
      // Generic CSV export: when the GET carries export=csv, stream the rows the
      // handler produced as a CSV attachment instead of the JSON envelope.
      if ("GET".equals(method)
          && NeoCsvExportService.tryExport(neoResponse, queryParams, response)) {
        return;
      }
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
    if (NeoTelemetryService.isWriteMethod(neoContext.getHttpMethod())) {
      return telemetryService.measureBackendOperation(neoContext,
          () -> dispatchCrudRequestInternal(entity, neoContext, request, response));
    }
    return dispatchCrudRequestInternal(entity, neoContext, request, response);
  }

  private NeoResponse dispatchCrudRequestInternal(SFEntity entity, NeoContext neoContext,
      HttpServletRequest request, HttpServletResponse response) {
    String javaQualifier = entity.getJavaQualifier();
    if (StringUtils.isNotBlank(javaQualifier)) {
      return servlet.handleWithHooks(javaQualifier, neoContext, request, response);
    }
    return handleDefault(neoContext);
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
      // Not DefaultJsonDataService.getInstance(): when a caller owns the transaction (a batch),
      // core's write path would commit this record on its own and defeat the caller's rollback
      // (IMP-23). NeoBatchJsonDataService decides which of the two applies to this thread.
      DefaultJsonDataService jsonService = BatchService.currentJsonService();
      NeoFieldFilter fieldFilter = NeoFieldFilter.forEntity(
          context.getSfEntity(), dalEntityName);
      Map<String, String> params = buildDalParams(context, adTab, dalEntityName);

      return executeJsonServiceAndBuildResponse(
          context, adTab, dalEntityName, fieldFilter, jsonService, params);
    } catch (MissingRequiredFieldsException e) {
      // ETP-3894: structured 400 lists the missing fields so the UI can highlight them.
      return buildMissingRequiredFieldsResponse(e);
    } catch (ReadOnlyFieldRejectedException e) {
      // IMP-28 clause 2: a value was sent for a field NeoFieldFilter.filterCreateRequest would
      // otherwise have silently dropped. Reject instead, so the caller sees why nothing changed.
      return buildReadOnlyFieldRejectedResponse(e);
    } catch (Exception e) {
      log.error("Error in default handler for {} {}", context.getHttpMethod(), context.getEntityName(), e);
      // ETP-4793 / IMP-17: same reclassification as the RPC-failure branch in
      // checkJsonServiceResponse, for the case where the violation is thrown rather than swallowed.
      // The column is read off the raw chain because sanitize() maps any DB exception to a generic
      // message, which is where the name would otherwise be lost.
      if (NeoErrorSanitizer.isNotNullViolation(e)) {
        String column = NeoErrorSanitizer.notNullViolationColumn(e);
        String fieldName = resolvePropertyNameForColumn(column, context.getAdTab());
        if (fieldName != null) {
          return buildMissingRequiredFieldsResponse(
              new MissingRequiredFieldsException(List.of(fieldName)));
        }
      }
      // A unique-constraint violation is a data conflict, not a server failure — the
      // client sent a value that already exists, which is a 409, never a 500. Reusing
      // this same classification is also what keeps the message worded consistently
      // with NeoErrorSanitizer.DUPLICATE_KEY_ERROR (see its javadoc for why the import
      // UI depends on that exact wording).
      int status = NeoErrorSanitizer.isDuplicateKeyViolation(e)
          ? HttpServletResponse.SC_CONFLICT
          : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      return NeoResponse.error(status, NeoErrorSanitizer.sanitize(e));
    }
  }

  /**
   * ETP-3894: build the structured 400 response returned when a create/update is rejected
   * because mandatory user-input fields are missing. Body shape:
   * <pre>{
   *   "error": {
   *     "code": "MISSING_REQUIRED_FIELDS",
   *     "message": "...",
   *     "fields": ["bpartner", "priceList", ...]
   *   }
   * }</pre>
   */
  private NeoResponse buildMissingRequiredFieldsResponse(MissingRequiredFieldsException e) {
    try {
      JSONArray fieldsArr = new JSONArray();
      for (String f : e.getFields()) {
        fieldsArr.put(f);
      }
      JSONObject errorObj = new JSONObject();
      errorObj.put("code", MissingRequiredFieldsException.ERROR_CODE);
      errorObj.put("status", HttpServletResponse.SC_BAD_REQUEST);
      errorObj.put("message", "Missing required fields");
      errorObj.put("fields", fieldsArr);
      JSONObject body = new JSONObject();
      body.put("error", errorObj);
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, body);
    } catch (Exception fallback) {
      log.warn("Could not build MISSING_REQUIRED_FIELDS body: {}", fallback.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MissingRequiredFieldsException.ERROR_CODE);
    }
  }

  /** HTTP-style status for a rejected read-only field write (IMP-28 clause 2, mirrors IMP-5). */
  private static final int STATUS_UNPROCESSABLE = 422;

  /**
   * IMP-28 clause 2: build the structured 422 response returned when a create is rejected
   * because the client tried to write a field that is read-only with no configured default
   * and no NeoHandler that could legitimately have supplied it. Body shape mirrors the flat
   * IMP-5 convention used by the MCP tool layer ({@code McpToolRouter}'s 422s) — status/error/
   * detail/field/hint/seeAlso — rather than the nested {@code {"error":{...}}} shape used by
   * {@link #buildMissingRequiredFieldsResponse}, since the two conventions' literal string
   * constants ({@code McpConstants}) live in a different, package-private class not
   * accessible from here; the key names below are copied verbatim rather than imported.
   * <pre>{
   *   "status": 422,
   *   "error": "read_only_field",
   *   "detail": "...",
   *   "field": "eTGOSalePrice",
   *   "hint": "...",
   *   "seeAlso": "docs(topic:\"creating records\")"
   * }</pre>
   */
  private NeoResponse buildReadOnlyFieldRejectedResponse(ReadOnlyFieldRejectedException e) {
    try {
      JSONObject errorObj = new JSONObject();
      errorObj.put("status", STATUS_UNPROCESSABLE);
      errorObj.put("error", "read_only_field");
      errorObj.put("detail", "Field '" + e.getFieldName()
          + "' is read-only and cannot be set by the caller; its value was rejected, not silently"
          + " dropped, so the write does not answer 200 with the field left unset.");
      errorObj.put("field", e.getFieldName());
      errorObj.put("hint", "Remove '" + e.getFieldName() + "' from the request. If this value must "
          + "be set, it is derived automatically (e.g. by a callout or a dedicated write path) — "
          + "check neo_schema's field descriptor for this entity before retrying.");
      errorObj.put("seeAlso", "docs(topic:\"creating records\")");
      return NeoResponse.error(STATUS_UNPROCESSABLE, errorObj);
    } catch (Exception fallback) {
      log.warn("Could not build READ_ONLY_FIELD_REJECTED body: {}", fallback.getMessage());
      return NeoResponse.error(STATUS_UNPROCESSABLE, ReadOnlyFieldRejectedException.ERROR_CODE);
    }
  }

  /**
   * ETP-4793 / IMP-17: turn a Postgres not-null violation into the same structured
   * {@code MISSING_REQUIRED_FIELDS} 400 a pre-flight validation failure produces.
   *
   * <p>What this replaces: omitting {@code partnerAddress} on a {@code sales-invoice} create used to
   * answer <b>500</b> with the raw violation, whose {@code detail} dumped the entire failing row —
   * ~90 columns of internals (IMP-23 §9.4). The status was wrong (the caller can fix this), the
   * payload leaked, and it named no field.</p>
   *
   * <p>The field name is best-effort: the violation names a DB column
   * ({@code c_bpartner_location_id}), so it is mapped back to the DAL property the caller actually
   * sends ({@code partnerAddress}) through the tab's entity model. When the column cannot be mapped
   * — or the server's locale hid it — the response still carries the corrected status and the
   * stripped message, with an empty {@code fields} array rather than a guess.</p>
   *
   * @param translated the already-translated violation message
   * @param adTab      the tab whose table the write targeted; may be {@code null}
   * @return the structured 400 response
   */
  private NeoResponse buildNotNullViolationResponse(String translated, Tab adTab) {
    String column = NeoErrorSanitizer.notNullViolationColumn(translated);
    String fieldName = resolvePropertyNameForColumn(column, adTab);
    List<String> fields = fieldName == null ? List.of() : List.of(fieldName);
    log.warn("Not-null violation on column '{}' mapped to field '{}'", column, fieldName);
    NeoResponse structured = buildMissingRequiredFieldsResponse(
        new MissingRequiredFieldsException(fields));
    if (fieldName != null) {
      return structured;
    }
    // Nothing to highlight, so the stripped message is the only actionable content left. Returned
    // instead of an empty `fields` list on its own, which would tell the caller nothing at all.
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        NeoErrorSanitizer.stripRowDump(NeoErrorSanitizer.redactObjectReferences(translated)));
  }

  /**
   * Maps a DB column name onto the DAL property name a NEO caller uses for it.
   *
   * @param column the DB column name; may be {@code null}
   * @param adTab  the tab whose table owns the column; may be {@code null}
   * @return the property name, or {@code null} when it cannot be resolved
   */
  private String resolvePropertyNameForColumn(String column, Tab adTab) {
    if (StringUtils.isBlank(column) || adTab == null || adTab.getTable() == null) {
      return null;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      for (Property prop : entity.getProperties()) {
        if (column.equalsIgnoreCase(prop.getColumnName())) {
          return prop.getName();
        }
      }
    } catch (Exception e) {
      log.debug("Could not map column {} to a property", column, e);
    }
    return null;
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

    normalizeBooleanCriteria(params, dalEntityName);

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
        tabWhere = resolveTabWhereTokens(adTab, tabWhere, parentId);
      }
      where.append("(").append(tabWhere).append(")");
    }
    if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      String parentFilter = resolveParentFilter(adTab, parentId);
      if (StringUtils.isNotBlank(parentFilter)) {
        if (where.length() > 0) {
          where.append(HQL_AND_OPERATOR);
        }
        where.append("(").append(parentFilter).append(")");
      }
    }
    String neoWhere = params.remove(NeoCrudHelper.NEO_WHERE_PARAM);
    if (StringUtils.isNotBlank(neoWhere)) {
      if (where.length() > 0) {
        where.append(HQL_AND_OPERATOR);
      }
      where.append("(").append(neoWhere).append(")");
    }
    if (where.length() > 0) {
      params.put(JsonConstants.WHERE_AND_FILTER_CLAUSE, where.toString());
    }
    params.put(JsonConstants.USE_ALIAS, "true");
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
    NeoResponse errorResponse = checkJsonServiceResponse(responseJson, adTab);
    if (errorResponse != null) {
      return errorResponse;
    }
    fieldFilter.filterGetResponse(responseJson);
    if ("GET".equals(context.getHttpMethod()) && context.getSfEntity() != null) {
      NeoListIdentifierHelper.enrichListIdentifiers(responseJson, context.getSfEntity());
      NeoLocatorIdentifierHelper.enrichLocatorIdentifiers(responseJson, context.getSfEntity());
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
  private NeoResponse checkJsonServiceResponse(JSONObject responseJson, Tab adTab) throws Exception {
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
      String translated = OBMessageUtils.messageBD(errMsg);
      // ETP-4793 / IMP-17: a not-null violation means the caller omitted a value the table
      // requires — their request to fix, so it is a 400 naming the field, never a 500. It reaches
      // here rather than the catch-all below because DefaultJsonDataService swallows the constraint
      // violation and returns it as an ordinary RPC failure body. Reusing ETP-3894's
      // MISSING_REQUIRED_FIELDS shape rather than inventing a second one keeps the React UI's
      // field-highlighting working on this path too, and gives neo_batch a 4xx it can map onto
      // IMP-24's `missingFields` envelope (IMP-23 §9.4).
      if (NeoErrorSanitizer.isNotNullViolationMessage(translated)) {
        return buildNotNullViolationResponse(translated, adTab);
      }
      // DefaultJsonDataService catches a unique-constraint violation internally and
      // returns it as a normal RPC failure response (this branch), never as a thrown
      // exception — so NeoErrorSanitizer.isDuplicateKeyViolation(Throwable), which only
      // inspects real exceptions, can't see it. Etendo's own translated message already
      // contains "must be unique" for this case, so that's what isDuplicateKeyMessage
      // checks instead. Same reasoning as the catch-all in handleDefault(): a duplicate
      // key is a data conflict (409), not a server failure (500).
      int httpStatus = NeoErrorSanitizer.isDuplicateKeyMessage(translated)
          ? HttpServletResponse.SC_CONFLICT
          : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      // Defence-in-depth: a DAL/validator failure message can carry a raw object toString
      // (e.g. a List-reference "one of the following values: pkg.Class@hex ..."). Strip it
      // before it reaches the client — the leak itself is built upstream in core (ETP-4668).
      // stripRowDump for the same defence-in-depth reason: any DAL failure message may carry a
      // Postgres `Failing row contains (…)` detail, which is both an internals leak and, for an MCP
      // agent that has to read it, a real context cost (ETP-4793 / IMP-17).
      return NeoResponse.error(httpStatus,
          NeoErrorSanitizer.stripRowDump(NeoErrorSanitizer.redactObjectReferences(translated)));
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
    // Snapshot the keys submitted by the user BEFORE injectMandatoryDefaults adds backend
    // defaults. The callout cascade is allowed to refine missing/derived fields, but it must
    // not overwrite values the user explicitly chose (e.g. paymentTerms manually changed in
    // the form after a businessPartner callout already ran).
    Set<String> userSubmittedFields = NeoCrudHelper.snapshotBodyFields(filteredBody);
    long perfTotalStart = System.nanoTime();
    long perfStart = perfTotalStart;
    // runCascade=false: the cascade is run explicitly right after by executePostCalloutCascade,
    // so we skip the duplicated pass embedded in injectMandatoryDefaults.
    NeoDefaultsService.injectMandatoryDefaults(filteredBody, adTab, context, parentIdValue, false);
    long perfInjectDefaults = System.nanoTime();
    Set<String> protectedCalloutFields = NeoCrudHelper.snapshotMandatoryBodyFields(filteredBody, adTab);
    protectedCalloutFields.addAll(userSubmittedFields);
    executePostCalloutCascade(filteredBody, adTab, context, parentIdValue, protectedCalloutFields);
    long perfCalloutCascade = System.nanoTime();
    // checkIfNotExists=false: a name the runtime model does not know must not blow up the create —
    // the policy simply abstains on a null entity.
    NeoCommercialLinePolicy.injectProductDerivedUomIfMissing(filteredBody,
        ModelProvider.getInstance().getEntity(dalEntityName, false),
        userSubmittedFields.contains("uOM"));
    NeoCommercialLinePolicy.injectGrossAmountIfMissing(filteredBody);
    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(filteredBody);
    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(filteredBody);
    stripContactsPreCreateBillingDefaults(filteredBody, context, adTab);
    // Coerce String primitives injected by injectMandatoryDefaults to their correct Java types.
    // Utility.getDefault() always returns String; JsonToDataConverter has no String→BigDecimal/
    // Integer/Boolean path and falls through to return value, causing OBDal type mismatches.
    NeoTypeCoercionHelper.coerceTypes(filteredBody, dalEntityName);
    // Remove 'id' after mandatory-default injection — injectMandatoryDefaults may accidentally
    // inject a fallback FK record ID for the PK column, which would cause DefaultJsonDataService
    // to UPDATE an existing record instead of INSERT a new one.
    if (filteredBody != null) {
      filteredBody.remove("id");
    }
    // ETP-3894: validate that all mandatory user-input fields have values after the full
    // resolution chain (defaults + session + parent + callout cascade). Throw a structured
    // exception so the UI can highlight the missing fields instead of letting Hibernate fail
    // with a generic not-null violation.
    List<String> missing = NeoMandatoryFieldValidator.findMissingMandatoryFields(filteredBody,
        adTab, userSubmittedFields);
    if (!missing.isEmpty()) {
      throw new MissingRequiredFieldsException(missing);
    }
    long perfPreSave = System.nanoTime();
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, null);
    String result = jsonService.add(params, wrappedBody);
    long perfEnd = System.nanoTime();
    log.info(
        "[NEO-PERF] executePostCreate entity={} injectDefaults={}ms calloutCascade={}ms otherDefaults+coerce={}ms jsonService.add={}ms total={}ms",
        dalEntityName,
        (perfInjectDefaults - perfStart) / 1_000_000L,
        (perfCalloutCascade - perfInjectDefaults) / 1_000_000L,
        (perfPreSave - perfCalloutCascade) / 1_000_000L,
        (perfEnd - perfPreSave) / 1_000_000L,
        (perfEnd - perfTotalStart) / 1_000_000L);
    return result;
  }

  private void executePostCalloutCascade(JSONObject filteredBody, Tab adTab,
      NeoContext context, String parentIdValue, Set<String> protectedFields) {
    if (adTab == null) {
      return;
    }
    // Cascade runs for ALL tab levels, not just headers (level=0). Child tabs
    // (invoice lines, order lines, ...) need their FK callouts (product → tax,
    // product → UOM, …) to fire server-side when the line is created via a
    // path that doesn't go through the React form's client-side callout
    // dispatch — e.g. the /batch endpoint or any external agent. The
    // protectedFields snapshot already guards values the caller supplied
    // explicitly, so UI flows that had already-derived fields stay correct.

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

    Set<String> effectiveProtected = protectedFields != null
        ? protectedFields
        : java.util.Collections.emptySet();
    long t0 = System.nanoTime();
    NeoDefaultsCascadeHelper.executeCalloutCascade(context, adTab, filteredBody, seqFields,
        effectiveProtected);
    long t1 = System.nanoTime();
    DocTypeResolver.reapplyDocTypeFromTabFilter(filteredBody, adTab, context, effectiveProtected);
    NeoDefaultsCascadeHelper.removeEmptyFkValues(filteredBody, adTab);
    long t2 = System.nanoTime();
    log.info(
        "[NEO-PERF]   executePostCalloutCascade calloutCascade={}ms docTypeReapply+removeEmpty={}ms",
        (t1 - t0) / 1_000_000L,
        (t2 - t1) / 1_000_000L);
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
    JSONObject rawBody = context.getRequestBody();
    // ETP-4531: capture accountingDate BEFORE filtering — filterWriteRequest/filterBody
    // does NOT return an independent copy, it mutates `rawBody` in place (filterRecord strips
    // non-writable keys directly from the same JSONObject reference it's given, when there's
    // no "data" wrapper to unwrap into a different object first). So `rawBody` and
    // `filteredBody` below end up being the SAME object once filtering runs — reading
    // `rawBody.has("accountingDate")` AFTER calling filterWriteRequest is always false,
    // because the filter already stripped it from that very same instance. Save the
    // pre-filter value first, while it still exists.
    Object accountingDateBeforeFilter = rawBody != null ? rawBody.opt(FIELD_ACCOUNTING_DATE) : null;
    boolean hadAccountingDate = rawBody != null && rawBody.has(FIELD_ACCOUNTING_DATE);
    JSONObject filteredBody = fieldFilter.filterWriteRequest(rawBody);
    // Inject lineNetAmount when absent from filteredBody (stripped by readOnly filter).
    // The frontend sends invoicedQuantity and unitPrice as editable fields, so both are
    // available here to compute the correct net amount even for products where SL_Invoice_Amt
    // throws on the sales invoice context (e.g. tax-exclusive price lists).
    NeoCommercialLinePolicy.injectLineNetAmountIfMissing(filteredBody);
    NeoCommercialLinePolicy.injectGrossAmountIfMissing(filteredBody);
    NeoCommercialLinePolicy.injectLineGrossAmountIfMissing(filteredBody);
    // ETP-4531: re-apply accountingDate now that filtering (which stripped it, correctly, as a
    // read-only field the CLIENT should never write directly) has run. Each header handler's
    // handle() pre-hook (e.g. AbstractInvoiceHeaderHandler#mirrorAccountingDate) mirrors the
    // document date into this field before this method runs; filterCreateRequest (used on
    // POST) already allows read-only fields through for exactly this reason ("they may carry
    // values from callouts or defaults") — PATCH/PUT's stricter writableFields filter needs
    // the same carve-out here. Uses the resolved DAL property name (e.g. "dateAcct"), not the
    // API key ("accountingDate") — filterWriteRequest already remapped every other field to
    // its DAL name via NeoFieldFilter#remapApiKeys, and DefaultJsonDataService only recognizes
    // DAL property names; injecting under the API key would silently no-op.
    if (hadAccountingDate) {
      filteredBody.put(fieldFilter.resolveWritablePropName(FIELD_ACCOUNTING_DATE), accountingDateBeforeFilter);
    }
    String wrappedBody = wrapForSmartclient(filteredBody, dalEntityName, context.getRecordId());
    return jsonService.update(params, wrappedBody);
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

  private static final Pattern TAB_WHERE_TOKEN_PATTERN = Pattern.compile("@([A-Za-z_.]+)@");

  /**
   * Replaces {@code @token@} placeholders in a tab HQL where clause using the actual column
   * values from the parent record rather than substituting all tokens with the same parentId.
   *
   * <p>Classic Etendo resolves each {@code @ColumnName@} against the corresponding column on the
   * parent record. NEO previously replaced every token with the same parentId, which broke cases
   * where a tab has two different tokens — e.g. {@code @AD_Org_ID@} (the parent's organization
   * UUID) and {@code @aeatsii_config_id@} (the parent record's primary key).</p>
   *
   * <p>Resolution order for each token:</p>
   * <ol>
   *   <li>{@code @AD_Org_ID@} / {@code @Org_ID@} → parent record's {@code organization.id}</li>
   *   <li>{@code @AD_Client_ID@} / {@code @Client_ID@} → parent record's {@code client.id}</li>
   *   <li>{@code @<tableName>_id@} → {@code parentId} (the parent PK)</li>
   *   <li>Any other token → matched against parent entity property column names</li>
   *   <li>Fallback → {@code parentId}</li>
   * </ol>
   */
  private String resolveTabWhereTokens(Tab adTab, String tabWhere, String parentId) {
    Tab parentTab = null;
    BaseOBObject parentRecord = null;
    Entity parentEntity = null;
    String parentTableName = null;

    try {
      parentTab = KernelUtils.getInstance().getParentTab(adTab);
      if (parentTab != null && parentTab.getTable() != null) {
        parentTableName = parentTab.getTable().getName().toLowerCase();
        parentEntity = ModelProvider.getInstance()
            .getEntityByTableId(parentTab.getTable().getId());
        if (parentEntity != null) {
          parentRecord = OBDal.getInstance().get(parentEntity.getName(), parentId);
        }
      }
    } catch (Exception e) {
      log.warn("Could not load parent record for tab '{}' parentId='{}': {}",
          adTab.getName(), parentId, e.getMessage());
    }

    final BaseOBObject finalParentRecord = parentRecord;
    final Entity finalParentEntity = parentEntity;
    final String finalParentTableName = parentTableName;

    Matcher matcher = TAB_WHERE_TOKEN_PATTERN.matcher(tabWhere);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String token = matcher.group(1);
      String value = resolveTokenFromParent(
          token, parentId, finalParentRecord, finalParentEntity, finalParentTableName);
      matcher.appendReplacement(result, Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Resolves a single {@code @token@} value from the parent record.
   */
  private String resolveTokenFromParent(String token, String parentId,
      BaseOBObject parentRecord, Entity parentEntity, String parentTableName) {
    if (parentRecord == null) {
      return parentId;
    }
    String tokenLower = token.toLowerCase(Locale.ROOT);

    if ("ad_org_id".equals(tokenLower) || "org_id".equals(tokenLower)) {
      return resolveRelatedObjectId(parentRecord, "organization", parentId);
    }

    if ("ad_client_id".equals(tokenLower) || "client_id".equals(tokenLower)) {
      return resolveRelatedObjectId(parentRecord, "client", parentId);
    }

    if (parentTableName != null && tokenLower.equals(parentTableName + "_id")) {
      return parentId;
    }

    if (parentEntity != null) {
      String entityValue = resolveTokenFromEntityProperty(token, parentRecord, parentEntity);
      return entityValue != null ? entityValue : parentId;
    }

    return parentId;
  }

  private String resolveRelatedObjectId(BaseOBObject parentRecord, String propertyName,
      String fallbackValue) {
    try {
      Object relatedObject = parentRecord.get(propertyName);
      if (relatedObject instanceof BaseOBObject) {
        return DalUtil.getId((BaseOBObject) relatedObject).toString();
      }
    } catch (Exception e) {
      log.debug("Could not resolve parent {} token", propertyName, e);
    }
    return fallbackValue;
  }

  private String resolveTokenFromEntityProperty(String token, BaseOBObject parentRecord,
      Entity parentEntity) {
    try {
      for (Property prop : parentEntity.getProperties()) {
        if (prop.getColumnName() != null && prop.getColumnName().equalsIgnoreCase(token)) {
          return stringifyParentValue(parentRecord.get(prop.getName()));
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve parent token {}", token, e);
    }
    return null;
  }

  private String stringifyParentValue(Object value) {
    if (value instanceof BaseOBObject) {
      return DalUtil.getId((BaseOBObject) value).toString();
    }
    return value != null ? value.toString() : "";
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

  /**
   * Parses an integer from a raw query-string value, falling back to
   * {@code fallback} when the value is blank or malformed.
   */
  private static int parseIntOrDefault(String raw, int fallback) {
    if (StringUtils.isBlank(raw)) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static final int DISTINCT_DEFAULT_PAGE_SIZE = 50;
  private static final int DISTINCT_MAX_PAGE_SIZE = 200;

  /**
   * Handles a {@code ?_distinct=<field>} list GET: returns the paginated set of
   * distinct values of a single scalar property, reusing the same tab where
   * clause and parent filter as the standard list fetch. {@link OBQuery}
   * automatically applies readable-client/organization/active filters from the
   * current {@link OBContext}.
   */
  private NeoResponse handleDistinctFetch(Tab adTab, Map<String, String> queryParams) {
    if (adTab == null || adTab.getTable() == null) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Distinct query requires a tab with a linked table");
    }
    String fieldName = queryParams.get(JsonConstants.DISTINCT_PARAMETER);
    if (StringUtils.isBlank(fieldName)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "_distinct requires a field name");
    }

    String dalEntityName = adTab.getTable().getName();
    Entity entityDef;
    try {
      entityDef = ModelProvider.getInstance().getEntity(dalEntityName);
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Unknown DAL entity: " + dalEntityName);
    }
    Property prop = NeoDistinctFetchSupport.resolveDistinctProperty(entityDef, fieldName);
    if (prop == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Unknown field '" + fieldName + "' on entity " + dalEntityName);
    }
    String resolvedProperty = prop.getName();

    int startRow = Math.max(0,
        parseIntOrDefault(queryParams.get(JsonConstants.STARTROW_PARAMETER), 0));
    int endRow = parseIntOrDefault(queryParams.get(JsonConstants.ENDROW_PARAMETER),
        startRow + DISTINCT_DEFAULT_PAGE_SIZE - 1);
    int requestedSize = (endRow >= startRow) ? (endRow - startRow + 1) : DISTINCT_DEFAULT_PAGE_SIZE;
    int pageSize = Math.max(1, Math.min(requestedSize, DISTINCT_MAX_PAGE_SIZE));

    String parentId = queryParams.get(PARAM_PARENT_ID);
    String search = queryParams.get("_distinctSearch");

    // Build the same where clause used by the list GET (tab HQL where with
    // @token@ substitution + parent filter for child tabs). Prefixed with
    // "as e" so OBQuery picks up the alias for its own client/org filters.
    String searchPredicate = NeoDistinctFetchSupport.buildDistinctSearchPredicate(prop, resolvedProperty, search);
    List<String> predicates = buildDistinctPredicates(adTab, resolvedProperty, parentId, searchPredicate);

    // For to-one associations (FK properties), project through the identifier
    // column (".id") instead of the bare association path, and reuse the exact
    // same expression in ORDER BY. Projecting the raw association makes
    // Hibernate expand ORDER BY into the target entity's own columns (a join)
    // while SELECT DISTINCT only ever projects the local FK column — Postgres
    // rejects that mismatch. See NeoDistinctFetchSupport#buildDistinctProjection.
    String projection = NeoDistinctFetchSupport.buildDistinctProjection(prop, resolvedProperty);
    boolean isRelation = !prop.isPrimitive();

    StringBuilder where = new StringBuilder(" as e where ")
        .append(String.join(HQL_AND_OPERATOR, predicates))
        .append(" order by ").append(projection).append(" asc");

    try {
      OBQuery<BaseOBObject> obQuery = OBDal.getInstance()
          .createQuery(dalEntityName, where.toString());
      obQuery.setSelectClause("DISTINCT " + projection);
      if (searchPredicate != null) {
        obQuery.setNamedParameter("search", "%" + search.toLowerCase() + "%");
      }
      obQuery.setFirstResult(startRow);
      obQuery.setMaxResult(pageSize + 1); // fetch one extra to detect hasMore

      List<Object> results = obQuery.createQuery(Object.class).list();
      boolean hasMore = results.size() > pageSize;
      List<Object> page = hasMore ? new ArrayList<>(results.subList(0, pageSize)) : results;

      JSONArray data = buildDistinctData(prop, isRelation, page);

      JSONObject payload = new JSONObject();
      payload.put("data", data);
      payload.put("startRow", startRow);
      payload.put("endRow", startRow + page.size() - 1);
      payload.put("hasMore", hasMore);
      JSONObject envelope = new JSONObject();
      envelope.put("response", payload);
      return NeoResponse.ok(envelope);
    } catch (Exception e) {
      log.error("Distinct fetch failed for {} / {}: {}",
          dalEntityName, resolvedProperty, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to compute distinct values");
    }
  }

  /**
   * Builds the HQL predicate list for {@link #handleDistinctFetch(Tab, Map)}: the
   * not-null guard on the projected property, the tab's own HQL where clause
   * (token-resolved), the parent-record filter for child tabs, and the
   * caller-resolved search predicate. Extracted purely to keep
   * {@code handleDistinctFetch}'s cognitive complexity within the Sonar limit
   * (S3776) — same logic, same order, unconditionally invoked once.
   */
  private List<String> buildDistinctPredicates(Tab adTab, String resolvedProperty, String parentId,
      String searchPredicate) {
    List<String> predicates = new ArrayList<>();
    predicates.add("e." + resolvedProperty + " IS NOT NULL");

    String tabWhere = adTab.getHqlwhereclause();
    addTabWherePredicate(adTab, tabWhere, parentId, predicates);
    if (parentId != null && adTab.getTabLevel() != null && adTab.getTabLevel() > 0) {
      String parentFilter = resolveParentFilter(adTab, parentId);
      if (StringUtils.isNotBlank(parentFilter)) {
        predicates.add("(" + parentFilter + ")");
      }
    }
    if (searchPredicate != null) {
      predicates.add(searchPredicate);
    }
    return predicates;
  }

  /**
   * Builds the {@code data} array for {@link #handleDistinctFetch(Tab, Map)}: for a
   * to-one association property, resolves display identifiers in batch; for a
   * primitive property, emits the raw distinct values as-is. Extracted purely to
   * keep {@code handleDistinctFetch}'s cognitive complexity within the Sonar
   * limit (S3776) — same logic, same order, unconditionally invoked once.
   */
  private JSONArray buildDistinctData(Property prop, boolean isRelation, List<Object> page) {
    JSONArray data = new JSONArray();
    if (isRelation) {
      Map<String, String> identifierById = NeoDistinctFetchSupport.loadIdentifiers(prop.getTargetEntity(), page);
      for (Object value : page) {
        data.put(NeoDistinctFetchSupport.toRelationDistinctEntry(value, identifierById));
      }
    } else {
      for (Object value : page) {
        data.put(NeoDistinctFetchSupport.toDistinctEntry(value));
      }
    }
    return data;
  }

  private void addTabWherePredicate(Tab adTab, String tabWhere, String parentId, List<String> predicates) {
    if (StringUtils.isNotBlank(tabWhere)) {
      if (parentId != null && tabWhere.contains("@")) {
        tabWhere = resolveTabWhereTokens(adTab, tabWhere, parentId);
      }
      if (!tabWhere.contains("@")) {
        // Skip when unresolved @session_tokens@ remain — OBQuery can't bind
        // them and the list fetch relies on DefaultJsonDataService to resolve.
        predicates.add("(" + tabWhere + ")");
      }
    }
  }

  /**
   * Rewrites the char {@code "Y"}/{@code "N"} filter value to a real boolean for any criterion
   * whose target property is a genuine {@link Boolean} DAL type.
   *
   * <p>The frontend serializes every boolean list column to {@code "Y"}/{@code "N"} (see
   * gridQuery.js booleanLabel mode). That is correct for AD button/list columns the DAL exposes
   * as {@code String} (e.g. {@code Posted}), which core matches verbatim. But for columns exposed
   * as an actual {@code Boolean} property (Yes/No reference, e.g. {@code IsDefault}), core
   * {@code AdvancedQueryBuilder} coerces the value with {@code Boolean.valueOf("Y") == false},
   * silently inverting the filter. Here we translate {@code "Y"/"N"} to {@code true/false} for
   * Boolean-typed properties only; String columns and non-{@code Y/N} values are left untouched,
   * so raw {@code true}/{@code false} keeps working. ETP-4705.
   */
  private void normalizeBooleanCriteria(Map<String, String> params, String dalEntityName) {
    String criteria = params.get(CRITERIA_PARAM);
    if (StringUtils.isBlank(criteria)) {
      return;
    }
    Entity entityDef = ModelProvider.getInstance().getEntity(dalEntityName);
    if (entityDef == null) {
      return;
    }
    try {
      JSONArray arr = new JSONArray(criteria);
      if (normalizeBooleanCriteriaArray(arr, entityDef)) {
        params.put(CRITERIA_PARAM, arr.toString());
      }
    } catch (JSONException e) {
      // Not a JSON array (e.g. a single object or an unexpected shape) — leave it untouched
      // rather than risk corrupting a criteria format we do not recognize.
      log.debug("Skipping boolean-criteria normalization; criteria is not a JSON array: {}",
          e.getMessage());
    }
  }

  /**
   * Walks a criteria array, normalizing flat clauses and recursing into nested {@code and}/{@code
   * or} composites. Returns true if any clause was rewritten.
   */
  boolean normalizeBooleanCriteriaArray(JSONArray arr, Entity entityDef)
      throws JSONException {
    boolean changed = false;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject clause = arr.optJSONObject(i);
      if (clause == null) {
        continue;
      }
      JSONArray nested = clause.optJSONArray(CRITERIA_PARAM);
      if (nested != null) {
        changed |= normalizeBooleanCriteriaArray(nested, entityDef);
      } else {
        changed |= normalizeBooleanClause(clause, entityDef);
      }
    }
    return changed;
  }

  /**
   * Rewrites a single flat criterion's {@code "Y"}/{@code "N"} value to {@code true}/{@code false}
   * when its target property is a Boolean DAL type. Returns true if the clause was rewritten.
   */
  boolean normalizeBooleanClause(JSONObject clause, Entity entityDef)
      throws JSONException {
    String fieldName = clause.optString("fieldName", null);
    if (StringUtils.isBlank(fieldName)) {
      return false;
    }
    Object value = clause.opt("value");
    if (!(value instanceof String)) {
      return false;
    }
    String str = ((String) value).trim();
    if (!("Y".equalsIgnoreCase(str) || "N".equalsIgnoreCase(str))) {
      return false;
    }
    Property prop = NeoDistinctFetchSupport.resolveDistinctProperty(entityDef, fieldName);
    if (prop == null || Boolean.class != prop.getPrimitiveObjectType()) {
      return false;
    }
    clause.put("value", "Y".equalsIgnoreCase(str));
    return true;
  }
}
