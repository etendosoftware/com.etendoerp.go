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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The {@code etendo_discover} tool handler and its spec selection (IMP-53), split out of
 * {@link McpToolRouter} (java:S1448). Called by the router inside its admin-mode block, after the
 * unknown-argument guard and the spec access check have run.
 */
final class McpDiscoverTool {

  private McpDiscoverTool() {
  }

  /**
   * List all active specs the current user can access, or only the ones named by the optional
   * {@code spec} argument (IMP-53). Replicates NeoServlet.handleDiscovery() logic.
   *
   * <p>Access is evaluated over the whole catalog either way: the reachable names are the
   * {@code available} list of the refusal when a requested name is not one of them, and an
   * unreachable spec is refused exactly like a non-existent one, so the narrowed call reveals
   * nothing the full one would not.</p>
   */
  static JSONObject handle(JSONObject arguments) throws Exception {
    OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
    specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    specCriteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    List<SFSpec> allSpecs = specCriteria.list();

    List<SFSpec> reachable = new ArrayList<>();
    for (SFSpec spec : allSpecs) {
      if (McpToolRouterSupport.hasSpecAccess(spec, spec.getSpecType())) {
        reachable.add(spec);
      }
    }
    List<SFSpec> selected = selectSpecs(reachable,
        arguments == null ? null : arguments.opt(McpConstants.PARAM_SPEC));

    JSONArray specsArray = new JSONArray();
    for (SFSpec spec : selected) {
      String specType = spec.getSpecType();
      // ETP-4254: load the included entities ONCE per W spec — the entity summary, the
      // caller-derived primaryEntity (IMP-9/ETP-4601) and the spec-level readOnly marker are
      // all derived from this same list, so none of them costs an extra query.
      List<SFEntity> includedEntities = "W".equals(specType)
          ? McpToolRouterSupport.listIncludedEntities(spec.getId()) : null;
      JSONArray entities = "W".equals(specType)
          ? McpToolRouterSupport.buildEntitySummaryArray(includedEntities) : null;
      // IMP-9: derived here (not inside buildDiscoverSpec) so that method stays DAL-free —
      // handle() already runs in the live/admin OBContext resolving tab levels needs.
      String primaryEntity = "W".equals(specType)
          ? McpToolRouterSupport.resolvePrimaryEntityName(includedEntities)
          : null;
      specsArray.put(McpToolRouterSupport.buildDiscoverSpec(
          spec, specType, entities, primaryEntity, includedEntities));
    }

    JSONObject result = new JSONObject();
    result.put("specs", specsArray);
    result.put("count", specsArray.length());
    result.put("guidance", McpToolRouterSupport.buildDocsGuidance());
    // ETP-5200: how to build an app link, advertised once per session instead of on every row.
    // Omitted entirely when no public app base URL is configured — see McpRecordUrls.
    JSONObject app = McpRecordUrls.buildAppMetadata();
    if (app != null) {
      result.put(McpRecordUrls.KEY_APP, app);
    }
    return McpToolRouter.wrapAsTextContent(result);
  }

  /**
   * The specs an {@code etendo_discover} call answers with (IMP-53): every reachable spec when no
   * {@code spec} argument was given, otherwise the reachable specs it names, in catalog order.
   *
   * @param reachable the specs this role reaches, in catalog order
   * @param requested the raw {@code spec} argument: absent/blank, a name, or an array of names
   * @return the specs to describe
   * @throws McpRoutingException {@code validation_error} on {@code spec}, naming every requested
   *     name that is not reachable (in request order) and carrying the reachable names
   * @throws JSONException if an array element cannot be read
   */
  static List<SFSpec> selectSpecs(List<SFSpec> reachable, Object requested)
      throws JSONException {
    // Request order, so a refusal naming several unknown specs always lists them the same way.
    Set<String> wanted = new LinkedHashSet<>();
    if (requested instanceof JSONArray) {
      JSONArray names = (JSONArray) requested;
      for (int i = 0; i < names.length(); i++) {
        wanted.add(String.valueOf(names.get(i)).trim());
      }
    } else if (requested != null && requested != JSONObject.NULL) {
      wanted.add(String.valueOf(requested).trim());
    }
    wanted.remove("");
    if (wanted.isEmpty()) {
      return reachable;
    }
    List<String> reachableNames = new ArrayList<>();
    for (SFSpec spec : reachable) {
      reachableNames.add(spec.getName());
    }
    List<String> unknown = new ArrayList<>();
    for (String name : wanted) {
      if (!reachableNames.contains(name)) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw McpRoutingException.unknownDiscoverSpec(unknown, reachableNames);
    }
    List<SFSpec> selected = new ArrayList<>();
    for (SFSpec spec : reachable) {
      if (wanted.contains(spec.getName())) {
        selected.add(spec);
      }
    }
    return selected;
  }
}
