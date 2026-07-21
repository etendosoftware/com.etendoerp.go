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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns the AD_Menu tree from Etendo as nested JSON, filtered by the current
 * role's window/process access (see {@code AD_Window_Access} / {@code AD_Process_Access}), as
 * well as {@code OBUIAPP_Process} access for menu entries whose {@code action} is
 * {@code 'OBUIAPP_Process'} (linked via {@code AD_Menu.em_obuiapp_process_id} rather than
 * {@code ad_window_id}/{@code ad_process_id}). Uses a recursive CTE to traverse the menu
 * hierarchy from ad_treenode/ad_menu.
 *
 * <p>The current role is captured once, at the very top of {@link #get(Map, Map)}, before
 * {@link OBContext#setAdminMode()} is entered. Admin mode is only used to bypass row-level
 * security filters on the underlying native queries — the access decisions themselves are
 * always made against the role captured up front, never against ambient context state, so a
 * user is never able to see more than their role grants regardless of what admin mode does
 * (or does not) do to the ambient {@link OBContext}. This also holds for the
 * {@code obuiappProcessId} check, which calls the role-parameterized
 * {@link NeoAccessHelper#hasObuiappProcessAccess(Role, String)} overload with the captured
 * role — matching the window/process branches — rather than an ambient-role overload that
 * would re-resolve the role from {@link OBContext#getOBContext()} at check time.</p>
 *
 * <p>A user with no role assigned gets an empty menu — the query is not even run.</p>
 *
 * GET /webhooks/SFListMenu         → full tree, filtered by the current role's access
 * GET /webhooks/SFListMenu?q=sales → flat filtered list, filtered by the current role's access
 */
public class SFListMenu extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFListMenu.class);

  /** JSON key used for the nested children array in menu tree nodes. */
  private static final String CHILDREN = "children";

  /** JSON key used for a menu node's linked AD_Window id. */
  private static final String WINDOW_ID = "windowId";

  /** JSON key used for a menu node's linked AD_Process id. */
  private static final String PROCESS_ID = "processId";

  /** JSON key used for a menu node's linked OBUIAPP_Process id. */
  private static final String OBUIAPP_PROCESS_ID = "obuiappProcessId";

  /** JSON key used for the accessible node count in a menu result. */
  private static final String COUNT = "count";

  private static final String MENU_TREE_SQL =
      "WITH RECURSIVE menu_tree AS ("
      + "  SELECT tn.node_id, tn.parent_id, tn.seqno,"
      + "    m.name, m.issummary, m.action, m.ad_window_id, m.ad_process_id,"
      + "    m.em_obuiapp_process_id, m.ad_form_id,"
      + "    0 AS depth"
      + "  FROM ad_treenode tn"
      + "  JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND tn.parent_id = '0' AND m.isactive = 'Y'"
      + "  UNION ALL"
      + "  SELECT tn.node_id, tn.parent_id, tn.seqno,"
      + "    m.name, m.issummary, m.action, m.ad_window_id, m.ad_process_id,"
      + "    m.em_obuiapp_process_id, m.ad_form_id,"
      + "    mt.depth + 1"
      + "  FROM ad_treenode tn"
      + "  JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  JOIN menu_tree mt ON tn.parent_id = mt.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND m.isactive = 'Y'"
      + ") "
      + "SELECT node_id, parent_id, seqno, name, issummary, action,"
      + "  ad_window_id, ad_process_id, em_obuiapp_process_id, ad_form_id, depth"
      + " FROM menu_tree"
      + " ORDER BY depth, parent_id, seqno";

  private static final String SEARCH_SQL =
      "SELECT m.ad_menu_id AS node_id, m.name, m.issummary, m.action,"
      + "  m.ad_window_id, m.ad_process_id, m.em_obuiapp_process_id, m.ad_form_id"
      + " FROM ad_menu m"
      + " WHERE m.isactive = 'Y' AND LOWER(m.name) LIKE :query"
      + " ORDER BY m.name";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode. Admin mode is only meant to
    // bypass row-level security on the query itself; access decisions must always be made
    // against the role that was actually resolved for this request, never against whatever
    // the ambient OBContext happens to expose once admin mode is active.
    Role currentRole = resolveCurrentRole();

    if (currentRole == null) {
      // No role assigned → empty menu, short-circuit before even touching the DB.
      responseVars.put("result", emptyResult().toString());
      return;
    }

    OBContext.setAdminMode();
    try {
      String query = parameter.get("q");

      JSONObject result;
      if (query != null && !query.trim().isEmpty()) {
        result = searchMenu(query.trim(), currentRole);
      } else {
        result = buildMenuTree(currentRole);
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
   * Resolves the role of the current request from the ambient {@link OBContext}, tolerating a
   * missing context. Must be called before {@link OBContext#setAdminMode()} — see the class
   * javadoc for why.
   *
   * @return the current {@link Role}, or {@code null} if there is no context or no role
   */
  private static Role resolveCurrentRole() {
    OBContext context = OBContext.getOBContext();
    return context == null ? null : context.getRole();
  }

  /**
   * Builds the empty-menu result used when the current request has no role assigned.
   */
  private static JSONObject emptyResult() {
    try {
      JSONObject result = new JSONObject();
      result.put("tree", new JSONArray());
      result.put(COUNT, 0);
      return result;
    } catch (JSONException e) {
      // JSONObject#put never throws for a non-null key; unreachable in practice.
      throw new IllegalStateException("Unable to build empty menu result", e);
    }
  }

  /**
   * Builds the full nested menu tree using a recursive CTE, then prunes it down to the nodes
   * {@code role} has access to.
   *
   * <p>Tree construction and access filtering are deliberately two separate passes: the first
   * pass (unchanged from before this access filter existed) builds the complete tree top-down
   * while streaming rows in a single pass; the second pass walks that tree bottom-up (post-order)
   * to filter window/process nodes and prune folders left with zero accessible children.</p>
   */
  @SuppressWarnings("unchecked")
  private JSONObject buildMenuTree(Role role) throws Exception {
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
      String obuiappProcessId = str(row[8]);
      String formId = str(row[9]);

      JSONObject node = new JSONObject();
      node.put("id", nodeId);
      node.put("name", name);
      node.put("type", resolveType(isSummary, action));

      putIfNotEmpty(node, WINDOW_ID, windowId);
      putIfNotEmpty(node, PROCESS_ID, processId);
      putIfNotEmpty(node, OBUIAPP_PROCESS_ID, obuiappProcessId);
      putIfNotEmpty(node, "formId", formId);

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

    // Second pass: post-order filter/prune against the captured role.
    JSONArray treeArray = new JSONArray();
    for (JSONObject root : roots) {
      JSONObject filteredRoot = filterNode(root, role);
      if (filteredRoot != null) {
        treeArray.put(filteredRoot);
      }
    }

    JSONObject result = new JSONObject();
    result.put("tree", treeArray);
    result.put(COUNT, countNodes(treeArray));
    return result;
  }

  /**
   * Searches menu items by name, returns a flat list filtered by {@code role}'s access.
   */
  @SuppressWarnings("unchecked")
  private JSONObject searchMenu(String searchTerm, Role role) throws Exception {
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
      String obuiappProcessId = str(row[6]);
      String formId = str(row[7]);

      JSONObject item = new JSONObject();
      item.put("id", nodeId);
      item.put("name", name);
      item.put("type", resolveType(isSummary, action));

      putIfNotEmpty(item, WINDOW_ID, windowId);
      putIfNotEmpty(item, PROCESS_ID, processId);
      putIfNotEmpty(item, OBUIAPP_PROCESS_ID, obuiappProcessId);
      putIfNotEmpty(item, "formId", formId);

      // Flat list: no folder-pruning concern, just keep or drop each leaf item.
      if (isNodeAccessible(item, role)) {
        items.put(item);
      }
    }

    JSONObject result = new JSONObject();
    result.put("tree", items);
    result.put(COUNT, items.length());
    return result;
  }

  /**
   * Recursively filters {@code node} against {@code role}'s access, post-order (children first).
   *
   * <p>Folder nodes ({@code type == "folder"}) are never filtered directly — instead their
   * children are filtered first, and the folder itself is pruned (returns {@code null}) only if
   * it ends up with zero accessible children. Leaf nodes are kept or dropped based on
   * {@link #isNodeAccessible(JSONObject, Role)}.</p>
   *
   * @param node the node to filter (mutated in place when kept: its children array, if any, is
   *             replaced with the filtered one)
   * @param role the role to check access against
   * @return {@code node} (with filtered children, if applicable) when it should be kept, or
   *         {@code null} when it should be pruned
   */
  private JSONObject filterNode(JSONObject node, Role role) throws JSONException {
    if ("folder".equals(node.getString("type"))) {
      JSONArray children = node.has(CHILDREN) ? node.getJSONArray(CHILDREN) : new JSONArray();
      JSONArray keptChildren = new JSONArray();
      for (int i = 0; i < children.length(); i++) {
        JSONObject filteredChild = filterNode(children.getJSONObject(i), role);
        if (filteredChild != null) {
          keptChildren.put(filteredChild);
        }
      }
      if (keptChildren.length() == 0) {
        return null;
      }
      node.put(CHILDREN, keptChildren);
      return node;
    }
    return isNodeAccessible(node, role) ? node : null;
  }

  /**
   * Whether {@code role} can see {@code node} in the menu.
   *
   * <p>Nodes carrying a {@code windowId} are checked via
   * {@link NeoAccessHelper#hasWindowAccess(Role, String)} (any-tier access — read-only or full —
   * is enough to appear in the menu). Nodes carrying a {@code processId} are checked via
   * {@link NeoAccessHelper#hasProcessAccess(Role, String)}. Nodes carrying an
   * {@code obuiappProcessId} (menu entries with {@code action = 'OBUIAPP_Process'}, whose real
   * link lives in {@code AD_Menu.em_obuiapp_process_id} rather than {@code ad_window_id}/
   * {@code ad_process_id}) are checked via
   * {@link NeoAccessHelper#hasObuiappProcessAccess(Role, String)}, using this method's {@code role}
   * parameter — the same captured-role guarantee the windowId/processId checks above already
   * follow. A node carrying more than one of
   * these IDs must pass every check it carries. A node carrying none of them (typically
   * {@code report}/{@code form}/{@code other} typed nodes with no OBUIAPP link) is left
   * unfiltered — out of scope for this ticket, which is about
   * {@code AD_Window_Access}/{@code AD_Process_Access}/{@code OBUIAPP} process access.</p>
   */
  private static boolean isNodeAccessible(JSONObject node, Role role) throws JSONException {
    boolean accessible = true;
    if (node.has(WINDOW_ID)) {
      accessible = NeoAccessHelper.hasWindowAccess(role, node.getString(WINDOW_ID));
    }
    if (accessible && node.has(PROCESS_ID)) {
      accessible = NeoAccessHelper.hasProcessAccess(role, node.getString(PROCESS_ID));
    }
    if (accessible && node.has(OBUIAPP_PROCESS_ID)) {
      accessible = NeoAccessHelper.hasObuiappProcessAccess(role, node.getString(OBUIAPP_PROCESS_ID));
    }
    return accessible;
  }

  /**
   * Counts every node in {@code nodes}, recursively including their {@code children} arrays —
   * used to recompute {@code count} after pruning, since the raw DB row count no longer applies.
   */
  private static int countNodes(JSONArray nodes) throws JSONException {
    int count = 0;
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject node = nodes.getJSONObject(i);
      count++;
      if (node.has(CHILDREN)) {
        count += countNodes(node.getJSONArray(CHILDREN));
      }
    }
    return count;
  }

  /**
   * Adds {@code key}/{@code value} to {@code obj} only when {@code value} is non-null and non-empty,
   * avoiding spurious null entries in the JSON output.
   */
  private static void putIfNotEmpty(JSONObject obj, String key, String value) throws JSONException {
    if (value != null && !value.isEmpty()) {
      obj.put(key, value);
    }
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
