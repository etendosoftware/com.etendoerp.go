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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code parent} section of {@code MCP_CONFIG}: how a child entity's parent is identified, and
 * for which verbs the parent key is required.
 *
 * <h2>Shape</h2>
 * <pre>
 * {
 *   "parent": {
 *     "field":       "salesOrder",
 *     "entity":      "header",
 *     "optionalFor": ["list", "get"],
 *     "reason":      "parent is a configuration singleton",
 *     "mode":        "sameRecord"
 *   }
 * }
 * </pre>
 *
 * <p>Every key is optional, and <b>the common case needs no configuration at all</b>. For
 * {@code sales-order/lines}, {@code C_OrderLine} has exactly one active parent-link column
 * ({@code C_Order_ID}) and it points at the header tab's table, so {@link McpParentScope} resolves
 * {@code salesOrder} on its own. This section is written only where that automatic resolution does
 * not land — roughly 20 of the ~102 exposed child entities.</p>
 *
 * <h2>{@code field} is a property name, not a DBColumnName</h2>
 * <p>The rest of the MCP speaks in properties: {@code neo_list}'s filters are
 * {@code {"businessPartner": ...}}, {@code neo_create}'s fields likewise, and {@code neo_schema}'s
 * descriptors are named the same way. A configuration in physical column names would be the only
 * place that did not, and the refusal message would have to translate between two vocabularies
 * instead of naming the key the agent must actually type. Both forms are accepted — resolution goes
 * through the same property-or-column lookup {@code mapFieldsToDalProperties} and
 * {@code McpQuerySupport.resolveFilterProperty} already use — but the property form is what is
 * documented.</p>
 *
 * <h2>Why {@code optionalFor} is per verb</h2>
 * <p>The gate is not equally defensible across verbs. On write, a child with no parent is an
 * orphan — there is no sensible reading of "create an order line with no order". On read there are
 * legitimate global listings: a parent that is a per-client configuration singleton means "every
 * child" and "the children of the only parent" are the same set, and tenant isolation already
 * scopes it.</p>
 *
 * <p>So relaxation is per verb and read-only: {@code optionalFor} accepts {@code list} and
 * {@code get} and nothing else. Naming a write verb is a validation error rather than a silently
 * ignored value, because the difference between "I meant to allow reads" and "I disabled the write
 * gate" is exactly what must not be guessable. {@code reason} is mandatory alongside it, so every
 * exception carries a written motive an operator can audit.</p>
 */
final class McpParentSection {

  /** The section name inside {@code MCP_CONFIG}. */
  static final String NAME = "parent";

  static final String KEY_FIELD = "field";
  static final String KEY_ENTITY = "entity";
  static final String KEY_OPTIONAL_FOR = "optionalFor";
  static final String KEY_REASON = "reason";
  static final String KEY_MODE = "mode";

  /** {@code mode} value declaring a 1:1 tab over the parent's own table. */
  static final String MODE_SAME_RECORD = "sameRecord";

  /**
   * {@code mode} value declaring that the child has <em>no</em> link to its parent, and that reads
   * are therefore global (ETP-5184).
   *
   * <p>For the tabs where the parent is a scope rather than an owner. In {@code sii-monitor} the
   * header tab is {@code aeatsii_config} — per-organization SII configuration — and the child tabs
   * are {@code C_Invoice}: there is no foreign key between them in either direction, not even a
   * two-hop path, and the tabs carry no {@code whereclause} or link column, so Etendo's own UI does
   * the scoping outside the dictionary. {@code Fact_Acct} is the other shape: it addresses its
   * document through a polymorphic {@code Record_ID} + {@code AD_Table_ID} pair, which is not a DAL
   * property at all.</p>
   *
   * <p>This is deliberately <b>not</b> a filter that quietly matches everything. The declaration is
   * that no parent filter exists, {@code reason} is mandatory so the claim is auditable, and
   * {@link McpParentScope} refuses the entity outright if it advertises any write method — a record
   * that cannot name its parent cannot be created without producing an orphan, which is the whole
   * point of the gate. The distinction reaches the agent as {@code parentFilterable: false}, so it
   * is told the parent is not a filter here instead of inferring it from a suspiciously large
   * result set.</p>
   */
  static final String MODE_UNPARENTED = "unparented";

