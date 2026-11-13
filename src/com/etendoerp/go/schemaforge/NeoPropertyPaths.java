/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.service.json.JsonConstants;

/**
 * The property-path fields of one entity (ETP-6148): read-only fields whose value lives on a
 * related record and is reached through a dotted DAL path relative to the entity, the way a
 * Classic {@code AD_Field.Property} field does ({@code paymentScheduleDetail.businessPartner} on
 * a remittance line). An {@code ETGO_SF_FIELD} row with {@code PROPERTY_PATH} set and no
 * {@code AD_COLUMN_ID} declares one; its {@code JAVA_QUALIFIER} is the API key the caller sees.
 *
 * <p>Generic by construction: nothing here names an entity, a path or a key. What it does, on
 * every channel (REST single, MCP):</p>
 * <ul>
 *   <li><b>Read.</b> {@link #applyToReadParams} asks core for the paths through
 *       {@code _extraProperties}, and {@link #capture} / {@link #restore} hand each value back
 *       under the API key ({@code apiKey}, plus {@code apiKey$_identifier} for a reference).</li>
 *   <li><b>Filter and sort.</b> {@link #applyToReadParams} rewrites the API key to the path in the
 *       grid {@code criteria} (any nesting), {@code _sortBy}, {@code _distinct} and
 *       {@code _summary}, and sets {@code _use_alias=true}: only in alias mode does core's
 *       {@code AdvancedQueryBuilder} turn the {@code $}-joined path into left outer joins wherever
 *       a step is optional, so a row whose path is broken is kept.</li>
 *   <li><b>Write.</b> Never writable; {@link #isPathKey} lets the write paths treat the key as
 *       read-only.</li>
 * </ul>
 *
 * <p>Core quirk handled by {@link #capture}: for a reference path, {@code DataToJsonConverter}
 * writes the id under the dotted key ({@code a.b.c}) while the identifier and a null value use
 * the {@code $}-joined one ({@code a$b$c}, {@code a$b$c$_identifier}).</p>
 */
public final class NeoPropertyPaths {

  private static final Logger log = LogManager.getLogger(NeoPropertyPaths.class);
  static final String IDENTIFIER_SUFFIX = DalUtil.FIELDSEPARATOR + JsonConstants.IDENTIFIER;
  private static final String CRITERIA = "criteria";
  private static final String FIELD_NAME = "fieldName";

  private static final NeoPropertyPaths EMPTY = new NeoPropertyPaths(Collections.emptyMap());

  /** One property-path field. */
  public static final class PathField {
    private final String apiKey;
    private final String path;
    private final Property target;

    PathField(String apiKey, String path, Property target) {
      this.apiKey = apiKey;
      this.path = path;
      this.target = target;
    }

    /** @return the key the caller reads, filters and sorts by */
    public String getApiKey() {
      return apiKey;
    }

    /** @return the dotted DAL path, relative to the entity */
    public String getPath() {
      return path;
    }

    /** @return the property the path ends on */
    public Property getTarget() {
      return target;
    }

    /** @return whether the path ends on a reference (it then also carries an identifier) */
    public boolean isReference() {
      return !target.isPrimitive();
    }

    /** @return the path as core's JSON and criteria spell it: {@code a$b$c} */
    public String joinedPath() {
      return path.replace(DalUtil.DOT, DalUtil.FIELDSEPARATOR);
    }

    /**
     * The HQL expression of the path from {@code alias}, through the computed-columns proxy when
     * the last step is a computed column.
     *
     * @param alias the main alias of the query, e.g. {@code e}
     * @return the expression
     */
    public String hqlPath(String alias) {
      if (!target.isComputedColumn()) {
        return alias + DalUtil.DOT + path;
      }
      int lastDot = path.lastIndexOf(DalUtil.DOT);
      String prefix = lastDot < 0 ? "" : path.substring(0, lastDot + 1);
      return alias + DalUtil.DOT + prefix + Entity.COMPUTED_COLUMNS_PROXY_PROPERTY + DalUtil.DOT
          + target.getName();
    }
  }

  /** Collects the declared rows of one entity, validating each. */
  static final class Builder {
    private final Entity dalEntity;
    private final Map<String, PathField> byApiKey = new LinkedHashMap<>();

    Builder(Entity dalEntity) {
      this.dalEntity = dalEntity;
    }

