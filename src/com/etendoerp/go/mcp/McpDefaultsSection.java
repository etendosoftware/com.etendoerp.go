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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code defaults} section of {@code MCP_CONFIG} (ETP-5852): the record values an entity's
 * {@code etendo_defaults} needs in {@code recordContext} before its dependent defaults are real.
 *
 * <pre>
 * "defaults": { "context": ["businessPartner"], "reason": "Payment terms ... come from it." }
 * </pre>
 *
 * <p>Production feedback: on document headers the defaults were resolved with no business partner,
 * so payment terms, payment method, address and price list came back generic, and agents copied
 * them onto the record. Nothing told them which value to send first. The declaration is published
 * in the {@code etendo_defaults} catalog description ({@link McpDefaultsContextCatalog}), so the
 * agent knows it before the first call.</p>
 */
final class McpDefaultsSection {

  static final String NAME = "defaults";
  static final String KEY_CONTEXT = "context";
  static final String KEY_REASON = "reason";

  /** The one instance, as for every section (ETP-5639). */
  private static final McpConfigSection DECLARATION = McpConfigSection.of(NAME,
      Set.of(KEY_CONTEXT, KEY_REASON), McpConfigSection.Merge.REPLACE,
      McpDefaultsSection::validate);

  private McpDefaultsSection() {
  }

  static McpConfigSection declaration() {
    return DECLARATION;
  }

  /**
   * {@code context} must be a non-empty array of field names; {@code reason}, when present, a
   * string.
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    JSONArray context = body.optJSONArray(KEY_CONTEXT);
    if (context == null || context.length() == 0) {
      problems.add("section 'defaults': 'context' must be a non-empty array of field names");
    } else {
      for (int i = 0; i < context.length(); i++) {
        Object name = context.opt(i);
        if (!(name instanceof String) || ((String) name).trim().isEmpty()) {
          problems.add("section 'defaults': every 'context' entry must be a field name");
          break;
        }
      }
    }
    if (body.has(KEY_REASON) && !(body.opt(KEY_REASON) instanceof String)) {
      problems.add("section 'defaults': 'reason' must be a string");
    }
    return problems;
  }

  /**
   * One catalog line, {@code spec/entity: f1, f2 — reason}.
   *
   * @return the line, or {@code null} when the body declares no usable context
   */
  static String catalogLine(String spec, String entity, JSONObject body) {
    if (body == null || !validate(body).isEmpty()) {
      return null;
    }
    JSONArray context = body.optJSONArray(KEY_CONTEXT);
    List<String> names = new ArrayList<>();
    for (int i = 0; i < context.length(); i++) {
      names.add(context.optString(i));
    }
    String reason = body.optString(KEY_REASON, "").trim();
    return spec + "/" + entity + ": " + String.join(", ", names)
        + (reason.isEmpty() ? "" : " — " + reason);
  }
}
