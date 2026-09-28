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
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * {@code neo_schema} answers for entities whose handler declares named actions (ETP-5468): the
 * action catalog served by {@link McpActionsView#buildDeclaredResponse} instead of the AD field
 * schema.
 *
 * <p>Extracted from {@link McpToolRouter} so that class stays under the Sonar per-class
 * method-count limit (java:S1448). Behavior is unchanged: {@code McpToolRouter#handleSchema} calls
 * {@link #reportSpecActionsSchema} and {@link #declaredActionsOf} at the same points as before.</p>
 */
final class McpReportActionsSchema {

  private McpReportActionsSchema() {
    // utility class — no instances
  }

  /**
   * {@code neo_schema} for a report spec whose handler declares named actions (ETP-5468): the
   * action catalog of the requested entity. When {@code entity} is omitted and exactly one included
   * entity declares actions, that one is used; with none or several, {@code null} lets the generic
   * path answer (its "Missing required argument: entity" stays the error).
   *
   * @return the catalog, or {@code null} when the spec is not such a spec / the entity declares none
   */
  static JSONObject reportSpecActionsSchema(String specName, JSONObject args)
      throws JSONException {
    SFSpec spec = actionReportSpec(specName);
    if (spec == null) {
      return null;
    }
    String requested = args != null
        ? StringUtils.trimToNull(args.optString(McpConstants.PARAM_ENTITY, null)) : null;
    SFEntity target = requested != null
        ? McpToolRouterSupport.findIncludedEntity(spec.getId(), requested)
        : soleActionEntity(spec);
    Map<String, NeoActionContract> contracts =
        target != null ? declaredActionsOf(target) : Collections.emptyMap();
    if (target == null || contracts.isEmpty()) {
      return null;
    }
    return McpToolRouter.wrapAsTextContent(
        McpActionsView.buildDeclaredResponse(specName, target.getName(), contracts));
  }

  /**
   * The active report spec named {@code specName} when it declares named actions (ETP-5468), or
   * {@code null} otherwise — including a lookup failure, a missing/non-report spec, or a report
   * spec whose handler declares none. Only a spec that actually declares actions is handled here.
   * Every other report spec returns before any entity lookup, so its {@code neo_schema} answer
   * stays exactly the generic path's (e.g. the 422 pointing at its {@code generate_*} tool) —
   * BUG-4.
   */
  private static SFSpec actionReportSpec(String specName) {
    SFSpec spec;
    try {
      spec = McpToolRouterSupport.findActiveSpecByName(specName);
    } catch (Exception e) {
      return null;
    }
    if (spec == null || !"R".equals(spec.getSpecType())) {
      return null;
    }
    if (!NeoActionContract.resolve(spec).isPresent()) {
      return null;
    }
    return spec;
  }

  /**
   * The spec's single included entity whose handler declares named actions (ETP-5468), or
   * {@code null} when none or several do — the ambiguous cases are left for the caller to refuse
   * rather than guessing which entity was meant.
   */
  private static SFEntity soleActionEntity(SFSpec spec) {
    SFEntity target = null;
    int declaring = 0;
    for (SFEntity candidate : McpToolRouterSupport.listIncludedEntities(spec.getId())) {
      if (!declaredActionsOf(candidate).isEmpty()) {
        declaring++;
        target = candidate;
      }
    }
    return declaring == 1 ? target : null;
  }

  /**
   * The named actions the entity's handler declares (ETP-5468), or an empty map. Looked up quietly:
   * a CDI failure must not break {@code neo_schema} for an ordinary entity.
   */
  static Map<String, NeoActionContract> declaredActionsOf(SFEntity sfEntity) {
    NeoHandler handler = NeoHandlerLookup.byQualifierQuietly(sfEntity.getJavaQualifier());
    Map<String, NeoActionContract> contracts = handler != null ? handler.actionContracts() : null;
    return contracts != null ? contracts : Collections.emptyMap();
  }
}
