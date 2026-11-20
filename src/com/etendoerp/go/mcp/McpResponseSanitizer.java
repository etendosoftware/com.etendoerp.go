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
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.ClassicMessageRemap;
import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;

/**
 * Removes keys that an MCP client may not receive, from the body of a tool result (ETP-5306).
 *
 * <p><b>Why this exists.</b> {@code $ref} is a reserved key inside Google Gemini's
 * {@code function_response.response}: it means "a pointer to an attached part, resolvable by
 * {@code display_name}". Openbravo's {@code DataToJsonConverter#toJsonObject} puts it on
 * <em>every</em> serialised record ({@code JsonConstants.REF}, line 169 of that class), so every
 * row of a {@code etendo_list} / {@code etendo_get} carried one. Gemini tried to resolve the pointer,
 * found no such part, and rejected the <em>whole</em> request with HTTP 400 {@code INVALID_ARGUMENT}
 * — "The referenced name {@code BusinessPartner/BC8D…} in function_response.response does not match
 * to a display_name in the function_response.parts". The failure is on the tool <em>result</em>, so
 * no prompt change and no retry can work around it: the Etendo GO MCP server was unusable with any
 * Gemini model as soon as the agent read a record.
 *
 * <p><b>What is and is not stripped.</b> Only the key spelled exactly {@code $ref} is removed.
 * A key that merely <em>contains</em> a {@code $} is harmless and must be kept — the FK identifier
 * columns are all spelled {@code xxx$_identifier}, and they were verified to pass. Likewise a
 * <em>value</em> of the form {@code "$ref:<opId>"} (the {@code etendo_batch} placeholder,
 * {@link com.etendoerp.go.schemaforge.BatchService#REF_PREFIX}) is untouched: this class only ever
 * looks at key names.
 *
 * <p><b>Why removing it is lossless.</b> {@code encodeReference} builds the value as
 * {@code entityName + "/" + id}, and both halves are already present on the same row as
 * {@code _entityName} and {@code id}. The construction rule is now declared once — in
 * {@code etendo_schema}'s hint and in the {@code docs} preamble — instead of being paid for on every
 * row of every response, which is also why this is an Agent Context Economy win, not just a fix.
 *
 * <p><b>Accounting data (ETP-5684).</b> For a role without {@code showAccountingFields}, the
 * accounting properties ({@code NeoAccountingAccessPolicy#isAccountingProperty}: the accounting
 * status and the posting button) are removed too, at any depth, so no tool — read, write echo,
 * batch, search, resource — hands them to the agent. The REST API keeps them on purpose: the SPA
 * derives the read-only state of a posted document from {@code posted}; declared in
 * {@code neo-headless.md} §4.12.9.</p>
 *
 * <p><b>MCP surface only.</b> The stripping happens in the MCP content wrappers
 * ({@code McpToolRouter#wrapAsTextContent} / {@code wrapAsErrorContent}), not in the shared
 * serialiser. The NEO REST API ({@code /sws/neo/…}) has other consumers — the React SPA among them
 * — and its response contract is unchanged.
 *
 * <p><b>Why it operates on {@link JSONObject}, never on serialised text.</b> Re-parsing a rendered
 * body to strip a key would silently rewrite the numbers in it: jettison parses JSON numbers into
 * {@code Integer}/{@code Long}/{@code Double}, so a decimal wider than a {@code double} comes back
 * degraded — {@code 123456789012345678901234.5} renders as {@code 1.2345678901234569E23} after one
 * round trip, measured against jettison 1.3. Stripping in place on the object graph, before it is
 * rendered, leaves every {@code BigDecimal} exactly as the producer put it. This is why the JSON
 * overloads of the content wrappers take the object and render it here, rather than accepting a
 * rendered string and sanitising that.
 *
 * <p><b>Dash-joined identifiers (ETP-5806 CP-8b, QA Pasada 2 — persists).</b> Core's
 * {@code IdentifierProvider} builds an address-wrapping entity's own {@code _identifier} (and any
 * FK's {@code <prop>$_identifier}) by concatenating every segment with {@code " - "} even when a
 * segment is blank, so {@code etendo_list} on {@code warehouse}, {@code contacts} and
 * {@code bp-location} returned raw strings like
 * {@code "Poligono Industrial Norte, s/n - Nave 3 - - - Getafe - - España"}. A client-side fix
 * ({@code sanitizeDashJoinedIdentifier} in the SPA, {@code tools/app-shell/src/lib/locationAddress.js})
 * only cleaned up what the SPA itself renders; an MCP agent reading {@code etendo_list}/
 * {@code etendo_get} directly still saw the raw, double-separated string. {@link #strip(JSONObject)}
 * now runs the same algorithm — split on {@code " - "}, trim each segment, drop the blank ones,
 * rejoin — on every key named {@link JsonConstants#IDENTIFIER} or ending in
 * {@code "$" + JsonConstants#IDENTIFIER}, at any depth, on every MCP tool result. This is
 * structural (keyed by the generic identifier-field SHAPE every entity can carry, not by a spec or
 * table name naming {@code warehouse}/{@code contacts}/{@code bp-location} specifically), so it
 * also covers any other entity whose identifier happens to concatenate blank segments the same
 * way, without a fourth per-entity patch.
 */
