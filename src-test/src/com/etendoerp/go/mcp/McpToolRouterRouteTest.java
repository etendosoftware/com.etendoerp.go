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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.AmortizationPlanService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * Unit tests for {@link McpToolRouter#route} and its internal handler dispatch,
 * argument validation, error handling, and MCP response formatting.
 * <p>
 * Tests that would require DefaultJsonDataService, ModelProvider, or NeoFieldFilter
 * are excluded because those classes have static initialisers that depend on a
 * Servlet container (WeldUtils.getStaticInstanceBeanManager). The remaining tests
 * cover authorization, argument validation, spec/entity resolution errors,
 * process/report tool flows, NeoResponse conversion, and the static content
 * wrapper methods.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolRouterRouteTest {

  private static final String SPEC_NAME = "sales-order";
  private static final String ENTITY_NAME = "header";
  private static final String SPEC_ID = "spec-id-001";
  private static final String ENTITY_ID = "entity-id-001";
  private static final String TAB_ID = "tab-id-001";
  private static final String TABLE_ID = "table-id-001";
  private static final String WINDOW_ID = "window-id-001";
  private static final String PROCESS_ID = "proc-id-001";

  private static final Set<String> READ_SCOPES = Set.of("neo:read");
  private static final Set<String> WRITE_SCOPES = Set.of("neo:write");
  private static final Set<String> PROCESS_SCOPES = Set.of("neo:process");
  private static final Set<String> REPORT_SCOPES = Set.of("neo:report");

  @Mock private OBDal mockOBDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<McpAuthorizationService> authMock;
  private MockedStatic<McpToolRouterSupport> supportMock;
  private MockedStatic<NeoAccessUtils> accessMock;
  private MockedStatic<NeoDefaultsService> defaultsMock;
  private MockedStatic<NeoProcessService> processMock;
  private MockedStatic<NeoSelectorService> selectorMock;
  private MockedStatic<NeoButtonActionHelper> buttonActionMock;

  private McpToolRouter router;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    authMock = mockStatic(McpAuthorizationService.class);
    supportMock = mockStatic(McpToolRouterSupport.class);
    accessMock = mockStatic(NeoAccessUtils.class);
    defaultsMock = mockStatic(NeoDefaultsService.class);
    processMock = mockStatic(NeoProcessService.class);
    selectorMock = mockStatic(NeoSelectorService.class);
    buttonActionMock = mockStatic(NeoButtonActionHelper.class);

    obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    obContextMock.when(() -> OBContext.setAdminMode()).thenAnswer(inv -> null);
    obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

    // The router now delegates argument validation to the (statically mocked) support
    // class. Run the REAL validation logic so the "Missing arguments" / "Missing required
    // argument: <key>" assertions stay meaningful. validateArgs is a static, side-effect-free
    // method (throws IllegalArgumentException) so thenCallRealMethod is safe here.
    supportMock.when(() -> McpToolRouterSupport.validateArgs(any(), any(String[].class)))
        .thenCallRealMethod();

    router = new McpToolRouter();
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) obDalMock.close();
    if (obContextMock != null) obContextMock.close();
    if (authMock != null) authMock.close();
    if (supportMock != null) supportMock.close();
    if (accessMock != null) accessMock.close();
    if (defaultsMock != null) defaultsMock.close();
    if (processMock != null) processMock.close();
    if (selectorMock != null) selectorMock.close();
    if (buttonActionMock != null) buttonActionMock.close();
  }

  // ── Shared mock setup helpers ─────────────────────────────────────────

  private SFSpec mockSpec() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn(SPEC_ID);
    when(spec.getName()).thenReturn(SPEC_NAME);
    when(spec.getSpecType()).thenReturn("W");
    return spec;
  }

  private SFEntity mockEntity() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn(ENTITY_ID);
    when(entity.getName()).thenReturn(ENTITY_NAME);
    return entity;
  }

  private Tab mockTab() {
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn(TAB_ID);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(TABLE_ID);
    when(table.getName()).thenReturn("C_Order");
    when(table.getDBTableName()).thenReturn("C_ORDER");
    when(table.getADColumnList()).thenReturn(new ArrayList<>());
    when(tab.getTable()).thenReturn(table);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(WINDOW_ID);
    when(tab.getWindow()).thenReturn(window);
    when(tab.getHqlwhereclause()).thenReturn(null);
    return tab;
  }

  /**
   * Prime the (statically mocked) support class so spec resolution returns {@code spec}.
   * The router delegates BOTH {@code authorizeSpecAccess} and every handler's spec lookup
   * to {@link McpToolRouterSupport#findActiveSpecByName}, so stubbing that one method covers
   * the whole route. {@code hasSpecAccess} is also stubbed to grant access by default —
   * both the 2-arg (GET-tier, used by neo_discover) and the 3-arg, method-aware overload
   * (ETP-4510: used by {@code authorizeSpecAccess} for every route() call, including reads)
   * so a {@code mockStatic()} on {@link McpToolRouterSupport} doesn't silently deny every
   * mutating tool call by falling through to the unstubbed-static default of {@code false}.
   */
  private void setupSpecLookup(SFSpec spec) {
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
        .thenReturn(spec);
    supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString()))
        .thenReturn(true);
    supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
        .thenReturn(true);
  }

  /**
   * Prime the (statically mocked) support class so entity resolution returns {@code entity}
   * and the entity's AD_Tab returns {@code tab}. Mirrors what the old OBDal-based criteria
   * setup produced before the router delegated entity lookup to the support class.
   */
  private void setupEntityLookup(SFEntity entity, Tab tab) {
    // The 8 entity-CRUD handlers resolve the entity via resolveIncludedEntityOrExplain
    // (ETP-4257); neo_action still uses findIncludedEntity directly. Stub BOTH so every
    // success path keeps returning the entity regardless of the entry point.
    supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
        any(SFSpec.class), anyString())).thenReturn(entity);
    supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
        .thenReturn(entity);
    when(entity.getADTab()).thenReturn(tab);
  }

  private static String contentText(JSONObject result) throws Exception {
    return result.getJSONArray("content").getJSONObject(0).getString("text");
  }

  private JSONObject buildCrudArgs() throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC_NAME);
    args.put("entity", ENTITY_NAME);
    return args;
  }

  // ── Authorization ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — authorization")
  class AuthorizationTests {

    @Test
    @DisplayName("spec access denied returns error content")
    void specAccessDeniedReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString()))
          .thenReturn(false);
      // route() authorizes neo_list (a GET-tier tool) through the 3-arg overload —
      // override the setupSpecLookup() default (true) back to denied for this method.
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
          .thenReturn(false);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }
  }

  // ── ETP-4510 write-tier authorization (code-review BUG-2) ─────────────

  /**
   * Integration-level regression coverage for the ETP-4510 code-review BUG-2 gap: the
   * only pre-existing {@code route()} authorization test used {@code neo_list} (a
   * GET/read-tier tool). Nothing exercised {@code route()} denying a WRITE tool
   * (neo_create/neo_update/neo_delete) for a read-only-access role at the integration
   * level — only the unit-level {@code McpToolRouterSupportTest#hasSpecAccess} tests did.
   * <p>
   * These tests drive the real {@code McpToolRouter#route} entry point end to end
   * (argument building, {@code resolveAccessMethod}, {@code authorizeSpecAccess}) with
   * only {@link McpToolRouterSupport#hasSpecAccess(SFSpec, String, String)} stubbed to
   * mimic a role whose {@code AD_Window_Access} row is read-only: GET tier is granted,
   * every write tier is denied.
   */
  @Nested
  @DisplayName("route — write-tier authorization (ETP-4510)")
  class WriteTierAuthorizationTests {

    /** Read-only role: GET passes, every write method is denied. */
    private void setupReadOnlyAccess(SFSpec spec) {
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
          .thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "GET"))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST"))
          .thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "PUT"))
          .thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "DELETE"))
          .thenReturn(false);
    }

    @Test
    @DisplayName("neo_create is denied for a read-only-access role")
    void createDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject result = router.route("neo_create", buildCrudArgs(), WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    @Test
    @DisplayName("neo_update is denied for a read-only-access role")
    void updateDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      args.put("fields", new JSONObject());

      JSONObject result = router.route("neo_update", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    @Test
    @DisplayName("neo_delete is denied for a read-only-access role")
    void deleteDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");

      JSONObject result = router.route("neo_delete", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    /**
     * Companion to the three denial tests above: the exact same read-only-access role
     * must still be able to reach the read handlers (neo_list, neo_get) — proving the
     * ETP-4510 fix only tightens writes and does not regress reads.
     * <p>
     * Deliberately omits a required argument (entity/id) rather than driving the handler
     * all the way through, mirroring {@code ListTests#listMissingEntityReturnsError} /
     * {@code GetTests#getMissingIdReturnsError} — {@code DefaultJsonDataService} has a
     * static initializer that needs a live servlet container (see this class's javadoc),
     * so a full success run isn't reachable here. What matters for this regression test
     * is that the failure is the argument-validation error, never "Access denied" —
     * proving authorization was passed before validation ran.
     */
    @Test
    @DisplayName("neo_list still passes authorization for the same read-only-access role")
    void listAllowedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      // "entity" intentionally omitted — proves we got past authorization into validateArgs.

      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertFalse(text.contains("Access denied"),
          "neo_list must not be blocked by access control for a read-only role, got: " + text);
      assertTrue(text.contains("entity"));
    }

    @Test
    @DisplayName("neo_get still passes authorization for the same read-only-access role")
    void getAllowedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = buildCrudArgs();
      // "id" intentionally omitted — proves we got past authorization into validateArgs.

      JSONObject result = router.route("neo_get", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertFalse(text.contains("Access denied"),
          "neo_get must not be blocked by access control for a read-only role, got: " + text);
      assertTrue(text.contains("id"));
    }
  }

  // ── neo_batch access control (ETP-4510 code-review BUG-2b) ────────────

  /**
   * {@code handleBatch}'s per-operation {@code authorizeSpecAccess(specName, "POST")} loop
   * (added by the ETP-4510 BLOCKER fix) had zero test coverage anywhere. Every
   * {@code BatchService#processOperation} op is a create (there is no update/delete op
   * type), so batch authorization is always write-tier ("POST").
   */
  @Nested
  @DisplayName("handleBatch — access control (ETP-4510)")
  class BatchAccessTests {

    private JSONObject buildBatchArgs() throws Exception {
      JSONObject op = new JSONObject();
      op.put("id", "op1");
      op.put("spec", SPEC_NAME);
      op.put("entity", ENTITY_NAME);
      op.put("body", new JSONObject());

      JSONArray operations = new JSONArray();
      operations.put(op);

      JSONObject args = new JSONObject();
      args.put("operations", operations);
      return args;
    }

    @Test
    @DisplayName("a read-only-access role's batch create operation is denied")
    void batchDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME))
          .thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST"))
          .thenReturn(false);

      try (MockedStatic<com.etendoerp.go.schemaforge.BatchService> batchMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchService.class)) {
        com.etendoerp.go.schemaforge.BatchService mockBatch =
            mock(com.etendoerp.go.schemaforge.BatchService.class);
        batchMock.when(com.etendoerp.go.schemaforge.BatchService::forBatchOnly)
            .thenReturn(mockBatch);

        JSONObject result = router.handleBatch(buildBatchArgs());

        assertTrue(result.getBoolean("isError"));
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue(text.contains("Access denied"));
        // The denial must short-circuit before any DAL work — BatchService must never
        // be reached once authorizeSpecAccess throws.
        org.mockito.Mockito.verify(mockBatch, org.mockito.Mockito.never())
            .executeBatch(any());
      }
    }

    @Test
    @DisplayName("a full-access role's batch create operation reaches BatchService")
    void batchAllowedForFullAccessRole() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME))
          .thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST"))
          .thenReturn(true);

      try (MockedStatic<com.etendoerp.go.schemaforge.BatchService> batchMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchService.class)) {
        com.etendoerp.go.schemaforge.BatchService mockBatch =
            mock(com.etendoerp.go.schemaforge.BatchService.class);
        batchMock.when(com.etendoerp.go.schemaforge.BatchService::forBatchOnly)
            .thenReturn(mockBatch);
        JSONObject batchResult = new JSONObject();
        batchResult.put("committed", true);
        when(mockBatch.executeBatch(any())).thenReturn(batchResult);

        JSONObject result = router.handleBatch(buildBatchArgs());

        assertFalse(result.has("isError"));
        org.mockito.Mockito.verify(mockBatch).executeBatch(any());
      }
    }
  }

  // ── neo_discover ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_discover")
  class DiscoverTests {

    @Test
    @DisplayName("neo_discover returns specs array")
    @SuppressWarnings("unchecked")
    void discoverReturnsSpecs() throws Exception {
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.emptyList());

      JSONObject result = router.route("neo_discover", null, READ_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertTrue(body.has("specs"));
      assertEquals(0, body.getInt("count"));
    }

    @Test
    @DisplayName("neo_discover includes accessible specs")
    @SuppressWarnings("unchecked")
    void discoverIncludesAccessibleSpecs() throws Exception {
      SFSpec spec = mockSpec();
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(List.of(spec));
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(eq(spec), eq("W")))
          .thenReturn(true);

      supportMock.when(() -> McpToolRouterSupport.buildEntitySummaryArray(SPEC_ID))
          .thenReturn(new JSONArray());

      JSONObject discoverSpec = new JSONObject();
      discoverSpec.put("name", SPEC_NAME);
      discoverSpec.put("type", "window");
      supportMock.when(
              () -> McpToolRouterSupport.buildDiscoverSpec(eq(spec), eq("W"), any(), any()))
          .thenReturn(discoverSpec);

      JSONObject result = router.route("neo_discover", null, READ_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals(1, body.getInt("count"));
    }
  }

  // ── neo_list ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_list")
  class ListTests {

    @Test
    @DisplayName("neo_list missing entity argument returns error")
    void listMissingEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);

      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("entity"));
    }

    @Test
    @DisplayName("neo_list with null arguments returns error")
    void listNullArgsReturnsError() throws Exception {
      JSONObject result = router.route("neo_list", null, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Missing arguments"));
    }

    /**
     * ETP-4257: neo_list on a CALLABLE report (R) spec no longer surfaces the opaque
     * "Entity not found: header". End-to-end (route → handleList → shared guard) the error
     * names the report type and points the agent at the concrete etendo_generate_ tool.
     */
    @Test
    @DisplayName("neo_list on a callable report (R) spec explains the generate tool")
    void listOnReportSpecExplainsGenerateTool() throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn(SPEC_ID);
      when(spec.getName()).thenReturn("invoice-report");
      when(spec.getSpecType()).thenReturn("R");
      setupSpecLookup(spec);

      // Run the real guard so the handler→helper path is covered; only report callability
      // is stubbed (its own DB query is out of scope here).
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenCallRealMethod();

      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);

        JSONObject result = router.route("neo_list", buildCrudArgs(), READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        String text = contentText(result);
        assertTrue(text.contains("report type (R)"),
            "error must state the spec is a report type: " + text);
        assertTrue(text.contains("etendo_generate_"),
            "error must point at the generate tool: " + text);
      }
    }
  }

  // ── neo_get ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_get")
  class GetTests {

    @Test
    @DisplayName("neo_get missing id argument returns error")
    void getMissingIdReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("neo_get", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("id"));
    }
  }

  // ── neo_create ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_create")
  class CreateTests {

    @Test
    @DisplayName("neo_create missing fields argument returns error")
    void createMissingFieldsReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("neo_create", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("fields"));
    }
  }

  // ── neo_update ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_update")
  class UpdateTests {

    @Test
    @DisplayName("neo_update missing id returns error")
    void updateMissingIdReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();
      args.put("fields", new JSONObject());

      JSONObject result = router.route("neo_update", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("id"));
    }
  }

  // ── neo_delete ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_delete")
  class DeleteTests {

    @Test
    @DisplayName("neo_delete missing id returns error")
    void deleteMissingIdReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("neo_delete", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
    }
  }

  // ── neo_selectors ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_selectors")
  class SelectorsTests {

    @Test
    @DisplayName("neo_selectors missing column returns error")
    void selectorsMissingColumnReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("neo_selectors", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("column"));
    }
  }

  // ── neo_defaults ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_defaults")
  class DefaultsTests {

    @Test
    @DisplayName("neo_defaults returns default values")
    void defaultsReturnsValues() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      OBContext obCtx = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      JSONObject defaultsBody = new JSONObject();
      defaultsBody.put("documentNo", "<auto>");
      NeoResponse neoResp = NeoResponse.ok(defaultsBody);
      defaultsMock.when(() -> NeoDefaultsService.resolveDefaults(any(), isNull()))
          .thenReturn(neoResp);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_defaults", args, READ_SCOPES);

      assertFalse(result.has("isError"));
    }

    @Test
    @DisplayName("neo_defaults missing entity returns error")
    void defaultsMissingEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);

      JSONObject result = router.route("neo_defaults", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("entity"));
    }
  }

  // ── Process tools ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — process tools")
  class ProcessTests {

    @Test
    @DisplayName("process tool executes process and returns result")
    void processToolExecutes() throws Exception {
      SFSpec spec = mockSpec();
      when(spec.getSpecType()).thenReturn("P");
      setupSpecLookup(spec);

      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(true);

      JSONObject processBody = new JSONObject();
      processBody.put("result", "completed");
      NeoResponse neoResp = NeoResponse.ok(processBody);
      processMock.when(() -> NeoProcessService.executeProcess(eq(adProcess), any()))
          .thenReturn(neoResp);

      JSONObject args = new JSONObject();
      args.put("parameters", new JSONObject());

      JSONObject result = router.route("complete_order", args, PROCESS_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("completed"));
    }

    @Test
    @DisplayName("process tool with no linked AD_Process returns error")
    void processToolNoProcessReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      when(spec.getProcess()).thenReturn(null);
      setupSpecLookup(spec);

      JSONObject result = router.route("complete_order", null, PROCESS_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("no linked AD_Process"));
    }

    @Test
    @DisplayName("process tool with denied RBAC returns error")
    void processToolDeniedRbacReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      setupSpecLookup(spec);

      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(false);

      JSONObject result = router.route("complete_order", null, PROCESS_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    @Test
    @DisplayName("process tool with error NeoResponse returns error content")
    void processToolErrorResponse() throws Exception {
      SFSpec spec = mockSpec();
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      setupSpecLookup(spec);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(true);

      NeoResponse errorResp = NeoResponse.error(500, "Process execution failed");
      processMock.when(() -> NeoProcessService.executeProcess(eq(adProcess), any()))
          .thenReturn(errorResp);

      JSONObject result = router.route("complete_order", null, PROCESS_SCOPES);

      assertTrue(result.getBoolean("isError"));
    }
  }

  // ── Report tools ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — report tools (ETP-4255: NEO-native handlers only)")
  class ReportTests {

    private SFSpec mockReportSpec() {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn(SPEC_ID);
      when(spec.getName()).thenReturn("invoice-report");
      when(spec.getSpecType()).thenReturn("R");
      return spec;
    }

    /**
     * ETP-4255: a report spec with no NEO-native handler is non-callable. The report tool
     * returns the canonical {@code not_configured_for_report_generation} body as plain TEXT
     * content (NOT an error), and never executes Jasper/AD_Process reports.
     */
    @Test
    @DisplayName("report tool with no handler returns non-error not_configured text")
    void reportToolNoHandlerReturnsNotConfigured() throws Exception {
      SFSpec spec = mockReportSpec();
      setupSpecLookup(spec);
      // No included entity declares a Java_Qualifier → no NEO-native handler.
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
          .thenReturn(Collections.emptyList());

      JSONObject result = router.route("generate_invoice_report", null, REPORT_SCOPES);

      // Not an error path.
      assertFalse(result.has("isError") && result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("invoice-report", body.getString("name"));
      assertEquals("report", body.getString("type"));
      assertFalse(body.getBoolean("callable"));
      assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, body.getString("status"));
      assertTrue(body.getString("message").contains("not configured"));
    }

    /**
     * A report spec whose included entity declares a {@code Java_Qualifier} is callable: the
     * matching NeoHandler runs and its NeoResponse JSON is returned (no Jasper involved).
     */
    @Test
    @DisplayName("report tool with NEO handler invokes handle() and returns its JSON")
    void reportToolWithHandlerReturnsHandlerJson() throws Exception {
      SFSpec spec = mockReportSpec();
      setupSpecLookup(spec);

      SFEntity reportEntity = mock(SFEntity.class);
      when(reportEntity.getName()).thenReturn("aging");
      when(reportEntity.getJavaQualifier()).thenReturn("agingReportHandler");
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
          .thenReturn(List.of(reportEntity));

      JSONObject reportData = new JSONObject();
      reportData.put("rows", 3);
      NeoResponse handlerResponse = NeoResponse.ok(reportData);

      NeoHandler handler = mock(NeoHandler.class);
      when(handler.handle(any(NeoContext.class))).thenReturn(handlerResponse);

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class)) {
        hookMock.when(() -> McpHookExecutor.resolveEntityHandler(reportEntity))
            .thenReturn(handler);
        hookMock.when(() -> McpHookExecutor.neoResponseToMcpResult(any()))
            .thenCallRealMethod();

        JSONObject result = router.route("generate_invoice_report", null, REPORT_SCOPES);

        assertFalse(result.has("isError") && result.getBoolean("isError"));
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue(text.contains("\"rows\""));
      }
    }
  }

  // ── Spec/entity resolution ────────────────────────────────────────────

  @Nested
  @DisplayName("route — spec/entity resolution errors")
  class ResolutionErrorTests {

    @Test
    @DisplayName("unknown spec returns error")
    void unknownSpecReturnsError() throws Exception {
      // The router delegates spec resolution to the support class, which throws
      // OBException("Spec not found: <name>") when no active spec matches.
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
          .thenThrow(new OBException("Spec not found: " + SPEC_NAME));

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Spec not found"));
    }

    @Test
    @DisplayName("unknown entity returns error")
    void unknownEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec(); // type "W"
      setupSpecLookup(spec);

      // AC-3: for a type-W spec the shared guard delegates to findIncludedEntity, preserving
      // the "Entity not found: <name>" message for a genuinely wrong entity name. Run the real
      // helper and let the delegate throw, exercising the W branch end-to-end.
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
          .thenThrow(new OBException("Entity not found: " + ENTITY_NAME));

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = contentText(result);
      assertTrue(text.contains("Entity not found"));
    }

    @Test
    @DisplayName("entity without AD_Tab returns error")
    void entityWithoutTabReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);

      // Entity resolves (via the shared guard) but has no linked AD_Tab. The router's own
      // getAdTabOrThrow (still private in McpToolRouter) raises "No AD_Tab linked to entity".
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenReturn(entity);
      when(entity.getADTab()).thenReturn(null);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = contentText(result);
      assertTrue(text.contains("No AD_Tab"));
    }
  }

  // ── neoResponseToMcpResult ────────────────────────────────────────────

  @Nested
  @DisplayName("route — NeoResponse conversion")
  class NeoResponseConversionTests {

    @Test
    @DisplayName("NeoResponse with status >= 400 produces error content")
    void errorNeoResponseProducesErrorContent() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      OBContext obCtx = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      JSONObject errorBody = new JSONObject();
      errorBody.put("error", "Not found");
      NeoResponse errorResp = NeoResponse.error(404, errorBody);
      defaultsMock.when(() -> NeoDefaultsService.resolveDefaults(any(), isNull()))
          .thenReturn(errorResp);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_defaults", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
    }

    @Test
    @DisplayName("NeoResponse with null body produces empty JSON")
    void nullBodyNeoResponse() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      OBContext obCtx = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obCtx);

      NeoResponse emptyResp = new NeoResponse(200, null);
      defaultsMock.when(() -> NeoDefaultsService.resolveDefaults(any(), isNull()))
          .thenReturn(emptyResp);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_defaults", args, READ_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertEquals("{}", text);
    }
  }

  // ── General error handling ────────────────────────────────────────────

  @Nested
  @DisplayName("route — general error handling")
  class GeneralErrorTests {

    @Test
    @DisplayName("unexpected exception is caught and returned as error content")
    void unexpectedExceptionReturnedAsError() throws Exception {
      // An unexpected DAL failure now surfaces through the delegated spec lookup
      // (authorizeSpecAccess → findActiveSpecByName). The router's catch-all must
      // wrap it as error content.
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
          .thenThrow(new RuntimeException("Database connection lost"));

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Database connection lost"));
    }

    @Test
    @DisplayName("exception inside adminMode still returns error content (finally restores mode)")
    void exceptionInsideAdminModeReturnsError() throws Exception {
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
          .thenThrow(new RuntimeException("fail"));

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("fail"));
    }
  }

  // ── wrapAsTextContent / wrapAsErrorContent (static) ───────────────────

  @Nested
  @DisplayName("MCP content wrappers")
  class ContentWrapperTests {

    @Test
    @DisplayName("wrapAsTextContent produces valid structure without isError")
    void textContentStructure() throws Exception {
      JSONObject result = McpToolRouter.wrapAsTextContent("hello");

      assertFalse(result.has("isError"));
      JSONArray content = result.getJSONArray("content");
      assertEquals(1, content.length());
      assertEquals("text", content.getJSONObject(0).getString("type"));
      assertEquals("hello", content.getJSONObject(0).getString("text"));
    }

    @Test
    @DisplayName("wrapAsErrorContent sets isError=true")
    void errorContentSetsFlag() throws Exception {
      JSONObject result = McpToolRouter.wrapAsErrorContent("fail");

      assertTrue(result.getBoolean("isError"));
      JSONArray content = result.getJSONArray("content");
      assertEquals("fail", content.getJSONObject(0).getString("text"));
    }

    @Test
    @DisplayName("wrapAsTextContent preserves JSON strings")
    void textContentPreservesJson() throws Exception {
      String json = "{\"key\":\"value\"}";
      JSONObject result = McpToolRouter.wrapAsTextContent(json);

      assertEquals(json, result.getJSONArray("content").getJSONObject(0).getString("text"));
    }
  }

  // ── docs ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — docs")
  class DocsTests {

    @Test
    @DisplayName("docs with missing topic returns error content")
    void docsMissingTopicReturnsError() throws Exception {
      JSONObject result = router.route("docs", new JSONObject(), READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("topic"));
    }

    @Test
    @DisplayName("docs with blank topic returns error content")
    void docsBlankTopicReturnsError() throws Exception {
      JSONObject args = new JSONObject();
      args.put("topic", "   ");

      JSONObject result = router.route("docs", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("topic"));
    }

    @Test
    @DisplayName("docs with null arguments returns error content")
    void docsNullArgsReturnsError() throws Exception {
      JSONObject result = router.route("docs", null, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("topic"));
    }

    @Test
    @DisplayName("handleDocs success path returns the docs body via injected client")
    void docsSuccessReturnsBody() throws Exception {
      Context7DocsClient mockClient = mock(Context7DocsClient.class);
      when(mockClient.fetchDocs(anyString(), org.mockito.ArgumentMatchers.anyInt(),
          anyString(), org.mockito.ArgumentMatchers.any()))
          .thenReturn("# Finance docs\nbody text");

      // Stub the token-resolution seam so the test needs no DB or static mocking
      McpToolRouter docsRouter = new McpToolRouter() {
        @Override
        String resolveContext7Token() {
          return null;
        }
      };

      JSONObject args = new JSONObject();
      args.put("topic", "finance");
      args.put("tokens", 1000);
      args.put("type", "txt");

      JSONObject result = docsRouter.handleDocs(args, mockClient);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertEquals("# Finance docs\nbody text", text);
    }

    @Test
    @DisplayName("handleDocs with blank body returns friendly no-results message")
    void docsBlankBodyReturnsFriendlyMessage() throws Exception {
      Context7DocsClient mockClient = mock(Context7DocsClient.class);
      when(mockClient.fetchDocs(anyString(), org.mockito.ArgumentMatchers.anyInt(),
          anyString(), org.mockito.ArgumentMatchers.any()))
          .thenReturn("");

      // Stub the token-resolution seam so the test needs no DB or static mocking
      McpToolRouter docsRouter = new McpToolRouter() {
        @Override
        String resolveContext7Token() {
          return null;
        }
      };

      JSONObject args = new JSONObject();
      args.put("topic", "nonexistent");

      JSONObject result = docsRouter.handleDocs(args, mockClient);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("No documentation found for topic"));
    }

    @Test
    @DisplayName("handleDocs threads the resolved Context7 token to the client")
    void docsPassesResolvedTokenToClient() throws Exception {
      Context7DocsClient mockClient = mock(Context7DocsClient.class);
      when(mockClient.fetchDocs(anyString(), org.mockito.ArgumentMatchers.anyInt(),
          anyString(), org.mockito.ArgumentMatchers.any()))
          .thenReturn("docs body");

      // Stub the token-resolution seam so the test needs no DB or static mocking
      McpToolRouter tokenRouter = new McpToolRouter() {
        @Override
        String resolveContext7Token() {
          return "tok-123";
        }
      };

      JSONObject args = new JSONObject();
      args.put("topic", "finance");

      JSONObject result = tokenRouter.handleDocs(args, mockClient);

      assertFalse(result.has("isError"));
      org.mockito.ArgumentCaptor<String> tokenCaptor =
          org.mockito.ArgumentCaptor.forClass(String.class);
      org.mockito.Mockito.verify(mockClient).fetchDocs(anyString(),
          org.mockito.ArgumentMatchers.anyInt(), anyString(), tokenCaptor.capture());
      assertEquals("tok-123", tokenCaptor.getValue());
    }

    @Test
    @DisplayName("handleDocs passes a null token to the client when none is configured")
    void docsPassesNullTokenWhenUnset() throws Exception {
      Context7DocsClient mockClient = mock(Context7DocsClient.class);
      when(mockClient.fetchDocs(anyString(), org.mockito.ArgumentMatchers.anyInt(),
          anyString(), org.mockito.ArgumentMatchers.any()))
          .thenReturn("docs body");

      McpToolRouter tokenRouter = new McpToolRouter() {
        @Override
        String resolveContext7Token() {
          return null;
        }
      };

      JSONObject args = new JSONObject();
      args.put("topic", "finance");

      JSONObject result = tokenRouter.handleDocs(args, mockClient);

      assertFalse(result.has("isError"));
      org.mockito.ArgumentCaptor<String> tokenCaptor =
          org.mockito.ArgumentCaptor.forClass(String.class);
      org.mockito.Mockito.verify(mockClient).fetchDocs(anyString(),
          org.mockito.ArgumentMatchers.anyInt(), anyString(), tokenCaptor.capture());
      org.junit.jupiter.api.Assertions.assertNull(tokenCaptor.getValue());
    }
  }

  // ── neo_action ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_action")
  class ActionTests {

    private static final Set<String> ACTION_SCOPES = Set.of("neo:write");
    private static final String RECORD_ID = "record-001";
    private static final String ACTION_NAME = "Processed";

    @BeforeEach
    void setupActionSupport() {
      supportMock.when(() -> McpToolRouterSupport.mapNeoResponseToActionResult(any()))
          .thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.resolveStatusFromErrorBody(any()))
          .thenCallRealMethod();
    }

    private JSONObject buildActionArgs() throws Exception {
      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      args.put("entity", ENTITY_NAME);
      args.put("id", RECORD_ID);
      args.put("action", ACTION_NAME);
      return args;
    }

    @Test
    @DisplayName("neo_action routes and returns processResult:success")
    void actionSuccessReturnsProcessResult() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject responseBody = new JSONObject();
      responseBody.put("status", "success");
      responseBody.put("message", "Process completed successfully");
      NeoResponse neoResp = NeoResponse.ok(responseBody);

      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(neoResp);

      JSONObject result = router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("success", body.getString("processResult"));
      assertEquals("Process completed successfully", body.getString("processMessage"));
    }

    @Test
    @DisplayName("neo_action surfaces processResult:error for error NeoResponse")
    void actionErrorSurfacesProcessResultError() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject errorBody = new JSONObject();
      errorBody.put("status", "error");
      errorBody.put("message", "usableLifeMonths must be greater than 0");
      NeoResponse errorResp = new NeoResponse(400, errorBody);

      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(errorResp);

      JSONObject result = router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("error", body.getString("processResult"));
      assertTrue(body.getString("processMessage").contains("usableLifeMonths"));
    }

    @Test
    @DisplayName("neo_action with missing action argument returns error")
    void actionMissingActionArgReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      args.put("entity", ENTITY_NAME);
      args.put("id", RECORD_ID);
      // "action" intentionally omitted

      JSONObject result = router.route("neo_action", args, ACTION_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("action"));
    }

    @Test
    @DisplayName("neo_action with null arguments returns error")
    void actionNullArgsReturnsError() throws Exception {
      JSONObject result = router.route("neo_action", null, ACTION_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Missing arguments"));
    }

    @Test
    @DisplayName("neo_action with warning NeoResponse returns success content with processResult:warning")
    void actionWarningReturnsWarningResult() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject warningBody = new JSONObject();
      warningBody.put("status", "warning");
      warningBody.put("message", "Process completed with warnings");
      NeoResponse warningResp = NeoResponse.ok(warningBody);

      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(warningResp);

      JSONObject result = router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("warning", body.getString("processResult"));
    }

    @Test
    @DisplayName("neo_action runs the entity NeoHandler hooks around the button action")
    void actionRunsEntityHandlerHooks() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject responseBody = new JSONObject();
      responseBody.put("status", "success");
      responseBody.put("message", "Process completed successfully");
      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(NeoResponse.ok(responseBody));

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class)) {
        router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

        // The REST action path wraps the button action in the entity's NeoHandler
        // (NeoSubEndpointDispatcher.handleHookedSubEndpoint with NeoEndpointType.ACTION),
        // exactly as neo_create/neo_update/neo_delete already do on the MCP side. Without
        // the same wrapping here, completing a document over MCP silently skips handler
        // logic the UI executes — e.g. AbstractOrderHeaderHandler's pre-CO total-discount
        // line, or GlJournalHeaderHandler's interception of the contextless classic
        // dispatch that would otherwise NPE inside FIN_AddPaymentFromJournal (ETP-4285).
        hookMock.verify(() -> McpHookExecutor.resolveEntityHandler(entity));
        hookMock.verify(() -> McpHookExecutor.runPreHook(any(), any()));
        hookMock.verify(() -> McpHookExecutor.runPostHook(any(), any(), any()));
      }
    }

    @Test
    @DisplayName("neo_action builds an ACTION hook context carrying the action name")
    void actionBuildsActionHookContextWithActionName() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject responseBody = new JSONObject();
      responseBody.put("status", "success");
      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(NeoResponse.ok(responseBody));

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class)) {
        router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

        hookMock.verify(() -> McpHookExecutor.buildActionHookContext(
            eq(SPEC_NAME), eq(ENTITY_NAME), eq(RECORD_ID), eq(ACTION_NAME),
            any(), eq(tab), eq(entity)));
      }
    }

    @Test
    @DisplayName("neo_action pre-hook result short-circuits without firing the process")
    void actionPreHookShortCircuitsWithoutFiringTheProcess() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject hookResult = McpToolRouter.wrapAsErrorContent("Order has no lines");

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class)) {
        hookMock.when(() -> McpHookExecutor.runPreHook(any(), any())).thenReturn(hookResult);

        JSONObject result = router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

        assertTrue(result.getBoolean("isError"));
        assertTrue(contentText(result).contains("Order has no lines"));
        buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
            any(), any(), any(), any()), never());
      }
    }

    @Test
    @DisplayName("neo_action forwards the caller's parameters object to the process")
    void actionForwardsCallerParametersToProcess() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject responseBody = new JSONObject();
      responseBody.put("status", "success");
      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(NeoResponse.ok(responseBody));

      // The documented way to complete a document: the chosen action value travels under
      // the key neo_schema advertises as 'actionParameter' (ETP-4285).
      JSONObject args = buildActionArgs();
      JSONObject parameters = new JSONObject();
      parameters.put("docAction", "CO");
      args.put("parameters", parameters);

      ArgumentCaptor<JSONObject> paramsCaptor = ArgumentCaptor.forClass(JSONObject.class);

      router.route("neo_action", args, ACTION_SCOPES);

      buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
          eq(entity), eq(RECORD_ID), eq(ACTION_NAME), paramsCaptor.capture()));
      assertEquals("CO", paramsCaptor.getValue().getString("docAction"));
    }

    @Test
    @DisplayName("neo_action post-hook result replaces the default action result")
    void actionPostHookReplacesResult() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject responseBody = new JSONObject();
      responseBody.put("status", "success");
      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(NeoResponse.ok(responseBody));

      JSONObject replaced = McpToolRouter.wrapAsTextContent("{\"processResult\":\"warning\"}");

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class)) {
        hookMock.when(() -> McpHookExecutor.runPreHook(any(), any())).thenReturn(null);
        hookMock.when(() -> McpHookExecutor.runPostHook(any(), any(), any()))
            .thenReturn(replaced);

        JSONObject result = router.route("neo_action", buildActionArgs(), ACTION_SCOPES);

        assertTrue(contentText(result).contains("warning"));
      }
    }
  }

  // ── neo_generate_amortization_plan (ETP-4232) ─────────────────────────────

  @Nested
  @DisplayName("route — neo_generate_amortization_plan (ETP-4232)")
  class GenerateAmortizationPlanTests {

    private static final Set<String> PROCESS_SCOPES_LOCAL = Set.of("neo:process");
    private MockedStatic<AmortizationPlanService> amortMock;

    @BeforeEach
    void setUpAmort() {
      amortMock = mockStatic(AmortizationPlanService.class);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDownAmort() {
      if (amortMock != null) {
        amortMock.close();
      }
    }

    @Test
    @DisplayName("routes neo_generate_amortization_plan to handleGenerateAmortizationPlan and returns success")
    void routesAmortizationToolToHandler() throws Exception {
      JSONObject planBody = new JSONObject();
      planBody.put("success", true);
      planBody.put("amortizationId", "AMORT-001");
      planBody.put("periodsGenerated", 12);
      NeoResponse successResp = NeoResponse.ok(planBody);

      amortMock.when(() -> AmortizationPlanService.generatePlan(eq("ASSET-001"))).thenReturn(successResp);

      JSONObject args = new JSONObject();
      args.put("assetId", "ASSET-001");

      JSONObject result = router.route(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
          args, PROCESS_SCOPES_LOCAL);

      assertFalse(result.has("isError"), "Successful plan generation must not be an error");
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("AMORT-001"));
    }

    @Test
    @DisplayName("neo_generate_amortization_plan propagates 400 error as isError")
    void amortizationToolPropagates400() throws Exception {
      NeoResponse errorResp = NeoResponse.error(400, "assetId is required");

      amortMock.when(() -> AmortizationPlanService.generatePlan(isNull())).thenReturn(errorResp);

      JSONObject result = router.route(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
          null, PROCESS_SCOPES_LOCAL);

      assertTrue(result.getBoolean("isError"), "Error response must set isError=true");
    }

    @Test
    @DisplayName("neo_generate_amortization_plan propagates 404 as isError")
    void amortizationToolPropagates404() throws Exception {
      NeoResponse notFoundResp = NeoResponse.error(404, "Asset not found: UNKNOWN");

      amortMock.when(() -> AmortizationPlanService.generatePlan(eq("UNKNOWN"))).thenReturn(notFoundResp);

      JSONObject args = new JSONObject();
      args.put("assetId", "UNKNOWN");

      JSONObject result = router.route(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
          args, PROCESS_SCOPES_LOCAL);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Asset not found"));
    }

    @Test
    @DisplayName("neo_generate_amortization_plan propagates 409 as isError")
    void amortizationToolPropagates409() throws Exception {
      NeoResponse conflictResp = NeoResponse.error(409,
          "Asset already has a generated amortization plan");

      amortMock.when(() -> AmortizationPlanService.generatePlan(eq("ASSET-001"))).thenReturn(conflictResp);

      JSONObject args = new JSONObject();
      args.put("assetId", "ASSET-001");

      JSONObject result = router.route(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
          args, PROCESS_SCOPES_LOCAL);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("already has a generated amortization plan"));
    }

    @Test
    @DisplayName("neo_generate_amortization_plan with missing assetId arg delegates null to service")
    void amortizationToolMissingAssetIdDelegatesToService() throws Exception {
      // When args has no assetId, the handler passes null to the service.
      NeoResponse errorResp = NeoResponse.error(400, "assetId is required");

      amortMock.when(() -> AmortizationPlanService.generatePlan(isNull())).thenReturn(errorResp);

      // Pass args without assetId
      JSONObject args = new JSONObject();
      JSONObject result = router.route(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
          args, PROCESS_SCOPES_LOCAL);

      assertTrue(result.getBoolean("isError"));
    }
  }

  // ── ETP-4257: entity-CRUD call-sites resolve via the shared guard ─────────

  /**
   * Every entity-CRUD handler now resolves its entity through
   * {@code McpToolRouterSupport.resolveIncludedEntityOrExplain} instead of
   * {@code findIncludedEntity}. These minimal tests drive each changed call-site
   * (neo_get/create/update/delete/selectors/schema) with a resolved entity that has no
   * AD_Tab, so the changed line executes and control exits at {@code getAdTabOrThrow} — before
   * any DefaultJsonDataService/ModelProvider static-init dependency is touched. neo_list and
   * neo_defaults call-sites are already covered by their own tests above.
   */
  @Nested
  @DisplayName("route — entity-CRUD tools resolve via shared guard (ETP-4257)")
  class CrudGuardCallSiteTests {

    private JSONObject routeWithNoTabEntity(String tool, JSONObject args, Set<String> scopes) {
      SFSpec spec = mockSpec(); // type "W"
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenReturn(entity);
      when(entity.getADTab()).thenReturn(null);
      return router.route(tool, args, scopes);
    }

    private void assertNoTabError(JSONObject result) throws Exception {
      assertTrue(result.getBoolean("isError"));
      assertTrue(contentText(result).contains("No AD_Tab"));
    }

    @Test
    @DisplayName("neo_get resolves entity via the shared guard")
    void getResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      assertNoTabError(routeWithNoTabEntity("neo_get", args, READ_SCOPES));
    }

    @Test
    @DisplayName("neo_create resolves entity via the shared guard")
    void createResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("fields", new JSONObject());
      assertNoTabError(routeWithNoTabEntity("neo_create", args, WRITE_SCOPES));
    }

    @Test
    @DisplayName("neo_update resolves entity via the shared guard")
    void updateResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      args.put("fields", new JSONObject());
      assertNoTabError(routeWithNoTabEntity("neo_update", args, WRITE_SCOPES));
    }

    @Test
    @DisplayName("neo_delete resolves entity via the shared guard")
    void deleteResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      assertNoTabError(routeWithNoTabEntity("neo_delete", args, WRITE_SCOPES));
    }

    @Test
    @DisplayName("neo_selectors resolves entity via the shared guard")
    void selectorsResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("column", "C_BPartner_ID");
      assertNoTabError(routeWithNoTabEntity("neo_selectors", args, READ_SCOPES));
    }

    @Test
    @DisplayName("neo_schema resolves entity via the shared guard")
    void schemaResolvesViaGuard() throws Exception {
      assertNoTabError(routeWithNoTabEntity("neo_schema", buildCrudArgs(), READ_SCOPES));
    }
  }
}
