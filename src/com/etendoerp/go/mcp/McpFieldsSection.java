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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code fields} section of {@code MCP_CONFIG}: the MCP's own field curation, for the fields
 * whose {@code ETGO_SF_FIELD} curation is missing or wrong for agent use.
 *
 * <h2>Shape</h2>
 * <pre>
 * {
 *   "fields": {
 *     "visibility":       "editable",
 *     "readOnly":         false,
 *     "businessCritical": true,
 *     "reason":           "C_Location's ETGO_SF_FIELD rows carry no visibility ..."
 *   }
 * }
 * </pre>
 *
 * <p>Every key is optional except {@code reason}, and the section applies at whichever level it is
 * written — spec, entity or field — with the most specific level winning ({@link
 * McpConfigSection.Merge#REPLACE}). An entity-level body therefore reclassifies every field of that
 * entity at once, which is the shape the first consumer needs: {@code bp-location/bpLocation} has
 * ten fields and all ten are uncurated in the same way.</p>
 *
 * <h2>What this does <em>not</em> do</h2>
 * <p><b>This reclassifies SchemaForge's curation, never Etendo's permissions.</b> Curation answers
 * "is this field part of the agent surface, and may the agent send it" — the question that decides
 * whether {@code neo_schema} advertises the field and whether {@code McpToolRouter} publishes
 * {@code POST}/{@code PUT} for the entity at all. Permissions answer "may this role write this
 * column on this record", and that question is settled downstream, by the DAL and by
 * {@code NeoCrudHandler}, exactly as before. Declaring {@code visibility:"editable"} on a column AD
 * itself marks non-updatable, or that the caller's role cannot write, does not make the write
 * succeed: it makes the agent able to attempt it and be refused by the authority that owns the
 * answer. The override widens what is <i>offered</i>, not what is <i>allowed</i>.</p>
 *
 * <h2>Why an override instead of fixing the curation</h2>
 * <p>The honest description of the first case is that it is a curation omission, not a design
 * choice. All ten {@code ETGO_SF_FIELD} rows for {@code C_Location} carry {@code VISIBILITY = NULL}.
 * The REST/React layer does not gate on that column — it reads {@code ISINCLUDED}/{@code ISREADONLY}
 * — so the gap is invisible there, while the MCP's method gate requires at least one
 * {@code editable} field before it will publish a write verb ({@code McpToolRouter}, via {@link
 * McpSchemaFieldBuilder#isAgentSuppliable}). The result was that no business-partner address could
 * be created through an agent at all: {@code contacts/locationAddress} needs an existing
 * {@code C_Location}, and {@code bp-location/bpLocation} is the only entity mapping that table.</p>
 *
 * <p>Backfilling {@code VISIBILITY} would have been the deeper fix, and it is the one to make
 * eventually — and it is not a tail: <b>130 of the 242 active entities that advertise POST or PUT
 * publish no field that passes the method gate at all</b> (128 of them carry no curated visibility
 * on any field, and 10 have no {@code ETGO_SF_FIELD} row whatsoever). Sizing that backfill as a
 * handful of entities, as an earlier revision of this javadoc and of commit {@code 3a6535d5} did
 * with the figure "18", understates it by roughly sevenfold. It was not taken here because
 * that column is read by the REST and React layers too, so a backfill changes the shared contract
 * for every existing consumer. This section is the deliberately low-risk path: it is read by the MCP
 * and nothing else ({@code MCP_CONFIG} has no reader outside {@code com.etendoerp.go.mcp}), so the
 * shared contract is untouched and the blast radius is one entity. {@code reason} is mandatory
 * precisely so that this trade-off stays written down on the row that makes it.</p>
 *
 * <h2>One resolver, every reader</h2>
 * <p>An override that only the first reader honoured would be worse than no override. Four places
 * derive these properties from {@code SFField} independently — {@code neo_schema}'s field metadata,
 * {@code neo_selectors}' editable-property set, the resource provider's field list and the
 * {@code view:"summary"} projection — and they do not agree by construction. {@link McpFieldView}
 * is the single resolver all of them go through, so "editable" means the same thing in every
 * response. {@code McpFieldViewSingleResolverCallSiteTest} fails the build if one stops.</p>
 */
final class McpFieldsSection {

  /** The section name inside {@code MCP_CONFIG}. */
  static final String NAME = "fields";

  static final String KEY_VISIBILITY = "visibility";
  static final String KEY_READ_ONLY = "readOnly";
  static final String KEY_BUSINESS_CRITICAL = "businessCritical";
  static final String KEY_REASON = "reason";

  /**
   * Every accepted {@code visibility}, reusing {@link McpSchemaFieldBuilder}'s constants so the
   * accepted values cannot drift from the ones the schema builder acts on.
   */
  private static final Set<String> VISIBILITIES = Set.of(
      McpSchemaFieldBuilder.VISIBILITY_EDITABLE,
      McpSchemaFieldBuilder.VISIBILITY_READ_ONLY,
      McpSchemaFieldBuilder.VISIBILITY_SYSTEM,
      McpSchemaFieldBuilder.VISIBILITY_DISCARDED);

  /** The keys that must hold a JSON boolean when present. */
  private static final List<String> BOOLEAN_KEYS =
      List.of(KEY_READ_ONLY, KEY_BUSINESS_CRITICAL);

  private static final Set<String> ALLOWED_KEYS =
      Set.of(KEY_VISIBILITY, KEY_READ_ONLY, KEY_BUSINESS_CRITICAL, KEY_REASON);

  private McpFieldsSection() {
  }

  /**
   * The registrable declaration of this section.
   *
   * @return the section, with its allowed keys, {@code REPLACE} merge and validator
   */
  static McpConfigSection declaration() {
    return McpConfigSection.of(NAME, ALLOWED_KEYS, McpConfigSection.Merge.REPLACE,
        McpFieldsSection::validate);
  }

  /**
   * Validate a {@code fields} body on its own terms.
   *
   * <p>Everything here is judgeable from the payload alone. Whether the reclassified field is
   * actually writable needs the DAL and the caller's role, and is neither known nor asserted at
   * this point — see this class's javadoc on curation versus permissions.</p>
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    if (body.length() == 0) {
      problems.add("the '" + NAME + "' section is empty - remove it, or set at least one of "
          + KEY_VISIBILITY + "/" + KEY_READ_ONLY + "/" + KEY_BUSINESS_CRITICAL + " plus "
          + KEY_REASON);
      return problems;
    }
    validateVisibility(body, problems);
    validateBooleans(body, problems);
    validateReason(body, problems);
    return problems;
  }

  /**
   * An unknown {@code visibility} is refused rather than ignored.
   *
   * <p>A silently dropped value here would leave the field on its uncurated default, which for the
   * case this section exists to fix is the value that hides the write verb — so the operator would
   * see the exact symptom they wrote the override to remove, with nothing saying why.</p>
   */
  private static void validateVisibility(JSONObject body, List<String> problems) {
    if (!body.has(KEY_VISIBILITY)) {
      return;
    }
    String visibility = StringUtils.trimToNull(body.optString(KEY_VISIBILITY, null));
    if (visibility == null) {
      problems.add(KEY_VISIBILITY + " is present but blank - remove the key or give it one of "
          + sortedVisibilities());
      return;
    }
    if (!VISIBILITIES.contains(visibility)) {
      problems.add("unknown " + KEY_VISIBILITY + " '" + visibility + "'. Accepted values are "
          + sortedVisibilities());
    }
  }

  private static List<String> sortedVisibilities() {
    List<String> visibilities = new ArrayList<>(VISIBILITIES);
    Collections.sort(visibilities);
    return visibilities;
  }

  /**
   * {@code readOnly} and {@code businessCritical} must be JSON booleans.
   *
   * <p>{@code "false"} as a string is the dangerous case: {@code optBoolean} would read the quoted
   * form the same way in some JSON libraries and the opposite way in others, and this section's
   * whole purpose is that an operator can predict what a row does. So the literal type is required
   * and the message names the correction.</p>
   */
  private static void validateBooleans(JSONObject body, List<String> problems) {
    for (String key : BOOLEAN_KEYS) {
      if (!body.has(key)) {
        continue;
      }
      Object value = body.opt(key);
      if (!(value instanceof Boolean)) {
        problems.add(key + " must be a JSON boolean (true or false), not "
            + describe(value) + " - write it unquoted");
      }
    }
  }

  private static String describe(Object value) {
    if (value == null) {
      return "null";
    }
    return value instanceof String ? "the string \"" + value + "\"" : String.valueOf(value);
  }

  /**
   * {@code reason} is mandatory whenever the section is present.
   *
   * <p>Every row of this section asserts that the shared curation is wrong for agent use. That is a
   * claim about the data, not a preference, and an operator reading the row later has no other way
   * to tell a deliberate reclassification from a leftover experiment.</p>
   */
  private static void validateReason(JSONObject body, List<String> problems) {
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required whenever the '" + NAME
          + "' section is present, so the reclassification is auditable");
    }
  }

  /**
   * The declared visibility.
   *
   * @param body the section body, may be {@code null}
   * @return the configured visibility, or {@code null} when this level does not reclassify it
   */
  static String visibility(JSONObject body) {
    return body == null ? null : StringUtils.trimToNull(body.optString(KEY_VISIBILITY, null));
  }

  /**
   * The declared read-only flag.
   *
   * @param body the section body, may be {@code null}
   * @return {@link Boolean#TRUE}/{@link Boolean#FALSE} as configured, or
   *         {@link Optional#empty()} when the key is absent — which is <b>not</b> the same as a
   *         configured {@code false}
   */
  static Optional<Boolean> readOnly(JSONObject body) {
    return booleanAt(body, KEY_READ_ONLY);
  }

  /**
   * The declared business-critical flag.
   *
   * @param body the section body, may be {@code null}
   * @return {@link Boolean#TRUE}/{@link Boolean#FALSE} as configured, or
   *         {@link Optional#empty()} when the key is absent — which is <b>not</b> the same as a
   *         configured {@code false}
   */
  static Optional<Boolean> businessCritical(JSONObject body) {
    return booleanAt(body, KEY_BUSINESS_CRITICAL);
  }

  /**
   * @param body the section body, may be {@code null}
   * @return the operator's stated reason for reclassifying, or {@code null}
   */
  static String reason(JSONObject body) {
    return body == null ? null : StringUtils.trimToNull(body.optString(KEY_REASON, null));
  }

  /**
   * Read one boolean key without letting a non-boolean value read as {@code false}.
   *
   * <p>{@link #validateBooleans} already refuses those payloads, so a non-boolean can only reach
   * here from a body that failed validation and is therefore not being acted on — but "absent" and
   * "malformed" must both answer "nothing was said" rather than silently answering
   * {@code false}: the merge in {@code McpFieldView} treats an unstated flag as "do not override",
   * and a {@code false} would demote a field the {@code SFField} row marked true.</p>
   *
   * <p>The tri-state is carried as an {@link Optional} rather than a nullable {@link Boolean}
   * (S2447). Two of the three states of a nullable {@code Boolean} unbox to the same thing at any
   * call site that forgets the null check, and the value crosses a package boundary into
   * {@code McpFieldView}; {@code Optional} makes the third state impossible to drop silently and
   * lets the merge state its rule in one line, {@code orElse(current)}. Nothing about which
   * payloads answer which state changed.</p>
   */
  private static Optional<Boolean> booleanAt(JSONObject body, String key) {
    if (body == null) {
      return Optional.empty();
    }
    Object value = body.opt(key);
    return value instanceof Boolean ? Optional.of((Boolean) value) : Optional.empty();
  }
}
