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
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;

/**
 * The {@code verbs} section of {@code MCP_CONFIG}: hides MCP write verbs that
 * {@code ETGO_SF_ENTITY} still enables for REST and the SPA (ETP-5558).
 *
 * <h2>Shape</h2>
 * <pre>
 * {
 *   "verbs": {
 *     "create":  false,
 *     "update":  false,
 *     "delete":  false,
 *     "reason":  "payments are created from the invoice, never by hand",
 *     "instead": "etendo_action(spec:'sales-invoice', entity:'header', action:'registerPayment')"
 *   }
 * }
 * </pre>
 *
 * <p><b>Why it exists.</b> The MCP surface must equal the UI surface both ways: what the UI does not
 * offer, an agent must not be drawn into. The payment windows create payments only through the
 * invoice actions, yet every payment entity has every method flag on, so MCP advertised and executed
 * a hand-built route nothing validates. The flags cannot be turned off — REST and the SPA read them —
 * so this section hides the verb for the MCP only. Nothing outside {@code src/com/etendoerp/go/mcp/}
 * reads it.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>{@code create} / {@code update} / {@code delete} are JSON booleans. {@code false} hides the
 *       verb; {@code true} or absence leaves the {@code ETGO_SF_ENTITY} flag in charge. It
 *       <b>never widens</b>: {@code true} cannot enable a verb whose flag is off. {@code update}
 *       covers both {@code PUT} and {@code PATCH}. At least one verb key is required.</li>
 *   <li>{@code reason} is mandatory: every hidden verb is a claim that the shared configuration is
 *       wrong for agents, and the claim must be auditable on the row that makes it. It also reaches
 *       the agent in the refusal.</li>
 *   <li>{@code instead} is optional: the call that does the job, quoted in the refusal's hint. Without
 *       it the hint points at the entity's actions.</li>
 *   <li>{@code REPLACE} across levels, written at entity level (a spec-level body applies to every
 *       entity of the spec unless one declares its own).</li>
 * </ul>
 */
final class McpVerbsSection {

  /** The section name inside {@code MCP_CONFIG}. */
  static final String NAME = "verbs";

  static final String KEY_CREATE = "create";
  static final String KEY_UPDATE = "update";
  static final String KEY_DELETE = "delete";
  static final String KEY_REASON = "reason";
  static final String KEY_INSTEAD = "instead";

  private static final List<String> VERB_KEYS = List.of(KEY_CREATE, KEY_UPDATE, KEY_DELETE);
  private static final Set<String> ALLOWED_KEYS =
      Set.of(KEY_CREATE, KEY_UPDATE, KEY_DELETE, KEY_REASON, KEY_INSTEAD);

  /**
   * The one instance of this section (ETP-5639). {@link #declaration()} used to build a new one on
   * every call, so two first callers registering concurrently handed the registry two different
   * objects under one name, and the loser's call failed as a duplicate.
   */
  private static final McpConfigSection DECLARATION = McpConfigSection.of(NAME, ALLOWED_KEYS,
      McpConfigSection.Merge.REPLACE, McpVerbsSection::validate);

  private static final Logger log = LogManager.getLogger(McpVerbsSection.class);

  /** The reason an agent reads when the configuration itself cannot be trusted. */
  private static final String UNUSABLE_REASON = "its MCP configuration is invalid";

  private McpVerbsSection() {
  }

  /**
   * The registrable declaration of this section.
   *
   * @return the section, with its allowed keys, {@code REPLACE} merge and validator
   */
  static McpConfigSection declaration() {
    return DECLARATION;
  }

  /**
   * Validate a {@code verbs} body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    boolean anyVerb = false;
    for (String verb : VERB_KEYS) {
      if (!body.has(verb)) {
        continue;
      }
      anyVerb = true;
      if (!(body.opt(verb) instanceof Boolean)) {
        problems.add(verb + " must be a JSON boolean (false hides the verb), not "
            + body.opt(verb));
      }
    }
    if (!anyVerb) {
      problems.add("names no verb — declare at least one of " + VERB_KEYS
          + ", or remove the section");
    }
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required, so every hidden verb is auditable");
    }
    if (body.has(KEY_INSTEAD) && StringUtils.isBlank(body.optString(KEY_INSTEAD, null))) {
      problems.add(KEY_INSTEAD + " is present but blank - remove the key or give it a value");
    }
    return problems;
  }

  /**
   * Why a write method is hidden from the MCP for this entity, if it is.
   */
  static final class Hidden {
    private final String reason;
    private final String instead;

    private Hidden(String reason, String instead) {
      this.reason = reason;
      this.instead = instead;
    }

    /** @return the declared reason, never blank */
    String getReason() {
      return reason;
    }

    /** @return the declared replacement call, or {@code null} */
    String getInstead() {
      return instead;
    }
  }

  /**
   * Whether {@code MCP_CONFIG} hides {@code method} on {@code entity}.
   *
   * <p>Fails closed: when the entity's configuration is unusable, every write method is reported
   * hidden. A restriction that failed validation must not switch itself off — the same rule
   * {@link McpEntityConfig} states for every section. Reads are never hidden.</p>
   *
   * @param entity the SchemaForge entity
   * @param method {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}; anything else is
   *               never hidden
   * @return the reason it is hidden, or {@code null} when the MCP may use it
   */
  static Hidden hiddenFor(SFEntity entity, String method) {
    String verb = verbOf(method);
    if (entity == null || verb == null) {
      return null;
    }
    McpEntityConfig.Resolved resolved = McpEntityConfig.forEntity(entity);
    if (!resolved.isUsable()) {
      log.warn("MCP write {} hidden on entity '{}' ({}): its MCP_CONFIG is unusable — {}", method,
          entity.getName(), entity.getId(), resolved.describeProblems());
      return new Hidden(UNUSABLE_REASON, null);
    }
    JSONObject body = resolved.section(NAME).orElse(null);
    if (body == null || !Boolean.FALSE.equals(body.opt(verb))) {
      return null;
    }
    return new Hidden(StringUtils.trim(body.optString(KEY_REASON, "")),
        StringUtils.trimToNull(body.optString(KEY_INSTEAD, null)));
  }

  private static String verbOf(String method) {
    if (NeoMethodPolicy.METHOD_POST.equals(method)) {
      return KEY_CREATE;
    }
    if (NeoMethodPolicy.METHOD_PUT.equals(method) || NeoMethodPolicy.METHOD_PATCH.equals(method)) {
      return KEY_UPDATE;
    }
    if (NeoMethodPolicy.METHOD_DELETE.equals(method)) {
      return KEY_DELETE;
    }
    return null;
  }
}
