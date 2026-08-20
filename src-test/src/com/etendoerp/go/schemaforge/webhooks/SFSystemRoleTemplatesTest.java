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

import static com.etendoerp.go.schemaforge.TestConstants.ERROR;
import static com.etendoerp.go.schemaforge.TestConstants.RESULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link SFSystemRoleTemplates}.
 *
 * <p>Mirrors {@link SFRolesOverviewTest}'s structure. Covers: the admin/client-admin access gate
 * (this endpoint returns system-level role data no ordinary role should see), that role
 * resolution goes through {@link SystemRoleTemplates#byName()}'s fixed ids (never the caller's
 * own tenant, regardless of which client the caller happens to belong to), Finance/Sales/
 * Purchasing/Inventory ordering, graceful degradation when a template id no longer resolves to
 * an active role, the Etendo-GO-window intersection + tier resolution (same convention as
 * {@code SFRolesOverview}), and exception handling.</p>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFSystemRoleTemplatesTest extends BaseWebhookTest {

    private static final String CALLER_CLIENT_ID = "some-tenant-client";

    private SFSystemRoleTemplates webhook;

    @BeforeEach
    void setUp() {
        webhook = new SFSystemRoleTemplates();
    }

    // ── mocking helpers ──────────────────────────────────────────────────

    /** Makes the ambient current role return {@code null} (no role assigned). */
    private void givenNoCallerRole() {
        when(obContext.getRole()).thenReturn(null);
    }

    /** Makes the ambient current role a non-admin, non-client-admin role. */
    private void givenRestrictedCallerRole() {
        Role callerRole = mockCallerRole("restricted-role", "restricted", false);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Makes the ambient current role the literal System Administrator role ({@code "0"}). */
    private void givenSystemAdminCallerRole() {
        Role callerRole = mockCallerRole("0", "System Administrator", false);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Makes the ambient current role a per-tenant "GO Admin" (client-admin) role. */
    private void givenClientAdminCallerRole() {
        Role callerRole = mockCallerRole("tenant-admin-role", "Client Admin caller", true);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    private Role mockCallerRole(String id, String name, boolean isClientAdmin) {
        Role role = mock(Role.class);
        Client client = mock(Client.class);
        when(client.getId()).thenReturn(CALLER_CLIENT_ID);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getClient()).thenReturn(client);
        when(role.isClientAdmin()).thenReturn(isClientAdmin);
        return role;
    }

    /** Builds a mock system-level template {@link Role}, active by default. */
    private Role mockTemplateRole(String id, String name) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.isActive()).thenReturn(true);
        return role;
    }

    /**
     * Stubs {@code OBDal.get(Role.class, id)} for every one of {@link SystemRoleTemplates
     * #byName()}'s 4 fixed ids, returning an active mock role named after the map key, in order.
     */
    private void stubAllFourTemplatesResolve() {
        SystemRoleTemplates.byName().forEach((name, id) -> {
            // Build the mock BEFORE opening the when(...) stub — mockTemplateRole() runs its own
            // when(...).thenReturn(...) calls internally, and Mockito's stubbing state is a
            // thread-local stack: starting a second when(...) chain as an ARGUMENT of the first
            // chain's thenReturn(...) (i.e. evaluating it before the outer chain completes)
            // trips an UnfinishedStubbingException.
            Role templateRole = mockTemplateRole(id, name);
            when(obDal.get(Role.class, id)).thenReturn(templateRole);
        });
    }

    private Window mockWindow(String id, String name) {
        Window w = mock(Window.class);
        when(w.getId()).thenReturn(id);
        when(w.getName()).thenReturn(name);
        return w;
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
     * Stubs the Etendo-GO-window resolution query (SFSpec criteria) to return specs backing
     * {@code goWindows}, and the per-role {@code WindowAccess} criteria to return
     * {@code emptyList} — the common baseline for tests that don't care about window data.
     */
    private void stubEmptyWindowAccess(List<Window> goWindows) {
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        List<SFSpec> specs = new java.util.ArrayList<>();
        for (Window w : goWindows) {
            specs.add(mockGoWindowSpec(w));
        }
        when(specCriteria.list()).thenReturn(specs);

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());
    }

    // ── access gate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("No caller role returns empty roles array without resolving any template role")
    void testNoCallerRoleReturnsEmptyRoles() throws Exception {
        givenNoCallerRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).get(Role.class, SystemRoleTemplates.FINANCE_ROLE_ID);
    }

    @Test
    @DisplayName("Restricted (non-admin) caller role returns empty roles array")
    void testRestrictedCallerRoleReturnsEmptyRoles() throws Exception {
        givenRestrictedCallerRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).get(Role.class, SystemRoleTemplates.FINANCE_ROLE_ID);
    }

    @Test
    @DisplayName("System Administrator caller (role id '0') passes the gate")
    void testSystemAdminCallerPassesGate() throws Exception {
        givenSystemAdminCallerRole();
        stubAllFourTemplatesResolve();
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(4, result.getJSONArray("roles").length());
    }

    @Test
    @DisplayName("Client-admin caller (is_client_admin='Y') passes the gate")
    void testClientAdminCallerPassesGate() throws Exception {
        givenClientAdminCallerRole();
        stubAllFourTemplatesResolve();
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(4, result.getJSONArray("roles").length());
    }

    // ── system-level (not tenant-scoped) resolution ──────────────────────

    /**
     * Regression proof that this webhook resolves the fixed {@link SystemRoleTemplates} ids
     * directly via {@code OBDal.get(Role.class, id)} — never a client-scoped {@code Role}
     * criteria keyed off the caller's own client, the way {@code SFRolesOverview} does. The
     * caller here belongs to a tenant client that never even appears in the stubbing, proving
     * the result is independent of it.
     */
    @Test
    @DisplayName("Roles are resolved via the fixed SystemRoleTemplates ids, independent of the caller's own client")
    void testRolesResolvedViaFixedSystemTemplateIds() throws Exception {
        givenSystemAdminCallerRole();
        stubAllFourTemplatesResolve();
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(4, roles.length());
        assertEquals(SystemRoleTemplates.FINANCE_ROLE_ID, roles.getJSONObject(0).getString("id"));
        assertEquals(SystemRoleTemplates.SALES_ROLE_ID, roles.getJSONObject(1).getString("id"));
        assertEquals(SystemRoleTemplates.PURCHASING_ROLE_ID, roles.getJSONObject(2).getString("id"));
        assertEquals(SystemRoleTemplates.INVENTORY_ROLE_ID, roles.getJSONObject(3).getString("id"));
        verify(obDal, never()).createCriteria(Role.class);
    }

    /** No {@code userCount} and no client-admin row — this endpoint's response shape omits both. */
    @Test
    @DisplayName("Response omits userCount and isClientAdmin entirely")
    void testResponseOmitsUserCountAndClientAdminFlag() throws Exception {
        givenSystemAdminCallerRole();
        stubAllFourTemplatesResolve();
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject financeRole = result.getJSONArray("roles").getJSONObject(0);
        assertFalse(financeRole.has("userCount"));
        assertFalse(financeRole.has("isClientAdmin"));
    }

    // ── ordering / graceful degradation ──────────────────────────────────

    @Test
    @DisplayName("Roles are returned in Finance/Sales/Purchasing/Inventory order")
    void testRolesReturnedInFixedOrder() throws Exception {
        givenSystemAdminCallerRole();
        stubAllFourTemplatesResolve();
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        JSONArray roles = new JSONObject(responseVars.get(RESULT)).getJSONArray("roles");
        assertEquals("Finance", roles.getJSONObject(0).getString("name"));
        assertEquals("Sales", roles.getJSONObject(1).getString("name"));
        assertEquals("Purchasing", roles.getJSONObject(2).getString("name"));
        assertEquals("Inventory", roles.getJSONObject(3).getString("name"));
    }

    /**
     * A template id that no longer resolves to an active {@code Role} (deleted, or deactivated)
     * is skipped, not surfaced as an error and not left as a null/partial entry.
     */
    @Test
    @DisplayName("A template id resolving to null is skipped, not an error")
    void testMissingTemplateRoleIsSkippedGracefully() throws Exception {
        givenSystemAdminCallerRole();
        Role sales = mockTemplateRole(SystemRoleTemplates.SALES_ROLE_ID, "Sales");
        Role purchasing = mockTemplateRole(SystemRoleTemplates.PURCHASING_ROLE_ID, "Purchasing");
        Role inventory = mockTemplateRole(SystemRoleTemplates.INVENTORY_ROLE_ID, "Inventory");
        when(obDal.get(Role.class, SystemRoleTemplates.FINANCE_ROLE_ID)).thenReturn(null);
        when(obDal.get(Role.class, SystemRoleTemplates.SALES_ROLE_ID)).thenReturn(sales);
        when(obDal.get(Role.class, SystemRoleTemplates.PURCHASING_ROLE_ID)).thenReturn(purchasing);
        when(obDal.get(Role.class, SystemRoleTemplates.INVENTORY_ROLE_ID)).thenReturn(inventory);
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONArray roles = new JSONObject(responseVars.get(RESULT)).getJSONArray("roles");
        assertEquals(3, roles.length());
    }

    /** An inactive (but still resolvable) template role is likewise skipped. */
    @Test
    @DisplayName("An inactive template role is skipped, not an error")
    void testInactiveTemplateRoleIsSkippedGracefully() throws Exception {
        givenSystemAdminCallerRole();
        Role inactiveFinance = mockTemplateRole(SystemRoleTemplates.FINANCE_ROLE_ID, "Finance");
        when(inactiveFinance.isActive()).thenReturn(false);
        Role sales = mockTemplateRole(SystemRoleTemplates.SALES_ROLE_ID, "Sales");
        Role purchasing = mockTemplateRole(SystemRoleTemplates.PURCHASING_ROLE_ID, "Purchasing");
        Role inventory = mockTemplateRole(SystemRoleTemplates.INVENTORY_ROLE_ID, "Inventory");
        when(obDal.get(Role.class, SystemRoleTemplates.FINANCE_ROLE_ID)).thenReturn(inactiveFinance);
        when(obDal.get(Role.class, SystemRoleTemplates.SALES_ROLE_ID)).thenReturn(sales);
        when(obDal.get(Role.class, SystemRoleTemplates.PURCHASING_ROLE_ID)).thenReturn(purchasing);
        when(obDal.get(Role.class, SystemRoleTemplates.INVENTORY_ROLE_ID)).thenReturn(inventory);
        stubEmptyWindowAccess(Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONArray roles = new JSONObject(responseVars.get(RESULT)).getJSONArray("roles");
        assertEquals(3, roles.length());
    }

    // ── window list: GO-window intersection + tier resolution ───────────

    @Test
    @DisplayName("A window not backed by an active Etendo-GO spec is excluded from the windows list")
    void testWindowOutsideEtendoGoSetIsExcluded() throws Exception {
        givenSystemAdminCallerRole();
        stubAllFourTemplatesResolve();

        Window goWindow = mockWindow("go-win-1", "Sales Order");
        Window nativeOnlyWindow = mockWindow("native-win-99", "Native Only Window");

        List<SFSpec> goWindowSpecs = Collections.singletonList(mockGoWindowSpec(goWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        List<WindowAccess> financeWindowRows = List.of(
                mockWindowAccessRow(goWindow, true),
                mockWindowAccessRow(nativeOnlyWindow, true));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                financeWindowRows,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject financeRole = new JSONObject(responseVars.get(RESULT)).getJSONArray("roles").getJSONObject(0);
        JSONArray windows = financeRole.getJSONArray("windows");
        assertEquals(1, windows.length());
        assertEquals("go-win-1", windows.getJSONObject(0).getString("id"));
    }

    @Test
    @DisplayName("Window tier resolves to full for IsReadWrite=true and read-only otherwise")
    void testWindowTierResolution() throws Exception {
        givenSystemAdminCallerRole();
        stubAllFourTemplatesResolve();

        Window fullWindow = mockWindow("win-full", "A Full Window");
        Window readOnlyWindow = mockWindow("win-ro", "B Read Only Window");

        List<SFSpec> goWindowSpecs = List.of(
                mockGoWindowSpec(fullWindow), mockGoWindowSpec(readOnlyWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        List<WindowAccess> financeWindowRows = List.of(
                mockWindowAccessRow(fullWindow, true),
                mockWindowAccessRow(readOnlyWindow, false));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                financeWindowRows,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        webhook.get(parameters, responseVars);

        JSONArray windows = new JSONObject(responseVars.get(RESULT))
                .getJSONArray("roles").getJSONObject(0).getJSONArray("windows");
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
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenThrow(new RuntimeException("DB error"));

        webhook.get(parameters, responseVars);

        assertEquals("DB error", responseVars.get(ERROR));
        assertNull(responseVars.get(RESULT));
    }
}
