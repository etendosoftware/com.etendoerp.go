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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link SFRolesOverview}.
 *
 * <p>Covers: the admin/client-admin access gate (ETP-4513's key security requirement — this
 * webhook returns cross-role aggregate data no ordinary role should see), the per-role
 * user-count and window-list aggregation, the Etendo-GO-window intersection (a role's native
 * {@code AD_Window_Access} rows are filtered down to windows Etendo GO actually exposes), tier
 * resolution (full vs read-only), a missing/renamed role id being skipped defensively, and
 * exception handling.</p>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFRolesOverviewTest extends BaseWebhookTest {

    /** Same order as {@code SFRolesOverview.GOCLIENT_ROLE_IDS}. */
    private static final String ADMIN_ROLE_ID = "9B8D736190724807AB256DC95F20EC5E";
    private static final String FINANCE_ROLE_ID = "127AE77FE2994067B7FE6495FC21D51E";
    private static final String SALES_ROLE_ID = "2A159DF4F4B944A6AA903202AD35B545";
    private static final String PURCHASING_ROLE_ID = "A826430F723E4C1B9A53EBB0746A98C0";
    private static final String INVENTORY_ROLE_ID = "55E05A4B43514A029D6FB6B8D94B49D4";

    private SFRolesOverview webhook;

    @BeforeEach
    void setUp() {
        webhook = new SFRolesOverview();
    }

    // ── mocking helpers ──────────────────────────────────────────────────

    /** Makes the ambient current role return {@code null} (no role assigned). */
    private void givenNoCallerRole() {
        when(obContext.getRole()).thenReturn(null);
    }

    /** Makes the ambient current role a non-admin, non-client-admin role. */
    private void givenRestrictedCallerRole(String roleId) {
        Role callerRole = mock(Role.class);
        when(callerRole.getId()).thenReturn(roleId);
        when(callerRole.isClientAdmin()).thenReturn(false);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Makes the ambient current role the literal System Administrator role ({@code "0"}). */
    private void givenSystemAdminCallerRole() {
        Role callerRole = mock(Role.class);
        when(callerRole.getId()).thenReturn("0");
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Makes the ambient current role a per-client "GO Admin" (client-admin) role. */
    private void givenClientAdminCallerRole(String roleId) {
        Role callerRole = mock(Role.class);
        when(callerRole.getId()).thenReturn(roleId);
        when(callerRole.isClientAdmin()).thenReturn(true);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Stubs {@code OBDal.get(Role.class, id)} to resolve a mock GOClient role. */
    private Role mockGoClientRole(String id, String name, String description) {
        Role r = mock(Role.class);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.getDescription()).thenReturn(description);
        when(obDal.get(Role.class, id)).thenReturn(r);
        return r;
    }

    /** Stubs all 5 GOClient roles resolving to a minimal mock, so tests can focus on one. */
    private void givenAllFiveGoClientRolesResolve() {
        mockGoClientRole(ADMIN_ROLE_ID, "GOClient Admin", "GOClient Admin");
        mockGoClientRole(FINANCE_ROLE_ID, "Finance", "Etendo Go system role");
        mockGoClientRole(SALES_ROLE_ID, "Sales", "Etendo Go system role");
        mockGoClientRole(PURCHASING_ROLE_ID, "Purchasing", "Etendo Go system role");
        mockGoClientRole(INVENTORY_ROLE_ID, "Inventory", "Etendo Go system role");
    }

    private Window mockWindow(String id, String name) {
        Window w = mock(Window.class);
        when(w.getId()).thenReturn(id);
        when(w.getName()).thenReturn(name);
        return w;
    }

    private UserRoles mockUserRolesRow(String userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        UserRoles row = mock(UserRoles.class);
        when(row.getUserContact()).thenReturn(user);
        return row;
    }

    private WindowAccess mockWindowAccessRow(Window window, boolean editable) {
        WindowAccess row = mock(WindowAccess.class);
        when(row.getWindow()).thenReturn(window);
        when(row.isEditableField()).thenReturn(editable);
        return row;
    }

    private SFSpec mockGoWindowSpec(Window window) {
        SFSpec spec = mock(SFSpec.class);
        when(spec.getADWindow()).thenReturn(window);
        return spec;
    }

    /**
     * Stubs {@code SFSpec} criteria (the Etendo-GO-window resolution query) to return specs
     * backing {@code windows}, and stubs the {@code UserRoles}/{@code WindowAccess} criteria to
     * return {@code emptyList} for every one of the 5 per-role queries — the common baseline for
     * tests that only care about one role's data (set that role's rows individually afterward).
     */
    @SuppressWarnings("unchecked")
    private void stubBaselineQueries(List<Window> goWindows) {
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        List<SFSpec> specs = new java.util.ArrayList<>();
        for (Window w : goWindows) {
            specs.add(mockGoWindowSpec(w));
        }
        when(specCriteria.list()).thenReturn(specs);

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());
    }

    // ── access gate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("No caller role returns empty roles array without querying any role")
    void testNoCallerRoleReturnsEmptyRoles() throws Exception {
        givenNoCallerRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).get(eq(Role.class), anyString());
    }

    @Test
    @DisplayName("Restricted (non-admin) caller role returns empty roles array")
    void testRestrictedCallerRoleReturnsEmptyRoles() throws Exception {
        givenRestrictedCallerRole("some-ordinary-role");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).get(eq(Role.class), anyString());
    }

    @Test
    @DisplayName("System Administrator caller (role id '0') passes the gate")
    void testSystemAdminCallerPassesGate() throws Exception {
        givenSystemAdminCallerRole();
        givenAllFiveGoClientRolesResolve();
        stubBaselineQueries(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(5, result.getJSONArray("roles").length());
    }

    @Test
    @DisplayName("Client-admin caller (is_client_admin='Y', non-zero id) passes the gate")
    void testClientAdminCallerPassesGate() throws Exception {
        givenClientAdminCallerRole("some-client-admin-role");
        givenAllFiveGoClientRolesResolve();
        stubBaselineQueries(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(5, result.getJSONArray("roles").length());
    }

    // ── role shape / ordering ────────────────────────────────────────────

    @Test
    @DisplayName("All 5 roles are returned in GOCLIENT_ROLE_IDS order with id/name/description")
    void testAllFiveRolesReturnedInOrder() throws Exception {
        givenSystemAdminCallerRole();
        givenAllFiveGoClientRolesResolve();
        stubBaselineQueries(Collections.emptyList());

        webhook.get(parameters, responseVars);

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(5, roles.length());

        assertEquals(ADMIN_ROLE_ID, roles.getJSONObject(0).getString("id"));
        assertEquals("GOClient Admin", roles.getJSONObject(0).getString("name"));
        assertEquals(FINANCE_ROLE_ID, roles.getJSONObject(1).getString("id"));
        assertEquals("Finance", roles.getJSONObject(1).getString("name"));
        assertEquals(SALES_ROLE_ID, roles.getJSONObject(2).getString("id"));
        assertEquals(PURCHASING_ROLE_ID, roles.getJSONObject(3).getString("id"));
        assertEquals(INVENTORY_ROLE_ID, roles.getJSONObject(4).getString("id"));
        assertEquals("Etendo Go system role", roles.getJSONObject(4).getString("rawDescription"));
    }

    @Test
    @DisplayName("A GOClient role id that fails to resolve is skipped, the other 4 still returned")
    void testMissingRoleIsSkippedGracefully() throws Exception {
        givenSystemAdminCallerRole();
        // Only 4 of the 5 resolve; ADMIN_ROLE_ID is deliberately left unstubbed -> OBDal.get(...) returns null.
        mockGoClientRole(FINANCE_ROLE_ID, "Finance", "d");
        mockGoClientRole(SALES_ROLE_ID, "Sales", "d");
        mockGoClientRole(PURCHASING_ROLE_ID, "Purchasing", "d");
        mockGoClientRole(INVENTORY_ROLE_ID, "Inventory", "d");
        stubBaselineQueries(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(4, roles.length());
        assertEquals(FINANCE_ROLE_ID, roles.getJSONObject(0).getString("id"));
    }

    // ── user count aggregation ───────────────────────────────────────────

    @Test
    @DisplayName("User count reflects distinct users only (duplicate rows for same user count once)")
    void testUserCountCountsDistinctUsersOnly() throws Exception {
        givenSystemAdminCallerRole();
        givenAllFiveGoClientRolesResolve();

        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());

        // First role queried (GOClient Admin) gets 2 rows for the same user -> count 1;
        // every subsequent role gets 0 rows.
        // (Rows are built as plain local statements, NOT inlined into the when(...).thenReturn(...)
        // call below -- nesting further when(...) stubbing calls as arguments to an outer,
        // not-yet-completed when(...).thenReturn(...) chain trips Mockito's
        // UnfinishedStubbingException.)
        List<UserRoles> adminRoleRows = Arrays.asList(mockUserRolesRow("user-1"), mockUserRolesRow("user-1"));
        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(
                adminRoleRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject adminRole = result.getJSONArray("roles").getJSONObject(0);
        assertEquals(1, adminRole.getInt("userCount"));
    }

    // ── window list: GO-window intersection + tier resolution ───────────

    @Test
    @DisplayName("A window not backed by an active Etendo-GO spec is excluded from the windows list")
    void testWindowOutsideEtendoGoSetIsExcluded() throws Exception {
        givenSystemAdminCallerRole();
        givenAllFiveGoClientRolesResolve();

        Window goWindow = mockWindow("go-win-1", "Sales Order");
        Window nativeOnlyWindow = mockWindow("native-win-99", "Native Only Window");

        List<SFSpec> goWindowSpecs = Collections.singletonList(mockGoWindowSpec(goWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

        List<WindowAccess> adminRoleWindowRows = Arrays.asList(
                mockWindowAccessRow(goWindow, true),
                mockWindowAccessRow(nativeOnlyWindow, true));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                adminRoleWindowRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject adminRole = result.getJSONArray("roles").getJSONObject(0);
        JSONArray windows = adminRole.getJSONArray("windows");
        assertEquals(1, windows.length());
        assertEquals("go-win-1", windows.getJSONObject(0).getString("id"));
        assertEquals("Sales Order", windows.getJSONObject(0).getString("name"));
    }

    @Test
    @DisplayName("Window tier resolves to full for IsReadWrite=true and read-only otherwise")
    void testWindowTierResolution() throws Exception {
        givenSystemAdminCallerRole();
        givenAllFiveGoClientRolesResolve();

        Window fullWindow = mockWindow("win-full", "A Full Window");
        Window readOnlyWindow = mockWindow("win-ro", "B Read Only Window");

        List<SFSpec> goWindowSpecs = Arrays.asList(
                mockGoWindowSpec(fullWindow), mockGoWindowSpec(readOnlyWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

        List<WindowAccess> adminRoleWindowRows = Arrays.asList(
                mockWindowAccessRow(fullWindow, true),
                mockWindowAccessRow(readOnlyWindow, false));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                adminRoleWindowRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        webhook.get(parameters, responseVars);

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray windows = result.getJSONArray("roles").getJSONObject(0).getJSONArray("windows");
        assertEquals(2, windows.length());
        // Sorted by name: "A Full Window" before "B Read Only Window".
        assertEquals("full", windows.getJSONObject(0).getString("tier"));
        assertEquals("read-only", windows.getJSONObject(1).getString("tier"));
    }

    // ── exception handling ───────────────────────────────────────────────

    @Test
    @DisplayName("Exception while building the overview sets error, not result")
    void testExceptionSetsError() {
        givenSystemAdminCallerRole();
        // Let the Etendo-GO-window resolution (SFSpec criteria) succeed first, so the thrown
        // exception below is unambiguously coming from the per-role Role lookup, not an
        // incidental NPE from an unstubbed SFSpec criteria.
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        when(obDal.get(eq(Role.class), anyString())).thenThrow(new RuntimeException("DB error"));

        webhook.get(parameters, responseVars);

        assertEquals("DB error", responseVars.get(ERROR));
        assertNull(responseVars.get(RESULT));
    }
}
