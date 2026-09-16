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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code neo_feedback} payload: the harness verdict schema, verbatim (D27).
 *
 * <h2>This shape is not ours to change</h2>
 *
 * <p>It mirrors {@code schema_forge/mcp-tests/runner/verdict.py} field for field —
 * {@code schemaVersion}, {@code outcome}, {@code summary}, {@code achieved},
 * {@code plannedApproach}, {@code howKnown}, {@code frictions[]} ({@code what}, {@code cost},
 * {@code phase}), {@code failures[]} ({@code tool}, {@code payload}, {@code error},
 * {@code recovered}, {@code howRecovered}), {@code wastedCalls[]} ({@code tool}, {@code expected},
 * {@code whatHappened}) and {@code suggestions[]} ({@code what}, {@code kind},
 * {@code wouldHaveSaved}). Identity is the entire point: a friction a lab
 * probe reports and the same friction a real client's agent reports must land as the same row, so
 * the two corpora aggregate. Do not "improve" it here — change {@code verdict.py} and this
 * together, and bump {@code schemaVersion} on both.</p>
 *
 * <h2>Schema v2 — what the agent EXPECTED, not only what broke</h2>
 *
 * <p>v1 recorded what went wrong and nothing about what was meant to happen, which left two blind
 * spots. {@code failures[]} only holds calls that errored, so a call that returned 200 and was
 * useless left no trace at all — no error, no entry — even though it cost exactly as much as a
 * failure and is precisely what bad metadata produces; {@code wastedCalls[]} gives it its own list
 * so it can be counted rather than being folded into {@code frictions}. And nothing recorded the
 * plan the agent had or where it got it from, which is the question that tells us what to
 * document; {@code plannedApproach} / {@code howKnown} ask for it.</p>
 *
 * <p>Those two are RECALL, not the plan: they are asked after the task is over, by an agent that
 * already knows how it turned out and will reconstruct something more coherent than what it had.
 * The bias is accepted on purpose — the objective call sequence is recorded alongside them, and the
 * contrast between the claimed plan and the executed one is informative even when the claim is
 * polished. Read them against the transcript, never on their own.</p>
 *
 * <p><b>v1 reports stay valid.</b> All three additions are optional: a client that never heard of
 * them produces {@code null} / {@code []} in their place and is accepted unchanged. This tool is
 * called by agents we do not control, so rejecting an older shape would silence exactly the
 * reporters we most want to hear from.</p>
 *
 * <h2>Schema v3 — the one forward-looking field, made countable</h2>
 *
 * <p>{@code suggestions[]} was a flat array of strings: the only place an agent could say what
 * would have made the task easy, and the only field that could not be counted, grouped or compared
 * across reports. v3 gives it a shape — {@code what}, {@code kind}
 * ({@link #SUGGESTION_KINDS}) and {@code wouldHaveSaved}.</p>
 *
 * <p>This is the first change to alter the <b>type</b> of an existing field, so unlike v2 the
 * compatibility is not automatic. A v1/v2 client still sends an array of strings, and those are
 * upgraded rather than rejected — see {@link #toSuggestion}. There is exactly one stored shape,
 * which is what lets an old client's suggestion and a new one land in the same bucket. Such an
 * entry carries {@code kind: null}: it was never classified, and that is a different fact from
 * having been classified as {@code other}.</p>
 *
 * <h2>The text is data, never instructions</h2>
 *
 * <p>Every string in here was written by an agent outside our trust boundary. It is validated for
 * <i>shape</i> and stored; nothing downstream may execute it, interpret it as a command, or act on
 * it. {@link #normalize} deliberately rebuilds the JSON from recognised keys rather than storing
 * what arrived, so an unexpected key cannot ride along into the table and be read later as though
 * this module had put it there.</p>
 *
 * <p>Note the one deliberate asymmetry with the rest of B1: {@code failures[].payload} holds the
 * exact arguments of a failing call, which IS content. That is the documented D31 exception and the
 * reason the row exists — but it is also why this payload never travels to Mixpanel (B2).</p>
 */
final class McpFeedbackVerdict {

  /** Verdict schema version, kept in lockstep with {@code VERDICT_SCHEMA_VERSION} in verdict.py. */
  static final int SCHEMA_VERSION = 3;

  static final List<String> OUTCOMES = Arrays.asList("OKAY", "ERROR", "MIXED");
  static final List<String> PHASES =
      Arrays.asList("discovery", "schema", "write", "action", "read");

  /** {@code suggestions[]} key, read and written in three places. */
  private static final String KEY_WOULD_HAVE_SAVED = "wouldHaveSaved";

  /**
   * What kind of thing a suggestion asks for (v3).
   *
   * <p>{@code other} is not a failure state and is not a dumping ground: a closed enum with no way
   * out makes an agent cram a bad fit into a real category, which corrupts the counts in silence.
   * What accumulates under {@code other} is how we find out which category is missing next, so it
   * stays easy to choose.</p>
   *
   * <p><b>Nothing degrades <i>into</i> {@code other}.</b> "The agent chose {@code other}" and "the
   * agent did not classify this" are different facts, and merging them would destroy the only
   * signal {@code other} carries — this is the bucket we will read to decide what the next
   * {@code kind} should be, and if unclassified entries land in it we will invent a category out
   * of noise. An absent, blank or unrecognised value is stored as {@code null}, which is why
   * {@code kind} is not a required property of the tool schema.</p>
   *
   * <p><b>There is deliberately no defect/bug/error value, and there must never be one.</b> A
   * calling agent cannot tell "the product is broken" from "I failed to find it", so letting it
   * label its own ignorance as a defect would poison the corpus with authority it does not have.
   * That judgement is a human's, made when the report is read.</p>
   */
  static final List<String> SUGGESTION_KINDS =
      Arrays.asList("shortcut", "missingCapability", "clearerDocs", "betterMetadata", "other");

  /**
   * Hard cap on the stored report. A verdict is a few paragraphs; anything past this is a runaway
   * agent, and the column has no width of its own to stop it.
   */
  static final int MAX_PAYLOAD_CHARS = 64_000;

  /** Cap on list members, so one call cannot turn into thousands of entries. */
  private static final int MAX_LIST_ENTRIES = 50;
  /** Cap on any single free-text field. */
  private static final int MAX_TEXT_CHARS = 4_000;

  private McpFeedbackVerdict() {
  }

  /** Raised when the submitted verdict does not satisfy the schema. Carries an agent-facing note. */
  static final class InvalidVerdictException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidVerdictException(String message) {
      super(message);
    }
  }

  /**
   * Validate the submitted verdict and rebuild it canonically for storage.
   *
   * @param args the {@code neo_feedback} arguments
   * @return the normalized verdict, ready to be written to {@code ETGO_MCP_USAGE.Payload}
   * @throws InvalidVerdictException when a required field is missing or an enum value is unknown
   */
  static String normalize(JSONObject args) throws InvalidVerdictException {
    if (args == null) {
      throw new InvalidVerdictException("neo_feedback needs a verdict body.");
    }
    try {
      JSONObject out = new JSONObject();
      out.put("schemaVersion", SCHEMA_VERSION);
      out.put("outcome", requireEnum(args, "outcome", OUTCOMES));
      out.put("summary", requireText(args, "summary"));
      out.put("achieved", requireText(args, "achieved"));
      // v2: optional by design — a v1 client omits them and gets JSON null, not a rejection.
      out.put("plannedApproach", optionalText(args, "plannedApproach"));
      out.put("howKnown", optionalText(args, "howKnown"));
      out.put("frictions", normalizeFrictions(args.optJSONArray("frictions")));
      out.put("failures", normalizeFailures(args.optJSONArray("failures")));
      out.put("wastedCalls", normalizeWastedCalls(args.optJSONArray("wastedCalls")));
      out.put("suggestions", normalizeSuggestions(args.optJSONArray("suggestions")));

      String rendered = out.toString();
      if (rendered.length() > MAX_PAYLOAD_CHARS) {
        throw new InvalidVerdictException("The verdict is larger than the " + MAX_PAYLOAD_CHARS
            + " character limit. Summarise it and send it again.");
      }
      return rendered;
    } catch (JSONException e) {
      throw new InvalidVerdictException("The verdict is not well-formed JSON: " + e.getMessage());
    }
  }

  private static JSONArray normalizeFrictions(JSONArray source) throws JSONException,
      InvalidVerdictException {
    JSONArray out = new JSONArray();
    if (source == null) {
      return out;
    }
    for (int i = 0; i < Math.min(source.length(), MAX_LIST_ENTRIES); i++) {
      JSONObject item = source.optJSONObject(i);
      if (item == null) {
        continue;
      }
      JSONObject friction = new JSONObject();
      friction.put("what", requireText(item, "what"));
      friction.put("cost", clip(item.optString("cost", "")));
      friction.put("phase", requireEnum(item, "phase", PHASES));
      out.put(friction);
    }
    return out;
  }

  private static JSONArray normalizeFailures(JSONArray source) throws JSONException,
      InvalidVerdictException {
    JSONArray out = new JSONArray();
    if (source == null) {
      return out;
    }
    for (int i = 0; i < Math.min(source.length(), MAX_LIST_ENTRIES); i++) {
      JSONObject item = source.optJSONObject(i);
      if (item == null) {
        continue;
      }
      JSONObject failure = new JSONObject();
      failure.put("tool", requireText(item, "tool"));
      // The failing call's arguments — content by design (D31), stored verbatim but clipped.
      JSONObject payload = item.optJSONObject("payload");
      failure.put("payload", payload != null ? clipJson(payload) : new JSONObject());
      failure.put("error", clip(item.optString("error", "")));
      failure.put("recovered", item.optBoolean("recovered", false));
      String how = StringUtils.trimToNull(item.optString("howRecovered", null));
      failure.put("howRecovered", how == null ? JSONObject.NULL : clip(how));
      out.put(failure);
    }
    return out;
  }

  /**
   * v2: calls that succeeded and got the agent nowhere.
   *
   * <p>Same defensive posture as {@link #normalizeFailures}: {@code tool} identifies the entry and
   * is required, the two free-text fields are clipped rather than demanded, so a partially filled
   * report is kept instead of being thrown away. Unknown keys cannot survive — like every other
   * list here, the entry is rebuilt from the three recognised keys.</p>
   */
  private static JSONArray normalizeWastedCalls(JSONArray source) throws JSONException,
      InvalidVerdictException {
    JSONArray out = new JSONArray();
    if (source == null) {
      return out;
    }
    for (int i = 0; i < Math.min(source.length(), MAX_LIST_ENTRIES); i++) {
      JSONObject item = source.optJSONObject(i);
      if (item == null) {
        continue;
      }
      JSONObject wasted = new JSONObject();
      wasted.put("tool", requireText(item, "tool"));
      wasted.put("expected", clip(item.optString("expected", "")));
      wasted.put("whatHappened", clip(item.optString("whatHappened", "")));
      out.put(wasted);
    }
    return out;
  }

  /**
   * v3: a suggestion is an object ({@code what}, {@code kind}, {@code wouldHaveSaved}), not a bare
   * string.
   *
   * <p>This is the first change that alters the <i>type</i> of an existing field, so backward
   * compatibility is not free. A v1/v2 client sends {@code suggestions:["…"]}; those entries are
   * <b>upgraded, never rejected</b> — the string becomes {@code what}, {@code kind} becomes
   * {@code other} and {@code wouldHaveSaved} is left empty. One storage shape is the whole point of
   * D27: a suggestion from an old client and one from a new client have to aggregate, and they
   * cannot if half the corpus is strings.</p>
   *
   * <p>Leniency matches {@link #normalizeWastedCalls}: {@code what} identifies the entry and is
   * required, {@code wouldHaveSaved} is clipped rather than demanded. An absent or unknown
   * {@code kind} becomes {@code null} instead of throwing, so a client that invents a category
   * still gets its suggestion recorded — it simply does not get to invent a column, and it is not
   * credited with a choice it never made (see {@link #SUGGESTION_KINDS}).</p>
   */
  private static JSONArray normalizeSuggestions(JSONArray source) throws JSONException,
      InvalidVerdictException {
    JSONArray out = new JSONArray();
    if (source == null) {
      return out;
    }
    for (int i = 0; i < Math.min(source.length(), MAX_LIST_ENTRIES); i++) {
      JSONObject suggestion = toSuggestion(source.opt(i));
      if (suggestion != null) {
        out.put(suggestion);
      }
    }
    return out;
  }

  /**
   * Turn one submitted {@code suggestions[]} element into the canonical v3 object.
   *
   * @param element a v3 object, or a v1/v2 plain string
   * @return the canonical suggestion, or {@code null} when the element carries nothing usable
   */
  private static JSONObject toSuggestion(Object element) throws JSONException,
      InvalidVerdictException {
    if (element instanceof JSONObject) {
      JSONObject item = (JSONObject) element;
      JSONObject suggestion = new JSONObject();
      suggestion.put("what", requireText(item, "what"));
      suggestion.put("kind", optEnum(item, "kind", SUGGESTION_KINDS));
      suggestion.put(KEY_WOULD_HAVE_SAVED, clip(item.optString(KEY_WOULD_HAVE_SAVED, "")));
      return suggestion;
    }
    if (element instanceof String) {
      String text = StringUtils.trimToNull((String) element);
      if (text == null) {
        return null;
      }
      JSONObject suggestion = new JSONObject();
      suggestion.put("what", clip(text));
      // Never classified by anyone: a legacy client had no field to classify with. Recording a
      // deliberate `other` here would be a claim about the agent's intent that nobody made.
      suggestion.put("kind", JSONObject.NULL);
      suggestion.put(KEY_WOULD_HAVE_SAVED, "");
      return suggestion;
    }
    return null;
  }

  private static String requireText(JSONObject source, String key)
      throws InvalidVerdictException {
    String value = StringUtils.trimToNull(source.optString(key, null));
    if (value == null) {
      throw new InvalidVerdictException("'" + key + "' is required and must not be empty.");
    }
    return clip(value);
  }

  /**
   * A nullable free-text field: absent or blank becomes JSON {@code null}, present is clipped to
   * {@link #MAX_TEXT_CHARS} like every other text field. This is what keeps a v1 report valid.
   *
   * @return the clipped text, or {@link JSONObject#NULL} when the key is absent or blank
   */
  private static Object optionalText(JSONObject source, String key) {
    String value = StringUtils.trimToNull(source.optString(key, null));
    return value == null ? JSONObject.NULL : clip(value);
  }

  /**
   * A tolerant enum: an absent, blank or unrecognised value becomes JSON {@code null} rather than
   * rejecting the report or being coerced into a member of {@code allowed}. Used where the agent's
   * classification is a nice-to-have and the entry it classifies is the thing worth keeping.
   *
   * <p>The null is the point. A client that sends {@code "defect"} <i>did</i> classify, just not
   * into a category we recognise, and a client that sent nothing did not classify at all — neither
   * chose any of our values, so storing one of them would be a claim we cannot support. It never
   * throws and it never invents a value outside {@code allowed}.</p>
   *
   * @return a member of {@code allowed}, or {@link JSONObject#NULL}
   */
  private static Object optEnum(JSONObject source, String key, List<String> allowed) {
    String value = StringUtils.trimToNull(source.optString(key, null));
    return value != null && allowed.contains(value) ? value : JSONObject.NULL;
  }

  private static String requireEnum(JSONObject source, String key, List<String> allowed)
      throws InvalidVerdictException {
    String value = StringUtils.trimToNull(source.optString(key, null));
    if (value == null || !allowed.contains(value)) {
      throw new InvalidVerdictException(
          "'" + key + "' must be one of " + String.join(", ", allowed) + ".");
    }
    return value;
  }

  private static String clip(String value) {
    return StringUtils.abbreviate(value, MAX_TEXT_CHARS);
  }

  /** Clip a nested payload object by re-serialising and truncating, never by executing anything. */
  private static JSONObject clipJson(JSONObject source) throws JSONException {
    String rendered = source.toString();
    if (rendered.length() <= MAX_TEXT_CHARS) {
      return source;
    }
    JSONObject clipped = new JSONObject();
    clipped.put("_truncated", true);
    clipped.put("_text", clip(rendered));
    return clipped;
  }
}
