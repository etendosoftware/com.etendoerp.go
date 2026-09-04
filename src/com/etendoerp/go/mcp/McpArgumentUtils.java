package com.etendoerp.go.mcp;

import org.codehaus.jettison.json.JSONArray;
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
}