  /** Every accepted {@code mode}, for the unknown-value message. */
  private static final Set<String> MODES = Set.of(MODE_SAME_RECORD, MODE_UNPARENTED);

  /** Verbs the gate covers. A child entity requires its parent key on all of them by default. */
  static final String VERB_LIST = "list";
  static final String VERB_GET = "get";
  static final String VERB_CREATE = "create";
  static final String VERB_UPDATE = "update";
  static final String VERB_DELETE = "delete";

  /** Every verb, in the order they are reported to the agent. */
  static final List<String> ALL_VERBS =
      List.of(VERB_LIST, VERB_GET, VERB_CREATE, VERB_UPDATE, VERB_DELETE);

  /** The only verbs {@code optionalFor} may name: reads. */
  private static final Set<String> RELAXABLE_VERBS = Set.of(VERB_LIST, VERB_GET);

  private static final Set<String> ALLOWED_KEYS =
      Set.of(KEY_FIELD, KEY_ENTITY, KEY_OPTIONAL_FOR, KEY_REASON, KEY_MODE);

  private McpParentSection() {
  }

  /**
   * The registrable declaration of this section.
   *
   * @return the section, with its allowed keys, {@code REPLACE} merge and validator
   */
  static McpConfigSection declaration() {
    return McpConfigSection.of(NAME, ALLOWED_KEYS, McpConfigSection.Merge.REPLACE,
        McpParentSection::validate);
  }

