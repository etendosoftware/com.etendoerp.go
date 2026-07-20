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
 * All portions are Copyright © 2021–2025 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import static com.etendoerp.go.schemaforge.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;

/**
 * Unit tests for {@link SFListMenu}.
 * Covers tree building, search mode, resolveType/str helpers,
 * exception handling, edge cases, and role-based access filtering
 * (ETP-4511: menu filtered by AD_Window_Access / AD_Process_Access).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFListMenuTest {

    private MockedStatic<OBDal> obDalMock;
    private MockedStatic<OBContext> obContextMock;
    private OBDal mockDal;
    private Session mockSession;
    private OBContext mockContext;
    private Role adminRole;
    private SFListMenu webhook;
    private Map<String, String> parameters;
    private Map<String, String> responseVars;

    @BeforeEach
    void setUp() {
        obDalMock = mockStatic(OBDal.class);
        obContextMock = mockStatic(OBContext.class);

        mockDal = mock(OBDal.class);
        mockSession = mock(Session.class);
        mockContext = mock(OBContext.class);
        adminRole = mock(Role.class);

        obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
        when(mockDal.getSession()).thenReturn(mockSession);

        // Default: a role with unrestricted (System Administrator) access, so all the
        // pre-existing tree/search tests keep exercising "full access → unchanged" behavior
        // without having to stub WindowAccess/ProcessAccess criteria individually.
        when(adminRole.getId()).thenReturn("0");
        when(mockContext.getRole()).thenReturn(adminRole);
        obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

        webhook = new SFListMenu();
        parameters = new HashMap<>();
        responseVars = new HashMap<>();
    }

    // ── access-filtering helpers ─────────────────────────────────────────

    /** Makes the current role return {@code null} (no role assigned). */
    private void givenNoRole() {
        when(mockContext.getRole()).thenReturn(null);
    }

    /** Makes the current role a non-admin role with the given id. */
    private Role givenRestrictedRole(String roleId) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(roleId);
        when(mockContext.getRole()).thenReturn(role);
        return role;
    }

    /** Stubs {@code OBDal.createCriteria(WindowAccess.class)} to return an active row (or not). */
    @SuppressWarnings("unchecked")
    private void stubWindowAccess(boolean hasAccess) {
        OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(WindowAccess.class)).thenReturn(criteria);
        when(criteria.add(any())).thenReturn(criteria);
        when(criteria.setMaxResults(1)).thenReturn(criteria);
        if (hasAccess) {
            WindowAccess access = mock(WindowAccess.class);
            when(criteria.list()).thenReturn(Collections.singletonList(access));
        } else {
            when(criteria.list()).thenReturn(Collections.emptyList());
        }
    }

    /** Stubs {@code OBDal.createCriteria(ProcessAccess.class)} to return an active row (or not). */
    @SuppressWarnings("unchecked")
    private void stubProcessAccess(boolean hasAccess) {
        OBCriteria<ProcessAccess> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(ProcessAccess.class)).thenReturn(criteria);
        when(criteria.add(any())).thenReturn(criteria);
        when(criteria.setMaxResults(1)).thenReturn(criteria);
        if (hasAccess) {
            ProcessAccess access = mock(ProcessAccess.class);
            when(criteria.list()).thenReturn(Collections.singletonList(access));
        } else {
            when(criteria.list()).thenReturn(Collections.emptyList());
        }
    }

    /**
     * Stubs {@code OBDal.createCriteria(org.openbravo.client.application.ProcessAccess.class)}
     * to return an active row (or not) — used by {@code NeoAccessHelper#hasObuiappProcessAccess},
     * which backs the {@code obuiappProcessId} check for {@code action = 'OBUIAPP_Process'} menu
     * entries (e.g. "Not Posted Documents", "Receivables Aging Schedule"). Fully-qualified to
     * avoid clashing with the already-imported {@code org.openbravo.model.ad.access.ProcessAccess}.
     */
    @SuppressWarnings("unchecked")
    private void stubObuiappProcessAccess(boolean hasAccess) {
        OBCriteria<org.openbravo.client.application.ProcessAccess> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(org.openbravo.client.application.ProcessAccess.class))
                .thenReturn(criteria);
        when(criteria.add(any())).thenReturn(criteria);
        when(criteria.setMaxResults(1)).thenReturn(criteria);
        if (hasAccess) {
            org.openbravo.client.application.ProcessAccess access =
                    mock(org.openbravo.client.application.ProcessAccess.class);
            when(criteria.list()).thenReturn(Collections.singletonList(access));
        } else {
            when(criteria.list()).thenReturn(Collections.emptyList());
        }
    }

    @AfterEach
    void tearDown() {
        obDalMock.close();
        obContextMock.close();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private NativeQuery<Object[]> stubNativeQuery(List<Object[]> rows) {
        NativeQuery<Object[]> mockQuery = mock(NativeQuery.class);
        when(mockSession.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(rows);
        return mockQuery;
    }

    // ── tree building ───────────────────────────────────────────────────

    /** Verifies empty tree returns count=0 and empty array. */
    @Test
    @DisplayName("Build tree with no items returns empty result")
    void testBuildMenuTreeNoItems() throws Exception {
        stubNativeQuery(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertNotNull(responseVars.get(RESULT));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
    }

    /** Verifies tree with root folder and child window node. */
    @Test
    @DisplayName("Build tree with folder and child window")
    void testBuildMenuTreeWithItems() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "0", 10, "Menu Root", "Y", null, null, null, null, null, 0});
        rows.add(new Object[]{"2", "1", 20, "Sales Window", "N", "W", "win1", null, null, null, 1});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(2, result.getInt(COUNT));
        JSONArray tree = result.getJSONArray("tree");
        assertEquals(1, tree.length());

        JSONObject root = tree.getJSONObject(0);
        assertEquals("1", root.getString("id"));
        assertEquals("Menu Root", root.getString("name"));
        assertEquals("folder", root.getString("type"));

        JSONArray children = root.getJSONArray("children");
        assertEquals(1, children.length());
        JSONObject child = children.getJSONObject(0);
        assertEquals("2", child.getString("id"));
        assertEquals("Sales Window", child.getString("name"));
        assertEquals("window", child.getString("type"));
        assertEquals("win1", child.getString("windowId"));
    }

    /** Verifies tree with a Process node. */
    @Test
    @DisplayName("Build tree with process node")
    void testTreeWithProcessNode() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"5", "0", 10, "My Process", "N", "P", null, "proc5", null, null, 0});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject node = result.getJSONArray("tree").getJSONObject(0);
        assertEquals("process", node.getString("type"));
        assertEquals("proc5", node.getString("processId"));
    }

    /** Verifies tree with a Form node. */
    @Test
    @DisplayName("Build tree with form node")
    void testTreeWithFormNode() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"6", "0", 10, "My Form", "N", "X", null, null, null, "form6", 0});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject node = result.getJSONArray("tree").getJSONObject(0);
        assertEquals("form", node.getString("type"));
        assertEquals("form6", node.getString("formId"));
    }

    // ── search mode ─────────────────────────────────────────────────────

    /** Verifies search path returns flat list with matching items. */
    @Test
    @DisplayName("Search with query returns flat list")
    void testSearchMenuWithQuery() throws Exception {
        parameters.put("q", "sales");

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"10", "Sales Order", "N", "W", "win10", null, null, null});

        NativeQuery<Object[]> mockQuery = stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getInt(COUNT));
        JSONArray items = result.getJSONArray("tree");
        assertEquals(1, items.length());

        JSONObject item = items.getJSONObject(0);
        assertEquals("10", item.getString("id"));
        assertEquals("Sales Order", item.getString("name"));
        assertEquals("window", item.getString("type"));

        verify(mockQuery).setParameter(eq("query"), eq("%sales%"));
    }

    /** Verifies whitespace-only query falls back to tree mode. */
    @Test
    @DisplayName("Whitespace query uses tree mode")
    void testSearchMenuEmptyQuery() throws Exception {
        parameters.put("q", "  ");
        stubNativeQuery(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertNotNull(responseVars.get(RESULT));
    }

    /** Verifies absent q param falls back to tree mode. */
    @Test
    @DisplayName("No q param uses tree mode")
    void testSearchMenuNoQuery() throws Exception {
        stubNativeQuery(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertNotNull(responseVars.get(RESULT));
    }

    // ── resolveType helper ──────────────────────────────────────────────

    /** Verifies resolveType maps isSummary/action correctly for all cases. */
    @Test
    @DisplayName("resolveType maps all action types")
    void testResolveType() throws Exception {
        Method resolveType = SFListMenu.class.getDeclaredMethod("resolveType", String.class, String.class);
        resolveType.setAccessible(true);

        assertEquals("folder", resolveType.invoke(null, "Y", null));
        assertEquals("folder", resolveType.invoke(null, "Y", "W"));
        assertEquals("window", resolveType.invoke(null, "N", "W"));
        assertEquals("process", resolveType.invoke(null, "N", "P"));
        assertEquals("report", resolveType.invoke(null, "N", "R"));
        assertEquals("form", resolveType.invoke(null, "N", "X"));
        assertEquals("unknown", resolveType.invoke(null, "N", null));
        assertEquals("other", resolveType.invoke(null, "N", "Z"));
        // ETP-4511: action='OBUIAPP_Process' still resolves to "other" — the fix only adds the
        // obuiappProcessId field/access check, it does not introduce a new resolved type.
        assertEquals("other", resolveType.invoke(null, "N", "OBUIAPP_Process"));
    }

    // ── str helper ──────────────────────────────────────────────────────

    /** Verifies str helper handles null, String and integer inputs. */
    @Test
    @DisplayName("str helper converts values correctly")
    void testStrHelper() throws Exception {
        Method str = SFListMenu.class.getDeclaredMethod("str", Object.class);
        str.setAccessible(true);

        assertNull(str.invoke(null, (Object) null));
        assertEquals("hello", str.invoke(null, "hello"));
        assertEquals("42", str.invoke(null, 42));
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies exception is caught and error set in response. */
    @Test
    @DisplayName("Exception sets error in response")
    void testExceptionSetsError() {
        when(mockDal.getSession()).thenThrow(new RuntimeException("DB error"));

        webhook.get(parameters, responseVars);

        assertEquals("DB error", responseVars.get(ERROR));
        assertNull(responseVars.get(RESULT));
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params not mutated")
    void testInputParamsNotMutated() {
        stubNativeQuery(new ArrayList<>());

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies special characters in search query are passed through. */
    @Test
    @DisplayName("Special characters in query are passed through")
    void testSpecialCharactersInQuery() throws Exception {
        parameters.put("q", "test<>&\"'");

        List<Object[]> rows = new ArrayList<>();
        NativeQuery<Object[]> mockQuery = stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(mockQuery).setParameter(eq("query"), eq("%test<>&\"'%"));
    }

    /** Verifies tree with deeply nested nodes. */
    @Test
    @DisplayName("Multi-level nested tree structure")
    void testMultiLevelNestedTree() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "0", 10, "Root", "Y", null, null, null, null, null, 0});
        rows.add(new Object[]{"2", "1", 10, "Level 1", "Y", null, null, null, null, null, 1});
        rows.add(new Object[]{"3", "2", 10, "Level 2 Window", "N", "W", "win3", null, null, null, 2});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(3, result.getInt(COUNT));

        JSONObject root = result.getJSONArray("tree").getJSONObject(0);
        JSONObject level1 = root.getJSONArray("children").getJSONObject(0);
        JSONObject level2 = level1.getJSONArray("children").getJSONObject(0);
        assertEquals("Level 2 Window", level2.getString("name"));
        assertEquals("win3", level2.getString("windowId"));
    }

    /** Verifies nodes without windowId/processId/formId do not include those keys. */
    @Test
    @DisplayName("Nodes without IDs omit those keys")
    void testNodeWithoutOptionalIds() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "0", 10, "Leaf", "N", "W", null, null, null, null, 0});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject node = result.getJSONArray("tree").getJSONObject(0);
        assertFalse(node.has("windowId"));
        assertFalse(node.has("processId"));
        assertFalse(node.has("obuiappProcessId"));
        assertFalse(node.has("formId"));
    }

    // ── ETP-4511: role-based access filtering ────────────────────────────

    /** No role assigned → empty tree, count 0, and the DB is never even queried. */
    @Test
    @DisplayName("No role assigned returns empty tree without querying the DB")
    void testNoRoleReturnsEmptyTree() throws Exception {
        givenNoRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
        verify(mockDal, never()).getSession();
    }

    /** No role assigned → empty flat list for the search variant too. */
    @Test
    @DisplayName("No role assigned returns empty search list")
    void testNoRoleReturnsEmptySearchList() throws Exception {
        parameters.put("q", "sales");
        givenNoRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
        verify(mockDal, never()).getSession();
    }

    /** A restricted role with access to the window keeps the window node. */
    @Test
    @DisplayName("Restricted role with window access keeps the window node")
    void testRestrictedRoleWithWindowAccessKeepsNode() throws Exception {
        givenRestrictedRole("role-with-access");
        stubWindowAccess(true);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"2", "0", 20, "Sales Window", "N", "W", "win1", null, null, null, 0});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getInt(COUNT));
        assertEquals(1, result.getJSONArray("tree").length());
    }

    /** A restricted role without access to the window drops it from the tree. */
    @Test
    @DisplayName("Restricted role without window access drops the window node")
    void testRestrictedRoleWithoutWindowAccessDropsNode() throws Exception {
        givenRestrictedRole("role-without-access");
        stubWindowAccess(false);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"2", "0", 20, "Sales Window", "N", "W", "win1", null, null, null, 0});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
    }

    /** A restricted role with process access keeps the process node. */
    @Test
    @DisplayName("Restricted role with process access keeps the process node")
    void testRestrictedRoleWithProcessAccessKeepsNode() throws Exception {
        givenRestrictedRole("role-with-process-access");
        stubProcessAccess(true);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"5", "0", 10, "My Process", "N", "P", null, "proc5", null, null, 0});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getInt(COUNT));
    }

    /** A restricted role without process access drops the process node. */
    @Test
    @DisplayName("Restricted role without process access drops the process node")
    void testRestrictedRoleWithoutProcessAccessDropsNode() throws Exception {
        givenRestrictedRole("role-without-process-access");
        stubProcessAccess(false);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"5", "0", 10, "My Process", "N", "P", null, "proc5", null, null, 0});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
    }

    /**
     * A folder whose only child is filtered out is itself pruned; a sibling folder that keeps
     * at least one accessible child survives.
     */
    @Test
    @DisplayName("Folder with all children filtered out is pruned, folder with a kept child survives")
    void testFolderPruningKeepsOnlyFoldersWithAccessibleChildren() throws Exception {
        givenRestrictedRole("role-mixed-access");

        // filterNode walks post-order over roots [Folder A, Folder B] in that order, and within
        // Folder B over its children in row order. That fixes the call sequence to
        // hasWindowAccess as: (1) Folder A's denied child, (2) Folder B's denied child,
        // (3) Folder B's allowed child — so createCriteria(WindowAccess.class) must be stubbed
        // to answer denied, denied, allowed in that exact order.
        OBCriteria<WindowAccess> allowedCriteria = mock(OBCriteria.class);
        OBCriteria<WindowAccess> deniedCriteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(WindowAccess.class))
                .thenReturn(deniedCriteria, deniedCriteria, allowedCriteria);
        when(allowedCriteria.add(any())).thenReturn(allowedCriteria);
        when(allowedCriteria.setMaxResults(1)).thenReturn(allowedCriteria);
        WindowAccess access = mock(WindowAccess.class);
        when(allowedCriteria.list()).thenReturn(Collections.singletonList(access));
        when(deniedCriteria.add(any())).thenReturn(deniedCriteria);
        when(deniedCriteria.setMaxResults(1)).thenReturn(deniedCriteria);
        when(deniedCriteria.list()).thenReturn(Collections.emptyList());

        List<Object[]> rows = new ArrayList<>();
        // Folder A (root) has only a denied window as a child → must be pruned.
        rows.add(new Object[]{"1", "0", 10, "Folder A", "Y", null, null, null, null, null, 0});
        rows.add(new Object[]{"2", "1", 10, "Denied Window", "N", "W", "win-denied", null, null, null, 1});
        // Folder B (root) has one denied and one allowed child → survives with 1 child.
        rows.add(new Object[]{"3", "0", 20, "Folder B", "Y", null, null, null, null, null, 0});
        rows.add(new Object[]{"4", "3", 10, "Denied Window 2", "N", "W", "win-denied", null, null, null, 1});
        rows.add(new Object[]{"5", "3", 20, "Allowed Window", "N", "W", "win-allowed", null, null, null, 1});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray tree = result.getJSONArray("tree");
        // Only Folder B survives.
        assertEquals(1, tree.length());
        JSONObject folderB = tree.getJSONObject(0);
        assertEquals("Folder B", folderB.getString("name"));
        JSONArray children = folderB.getJSONArray("children");
        assertEquals(1, children.length());
        assertEquals("Allowed Window", children.getJSONObject(0).getString("name"));
        // count = Folder B + its one surviving child.
        assertEquals(2, result.getInt(COUNT));
    }

    /** With the default admin role (set up in {@code @BeforeEach}), the tree is unchanged from today's behavior. */
    @Test
    @DisplayName("Admin role sees the full tree, unchanged from today's behavior")
    void testAdminRoleSeesFullTreeUnchanged() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "0", 10, "Menu Root", "Y", null, null, null, null, null, 0});
        rows.add(new Object[]{"2", "1", 20, "Sales Window", "N", "W", "win1", null, null, null, 1});
        rows.add(new Object[]{"3", "1", 30, "Some Process", "N", "P", null, "proc1", null, null, 1});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(3, result.getInt(COUNT));
        JSONObject root = result.getJSONArray("tree").getJSONObject(0);
        assertEquals(2, root.getJSONArray("children").length());
    }

    /** A restricted role with search results filters out inaccessible windows from the flat list. */
    @Test
    @DisplayName("Search filters out windows the restricted role cannot access")
    void testSearchFiltersInaccessibleWindow() throws Exception {
        parameters.put("q", "sales");
        givenRestrictedRole("role-search-restricted");
        stubWindowAccess(false);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"10", "Sales Order", "N", "W", "win10", null, null, null});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
    }

    /** Search results for an accessible window remain in the flat list. */
    @Test
    @DisplayName("Search keeps windows the restricted role can access")
    void testSearchKeepsAccessibleWindow() throws Exception {
        parameters.put("q", "sales");
        givenRestrictedRole("role-search-allowed");
        stubWindowAccess(true);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"10", "Sales Order", "N", "W", "win10", null, null, null});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getInt(COUNT));
        assertEquals(1, result.getJSONArray("tree").length());
    }

    // ── ETP-4511: OBUIAPP_Process menu entries (Not Posted Documents / Aging Schedule) ──────
    //
    // action='OBUIAPP_Process' menu rows carry no ad_window_id/ad_process_id — their real link
    // is AD_Menu.em_obuiapp_process_id. Before this fix isNodeAccessible() never looked at that
    // column, so these entries always defaulted to accessible=true regardless of role. The two
    // real production entries gated at the handler level by the sibling ETP-4510 fix are used
    // here as concrete examples (ids/names taken from the live DB).

    /**
     * A restricted role without an active {@code AD_Menu.em_obuiapp_process_id}-backed grant
     * (via {@code NeoAccessHelper#hasObuiappProcessAccess}) must not see the "Not Posted
     * Documents" menu entry at all — previously this fell through {@code isNodeAccessible} as
     * accessible=true unconditionally since it carries neither windowId nor processId.
     */
    @Test
    @DisplayName("Restricted role without OBUIAPP process access drops the Not Posted Documents node")
    void testRestrictedRoleWithoutObuiappProcessAccessDropsNotPostedDocumentsNode() throws Exception {
        givenRestrictedRole("role-without-obuiapp-access");
        stubObuiappProcessAccess(false);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"3EB0F5F33ECC4FEBABD8F513E9C49521", "0", 10, "Not Posted Documents",
                "N", "OBUIAPP_Process", null, null, "D6AB95CE52D34E1599590526115E26C6", null, 0});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
    }

    /**
     * A restricted role WITH an active grant for the OBUIAPP process keeps the "Receivables
     * Aging Schedule" menu entry, and the node correctly exposes {@code obuiappProcessId}.
     */
    @Test
    @DisplayName("Restricted role with OBUIAPP process access keeps the Receivables Aging Schedule node")
    void testRestrictedRoleWithObuiappProcessAccessKeepsAgingReportNode() throws Exception {
        givenRestrictedRole("role-with-obuiapp-access");
        stubObuiappProcessAccess(true);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"CC226771DE354AEEAA5D69F696F1A676", "0", 10, "Receivables Aging Schedule",
                "N", "OBUIAPP_Process", null, null, "0D37A9F6109549DEB058373EF2DAEB6A", null, 0});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getInt(COUNT));
        JSONObject node = result.getJSONArray("tree").getJSONObject(0);
        assertEquals("Receivables Aging Schedule", node.getString("name"));
        assertEquals("other", node.getString("type"));
        assertEquals("0D37A9F6109549DEB058373EF2DAEB6A", node.getString("obuiappProcessId"));
    }

    /**
     * A folder whose only child is a denied {@code OBUIAPP_Process} node must itself be pruned,
     * mirroring the existing windowId/processId folder-pruning behavior.
     */
    @Test
    @DisplayName("Folder whose only child is a denied OBUIAPP_Process node is pruned")
    void testFolderWithOnlyDeniedObuiappProcessChildIsPruned() throws Exception {
        givenRestrictedRole("role-without-obuiapp-access-2");
        stubObuiappProcessAccess(false);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "0", 10, "Reports Folder", "Y", null, null, null, null, null, 0});
        rows.add(new Object[]{"3EB0F5F33ECC4FEBABD8F513E9C49521", "1", 20, "Not Posted Documents",
                "N", "OBUIAPP_Process", null, null, "D6AB95CE52D34E1599590526115E26C6", null, 1});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
    }

    /** Search variant: OBUIAPP_Process entries are dropped for a role without access. */
    @Test
    @DisplayName("Search drops OBUIAPP_Process entries the restricted role cannot access")
    void testSearchDropsInaccessibleObuiappProcessNode() throws Exception {
        parameters.put("q", "posted");
        givenRestrictedRole("role-search-obuiapp-denied");
        stubObuiappProcessAccess(false);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"3EB0F5F33ECC4FEBABD8F513E9C49521", "Not Posted Documents", "N",
                "OBUIAPP_Process", null, null, "D6AB95CE52D34E1599590526115E26C6", null});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getInt(COUNT));
        assertEquals(0, result.getJSONArray("tree").length());
    }

    /** Search variant: OBUIAPP_Process entries are kept for a role with access. */
    @Test
    @DisplayName("Search keeps OBUIAPP_Process entries the restricted role can access")
    void testSearchKeepsAccessibleObuiappProcessNode() throws Exception {
        parameters.put("q", "aging");
        givenRestrictedRole("role-search-obuiapp-allowed");
        stubObuiappProcessAccess(true);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"CC226771DE354AEEAA5D69F696F1A676", "Receivables Aging Schedule", "N",
                "OBUIAPP_Process", null, null, "0D37A9F6109549DEB058373EF2DAEB6A", null});
        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getInt(COUNT));
        assertEquals(1, result.getJSONArray("tree").length());
        assertEquals("0D37A9F6109549DEB058373EF2DAEB6A",
                result.getJSONArray("tree").getJSONObject(0).getString("obuiappProcessId"));
    }
}
