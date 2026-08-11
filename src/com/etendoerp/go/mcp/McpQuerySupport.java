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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * neo_list query-shaping helpers extracted from {@link McpToolRouterSupport} (ETP-4254): builds the
 * HQL where clause from MCP filter key-value pairs and applies the optional IMP-2 field projection
 * (summary / explicit field whitelist) to a response. Kept as a focused, DAL-aware companion so
 * {@link McpToolRouterSupport} stays within the class method-count limit.
 */
final class McpQuerySupport {

  private McpQuerySupport() {
    // utility class — no instances
  }

  /**
   * Build an HQL where clause fragment from MCP filter key-value pairs (IMP-3).
   *
   * <p>Three filter shapes are honored, all backward compatible:
   * <ul>
   *   <li><b>Scalar</b> {@code {column: value}} — exact match (the historical behavior), now
   *       rendered type-aware so numbers/booleans/dates are not blindly quoted.</li>
   *   <li><b>Range</b> {@code {column: {gt|gte|lt|lte: value}}} or {@code {column: {between: [a,b]}}}
   *       — comparison operators via {@link McpBusinessFilters}.</li>
   *   <li><b>Named status</b> {@code {status: "<name>"}} — resolved against the entity's hand-authored
   *       {@code NAMED_FILTERS} (see {@link McpNamedFilters}); an unknown name raises a 422 envelope
   *       listing the valid ones in {@code available}, while an entity that declares no named filters
   *       falls back to treating {@code status} as a plain column.</li>
   * </ul>
   */
  static String buildWhereFromFilters(JSONObject filters, Tab adTab, SFEntity sfEntity,
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
      Object value = filters.get(key);
      if (McpBusinessFilters.STATUS_KEY.equalsIgnoreCase(key) && value instanceof String
          && appendStatusCondition(where, sfEntity, (String) value, log)) {
        continue;
      }
      if (value instanceof JSONObject) {
        appendOperatorConditions(where, dalEntity, key, (JSONObject) value, log);
      } else {
        appendEqualityCondition(where, dalEntity, key, value, log);
      }
    }
    return where.length() > 0 ? where.toString() : null;
  }

  /** Resolve a filter key to a DAL property, tolerating both column names and property names. */
  private static Property resolveFilterProperty(Entity dalEntity, String key,
      org.apache.logging.log4j.Logger log) {
    Property byColumn = dalEntity.getPropertyByColumnName(key, false);
    if (byColumn != null) {
      return byColumn;
    }
    try {
      return dalEntity.getProperty(key);
    } catch (Exception ignored) {
      log.debug("Filter column '{}' not found in entity, skipping", key);
      return null;
    }
  }

  /** Append {@code e.prop = value} (or {@code e.prop.id = 'value'} for a FK), type-aware. */
  private static void appendEqualityCondition(StringBuilder where, Entity dalEntity,
      String key, Object value, org.apache.logging.log4j.Logger log) {
    Property prop = resolveFilterProperty(dalEntity, key, log);
    if (prop == null) {
      log.warn("Filter key '{}' could not be resolved to a DAL property, ignoring", key);
      return;
    }
    appendAnd(where);
    if (!prop.isPrimitive()) {
      where.append("e.").append(prop.getName()).append(".id=")
          .append(McpBusinessFilters.formatHqlValue(String.class, false, value));
    } else {
      where.append("e.").append(prop.getName()).append('=').append(
          McpBusinessFilters.formatHqlValue(prop.getPrimitiveObjectType(), true, value));
    }
  }

  /** Append one HQL comparison per range operator found in {@code operators}. */
  private static void appendOperatorConditions(StringBuilder where, Entity dalEntity,
      String key, JSONObject operators, org.apache.logging.log4j.Logger log) throws JSONException {
    Property prop = resolveFilterProperty(dalEntity, key, log);
    if (prop == null) {
      log.warn("Filter key '{}' could not be resolved to a DAL property, ignoring", key);
      return;
    }
    Class<?> type = prop.isPrimitive() ? prop.getPrimitiveObjectType() : String.class;
    java.util.Iterator<String> ops = operators.keys();
    while (ops.hasNext()) {
      String op = ops.next();
      if (McpBusinessFilters.OP_BETWEEN.equals(op)) {
        JSONArray bounds = operators.optJSONArray(op);
        if (bounds == null || bounds.length() != 2) {
          log.warn("Filter '{}' between operator needs a [from, to] array, ignoring", key);
        } else {
          appendAnd(where);
          where.append("e.").append(prop.getName()).append(" between ")
              .append(McpBusinessFilters.formatHqlValue(type, prop.isPrimitive(), bounds.get(0)))
              .append(" and ")
              .append(McpBusinessFilters.formatHqlValue(type, prop.isPrimitive(), bounds.get(1)));
        }
      } else {
        String sql = McpBusinessFilters.operatorToSql(op);
        if (sql == null) {
          log.warn("Filter '{}' has unknown operator '{}', ignoring", key, op);
        } else {
          appendAnd(where);
          where.append("e.").append(prop.getName()).append(' ').append(sql).append(' ')
              .append(McpBusinessFilters.formatHqlValue(type, prop.isPrimitive(), operators.get(op)));
        }
      }
    }
  }

  /**
   * Append the HQL condition for a named business status, resolved against the entity's
   * hand-authored {@code NAMED_FILTERS}. Returns {@code false} when the entity declares no named
   * filters, so the caller can fall back to treating {@code status} as a plain column (backward
   * compatible). Throws {@link McpRoutingException} — surfaced to the agent as a 422 envelope naming
   * the valid states in {@code available}, never an HQL-500 — when the entity has named filters but
   * none matches the requested name.
   *
   * <p>ETP-4793 / IMP-17: this used to be an {@code IllegalArgumentException}, which the router's
   * catch-all could only render as prose (evidence C14: the list of valid states was there, but the
   * response carried no status and no machine-detectable code). Since IMP-17 that catch-all classifies
   * an unrecognised exception as {@code server_error}, so leaving it untyped would actively mislead —
   * this is the caller's mistake and one corrected word fixes it.</p>
   */
  private static boolean appendStatusCondition(StringBuilder where, SFEntity sfEntity,
      String status, org.apache.logging.log4j.Logger log) {
    java.util.Map<String, String> namedFilters =
        McpNamedFilters.parseWhereByName(sfEntity.getNamedFilters());
    if (namedFilters.isEmpty()) {
      return false;
    }
    String fragment = namedFilters.get(status);
    if (fragment == null) {
      throw McpRoutingException.unknownNamedFilter(status, sfEntity.getName(),
          new java.util.ArrayList<>(namedFilters.keySet()));
    }
    log.debug("Applying named filter '{}' for entity '{}'", status, sfEntity.getName());
    appendAnd(where);
    where.append('(').append(fragment).append(')');
    return true;
  }

  private static void appendAnd(StringBuilder where) {
    if (where.length() > 0) {
      where.append(" and ");
    }
  }

  /**
   * Collect the DAL property names the agent may write for an entity — the {@code editable} SFFields
   * (IMP-7). Feeds {@link McpDefaultsView#apply} so the grouped {@code neo_defaults} view can split
   * writable defaults from server-managed compliance flags. Returns an empty set (never null) when
   * the entity or its DAL model cannot be resolved, which makes the grouped view degrade to
   * "everything is systemManaged" rather than fail.
   *
   * <p>The Schema Forge {@code visibility} decision (editable/readOnly/system/discarded) is never
   * stored on {@code ETGO_SF_FIELD} as a literal string — {@code push-to-neo} maps it to the
   * {@code isIncluded}/{@code isReadOnly} booleans (editable = {@code isIncluded && !isReadOnly}).
   * Reading a nonexistent {@code visibility} column left this set empty for every spec, so we derive
   * editability from those two populated flags instead.
   */
  static java.util.Set<String> editablePropertyNames(SFEntity sfEntity, Tab adTab) {
    java.util.Set<String> result = new java.util.HashSet<>();
    Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return result;
    }
    OBCriteria<SFField> crit = OBDal.getInstance().createCriteria(SFField.class);
    crit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    crit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    for (SFField sfField : crit.list()) {
      Column col = sfField.getADColumn();
      // editable = included in the spec and not read-only. Mirrors mapVisibility() in
      // push-to-neo.js: editable is the only visibility yielding isIncluded='Y', isReadOnly='N'
      // (readOnly/system are included but read-only; discarded is excluded).
      boolean editable = Boolean.TRUE.equals(sfField.isIncluded())
          && !Boolean.TRUE.equals(sfField.isReadOnly());
      if (col == null || !editable) {
        continue;
      }
      Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName(), false);
      if (prop != null) {
        result.add(prop.getName());
      }
    }
    return result;
  }

  /**
   * Collect the DAL property names that make up a spec entity's curated summary — its
   * {@code business-critical} SFFields (IMP-2, {@code view:"summary"}). Feeds
   * {@link McpFieldProjection#apply}. Returns an empty set (never null) when nothing is flagged, in
   * which case the caller leaves the response full rather than hiding everything.
   */
  static java.util.Set<String> summaryFields(SFEntity sfEntity, Tab adTab) {
    java.util.Set<String> result = new java.util.HashSet<>();
    Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return result;
    }
    OBCriteria<SFField> crit = OBDal.getInstance().createCriteria(SFField.class);
    crit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    crit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    for (SFField sfField : crit.list()) {
      Column col = sfField.getADColumn();
      if (Boolean.TRUE.equals(sfField.isBusinessCritical()) && col != null) {
        Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName(), false);
        if (prop != null) {
          result.add(prop.getName());
        }
      }
    }
    return result;
  }

  /**
   * Apply the optional IMP-2 field projection to a {@code neo_list}/{@code neo_get} response.
   * Precedence: an explicit {@code fields:[...]} whitelist wins; otherwise {@code view:"summary"}
   * uses the entity's business-critical fields; anything else leaves the response full. A no-op
   * when neither is present, so the default behavior is unchanged.
   *
   * <p>IMP-18: an explicit whitelist is also validated, and any name the entity cannot emit comes
   * back as {@code response.unknownFields} instead of vanishing. {@code fieldFilter} is the same
   * filter the caller already applied to the rows, and is what makes the answer honest — the
   * emittable set has to be the spec's exposure post-rename, not the DAL model.
   */
  static void applyProjection(JSONObject responseJson, JSONObject args, SFEntity sfEntity, Tab adTab,
      NeoFieldFilter fieldFilter) throws JSONException {
    if (args == null) {
      return;
    }
    java.util.Set<String> requested;
    JSONArray fields = args.optJSONArray(McpFieldProjection.PARAM_FIELDS);
    boolean explicitWhitelist = fields != null;
    if (explicitWhitelist) {
      requested = McpFieldProjection.baseNames(McpFieldProjection.parseFields(fields));
    } else if (McpFieldProjection.isSummaryView(
        args.optString(McpFieldProjection.PARAM_VIEW, null))) {
      requested = summaryFields(sfEntity, adTab);
    } else {
      return;
    }
    if (explicitWhitelist) {
      // IMP-18: only an explicit caller whitelist can contain a typo. A view:"summary" set is
      // derived server-side from properties that already resolved, so an unknown name there would
      // be a server bug, not caller input, and reporting it would blame the wrong party.
      McpFieldProjection.reportUnknownFields(responseJson, requested,
          emittableBaseNames(fieldFilter, adTab));
    }
    McpFieldProjection.apply(responseJson, requested);
  }

  /**
   * The base field names a response for this entity can contain, for validating a caller's
   * {@code fields:[…]} whitelist (IMP-18).
   *
   * <p>The authoritative source is {@link NeoFieldFilter#emittableResponseKeys()} — the spec's own
   * exposure, already renamed to API keys — because a DAL property the spec does not include is just
   * as unavailable to the caller as one that does not exist. When no {@code ETGO_SF_FIELD} config
   * exists the filter is inactive and the response is unfiltered, so the DAL entity's property list
   * is the correct fallback.
   *
   * @return the emittable base names, or {@code null} when neither source is available, which means
   *     "cannot validate" and must leave the caller's names unjudged rather than reported as unknown
   */
  private static java.util.Set<String> emittableBaseNames(NeoFieldFilter fieldFilter, Tab adTab) {
    java.util.Set<String> keys = fieldFilter == null ? null : fieldFilter.emittableResponseKeys();
    if (keys == null) {
      Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
          .getEntityByTableName(adTab.getTable().getDBTableName());
      if (dalEntity == null) {
        return null;
      }
      keys = new java.util.HashSet<>();
      for (Property prop : dalEntity.getProperties()) {
        keys.add(prop.getName());
      }
    }
    java.util.Set<String> base = new java.util.HashSet<>();
    for (String key : keys) {
      base.add(McpDefaultsView.baseProperty(key));
    }
    return base;
  }
}
