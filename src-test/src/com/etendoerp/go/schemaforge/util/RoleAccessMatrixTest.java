/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Window;

/**
 * Unit tests for {@link RoleAccessMatrix}'s pure row-building edge cases (ETP-5485). The
 * end-to-end tier resolution through real OBCriteria mocks is covered by {@code
 * SFRolesOverviewTest} and {@code SFSystemRoleTemplatesTest}, which both call this builder.
 */
class RoleAccessMatrixTest {

  private MockedStatic<OBDal> obDalMock;
  private OBDal dal;
  private Session session;
  private NativeQuery<Object[]> categoryQuery;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    session = mock(Session.class);
    categoryQuery = mock(NativeQuery.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(categoryQuery);
    when(categoryQuery.getResultList()).thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  private static Window mockWindow(String id, String name) {
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(id);
    when(window.getName()).thenReturn(name);
    return window;
  }

  private static Map<String, Window> windowsById(Window... windows) {
    Map<String, Window> byId = new LinkedHashMap<>();
    for (Window window : windows) {
      byId.put(window.getId(), window);
    }
    return byId;
  }

  /** Every row across the matrix's categories, as (category, row) pairs in output order. */
  private static List<String[]> rowsWithCategory(JSONObject matrix) throws Exception {
    List<String[]> rows = new ArrayList<>();
    JSONArray categories = matrix.getJSONArray("categories");
    for (int c = 0; c < categories.length(); c++) {
      JSONObject category = categories.getJSONObject(c);
      JSONArray windows = category.getJSONArray("windows");
      for (int i = 0; i < windows.length(); i++) {
        rows.add(new String[] { category.getString("name"), windows.getJSONObject(i).getString("id") });
      }
    }
    return rows;
  }

  @Test
  @DisplayName("No roles: every row still exists, each with an empty access map")
  void testEmptyRoleListStillProducesRows() throws Exception {
    JSONObject matrix = RoleAccessMatrix.buildMatrix(windowsById(mockWindow("w1", "Orders")),
        Collections.emptyMap());

    List<String[]> rows = rowsWithCategory(matrix);
    // 1 real window + the 3 proxy rows (none of them collides with "w1").
    assertEquals(4, rows.size());
    JSONObject firstRow = matrix.getJSONArray("categories").getJSONObject(0)
        .getJSONArray("windows").getJSONObject(0);
    assertEquals(0, firstRow.getJSONObject("access").length());
  }

  @Test
  @DisplayName("A proxy id that is also a real GO window produces exactly one row")
  void testProxyIdCollidingWithRealWindowIsNotDuplicated() throws Exception {
    Window siiMonitor = mockWindow(RoleAccessMatrix.FISCAL_MONITOR_PROXY_WINDOW_ID, "SII Monitor");
    Window taxReport = mockWindow(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID, "Tax Report");

    JSONObject matrix = RoleAccessMatrix.buildMatrix(windowsById(siiMonitor, taxReport),
        Map.of("r1", Map.of(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID, RoleAccessMatrix.FULL)));

    List<String[]> rows = rowsWithCategory(matrix);
    // 2 real rows + only the Not Posted Documents proxy.
    assertEquals(3, rows.size());
    long taxRows = rows.stream()
        .filter(r -> RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID.equals(r[1])).count();
    assertEquals(1, taxRows);
  }

  @Test
  @DisplayName("A window with no menu category falls back to Other; a resolved one uses its folder")
  void testWindowWithoutCategoryFallsBackToOther() throws Exception {
    List<Object[]> categoryRows = new ArrayList<>();
    categoryRows.add(new Object[] { "w1", "Sales" });
    when(categoryQuery.getResultList()).thenReturn(categoryRows);

    JSONObject matrix = RoleAccessMatrix.buildMatrix(
        windowsById(mockWindow("w1", "Orders"), mockWindow("w2", "Orphan")),
        Map.of("r1", Map.of("w1", RoleAccessMatrix.READ_ONLY)));

    Map<String, String> categoryById = new LinkedHashMap<>();
    for (String[] row : rowsWithCategory(matrix)) {
      categoryById.put(row[1], row[0]);
    }
    assertEquals("Sales", categoryById.get("w1"));
    assertEquals(RoleAccessMatrix.OTHER_CATEGORY, categoryById.get("w2"));
    // The Not Posted Documents proxy is a process id — never resolvable, always Other.
    assertEquals(RoleAccessMatrix.OTHER_CATEGORY,
        categoryById.get(RoleAccessMatrix.NOT_POSTED_DOCS_PROXY_PROCESS_ID));
  }

  @Test
  @DisplayName("A role with no tier for a row gets 'none' for it")
  void testMissingTierResolvesToNone() throws Exception {
    JSONObject matrix = RoleAccessMatrix.buildMatrix(windowsById(mockWindow("w1", "Orders")),
        Map.of("r1", Map.of()));

    JSONArray categories = matrix.getJSONArray("categories");
    for (int c = 0; c < categories.length(); c++) {
      JSONArray windows = categories.getJSONObject(c).getJSONArray("windows");
      for (int i = 0; i < windows.length(); i++) {
        assertEquals(RoleAccessMatrix.NONE,
            windows.getJSONObject(i).getJSONObject("access").getString("r1"));
      }
    }
  }

  @Test
  @DisplayName("withoutUiExcluded drops the UI-hidden windows and keeps order")
  void testWithoutUiExcludedDropsHiddenWindows() {
    String hiddenId = RoleAccessMatrix.UI_EXCLUDED_WINDOW_IDS.iterator().next();
    Map<String, Window> all = windowsById(mockWindow("b", "B"), mockWindow(hiddenId, "Hidden"),
        mockWindow("a", "A"));

    Map<String, Window> ui = RoleAccessMatrix.withoutUiExcluded(all);

    assertEquals(List.of("b", "a"), new ArrayList<>(ui.keySet()));
    assertTrue(all.containsKey(hiddenId), "input map must not be mutated");
  }

  @Test
  @DisplayName("windowsJson never emits a proxy id (only ids in the GO window map)")
  void testWindowsJsonSkipsProxyIds() throws Exception {
    Map<String, String> tiers = new LinkedHashMap<>();
    tiers.put("w1", RoleAccessMatrix.FULL);
    tiers.put(RoleAccessMatrix.NOT_POSTED_DOCS_PROXY_PROCESS_ID, RoleAccessMatrix.FULL);

    JSONArray windows = RoleAccessMatrix.windowsJson(tiers, windowsById(mockWindow("w1", "Orders")));

    assertEquals(1, windows.length());
    assertEquals("w1", windows.getJSONObject(0).getString("id"));
  }

  @Test
  @DisplayName("Category lookup with no ids short-circuits without touching the session")
  void testResolveWindowCategoriesEmptyShortCircuits() {
    assertTrue(RoleAccessMatrix.resolveWindowCategories(Set.of()).isEmpty());
    verify(dal, never()).getSession();
  }

  @Test
  @DisplayName("reportsMatrix lists every catalog row once, access keyed by role")
  void testReportsMatrixListsEveryCatalogRow() throws Exception {
    JSONObject reportsMatrix = RoleAccessMatrix.buildReportsMatrix(
        Map.of("r1", Map.of(ReportAccessCatalog.ROWS.get(0).id, RoleAccessMatrix.FULL)));

    int count = 0;
    boolean granted = false;
    JSONArray categories = reportsMatrix.getJSONArray("categories");
    for (int c = 0; c < categories.length(); c++) {
      JSONArray reports = categories.getJSONObject(c).getJSONArray("reports");
      for (int i = 0; i < reports.length(); i++) {
        JSONObject report = reports.getJSONObject(i);
        count++;
        String tier = report.getJSONObject("access").getString("r1");
        if (ReportAccessCatalog.ROWS.get(0).id.equals(report.getString("id"))) {
          granted = RoleAccessMatrix.FULL.equals(tier);
        } else {
          assertFalse(RoleAccessMatrix.FULL.equals(tier));
        }
      }
    }
    assertEquals(ReportAccessCatalog.ROWS.size(), count);
    assertTrue(granted);
  }
}
