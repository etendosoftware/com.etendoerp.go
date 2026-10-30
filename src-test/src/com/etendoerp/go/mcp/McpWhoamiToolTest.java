/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * etendo-ai#53 — {@code etendo_whoami}: the user, role, client and organization of the
 * connection, with what the role and token can do, read from the context the call runs under.
 *
 * @covers com.etendoerp.go.mcp.McpWhoamiTool
 */
class McpWhoamiToolTest {

  private static final String ORG = "ORG1";
  /** 23:30 UTC on the 30th is already the 31st in Madrid: the date is the server zone's. */
  private static final Clock MADRID_LATE_EVENING =
      Clock.fixed(Instant.parse("2026-10-30T23:30:00Z"), ZoneId.of("Europe/Madrid"));

  private MockedStatic<NeoAccountingAccessPolicy> accountingMock;
  private MockedStatic<OBCurrencyUtils> currencyUtilsMock;
  private MockedStatic<OBDal> dalMock;
  private OBContext context;
  private Role role;
  private OBDal dal;

  @BeforeEach
  void setUp() {
    User user = mock(User.class);
    when(user.getId()).thenReturn("USER1");
    when(user.getName()).thenReturn("Compras");
    when(user.getUsername()).thenReturn("compras@acme.test");
    role = mock(Role.class);
    when(role.getId()).thenReturn("ROLE1");
    when(role.getName()).thenReturn("Purchasing");
    when(role.isClientAdmin()).thenReturn(false);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT1");
    when(client.getName()).thenReturn("ACME");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG);
    when(org.getName()).thenReturn("ACME Spain");
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("es_ES");

