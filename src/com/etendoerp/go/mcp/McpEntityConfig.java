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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import org.openbravo.base.structure.BaseOBObject;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Reads the {@code MCP_CONFIG} column of the SchemaForge tables: the single place MCP-specific
 * behaviour is configured.
 *
 * <h2>Why this exists</h2>
 * <p>Every MCP-specific option so far meant a new column on one of the SchemaForge tables, each
 * with its own {@code AD_Column}, {@code AD_Field}, {@code AD_Element} and model XML entry. There
 * are already seven of them, all read only from this package: {@code AGENT_PROMPT} (on all three
 * tables), {@code SHOWINMCP}, {@code NAMED_FILTERS}, {@code VISIBILITY} and
 * {@code ISBUSINESSCRITICAL}. The marginal cost of each new flag is high and entirely metadata.
 * {@code MCP_CONFIG} gives them somewhere to live: a new option becomes a new key inside a section,
 * with no model change at all.</p>
 *
 * <p><b>The REST/headless API does not read this column.</b> That is the point of it — anything
 * configured here affects the MCP and nothing else. The seven columns above are deliberately left
 * alone: migrating them would be a separate breaking change and buys nothing today.</p>
 *
 * <h2>Shape</h2>
 * <p>The column holds a JSON object mapping <b>section name → section body</b>:</p>
 * <pre>
 * {
 *   "parent": { "field": "salesOrder", "entity": "header" }
 * }
 * </pre>
 * <p>Each feature owns one section, registered through {@link #register(McpConfigSection)}, and
 * reads only its own keys.</p>
 *
 * <h2>Precedence</h2>
 * <p>{@code field} &gt; {@code entity} &gt; {@code spec}. With {@link McpConfigSection.Merge#REPLACE}
 * — the default — the most specific level that defines a section wins outright. With
 * {@link McpConfigSection.Merge#ADDITIVE} every level that defines it contributes, outermost first,
 * and the section combines them itself. Both modes exist because both behaviours are already in use
 * here: the {@code parent} section replaces, while {@code AGENT_PROMPT} concatenates its three
 * levels.</p>
 *
 * <h2>Failure is loud, deliberately</h2>
 * <p>{@code McpNamedFilters} states in its javadoc that "a blank/malformed payload yields an empty
 * map (never null) so callers degrade gracefully". For named filters that is the right call — what
 * is lost is a convenience. <b>Here it would be dangerous.</b> A section of this column can be
 * carrying a security or integrity restriction, so reading malformed JSON as "no configuration"
 * would silently <i>switch that restriction off</i> — the same shape as the dropped-filter defect in
 * {@code McpQuerySupport#appendEqualityCondition}, which returned an unfiltered listing under status
 * 0 and produced one wrong answer in production.</p>
 *
 * <p>So: unparseable JSON, an unknown section, an unknown key inside a known section, or a failing
 * section validator all make {@link #problems} non-empty, and a non-empty {@code problems} means the
 * entity is <b>not published</b> — the caller reports it as misconfigured rather than serving it
 * with the section ignored. An empty or absent column is not a failure: it means "unconfigured", and
 * each section defines its own default for that.</p>
 *
 * <p>Reporting unknown keys is what separates a typo from a decision. Without a typed schema,
 * {@code "parentField"} written where {@code "field"} was meant is indistinguishable from an absent
 * key — and an absent key is the permissive reading.</p>
 *
 * <h2>Caching</h2>
 * <p>Parsing goes through {@link McpConfigCache}, keyed by record id, with an inactivity TTL plus
 * event-driven invalidation. See that class for why nothing session-dependent may be cached.</p>
 */
final class McpEntityConfig {

  private static final Logger log = LogManager.getLogger(McpEntityConfig.class);

  /** Registered sections by name. Concurrent: registration can race with reads at startup. */
  private static final Map<String, McpConfigSection> SECTIONS = new ConcurrentHashMap<>();

  private McpEntityConfig() {
  }

  /**
   * Register a section. Idempotent for the same instance; re-registering a different section under
   * a name already taken is a programming error and throws.
   *
   * @param section the section to register
   * @throws IllegalStateException if another section is already registered under that name
   */
  static void register(McpConfigSection section) {
    McpConfigSection previous = SECTIONS.putIfAbsent(section.getName(), section);
    if (previous != null && previous != section) {
      throw new IllegalStateException(
          "MCP config section '" + section.getName() + "' is already registered");
    }
  }

  /** Drop every registration. Tests only — production registers once at class-init time. */
  static void clearRegistrations() {
    SECTIONS.clear();
  }

  /**
   * The result of reading the {@code MCP_CONFIG} chain for one entity or field.
   *
   * <p>Always inspect {@link #getProblems()} before {@link #section(String)}: a resolved value from
   * a payload that failed validation must not be acted on. {@link #isUsable()} is the same check.</p>
   */
  static final class Resolved {

    private final Map<String, List<JSONObject>> bodiesBySection;
    private final List<String> problems;

    private Resolved(Map<String, List<JSONObject>> bodiesBySection, List<String> problems) {
      this.bodiesBySection = bodiesBySection;
      this.problems = problems;
    }

    /**
     * The winning body of one section, honouring its merge mode.
     *
     * @param sectionName the section to read
     * @return the most specific body for {@link McpConfigSection.Merge#REPLACE}, the outermost for
     *         {@link McpConfigSection.Merge#ADDITIVE} (use {@link #sectionLevels} there), or empty
     *         when no level defines it
     */
    Optional<JSONObject> section(String sectionName) {
      List<JSONObject> bodies = bodiesBySection.get(sectionName);
      if (bodies == null || bodies.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(bodies.get(bodies.size() - 1));
    }

    /**
     * Every level that defines a section, ordered {@code spec} → {@code entity} → {@code field}.
     * For an {@link McpConfigSection.Merge#ADDITIVE} section this is what it accumulates over.
     *
     * @param sectionName the section to read
     * @return the bodies, outermost first; empty when no level defines it
     */
    List<JSONObject> sectionLevels(String sectionName) {
      return bodiesBySection.getOrDefault(sectionName, Collections.emptyList());
    }

    /**
     * Everything wrong with this chain's payloads.
     *
     * @return the problems, empty when every payload is usable
     */
    List<String> getProblems() {
      return problems;
    }

    /**
     * @return {@code true} when no payload in the chain has a problem
     */
    boolean isUsable() {
      return problems.isEmpty();
    }

    /**
     * One line naming what is wrong, for the {@code neo_discover} report and the deploy validator.
     *
     * @return the joined problems, or an empty string when there are none
     */
    String describeProblems() {
      return String.join("; ", problems);
    }
  }

  private static final Resolved EMPTY =
      new Resolved(Collections.emptyMap(), Collections.emptyList());

  /**
   * Read the {@code spec} → {@code entity} chain for an entity.
   *
   * @param entity the SchemaForge entity; {@code null} yields an empty, usable result
   * @return the resolved configuration, never {@code null}
   */
  static Resolved forEntity(SFEntity entity) {
    if (entity == null) {
      return EMPTY;
    }
    return resolve(levelsOf(entity));
  }

  /**
   * Read the {@code spec} → {@code entity} → {@code field} chain for a field.
   *
   * @param field the SchemaForge field; {@code null} yields an empty, usable result
   * @return the resolved configuration, never {@code null}
   */
  static Resolved forField(SFField field) {
    if (field == null) {
      return EMPTY;
    }
    List<Level> levels = levelsOf(field.getETGOSFEntity());
    levels.add(new Level("field", field.getId(), rawConfig(field)));
    return resolve(levels);
  }

  /**
   * Read a spec's own configuration, with no entity below it.
   *
   * @param spec the SchemaForge spec; {@code null} yields an empty, usable result
   * @return the resolved configuration, never {@code null}
   */
  static Resolved forSpec(SFSpec spec) {
    if (spec == null) {
      return EMPTY;
    }
    List<Level> levels = new ArrayList<>();
    levels.add(new Level("spec", spec.getId(), rawConfig(spec)));
    return resolve(levels);
  }

  /** The spec → entity prefix both {@link #forEntity} and {@link #forField} start from. */
  private static List<Level> levelsOf(SFEntity entity) {
    List<Level> levels = new ArrayList<>();
    if (entity == null) {
      return levels;
    }
    SFSpec spec = entity.getETGOSFSpec();
    if (spec != null) {
      levels.add(new Level("spec", spec.getId(), rawConfig(spec)));
    }
    levels.add(new Level("entity", entity.getId(), rawConfig(entity)));
    return levels;
  }

  /**
   * Parse and validate every level, then group the section bodies outermost-first.
   *
   * <p>Ordering matters: {@link Resolved#section} takes the last body as the winner, so the list
   * has to be built spec → entity → field for "most specific wins" to hold.</p>
   */
  private static Resolved resolve(List<Level> levels) {
    // Every section must be registered before the first payload is read: an unregistered section
    // reads as "unknown section", which is an error by design. See McpConfigSections.
    McpConfigSections.ensureRegistered();
    Map<String, List<JSONObject>> bodies = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();

    for (Level level : levels) {
      JSONObject payload = parse(level, problems);
      if (payload == null) {
        continue;
      }
      collectSections(level, payload, bodies, problems);
    }
    if (!problems.isEmpty()) {
      // Warned, not just returned. The caller surfaces the problem to the agent that happened to
      // ask (neo_discover reports it per entity), but a misconfiguration nobody queries today is
      // still a misconfiguration, and the server log is where an operator looks after a deploy.
      //
      // A modulescript would be the earlier signal, but it cannot work: sections register at
      // runtime when the MCP starts, so at update.database time there is nothing to validate a
      // payload against. First read is the earliest point the answer is knowable.
      log.warn("Unusable MCP_CONFIG: {}", String.join("; ", problems));
    }
    return new Resolved(bodies, problems);
  }

  /**
   * Parse one level's column, caching the result by record id.
   *
   * @return the payload, or {@code null} when the column is blank (not a problem) or unparseable
   *         (a problem, already recorded)
   */
  private static JSONObject parse(Level level, List<String> problems) {
    if (StringUtils.isBlank(level.raw)) {
      return null;
    }
    try {
      return McpConfigCache.parsedConfig(level.recordId, () -> new JSONObject(level.raw));
    } catch (RuntimeException e) {
      // Unparseable JSON is exactly the case that must not be read as "unconfigured".
      problems.add(level.name + "-level MCP_CONFIG is not valid JSON: " + rootMessage(e));
      log.debug("Unparseable MCP_CONFIG at {} level, record {}", level.name, level.recordId);
      return null;
    }
  }

  /**
   * The body of one declared section, or {@code null} having recorded why it is unusable.
   *
   * <p>Extracted so {@link #collectSections} has a single exit per iteration (java:S135). Both
   * rejections are payload shape, not section semantics: a name no section is registered under,
   * and a value that is not an object. The section's own validator judges everything past that.</p>
   */
  private static JSONObject usableBody(Level level, JSONObject payload, String sectionName,
      List<String> problems) {
    if (!SECTIONS.containsKey(sectionName)) {
      problems.add(level.name + "-level MCP_CONFIG declares unknown section '" + sectionName
          + "'. Known sections: " + knownSectionNames());
      return null;
    }
    JSONObject body = payload.optJSONObject(sectionName);
    if (body == null) {
      problems.add(level.name + SECTION_PREFIX + sectionName + "' must be a JSON object");
    }
    return body;
  }

  /** Repeated in three problem messages; Sonar java:S1192 and one place to change the wording. */
  private static final String SECTION_PREFIX = "-level MCP_CONFIG section '";

  /** Validate every section of one payload and file its body under the section name. */
  private static void collectSections(Level level, JSONObject payload,
      Map<String, List<JSONObject>> bodies, List<String> problems) {
    for (String sectionName : keysOf(payload)) {
      JSONObject body = usableBody(level, payload, sectionName, problems);
      if (body == null) {
        continue;
      }
      List<String> sectionProblems = validateBody(level, SECTIONS.get(sectionName), body);
      if (!sectionProblems.isEmpty()) {
        problems.addAll(sectionProblems);
        continue;
      }
      bodies.computeIfAbsent(sectionName, k -> new ArrayList<>()).add(body);
    }
  }

  /** Unknown keys first — a typo there makes any further diagnosis misleading. */
  private static List<String> validateBody(Level level, McpConfigSection section, JSONObject body) {
    List<String> unknown = new ArrayList<>();
    for (String key : keysOf(body)) {
      if (!section.getAllowedKeys().contains(key)) {
        unknown.add(key);
      }
    }
    if (!unknown.isEmpty()) {
      return Collections.singletonList(level.name + SECTION_PREFIX
          + section.getName() + "' has unknown key(s) " + unknown
          + ". Allowed: " + new ArrayList<>(section.getAllowedKeys()));
    }
    List<String> problems = new ArrayList<>();
    for (String problem : section.validate(body)) {
      problems.add(level.name + SECTION_PREFIX + section.getName() + "': " + problem);
    }
    return problems;
  }

  /**
   * The DAL property the {@code MCP_CONFIG} column maps to.
   *
   * <p><b>Not {@code "mcpConfig"}.</b> Etendo's model generator uppercases a leading acronym, so
   * {@code MCP_Config} became {@code mCPConfig} — and reading the wrong name is the one failure this
   * class cannot report, because a nonexistent property is indistinguishable from an unconfigured
   * record: every payload would have been ignored in silence, which is precisely the degradation the
   * loud-failure path exists to prevent. Taken from the generated constant rather than typed out, so
   * a future rename breaks the build instead of quietly emptying the configuration.</p>
   */
  static final String PROPERTY_MCP_CONFIG = SFEntity.PROPERTY_MCPCONFIG;

  /**
   * Read one record's raw {@code MCP_CONFIG} text.
   *
   * <p>By property name rather than the generated {@code getMCPConfig()} accessor, because one
   * method has to serve all three levels and {@link SFSpec}, {@link SFEntity} and {@link SFField}
   * share no interface that declares it. The name itself comes from generated code
   * ({@link #PROPERTY_MCP_CONFIG}), so this is not a hand-written string that can drift.</p>
   *
   * <p>A read that throws is reported, not swallowed. It used to be debug-logged as "the column
   * does not exist yet", which was true while the column was still being added and is now a lie
   * that would hide a real model problem — and hide it in the quietest possible way, since the
   * caller would see an unconfigured record and carry on.</p>
   *
   * @param target the SchemaForge record to read
   * @return the raw column text, or {@code null} when unset or unreadable
   */
  private static String rawConfig(BaseOBObject target) {
    try {
      Object value = target.get(PROPERTY_MCP_CONFIG);
      return value == null ? null : String.valueOf(value);
    } catch (RuntimeException e) {
      log.error("Could not read {} on {} — every MCP_CONFIG payload on this record is being "
          + "ignored", PROPERTY_MCP_CONFIG, target.getEntityName(), e);
      return null;
    }
  }

  private static List<String> keysOf(JSONObject object) {
    List<String> keys = new ArrayList<>();
    java.util.Iterator<?> it = object.keys();
    while (it.hasNext()) {
      keys.add(String.valueOf(it.next()));
    }
    return keys;
  }

  private static List<String> knownSectionNames() {
    List<String> names = new ArrayList<>(SECTIONS.keySet());
    Collections.sort(names);
    return names;
  }

  /**
   * The innermost message of a parse failure. Jettison wraps its own {@link JSONException}, and the
   * wrapper's message is less useful than the cause's for someone fixing the JSON.
   */
  private static String rootMessage(Throwable t) {
    Throwable current = t;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return StringUtils.isBlank(message) ? current.getClass().getSimpleName() : message;
  }

  /** One rung of the spec → entity → field chain. */
  private static final class Level {
    private final String name;
    private final String recordId;
    private final String raw;

    private Level(String name, String recordId, String raw) {
      this.name = name;
      this.recordId = recordId;
      this.raw = raw;
    }
  }
}
