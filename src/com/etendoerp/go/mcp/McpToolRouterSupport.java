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

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;

import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

final class McpToolRouterSupport {

  private McpToolRouterSupport() {
  }

  static SFSpec findActiveSpecByName(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    if (results.isEmpty()) {
      throw new OBException("Spec not found: " + specName);
    }
    return results.get(0);
  }

  static SFEntity findIncludedEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    if (results.isEmpty()) {
      throw new OBException("Entity not found: " + entityName);
    }
    return results.get(0);
  }

  /**
   * Resolve an included entity for an entity-CRUD tool (neo_list/get/create/update/delete/
   * selectors/defaults/schema), or throw a descriptive error when the spec cannot expose
   * listable entities.
   *
   * <p>Report-type specs (specType {@code "R"}) expose no CRUD entities, so a bare
   * {@code findIncludedEntity} would surface an opaque {@code "Entity not found: <name>"}
   * (ETP-4257). Instead this guard fires before the entity lookup and explains what the spec
   * is and what to do:</p>
   * <ul>
   *   <li>callable report (NEO-native handler, ETP-4255) → point the agent at the
   *       {@code etendo_generate_<snake>} report tool;</li>
   *   <li>non-callable report → the stable {@link NeoReportCallability#buildNotConfiguredMessage}
   *       {@code not_configured_for_report_generation} text.</li>
   * </ul>
   *
   * <p>Type-W (and any non-R) specs are unaffected: the call delegates to
   * {@link #findIncludedEntity(String, String)}, preserving the existing
   * {@code "Entity not found: <name>"} message for a genuinely wrong entity name.</p>
   *
   * @param spec       the resolved active spec (already found by {@link #findActiveSpecByName})
   * @param entityName the requested entity name
   * @return the matching included {@link SFEntity} for a non-report spec
   * @throws OBException with a descriptive message for a report-type spec, or when the entity
   *                     name does not match an included entity on a non-report spec
   */
  static SFEntity resolveIncludedEntityOrExplain(SFSpec spec, String entityName) {
    if ("R".equals(spec.getSpecType())) {
      if (NeoReportCallability.isReportCallable(spec)) {
        String snakeTool = McpConstants.GENERATE_PREFIX + ToolRegistry.kebabToSnake(spec.getName());
        throw new OBException("Spec '" + spec.getName() + "' is a report type (R) and does not "
            + "expose listable entities. Use the etendo_" + snakeTool
            + " tool to produce this report.");
      }
      throw new OBException(NeoReportCallability.buildNotConfiguredMessage(spec.getName()));
    }
    return findIncludedEntity(spec.getId(), entityName);
  }

  static List<SFEntity> listIncludedEntities(String specId) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
    return criteria.list();
  }

  static JSONArray buildEntitySummaryArray(String specId) throws JSONException {
    JSONArray entities = new JSONArray();
    for (SFEntity entity : listIncludedEntities(specId)) {
      entities.put(buildDiscoverEntity(entity));
    }
    return entities;
  }

  /**
   * Builds the entity metadata returned by {@code neo_discover}.
   *
   * <p>The {@code readOnly} flag is derived from the entity's configured mutation methods rather
   * than its name, so it applies consistently to handler-backed GET-only entities and any future
   * system-data entity configured without POST, PUT, PATCH, or DELETE support.
   */
  static JSONObject buildDiscoverEntity(SFEntity entity) throws JSONException {
    JSONObject item = new JSONObject();
    item.put("name", entity.getName());
    item.put("methods", buildMethodsArray(entity));
    item.put("readOnly", isReadOnlyEntity(entity));
    // Entity-level agent guidance (ETP-4278), additive to the spec-level and
    // per-field prompts. Emitted only when set so untagged entities stay lean.
    String agentPrompt = entity.getAgentPrompt();
    if (agentPrompt != null && !agentPrompt.trim().isEmpty()) {
      item.put("agentPrompt", agentPrompt.trim());
    }
    return item;
  }

  /** Returns whether an entity declares at least one read method and no supported mutation method. */
  static boolean isReadOnlyEntity(SFEntity entity) {
    boolean canRead = Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID());
    return canRead
        && !Boolean.TRUE.equals(entity.isPost())
        && !Boolean.TRUE.equals(entity.isPut())
        && !Boolean.TRUE.equals(entity.isPatch())
        && !Boolean.TRUE.equals(entity.isDelete());
  }

  static JSONArray buildMethodsArray(SFEntity entity) {
    JSONArray methods = new JSONArray();
    if (Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID())) {
      methods.put("GET");
    }
    if (Boolean.TRUE.equals(entity.isPost())) {
      methods.put("POST");
    }
    if (Boolean.TRUE.equals(entity.isPut())) {
      methods.put("PUT");
    }
    if (Boolean.TRUE.equals(entity.isPatch())) {
      methods.put("PATCH");
    }
    if (Boolean.TRUE.equals(entity.isDelete())) {
      methods.put("DELETE");
    }
    return methods;
  }

  /**
   * Identify the spec whose entities are handler-backed business widgets rather than
   * CRUD windows (gap G4, ETP-4284). These entities have no {@code AD_Tab}, so the
   * generic CRUD path cannot serve them; they are exposed via the {@code neo_widget}
   * tool instead and must be excluded from the type-W CRUD catalog and discovery.
   *
   * @param spec the spec to test (may be {@code null})
   * @return {@code true} when the spec backs widget handlers
   */
  static boolean isWidgetSpec(SFSpec spec) {
    return spec != null && McpConstants.SPEC_DASHBOARD.equals(spec.getName());
  }

  /**
   * Read-tier ({@code GET}) spec access check. Use only for visibility/discovery
   * (neo_discover, MCP resource listing) — never to gate an actual write operation;
   * see {@link #hasSpecAccess(SFSpec, String, String)} for that.
   */
  static boolean hasSpecAccess(SFSpec spec, String specType) {
    return hasSpecAccess(spec, specType, "GET");
  }

  /**
   * Checks whether the current role can perform {@code httpMethod} against {@code spec}.
   * <p>
   * For window specs (type {@code "W"}), enforces the read-only vs. full-access tiering
   * (ETP-4510) via {@link NeoAccessUtils#hasWindowAccess(String, String)} — callers that
   * gate a mutating MCP tool (neo_create/neo_update/neo_delete/neo_batch) MUST pass the
   * write-intent method here, not the 1-arg overload, or a read-only
   * {@code AD_Window_Access} role would be able to write through MCP even though the
   * equivalent REST NEO Headless call correctly returns 403.
   * <p>
   * Process specs (type {@code "P"}/{@code "R"}) remain binary — process access has no
   * read/write tiering — so {@code httpMethod} is ignored for them.
   *
   * @param spec       the spec to check (may be {@code null})
   * @param specType   the spec's type ({@code "W"}, {@code "P"}, or {@code "R"})
   * @param httpMethod the HTTP-method equivalent of the MCP operation being authorized
   * @return {@code true} if the current role may perform {@code httpMethod} on {@code spec}
   */
  static boolean hasSpecAccess(SFSpec spec, String specType, String httpMethod) {
    // The dashboard/widget spec is not a CRUD window; it is surfaced via neo_widget,
    // never through neo_discover's W catalog (ETP-4284 / G4).
    if (isWidgetSpec(spec)) {
      return false;
    }
    if ("W".equals(specType)) {
      // ETP-4510 BUG-3: hasWindowAccessForSpec covers both ordinary window specs AND
      // windowless/custom "combination" specs (spec.getADWindow() == null) — it must run
      // unconditionally rather than skipping the check when there is no directly linked
      // window, otherwise a role with no access at all (or no role assigned) could reach
      // a windowless spec unchecked.
      return NeoAccessUtils.hasWindowAccessForSpec(spec, httpMethod);
    }
    if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = spec.getProcess();
      return adProcess == null || NeoAccessUtils.hasProcessAccess(adProcess.getId());
    }
    return true;
  }

  static JSONObject buildDiscoverSpec(SFSpec spec, String specType, JSONArray entities)
      throws Exception {
    JSONObject specObj = new JSONObject();
    specObj.put("name", spec.getName());
    specObj.put("type", specType);
    if (spec.getDescription() != null) {
      specObj.put(McpConstants.KEY_DESCRIPTION, spec.getDescription());
    }
    String agentPrompt = spec.getAgentPrompt();
    if (agentPrompt != null && !agentPrompt.trim().isEmpty()) {
      specObj.put("agentPrompt", agentPrompt.trim());
    }
    if (entities != null) {
      specObj.put("entities", entities);
    }
    if ("R".equals(specType)) {
      // Report callability is truthful (ETP-4255): a report spec is callable only when it
      // is backed by a NEO-native report handler. Non-callable specs expose a stable
      // not_configured_for_report_generation status + message; Jasper/AD_Process reports
      // are never executable by Etendo Go.
      specObj.put("isReport", true);
      boolean callable = NeoReportCallability.isReportCallable(spec);
      specObj.put("callable", callable);
      if (callable) {
        // Surface the concrete report tool so the agent can call it directly instead of
        // guessing an entity for neo_list (ETP-4257). Client sees it as etendo_<reportTool>.
        specObj.put("reportTool",
            McpConstants.GENERATE_PREFIX + ToolRegistry.kebabToSnake(spec.getName()));
      } else {
        specObj.put("status", NeoReportCallability.STATUS_NOT_CONFIGURED);
        specObj.put("message", NeoReportCallability.buildNotConfiguredMessage(spec.getName()));
      }
    }
    return specObj;
  }

  static Property resolveMandatoryProperty(Tab adTab, Entity dalEntity, Column col,
      java.util.Set<String> systemColumns) {
    if (!col.isActive() || !col.isMandatory()) {
      return null;
    }
    String dbColName = col.getDBColumnName();
    if (dbColName.equalsIgnoreCase(adTab.getTable().getDBTableName() + "_ID")
        || systemColumns.contains(dbColName.toUpperCase())) {
      return null;
    }
    try {
      return dalEntity.getPropertyByColumnName(dbColName);
    } catch (Exception ignored) {
      return null;
    }
  }

  static boolean isMandatoryValueMissing(JSONObject body, String propName) {
    if (!body.has(propName) || body.isNull(propName)) {
      return true;
    }
    Object value = body.opt(propName);
    return value instanceof String && ((String) value).isEmpty();
  }

  static JSONObject buildMissingFieldInfo(Column col, String propName,
      java.util.Set<String> selectorRefs) throws JSONException {
    JSONObject fieldInfo = new JSONObject();
    String refId = col.getReference() != null ? col.getReference().getId() : null;
    boolean isFK = selectorRefs.contains(refId);
    fieldInfo.put("name", propName);
    fieldInfo.put("column", col.getDBColumnName());
    fieldInfo.put("type", isFK ? "foreignKey" : "other");
    if (isFK) {
      fieldInfo.put("hasSelector", true);
    }
    fieldInfo.put("label", col.getName());
    return fieldInfo;
  }

  static void coercePrimitiveFieldValue(JSONObject body, String key, Property prop,
      org.apache.logging.log4j.Logger log) {
    Object value = body.opt(key);
    if (!(value instanceof String)) {
      return;
    }
    String strVal = (String) value;
    if (strVal.isEmpty()) {
      return;
    }
    try {
      Class<?> type = prop.getPrimitiveObjectType();
      if (type == Long.class) {
        body.put(key,
            Long.parseLong(strVal.contains(".") ? strVal.substring(0, strVal.indexOf('.')) : strVal));
      } else if (type == java.math.BigDecimal.class) {
        body.put(key, new java.math.BigDecimal(strVal));
      } else if (type == Boolean.class) {
        body.put(key, "Y".equalsIgnoreCase(strVal) || "true".equalsIgnoreCase(strVal));
      }
    } catch (Exception e) {
      log.debug("Could not coerce field {} value '{}': {}", key, strVal, e.getMessage());
    }
  }

  // ── Action result mapping (kept here to stay within McpToolRouter method-count limit) ─

  /**
   * Maps a {@link NeoResponse} body to the structured MCP action result keys.
   * Handles both top-level {@code {status, message}} bodies (from
   * {@code NeoProcessService.translate*Result}) and nested
   * {@code {"error":{status, message}}} bodies (from {@code NeoResponse.error()}).
   * Extra keys from the process result are passed through unchanged.
   */
  static JSONObject mapNeoResponseToActionResult(NeoResponse neoResponse) throws JSONException {
    JSONObject actionResult = new JSONObject();
    JSONObject body = neoResponse.getBody();
    if (body == null) {
      return actionResult;
    }
    String status = body.optString(McpConstants.KEY_STATUS, null);
    String message = body.optString(McpConstants.KEY_MESSAGE, null);

    if (status == null && message == null) {
      status = resolveStatusFromErrorBody(body);
      JSONObject errorObj = body.optJSONObject(McpConstants.KEY_ERROR);
      if (errorObj != null) {
        message = errorObj.optString(McpConstants.KEY_MESSAGE, null);
      }
    }

    if (status != null) {
      actionResult.put(McpConstants.KEY_PROCESS_RESULT, status);
    }
    if (message != null) {
      actionResult.put(McpConstants.KEY_PROCESS_MESSAGE, message);
    }
    java.util.Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!McpConstants.KEY_STATUS.equals(key) && !McpConstants.KEY_MESSAGE.equals(key)
          && !McpConstants.KEY_ERROR.equals(key)) {
        actionResult.put(key, body.get(key));
      }
    }
    return actionResult;
  }

  /**
   * Resolves the status string from a nested error body produced by
   * {@code NeoResponse.error(int, String)}.
   */
  static String resolveStatusFromErrorBody(JSONObject body) {
    JSONObject errorObj = body.optJSONObject(McpConstants.KEY_ERROR);
    if (errorObj == null) {
      return null;
    }
    String status = errorObj.optString(McpConstants.KEY_STATUS, null);
    if (status != null) {
      return status;
    }
    int errorStatus = errorObj.optInt(McpConstants.KEY_STATUS, -1);
    return errorStatus > 0 ? String.valueOf(errorStatus) : null;
  }

  // ── List filter helpers (kept here to stay within McpToolRouter method-count limit) ─

  /**
   * Build an HQL where clause fragment from MCP filter key-value pairs.
   * Filters are applied as exact-match conditions using the DAL property name.
   */
  static String buildWhereFromFilters(JSONObject filters, Tab adTab,
      org.apache.logging.log4j.Logger log) throws JSONException {
    Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return null;
    }

    StringBuilder where = new StringBuilder();
    java.util.Iterator<String> keys = filters.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      String value = filters.getString(key);
      appendFilterCondition(where, dalEntity, key, value, log);
    }
    return where.length() > 0 ? where.toString() : null;
  }

  /**
   * Resolve a single filter key to a DAL property and append an HQL condition.
   */
  private static void appendFilterCondition(StringBuilder where, Entity dalEntity,
      String key, String value, org.apache.logging.log4j.Logger log) {
    Property prop = null;
    try {
      prop = dalEntity.getPropertyByColumnName(key);
    } catch (Exception ignored) {
      try {
        prop = dalEntity.getProperty(key);
      } catch (Exception alsoIgnored) {
        log.debug("Filter column '{}' not found in entity, skipping", key);
      }
    }

    if (prop == null) {
      log.warn("Filter key '{}' could not be resolved to a DAL property, ignoring", key);
      return;
    }

    if (where.length() > 0) {
      where.append(" and ");
    }
    String escaped = value.replace("'", "''");
    if (!prop.isPrimitive()) {
      where.append("e.").append(prop.getName()).append(".id='").append(escaped).append("'");
    } else {
      where.append("e.").append(prop.getName()).append("='").append(escaped).append("'");
    }
  }

  /**
   * Wraps a flat JSON body into the structure expected by DefaultJsonDataService.
   * Identical to NeoServlet.wrapForSmartclient().
   */
  static String wrapForSmartclient(JSONObject filteredBody, String dalEntityName,
      String recordId, org.apache.logging.log4j.Logger log) {
    try {
      JSONObject data = filteredBody != null ? filteredBody : new JSONObject();
      data.put(org.openbravo.service.json.JsonConstants.ENTITYNAME, dalEntityName);
      if (recordId != null) {
        data.put(org.openbravo.service.json.JsonConstants.ID, recordId);
      } else {
        data.put(org.openbravo.service.json.JsonConstants.NEW_INDICATOR, true);
      }

      JSONObject wrapper = new JSONObject();
      wrapper.put(org.openbravo.service.json.JsonConstants.DATA, data);
      return wrapper.toString();
    } catch (Exception e) {
      log.error("Error wrapping body for Smartclient format: {}", e.getMessage(), e);
      return "{}";
    }
  }

  /**
   * Validate that the given required arguments are present and non-null in {@code args}.
   * Shared by {@link McpToolRouter} and {@link McpWidgetHandler} so the contract (and the
   * error messages tests assert on) lives in a single place.
   *
   * @param args     the tool arguments (may be {@code null})
   * @param required the argument keys that must be present
   * @throws IllegalArgumentException when {@code args} is {@code null} or a key is missing
   */
  static void validateArgs(JSONObject args, String... required) {
    if (args == null) {
      throw new IllegalArgumentException("Missing arguments");
    }
    for (String key : required) {
      if (!args.has(key) || args.isNull(key)) {
        throw new IllegalArgumentException("Missing required argument: " + key);
      }
    }
  }

  /**
   * Copy an alias argument onto its canonical key when the canonical key is absent (IMP-8).
   * <p>
   * Lets a natural first-try call shape succeed instead of failing on a missing-argument
   * error. Used by {@code neo_selectors} to accept {@code field} as an alias for the
   * canonical {@code column} argument. The canonical key wins when both are present, and a
   * blank/null alias is ignored so it never shadows a required-argument check.
   *
   * @param args      the tool arguments (may be {@code null} — then this is a no-op)
   * @param aliasKey  the accepted alias argument name (e.g. {@code "field"})
   * @param canonical the canonical argument name the handler reads (e.g. {@code "column"})
   */
  static void aliasArg(JSONObject args, String aliasKey, String canonical) {
    if (args == null || args.has(canonical) || !args.has(aliasKey) || args.isNull(aliasKey)) {
      return;
    }
    try {
      args.put(canonical, args.get(aliasKey));
    } catch (JSONException e) {
      // args.get(aliasKey) cannot throw here — has()/!isNull() already gated it.
      throw new OBException("Could not alias argument '" + aliasKey + "' to '" + canonical + "'", e);
    }
  }

  /**
   * Detect a {@link org.openbravo.service.json.DefaultJsonDataService} fetch that returned a
   * successful but empty {@code data} array (IMP-5).
   * <p>
   * A get-by-id that matches no record comes back as {@code {response:{data:[], status:0}}},
   * which is indistinguishable from a legitimate success — {@code status 0} reads as OK. This
   * is the ambiguous not-found signal the MCP must translate into an explicit error so an agent
   * can self-correct. Only meaningful for get-by-id: an empty {@code neo_list} is a valid
   * result, never a not-found.
   *
   * @param responseJson the parsed DefaultJsonDataService response (may be {@code null})
   * @return {@code true} when the response is a success carrying zero rows
   */
  static boolean isEmptySuccessResult(JSONObject responseJson) {
    if (responseJson == null) {
      return false;
    }
    JSONObject inner = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (inner == null) {
      return false;
    }
    if (inner.optInt(JsonConstants.RESPONSE_STATUS, Integer.MIN_VALUE)
        != JsonConstants.RPCREQUEST_STATUS_SUCCESS) {
      return false;
    }
    JSONArray data = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
    return data == null || data.length() == 0;
  }

  /**
   * Build an explicit, machine-detectable not-found error body for a get-by-id (IMP-5).
   * <p>
   * Replaces the ambiguous {@code {data:[], status:0}} success shape with a clear
   * {@code {response:{status:404, error:"not_found", detail:"…"}}} so an agent can tell
   * "not found" from "empty match" purely from the response.
   *
   * @param specName   the spec name from the tool call
   * @param entityName the entity name from the tool call
   * @param recordId   the id that matched no record
   * @return the wrapped not-found error object
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  static JSONObject buildNotFoundError(String specName, String entityName, String recordId)
      throws JSONException {
    JSONObject inner = new JSONObject();
    inner.put(McpConstants.KEY_STATUS, McpConstants.STATUS_NOT_FOUND);
    inner.put(McpConstants.KEY_ERROR, McpConstants.ERROR_NOT_FOUND);
    inner.put(McpConstants.KEY_DETAIL,
        "No " + specName + "/" + entityName + " with id " + recordId);
    inner.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_READING);
    JSONObject wrapper = new JSONObject();
    wrapper.put(JsonConstants.RESPONSE_RESPONSE, inner);
    return wrapper;
  }

  /**
   * Builds the guidance object advertised by {@code neo_discover} so a cold agent is routed to the
   * {@code docs} tool for ready-to-run recipes (IMP-10). Shape:
   * {@code {"tool":"docs","hint":"Call docs(topic:…) for ready-to-run recipes per task."}}.
   *
   * @return the guidance object
   * @throws JSONException never in practice (all values are plain strings)
   */
  static JSONObject buildDocsGuidance() throws JSONException {
    JSONObject guidance = new JSONObject();
    guidance.put(McpConstants.KEY_TOOL, McpConstants.TOOL_DOCS);
    guidance.put(McpConstants.KEY_HINT, McpConstants.GUIDANCE_DOCS_HINT);
    return guidance;
  }
}