    /**
     * Adds a declared path field, or skips it with a warning when it cannot be served: no API key,
     * a key that is a property of the entity or already taken, or a path that does not resolve.
     *
     * @param apiKey the row's {@code JAVA_QUALIFIER}
     * @param path   the row's {@code PROPERTY_PATH}
     * @return whether the field was added
     */
    boolean add(String apiKey, String path) {
      String key = StringUtils.trimToNull(apiKey);
      String trimmedPath = StringUtils.trimToNull(path);
      if (key == null || trimmedPath == null) {
        log.warn("Property-path field on {} skipped: it needs both a path and an API key "
            + "(path={}, apiKey={})", dalEntity.getName(), path, apiKey);
        return false;
      }
      if (dalEntity.hasProperty(key) || byApiKey.containsKey(key)) {
        log.warn("Property-path field {} on {} skipped: its API key '{}' is already a property "
            + "or another field's key", trimmedPath, dalEntity.getName(), key);
        return false;
      }
      Property target = resolvePath(dalEntity, trimmedPath);
      if (target == null) {
        return false;
      }
      byApiKey.put(key, new PathField(key, trimmedPath, target));
      return true;
    }

    /**
     * @param takenApiKeys the API keys the entity's column fields already use
     * @return the collected fields, minus any whose key collides with {@code takenApiKeys}
     */
    NeoPropertyPaths build(Collection<String> takenApiKeys) {
      Map<String, PathField> result = new LinkedHashMap<>(byApiKey);
      for (String taken : takenApiKeys) {
        if (result.remove(taken) != null) {
          log.warn("Property-path field '{}' on {} skipped: a column field already uses that key",
              taken, dalEntity.getName());
        }
      }
      return result.isEmpty() ? EMPTY : new NeoPropertyPaths(result);
    }
  }

  private final Map<String, PathField> byApiKey;

  private NeoPropertyPaths(Map<String, PathField> byApiKey) {
    this.byApiKey = byApiKey;
  }

  /** @return the instance with no field */
  static NeoPropertyPaths empty() {
    return EMPTY;
  }

  /**
   * The path fields of a field filter, never {@code null} (a filter stubbed without them answers
   * none).
   *
   * @param filter the entity's field filter, may be {@code null}
   * @return its path fields, or the empty instance
   */
  public static NeoPropertyPaths of(NeoFieldFilter filter) {
    NeoPropertyPaths paths = filter == null ? null : filter.propertyPaths();
    return paths == null ? EMPTY : paths;
  }

  /**
   * The property-path fields of an entity, from the same field filter its reads apply.
   *
   * @param sfEntity      the SchemaForge entity, or {@code null} (no paths)
   * @param dalEntityName the DAL entity name
   * @return its path fields, or the empty instance
   */
  public static NeoPropertyPaths forEntity(com.etendoerp.go.schemaforge.data.SFEntity sfEntity,
      String dalEntityName) {
    // forEntity answers an inactive filter, with no path, for a null entity.
    return of(NeoFieldFilter.forEntity(sfEntity, dalEntityName));
  }

  /**
   * @param dalEntity the entity the paths start from
   * @return a builder for that entity's fields
   */
  static Builder builder(Entity dalEntity) {
    return new Builder(dalEntity);
  }

  /**
   * Resolves a dotted path against {@code entity}: every step must exist, every step but the last
   * must be a many-to-one reference, and the last must not be a collection.
   *
   * @param entity the entity the path starts from
   * @param path   the dotted path
   * @return the property the path ends on, or {@code null} (logged) when it does not resolve
   */
  static Property resolvePath(Entity entity, String path) {
    String[] steps = path.split("\\.");
    Entity current = entity;
    Property property = null;
    for (int i = 0; i < steps.length; i++) {
      if (current == null || steps[i].isEmpty() || !current.hasProperty(steps[i])) {
        log.warn("Property path '{}' does not resolve on {} at step '{}'", path, entity.getName(),
            steps[i]);
        return null;
      }
      property = current.getProperty(steps[i]);
      if (property.isOneToMany()) {
        log.warn("Property path '{}' on {} crosses a collection at '{}'", path, entity.getName(),
            steps[i]);
        return null;
      }
      boolean last = i == steps.length - 1;
      if (!last && (property.isPrimitive() || property.getTargetEntity() == null)) {
        log.warn("Property path '{}' on {} continues past the value '{}'", path, entity.getName(),
            steps[i]);
        return null;
      }
      current = property.getTargetEntity();
    }
    return property;
  }

  /** @return whether the entity declares no path field */
  public boolean isEmpty() {
    return byApiKey.isEmpty();
  }

  /** @return the fields, in declaration order */
  public Collection<PathField> fields() {
    return byApiKey.values();
  }

  /**
   * The declared field of an API key.
   *
   * @param apiKey an API key
   * @return its field, or {@code null}
   */
  public PathField get(String apiKey) {
    return apiKey == null ? null : byApiKey.get(apiKey);
  }