final class McpResponseSanitizer {

  /**
   * The reserved key. Mirrors {@code org.openbravo.service.json.JsonConstants#REF}; spelled out
   * here rather than imported so this class states the MCP-side contract on its own terms.
   */
  static final String RESERVED_REF_KEY = "$ref";

  /** Indent factor of a result rendered for a human, the pre-IMP-53 format. */
  private static final int HUMAN_INDENT = 2;

  /**
   * Whether the tool call running on this thread asked for {@code _indentResponse:true} (IMP-53).
   * Set and restored by {@code McpToolRouter.route} around the whole call, so every JSON body that
   * call renders — result or error — follows it without each handler passing a flag along.
   */
  private static final ThreadLocal<Boolean> INDENTED = ThreadLocal.withInitial(() -> false);

  private McpResponseSanitizer() {
  }

  /**
   * Set the rendering mode for the tool call on this thread.
   *
   * @param indented {@code true} to indent JSON results for human reading
   * @return the previous mode, to hand back to {@link #restoreIndented(boolean)}
   */
  static boolean setIndented(boolean indented) {
    boolean previous = INDENTED.get();
    INDENTED.set(indented);
    return previous;
  }

  /**
   * Restore the mode {@link #setIndented(boolean)} returned; the default mode removes the value.
   *
   * @param previous the mode to restore
   */
  static void restoreIndented(boolean previous) {
    if (previous) {
      INDENTED.set(true);
    } else {
      INDENTED.remove();
    }
  }

  /**
   * Serialise a JSON body in the mode of the current tool call: compact unless the call asked for
   * {@code _indentResponse:true}. The one place an MCP JSON result becomes text.
   *
   * @param body the body to serialise, not {@code null}
   * @return the serialised body
   * @throws JSONException if the body cannot be rendered
   */
  static String serialize(JSONObject body) throws JSONException {
    return Boolean.TRUE.equals(INDENTED.get()) ? body.toString(HUMAN_INDENT) : body.toString();
  }

  /**
   * Strip the reserved keys from {@code body} in place and render it.
   *
   * <p>No indentation by default (IMP-53): pretty-printing was 41 % of {@code etendo_discover}'s
   * bytes — 76 541 against 45 051 compact — and the usual reader is an agent that pays for every
   * byte in context. A call with {@code _indentResponse:true} gets the indented form
   * ({@link #serialize}). Whitespace only: the data is unchanged either way.</p>
   *
   * @param body the tool-result body (may be {@code null})
   * @return the rendered body, or {@code "{}"} when {@code body} is {@code null}
   * @throws JSONException if the body cannot be rendered
   */
  static String render(JSONObject body) throws JSONException {
    return render(body, false);
  }

  /**
   * {@link #render(JSONObject)} for the body of an error tool result: its root is the error object
   * the client reads, so a Classic message recognised there publishes its key in a root
   * {@code messageKeys} (ETP-6158).
   *
   * @param body the error body (may be {@code null})
   * @return the rendered body, or {@code "{}"} when {@code body} is {@code null}
   * @throws JSONException if the body cannot be rendered
   */
  static String renderError(JSONObject body) throws JSONException {
    return render(body, true);
  }

  private static String render(JSONObject body, boolean errorBody) throws JSONException {
    if (body == null) {
      return "{}";
    }
    strip(body);
    // ETP-6158: Classic fiscal messages that name Classic-only UI, reworded for GO (REST does the
    // same in NeoServlet#writeResponse).
    if (errorBody) {
      ClassicMessageRemap.applyToErrorBody(body);
    } else {
      ClassicMessageRemap.applyToBody(body);
    }
    // ETP-5684: a role without showAccountingFields reads no accounting status through the MCP,
    // whatever tool produced the body. The role flag is read only when there is one to remove.
    if (containsAccountingKey(body) && !NeoAccountingAccessPolicy.currentRoleSeesAccounting()) {
      stripAccounting(body);
    }
    return serialize(body);
  }

  /**
   * Recursively remove every {@link #RESERVED_REF_KEY} entry from {@code object} and from every
   * object nested inside it, directly or through arrays. Rows nest (child entities, {@code data}
   * arrays, a handler's own envelope), so a shallow removal would leave the client rejecting the
   * response for a {@code $ref} one level down.
   *
   * @param object the object to sanitize in place; {@code null} is a no-op
   */
  static void strip(JSONObject object) throws JSONException {
    if (object == null) {
      return;
    }
    object.remove(RESERVED_REF_KEY);
    // Snapshot the key set: jettison's iterator is backed by the underlying map, and descending
    // into a value must not be done while that iterator is live.
    List<String> keys = new ArrayList<>();
    for (Iterator<?> it = object.keys(); it.hasNext();) {
      keys.add(String.valueOf(it.next()));
    }
    for (String key : keys) {
      Object value = object.get(key);
      // ETP-5806 CP-8b — an identifier key never nests further (its value is always a String),
      // so this is checked before the generic object/array descent below, not after it.
      if (isIdentifierKey(key) && value instanceof String) {
        String cleaned = sanitizeDashJoinedIdentifier((String) value);
        if (!cleaned.equals(value)) {
          object.put(key, cleaned);
        }
      } else {
        stripValue(value);
      }
    }
  }

