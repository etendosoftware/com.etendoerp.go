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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoServerOwnedFields;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;
import com.etendoerp.go.schemaforge.util.NeoListReferenceError;

/**
 * Request-body preparation and DAL-response classification helpers extracted from
 * {@link McpToolRouter} (ETP-4793): everything {@code handleCreate}/{@code handleUpdate} need to
 * turn caller-supplied fields into a {@code DefaultJsonDataService}-ready body, plus the
 * classifier that turns its response into an IMP-5 envelope. Kept as a focused companion — mirrors
 * the same seam {@link McpQuerySupport} already cut for the read path — so {@link McpToolRouter}
 * stays within Sonar's method-count-per-class limit (S1448, 35 methods; the router carried 43).
 *
 * <p>Every method here is a pure re-shape of a body/response object the caller already holds, plus
 * the DAL/AD lookups needed to do it (schema introspection, column-to-property resolution). None of
 * it depends on {@link McpToolRouter} instance state, which is what made this cluster a clean cut
 * rather than an arbitrary one: no field carried along, no constructor needed.
 */
final class McpWriteRequestSupport {

  private McpWriteRequestSupport() {
    // utility class — no instances
  }

  /**
   * Get the AD_Tab linked to an entity, or throw if not linked.
   */
  static Tab getAdTabOrThrow(SFEntity sfEntity, String entityName) throws Exception {
    Tab tab = sfEntity.getADTab();
    if (tab == null) {
      // ETP-5405: a routing failure, not a server fault — see McpRoutingException.entityHasNoTab.
      throw McpRoutingException.entityHasNoTab(entityName,
          McpHookExecutor.resolveEntityHandler(sfEntity) != null);
    }
    return tab;
  }

  /**
   * Build the base parameter map for DefaultJsonDataService calls.
   */
  static Map<String, String> buildBaseParams(Tab adTab, String dalEntityName) {
    Map<String, String> params = new HashMap<>();
    params.put(JsonConstants.ENTITYNAME, dalEntityName);
    params.put(JsonConstants.TAB_PARAMETER, adTab.getId());
    params.put(JsonConstants.WINDOW_ID, adTab.getWindow().getId());
    params.put(JsonConstants.NO_ACTIVE_FILTER, "true");
    return params;
  }

  /**
   * Map user-provided fields to DAL property names without SF-field filtering.
   * Accepts both DAL property names ("businessPartner") and DB column names
   * ("C_BPartner_ID"), resolving all to their DAL property equivalents.
   * This allows MCP AI agents to set any valid column on the table.
   */
  static JSONObject mapFieldsToDalProperties(JSONObject fields, Tab adTab)
      throws JSONException {
    return mapFieldsToDalProperties(fields, adTab, null);
  }

  /**
   * Maps the caller's field names onto DAL properties, refusing any the spec excludes.
   *
   * <p><b>IMP-39 — this reverses a deliberate earlier decision, and the reversal is the point.</b>
   * Both write call sites carried the comment <em>"MCP: accept all valid table columns from AI
   * agents, not just SF-configured ones. filterWriteRequest strips fields not in ETGO_SF_FIELD
   * writableFields, which is too restrictive for MCP where AI agents need to set any valid
   * column."</em> The cost of that openness was measured: {@code orderReference}, curated out of
   * the sales-order window, could be written and filtered while {@code neo_get} refused to project
   * it — so an agent could set a value, be told 200, and never read it back. The three tools now
   * answer the same question the same way.</p>
   *
   * <p><b>IMP-48 — and refusing what it exposes as read-only.</b> The same pass rejects a value
   * sent for a field the spec publishes with {@code readOnly: true}. That gate existed only on the
   * REST path ({@code NeoFieldFilter.filterCreateRequest}, IMP-28 clause 2); the MCP built a
   * {@code NeoFieldFilter} solely to project GET responses, so nothing stopped the write and AD's
   * {@code isUpdatable} alone decided whether the value was dropped or persisted. The exemptions
   * are copied from that predicate rather than reinvented — see {@link McpQuerySupport#writeGate}.
   * It applies to {@code neo_create} and {@code neo_update} alike: a field is read-only or it is
   * not, and which verb is asking does not change the answer.</p>
   *
   * <p><b>What it does not do.</b> A key that resolves to no property at all still passes through
   * untouched: that is <b>IMP-18</b> (an unknown field accepted in silence on write) and it is not
   * fixed here. The excluded set is only the one the spec explicitly excluded — a field with
   * no {@code ETGO_SF_FIELD} row is uncurated, and absence of curation is not a decision. Nor does
   * either gate touch the server's own injected keys: the injectors run downstream of this
   * mapping, on the body it returns, so a derived read-only value is unaffected.</p>
   *
   * @param fields   the caller's field map
   * @param adTab    the tab whose table the fields belong to
   * @param sfEntity the SchemaForge entity; {@code null} skips the check, which is what the
   *                 two-argument overload preserves for callers that have no spec in hand
   * @return the body keyed by DAL property name
   * @throws JSONException       if the body cannot be read
   * @throws McpRoutingException 422 {@code field_not_allowed} naming what may be sent instead, or
   *                             422 {@code read_only_field} for a field the surface publishes as
   *                             read-only
   */
  static JSONObject mapFieldsToDalProperties(JSONObject fields, Tab adTab, SFEntity sfEntity)
      throws JSONException {
    return mapFieldsToDalProperties(fields, adTab, sfEntity, new java.util.TreeSet<>());
  }

