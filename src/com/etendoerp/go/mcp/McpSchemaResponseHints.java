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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The derivations {@code neo_schema} adds on top of the field list: what a caller must supply,
 * and what the server will fill in for it.
 *
 * <p>Split out of {@link McpToolRouter} under Sonar's class-size rule (S1448). They belong
 * together because each answers the same question from a different side - what does the agent
 * still have to provide - and each one is wrong in the same direction if it fails: an agent told
 * it must supply a value the server already resolves will send it, which is exactly the class of
 * defect this module has been closing.</p>
 */
final class McpSchemaResponseHints {

  private static final Logger log = LogManager.getLogger(McpSchemaResponseHints.class);

  /** The defaults service is asked as a read; the router spells this the same way. */
  private static final String HTTP_METHOD_GET = "GET";

  private McpSchemaResponseHints() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * The sentence that tells an agent a child entity cannot be read without its parent.
   *
   * @param scope the parent scope resolved for the entity
   * @return the hint, or an empty string when the entity needs no parent
   */
  static String parentHint(McpParentScope.Scope scope) {
    List<String> required = scope.requiredVerbs();
    if (required.isEmpty()) {
      return "";
    }
    String parent = scope.getParentEntity() == null ? "parent"
        : "'" + scope.getParentEntity() + "'";
    return "This is a child entity: pass parentId (the id of the " + parent + " record) on "
        + String.join(", ", required)
        + " \u2014 there is no global list of these records to read without it. ";
  }

  /**
   * The fields the server resolves on its own, asked of the defaults service rather than inferred
   * from {@code AD_Column.DefaultValue}.
   *
   * <p>Falls back to an empty set on any failure: reporting nothing as server-defaulted leaves
   * the agent supplying values it did not have to, which is wasteful but correct, whereas
   * claiming a default that does not exist would have it omit a value nothing fills.</p>
   *
   * @param specName   the spec being described
   * @param entityName the entity being described
   * @param adTab      the AD tab behind the entity
   * @param sfEntity   the SchemaForge entity, may be {@code null}
   * @return the property names the server resolves, never {@code null}
   */
  static Set<String> serverDefaultedNames(String specName, String entityName, Tab adTab,
      SFEntity sfEntity) {
    try {
      NeoContext ctx = NeoContext.builder()
          .specName(specName)
          .entityName(entityName)
          .httpMethod(HTTP_METHOD_GET)
          .adTab(adTab)
          .sfEntity(sfEntity)
          .obContext(OBContext.getOBContext())
          .queryParams(new HashMap<>())
          .build();
      NeoResponse defaults = NeoDefaultsService.resolveDefaults(ctx, null);
      if (defaults == null || defaults.getHttpStatus() >= 400) {
        return Collections.emptySet();
      }
      return McpSchemaCreateView.resolvedDefaultNames(defaults.getBody());
    } catch (Exception e) {
      log.warn("neo_schema view:create could not resolve defaults for {}/{}; falling back to the "
          + "AD_Column.DefaultValue rule", specName, entityName, e);
      return Collections.emptySet();
    }
  }

  /**
   * Whether any field in the list is one the agent may supply.
   *
   * @param fieldsArray the schema field array, may be {@code null}
   * @return {@code true} when at least one field is agent-suppliable
   */
  static boolean hasAnyAgentSuppliableField(JSONArray fieldsArray) {
    if (fieldsArray == null) {
      return false;
    }
    for (int i = 0; i < fieldsArray.length(); i++) {
      JSONObject field = fieldsArray.optJSONObject(i);
      if (field != null && McpSchemaFieldBuilder.isAgentSuppliable(field)) {
        return true;
      }
    }
    return false;
  }
}
