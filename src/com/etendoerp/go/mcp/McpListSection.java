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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The {@code list} section of {@code MCP_CONFIG}: the fields {@code etendo_list} returns when the
 * agent names none (ETP-5848).
 *
 * <h2>Shape</h2>
 * <pre>
 * {
 *   "list": { "fields": ["documentNo", "businessPartner", "grandTotalAmount"] }
 * }
 * </pre>
 *
 * <p><b>Why it exists.</b> Without {@code fields}, {@code etendo_list} used to answer every column:
 * 57 to 109 fields and 1.5 to 3 kB per row, 30% of all the text agents read in the OKR O2 sweep.
 * The default is now the window's own list columns ({@code ETGO_SF_FIELD.GRID_SEQNO}, written by
 * push-to-neo from the frontend contract). This section replaces that default for one entity, for
 * when the list a person scans is not what an agent needs to pick a record.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>{@code fields} is a non-empty array of field names, as {@code etendo_list} returns them.
 *       {@code id} is always returned and need not be named.</li>
 *   <li>It is only the default: an explicit {@code fields:[...]} or {@code view} from the agent
 *       wins.</li>
 *   <li>{@code REPLACE} across levels, written at entity level.</li>
 * </ul>
 */
final class McpListSection {

  /** The section name inside {@code MCP_CONFIG}. */
  static final String NAME = "list";

  static final String KEY_FIELDS = "fields";

  private static final McpConfigSection DECLARATION = McpConfigSection.of(NAME,
      Set.of(KEY_FIELDS), McpConfigSection.Merge.REPLACE, McpListSection::validate);

  private static final Logger log = LogManager.getLogger(McpListSection.class);

  private McpListSection() {
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
   * Validate a {@code list} body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    JSONArray fields = body.optJSONArray(KEY_FIELDS);
    if (fields == null || fields.length() == 0) {
      problems.add(KEY_FIELDS + " must be a non-empty array of field names");
      return problems;
    }
    for (int i = 0; i < fields.length(); i++) {
      Object name = fields.opt(i);
      if (!(name instanceof String) || StringUtils.isBlank((String) name)) {
        problems.add(KEY_FIELDS + "[" + i + "] must be a non-blank field name, not " + name);
      }
    }
    return problems;
  }

  /**
   * The fields this entity's configuration names as its default list projection.
   *
   * <p>An unusable configuration is logged and read as unconfigured: the fallback is the window's
   * list columns, which is still a correct answer — nothing is exposed that the agent could not
   * already ask for.</p>
   *
   * @param entity the SchemaForge entity
   * @return the configured names in order, or {@code null} when the entity declares none
   */
  static Set<String> configuredFields(SFEntity entity) {
    if (entity == null) {
      return null;
    }
    McpEntityConfig.Resolved resolved = McpEntityConfig.forEntity(entity);
    if (!resolved.isUsable()) {
      log.warn("MCP list default of entity '{}' ({}) ignored: its MCP_CONFIG is unusable — {}",
          entity.getName(), entity.getId(), resolved.describeProblems());
      return null;
    }
    JSONArray fields = resolved.section(NAME).map(b -> b.optJSONArray(KEY_FIELDS)).orElse(null);
    if (fields == null) {
      return null;
    }
    Set<String> names = new LinkedHashSet<>();
    for (int i = 0; i < fields.length(); i++) {
      names.add(fields.optString(i).trim());
    }
    return names;
  }
}
