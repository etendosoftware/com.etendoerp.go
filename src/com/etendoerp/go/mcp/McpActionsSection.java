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
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The {@code actions} section of {@code MCP_CONFIG}: which of an entity's actions the MCP must not
 * offer, and which ones it should point elsewhere (ETP-5558).
 *
 * <h2>Shape</h2>
 * <pre>
 * {
 *   "actions": {
 *     "hidden":   ["pisTemplates", "psd2GenerateBankPayment"],
 *     "redirect": { "aPRMAddpayment": "registerPayment" },
 *     "values":   { "aPRMProcessPayment": ["P"] },
 *     "reason":   "PIS needs a person to authorize at the bank",
 *     "redirectReason": "the classic Add Payment button is not the Etendo payment flow"
 *   }
 * }
 * </pre>
 *
 * <p><b>Why it exists.</b> The sibling of {@code verbs} for actions. A handler serves the same
 * action to the SPA and to the agent, and some of them must stay with the SPA: a bank-initiated
 * (PIS) payment ends in an authorization only a person can give. Neither the handler nor the AD can
 * be changed for that without changing what the UI does, so the MCP-only configuration says it.</p>
 *
 * <ul>
 *   <li>{@code hidden} — action names (declared handler actions or AD button names). They are left
 *       out of {@code etendo_schema view:"actions"} and {@code etendo_discover}, and {@code etendo_action}
 *       refuses them with 405 even though the handler would serve them.</li>
 *   <li>{@code redirect} — {@code button → action}: a button the agent should not use, and the
 *       action to use instead. The button stays listed (the catalogue is complete, IMP-21) carrying
 *       {@code useInstead}; {@code etendo_action} on it is refused with that hint.</li>
 *   <li>{@code values} — {@code button → [values]}: the only values of a list-backed button
 *       ({@code actionValues}) the agent is offered — the ones the UI's own button sends. The view
 *       lists only those, and {@code etendo_action} refuses another {@code docAction}/{@code action}
 *       with 422. Sending none keeps the button's own default, as the UI does.</li>
 *   <li>{@code reason} — mandatory; it reaches the agent in the refusal.</li>
 *   <li>{@code redirectReason} — optional: the reason a redirected button gives, when it is not the
 *       one the hidden actions give. Defaults to {@code reason}.</li>
 *   <li>{@code REPLACE}, entity level. Fails closed: an unusable {@code MCP_CONFIG} refuses every
 *       action through {@code etendo_action}.</li>
 * </ul>
 */
final class McpActionsSection {

  private static final Logger log = LogManager.getLogger(McpActionsSection.class);

  /** The section name inside {@code MCP_CONFIG}. */
  static final String NAME = "actions";

  static final String KEY_HIDDEN = "hidden";
  static final String KEY_REDIRECT = "redirect";
  static final String KEY_REASON = "reason";
  static final String KEY_REDIRECT_REASON = "redirectReason";
  static final String KEY_VALUES = "values";

  private static final Set<String> ALLOWED_KEYS =
      Set.of(KEY_HIDDEN, KEY_REDIRECT, KEY_REASON, KEY_REDIRECT_REASON, KEY_VALUES);

  /** The reason an agent reads when the configuration itself cannot be trusted. */
  static final String UNUSABLE_REASON = "its MCP configuration is invalid";

  private McpActionsSection() {
  }

  /**
   * The registrable declaration of this section.
   *
   * @return the section, with its allowed keys, {@code REPLACE} merge and validator
   */
  static McpConfigSection declaration() {
    return McpConfigSection.of(NAME, ALLOWED_KEYS, McpConfigSection.Merge.REPLACE,
        McpActionsSection::validate);
  }

  /**
   * Validate an {@code actions} body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    if (!body.has(KEY_HIDDEN) && !body.has(KEY_REDIRECT) && !body.has(KEY_VALUES)) {
      problems.add("names no action — declare " + KEY_HIDDEN + ", " + KEY_REDIRECT + " or "
          + KEY_VALUES + ", or remove the section");
    }
    validateValues(body, problems);
    validateHidden(body, problems);
    validateRedirect(body, problems);
    if (body.has(KEY_REDIRECT_REASON)
        && !(body.opt(KEY_REDIRECT_REASON) instanceof String
            && StringUtils.isNotBlank(body.optString(KEY_REDIRECT_REASON)))) {
      problems.add(KEY_REDIRECT_REASON + " must be a non-blank string when present");
    }
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required, so every hidden or redirected action is auditable");
    }
    return problems;
  }

  private static void validateHidden(JSONObject body, List<String> problems) {
    if (!body.has(KEY_HIDDEN)) {
      return;
    }
    JSONArray hidden = body.optJSONArray(KEY_HIDDEN);
    if (hidden == null || hidden.length() == 0) {
      problems.add(KEY_HIDDEN + " must be a non-empty array of action names");
      return;
    }
    for (int i = 0; i < hidden.length(); i++) {
      Object name = hidden.opt(i);
      if (!(name instanceof String) || StringUtils.isBlank((String) name)) {
        problems.add(KEY_HIDDEN + "[" + i + "] must be a non-blank action name");
      }
    }
  }

  private static void validateRedirect(JSONObject body, List<String> problems) {
    if (!body.has(KEY_REDIRECT)) {
      return;
    }
    JSONObject redirect = body.optJSONObject(KEY_REDIRECT);
    if (redirect == null || redirect.length() == 0) {
      problems.add(KEY_REDIRECT + " must be a non-empty object {button: action}");
      return;
    }
    for (Iterator<?> it = redirect.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      Object target = redirect.opt(key);
      if (!(target instanceof String) || StringUtils.isBlank((String) target)) {
        problems.add(KEY_REDIRECT + "." + key + " must name the action to use instead");
      }
    }
  }

  private static void validateValues(JSONObject body, List<String> problems) {
    if (!body.has(KEY_VALUES)) {
      return;
    }
    JSONObject values = body.optJSONObject(KEY_VALUES);
    if (values == null || values.length() == 0) {
      problems.add(KEY_VALUES + " must be a non-empty object {button: [values]}");
      return;
    }
    for (Iterator<?> it = values.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      JSONArray allowed = values.optJSONArray(key);
      if (allowed == null || allowed.length() == 0) {
        problems.add(KEY_VALUES + "." + key + " must be a non-empty array of values");
        continue;
      }
      for (int i = 0; i < allowed.length(); i++) {
        Object value = allowed.opt(i);
        if (!(value instanceof String) || StringUtils.isBlank((String) value)) {
          problems.add(KEY_VALUES + "." + key + "[" + i + "] must be a non-blank value");
        }
      }
    }
  }

  /** One entity's resolved {@code actions} configuration. */
  static final class View {
    private static final View NONE =
        new View(Collections.emptySet(), Collections.emptyMap(), null, null, false);

