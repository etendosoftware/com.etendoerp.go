package com.etendoerp.go.mcp;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.apache.commons.lang3.StringUtils;

/** Small, side-effect-free helpers for reading optional MCP arguments. */
final class McpArgumentUtils {

  private McpArgumentUtils() {
  }

  static String optionalString(JSONObject arguments, String key) {
    if (arguments == null || !arguments.has(key) || arguments.isNull(key)) return null;
    return String.valueOf(arguments.opt(key));
  }

  static String joinStringArray(JSONArray values) {
    if (values == null) return null;
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < values.length(); i++) {
      String value = values.optString(i, null);
      if (StringUtils.isBlank(value)) continue;
      if (joined.length() > 0) joined.append(',');
      joined.append(value);
    }
    return joined.toString();
  }

  /**
   * IMP-40: copies a non-blank top-level {@code parentId} argument into {@code fields}, where it
   * overrides any {@code parentId} the fields already carry.
   *
   * @param args   the tool arguments
   * @param fields the record fields of the create
   * @throws JSONException if the argument cannot be read or copied
   */
  static void copyTopLevelParentId(JSONObject args, JSONObject fields)
      throws JSONException {
    if (args.has(McpConstants.PARAM_PARENT_ID) && !args.isNull(McpConstants.PARAM_PARENT_ID)) {
      String topLevelParentId = args.getString(McpConstants.PARAM_PARENT_ID);
      if (StringUtils.isNotBlank(topLevelParentId)) {
        fields.put(McpConstants.PARAM_PARENT_ID, topLevelParentId);
      }
    }
  }
}
