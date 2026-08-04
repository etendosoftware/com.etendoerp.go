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

import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

import com.etendoerp.go.schemaforge.NeoActionSurface;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

final class McpToolRouterSupport {

  private static final Logger log = LogManager.getLogger(McpToolRouterSupport.class);

  private McpToolRouterSupport() {
  }

  static SFSpec findActiveSpecByName(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, specName));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
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

  /**
   * Build the {@code neo_discover} entity summary from an already-loaded entity list.
   *
   * <p>ETP-4254 changed this from taking a spec id to taking the list, so the discover loop can
   * load a spec's included entities ONCE and derive both the per-entity summary and the
   * spec-level {@code readOnly} marker from the same list instead of querying twice per spec.</p>
   *
   * @param entities the spec's active, included entities (may be {@code null})
   * @return the entity summary array, empty when {@code entities} is {@code null}
   */
  static JSONArray buildEntitySummaryArray(List<SFEntity> entities) throws JSONException {
    JSONArray summary = new JSONArray();
    if (entities == null) {
      return summary;
    }
    for (SFEntity entity : entities) {
      summary.put(buildDiscoverEntity(entity));
    }
    return summary;
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
    return item;
  }

  /**
   * Returns whether an entity declares at least one read method and no supported mutation
   * method. Delegates to {@link NeoMethodPolicy#isReadOnly(SFEntity)} — the single source of
   * truth for the {@code ETGO_SF_ENTITY} method flags (ETP-4254).
   */
  static boolean isReadOnlyEntity(SFEntity entity) {
    return NeoMethodPolicy.isReadOnly(entity);
  }

  static JSONArray buildMethodsArray(SFEntity entity) {
    JSONArray methods = new JSONArray();
    for (String method : NeoMethodPolicy.enabledMethods(entity)) {
      methods.put(method);
    }
    return methods;
  }

  /**
   * Refuse an MCP tool call whose HTTP-method equivalent is not enabled on the target entity
   * (ETP-4254).
   *
   * <p>The REST CRUD path has always enforced the {@code ETGO_SF_ENTITY} method flags
   * ({@code NeoCrudHandler#handleWindowEntityCrud} → {@code 405}), but the MCP write handlers
   * resolve the entity and go straight to {@code DefaultJsonDataService}, so they never
   * consulted them. Their only gate was {@link #hasSpecAccess(SFSpec, String, String)}, which
   * is role-level ({@code AD_Window_Access} tiering, ETP-4510), not entity-level. Turning the
   * mutation flags off on a monitor/log window therefore blocked the React UI while leaving
   * the agent free to write — and made {@code neo_discover}'s {@code readOnly: true} a lie.</p>
   *
   * <p>Reported as an explained refusal rather than a bare status code, matching
   * {@link #resolveIncludedEntityOrExplain(SFSpec, String)}: the thrown message names the
   * enabled methods and tells the agent what to do instead. {@code McpToolRouter#route}
   * turns it into MCP error content.</p>
   *
   * @param spec   the resolved spec (used for the message only)
   * @param entity the resolved included entity
   * @param method the HTTP-method equivalent of the MCP operation ({@code POST}/{@code PUT}/
   *               {@code DELETE})
   * @throws OBException when the method is not enabled on the entity
   */
  static void requireMethodEnabled(SFSpec spec, SFEntity entity, String method) {
    if (NeoMethodPolicy.isMethodEnabled(entity, method)) {
      return;
    }
    String specName = spec != null ? spec.getName() : null;
    String entityName = entity != null ? entity.getName() : null;
    throw new OBException(
        NeoMethodPolicy.buildMcpNotEnabledMessage(specName, entityName, method, entity));
  }

  /**
   * Identify a spec that the generic CRUD path cannot serve at all: one whose included
   * entities are handler-backed (no {@code AD_Tab}), such as the dashboard's business widgets
   * (gap G4, ETP-4284).
   *
   * <p>ETP-4254 replaced the previous hardcoded {@code "dashboard"} spec-name literal with
   * this data-driven test, so any future handler-only spec is recognized without a code
   * change. The rule is deliberately conservative — it fires only on positive evidence (the
   * spec HAS included entities and NONE of them is AD_Tab-backed). An empty entity list, or a
   * failed lookup, answers {@code false}: the authoritative per-operation gate is
   * {@link #requireMethodEnabled(SFSpec, SFEntity, String)}, so this predicate only shapes the
   * catalog and must never be the thing that silently hides a working window.</p>
   *
   * <p><b>Being handler-only is NOT on its own a reason to hide a spec</b> — see
   * {@link #isCatalogExcludedSpec(SFSpec)}, which is what the callers actually use.</p>
   *
   * <p>Cost: one indexed {@code ETGO_SF_ENTITY} query per call.</p>
   *
   * @param spec the spec to test (may be {@code null})
   * @return {@code true} when every included entity of the spec is handler-backed
   */
  static boolean isHandlerOnlySpec(SFSpec spec) {
    if (spec == null) {
      return false;
    }
    return isHandlerOnlySpec(safeListIncludedEntities(spec));
  }

  /**
   * Same shape test as {@link #isHandlerOnlySpec(SFSpec)}, against an already-loaded list.
   *
   * @param entities the spec's active, included entities (may be {@code null} or empty)
   * @return {@code true} when the list is non-empty and no entity is AD_Tab-backed
   */
  static boolean isHandlerOnlySpec(List<SFEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return false;
    }
    return entities.stream().noneMatch(entity -> entity.getADTab() != null);
  }

  /**
   * Decide whether a type-{@code W} spec must stay OUT of the agentic catalog entirely — out
   * of {@code neo_discover}, out of the CRUD/action tool enums and out of
   * {@code McpResourceProvider}.
   *
   * <p>Two conditions, both required:</p>
   * <ol>
   *   <li>{@link #isHandlerOnlySpec(SFSpec)} — the generic CRUD path cannot serve it, because
   *       not one included entity is AD_Tab-backed.</li>
   *   <li>{@link NeoActionSurface#hasActionSurface(List)} is {@code false} — no entity's
   *       handler answers ACTION requests, so there is no {@code /action} route either.</li>
   * </ol>
   *
   * <p><b>Why the second condition exists.</b> Condition 1 alone is too broad, and matched two
   * specs rather than the one it was written for: {@code dashboard} (9 widget entities, no
   * agentic surface at all — correctly hidden, exposed via {@code neo_widget} instead) and
   * {@code not-posted-documents} (one tab-less entity whose handler serves {@code post} and
   * {@code bulk-post}). Hiding the latter would have removed a genuine transactional business
   * action from agents — the exact opposite of what ETP-4254 is for, since {@code hasSpecAccess}
   * gates {@code neo_action} too. So a tab-less spec that still exposes an action route stays
   * in the catalog; only the fully unreachable ones are dropped.</p>
   *
   * <p>Only applied to type-{@code W} specs. Report specs (type {@code R}) are handler-only by
   * design — {@code NeoReportCallability} resolves them through a {@code Java_Qualifier} — so
   * testing them here would delete every report from discovery.</p>
   *
   * @param spec the spec to test (may be {@code null})
   * @return {@code true} when the spec has neither a CRUD nor an action surface
   */
  static boolean isCatalogExcludedSpec(SFSpec spec) {
    if (spec == null) {
      return false;
    }
    List<SFEntity> entities = safeListIncludedEntities(spec);
    // Short-circuit on the cheap shape test: the CDI action probe below only ever runs for the
    // handful of tab-less specs, never for an ordinary AD_Tab-backed window.
    if (!isHandlerOnlySpec(entities)) {
      return false;
    }
    return !NeoActionSurface.hasActionSurface(entities);
  }

  /**
   * Reports whether a spec exposes no writable entity at all: it HAS included entities and
   * not one of them enables {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}.
   *
   * <p>This is the data-driven rule behind the MCP write-tool catalog (ETP-4254 AC#1):
   * monitor/log specs (SII, VeriFactu, conversion-rate download log, TicketBAI sent
   * invoices) are read-only by configuration, so write tools must not offer them in their
   * {@code spec} enum. Like {@link #isHandlerOnlySpec(SFSpec)} it requires positive evidence:
   * an empty entity list or a failed lookup returns {@code false} and keeps the spec writable
   * in the catalog, because the real refusal happens in
   * {@link #requireMethodEnabled(SFSpec, SFEntity, String)}.</p>
   *
   * @param spec the spec to test (may be {@code null})
   * @return {@code true} when the spec has included entities and none of them is mutable
   */
  static boolean isReadOnlySpec(SFSpec spec) {
    if (spec == null) {
      return false;
    }
    return isReadOnlySpec(safeListIncludedEntities(spec));
  }

  /**
   * Reports whether a spec has at least one included entity enabling {@code method}.
   * ToolRegistry uses this per verb so a mixed spec is offered only by the write tools it can
   * actually satisfy (for example, {@code monitor-verifactu} supports PUT but not POST/DELETE).
   *
   * <p>The catalog is deliberately fail-open: an empty list or lookup failure returns
   * {@code true}, preserving the pre-existing tool until positive entity metadata proves the
   * verb unavailable. The authoritative per-operation gate still lives in
   * {@link #requireMethodEnabled}.</p>
   *
   * @param spec   the spec to inspect (may be {@code null})
   * @param method the write method to look for
   * @return {@code true} when at least one included entity enables the method
   */
  static boolean hasEntityWithMethod(SFSpec spec, String method) {
    if (spec == null) {
      return true;
    }
    try {
      List<SFEntity> entities = listIncludedEntities(spec.getId());
      return entities == null || entities.isEmpty() || entities.stream()
          .anyMatch(entity -> NeoMethodPolicy.isMethodEnabled(entity, method));
    } catch (Exception e) {
      log.warn("Could not inspect method {} for spec '{}': {}", method, spec.getName(),
          e.getMessage());
      return true;
    }
  }

  /**
   * Same predicate as {@link #isReadOnlySpec(SFSpec)}, evaluated against an already-loaded
   * entity list. This is the single implementation; the spec-taking overload is the querying
   * entry point. Callers that already hold the list (the {@code neo_discover} loop) must use
   * this one so the flag costs no extra query.
   *
   * @param entities the spec's active, included entities (may be {@code null} or empty)
   * @return {@code true} when the list is non-empty and no entity is mutable
   */
  static boolean isReadOnlySpec(List<SFEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return false;
    }
    return entities.stream().noneMatch(NeoMethodPolicy::hasMutableMethod);
  }

  /**
   * Load a spec's included entities without letting an infrastructure failure break the
   * caller. Both catalog predicates above are advisory (see their javadoc), so a lookup
   * failure degrades to "no evidence" — an empty list — rather than hiding a spec.
   */
  private static List<SFEntity> safeListIncludedEntities(SFSpec spec) {
    try {
      List<SFEntity> entities = listIncludedEntities(spec.getId());
      return entities != null ? entities : Collections.emptyList();
    } catch (Exception e) {
      log.warn("Could not list included entities for spec '{}': {}", spec.getName(),
          e.getMessage());
      return Collections.emptyList();
    }
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
    if ("W".equals(specType)) {
      // ETP-4510 BUG-3: hasWindowAccessForSpec covers both ordinary window specs AND
      // windowless/custom "combination" specs (spec.getADWindow() == null) — it must run
      // unconditionally rather than skipping the check when there is no directly linked
      // window, otherwise a role with no access at all (or no role assigned) could reach
      // a windowless spec unchecked.
      if (!NeoAccessUtils.hasWindowAccessForSpec(spec, httpMethod)) {
        return false;
      }
      // A window spec with neither a CRUD nor an action surface (the dashboard's business
      // widgets) is not a CRUD window; it is surfaced via neo_widget, never through
      // neo_discover's W catalog (ETP-4284 / G4). ETP-4254 made this test data-driven instead
      // of matching the literal spec name; it is scoped to "W" here because type-R report
      // specs are handler-only by design and must keep their existing path. Evaluated after
      // the role check so a denied spec costs no entity query. NOTE: this gate also covers
      // neo_action (McpToolRouter#route → hasSpecAccess), which is why it must not exclude a
      // tab-less spec that still serves actions — see isCatalogExcludedSpec.
      return !isCatalogExcludedSpec(spec);
    }
    if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = spec.getProcess();
      return adProcess == null || NeoAccessUtils.hasProcessAccess(adProcess.getId());
    }
    return true;
  }

  /**
   * Build one {@code neo_discover} spec entry.
   *
   * <p>ETP-4254 AC#4: a type-{@code W} spec whose every included entity is configured read-only
   * (the SII / VeriFactu / conversion-rate-log / TicketBAI monitors) carries a spec-level
   * {@code "readOnly": true} marker, so an agent can pick out the writable specs from the
   * catalog without inspecting every entity of every spec. The key is emitted ONLY when true —
   * the ~44 writable W specs stay unchanged, and the negative case is already carried by the
   * per-entity {@code readOnly} key inside {@code entities}.</p>
   *
   * @param spec             the spec to describe
   * @param specType         the spec type ({@code "W"}, {@code "P"} or {@code "R"})
   * @param entities         the pre-built entity summary array, or {@code null} for non-W specs
   * @param includedEntities the same entities the summary was built from, used to derive the
   *                         spec-level read-only marker without a second query; {@code null}
   *                         for non-W specs (and then no marker is emitted)
   */
  static JSONObject buildDiscoverSpec(SFSpec spec, String specType, JSONArray entities,
      List<SFEntity> includedEntities) throws Exception {
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
    if ("W".equals(specType) && isReadOnlySpec(includedEntities)) {
      specObj.put("readOnly", true);
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
}
