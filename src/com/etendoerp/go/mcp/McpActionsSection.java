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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

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
 *     "redirectReason": "the classic Add Payment button is not the Etendo payment flow",
 *     "reasons":  { "posted": "a blind toggle; use the post and unpost actions" }
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
 *   <li>{@code reasons} — optional {@code action → reason} (ETP-5692): the reason ONE hidden or
 *       redirected action gives, in place of the section-wide {@code reason} /
 *       {@code redirectReason}. For a section that hides actions for unrelated causes — the invoice
 *       headers hide the PIS actions (SCA) and the {@code posted} toggle (use post / unpost) — so
 *       an agent refused one of them is not handed the other's explanation. Each key must name an
 *       action of {@code hidden} or {@code redirect}.</li>
 *   <li>{@code availableWhen} — optional {@code action → {property: [values]}} (ETP-5714): the
 *       record states in which the action applies (every property must match; a value prefixed
 *       with {@code !} excludes it). Rendered in {@code etendo_schema view:"actions"} and, given a
 *       record id, judged there as {@code availableNow}. <b>Advisory</b>: it never refuses a
 *       call. Needs no {@code reason}, since nothing is withheld.</li>
 *   <li>{@code valuesByStatus} — optional {@code action → {property, values:{status:[values]}}}
 *       (ETP-5714): for a list-backed button such as {@code documentAction}, which of its values
 *       apply in each status. Advisory, like {@code availableWhen}.</li>
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
  static final String KEY_REASONS = "reasons";
  static final String KEY_AVAILABLE_WHEN = NeoActionAvailability.KEY_AVAILABLE_WHEN;
  static final String KEY_VALUES_BY_STATUS = NeoActionAvailability.KEY_VALUES_BY_STATUS;

  private static final Set<String> ALLOWED_KEYS =
      Set.of(KEY_HIDDEN, KEY_REDIRECT, KEY_REASON, KEY_REDIRECT_REASON, KEY_VALUES, KEY_REASONS,
          KEY_AVAILABLE_WHEN, KEY_VALUES_BY_STATUS);

  /**
   * The one instance of this section (ETP-5639). {@link #declaration()} used to build a new one on
   * every call, so two first callers registering concurrently handed the registry two different
   * objects under one name, and the loser's call failed as a duplicate.
   */
  private static final McpConfigSection DECLARATION = McpConfigSection.of(NAME, ALLOWED_KEYS,
      McpConfigSection.Merge.REPLACE, McpActionsSection::validate);

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
    return DECLARATION;
  }

  /**
   * Validate an {@code actions} body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    boolean withholds = body.has(KEY_HIDDEN) || body.has(KEY_REDIRECT) || body.has(KEY_VALUES);
    boolean describes = body.has(KEY_AVAILABLE_WHEN) || body.has(KEY_VALUES_BY_STATUS);
    if (!withholds && !describes) {
      problems.add("names no action — declare " + KEY_HIDDEN + ", " + KEY_REDIRECT + ", "
          + KEY_VALUES + ", " + KEY_AVAILABLE_WHEN + " or " + KEY_VALUES_BY_STATUS
          + ", or remove the section");
    }
    validateAvailability(body, problems);
    validateValues(body, problems);
    validateHidden(body, problems);
    validateRedirect(body, problems);
    validateReasons(body, problems);
    if (body.has(KEY_REDIRECT_REASON)
        && !(body.opt(KEY_REDIRECT_REASON) instanceof String
            && StringUtils.isNotBlank(body.optString(KEY_REDIRECT_REASON)))) {
      problems.add(KEY_REDIRECT_REASON + " must be a non-blank string when present");
    }
    // A section that only describes when an action applies withholds nothing, so it has nothing
    // to justify (ETP-5714).
    if ((!describes || withholds) && StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required, so every hidden or redirected action is auditable");
    }
    return problems;
  }

  /** {@code availableWhen} / {@code valuesByStatus}: objects keyed by action, well-formed each. */
  private static void validateAvailability(JSONObject body, List<String> problems) {
    JSONObject when = objectOrNull(body, KEY_AVAILABLE_WHEN, problems);
    JSONObject byStatus = objectOrNull(body, KEY_VALUES_BY_STATUS, problems);
    Set<String> actions = new LinkedHashSet<>();
    addKeys(actions, when);
    addKeys(actions, byStatus);
    for (String action : actions) {
      validateAvailabilityOf(action, entryOf(when, action, KEY_AVAILABLE_WHEN, problems),
          entryOf(byStatus, action, KEY_VALUES_BY_STATUS, problems), problems);
    }
  }

  /** The object declared for {@code action} in {@code section}, or {@code null} (reported if malformed). */
  private static JSONObject entryOf(JSONObject section, String action, String sectionName,
      List<String> problems) {
    if (section == null || !section.has(action)) {
      return null;
    }
    JSONObject entry = section.optJSONObject(action);
    if (entry == null) {
      problems.add(sectionName + "." + action + " must be an object");
    }
    return entry;
  }

  private static void validateAvailabilityOf(String action, JSONObject when, JSONObject byStatus,
      List<String> problems) {
    for (String problem : NeoActionAvailability.problemsOf(when, byStatus)) {
      problems.add(action + ": " + problem);
    }
  }

  private static JSONObject objectOrNull(JSONObject body, String key, List<String> problems) {
    if (!body.has(key)) {
      return null;
    }
    JSONObject value = body.optJSONObject(key);
    if (value == null || value.length() == 0) {
      problems.add(key + " must be a non-empty object keyed by action name");
      return null;
    }
    return value;
  }

  private static void addKeys(Set<String> into, JSONObject from) {
    for (Iterator<?> it = from == null ? Collections.emptyIterator() : from.keys(); it.hasNext();) {
      into.add(String.valueOf(it.next()));
    }
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

  /**
   * {@code reasons}: a non-empty object whose every key is an action the section hides or
   * redirects, and whose every value is a non-blank reason. A reason for an action the section
   * does not refuse would never be read, which is a typo rather than a choice.
   */
  private static void validateReasons(JSONObject body, List<String> problems) {
    if (!body.has(KEY_REASONS)) {
      return;
    }
    JSONObject reasons = body.optJSONObject(KEY_REASONS);
    if (reasons == null || reasons.length() == 0) {
      problems.add(KEY_REASONS + " must be a non-empty object {action: reason}");
      return;
    }
    Set<String> refused = parseHidden(body);
    refused.addAll(parseRedirect(body).keySet());
    for (Iterator<?> it = reasons.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      Object reason = reasons.opt(key);
      if (!(reason instanceof String) || StringUtils.isBlank((String) reason)) {
        problems.add(KEY_REASONS + "." + key + " must be a non-blank reason");
      } else if (!refused.contains(key)) {
        problems.add(KEY_REASONS + "." + key + " names no action of " + KEY_HIDDEN + " or "
            + KEY_REDIRECT);
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
    /** {@code action → reason} overrides (ETP-5692); empty when the section declares none. */
    private final Map<String, String> reasons;
    /** {@code action → availability} (ETP-5714); empty when the section declares none. */
    private final Map<String, NeoActionAvailability> availability;

    private View(Set<String> hidden, Map<String, String> redirect, String reason,
        String redirectReason, boolean unusable) {
      this(hidden, redirect, Collections.emptyMap(), reason, redirectReason, unusable,
          Collections.emptyMap(), Collections.emptyMap());
    }

    @SuppressWarnings("java:S107") // the section's resolved parts; a holder would only rename them
    private View(Set<String> hidden, Map<String, String> redirect,
        Map<String, Set<String>> values, String reason, String redirectReason, boolean unusable,
        Map<String, String> reasons, Map<String, NeoActionAvailability> availability) {
      this.hidden = hidden;
      this.redirect = redirect;
      this.values = values;
      this.reason = reason;
      this.redirectReason = redirectReason;
      this.unusable = unusable;
      this.reasons = reasons;
      this.availability = availability;
    }

    /**
     * The availability this section declares for {@code action} (ETP-5714).
     *
     * @param action the action, under the name it was matched by
     * @return the declaration, or {@code null} when the section declares none for it
     */
    NeoActionAvailability availabilityOf(String action) {
      return action == null ? null : availability.get(action);
    }

    /**
     * @return the only values of {@code action} the agent is offered, or {@code null} when the
     *         configuration does not narrow it
     */
    Set<String> allowedValuesOf(String action) {
      return action == null ? null : values.get(action);
    }

    /**
     * The reason {@code action} is refused (ETP-5692): its own entry in {@code reasons}, else the
     * section-wide {@code reason}.
     *
     * @param action the refused action, under the name it was matched by
     * @return the reason, never {@code null} when the section is configured
     */
    String reasonOf(String action) {
      String own = action == null ? null : reasons.get(action);
      return own != null ? own : reason;
    }

    /**
     * The reason the redirected {@code action} gives (ETP-5692): its own entry in {@code reasons},
     * else {@link #getRedirectReason()}.
     *
     * @param action the redirected button, under the name it was matched by
     * @return the reason
     */
    String redirectReasonOf(String action) {
      String own = action == null ? null : reasons.get(action);
      return own != null ? own : getRedirectReason();
    }

    /** @return the reason a redirected button gives: {@code redirectReason}, else {@code reason} */
    String getRedirectReason() {
      return redirectReason != null ? redirectReason : reason;
    }

    /**
     * The hidden or redirected name this section spells differently from {@code action}
     * (ETP-5714): the same letters once case and separators are ignored, so
     * {@code cancelAndReplace} finds {@code Cancelandreplace} and {@code rmPickFromReceipt} finds
     * {@code RM_Pickfromreceipt}. Without it an agent that guessed the camelCase name got a bare
     * "Action not found" instead of the configured reason.
     *
     * @param action the action as typed
     * @return the configured name, or {@code null} when none matches
     */
    String configuredNameLike(String action) {
      String wanted = normalized(action);
      if (wanted.isEmpty()) {
        return null;
      }
      for (String name : hidden) {
        if (normalized(name).equals(wanted)) {
          return name;
        }
      }
      for (String name : redirect.keySet()) {
        if (normalized(name).equals(wanted)) {
          return name;
        }
      }
      return null;
    }

    /** Lower case with everything but letters and digits removed: the spelling-insensitive key. */
    private static String normalized(String name) {
      return name == null ? "" : name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
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
    return new View(parseHidden(body), parseRedirect(body), parseValues(body),
        StringUtils.trim(body.optString(KEY_REASON, "")),
        StringUtils.trimToNull(body.optString(KEY_REDIRECT_REASON, null)), false,
        parseReasons(body), parseAvailability(body));
  }

  /** {@code availableWhen} + {@code valuesByStatus}, merged per action; empty when absent. */
  private static Map<String, NeoActionAvailability> parseAvailability(JSONObject body) {
    JSONObject when = body.optJSONObject(KEY_AVAILABLE_WHEN);
    JSONObject byStatus = body.optJSONObject(KEY_VALUES_BY_STATUS);
    Set<String> actions = new LinkedHashSet<>();
    addKeys(actions, when);
    addKeys(actions, byStatus);
    Map<String, NeoActionAvailability> out = new LinkedHashMap<>();
    for (String action : actions) {
      out.put(action, NeoActionAvailability.fromJson(
          when == null ? null : when.optJSONObject(action),
          byStatus == null ? null : byStatus.optJSONObject(action)));
    }
    return Collections.unmodifiableMap(out);
  }

  /** {@code hidden}: the action names, in declaration order; empty when absent. */
  private static Set<String> parseHidden(JSONObject body) {
    Set<String> hidden = new LinkedHashSet<>();
    JSONArray names = body.optJSONArray(KEY_HIDDEN);
    for (int i = 0; names != null && i < names.length(); i++) {
      hidden.add(names.optString(i));
    }
    return hidden;
  }

  /** {@code redirect}: {@code button → action}, in declaration order; empty when absent. */
  private static Map<String, String> parseRedirect(JSONObject body) {
    Map<String, String> redirect = new LinkedHashMap<>();
    JSONObject targets = body.optJSONObject(KEY_REDIRECT);
    if (targets == null) {
      return redirect;
    }
    for (Iterator<?> it = targets.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      redirect.put(key, targets.optString(key));
    }
    return redirect;
  }

  /** {@code values}: {@code button → allowed values}, each set unmodifiable; empty when absent. */
  private static Map<String, Set<String>> parseValues(JSONObject body) {
    Map<String, Set<String>> values = new LinkedHashMap<>();
    JSONObject narrowed = body.optJSONObject(KEY_VALUES);
    if (narrowed == null) {
      return values;
    }
    for (Iterator<?> it = narrowed.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      values.put(key, Collections.unmodifiableSet(parseAllowedValues(narrowed.optJSONArray(key))));
    }
    return values;
  }

  private static Set<String> parseAllowedValues(JSONArray list) {
    Set<String> allowed = new LinkedHashSet<>();
    for (int i = 0; list != null && i < list.length(); i++) {
      allowed.add(list.optString(i));
    }
    return allowed;
  }

  /**
   * {@code reasons} (ETP-5692): {@code action → reason}, trimmed, blank entries dropped;
   * unmodifiable, empty when absent.
   */
  private static Map<String, String> parseReasons(JSONObject body) {
    Map<String, String> reasons = new LinkedHashMap<>();
    JSONObject ownReasons = body.optJSONObject(KEY_REASONS);
    if (ownReasons != null) {
      for (Iterator<?> it = ownReasons.keys(); it.hasNext();) {
        String key = String.valueOf(it.next());
        String reason = StringUtils.trimToNull(ownReasons.optString(key, null));
        if (reason != null) {
          reasons.put(key, reason);
        }
      }
    }
    return Collections.unmodifiableMap(reasons);
  }
}
