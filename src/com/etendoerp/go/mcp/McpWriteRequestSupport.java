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

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;

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
      throw new IllegalArgumentException("No AD_Tab linked to entity: " + entityName);
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
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableId(adTab.getTable().getId());
    JSONObject mapped = new JSONObject();

    Iterator<String> keys = fields.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = fields.get(key);
      String mappedKey = key;

      // Try as DAL property name first
      Property prop = dalEntity.getProperty(key, false);
      if (prop != null) {
        mappedKey = key;
      } else {
        // Try as DB column name
        prop = dalEntity.getPropertyByColumnName(key, false);
        if (prop != null) {
          mappedKey = prop.getName();
        }
      }

      // Pass through unknown keys (parentId, etc.)
      mapped.put(mappedKey, value);
    }
    return mapped;
  }

  /**
   * Validate that all mandatory columns have a value in the body before insert.
   * Returns a JSONArray of missing fields using the same structure as neo_schema
   * (name, column, type, hasSelector) so the model knows exactly what to provide.
   *
   * @param systemColumns system/audit columns excluded from schema (auto-managed by Etendo)
   * @param selectorRefs  AD_Reference IDs for OBUISEL selectors (extends the base FK refs from
   *                      NeoSelectorService)
   */
  static JSONArray validateMandatoryFields(JSONObject body, Tab adTab, Entity dalEntity,
      Set<String> systemColumns, Set<String> selectorRefs, Logger log) {
    JSONArray missing = new JSONArray();
    if (dalEntity == null) {
      return missing;
    }

    for (Column col : adTab.getTable().getADColumnList()) {
      Property prop = McpToolRouterSupport.resolveMandatoryProperty(adTab, dalEntity, col,
          systemColumns);
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
   * Replicates the same logic from NeoServlet's POST handler.
   */
  static void resolveParentFK(Tab adTab, JSONObject body, String parentIdValue, Logger log)
      throws JSONException {
    if (adTab.getTabLevel() == null || adTab.getTabLevel() <= 0) {
      return;
    }

    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return;
    }

    for (Column col : adTab.getTable().getADColumnList()) {
      if (col.isLinkToParentColumn() && col.isActive()) {
        try {
          Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName());
          if (prop != null) {
            body.put(prop.getName(), parentIdValue);
            break;
          }
        } catch (Exception e) {
          log.warn("Column '{}' not mappable to property in entity '{}': {}", col.getDBColumnName(), dalEntity.getName(), e.getMessage());
        }
      }
    }
  }

  /**
   * Check if a DefaultJsonDataService response contains an error.
   *
   * <p>Returns the IMP-5 envelope describing it, or {@code null} when the response is not a failure.
   * Before ETP-4793 / IMP-17 this returned a bare {@code String} — core's own prose — which is how a
   * callout rejection reached agents with no status and no code (evidence B13).</p>
   *
   * @param responseJson the raw DAL response
   * @param seeAlso      the {@code docs} recipe for the calling verb; also tells the failure builder
   *                     whether the caller submitted values, which decides 422 vs 500
   * @return the error envelope, or {@code null} if the response reports no failure
   * @throws JSONException if the envelope cannot be built
   */
  static JSONObject checkJsonServiceError(JSONObject responseJson, String seeAlso)
      throws JSONException {
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
      return buildDalValidationEnvelope(innerResponse, seeAlso);
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
        NeoErrorSanitizer.redactObjectReferences(rawMessage));
    boolean write = McpConstants.SEE_ALSO_WRITING.equals(seeAlso);
    JSONObject envelope = new JSONObject();
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
   */
  private static JSONObject buildDalValidationEnvelope(JSONObject innerResponse, String seeAlso)
      throws JSONException {
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
            NeoErrorSanitizer.redactObjectReferences(rawErrors.optString(key, ""))));
      }
    }
    if (fieldErrors.length() > 0) {
      envelope.put(McpConstants.KEY_DETAIL, "One or more values were rejected by field validation");
      envelope.put("fieldErrors", fieldErrors);
      envelope.put(McpConstants.KEY_HINT, "Each key in 'fieldErrors' is a field you sent; correct "
          + "the value it describes and retry.");
    } else {
      envelope.put(McpConstants.KEY_DETAIL, "Field validation rejected the request, and named no "
          + "field");
      envelope.put(McpConstants.KEY_HINT, "Call neo_schema for this entity to check the type and "
          + "allowed values of every field sent.");
    }
    envelope.put(McpConstants.KEY_SEE_ALSO, seeAlso);
    return envelope;
  }
}
