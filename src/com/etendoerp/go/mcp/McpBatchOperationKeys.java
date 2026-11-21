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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The guard that refuses a key an {@code etendo_batch} operation does not declare (ETP-5810), in the
 * batch pre-flight envelope with the {@code unknown_argument} error of IMP-40. Split from
 * {@link McpToolRouter} and {@link ToolRegistry}, which are at their method limit.
 */
final class McpBatchOperationKeys {

  private static final String KEY_OPERATIONS = "operations";
  private static final String KEY_ITEMS = "items";

  private McpBatchOperationKeys() {
  }

  /** Lazy holder: the batch tool definition is built once, not on every etendo_batch call. */
  private static final class Holder {
    static final Set<String> VALUE = keysOf(new ToolRegistry().buildBatchTool().getInputSchema());
  }

  /**
   * The keys an {@code etendo_batch} operation declares, read from the tool's own schema so the
   * guard cannot drift from what the agent was told.
   *
   * @return the declared operation keys, never empty
   */
  static Set<String> declaredKeys() {
    return Holder.VALUE;
  }

  /**
   * The property names of {@code operations.items} in a batch input schema.
   *
   * @throws IllegalStateException when the schema has none: an empty set would make the guard
   *     refuse every key, so a drifted schema fails loudly instead
   */
  static Set<String> keysOf(Map<String, Object> inputSchema) {
    Object props = inputSchema == null ? null : inputSchema.get(McpConstants.KEY_PROPERTIES);
    Object operations = props instanceof Map ? ((Map<?, ?>) props).get(KEY_OPERATIONS) : null;
    Object items = operations instanceof Map ? ((Map<?, ?>) operations).get(KEY_ITEMS) : null;
    Object opProps = items instanceof Map
        ? ((Map<?, ?>) items).get(McpConstants.KEY_PROPERTIES) : null;
    if (!(opProps instanceof Map) || ((Map<?, ?>) opProps).isEmpty()) {
      throw new IllegalStateException(
          "etendo_batch schema declares no operations.items.properties");
    }
    Set<String> names = new LinkedHashSet<>();
    for (Object key : ((Map<?, ?>) opProps).keySet()) {
      names.add(String.valueOf(key));
    }
    return Collections.unmodifiableSet(names);
  }

  /**
   * Refuses the first key of an operation that the operation does not declare.
   *
   * @param operations the operations as received; entries that are not objects are skipped
   * @return the pre-flight failure envelope, or {@code null} when every key is declared
   */
  static JSONObject rejectUnknown(JSONArray operations) throws JSONException {
    Set<String> declared = declaredKeys();
    for (int i = 0; i < operations.length(); i++) {
      JSONObject op = operations.optJSONObject(i);
      if (op == null) {
        continue;
      }
      Iterator<String> keys = op.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!declared.contains(key)) {
          JSONObject error = McpRoutingException.unknownArgument(
              KEY_OPERATIONS + "[" + i + "]." + key, McpConstants.TOOL_NEO_BATCH,
              new ArrayList<>(declared)).toEnvelope();
          error.put(McpConstants.KEY_TOOL, McpConstants.TOOL_NEO_BATCH);
          return McpBatchEnvelope.toMcpBatchPreflightFailure(error, i, op.optString("id", null));
        }
      }
    }
    return null;
  }
}
