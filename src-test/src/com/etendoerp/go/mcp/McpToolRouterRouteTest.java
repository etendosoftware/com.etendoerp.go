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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
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
import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.NeoVectorSearchEndpoint;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

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
 *
 * @covers com.etendoerp.go.mcp.McpToolRouter
 * @covers com.etendoerp.go.mcp.McpRoutingException
 * @covers com.etendoerp.go.mcp.McpNamedFilters
 * @covers com.etendoerp.go.mcp.McpDiscoverTool
 * @covers com.etendoerp.go.mcp.McpVectorSearchTool
 * @covers com.etendoerp.go.mcp.McpIndentResponse
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpToolRouterRouteTest {

  /**
   * A syntactically valid `updated` token for cases that are NOT about concurrency (ETP-5073).
   *
   * Its value is irrelevant: these tests never reach the version comparison, they only have to get
   * past the required-argument check. Naming it makes that intent explicit instead of leaving a
   * bare timestamp literal that reads like it matters.
   */
  private static final String STALE_SAFE_VERSION = "2026-08-21T16:20:38+00:00";

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
   * both the 2-arg (GET-tier, used by etendo_discover) and the 3-arg, method-aware overload
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
    // (ETP-4257); etendo_action still uses findIncludedEntity directly. Stub BOTH so every
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

  /**
   * Assert the IMP-5 envelope {@link McpRoutingException#entityHasNoTab} builds for an entity that
   * has neither an AD tab nor a {@code NeoHandler} (ETP-5405, no-handler branch).
   *
   * <p>Shared by every call site that exits at {@code McpWriteRequestSupport.getAdTabOrThrow}.
   * Structured on purpose: the assertions these replaced matched the substring
   * {@code "No AD_Tab"} of a prose message, which is exactly what let a deliberate reword — and a
   * status change from 500 to 422 — pass unnoticed until the message disappeared. {@code status}
   * and {@code error} are the machine-readable contract an agent branches on, so they are asserted
   * exactly; the {@code detail} check is kept to the one clause that carries the meaning (there is
   * nothing behind the entity) rather than the whole sentence.</p>
   *
   * @param envelope the parsed error content of the tool result
   */
  private static void assertNoTabEnvelope(JSONObject envelope) throws Exception {
    assertEquals(422, envelope.getInt("status"));
    assertEquals("validation_error", envelope.getString("error"));
    assertTrue(envelope.getString("detail").contains("no window and no handler"),
        envelope.toString());
    assertTrue(envelope.getString("hint").contains("etendo_discover"), envelope.toString());
  }

  /**
   * Assert the envelope {@link McpRoutingException#entityHasNoTab} builds for a tab-less entity
   * that IS backed by a {@code NeoHandler} (ETP-5405, handler branch).
   *
   * <p>405 rather than 422 is the whole point of the branch: the entity is perfectly readable
   * through {@code etendo_list}/{@code etendo_get}, only this tool cannot serve it, so the agent must
   * not be told the configuration is broken and must not retry with corrected values —
   * {@code method_not_allowed} is the code that says "the request is fine, this surface is
   * wrong" (see {@code McpConstants#ERROR_METHOD_NOT_ALLOWED}).</p>
   *
   * @param envelope the parsed error content of the tool result
   */
  private static void assertHandlerServedEnvelope(JSONObject envelope) throws Exception {
    assertEquals(405, envelope.getInt("status"));
    assertEquals("method_not_allowed", envelope.getString("error"));
    assertTrue(envelope.getString("detail").contains("served by a dedicated handler"),
        envelope.toString());
    assertTrue(envelope.getString("hint").contains("etendo_list"), envelope.toString());
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
      // route() authorizes etendo_list (a GET-tier tool) through the 3-arg overload —
      // override the setupSpecLookup() default (true) back to denied for this method.
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
          .thenReturn(false);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }
  }

  // ── ETP-5602: removed neo_<x> tool names ──────────────────────────────

  @Nested
  @DisplayName("a removed neo_<x> tool name")
  class RenamedToolTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "neo_list, etendo_list",
        "neo_action, etendo_action",
        "neo_get_image_upload, etendo_get_image_upload",
        "neo_generate_amortization_plan, etendo_generate_amortization_plan"
    })
    @DisplayName("answers with the new name and executes nothing")
    void answersWithTheNewNameAndExecutesNothing(String oldName, String newName)
        throws Exception {
      JSONObject result = router.route(oldName, buildCrudArgs(), READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      JSONObject envelope = new JSONObject(contentText(result));
      assertEquals("Tool '" + oldName + "' was renamed to '" + newName + "'",
          envelope.getString("detail"));
      assertEquals(404, envelope.getInt("status"));
      assertEquals(newName, envelope.getJSONArray("available").getString(0));
      // Refused before authorization or any lookup: this is not an alias.
      authMock.verify(() -> McpAuthorizationService.authorizeToolCall(anyString(), any()),
          never());
      supportMock.verify(() -> McpToolRouterSupport.findActiveSpecByName(anyString()), never());
    }

    @Test
    @DisplayName("an unrelated neo_ name is not treated as a rename")
    void unrelatedNeoNameIsNotARename() {
      assertNull(McpRoutingException.renamedToolName("neo_whatever"));
      assertNull(McpRoutingException.renamedToolName("etendo_list"));
      assertNull(McpRoutingException.renamedToolName(null));
      assertEquals("etendo_list", McpRoutingException.renamedToolName("neo_list"));
    }
  }

  // ── ETP-4510 write-tier authorization (code-review BUG-2) ─────────────

  /**
   * Integration-level regression coverage for the ETP-4510 code-review BUG-2 gap: the
   * only pre-existing {@code route()} authorization test used {@code etendo_list} (a
   * GET/read-tier tool). Nothing exercised {@code route()} denying a WRITE tool
   * (etendo_create/etendo_update/etendo_delete) for a read-only-access role at the integration
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
    @DisplayName("etendo_create is denied for a read-only-access role")
    void createDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject result = router.route("etendo_create", buildCrudArgs(), WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    @Test
    @DisplayName("etendo_update is denied for a read-only-access role")
    void updateDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      args.put("fields", new JSONObject());

      JSONObject result = router.route("etendo_update", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    @Test
    @DisplayName("etendo_delete is denied for a read-only-access role")
    void deleteDeniedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");

      JSONObject result = router.route("etendo_delete", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    /**
     * Companion to the three denial tests above: the exact same read-only-access role
     * must still be able to reach the read handlers (etendo_list, etendo_get) — proving the
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
    @DisplayName("etendo_list still passes authorization for the same read-only-access role")
    void listAllowedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      // "entity" intentionally omitted — proves we got past authorization into validateArgs.

      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertFalse(text.contains("Access denied"),
          "etendo_list must not be blocked by access control for a read-only role, got: " + text);
      assertTrue(text.contains("entity"));
    }

    @Test
    @DisplayName("etendo_get still passes authorization for the same read-only-access role")
    void getAllowedForReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      setupReadOnlyAccess(spec);

      JSONObject args = buildCrudArgs();
      // "id" intentionally omitted — proves we got past authorization into validateArgs.

      JSONObject result = router.route("etendo_get", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertFalse(text.contains("Access denied"),
          "etendo_get must not be blocked by access control for a read-only role, got: " + text);
      assertTrue(text.contains("id"));
    }
  }

  // ── etendo_batch access control (ETP-4510 code-review BUG-2b) ────────────

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
      args.put("async", false); // these tests exercise the synchronous, atomic path
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
            .executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
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
        when(mockBatch.executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(batchResult);

        JSONObject result = router.handleBatch(buildBatchArgs());

        assertFalse(result.has("isError"));
        // ETP-5415: a non-null preprocessor is the assertion, not an incidental detail — the MCP
        // body transforms ride along as a per-operation preprocessor, and a call through the
        // one-arg overload would silently run the batch with none of them. ETP-5676: the
        // _onlyReturnIds and _skipRedundantCallouts options default to false on MCP.
        org.mockito.Mockito.verify(mockBatch).executeBatch(any(),
            org.mockito.ArgumentMatchers.notNull(), org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq(false));
      }
    }

    /** ETP-5676: the etendo_batch argument reaches BatchService with the REST option's meaning. */
    @Test
    @DisplayName("an etendo_batch forwards _onlyReturnIds and _skipRedundantCallouts")
    void batchForwardsOnlyReturnIds() throws Exception {
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
        when(mockBatch.executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(batchResult);
        JSONObject args = buildBatchArgs();
        args.put(com.etendoerp.go.schemaforge.BatchService.PARAM_ONLY_RETURN_IDS, true);
        args.put(com.etendoerp.go.schemaforge.BatchService.PARAM_SKIP_REDUNDANT_CALLOUTS, true);

        JSONObject result = router.handleBatch(args);

        assertFalse(result.has("isError"));
        org.mockito.Mockito.verify(mockBatch).executeBatch(any(),
            org.mockito.ArgumentMatchers.notNull(), org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.eq(true));
      }
    }
  }

  // ── etendo_batch async + job tools (ETP-5677) ────────────────────────────

  /**
   * The async mode shares the synchronous call's access check and hands the job to
   * {@code BatchJobService}; the two job tools only forward to the same service.
   */
  @Nested
  @DisplayName("etendo_batch async and etendo_job_* (ETP-5677)")
  class BatchJobTests {

    private JSONObject asyncArgs() throws Exception {
      JSONObject op = new JSONObject().put("id", "op1").put("spec", SPEC_NAME)
          .put("entity", ENTITY_NAME).put("body", new JSONObject());
      return new JSONObject().put("operations", new JSONArray().put(op)).put("async", true);
    }

    private String textOf(JSONObject result) throws Exception {
      return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    @Test
    @DisplayName("async:true is access-checked per spec, then submitted as an mcp job without running a batch")
    void asyncSubmitsAJobAfterTheAccessCheck() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST")).thenReturn(true);

      com.etendoerp.go.schemaforge.BatchJobService jobs =
          mock(com.etendoerp.go.schemaforge.BatchJobService.class);
      when(jobs.submit(any(), any(), eq("mcp"), any()))
          .thenReturn(new JSONObject().put("jobId", "J1").put("state", "QUEUED"));
      try (MockedStatic<com.etendoerp.go.schemaforge.BatchJobService> jobsMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchJobService.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchJobService.Owner> ownerMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchJobService.Owner.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchService> batchMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchService.class)) {
        jobsMock.when(com.etendoerp.go.schemaforge.BatchJobService::getInstance).thenReturn(jobs);
        ownerMock.when(com.etendoerp.go.schemaforge.BatchJobService.Owner::current)
            .thenReturn(new com.etendoerp.go.schemaforge.BatchJobService.Owner("U", "R", "C", "O",
                "en_US", null));
        com.etendoerp.go.schemaforge.BatchService mockBatch =
            mock(com.etendoerp.go.schemaforge.BatchService.class);
        batchMock.when(com.etendoerp.go.schemaforge.BatchService::forBatchOnly)
            .thenReturn(mockBatch);

        JSONObject result = router.handleBatch(asyncArgs());

        assertFalse(result.has("isError"));
        assertEquals("J1", new JSONObject(textOf(result)).getString("jobId"));
        org.mockito.Mockito.verify(mockBatch, org.mockito.Mockito.never())
            .executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
      }
    }

    /**
     * Volume is the default: without {@code async} the call is a job, and only an explicit
     * {@code async:false} runs the atomic, synchronous batch.
     */
    @Test
    @DisplayName("async omitted submits a job; async:false runs the synchronous batch")
    void asyncIsTheDefaultAndFalseOptsOut() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST")).thenReturn(true);
      com.etendoerp.go.schemaforge.BatchJobService jobs =
          mock(com.etendoerp.go.schemaforge.BatchJobService.class);
      when(jobs.submit(any(), any(), eq("mcp"), any()))
          .thenReturn(new JSONObject().put("jobId", "J1").put("state", "QUEUED"));

      try (MockedStatic<com.etendoerp.go.schemaforge.BatchJobService> jobsMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchJobService.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchJobService.Owner> ownerMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchJobService.Owner.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchService> batchMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchService.class)) {
        jobsMock.when(com.etendoerp.go.schemaforge.BatchJobService::getInstance).thenReturn(jobs);
        ownerMock.when(com.etendoerp.go.schemaforge.BatchJobService.Owner::current)
            .thenReturn(new com.etendoerp.go.schemaforge.BatchJobService.Owner("U", "R", "C", "O",
                "en_US", null));
        com.etendoerp.go.schemaforge.BatchService mockBatch =
            mock(com.etendoerp.go.schemaforge.BatchService.class);
        batchMock.when(com.etendoerp.go.schemaforge.BatchService::forBatchOnly)
            .thenReturn(mockBatch);
        when(mockBatch.executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(new JSONObject().put("committed", true));

        JSONObject omitted = asyncArgs();
        omitted.remove("async");
        JSONObject defaulted = router.handleBatch(omitted);
        assertEquals("J1", new JSONObject(textOf(defaulted)).getString("jobId"));
        verify(jobs).submit(any(), any(), eq("mcp"), any());
        org.mockito.Mockito.verify(mockBatch, org.mockito.Mockito.never())
            .executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());

        JSONObject optedOut = asyncArgs();
        optedOut.put("async", false);
        router.handleBatch(optedOut);
        org.mockito.Mockito.verify(jobs, org.mockito.Mockito.times(1))
            .submit(any(), any(), eq("mcp"), any());
        org.mockito.Mockito.verify(mockBatch).executeBatch(any(),
            org.mockito.ArgumentMatchers.notNull(), eq(false), eq(false));
      }
    }

    /**
     * REST/MCP parity: the job's per-row runner IS the synchronous MCP call — same
     * {@code executeBatch} with the MCP preprocessor and the request's options, under admin mode,
     * and a failed row gets the same IMP-5 envelope rewrite the synchronous tool applies.
     */
    @Test
    @DisplayName("the async row runner is the synchronous MCP call, row by row")
    void theAsyncRowRunnerIsTheSynchronousMcpCall() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST")).thenReturn(true);
      com.etendoerp.go.schemaforge.BatchJobService jobs =
          mock(com.etendoerp.go.schemaforge.BatchJobService.class);
      when(jobs.submit(any(), any(), eq("mcp"), any())).thenReturn(new JSONObject().put("jobId", "J1"));
      JSONObject failure = new JSONObject().put("committed", false).put("error", new JSONObject());
      JSONArray rowOps = new JSONArray().put(new JSONObject().put("id", "r"));

      try (MockedStatic<com.etendoerp.go.schemaforge.BatchJobService> jobsMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchJobService.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchJobService.Owner> ownerMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchJobService.Owner.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchService> batchMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchService.class);
          MockedStatic<McpBatchEnvelope> envelopeMock = mockStatic(McpBatchEnvelope.class)) {
        jobsMock.when(com.etendoerp.go.schemaforge.BatchJobService::getInstance).thenReturn(jobs);
        ownerMock.when(com.etendoerp.go.schemaforge.BatchJobService.Owner::current)
            .thenReturn(new com.etendoerp.go.schemaforge.BatchJobService.Owner("U", "R", "C", "O",
                "en_US", null));
        com.etendoerp.go.schemaforge.BatchService mockBatch =
            mock(com.etendoerp.go.schemaforge.BatchService.class);
        batchMock.when(com.etendoerp.go.schemaforge.BatchService::forBatchOnly).thenReturn(mockBatch);
        when(mockBatch.executeBatch(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(failure);
        JSONObject args = asyncArgs();
        args.put(com.etendoerp.go.schemaforge.BatchService.PARAM_ONLY_RETURN_IDS, true);
        args.put(com.etendoerp.go.schemaforge.BatchService.PARAM_SKIP_REDUNDANT_CALLOUTS, true);

        router.handleBatch(args);

        ArgumentCaptor<com.etendoerp.go.schemaforge.BatchJobService.RowRunner> runner =
            ArgumentCaptor.forClass(com.etendoerp.go.schemaforge.BatchJobService.RowRunner.class);
        verify(jobs).submit(any(), any(), eq("mcp"), runner.capture());
        obContextMock.clearInvocations();
        JSONObject outcome = runner.getValue().run(rowOps);

        assertEquals(failure, outcome);
        verify(mockBatch).executeBatch(eq(rowOps), org.mockito.ArgumentMatchers.notNull(), eq(true),
            eq(true));
        envelopeMock.verify(() -> McpBatchEnvelope.toMcpBatchFailure(failure));
        obContextMock.verify(OBContext::setAdminMode);
        obContextMock.verify(OBContext::restorePreviousMode);
      }
    }

    @Test
    @DisplayName("a read-only role cannot start an async batch either")
    void asyncIsDeniedForAReadOnlyRole() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST")).thenReturn(false);

      JSONObject result = router.handleBatch(asyncArgs());

      assertTrue(result.getBoolean("isError"));
      assertTrue(textOf(result).contains("Access denied"));
    }

    @Test
    @DisplayName("a refused submit (429) tells the agent when to retry")
    void aTooBusySubmitCarriesTheRetryHint() throws Exception {
      SFSpec spec = mockSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC_NAME)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(spec, "W", "POST")).thenReturn(true);
      com.etendoerp.go.schemaforge.BatchJobService jobs =
          mock(com.etendoerp.go.schemaforge.BatchJobService.class);
      when(jobs.submit(any(), any(), any(), any())).thenThrow(
          new com.etendoerp.go.schemaforge.BatchJobService.JobException(429, "Too many", 5));
      try (MockedStatic<com.etendoerp.go.schemaforge.BatchJobService> jobsMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchJobService.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchJobService.Owner> ownerMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchJobService.Owner.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchService> batchMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchService.class)) {
        jobsMock.when(com.etendoerp.go.schemaforge.BatchJobService::getInstance).thenReturn(jobs);
        ownerMock.when(com.etendoerp.go.schemaforge.BatchJobService.Owner::current)
            .thenReturn(new com.etendoerp.go.schemaforge.BatchJobService.Owner("U", "R", "C", "O",
                "en_US", null));
        batchMock.when(com.etendoerp.go.schemaforge.BatchService::forBatchOnly)
            .thenReturn(mock(com.etendoerp.go.schemaforge.BatchService.class));

        JSONObject result = router.handleBatch(asyncArgs());

        assertTrue(result.getBoolean("isError"));
        assertTrue(textOf(result).contains("retry after 5 s"));
      }
    }

    @Test
    @DisplayName("etendo_job_status returns the stored status of the caller's job")
    void jobStatusForwardsToTheService() throws Exception {
      com.etendoerp.go.schemaforge.BatchJobService jobs =
          mock(com.etendoerp.go.schemaforge.BatchJobService.class);
      com.etendoerp.go.schemaforge.BatchJobService.Owner owner =
          new com.etendoerp.go.schemaforge.BatchJobService.Owner("U", "R", "C", "O", "en_US", null);
      when(jobs.status(owner, "J1")).thenReturn(new JSONObject().put("state", "RUNNING"));
      try (MockedStatic<com.etendoerp.go.schemaforge.BatchJobService> jobsMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchJobService.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchJobService.Owner> ownerMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchJobService.Owner.class)) {
        jobsMock.when(com.etendoerp.go.schemaforge.BatchJobService::getInstance).thenReturn(jobs);
        ownerMock.when(com.etendoerp.go.schemaforge.BatchJobService.Owner::current).thenReturn(owner);

        JSONObject result = McpBatchJobTools.handleBatchJob("etendo_job_status",
            new JSONObject().put("jobId", "J1"));

        assertFalse(result.has("isError"));
        assertEquals("RUNNING", new JSONObject(textOf(result)).getString("state"));
      }
    }

    @Test
    @DisplayName("etendo_job_cancel forwards to cancel, and a stranger's job answers not found")
    void jobCancelForwardsAndMapsRefusals() throws Exception {
      com.etendoerp.go.schemaforge.BatchJobService jobs =
          mock(com.etendoerp.go.schemaforge.BatchJobService.class);
      com.etendoerp.go.schemaforge.BatchJobService.Owner owner =
          new com.etendoerp.go.schemaforge.BatchJobService.Owner("U", "R", "C", "O", "en_US", null);
      when(jobs.cancel(owner, "MINE")).thenReturn(new JSONObject().put("state", "CANCELLED"));
      when(jobs.cancel(owner, "THEIRS")).thenThrow(
          new com.etendoerp.go.schemaforge.BatchJobService.JobException(404, "Job not found: THEIRS",
              null));
      try (MockedStatic<com.etendoerp.go.schemaforge.BatchJobService> jobsMock =
          mockStatic(com.etendoerp.go.schemaforge.BatchJobService.class);
          MockedStatic<com.etendoerp.go.schemaforge.BatchJobService.Owner> ownerMock =
              mockStatic(com.etendoerp.go.schemaforge.BatchJobService.Owner.class)) {
        jobsMock.when(com.etendoerp.go.schemaforge.BatchJobService::getInstance).thenReturn(jobs);
        ownerMock.when(com.etendoerp.go.schemaforge.BatchJobService.Owner::current).thenReturn(owner);

        JSONObject mine = McpBatchJobTools.handleBatchJob("etendo_job_cancel", new JSONObject().put("jobId", "MINE"));
        JSONObject theirs = McpBatchJobTools.handleBatchJob("etendo_job_cancel", new JSONObject().put("jobId", "THEIRS"));

        assertEquals("CANCELLED", new JSONObject(textOf(mine)).getString("state"));
        assertTrue(theirs.getBoolean("isError"));
        assertTrue(textOf(theirs).contains("Job not found"));
      }
    }

    @Test
    @DisplayName("a job tool without a jobId is refused")
    void jobToolsRequireAJobId() throws Exception {
      JSONObject result = McpBatchJobTools.handleBatchJob("etendo_job_status", new JSONObject());
      assertTrue(result.getBoolean("isError"));
      assertTrue(textOf(result).contains("jobId is required"));
    }
  }

  // ── etendo_discover ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_discover")
  class DiscoverTests {

    @Test
    @DisplayName("etendo_discover returns specs array")
    @SuppressWarnings("unchecked")
    void discoverReturnsSpecs() throws Exception {
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.emptyList());

      JSONObject result = router.route("etendo_discover", null, READ_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertTrue(body.has("specs"));
      assertEquals(0, body.getInt("count"));
    }

    @Test
    @DisplayName("etendo_discover includes accessible specs")
    @SuppressWarnings("unchecked")
    void discoverIncludesAccessibleSpecs() throws Exception {
      SFSpec spec = mockSpec();
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(List.of(spec));
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(eq(spec), eq("W")))
          .thenReturn(true);

      // ETP-4254: McpDiscoverTool.handle now loads the included entities once and feeds both the
      // summary array and the spec-level readOnly marker from that single list.
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
          .thenReturn(Collections.emptyList());
      supportMock.when(() -> McpToolRouterSupport.buildEntitySummaryArray(anyList()))
          .thenReturn(new JSONArray());

      JSONObject discoverSpec = new JSONObject();
      discoverSpec.put("name", SPEC_NAME);
      discoverSpec.put("type", "window");
      supportMock.when(() -> McpToolRouterSupport.buildDiscoverSpec(
          eq(spec), eq("W"), any(), any(), any())).thenReturn(discoverSpec);

      JSONObject result = router.route("etendo_discover", null, READ_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals(1, body.getInt("count"));
    }

    @Test
    @DisplayName("etendo_discover hides a spec whose entities are all hidden accounting (ETP-5648)")
    @SuppressWarnings("unchecked")
    void discoverHidesAllAccountingSpec() throws Exception {
      SFSpec spec = mockSpec();
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(List.of(spec));
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(eq(spec), eq("W")))
          .thenReturn(true);
      List<SFEntity> included = List.of(mock(SFEntity.class));
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
          .thenReturn(included);
      try (MockedStatic<NeoAccountingAccessPolicy> policyMock =
          mockStatic(NeoAccountingAccessPolicy.class)) {
        policyMock.when(() -> NeoAccountingAccessPolicy.visibleEntities(included))
            .thenReturn(Collections.emptyList());

        JSONObject body = new JSONObject(router.route("etendo_discover", null, READ_SCOPES)
            .getJSONArray("content").getJSONObject(0).getString("text"));

        assertEquals(0, body.getInt("count"));
      }
      supportMock.verify(() -> McpToolRouterSupport.buildDiscoverSpec(
          any(), any(), any(), any(), any()), never());
    }

    // ── IMP-53 (ETP-5639): etendo_discover({spec}) narrows the catalog ──────

    private static final String OTHER_SPEC = "purchase-order";
    private static final String HIDDEN_SPEC = "hidden-spec";

    /**
     * Stubs a W spec that the role can (or cannot) reach, with an empty entity list and a
     * buildDiscoverSpec row carrying its name.
     */
    private SFSpec stubDiscoverableSpec(String name, boolean accessible) throws Exception {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn(name + "-id");
      when(spec.getName()).thenReturn(name);
      when(spec.getSpecType()).thenReturn("W");
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(eq(spec), eq("W")))
          .thenReturn(accessible);
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(name + "-id"))
          .thenReturn(Collections.emptyList());
      JSONObject row = new JSONObject();
      row.put("name", name);
      supportMock.when(() -> McpToolRouterSupport.buildDiscoverSpec(
          eq(spec), eq("W"), any(), any(), any())).thenReturn(row);
      return spec;
    }

    @SuppressWarnings("unchecked")
    private void stubCatalog(SFSpec... specs) {
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(List.of(specs));
      supportMock.when(() -> McpToolRouterSupport.buildEntitySummaryArray(anyList()))
          .thenReturn(new JSONArray());
    }

    private JSONObject bodyOf(JSONObject result) throws Exception {
      return new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
    }

    @Test
    @DisplayName("etendo_discover(spec) returns only the requested spec, same envelope")
    void discoverWithSpecReturnsOnlyThatSpec() throws Exception {
      SFSpec wanted = stubDiscoverableSpec(OTHER_SPEC, true);
      SFSpec other = stubDiscoverableSpec(SPEC_NAME, true);
      stubCatalog(other, wanted);

      JSONObject args = new JSONObject();
      args.put("spec", OTHER_SPEC);
      JSONObject result = router.route("etendo_discover", args, READ_SCOPES);

      assertFalse(result.has("isError"), result.toString());
      JSONObject body = bodyOf(result);
      assertEquals(1, body.getInt("count"));
      assertEquals(1, body.getJSONArray("specs").length());
      assertEquals(OTHER_SPEC, body.getJSONArray("specs").getJSONObject(0).getString("name"));
      // The narrowed answer pays only for the spec it returns.
      supportMock.verify(() -> McpToolRouterSupport.buildDiscoverSpec(
          eq(other), any(), any(), any(), any()), never());
    }

    @Test
    @DisplayName("etendo_discover(spec:[a,b]) returns exactly the requested specs")
    void discoverWithSpecArrayReturnsThoseSpecs() throws Exception {
      SFSpec a = stubDiscoverableSpec(OTHER_SPEC, true);
      SFSpec b = stubDiscoverableSpec(SPEC_NAME, true);
      SFSpec c = stubDiscoverableSpec("third-spec", true);
      stubCatalog(a, b, c);

      JSONObject args = new JSONObject();
      args.put("spec", new JSONArray(List.of(SPEC_NAME, OTHER_SPEC)));
      JSONObject body = bodyOf(router.route("etendo_discover", args, READ_SCOPES));

      assertEquals(2, body.getInt("count"));
      Set<String> names = new java.util.HashSet<>();
      for (int i = 0; i < body.getJSONArray("specs").length(); i++) {
        names.add(body.getJSONArray("specs").getJSONObject(i).getString("name"));
      }
      assertEquals(Set.of(SPEC_NAME, OTHER_SPEC), names);
    }

    @Test
    @DisplayName("etendo_discover(spec) with an unknown name answers 422 carrying the reachable names")
    void discoverWithUnknownSpecReturns422WithAvailable() throws Exception {
      SFSpec visible = stubDiscoverableSpec(SPEC_NAME, true);
      SFSpec hidden = stubDiscoverableSpec(HIDDEN_SPEC, false);
      stubCatalog(visible, hidden);

      JSONObject args = new JSONObject();
      args.put("spec", "no-such-spec");
      JSONObject result = router.route("etendo_discover", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"), result.toString());
      JSONObject envelope = bodyOf(result);
      assertEquals(422, envelope.getInt("status"));
      assertEquals("validation_error", envelope.getString("error"));
      assertEquals("spec", envelope.getString("field"));
      assertTrue(envelope.getString("detail").contains("no-such-spec"));
      JSONArray available = envelope.getJSONArray("available");
      assertEquals(1, available.length(), "only the specs this role reaches are offered");
      assertEquals(SPEC_NAME, available.getString(0));
      assertTrue(envelope.has("hint"));
      assertTrue(envelope.has("seeAlso"));
    }

    @Test
    @DisplayName("etendo_discover(spec:[...]) with several unknown names lists them all, in request order")
    void discoverWithSeveralUnknownSpecsNamesThemInRequestOrder() throws Exception {
      SFSpec visible = stubDiscoverableSpec(SPEC_NAME, true);
      stubCatalog(visible);

      JSONObject args = new JSONObject();
      args.put("spec", new JSONArray(List.of("zeta-spec", SPEC_NAME, "alpha-spec")));
      JSONObject envelope = bodyOf(router.route("etendo_discover", args, READ_SCOPES));

      assertEquals(422, envelope.getInt("status"));
      assertEquals("Unknown specs 'zeta-spec', 'alpha-spec' for etendo_discover",
          envelope.getString("detail"));
    }

    @Test
    @DisplayName("etendo_discover(spec) naming a spec the role cannot reach is refused like an unknown one")
    void discoverWithInaccessibleSpecIsRefused() throws Exception {
      SFSpec visible = stubDiscoverableSpec(SPEC_NAME, true);
      SFSpec hidden = stubDiscoverableSpec(HIDDEN_SPEC, false);
      stubCatalog(visible, hidden);

      JSONObject args = new JSONObject();
      args.put("spec", HIDDEN_SPEC);
      JSONObject envelope = bodyOf(router.route("etendo_discover", args, READ_SCOPES));

      assertEquals(422, envelope.getInt("status"));
      assertFalse(envelope.getJSONArray("available").toString().contains(HIDDEN_SPEC));
    }

    // ── IMP-53: _indentResponse, the per-call presentation flag ────────────

    private String textOf(JSONObject result) throws Exception {
      return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    @Test
    @DisplayName("_indentResponse:true returns the JSON indented; the default stays compact")
    void indentResponseIndentsOnlyWhenAsked() throws Exception {
      stubCatalog(stubDiscoverableSpec(SPEC_NAME, true));

      JSONObject indented = new JSONObject();
      indented.put("_indentResponse", true);
      String text = textOf(router.route("etendo_discover", indented, READ_SCOPES));
      assertTrue(text.contains("\n  \""), "expected two-space indentation: " + text);

      // The flag is per call: the next call without it is compact again.
      String compact = textOf(router.route("etendo_discover", null, READ_SCOPES));
      assertFalse(compact.contains("\n"), compact);
    }

    @Test
    @DisplayName("_indentResponse:true indents the error body of that call too")
    void indentResponseIndentsErrors() throws Exception {
      stubCatalog(stubDiscoverableSpec(SPEC_NAME, true));

      JSONObject args = new JSONObject();
      args.put("spec", "no-such-spec");
      args.put("_indentResponse", true);
      JSONObject result = router.route("etendo_discover", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      assertTrue(textOf(result).contains("\n  \""), textOf(result));
    }

    @Test
    @DisplayName("_indentResponse is stripped before routing: not an unknown argument, not forwarded")
    void indentResponseIsStrippedBeforeRouting() throws Exception {
      stubCatalog(stubDiscoverableSpec(SPEC_NAME, true));

      JSONObject args = new JSONObject();
      args.put("_indentResponse", false);
      JSONObject result = router.route("etendo_discover", args, READ_SCOPES);

      // etendo_discover declares only 'spec'; the guard would refuse the flag had it reached it.
      assertFalse(result.has("isError"), result.toString());
      assertFalse(args.has("_indentResponse"),
          "the flag must be gone before any handler or telemetry reads the arguments");
    }

    @Test
    @DisplayName("etendo_discover rejects an undeclared argument instead of ignoring it")
    void discoverRejectsUnknownArgument() throws Exception {
      stubCatalog();

      JSONObject args = new JSONObject();
      args.put("entity", "header");
      JSONObject result = router.route("etendo_discover", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"), result.toString());
      JSONObject envelope = bodyOf(result);
      assertEquals("unknown_argument", envelope.getString("error"));
      assertTrue(envelope.getJSONArray("available").toString().contains("spec"));
    }

    @Test
    @DisplayName("etendo_send_document_email refuses a wrong-case or unknown key before any send")
    void sendDocumentEmailRejectsUnknownKeysBeforeSending() throws Exception {
      stubCatalog();
      try (MockedStatic<McpDocumentEmailTool> handler = mockStatic(McpDocumentEmailTool.class)) {
        for (String key : List.of("Preview", "recipient", "bcc")) {
          JSONObject args = new JSONObject();
          args.put("spec", SPEC_NAME);
          args.put("id", "REC1");
          args.put("to", new org.codehaus.jettison.json.JSONArray(List.of("a@b.example")));
          args.put(key, true);

          JSONObject result = router.route(McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL, args,
              WRITE_SCOPES);

          assertTrue(result.getBoolean("isError"), result.toString());
          assertEquals("unknown_argument", bodyOf(result).getString("error"), key);
          assertEquals(key, bodyOf(result).getString("field"));
        }
        handler.verify(() -> McpDocumentEmailTool.handle(any()), org.mockito.Mockito.never());
      }
    }

    @Test
    @DisplayName("ETP-5669: etendo_action refuses a top-level docAction and points at 'parameters'")
    void actionRejectsTopLevelDocAction() throws Exception {
      stubCatalog();

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      args.put("entity", "header");
      args.put("id", "J1");
      args.put("action", "documentAction");
      args.put("docAction", "CO");
      JSONObject result = router.route("etendo_action", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"), result.toString());
      JSONObject envelope = bodyOf(result);
      assertEquals("unknown_argument", envelope.getString("error"));
      assertEquals("docAction", envelope.getString("field"));
      assertTrue(envelope.getString("hint").contains("'parameters'"), envelope.toString());
    }
  }

  // ── etendo_list ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_list")
  class ListTests {

    /**
     * IMP-50, W2: a named {@code status} plus {@code fields}. {@code handleList} itself needs
     * {@code DefaultJsonDataService} (see the class javadoc), so the test runs the same steps it
     * runs after the fetch, in its order — projection on the core-shaped body, flatten, attach —
     * and then pins that order on the method source, so the composition tested is the one that
     * ships.
     */
    @Test
    @DisplayName("a named status with fields: namedFilters is top-level and survives projection")
    void namedStatusBlockSurvivesProjection() throws Exception {
      supportMock.when(() -> McpToolRouterSupport.flattenCoreResponse(any()))
          .thenCallRealMethod();
      String namedFilters = "[{\"name\":\"open\",\"description\":\"Owes a balance.\","
          + "\"where\":\"e.open = true\"},{\"name\":\"closed\",\"where\":\"e.open = false\"}]";
      JSONObject row = new JSONObject();
      row.put("documentNo", "INV-1");
      row.put("grandTotalAmount", 10);
      JSONObject inner = new JSONObject();
      inner.put("status", 0);
      inner.put("data", new JSONArray().put(row));
      JSONObject core = new JSONObject();
      core.put("response", inner);
      JSONObject filters = new JSONObject();
      filters.put("status", "open");

      McpFieldProjection.apply(core, Set.of("documentNo"));
      JSONObject flat = McpToolRouterSupport.flattenCoreResponse(core);
      McpNamedFilters.attachApplied(flat, namedFilters, filters);
      JSONObject body = new JSONObject(McpToolRouter.wrapAsTextContent(flat)
          .getJSONArray("content").getJSONObject(0).getString("text"));

      JSONObject block = body.getJSONObject("namedFilters");
      assertEquals("open", block.getString("applied"));
      assertEquals("closed", block.getJSONArray("available").getJSONObject(0).getString("name"));
      JSONObject projected = body.getJSONArray("data").getJSONObject(0);
      assertEquals("INV-1", projected.getString("documentNo"));
      assertFalse(projected.has("grandTotalAmount"), "projection still applies to the rows");
      assertFalse(projected.has("namedFilters"), "the block is not a row column");

      String handleList = McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleList");
      int projection = handleList.indexOf("McpQuerySupport.applyProjection(");
      int flatten = handleList.indexOf("flattenCoreResponse(");
      int attach = handleList.indexOf("McpNamedFilters.attachApplied(flat,");
      assertTrue(projection >= 0 && flatten > projection && attach > flatten,
          "handleList must project, then flatten, then attach the block to the flat body");
      assertTrue(handleList.indexOf("wrapAsTextContent(flat)", attach) > attach,
          "the body returned must be the one carrying the block");
    }

    @Test
    @DisplayName("etendo_list missing entity argument returns error")
    void listMissingEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);

      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("entity"));
    }

    @Test
    @DisplayName("etendo_list with null arguments returns error")
    void listNullArgsReturnsError() throws Exception {
      JSONObject result = router.route("etendo_list", null, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Missing arguments"));
    }

    /**
     * ETP-4257: etendo_list on a CALLABLE report (R) spec no longer surfaces the opaque
     * "Entity not found: header". End-to-end (route → handleList → shared guard) the error
     * names the report type and points the agent at the concrete etendo_generate_ tool.
     */
    @Test
    @DisplayName("etendo_list on a callable report (R) spec explains the generate tool")
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

        JSONObject result = router.route("etendo_list", buildCrudArgs(), READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        String text = contentText(result);
        assertTrue(text.contains("report type (R)"),
            "error must state the spec is a report type: " + text);
        assertTrue(text.contains("etendo_generate_"),
            "error must point at the generate tool: " + text);
      }
    }
  }

  // ── etendo_get ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_get")
  class GetTests {

    @Test
    @DisplayName("etendo_get missing id argument returns error")
    void getMissingIdReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("etendo_get", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("id"));
    }
  }

  // ── etendo_create ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_create")
  class CreateTests {

    @Test
    @DisplayName("etendo_create missing fields argument returns error")
    void createMissingFieldsReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("etendo_create", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("fields"));
    }
  }

  // ── etendo_update ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_update")
  class UpdateTests {

    @Test
    @DisplayName("etendo_update missing id returns error")
    void updateMissingIdReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();
      args.put("fields", new JSONObject());

      JSONObject result = router.route("etendo_update", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("id"));
    }
    @Test
    @DisplayName("etendo_update rejects blank and null-string versions before record lookup")
    void updateRejectsUnusableVersion() throws Exception {
      setupSpecLookup(mockSpec());
      for (String updated : new String[] { "", "  ", "null" }) {
        JSONObject args = buildCrudArgs();
        args.put("id", "record-id");
        args.put("fields", new JSONObject());
        args.put("updated", updated);

        JSONObject result = router.route("etendo_update", args, WRITE_SCOPES);

        assertTrue(result.getBoolean("isError"));
        JSONObject envelope = new JSONObject(contentText(result));
        assertEquals(422, envelope.getInt("status"));
        assertTrue(envelope.getString("detail").contains("updated"), envelope.toString());
      }
    }
  }

  // ── etendo_delete ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_delete")
  class DeleteTests {

    @Test
    @DisplayName("etendo_delete missing id returns error")
    void deleteMissingIdReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("etendo_delete", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
    }
  }

  // ── ETP-4254 entity method-flag gate on the MCP write path ────────────

  /**
   * ETP-4254: the MCP write handlers resolve the entity and go straight to
   * {@code DefaultJsonDataService}, so before this fix they never consulted the
   * {@code ETGO_SF_ENTITY} method flags that the REST CRUD path enforces with a {@code 405}.
   * Turning the mutation flags off on a monitor/log window therefore blocked the React UI
   * while leaving an MCP agent free to write — and made {@code etendo_discover}'s
   * {@code readOnly: true} a lie.
   *
   * <p>These tests run the REAL {@code requireMethodEnabled} (same {@code thenCallRealMethod}
   * seam already used for {@code validateArgs}) so the refusal text is asserted end to end
   * through {@code route()}.</p>
   */
  @Nested
  @DisplayName("route — entity method-flag gate (ETP-4254)")
  class MethodFlagGateTests {

    @BeforeEach
    void enableRealGate() {
      supportMock.when(() -> McpToolRouterSupport.requireMethodEnabled(
          any(), any(), anyString())).thenCallRealMethod();
    }

    /** A monitor/log entity: readable, every mutation flag off. */
    private SFEntity readOnlyEntity() {
      SFEntity entity = mockEntity();
      when(entity.isGet()).thenReturn(true);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(false);
      when(entity.isPut()).thenReturn(false);
      when(entity.isPatch()).thenReturn(false);
      when(entity.isDelete()).thenReturn(false);
      return entity;
    }

    private SFEntity writableEntity() {
      SFEntity entity = mockEntity();
      when(entity.isGet()).thenReturn(true);
      when(entity.isGetByID()).thenReturn(true);
      when(entity.isPost()).thenReturn(true);
      when(entity.isPut()).thenReturn(true);
      when(entity.isPatch()).thenReturn(true);
      when(entity.isDelete()).thenReturn(true);
      return entity;
    }

    private String routeAndGetText(String toolName, JSONObject args) throws Exception {
      JSONObject result = router.route(toolName, args, WRITE_SCOPES);
      return result.getJSONArray("content").getJSONObject(0).getString("text");
    }

    private JSONObject createArgs() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("fields", new JSONObject());
      return args;
    }

    private JSONObject updateArgs() throws Exception {
      JSONObject args = createArgs();
      args.put("id", "rec-1");
      // ETP-5073 / DOC-04: etendo_update requires the record's `updated` value. These cases are about
      // the method-flag gate, not about argument validation, so the token is supplied to let them
      // reach the gate they are testing — argument validation runs first and would otherwise
      // answer "Missing required argument: updated" before the gate is ever consulted.
      args.put("updated", STALE_SAFE_VERSION);
      return args;
    }

    private JSONObject deleteArgs() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      return args;
    }

    @Test
    @DisplayName("etendo_create is refused on a read-only entity")
    void createRefusedOnReadOnlyEntity() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      setupEntityLookup(readOnlyEntity(), null);

      String text = routeAndGetText("etendo_create", createArgs());

      assertTrue(text.contains("does not enable POST"), text);
      assertTrue(text.contains("read-only"), text);
    }

    @Test
    @DisplayName("etendo_update is refused on a read-only entity")
    void updateRefusedOnReadOnlyEntity() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      setupEntityLookup(readOnlyEntity(), null);

      String text = routeAndGetText("etendo_update", updateArgs());

      assertTrue(text.contains("does not enable PUT"), text);
    }

    @Test
    @DisplayName("etendo_delete is refused on a read-only entity")
    void deleteRefusedOnReadOnlyEntity() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      setupEntityLookup(readOnlyEntity(), null);

      String text = routeAndGetText("etendo_delete", deleteArgs());

      assertTrue(text.contains("does not enable DELETE"), text);
    }

    /**
     * Companion to the three refusals: a writable entity must get PAST the gate. The entity is
     * given a null AD_Tab on purpose so the call then fails in {@code getAdTabOrThrow} — the
     * next step after the gate — instead of reaching {@code DefaultJsonDataService}, whose
     * static initialiser needs a servlet container (see this class's javadoc). What matters is
     * that the failure is the tab-less one and never the method-flag refusal, mirroring
     * {@code WriteTierAuthorizationTests#listAllowedForReadOnlyRole}.
     *
     * <p>ETP-5405: that failure used to be a bare {@code IllegalArgumentException} rendered by the
     * catch-all as {@code 500 server_error "No AD_Tab linked to entity: header"}. It is now
     * {@link McpRoutingException#entityHasNoTab}'s no-handler branch, so the assertion reads the
     * envelope rather than matching a prose substring that the reword silently invalidated.</p>
     */
    @Test
    @DisplayName("a writable entity passes the gate")
    void writableEntityPassesTheGate() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      setupEntityLookup(writableEntity(), null);

      String text = routeAndGetText("etendo_create", createArgs());

      assertFalse(text.contains("does not enable"), text);
      assertNoTabEnvelope(new JSONObject(text));
    }
  }

  // ── etendo_selectors ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_selectors")
  class SelectorsTests {

    @Test
    @DisplayName("etendo_selectors missing column returns error")
    void selectorsMissingColumnReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = buildCrudArgs();

      JSONObject result = router.route("etendo_selectors", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("column"));
    }
  }

  // ── etendo_defaults ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_defaults")
  class DefaultsTests {

    @Test
    @DisplayName("etendo_defaults returns default values")
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
      JSONObject result = router.route("etendo_defaults", args, READ_SCOPES);

      assertFalse(result.has("isError"));
    }

    @Test
    @DisplayName("etendo_defaults missing entity returns error")
    void defaultsMissingEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);

      JSONObject result = router.route("etendo_defaults", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("entity"));
    }

    // ── ETP-5558: etendo_defaults on an entity whose create MCP_CONFIG.verbs hides ──

    private static final String HIDE_CREATE = "{\"verbs\":{\"create\":false,"
        + "\"reason\":\"Payments are registered from the invoice\","
        + "\"instead\":\"etendo_action(spec:'sales-invoice', entity:'header', "
        + "action:'registerPayment')\"}}";

    private JSONObject defaultsFor(SFEntity entity) throws Exception {
      McpConfigSections.resetForTests();
      McpConfigCache.invalidateAll();
      try {
        setupSpecLookup(mockSpec());
        setupEntityLookup(entity, mockTab());
        obContextMock.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
        supportMock.when(() -> McpToolRouterSupport.requireVerbNotHidden(any(), any(),
            anyString())).thenCallRealMethod();
        defaultsMock.when(() -> NeoDefaultsService.resolveDefaults(any(), isNull()))
            .thenReturn(NeoResponse.ok(new JSONObject().put("documentNo", "<auto>")));
        return router.route("etendo_defaults", buildCrudArgs(), READ_SCOPES);
      } finally {
        McpConfigCache.invalidateAll();
      }
    }

    @Test
    @DisplayName("ETP-5558: a create hidden by MCP_CONFIG.verbs answers the 405 etendo_create gives")
    void defaultsRefusedWhenCreateIsHidden() throws Exception {
      SFEntity entity = mockEntity();
      when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(HIDE_CREATE);
      when(entity.isPost()).thenReturn(true);

      JSONObject result = defaultsFor(entity);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject expected = McpRoutingException.verbHidden(SPEC_NAME, ENTITY_NAME, "POST",
          "Payments are registered from the invoice",
          "etendo_action(spec:'sales-invoice', entity:'header', action:'registerPayment')")
          .toEnvelope();
      JSONObject actual = new JSONObject(text);
      assertEquals(405, actual.getInt(McpConstants.KEY_STATUS));
      assertEquals("method_not_allowed", actual.getString(McpConstants.KEY_ERROR));
      assertEquals(expected.getString(McpConstants.KEY_DETAIL),
          actual.getString(McpConstants.KEY_DETAIL));
      assertEquals(expected.getString(McpConstants.KEY_HINT),
          actual.getString(McpConstants.KEY_HINT));
      assertTrue(actual.getString(McpConstants.KEY_DETAIL)
          .contains("Payments are registered from the invoice"), text);
      assertTrue(actual.getString(McpConstants.KEY_HINT).contains("registerPayment"), text);
      defaultsMock.verify(() -> NeoDefaultsService.resolveDefaults(any(), any()), never());
    }

    @Test
    @DisplayName("ETP-5558: a raw ISPOST off with no verbs section keeps the old etendo_defaults")
    void defaultsUnchangedWhenOnlyTheFlagIsOff() throws Exception {
      SFEntity entity = mockEntity();
      when(entity.isPost()).thenReturn(false);

      JSONObject result = defaultsFor(entity);

      assertFalse(result.has("isError"), result.toString());
      defaultsMock.verify(() -> NeoDefaultsService.resolveDefaults(any(), isNull()));
      supportMock.verify(() -> McpToolRouterSupport.requireVerbNotHidden(any(), any(),
          eq("POST")));
      supportMock.verify(() -> McpToolRouterSupport.requireMethodEnabled(any(), any(),
          anyString()), never());
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
    @DisplayName("_indentResponse never reaches the process as a parameter (IMP-53)")
    void processToolNeverReceivesIndentResponse() throws Exception {
      SFSpec spec = mockSpec();
      when(spec.getSpecType()).thenReturn("P");
      setupSpecLookup(spec);
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(true);
      JSONObject processBody = new JSONObject();
      processBody.put("result", "completed");
      // Two keys: jettison renders a one-key object on a single line even when indenting.
      processBody.put("message", "done");
      ArgumentCaptor<JSONObject> paramsCaptor = ArgumentCaptor.forClass(JSONObject.class);
      processMock.when(() -> NeoProcessService.executeProcess(eq(adProcess),
          paramsCaptor.capture())).thenReturn(NeoResponse.ok(processBody));

      JSONObject parameters = new JSONObject();
      parameters.put("docAction", "CO");
      JSONObject args = new JSONObject();
      args.put("parameters", parameters);
      args.put("_indentResponse", true);
      JSONObject result = router.route("complete_order", args, PROCESS_SCOPES);

      assertFalse(result.has("isError"), result.toString());
      assertFalse(paramsCaptor.getValue().has("_indentResponse"));
      assertEquals("CO", paramsCaptor.getValue().getString("docAction"));
      // A JSON NeoResponse body is a JSON tool result, so the flag applies to it.
      assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("\n"));
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

      // IMP-19: the handler must also declare a report contract. An empty declaration is a
      // valid one — "this report takes no inputs" — and is what keeps this vector callable.
      NeoHandler handler = mock(NeoHandler.class);
      when(handler.reportParameters()).thenReturn(Optional.of(List.of()));
      when(handler.reportFormats()).thenReturn(List.of("json"));
      when(handler.handle(any(NeoContext.class))).thenReturn(handlerResponse);

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        // ETP-5415: resolution moved onto NeoExtensionDispatcher, which for the MCP channel asks
        // NeoHandlerLookup.byQualifier; ETP-5588: the report entity is the first one
        // NeoHandlerLookup.hasCustomization accepts. The assertions below are unchanged.
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);
        lookupMock.when(() -> NeoHandlerLookup.hasCustomization(reportEntity)).thenReturn(true);
        hookMock.when(() -> McpHookExecutor.neoResponseToMcpResult(any()))
            .thenCallRealMethod();

        JSONObject result = router.route("generate_invoice_report", null, REPORT_SCOPES);

        assertFalse(result.has("isError") && result.getBoolean("isError"));
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue(text.contains("\"rows\""));
      }
    }

    // ── The declared contract gates and validates the call (IMP-19) ──────

    /**
     * The defect that hid five unusable tools: a {@code Java_Qualifier} means the handler serves
     * the entity, not that it generates reports. Five of the eight published {@code generate_*}
     * tools were UI handlers dispatching on an {@code action} query param, so the report route —
     * a POST with no query params — could only ever get 405 or 400 out of them. A handler that
     * declares no report contract must therefore read as non-callable, exactly like no handler
     * at all.
     */
    @Test
    @DisplayName("handler that declares no report contract is non-callable")
    void handlerWithoutContractIsNotCallable() throws Exception {
      SFSpec spec = mockReportSpec();
      setupSpecLookup(spec);

      SFEntity reportEntity = mock(SFEntity.class);
      when(reportEntity.getJavaQualifier()).thenReturn("financialAccountsPageHandler");
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
          .thenReturn(List.of(reportEntity));

      // Default reportParameters() — Optional.empty(): "I am not a report generator".
      NeoHandler handler = mock(NeoHandler.class);
      when(handler.reportParameters()).thenReturn(Optional.empty());

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        // ETP-5415: resolution moved onto NeoExtensionDispatcher, which for the MCP channel asks
        // NeoHandlerLookup.byQualifier; ETP-5588: the report entity is the first one
        // NeoHandlerLookup.hasCustomization accepts. The assertions below are unchanged.
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);
        lookupMock.when(() -> NeoHandlerLookup.hasCustomization(reportEntity)).thenReturn(true);

        JSONObject result = router.route("generate_invoice_report", null, REPORT_SCOPES);

        JSONObject body = new JSONObject(
            result.getJSONArray("content").getJSONObject(0).getString("text"));
        assertFalse(body.getBoolean("callable"));
        assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, body.getString("status"));
      }
      // The point of the gate: the handler is never asked to serve a request it cannot answer.
      verify(handler, never()).handle(any(NeoContext.class));
    }

    @Test
    @DisplayName("missing required parameter is rejected before the handler runs")
    void missingRequiredParameterIsRejected() throws Exception {
      NeoHandler handler = contractHandler(List.of(
          NeoReportParam.required("dateFrom", NeoReportParam.TYPE_DATE, "Start."),
          NeoReportParam.required("dateTo", NeoReportParam.TYPE_DATE, "End.")));
      SFEntity reportEntity = setupContractSpec();

      JSONObject args = new JSONObject();
      JSONObject params = new JSONObject();
      params.put("dateFrom", "2026-01-01");
      args.put("parameters", params);

      JSONObject error = routeReportWith(handler, reportEntity, args);

      assertTrue(error.getBoolean("isError"));
      JSONObject body = new JSONObject(
          error.getJSONArray("content").getJSONObject(0).getString("text"));
      assertEquals(422, body.getInt("status"));
      assertEquals("validation_error", body.getString("error"));
      // Names the one that is missing, and only that one.
      JSONArray missing = body.getJSONArray("missingParameters");
      assertEquals(1, missing.length());
      assertEquals("dateTo", missing.getString(0));
      verify(handler, never()).handle(any(NeoContext.class));
    }

    /**
     * An empty string is as absent as a missing key: every handler reads these with
     * {@code optString(name, "")} and treats {@code ""} as unset, so accepting it would only
     * move the failure back into the handler's own ad-hoc error path.
     */
    @Test
    @DisplayName("blank required parameter counts as missing")
    void blankRequiredParameterCountsAsMissing() throws Exception {
      NeoHandler handler = contractHandler(List.of(
          NeoReportParam.required("dateFrom", NeoReportParam.TYPE_DATE, "Start.")));
      SFEntity reportEntity = setupContractSpec();

      JSONObject args = new JSONObject();
      JSONObject params = new JSONObject();
      params.put("dateFrom", "   ");
      args.put("parameters", params);

      JSONObject error = routeReportWith(handler, reportEntity, args);

      assertTrue(error.getBoolean("isError"));
      JSONObject body = new JSONObject(
          error.getJSONArray("content").getJSONObject(0).getString("text"));
      assertEquals("dateFrom", body.getJSONArray("missingParameters").getString(0));
    }

    /**
     * The format argument used to be declared ("pdf, xlsx, csv") and never read, so a request
     * for a PDF was answered with JSON and nothing said the format had been ignored. It is now
     * checked against what the handler serves.
     */
    @Test
    @DisplayName("unsupported format is rejected and names what is served")
    void unsupportedFormatIsRejected() throws Exception {
      NeoHandler handler = contractHandler(List.of());
      SFEntity reportEntity = setupContractSpec();

      JSONObject args = new JSONObject();
      args.put("format", "pdf");

      JSONObject error = routeReportWith(handler, reportEntity, args);

      assertTrue(error.getBoolean("isError"));
      JSONObject body = new JSONObject(
          error.getJSONArray("content").getJSONObject(0).getString("text"));
      assertEquals(422, body.getInt("status"));
      assertEquals("format", body.getString("field"));
      assertEquals("json", body.getJSONArray("supportedFormats").getString(0));
      verify(handler, never()).handle(any(NeoContext.class));
    }

    @Test
    @DisplayName("declared format and satisfied requirements reach the handler")
    void satisfiedContractReachesHandler() throws Exception {
      NeoHandler handler = contractHandler(List.of(
          NeoReportParam.required("dateFrom", NeoReportParam.TYPE_DATE, "Start.")));
      SFEntity reportEntity = setupContractSpec();

      JSONObject args = new JSONObject();
      JSONObject params = new JSONObject();
      params.put("dateFrom", "2026-01-01");
      args.put("parameters", params);
      args.put("format", "JSON"); // case-insensitive: the same format, spelled loudly

      JSONObject result = routeReportWith(handler, reportEntity, args);

      assertFalse(result.has("isError") && result.getBoolean("isError"));
      assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text")
          .contains("\"rows\""));
    }

    /** A handler declaring the given parameters, JSON output, and a trivial successful run. */
    private NeoHandler contractHandler(List<NeoReportParam> params) throws Exception {
      JSONObject reportData = new JSONObject();
      reportData.put("rows", 3);
      NeoHandler handler = mock(NeoHandler.class);
      when(handler.reportParameters()).thenReturn(Optional.of(params));
      when(handler.reportFormats()).thenReturn(List.of("json"));
      when(handler.handle(any(NeoContext.class))).thenReturn(NeoResponse.ok(reportData));
      return handler;
    }

    private SFEntity setupContractSpec() {
      SFSpec spec = mockReportSpec();
      setupSpecLookup(spec);
      SFEntity reportEntity = mock(SFEntity.class);
      when(reportEntity.getName()).thenReturn("tax");
      when(reportEntity.getJavaQualifier()).thenReturn("taxReportHandler");
      supportMock.when(() -> McpToolRouterSupport.listIncludedEntities(SPEC_ID))
          .thenReturn(List.of(reportEntity));
      return reportEntity;
    }

    private JSONObject routeReportWith(NeoHandler handler, SFEntity reportEntity, JSONObject args)
        throws Exception {
      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        // ETP-5415: resolution moved onto NeoExtensionDispatcher, which for the MCP channel asks
        // NeoHandlerLookup.byQualifier; ETP-5588: the report entity is the first one
        // NeoHandlerLookup.hasCustomization accepts. The assertions below are unchanged.
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);
        lookupMock.when(() -> NeoHandlerLookup.hasCustomization(reportEntity)).thenReturn(true);
        hookMock.when(() -> McpHookExecutor.neoResponseToMcpResult(any()))
            .thenCallRealMethod();
        return router.route("generate_invoice_report", args, REPORT_SCOPES);
      }
    }
  }

  // ── Spec/entity resolution ────────────────────────────────────────────

  @Nested
  @DisplayName("route — spec/entity resolution errors")
  class ResolutionErrorTests {

    @Test
    @DisplayName("unknown spec returns a 404 not_found envelope pointing at etendo_discover")
    void unknownSpecReturnsError() throws Exception {
      // ETP-4793 / IMP-17: spec resolution throws McpRoutingException, which route renders as the
      // IMP-5 envelope instead of the old bare prose line. No `available` list on purpose — the
      // catalog can hold dozens of specs, so the hint routes to etendo_discover instead (ACE).
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
          .thenThrow(McpRoutingException.specNotFound(SPEC_NAME));

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      JSONObject envelope = new JSONObject(
          result.getJSONArray("content").getJSONObject(0).getString("text"));
      assertEquals(404, envelope.getInt("status"));
      assertEquals("not_found", envelope.getString("error"));
      assertEquals("spec", envelope.getString("field"));
      assertTrue(envelope.getString("detail").contains(SPEC_NAME));
      assertTrue(envelope.getString("hint").contains("etendo_discover"));
      assertFalse(envelope.has("available"));
    }

    @Test
    @DisplayName("unknown entity returns a 404 not_found envelope carrying the valid entity names")
    void unknownEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec(); // type "W"
      setupSpecLookup(spec);

      // AC-3: for a type-W spec the shared guard delegates to findIncludedEntity for a genuinely
      // wrong entity name. Run the real helper and let the delegate throw, exercising the W branch
      // end-to-end. ETP-4793 / IMP-17: the miss now travels as an envelope whose `available` list is
      // the whole answer to the agent's next question (evidence B20 was exactly this call).
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
          .thenThrow(McpRoutingException.entityNotFound(ENTITY_NAME, SPEC_NAME,
              List.of("orderHeader", "orderLines")));

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      JSONObject envelope = new JSONObject(contentText(result));
      assertEquals(404, envelope.getInt("status"));
      assertEquals("not_found", envelope.getString("error"));
      assertEquals("entity", envelope.getString("field"));
      assertEquals("etendo_list", envelope.getString("tool"));
      JSONArray available = envelope.getJSONArray("available");
      assertEquals(2, available.length());
      assertEquals("orderHeader", available.getString(0));
    }

    /**
     * ETP-5714: the tool description used to give 'Header' as the entity example while the catalogue
     * spells it 'header', so the first call of an agent that followed it failed. A miss on the
     * entity is retried ONCE with the spelling the spec uses, and the argument itself is rewritten
     * so every later consumer sees the canonical name.
     */
    @Test
    @DisplayName("an entity typed with other capitals is routed as the spec spells it")
    void entityTypedWithOtherCapitalsIsRetriedCanonically() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
          .thenThrow(McpRoutingException.entityNotFound("x", SPEC_NAME, List.of("header")));
      JSONObject args = new JSONObject().put("spec", SPEC_NAME).put("entity", "Header");

      try (MockedStatic<McpEntityNames> names = mockStatic(McpEntityNames.class)) {
        names.when(() -> McpEntityNames.canonicalName(SPEC_NAME, "Header")).thenReturn("header");

        JSONObject result = router.route("etendo_list", args, READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
      }
      assertEquals("header", args.getString("entity"), "the argument is rewritten, not just shadowed");
      supportMock.verify(() -> McpToolRouterSupport.findIncludedEntity(anyString(),
          org.mockito.ArgumentMatchers.eq("Header")));
      supportMock.verify(() -> McpToolRouterSupport.findIncludedEntity(anyString(),
          org.mockito.ArgumentMatchers.eq("header")));
    }

    @Test
    @DisplayName("an entity no capitalisation explains is NOT retried and keeps the typed name")
    void unknownEntityIsNotRetried() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
          .thenThrow(McpRoutingException.entityNotFound("Nope", SPEC_NAME, List.of("header")));
      JSONObject args = new JSONObject().put("spec", SPEC_NAME).put("entity", "Nope");

      try (MockedStatic<McpEntityNames> names = mockStatic(McpEntityNames.class)) {
        JSONObject result = router.route("etendo_list", args, READ_SCOPES);

        assertEquals(404, new JSONObject(contentText(result)).getInt("status"));
        names.verify(() -> McpEntityNames.canonicalName(SPEC_NAME, "Nope"));
      }
      assertEquals("Nope", args.getString("entity"));
      supportMock.verify(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()),
          org.mockito.Mockito.times(1));
    }

    @Test
    @DisplayName("a missing spec is not an entity miss: no canonical lookup happens")
    void missingSpecIsNotRetried() throws Exception {
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString()))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString()))
          .thenThrow(McpRoutingException.specNotFound(SPEC_NAME));

      try (MockedStatic<McpEntityNames> names = mockStatic(McpEntityNames.class)) {
        router.route("etendo_list", buildCrudArgs(), READ_SCOPES);

        names.verifyNoInteractions();
      }
    }

    /**
     * An entity with neither a tab nor a handler is unserviceable, and ETP-5405 made the router
     * say so as a routing failure instead of a server fault.
     *
     * <p>{@code McpWriteRequestSupport.getAdTabOrThrow} used to throw a bare
     * {@code IllegalArgumentException}, which the catch-all rendered as
     * {@code 500 server_error "No AD_Tab linked to entity: header"}. The blind-agent run on
     * ETP-5405 showed the cost: the agent read the 500 and its "re-sending will not help" hint as
     * proof the capability was broken and abandoned the spec. It is now a 422
     * {@code validation_error} naming the configuration as the fault, which is the branch this
     * mock produces — the entity declares no {@code Java_Qualifier}, so
     * {@code NeoHandlerLookup.forEntity} finds nothing and {@code hasHandler} is
     * false.</p>
     */
    @Test
    @DisplayName("entity with neither AD_Tab nor handler returns a 422 routing envelope")
    void entityWithoutTabReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);

      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenReturn(entity);
      when(entity.getADTab()).thenReturn(null);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      JSONObject envelope = new JSONObject(contentText(result));
      assertNoTabEnvelope(envelope);
      assertEquals("etendo_list", envelope.getString("tool"));
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
      JSONObject result = router.route("etendo_defaults", args, READ_SCOPES);

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
      JSONObject result = router.route("etendo_defaults", args, READ_SCOPES);

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
      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

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
      JSONObject result = router.route("etendo_list", args, READ_SCOPES);

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

  // ── etendo_vector_search ──────────────────────────────────────────────────

  @Test
  @DisplayName("route — etendo_vector_search validates required arguments")
  void vectorSearchMissingArgumentsReturnsErrorContent() throws Exception {
    JSONObject result = router.route("etendo_vector_search", new JSONObject(), READ_SCOPES);
    assertTrue(result.optBoolean("isError"));
    assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text")
        .contains("query and either targets or namespaces are required"));
  }

  // ── etendo_vector_search — omitted-targets substitution (IMP-41 follow-up) ─

  @Nested
  @DisplayName("route — etendo_vector_search omitted-targets substitution")
  class VectorSearchTargetSubstitutionTests {

    @Test
    @DisplayName("omitted targets are substituted with the full authorized list")
    void omittedTargetsSubstituteAuthorizedList() throws Exception {
      JSONObject args = new JSONObject();
      args.put("query", "quotation");

      try (MockedStatic<NeoVectorSearchEndpoint> endpointStatics =
               mockStatic(NeoVectorSearchEndpoint.class);
           MockedConstruction<NeoVectorSearchEndpoint> construction =
               mockConstruction(NeoVectorSearchEndpoint.class, (endpointMock, context) ->
                   when(endpointMock.handle(any(), any(), any(), any(), any(), any(), any()))
                       .thenReturn(NeoResponse.ok(new JSONObject().put("items", new JSONArray()))))) {
        endpointStatics.when(NeoVectorSearchEndpoint::authorizedTargetKeys)
            .thenReturn(Optional.of(List.of("sales-quotation", "purchase-order")));

        JSONObject result = router.route("etendo_vector_search", args, READ_SCOPES);

        assertFalse(result.optBoolean("isError"));
        assertEquals(1, construction.constructed().size());
        NeoVectorSearchEndpoint endpoint = construction.constructed().get(0);
        verify(endpoint).handle(eq("quotation"), isNull(), eq("sales-quotation,purchase-order"),
            isNull(), isNull(), isNull(), isNull());
      }
    }

    @Test
    @DisplayName("no readable target returns the dedicated refusal, not a generic 400")
    void noReadableTargetsReturnsDedicatedRefusal() throws Exception {
      JSONObject args = new JSONObject();
      args.put("query", "quotation");

      try (MockedStatic<NeoVectorSearchEndpoint> endpointStatics =
               mockStatic(NeoVectorSearchEndpoint.class);
           MockedConstruction<NeoVectorSearchEndpoint> construction =
               mockConstruction(NeoVectorSearchEndpoint.class)) {
        endpointStatics.when(NeoVectorSearchEndpoint::authorizedTargetKeys)
            .thenReturn(Optional.of(Collections.emptyList()));

        JSONObject result = router.route("etendo_vector_search", args, READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue(text.contains("no_searchable_vector_targets"), "body must name the dedicated refusal code");
        assertFalse(text.contains("query and either targets or namespaces are required"),
            "must not read as the generic missing-parameter 400");
        assertTrue(construction.constructed().isEmpty(),
            "the endpoint must not even be constructed once the role has nothing to search");
      }
    }

    @Test
    @DisplayName("unreadable target catalog leaves targets null and defers to the endpoint")
    void unreadableCatalogDefersToEndpoint() throws Exception {
      JSONObject args = new JSONObject();
      args.put("query", "quotation");

      try (MockedStatic<NeoVectorSearchEndpoint> endpointStatics =
               mockStatic(NeoVectorSearchEndpoint.class)) {
        endpointStatics.when(NeoVectorSearchEndpoint::authorizedTargetKeys)
            .thenReturn(Optional.empty());

        // No mockConstruction here: the real endpoint runs, exactly as it did before this
        // feature existed. With no substitution, both namespaces and targets stay empty, so the
        // endpoint answers its own bad-request 400 without touching any DB.
        JSONObject result = router.route("etendo_vector_search", args, READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue(text.contains("query and either targets or namespaces are required"));
        assertFalse(text.contains("no_searchable_vector_targets"),
            "a null catalog must not be confused with an empty (all-denied) one");
      }
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
      // ETP-5306: the docs body is returned verbatim, preceded by the record-reference note —
      // the construction rule that replaced the per-row `$ref` field.
      assertTrue(text.endsWith("# Finance docs\nbody text"));
      assertTrue(text.startsWith(McpConstants.RECORD_REF_NOTE));
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
      assertNull(tokenCaptor.getValue());
    }
  }

  // ── etendo_action ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_action")
  class ActionTests {

    private static final Set<String> ACTION_SCOPES = Set.of("neo:process");
    private static final String RECORD_ID = "record-001";
    private static final String ACTION_NAME = "Processed";
    /**
     * ETP-5415: {@code NeoExtensionDispatcher} answers "no customization" for a blank
     * {@code Java_Qualifier} before it consults {@code NeoHandlerLookup}, so an entity mock has to
     * carry one for the stubbed handler to be reached.
     */
    private static final String HANDLER_QUALIFIER = "action-test-handler";

    /**
     * ETP-5558's record-ownership guard runs before every action. Unstubbed it reaches the real
     * {@code ModelProvider}, which initializes against the database: these tests passed only while
     * a local PostgreSQL was up, and failed with a 404 instead of the action result when it was not.
     * The guard has its own coverage in {@code NeoActionRecordGuardTest}; here it admits the record.
     */
    private MockedStatic<NeoActionRecordGuard> recordGuardMock;

    @BeforeEach
    void setupActionSupport() {
      recordGuardMock = mockStatic(NeoActionRecordGuard.class);
      recordGuardMock.when(() -> NeoActionRecordGuard.refusalFor(any(), any())).thenReturn(null);
      supportMock.when(() -> McpToolRouterSupport.mapNeoResponseToActionResult(any()))
          .thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.resolveStatusFromErrorBody(any()))
          .thenCallRealMethod();
      // ETP-5415: a handler's pre-hook error now reaches the caller through
      // McpHookExecutor.neoResponseToMcpResult, which normalizes it with toMcpHandlerError. With the
      // class statically mocked that method returned null and the handler's message was lost; here
      // the body is passed through so the test asserts on what the handler said.
      supportMock.when(() -> McpToolRouterSupport.toMcpHandlerError(any(), anyInt()))
          .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void closeRecordGuard() {
      if (recordGuardMock != null) {
        recordGuardMock.close();
      }
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
    @DisplayName("etendo_action routes and returns processResult:success")
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

      JSONObject result = router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("success", body.getString("processResult"));
      assertEquals("Process completed successfully", body.getString("processMessage"));
    }

    @Test
    @DisplayName("etendo_action surfaces processResult:error for error NeoResponse")
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

      JSONObject result = router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("error", body.getString("processResult"));
      assertTrue(body.getString("processMessage").contains("usableLifeMonths"));
    }

    @Test
    @DisplayName("etendo_action with missing action argument returns error")
    void actionMissingActionArgReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      args.put("entity", ENTITY_NAME);
      args.put("id", RECORD_ID);
      // "action" intentionally omitted

      JSONObject result = router.route("etendo_action", args, ACTION_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("action"));
    }

    @Test
    @DisplayName("etendo_action with null arguments returns error")
    void actionNullArgsReturnsError() throws Exception {
      JSONObject result = router.route("etendo_action", null, ACTION_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Missing arguments"));
    }

    @Test
    @DisplayName("etendo_action with warning NeoResponse returns success content with processResult:warning")
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

      JSONObject result = router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

      assertFalse(result.has("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      JSONObject body = new JSONObject(text);
      assertEquals("warning", body.getString("processResult"));
    }

    @Test
    @DisplayName("etendo_action runs the entity NeoHandler hooks around the button action")
    void actionRunsEntityHandlerHooks() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      when(entity.getJavaQualifier()).thenReturn(HANDLER_QUALIFIER);
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

      // ETP-5415: etendo_action dispatches through NeoExtensionDispatcher, which on the MCP channel
      // resolves via NeoHandlerLookup.byQualifier. Asserting on the handler itself rather than on
      // McpHookExecutor is also the better test: what matters is that the customization ran, not
      // which helper carried the call.
      NeoHandler handler = mock(NeoHandler.class);
      try (MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);

        router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

        // The REST action path wraps the button action in the entity's NeoHandler
        // (NeoSubEndpointDispatcher.handleHookedSubEndpoint with NeoEndpointType.ACTION),
        // exactly as etendo_create/etendo_update/etendo_delete already do on the MCP side. Without
        // the same wrapping here, completing a document over MCP silently skips handler
        // logic the UI executes — e.g. AbstractOrderHeaderHandler's pre-CO total-discount
        // line, or GlJournalHeaderHandler's interception of the contextless classic
        // dispatch that would otherwise NPE inside FIN_AddPaymentFromJournal (ETP-4285).
        verify(handler).handle(any(NeoContext.class));
        verify(handler).afterHandle(any(NeoContext.class));
      }
    }

    @Test
    @DisplayName("etendo_action builds an ACTION hook context carrying the action name")
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
        router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

        // ETP-5558: the method comes from the declared contract; an AD button has none and stays
        // on POST, exactly as before.
        hookMock.verify(() -> McpHookExecutor.buildActionHookContext(
            eq(SPEC_NAME), eq(ENTITY_NAME), eq(RECORD_ID), eq(ACTION_NAME),
            any(), eq(tab), eq(entity), eq("POST")));
      }
    }

    @Test
    @DisplayName("etendo_action pre-hook result short-circuits without firing the process")
    void actionPreHookShortCircuitsWithoutFiringTheProcess() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      when(entity.getJavaQualifier()).thenReturn(HANDLER_QUALIFIER);
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      // ETP-5415: etendo_action dispatches through NeoExtensionDispatcher, which on the MCP channel
      // resolves via NeoHandlerLookup.byQualifier. Asserting on the handler itself rather than on
      // McpHookExecutor is also the better test: what matters is that the customization ran, not
      // which helper carried the call.
      NeoHandler handler = mock(NeoHandler.class);
      when(handler.handle(any(NeoContext.class)))
          .thenReturn(NeoResponse.error(400, "Order has no lines"));

      try (MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);

        JSONObject result = router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

        assertTrue(result.getBoolean("isError"));
        assertTrue(contentText(result).contains("Order has no lines"));
        buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
            any(), any(), any(), any()), never());
      }
    }

    @Test
    @DisplayName("etendo_action forwards the caller's parameters object to the process")
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
      // the key etendo_schema advertises as 'actionParameter' (ETP-4285).
      JSONObject args = buildActionArgs();
      JSONObject parameters = new JSONObject();
      parameters.put("docAction", "CO");
      args.put("parameters", parameters);

      ArgumentCaptor<JSONObject> paramsCaptor = ArgumentCaptor.forClass(JSONObject.class);

      router.route("etendo_action", args, ACTION_SCOPES);

      buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
          eq(entity), eq(RECORD_ID), eq(ACTION_NAME), paramsCaptor.capture()));
      assertEquals("CO", paramsCaptor.getValue().getString("docAction"));
    }

    @Test
    @DisplayName("etendo_action post-hook result replaces the default action result")
    void actionPostHookReplacesResult() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      when(entity.getJavaQualifier()).thenReturn(HANDLER_QUALIFIER);
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      JSONObject responseBody = new JSONObject();
      responseBody.put("status", "success");
      buttonActionMock.when(() ->
          NeoButtonActionHelper.executeButtonActionCore(eq(entity), eq(RECORD_ID),
              eq(ACTION_NAME), any()))
          .thenReturn(NeoResponse.ok(responseBody));

      // ETP-5415: etendo_action dispatches through NeoExtensionDispatcher, which on the MCP channel
      // resolves via NeoHandlerLookup.byQualifier. Asserting on the handler itself rather than on
      // McpHookExecutor is also the better test: what matters is that the customization ran, not
      // which helper carried the call.
      JSONObject replacedBody = new JSONObject();
      replacedBody.put("processResult", "warning");

      NeoHandler handler = mock(NeoHandler.class);
      when(handler.handle(any(NeoContext.class))).thenReturn(null);
      when(handler.afterHandle(any(NeoContext.class))).thenReturn(NeoResponse.ok(replacedBody));

      try (MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);

        JSONObject result = router.route("etendo_action", buildActionArgs(), ACTION_SCOPES);

        assertTrue(contentText(result).contains("warning"));
      }
    }
  }

  // ── etendo_action_batch (ETP-5715) ────────────────────────────────────────

  @Nested
  @DisplayName("route — etendo_action_batch")
  class ActionBatchTests {

    private static final String ACTION_NAME = "documentAction";

    @BeforeEach
    void setupBatchSupport() {
      supportMock.when(() -> McpToolRouterSupport.mapNeoResponseToActionResult(any()))
          .thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.resolveStatusFromErrorBody(any()))
          .thenCallRealMethod();
      // the batch calls this one; with the support class mocked it must run for real
      supportMock.when(() -> McpToolRouterSupport.validateArgs(any(), any(String[].class)))
          .thenCallRealMethod();
      org.openbravo.model.ad.datamodel.Column button =
          mock(org.openbravo.model.ad.datamodel.Column.class);
      when(button.getDBColumnName()).thenReturn("DocAction");
      buttonActionMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
          .thenReturn(button);
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);
      setupEntityLookup(entity, mockTab());
    }

    private JSONObject batchArgs(String... ids) throws Exception {
      JSONObject args = new JSONObject();
      args.put("spec", SPEC_NAME);
      args.put("entity", ENTITY_NAME);
      args.put("action", ACTION_NAME);
      args.put("ids", new JSONArray(java.util.Arrays.asList(ids)));
      args.put("parameters", new JSONObject().put("docAction", "CO"));
      return args;
    }

    private NeoResponse processOk(String message) throws Exception {
      return NeoResponse.ok(new JSONObject().put("status", "success").put("message", message));
    }

    @Test
    @DisplayName("completes three documents through the etendo_action path, one process call each")
    void completesThreeDocuments() throws Exception {
      buttonActionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), anyString(), any()))
          .thenAnswer(inv -> processOk("completed " + inv.getArgument(1)));

      JSONObject result = router.route("etendo_action_batch", batchArgs("o1", "o2", "o3"),
          WRITE_SCOPES);

      assertFalse(result.has("isError"));
      JSONObject answer = new JSONObject(contentText(result));
      assertEquals(3, answer.getJSONObject("summary").getInt("succeeded"));
      assertEquals("completed o2", answer.getJSONArray("results").getJSONObject(1)
          .getJSONObject("result").getString("processMessage"));
      buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), eq(ACTION_NAME), any()), org.mockito.Mockito.times(3));
      verify(mockOBDal, org.mockito.Mockito.times(3)).commitAndClose();
    }

    @Test
    @DisplayName("one document the core refuses does not stop the others, and is rolled back")
    void oneRefusedAmongThree() throws Exception {
      buttonActionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), anyString(), any()))
          .thenAnswer(inv -> "o2".equals(inv.getArgument(1))
              ? new NeoResponse(400, new JSONObject().put("status", "error")
                  .put("message", "Document already completed"))
              : processOk("completed"));

      JSONObject result = router.route("etendo_action_batch", batchArgs("o1", "o2", "o3"),
          WRITE_SCOPES);

      JSONObject answer = new JSONObject(contentText(result));
      assertEquals(2, answer.getJSONObject("summary").getInt("succeeded"));
      assertEquals(1, answer.getJSONObject("summary").getInt("failed"));
      JSONObject bad = answer.getJSONArray("results").getJSONObject(1);
      assertFalse(bad.getBoolean("ok"));
      assertEquals("Document already completed",
          bad.getJSONObject("error").getString("processMessage"));
      verify(mockOBDal).rollbackAndClose();
      verify(mockOBDal, org.mockito.Mockito.times(2)).commitAndClose();
    }

    @Test
    @DisplayName("an exception thrown by the process answers that document only")
    void exceptionAnswersOnlyThatDocument() throws Exception {
      buttonActionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), anyString(), any()))
          .thenAnswer(inv -> {
            if ("o1".equals(inv.getArgument(1))) {
              throw new IllegalStateException("process blew up");
            }
            return processOk("completed");
          });

      JSONObject answer = new JSONObject(contentText(
          router.route("etendo_action_batch", batchArgs("o1", "o2"), WRITE_SCOPES)));

      assertFalse(answer.getJSONArray("results").getJSONObject(0).getBoolean("ok"));
      assertTrue(answer.getJSONArray("results").getJSONObject(1).getBoolean("ok"));
    }

    @Test
    @DisplayName("a role without access to the spec is refused and no process runs")
    void unauthorizedSpecRunsNothing() throws Exception {
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
          .thenReturn(false);

      JSONObject result = router.route("etendo_action_batch", batchArgs("o1", "o2"), WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      assertTrue(contentText(result).contains("Access denied"));
      buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(), anyString(), anyString(), any()), never());
    }

    @Test
    @DisplayName("the argument guard is armed for the new tool: 'id' is refused, 'ids' is the name")
    void singularIdIsRefused() throws Exception {
      JSONObject args = batchArgs("o1");
      args.remove("ids");
      args.put("id", "o1");

      JSONObject result = router.route("etendo_action_batch", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      assertTrue(contentText(result).contains("'id'"), contentText(result));
      buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(), anyString(), anyString(), any()), never());
    }

    @Test
    @DisplayName("repeated ids reach the caller as a refusal, never a double completion")
    void repeatedIdsRunNothing() throws Exception {
      JSONObject result = router.route("etendo_action_batch", batchArgs("o1", "o1"), WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      assertEquals("ids", new JSONObject(contentText(result)).getString("field"));
      buttonActionMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(), anyString(), anyString(), any()), never());
    }
  }

  // ── etendo_action on the document-action button: an id that is no row (ETP-5715 W1) ──

  @Nested
  @DisplayName("route — etendo_action on the document-action button")
  class DocumentActionButtonTests {

    private static final String INVOICE_ID = "inv-001";
    private static final String DAL_ENTITY = "Invoice";
    private final List<String> events = new ArrayList<>();
    private MockedStatic<McpDeclaredActions> declaredMock;
    private MockedStatic<org.openbravo.base.model.ModelProvider> modelMock;
    private boolean posted = true;

    @BeforeEach
    void setUp() throws Exception {
      supportMock.when(() -> McpToolRouterSupport.mapNeoResponseToActionResult(any()))
          .thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.resolveStatusFromErrorBody(any()))
          .thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.validateArgs(any(), any(String[].class)))
          .thenCallRealMethod();
      supportMock.when(() -> McpToolRouterSupport.toMcpHandlerError(any(), anyInt()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      when(entity.getETGOSFSpec()).thenReturn(spec);
      Tab tab = mockTab();
      setupSpecLookup(spec);
      setupEntityLookup(entity, tab);

      org.openbravo.model.ad.datamodel.Column docAction =
          mock(org.openbravo.model.ad.datamodel.Column.class);
      when(docAction.getDBColumnName()).thenReturn("DocAction");
      buttonActionMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
          .thenReturn(docAction);

      // the entity declares unpost; every other action is judged as an AD button
      com.etendoerp.go.schemaforge.util.NeoActionContract unpostContract =
          mock(com.etendoerp.go.schemaforge.util.NeoActionContract.class);
      when(unpostContract.getName()).thenReturn("unpost");
      when(unpostContract.getHttpMethod()).thenReturn("POST");
      declaredMock = mockStatic(McpDeclaredActions.class);
      declaredMock.when(() -> McpDeclaredActions.precheck(any(), anyString(), any()))
          .thenAnswer(inv -> "unpost".equals(inv.getArgument(1)) ? unpostContract : null);
      declaredMock.when(() -> McpDeclaredActions.customizationNameOf(any(), anyString()))
          .thenAnswer(inv -> inv.getArgument(1));

      org.openbravo.base.model.Entity dal = mock(org.openbravo.base.model.Entity.class);
      when(dal.getName()).thenReturn(DAL_ENTITY);
      org.openbravo.base.model.Property postedProperty =
          mock(org.openbravo.base.model.Property.class);
      when(postedProperty.getName()).thenReturn("posted");
      when(dal.getPropertyByColumnName("Posted", false)).thenReturn(postedProperty);
      org.openbravo.base.model.ModelProvider provider =
          mock(org.openbravo.base.model.ModelProvider.class);
      when(provider.getEntityByTableId(anyString())).thenReturn(dal);
      modelMock = mockStatic(org.openbravo.base.model.ModelProvider.class);
      modelMock.when(org.openbravo.base.model.ModelProvider::getInstance).thenReturn(provider);

      when(mockOBDal.get(eq(DAL_ENTITY), anyString())).thenAnswer(inv -> {
        if ("ghost".equals(inv.getArgument(1))) {
          return null;
        }
        org.openbravo.base.structure.BaseOBObject row =
            mock(org.openbravo.base.structure.BaseOBObject.class);
        when(row.get("posted")).thenReturn(posted ? "Y" : "N");
        return row;
      });
      org.mockito.Mockito.doAnswer(inv -> events.add("commit")).when(mockOBDal).commitAndClose();
      org.mockito.Mockito.doAnswer(inv -> events.add("rollback")).when(mockOBDal)
          .rollbackAndClose();
    }

    @AfterEach
    void closeStatics() {
      declaredMock.close();
      modelMock.close();
    }

    private JSONObject reactivateArgs(String docAction) throws Exception {
      return new JSONObject().put("spec", SPEC_NAME).put("entity", ENTITY_NAME)
          .put("id", INVOICE_ID).put("action", "documentAction")
          .put("parameters", new JSONObject().put("docAction", docAction));
    }

    /** The process answers: refuse the action named in {@code failing}, accept everything else. */
    private void processAnswers(String failing) {
      buttonActionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            String action = inv.getArgument(2);
            events.add("run:" + action);
            return action.equals(failing)
                ? new NeoResponse(422, new JSONObject().put("status", "error")
                    .put("message", action + " refused"))
                : NeoResponse.ok(new JSONObject().put("status", "success")
                    .put("message", action + " done"));
          });
    }

    @Test
    @DisplayName("W1: a DocAction on an id that is not a row answers the same 404 as a foreign id")
    void missingDocumentIsARecordNotFound() throws Exception {
      processAnswers(null);
      JSONObject args = reactivateArgs("CO").put("id", "ghost");

      JSONObject result = router.route("etendo_action", args, WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      assertTrue(contentText(result).contains("Record not found"), contentText(result));
      assertEquals(List.of(), events, "no process may run for an id that is not a document");
    }

    @Test
    @DisplayName("W1: in a batch a missing id is a per-document 404 and the others still run")
    void batchAnswersMissingDocumentPerDocument() throws Exception {
      processAnswers(null);
      JSONObject args = new JSONObject().put("spec", SPEC_NAME).put("entity", ENTITY_NAME)
          .put("action", "documentAction").put("ids", new JSONArray(List.of("ghost", INVOICE_ID)))
          .put("parameters", new JSONObject().put("docAction", "CO"));

      JSONObject result = router.route("etendo_action_batch", args, WRITE_SCOPES);

      JSONObject answer = new JSONObject(contentText(result));
      assertEquals(1, answer.getJSONObject("summary").getInt("failed"));
      JSONObject ghost = answer.getJSONArray("results").getJSONObject(0);
      assertFalse(ghost.getBoolean("ok"));
      assertTrue(ghost.toString().contains("Record not found"), ghost.toString());
      assertTrue(answer.getJSONArray("results").getJSONObject(1).getBoolean("ok"));
      assertEquals(List.of("run:documentAction"), events.stream()
          .filter(e -> e.startsWith("run:")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("W1: an action that is not the document action keeps running on an unknown id")
    void missingIdOfAnotherActionIsUntouched() throws Exception {
      org.openbravo.model.ad.datamodel.Column other =
          mock(org.openbravo.model.ad.datamodel.Column.class);
      when(other.getDBColumnName()).thenReturn("Processed");
      buttonActionMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
          .thenReturn(other);
      processAnswers(null);

      router.route("etendo_action", reactivateArgs("CO").put("id", "ghost"), WRITE_SCOPES);

      assertEquals(List.of("run:documentAction"), events);
    }

    @Test
    @DisplayName("W2: an exception after a committed un-post is reported with steps and partial")
    void exceptionAfterCommittedUnpostIsReported() throws Exception {
      // ETP-5684: the un-post runs server-side, inside the customization, before the button. Drive
      // the real ReactivationUnpost.unpostFirst on the action's own context, then let the button throw.
      com.etendoerp.go.schemaforge.handlers.DocumentPostingService posting =
          mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
      when(posting.unpost(anyString(), anyString())).thenAnswer(inv -> {
        events.add("unpost");
        return new com.etendoerp.go.schemaforge.handlers.DocumentPostingService.PostResult(
            true, "Unposted", null, null);
      });
      buttonActionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), anyString(), any())).thenAnswer(inv -> {
            events.add("run:" + inv.getArgument(2));
            throw new IllegalStateException("boom");
          });

      JSONObject result;
      try (MockedStatic<McpHookExecutor> hooks =
          mockStatic(McpHookExecutor.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
        hooks.when(() -> McpHookExecutor.buildActionHookContext(anyString(), anyString(),
            anyString(), anyString(), any(), any(), any(), anyString())).thenAnswer(inv -> {
              com.etendoerp.go.schemaforge.NeoContext ctx =
                  (com.etendoerp.go.schemaforge.NeoContext) inv.callRealMethod();
              assertNull(com.etendoerp.go.schemaforge.handlers.ReactivationUnpost
                  .unpostFirst(ctx, posting));
              return ctx;
            });
        result = router.route("etendo_action", reactivateArgs("RE"), WRITE_SCOPES);
      }

      assertTrue(result.getBoolean("isError"));
      JSONObject body = new JSONObject(contentText(result));
      assertEquals("[\"unpost\"]", body.getJSONArray("steps").toString());
      assertTrue(body.getString("partial").contains("not undone"), body.toString());
      assertEquals(List.of("unpost", "run:documentAction", "rollback"), events);
    }

    @Test
    @DisplayName("W2: an exception when no un-post ran propagates exactly as before")
    void exceptionWithoutUnpostIsUnchanged() throws Exception {
      posted = false;
      buttonActionMock.when(() -> NeoButtonActionHelper.executeButtonActionCore(
          any(SFEntity.class), anyString(), anyString(), any()))
          .thenThrow(new IllegalStateException("boom"));

      JSONObject result = router.route("etendo_action", reactivateArgs("RE"), WRITE_SCOPES);

      assertTrue(result.getBoolean("isError"));
      assertFalse(contentText(result).contains("steps"), contentText(result));
    }

    @Test
    @DisplayName("an action that is not the document action is unchanged even with RE")
    void otherButtonIsUnchanged() throws Exception {
      org.openbravo.model.ad.datamodel.Column other =
          mock(org.openbravo.model.ad.datamodel.Column.class);
      when(other.getDBColumnName()).thenReturn("Processed");
      buttonActionMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
          .thenReturn(other);
      processAnswers(null);

      router.route("etendo_action", reactivateArgs("RE"), WRITE_SCOPES);

      assertEquals(List.of("run:documentAction"), events);
    }
  }

  // ── etendo_generate_amortization_plan (ETP-4232) ─────────────────────────────

  @Nested
  @DisplayName("route — etendo_generate_amortization_plan (ETP-4232)")
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
    @DisplayName("routes etendo_generate_amortization_plan to handleGenerateAmortizationPlan and returns success")
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
    @DisplayName("etendo_generate_amortization_plan propagates 400 error as isError")
    void amortizationToolPropagates400() throws Exception {
      NeoResponse errorResp = NeoResponse.error(400, "assetId is required");

      amortMock.when(() -> AmortizationPlanService.generatePlan(isNull())).thenReturn(errorResp);

      JSONObject result = router.route(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN,
          null, PROCESS_SCOPES_LOCAL);

      assertTrue(result.getBoolean("isError"), "Error response must set isError=true");
    }

    @Test
    @DisplayName("etendo_generate_amortization_plan propagates 404 as isError")
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
    @DisplayName("etendo_generate_amortization_plan propagates 409 as isError")
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
    @DisplayName("etendo_generate_amortization_plan with missing assetId arg delegates null to service")
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
   * (etendo_get/create/update/delete/selectors/schema) with a resolved entity that has no
   * AD_Tab, so the changed line executes and control exits at {@code getAdTabOrThrow} — before
   * any DefaultJsonDataService/ModelProvider static-init dependency is touched. etendo_list and
   * etendo_defaults call-sites are already covered by their own tests above.
   *
   * <p>ETP-5405: the entity here declares no {@code Java_Qualifier}, so the exit is the
   * no-handler branch of {@link McpRoutingException#entityHasNoTab} — a 422 routing envelope,
   * not the {@code 500 server_error "No AD_Tab linked to entity"} this used to match on. The
   * handler branch and the tab-less read path are covered by {@code TablessEntityTests}.</p>
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
      assertNoTabEnvelope(new JSONObject(contentText(result)));
    }

    @Test
    @DisplayName("etendo_get resolves entity via the shared guard")
    void getResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      assertNoTabError(routeWithNoTabEntity("etendo_get", args, READ_SCOPES));
    }

    @Test
    @DisplayName("etendo_create resolves entity via the shared guard")
    void createResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("fields", new JSONObject());
      assertNoTabError(routeWithNoTabEntity("etendo_create", args, WRITE_SCOPES));
    }

    @Test
    @DisplayName("etendo_update resolves entity via the shared guard")
    void updateResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      args.put("fields", new JSONObject());
      // ETP-5073 / DOC-04: required now, and supplied here for the same reason as in updateArgs —
      // this case is about entity resolution, and argument validation precedes it.
      args.put("updated", STALE_SAFE_VERSION);
      assertNoTabError(routeWithNoTabEntity("etendo_update", args, WRITE_SCOPES));
    }

    @Test
    @DisplayName("etendo_delete resolves entity via the shared guard")
    void deleteResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");
      assertNoTabError(routeWithNoTabEntity("etendo_delete", args, WRITE_SCOPES));
    }

    @Test
    @DisplayName("etendo_selectors resolves entity via the shared guard")
    void selectorsResolvesViaGuard() throws Exception {
      JSONObject args = buildCrudArgs();
      args.put("column", "C_BPartner_ID");
      assertNoTabError(routeWithNoTabEntity("etendo_selectors", args, READ_SCOPES));
    }

    @Test
    @DisplayName("etendo_schema resolves entity via the shared guard")
    void schemaResolvesViaGuard() throws Exception {
      assertNoTabError(routeWithNoTabEntity("etendo_schema", buildCrudArgs(), READ_SCOPES));
    }
  }

  // ── ETP-5405: tab-less entities ──────────────────────────────────────────

  /**
   * The two halves of ETP-5405, neither of which existed before it.
   *
   * <p>Sixteen active, included entities carry {@code ETGO_SF_ENTITY.ad_tab_id IS NULL} because a
   * handler serves them instead of a window — the dashboard widgets, {@code contacts/bp-stats},
   * {@code not-posted-documents/header}, the report specs, {@code warehouse/location}. Over REST
   * they answer fine: {@code NeoCrudHandler} consults the entity's {@code Java_Qualifier} first and
   * only falls through to the tab-based path when there is none. The MCP router did the opposite —
   * it demanded the tab up front — so every tool answered {@code 500 server_error} on an entity the
   * SPA renders. ETP-5405 split that into two answers:</p>
   *
   * <ol>
   *   <li>{@code etendo_list} and {@code etendo_get} now run the handler's pre-hook before the tab is
   *       demanded, so those reads simply work ({@code runTablessReadHook});</li>
   *   <li>every tool that genuinely needs a tab to answer — {@code etendo_schema} describes one's
   *       fields, the write paths fill them — answers 405 naming the tools that do work, instead
   *       of a 500 that reads as a broken instance.</li>
   * </ol>
   *
   * <p>The third test is the guard on the narrowness of (1): the 171 tab-backed entities must keep
   * the exact path they had, so the read hook must not fire for them.</p>
   */
  @Nested
  @DisplayName("route — tab-less entities served by a handler (ETP-5405)")
  class TablessEntityTests {

    /** A tab-less entity resolved by the shared guard, as every tool sees one. */
    private SFEntity setupTablessEntity() {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);
      supportMock.when(() -> McpToolRouterSupport.resolveIncludedEntityOrExplain(
          any(SFSpec.class), anyString())).thenReturn(entity);
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), anyString()))
          .thenReturn(entity);
      when(entity.getADTab()).thenReturn(null);
      return entity;
    }

    /**
     * What a handler's pre-hook hands back once {@code McpHookExecutor} has converted it: the MCP
     * content wrapper, not a {@code NeoResponse}. Carries a marker the assertions can recognise,
     * so "the handler answered" is proved by the payload rather than by the absence of an error.
     */
    private JSONObject handlerResult() throws Exception {
      JSONObject payload = new JSONObject();
      payload.put("servedBy", "handler");
      JSONObject text = new JSONObject();
      text.put("type", "text");
      text.put("text", payload.toString());
      JSONArray content = new JSONArray();
      content.put(text);
      JSONObject result = new JSONObject();
      result.put("content", content);
      return result;
    }

    /**
     * {@code etendo_schema} describes a tab's fields, so a tab-less entity leaves it nothing to
     * describe. With a handler behind the entity that is not a fault — the record is readable, the
     * tool is the wrong one — and the 405 says so while pointing at {@code etendo_list}/{@code
     * etendo_get}. This is the branch that never had a test: the pre-ETP-5405 code could not
     * distinguish the two cases at all.
     */
    @Test
    @DisplayName("etendo_schema on a tab-less entity WITH a handler answers 405, not 422")
    void schemaOnTablessHandledEntityIsMethodNotAllowed() throws Exception {
      SFEntity entity = setupTablessEntity();

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        lookupMock.when(() -> NeoHandlerLookup.forEntity(entity))
            .thenReturn(mock(NeoHandler.class));

        JSONObject result = router.route("etendo_schema", buildCrudArgs(), READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        JSONObject envelope = new JSONObject(contentText(result));
        assertHandlerServedEnvelope(envelope);
        assertEquals("etendo_schema", envelope.getString("tool"));
      }
    }

    /**
     * The same refusal on a write tool. Kept separate from the {@code etendo_schema} case because the
     * two reach {@code getAdTabOrThrow} from different call sites and only a per-site test proves
     * both were converted; the write path additionally passes the method-flag gate first, so this
     * also pins the order — the tab-less answer must come from the routing exception, never from a
     * gate refusal.
     */
    @Test
    @DisplayName("etendo_create on a tab-less entity WITH a handler answers 405")
    void createOnTablessHandledEntityIsMethodNotAllowed() throws Exception {
      SFEntity entity = setupTablessEntity();
      JSONObject args = buildCrudArgs();
      args.put("fields", new JSONObject());

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        lookupMock.when(() -> NeoHandlerLookup.forEntity(entity))
            .thenReturn(mock(NeoHandler.class));

        JSONObject result = router.route("etendo_create", args, WRITE_SCOPES);

        assertTrue(result.getBoolean("isError"));
        assertHandlerServedEnvelope(new JSONObject(contentText(result)));
      }
    }

    /**
     * {@code etendo_list} on a tab-less, handled entity must be answered BY the handler — no tab
     * demanded, no error. Before ETP-5405 this call could only 500.
     */
    @Test
    @DisplayName("etendo_list on a tab-less entity is served by the handler pre-hook")
    void listOnTablessHandledEntityIsServedByHandler() throws Exception {
      SFEntity entity = setupTablessEntity();
      NeoHandler handler = mock(NeoHandler.class);
      JSONObject handled = handlerResult();

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        // ETP-5415: resolution moved onto NeoExtensionDispatcher, which for the MCP channel asks
        // NeoHandlerLookup.byQualifier; ETP-5588: entity-level readers ask
        // NeoHandlerLookup.forEntity. The assertions below are unchanged.
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);
        lookupMock.when(() -> NeoHandlerLookup.forEntity(entity)).thenReturn(handler);
        // ETP-5415 (D13): the tab-less read is served by runReadProvider, which runs the read's
        // PRE phase before the tab is demanded. ETP-5405 reached the same behaviour through
        // the entity's handler + runPreHook; the seam moved, the assertion below did not.
        hookMock.when(() -> McpHookExecutor.runReadProvider(any(), any(), any(), any(), any()))
            .thenReturn(handled);
        hookMock.when(() -> McpHookExecutor.runPreHook(eq(handler), any())).thenReturn(handled);

        JSONObject result = router.route("etendo_list", buildCrudArgs(), READ_SCOPES);

        assertFalse(result.optBoolean("isError"), result.toString());
        assertTrue(contentText(result).contains("\"servedBy\":\"handler\""), contentText(result));
      }
    }

    /**
     * The same for {@code etendo_get}, which reaches the hook from its own call site. The record id
     * is what distinguishes a get from a list for the handler, so it must travel into the hook
     * context — asserted here rather than left to the list case, which passes {@code null}.
     */
    @Test
    @DisplayName("etendo_get on a tab-less entity is served by the handler pre-hook")
    void getOnTablessHandledEntityIsServedByHandler() throws Exception {
      SFEntity entity = setupTablessEntity();
      NeoHandler handler = mock(NeoHandler.class);
      JSONObject handled = handlerResult();
      JSONObject args = buildCrudArgs();
      args.put("id", "rec-1");

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        // ETP-5415: resolution moved onto NeoExtensionDispatcher, which for the MCP channel asks
        // NeoHandlerLookup.byQualifier; ETP-5588: entity-level readers ask
        // NeoHandlerLookup.forEntity. The assertions below are unchanged.
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(handler);
        lookupMock.when(() -> NeoHandlerLookup.forEntity(entity)).thenReturn(handler);
        // ETP-5415 (D13): the tab-less read is served by runReadProvider, which runs the read's
        // PRE phase before the tab is demanded. ETP-5405 reached the same behaviour through
        // the entity's handler + runPreHook; the seam moved, the assertion below did not.
        hookMock.when(() -> McpHookExecutor.runReadProvider(any(), any(), any(), any(), any()))
            .thenReturn(handled);
        hookMock.when(() -> McpHookExecutor.runPreHook(eq(handler), any())).thenReturn(handled);

        JSONObject result = router.route("etendo_get", args, READ_SCOPES);

        assertFalse(result.optBoolean("isError"), result.toString());
        assertTrue(contentText(result).contains("\"servedBy\":\"handler\""), contentText(result));
        // The seam the router now calls is runReadProvider (ETP-5415, D13); the earlier assertion on
        // buildReadHookContext could not hold once the whole McpHookExecutor is stubbed.
        hookMock.verify(() -> McpHookExecutor.runReadProvider(eq(SPEC_NAME), eq(ENTITY_NAME),
            eq("rec-1"), eq(entity), any()));
      }
    }

    /**
     * A tab-less entity with NO handler falls through the read hook and reaches the tab demand, so
     * {@code etendo_list} still refuses — with the 422 that says the entity itself is misconfigured.
     * This pins the {@code return null} in {@code runTablessReadHook}: were it to swallow the call,
     * an unserviceable entity would answer an empty success.
     */
    @Test
    @DisplayName("etendo_list on a tab-less entity WITHOUT a handler still refuses with 422")
    void listOnTablessUnhandledEntityStillRefuses() throws Exception {
      SFEntity entity = setupTablessEntity();

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        // ETP-5415: resolution moved onto NeoExtensionDispatcher, which for the MCP channel asks
        // NeoHandlerLookup.byQualifier; ETP-5588: entity-level readers ask
        // NeoHandlerLookup.forEntity. The assertions below are unchanged.
        lookupMock.when(() -> NeoHandlerLookup.byQualifier(anyString())).thenReturn(null);
        lookupMock.when(() -> NeoHandlerLookup.forEntity(entity)).thenReturn(null);

        JSONObject result = router.route("etendo_list", buildCrudArgs(), READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        assertNoTabEnvelope(new JSONObject(contentText(result)));
        hookMock.verify(() -> McpHookExecutor.runPreHook(any(), any()), never());
      }
    }

    /**
     * The narrowness guard. {@code runTablessReadHook} returns immediately when the entity has a
     * tab, which is what keeps ETP-5405 incapable of changing the shape of any read that already
     * worked — 171 entities, 80 of them with a handler whose {@code afterHandle} would otherwise
     * start running on MCP reads for the first time.
     *
     * <p>The call is allowed to fail afterwards and its outcome is deliberately not asserted: a
     * tab-backed {@code etendo_list} continues into {@code DefaultJsonDataService}, whose static
     * initialiser needs a servlet container (see this class's javadoc), and it may surface as an
     * error result or as an {@code Error} escaping {@code route}'s {@code catch (Exception)}.
     * What this test pins is only that no hook ran before that point.</p>
     */
    @Test
    @DisplayName("an entity WITH a tab never reaches the read hook")
    void tabBackedEntityNeverRunsTheReadHook() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);
      setupEntityLookup(entity, mockTab());

      try (MockedStatic<McpHookExecutor> hookMock = mockStatic(McpHookExecutor.class);
          MockedStatic<NeoHandlerLookup> lookupMock = mockStatic(NeoHandlerLookup.class)) {
        lookupMock.when(() -> NeoHandlerLookup.forEntity(entity))
            .thenReturn(mock(NeoHandler.class));

        try {
          router.route("etendo_list", buildCrudArgs(), READ_SCOPES);
        } catch (Throwable ignored) {
          // See the javadoc: the generic path's dependencies are out of scope for this class.
        }

        lookupMock.verify(() -> NeoHandlerLookup.forEntity(entity), never());
        hookMock.verify(() -> McpHookExecutor.runPreHook(any(), any()), never());
      }
    }
  }

  // ── attachment tools (ETP-5715) ───────────────────────────────────────────

  @Nested
  @DisplayName("route — attachment tools")
  class AttachmentToolsTests {

    private final String[] tools = {McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD,
        McpConstants.TOOL_NEO_LIST_ATTACHMENTS, McpConstants.TOOL_NEO_DELETE_ATTACHMENT};

    private JSONObject attachmentArgs() throws Exception {
      return new JSONObject().put("spec", SPEC_NAME).put("id", "REC1");
    }

    @Test
    @DisplayName("each tool is dispatched to the attachment handler, after the scope and spec checks")
    void dispatchedAfterTheChecks() throws Exception {
      setupSpecLookup(mockSpec());
      JSONObject sentinel = McpToolRouter.wrapAsTextContent("handled");
      try (MockedStatic<McpAttachmentTools> handler = mockStatic(McpAttachmentTools.class)) {
        handler.when(() -> McpAttachmentTools.handle(anyString(), any())).thenReturn(sentinel);

        for (String tool : tools) {
          assertEquals(sentinel, router.route(tool, attachmentArgs(), WRITE_SCOPES), tool);
          handler.verify(() -> McpAttachmentTools.handle(eq(tool), any()));
          authMock.verify(() -> McpAuthorizationService.authorizeToolCall(eq(tool), any()));
        }
      }
      supportMock.verify(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), eq("GET")),
          org.mockito.Mockito.times(3));
    }

    @Test
    @DisplayName("the email send is dispatched to its handler on a write scope, after the spec check")
    void sendDocumentEmailIsDispatchedAfterTheChecks() throws Exception {
      setupSpecLookup(mockSpec());
      JSONObject sentinel = McpToolRouter.wrapAsTextContent("emailed");
      try (MockedStatic<McpDocumentEmailTool> handler = mockStatic(McpDocumentEmailTool.class)) {
        handler.when(() -> McpDocumentEmailTool.handle(any())).thenReturn(sentinel);

        assertEquals(sentinel, router.route(McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL,
            attachmentArgs(), WRITE_SCOPES));

        handler.verify(() -> McpDocumentEmailTool.handle(any()));
        authMock.verify(() -> McpAuthorizationService
            .authorizeToolCall(eq(McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL), any()));
      }
    }

    @Test
    @DisplayName("the two extraction tools are dispatched to their handler on a write scope, after the spec check")
    void documentExtractionToolsAreDispatchedAfterTheChecks() throws Exception {
      setupSpecLookup(mockSpec());
      JSONObject sentinel = McpToolRouter.wrapAsTextContent("extracted");
      try (MockedStatic<McpOcrTools> handler = mockStatic(McpOcrTools.class)) {
        handler.when(() -> McpOcrTools.handle(anyString(), any())).thenReturn(sentinel);

        for (String tool : List.of(McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD,
            McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT)) {
          JSONObject args = new JSONObject().put("spec", SPEC_NAME).put("name", "f.pdf")
              .put("staged_document_id", "S1");
          args.remove(McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT.equals(tool) ? "name"
              : "staged_document_id");
          assertEquals(sentinel, router.route(tool, args, WRITE_SCOPES), tool);
          handler.verify(() -> McpOcrTools.handle(eq(tool), any()));
          authMock.verify(() -> McpAuthorizationService.authorizeToolCall(eq(tool), any()));
        }
      }
    }

    @Test
    @DisplayName("the document PDF tool is dispatched to its handler on a read scope, after the spec check")
    void documentPdfIsDispatchedAfterTheChecks() throws Exception {
      setupSpecLookup(mockSpec());
      JSONObject sentinel = McpToolRouter.wrapAsTextContent("pdf");
      try (MockedStatic<McpDocumentPdfTool> handler = mockStatic(McpDocumentPdfTool.class)) {
        handler.when(() -> McpDocumentPdfTool.handle(any())).thenReturn(sentinel);

        assertEquals(sentinel,
            router.route(McpConstants.TOOL_NEO_GET_DOCUMENT_PDF, attachmentArgs(), READ_SCOPES));

        handler.verify(() -> McpDocumentPdfTool.handle(any()));
        authMock.verify(() -> McpAuthorizationService
            .authorizeToolCall(eq(McpConstants.TOOL_NEO_GET_DOCUMENT_PDF), any()));
      }
      supportMock.verify(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), eq("GET")));
    }

    @Test
    @DisplayName("a role without access to the spec is refused before the handler is reached")
    void roleWithoutSpecAccessNeverReachesTheHandler() throws Exception {
      setupSpecLookup(mockSpec());
      supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString(), anyString()))
          .thenReturn(false);
      try (MockedStatic<McpAttachmentTools> handler = mockStatic(McpAttachmentTools.class)) {
        for (String tool : tools) {
          JSONObject result = router.route(tool, attachmentArgs(), WRITE_SCOPES);

          assertTrue(result.getBoolean("isError"), tool);
        }
        handler.verifyNoInteractions();
      }
    }

    @Test
    @DisplayName("an argument the tool does not declare is refused, not silently dropped")
    void unknownArgumentIsRefused() throws Exception {
      setupSpecLookup(mockSpec());
      try (MockedStatic<McpAttachmentTools> handler = mockStatic(McpAttachmentTools.class)) {
        JSONObject result = router.route(McpConstants.TOOL_NEO_LIST_ATTACHMENTS,
            attachmentArgs().put("attachmentId", "F1"), READ_SCOPES);

        assertTrue(result.getBoolean("isError"));
        handler.verifyNoInteractions();
      }
    }
  }
}
