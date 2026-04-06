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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.webhooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns the full AD_Menu tree from Etendo as nested JSON.
 * Uses a recursive CTE to traverse the menu hierarchy from ad_treenode/ad_menu.
 *
 * GET /webhooks/SFListMenu         → full tree
 * GET /webhooks/SFListMenu?q=sales → flat filtered list
 */
public class SFListMenu extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFListMenu.class);

  /** JSON key used for the nested children array in menu tree nodes. */
  private static final String CHILDREN = "children";

  private static final String MENU_TREE_SQL =
      "WITH RECURSIVE menu_tree AS ("
      + "  SELECT tn.node_id, tn.parent_id, tn.seqno,"
      + "    m.name, m.issummary, m.action, m.ad_window_id, m.ad_process_id, m.ad_form_id,"
      + "    0 AS depth"
      + "  FROM ad_treenode tn"
      + "  JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND tn.parent_id = '0' AND m.isactive = 'Y'"
      + "  UNION ALL"
      + "  SELECT tn.node_id, tn.parent_id, tn.seqno,"
      + "    m.name, m.issummary, m.action, m.ad_window_id, m.ad_process_id, m.ad_form_id,"
      + "    mt.depth + 1"
      + "  FROM ad_treenode tn"
      + "  JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  JOIN menu_tree mt ON tn.parent_id = mt.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND m.isactive = 'Y'"
      + ") "
      + "SELECT node_id, parent_id, seqno, name, issummary, action,"
      + "  ad_window_id, ad_process_id, ad_form_id, depth"
      + " FROM menu_tree"
      + " ORDER BY depth, parent_id, seqno";

  private static final String SEARCH_SQL =
      "SELECT m.ad_menu_id AS node_id, m.name, m.issummary, m.action,"
      + "  m.ad_window_id, m.ad_process_id, m.ad_form_id"
      + " FROM ad_menu m"
      + " WHERE m.isactive = 'Y' AND LOWER(m.name) LIKE :query"
      + " ORDER BY m.name";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String query = parameter.get("q");

      JSONObject result;
      if (query != null && !query.trim().isEmpty()) {
        result = searchMenu(query.trim());
      } else {
        result = buildMenuTree();
      }

      responseVars.put("result", result.toString());

    } catch (Exception e) {
      log.error("Error in SFListMenu", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Builds the full nested menu tree using a recursive CTE.
   */
  @SuppressWarnings("unchecked")
  private JSONObject buildMenuTree() throws Exception {
    Session session = OBDal.getInstance().getSession();
    NativeQuery<Object[]> nativeQuery = session.createNativeQuery(MENU_TREE_SQL);
    List<Object[]> rows = nativeQuery.getResultList();

    // Index: node_id -> JSONObject (with children array)
    Map<String, JSONObject> nodeMap = new LinkedHashMap<>();
    // Track root-level nodes
    List<JSONObject> roots = new ArrayList<>();

    for (Object[] row : rows) {
      String nodeId = str(row[0]);
      String parentId = str(row[1]);
      String name = str(row[3]);
      String isSummary = str(row[4]);
      String action = str(row[5]);
      String windowId = str(row[6]);
      String processId = str(row[7]);
      String formId = str(row[8]);

      JSONObject node = new JSONObject();
      node.put("id", nodeId);
      node.put("name", name);
      node.put("type", resolveType(isSummary, action));

      if (windowId != null && !windowId.isEmpty()) {
        node.put("windowId", windowId);
      }
      if (processId != null && !processId.isEmpty()) {
        node.put("processId", processId);
      }
      if (formId != null && !formId.isEmpty()) {
        node.put("formId", formId);
      }

      // Folders always get a children array
      if ("Y".equals(isSummary)) {
        node.put(CHILDREN, new JSONArray());
      }

      nodeMap.put(nodeId, node);

      // Attach to parent or mark as root
      if ("0".equals(parentId) || !nodeMap.containsKey(parentId)) {
        roots.add(node);
      } else {
        JSONObject parent = nodeMap.get(parentId);
        if (parent.has(CHILDREN)) {
          parent.getJSONArray(CHILDREN).put(node);
        } else {
          // Parent wasn't marked as folder but has children — add array
          JSONArray children = new JSONArray();
          children.put(node);
          parent.put(CHILDREN, children);
        }
      }
    }

    JSONArray treeArray = new JSONArray();
    for (JSONObject root : roots) {
      treeArray.put(root);
    }

    JSONObject result = new JSONObject();
    result.put("tree", treeArray);
    result.put("count", rows.size());
    return result;
  }

  /**
   * Searches menu items by name, returns a flat list.
   */
  @SuppressWarnings("unchecked")
  private JSONObject searchMenu(String searchTerm) throws Exception {
    Session session = OBDal.getInstance().getSession();
    NativeQuery<Object[]> nativeQuery = session.createNativeQuery(SEARCH_SQL);
    nativeQuery.setParameter("query", "%" + searchTerm.toLowerCase() + "%");
    List<Object[]> rows = nativeQuery.getResultList();

    JSONArray items = new JSONArray();
    for (Object[] row : rows) {
      String nodeId = str(row[0]);
      String name = str(row[1]);
      String isSummary = str(row[2]);
      String action = str(row[3]);
      String windowId = str(row[4]);
      String processId = str(row[5]);
      String formId = str(row[6]);

      JSONObject item = new JSONObject();
      item.put("id", nodeId);
      item.put("name", name);
      item.put("type", resolveType(isSummary, action));

      if (windowId != null && !windowId.isEmpty()) {
        item.put("windowId", windowId);
      }
      if (processId != null && !processId.isEmpty()) {
        item.put("processId", processId);
      }
      if (formId != null && !formId.isEmpty()) {
        item.put("formId", formId);
      }

      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put("tree", items);
    result.put("count", items.length());
    return result;
  }

  /**
   * Maps AD_Menu issummary/action to a human-readable type string.
   */
  private static String resolveType(String isSummary, String action) {
    if ("Y".equals(isSummary)) {
      return "folder";
    }
    if (action == null) {
      return "unknown";
    }
    switch (action) {
      case "W": return "window";
      case "P": return "process";
      case "R": return "report";
      case "X": return "form";
      default: return "other";
    }
  }

  /**
   * Safely converts a result column to String, handling null.
   */
  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