  /**
   * The declared field of a dotted path.
   *
   * @param path a dotted path
   * @return the field declared for it, or {@code null}
   */
  public PathField byPath(String path) {
    for (PathField field : byApiKey.values()) {
      if (field.path.equals(path)) {
        return field;
      }
    }
    return null;
  }

  /**
   * Whether a body key belongs to one of these fields.
   *
   * @param key a key of a request or response body
   * @return whether it is a path field's key or its identifier key
   */
  public boolean isPathKey(String key) {
    return fieldOf(key) != null;
  }

  /** @return every key a response can carry for these fields */
  public Set<String> responseKeys() {
    Set<String> keys = new LinkedHashSet<>();
    for (PathField field : byApiKey.values()) {
      keys.add(field.apiKey);
      if (field.isReference()) {
        keys.add(field.apiKey + IDENTIFIER_SUFFIX);
      }
    }
    return keys;
  }

  private PathField fieldOf(String key) {
    if (key == null || byApiKey.isEmpty()) {
      return null;
    }
    PathField field = byApiKey.get(key);
    if (field == null && key.endsWith(IDENTIFIER_SUFFIX)) {
      PathField owner = byApiKey.get(key.substring(0, key.length() - IDENTIFIER_SUFFIX.length()));
      field = owner != null && owner.isReference() ? owner : null;
    }
    return field;
  }

  /**
   * The name core reads for a caller-side name in criteria, {@code _distinct} and
   * {@code _summary}: {@code apiKey} → {@code a$b$c}, {@code apiKey$_identifier} →
   * {@code a$b$c$_identifier}.
   *
   * @param name the caller's field name
   * @return the path name, or {@code null} when {@code name} is no path field's
   */
  public String toReadPath(String name) {
    PathField field = fieldOf(name);
    if (field == null) {
      return null;
    }
    return name.equals(field.apiKey) ? field.joinedPath()
        : field.joinedPath() + IDENTIFIER_SUFFIX;
  }

  /**
   * As {@link #toReadPath}, but a reference sorts by its identifier, as the grid shows it.
   *
   * @param name the caller's sort field, without direction
   * @return the path name, or {@code null}
   */
  String toSortPath(String name) {
    PathField field = fieldOf(name);
    if (field == null) {
      return null;
    }
    return field.isReference() ? field.joinedPath() + IDENTIFIER_SUFFIX : toReadPath(name);
  }

  /**
   * Prepares a read's datasource parameters: adds the paths to {@code _extraProperties} (not for
   * a {@code _distinct} or {@code _summary} fetch, whose rows are not this entity's) and rewrites
   * the API keys in {@code criteria}, {@code _sortBy}, {@code _distinct} and {@code _summary}.
   * When it rewrites any of them it sets {@code _use_alias=true}, without which core joins the
   * path implicitly (inner) and drops the rows whose path is broken.
   *
   * @param params the parameters handed to {@code DefaultJsonDataService.fetch}; modified in place
   */
  public void applyToReadParams(Map<String, String> params) {
    if (byApiKey.isEmpty() || params == null) {
      return;
    }
    if (!params.containsKey(JsonConstants.DISTINCT_PARAMETER)
        && !params.containsKey(JsonConstants.SUMMARY_PARAMETER)) {
      addExtraProperties(params);
    }
    boolean rewritten = rewriteSort(params);
    String distinct = params.get(JsonConstants.DISTINCT_PARAMETER);
    String distinctPath = toReadPath(distinct);
    if (distinctPath != null) {
      params.put(JsonConstants.DISTINCT_PARAMETER, distinctPath);
      rewritten = true;
    }
    rewritten |= rewriteJsonParam(params, CRITERIA);
    rewritten |= rewriteSummary(params);
    if (rewritten) {
      // Core joins a path with a LEFT join only in alias mode; without it the join is implicit
      // (inner) and a row whose path is broken vanishes from a sorted or filtered list. REST
      // always sets it (its where clause); an MCP list without filters did not.
      params.put(JsonConstants.USE_ALIAS, "true");
    }
  }

  private void addExtraProperties(Map<String, String> params) {
    Set<String> extra = new LinkedHashSet<>();
    String existing = params.get(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER);
    if (StringUtils.isNotBlank(existing)) {
      for (String part : existing.split(",")) {
        if (StringUtils.isNotBlank(part)) {
          extra.add(part.trim());
        }
      }
    }
    for (PathField field : byApiKey.values()) {
      extra.add(field.path);
    }
    params.put(JsonConstants.ADDITIONAL_PROPERTIES_PARAMETER, String.join(",", extra));
  }

