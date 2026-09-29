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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.client.application.Process;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-5485 — the single builder of the window × role permission {@code matrix} (and its
 * Informes sibling {@code reportsMatrix}): which rows exist, which top-level menu category each
 * belongs to, and every role's tri-state tier on each row.
 *
 * <p>Both role-matrix webhooks call it — {@code SFRolesOverview} ("Configuración &gt; Roles") and
 * {@code SFSystemRoleTemplates} with {@code includeMatrix=true} (the User window's "Roles del
 * usuario" tab). Before ETP-5485 the User tab rebuilt its rows on its own from the per-role
 * {@code windows[]} arrays, so every rule added to the Roles page matrix had to be hand-copied to
 * it: the ETP-5071 proxy rows never were, which is why "Modelos Fiscales" and "Documentos no
 * contabilizados" were missing from that tab. Sharing one builder makes "same rows in both
 * views" hold by construction. Same pattern as {@link ReportAccessCatalog}, which already shares
 * the Informes rows between the two webhooks.</p>
 *
 * <p>Static and stateless. Every query disables readable-client/org filtering, so it works
 * unchanged for a tenant role and for a system-client ({@code AD_Client_ID = '0'}) template.</p>
 *
 * <p><b>3 windowless {@code matrix} rows (ETP-5071).</b> "Monitor Fiscal", "Modelos Fiscales" and
 * "Documentos no contabilizados" are real, visible Etendo GO sidebar entries with no {@code
 * AD_Window_ID} of their own — 3 of the "twelve matrix rows" {@code TemplateRoleWindowAccess}'s
 * javadoc documents as a known gap. Each synthetic row's access is resolved from a REAL {@code
 * AD_Window_Access}/{@code OBUIAPP_Process_Access} grant on a different, related entity — see
 * {@link #FISCAL_MONITOR_PROXY_WINDOW_ID}, {@link #TAX_MODELS_PROXY_WINDOW_ID} and {@link
 * #NOT_POSTED_DOCS_PROXY_PROCESS_ID}. Only 2 of the 3 ever add a NEW row: SII Monitor ({@code
 * FISCAL_MONITOR_PROXY_WINDOW_ID}) already produces its own real row (see {@link
 * #buildMatrix(Map, Map)}'s duplicate-id guard). None of the 3 ever reaches a role's own
 * {@code windows[]} ({@link #windowsJson(Map, Map)}).</p>
 *
 * <p>Category names are the raw (English) {@code AD_Menu.name} of each window's top-level
 * folder; the frontend maps/translates them (and its {@code menu.json} overrides name and
 * category for a matched id), it never renders them verbatim.</p>
 */
public final class RoleAccessMatrix {

  private RoleAccessMatrix() {
    // Static utility, never instantiated.
  }

  /** Access-tier value for a window with full (read+write) access. */
  public static final String FULL = "full";

  /** Access-tier value for a window with read-only access. */
  public static final String READ_ONLY = "read-only";

  /** Access-tier value for a window a role cannot reach at all — {@code matrix}-only. */
  public static final String NONE = "none";

  private static final String ID = "id";
  private static final String NAME = "name";
  private static final String TIER = "tier";
  private static final String WINDOWS = "windows";
  private static final String REPORTS = "reports";
  private static final String CATEGORIES = "categories";
  private static final String ACCESS = "access";

  /** {@code ETGO_SF_SPEC.SPEC_TYPE} value identifying a window/CRUD spec. */
  private static final String SPEC_TYPE_WINDOW = "W";

  /**
   * Category bucket used for a window whose top-level {@code AD_Menu} folder could not be
   * resolved (should not happen for a window Etendo GO actually exposes, but degrading to a
   * named bucket is safer than silently dropping the window from the matrix).
   */
  public static final String OTHER_CATEGORY = "Other";

  /**
   * AD windows Etendo GO deliberately does NOT surface anywhere in its own UI, even though they
   * still have an active {@code SPEC_TYPE = 'W'} {@code ETGO_SF_SPEC} because NEO/MCP keeps
   * serving them read-only.
   *
   * <p>Filtered out in {@link #resolveActiveEtendoGoWindowsById()}, the single source every
   * matrix row derives from — so ONE entry here removes the window from "Configuración &gt;
   * Roles" AND from "Usuario &gt; Roles del usuario" (both render this class's {@code matrix}
   * since ETP-5485), and from {@code SFRolesOverview}'s per-role {@code windows}/{@code
   * windowCount}.
   *
   * <p>ETP-5068 — "Conversion Rate Downloader Log"
   * ({@code 6FEBA130CDE24CC09041FFA6117ADFA9}): an internal log of the conversion-rate
   * downloader job, dropped from the Etendo Go menu because it adds no value to the end user.
   * Administrators read it in Etendo classic, so the GO template roles deliberately KEEP their
   * {@code AD_Window_Access} grant (see {@code TemplateRoleWindowAccess}) — which is precisely
   * why the window cannot be hidden by revoking access, and why the exclusion lives here and
   * not in {@code SFListMenu}, whose tree must keep reporting the native AD menu as-is for its
   * other consumers.
   *
   * <p>ETP-5116 (QA fix) — 2 fiscal-family GO pages each aggregate 3 classic windows into a
   * single Etendo Go page, with the product owner picking ONE of the three as the row's
   * representative (its {@code AD_Window_Access} grant stands in for the whole page — same
   * mechanism as {@link #FISCAL_MONITOR_PROXY_WINDOW_ID}'s own rationale). The other two of each
   * trio would otherwise ALSO surface as their own separate {@code matrix} rows:
   * <ul>
   *   <li>"Fiscal Monitor" — representative is SII Monitor ({@link
   *   #FISCAL_MONITOR_PROXY_WINDOW_ID}); excluded: Monitor Verifactu
   *   ({@code F4675DAB02134762B66881DAE4672AD0}) and TBAI Facturas Enviadas
   *   ({@code 71F24BF89DE748B483BE87594747D6FB}).</li>
   *   <li>"Fiscal Configuration" — representative is SII Configuration
   *   ({@code C1D3A2A017AC4B82B9FEE6F4D2A0C55A}, not itself added here); excluded: Configuración
   *   TBAI ({@code C327DE215AC945F69363905840118177}) and Configuración Verifactu
   *   ({@code 27A453FA86974745977672F1A8DCCEFF}).</li>
   * </ul>
   *
   * <p>ETP-5116 (QA fix, second pass) — 5 more rows with no independent live Etendo Go page:
   * <ul>
   *   <li>"End Year Close" ({@code B5673F73F613496C8BEA22FB55E4E1E4}) — an action inside the
   *   Fiscal Calendar window ({@code AD_Window_ID = 117}), not independently reachable.</li>
   *   <li>"Location" ({@code 121}) and "Transaction Type"
   *   ({@code 82922976BB524D1BAA3CF8462B9219FE}) — classic embedded reference windows, never
   *   their own page.</li>
   *   <li>"Return to Vendor" ({@code C50A8AEE6F044825B5EF54FAAE76826F}) and "Return from
   *   Customer" ({@code FF808081330213E60133021822E40007}) — dead windows {@code
   *   TemplateRoleWindowAccess} stopped granting; their live replacements ("Return to Vendor
   *   Shipment" / "Return Receipt") show instead.</li>
   * </ul>
   *
   * <p>Note {@code Set.of(...)} rejects {@code contains(null)} with an NPE rather than returning
   * {@code false}, so callers must guard the id before probing this set.
   */
  static final Set<String> UI_EXCLUDED_WINDOW_IDS = Set.of(
      "6FEBA130CDE24CC09041FFA6117ADFA9",
      "F4675DAB02134762B66881DAE4672AD0",
      "71F24BF89DE748B483BE87594747D6FB",
      "C327DE215AC945F69363905840118177",
      "27A453FA86974745977672F1A8DCCEFF",
      "B5673F73F613496C8BEA22FB55E4E1E4",
      "121",
      "82922976BB524D1BAA3CF8462B9219FE",
      "C50A8AEE6F044825B5EF54FAAE76826F",
      "FF808081330213E60133021822E40007");

  /**
   * ETP-5071 — proxy {@code AD_Window_ID} standing in for "Monitor Fiscal" in the {@code matrix}.
   *
   * <p>"Monitor Fiscal" is a pure Etendo-GO-native custom page with no {@code AD_Window_ID} of
   * its own. Its frontend page aggregates data from 3 real classic windows — SII Monitor
   * ({@value}), Monitor Verifactu and TBAI Facturas Enviadas — and the product owner picked SII
   * Monitor as the single representative window whose {@code AD_Window_Access} grant stands in
   * for "can this role see Monitor Fiscal at all". Spot-checked live (2026-09-04): every role that
   * grants any of the three grants identical tiers on all three.
   *
   * <p>A deliberately narrow, human-chosen proxy for exactly this one row — NOT a general
   * solution to the other ~9 rows in {@code TemplateRoleWindowAccess}'s "twelve rows" list.
   */
  static final String FISCAL_MONITOR_PROXY_WINDOW_ID = "FEF76C3E0F104F06A89AAD15A4A4A35C";

  /**
   * ETP-5071 — proxy {@code AD_Window_ID} standing in for "Modelos Fiscales" in the {@code
   * matrix}, same windowless-page situation as {@link #FISCAL_MONITOR_PROXY_WINDOW_ID}. The
   * product owner chose the Tax Report window as this row's access proxy.
   */
  static final String TAX_MODELS_PROXY_WINDOW_ID = "3E8FEA1EA7404D979306C9EE7FD2E7E8";

  /**
   * ETP-5071 — proxy {@code OBUIAPP_Process_ID} standing in for "Documentos no contabilizados" in
   * the {@code matrix}. This page has no candidate classic WINDOW at all to proxy through (it is
   * a report-type spec with zero classic AD entity), so its access is resolved from the real
   * {@code OBUIAPP_Process_Access} grant on the "Not Posted Documents" process instead. See
   * {@link #resolveProcessTierMap(Role, String)}.
   */
  static final String NOT_POSTED_DOCS_PROXY_PROCESS_ID = "D6AB95CE52D34E1599590526115E26C6";

  /**
   * A single {@code matrix} row's identity (id + display name) — either a real {@link Window} or
   * one of the {@link #PROXY_MATRIX_ROWS} synthetic ETP-5071 rows. {@link #buildMatrix(Map, Map)}
   * groups/sorts/renders both kinds identically once expressed as this common shape.
   */
  private static final class MatrixRow {
    private final String id;
    private final String name;

    MatrixRow(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  /**
   * The 3 ETP-5071 synthetic {@code matrix} rows. Fallback display names only — the frontend's
   * own {@code menu.json} overrides both name and category for a matched id in practice.
   */
  private static final List<MatrixRow> PROXY_MATRIX_ROWS = List.of(
      new MatrixRow(FISCAL_MONITOR_PROXY_WINDOW_ID, "Fiscal Monitor"),
      new MatrixRow(TAX_MODELS_PROXY_WINDOW_ID, "Fiscal Models"),
      new MatrixRow(NOT_POSTED_DOCS_PROXY_PROCESS_ID, "Not Posted Documents"));

  private static final String WINDOW_CATEGORY_SQL =
      "WITH RECURSIVE menu_tree AS ("
      + "  SELECT tn.node_id, tn.parent_id, m.ad_window_id, tn.node_id AS top_id"
      + "  FROM ad_treenode tn JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND tn.parent_id = '0' AND m.isactive = 'Y'"
      + "  UNION ALL"
      + "  SELECT tn.node_id, tn.parent_id, m.ad_window_id, mt.top_id"
      + "  FROM ad_treenode tn JOIN ad_menu m ON m.ad_menu_id = tn.node_id"
      + "  JOIN menu_tree mt ON tn.parent_id = mt.node_id"
      + "  WHERE tn.ad_tree_id = '10' AND m.isactive = 'Y'"
      + ") "
      + "SELECT DISTINCT ON (mt.ad_window_id) mt.ad_window_id, top.name"
      + " FROM menu_tree mt JOIN ad_menu top ON top.ad_menu_id = mt.top_id"
      + " WHERE mt.ad_window_id IN (:windowIds)"
      + " ORDER BY mt.ad_window_id, top.name";

  // ── window set ──────────────────────────────────────────────────────────

  /**
   * Every distinct {@code AD_Window} backing an active, {@code SPEC_TYPE = 'W'} {@code
   * ETGO_SF_SPEC} — i.e. every window Etendo GO actually exposes today, INCLUDING the ones it
   * never shows in its UI ({@link #UI_EXCLUDED_WINDOW_IDS}). Callers that render rows want
   * {@link #resolveActiveEtendoGoWindowsById()} instead; this unfiltered view exists for {@code
   * SFSystemRoleTemplates}' per-role {@code windows[]}, which has always used the unexcluded set.
   *
   * @return the distinct exposed windows, keyed by id (insertion order)
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Window> resolveAllActiveEtendoGoWindowsById() {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, SPEC_TYPE_WINDOW));

    Map<String, Window> windowsById = new LinkedHashMap<>();
    for (SFSpec spec : (List<SFSpec>) criteria.list()) {
      Window window = spec.getADWindow();
      if (window != null && window.getId() != null) {
        windowsById.put(window.getId(), window);
      }
    }
    return windowsById;
  }

  /**
   * Every window Etendo GO exposes AND shows in its UI: {@link
   * #resolveAllActiveEtendoGoWindowsById()} minus {@link #UI_EXCLUDED_WINDOW_IDS}. This is the
   * window set every {@code matrix} is built from.
   *
   * @return the distinct UI-exposed windows, keyed by id (insertion order)
   */
  public static Map<String, Window> resolveActiveEtendoGoWindowsById() {
    return withoutUiExcluded(resolveAllActiveEtendoGoWindowsById());
  }

  /**
   * Returns a copy of {@code windowsById} without the {@link #UI_EXCLUDED_WINDOW_IDS} entries,
   * preserving insertion order — lets a caller that already resolved the unfiltered set derive
   * the UI set without a second query.
   */
  public static Map<String, Window> withoutUiExcluded(Map<String, Window> windowsById) {
    Map<String, Window> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, Window> entry : windowsById.entrySet()) {
      if (entry.getKey() != null && !UI_EXCLUDED_WINDOW_IDS.contains(entry.getKey())) {
        filtered.put(entry.getKey(), entry.getValue());
      }
    }
    return filtered;
  }

  // ── tiers ───────────────────────────────────────────────────────────────

  /**
   * {@code role}'s full row-id → tier map for the {@code matrix}: its real Etendo GO windows
   * ({@link #resolveWindowTierMap(Role, Set)} over {@code goWindowsById}) plus the ETP-5071 proxy
   * rows ({@link #mergeProxyAccessTiers(Role, Map)}). A row absent from the map means no grant.
   */
  public static Map<String, String> resolveTierMap(Role role, Map<String, Window> goWindowsById) {
    Map<String, String> tiers = resolveWindowTierMap(role, goWindowsById.keySet());
    mergeProxyAccessTiers(role, tiers);
    return tiers;
  }

  /**
   * Resolves {@code role}'s active {@code AD_Window_Access} rows into a window-id → tier map
   * ({@link #FULL} for {@code IsReadWrite = true}, {@link #READ_ONLY} otherwise), intersected
   * with {@code goWindowIds} — a role may hold native window-access rows for windows Etendo GO
   * never exposes, and those must not leak into the matrix.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, String> resolveWindowTierMap(Role role, Set<String> goWindowIds) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (WindowAccess access : (List<WindowAccess>) criteria.list()) {
      Window window = access.getWindow();
      if (window == null || !goWindowIds.contains(window.getId())) {
        continue;
      }
      tiers.put(window.getId(), Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
    }
    return tiers;
  }

  /**
   * ETP-5071 — merges the proxy access tiers for {@code role} into {@code tiers}, in place.
   *
   * <p>Only resolves {@link #TAX_MODELS_PROXY_WINDOW_ID} and {@link
   * #NOT_POSTED_DOCS_PROXY_PROCESS_ID} — deliberately NOT {@link #FISCAL_MONITOR_PROXY_WINDOW_ID}
   * (SII Monitor), which is a real, separately-exposed Etendo GO window whose tier is already in
   * {@code tiers}; resolving it again would be a wasted query for the same value.
   *
   * <p>Never pollutes {@link #windowsJson(Map, Map)}'s output: the two proxy ids are never keys in
   * the GO window map, so that method skips them.</p>
   */
  static void mergeProxyAccessTiers(Role role, Map<String, String> tiers) {
    tiers.putAll(resolveWindowTierMap(role, Set.of(TAX_MODELS_PROXY_WINDOW_ID)));
    tiers.putAll(resolveProcessTierMap(role, NOT_POSTED_DOCS_PROXY_PROCESS_ID));
  }

  /**
   * ETP-5071 — the {@code OBUIAPP_Process_Access} equivalent of {@link #resolveWindowTierMap(Role,
   * Set)}, for a single target process id. Same tier logic: {@link #FULL} for {@code
   * IsEditableField = true}, {@link #READ_ONLY} otherwise.
   *
   * @return a single-entry map ({@code obuiappProcessId -> tier}), or empty if {@code role} has
   *     no active grant for it
   */
  @SuppressWarnings("unchecked")
  static Map<String, String> resolveProcessTierMap(Role role, String obuiappProcessId) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (ProcessAccess access : (List<ProcessAccess>) criteria.list()) {
      Process process = access.getObuiappProcess();
      if (process == null || !obuiappProcessId.equals(process.getId())) {
        continue;
      }
      tiers.put(obuiappProcessId, Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
    }
    return tiers;
  }

  // ── JSON ────────────────────────────────────────────────────────────────

  /**
   * Turns a row-id → tier map into the sorted-by-name {@code windows} JSON array a role card
   * carries. Only ids present in {@code goWindowsById} are emitted, so the ETP-5071 proxy rows
   * never reach it.
   */
  public static JSONArray windowsJson(Map<String, String> tiers, Map<String, Window> goWindowsById)
      throws JSONException {
    List<JSONObject> windowJsons = new ArrayList<>();
    for (Map.Entry<String, String> entry : tiers.entrySet()) {
      Window window = goWindowsById.get(entry.getKey());
      if (window == null) {
        continue;
      }
      JSONObject windowJson = new JSONObject();
      windowJson.put(ID, window.getId());
      windowJson.put(NAME, window.getName());
      windowJson.put(TIER, entry.getValue());
      windowJsons.add(windowJson);
    }

    windowJsons.sort((a, b) -> {
      try {
        return a.getString(NAME).compareToIgnoreCase(b.getString(NAME));
      } catch (JSONException e) {
        return 0;
      }
    });

    JSONArray windows = new JSONArray();
    for (JSONObject windowJson : windowJsons) {
      windows.put(windowJson);
    }
    return windows;
  }

  /**
   * Builds the ETP-4907 {@code matrix}: every window in {@code goWindowsById} PLUS (ETP-5071)
   * the {@link #PROXY_MATRIX_ROWS} synthetic windowless rows — EXCEPT a proxy row whose id is
   * already a key in {@code goWindowsById} — grouped by top-level {@code AD_Menu} category
   * ({@link #resolveWindowCategories(Set)}), each with a per-role tri-state {@code access} map
   * built from {@code tierMapsByRoleId}; a role/row pair absent from that role's tier map
   * resolves to {@link #NONE}.
   *
   * <p><b>Duplicate-id guard (ETP-5071 fix).</b> {@link #FISCAL_MONITOR_PROXY_WINDOW_ID} (SII
   * Monitor) IS already a key in {@code goWindowsById}, so it produces its own real row. Appending
   * {@code PROXY_MATRIX_ROWS} unconditionally would add a SECOND row with the same id — a real
   * collision, since the frontend keys matrix rows by category+id. {@link
   * #TAX_MODELS_PROXY_WINDOW_ID} and {@link #NOT_POSTED_DOCS_PROXY_PROCESS_ID} are never keys in
   * {@code goWindowsById} (Tax Report has no active spec; the process id lives in a different
   * id-space), so they are always appended.</p>
   *
   * @param goWindowsById the UI window set ({@link #resolveActiveEtendoGoWindowsById()})
   * @param tierMapsByRoleId role id → {@link #resolveTierMap(Role, Map)}, in column order
   */
  public static JSONObject buildMatrix(Map<String, Window> goWindowsById,
      Map<String, Map<String, String>> tierMapsByRoleId) throws JSONException {
    List<MatrixRow> rows = new ArrayList<>();
    for (Window window : goWindowsById.values()) {
      rows.add(new MatrixRow(window.getId(), window.getName()));
    }
    for (MatrixRow proxyRow : PROXY_MATRIX_ROWS) {
      if (!goWindowsById.containsKey(proxyRow.id)) {
        rows.add(proxyRow);
      }
    }

    // Category lookup includes the 2 window-based proxy ids (SII Monitor, Tax Report) — both are
    // real AD_Window_IDs that may resolve a classic-AD-menu-tree category via the same SQL.
    // NOT_POSTED_DOCS_PROXY_PROCESS_ID is a process id, so it cannot resolve one and falls back
    // to OTHER_CATEGORY; the frontend's menu.json override replaces it anyway.
    Set<String> categoryLookupIds = new LinkedHashSet<>(goWindowsById.keySet());
    categoryLookupIds.add(FISCAL_MONITOR_PROXY_WINDOW_ID);
    categoryLookupIds.add(TAX_MODELS_PROXY_WINDOW_ID);
    Map<String, String> categoryByWindowId = resolveWindowCategories(categoryLookupIds);

    Map<String, List<MatrixRow>> rowsByCategory = new LinkedHashMap<>();
    for (MatrixRow row : rows) {
      String category = categoryByWindowId.getOrDefault(row.id, OTHER_CATEGORY);
      rowsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(row);
    }

    List<String> sortedCategories = new ArrayList<>(rowsByCategory.keySet());
    sortedCategories.sort(String.CASE_INSENSITIVE_ORDER);

    JSONArray categories = new JSONArray();
    for (String category : sortedCategories) {
      List<MatrixRow> rowsInCategory = rowsByCategory.get(category);
      rowsInCategory.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

      JSONArray windowsJson = new JSONArray();
      for (MatrixRow row : rowsInCategory) {
        JSONObject windowJson = new JSONObject();
        windowJson.put(ID, row.id);
        windowJson.put(NAME, row.name);
        windowJson.put(ACCESS, accessJson(row.id, tierMapsByRoleId));
        windowsJson.put(windowJson);
      }

      JSONObject categoryJson = new JSONObject();
      categoryJson.put(NAME, category);
      categoryJson.put(WINDOWS, windowsJson);
      categories.put(categoryJson);
    }

    JSONObject matrix = new JSONObject();
    matrix.put(CATEGORIES, categories);
    return matrix;
  }

  /**
   * ETP-5402 — builds {@code reportsMatrix}: every {@link ReportAccessCatalog#ROWS} row, grouped
   * by its own hardcoded category, each with a per-role tri-state {@code access} map built from
   * {@code reportTierMapsByRoleId} — same shape, same {@link #NONE} fallback, same category-sort
   * and row-sort conventions as {@link #buildMatrix(Map, Map)}, so the frontend reuses the same
   * adapter code path for both keys.
   *
   * @param reportTierMapsByRoleId role id → {@link ReportAccessCatalog#resolveTierMap(Role)}
   */
  public static JSONObject buildReportsMatrix(
      Map<String, Map<String, String>> reportTierMapsByRoleId) throws JSONException {
    Map<String, List<ReportAccessCatalog.Row>> rowsByCategory = new LinkedHashMap<>();
    for (ReportAccessCatalog.Row row : ReportAccessCatalog.ROWS) {
      rowsByCategory.computeIfAbsent(row.category, k -> new ArrayList<>()).add(row);
    }

    List<String> sortedCategories = new ArrayList<>(rowsByCategory.keySet());
    sortedCategories.sort(String.CASE_INSENSITIVE_ORDER);

    JSONArray categories = new JSONArray();
    for (String category : sortedCategories) {
      List<ReportAccessCatalog.Row> rowsInCategory = new ArrayList<>(rowsByCategory.get(category));
      rowsInCategory.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

      JSONArray reportsJson = new JSONArray();
      for (ReportAccessCatalog.Row row : rowsInCategory) {
        JSONObject reportJson = new JSONObject();
        reportJson.put(ID, row.id);
        reportJson.put(NAME, row.name);
        reportJson.put(ACCESS, accessJson(row.id, reportTierMapsByRoleId));
        reportsJson.put(reportJson);
      }

      JSONObject categoryJson = new JSONObject();
      categoryJson.put(NAME, category);
      categoryJson.put(REPORTS, reportsJson);
      categories.put(categoryJson);
    }

    JSONObject reportsMatrix = new JSONObject();
    reportsMatrix.put(CATEGORIES, categories);
    return reportsMatrix;
  }

  /** One row's {@code access} object: every role id → its tier on {@code rowId}, else {@link #NONE}. */
  private static JSONObject accessJson(String rowId,
      Map<String, Map<String, String>> tierMapsByRoleId) throws JSONException {
    JSONObject access = new JSONObject();
    for (Map.Entry<String, Map<String, String>> roleEntry : tierMapsByRoleId.entrySet()) {
      access.put(roleEntry.getKey(), roleEntry.getValue().getOrDefault(rowId, NONE));
    }
    return access;
  }

  /**
   * Resolves each window id in {@code windowIds} to the name of its top-level {@code AD_Menu}
   * folder (tree {@code '10'}, the same menu tree {@code SFListMenu} walks) via one recursive-CTE
   * native query — a window linked from two different top-level folders deterministically picks
   * the alphabetically-first one, and a window absent from the result is simply missing from the
   * returned map ({@link #buildMatrix(Map, Map)} falls back to {@link #OTHER_CATEGORY}).
   *
   * @return window id → top-level {@code AD_Menu.name}; never {@code null}, empty when {@code
   *     windowIds} is empty (short-circuits before touching the database)
   */
  @SuppressWarnings("unchecked")
  static Map<String, String> resolveWindowCategories(Set<String> windowIds) {
    if (windowIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Session session = OBDal.getInstance().getSession();
    NativeQuery<Object[]> query = session.createNativeQuery(WINDOW_CATEGORY_SQL);
    query.setParameterList("windowIds", windowIds);

    Map<String, String> categoryByWindowId = new LinkedHashMap<>();
    for (Object[] row : (List<Object[]>) query.getResultList()) {
      String windowId = row[0] == null ? null : row[0].toString();
      String category = row[1] == null ? null : row[1].toString();
      if (windowId != null && category != null) {
        categoryByWindowId.put(windowId, category);
      }
    }
    return categoryByWindowId;
  }
}