    context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);
    when(context.getRole()).thenReturn(role);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getCurrentOrganization()).thenReturn(org);
    when(context.getLanguage()).thenReturn(language);
    Warehouse warehouse = mock(Warehouse.class);
    when(warehouse.getId()).thenReturn("WH1");
    when(warehouse.getName()).thenReturn("Main warehouse");
    when(context.getWarehouse()).thenReturn(warehouse);

    accountingMock = mockStatic(NeoAccountingAccessPolicy.class);
    currencyUtilsMock = mockStatic(OBCurrencyUtils.class);
    dalMock = mockStatic(OBDal.class);
    dal = mock(OBDal.class);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalMock.close();
    currencyUtilsMock.close();
    accountingMock.close();
  }

  @Test
  @DisplayName("reports the identity, capabilities, date, currency and language of the session")
  void reportsTheSessionContext() throws Exception {
    accountingMock.when(() -> NeoAccountingAccessPolicy.canSeeAccounting(role)).thenReturn(true);
    Currency eur = mock(Currency.class);
    when(eur.getId()).thenReturn("EUR_ID");
    when(eur.getISOCode()).thenReturn("EUR");
    when(eur.getStandardPrecision()).thenReturn(2L);
    currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency(ORG)).thenReturn("EUR_ID");
    when(dal.get(Currency.class, "EUR_ID")).thenReturn(eur);

    JSONObject body = McpWhoamiTool.describe(context,
        Set.of("neo:write", "etendo:read"), MADRID_LATE_EVENING);

    JSONObject user = body.getJSONObject(McpWhoamiTool.KEY_USER);
    assertEquals("USER1", user.getString("id"));
    assertEquals("Compras", user.getString("name"));
    assertEquals("compras@acme.test", user.getString("username"));
    JSONObject roleBody = body.getJSONObject(McpWhoamiTool.KEY_ROLE);
    assertEquals("ROLE1", roleBody.getString("id"));
    assertEquals("Purchasing", roleBody.getString("name"));
    assertFalse(roleBody.getBoolean(McpWhoamiTool.KEY_IS_ADMIN));
    assertEquals("ACME", body.getJSONObject(McpWhoamiTool.KEY_CLIENT).getString("name"));
    assertEquals(ORG, body.getJSONObject(McpWhoamiTool.KEY_ORGANIZATION).getString("id"));
    assertEquals("Main warehouse",
        body.getJSONObject(McpWhoamiTool.KEY_WAREHOUSE).getString("name"));
    JSONObject capabilities = body.getJSONObject(McpWhoamiTool.KEY_CAPABILITIES);
    assertTrue(capabilities.getBoolean(McpWhoamiTool.KEY_SHOW_ACCOUNTING_FIELDS));
    // Legacy neo: scopes are reported under their current name, sorted.
    assertEquals("[\"etendo:read\",\"etendo:write\"]",
        capabilities.getJSONArray(McpWhoamiTool.KEY_SCOPES).toString());
    assertEquals("2026-10-31", body.getString(McpWhoamiTool.KEY_CURRENT_DATE));
    assertEquals("Europe/Madrid", body.getString(McpWhoamiTool.KEY_TIME_ZONE));
    JSONObject currency = body.getJSONObject(McpWhoamiTool.KEY_CURRENCY);
    assertEquals("EUR", currency.getString("isoCode"));
    assertEquals(2, currency.getInt("standardPrecision"));
    assertEquals("es_ES", body.getString(McpWhoamiTool.KEY_LANGUAGE));
  }

  @Test
  @DisplayName("a client-admin role is reported as admin, the same bypass the access checks apply")
  void clientAdminRoleIsAdmin() throws Exception {
    when(role.isClientAdmin()).thenReturn(true);

    JSONObject body = McpWhoamiTool.describe(context, Set.of("etendo:read"), MADRID_LATE_EVENING);

    assertTrue(body.getJSONObject(McpWhoamiTool.KEY_ROLE).getBoolean(McpWhoamiTool.KEY_IS_ADMIN));
  }

  @Test
  @DisplayName("a role without Show Accounting Fields is told so")
  void roleWithoutAccountingIsToldSo() throws Exception {
    accountingMock.when(() -> NeoAccountingAccessPolicy.canSeeAccounting(role)).thenReturn(false);

    JSONObject body = McpWhoamiTool.describe(context, Set.of("etendo:read"), MADRID_LATE_EVENING);

    assertFalse(body.getJSONObject(McpWhoamiTool.KEY_CAPABILITIES)
        .getBoolean(McpWhoamiTool.KEY_SHOW_ACCOUNTING_FIELDS));
  }

  @Test
  @DisplayName("no resolvable currency is null, never a placeholder the agent would take as real")
  void unresolvedCurrencyIsNull() throws Exception {
    currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency(any())).thenReturn(null);

    JSONObject body = McpWhoamiTool.describe(context, Set.of("etendo:read"), MADRID_LATE_EVENING);

    assertTrue(body.isNull(McpWhoamiTool.KEY_CURRENCY));
  }

  @Test
  @DisplayName("no default warehouse is null")
  void noWarehouseIsNull() throws Exception {
    when(context.getWarehouse()).thenReturn(null);

    JSONObject body = McpWhoamiTool.describe(context, Set.of("etendo:read"), MADRID_LATE_EVENING);

    assertTrue(body.isNull(McpWhoamiTool.KEY_WAREHOUSE));
  }

  @Test
  @DisplayName("permissions list only reachable rows, by spec name, and drop emptied categories")
  void permissionsListOnlyReachableRowsBySpec() throws Exception {
    try (MockedStatic<RoleAccessMatrix> matrix = mockStatic(RoleAccessMatrix.class);
        MockedStatic<ReportAccessCatalog> reports = mockStatic(ReportAccessCatalog.class)) {
      stubMatrices(matrix);

      JSONObject permissions = McpWhoamiTool.permissions(role, SPECS_BY_WINDOW);

      JSONArray categories = permissions.getJSONObject(McpWhoamiTool.KEY_WINDOWS)
          .getJSONArray("categories");
      assertEquals(1, categories.length(), "a category with only none rows is dropped");
      JSONArray windows = categories.getJSONObject(0).getJSONArray("windows");
      assertEquals(2, windows.length(), "the none row is dropped");
      JSONObject salesOrder = windows.getJSONObject(0);
      assertEquals("Sales Order", salesOrder.getString("name"));
      assertEquals("[\"sales-order\"]", salesOrder.getJSONArray("specs").toString());
      assertEquals("full", salesOrder.getString("access"));
      assertFalse(salesOrder.has("id"), "the AD_Window_ID is not what the agent addresses");
      // A reachable row no MCP spec serves (a GO page with no spec of its own) says so.
      assertEquals(0, windows.getJSONObject(1).getJSONArray("specs").length());
      JSONArray reportRows = permissions.getJSONObject(McpWhoamiTool.KEY_REPORTS)
          .getJSONArray("categories").getJSONObject(0).getJSONArray("reports");
      assertEquals(1, reportRows.length());
      assertEquals("aging-receivable", reportRows.getJSONObject(0).getString("spec"));
      assertEquals("read-only", reportRows.getJSONObject(0).getString("access"));
    }
  }

  @Test
  @DisplayName("an admin role reaches every row with full access, whatever its grant rows say")
  void adminPermissionsAreFullEverywhere() throws Exception {
    when(role.isClientAdmin()).thenReturn(true);
    try (MockedStatic<RoleAccessMatrix> matrix = mockStatic(RoleAccessMatrix.class);
        MockedStatic<ReportAccessCatalog> reports = mockStatic(ReportAccessCatalog.class)) {
      stubMatrices(matrix);

      JSONObject permissions = McpWhoamiTool.permissions(role, SPECS_BY_WINDOW);

      JSONArray categories = permissions.getJSONObject(McpWhoamiTool.KEY_WINDOWS)
          .getJSONArray("categories");
      assertEquals(2, categories.length());
      JSONObject purchaseOrder = categories.getJSONObject(0).getJSONArray("windows")
          .getJSONObject(2);
      assertEquals("[\"purchase-order\"]", purchaseOrder.getJSONArray("specs").toString());
      assertEquals("full", purchaseOrder.getString("access"));
      JSONArray reportRows = permissions.getJSONObject(McpWhoamiTool.KEY_REPORTS)
          .getJSONArray("categories").getJSONObject(0).getJSONArray("reports");
      assertEquals(2, reportRows.length());
      assertEquals("full", reportRows.getJSONObject(1).getString("access"));
    }
  }

  @Test
  @DisplayName("a session without a role has no permissions to list")
  void noRoleHasNoPermissions() throws Exception {
    assertEquals(JSONObject.NULL, McpWhoamiTool.permissions(null));
  }

  private static final Map<String, List<String>> SPECS_BY_WINDOW =
      Map.of("143", List.of("sales-order"), "181", List.of("purchase-order"));

  /** The matrices as RoleAccessMatrix builds them: every row, access keyed by role id. */
  private static void stubMatrices(MockedStatic<RoleAccessMatrix> matrix) throws Exception {
    JSONObject windowMatrix = new JSONObject().put("categories", new JSONArray()
        .put(new JSONObject().put("name", "Sales").put("windows", new JSONArray()
            .put(windowRow("143", "Sales Order", "full"))
            .put(windowRow("3E8FEA1EA7404D979306C9EE7FD2E7E8", "Fiscal Models", "read-only"))
            .put(windowRow("181", "Purchase Order", "none"))))
        .put(new JSONObject().put("name", "Warehouse").put("windows", new JSONArray()
            .put(windowRow("168", "Physical Inventory", "none")))));
    JSONObject reportMatrix = new JSONObject().put("categories", new JSONArray().put(
        new JSONObject().put("name", "Finance").put("reports", new JSONArray()
            .put(windowRow("aging-receivable", "Aging", "read-only"))
            .put(windowRow("balance-sheet", "Balance Sheet", "none")))));
    matrix.when(() -> RoleAccessMatrix.buildMatrix(any(), any())).thenReturn(windowMatrix);
    matrix.when(() -> RoleAccessMatrix.buildReportsMatrix(any())).thenReturn(reportMatrix);
  }

  private static JSONObject windowRow(String id, String name, String tier) throws Exception {
    return new JSONObject().put("id", id).put("name", name)
        .put("access", new JSONObject().put("ROLE1", tier));
  }

  @Test
  @DisplayName("a read-only token may call it; a token without read may not")
  void requiresTheReadScope() {
    assertDoesNotThrow(() -> McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_WHOAMI, Set.of("etendo:read")));
    assertThrows(OBSecurityException.class, () -> McpAuthorizationService.authorizeToolCall(
        McpConstants.TOOL_NEO_WHOAMI, Set.of("etendo:write")));
  }

  @Test
  @DisplayName("addresses no spec and declares only includePermissions, so others are refused")
  void addressesNoSpecAndDeclaresOnlyIncludePermissions() throws Exception {
    assertNull(ToolRegistry.resolveSpecName(McpConstants.TOOL_NEO_WHOAMI,
        new JSONObject().put("spec", "sales-order")));
    assertEquals(Optional.of(Set.of(McpWhoamiTool.PARAM_INCLUDE_PERMISSIONS)),
        ToolRegistry.declaredArgumentNames(McpConstants.TOOL_NEO_WHOAMI));
  }
}
