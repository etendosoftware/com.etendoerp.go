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

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Generic, metadata-driven validator that finds process preconditions declared on
 * {@code ETGO_SF_ENTITY.preconditions} that are NOT met by the target record before
 * a process runs. Pure static logic — no CDI, no DB access of its own, and
 * <strong>zero window/process-specific branches</strong>. The first consumer
 * (assets / "Create Amortization", AD_Process 800125) is expressed entirely as
 * data in the {@code preconditions} column.
 *
 * <p>Stored JSON shape (value of {@code ETGO_SF_ENTITY.preconditions}), keyed by
 * AD_Process_ID:</p>
 * <pre>
 * {
 *   "800125": [
 *     { "field": "usableLifeMonths", "requiredWhen": "@calculateType@ != 'PE' &amp;&amp; @amortize@ != 'YE'" },
 *     { "field": "usableLifeYears",  "requiredWhen": "@amortize@ == 'YE'" },
 *     { "field": "currency" }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code field} — NEO field identifier (camelCase DAL property) the record is checked against.</li>
 *   <li>{@code requiredWhen} — optional condition (see {@link PreconditionConditionEvaluator});
 *       when absent the precondition is unconditional; when present and false, the rule is skipped.</li>
 *   <li>{@code message} — optional human message (currently informational; not surfaced by the validator).</li>
 * </ul>
 */
public final class NeoProcessPreconditionValidator {

  private static final Logger log = LogManager.getLogger(NeoProcessPreconditionValidator.class);

  /** DAL property that holds the preconditions JSON on {@link SFEntity}. */
  static final String PRECONDITIONS_PROPERTY = "preconditions";

  private static final String FIELD = "field";
  private static final String REQUIRED_WHEN = "requiredWhen";

  private NeoProcessPreconditionValidator() {
  }

  /**
   * Returns the NEO field names whose precondition is unmet for {@code process} on
   * {@code record}. Returns an empty list when there are no declarations for the
   * process, when the entity has no preconditions, or when all preconditions hold.
   *
   * @param process the process about to be executed
   * @param entity  the {@link SFEntity} whose {@code preconditions} declaration governs the check
   * @param record  the target record the process operates on
   * @param params  the request params (checked before the record for a submitted value)
   * @return unmet NEO field (DAL property) names; never {@code null}
   */
  public static List<String> findUnmetPreconditions(Process process, SFEntity entity,
      BaseOBObject record, JSONObject params) {
    List<String> missing = new ArrayList<>();
    if (process == null || entity == null) {
      return missing;
    }
    String raw = readPreconditions(entity);
    if (raw == null || raw.trim().isEmpty()) {
      return missing;
    }
    try {
      JSONObject byProcess = new JSONObject(raw);
      String processId = process.getId();
      if (processId == null || !byProcess.has(processId)) {
        return missing;
      }
      JSONArray rules = byProcess.getJSONArray(processId);
      collectMissing(processId, rules, record, params, missing);
    } catch (JSONException e) {
      log.warn("Invalid preconditions JSON on entity {}: {}", safeEntityId(entity), e.getMessage());
    }
    return missing;
  }

  private static void collectMissing(String processId, JSONArray rules, BaseOBObject record,
      JSONObject params, List<String> missing) throws JSONException {
    for (int i = 0; i < rules.length(); i++) {
      JSONObject rule = rules.optJSONObject(i);
      if (rule == null) {
        continue;
      }
      String field = rule.optString(FIELD, null);
      if (field == null || field.trim().isEmpty()) {
        continue;
      }
      String requiredWhen = rule.optString(REQUIRED_WHEN, null);
      if (requiredWhen != null && !requiredWhen.trim().isEmpty()
          && !PreconditionConditionEvaluator.evaluate(requiredWhen,
              prop -> resolveValue(prop, record, params))) {
        // Condition present and false → precondition does not apply.
        continue;
      }
      if (record != null && !isKnownProperty(record, field)) {
        // Config error (typo / unknown property), not an unmet precondition.
        // Fail open: never block a process on a misconfigured rule.
        log.warn("Skipping precondition for process {}: field '{}' is not a known property of "
            + "entity {}", processId, field, safeRecordEntityName(record));
        continue;
      }
      if (isFieldEmpty(field, record, params)) {
        missing.add(field);
      }
    }
  }

  /**
   * True when {@code field} is a declared DAL property of the record's entity. A rule that
   * names a property the entity does not have is a configuration error rather than an unmet
   * precondition, so callers skip it (fail-open). When the model cannot be inspected this
   * returns {@code true} so the downstream value check still runs and genuinely-empty
   * known fields stay reported.
   */
  private static boolean isKnownProperty(BaseOBObject record, String field) {
    try {
      return record.getEntity().hasProperty(field);
    } catch (Exception e) {
      return true;
    }
  }

  private static String safeRecordEntityName(BaseOBObject record) {
    try {
      return record.getEntity().getName();
    } catch (Exception e) {
      return "<unknown>";
    }
  }

  /**
   * Reads the {@code preconditions} JSON from the entity via the generic DAL accessor.
   * Uses {@link BaseOBObject#get(String)} so this compiles independently of the
   * generated {@code SFEntity} getter and gracefully no-ops on environments where the
   * column has not yet been applied to the runtime model.
   */
  private static String readPreconditions(SFEntity entity) {
    try {
      Object value = entity.get(PRECONDITIONS_PROPERTY);
      return value == null ? null : value.toString();
    } catch (Exception e) {
      // Property not present in the runtime model yet (pre-migration) → treat as no declarations.
      log.debug("Could not read preconditions property: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Resolves a {@code @prop@} reference used in a {@code requiredWhen} condition.
   * Prefers a non-null submitted value in {@code params}, else the record property.
   */
  private static String resolveValue(String prop, BaseOBObject record, JSONObject params) {
    if (params != null && params.has(prop) && !params.isNull(prop)) {
      return params.optString(prop, null);
    }
    if (record == null) {
      return null;
    }
    try {
      return valueToString(record.get(prop));
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * A field is considered present when EITHER the request params or the record hold a
   * non-empty value for it.
   */
  private static boolean isFieldEmpty(String field, BaseOBObject record, JSONObject params) {
    if (paramHasValue(params, field)) {
      return false;
    }
    return !recordHasValue(record, field);
  }

  private static boolean paramHasValue(JSONObject params, String field) {
    if (params == null || !params.has(field) || params.isNull(field)) {
      return false;
    }
    Object value = params.opt(field);
    return !(value instanceof String) || !((String) value).trim().isEmpty();
  }

  private static boolean recordHasValue(BaseOBObject record, String field) {
    if (record == null) {
      return false;
    }
    Object value;
    try {
      value = record.get(field);
    } catch (Exception e) {
      return false;
    }
    if (value == null) {
      return false;
    }
    if (value instanceof String) {
      return !((String) value).trim().isEmpty();
    }
    return true;
  }

  private static String valueToString(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BaseOBObject) {
      return (String) ((BaseOBObject) value).getId();
    }
    return value.toString();
  }

  private static String safeEntityId(SFEntity entity) {
    try {
      return entity.getId();
    } catch (Exception e) {
      return "<unknown>";
    }
  }
}