  /**
   * As {@link #mapFieldsToDalProperties(JSONObject, Tab, SFEntity)}, also collecting the keys that
   * matched no field of the entity.
   *
   * <p><b>IMP-18 — the write verbs report, they do not refuse.</b> {@code neo_schema},
   * {@code neo_list} and {@code neo_get} have answered an unrecognised name with
   * {@code unknownFields} since 2026-08-10; the write verbs dropped it in silence, so a create
   * carrying a misspelt field returned 201 and no later read could contradict it. The obvious
   * symmetry with the two gates above — refuse it — was measured and rejected: of the 73 handler
   * qualifiers reachable by an MCP write, at least eight read request keys that are <b>not AD
   * columns anywhere in the instance</b> ({@code formState}, {@code lines}, {@code shipmentId},
   * {@code receiptId}, {@code fieldValues}, {@code includeZeroStock}, {@code destinationAccountId},
   * {@code paymentRemoval} …). Those keys travel through exactly this branch today. Unlike an
   * excluded or read-only field, "unknown" here is not a set anything declares, so a refusal could
   * not tell a caller's typo from a handler's own protocol — and the reporting is what will produce
   * the inventory a refusal would need.</p>
   *
   * @param fields   the caller's field map
   * @param adTab    the tab whose table the fields belong to
   * @param sfEntity the SchemaForge entity; {@code null} skips the two gates
   * @param unknown  collects, in order, the keys that matched no property; never {@code null}
   * @return the body keyed by DAL property name
   * @throws JSONException if the body cannot be read
   */
  static JSONObject mapFieldsToDalProperties(JSONObject fields, Tab adTab, SFEntity sfEntity,
      Set<String> unknown) throws JSONException {
    return mapFieldsToDalProperties(fields, adTab, sfEntity, unknown, new JSONObject());
  }

