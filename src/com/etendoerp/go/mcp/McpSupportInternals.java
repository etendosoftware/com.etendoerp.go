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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;

import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.MissingRequiredFieldsException;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;

/**
 * Private implementation details of {@link McpToolRouterSupport}, extracted so that class stays
 * under SonarQube's 35-method-per-class limit (ETP-4793: it had grown to 46).
 *
 * <p><b>Why this seam and not a topical one.</b> Every method here was verified, by a repo-wide
 * search, to have zero call sites outside {@code McpToolRouterSupport.java} — none of the MCP
 * tool handlers ({@code McpToolRouter}, {@code McpWidgetHandler}, {@code McpResourceProvider},
 * {@code McpFkResolver}, {@code McpHookExecutor}, {@code McpWriteRequestSupport}) or their tests
 * call any of them directly, and neither does anything outside the {@code mcp} package. That is
 * also the constraint the split had to respect: several of {@code McpToolRouterSupport}'s other
 * methods (e.g. {@code mapNeoResponseToActionResult}, {@code toMcpBatchFailure},
 * {@code coercePrimitiveFieldValue}, {@code errorCodeForStatus} as directly unit-tested, …) are
 * called from — or {@code mockStatic}'d against — files owned by other concurrent changes, so
 * moving them would have broken call sites this change must not touch. What is left is a
 * legitimate cohesion criterion on its own: this class is {@code McpToolRouterSupport}'s private
 * implementation surface, callable only from the class it still serves.
 *
 * <p>Three clusters ended up here, each still reachable only through a method that remains on
 * {@code McpToolRouterSupport}:
 * <ul>
 *   <li>spec/entity discovery shaping — backs {@code findIncludedEntity}'s error message,
 *       {@code buildEntitySummaryArray}'s per-entity JSON, and {@code isCatalogExcludedSpec}'s
 *       handler-only-spec test;</li>
 *   <li>the date-write rejection descriptors (IMP-16 / IMP-24) — backs
 *       {@code coercePrimitiveFieldValue}'s date branch;</li>
 *   <li>batch/DAL error-message extraction (IMP-15 / IMP-17) — backs
 *       {@code toMcpBatchFailure}.</li>
 * </ul>
 *
 * <p>Package-private visibility throughout: every caller lives in {@code McpToolRouterSupport},
 * in the same package.
 */
final class McpSupportInternals {

  private static final Logger log = LogManager.getLogger(McpSupportInternals.class);

