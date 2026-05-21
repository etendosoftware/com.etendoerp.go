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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link ToolRegistry} — covers generateTools, processSpec,
 * RBAC/scope permission resolution, CRUD tool registration, process/report
 * tool building, and resolveSpecName.
 * <p>
 * Pure unit tests with MockedStatic for OBDal and NeoAccessUtils.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolRegistryGenerateToolsTest {

  private static final String SPEC_SALES_ORDER = "sales-order";
  private static final String SPEC_INVOICES = "invoices";
  private static final String SPEC_COMPLETE_ORDER = "complete-order";
  private static final String SPEC_PRINT_INVOICE = "print-invoice";
  private static final String WINDOW_ID = "win-001";
  private static final String PROCESS_ID = "proc-001";

  @Mock
  private OBDal mockOBDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<NeoAccessUtils> accessMock;

  private ToolRegistry registry;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    accessMock = mockStatic(NeoAccessUtils.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mockOBDal);
    registry = new ToolRegistry();
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (accessMock != null) {
      accessMock.close();
    }
  }

  // ── Helper methods ──────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private OBCriteria<SFSpec> mockSpecCriteria(List<SFSpec> specs) {
    OBCriteria<SFSpec> criteria = mock(OBCriteria.class);
    when(mockOBDal.createCriteria(SFSpec.class)).thenReturn(criteria);
    when(criteria.list()).thenReturn(specs);
    return criteria;
  }

  private SFSpec createWindowSpec(String name) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(name);
    when(spec.getSpecType()).thenReturn("W");
    return spec;
  }

  private SFSpec createWindowSpecWithWindow(String name, String windowId) {
    SFSpec spec = createWindowSpec(name);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(windowId);
    when(spec.getADWindow()).thenReturn(window);
    return spec;
  }

  private SFSpec createProcessSpec(String name) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(name);
    when(spec.getSpecType()).thenReturn("P");
    return spec;
  }

  private SFSpec createReportSpec(String name) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(name);
    when(spec.getSpecType()).thenReturn("R");
    return spec;
  }

  @SuppressWarnings("unchecked")
  private void mockEmptyEntities(@SuppressWarnings("unused") SFSpec spec) {
    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Collections.emptyList());
  }

  private Set<String> scopesOf(String... scopes) {
    Set<String> set = new HashSet<>();
    Collections.addAll(set, scopes);
    return set;
  }

  private List<String> toolNames(List<McpToolDefinition> tools) {
    return tools.stream().map(McpToolDefinition::getName).collect(Collectors.toList());
  }

  // ── generateTools: scope resolution ─────────────────────────────────────

  @Nested
  @DisplayName("generateTools — scope permission resolution")
  class ScopePermissionTests {

    @Test
    @DisplayName("neo:read scope includes neo_discover when no specs exist")
    void readScopeIncludesDiscover() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      List<String> names = toolNames(tools);
      assertTrue(names.contains("neo_discover"));
      assertEquals(1, tools.size());
    }

    @Test
    @DisplayName("no read scope excludes neo_discover")
    void noReadScopeExcludesDiscover() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));

      assertFalse(toolNames(tools).contains("neo_discover"));
      assertTrue(tools.isEmpty());
    }

    @Test
    @DisplayName("neo:* scope grants all permissions")
    void wildcardScopeGrantsAll() {
      SFSpec windowSpec = createWindowSpec(SPEC_SALES_ORDER);
      when(windowSpec.getADWindow()).thenReturn(null);

      SFSpec processSpec = createProcessSpec(SPEC_COMPLETE_ORDER);
      when(processSpec.getProcess()).thenReturn(null);
      mockEmptyEntities(processSpec);

      SFSpec reportSpec = createReportSpec(SPEC_PRINT_INVOICE);
      when(reportSpec.getProcess()).thenReturn(null);
      mockEmptyEntities(reportSpec);

      mockSpecCriteria(List.of(windowSpec, processSpec, reportSpec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:*"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("neo_list"));
      assertTrue(names.contains("neo_create"));
      assertTrue(names.contains("complete_order"));
      assertTrue(names.contains("generate_print_invoice"));
    }

    @Test
    @DisplayName("empty scopes produce no tools")
    void emptyScopesProduceNoTools() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(Collections.emptySet());

      assertTrue(tools.isEmpty());
    }
  }

  // ── generateTools: window spec processing ─────────────────────────────

  @Nested
  @DisplayName("generateTools — window specs and CRUD tools")
  class WindowSpecTests {

    @Test
    @DisplayName("window spec with null AD_Window is always accessible")
    void windowSpecNullWindowAlwaysAccessible() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("neo_list"));
      assertTrue(names.contains("neo_get"));
      assertTrue(names.contains("neo_selectors"));
      assertTrue(names.contains("neo_defaults"));
      assertTrue(names.contains("neo_schema"));
    }

    @Test
    @DisplayName("window spec with accessible AD_Window is included")
    void windowSpecWithAccessibleWindow() {
      SFSpec spec = createWindowSpecWithWindow(SPEC_SALES_ORDER, WINDOW_ID);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccess(WINDOW_ID)).thenReturn(true);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertTrue(toolNames(tools).contains("neo_list"));
    }

    @Test
    @DisplayName("window spec with denied AD_Window is excluded")
    void windowSpecWithDeniedWindow() {
      SFSpec spec = createWindowSpecWithWindow(SPEC_SALES_ORDER, WINDOW_ID);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccess(WINDOW_ID)).thenReturn(false);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      // Only neo_discover, no CRUD tools since no accessible window specs
      assertEquals(1, tools.size());
      assertEquals("neo_discover", tools.get(0).getName());
    }

    @Test
    @DisplayName("read scope registers read CRUD tools but not write tools")
    void readScopeRegistersOnlyReadTools() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("neo_list"));
      assertTrue(names.contains("neo_get"));
      assertTrue(names.contains("neo_selectors"));
      assertTrue(names.contains("neo_defaults"));
      assertTrue(names.contains("neo_schema"));
      assertFalse(names.contains("neo_create"));
      assertFalse(names.contains("neo_update"));
      assertFalse(names.contains("neo_delete"));
    }

    @Test
    @DisplayName("write scope registers write CRUD tools but not read tools")
    void writeScopeRegistersOnlyWriteTools() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));
      List<String> names = toolNames(tools);

      assertFalse(names.contains("neo_discover"));
      assertFalse(names.contains("neo_list"));
      assertTrue(names.contains("neo_create"));
      assertTrue(names.contains("neo_update"));
      assertTrue(names.contains("neo_delete"));
    }

    @Test
    @DisplayName("read+write scope registers all CRUD tools")
    void readWriteScopeRegistersAllCrud() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read", "neo:write"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("neo_list"));
      assertTrue(names.contains("neo_get"));
      assertTrue(names.contains("neo_create"));
      assertTrue(names.contains("neo_update"));
      assertTrue(names.contains("neo_delete"));
      assertTrue(names.contains("neo_selectors"));
      assertTrue(names.contains("neo_defaults"));
      assertTrue(names.contains("neo_schema"));
    }

    @Test
    @DisplayName("multiple window specs create CRUD tools with enum of all spec names")
    @SuppressWarnings("unchecked")
    void multipleWindowSpecsCreateEnumInCrudTools() {
      SFSpec spec1 = createWindowSpec(SPEC_SALES_ORDER);
      when(spec1.getADWindow()).thenReturn(null);
      SFSpec spec2 = createWindowSpec(SPEC_INVOICES);
      when(spec2.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec1, spec2));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition listTool = tools.stream()
          .filter(t -> "neo_list".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(listTool);

      Map<String, Object> schema = listTool.getInputSchema();
      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertNotNull(props);
      Map<String, Object> specProp = (Map<String, Object>) props.get("spec");
      assertNotNull(specProp);
      List<String> enumValues = (List<String>) specProp.get("enum");
      assertNotNull(enumValues);
      assertTrue(enumValues.contains(SPEC_SALES_ORDER));
      assertTrue(enumValues.contains(SPEC_INVOICES));
    }

    @Test
    @DisplayName("no accessible window specs produces no CRUD tools")
    void noAccessibleWindowSpecsNoCrudTools() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read", "neo:write"));

      // Only neo_discover
      assertEquals(1, tools.size());
      assertEquals("neo_discover", tools.get(0).getName());
    }
  }

  // ── generateTools: process spec processing ────────────────────────────

  @Nested
  @DisplayName("generateTools — process specs")
  class ProcessSpecTests {

    @Test
    @DisplayName("process spec with access and neo:process scope generates tool")
    void processSpecWithAccessGeneratesTool() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      when(spec.getProcess()).thenReturn(null);
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("complete_order"));
    }

    @Test
    @DisplayName("process spec without neo:process scope is excluded")
    void processSpecWithoutProcessScope() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertFalse(toolNames(tools).contains("complete_order"));
    }

    @Test
    @DisplayName("process spec with denied process access is excluded")
    void processSpecWithDeniedAccess() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(false);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      assertFalse(toolNames(tools).contains("complete_order"));
    }

    @Test
    @DisplayName("process spec with granted process access generates tool")
    void processSpecWithGrantedAccess() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(true);
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      assertTrue(toolNames(tools).contains("complete_order"));
    }

    @Test
    @DisplayName("process tool name is kebab-to-snake of spec name")
    void processToolNameIsSnakeCase() {
      SFSpec spec = createProcessSpec("multi-step-process");
      when(spec.getProcess()).thenReturn(null);
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      assertTrue(toolNames(tools).contains("multi_step_process"));
    }

    @Test
    @DisplayName("process tool includes spec description when available")
    void processToolIncludesDescription() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      when(spec.getProcess()).thenReturn(null);
      when(spec.getDescription()).thenReturn("Completes a sales order");
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      McpToolDefinition tool = tools.stream()
          .filter(t -> "complete_order".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(tool);
      assertTrue(tool.getDescription().contains("Completes a sales order"));
    }
  }

  // ── generateTools: report spec processing ─────────────────────────────

  @Nested
  @DisplayName("generateTools — report specs")
  class ReportSpecTests {

    @Test
    @DisplayName("report spec with access and neo:report scope generates tool")
    void reportSpecWithAccessGeneratesTool() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("generate_print_invoice"));
    }

    @Test
    @DisplayName("report spec without neo:report scope is excluded")
    void reportSpecWithoutReportScope() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertFalse(toolNames(tools).contains("generate_print_invoice"));
    }

    @Test
    @DisplayName("report spec with denied process access is excluded")
    void reportSpecWithDeniedAccess() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      Process adProcess = mock(Process.class);
      when(adProcess.getId()).thenReturn(PROCESS_ID);
      when(spec.getProcess()).thenReturn(adProcess);
      accessMock.when(() -> NeoAccessUtils.hasProcessAccess(PROCESS_ID)).thenReturn(false);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));

      assertFalse(toolNames(tools).contains("generate_print_invoice"));
    }

    @Test
    @DisplayName("report tool includes format parameter")
    @SuppressWarnings("unchecked")
    void reportToolIncludesFormatParam() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));

      McpToolDefinition tool = tools.stream()
          .filter(t -> t.getName().startsWith("generate_"))
          .findFirst()
          .orElse(null);
      assertNotNull(tool);
      Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
      assertTrue(props.containsKey("format"));
    }

    @Test
    @DisplayName("report tool description includes spec description when available")
    void reportToolIncludesDescription() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      when(spec.getDescription()).thenReturn("Generates a PDF invoice");
      mockEmptyEntities(spec);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));

      McpToolDefinition tool = tools.stream()
          .filter(t -> "generate_print_invoice".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(tool);
      assertTrue(tool.getDescription().contains("Generates a PDF invoice"));
    }
  }

  // ── generateTools: mixed specs ────────────────────────────────────────

  @Nested
  @DisplayName("generateTools — mixed spec types")
  class MixedSpecTests {

    @Test
    @DisplayName("mixed specs with full permissions generate all tool types")
    void mixedSpecsFullPermissions() {
      SFSpec windowSpec = createWindowSpec(SPEC_SALES_ORDER);
      when(windowSpec.getADWindow()).thenReturn(null);

      SFSpec processSpec = createProcessSpec(SPEC_COMPLETE_ORDER);
      when(processSpec.getProcess()).thenReturn(null);
      mockEmptyEntities(processSpec);

      SFSpec reportSpec = createReportSpec(SPEC_PRINT_INVOICE);
      when(reportSpec.getProcess()).thenReturn(null);
      mockEmptyEntities(reportSpec);

      mockSpecCriteria(List.of(windowSpec, processSpec, reportSpec));

      List<McpToolDefinition> tools = registry.generateTools(
          scopesOf("neo:read", "neo:write", "neo:process", "neo:report"));
      List<String> names = toolNames(tools);

      // discover + 8 CRUD + 1 process + 1 report = 11
      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("neo_list"));
      assertTrue(names.contains("neo_create"));
      assertTrue(names.contains("complete_order"));
      assertTrue(names.contains("generate_print_invoice"));
    }

    @Test
    @DisplayName("spec processing exception is caught and does not break other specs")
    void specExceptionDoesNotBreakOthers() {
      SFSpec badSpec = mock(SFSpec.class);
      when(badSpec.getName()).thenReturn("bad-spec");
      when(badSpec.getSpecType()).thenThrow(new RuntimeException("DB error"));

      SFSpec goodSpec = createWindowSpec(SPEC_SALES_ORDER);
      when(goodSpec.getADWindow()).thenReturn(null);

      mockSpecCriteria(List.of(badSpec, goodSpec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      // Good spec should still produce CRUD tools
      assertTrue(names.contains("neo_list"));
    }
  }

  // ── resolveSpecName ───────────────────────────────────────────────────

  @Nested
  @DisplayName("resolveSpecName")
  class ResolveSpecNameTests {

    @Test
    @DisplayName("CRUD tool resolves spec from arguments")
    void crudToolResolvesFromArgs() throws Exception {
      JSONObject args = new JSONObject();
      args.put("spec", SPEC_SALES_ORDER);

      String result = ToolRegistry.resolveSpecName("neo_list", args);
      assertEquals(SPEC_SALES_ORDER, result);
    }

    @Test
    @DisplayName("CRUD tool with null arguments returns null")
    void crudToolNullArgsReturnsNull() {
      String result = ToolRegistry.resolveSpecName("neo_get", null);
      assertNull(result);
    }

    @Test
    @DisplayName("CRUD tool with missing spec arg returns null")
    void crudToolMissingSpecArgReturnsNull() throws Exception {
      JSONObject args = new JSONObject();
      args.put("entity", "Header");

      String result = ToolRegistry.resolveSpecName("neo_create", args);
      assertNull(result);
    }

    @Test
    @DisplayName("report tool strips generate_ prefix and converts to kebab")
    void reportToolStripsPrefix() {
      String result = ToolRegistry.resolveSpecName("generate_print_invoice", null);
      assertEquals("print-invoice", result);
    }

    @Test
    @DisplayName("process tool converts snake_case to kebab-case")
    void processToolConvertsToKebab() {
      String result = ToolRegistry.resolveSpecName("complete_order", null);
      assertEquals("complete-order", result);
    }

    @Test
    @DisplayName("single word process tool returns as-is")
    void singleWordProcessToolReturnsAsIs() {
      String result = ToolRegistry.resolveSpecName("invoices", null);
      assertEquals("invoices", result);
    }
  }

  // ── isCrudTool ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("isCrudTool")
  class IsCrudToolTests {

    @Test
    @DisplayName("all CRUD tool names return true")
    void allCrudToolNamesReturnTrue() {
      assertTrue(ToolRegistry.isCrudTool("neo_discover"));
      assertTrue(ToolRegistry.isCrudTool("neo_list"));
      assertTrue(ToolRegistry.isCrudTool("neo_get"));
      assertTrue(ToolRegistry.isCrudTool("neo_create"));
      assertTrue(ToolRegistry.isCrudTool("neo_update"));
      assertTrue(ToolRegistry.isCrudTool("neo_delete"));
      assertTrue(ToolRegistry.isCrudTool("neo_selectors"));
      assertTrue(ToolRegistry.isCrudTool("neo_defaults"));
      assertTrue(ToolRegistry.isCrudTool("neo_schema"));
    }

    @Test
    @DisplayName("process tool name returns false")
    void processToolNameReturnsFalse() {
      assertFalse(ToolRegistry.isCrudTool("complete_order"));
    }

    @Test
    @DisplayName("report tool name returns false")
    void reportToolNameReturnsFalse() {
      assertFalse(ToolRegistry.isCrudTool("generate_print_invoice"));
    }

    @Test
    @DisplayName("arbitrary string returns false")
    void arbitraryStringReturnsFalse() {
      assertFalse(ToolRegistry.isCrudTool("random_name"));
    }

    @Test
    @DisplayName("empty string returns false")
    void emptyStringReturnsFalse() {
      assertFalse(ToolRegistry.isCrudTool(""));
    }
  }

  // ── kebabToSnake / snakeToKebab ───────────────────────────────────────

  @Nested
  @DisplayName("naming conversions")
  class NamingConversionTests {

    @Test
    @DisplayName("kebabToSnake converts dashes to underscores")
    void kebabToSnake() {
      assertEquals("a_b_c", ToolRegistry.kebabToSnake("a-b-c"));
    }

    @Test
    @DisplayName("snakeToKebab converts underscores to dashes")
    void snakeToKebab() {
      assertEquals("a-b-c", ToolRegistry.snakeToKebab("a_b_c"));
    }

    @Test
    @DisplayName("kebabToSnake with no dashes returns unchanged")
    void kebabToSnakeNoDashes() {
      assertEquals("invoices", ToolRegistry.kebabToSnake("invoices"));
    }

    @Test
    @DisplayName("snakeToKebab with no underscores returns unchanged")
    void snakeToKebabNoUnderscores() {
      assertEquals("invoices", ToolRegistry.snakeToKebab("invoices"));
    }

    @Test
    @DisplayName("empty string conversions return empty string")
    void emptyStringConversions() {
      assertEquals("", ToolRegistry.kebabToSnake(""));
      assertEquals("", ToolRegistry.snakeToKebab(""));
    }
  }

  // ── Tool schema validation ────────────────────────────────────────────

  @Nested
  @DisplayName("tool schema structure")
  class ToolSchemaTests {

    @Test
    @DisplayName("neo_discover has empty properties and no required fields")
    @SuppressWarnings("unchecked")
    void discoverToolSchema() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition discover = tools.stream()
          .filter(t -> "neo_discover".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(discover);

      Map<String, Object> schema = discover.getInputSchema();
      assertEquals("object", schema.get("type"));
      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertNotNull(props);
      assertTrue(props.isEmpty());
    }

    @Test
    @DisplayName("neo_list has required spec and entity fields")
    @SuppressWarnings("unchecked")
    void listToolRequiredFields() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition listTool = tools.stream()
          .filter(t -> "neo_list".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(listTool);

      Map<String, Object> schema = listTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertNotNull(required);
      assertTrue(required.contains("spec"));
      assertTrue(required.contains("entity"));
    }

    @Test
    @DisplayName("neo_get has required spec, entity, and id fields")
    @SuppressWarnings("unchecked")
    void getToolRequiredFields() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition getTool = tools.stream()
          .filter(t -> "neo_get".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(getTool);

      Map<String, Object> schema = getTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertTrue(required.contains("spec"));
      assertTrue(required.contains("entity"));
      assertTrue(required.contains("id"));
    }

    @Test
    @DisplayName("neo_create has required spec, entity, and fields")
    @SuppressWarnings("unchecked")
    void createToolRequiredFields() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));

      McpToolDefinition createTool = tools.stream()
          .filter(t -> "neo_create".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(createTool);

      Map<String, Object> schema = createTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertTrue(required.contains("spec"));
      assertTrue(required.contains("entity"));
      assertTrue(required.contains("fields"));
    }

    @Test
    @DisplayName("neo_delete has required spec, entity, and id fields")
    @SuppressWarnings("unchecked")
    void deleteToolRequiredFields() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));

      McpToolDefinition deleteTool = tools.stream()
          .filter(t -> "neo_delete".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(deleteTool);

      Map<String, Object> schema = deleteTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertTrue(required.contains("spec"));
      assertTrue(required.contains("entity"));
      assertTrue(required.contains("id"));
    }

    @Test
    @DisplayName("neo_selectors has required spec, entity, and column fields")
    @SuppressWarnings("unchecked")
    void selectorsToolRequiredFields() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition selectorsTool = tools.stream()
          .filter(t -> "neo_selectors".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(selectorsTool);

      Map<String, Object> schema = selectorsTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertTrue(required.contains("spec"));
      assertTrue(required.contains("entity"));
      assertTrue(required.contains("column"));
    }

    @Test
    @DisplayName("neo_list includes optional filters, limit, offset, orderBy properties")
    @SuppressWarnings("unchecked")
    void listToolOptionalProperties() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition listTool = tools.stream()
          .filter(t -> "neo_list".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(listTool);

      Map<String, Object> props = (Map<String, Object>) listTool.getInputSchema()
          .get("properties");
      assertTrue(props.containsKey("filters"));
      assertTrue(props.containsKey("limit"));
      assertTrue(props.containsKey("offset"));
      assertTrue(props.containsKey("orderBy"));
    }
  }

  // ── Process/report parameter schema from entities/fields ──────────────

  @Nested
  @DisplayName("process parameter schema from SF entities/fields")
  class ProcessParamSchemaTests {

    @Test
    @DisplayName("process tool includes field parameters from SF entities")
    @SuppressWarnings("unchecked")
    void processToolIncludesFieldParams() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      when(spec.getProcess()).thenReturn(null);
      when(spec.getId()).thenReturn("spec-id-1");

      // Mock entity criteria
      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);

      SFEntity entity = mock(SFEntity.class);
      when(entity.getId()).thenReturn("entity-id-1");
      when(entityCriteria.list()).thenReturn(List.of(entity));

      // Mock field criteria
      OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

      SFField field = mock(SFField.class);
      Column adColumn = mock(Column.class);
      when(adColumn.getDBColumnName()).thenReturn("C_Order_ID");
      when(adColumn.getName()).thenReturn("Order");
      when(field.getADColumn()).thenReturn(adColumn);
      when(fieldCriteria.list()).thenReturn(List.of(field));

      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      McpToolDefinition tool = tools.stream()
          .filter(t -> "complete_order".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(tool);

      Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
      assertNotNull(props);
      Map<String, Object> paramsProp = (Map<String, Object>) props.get("parameters");
      assertNotNull(paramsProp);
      Map<String, Object> nestedProps = (Map<String, Object>) paramsProp.get("properties");
      assertNotNull(nestedProps);
      assertTrue(nestedProps.containsKey("C_Order_ID"));
    }

    @Test
    @DisplayName("process tool skips fields without AD_Column")
    @SuppressWarnings("unchecked")
    void processToolSkipsFieldsWithoutColumn() {
      SFSpec spec = createProcessSpec(SPEC_COMPLETE_ORDER);
      when(spec.getProcess()).thenReturn(null);
      when(spec.getId()).thenReturn("spec-id-1");

      OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);

      SFEntity entity = mock(SFEntity.class);
      when(entity.getId()).thenReturn("entity-id-1");
      when(entityCriteria.list()).thenReturn(List.of(entity));

      OBCriteria<SFField> fieldCriteria = mock(OBCriteria.class);
      when(mockOBDal.createCriteria(SFField.class)).thenReturn(fieldCriteria);

      SFField field = mock(SFField.class);
      when(field.getADColumn()).thenReturn(null);
      when(fieldCriteria.list()).thenReturn(List.of(field));

      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      McpToolDefinition tool = tools.stream()
          .filter(t -> "complete_order".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(tool);

      Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
      Map<String, Object> paramsProp = (Map<String, Object>) props.get("parameters");
      // No nested properties since the only field had no column
      assertFalse(paramsProp.containsKey("properties"));
    }
  }
}
