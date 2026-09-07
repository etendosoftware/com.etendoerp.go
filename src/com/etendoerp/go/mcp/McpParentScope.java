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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Answers, for one SchemaForge entity: is this a child record, which field links it to its parent,
 * which entity is that parent, and on which verbs may the parent key be omitted.
 *
 * <h2>Why {@code TABLEVEL > 0} is not the whole answer</h2>
 * <p>Cross-checking the SEQNO parent that core resolves against each child table's actual
 * parent-link columns, over the 102 child entities the MCP exposes, gives four distinct
 * situations — and only the first is what a naive {@code tabLevel > 0} check assumes:</p>
 * <ol>
 *   <li><b>82 entities</b> where a parent-link column points at the SEQNO parent's table. These
 *       resolve with no configuration at all.</li>
 *   <li><b>30 of those</b> carry more than one active parent-link column
 *       ({@code purchase-invoice/exchangeRates} has five). Picking "the first one" is not
 *       deterministic — {@code getADColumnList()} order is not guaranteed — so the column is
 *       chosen by matching the parent tab's table, never by position.</li>
 *   <li><b>3 entities</b> ({@code contacts/customer}, {@code employee}, {@code vendorCreditor})
 *       have <i>the same table as their parent</i>: they are the Customer/Employee/Vendor tabs of a
 *       business partner, editing fields of the {@code C_BPartner} row itself. {@code TABLEVEL > 0}
 *       but 1:1 — asking them for a foreign parent id would make them unusable. Detected from the
 *       model, not only from configuration.</li>
 *   <li><b>17 entities</b> where no parent-link column points at the SEQNO parent, so there is
 *       nothing to filter on. {@code product/stock}'s SEQNO parent is {@code M_Product} while its
 *       only parent-link column is {@code M_RefInventory_ID}. These need
 *       {@code MCP_CONFIG}'s {@code parent.field}, and without it the entity is not publishable —
 *       the honest answer, since the gate cannot be applied at all.</li>
 * </ol>
 *
 * <h2>This also fixes an existing defect</h2>
 * <p>{@code McpWriteRequestSupport#resolveParentFK} walks the columns, takes the first with
 * {@code isLinkToParentColumn()} and assigns {@code parentId} to it <b>without checking it points
 * at the real parent</b>. In the 17 mismatched entities that first column is the wrong one: a
 * {@code neo_create} on {@code product/stock} passing a product id writes it into the
 * "referenced inventory" foreign key. It does not fail — it stores wrong data, which is why nobody
 * noticed. Both names exist on the same entity ({@code product} and {@code referencedInventory}),
 * so the write lands in the neighbouring field. Routing that resolution through this class corrects
 * it by construction rather than by a separate patch.</p>
 *
 * <h2>Resolution order</h2>
 * <pre>
 * 1. tabLevel == 0 or no tab        -&gt; NOT_CHILD, the gate does not apply
 * 2. parent.mode = sameRecord       -&gt; SAME_RECORD (verified against the model)
 * 3. parent.field declared          -&gt; RESOLVED by that property. Wins over the heuristic
 * 4. one parent-link column pointing at the SEQNO parent's table -&gt; RESOLVED
 * 5. anything else                  -&gt; UNRESOLVABLE: not publishable until declared
 * </pre>
 *
 * <p>Step 5 is what keeps the gate from having a permissive default. An entity whose parent cannot
 * be identified is not served with the check skipped — it is withheld, and {@code neo_discover}
 * says why, the same way a report spec with no contract is withheld today.</p>
 *
 * <h2>The one place {@code MCP_CONFIG} is read on this path</h2>
 * <p>This class is the single consumer of the column for parent purposes, and it is also what
 * reports a broken payload — from <i>any</i> section, not only {@code parent}. Callers therefore
 * ask one question instead of two: {@link Scope#isPublishable()} covers both "the configuration is
 * unusable" and "the parent cannot be identified", which are the same answer from the caller's
 * point of view — do not serve this entity, and here is why.</p>
 *
 * <p>Results are cached by {@code AD_Tab} id through {@link McpConfigCache}: the dictionary lookup
 * behind {@code KernelUtils.getParentTab} is an SQL query, and the answer only changes with an
 * {@code update.database}. The {@code MCP_CONFIG} half is cached separately and invalidated on
 * edit, so a configuration change is picked up without waiting for the dictionary TTL.</p>
 */
final class McpParentScope {

  private static final Logger log = LogManager.getLogger(McpParentScope.class);

  /** What kind of parent relationship an entity has. */
  enum Kind {
    /** A header tab, or handler-backed with no tab: nothing to gate. */
    NOT_CHILD,
    /** A 1:1 tab over the parent's own table: the "parent" is the record itself. */
    SAME_RECORD,
    /** A genuine child with an identified link field. */
    RESOLVED,
    /**
     * A child that declares it has no link to its parent: reads are global, writes are refused.
     * Publishable, unlike {@link #UNRESOLVABLE}, because the absence is declared and justified
     * rather than merely undiscovered.
     */
    UNPARENTED,
    /** A child whose link field cannot be identified: not publishable. */
    UNRESOLVABLE
  }

  private McpParentScope() {
  }

  /**
   * Everything the gate and the discovery tools need to know about one entity's parent.
   */
  static final class Scope {

    private final Kind kind;
    private final String parentField;
    private final String parentEntity;
    private final Set<String> optionalVerbs;
    private final String reason;
    private final String problem;

    private Scope(Kind kind, String parentField, String parentEntity, Set<String> optionalVerbs,
        String reason, String problem) {
      this.kind = kind;
      this.parentField = parentField;
      this.parentEntity = parentEntity;
      this.optionalVerbs = optionalVerbs;
      this.reason = reason;
      this.problem = problem;
    }

    Kind getKind() {
      return kind;
    }

    /**
     * @return the DAL property linking this entity to its parent, or {@code null} unless
     *         {@link Kind#RESOLVED}
     */
    String getParentField() {
      return parentField;
    }

    /**
     * @return the parent entity's name as the agent addresses it, or {@code null} when it could not
     *         be derived. Used to name the parent in a refusal, never to gate
     */
    String getParentEntity() {
      return parentEntity;
    }

    /**
     * @return why this entity cannot be published, or {@code null} when it can
     */
    String getProblem() {
      return problem;
    }

    /**
     * @return {@code true} when the entity may be served
     */
    boolean isPublishable() {
      return kind != Kind.UNRESOLVABLE;
    }

    /**
     * Whether the parent key must accompany a given verb.
     *
     * @param verb one of {@link McpParentSection#ALL_VERBS}
     * @return {@code true} only for a resolved child whose configuration does not relax that verb
     */
    boolean requiresParentFor(String verb) {
      return kind == Kind.RESOLVED && !optionalVerbs.contains(verb);
    }

    /**
     * The verbs the parent key is required on, for {@code neo_discover} and {@code neo_schema}.
     *
     * @return the required verbs in declaration order, empty when the gate does not apply
     */
    List<String> requiredVerbs() {
      List<String> required = new ArrayList<>();
      if (kind != Kind.RESOLVED) {
        return required;
      }
      for (String verb : McpParentSection.ALL_VERBS) {
        if (!optionalVerbs.contains(verb)) {
          required.add(verb);
        }
      }
      return required;
    }

    /**
     * This scope as the block {@code neo_discover} and {@code neo_schema} publish.
     *
     * <p>Emitting it is what keeps the gate from being pure friction: an agent that can read
     * {@code parentField} and {@code parentRequiredFor} makes the correct call on the first try
     * instead of guessing and retrying.</p>
     *
     * @return the descriptor, or empty for a header entity, which needs no annotation
     * @throws JSONException if the descriptor cannot be built
     */
    Optional<JSONObject> describe() throws JSONException {
      if (kind == Kind.NOT_CHILD) {
        return Optional.empty();
      }
      JSONObject item = new JSONObject();
      item.put("isChild", kind != Kind.SAME_RECORD);
      if (kind == Kind.SAME_RECORD) {
        item.put("sameRecordAsParent", true);
      }
      if (kind == Kind.UNPARENTED) {
        // Said explicitly, because the alternative is the agent inferring it from a result set
        // that looks too large — which is the failure this whole change exists to remove. The
        // entity is a child in the UI and has a named parent, but the parent is a scope here, not
        // a filter: there is no key to pass and passing one would be rejected as unknown.
        item.put("parentFilterable", false);
      }
      if (parentEntity != null) {
        item.put("parentEntity", parentEntity);
      }
      if (parentField != null) {
        item.put("parentField", parentField);
      }
      List<String> required = requiredVerbs();
      if (!required.isEmpty()) {
        item.put("parentRequiredFor", new JSONArray(required));
      }
      if (reason != null) {
        item.put("parentOptionalReason", reason);
      }
      if (problem != null) {
        item.put("parentProblem", problem);
      }
      return Optional.of(item);
    }
  }

  private static final Scope NOT_CHILD =
      new Scope(Kind.NOT_CHILD, null, null, Set.of(), null, null);

  /**
   * Resolve one entity's parent scope.
   *
   * @param entity the SchemaForge entity; {@code null} or tab-less yields {@link Kind#NOT_CHILD}
   * @return the scope, never {@code null}
   */
  static Scope forEntity(SFEntity entity) {
    if (entity == null) {
      return NOT_CHILD;
    }
    Tab tab = entity.getADTab();
    if (tab == null || tab.getTabLevel() == null || tab.getTabLevel() <= 0) {
      // Header tabs and handler-backed entities (dashboard widgets, bp-trend) have no parent.
      return NOT_CHILD;
    }
    // The single read of MCP_CONFIG on this path. The scope is the one caller that needs it, so
    // reading it here — rather than eagerly wherever an entity is touched — keeps the column's
    // consumers to exactly one, and lets the cache do its job across the verbs that follow.
    McpEntityConfig.Resolved resolved = McpEntityConfig.forEntity(entity);
    if (!resolved.isUsable()) {
      // Any section's payload being unusable withholds the entity, not just this one's: an entity
      // whose configuration cannot be trusted must not be served on the strength of the parts that
      // happened to parse. Reporting it through the scope is what keeps callers to one question
      // ("may I serve this, and how?") instead of two.
      return new Scope(Kind.UNRESOLVABLE, null, null, Set.of(), null, resolved.describeProblems());
    }
    return resolveChild(entity, tab, resolved.section(McpParentSection.NAME).orElse(null));
  }

  private static Scope resolveChild(SFEntity entity, Tab tab, JSONObject config) {
    Set<String> optional = McpParentSection.optionalVerbs(config);
    String reason = McpParentSection.reason(config);
    Tab parentTab = parentTabOf(tab);
    Entity dalEntity = entityOf(tab);

    if (dalEntity == null) {
      return unresolvable(tab, "its table has no DAL entity", optional, reason);
    }
    Scope unparented = unparentedScope(entity, parentTab, config, reason);
    if (unparented != null) {
      return unparented;
    }
    Scope sameRecord = sameRecordScope(entity, tab, parentTab, config, optional, reason);
    if (sameRecord != null) {
      return sameRecord;
    }
    String declared = McpParentSection.declaredField(config);
    if (declared != null) {
      return declaredScope(entity, tab, parentTab, dalEntity, declared, config, optional, reason);
    }
    return heuristicScope(entity, tab, parentTab, dalEntity, config, optional, reason);
  }

  /**
   * A tab that declares no parent link at all: reads are unfiltered, writes are refused.
   *
   * <p>Only ever reached by declaration — nothing about the model implies it, and inferring it
   * would defeat the gate, since "I could not find the parent link" is exactly the state that must
   * withhold an entity rather than publish it unfiltered. The declaration is what turns an
   * undiscovered link into an audited decision.</p>
   *
   * <p>The write check is the part that carries the safety. An entity with no way to name its
   * parent cannot be created without producing an orphan, so advertising POST/PUT/PATCH/DELETE
   * alongside this mode is a contradiction, and it is refused as a configuration error instead of
   * being quietly honoured for reads. It is also self-healing: the flags live on {@code
   * ETGO_SF_ENTITY}, whose changes invalidate this entity's cached scope, so turning POST on later
   * re-resolves to {@link Kind#UNRESOLVABLE} and the entity stops being served — no separate
   * review step has to remember this rule.</p>
   *
   * @return the scope, or {@code null} when the mode was not declared
   */
  private static Scope unparentedScope(SFEntity entity, Tab parentTab, JSONObject config,
      String reason) {
    if (!McpParentSection.isUnparented(config)) {
      return null;
    }
    List<String> writes = advertisedWrites(entity);
    if (!writes.isEmpty()) {
      return new Scope(Kind.UNRESOLVABLE, null, null, Set.of(), reason,
          "MCP_CONFIG declares mode '" + McpParentSection.MODE_UNPARENTED + "' but entity '"
              + entity.getName() + "' advertises " + writes + ". A record whose parent cannot be "
              + "named cannot be written without creating an orphan — either turn those methods "
              + "off or declare a parent.field");
    }
    return new Scope(Kind.UNPARENTED, null, parentEntityName(entity, parentTab, config),
        Set.of(), reason, null);
  }

  /**
   * The write methods an entity advertises, named as the agent would see them.
   *
   * @param entity the SchemaForge entity
   * @return the advertised write methods, empty for a read-only entity
   */
  private static List<String> advertisedWrites(SFEntity entity) {
    List<String> writes = new ArrayList<>();
    if (Boolean.TRUE.equals(entity.isPost())) {
      writes.add("POST");
    }
    if (Boolean.TRUE.equals(entity.isPut())) {
      writes.add("PUT");
    }
    if (Boolean.TRUE.equals(entity.isPatch())) {
      writes.add("PATCH");
    }
    if (Boolean.TRUE.equals(entity.isDelete())) {
      writes.add("DELETE");
    }
    return writes;
  }

  /**
   * Category 3: a tab whose table IS its parent's table.
   *
   * <p>Accepted both when declared and when the model says so on its own. Detecting it is what
   * keeps the three business-partner role tabs usable: they would otherwise fall through to
   * "no parent-link column" and be withheld. A declaration that contradicts the model is refused,
   * because {@code sameRecord} is a claim about the model, not a preference.</p>
   */
  private static Scope sameRecordScope(SFEntity entity, Tab tab, Tab parentTab, JSONObject config,
      Set<String> optional, String reason) {
    boolean declared = McpParentSection.isSameRecord(config);
    boolean actual = parentTab != null && sameTable(tab, parentTab);
    if (declared && !actual) {
      return unresolvable(tab,
          "MCP_CONFIG declares mode 'sameRecord' but this tab's table differs from its parent's",
          optional, reason);
    }
    if (!actual) {
      return null;
    }
    return new Scope(Kind.SAME_RECORD, null, parentEntityName(entity, parentTab, config),
        optional, reason, null);
  }

  /** Steps 3: an explicit {@code parent.field} wins over the heuristic. */
  private static Scope declaredScope(SFEntity entity, Tab tab, Tab parentTab, Entity dalEntity,
      String declared, JSONObject config, Set<String> optional, String reason) {
    Property property = resolveProperty(dalEntity, declared);
    if (property == null) {
      return unresolvable(tab, "MCP_CONFIG parent.field '" + declared
          + "' matches no property or column of " + dalEntity.getName(), optional, reason);
    }
    if (property.isPrimitive() || property.getTargetEntity() == null) {
      return unresolvable(tab, "MCP_CONFIG parent.field '" + declared
          + "' is not a foreign key — filtering by parent needs a reference", optional, reason);
    }
    // Deliberately not an error when it disagrees with the SEQNO parent: the 17 mismatched
    // entities are exactly the case where the declaration is meant to override the heuristic.
    if (parentTab != null && !targetsTableOf(property, parentTab)) {
      log.debug("Entity {} declares parent.field {} pointing at {}, not the tab parent {}",
          entity.getName(), declared, property.getTargetEntity().getName(),
          parentTab.getTable().getDBTableName());
    }
    return new Scope(Kind.RESOLVED, property.getName(),
        parentEntityName(entity, parentTab, config), optional, reason, null);
  }

  /**
   * Step 4: among the parent-link columns, the one whose target is the parent tab's table.
   *
   * <p>Matching by target table rather than by position is the fix for the 30 multi-link entities:
   * "the first column in the list" is whatever {@code getADColumnList()} happens to return.</p>
   */
  private static Scope heuristicScope(SFEntity entity, Tab tab, Tab parentTab, Entity dalEntity,
      JSONObject config, Set<String> optional, String reason) {
    if (parentTab == null) {
      return unresolvable(tab, "its parent tab could not be resolved", optional, reason);
    }
    List<Property> candidates = parentLinkProperties(tab, dalEntity);
    if (candidates.isEmpty()) {
      return unresolvable(tab, "it declares no active parent-link column. Set MCP_CONFIG "
          + "parent.field to the property that links it to '"
          + parentTab.getTable().getDBTableName() + "'", optional, reason);
    }
    for (Property candidate : candidates) {
      if (targetsTableOf(candidate, parentTab)) {
        return new Scope(Kind.RESOLVED, candidate.getName(),
            parentEntityName(entity, parentTab, config), optional, reason, null);
      }
    }
    List<String> names = new ArrayList<>();
    for (Property candidate : candidates) {
      names.add(candidate.getName());
    }
    return unresolvable(tab, "none of its parent-link fields " + names + " points at the parent tab "
        + "table '" + parentTab.getTable().getDBTableName() + "'. Set MCP_CONFIG parent.field to "
        + "the correct one", optional, reason);
  }

  private static Scope unresolvable(Tab tab, String why, Set<String> optional, String reason) {
    String problem = "cannot determine the parent of tab '" + tab.getName() + "': " + why;
    log.warn("Parent scope unresolvable — {}", problem);
    return new Scope(Kind.UNRESOLVABLE, null, null, optional, reason, problem);
  }

  // -- model helpers --------------------------------------------------------

  /**
   * The parent tab, as core resolves it: the previous tab by {@code SEQNO} at {@code TABLEVEL - 1}
   * within the same window ({@code KernelUtils_data.xsql}). Cached — it costs an SQL query.
   */
  private static Tab parentTabOf(Tab tab) {
    try {
      return McpConfigCache.resolvedHierarchy("parentTab:" + tab.getId(),
          () -> Optional.ofNullable(KernelUtils.getInstance().getParentTab(tab)))
          .orElse(null);
    } catch (RuntimeException e) {
      log.debug("Could not resolve parent tab of {}", tab.getId(), e);
      return null;
    }
  }

  private static Entity entityOf(Tab tab) {
    Table table = tab.getTable();
    if (table == null) {
      return null;
    }
    try {
      return ModelProvider.getInstance().getEntityByTableId(table.getId());
    } catch (RuntimeException e) {
      log.debug("No DAL entity for table {}", table.getDBTableName(), e);
      return null;
    }
  }

  private static boolean sameTable(Tab a, Tab b) {
    return a.getTable() != null && b.getTable() != null
        && a.getTable().getId().equals(b.getTable().getId());
  }

  /** Every active parent-link column of the tab's table, as DAL properties. */
  private static List<Property> parentLinkProperties(Tab tab, Entity dalEntity) {
    List<Property> properties = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Column column : tab.getTable().getADColumnList()) {
      if (!Boolean.TRUE.equals(column.isLinkToParentColumn()) || !column.isActive()) {
        continue;
      }
      try {
        Property property = dalEntity.getPropertyByColumnName(column.getDBColumnName());
        if (property != null && seen.add(property.getName())) {
          properties.add(property);
        }
      } catch (RuntimeException e) {
        log.debug("Column {} not mappable on {}", column.getDBColumnName(), dalEntity.getName());
      }
    }
    return properties;
  }

  private static boolean targetsTableOf(Property property, Tab parentTab) {
    if (property.isPrimitive() || property.getTargetEntity() == null
        || parentTab.getTable() == null) {
      return false;
    }
    Entity parentEntity = ModelProvider.getInstance()
        .getEntityByTableId(parentTab.getTable().getId());
    return parentEntity != null && parentEntity.getName().equals(property.getTargetEntity().getName());
  }

  /** Accepts a property name or a DBColumnName, like every other MCP entry point. */
  private static Property resolveProperty(Entity dalEntity, String name) {
    Property property = dalEntity.getProperty(name, false);
    if (property != null) {
      return property;
    }
    return dalEntity.getPropertyByColumnName(name, false);
  }

  /**
   * The parent entity's name as the agent addresses it.
   *
   * <p>A declared {@code parent.entity} wins; otherwise it is looked up among the spec's included
   * entities by parent tab. Comes back {@code null} when the parent is not exposed in this spec —
   * one of the six orphan cases — and the refusal then names only the field, which is still enough
   * to act on.</p>
   */
  private static String parentEntityName(SFEntity entity, Tab parentTab, JSONObject config) {
    String declared = McpParentSection.declaredEntity(config);
    if (declared != null) {
      return declared;
    }
    if (parentTab == null) {
      return null;
    }
    SFSpec spec = entity.getETGOSFSpec();
    if (spec == null) {
      return null;
    }
    for (SFEntity sibling : McpToolRouterSupport.listIncludedEntities(spec.getId())) {
      Tab siblingTab = sibling.getADTab();
      if (siblingTab != null && siblingTab.getId().equals(parentTab.getId())) {
        return sibling.getName();
      }
    }
    log.debug("Parent tab {} of {} is not an included entity of its spec",
        parentTab.getId(), entity.getName());
    return null;
  }

  /**
   * The refusal an agent gets when it omits a required parent key.
   *
   * <p>Self-correcting on purpose, in the style of {@code buildNotFoundError} and
   * {@code resolveIncludedEntityOrExplain}: it names the parent entity and the field, so the next
   * call can be right instead of guessed. A message that only said "parentId is required" would
   * cost an extra round trip every time.</p>
   *
   * <p>The wording lives in {@link McpRoutingException#parentRequired}, which the tools that can
   * throw use directly; this is the envelope form, for the call sites that return a JSON body
   * instead of raising. Both spell the refusal the same way because there is only one copy of
   * it.</p>
   *
   * @param specName   the spec being addressed
   * @param entityName the child entity
   * @param scope      the resolved scope, which must be {@link Kind#RESOLVED}
   * @return the error envelope
   * @throws JSONException if the envelope cannot be built
   */
  static JSONObject buildParentRequiredError(String specName, String entityName, Scope scope)
      throws JSONException {
    return McpRoutingException
        .parentRequired(specName, entityName, scope.getParentEntity(), scope.getParentField())
        .toEnvelope();
  }

  /**
   * Copy a scope's descriptor onto a response object, so every tool that describes an entity
   * describes it identically.
   *
   * <p>{@code neo_discover} and {@code neo_schema} both need this block, and an agent that reads
   * {@code parentField} from one and then calls the other must find the same key spelled the same
   * way. Duplicating the merge loop in each caller is how those two drift apart, so the loop lives
   * here and the callers pass their own target.</p>
   *
   * <p>A non-publishable scope also contributes {@code configError}: an entity whose
   * {@code MCP_CONFIG} cannot be parsed is reported as broken by the tools that advertise it,
   * rather than being advertised as if it were fine.</p>
   *
   * @param target the response object to decorate; untouched for a header entity
   * @param scope  the resolved scope
   * @throws JSONException if the descriptor cannot be written
   */
  static void publishInto(JSONObject target, Scope scope) throws JSONException {
    if (!scope.isPublishable()) {
      target.put("configError", scope.getProblem());
    }
    Optional<JSONObject> parentInfo = scope.describe();
    if (parentInfo.isEmpty()) {
      return;
    }
    JSONObject info = parentInfo.get();
    java.util.Iterator<String> keys = info.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      target.put(key, info.get(key));
    }
  }
}
