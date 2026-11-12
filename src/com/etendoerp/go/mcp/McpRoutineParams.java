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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The typed parameters of a saved {@code etendo_routine} (ETP-6236): their declaration when a
 * routine is saved, and the values a {@code run} sends.
 *
 * <p>A declaration is {@code {<name>: {type, required, description}}}; {@code type} is one of
 * {@link #TYPES}. {@code run} checks every value before the first step, so a routine never stops
 * half-way for a value the caller could have been told about up front.</p>
 */
final class McpRoutineParams {

  static final String KEY_TYPE = "type";
  static final String KEY_REQUIRED = "required";
  static final String KEY_FROM = "from";
  static final String FIELD_PARAMS = "params";

  static final String TYPE_STRING = "string";
  static final String TYPE_NUMBER = "number";
  static final String TYPE_BOOLEAN = "boolean";
  static final String TYPE_ID = "id";
  static final List<String> TYPES = List.of(TYPE_STRING, TYPE_NUMBER, TYPE_BOOLEAN, TYPE_ID);

  private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

  private McpRoutineParams() {
  }

  /**
   * Reads and normalizes the declared parameters of a routine being saved.
   *
   * @param raw       the {@code params} argument; absent means no parameters
   * @param allowFrom whether a declaration may carry {@code from} (saving from an execution)
   * @return {@code {name: {type, required, description[, from]}}}, in the order sent
   * @throws McpRoutineSteps.Refusal naming the declaration that is wrong
   */
  static JSONObject declarations(Object raw, boolean allowFrom) throws McpRoutineSteps.Refusal {
    JSONObject normalized = new JSONObject();
    if (raw == null) {
      return normalized;
    }
    if (!(raw instanceof JSONObject)) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "'params' must be an object "
          + "{<name>: {type, required, description}}.");
    }
    JSONObject declared = (JSONObject) raw;
    Iterator<?> names = declared.keys();
    try {
      while (names.hasNext()) {
        String name = String.valueOf(names.next());
        normalized.put(name, declaration(name, declared.opt(name), allowFrom));
      }
    } catch (JSONException e) {
      throw new McpToolException("Error reading routine parameters", e);
    }
    return normalized;
  }

  private static JSONObject declaration(String name, Object raw, boolean allowFrom)
      throws McpRoutineSteps.Refusal, JSONException {
    if (!NAME_PATTERN.matcher(name).matches()) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "Parameter name '" + name + "' must be letters, "
          + "digits or '_', starting with a letter (at most 40 characters).");
    }
    if (!(raw instanceof JSONObject)) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "Parameter '" + name + "' must be an object "
          + "{type, required, description}.");
    }
    JSONObject source = (JSONObject) raw;
    Iterator<?> keys = source.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      boolean known = KEY_TYPE.equals(key) || KEY_REQUIRED.equals(key)
          || McpConstants.KEY_DESCRIPTION.equals(key) || (allowFrom && KEY_FROM.equals(key));
      if (!known) {
        throw McpRoutineSteps.refusal(FIELD_PARAMS, "Parameter '" + name + "' has an unknown key '"
            + key + "'. It takes type, required and description"
            + (allowFrom ? ", and from when saving from an execution." : "."));
      }
    }
    String type = source.optString(KEY_TYPE, null);
    if (!TYPES.contains(type)) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "Parameter '" + name + "' needs a 'type': one "
          + "of " + TYPES + ".");
    }
    Object required = source.opt(KEY_REQUIRED);
    if (!(required instanceof Boolean)) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "Parameter '" + name + "' needs 'required': "
          + "true or false.");
    }
    String description = StringUtils.trimToNull(source.optString(McpConstants.KEY_DESCRIPTION,
        null));
    if (description == null) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "Parameter '" + name + "' needs a "
          + "'description': what value to send.");
    }
    JSONObject normalized = new JSONObject();
    normalized.put(KEY_TYPE, type);
    normalized.put(KEY_REQUIRED, required);
    normalized.put(McpConstants.KEY_DESCRIPTION, description);
    if (source.has(KEY_FROM)) {
      normalized.put(KEY_FROM, source.get(KEY_FROM));
    }
    return normalized;
  }

  /**
   * Checks the values a {@code run} sends against the routine's declarations.
   *
   * @param declarations the routine's declared parameters
   * @param raw          the {@code params} argument of the run; absent means none sent
   * @return the values by name; an optional parameter that was not sent is absent
   * @throws McpRoutineSteps.Refusal naming every problem found, before anything runs
   */
  static Map<String, Object> values(JSONObject declarations, Object raw)
      throws McpRoutineSteps.Refusal {
    if (raw != null && !(raw instanceof JSONObject)) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "'params' must be an object {<name>: value}.");
    }
    JSONObject sent = raw == null ? new JSONObject() : (JSONObject) raw;
    Set<String> declared = names(declarations);
    StringBuilder problems = new StringBuilder();
    Iterator<?> keys = sent.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (!declared.contains(key)) {
        problems.append(" '").append(key).append("' is not a parameter of this routine.");
      }
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (String name : declared) {
      JSONObject declaration = declarations.optJSONObject(name);
      Object value = sent.opt(name);
      if (value == null || value == JSONObject.NULL) {
        if (declaration.optBoolean(KEY_REQUIRED)) {
          problems.append(" '").append(name).append("' is required: ")
              .append(declaration.optString(McpConstants.KEY_DESCRIPTION)).append('.');
        }
      } else if (!matches(declaration.optString(KEY_TYPE), value)) {
        problems.append(" '").append(name).append("' must be a ")
            .append(declaration.optString(KEY_TYPE)).append('.');
      } else {
        values.put(name, value);
      }
    }
    if (problems.length() > 0) {
      throw McpRoutineSteps.refusal(FIELD_PARAMS, "The parameters do not match the routine:"
          + problems);
    }
    return values;
  }

  /** @return the declared parameter names, in declaration order */
  static Set<String> names(JSONObject declarations) {
    Set<String> names = new java.util.LinkedHashSet<>();
    Iterator<?> keys = declarations.keys();
    while (keys.hasNext()) {
      names.add(String.valueOf(keys.next()));
    }
    return names;
  }

  private static boolean matches(String type, Object value) {
    switch (type) {
      case TYPE_NUMBER:
        return value instanceof Number;
      case TYPE_BOOLEAN:
        return value instanceof Boolean;
      case TYPE_ID:
        return value instanceof String && StringUtils.isNotBlank((String) value);
      default:
        return value instanceof String;
    }
  }
}
