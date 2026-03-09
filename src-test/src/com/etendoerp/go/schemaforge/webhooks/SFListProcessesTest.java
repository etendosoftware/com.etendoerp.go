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
import org.openbravo.model.ad.ui.Process;

/**
 * Unit tests for {@link SFListProcesses}.
 * Covers listing with/without search filter, max results, ordering,
 * exception handling, and edge cases.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFListProcessesTest extends BaseWebhookTest {

    @InjectMocks
    private SFListProcesses webhook;

    private OBCriteria<Process> mockCriteria;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webhook = new SFListProcesses();
        mockCriteria = mockCriteria(Process.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Process createMockProcess(String id, String name) {
        Process p = mock(Process.class);
        when(p.getId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        return p;
    }

    // ── happy paths ─────────────────────────────────────────────────────

    /** Verifies listing all processes returns correct JSON with count. */
    @Test
    @DisplayName("List all processes returns correct JSON")
    void testListAllProcesses() throws Exception {
        Process p1 = createMockProcess("proc1", "Process One");
        Process p2 = createMockProcess("proc2", "Process Two");
        when(mockCriteria.list()).thenReturn(Arrays.asList(p1, p2));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("2", responseVars.get(COUNT));

        JSONArray arr = new JSONArray(responseVars.get(RESULT));
        assertEquals(2, arr.length());
        assertEquals("proc1", arr.getJSONObject(0).getString("id"));
        assertEquals("Process One", arr.getJSONObject(0).getString("name"));
        assertEquals("proc2", arr.getJSONObject(1).getString("id"));
        assertEquals("Process Two", arr.getJSONObject(1).getString("name"));
    }

    /** Verifies search filter adds ilike restriction. */
    @Test
    @DisplayName("Search query adds ilike restriction")
    void testListProcessesWithSearchQuery() throws Exception {
        parameters.put("q", "report");
        Process p1 = createMockProcess("proc3", "Daily Report");
        when(mockCriteria.list()).thenReturn(Arrays.asList(p1));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("1", responseVars.get(COUNT));
        // Two add() calls: one for active filter, one for ilike
        verify(mockCriteria, times(2)).add(any(Criterion.class));
    }

    // ── empty result ────────────────────────────────────────────────────

    /** Verifies empty result returns count=0 and empty array. */
    @Test
    @DisplayName("Empty result returns count=0")
    void testListProcessesEmptyResult() throws Exception {
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
    void testListProcessesEmptyQueryIgnored() throws Exception {
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

        verify(mockCriteria).addOrderBy(Process.PROPERTY_NAME, true);
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies exception is caught and error set. */
    @Test
    @DisplayName("Exception sets error in response")
    void testExceptionSetsError() {
        when(mockCriteria.list()).thenThrow(new RuntimeException("DB failure"));

        webhook.get(parameters, responseVars);

        assertEquals("DB failure", responseVars.get(ERROR));
        assertNull(responseVars.get(COUNT));
        assertNull(responseVars.get(RESULT));
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params not mutated")
    void testInputParamsNotMutated() {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies single result is returned correctly. */
    @Test
    @DisplayName("Single result is correct")
    void testSingleResult() throws Exception {
        Process p = createMockProcess("proc99", "Only Process");
        when(mockCriteria.list()).thenReturn(Arrays.asList(p));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("1", responseVars.get(COUNT));
        JSONArray arr = new JSONArray(responseVars.get(RESULT));
        assertEquals(1, arr.length());
        assertEquals("proc99", arr.getJSONObject(0).getString("id"));
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

    /** Verifies process with special characters in name. */
    @Test
    @DisplayName("Process with special chars in name")
    void testProcessWithSpecialCharsInName() throws Exception {
        Process p = createMockProcess("p1", "Report <Daily> & \"Monthly\"");
        when(mockCriteria.list()).thenReturn(Arrays.asList(p));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONArray arr = new JSONArray(responseVars.get(RESULT));
        assertEquals("Report <Daily> & \"Monthly\"", arr.getJSONObject(0).getString("name"));
    }

    /** Verifies null q param uses list-all mode. */
    @Test
    @DisplayName("Null q param lists all processes")
    void testNullQueryListsAll() throws Exception {
        when(mockCriteria.list()).thenReturn(new ArrayList<>());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        // Only one add() for active filter
        verify(mockCriteria, times(1)).add(any(Criterion.class));
    }
}
