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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
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

import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.MissingRequiredFieldsException;
import com.etendoerp.go.schemaforge.NeoActionSurface;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoBooleanFormat;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
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
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    if (results.isEmpty()) {
      throw McpRoutingException.specNotFound(specName);
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
      // ETP-4793 / IMP-17: the miss is only worth reporting alongside the names that would have
      // worked. The extra query runs on the failure path only, and it is the one the agent would
      // otherwise have to make itself (evidence B20).
      throw McpRoutingException.entityNotFound(entityName, resolveSpecNameForError(specId),
          includedEntityNames(specId));
    }
    return results.get(0);
  }

  /**
   * The spec's caller-facing name, for an error message built on the failure path.
   *
   * <p>{@code SFSpec}'s primary key is a UUID, and the agent addressed the spec by its kebab-case
   * name — echoing the UUID back would name something it never sent. Falls back to the id only if
   * the spec somehow cannot be loaded, which cannot happen on this path (it was just resolved).</p>
   *
   * @param specId the spec's primary key
   * @return the spec name, or the id when it cannot be resolved
   */
  private static String resolveSpecNameForError(String specId) {
    try {
      SFSpec spec = OBDal.getInstance().get(SFSpec.class, specId);
      if (spec != null && spec.getName() != null) {
        return spec.getName();
      }
    } catch (Exception e) {
      log.debug("Could not resolve spec name for {}", specId, e);
    }
    return specId;
  }

  /**
   * The names of a spec's active, included entities — the {@code available} list an unknown entity
   * name is answered with (ETP-4793 / IMP-17).
   *
   * @param specId the spec whose entities to name
   * @return the entity names in {@code seqNo} order, empty when the spec includes none
   */
  static List<String> includedEntityNames(String specId) {
    List<String> names = new ArrayList<>();
    for (SFEntity entity : listIncludedEntities(specId)) {
      names.add(entity.getName());
    }
    return names;
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
        throw McpRoutingException.notCrudCapable("Spec '" + spec.getName()
            + "' is a report type (R) and does not expose listable entities. Use the etendo_"
            + snakeTool + " tool to produce this report.");
      }
      throw McpRoutingException.notCrudCapable(
          NeoReportCallability.buildNotConfiguredMessage(spec.getName()));
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
   * Resolve the root ("header") entity of a window spec for {@code neo_discover} (IMP-9), so an
   * agent knows which entity to create first without calling {@code neo_schema} on each one.
   * <p>
   * Authoritative signal: {@code AD_Tab.tabLevel == 0} marks the header tab (same convention
   * {@link McpToolRouter#resolveParentFK} relies on). {@code SFEntity} carries no parent column,
   * so hierarchy can only be read off the linked {@code AD_Tab}. Falls back to the first entity
   * by {@code seqNo} ({@link #listIncludedEntities} already orders ascending) when no included
   * entity has a level-0 tab, or when an entity has no linked tab at all (handler-backed entities).
   *
   * <p>ETP-4254: takes the already-loaded entity list (the discover loop loads it once and reuses
   * it for the summary, the read-only marker and this) instead of re-querying by spec id.</p>
   *
   * @param entities the spec's active, included entities (already ordered by {@code seqNo})
   * @return the primary entity's name, or {@code null} when the spec includes no entities
   */
  static String resolvePrimaryEntityName(List<SFEntity> entities) {
    if (entities == null || entities.isEmpty()) {
      return null;
    }
    for (SFEntity entity : entities) {
      Tab tab = entity.getADTab();
      if (tab != null && tab.getTabLevel() != null && tab.getTabLevel() == 0) {
        return entity.getName();
      }
    }
    return entities.get(0).getName();
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
    throw McpRoutingException.methodNotAllowed(
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
   * <p>IMP-9 / ETP-4601: {@code primaryEntity} is derived by the caller (handleDiscover) and
   * passed in, not computed here, so this method stays DAL-free — resolving tab levels needs the
   * live/admin OBContext that handleDiscover already runs in.</p>
   *
   * @param spec             the spec to describe
   * @param specType         the spec type ({@code "W"}, {@code "P"} or {@code "R"})
   * @param entities         the pre-built entity summary array, or {@code null} for non-W specs
   * @param primaryEntity    the caller-resolved primary (header-level) entity name, surfaced
   *                         alongside {@code entities}; {@code null} omits the key
   * @param includedEntities the same entities the summary was built from, used to derive the
   *                         spec-level read-only marker without a second query; {@code null}
   *                         for non-W specs (and then no marker is emitted)
   */
  static JSONObject buildDiscoverSpec(SFSpec spec, String specType, JSONArray entities,
      String primaryEntity, List<SFEntity> includedEntities) throws Exception {
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
      if (primaryEntity != null) {
        specObj.put("primaryEntity", primaryEntity);
      }
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

  /**
   * Coerce one primitive write value in place, and report it back when it is unusable.
   *
   * @param callerSupplied whether this key came from the agent rather than from a server-injected
   *     default. Only a caller's own value may be rejected — see
   *     {@link #coerceDateFieldValue} and {@code McpToolRouter.coerceFieldTypes}
   * @return a rejection descriptor (see {@link #buildInvalidDateInfo}) when the value cannot be
   *     used, or {@code null} when it was coerced, left alone, or is not the caller's to fix
   */
  static JSONObject coercePrimitiveFieldValue(JSONObject body, String key, Property prop,
      boolean callerSupplied, org.apache.logging.log4j.Logger log) {
    Object value = body.opt(key);
    if (!(value instanceof String)) {
      return null;
    }
    String strVal = (String) value;
    if (strVal.isEmpty()) {
      return null;
    }
    try {
      Class<?> type = prop.getPrimitiveObjectType();
      if (type == Long.class) {
        body.put(key,
            Long.parseLong(strVal.contains(".") ? strVal.substring(0, strVal.indexOf('.')) : strVal));
      } else if (type == java.math.BigDecimal.class) {
        body.put(key, new java.math.BigDecimal(strVal));
      } else if (type != null && Boolean.class.isAssignableFrom(type)) {
        // ETP-4793: shared with the REST coercer via NeoBooleanFormat. The two used to differ
        // on case sensitivity ("y" was accepted here and rejected there).
        body.put(key, NeoBooleanFormat.toLenientBoolean(strVal));
      } else if (type != null && java.util.Date.class.isAssignableFrom(type)) {
        return coerceDateFieldValue(body, key, prop, strVal, callerSupplied, log);
      }
    } catch (Exception e) {
      // Numeric and boolean shapes stay lenient here: a malformed one already surfaces as a DAL
      // error whose text names the column, so the agent is not left guessing. Dates were the
      // exception worth fixing (IMP-24) because the lenient parser *succeeds* on them.
      log.debug("Could not coerce field {} value '{}': {}", key, strVal, e.getMessage());
    }
    return null;
  }

  /**
   * Normalize a date-typed write value to the canonical ISO wire format (ETP-4793 / IMP-16).
   *
   * <p>The DAL parses date strings with a <b>lenient</b> {@code SimpleDateFormat}
   * ({@code JsonUtils.createDateFormat}), so a {@code dd-MM-yyyy} value does not fail — it is
   * reinterpreted, and {@code "06-08-2026"} persists as year <b>0012</b>. That happens even
   * when the agent sends no date at all, because {@code McpToolRouter} re-runs
   * {@code injectMandatoryDefaults} before the save and the server's own default arrives in
   * that format. This branch is the last point where the value can still be repaired.
   *
   * <p>Which properties are eligible at all is narrow — see
   * {@link NeoDateFormat#canonicalShapeFor(Property)}: a time-of-day or timezone-free property
   * is a {@code java.util.Date} as well, and is deliberately left as it arrives.
   *
   * <p><b>Phase 2 (IMP-24).</b> An unrecognised shape used to be passed through with a WARN, which
   * left the agent with the DAL's own leak — {@code status:-4} plus a bare
   * {@code java.text.ParseException} naming no field. It is now rejected as a structured 422
   * instead, but only under two conditions, because either one alone would make the rejection
   * wrong:
   * <ul>
   *   <li>{@link NeoDateFormat#isOffsetDateTime} must not claim it. That family is refused by
   *       {@code toCanonical} <i>because the DAL already parses it correctly</i>; a 422 there would
   *       break a working call rather than fix a broken one;</li>
   *   <li>the value must be the caller's own. A server-injected default in an unrecognised shape is
   *       our bug, and answering it with a 422 would hand the agent an error it cannot act on —
   *       it never sent the field. Those still pass through with the phase-1 WARN, which is the
   *       signal that the default needs fixing at its source.</li>
   * </ul>
   *
   * @return the rejection descriptor, or {@code null} when nothing is wrong with the value
   */
  private static JSONObject coerceDateFieldValue(JSONObject body, String key, Property prop,
      String strVal, boolean callerSupplied, org.apache.logging.log4j.Logger log)
      throws JSONException {
    Boolean shape = NeoDateFormat.canonicalShapeFor(prop);
    if (shape == null) {
      return null;
    }
    String canonical = NeoDateFormat.toCanonical(strVal, shape.booleanValue());
    if (canonical == null) {
      if (NeoDateFormat.isOffsetDateTime(strVal)) {
        log.debug("[MCP] Offset datetime for '{}': '{}' passed through, the DAL parses it", key,
            strVal);
        return null;
      }
      if (!callerSupplied) {
        log.warn("[MCP] Unrecognized date format for server default '{}': '{}' passed through",
            key, strVal);
        return null;
      }
      return buildInvalidDateInfo(key, strVal, shape.booleanValue());
    }
    if (!canonical.equals(strVal)) {
      log.info("[MCP] Normalized date '{}': '{}' -> '{}'", key, strVal, canonical);
      body.put(key, canonical);
    }
    return null;
  }

  /**
   * Describe one unusable date value for the 422 body (ETP-4793 / IMP-24).
   *
   * <p>{@code received} is echoed back verbatim. An agent that batched several writes cannot tell
   * which of them it is being told about otherwise, and the field name alone does not distinguish a
   * wrong <i>format</i> from a wrong <i>date</i> — {@code "2026-02-30"} is ISO-shaped and still
   * impossible, so the value has to be visible for the message to be actionable.
   */
  static JSONObject buildInvalidDateInfo(String key, String received, boolean datetime)
      throws JSONException {
    JSONObject info = new JSONObject();
    info.put("name", key);
    info.put("received", received);
    info.put("expectedFormat", NeoDateFormat.canonicalPattern(datetime));
    info.put("example", datetime ? "2026-08-10T14:30:00" : "2026-08-10");
    return info;
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

  /**
   * Wraps a flat JSON body into the structure expected by DefaultJsonDataService.
   *
   * <p><b>This wrapper does not coerce.</b> It is no longer identical to
   * {@link com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper#wrapForSmartclient}, which calls
   * {@code coerceTypes} on its way through — so on the REST side the wrap is a second safety net,
   * and here it is not. Type and date coercion is the caller's responsibility on this path:
   * {@code McpToolRouter#coerceFieldTypes} must run before the body reaches this method.
   *
   * <p>That asymmetry is what let ETP-4793 / IMP-16 ship a working date coercer and still corrupt
   * dates on {@code neo_update}: the verb wrapped without coercing, and nothing in either signature
   * said it had to. Do not add coercion here to fix a future gap of that kind — it would give
   * {@code neo_create} two passes and hide the missing call site again instead of naming it.
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
    // ETP-4793 / IMP-17: an absent argument is the caller's mistake, so it must not travel as an
    // IllegalArgumentException the router can only classify as a 500. The exception carries its own
    // 422 envelope and names the argument in `field`.
    if (args == null) {
      throw McpRoutingException.missingArgument("Missing arguments", null);
    }
    for (String key : required) {
      if (!args.has(key) || args.isNull(key)) {
        throw McpRoutingException.missingArgument("Missing required argument: " + key, key);
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

  /**
   * Normalize a {@link com.etendoerp.go.schemaforge.NeoResponse} error body into the flat IMP-5
   * envelope (ETP-4793 / IMP-5 clause (iv)).
   *
   * <p><b>The fourth error funnel.</b> IMP-17 enumerated three places a raw error escaped the
   * envelope and closed all three. Verifying IMP-19 found a fourth that none of them covered:
   * a report handler's <em>own</em> errors. {@code McpToolRouter#handleReport} validates the call
   * against the handler's declared contract — that half is enveloped — and then invokes the handler,
   * whose {@code NeoResponse} body is forwarded verbatim. So
   * {@code generate_aging_receivable({})} answered
   * {@code {"error":{"message":"No accounting schema with currency is configured for organization
   * 6184…","status":422}}}: the nested pre-IMP-5 shape, with no machine-detectable code to branch on.
   * The same funnel serves {@code neo_process}, the {@code neo_widget}/amortization paths and every
   * entity pre/post hook, because all of them return through
   * {@code McpHookExecutor#neoResponseToMcpResult} — which is why one normalization here covers five
   * surfaces, the same leverage IMP-17 got from closing three funnels with one change.</p>
   *
   * <p><b>The shape is not rewritten in {@code NeoResponse.error} itself</b>, deliberately. That
   * factory serves the REST endpoints the React UI consumes, and its nested {@code error.message} is
   * what the UI reads. This is the same split IMP-17 §4.4 made for {@code MISSING_REQUIRED_FIELDS}:
   * the REST shape stays, the translation to the agent's shape happens in the MCP layer.</p>
   *
   * <p><b>Additive, never destructive.</b> A handler may have built a rich body on purpose, so no
   * key is ever removed: the nested {@code error} object is flattened (its {@code message} becomes
   * {@code detail}, its remaining keys are lifted alongside), and {@code status} / {@code error} are
   * filled in only when absent. A body that is already a canonical envelope — {@code error} carrying
   * a code {@code String} — is returned untouched, which is what keeps this idempotent and keeps the
   * richer IMP-17 / IMP-24 bodies ({@code missingFields}, {@code invalidDates}, {@code fieldErrors})
   * passing through this method intact.</p>
   *
   * <p><b>No {@code seeAlso} is added</b>, on IMP-17 §4.3's precedent that a deliberate omission
   * beats an unhelpful value. The two topics that exist are {@code "reading records"} and
   * {@code "creating records"}; pointing an agent at either for *"no accounting schema with currency
   * is configured"* sends it to read a recipe that cannot help. When a handler knows a better
   * pointer it can put one in its own body, and this method will preserve it.</p>
   *
   * @param body       the handler's error body, or {@code null}
   * @param httpStatus the response status, used when the body names none
   * @return the normalized envelope; a fresh object when {@code body} is {@code null}, otherwise
   *         {@code body} itself, mutated in place
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  static JSONObject toMcpHandlerError(JSONObject body, int httpStatus) throws JSONException {
    if (body == null) {
      JSONObject envelope = new JSONObject();
      envelope.put(McpConstants.KEY_STATUS, httpStatus);
      envelope.put(McpConstants.KEY_ERROR, errorCodeForStatus(httpStatus));
      envelope.put(McpConstants.KEY_DETAIL, "Request failed with status " + httpStatus);
      return envelope;
    }
    // Already canonical: 'error' holds the code itself. Returning early is what makes repeated
    // normalization safe, and it is why the envelopes IMP-17 and IMP-24 build upstream survive.
    if (body.optString(McpConstants.KEY_ERROR, null) != null
        && body.optJSONObject(McpConstants.KEY_ERROR) == null) {
      return body;
    }
    JSONObject nested = body.optJSONObject(McpConstants.KEY_ERROR);
    int status = httpStatus;
    if (nested != null) {
      status = nested.optInt(McpConstants.KEY_STATUS, httpStatus);
      String message = nested.optString(McpConstants.KEY_MESSAGE, null);
      if (message != null && !message.isBlank() && !body.has(McpConstants.KEY_DETAIL)) {
        body.put(McpConstants.KEY_DETAIL, message);
      }
      // Lift whatever else the handler put inside the nested object rather than discarding it —
      // a handler that reported a field or a candidate list meant the agent to see it.
      for (Iterator<?> keys = nested.keys(); keys.hasNext();) {
        String key = String.valueOf(keys.next());
        if (!McpConstants.KEY_MESSAGE.equals(key) && !McpConstants.KEY_STATUS.equals(key)
            && !body.has(key)) {
          body.put(key, nested.get(key));
        }
      }
      body.remove(McpConstants.KEY_ERROR);
    }
    body.put(McpConstants.KEY_ERROR, errorCodeForStatus(status));
    if (!body.has(McpConstants.KEY_STATUS)) {
      body.put(McpConstants.KEY_STATUS, status);
    }
    if (!body.has(McpConstants.KEY_DETAIL)) {
      body.put(McpConstants.KEY_DETAIL, "Request failed with status " + status);
    }
    return body;
  }

  /**
   * Report a batch rejected by the MCP FK pre-pass in the same outcome envelope a batch failure
   * always uses (ETP-4793 / IMP-5 clause (i)).
   *
   * <p><b>The envelope used to differ by failure class.</b> A batch that failed inside
   * {@code executeBatch} came back as {@code {committed:false, atomic, persisted, hint, failedAt,
   * error:{…}}}. A batch rejected by the FK-by-name pre-pass — which runs <em>before</em>
   * {@code executeBatch} — came back as the resolver's flat error with a {@code failedAt} bolted on
   * and <b>no {@code committed} key at all</b> (evidence C9), so an agent branching on
   * {@code committed}, exactly as the tool description tells it to, read {@code false} from a missing
   * key by luck or crashed on it. One condition, two shapes, and the difference was invisible from
   * the call site.</p>
   *
   * <p><b>{@code atomic:true} / {@code persisted:[]} are true here by construction</b>, not by
   * observation — a stronger guarantee than {@code executeBatch} can give. IMP-23 §1 found that the
   * discriminator three benchmark runs had missed was exactly this: a pre-pass failure happens before
   * the transaction opens, so nothing can have persisted, which is why these failures always
   * <em>looked</em> atomic while persist-time failures were not. What used to be an accident of
   * timing is now a claim the response makes. The hint says so specifically rather than reusing
   * {@code BatchService}'s "rolled back as a unit" wording: no rollback happened, because no
   * transaction was opened.</p>
   *
   * @param fkError the resolver's structured error for the first op that failed to resolve
   * @param index   the index of that operation in the {@code operations} array
   * @param opId    that operation's caller-supplied {@code id}, or {@code null} when it declared none
   * @return the batch outcome envelope
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  static JSONObject toMcpBatchPreflightFailure(JSONObject fkError, int index, String opId)
      throws JSONException {
    JSONObject body = new JSONObject();
    body.put(BatchService.FIELD_COMMITTED, false);
    body.put(BatchService.FIELD_ATOMIC, true);
    body.put(BatchService.FIELD_PERSISTED, new JSONArray());
    body.put(BatchService.FIELD_HINT, "Nothing was persisted: the batch was rejected before the "
        + "transaction opened, so no records were created and none need cleaning up. Fix the "
        + "operation reported in 'failedAt' and retry the whole batch.");
    JSONObject failedAt = new JSONObject();
    failedAt.put("index", index);
    if (StringUtils.isNotBlank(opId)) {
      failedAt.put("id", opId);
    }
    body.put("failedAt", failedAt);
    body.put(McpConstants.KEY_ERROR, fkError);
    return body;
  }

  /**
   * Rewrite a {@code BatchService} failure body into the IMP-5 error envelope (IMP-15).
   * <p>
   * {@code BatchService} serves both the REST {@code /batch} endpoint and {@code neo_batch}, and it
   * forwards the failing operation's sub-response verbatim as {@code error.detail}. For an MCP agent
   * that meant a raw DAL payload — {@code {"response":{"status":-4,"errors":{"id":"New object
   * Currency(null) (key: EUR_Currency) refered to but not present in the import set"}}}} — with no
   * error code, no field and no next step, while the single-record verbs had carried a structured
   * envelope since IMP-5. The translation happens here rather than in {@code BatchService} so the
   * REST contract, and any non-MCP caller reading {@code detail}, stay untouched.
   * <p>
   * Success bodies ({@code committed:true}) and bodies with no {@code error} object pass through
   * unchanged. The {@code failedAt} pointer is always preserved — it is what tells the agent which
   * operation to fix.
   *
   * @param result the body returned by {@code BatchService#executeBatch}, mutated in place
   * @return the same object, for call chaining
   * @throws JSONException never in practice (all values are plain strings/ints)
   */
  static JSONObject toMcpBatchFailure(JSONObject result) throws JSONException {
    if (result == null || result.optBoolean("committed", false)) {
      return result;
    }
    JSONObject rawError = result.optJSONObject(McpConstants.KEY_ERROR);
    if (rawError == null) {
      return result;
    }
    int status = rawError.optInt(McpConstants.KEY_STATUS, 500);
    String message = rawError.optString(McpConstants.KEY_MESSAGE, "Batch operation failed");
    JSONObject detail = rawError.optJSONObject(McpConstants.KEY_DETAIL);

    JSONArray missingFields = extractMissingFields(detail);
    if (missingFields != null) {
      result.put(McpConstants.KEY_ERROR, buildBatchMissingFieldsError(missingFields));
      return result;
    }

    String dalMessage = extractDalMessage(detail);

    JSONObject clean = new JSONObject();
    clean.put(McpConstants.KEY_STATUS, status);
    clean.put(McpConstants.KEY_ERROR, errorCodeForStatus(status));
    clean.put(McpConstants.KEY_DETAIL, dalMessage == null ? message : message + ": " + dalMessage);
    clean.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    result.put(McpConstants.KEY_ERROR, clean);
    return result;
  }

  /**
   * Lift {@code NeoCrudHandler}'s {@code MISSING_REQUIRED_FIELDS} body into the {@code missingFields}
   * list an MCP agent already knows (ETP-4793 / IMP-17, from IMP-23 §9.4).
   *
   * <p>The condition was reported three different ways for the same mistake: {@code neo_create}
   * answered IMP-5's {@code missingFields} 422, the REST CRUD path answered ETP-3894's
   * {@code MISSING_REQUIRED_FIELDS} 400, and {@code neo_batch} — which reaches that REST path —
   * forwarded whatever came back. Omitting {@code partnerAddress} inside a batch used to surface a
   * <b>500</b> carrying a raw Postgres not-null violation with the whole failing row in it. The REST
   * shape stays as it is, because the React UI highlights fields from it; the translation to the
   * agent's shape belongs here, for the same reason the rest of this method does.</p>
   *
   * @param detail the failing operation's forwarded sub-response, or {@code null}
   * @return the missing field names, or {@code null} when this is not that failure
   */
  private static JSONArray extractMissingFields(JSONObject detail) {
    if (detail == null) {
      return null;
    }
    JSONObject body = detail.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    JSONObject error = (body == null ? detail : body).optJSONObject(McpConstants.KEY_ERROR);
    if (error == null
        || !MissingRequiredFieldsException.ERROR_CODE.equals(error.optString("code", null))) {
      return null;
    }
    JSONArray fields = error.optJSONArray(McpConstants.PARAM_FIELDS);
    return fields == null || fields.length() == 0 ? null : fields;
  }

  /** The {@code missingFields} 422 a batch reports for an omitted required value (IMP-17). */
  private static JSONObject buildBatchMissingFieldsError(JSONArray missingFields)
      throws JSONException {
    JSONObject clean = new JSONObject();
    clean.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    clean.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
    clean.put(McpConstants.KEY_DETAIL, "Missing required fields on the operation named in 'failedAt'");
    clean.put(McpConstants.KEY_MISSING_FIELDS, missingFields);
    clean.put(McpConstants.KEY_HINT, "Add these fields to that operation's body, or use "
        + "neo_selectors to find valid values for foreignKey fields, then retry the whole batch.");
    clean.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return clean;
  }

  /** Map a batch failure's HTTP status onto a stable machine-detectable code (IMP-15). */
  static String errorCodeForStatus(int status) {
    if (status == McpConstants.STATUS_NOT_FOUND) {
      return McpConstants.ERROR_NOT_FOUND;
    }
    if (status == 405) {
      return McpConstants.ERROR_METHOD_NOT_ALLOWED;
    }
    // Everything else in the 4xx range is something the agent can fix by changing the request;
    // 5xx (and the -1 index used for an unexpected batch-wide failure) is not.
    return status >= 400 && status < 500 ? McpConstants.ERROR_VALIDATION : McpConstants.ERROR_SERVER;
  }

  /**
   * Pull the one human-readable sentence out of a DAL sub-response, discarding its transport
   * internals (notably the SmartClient {@code status:-4} an agent cannot act on).
   *
   * @param detail the forwarded sub-response, or {@code null}
   * @return the message, or {@code null} when the payload carries none
   */
  static String extractDalMessage(JSONObject detail) {
    if (detail == null) {
      return null;
    }
    JSONObject response = detail.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (response == null) {
      response = detail;
    }
    JSONObject error = response.optJSONObject(JsonConstants.RESPONSE_ERROR);
    if (error != null) {
      String message = error.optString(McpConstants.KEY_MESSAGE, null);
      if (message != null && !message.isBlank()) {
        return message;
      }
    }
    // Per-field violations: {"errors":{"id":"…","documentNo":"…"}} — keep the field names, they are
    // the most actionable part of the payload.
    JSONObject errors = response.optJSONObject("errors");
    if (errors != null) {
      StringBuilder joined = new StringBuilder();
      Iterator<String> keys = errors.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (joined.length() > 0) {
          joined.append("; ");
        }
        joined.append(key).append(": ").append(errors.optString(key, ""));
      }
      if (joined.length() > 0) {
        return joined.toString();
      }
    }
    String message = response.optString(McpConstants.KEY_MESSAGE, null);
    return message == null || message.isBlank() ? null : message;
  }
}
