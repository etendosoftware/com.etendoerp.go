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

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONObject;

/**
 * The contract a consumer registers with {@link McpEntityConfig} to own one section of the
 * {@code MCP_CONFIG} JSON.
 *
 * <p>{@code MCP_CONFIG} is a text column on the SchemaForge tables whose content is a JSON object
 * mapping <b>section name → section body</b>. Each MCP feature owns exactly one section and its
 * internal schema; nothing reads another section's keys. That is what makes a new option a new key
 * inside an existing section — no model change and no AD metadata.</p>
 *
 * <p>Registration declares three things, and {@link McpEntityConfig} needs all three to validate a
 * payload without knowing any section's semantics:</p>
 * <ol>
 *   <li><b>the name</b> — the JSON key this section lives under;</li>
 *   <li><b>the allowed keys</b> — anything else inside the section is an error, not a value that is
 *       quietly dropped. Without a typed schema a misspelled key is indistinguishable from an
 *       absent one, and for this column an absent key means "unconfigured", which for a section
 *       gating access is the permissive answer. See {@link McpEntityConfig}'s class javadoc;</li>
 *   <li><b>the merge mode</b> — how the spec/entity/field levels combine ({@link Merge}).</li>
 * </ol>
 *
 * <p>The validator receives the section body and returns the problems it found, empty when the
 * body is usable. It must not throw: a section whose validator throws is treated as invalid, but
 * the message an operator sees is then the exception's rather than a diagnosis.</p>
 */
final class McpConfigSection {

  /**
   * How a section's bodies combine across the {@code spec} → {@code entity} → {@code field} levels.
   *
   * <p>The two values exist because both behaviours are already in use in this module, so the base
   * cannot pick one and be done: {@code parent} (the first consumer, ETP-5184) needs
   * {@link #REPLACE} — an entity declares its parent and there is nothing to accumulate from the
   * spec — while {@code AGENT_PROMPT} concatenates all three levels today, so a section migrated
   * from it would need {@link #ADDITIVE}.</p>
   */
  enum Merge {
    /** The most specific level that defines the section wins outright. The default. */
    REPLACE,
    /** Every level that defines the section contributes; the section resolves the list itself. */
    ADDITIVE
  }

  private final String name;
  private final Set<String> allowedKeys;
  private final Merge merge;
  private final Function<JSONObject, List<String>> validator;

  private McpConfigSection(String name, Set<String> allowedKeys, Merge merge,
      Function<JSONObject, List<String>> validator) {
    this.name = name;
    this.allowedKeys = allowedKeys;
    this.merge = merge;
    this.validator = validator;
  }

  /**
   * Declare a section.
   *
   * @param name        the JSON key the section lives under; must be non-blank
   * @param allowedKeys every key the section body may contain. An empty set means the section takes
   *                    no keys at all, which is valid — and then any key is an error
   * @param merge       how the levels combine; {@link Merge#REPLACE} when in doubt
   * @param validator   returns the problems found in a section body, empty when it is usable. Never
   *                    returns {@code null}
   * @return the registrable section
   * @throws IllegalArgumentException if {@code name} is blank or any argument is {@code null}
   */
  static McpConfigSection of(String name, Set<String> allowedKeys, Merge merge,
      Function<JSONObject, List<String>> validator) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException("A config section needs a non-blank name");
    }
    if (allowedKeys == null || merge == null || validator == null) {
      throw new IllegalArgumentException(
          "Section '" + name + "': allowedKeys, merge and validator are all required");
    }
    return new McpConfigSection(name, Collections.unmodifiableSet(allowedKeys), merge, validator);
  }

  String getName() {
    return name;
  }

  Set<String> getAllowedKeys() {
    return allowedKeys;
  }

  Merge getMerge() {
    return merge;
  }

  /**
   * Run this section's own validation over a body whose keys have already been checked against
   * {@link #getAllowedKeys()}.
   *
   * <p>A validator that throws is reported as a problem rather than propagating: one section's bug
   * must not take down the catalog for entities that do not use it.</p>
   *
   * @param body the section body, never {@code null}
   * @return the problems found, empty when the body is usable
   */
  List<String> validate(JSONObject body) {
    try {
      List<String> problems = validator.apply(body);
      return problems == null ? Collections.emptyList() : problems;
    } catch (RuntimeException e) {
      return Collections.singletonList(
          "section '" + name + "' validator failed: " + e.getMessage());
    }
  }
}
