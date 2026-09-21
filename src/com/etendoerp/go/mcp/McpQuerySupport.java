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

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
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
   *
   * <p><b>ETP-5184: no filter is ever dropped in silence.</b> An unresolvable key, an unknown
   * operator, and a malformed {@code between} value each used to be logged at WARN and skipped,
   * leaving the query to run with whatever conditions survived. Filtering on one misspelled key
   * therefore answered 200 with the entire table — and from the caller's side that is
   * indistinguishable from a filter that legitimately matched everything, so nothing prompted a
   * retry. All three now raise a 422 naming the correction. A caller that means "no filter" says so
   * by sending no filter.</p>
   *
   * @throws McpRoutingException 422 when any filter key, operator, or operator value is not usable
   */
  static String buildWhereFromFilters(JSONObject filters, Tab adTab, SFEntity sfEntity,
      org.apache.logging.log4j.Logger log) throws JSONException {
    Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEntity == null) {
      return null;
    }

    // IMP-39: resolved once per request, not once per key - it costs one query, and the previous
    // contract was that the happy path pays nothing for the failure path's `available` list.
    java.util.Set<String> excluded = excludedPropertyNames(sfEntity, dalEntity);

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
        appendOperatorConditions(where, dalEntity, sfEntity, excluded, key, (JSONObject) value);
      } else {
        appendEqualityCondition(where, dalEntity, sfEntity, excluded, key, value);
      }
    }
    return where.length() > 0 ? where.toString() : null;
  }

  /**
   * Resolve a filter key to a DAL property, tolerating both column names and property names.
   *
   * <p>ETP-5184: an unresolvable key now throws instead of returning {@code null}. It used to be
   * logged at WARN by each caller and the condition dropped, so a query filtering on one misspelled
   * key ran with the remaining conditions — or with none at all — and answered 200 with the whole
   * table. That is indistinguishable, from the agent's side, from a filter that legitimately
   * matched everything, which is exactly the failure that made a child-entity list look like it had
   * returned the right thing. Silence was the bug; the correction is one word, so name it.</p>
   *
   * @param dalEntity the DAL entity the filter is aimed at
   * @param sfEntity  the SchemaForge entity, used to list the filterable names on the failure path
   * @param excluded  the property names the spec excluded, resolved once per request by
   *                  {@link #excludedPropertyNames}
   * @param key       the filter key as the caller spelled it
   * @return the resolved property, never {@code null}
   * @throws McpRoutingException 422 {@code unknown_filter_field}, naming the keys that would work
   */
  private static Property resolveFilterProperty(Entity dalEntity, SFEntity sfEntity,
      java.util.Set<String> excluded, String key) {
    Property resolved = dalEntity.getPropertyByColumnName(key, false);
    if (resolved == null) {
      try {
        resolved = dalEntity.getProperty(key);
      } catch (Exception ignored) {
        throw unknownFilterField(key, dalEntity, sfEntity);
      }
    }
    // IMP-39: resolving against the DAL model is not the same question as "may this be filtered".
    // It used to be the only check, so a field the spec excluded filtered perfectly well while the
    // `available` list below - which has always been scoped to the included rows - did not name it
    // and neo_get did not project it. The set that is enforced and the set that is advertised must
    // be one set.
    if (excluded.contains(resolved.getName())) {
      throw unknownFilterField(key, dalEntity, sfEntity);
    }
    return resolved;
  }

  /**
   * The one refusal for a filter key that may not be used, whatever the reason.
   *
   * <p><b>Deliberately identical for a name that does not exist and a name the spec excludes.</b>
   * Two distinguishable answers would let any caller enumerate the columns of the underlying AD
   * table by probing keys and reading which refusal came back — the response itself would confirm
   * the existence of every field the spec was curated to hide. The wording says the key is not
   * available on this entity and stops there: it neither asserts nor denies that a column of that
   * name exists. What the caller is entitled to know is in {@code available}, which lists exactly
   * the fields this entity does expose.</p>
   *
   * @param key       the filter key as the caller spelled it
   * @param dalEntity the DAL entity, for mapping columns to property names
   * @param sfEntity  the SchemaForge entity, or {@code null}
   * @return the exception to throw
   */
  private static McpRoutingException unknownFilterField(String key, Entity dalEntity,
      SFEntity sfEntity) {
    return McpRoutingException.unknownFilterField(key,
        sfEntity == null ? dalEntity.getName() : sfEntity.getName(),
        filterablePropertyNames(sfEntity, dalEntity));
  }

  /**
   * The property names the spec deliberately excluded from this entity's surface — the one set
   * both the filter path and the write path refuse, so the two cannot drift apart.
   *
   * <p><b>Excluded, not "not included".</b> Only a {@code ETGO_SF_FIELD} row that exists and says
   * {@code ISINCLUDED = 'N'} bars a filter. A column with no row at all is uncurated, and absence
   * of curation is not a decision to hide it — there are over a thousand such columns across the
   * curated entities of a typical instance, and a handler-backed entity (dashboards, reports,
   * reconciliation views) has no field rows whatsoever, so an "included-only" allowlist would
   * reject every filter those entities were ever sent.</p>
   *
   * @param sfEntity  the SchemaForge entity, or {@code null} when none is in play
   * @param dalEntity the DAL entity, for mapping columns to property names
   * @return the excluded property names, possibly empty, never {@code null}
   */
  static java.util.Set<String> excludedPropertyNames(SFEntity sfEntity, Entity dalEntity) {
    return writeGate(sfEntity, dalEntity).excluded;
  }

  /**
   * The two sets a write must clear, built in one pass over the entity's curated rows.
   *
   * <p>They are disjoint by construction: {@link #excluded} is what the spec does not expose at
   * all, {@link #readOnlyRejectable} is what it exposes and marks unwritable. A field cannot be
   * both, and the two refusals say different things on purpose — see
   * {@link McpRoutingException#fieldNotAllowed} and {@link McpRoutingException#readOnlyField}.</p>
   */
  static final class WriteGate {
    /** Property names the spec excludes from the agent surface (IMP-39). */
    final java.util.Set<String> excluded;
    /** Property names the spec exposes as read-only and no one else could be supplying (IMP-48). */
    final java.util.Set<String> readOnlyRejectable;
    /**
     * IMP-30 (second half): read-only properties exempted because their AD column carries a
     * literal configured default, mapped to that default. The exemption exists so an agent that
     * echoes back what {@code neo_defaults} handed it is not refused for following the documented
     * sequence — so it should cover the echo and nothing else. It used to cover any value at all,
     * which is how {@code documentStatus} (default {@code 'DR'}) still accepted {@code "CO"} and
     * created a completed order with no lines, the exact state IMP-30's 2026-08-13 probe reached.
     * A value equal to the default is an echo; a different one is an override of a field the
     * surface publishes as read-only.
     */
    final java.util.Map<String, String> readOnlyDefaults;

    private WriteGate(java.util.Set<String> excluded, java.util.Set<String> readOnlyRejectable,
        java.util.Map<String, String> readOnlyDefaults) {
      this.excluded = excluded;
      this.readOnlyRejectable = readOnlyRejectable;
      this.readOnlyDefaults = readOnlyDefaults;
    }

    /**
     * @param property the mapped DAL property name
     * @param value    the value the caller sent for it
     * @return {@code true} when this write must be refused as a read-only override
     */
    boolean rejectsDefaultOverride(String property, Object value) {
      String configured = readOnlyDefaults.get(property);
      return configured != null && !configured.equals(String.valueOf(value));
    }
  }

  /**
   * Build the write gate for an entity.
   *
   * <p><b>The read-only predicate is {@code NeoFieldFilter}'s {@code rejectableOnCreateFields}
   * (IMP-28 clause 2); one of its two exemptions is deliberately <em>not</em> carried over, and the
   * reason is structural rather than a difference of opinion about what read-only means.</b></p>
   *
   * <ul>
   *   <li><b>Dropped: the entity-wide {@code Java_Qualifier} exemption.</b> On the REST path
   *       {@code filterCreateRequest} runs <em>after</em> {@code NeoServletSupport.handleWithHooks}
   *       has already invoked the entity's {@code NeoHandler} pre-hook, so by the time it inspects
   *       the body it cannot tell a value the handler injected ({@code InventoryLineHandler} sets
   *       {@code bookQuantity}) from one the client sent — and exempting the whole entity is the
   *       only safe answer available to it. <b>On the MCP path that ambiguity does not exist:</b>
   *       this mapping runs on the caller's own {@code fields} argument, and
   *       {@code McpHookExecutor.runPreHook} fires further down {@code handleCreate}, on the body
   *       this returns. Every key here is the caller's by construction. Keeping the exemption would
   *       have cost most of the gate — <b>79 of the 128 writable entities declare a qualifier, and
   *       783 curated read-only fields behind them are AD-updatable</b>, so the rejection would
   *       have fired on under two fifths of the surface. It was kept in the first implementation
   *       and a live probe caught it: {@code neo_update} on {@code sales-order/header}, whose
   *       qualifier is {@code salesOrderHeaderHandler}, accepted {@code documentNo} and answered
   *       200.</li>
   *   <li><b>Kept: the configured-AD-default exemption.</b> The platform fills that column, and an
   *       agent following {@code neo_defaults} is actively invited to send resolved values back in
   *       {@code fields} (the subject of IMP-45), so a default echoed into a write is a shape the
   *       recommended sequence produces rather than a mistake.</li>
   * </ul>
   *
   * <p>Read-only-ness is resolved through {@link McpFieldView}, so a {@code MCP_CONFIG}
   * {@code fields.readOnly: false} override reclaims a field for writing exactly the way
   * {@code fields.included} reclaims an excluded one.</p>
   *
   * @param sfEntity  the SchemaForge entity; {@code null} yields two empty sets
   * @param dalEntity the DAL entity, for mapping columns to property names
   * @return the gate, never {@code null}
   */
  static WriteGate writeGate(SFEntity sfEntity, Entity dalEntity) {
    java.util.Set<String> excluded = new java.util.HashSet<>();
    java.util.Set<String> readOnly = new java.util.HashSet<>();
    java.util.Map<String, String> readOnlyDefaults = new java.util.HashMap<>();
    if (sfEntity == null) {
      return new WriteGate(excluded, readOnly, readOnlyDefaults);
    }
    for (SFField sfField : activeFields(sfEntity)) {
      Column col = sfField.getADColumn();
      // A row with no column, and a column the DAL does not map, are the same non-answer here:
      // there is no property to put in either set.
      Property prop = col == null ? null
          : dalEntity.getPropertyByColumnName(col.getDBColumnName(), false);
      if (prop == null) {
        continue;
      }
      // Through McpFieldView, never a Restrictions.eq on ISINCLUDED/ISREADONLY: the MCP_CONFIG
      // overrides are invisible to a criteria, and a reader that ignored them would drift from
      // neo_schema - which is the disagreement IMP-39 exists to end.
      McpFieldView view = McpFieldView.of(sfField);
      if (!view.isIncluded()) {
        excluded.add(prop.getName());
      } else if (view.isReadOnly()) {
        String literalDefault = literalDefault(col);
        if (literalDefault == null) {
          readOnly.add(prop.getName());
        } else {
          readOnlyDefaults.put(prop.getName(), literalDefault);
        }
      }
    }
    return new WriteGate(excluded, readOnly, readOnlyDefaults);
  }

  /**
   * @param adColumn the AD column
   * @return whether AD itself fills this column, which exempts it from the read-only rejection
   */
  private static boolean hasConfiguredDefault(Column adColumn) {
    return adColumn != null && StringUtils.isNotBlank(adColumn.getDefaultValue());
  }

  /**
   * The column's AD default when it is a plain literal a caller could echo back, else {@code null}.
   *
   * <p>An Etendo default is only sometimes a value: {@code @#AD_Org_ID@} and {@code @SQL=…} are
   * session/context expressions and {@code now()} is evaluated per request, so none of them can be
   * compared against what the caller sent.</p>
   *
   * <p>Returning {@code null} for those puts the property in the gate's strict {@code readOnly}
   * set, so sending the column is <b>refused</b> rather than echo-exempted. That is deliberate:
   * the echo exemption exists to forgive a caller that read the schema and sent the value back
   * unchanged, and it can only forgive what it can verify. With an expression there is nothing to
   * compare against, so accepting would not be forgiving a known-harmless echo — it would be
   * waving through an unexamined value. A refusal costs the caller a 422 it can act on; the other
   * side of the mistake is silent.</p>
   *
   * <p><b>Corrected 2026-09-16 (ETP-5335).</b> This paragraph previously claimed those columns
   * "keep the blanket exemption they have had since IMP-48" and warned that narrowing it would
   * refuse legitimate echoes. The code has always done the opposite of what that described, and
   * the code is the behaviour we want; the text was wrong, not the branch. The strongest evidence
   * is {@code @#AD_Org_ID@} itself: it was the default behind the cross-tenant write reported in
   * the 2026-09-16 security review, and the conclusion there was that those columns are resolved
   * from the session and never from the payload — see {@code NeoServerOwnedFields}, which now
   * strips {@code client} and {@code organization} before a write ever reaches this gate.</p>
   *
   * @param adColumn the AD column
   * @return the literal default, or {@code null} when there is none or it is an expression
   */
  private static String literalDefault(Column adColumn) {
    if (!hasConfiguredDefault(adColumn)) {
      return null;
    }
    String value = adColumn.getDefaultValue().trim();
    if (value.startsWith("@") || value.contains("(")) {
      return null;
    }
    return value;
  }

  /**
   * Every active {@code SFField} row of an entity, in one query.
   *
   * @param sfEntity the SchemaForge entity
   * @return the rows, possibly empty
   */
  private static java.util.List<SFField> activeFields(SFEntity sfEntity) {
    OBCriteria<SFField> crit = OBDal.getInstance().createCriteria(SFField.class);
    crit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    crit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    return crit.list();
  }

  /**
   * The property names a caller may filter on, for the {@code available} list of an unknown-key
   * refusal (ETP-5184).
   *
   * <p>Scoped to the entity's included {@code ETGO_SF_FIELD} rows rather than every DAL property:
   * a name the spec excluded is not an answer, and offering it would send the agent to a second
   * 422. Read-only fields stay in — being unable to write a value has never stopped anyone from
   * filtering on it. Sorted so the list reads the same way twice, and computed only on the failure
   * path, so the happy path pays nothing for it.</p>
   *
   * @param sfEntity  the SchemaForge entity; {@code null} falls back to the DAL property list
   * @param dalEntity the DAL entity, for mapping columns to property names
   * @return the sorted filterable names, possibly empty, never {@code null}
   */
  static java.util.List<String> filterablePropertyNames(SFEntity sfEntity,
      Entity dalEntity) {
    java.util.SortedSet<String> names = new java.util.TreeSet<>();
    if (sfEntity == null) {
      for (Property prop : dalEntity.getProperties()) {
        names.add(prop.getName());
      }
      return new java.util.ArrayList<>(names);
    }
    for (SFField sfField : activeFields(sfEntity)) {
      Column col = sfField.getADColumn();
      // Same resolver as the exclusion set above, so what is advertised and what is enforced
      // cannot disagree - including when MCP_CONFIG reclaims a field.
      if (col == null || !McpFieldView.of(sfField).isIncluded()) {
        continue;
      }
      Property prop = dalEntity.getPropertyByColumnName(col.getDBColumnName(), false);
      if (prop != null) {
        names.add(prop.getName());
      }
    }
    return new java.util.ArrayList<>(names);
  }

  /** Append {@code e.prop = value} (or {@code e.prop.id = 'value'} for a FK), type-aware. */
  private static void appendEqualityCondition(StringBuilder where, Entity dalEntity,
      SFEntity sfEntity, java.util.Set<String> excluded, String key, Object value) {
    Property prop = resolveFilterProperty(dalEntity, sfEntity, excluded, key);
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
      SFEntity sfEntity, java.util.Set<String> excluded, String key,
      JSONObject operators) throws JSONException {
    Property prop = resolveFilterProperty(dalEntity, sfEntity, excluded, key);
    Class<?> type = prop.isPrimitive() ? prop.getPrimitiveObjectType() : String.class;
    java.util.Iterator<String> ops = operators.keys();
    while (ops.hasNext()) {
      String op = ops.next();
      if (McpBusinessFilters.OP_BETWEEN.equals(op)) {
        JSONArray bounds = operators.optJSONArray(op);
        if (bounds == null || bounds.length() != 2) {
          // ETP-5184: same silent-drop shape as an unknown key. A malformed between used to be
          // logged and skipped, so `{"amount":{"between":[100]}}` answered 200 with every row —
          // the opposite of the narrowing the caller asked for.
          throw McpRoutingException.malformedFilterOperator(key, op,
              "the 'between' operator takes a two-element [from, to] array");
        }
        appendAnd(where);
        where.append("e.").append(prop.getName()).append(" between ")
            .append(McpBusinessFilters.formatHqlValue(type, prop.isPrimitive(), bounds.get(0)))
            .append(" and ")
            .append(McpBusinessFilters.formatHqlValue(type, prop.isPrimitive(), bounds.get(1)));
      } else {
        String sql = McpBusinessFilters.operatorToSql(op);
        if (sql == null) {
          throw McpRoutingException.unknownFilterOperator(key, op,
              McpBusinessFilters.operatorKeys());
        }
        appendAnd(where);
        where.append("e.").append(prop.getName()).append(' ').append(sql).append(' ')
            .append(McpBusinessFilters.formatHqlValue(type, prop.isPrimitive(), operators.get(op)));
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
   * <p>{@code push-to-neo} maps the Schema Forge {@code visibility} decision
   * (editable/readOnly/system/discarded) to the {@code isIncluded}/{@code isReadOnly} booleans
   * (editable = {@code isIncluded && !isReadOnly}), and for many specs that is all that is
   * populated — the {@code VISIBILITY} column itself is frequently {@code NULL}. Editability is
   * therefore resolved through {@link McpFieldView}, which falls back to those two booleans when no
   * visibility is curated and honours the {@code MCP_CONFIG} {@code fields} override when one is.
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
      // One resolver for every reader (ETP-5184): McpFieldView keeps this derivation - included in
      // the spec and not read-only, mirroring mapVisibility() in push-to-neo.js, where editable is
      // the only visibility yielding isIncluded='Y', isReadOnly='N' - and prefers the curated
      // visibility string wherever one exists, including one the MCP_CONFIG "fields" section
      // supplies. neo_schema and neo_selectors can no longer disagree about the same field.
      if (col == null || !McpFieldView.of(sfField).isEditable()) {
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
      if (McpFieldView.of(sfField).isBusinessCritical() && col != null) {
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
   * @return {@link Optional#of} the emittable base names, or {@link Optional#empty()} when neither
   *     source is available, which means "cannot validate" and must leave the caller's names
   *     unjudged rather than reported as unknown
   */
  private static Optional<java.util.Set<String>> emittableBaseNames(NeoFieldFilter fieldFilter, Tab adTab) {
    Optional<java.util.Set<String>> filterKeys =
        fieldFilter == null ? Optional.empty() : fieldFilter.emittableResponseKeys();
    java.util.Set<String> keys;
    if (filterKeys.isPresent()) {
      keys = filterKeys.get();
    } else {
      Entity dalEntity = org.openbravo.base.model.ModelProvider.getInstance()
          .getEntityByTableName(adTab.getTable().getDBTableName());
      if (dalEntity == null) {
        return Optional.empty();
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
    return Optional.of(base);
  }
}