  private boolean rewriteSort(Map<String, String> params) {
    String sortBy = params.get(JsonConstants.SORTBY_PARAMETER);
    if (StringUtils.isBlank(sortBy)) {
      return false;
    }
    List<String> parts = new ArrayList<>();
    boolean changed = false;
    for (String raw : sortBy.split(",")) {
      String token = raw.trim();
      boolean desc = token.startsWith("-");
      String name = desc ? token.substring(1) : token;
      // "name desc" is accepted by core too: only the name part is mapped.
      int space = name.indexOf(' ');
      String suffix = space < 0 ? "" : name.substring(space);
      String mapped = toSortPath(space < 0 ? name : name.substring(0, space));
      if (mapped != null) {
        changed = true;
        parts.add((desc ? "-" : "") + mapped + suffix);
      } else {
        parts.add(token);
      }
    }
    if (changed) {
      params.put(JsonConstants.SORTBY_PARAMETER, String.join(",", parts));
    }
    return changed;
  }

  private boolean rewriteJsonParam(Map<String, String> params, String name) {
    String raw = params.get(name);
    if (StringUtils.isBlank(raw)) {
      return false;
    }
    try {
      String trimmed = raw.trim();
      if (trimmed.startsWith("[")) {
        JSONArray array = new JSONArray(trimmed);
        if (rewriteCriteria(array)) {
          params.put(name, array.toString());
          return true;
        }
      } else if (trimmed.startsWith("{")) {
        JSONObject object = new JSONObject(trimmed);
        if (rewriteCriteria(object)) {
          params.put(name, object.toString());
          return true;
        }
      }
    } catch (JSONException e) {
      // Left as sent: core parses it next and answers the malformed criteria itself.
      log.debug("Criteria not rewritten for property paths: {}", e.getMessage());
    }
    return false;
  }

  private boolean rewriteCriteria(Object node) throws JSONException {
    boolean changed = false;
    if (node instanceof JSONArray) {
      JSONArray array = (JSONArray) node;
      for (int i = 0; i < array.length(); i++) {
        changed |= rewriteCriteria(array.opt(i));
      }
    } else if (node instanceof JSONObject) {
      JSONObject object = (JSONObject) node;
      String mapped = toReadPath(object.optString(FIELD_NAME, null));
      if (mapped != null) {
        object.put(FIELD_NAME, mapped);
        changed = true;
      }
      Object nested = object.opt(CRITERIA);
      if (nested != null) {
        changed |= rewriteCriteria(nested);
      }
    }
    return changed;
  }

  @SuppressWarnings("unchecked")
  private boolean rewriteSummary(Map<String, String> params) {
    String raw = params.get(JsonConstants.SUMMARY_PARAMETER);
    if (StringUtils.isBlank(raw) || !raw.trim().startsWith("{")) {
      return false;
    }
    try {
      JSONObject summary = new JSONObject(raw.trim());
      JSONObject rewritten = new JSONObject();
      boolean changed = false;
      Iterator<String> keys = summary.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String mapped = toReadPath(key);
        changed |= mapped != null;
        rewritten.put(mapped != null ? mapped : key, summary.get(key));
      }
      if (changed) {
        params.put(JsonConstants.SUMMARY_PARAMETER, rewritten.toString());
      }
      return changed;
    } catch (JSONException e) {
      log.debug("Summary not rewritten for property paths: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Reads the path values core wrote into {@code row}, before the field filter strips the raw
   * keys. Only the values actually present are taken, so a response that did not ask for the
   * paths (a write's echo) is left alone.
   *
   * @param row one response row
   * @return the values by API key, possibly empty
   */
  Map<String, Object> capture(JSONObject row) {
    if (byApiKey.isEmpty() || row == null) {
      return Collections.emptyMap();
    }
    Map<String, Object> values = new HashMap<>();
    for (PathField field : byApiKey.values()) {
      String joined = field.joinedPath();
      if (row.has(field.path)) {
        values.put(field.apiKey, row.opt(field.path));
      } else if (row.has(joined)) {
        values.put(field.apiKey, row.opt(joined));
      }
      if (field.isReference() && row.has(joined + IDENTIFIER_SUFFIX)) {
        values.put(field.apiKey + IDENTIFIER_SUFFIX, row.opt(joined + IDENTIFIER_SUFFIX));
      }
    }
    return values;
  }

  /**
   * Writes back what {@link #capture} took, under the API keys.
   *
   * @param row   the row, after field filtering
   * @param captured what {@link #capture} returned for it
   */
  void restore(JSONObject row, Map<String, Object> captured) {
    if (row == null || captured.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : captured.entrySet()) {
      try {
        row.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
      } catch (JSONException e) {
        log.warn("Could not write property-path value {}: {}", entry.getKey(), e.getMessage());
      }
    }
  }
}