    private final Set<String> hidden;
    private final Map<String, String> redirect;
    private final Map<String, Set<String>> values;
    private final String reason;
    private final String redirectReason;
    private final boolean unusable;

    private View(Set<String> hidden, Map<String, String> redirect, String reason,
        String redirectReason, boolean unusable) {
      this(hidden, redirect, Collections.emptyMap(), reason, redirectReason, unusable);
    }

    private View(Set<String> hidden, Map<String, String> redirect,
        Map<String, Set<String>> values, String reason, String redirectReason, boolean unusable) {
      this.hidden = hidden;
      this.redirect = redirect;
      this.values = values;
      this.reason = reason;
      this.redirectReason = redirectReason;
      this.unusable = unusable;
    }

    /**
     * @return the only values of {@code action} the agent is offered, or {@code null} when the
     *         configuration does not narrow it
     */
    Set<String> allowedValuesOf(String action) {
      return action == null ? null : values.get(action);
    }

    /** @return the reason a redirected button gives: {@code redirectReason}, else {@code reason} */
    String getRedirectReason() {
      return redirectReason != null ? redirectReason : reason;
    }

    /** @return whether the MCP must not offer or run {@code action} */
    boolean isHidden(String action) {
      return action != null && hidden.contains(action);
    }

    /** @return the action to use instead of {@code action}, or {@code null} */
    String redirectOf(String action) {
      return action == null ? null : redirect.get(action);
    }

    /** @return the declared reason, or {@code null} when nothing is configured */
    String getReason() {
      return reason;
    }

    /** @return whether the entity's configuration is unusable, which refuses every action */
    boolean isUnusable() {
      return unusable;
    }
  }

  /**
   * Resolve one entity's {@code actions} configuration.
   *
   * @param entity the SchemaForge entity, may be {@code null}
   * @return the view, never {@code null}; empty when nothing is configured
   */
  static View forEntity(SFEntity entity) {
    if (entity == null) {
      return View.NONE;
    }
    McpEntityConfig.Resolved resolved = McpEntityConfig.forEntity(entity);
    if (!resolved.isUsable()) {
      log.warn("Every MCP action refused on entity '{}' ({}): its MCP_CONFIG is unusable — {}",
          entity.getName(), entity.getId(), resolved.describeProblems());
      return new View(Collections.emptySet(), Collections.emptyMap(), UNUSABLE_REASON, null,
          true);
    }
    JSONObject body = resolved.section(NAME).orElse(null);
    if (body == null) {
      return View.NONE;
    }
    Set<String> hidden = new LinkedHashSet<>();
    JSONArray names = body.optJSONArray(KEY_HIDDEN);
    for (int i = 0; names != null && i < names.length(); i++) {
      hidden.add(names.optString(i));
    }
    Map<String, String> redirect = new LinkedHashMap<>();
    JSONObject targets = body.optJSONObject(KEY_REDIRECT);
    if (targets != null) {
      for (Iterator<?> it = targets.keys(); it.hasNext();) {
        String key = String.valueOf(it.next());
        redirect.put(key, targets.optString(key));
      }
    }
    Map<String, Set<String>> values = new LinkedHashMap<>();
    JSONObject narrowed = body.optJSONObject(KEY_VALUES);
    if (narrowed != null) {
      for (Iterator<?> it = narrowed.keys(); it.hasNext();) {
        String key = String.valueOf(it.next());
        Set<String> allowed = new LinkedHashSet<>();
        JSONArray list = narrowed.optJSONArray(key);
        for (int i = 0; list != null && i < list.length(); i++) {
          allowed.add(list.optString(i));
        }
        values.put(key, Collections.unmodifiableSet(allowed));
      }
    }
    return new View(hidden, redirect, values, StringUtils.trim(body.optString(KEY_REASON, "")),
        StringUtils.trimToNull(body.optString(KEY_REDIRECT_REASON, null)), false);
  }
}