  private McpSupportInternals() {
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
  static String resolveSpecNameForError(String specId) {
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
    for (SFEntity entity : McpToolRouterSupport.listIncludedEntities(specId)) {
      names.add(entity.getName());
    }
    return names;
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
    item.put("methods", McpToolRouterSupport.buildMethodsArray(entity));
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
   * {@code McpToolRouterSupport#requireMethodEnabled}, so this predicate only shapes the
   * catalog and must never be the thing that silently hides a working window.</p>
   *
   * <p><b>Being handler-only is NOT on its own a reason to hide a spec</b> — see
   * {@code McpToolRouterSupport#isCatalogExcludedSpec}, which is what the callers actually use.</p>
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
   * Load a spec's included entities without letting an infrastructure failure break the
   * caller. Both catalog predicates above are advisory (see their javadoc), so a lookup
   * failure degrades to "no evidence" — an empty list — rather than hiding a spec.
   */
  static List<SFEntity> safeListIncludedEntities(SFSpec spec) {
    try {
      List<SFEntity> entities = McpToolRouterSupport.listIncludedEntities(spec.getId());
      return entities != null ? entities : Collections.emptyList();
    } catch (Exception e) {
      log.warn("Could not list included entities for spec '{}': {}", spec.getName(),
          e.getMessage());
      return Collections.emptyList();
    }
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
   * <p><b>Phase 2.1 (IMP-24, the ambiguity gate).</b> A value {@code toCanonical} repairs is not
   * automatically safe: the UI pattern reads {@code "03-04-2026"} as 3 April, but {@code
   * "04-03-2026"} would read as 4 March under the same pattern, and 4 March is what an agent
   * meaning {@code MM-dd-yyyy} would have written as {@code "03-04-2026"} instead. Repairing that
   * value picks one of two equally valid calendar dates without ever asking, which is the same
   * silent reinterpretation this item exists to stop — the value just happens to already be
   * repairable, so phase 2 alone let it through. {@link NeoDateFormat#isAmbiguousUiDate} tells
   * repairable-but-ambiguous apart from repairable-and-safe (e.g. {@code "20-09-2026"}, where no
   * month 20 exists so {@code dd-MM-yyyy} is the only possible reading); this rejects the former
   * and leaves the latter as a silent repair. As with the phase-2 gate above, only a
   * caller-supplied value may be rejected here — a server-injected default that happens to be
   * ambiguous (e.g. today's date on days 1–12) is still repaired, never blamed on the agent.
   *
   * @return the rejection descriptor, or {@code null} when nothing is wrong with the value
   */
  static JSONObject coerceDateFieldValue(JSONObject body, String key, Property prop,
      String strVal, boolean callerSupplied, org.apache.logging.log4j.Logger log)
      throws JSONException {
    Optional<Boolean> shape = NeoDateFormat.canonicalShapeFor(prop);
    if (shape.isEmpty()) {
      return null;
    }
    String canonical = NeoDateFormat.toCanonical(strVal, shape.get().booleanValue());
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
      return buildInvalidDateInfo(key, strVal, shape.get().booleanValue());
    }
    if (callerSupplied && NeoDateFormat.isAmbiguousUiDate(strVal)) {
      log.info("[MCP] Ambiguous date for '{}': '{}' rejected — both day-first and month-first "
          + "readings are valid calendar dates", key, strVal);
      return buildAmbiguousDateInfo(key, strVal);
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
    info.put("reason", "unreadable");
    return info;
  }

  /**
   * Describe one ambiguous date value for the 422 body (ETP-4793 / IMP-24, the ambiguity gate).
   *
   * <p>Reuses {@code invalidDates}' four base keys — {@code name}/{@code received}/
   * {@code expectedFormat}/{@code example} — so an agent that already handles that envelope needs
   * no new parsing logic for this case; {@code reason} and {@code candidates} are additive. Unlike
   * {@link #buildInvalidDateInfo}, this value is not unreadable — it is undecidable between two
   * readings, both listed in {@code candidates} so the agent can resend the one it actually meant
   * instead of guessing at a third shape.
   */
  static JSONObject buildAmbiguousDateInfo(String key, String received) throws JSONException {
    JSONObject info = new JSONObject();
    info.put("name", key);
    info.put("received", received);
    info.put("expectedFormat", NeoDateFormat.ISO_DATE);
    info.put("example", "2026-08-10");
    info.put("reason", "ambiguous");
    String[] readings = NeoDateFormat.ambiguousReadings(received);
    if (readings.length > 0) {
      info.put("candidates", new JSONArray(Arrays.asList(readings)));
    }
    return info;
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
  static JSONArray extractMissingFields(JSONObject detail) {
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
  static JSONObject buildBatchMissingFieldsError(JSONArray missingFields)
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

  /**
   * Map a batch failure's HTTP status onto a stable machine-detectable code (IMP-15).
   *
   * <p>Any other status in the 4xx range is treated as something the calling agent can fix by
   * changing the request; a 5xx status, and likewise the sentinel batch index of {@code -1} used
   * for an unexpected batch-wide failure, are not agent-fixable.
   */
  static String errorCodeForStatus(int status) {
    if (status == McpConstants.STATUS_NOT_FOUND) {
      return McpConstants.ERROR_NOT_FOUND;
    }
    if (status == 405) {
      return McpConstants.ERROR_METHOD_NOT_ALLOWED;
    }
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
    String fieldViolations = joinFieldViolations(response.optJSONObject("errors"));
    if (fieldViolations != null) {
      return fieldViolations;
    }
    String message = response.optString(McpConstants.KEY_MESSAGE, null);
    return message == null || message.isBlank() ? null : message;
  }

  /**
   * Join a DAL per-field violations map into a single {@code "key: value"} sentence, {@code "; "}
   * separated, in {@code errors.keys()} iteration order — the field names are the most actionable
   * part of the payload.
   *
   * @param errors the {@code errors} map from a DAL sub-response, or {@code null}
   * @return the joined sentence, or {@code null} when there is no map or it yields nothing
   */
  private static String joinFieldViolations(JSONObject errors) {
    if (errors == null) {
      return null;
    }
    StringBuilder joined = new StringBuilder();
    java.util.Iterator<String> keys = errors.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (joined.length() > 0) {
        joined.append("; ");
      }
      joined.append(key).append(": ").append(errors.optString(key, ""));
    }
    return joined.length() > 0 ? joined.toString() : null;
  }
}
