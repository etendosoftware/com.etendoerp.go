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
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link SFListMenu}.
 * Covers tree building, search mode, resolveType/str helpers,
 * exception handling, and edge cases.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFListMenuTest {

    private MockedStatic<OBDal> obDalMock;
    private MockedStatic<OBContext> obContextMock;
    private OBDal mockDal;
    private Session mockSession;
    private SFListMenu webhook;
    private Map<String, String> parameters;
    private Map<String, String> responseVars;

    @BeforeEach
    void setUp() {
        obDalMock = mockStatic(OBDal.class);
        obContextMock = mockStatic(OBContext.class);

        mockDal = mock(OBDal.class);
        mockSession = mock(Session.class);

        obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
        when(mockDal.getSession()).thenReturn(mockSession);

        webhook = new SFListMenu();
        parameters = new HashMap<>();
        responseVars = new HashMap<>();
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
        rows.add(new Object[]{"1", "0", 10, "Menu Root", "Y", null, null, null, null, 0});
        rows.add(new Object[]{"2", "1", 20, "Sales Window", "N", "W", "win1", null, null, 1});

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
        rows.add(new Object[]{"5", "0", 10, "My Process", "N", "P", null, "proc5", null, 0});

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
        rows.add(new Object[]{"6", "0", 10, "My Form", "N", "X", null, null, "form6", 0});

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
        rows.add(new Object[]{"10", "Sales Order", "N", "W", "win10", null, null});

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
        rows.add(new Object[]{"1", "0", 10, "Root", "Y", null, null, null, null, 0});
        rows.add(new Object[]{"2", "1", 10, "Level 1", "Y", null, null, null, null, 1});
        rows.add(new Object[]{"3", "2", 10, "Level 2 Window", "N", "W", "win3", null, null, 2});

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
        rows.add(new Object[]{"1", "0", 10, "Leaf", "N", "W", null, null, null, 0});

        stubNativeQuery(rows);

        webhook.get(parameters, responseVars);

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject node = result.getJSONArray("tree").getJSONObject(0);
        assertFalse(node.has("windowId"));
        assertFalse(node.has("processId"));
        assertFalse(node.has("formId"));
    }
}