  /**
   * Whether {@code key} names a generic identifier field — the entity's own ({@link
   * JsonConstants#IDENTIFIER}, {@code "_identifier"}) or an FK's ({@code "<prop>$_identifier"}) —
   * the two shapes {@code DataToJsonConverter} ever produces this suffix under. Structural: the
   * key's own shape, not any entity or table name (ETP-5806 CP-8b).
   */
  private static boolean isIdentifierKey(String key) {
    return JsonConstants.IDENTIFIER.equals(key)
        || key.endsWith("$" + JsonConstants.IDENTIFIER);
  }

  /** The literal segment separator {@code IdentifierProvider} joins address segments with. */
  private static final String DASH_SEPARATOR = " - ";

  /**
   * Matches a run of one or more {@code " -"} tokens followed by a trailing space — i.e. the
   * whole separator cluster left behind by N consecutive blank segments (N+1 repetitions of the
   * {@code " -"} token), collapsing to a single delimiter regardless of how many blank segments
   * produced it. A single isolated separator ({@code " - "}) is exactly one repetition, so this
   * also covers the ordinary single-separator case. Splitting on the literal {@code " - "}
   * string instead (the previous approach) under-counts matches on a run of repeated separators,
   * because consecutive {@code " - "} tokens share boundary characters and the literal split
   * finds fewer matches than there are blank segments — see ETP-5806 CP-8b follow-up.
   */
  private static final Pattern DASH_RUN_PATTERN = Pattern.compile("(?: -)+ ");

  /**
   * Drops empty segments from a {@code " - "}-joined identifier string, so a blank address
   * segment never leaves a double or dangling separator on the wire (ETP-5806 CP-8b).
   *
   * <p>Mirrors the SPA's own {@code sanitizeDashJoinedIdentifier}
   * ({@code tools/app-shell/src/lib/locationAddress.js}) exactly: split on the separator-run
   * pattern, trim each segment, filter the blank ones, rejoin with the canonical separator. A
   * string with no occurrence of the separator — the common case for most identifiers — is
   * returned unchanged without being split at all.
   *
   * @param raw the identifier value as the default service built it; never {@code null} here,
   *            since the only call site already checked {@code instanceof String}
   * @return the cleaned identifier; equal to {@code raw} when there was nothing to clean
   */
  static String sanitizeDashJoinedIdentifier(String raw) {
    if (!raw.contains(DASH_SEPARATOR)) {
      return raw;
    }
    String[] segments = DASH_RUN_PATTERN.split(raw, -1);
    StringBuilder cleaned = new StringBuilder();
    for (String segment : segments) {
      String trimmed = segment.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (cleaned.length() > 0) {
        cleaned.append(DASH_SEPARATOR);
      }
      cleaned.append(trimmed);
    }
    return cleaned.toString();
  }

  /**
   * Recursively sanitize every element of {@code array}.
   *
   * @param array the array to sanitize in place; {@code null} is a no-op
   */
  static void strip(JSONArray array) throws JSONException {
    if (array == null) {
      return;
    }
    for (int i = 0; i < array.length(); i++) {
      stripValue(array.get(i));
    }
  }

  /**
   * Whether {@code value} holds an accounting property at any depth.
   *
   * @param value a JSON value
   * @return {@code true} when an accounting key is present
   */
  static boolean containsAccountingKey(Object value) throws JSONException {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      for (String key : keysOf(object)) {
        if (NeoAccountingAccessPolicy.isAccountingProperty(key)
            || containsAccountingKey(object.get(key))) {
          return true;
        }
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        if (containsAccountingKey(array.get(i))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Remove every accounting property from {@code value} in place, at any depth.
   *
   * @param value a JSON value
   */
  static void stripAccounting(Object value) throws JSONException {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      for (String key : keysOf(object)) {
        if (NeoAccountingAccessPolicy.isAccountingProperty(key)) {
          object.remove(key);
        } else {
          stripAccounting(object.get(key));
        }
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        stripAccounting(array.get(i));
      }
    }
  }

  /** A snapshot of the keys, so the object can be changed while they are walked. */
  private static List<String> keysOf(JSONObject object) {
    List<String> keys = new ArrayList<>();
    for (Iterator<?> it = object.keys(); it.hasNext();) {
      keys.add(String.valueOf(it.next()));
    }
    return keys;
  }

  /** Dispatch on the runtime type of a JSON value; scalars are left alone. */
  private static void stripValue(Object value) throws JSONException {
    if (value instanceof JSONObject) {
      strip((JSONObject) value);
    } else if (value instanceof JSONArray) {
      strip((JSONArray) value);
    }
  }
}
