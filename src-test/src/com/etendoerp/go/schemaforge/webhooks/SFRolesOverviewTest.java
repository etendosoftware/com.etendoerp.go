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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.SimpleExpression;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link SFRolesOverview}.
 *
 * <p>Covers: the admin/client-admin access gate (ETP-4513's key security requirement — this
 * webhook returns cross-role aggregate data no ordinary role should see), tenant-relative role
 * resolution (2026-07-27 fix — the calling tenant's own client-admin + 4 named roles, NOT
 * GOClient's hardcoded ids, see {@link SFRolesOverview}'s class javadoc for the live bug this
 * closes), per-role user-count and window-list aggregation, the Etendo-GO-window intersection (a
 * role's native {@code AD_Window_Access} rows are filtered down to windows Etendo GO actually
 * exposes), tier resolution (full vs read-only), and exception handling.</p>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFRolesOverviewTest extends BaseWebhookTest {

    private static final String CLIENT_ID = "tenant-client-1";
    private static final String ADMIN_ROLE_ID = "tenant-admin-role";
    private static final String FINANCE_ROLE_ID = "tenant-finance-role";
    private static final String SALES_ROLE_ID = "tenant-sales-role";
    private static final String PURCHASING_ROLE_ID = "tenant-purchasing-role";
    private static final String INVENTORY_ROLE_ID = "tenant-inventory-role";

    private SFRolesOverview webhook;
    private NativeQuery<Object[]> categoryQuery;

    @BeforeEach
    void setUp() {
        webhook = new SFRolesOverview();

        // ETP-4907: buildRolesOverview() unconditionally resolves the matrix's window
        // categories via a native query. Default every test to "no category rows" (every
        // window falls back to the "Other" bucket) — tests that care about category grouping
        // override categoryQuery's stub explicitly.
        Session mockSession = mock(Session.class);
        when(obDal.getSession()).thenReturn(mockSession);
        categoryQuery = mock(NativeQuery.class);
        when(mockSession.createNativeQuery(anyString())).thenReturn(categoryQuery);
        when(categoryQuery.getResultList()).thenReturn(Collections.emptyList());

        // ETP-5071: every role card now also resolves 3 proxy access tiers (2 extra
        // WindowAccess lookups plus one ProcessAccess lookup — see
        // SFRolesOverview#mergeProxyAccessTiers) right after its own real-GO-window tier map.
        // Default the new ProcessAccess criteria to "no grants" so tests that don't care about
        // it don't need to know about it (mirrors categoryQuery's own default above). Tests
        // that stub OBCriteria<WindowAccess>'s list() with an exact per-role sequence must
        // account for the 2 extra WindowAccess.list() calls per role card this introduces.
        OBCriteria<ProcessAccess> processAccessCriteria = mockCriteria(ProcessAccess.class);
        when(processAccessCriteria.list()).thenReturn(Collections.emptyList());
    }

    // ── mocking helpers ──────────────────────────────────────────────────

    /** Makes the ambient current role return {@code null} (no role assigned). */
    private void givenNoCallerRole() {
        when(obContext.getRole()).thenReturn(null);
    }

    /** Makes the ambient current role a non-admin, non-client-admin role in {@code CLIENT_ID}. */
    private void givenRestrictedCallerRole(String roleId) {
        Role callerRole = mockRole(roleId, "restricted", false);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Makes the ambient current role the literal System Administrator role ({@code "0"}). */
    private void givenSystemAdminCallerRole() {
        Role callerRole = mockRole("0", "System Administrator", false);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Makes the ambient current role a per-client "GO Admin" (client-admin) role. */
    private void givenClientAdminCallerRole(String roleId) {
        Role callerRole = mockRole(roleId, "Client Admin caller", true);
        when(obContext.getRole()).thenReturn(callerRole);
    }

    /** Builds a mock {@link Role} with a client bound to {@link #CLIENT_ID}. */
    private Role mockRole(String id, String name, boolean isClientAdmin) {
        Role role = mock(Role.class);
        Client client = mock(Client.class);
        when(client.getId()).thenReturn(CLIENT_ID);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getClient()).thenReturn(client);
        when(role.isClientAdmin()).thenReturn(isClientAdmin);
        return role;
    }

    /**
     * Stubs the tenant-role-resolution {@code OBCriteria<Role>} to return exactly the given
     * roles (in whatever order supplied — {@link SFRolesOverview} sorts them itself, so tests
     * that care about ordering pass them scrambled and assert on the response order).
     */
    private void stubTenantRoles(List<Role> roles) {
        OBCriteria<Role> roleCriteria = mockCriteria(Role.class);
        when(roleCriteria.list()).thenReturn(roles);
    }

    /** The tenant's standard 5 roles, admin first, matching {@code SFRolesOverview}'s own order. */
    private List<Role> standardTenantRoles() {
        return Arrays.asList(
                mockRole(ADMIN_ROLE_ID, "RolesPresa Admin", true),
                mockRole(FINANCE_ROLE_ID, "Finance", false),
                mockRole(SALES_ROLE_ID, "Sales", false),
                mockRole(PURCHASING_ROLE_ID, "Purchasing", false),
                mockRole(INVENTORY_ROLE_ID, "Inventory", false));
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
     * backing {@code goWindows}, the tenant-role criteria to return {@code roles}, and the
     * {@code UserRoles}/{@code WindowAccess} criteria to return {@code emptyList} for every
     * per-role query — the common baseline for tests that only care about one role's data (set
     * that role's rows individually afterward via a fresh {@code thenReturn} sequence).
     */
    private void stubBaselineQueries(List<Role> roles, List<Window> goWindows) {
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        List<SFSpec> specs = new java.util.ArrayList<>();
        for (Window w : goWindows) {
            specs.add(mockGoWindowSpec(w));
        }
        when(specCriteria.list()).thenReturn(specs);

        stubTenantRoles(roles);

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());
    }

    /**
     * Invokes {@code webhook.get(parameters, responseVars)} under a default, empty {@link
     * UserRoleCompositionService} construction stub.
     *
     * <p>ETP-5065 Fix 2 made {@link SFRolesOverview#addTenantRoleCardWithTemplateOverlap}
     * resolve composition data for the FIRST active fixed-name tenant role unconditionally
     * (previously, composition was only ever queried when a fixed name had NO active tenant
     * role — the system-template-fallback branch). Any test whose tenant roles include at
     * least one active fixed-name role (e.g. built via {@link #standardTenantRoles()}) now
     * triggers a REAL {@code new UserRoleCompositionService()} unless this stub — or a
     * test-specific {@code mockConstruction} block, for tests that care about a particular
     * composed map — is in scope.</p>
     */
    private void invokeWebhookWithNoTemplateComposition() {
        try (MockedConstruction<UserRoleCompositionService> ignored = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(anyString()))
                                .thenReturn(Collections.emptyMap()))) {
            webhook.get(parameters, responseVars);
        }
    }

    // ── access gate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("No caller role returns empty roles array without resolving any tenant role")
    void testNoCallerRoleReturnsEmptyRoles() throws Exception {
        givenNoCallerRole();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).createCriteria(Role.class);
    }

    @Test
    @DisplayName("Restricted (non-admin) caller role returns empty roles array")
    void testRestrictedCallerRoleReturnsEmptyRoles() throws Exception {
        givenRestrictedCallerRole("some-ordinary-role");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).createCriteria(Role.class);
    }

    /**
     * The gate must key off {@link com.etendoerp.go.schemaforge.util.NeoAccessHelper
     * #isAdminOrClientAdmin(Role)} only — never off whether the caller's own role id happens to
     * be one of the tenant's own 5 fixed roles. A caller authenticated AS the Finance role (one
     * of the 5 fixed roles, but not admin/client-admin) must be denied exactly like any other
     * restricted role.
     */
    @Test
    @DisplayName("Caller authenticated as one of the tenant's 5 fixed roles (Finance), but not admin/client-admin, is still denied")
    void testCallerIsAFixedRoleButNotAdminIsStillDenied() throws Exception {
        givenRestrictedCallerRole(FINANCE_ROLE_ID);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(0, result.getJSONArray("roles").length());
        verify(obDal, never()).createCriteria(Role.class);
    }

    @Test
    @DisplayName("System Administrator caller (role id '0') passes the gate")
    void testSystemAdminCallerPassesGate() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(standardTenantRoles(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(5, result.getJSONArray("roles").length());
    }

    @Test
    @DisplayName("Client-admin caller (is_client_admin='Y', non-zero id) passes the gate")
    void testClientAdminCallerPassesGate() throws Exception {
        givenClientAdminCallerRole(ADMIN_ROLE_ID);
        stubBaselineQueries(standardTenantRoles(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(5, result.getJSONArray("roles").length());
    }

    // ── tenant-relative resolution (2026-07-27 fix) ──────────────────────

    /**
     * Regression test for the live RolesPresa bug: the role-resolution query must be scoped to
     * the CALLER's own client, not a hardcoded GOClient id list. Verified here by asserting the
     * criteria was built against {@code Role.class} at all (the old code never queried a Role
     * criteria — it used {@code OBDal.get(Role.class, hardcodedId)} instead) and that the
     * returned roles' own ids (this tenant's, not GOClient's) come through untouched.
     */
    @Test
    @DisplayName("Roles are resolved via a client-scoped criteria query, not hardcoded GOClient ids")
    void testRolesResolvedViaClientScopedCriteria() throws Exception {
        givenClientAdminCallerRole(ADMIN_ROLE_ID);
        stubBaselineQueries(standardTenantRoles(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(5, roles.length());
        verify(obDal).createCriteria(Role.class);
        // None of these ids match the old hardcoded GOClient constants — proves the response
        // reflects THIS tenant's own roles, not GOClient's.
        assertEquals(ADMIN_ROLE_ID, roles.getJSONObject(0).getString("id"));
    }

    /**
     * A tenant missing one of its 4 fixed-name roles (e.g. provisioning ran before it existed)
     * simply gets fewer entries — there is no per-id null-skip branch anymore (that was an
     * artifact of the old hardcoded-id-list design); the criteria naturally returns only what
     * exists for this client.
     */
    @Test
    @DisplayName("A tenant with fewer than 5 matching roles returns exactly what exists, not an error")
    void testFewerThanFiveRolesReturnsWhatExists() throws Exception {
        givenSystemAdminCallerRole();
        List<Role> onlyFour = Arrays.asList(
                mockRole(ADMIN_ROLE_ID, "RolesPresa Admin", true),
                mockRole(FINANCE_ROLE_ID, "Finance", false),
                mockRole(SALES_ROLE_ID, "Sales", false),
                mockRole(PURCHASING_ROLE_ID, "Purchasing", false));
        stubBaselineQueries(onlyFour, Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        assertEquals(4, result.getJSONArray("roles").length());
    }

    // ── role shape / ordering ────────────────────────────────────────────

    @Test
    @DisplayName("Roles are returned admin-first, then Finance/Sales/Purchasing/Inventory, regardless of query order")
    void testRolesSortedAdminFirstThenFixedNameOrder() throws Exception {
        givenSystemAdminCallerRole();
        // Deliberately scrambled — SFRolesOverview must sort these itself, not trust query order.
        List<Role> scrambled = Arrays.asList(
                mockRole(INVENTORY_ROLE_ID, "Inventory", false),
                mockRole(FINANCE_ROLE_ID, "Finance", false),
                mockRole(ADMIN_ROLE_ID, "RolesPresa Admin", true),
                mockRole(PURCHASING_ROLE_ID, "Purchasing", false),
                mockRole(SALES_ROLE_ID, "Sales", false));
        stubBaselineQueries(scrambled, Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(5, roles.length());
        assertEquals(ADMIN_ROLE_ID, roles.getJSONObject(0).getString("id"));
        assertTrue(roles.getJSONObject(0).getBoolean("isClientAdmin"));
        assertEquals(FINANCE_ROLE_ID, roles.getJSONObject(1).getString("id"));
        assertEquals(SALES_ROLE_ID, roles.getJSONObject(2).getString("id"));
        assertEquals(PURCHASING_ROLE_ID, roles.getJSONObject(3).getString("id"));
        assertEquals(INVENTORY_ROLE_ID, roles.getJSONObject(4).getString("id"));
        for (int i = 1; i < roles.length(); i++) {
            assertFalse(roles.getJSONObject(i).getBoolean("isClientAdmin"),
                    "Only the admin role (index 0) should carry isClientAdmin=true");
        }
    }

    // ── user count aggregation ───────────────────────────────────────────

    @Test
    @DisplayName("User count reflects distinct users only (duplicate rows for same user count once)")
    void testUserCountCountsDistinctUsersOnly() throws Exception {
        givenSystemAdminCallerRole();

        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        stubTenantRoles(standardTenantRoles());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());

        // The first role queried (admin, sorted first), receives two rows for the same user,
        // which should count as a single distinct user. Every subsequent role receives zero rows.
        List<UserRoles> adminRoleRows = Arrays.asList(mockUserRolesRow("user-1"), mockUserRolesRow("user-1"));
        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(
                adminRoleRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject adminRole = result.getJSONArray("roles").getJSONObject(0);
        assertEquals(1, adminRole.getInt("userCount"));
    }

    /**
     * A role with zero active {@code AD_User_Roles} rows AND zero active
     * {@code AD_Window_Access} rows (e.g. a newly-provisioned role that hasn't been assigned any
     * users or windows yet) must degrade gracefully to {@code userCount: 0} and an empty
     * {@code windows} array for EVERY one of the 5 roles — not throw, and not silently omit the
     * role from the response.
     */
    @Test
    @DisplayName("A role with zero users and zero window-access rows degrades to userCount=0 and an empty windows array, not an error")
    void testRoleWithNoUsersAndNoWindowAccessDegradesGracefully() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(standardTenantRoles(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(5, roles.length());
        for (int i = 0; i < roles.length(); i++) {
            JSONObject role = roles.getJSONObject(i);
            assertEquals(0, role.getInt("userCount"), "userCount for role " + role.getString("id"));
            assertEquals(0, role.getJSONArray("windows").length(), "windows for role " + role.getString("id"));
        }
    }

    // ── ETP-5065 Fix 1: cross-client bootstrap-user exclusion ───────────

    /**
     * Regression test for ETP-5065 Fix 1: a role's user count must exclude a cross-client
     * bootstrap login (the seed {@code AD_User_ID='100'} account, client {@code '0'}) even
     * though it holds a real, active {@code AD_User_Roles} row on the tenant's admin role — see
     * {@link SFRolesOverview#resolveActiveUserIds(Role)}'s javadoc for the root cause.
     *
     * <p>Because {@link OBCriteria} is fully mocked here, {@code criteria.list()} cannot exercise
     * real Hibernate-level filtering — the mocked return value is entirely test-controlled and
     * would report the same count whether or not the fix's restriction exists. So this test
     * verifies BOTH halves: (1) structurally, that {@link SFRolesOverview#resolveActiveUserIds}
     * actually adds the {@code userContact.client.id} restriction to the query (captured via
     * {@link ArgumentCaptor}, since that is the only way a mocked-criteria unit test can prove the
     * fix's Hibernate restriction exists at all), and (2) the resulting {@code userCount}, given a
     * {@code list()} return value that simulates what the DB would hand back once that
     * restriction is applied (i.e. with the cross-client bootstrap row already excluded).</p>
     */
    @Test
    @DisplayName("Admin role user count excludes the cross-client bootstrap user")
    void testAdminRoleCountExcludesCrossClientBootstrapUser() throws Exception {
        givenSystemAdminCallerRole();

        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        stubTenantRoles(standardTenantRoles());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());

        // Only the real tenant-client owner survives the client-scoped restriction added by
        // Fix 1 — the cross-client bootstrap user (AD_User_ID='100', client '0') would be
        // filtered out by the real DB; this simulates that already-filtered result.
        List<UserRoles> adminRoleRows = Collections.singletonList(mockUserRolesRow("real-owner"));
        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(
                adminRoleRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject adminRole = result.getJSONArray("roles").getJSONObject(0);
        assertEquals(1, adminRole.getInt("userCount"));

        // The restriction must reach the user's OWN client, and must be expressed through an
        // explicit alias. It used to be written as the two-level path "userContact.client.id",
        // which compiles and passes a mocked criteria but throws "could not resolve property" from
        // AbstractEntityPersister.toColumns the moment Hibernate runs it — a Criteria resolves a
        // one-level "property.id" (the FK column on this table) and nothing deeper. That shipped
        // and answered 500 for the whole Roles page.
        //
        // So this asserts the PAIR — the alias on userContact, and the client restriction hanging
        // off that alias — instead of one hardcoded property string. The alias NAME is captured
        // rather than assumed, so renaming it stays a free refactor while dropping either half
        // still fails here.
        ArgumentCaptor<String> aliasCaptor = ArgumentCaptor.forClass(String.class);
        // atLeastOnce: the same mocked criteria is reused across the five roles of the overview.
        verify(userRolesCriteria, atLeastOnce())
                .createAlias(eq(UserRoles.PROPERTY_USERCONTACT), aliasCaptor.capture());
        String userContactAlias = aliasCaptor.getValue();

        ArgumentCaptor<Criterion> restrictionCaptor = ArgumentCaptor.forClass(Criterion.class);
        verify(userRolesCriteria, atLeastOnce()).add(restrictionCaptor.capture());
        boolean hasClientRestriction = restrictionCaptor.getAllValues().stream()
                .filter(SimpleExpression.class::isInstance)
                .map(SimpleExpression.class::cast)
                .anyMatch(expr -> (userContactAlias + ".client.id").equals(expr.getPropertyName()));
        assertTrue(hasClientRestriction,
                "resolveActiveUserIds must restrict the assignee's own client through the "
                        + UserRoles.PROPERTY_USERCONTACT + " alias, not a nested property path");
    }

    /**
     * The promote/demote admin design (see {@link SFRolesOverview#resolveActiveUserIds(Role)}'s
     * javadoc) legitimately assigns the tenant admin role directly, in {@code AD_User_Roles}, to
     * one or more real, same-client users at once. Fix 1's added client-scoping restriction must
     * not accidentally cap or dedupe this down — two DISTINCT same-client users must both count.
     */
    @Test
    @DisplayName("Admin role user count allows two distinct same-client direct assignees (promote/demote design)")
    void testAdminRoleCountAllowsMultipleSameClientDirectAssignees() throws Exception {
        givenSystemAdminCallerRole();

        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        stubTenantRoles(standardTenantRoles());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());

        List<UserRoles> adminRoleRows = Arrays.asList(mockUserRolesRow("owner-1"), mockUserRolesRow("owner-2"));
        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(
                adminRoleRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject adminRole = result.getJSONArray("roles").getJSONObject(0);
        assertEquals(2, adminRole.getInt("userCount"));
    }

    // ── ETP-5065 Fix 2: hybrid-state template-overlap union ─────────────

    /**
     * Regression test for ETP-5065 Fix 2 (the live GOClient hybrid-state undercount): when the
     * tenant's own active Finance role has 1 direct assignee, but a DIFFERENT user reaches the
     * same access by composing the matching SYSTEM TEMPLATE Finance role onto their personal
     * role ({@link UserRoleCompositionService}), the Finance card's {@code userCount} must be the
     * union of both sources — before this fix, {@link SFRolesOverview#addTenantRoleCard} (used
     * for every fixed name whose tenant role is still active) only ever saw the direct assignee,
     * silently dropping the template-composed user from the card entirely.
     */
    @Test
    @DisplayName("Finance card userCount unions direct tenant-role assignees with template-composed users")
    void testFinanceCardUnionsDirectAssigneesWithTemplateComposedUsers() throws Exception {
        givenSystemAdminCallerRole();

        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        stubTenantRoles(standardTenantRoles());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());

        // UserRoles.list() is invoked once per role card, in order: admin, then Finance/Sales/
        // Purchasing/Inventory (SystemRoleTemplates#byName order). Only Finance has a direct
        // assignee.
        List<UserRoles> financeDirectRows = Collections.singletonList(mockUserRolesRow("finance-tester"));
        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(
                Collections.emptyList(),
                financeDirectRows,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        // A different user, "invite1", composes the TEMPLATE Finance role onto their personal
        // role — not present in Finance's direct-assignee rows above.
        Map<String, List<String>> composed = new LinkedHashMap<>();
        composed.put("invite1", List.of(SystemRoleTemplates.FINANCE_ROLE_ID));
        try (MockedConstruction<UserRoleCompositionService> construction = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(CLIENT_ID)).thenReturn(composed))) {

            webhook.get(parameters, responseVars);

            assertEquals(1, construction.constructed().size(),
                    "UserRoleCompositionService must be built lazily, once, for the whole request, "
                            + "even though all 4 fixed-name roles are active tenant roles here");
        }

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject finance = result.getJSONArray("roles").getJSONObject(1);
        assertEquals(FINANCE_ROLE_ID, finance.getString("id"));
        assertEquals("tenant", finance.getString("roleSource"));
        assertEquals(2, finance.getInt("userCount"));
    }

    /**
     * A user who satisfies BOTH conditions at once — directly assigned to the tenant's own
     * Finance role AND (redundantly) composing the matching system-template Finance role onto
     * their personal role — must be counted exactly once, not twice. Proves the union in {@link
     * SFRolesOverview#addTenantRoleCardWithTemplateOverlap} is a real set union (dedup by user
     * id), not a naive count addition.
     */
    @Test
    @DisplayName("Finance card userCount does not double-count a user satisfying both the direct and template-composed conditions")
    void testFinanceCardDoesNotDoubleCountSameUserInBothPaths() throws Exception {
        givenSystemAdminCallerRole();

        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        stubTenantRoles(standardTenantRoles());

        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(Collections.emptyList());

        List<UserRoles> financeDirectRows = Collections.singletonList(mockUserRolesRow("hybrid-user"));
        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(
                Collections.emptyList(),
                financeDirectRows,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        // The SAME user id also composes the template Finance role onto their personal role.
        Map<String, List<String>> composed = new LinkedHashMap<>();
        composed.put("hybrid-user", List.of(SystemRoleTemplates.FINANCE_ROLE_ID));
        try (MockedConstruction<UserRoleCompositionService> construction = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(CLIENT_ID)).thenReturn(composed))) {
            webhook.get(parameters, responseVars);
        }

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONObject finance = result.getJSONArray("roles").getJSONObject(1);
        assertEquals(1, finance.getInt("userCount"));
    }

    // ── window list: GO-window intersection + tier resolution ───────────

    @Test
    @DisplayName("A window not backed by an active Etendo-GO spec is excluded from the windows list")
    void testWindowOutsideEtendoGoSetIsExcluded() throws Exception {
        givenSystemAdminCallerRole();
        stubTenantRoles(standardTenantRoles());

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

        invokeWebhookWithNoTemplateComposition();

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
        stubTenantRoles(standardTenantRoles());

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

        invokeWebhookWithNoTemplateComposition();

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray windows = result.getJSONArray("roles").getJSONObject(0).getJSONArray("windows");
        assertEquals(2, windows.length());
        // Sorted by name: "A Full Window" before "B Read Only Window".
        assertEquals("full", windows.getJSONObject(0).getString("tier"));
        assertEquals("read-only", windows.getJSONObject(1).getString("tier"));
    }

    // ── ETP-4907: system-template fallback ──────────────────────────────

    /** Builds a mock system-level template {@link Role}, active by default. */
    private Role mockTemplateRole(String id, String name) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.isActive()).thenReturn(true);
        when(role.isClientAdmin()).thenReturn(false);
        return role;
    }

    /**
     * Stubs {@code OBDal.get(Role.class, id)} for every one of {@link SystemRoleTemplates
     * #byName()}'s 4 fixed ids, returning an active mock role named after the map key.
     */
    private void stubAllFourTemplatesResolve() {
        SystemRoleTemplates.byName().forEach((name, id) -> {
            Role templateRole = mockTemplateRole(id, name);
            when(obDal.get(Role.class, id)).thenReturn(templateRole);
        });
    }

    /**
     * A tenant that has migrated to ETP-4852 system-level templates (its own "Finance"/"Sales"/
     * "Purchasing"/"Inventory" rows deactivated — the live GOClient state, confirmed 2026-08-18)
     * must still get 5 role cards: {@link #resolveTenantRoles} only returns the client-admin
     * role, so the 4 fixed names fall back to the system templates, sourcing {@code userCount}
     * from {@link UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)} (never
     * a direct {@code AD_User_Roles} count against the template) and {@code windows} from the
     * SAME window-tier resolution a real tenant role uses.
     */
    @Test
    @DisplayName("Missing tenant roles fall back to system-level templates, composition-based userCount")
    void testSystemTemplateFallbackWhenTenantRolesAreMissing() throws Exception {
        givenSystemAdminCallerRole();

        Window salesOrderWindow = mockWindow("win-1", "Sales Order");
        List<SFSpec> goWindowSpecs = Collections.singletonList(mockGoWindowSpec(salesOrderWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        // Only the client-admin role exists at the tenant level — the 4 fixed names are absent
        // (mirrors GOClient's live, migrated state).
        stubTenantRoles(Collections.singletonList(mockRole(ADMIN_ROLE_ID, "GOClient Admin", true)));
        stubAllFourTemplatesResolve();

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList()); // admin's own count

        // WindowAccess is queried once per role card: admin, then Finance/Sales/Purchasing/
        // Inventory (SystemRoleTemplates#byName order) — only Finance's template grants the one
        // GO window.
        List<WindowAccess> financeWindowRows =
                Collections.singletonList(mockWindowAccessRow(salesOrderWindow, true));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                Collections.emptyList(),
                financeWindowRows,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        Map<String, List<String>> composed = new LinkedHashMap<>();
        composed.put("user-1", List.of(SystemRoleTemplates.FINANCE_ROLE_ID));
        composed.put("user-2", List.of());
        try (MockedConstruction<UserRoleCompositionService> construction = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(CLIENT_ID)).thenReturn(composed))) {

            webhook.get(parameters, responseVars);

            assertEquals(1, construction.constructed().size(),
                    "UserRoleCompositionService must be built lazily, once, for the whole request");
        }

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(5, roles.length());

        JSONObject admin = roles.getJSONObject(0);
        assertEquals("tenant", admin.getString("roleSource"));

        JSONObject finance = roles.getJSONObject(1);
        assertEquals(SystemRoleTemplates.FINANCE_ROLE_ID, finance.getString("id"));
        assertEquals("systemTemplate", finance.getString("roleSource"));
        assertEquals(1, finance.getInt("userCount"));
        assertEquals(1, finance.getInt("windowCount"));
        assertEquals("win-1", finance.getJSONArray("windows").getJSONObject(0).getString("id"));

        JSONObject sales = roles.getJSONObject(2);
        assertEquals(SystemRoleTemplates.SALES_ROLE_ID, sales.getString("id"));
        assertEquals("systemTemplate", sales.getString("roleSource"));
        assertEquals(0, sales.getInt("userCount"));
        assertEquals(0, sales.getInt("windowCount"));
    }

    /**
     * When the tenant's own role for a fixed name is still active, it must be used as-is (not
     * overridden by its system-template counterpart) — the system-template fallback ({@code
     * roleSource: "systemTemplate"}) is only for names with NO active tenant-scoped match. Proves
     * the two paths coexist correctly rather than one always winning.
     *
     * <p>Unlike before ETP-5065 Fix 2, {@link UserRoleCompositionService} IS now constructed here
     * (exactly once, lazily, for the first active fixed-name role — Finance) even though every
     * fixed name already has an active tenant role: {@link
     * SFRolesOverview#addTenantRoleCardWithTemplateOverlap} unions direct assignees with
     * template-composed users unconditionally, not only in the hybrid-state case. With an empty
     * composed map (no personal-role composition configured in this test), that union changes
     * nothing observable here — {@code roleSource} stays {@code "tenant"} and the ids stay the
     * tenant's own — so this test still proves what its name says, just no longer via "never
     * constructed".</p>
     */
    @Test
    @DisplayName("An active tenant role is preferred over its system-template counterpart")
    void testActiveTenantRoleIsNotOverriddenByTemplate() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(standardTenantRoles(), Collections.emptyList());

        try (MockedConstruction<UserRoleCompositionService> construction = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(anyString()))
                                .thenReturn(Collections.emptyMap()))) {
            webhook.get(parameters, responseVars);

            assertEquals(1, construction.constructed().size(),
                    "UserRoleCompositionService must be built lazily, once, for the whole request — "
                            + "ETP-5065 Fix 2 resolves it for the first active fixed-name role too, not "
                            + "only in the system-template-fallback branch");
        }

        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        for (int i = 0; i < roles.length(); i++) {
            assertEquals("tenant", roles.getJSONObject(i).getString("roleSource"));
        }
        assertEquals(FINANCE_ROLE_ID, roles.getJSONObject(1).getString("id"));
    }

    /**
     * When the system-template role itself does not resolve at all (missing {@code AD_Role} row
     * — e.g. deleted or never seeded) for a fixed name with no active tenant-scoped match, that
     * name is simply absent from the response — degrading gracefully like the existing
     * "fewer than 5 roles" case — rather than throwing. Targets the specific early-return branch
     * in {@link SFRolesOverview#addSystemTemplateRoleCardIfResolvable} triggered by
     * {@code OBDal.get(Role.class, templateId) == null}.
     */
    @Test
    @DisplayName("Missing system-template role (OBDal.get returns null) is silently omitted, not thrown")
    void testSystemTemplateFallbackSkippedWhenTemplateRoleMissing() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(
                Collections.singletonList(mockRole(ADMIN_ROLE_ID, "GOClient Admin", true)),
                Collections.emptyList());

        stubAllFourTemplatesResolve();
        // Purchasing's template row does not exist at all (deleted / never seeded).
        when(obDal.get(Role.class, SystemRoleTemplates.PURCHASING_ROLE_ID)).thenReturn(null);

        Map<String, List<String>> composed = new LinkedHashMap<>();
        try (MockedConstruction<UserRoleCompositionService> construction = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(CLIENT_ID)).thenReturn(composed))) {
            webhook.get(parameters, responseVars);
        }

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        // Admin + Finance + Sales + Inventory = 4; Purchasing is absent, not a 5th entry with
        // null/empty fields.
        assertEquals(4, roles.length());
        for (int i = 0; i < roles.length(); i++) {
            assertNotEquals(SystemRoleTemplates.PURCHASING_ROLE_ID, roles.getJSONObject(i).getString("id"));
        }
    }

    /**
     * When the system-template role resolves but is {@code IsActive = 'N'}, it must be treated
     * exactly like a missing row — omitted, not returned with stale/inactive data. Targets the
     * {@code !Boolean.TRUE.equals(templateRole.isActive())} half of the same early-return branch.
     */
    @Test
    @DisplayName("Inactive system-template role is silently omitted, not thrown")
    void testSystemTemplateFallbackSkippedWhenTemplateRoleInactive() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(
                Collections.singletonList(mockRole(ADMIN_ROLE_ID, "GOClient Admin", true)),
                Collections.emptyList());

        stubAllFourTemplatesResolve();
        // Inventory's template row exists but has since been deactivated.
        Role inactiveInventoryTemplate = mockTemplateRole(SystemRoleTemplates.INVENTORY_ROLE_ID, "Inventory");
        when(inactiveInventoryTemplate.isActive()).thenReturn(false);
        when(obDal.get(Role.class, SystemRoleTemplates.INVENTORY_ROLE_ID)).thenReturn(inactiveInventoryTemplate);

        Map<String, List<String>> composed = new LinkedHashMap<>();
        try (MockedConstruction<UserRoleCompositionService> construction = mockConstruction(
                UserRoleCompositionService.class, (mockService, ctx) ->
                        when(mockService.getAppliedTemplateRoleIdsForClient(CLIENT_ID)).thenReturn(composed))) {
            webhook.get(parameters, responseVars);
        }

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(4, roles.length());
        for (int i = 0; i < roles.length(); i++) {
            assertNotEquals(SystemRoleTemplates.INVENTORY_ROLE_ID, roles.getJSONObject(i).getString("id"));
        }
    }

    /**
     * The full degradation case: a tenant with only its client-admin role active, AND every one
     * of the 4 system templates missing/inactive, must still return a valid (if minimal)
     * response — just the admin card — never an exception. Also confirms
     * {@code UserRoleCompositionService} is never constructed when no fallback template ever
     * resolves far enough to need a composed user count (laziness holds under total
     * degradation, not only in the "every fixed name already has a tenant role" case covered by
     * {@link #testActiveTenantRoleIsNotOverriddenByTemplate}).
     */
    @Test
    @DisplayName("All four system templates missing/inactive degrades to just the admin role, without constructing the composition service")
    void testAllSystemTemplatesUnresolvableDegradesToAdminOnly() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(
                Collections.singletonList(mockRole(ADMIN_ROLE_ID, "GOClient Admin", true)),
                Collections.emptyList());
        // obDal.get(Role.class, <any template id>) is left unstubbed → returns null for all 4.

        try (MockedConstruction<UserRoleCompositionService> construction =
                mockConstruction(UserRoleCompositionService.class)) {
            webhook.get(parameters, responseVars);

            assertTrue(construction.constructed().isEmpty(),
                    "The composition service must never be constructed when every fallback template "
                            + "is unresolvable");
        }

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray roles = result.getJSONArray("roles");
        assertEquals(1, roles.length());
        assertEquals(ADMIN_ROLE_ID, roles.getJSONObject(0).getString("id"));
    }

    // ── ETP-4907: matrix ──────────────────────────────────────────────────

    /**
     * The matrix must include EVERY Etendo GO window — including one no role in the response can
     * reach at all (tier {@code "none"}) — grouped by its resolved top-level menu category, and
     * keyed per-role by the SAME role ids the {@code roles} array uses.
     */
    @Test
    @DisplayName("Matrix covers every GO window, grouped by category, with 'none' for unreachable windows")
    void testMatrixGroupsByCategoryAndMarksNoneForUnreachableWindow() throws Exception {
        givenSystemAdminCallerRole();

        Window reachableWindow = mockWindow("win-reachable", "Sales Order");
        Window unreachableWindow = mockWindow("win-unreachable", "Orphan Window");
        List<SFSpec> goWindowSpecs = Arrays.asList(
                mockGoWindowSpec(reachableWindow), mockGoWindowSpec(unreachableWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        List<Role> roles = standardTenantRoles();
        stubTenantRoles(roles);

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

        // Only the admin role (processed first) can reach reachableWindow; nobody can reach
        // unreachableWindow.
        List<WindowAccess> adminWindowRows = Collections.singletonList(mockWindowAccessRow(reachableWindow, true));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                adminWindowRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        // Category resolution: both windows map to the same "Sales Management" folder.
        Object[] row1 = { "win-reachable", "Sales Management" };
        Object[] row2 = { "win-unreachable", "Sales Management" };
        when(categoryQuery.getResultList()).thenReturn(Arrays.asList(row1, row2));

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray categories = result.getJSONObject("matrix").getJSONArray("categories");
        assertEquals(1, categories.length());
        JSONObject category = categories.getJSONObject(0);
        assertEquals("Sales Management", category.getString("name"));

        JSONArray windows = category.getJSONArray("windows");
        assertEquals(2, windows.length());
        // Sorted by name: "Orphan Window" before "Sales Order".
        JSONObject orphan = windows.getJSONObject(0);
        assertEquals("win-unreachable", orphan.getString("id"));
        JSONObject orphanAccess = orphan.getJSONObject("access");
        assertEquals("none", orphanAccess.getString(ADMIN_ROLE_ID));

        JSONObject salesOrder = windows.getJSONObject(1);
        assertEquals("win-reachable", salesOrder.getString("id"));
        assertEquals("full", salesOrder.getJSONObject("access").getString(ADMIN_ROLE_ID));
        assertEquals("none", salesOrder.getJSONObject("access").getString(FINANCE_ROLE_ID));
    }

    /**
     * A window with no resolvable top-level menu folder (empty category query result) must not
     * be silently dropped from the matrix — it falls back into the {@code "Other"} bucket.
     */
    @Test
    @DisplayName("A window with no resolvable category falls back to the 'Other' bucket")
    void testWindowWithNoCategoryFallsBackToOther() throws Exception {
        givenSystemAdminCallerRole();
        stubBaselineQueries(standardTenantRoles(),
                Collections.singletonList(mockWindow("win-x", "Mystery Window")));
        // categoryQuery already stubbed to return an empty list by default (see setUp()).

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray categories = result.getJSONObject("matrix").getJSONArray("categories");
        assertEquals(1, categories.length());
        assertEquals("Other", categories.getJSONObject(0).getString("name"));
        assertEquals(1, categories.getJSONObject(0).getJSONArray("windows").length());
    }

    // ── ETP-5068: windows Etendo GO serves but never shows ───────────────

    /**
     * ETP-5068 — a window listed in {@code UI_EXCLUDED_WINDOW_IDS} must not reach ANY part of
     * this response, even when the calling tenant's roles hold a live {@code AD_Window_Access}
     * grant for it. The grant is the real-world state, not a corner case: {@code
     * TemplateRoleWindowAccess} deliberately keeps granting "Conversion Rate Downloader Log" to
     * the GO template roles so administrators can still read the log in Etendo classic — which
     * is exactly why the window cannot be hidden by revoking access and has to be filtered here.
     *
     * <p>Asserting all three derived structures at once (the {@code matrix}, the role's {@code
     * windows} array and its {@code windowCount}) is deliberate: they are what "Configuración
     * &gt; Roles" and "Usuario &gt; Roles" render, and the whole point of filtering in {@code
     * resolveActiveEtendoGoWindowsById()} is that one filter covers all of them.
     */
    @Test
    @DisplayName("ETP-5068: a UI-excluded window is absent from the matrix, windows array and windowCount even when granted")
    void testUiExcludedWindowNeverReachesTheResponse() throws Exception {
        givenSystemAdminCallerRole();

        Window visibleWindow = mockWindow("win-visible", "Sales Order");
        Window excludedWindow = mockWindow("6FEBA130CDE24CC09041FFA6117ADFA9",
                "Conversion Rate Downloader Log");
        // Build the spec list BEFORE opening the when(...) — mockGoWindowSpec() stubs a mock of
        // its own, and Mockito rejects a nested stubbing inside an unfinished thenReturn(...)
        // with UnfinishedStubbingException. Same reason the sibling matrix test above hoists its
        // list into a local. Do not re-inline this.
        List<SFSpec> goWindowSpecs = Arrays.asList(
                mockGoWindowSpec(visibleWindow), mockGoWindowSpec(excludedWindow));
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(goWindowSpecs);

        List<Role> roles = standardTenantRoles();
        stubTenantRoles(roles);

        OBCriteria<UserRoles> userRolesCriteria = mockCriteria(UserRoles.class);
        when(userRolesCriteria.list()).thenReturn(Collections.emptyList());

        // The admin role (processed first) is granted BOTH windows — including the excluded one.
        List<WindowAccess> adminWindowRows = Arrays.asList(
                mockWindowAccessRow(visibleWindow, true),
                mockWindowAccessRow(excludedWindow, true));
        OBCriteria<WindowAccess> windowAccessCriteria = mockCriteria(WindowAccess.class);
        when(windowAccessCriteria.list()).thenReturn(
                adminWindowRows,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        // Both windows would land in the same top-level menu folder.
        when(categoryQuery.getResultList()).thenReturn(Arrays.asList(
                new Object[] { "win-visible", "Settings" },
                new Object[] { "6FEBA130CDE24CC09041FFA6117ADFA9", "Settings" }));

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        String rawResult = responseVars.get(RESULT);
        // Blunt but decisive: the id must not appear ANYWHERE in the payload, whichever
        // structure a future refactor might add it to.
        assertFalse(rawResult.contains("6FEBA130CDE24CC09041FFA6117ADFA9"),
                "the UI-excluded window id must not appear anywhere in the response");

        JSONObject result = new JSONObject(rawResult);
        JSONArray categories = result.getJSONObject("matrix").getJSONArray("categories");
        assertEquals(1, categories.length());
        JSONArray matrixWindows = categories.getJSONObject(0).getJSONArray("windows");
        assertEquals(1, matrixWindows.length());
        assertEquals("win-visible", matrixWindows.getJSONObject(0).getString("id"));

        JSONObject admin = result.getJSONArray("roles").getJSONObject(0);
        assertEquals(ADMIN_ROLE_ID, admin.getString("id"));
        assertEquals(1, admin.getInt("windowCount"));
        assertEquals(1, admin.getJSONArray("windows").length());
        assertEquals("win-visible", admin.getJSONArray("windows").getJSONObject(0).getString("id"));
    }

    /**
     * Guards the flip side of the filter: a window that merely SHARES the excluded window's
     * category (and, in the real data, a similar name — "Conversion Rates" is the companion
     * window users actually need) must be completely unaffected.
     */
    @Test
    @DisplayName("ETP-5068: a non-excluded window in the same category is unaffected")
    void testNonExcludedWindowInSameCategorySurvives() throws Exception {
        givenSystemAdminCallerRole();

        Window conversionRates = mockWindow("116", "Conversion Rates");
        stubBaselineQueries(standardTenantRoles(), Collections.singletonList(conversionRates));
        when(categoryQuery.getResultList()).thenReturn(
                Collections.singletonList(new Object[] { "116", "Settings" }));

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray categories = result.getJSONObject("matrix").getJSONArray("categories");
        assertEquals(1, categories.length());
        JSONArray windows = categories.getJSONObject(0).getJSONArray("windows");
        assertEquals(1, windows.length());
        assertEquals("116", windows.getJSONObject(0).getString("id"));
        assertEquals("Conversion Rates", windows.getJSONObject(0).getString("name"));
    }

    // ── ETP-5071: proxy access rows ──────────────────────────────────────

    /**
     * Regression test for a real correctness bug found in review before this shipped: SII
     * Monitor already backs its own active Etendo-GO window/spec today, so its id is already a
     * key in {@code goWindowsById} and already produces its own real {@code matrix} row from the
     * main window loop. Appending the "Fiscal Monitor" proxy row (same id — see {@code
     * SFRolesOverview#FISCAL_MONITOR_PROXY_WINDOW_ID}) unconditionally would have added a SECOND
     * row with the identical id — a real collision, since the frontend keys matrix rows by
     * category+id ({@code buildRowKey} in {@code useRolesOverviewData.js}). Exactly one row for
     * that id must survive, carrying the real window's own raw name (the frontend's own {@code
     * menu.json} is responsible for relabeling it to "Fiscal Monitor", not this backend).
     */
    @Test
    @DisplayName("ETP-5071: SII Monitor's real matrix row is not duplicated by its Fiscal Monitor proxy")
    void testFiscalMonitorProxyDoesNotDuplicateSiiMonitorsRealRow() throws Exception {
        givenSystemAdminCallerRole();

        // The real SII Monitor window/spec — the exact same id ETP-5071 uses as the "Fiscal
        // Monitor" proxy row's id.
        Window siiMonitor = mockWindow("FEF76C3E0F104F06A89AAD15A4A4A35C", "SII Monitor");
        stubBaselineQueries(standardTenantRoles(), Collections.singletonList(siiMonitor));

        invokeWebhookWithNoTemplateComposition();

        assertNull(responseVars.get(ERROR));
        JSONObject result = new JSONObject(responseVars.get(RESULT));
        JSONArray categories = result.getJSONObject("matrix").getJSONArray("categories");
        // categoryQuery defaults to empty (see setUp()) — every row here falls back to "Other".
        assertEquals(1, categories.length());
        JSONArray windows = categories.getJSONObject(0).getJSONArray("windows");

        int siiMonitorRowCount = 0;
        for (int i = 0; i < windows.length(); i++) {
            if ("FEF76C3E0F104F06A89AAD15A4A4A35C".equals(windows.getJSONObject(i).getString("id"))) {
                siiMonitorRowCount++;
                assertEquals("SII Monitor", windows.getJSONObject(i).getString("name"),
                        "the surviving row must be the real window's own row, not the proxy's fallback name");
            }
        }
        assertEquals(1, siiMonitorRowCount,
                "SII Monitor's id must appear exactly once in the matrix, never duplicated by its own proxy row");
    }

    // ── exception handling ───────────────────────────────────────────────

    @Test
    @DisplayName("Exception while building the overview sets error, not result")
    void testExceptionSetsError() {
        givenSystemAdminCallerRole();
        // Let the Etendo-GO-window resolution (SFSpec criteria) succeed first, so the thrown
        // exception below is unambiguously coming from the tenant-role resolution, not an
        // incidental NPE from an unstubbed SFSpec criteria.
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());
        OBCriteria<Role> roleCriteria = mockCriteria(Role.class);
        when(roleCriteria.list()).thenThrow(new RuntimeException("DB error"));

        webhook.get(parameters, responseVars);

        assertEquals("DB error", responseVars.get(ERROR));
        assertNull(responseVars.get(RESULT));
    }
}
