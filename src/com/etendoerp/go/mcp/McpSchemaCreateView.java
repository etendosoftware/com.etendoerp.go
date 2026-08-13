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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pure (DAL-free) create-shaped projection for {@code neo_schema} (IMP-12).
 *
 * <p>A compliance-heavy window returns ~157 fields / 62 kB, which on 2026-08-06 did not merely waste
 * the agent's budget — the call <b>failed outright</b> against the client token limit, making
 * {@code neo_schema} unusable for exactly the widest windows. Yet to create a record the agent must
 * decide values for a handful of fields; the rest are audit columns, computed totals, compliance
 * flags and buttons.
 *
 * <p>Two independent projections, both additive — omitting them leaves the response untouched:
 * <ul>
 *   <li><b>{@code view:"create"}</b> — only fields the agent may actually supply, in two groups:
 *       {@code required} (mandatory <i>and</i> unresolved by anything else) and {@code optional} (the
 *       rest, so an agent can still enrich the record). Both are filtered through
 *       {@link McpSchemaFieldBuilder#isAgentSuppliable} first. Membership of {@code required} is
 *       cross-checked against {@code neo_defaults} — see {@link #resolvedDefaultNames}.</li>
 *   <li><b>{@code fields:[…]}</b> — an explicit whitelist of descriptor names, for an agent that
 *       already knows what it wants and needs the shape of those fields only.</li>
 * </ul>
 *
 * <p><b>Why {@code businessCritical} is intersected, not unioned.</b> IMP-12's original
 * specification defined the view as {@code userRequired ∪ businessCritical ∪ FK-with-selector}. On
 * {@code sales-invoice/header} that admits 18 of 157 fields — but three of them
 * ({@code DocumentNo}, {@code GrandTotal}, {@code OutstandingAmt}) are {@code readOnly} and
 * {@code businessCritical}, so the rule contradicted its own "every field in it is one the agent must
 * provide". {@code businessCritical} answers a different question — "must I confirm this value with
 * the user before writing?" — and is orthogonal to "may I send it". It is kept as a flag on the
 * emitted fields, and gates nothing.
 *
 * <p>This class only reshapes an already-built field array (the one
 * {@link McpSchemaFieldBuilder#buildSchemaFieldsArray} produced, after labels and preconditions are
 * overlaid). No extra DAL access, no new query.
 */
final class McpSchemaCreateView {

  private McpSchemaCreateView() {
  }

  /** The {@code view} parameter itself is declared by {@link McpActionsView#PARAM_VIEW}. */
  static final String PARAM_FIELDS = "fields";
  static final String VIEW_CREATE = "create";

  private static final String KEY_NAME = "name";
  private static final String KEY_REQUIRED = "required";
  private static final String KEY_OPTIONAL = "optional";

  /**
   * Keys dropped from every emitted descriptor, because none of them tells the agent anything the
   * view has not already told it.
   *
   * <p>{@code visibility} is always {@code editable}, {@code readOnly} always {@code false} and
   * {@code userRequired} is exactly the group the field is in — repeating them 24 times is the
   * verbosity this item exists to remove. {@code required} is the raw AD mandatory flag, which
   * {@link McpSchemaFieldBuilder#addVisibility} already corrected into {@code userRequired}; leaving
   * it in would contradict the grouping (an AD-mandatory field with a default sits under
   * {@code optional} while carrying {@code required: true}).
   *
   * <p>{@code defaultExpression} / {@code defaultSource} go too, and that one is a size decision as
   * much as a clarity one: on {@code sales-invoice/header} two AEAT compliance columns carry 806 and
   * 604 characters of raw {@code @SQL=…} between them. An agent cannot evaluate an AD default
   * expression — {@code neo_defaults} resolves it server-side, and the hint says so.
   */
  private static final Set<String> REDUNDANT_KEYS = Set.of(
      "visibility", "readOnly", McpSchemaFieldBuilder.KEY_USER_REQUIRED, KEY_REQUIRED,
      McpSchemaFieldBuilder.KEY_DEFAULT_EXPRESSION, McpSchemaFieldBuilder.KEY_DEFAULT_SOURCE);

  static final String CREATE_HINT =
      "Every field listed here is one you may send to neo_create. Fields under 'required' MUST be "
      + "provided — they are mandatory and neither an AD default nor neo_defaults resolves a value "
      + "for them, so nothing else supplies them. Fields under 'optional' are accepted but not "
      + "needed; those carrying serverDefaulted=true are mandatory in Etendo but the server already "
      + "has a value for them, so do not ask the user — send one only to override it. "
      + "Anything omitted from this view is either auto-derived, read-only, or excluded — 'visibility' "
      + "is the authoritative field for why (editable/readOnly/system/discarded), 'readOnly' agrees "
      + "with it and is a shorthand of the same signal, so trust either one alone. Do not send an "
      + "omitted field to neo_create. If the value you need lives on a field that was omitted for "
      + "this reason, that value may still be writable elsewhere: call neo_schema again without "
      + "view:\"create\" (or on a sibling entity of this spec) to see the full field list and each "
      + "field's visibility — do not assume the value is unreachable just because this view left it "
      + "out. Fields with hasSelector=true take a record id: resolve it with neo_selectors, or pass "
      + "the display name and let the server resolve it. Fields with businessCritical=true carry core "
      + "business data — you MUST confirm those values with the user before creating the record. "
      + "Every field here is editable and writable, so visibility/readOnly/userRequired are omitted — "
      + "the group already says it. Default values are omitted too: call neo_defaults to get the "
      + "values the server will fill in, already resolved. For the full descriptor of any field "
      + "listed here, call neo_schema again with fields:[\"<name>\"].";

  /** @return {@code true} when {@code view} requests the create-shaped projection. */
  static boolean isCreateView(String view) {
    return VIEW_CREATE.equalsIgnoreCase(view);
  }

  private static final String KEY_SERVER_DEFAULTED = "serverDefaulted";
  private static final String KEY_DEFAULTS = "defaults";
  private static final String KEY_METADATA = "metadata";
  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  /**
   * The field names {@code neo_defaults} resolves a usable value for, read off its response body.
   *
   * <p><b>Why this cannot be derived from the schema.</b> {@link McpSchemaFieldBuilder#addVisibility}
   * asks {@code AD_Column.DefaultValue} whether the server will supply a field, and that is an
   * incomplete proxy. Measured on {@code sales-invoice/header}: four of the six fields the static rule
   * calls {@code userRequired} — {@code transactionDocument}, {@code paymentMethod},
   * {@code paymentTerms}, {@code priceList} — have no {@code AD_Column.DefaultValue} at all, yet
   * {@code NeoDefaultsService} resolves each of them from session preferences, the business partner's
   * own configuration, or an AD callout. Claiming the agent must provide them is the same
   * aspirational-hint defect IMP-11 was about, so the authoritative source has to be asked directly.
   *
   * <p>An empty string does <b>not</b> count. {@code partnerAddress} comes back as {@code ""}: the
   * server knows the field exists and could not resolve it, which is precisely the case where the
   * agent must still supply a value.
   *
   * <p><b>The body is nested.</b> {@code resolveDefaults} returns
   * {@code {defaults:{…}, metadata:{…}}}; the flat map an agent sees over MCP is what
   * {@code neoResponseToMcpResult} and {@link McpDefaultsView} produce downstream. Reading the top
   * level directly is the bug this method shipped with on 2026-08-06 — it collected the single literal
   * key {@code "defaults"}, which matches no field, so the cross-check silently did nothing. The
   * fallback to the body itself keeps it working if an {@code afterHandle} hook returns a flat map.
   *
   * @param defaultsBody the body of {@code NeoDefaultsService.resolveDefaults}, or {@code null} when
   *     defaults could not be resolved — in which case the static rule stands unchanged
   */
  static Set<String> resolvedDefaultNames(JSONObject defaultsBody) {
    Set<String> resolved = new HashSet<>();
    if (defaultsBody == null) {
      return resolved;
    }
    JSONObject values = defaultsBody.optJSONObject(KEY_DEFAULTS);
    if (values == null) {
      values = defaultsBody;
    }
    Iterator<?> keys = values.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      boolean skip = KEY_METADATA.equals(key) || key.endsWith(IDENTIFIER_SUFFIX)
          || McpDefaultsView.isUnresolvedValue(values.opt(key));
      if (!skip) {
        resolved.add(key);
      }
    }
    return resolved;
  }

  /**
   * Builds the {@code neo_schema({view:"create"})} response:
   * {@code {spec, entity, required[], optional[], requiredCount, optionalCount, hint}}.
   *
   * <p>{@code table}, {@code methods} and {@code namedFilters} are deliberately dropped — none of
   * them is needed to build a create payload, and every key omitted is budget the agent keeps.
   *
   * @param fields the array built by {@link McpSchemaFieldBuilder#buildSchemaFieldsArray}, with the
   *     IMP-1 labels and the precondition requirements already applied
   * @param serverResolved names {@code neo_defaults} resolves a value for
   *     ({@link #resolvedDefaultNames}). A field in this set is demoted to {@code optional} and
   *     flagged {@code serverDefaulted}, however mandatory AD says it is — otherwise {@code required}
   *     would tell the agent to interrogate the user for a value the server already has. Pass an
   *     empty set to fall back to the static {@code userRequired} rule alone.
   */
  static JSONObject buildResponse(String specName, String entityName, JSONArray fields,
      Set<String> serverResolved) throws JSONException {
    JSONArray required = new JSONArray();
    JSONArray optional = new JSONArray();
    Set<String> resolved = serverResolved == null ? Set.of() : serverResolved;
    if (fields != null) {
      for (int i = 0; i < fields.length(); i++) {
        JSONObject field = fields.optJSONObject(i);
        // Buttons are actions, not payload — they belong to view:"actions" (IMP-6).
        if (field == null || McpActionsView.TYPE_BUTTON.equals(field.optString("type", null))
            || !McpSchemaFieldBuilder.isAgentSuppliable(field)) {
          continue;
        }
        JSONObject emitted = slim(field);
        // Guard the null name explicitly: Set.of() throws on contains(null).
        String name = field.optString(KEY_NAME, null);
        if (name != null && resolved.contains(name)) {
          // Distinguish "optional because nobody needs it" from "optional because the server fills
          // it" — the second is the one the agent must not ask the user about.
          emitted.put(KEY_SERVER_DEFAULTED, true);
          optional.put(emitted);
        } else if (field.optBoolean(McpSchemaFieldBuilder.KEY_USER_REQUIRED, false)) {
          required.put(emitted);
        } else {
          optional.put(emitted);
        }
      }
    }
    JSONObject response = new JSONObject();
    response.put("spec", specName);
    response.put("entity", entityName);
    response.put(KEY_REQUIRED, required);
    response.put(KEY_OPTIONAL, optional);
    response.put("requiredCount", required.length());
    response.put("optionalCount", optional.length());
    response.put("hint", CREATE_HINT);
    return response;
  }

  /**
   * Copies a descriptor without the {@link #REDUNDANT_KEYS}. The original is left untouched — the
   * default (no-view) response is built from the same array and must stay byte-for-byte unchanged.
   */
  private static JSONObject slim(JSONObject field) throws JSONException {
    JSONObject copy = new JSONObject();
    Iterator<?> keys = field.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (!REDUNDANT_KEYS.contains(key)) {
        copy.put(key, field.get(key));
      }
    }
    return copy;
  }

  /**
   * Filters a schema field array down to the descriptors whose {@code name} is in {@code requested}.
   *
   * <p>Distinct from {@link McpFieldProjection#apply}, which trims <i>record rows</i> inside a
   * {@code response.data[]} envelope. A schema payload has no such envelope, so that method would
   * silently no-op here.
   *
   * @return a new array in the original order; a {@code null}/empty {@code requested} set returns
   *     {@code fields} unchanged, so the caller stays backward compatible
   */
  static JSONArray applyFieldWhitelist(JSONArray fields, Set<String> requested)
      throws JSONException {
    if (fields == null || requested == null || requested.isEmpty()) {
      return fields;
    }
    JSONArray filtered = new JSONArray();
    for (int i = 0; i < fields.length(); i++) {
      JSONObject field = fields.optJSONObject(i);
      if (field != null && requested.contains(field.optString(KEY_NAME, null))) {
        filtered.put(field);
      }
    }
    return filtered;
  }

  /**
   * Names in {@code requested} that matched no descriptor. Reported back as {@code unknownFields} so
   * a typo surfaces instead of a field silently vanishing — the same defect IMP-18 tracks for
   * {@code neo_list}'s projection, avoided here at birth rather than fixed later.
   */
  static JSONArray unknownFields(JSONArray fields, Set<String> requested) {
    JSONArray unknown = new JSONArray();
    if (requested == null || requested.isEmpty()) {
      return unknown;
    }
    Set<String> known = new HashSet<>();
    if (fields != null) {
      for (int i = 0; i < fields.length(); i++) {
        JSONObject field = fields.optJSONObject(i);
        if (field != null) {
          String name = field.optString(KEY_NAME, null);
          if (name != null) {
            known.add(name);
          }
        }
      }
    }
    for (String name : requested) {
      if (!known.contains(name)) {
        unknown.put(name);
      }
    }
    return unknown;
  }
}
