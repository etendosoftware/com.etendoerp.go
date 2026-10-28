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

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * {@code etendo_list(allParents:true)}: a child entity read across every parent, asked for on
 * purpose (ETP-5852, M-13 / M-46). Only where {@code MCP_CONFIG.parent.allParents} allows it; the
 * parent stays required otherwise, and {@code parentId} stays the way to read one parent.
 *
 * <p>The child's table is often shared by several specs ({@code C_OrderLine} holds sales and
 * purchase order lines, quotations and returns), so a read across parents keeps only the children
 * whose parent passes the parent tab's own filter — the clause {@code etendo_list} applies to the
 * header of the same spec: {@code sales-order/lines} answers lines of sales orders, never of
 * purchases.</p>
 */
final class McpAllParents {

  static final String KEY_NOTE = "allParentsNote";

  /** {@code e.} as the alias of the parent's where clause, not inside another path. */
  private static final Pattern ALIAS = Pattern.compile("(?<![\\w.$])e\\.");
  private static final String PARENT_ALIAS = "p.";

  private McpAllParents() {
  }

  /**
   * The predicate that scopes a read across parents to the parent tab's filter.
   *
   * @return the HQL predicate over {@code e}, or {@code null} when the parent tab has no filter
   * @throws McpRoutingException when parentId is sent too, or the entity does not allow it
   */
  static String scopeWhere(String specName, String entityName, SFEntity sfEntity,
      String parentId) {
    if (StringUtils.isNotBlank(parentId)) {
      throw McpRoutingException.allParentsWithParent();
    }
    McpParentScope.Scope scope = McpParentScope.forEntity(sfEntity);
    if (!scope.allowsAllParents()) {
      throw McpRoutingException.allParentsNotAllowed(specName, entityName);
    }
    Tab parentTab = scope.getAllParentsTab();
    return scopeWhere(scope.getParentField(), parentTab.getTable().getName(),
        McpSpecScope.tabWhere(parentTab));
  }

  /**
   * @param parentField the child's link to its parent
   * @param parentEntity the parent's DAL entity
   * @param parentWhere the parent tab's filter over {@code e}, or {@code null}
   * @return {@code e.<parentField>.id in (select p.id from <parentEntity> p where …)}, or
   *         {@code null} without a filter
   */
  static String scopeWhere(String parentField, String parentEntity, String parentWhere) {
    if (StringUtils.isBlank(parentWhere)) {
      return null;
    }
    return "e." + parentField + ".id in (select p.id from " + parentEntity + " p where "
        + ALIAS.matcher(parentWhere).replaceAll(PARENT_ALIAS) + ")";
  }

  /** Says, in the answer, that it spans every parent and how to read one instead. */
  static void annotate(JSONObject flat, SFEntity sfEntity) throws JSONException {
    McpParentScope.Scope scope = McpParentScope.forEntity(sfEntity);
    flat.put(McpConstants.PARAM_ALL_PARENTS, true);
    flat.put(KEY_NOTE, "Records across every " + scope.getParentEntity() + " this spec lists"
        + (scope.getParentField() == null ? "" : "; each row names its parent in '"
            + scope.getParentField() + "'")
        + ". To read one parent's records, call etendo_list with parentId instead.");
  }
}
