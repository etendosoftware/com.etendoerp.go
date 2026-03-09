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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.ui.Window;

/**
 * Unit tests for {@link SFListWindows}.
 * Covers listing with/without search filter, max results, ordering,
 * exception handling, and edge cases.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFListWindowsTest extends BaseWebhookTest {

    @InjectMocks
    private SFListWindows webhook;

    private OBCriteria<Window> mockCriteria;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webhook = new SFListWindows();
        mockCriteria = mockCriteria(Window.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Window createMockWindow(String id, String name) {
        Window w = mock(Window.class);
        when(w.getId()).thenReturn(id);
        when(w.getName()).thenReturn(name);
        return w;
    }

    // ── happy paths ─────────────────────────────────────────────────────

    /** Verifies listing all windows returns correct JSON with count. */
    @Test
    @DisplayName("List all windows returns correct JSON")
    void testListAllWindows() throws Exception {
        Window w1 = createMockWindow("win1", "Sales Order");
        Window w2 = createMockWindow("win2", "Purchase Invoice");
        when(mockCriteria.list()).thenReturn(Arrays.asList(w1, w2));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("2", responseVars.get(COUNT));

        JSONArray arr = new JSONArray(responseVars.get(RESULT));
        assertEquals(2, arr.length());
        assertEquals("win1", arr.getJSONObject(0).getString("id"));
        assertEquals("Sales Order", arr.getJSONObject(0).getString("name"));
        assertEquals("win2", arr.getJSONObject(1).getString("id"));
        assertEquals("Purchase Invoice", arr.getJSONObject(1).getString("name"));
    }

    /** Verifies search filter adds ilike restriction. */
    @Test
    @DisplayName("Search query adds ilike restriction")
    void testListWindowsWithSearchQuery() throws Exception {
        parameters.put("q", "sales");
        Window w1 = createMockWindow("win3", "Sales Order");
        when(mockCriteria.list()).thenReturn(Arrays.asList(w1));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("1", responseVars.get(COUNT));
        // Two add() calls: active + ilike
        verify(mockCriteria, times(2)).add(any(Criterion.class));
    }

    // ── empty result ────────────────────────────────────────────────────

    /** Verifies empty result returns count=0 and empty array. */
    @Test
    @DisplayName("Empty result returns count=0")
    void testListWindowsEmptyResult() throws Exception {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("0", responseVars.get(COUNT));
        assertEquals(0, new JSONArray(responseVars.get(RESULT)).length());
    }

    // ── blank query ignored ─────────────────────────────────────────────

    /** Verifies whitespace-only query is ignored (only active filter applied). */
    @Test
    @DisplayName("Whitespace query is ignored")
    void testListWindowsEmptyQueryIgnored() throws Exception {
        parameters.put("q", "  ");
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(mockCriteria, times(1)).add(any(Criterion.class));
    }

    // ── criteria configuration ──────────────────────────────────────────

    /** Verifies max results is set to 100. */
    @Test
    @DisplayName("Max results set to 100")
    void testMaxResults() throws Exception {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        verify(mockCriteria).setMaxResults(100);
    }

    /** Verifies order by name ascending. */
    @Test
    @DisplayName("Ordered by name ascending")
    void testOrderByName() throws Exception {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        verify(mockCriteria).addOrderBy(Window.PROPERTY_NAME, true);
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies exception is caught and error set. */
    @Test
    @DisplayName("Exception sets error in response")
    void testExceptionSetsError() {
        when(mockCriteria.list()).thenThrow(new RuntimeException("Connection lost"));

        webhook.get(parameters, responseVars);

        assertEquals("Connection lost", responseVars.get(ERROR));
        assertNull(responseVars.get(COUNT));
        assertNull(responseVars.get(RESULT));
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies single result is returned correctly. */
    @Test
    @DisplayName("Single result is correct")
    void testSingleResult() throws Exception {
        Window w = createMockWindow("win99", "Only Window");
        when(mockCriteria.list()).thenReturn(Arrays.asList(w));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("1", responseVars.get(COUNT));
        JSONArray arr = new JSONArray(responseVars.get(RESULT));
        assertEquals(1, arr.length());
        assertEquals("win99", arr.getJSONObject(0).getString("id"));
    }

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params not mutated")
    void testInputParamsNotMutated() {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies special characters in search query do not cause errors. */
    @Test
    @DisplayName("Special characters in query accepted")
    void testSpecialCharactersInQuery() throws Exception {
        parameters.put("q", "test<>&\"'");
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
    }

    /** Verifies window with special characters in name. */
    @Test
    @DisplayName("Window with special chars in name")
    void testWindowWithSpecialCharsInName() throws Exception {
        Window w = createMockWindow("w1", "Order <Daily> & \"Monthly\"");
        when(mockCriteria.list()).thenReturn(Arrays.asList(w));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONArray arr = new JSONArray(responseVars.get(RESULT));
        assertEquals("Order <Daily> & \"Monthly\"", arr.getJSONObject(0).getString("name"));
    }

    /** Verifies null q param uses list-all mode. */
    @Test
    @DisplayName("Null q param lists all windows")
    void testNullQueryListsAll() throws Exception {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(mockCriteria, times(1)).add(any(Criterion.class));
    }
}