  /**
   * As {@link #mapFieldsToDalProperties(JSONObject, Tab, SFEntity, Set)}, also collecting the
   * server-owned fields the caller sent whose value was not its own session's.
   *
   * @param serverOwned collects the discarded tenant fields worth reporting; never {@code null}
   * @return the body with DAL property names, and without any server-owned key
   * @throws JSONException if the body cannot be read
   */
  static JSONObject mapFieldsToDalProperties(JSONObject fields, Tab adTab, SFEntity sfEntity,
      Set<String> unknown, JSONObject serverOwned) throws JSONException {
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    McpQuerySupport.WriteGate gate = McpQuerySupport.writeGate(sfEntity, dalEntity);
    // ETP-5368: resolved once for the whole body rather than per unresolved key — it is a DB read.
    Set<String> virtualFieldNames = virtualFieldNames(sfEntity);
    JSONObject mapped = new JSONObject();

    Iterator<String> keys = fields.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = fields.get(key);
      Property prop = resolveProperty(dalEntity, key);
      String mappedKey = mappedKeyFor(dalEntity, key, prop);

      // Tenant ownership outranks curation: client and organization are resolved from the
      // session on every write, so the caller's value is dropped here and never reaches the
      // body. Neither column has an ETGO_SF_FIELD row, so both gates below would let it
      // through - which is how a create could land in another tenant and answer 200 OK.
      if (NeoServerOwnedFields.isServerOwned(mappedKey)) {
        NeoServerOwnedFields.recordIfDifferent(serverOwned, mappedKey, value);
        continue;
      }

      if (prop == null) {
        // parentId is a declared argument of the write tools, not a stray key - see
        // resolveParentFK. Every other unresolved key is reported, not refused (IMP-18).
        // ETP-5368: a wrapper entity's virtual fields resolve against a second table, so they are
        // not properties of this one - but neo_schema now publishes them and the handler writes
        // them, and a key the schema advertises must not come back labelled unrecognised.
        if (!McpConstants.PARAM_PARENT_ID.equals(key) && !virtualFieldNames.contains(key)) {
          unknown.add(key);
        }
      } else {
        applyWriteGates(gate, key, mappedKey, value, sfEntity, dalEntity);
      }
      mapped.put(mappedKey, value);
    }
    return mapped;
  }

  /**
   * The caller-facing names of the virtual fields the entity's wrapper policy publishes.
   *
   * <p>ETP-5368. Compared against the backing table's own DAL property names, resolved the same
   * way {@code neo_schema} resolves them, so the two answers come from one source rather than from
   * two hand-kept lists.
   */
  private static Set<String> virtualFieldNames(SFEntity sfEntity) {
    Set<String> names = new HashSet<>();
    for (Column col : NeoSelectorPolicy.resolveVirtualColumns(sfEntity)) {
      names.add(col.getDBColumnName());
      Entity backing = ModelProvider.getInstance()
          .getEntityByTableName(col.getTable().getDBTableName());
      Property prop = backing == null
          ? null
          : backing.getPropertyByColumnName(col.getDBColumnName(), false);
      if (prop != null) {
        names.add(prop.getName());
      }
    }
    return names;
  }

  /**
   * The DAL property a caller's key names: its property name first, its DB column name second.
   *
   * @param dalEntity the entity being written to
   * @param key       the caller's own key
   * @return the resolved property, or {@code null} when the key names neither
   */
  private static Property resolveProperty(Entity dalEntity, String key) {
    Property byPropertyName = dalEntity.getProperty(key, false);
    return byPropertyName != null ? byPropertyName
        : dalEntity.getPropertyByColumnName(key, false);
  }

  /**
   * The key the caller's value travels under from here on.
   *
   * <p>The caller's own key when it already named a property, or when it resolved to nothing at
   * all - an unresolved key is passed through untouched, which is how {@code parentId} and the
   * handler-read keys reach their handlers. The property name only when the caller named a DB
   * column, since that is the one case where the two spellings differ.</p>
   *
   * @param dalEntity the entity being written to
   * @param key       the caller's own key
   * @param prop      the property {@link #resolveProperty} found, may be {@code null}
   * @return the key to write under
   */
  private static String mappedKeyFor(Entity dalEntity, String key, Property prop) {
    if (prop == null || dalEntity.getProperty(key, false) != null) {
      return key;
    }
    return prop.getName();
  }

  /**
   * Apply the two curation gates to one key that resolved to a property.
   *
   * <p>IMP-39 / IMP-48: two gates, two answers. The unresolved case is not handled here - it is
   * not a refusal but a report (IMP-18), and it stays at the call site so this method has one
   * job and the caller keeps the parameter count honest.</p>
   *
   * @param gate       the entity's write gate
   * @param key        the caller's own key, used in the refusal so it reads back what it sent
   * @param mappedKey  the key the gates are keyed by
   * @param value      the value sent, needed to tell a default echo from an override
   * @param sfEntity   the SchemaForge entity, may be {@code null}
   * @param dalEntity  the DAL entity being written to
   * @throws McpRoutingException when the field is excluded or read-only
   */
  private static void applyWriteGates(McpQuerySupport.WriteGate gate, String key,
      String mappedKey, Object value, SFEntity sfEntity, Entity dalEntity) {
    String entityName = sfEntity == null ? dalEntity.getName() : sfEntity.getName();
    if (gate.excluded.contains(mappedKey)) {
      throw McpRoutingException.fieldNotAllowed(key, entityName,
          McpQuerySupport.filterablePropertyNames(sfEntity, dalEntity));
    }
    if (gate.readOnlyRejectable.contains(mappedKey)
        || gate.rejectsDefaultOverride(mappedKey, value)) {
      throw McpRoutingException.readOnlyField(key, entityName);
    }
  }

  /**
   * Attach the keys a write did not recognise to the body handed back to the agent (IMP-18).
   *
   * <p>Mirrors the {@code unknownFields} array {@code neo_list}, {@code neo_get} and
   * {@code neo_schema} already return, so the same word means the same thing on every tool. The
   * accompanying hint is worded to be <b>true even when a {@code NeoHandler} consumed the key</b>:
   * it says the name was not mapped to a field of this entity and no field of the record holds the
   * value, which is exactly what happened in both cases. Claiming the key was ignored would be a
   * lie on the eight-odd entities whose handlers read their own request keys.</p>
   *
   * @param body    the flattened response body handed to the agent, mutated in place
   * @param unknown the unrecognised keys, in the order collected
   */
  static void reportUnknownFields(JSONObject body, Set<String> unknown) {
    if (body == null || unknown == null || unknown.isEmpty()) {
      return;
    }
    try {
      body.put(McpFieldProjection.KEY_UNKNOWN_FIELDS, new JSONArray(unknown));
      body.put("unknownFieldsHint", "These names were not mapped to a field of this entity, and "
          + "no field of the record holds their value. Call neo_schema with view:\"create\" for "
          + "the names this entity accepts.");
    } catch (JSONException ignored) {
      // Reporting is an aid, never the answer. The write already succeeded; a body that cannot
      // carry the warning is still a valid result, and failing the call over it would be a worse
      // outcome than the silence IMP-18 exists to end. Nothing to recover, nothing to report.
    }
  }

  /**
   * Attach the callout-vs-caller divergences a create left behind (IMP-45).
   *
   * <p>{@code neo_defaults} tells an agent to use its result as the starting point for
   * {@code neo_create}, and {@code neo_create} repeats the advice. Follow it literally and every
   * value handed over becomes a value the caller sent, which ETP-4784 protects from being
   * recomputed by a callout that knows the record's real context. The measured case:
   * {@code neo_defaults(sales-order/header)} answers {@code paymentTerms: "30 Días"} with no
   * business partner in sight, and the partner chosen a moment later implies {@code "Inmediato"} —
   * an agent that echoed the default has pinned the wrong one, and the 201 says nothing.</p>
   *
   * <p>The value the caller sent still wins. Overriding it would be worse: on this path the server
   * cannot tell an echoed default from a value a human deliberately chose, and silently replacing
   * the second is a harder failure than reporting the first. So this reports, as IMP-18 does for
   * unrecognised names — the write succeeded, and now the caller can see what its own value
   * displaced.</p>
   *
   * @param body       the flattened response body handed to the agent, mutated in place
   * @param superseded field → {sent, callout}, or {@code null}/empty when nothing diverged
   */
  static void reportSupersededDefaults(JSONObject body, JSONObject superseded) {
    if (body == null || superseded == null || superseded.length() == 0) {
      return;
    }
    try {
      body.put("supersededDefaults", superseded);
      body.put("supersededDefaultsHint", "For each field listed, the value you sent was kept and a "
          + "callout had resolved a different one from this record's own context (the business "
          + "partner's configuration, for one). That is correct if the value was chosen "
          + "deliberately. If you copied it from neo_defaults, it was a generic default resolved "
          + "before this record had a business partner: omit that field and let the server resolve "
          + "it, or send the value under \"callout\" instead.");
    } catch (JSONException ignored) {
      // Same reasoning as reportUnknownFields: the record is written and correct as far as the
      // caller asked; losing a diagnostic must never turn a successful create into a failure.
    }
  }

  /**
   * Reports the server-owned fields this write discarded, when the value differed from the
   * session's own.
   *
   * <p>The write is not refused. {@code client} and {@code organization} are resolved from the
   * session whatever the caller sent, so the record is always created in the caller's own
   * tenant; what this adds is the caller being told, instead of finding out from a 404 on the
   * record it believes it just created somewhere else.</p>
   *
   * @param body the response body, modified in place
   * @param serverOwned the report built by {@link NeoServerOwnedFields}; empty means silence
   */
  static void reportServerOwnedFields(JSONObject body, JSONObject serverOwned) {
    if (body == null || serverOwned == null || serverOwned.length() == 0) {
      return;
    }
    try {
      body.put("serverOwnedFields", serverOwned);
      body.put("serverOwnedFieldsHint", "These fields are owned by the server and were resolved "
          + "from your session, not from the values you sent. They identify the tenant you are "
          + "working in and cannot be chosen per request. Read them back from the record; do not "
          + "send them.");
    } catch (JSONException ignored) {
      // Reporting is best-effort: a write that succeeded is never failed by its own report.
    }
  }

  /**
   * Validate that all mandatory columns have a value in the body before insert.
   * Returns a JSONArray of missing fields using the same structure as neo_schema
   * (name, column, type, hasSelector) so the model knows exactly what to provide.
   *
   * <p><b>ETP-5368 — a field the server resolves is not a field the caller omitted.</b> This walk
   * reads {@code col.isMandatory()} straight off AD, which describes the ROW, not the payload. On
   * {@code contacts/locationAddress} that made the create mode the SPA always uses impossible
   * through the MCP: {@code C_BPartner_Location.C_Location_ID} is NOT NULL, so a body carrying a
   * country, a street and a province was refused with "Missing required fields" naming
   * {@code locationAddress} — the very record {@code ContactsLocationAddressHandler} was about to
   * create from those fields. Skipping the names the wrapper policy declares server-resolved is
   * the same declaration {@code neo_schema} uses to demote them to {@code optional}, so the
   * catalogue and the write agree instead of contradicting each other.
   *
   * @param systemColumns system/audit columns excluded from schema (auto-managed by Etendo)
   * @param selectorRefs  AD_Reference IDs for OBUISEL selectors (extends the base FK refs from
   *                      NeoSelectorService)
   * @param sfEntity      the Schema Forge entity, consulted for handler-resolved fields; may be
   *                      {@code null}
   */
  static JSONArray validateMandatoryFields(JSONObject body, Tab adTab, Entity dalEntity,
      Set<String> systemColumns, Set<String> selectorRefs, SFEntity sfEntity, Logger log) {
    JSONArray missing = new JSONArray();
    if (dalEntity == null) {
      return missing;
    }
    Set<String> serverResolved = NeoSelectorPolicy.serverResolvedFieldNames(sfEntity);

    for (Column col : adTab.getTable().getADColumnList()) {
      Property prop = McpToolRouterSupport.resolveMandatoryProperty(adTab, dalEntity, col,
          systemColumns);
      if (prop != null && serverResolved.contains(prop.getName())) {
        continue;
      }
      if (prop != null && McpToolRouterSupport.isMandatoryValueMissing(body, prop.getName())) {
        try {
          missing.put(McpToolRouterSupport.buildMissingFieldInfo(col, prop.getName(),
              selectorRefs));
        } catch (Exception e) {
          log.warn("Error building missing field info for column {}: {}", col.getDBColumnName(), e.getMessage());
        }
      }
    }
    return missing;
  }

  /**
   * Coerce string values in the body to the proper JSON types expected by the DAL.
   * Callout cascade and session defaults return everything as strings, but
   * DefaultJsonDataService expects JSON numbers for Long/BigDecimal properties
   * and JSON booleans for Boolean properties.
   *
   * <p>Date-typed properties are not merely re-typed but <b>re-shaped</b> to the canonical
   * ISO wire format (ETP-4793 / IMP-16). That branch is not cosmetic: the DAL parses dates
   * leniently, so a {@code dd-MM-yyyy} value is silently reinterpreted rather than rejected
   * and {@code "06-08-2026"} persists as year 0012.
   *
   * @param callerKeys the keys the agent itself sent, as a snapshot taken before any server default
   *     was injected; {@code null} means every key in {@code body} is the caller's. Only those keys
   *     can produce a rejection — see
   *     {@code McpSupportInternals.coerceDateFieldValue} (ETP-4793 / IMP-24)
   * @return one descriptor per unusable date value, empty when the body is clean
   */
  static JSONArray coerceFieldTypes(JSONObject body, Entity dalEntity, JSONObject callerKeys,
      Logger log) {
    JSONArray invalid = new JSONArray();
    if (body == null || dalEntity == null) {
      return invalid;
    }
    List<String> keys = new ArrayList<>();
    Iterator<String> it = body.keys();
    while (it.hasNext()) {
      keys.add(it.next());
    }
    for (String key : keys) {
      Property prop = dalEntity.getProperty(key, false);
      if (prop == null || !prop.isPrimitive()) {
        continue;
      }
      boolean callerSupplied = callerKeys == null || callerKeys.has(key);
      JSONObject rejection = McpToolRouterSupport.coercePrimitiveFieldValue(body, key, prop,
          callerSupplied, log);
      if (rejection != null) {
        invalid.put(rejection);
      }
    }
    return invalid;
  }

  /**
   * The 422 for date values the agent must re-send (ETP-4793 / IMP-24).
   *
   * <p>Mirrors the shape of the {@code missingFields} error a few lines above rather than inventing
   * a second one: same envelope keys, same bare-object delivery, one list keyed by what is wrong
   * with it. Before this existed the same input produced the DAL's raw
   * {@code {"status":-4}} plus a {@code java.text.ParseException} that named no field at all, so the
   * agent could not tell which of the dates it sent was the problem — or that a date was the
   * problem.
   *
   * <p>{@code detail} branches on each item's {@code reason} (ETP-4793, the ambiguity gate):
   * "unreadable" and "ambiguous" are different failures — one is a format the parser cannot make
   * sense of at all, the other is a format the parser understands two different ways at once — and
   * conflating them back into one generic sentence would cost the agent the distinction the
   * per-item {@code reason}/{@code candidates} keys exist to give it.
   */
  static JSONObject buildInvalidDatesError(JSONArray invalidDates) throws JSONException {
    boolean hasUnreadable = false;
    boolean hasAmbiguous = false;
    for (int i = 0; i < invalidDates.length(); i++) {
      String reason = invalidDates.getJSONObject(i).optString("reason", "unreadable");
      if ("ambiguous".equals(reason)) {
        hasAmbiguous = true;
      } else {
        hasUnreadable = true;
      }
    }
    String detail;
    if (hasAmbiguous && hasUnreadable) {
      detail = "One or more date values are not in a format this API can read, and one or more "
          + "others are ambiguous — readable as two different calendar dates depending on which "
          + "day-first/month-first convention is assumed";
    } else if (hasAmbiguous) {
      detail = "One or more date values are ambiguous: each is readable as two different calendar "
          + "dates depending on which day-first/month-first convention is assumed, so this API "
          + "refuses to guess. See each item's 'candidates' for the two readings";
    } else {
      detail = "One or more date values are not in a format this API can read";
    }
    JSONObject errorObj = new JSONObject();
    errorObj.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    errorObj.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
    errorObj.put(McpConstants.KEY_DETAIL, detail);
    errorObj.put("invalidDates", invalidDates);
    errorObj.put("hint", "Send dates as ISO: yyyy-MM-dd for dates, yyyy-MM-dd'T'HH:mm:ss for "
        + "datetimes. Check the value is a real calendar date too — 2026-02-30 is ISO-shaped and "
        + "still invalid. For an ambiguous value, resend the exact ISO date you meant from "
        + "'candidates'.");
    errorObj.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return errorObj;
  }

  /**
   * Replace FK sentinel values ("0") in the body with real values.
   * The DAL's JsonToDataConverter tries to load entities by ID, and "0" is not a valid UUID.
   * In Etendo, "0" means "not yet determined" — the real value comes from a related field
   * (e.g. C_DocType_ID copies from C_DocTypeTarget_ID). For each sentinel, we find another
   * property in the body that targets the same entity and has a real value.
   */
  static void resolveFkSentinels(JSONObject body, Entity dalEntity, Logger log)
      throws JSONException {
    // First pass: collect all sentinels and all real FK values by target entity
    Map<String, String> sentinelProps = new HashMap<>(); // propName -> targetEntityName
    Map<String, String> realValues = new HashMap<>();    // targetEntityName -> value

    Iterator<String> keys = body.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Property prop = dalEntity.getProperty(key, false);
      if (prop == null || prop.isPrimitive() || prop.getTargetEntity() == null) {
        continue;
      }
      String targetEntity = prop.getTargetEntity().getName();
      String value = body.optString(key, "");
      if ("0".equals(value)) {
        sentinelProps.put(key, targetEntity);
      } else if (!value.isEmpty()) {
        realValues.put(targetEntity, value);
      }
    }

    // Second pass: replace sentinels with real values from same-target-entity fields
    for (Map.Entry<String, String> entry : sentinelProps.entrySet()) {
      String propName = entry.getKey();
      String targetEntity = entry.getValue();
      String realValue = realValues.get(targetEntity);
      if (realValue != null) {
        body.put(propName, realValue);
        log.debug("Resolved FK sentinel: {} = {} (from sibling targeting {})",
            propName, realValue, targetEntity);
      } else {
        // No sibling with real value — remove to avoid DAL error. The column must
        // either have a DB default or be nullable; if not, the INSERT will fail.
        body.remove(propName);
        log.warn("Removed FK sentinel '0' for {} — no sibling value found for {}",
            propName, targetEntity);
      }
    }
  }

  /**
   * Resolve parentId to the actual FK property name on child tabs.
   *
   * <p><b>ETP-5184:</b> the target property now comes from {@link McpParentScope}, which picks the
   * parent-link column whose target IS the parent tab's table (or the one declared in
   * {@code MCP_CONFIG}). The previous implementation walked {@code getADColumnList()} and took the
   * first {@code isLinkToParentColumn()} it found, without checking where that column pointed —
   * and on the 17 entities whose first such column is not the parent link, it wrote the parent id
   * into the wrong foreign key. {@code product/stock} is the clearest case: its SEQNO parent is
   * {@code M_Product} but its only parent-link column is {@code M_RefInventory_ID}, so a create
   * passing a product id stored it as "referenced inventory". It did not fail — it stored wrong
   * data, which is why it went unnoticed. Both properties exist on the same entity
   * ({@code product} and {@code referencedInventory}), so the write landed in the neighbouring
   * field.</p>
   *
   * <p>A scope that cannot identify the parent writes nothing, exactly as before: the caller's
   * gate is what refuses such an entity, and silently guessing a column here is what caused the
   * defect in the first place.</p>
   *
   * @param adTab         the child tab
   * @param body          the write payload, mutated in place
   * @param parentIdValue the parent record id
   * @param log           caller's logger
   * @param sfEntity      the SchemaForge entity, needed to read its {@code MCP_CONFIG}
   * @throws JSONException if the payload cannot be written to
   */
  static void resolveParentFK(Tab adTab, JSONObject body, String parentIdValue, Logger log,
      SFEntity sfEntity) throws JSONException {
    if (adTab.getTabLevel() == null || adTab.getTabLevel() <= 0) {
      return;
    }
    McpParentScope.Scope scope = McpParentScope.forEntity(sfEntity);
    if (scope.getParentField() == null) {
      log.warn("No parent field resolved for tab '{}' — parentId not applied ({})",
          adTab.getName(), scope.getProblem());
      return;
    }
    body.put(scope.getParentField(), parentIdValue);
  }

  /**
   * Check if a DefaultJsonDataService response contains an error.
   *
   * <p>Returns the IMP-5 envelope describing it, or {@code null} when the response is not a failure.
   * Before ETP-4793 / IMP-17 this returned a bare {@code String} — core's own prose — which is how a
   * callout rejection reached agents with no status and no code (evidence B13).</p>
   *
   * <p>Equivalent to calling the 3-arg overload with {@code callerProvidedFields = null}: every
   * {@code fieldErrors} key is then described the old, caller-agnostic way. Kept for the read path
   * and for {@code neo_delete}, neither of which tracks a pre-defaults snapshot of caller fields.
   *
   * @param responseJson the raw DAL response
   * @param seeAlso      the {@code docs} recipe for the calling verb; also tells the failure builder
   *                     whether the caller submitted values, which decides 422 vs 500
   * @return the error envelope, or {@code null} if the response reports no failure
   * @throws JSONException if the envelope cannot be built
   */
  static JSONObject checkJsonServiceError(JSONObject responseJson, String seeAlso)
      throws JSONException {
    return checkJsonServiceError(responseJson, seeAlso, null);
  }

  /**
   * Same as {@link #checkJsonServiceError(JSONObject, String)}, but able to tell a caller-sent
   * {@code fieldErrors} field apart from one the server itself filled in (a mandatory default,
   * a callout, FK-by-name resolution, ...) before the write was attempted. See
   * {@link #buildDalValidationEnvelope(JSONObject, String, Set)} for why that distinction matters.
   *
   * @param callerProvidedFields the field names present in the caller's own request body, taken
   *                             BEFORE any server-side default/callout injection ran — typically
   *                             {@code NeoCrudHelper.snapshotBodyFields} of that pre-injection
   *                             snapshot. {@code null} when the call site does not track one
   *                             (reads, deletes), in which case every {@code fieldErrors} key
   *                             falls back to the old, caller-agnostic wording.
   */
  static JSONObject checkJsonServiceError(JSONObject responseJson, String seeAlso,
      Set<String> callerProvidedFields) throws JSONException {
    JSONObject innerResponse = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
    if (innerResponse == null) {
      return null;
    }

    int status = innerResponse.optInt(JsonConstants.RESPONSE_STATUS, 0);
    if (status == JsonConstants.RPCREQUEST_STATUS_FAILURE) {
      String message = innerResponse.has(JsonConstants.RESPONSE_ERROR)
          ? innerResponse.getJSONObject(JsonConstants.RESPONSE_ERROR)
              .optString(McpConstants.KEY_MESSAGE, "Operation failed")
          : "Operation failed";
      return buildDalFailureEnvelope(message, seeAlso);
    }
    if (status == JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR) {
      return buildDalValidationEnvelope(innerResponse, seeAlso, callerProvidedFields);
    }
    return null;
  }

  /**
   * The IMP-5 envelope for a DAL/callout rejection (ETP-4793 / IMP-17).
   *
   * <p>This is where evidence B13 escaped. A callout refusing a create returned its message as the
   * whole response body — <i>"La fecha de operación no puede ser posterior a la fecha de la
   * factura."</i> — with no {@code status}, no error code and no {@code field}, while the write verbs
   * around it had carried a structured envelope since IMP-5. An agent could not tell that failure
   * apart from a server fault except by reading Spanish prose.</p>
   *
   * <p>Two things this deliberately does not do. It does not translate: the message comes from
   * {@code AD_Message} in the session user's language, so producing English would mean pinning the
   * MCP session's locale — a separate change with its own blast radius (it would move process
   * messages too), and not what IMP-17 registered. And it does not invent a {@code field}: a callout
   * rejects a <em>combination</em> of values far more often than a single one, and a guessed field
   * would point the agent at the wrong input, which is worse than no pointer at all.</p>
   *
   * <p>The status follows the failure, not the verb, with one exception: {@code seeAlso} tells us
   * whether the caller submitted values at all. On a write, {@code status:-1} from core is a
   * rejection of what was sent, so it is a 422 the agent can act on. On a read there is nothing to
   * correct — the one actionable read failure, an unknown named filter, is answered upstream by
   * IMP-3 — so inviting a retry-with-corrections would be a loop with no exit.</p>
   */
  private static JSONObject buildDalFailureEnvelope(String rawMessage, String seeAlso)
      throws JSONException {
    String detail = NeoErrorSanitizer.stripRowDump(
        NeoErrorSanitizer.redactObjectReferences(NeoListReferenceError.enrich(rawMessage)));
    boolean write = McpConstants.SEE_ALSO_WRITING.equals(seeAlso);
    JSONObject envelope = new JSONObject();
    // ETP-5073 / DOC-04: core's optimistic-locking refusal, classified first because its remedy is
    // the opposite of every branch below (re-read, do not correct-and-retry).
    //
    // `detail` arrives as prose, not as the AD code: DefaultJsonDataService funnels the exception
    // through JsonUtils.convertExceptionToJson, which translates it before building the body. The
    // match therefore goes through NeoErrorSanitizer, which resolves the code against AD_Message
    // for the current language rather than comparing hardcoded text.
    //
    // The detail is still replaced rather than passed through, for a reason that is about the
    // consumer and not about the code being opaque: this envelope is read by an agent, and a
    // sentence whose wording depends on the session language is harder to act on than a stable one
    // whose remedy lives in `hint`. The class stance above still holds — we substitute a
    // description, we do not localize a message.
    if (NeoErrorSanitizer.isStaleRecordMessage(detail)) {
      return buildStaleRecordError();
    }
    if (NeoErrorSanitizer.isDuplicateKeyMessage(detail)) {
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_CONFLICT);
      envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_CONFLICT);
      envelope.put(McpConstants.KEY_HINT, "A record with this business key already exists. Find it "
          + "with neo_list and update it, or send a different key.");
    } else if (write) {
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
      envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
      envelope.put(McpConstants.KEY_HINT, "A business rule rejected the values sent. Read 'detail', "
          + "correct the values it names and retry — the record was not written.");
    } else {
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_SERVER_ERROR);
      envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_SERVER);
      envelope.put(McpConstants.KEY_HINT, "The query itself failed; re-sending it unchanged will "
          + "fail the same way.");
    }
    envelope.put(McpConstants.KEY_DETAIL, detail);
    envelope.put(McpConstants.KEY_SEE_ALSO, seeAlso);
    return envelope;
  }

  /**
   * The IMP-5 envelope for core's per-field validation failure (ETP-4793 / IMP-17).
   *
   * <p>Replaces {@code "Validation error: " + innerResponse.toString()}, which shipped the raw DAL
   * transport object — {@code status:-4} and all — into the agent's context. The per-field map is the
   * only part that was ever actionable, so it is lifted into {@code fieldErrors} and the transport is
   * dropped.</p>
   *
   * @param callerProvidedFields see {@link #checkJsonServiceError(JSONObject, String, Set)}
   */
  private static JSONObject buildDalValidationEnvelope(JSONObject innerResponse, String seeAlso,
      Set<String> callerProvidedFields) throws JSONException {
    JSONObject envelope = new JSONObject();
    envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_VALIDATION);
    JSONObject rawErrors = innerResponse.optJSONObject("errors");
    JSONObject fieldErrors = new JSONObject();
    if (rawErrors != null) {
      Iterator<String> keys = rawErrors.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        fieldErrors.put(key, NeoErrorSanitizer.stripRowDump(
            NeoErrorSanitizer.redactObjectReferences(
                NeoListReferenceError.enrich(rawErrors.optString(key, "")))));
      }
    }
    if (fieldErrors.length() > 0) {
      envelope.put(McpConstants.KEY_DETAIL, "One or more values were rejected by field validation");
      envelope.put("fieldErrors", fieldErrors);
      envelope.put(McpConstants.KEY_HINT, buildFieldErrorsHint(fieldErrors, callerProvidedFields));
    } else {
      envelope.put(McpConstants.KEY_DETAIL, "Field validation rejected the request, and named no "
          + "field");
      envelope.put(McpConstants.KEY_HINT, "Call neo_schema with view:\"create\" for this entity "
          + "to check the type and allowed values of every field sent.");
    }
    envelope.put(McpConstants.KEY_SEE_ALSO, seeAlso);
    return envelope;
  }

  /**
   * The {@code hint} for a per-field DAL validation failure, honest about who put the rejected
   * value there.
   *
   * <p>Before this every {@code fieldErrors} key was described as "a field you sent" —
   * unconditionally, even for a field the caller never mentioned. That happens whenever a
   * mandatory default injected server-side (from {@code AD_Column.DefaultValue}) is itself
   * invalid — e.g. a quoted SQL literal NEO forgot to unwrap — and the DAL rejects the very
   * value it just manufactured. An agent creating a Business Partner with only {@code searchKey}
   * and {@code name} hit exactly this: {@code oBTIKTaxIDKey} came back in {@code fieldErrors}
   * with a hint telling it to "correct" a field it had never touched, and named that the single
   * most confusing part of the whole exchange — there is nothing in the caller's own request to
   * fix.
   *
   * <p>{@code callerProvidedFields} is what makes the distinction possible: it is a snapshot of
   * the request body taken before any server-side default/callout ran, so a {@code fieldErrors}
   * key absent from it can only have been filled in afterwards, by the server itself.
   *
   * @param fieldErrors           the per-field messages already built for the response
   * @param callerProvidedFields  field names present in the caller's own request, pre-injection;
   *                              {@code null} when the call site does not track one, in which
   *                              case every key falls back to the original, caller-agnostic
   *                              wording rather than risk a false "not yours" claim
   */
  private static String buildFieldErrorsHint(JSONObject fieldErrors,
      Set<String> callerProvidedFields) {
    String caseSentIt = "Each key in 'fieldErrors' is a field you sent; correct the value it "
        + "describes and retry.";
    if (callerProvidedFields == null) {
      return caseSentIt;
    }
    List<String> callerFields = new ArrayList<>();
    List<String> serverFields = new ArrayList<>();
    Iterator<String> keys = fieldErrors.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      (callerProvidedFields.contains(key) ? callerFields : serverFields).add(key);
    }
    if (serverFields.isEmpty()) {
      return caseSentIt;
    }
    String serverList = String.join(", ", serverFields);
    if (callerFields.isEmpty()) {
      return "None of these fields were in your request: " + serverList + " — the server filled "
          + "them in from an AD default, and that default value itself failed validation. This "
          + "is a configuration problem, not something wrong with your request. Work around it "
          + "by sending an explicit, valid value for " + serverList + " yourself.";
    }
    return "You sent " + String.join(", ", callerFields) + "; correct the value(s) it/they "
        + "describe(s) and retry. " + serverList + " came from the server's own default, not "
        + "from your request, and that default value itself failed validation — a configuration "
        + "problem you can work around by sending an explicit, valid value for " + serverList
        + " too.";
  }

  /**
   * ETP-5073 / DOC-04: the 409 envelope for a concurrent-modification conflict.
   *
   * <p>Public to the package because two callers need the identical body: {@code handleUpdate},
   * which now detects the conflict itself before writing (see {@code NeoRecordVersion} for why
   * reading core's refusal proved unworkable), and {@link #buildDalFailureEnvelope}, which keeps
   * the message-based branch as a backstop for the stateless path where the untranslated code
   * does survive.
   *
   * <p>The detail is written here rather than forwarded from core, and that is about the consumer:
   * this envelope is read by an agent, and a sentence whose wording depends on the session
   * language is harder to act on than a stable one whose remedy lives in {@code hint}. It is not a
   * translation — a description is substituted, a message is not localized.
   */
  static JSONObject buildStaleRecordError() throws JSONException {
    JSONObject envelope = new JSONObject();
    envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_CONFLICT);
    envelope.put(McpConstants.KEY_ERROR, McpConstants.ERROR_STALE_RECORD);
    envelope.put(McpConstants.KEY_DETAIL, "This record was modified by someone else after the '"
        + McpConstants.PARAM_UPDATED + "' value you sent was read. The write was refused so their "
        + "change is not lost; nothing was written.");
    envelope.put(McpConstants.KEY_HINT, "Re-read the record with neo_get, reapply your changes on "
        + "top of the values it returns, and retry with the fresh '"
        + McpConstants.PARAM_UPDATED + "'. Retrying the same payload unchanged will fail "
        + "identically.");
    envelope.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return envelope;
  }
}