  /**
   * Validate a {@code parent} body on its own terms.
   *
   * <p>Only what can be judged from the payload itself is checked here. Whether {@code field}
   * actually resolves to a foreign key on this entity's table, and whether {@code mode} agrees with
   * the model, need the DAL and are checked by {@link McpParentScope} — which is also the only
   * place that knows which entity the body belongs to.</p>
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    validateMode(body, problems);
    validateOptionalFor(body, problems);
    validateBlankStrings(body, problems);
    return problems;
  }

  private static void validateMode(JSONObject body, List<String> problems) {
    String mode = body.optString(KEY_MODE, null);
    if (mode == null) {
      return;
    }
    if (!MODES.contains(mode)) {
      problems.add("unknown mode '" + mode + "'. Accepted values are " + sortedModes());
      return;
    }
    if (MODE_UNPARENTED.equals(mode)) {
      validateUnparented(body, problems);
    }
  }

  private static List<String> sortedModes() {
    List<String> modes = new ArrayList<>(MODES);
    Collections.sort(modes);
    return modes;
  }

  /**
   * {@code unparented} needs a reason, and contradicts every other key.
   *
   * <p>Each refusal here is a payload that says two incompatible things, and the danger is that
   * whoever wrote it believes the half that is not true. {@code field} alongside {@code unparented}
   * would name a parent link on an entity that just declared it has none — and the resolver would
   * have to pick a winner, silently. {@code optionalFor} is redundant rather than contradictory,
   * but it reads as "reads are relaxed and writes are still gated", which is the opposite of what
   * {@code unparented} means: writes are not gated here, they are refused outright.</p>
   */
  private static void validateUnparented(JSONObject body, List<String> problems) {
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required with mode '" + MODE_UNPARENTED
          + "', so the claim that no parent filter exists is auditable");
    }
    if (body.has(KEY_FIELD)) {
      problems.add(KEY_FIELD + " cannot be combined with mode '" + MODE_UNPARENTED
          + "': the entity cannot both have and lack a parent link");
    }
    if (body.has(KEY_OPTIONAL_FOR)) {
      problems.add(KEY_OPTIONAL_FOR + " is meaningless with mode '" + MODE_UNPARENTED
          + "': reads are already unfiltered and writes are refused, not relaxed");
    }
  }

  /**
   * {@code optionalFor} must be an array of read verbs, and must come with a reason.
   *
   * <p>A write verb here is refused rather than filtered out: silently dropping it would leave an
   * operator believing the write gate was relaxed when it was not, and the reverse mistake — a
   * relaxation that was meant to be narrower — is worse still.</p>
   */
  private static void validateOptionalFor(JSONObject body, List<String> problems) {
    if (!body.has(KEY_OPTIONAL_FOR)) {
      return;
    }
    JSONArray verbs = body.optJSONArray(KEY_OPTIONAL_FOR);
    if (verbs == null) {
      problems.add(KEY_OPTIONAL_FOR + " must be an array of verbs, e.g. [\"list\",\"get\"]");
      return;
    }
    if (verbs.length() == 0) {
      problems.add(KEY_OPTIONAL_FOR + " is empty - omit the key instead");
      return;
    }
    for (int i = 0; i < verbs.length(); i++) {
      String verb = verbs.optString(i, "");
      if (RELAXABLE_VERBS.contains(verb)) {
        continue;
      }
      if (ALL_VERBS.contains(verb)) {
        problems.add(KEY_OPTIONAL_FOR + " cannot contain the write verb '" + verb
            + "'. Only " + RELAXABLE_VERBS + " may be relaxed: a child record written without its "
            + "parent is an orphan");
      } else {
        problems.add(KEY_OPTIONAL_FOR + " contains unknown verb '" + verb + "'. Valid: "
            + RELAXABLE_VERBS);
      }
    }
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required whenever " + KEY_OPTIONAL_FOR
          + " is set, so the exception is auditable");
    }
  }

  /** A key present but blank is a half-finished edit, not a value. */
  private static void validateBlankStrings(JSONObject body, List<String> problems) {
    for (String key : Arrays.asList(KEY_FIELD, KEY_ENTITY, KEY_REASON, KEY_MODE)) {
      if (body.has(key) && StringUtils.isBlank(body.optString(key, null))) {
        problems.add(key + " is present but blank - remove the key or give it a value");
      }
    }
  }

  /**
   * The verbs this body relaxes.
   *
   * @param body the section body, may be {@code null}
   * @return the relaxed read verbs, empty when the parent key is required on every verb
   */
  static Set<String> optionalVerbs(JSONObject body) {
    Set<String> relaxed = new LinkedHashSet<>();
    if (body == null) {
      return relaxed;
    }
    JSONArray verbs = body.optJSONArray(KEY_OPTIONAL_FOR);
    if (verbs == null) {
      return relaxed;
    }
    for (int i = 0; i < verbs.length(); i++) {
      String verb = verbs.optString(i, "");
      if (RELAXABLE_VERBS.contains(verb)) {
        relaxed.add(verb);
      }
    }
    return relaxed;
  }

  /**
   * @param body the section body, may be {@code null}
   * @return {@code true} when the body declares this entity a 1:1 tab over its parent's own table
   */
  static boolean isSameRecord(JSONObject body) {
    return body != null && MODE_SAME_RECORD.equals(body.optString(KEY_MODE, null));
  }

  /**
   * @param body the section body, may be {@code null}
   * @return {@code true} when the body declares this entity has no parent link at all
   */
  static boolean isUnparented(JSONObject body) {
    return body != null && MODE_UNPARENTED.equals(body.optString(KEY_MODE, null));
  }

  /**
   * @param body the section body, may be {@code null}
   * @return the declared parent field, or {@code null} when the heuristic should resolve it
   */
  static String declaredField(JSONObject body) {
    return body == null ? null : StringUtils.trimToNull(body.optString(KEY_FIELD, null));
  }

  /**
   * @param body the section body, may be {@code null}
   * @return the declared parent entity name, or {@code null} to derive it from the parent tab
   */
  static String declaredEntity(JSONObject body) {
    return body == null ? null : StringUtils.trimToNull(body.optString(KEY_ENTITY, null));
  }

  /**
   * @param body the section body, may be {@code null}
   * @return the operator's stated reason for relaxing read verbs, or {@code null}
   */
  static String reason(JSONObject body) {
    return body == null ? null : StringUtils.trimToNull(body.optString(KEY_REASON, null));
  }
}
