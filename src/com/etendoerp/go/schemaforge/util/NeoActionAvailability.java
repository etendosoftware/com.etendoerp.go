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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * In which record states an action applies, and how to judge a concrete record against that
 * (ETP-5714).
 *
 * <p><b>Why it exists.</b> {@code etendo_schema(view:"actions")} used to publish the action
 * catalogue with no sense of time: an agent could not tell that completing is for drafts only, that
 * a document can be voided only before it completes, or what {@code documentAction} means for the
 * document in front of it, and learned each rule from a refusal. This is the declaration an action
 * (or an {@code MCP_CONFIG.actions} entry) carries to say it.</p>
 *
 * <p><b>Two parts, both optional.</b></p>
 * <ul>
 *   <li>{@code availableWhen} — {@code property → allowed values}, every property must match
 *       (AND). A value prefixed with {@value #NOT_PREFIX} means "anything but that value", e.g.
 *       {@code posted: ["!Y"]}.</li>
 *   <li>{@code valuesByStatus} — for a list-backed action such as {@code documentAction}: the
 *       values of its parameter that apply for each value of a status property.</li>
 * </ul>
 *
 * <p><b>Advisory only.</b> The result tells an agent what to expect; it never refuses a call. The
 * enforcement stays in the contracts' enums and the handlers' own guards, so a wrong declaration
 * can mislead a reader but can never block a legitimate action. A property the record cannot
 * supply makes the verdict "unknown", not "unavailable".</p>
 *
 * <p><b>Structural.</b> The properties are read off the record by name through a reader supplied by
 * the caller; nothing here names an entity.</p>
 */
public final class NeoActionAvailability {

  /** JSON key: {@code property → allowed values}. */
  public static final String KEY_AVAILABLE_WHEN = "availableWhen";
  /** JSON key: the {@code docAction}-style parameter values that apply per status. */
  public static final String KEY_VALUES_BY_STATUS = "valuesByStatus";
  /** JSON key inside {@code valuesByStatus}: the status property that selects the values. */
  public static final String KEY_PROPERTY = "property";
  /** JSON key inside {@code valuesByStatus}: {@code status → values}. */
  public static final String KEY_VALUES = "values";
  /** JSON key on an action: whether it applies to the record that was asked about. */
  public static final String KEY_AVAILABLE_NOW = "availableNow";
  /** JSON key on an action: why it does not apply to the record that was asked about. */
  public static final String KEY_UNAVAILABLE_REASON = "unavailableReason";
  /** JSON key on an action: the parameter values that apply to the record that was asked about. */
  public static final String KEY_AVAILABLE_VALUES_NOW = "availableValuesNow";
  /** Prefix of a value that means "anything but this one". */
  public static final String NOT_PREFIX = "!";
  /** Separates an action from one of its values in a key of {@code unavailableActions}. */
  public static final String VALUE_KEY_SEPARATOR = ":";

  private final Map<String, List<String>> when;
  private final String statusProperty;
  private final Map<String, List<String>> valuesByStatus;
  private final String note;

  private NeoActionAvailability(Map<String, List<String>> when, String statusProperty,
      Map<String, List<String>> valuesByStatus, String note) {
    this.when = Collections.unmodifiableMap(when);
    this.statusProperty = statusProperty;
    this.valuesByStatus = Collections.unmodifiableMap(valuesByStatus);
    this.note = note;
  }

  /**
   * What a record yielded when judged: whether the action applies, why not, and the parameter
   * values that apply.
   *
   * @param available   {@code true}/{@code false}, or {@code null} when the record could not say
   * @param reason      why the action does not apply; {@code null} unless {@code available} is
   *                    {@code false}
   * @param valuesNow   the parameter values that apply to this record; empty when none is declared
   */
  public record Verdict(Boolean available, String reason, List<String> valuesNow) {

    /**
     * Canonical constructor: normalizes {@code valuesNow} to an immutable list.
     *
     * @param available the verdict, or {@code null} for "cannot tell"
     * @param reason    the reason, or {@code null}
     * @param valuesNow the applicable parameter values; may be {@code null}
     */
    public Verdict {
      valuesNow = valuesNow == null ? List.of() : List.copyOf(valuesNow);
    }
  }

  /**
   * The key under which a customization rules out ONE value of a list-backed action
   * ({@code NeoHandler#unavailableActions}) — Reactivate on an order that has linked documents —
   * without claiming the whole action: the others (Close) still apply.
   *
   * @param action the action name
   * @param value  the value of its parameter
   * @return {@code action:value}
   */
  public static String valueKey(String action, String value) {
    return action + VALUE_KEY_SEPARATOR + value;
  }

  /**
   * Availability that requires {@code property} to be one of {@code values}.
   *
   * @param property the record property to read
   * @param values   the allowed values; a {@value #NOT_PREFIX}-prefixed one excludes that value
   * @return the declaration
   */
  public static NeoActionAvailability whenIn(String property, String... values) {
    return empty().andWhen(property, values);
  }

  /**
   * Availability that declares only the parameter values per status.
   *
   * @param statusProperty the status property that selects the values
   * @param byStatus       {@code status → values}; an empty list offers nothing, and a status
   *                       with no entry is not judged at all
   * @return the declaration
   */
  public static NeoActionAvailability valuesByStatus(String statusProperty,
      Map<String, List<String>> byStatus) {
    return empty().withValuesByStatus(statusProperty, byStatus);
  }

  private static NeoActionAvailability empty() {
    return new NeoActionAvailability(new LinkedHashMap<>(), null, new LinkedHashMap<>(), null);
  }

  /**
   * The same declaration with one more required property.
   *
   * @param property the record property to read
   * @param values   the allowed values
   * @return a copy
   */
  public NeoActionAvailability andWhen(String property, String... values) {
    Map<String, List<String>> next = new LinkedHashMap<>(when);
    next.put(property, List.of(values));
    return new NeoActionAvailability(next, statusProperty, valuesByStatus, note);
  }

  /**
   * The same declaration, also stating which parameter values apply per status.
   *
   * @param property the status property that selects the values
   * @param byStatus {@code status → values}
   * @return a copy
   */
  public NeoActionAvailability withValuesByStatus(String property,
      Map<String, List<String>> byStatus) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    byStatus.forEach((status, values) -> copy.put(status, List.copyOf(values)));
    return new NeoActionAvailability(when, property, copy, note);
  }

  /**
   * The same declaration with a sentence appended to every "not available" reason, for what to do
   * instead (for example "correct a completed document with a return").
   *
   * @param text the sentence
   * @return a copy
   */
  public NeoActionAvailability withNote(String text) {
    return new NeoActionAvailability(when, statusProperty, valuesByStatus, text);
  }

  /**
   * Parses the {@code MCP_CONFIG.actions} form of one action: both objects are optional.
   *
   * @param availableWhen  {@code {property: [values]}}, or {@code null}
   * @param valuesByStatus {@code {property, values:{status:[values]}}}, or {@code null}
   * @return the declaration; {@code null} when neither part is present
   */
  public static NeoActionAvailability fromJson(JSONObject availableWhen,
      JSONObject valuesByStatus) {
    if (availableWhen == null && valuesByStatus == null) {
      return null;
    }
    NeoActionAvailability out = empty();
    if (availableWhen != null) {
      for (Iterator<?> it = availableWhen.keys(); it.hasNext();) {
        String property = String.valueOf(it.next());
        out = out.andWhen(property, stringsOf(availableWhen.optJSONArray(property)));
      }
    }
    if (valuesByStatus != null) {
      JSONObject values = valuesByStatus.optJSONObject(KEY_VALUES);
      Map<String, List<String>> byStatus = new LinkedHashMap<>();
      Iterator<?> statuses = values == null ? Collections.emptyIterator() : values.keys();
      while (statuses.hasNext()) {
        String status = String.valueOf(statuses.next());
        byStatus.put(status, List.of(stringsOf(values.optJSONArray(status))));
      }
      out = out.withValuesByStatus(valuesByStatus.optString(KEY_PROPERTY, null), byStatus);
    }
    return out;
  }

  private static String[] stringsOf(JSONArray array) {
    List<String> out = new ArrayList<>();
    for (int i = 0; array != null && i < array.length(); i++) {
      out.add(array.optString(i));
    }
    return out.toArray(new String[0]);
  }

  /**
   * Problems with a parsed declaration, for configuration validation.
   *
   * @param availableWhen  the {@code availableWhen} object of one action, or {@code null}
   * @param valuesByStatus the {@code valuesByStatus} object of one action, or {@code null}
   * @return the problems found; empty when both are well-formed
   */
  public static List<String> problemsOf(JSONObject availableWhen, JSONObject valuesByStatus) {
    List<String> problems = new ArrayList<>();
    if (availableWhen != null) {
      for (Iterator<?> it = availableWhen.keys(); it.hasNext();) {
        String property = String.valueOf(it.next());
        if (!nonBlankStrings(availableWhen.optJSONArray(property))) {
          problems.add(KEY_AVAILABLE_WHEN + "." + property
              + " must be a non-empty array of values");
        }
      }
    }
    if (valuesByStatus != null) {
      problems.addAll(valuesByStatusProblems(valuesByStatus));
    }
    return problems;
  }

  private static List<String> valuesByStatusProblems(JSONObject valuesByStatus) {
    List<String> problems = new ArrayList<>();
    if (StringUtils.isBlank(valuesByStatus.optString(KEY_PROPERTY, null))) {
      problems.add(KEY_VALUES_BY_STATUS + " needs a '" + KEY_PROPERTY + "' naming the status");
    }
    JSONObject values = valuesByStatus.optJSONObject(KEY_VALUES);
    if (values == null || values.length() == 0) {
      problems.add(KEY_VALUES_BY_STATUS + " needs a non-empty '" + KEY_VALUES
          + "' object {status: [values]}");
      return problems;
    }
    for (Iterator<?> it = values.keys(); it.hasNext();) {
      String status = String.valueOf(it.next());
      JSONArray list = values.optJSONArray(status);
      if (list == null || !allStrings(list)) {
        problems.add(KEY_VALUES_BY_STATUS + "." + KEY_VALUES + "." + status
            + " must be an array of values (empty = nothing applies)");
      }
    }
    return problems;
  }

  private static boolean nonBlankStrings(JSONArray array) {
    if (array == null || array.length() == 0) {
      return false;
    }
    for (int i = 0; i < array.length(); i++) {
      if (!(array.opt(i) instanceof String) || StringUtils.isBlank(array.optString(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean allStrings(JSONArray array) {
    for (int i = 0; i < array.length(); i++) {
      if (!(array.opt(i) instanceof String) || StringUtils.isBlank(array.optString(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Adds the static declaration to an action's JSON: {@code availableWhen} and/or
   * {@code valuesByStatus}, only the parts that are declared.
   *
   * @param action the action entry to decorate
   * @throws JSONException if the JSON cannot be built
   */
  public void writeTo(JSONObject action) throws JSONException {
    if (!when.isEmpty()) {
      JSONObject whenJson = new JSONObject();
      for (Map.Entry<String, List<String>> e : when.entrySet()) {
        whenJson.put(e.getKey(), new JSONArray(e.getValue()));
      }
      action.put(KEY_AVAILABLE_WHEN, whenJson);
    }
    if (statusProperty != null) {
      JSONObject values = new JSONObject();
      for (Map.Entry<String, List<String>> e : valuesByStatus.entrySet()) {
        values.put(e.getKey(), new JSONArray(e.getValue()));
      }
      action.put(KEY_VALUES_BY_STATUS,
          new JSONObject().put(KEY_PROPERTY, statusProperty).put(KEY_VALUES, values));
    }
  }

  /**
   * Judges a concrete record. Never throws and never decides anything: the caller only reports it.
   *
   * @param reader reads a record property by name as a string, {@code null} when the record has no
   *               such property or no value for it
   * @return the verdict; {@code available == null} when a declared property could not be read
   */
  public Verdict evaluate(UnaryOperator<String> reader) {
    for (Map.Entry<String, List<String>> e : when.entrySet()) {
      String actual = reader.apply(e.getKey());
      if (actual == null) {
        return new Verdict(null, null, List.of());
      }
      if (!matches(e.getValue(), actual)) {
        return new Verdict(false, reasonFor(e.getKey(), actual, e.getValue()), List.of());
      }
    }
    if (statusProperty == null) {
      return new Verdict(true, null, List.of());
    }
    String status = reader.apply(statusProperty);
    if (status == null) {
      return new Verdict(null, null, List.of());
    }
    if (!valuesByStatus.containsKey(status)) {
      // A status the declaration does not list is not a judgement: advisory data never claims
      // "unavailable" for a state it never described. An intentionally empty list is listed.
      return new Verdict(null, null, List.of());
    }
    List<String> values = valuesByStatus.get(status);
    if (values.isEmpty()) {
      return new Verdict(false, appendNote("No value of this action applies when " + statusProperty
          + " is " + status + "."), List.of());
    }
    return new Verdict(true, null, values);
  }

  private String reasonFor(String property, String actual, List<String> allowed) {
    boolean negated = allowed.stream().allMatch(v -> v.startsWith(NOT_PREFIX));
    String expectation = negated
        ? "is not " + String.join(" or ", allowed.stream().map(v -> v.substring(1)).toList())
        : "is " + String.join(" or ", allowed);
    return appendNote("Applies when " + property + " " + expectation + "; this record's " + property
        + " is " + actual + ".");
  }

  private String appendNote(String reason) {
    return note == null ? reason : reason + " " + note;
  }

  private static boolean matches(List<String> allowed, String actual) {
    boolean anyPositive = false;
    boolean positiveHit = false;
    for (String value : allowed) {
      if (value.startsWith(NOT_PREFIX)) {
        if (value.substring(1).equals(actual)) {
          return false;
        }
      } else {
        anyPositive = true;
        positiveHit = positiveHit || value.equals(actual);
      }
    }
    return !anyPositive || positiveHit;
  }
}
