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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoDefaultsService;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoReportService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

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
  private MockedStatic<NeoReportService> reportMock;
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
    reportMock = mockStatic(NeoReportService.class);
    selectorMock = mockStatic(NeoSelectorService.class);
    buttonActionMock = mockStatic(NeoButtonActionHelper.class);

    obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    obContextMock.when(() -> OBContext.setAdminMode()).thenAnswer(inv -> null);
    obContextMock.when(OBContext::restorePreviousMode).thenAnswer(inv -> null);

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
    if (reportMock != null) reportMock.close();
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

  @SuppressWarnings("unchecked")
  private void setupSpecLookup(SFSpec spec) {
    OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
    when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
    when(specCriteria.list()).thenReturn(List.of(spec));
    supportMock.when(() -> McpToolRouterSupport.hasSpecAccess(any(), anyString()))
        .thenReturn(true);
  }

  @SuppressWarnings("unchecked")
  private void setupEntityLookup(SFEntity entity, Tab tab) {
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(List.of(entity));
    when(entity.getADTab()).thenReturn(tab);
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

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
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
      supportMock.when(() -> McpToolRouterSupport.buildDiscoverSpec(eq(spec), eq("W"), any()))
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
  @DisplayName("route — report tools")
  class ReportTests {

    @Test
    @DisplayName("report tool with no linked AD_Process returns error")
    void reportToolNoProcessReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      when(spec.getProcess()).thenReturn(null);
      setupSpecLookup(spec);

      JSONObject result = router.route("generate_invoice_report", null, REPORT_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("no linked AD_Process"));
    }

    @Test
    @DisplayName("report tool with denied RBAC returns error")
    void reportToolDeniedRbacReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      setupSpecLookup(spec);

      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(false);

      JSONObject result = router.route("generate_invoice_report", null, REPORT_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Access denied"));
    }

    @Test
    @DisplayName("report tool with generation failure returns fallback description")
    void reportToolGenerationFailureFallback() throws Exception {
      SFSpec spec = mockSpec();
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      setupSpecLookup(spec);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(true);

      reportMock.when(() -> NeoReportService.generateReport(
          eq(adProcess), any(), anyString(), any()))
          .thenThrow(new RuntimeException("Template not found"));

      JSONObject describeBody = new JSONObject();
      describeBody.put("info", "Invoice report");
      NeoResponse describeResp = NeoResponse.ok(describeBody);
      reportMock.when(() -> NeoReportService.describeReport(adProcess))
          .thenReturn(describeResp);

      JSONObject args = new JSONObject();
      args.put("format", "pdf");

      JSONObject result = router.route("generate_invoice_report", args, REPORT_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Template not found"));
    }
  }

  // ── Spec/entity resolution ────────────────────────────────────────────

  @Nested
  @DisplayName("route — spec/entity resolution errors")
  class ResolutionErrorTests {

    @Test
    @DisplayName("unknown spec returns error")
    @SuppressWarnings("unchecked")
    void unknownSpecReturnsError() throws Exception {
      OBCriteria<SFSpec> specCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(specCriteria);
      when(specCriteria.list()).thenReturn(Collections.emptyList());

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Spec not found"));
    }

    @Test
    @DisplayName("unknown entity returns error")
    @SuppressWarnings("unchecked")
    void unknownEntityReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      setupSpecLookup(spec);

      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(Collections.emptyList());

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
      assertTrue(text.contains("Entity not found"));
    }

    @Test
    @DisplayName("entity without AD_Tab returns error")
    @SuppressWarnings("unchecked")
    void entityWithoutTabReturnsError() throws Exception {
      SFSpec spec = mockSpec();
      SFEntity entity = mockEntity();
      setupSpecLookup(spec);

      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
      when(entityCriteria.list()).thenReturn(List.of(entity));
      when(entity.getADTab()).thenReturn(null);

      JSONObject args = buildCrudArgs();
      JSONObject result = router.route("neo_list", args, READ_SCOPES);

      assertTrue(result.getBoolean("isError"));
      String text = result.getJSONArray("content").getJSONObject(0).getString("text");
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
      when(mockOBDal.createCriteria(SFSpec.class))
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
      when(mockOBDal.createCriteria(SFSpec.class))
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

  // ── neo_action ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("route — neo_action")
  class ActionTests {

    private static final Set<String> ACTION_SCOPES = Set.of("neo:write");
    private static final String RECORD_ID = "record-001";
    private static final String ACTION_NAME = "Processed";

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
  }
}
