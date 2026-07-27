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
package com.etendoerp.go.schemaforge.webhooks;

import static com.etendoerp.go.schemaforge.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link SFWindowAccessMap}.
 * Covers no-role/empty-map short-circuiting, admin/client-admin bypass (full access to every
 * active Etendo GO window + every capability true), and role-based resolution of both the
 * per-window access tier (from {@code AD_Window_Access.IsReadWrite}) and the
 * {@code showAccountingFields} capability (from {@code AD_Role.EM_ETGO_Show_Acct_Fields}).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFWindowAccessMapTest {

    private MockedStatic<OBDal> obDalMock;
    private MockedStatic<OBContext> obContextMock;
    private OBDal mockDal;
    private Session mockSession;
    private OBContext mockContext;
    private SFWindowAccessMap webhook;
    private Map<String, String> parameters;
    private Map<String, String> responseVars;

    @BeforeEach
    void setUp() {
        obDalMock = mockStatic(OBDal.class);
        obContextMock = mockStatic(OBContext.class);

        mockDal = mock(OBDal.class);
        mockSession = mock(Session.class);
        mockContext = mock(OBContext.class);

        obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
        when(mockDal.getSession()).thenReturn(mockSession);
        obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

        webhook = new SFWindowAccessMap();
        parameters = new HashMap<>();
        responseVars = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        obDalMock.close();
        obContextMock.close();
    }

    // ── role helpers ──────────────────────────────────────────────────────

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

    /** Makes the current role the literal System Administrator role ({@code "0"}). */
    private Role givenSystemAdminRole() {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn("0");
        when(mockContext.getRole()).thenReturn(role);
        return role;
    }

    /** Makes the current role a per-client "GO Admin" role ({@code is_client_admin = 'Y'}). */
    private Role givenClientAdminRole(String roleId) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(roleId);
        when(role.isClientAdmin()).thenReturn(true);
        when(mockContext.getRole()).thenReturn(role);
        return role;
    }

    // ── query stubs ───────────────────────────────────────────────────────

    /** Stubs {@code OBDal.createCriteria(WindowAccess.class)} to return the given rows. */
    @SuppressWarnings("unchecked")
    private void stubWindowAccessRows(List<WindowAccess> rows) {
        OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(WindowAccess.class)).thenReturn(criteria);
        when(criteria.add(any())).thenReturn(criteria);
        when(criteria.list()).thenReturn(rows);
    }

    /** Builds a mocked {@code WindowAccess} row for the given window id + read/write tier. */
    private WindowAccess windowAccessRow(String windowId, boolean editable) {
        WindowAccess access = mock(WindowAccess.class);
        Window window = mock(Window.class);
        when(window.getId()).thenReturn(windowId);
        when(access.getWindow()).thenReturn(window);
        when(access.isEditableField()).thenReturn(editable);
        return access;
    }

    /** Stubs {@code OBDal.createCriteria(SFSpec.class)} to return the given rows. */
    @SuppressWarnings("unchecked")
    private void stubSpecRows(List<SFSpec> rows) {
        OBCriteria<SFSpec> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(SFSpec.class)).thenReturn(criteria);
        when(criteria.add(any())).thenReturn(criteria);
        when(criteria.list()).thenReturn(rows);
    }

    /** Builds a mocked window-type {@code SFSpec} backed by the given window id. */
    private SFSpec windowSpec(String windowId) {
        SFSpec spec = mock(SFSpec.class);
        Window window = mock(Window.class);
        when(window.getId()).thenReturn(windowId);
        when(spec.getADWindow()).thenReturn(window);
        return spec;
    }

    /**
     * Stubs the native SQL lookup of {@code AD_Role.EM_ETGO_Show_Acct_Fields} for any role.
     * Accepts {@code List<?>}, not {@code List<String>}: real Hibernate returns {@link Character}
     * elements for this {@code char(1)} column (see {@code testShowAccountingFieldsHandlesRealCharacterResult}
     * below), and this helper needs to be able to simulate that faithfully, not just the
     * String-typed shape most other tests use.
     */
    @SuppressWarnings("unchecked")
    private NativeQuery<String> stubShowAcctFieldsQuery(List<?> resultRows) {
        NativeQuery<String> mockQuery = mock(NativeQuery.class);
        when(mockSession.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn((List<String>) resultRows);
        return mockQuery;
    }

    // ── no role ───────────────────────────────────────────────────────────

    /** No role assigned → both maps empty, and the DB is never even queried. */
    @Test
    @DisplayName("No role assigned returns empty windowAccess and capabilities without querying the DB")
    void testNoRoleReturnsEmptyMaps() throws Exception {
        givenNoRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONObject("windowAccess").length());
        assertEquals(0, result.getJSONObject("capabilities").length());
        verify(mockDal, never()).createCriteria(WindowAccess.class);
        verify(mockDal, never()).createCriteria(SFSpec.class);
        verify(mockDal, never()).getSession();
    }

    // ── admin / client-admin bypass ──────────────────────────────────────

    /**
     * System Administrator role ({@code "0"}) → every active, window-type
     * {@code ETGO_SF_SPEC}'s window resolves to {@code "full"}, and
     * {@code showAccountingFields} is {@code true} without ever reading the
     * {@code EM_ETGO_Show_Acct_Fields} column.
     */
    @Test
    @DisplayName("System Administrator role gets full access to every active Etendo GO window and every capability true")
    void testSystemAdminRoleGetsFullAccessToEveryWindow() throws Exception {
        givenSystemAdminRole();
        stubSpecRows(Arrays.asList(windowSpec("win-1"), windowSpec("win-2")));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject windowAccess = result.getJSONObject("windowAccess");
        assertEquals(2, windowAccess.length());
        assertEquals("full", windowAccess.getString("win-1"));
        assertEquals("full", windowAccess.getString("win-2"));
        assertTrue(result.getJSONObject("capabilities").getBoolean("showAccountingFields"));
        // Admin bypass never needs the accounting-column lookup.
        verify(mockDal, never()).getSession();
    }

    /**
     * A per-client "GO Admin" role ({@code is_client_admin = 'Y'}, not the literal System
     * Administrator role) gets the exact same bypass treatment.
     */
    @Test
    @DisplayName("Client-admin role gets full access to every active Etendo GO window and every capability true")
    void testClientAdminRoleGetsFullAccessToEveryWindow() throws Exception {
        givenClientAdminRole("role-client-admin");
        stubSpecRows(Collections.singletonList(windowSpec("win-only")));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject windowAccess = result.getJSONObject("windowAccess");
        assertEquals(1, windowAccess.length());
        assertEquals("full", windowAccess.getString("win-only"));
        assertTrue(result.getJSONObject("capabilities").getBoolean("showAccountingFields"));
    }

    /** Duplicate windows across multiple specs are deduplicated in the admin-bypass map. */
    @Test
    @DisplayName("Admin bypass deduplicates windows shared by more than one spec")
    void testAdminBypassDeduplicatesSharedWindows() throws Exception {
        givenSystemAdminRole();
        stubSpecRows(Arrays.asList(windowSpec("win-shared"), windowSpec("win-shared")));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(1, result.getJSONObject("windowAccess").length());
    }

    // ── restricted role: windowAccess resolution ─────────────────────────

    /** A restricted role's read-write AD_Window_Access row resolves to "full". */
    @Test
    @DisplayName("Restricted role with IsReadWrite=true resolves to full")
    void testRestrictedRoleReadWriteResolvesToFull() throws Exception {
        givenRestrictedRole("role-rw");
        stubWindowAccessRows(Collections.singletonList(windowAccessRow("win-rw", true)));
        stubShowAcctFieldsQuery(Collections.singletonList("N"));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals("full", result.getJSONObject("windowAccess").getString("win-rw"));
    }

    /** A restricted role's read-only AD_Window_Access row resolves to "read-only". */
    @Test
    @DisplayName("Restricted role with IsReadWrite=false resolves to read-only")
    void testRestrictedRoleReadOnlyResolvesToReadOnly() throws Exception {
        givenRestrictedRole("role-ro");
        stubWindowAccessRows(Collections.singletonList(windowAccessRow("win-ro", false)));
        stubShowAcctFieldsQuery(Collections.singletonList("N"));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals("read-only", result.getJSONObject("windowAccess").getString("win-ro"));
    }

    /** A window with no active AD_Window_Access row is simply absent from the map. */
    @Test
    @DisplayName("Restricted role with no AD_Window_Access rows gets an empty windowAccess map")
    void testRestrictedRoleWithNoRowsGetsEmptyMap() throws Exception {
        givenRestrictedRole("role-none");
        stubWindowAccessRows(Collections.emptyList());
        stubShowAcctFieldsQuery(Collections.singletonList("N"));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONObject("windowAccess").length());
    }

    /** A restricted role can mix full and read-only tiers across different windows. */
    @Test
    @DisplayName("Restricted role resolves mixed tiers across multiple windows")
    void testRestrictedRoleResolvesMixedTiers() throws Exception {
        givenRestrictedRole("role-mixed");
        stubWindowAccessRows(Arrays.asList(
                windowAccessRow("win-full", true),
                windowAccessRow("win-read-only", false)));
        stubShowAcctFieldsQuery(Collections.singletonList("N"));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject windowAccess = new JSONObject(responseVars.get(RESULT)).getJSONObject("windowAccess");
        assertEquals(2, windowAccess.length());
        assertEquals("full", windowAccess.getString("win-full"));
        assertEquals("read-only", windowAccess.getString("win-read-only"));
    }

    // ── restricted role: showAccountingFields capability ─────────────────

    /** {@code EM_ETGO_Show_Acct_Fields = 'Y'} for the role's AD_Role row → capability true. */
    @Test
    @DisplayName("Role with EM_ETGO_Show_Acct_Fields = Y resolves showAccountingFields true")
    void testShowAccountingFieldsTrueWhenColumnIsY() throws Exception {
        givenRestrictedRole("role-acct-y");
        stubWindowAccessRows(Collections.emptyList());
        NativeQuery<String> query = stubShowAcctFieldsQuery(Collections.singletonList("Y"));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertTrue(result.getJSONObject("capabilities").getBoolean("showAccountingFields"));
        verify(query).setParameter("roleId", "role-acct-y");
    }

    /**
     * Regression test for a live onboarding failure (2026-07-27): Hibernate's native-query result
     * for a PostgreSQL {@code char(1)} column with no explicit {@code addScalar} type comes back
     * as {@link Character}, not {@link String} — a plain {@code List<String>.get(0)} throws
     * {@code ClassCastException: Character cannot be cast to String} via generics-erasure the
     * instant a real row is returned. Every other test in this file stubs a {@code String} (e.g.
     * {@code "Y"}), which happens to also satisfy {@code Object.toString()} and so would NOT have
     * caught this — this test stubs a real {@link Character} to prove the fix actually handles it.
     */
    @Test
    @DisplayName("Role with EM_ETGO_Show_Acct_Fields returned as a real Character (not String) still resolves correctly")
    void testShowAccountingFieldsHandlesRealCharacterResult() throws Exception {
        givenRestrictedRole("role-acct-char");
        stubWindowAccessRows(Collections.emptyList());
        stubShowAcctFieldsQuery(Collections.singletonList(Character.valueOf('Y')));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertTrue(result.getJSONObject("capabilities").getBoolean("showAccountingFields"));
    }

    /**
     * A role with the new column unset (physically {@code 'N'}, the column's default) resolves
     * {@code showAccountingFields: false} — the ETP-4520 default-off behavior.
     */
    @Test
    @DisplayName("Role with EM_ETGO_Show_Acct_Fields unset (N) resolves showAccountingFields false")
    void testShowAccountingFieldsFalseWhenColumnIsUnset() throws Exception {
        givenRestrictedRole("role-acct-unset");
        stubWindowAccessRows(Collections.emptyList());
        stubShowAcctFieldsQuery(Collections.singletonList("N"));

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertFalse(result.getJSONObject("capabilities").getBoolean("showAccountingFields"));
    }

    /** No matching AD_Role row at all (defensive) also resolves to false, not an exception. */
    @Test
    @DisplayName("Role with no matching AD_Role row resolves showAccountingFields false")
    void testShowAccountingFieldsFalseWhenNoRoleRowFound() throws Exception {
        givenRestrictedRole("role-missing");
        stubWindowAccessRows(Collections.emptyList());
        stubShowAcctFieldsQuery(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertFalse(result.getJSONObject("capabilities").getBoolean("showAccountingFields"));
    }

    // ── exception handling ────────────────────────────────────────────────

    /** Verifies an exception during resolution is caught and reported as an error. */
    @Test
    @DisplayName("Exception sets error in response")
    void testExceptionSetsError() {
        givenRestrictedRole("role-error");
        when(mockDal.createCriteria(WindowAccess.class)).thenThrow(new RuntimeException("DB error"));

        webhook.get(parameters, responseVars);

        assertEquals("DB error", responseVars.get(ERROR));
        assertNull(responseVars.get(RESULT));
    }

    // ── edge cases ─────────────────────────────────────────────────────────

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params not mutated")
    void testInputParamsNotMutated() {
        givenRestrictedRole("role-params");
        stubWindowAccessRows(Collections.emptyList());
        stubShowAcctFieldsQuery(Collections.singletonList("N"));

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }
}
