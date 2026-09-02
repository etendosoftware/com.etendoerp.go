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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;
import com.etendoerp.go.schemaforge.util.NeoReportContract;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

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
    // ETP-4510 BUG-3: addWindowSpec now routes every spec (windowed or windowless)
    // through hasWindowAccessForSpec instead of short-circuiting on a null AD_Window.
    // Default all specs to accessible here so the many pre-existing tests that build a
    // windowless spec (spec.getADWindow() == null) without caring about access keep
    // passing; tests that specifically exercise the access-denied path override this
    // default with a spec-specific stub.
    accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(any(), anyString()))
        .thenReturn(true);
    // ETP-4596: processSpec's "R" branch now additionally gates on hasReportSpecAccess.
    // Default every report spec to accessible here for the same reason as the
    // hasWindowAccessForSpec default above — tests that specifically exercise report-spec
    // access denial override this with a spec-specific stub.
    accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(any(), anyString()))
        .thenReturn(true);
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
  private void mockEmptyEntities() {
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

  // ── report contract fixtures (ETP-4793 / IMP-19) ──────────────────────
  //
  // A report tool is now emitted only for a handler that DECLARES a report contract, and its
  // `parameters` schema is built from that declaration. These are resolved through the real
  // NeoReportCallability.contractOf at class-init time, i.e. before any test installs a static
  // mock over that class.

  /** A real report that takes no inputs (the inventory-stock case). */
  private static final Optional<NeoReportContract> NO_INPUT_CONTRACT = declaredContract(List.of());

  /** A report with two mandatory dates, a closed set, and optional filters (the aging case). */
  private static final Optional<NeoReportContract> RICH_CONTRACT = declaredContract(List.of(
      NeoReportParam.required("dateFrom", NeoReportParam.TYPE_DATE, "Start date."),
      NeoReportParam.required("dateTo", NeoReportParam.TYPE_DATE, "End date."),
      NeoReportParam.options("recOrPay", "Which side.", List.of("RECEIVABLES", "PAYABLES")),
      NeoReportParam.optional("daysStep", NeoReportParam.TYPE_INTEGER, "Bucket width."),
      NeoReportParam.optional("showDetails", NeoReportParam.TYPE_BOOLEAN, "Per-document rows.")));

  private static Optional<NeoReportContract> declaredContract(List<NeoReportParam> params) {
    NeoHandler handler = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }

      @Override
      public Optional<List<NeoReportParam>> reportParameters() {
        return Optional.of(params);
      }
    };
    return NeoReportCallability.contractOf(handler, "testReportHandler");
  }

  @Nested
  @DisplayName("generateTools — scope permission resolution")
  class ScopePermissionTests {

    @Test
    @DisplayName("neo:read scope includes neo_discover when no specs exist")
    void readScopeIncludesDiscover() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      List<String> names = toolNames(tools);
      // Read access always yields neo_discover + docs + neo_widget + neo_vector_search when no
      // specs exist. These are built-in read tools (ETP-4284 / ETP-5123).
      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("docs"));
      assertTrue(names.contains(McpConstants.TOOL_NEO_WIDGET));
      assertTrue(names.contains(McpConstants.TOOL_NEO_VECTOR_SEARCH));
      assertEquals(4, tools.size());
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
      mockEmptyEntities();

      SFSpec reportSpec = createReportSpec(SPEC_PRINT_INVOICE);
      when(reportSpec.getProcess()).thenReturn(null);
      mockEmptyEntities();

      mockSpecCriteria(List.of(windowSpec, processSpec, reportSpec));

      // ETP-4255: a generate_* tool is only emitted for a callable (NeoHandler-backed)
      // report spec.
      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.resolveReportContract(reportSpec))
            .thenReturn(NO_INPUT_CONTRACT);

        List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:*"));
        List<String> names = toolNames(tools);

        assertTrue(names.contains("neo_discover"));
        assertTrue(names.contains("neo_list"));
        assertTrue(names.contains("neo_create"));
        assertTrue(names.contains("complete_order"));
        assertTrue(names.contains("generate_print_invoice"));
      }
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
    @DisplayName("window spec with null AD_Window is accessible when hasWindowAccessForSpec grants it")
    void windowSpecNullWindowAllowedProceedsNormally() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("neo_list"));
      assertTrue(names.contains("neo_get"));
      assertTrue(names.contains("neo_selectors"));
      assertTrue(names.contains("neo_defaults"));
      assertTrue(names.contains("neo_schema"));
    }

    /**
     * ETP-4510 BUG-3 (cycle 3): before this fix, {@code addWindowSpec} short-circuited on
     * {@code spec.getADWindow() == null}, granting access to every windowless/combination
     * spec unconditionally — including a caller with no role assigned. Verifies that a
     * windowless spec denied by {@code hasWindowAccessForSpec} is now correctly excluded
     * from the CRUD spec catalog instead of silently allowed.
     */
    @Test
    @DisplayName("window spec with null AD_Window is excluded when hasWindowAccessForSpec denies it")
    void windowSpecNullWindowDeniedIsExcluded() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(false);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      // No CRUD tools since there are no accessible window specs; only the
      // read-scope baseline tools (neo_discover + docs + neo_widget + neo_vector_search) are
      // present.
      assertFalse(names.contains("neo_list"));
      assertFalse(names.contains("neo_get"));
      assertFalse(names.contains("neo_create"));
      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("docs"));
      assertTrue(names.contains(McpConstants.TOOL_NEO_WIDGET));
      assertTrue(names.contains(McpConstants.TOOL_NEO_VECTOR_SEARCH));
      assertEquals(4, tools.size());
    }

    @Test
    @DisplayName("window spec with accessible AD_Window is included")
    void windowSpecWithAccessibleWindow() {
      SFSpec spec = createWindowSpecWithWindow(SPEC_SALES_ORDER, WINDOW_ID);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(true);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertTrue(toolNames(tools).contains("neo_list"));
    }

    @Test
    @DisplayName("window spec with denied AD_Window is excluded")
    void windowSpecWithDeniedWindow() {
      SFSpec spec = createWindowSpecWithWindow(SPEC_SALES_ORDER, WINDOW_ID);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(spec, "GET")).thenReturn(false);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      // No CRUD tools since there are no accessible window specs; only the
      // read-scope baseline tools (neo_discover + docs + neo_widget + neo_vector_search) are
      // present.
      assertFalse(names.contains("neo_list"));
      assertFalse(names.contains("neo_get"));
      assertFalse(names.contains("neo_create"));
      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("docs"));
      assertTrue(names.contains(McpConstants.TOOL_NEO_WIDGET));
      assertTrue(names.contains(McpConstants.TOOL_NEO_VECTOR_SEARCH));
      assertEquals(4, tools.size());
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
      List<String> names = toolNames(tools);

      // No window specs => no CRUD/window tools. Only the read-scope baseline
      // tools (neo_discover + docs + neo_widget + neo_vector_search) are present.
      assertFalse(names.contains("neo_list"));
      assertFalse(names.contains("neo_create"));
      assertFalse(names.contains("neo_update"));
      assertFalse(names.contains("neo_delete"));
      assertTrue(names.contains("neo_discover"));
      assertTrue(names.contains("docs"));
      assertTrue(names.contains(McpConstants.TOOL_NEO_WIDGET));
      assertTrue(names.contains(McpConstants.TOOL_NEO_VECTOR_SEARCH));
      assertEquals(4, tools.size());
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
      mockEmptyEntities();
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
      mockEmptyEntities();
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      assertTrue(toolNames(tools).contains("complete_order"));
    }

    @Test
    @DisplayName("process tool name is kebab-to-snake of spec name")
    void processToolNameIsSnakeCase() {
      SFSpec spec = createProcessSpec("multi-step-process");
      when(spec.getProcess()).thenReturn(null);
      mockEmptyEntities();
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
      mockEmptyEntities();
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
  @DisplayName("generateTools — report specs (ETP-4255 callability gate)")
  class ReportSpecTests {

    private MockedStatic<NeoReportCallability> callabilityMock;

    @BeforeEach
    void setUpCallability() {
      callabilityMock = mockStatic(NeoReportCallability.class);
    }

    @AfterEach
    void tearDownCallability() {
      if (callabilityMock != null) {
        callabilityMock.close();
      }
    }

    /**
     * A NEO-native callable report spec (backed by a NeoHandler) with neo:report scope
     * emits a generate_* tool.
     */
    @Test
    @DisplayName("callable report spec with neo:report scope generates tool")
    void callableReportSpecGeneratesTool() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      mockEmptyEntities();
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("generate_print_invoice"));
    }

    /**
     * A non-callable report spec (no NEO-native handler) emits NO generate_* tool even with
     * neo:report scope — Jasper/AD_Process reports are never executed by Etendo Go (ETP-4255).
     */
    @Test
    @DisplayName("non-callable report spec emits no generate_ tool")
    void nonCallableReportSpecEmitsNoTool() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(Optional.empty());
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));

      assertFalse(toolNames(tools).contains("generate_print_invoice"),
          "Non-callable report specs must not produce a generate_* tool");
    }

    /**
     * ETP-4596: a callable report spec whose constituent window is inaccessible to the
     * current role (e.g. bank-statements once its entity carries a populated
     * {@code AD_TAB_ID}) must not surface a generate_* tool, even though it is callable and
     * the caller holds the {@code neo:report} scope. Before this fix, RBAC was never
     * consulted here at all.
     *
     * <p>The callable half is stubbed as a resolved <em>contract</em> rather than as
     * {@code isReportCallable}, which is what ETP-4793 replaced it with (IMP-19): the point of
     * this test is that access is denied to a spec that would otherwise have produced a tool, so
     * it has to stub whatever the emitting branch actually asks. Stubbing the retired predicate
     * would leave the test green for the wrong reason — the access gate short-circuits before the
     * contract lookup, so an unused stub is indistinguishable from a correct one.</p>
     */
    @Test
    @DisplayName("callable report spec denied by hasReportSpecAccess emits no tool")
    void callableReportSpecDeniedByRbacEmitsNoTool() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(spec, "GET")).thenReturn(false);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));

      assertFalse(toolNames(tools).contains("generate_print_invoice"),
          "A report spec denied by hasReportSpecAccess must not produce a generate_* tool");
    }

    @Test
    @DisplayName("callable report spec without neo:report scope is excluded")
    void reportSpecWithoutReportScope() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertFalse(toolNames(tools).contains("generate_print_invoice"));
    }

    @Test
    @DisplayName("report tool includes format parameter")
    @SuppressWarnings("unchecked")
    void reportToolIncludesFormatParam() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      mockEmptyEntities();
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

    /**
     * The defect IMP-19 records: `parameters` was published as a bare
     * {@code {"type":"object"}} for all eight report tools, because the schema was built from
     * {@code ETGO_SF_FIELD} rows and every report spec has none — report inputs like
     * {@code dateFrom} are not AD columns of any table, so no amount of configuration could
     * have filled them in. The handler's declaration is the only place they exist.
     */
    @Test
    @DisplayName("report tool publishes the declared parameters, typed and named")
    @SuppressWarnings("unchecked")
    void reportToolPublishesDeclaredParameters() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(RICH_CONTRACT);
      mockEmptyEntities();
      mockSpecCriteria(List.of(spec));

      Map<String, Object> parameters = reportParametersSchema(
          registry.generateTools(scopesOf("neo:report")));
      Map<String, Object> props = (Map<String, Object>) parameters.get("properties");

      assertEquals(Set.of("dateFrom", "dateTo", "recOrPay", "daysStep", "showDetails"),
          props.keySet());
      // Only the two the handler cannot run without.
      assertEquals(List.of("dateFrom", "dateTo"), parameters.get("required"));

      // A date is not a string: IMP-16 recorded silent date corruption from that conflation,
      // so the expected pattern is spelled out in the description too.
      Map<String, Object> dateFrom = (Map<String, Object>) props.get("dateFrom");
      assertEquals("string", dateFrom.get("type"));
      assertEquals("date", dateFrom.get("format"));
      assertTrue(((String) dateFrom.get("description")).contains("yyyy-MM-dd"));

      // A closed set is published as an enum, so an agent cannot guess "receivable".
      Map<String, Object> recOrPay = (Map<String, Object>) props.get("recOrPay");
      assertEquals(List.of("RECEIVABLES", "PAYABLES"), recOrPay.get("enum"));

      assertEquals("integer", ((Map<String, Object>) props.get("daysStep")).get("type"));
      assertEquals("boolean", ((Map<String, Object>) props.get("showDetails")).get("type"));
    }

    @Test
    @DisplayName("a report with no inputs publishes no properties and no required list")
    @SuppressWarnings("unchecked")
    void reportToolWithoutParametersPublishesEmptySchema() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      mockEmptyEntities();
      mockSpecCriteria(List.of(spec));

      Map<String, Object> parameters = reportParametersSchema(
          registry.generateTools(scopesOf("neo:report")));

      assertTrue(((Map<String, Object>) parameters.get("properties")).isEmpty());
      assertFalse(parameters.containsKey("required"),
          "nothing is required, so nothing should be listed");
    }

    /**
     * {@code format} used to be advertised as "pdf, xlsx, csv (default: pdf)" and never read —
     * a request for a PDF was answered with JSON. It is now an enum of what is actually served.
     */
    @Test
    @DisplayName("format is an enum of the formats the handler actually serves")
    @SuppressWarnings("unchecked")
    void formatEnumMatchesWhatIsServed() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      mockEmptyEntities();
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:report"));
      Map<String, Object> props = (Map<String, Object>) reportTool(tools)
          .getInputSchema().get("properties");
      Map<String, Object> format = (Map<String, Object>) props.get("format");

      assertEquals(List.of("json"), format.get("enum"));
      assertTrue(((String) format.get("description")).contains("json"));
    }

    private McpToolDefinition reportTool(List<McpToolDefinition> tools) {
      McpToolDefinition tool = tools.stream()
          .filter(t -> t.getName().startsWith("generate_"))
          .findFirst()
          .orElse(null);
      assertNotNull(tool);
      return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reportParametersSchema(List<McpToolDefinition> tools) {
      Map<String, Object> props = (Map<String, Object>) reportTool(tools)
          .getInputSchema().get("properties");
      return (Map<String, Object>) props.get("parameters");
    }

    @Test
    @DisplayName("report tool description includes spec description when available")
    void reportToolIncludesDescription() {
      SFSpec spec = createReportSpec(SPEC_PRINT_INVOICE);
      when(spec.getProcess()).thenReturn(null);
      when(spec.getDescription()).thenReturn("Generates a PDF invoice");
      callabilityMock.when(() -> NeoReportCallability.resolveReportContract(spec))
          .thenReturn(NO_INPUT_CONTRACT);
      mockEmptyEntities();
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
      mockEmptyEntities();

      SFSpec reportSpec = createReportSpec(SPEC_PRINT_INVOICE);
      when(reportSpec.getProcess()).thenReturn(null);
      mockEmptyEntities();

      mockSpecCriteria(List.of(windowSpec, processSpec, reportSpec));

      // ETP-4255: a generate_* tool is only emitted for a callable (NeoHandler-backed)
      // report spec.
      try (MockedStatic<NeoReportCallability> callabilityMock =
          mockStatic(NeoReportCallability.class)) {
        callabilityMock.when(() -> NeoReportCallability.resolveReportContract(reportSpec))
            .thenReturn(NO_INPUT_CONTRACT);

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

    @Test
    @DisplayName("neo_generate_amortization_plan returns true (ETP-4232)")
    void generateAmortizationPlanIsCrudTool() {
      assertTrue(ToolRegistry.isCrudTool(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN));
    }
  }

  // ── neo_generate_amortization_plan tool (ETP-4232) ────────────────────────

  @Nested
  @DisplayName("generateTools — neo_generate_amortization_plan (ETP-4232)")
  class GenerateAmortizationPlanToolTests {

    @Test
    @DisplayName("neo:process scope registers neo_generate_amortization_plan even with no window specs")
    void processScopeRegistersAmortizationTool() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      List<String> names = toolNames(tools);
      assertTrue(names.contains(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN),
          "neo_generate_amortization_plan must be registered with neo:process scope");
    }

    @Test
    @DisplayName("neo:read scope does NOT register neo_generate_amortization_plan")
    void readScopeDoesNotRegisterAmortizationTool() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertFalse(toolNames(tools).contains(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN),
          "neo_generate_amortization_plan must NOT be registered with read-only scope");
    }

    @Test
    @DisplayName("neo:* scope registers neo_generate_amortization_plan")
    void wildcardScopeRegistersAmortizationTool() {
      SFSpec windowSpec = createWindowSpec(SPEC_SALES_ORDER);
      when(windowSpec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(windowSpec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:*"));

      assertTrue(toolNames(tools).contains(McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN));
    }

    @Test
    @DisplayName("neo_generate_amortization_plan tool has assetId as required property")
    @SuppressWarnings("unchecked")
    void amortizationToolHasAssetIdRequired() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      McpToolDefinition tool = tools.stream()
          .filter(t -> McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN.equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(tool, "Tool must be present");

      Map<String, Object> schema = tool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertNotNull(required);
      assertTrue(required.contains("assetId"), "assetId must be in required list");

      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertNotNull(props);
      assertTrue(props.containsKey("assetId"), "assetId must be in properties");
    }

    @Test
    @DisplayName("resolveSpecName returns null for neo_generate_amortization_plan (CRUD tool)")
    void resolveSpecNameReturnNullForAmortizationTool() throws Exception {
      JSONObject args = new JSONObject();
      args.put("assetId", "ASSET-001");

      String result = ToolRegistry.resolveSpecName(
          McpConstants.TOOL_GENERATE_AMORTIZATION_PLAN, args);

      // isCrudTool == true, so it reads "spec" from args — which is absent, returning null
      assertNull(result, "resolveSpecName must return null for amortization tool (no spec arg)");
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

  // ── docs tool registration ────────────────────────────────────────────────

  @Nested
  @DisplayName("generateTools — docs tool")
  class DocsToolTests {

    @Test
    @DisplayName("neo:read scope registers the docs tool")
    void readScopeRegistersDocs() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertTrue(toolNames(tools).contains("docs"), "docs should be registered with read scope");
    }

    @Test
    @DisplayName("neo:* scope registers the docs tool")
    void wildcardScopeRegistersDocs() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:*"));

      assertTrue(toolNames(tools).contains("docs"), "docs should be registered with wildcard scope");
    }

    @Test
    @DisplayName("scope without read access (only neo:process) does not register the docs tool")
    void noReadScopeDoesNotRegisterDocs() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:process"));

      assertFalse(toolNames(tools).contains("docs"),
          "docs should NOT be registered without read access");
    }

    @Test
    @DisplayName("docs tool has required topic field")
    @SuppressWarnings("unchecked")
    void docsToolHasRequiredTopic() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      McpToolDefinition docsTool = tools.stream()
          .filter(t -> "docs".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(docsTool, "docs tool should be present");

      Map<String, Object> schema = docsTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertNotNull(required);
      assertTrue(required.contains("topic"));

      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertTrue(props.containsKey("topic"));
      assertTrue(props.containsKey("tokens"));
      assertTrue(props.containsKey("type"));
    }

    @Test
    @DisplayName("resolveSpecName returns null for the docs tool")
    void resolveSpecNameReturnsNullForDocs() {
      assertNull(ToolRegistry.resolveSpecName("docs", null));
    }
  }

  // ── neo_action registration ──────────────────────────────────────────────

  @Nested
  @DisplayName("generateTools — neo_action tool")
  class NeoActionToolTests {

    @Test
    @DisplayName("write scope registers neo_action alongside write CRUD tools")
    void writeScopeRegistersNeoAction() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));
      List<String> names = toolNames(tools);

      assertTrue(names.contains("neo_action"), "neo_action should be registered with write scope");
    }

    @Test
    @DisplayName("read-only scope does not register neo_action")
    void readScopeDoesNotRegisterNeoAction() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      List<String> names = toolNames(tools);

      assertFalse(names.contains("neo_action"), "neo_action should NOT be registered with read-only scope");
    }

    @Test
    @DisplayName("neo_action tool has required fields: spec, entity, id, action")
    @SuppressWarnings("unchecked")
    void neoActionToolHasRequiredFields() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));

      McpToolDefinition actionTool = tools.stream()
          .filter(t -> "neo_action".equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(actionTool, "neo_action tool should be present");

      Map<String, Object> schema = actionTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertNotNull(required);
      assertTrue(required.contains("spec"));
      assertTrue(required.contains("entity"));
      assertTrue(required.contains("id"));
      assertTrue(required.contains("action"));

      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertTrue(props.containsKey("parameters"), "neo_action should have optional parameters prop");
    }

    @Test
    @DisplayName("isCrudTool returns true for neo_action")
    void isCrudToolReturnsTrueForNeoAction() {
      assertTrue(ToolRegistry.isCrudTool("neo_action"));
    }

    @Test
    @DisplayName("neo:* scope registers neo_action")
    void wildcardScopeRegistersNeoAction() {
      SFSpec spec = createWindowSpec(SPEC_SALES_ORDER);
      when(spec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(spec));

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:*"));
      assertTrue(toolNames(tools).contains("neo_action"));
    }
  }

  // ── write-tool spec enum split (ETP-4254) ─────────────────────────────────

  /**
   * ETP-4254 AC#1: the write tools (neo_create/neo_update/neo_delete) get their own, narrower
   * spec enum. A monitor/log spec — every included entity configured read-only — stays fully
   * readable but must not be offered as a write target.
   *
   * <p>{@code isReadOnlySpec} itself is unit-tested in {@code McpToolRouterSupportTest}; it is
   * stubbed per spec here so these tests cover only the {@code ToolRegistry} enum wiring.</p>
   */
  @Nested
  @DisplayName("generateTools — write-tool spec enum (ETP-4254)")
  class WriteCatalogTests {

    private static final String SPEC_MONITOR = "monitor-verifactu";

    @SuppressWarnings("unchecked")
    private List<String> specEnumOf(List<McpToolDefinition> tools, String toolName) {
      McpToolDefinition tool = tools.stream()
          .filter(t -> toolName.equals(t.getName()))
          .findFirst()
          .orElse(null);
      assertNotNull(tool, toolName + " must be registered");
      Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
      Map<String, Object> specProp = (Map<String, Object>) props.get("spec");
      assertNotNull(specProp);
      return (List<String>) specProp.get("enum");
    }

    /** One all-method spec + one read-only spec, with capability predicates stubbed per spec. */
    private MockedStatic<McpToolRouterSupport> setupMixedCatalog(SFSpec writable,
        SFSpec readOnly) {
      MockedStatic<McpToolRouterSupport> supportMock = mockStatic(McpToolRouterSupport.class);
      supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(any())).thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(writable, "POST"))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(writable, "PUT"))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(writable, "DELETE"))
          .thenReturn(true);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(readOnly, "POST"))
          .thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(readOnly, "PUT"))
          .thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(readOnly, "DELETE"))
          .thenReturn(false);
      return supportMock;
    }

    @Test
    @DisplayName("a read-only spec is in the read enum but not in the write enum")
    void readOnlySpecIsReadableButNotWritable() {
      SFSpec writable = createWindowSpec(SPEC_SALES_ORDER);
      when(writable.getADWindow()).thenReturn(null);
      SFSpec readOnly = createWindowSpec(SPEC_MONITOR);
      when(readOnly.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(writable, readOnly));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          setupMixedCatalog(writable, readOnly)) {
        List<McpToolDefinition> tools =
            registry.generateTools(scopesOf("neo:read", "neo:write"));

        List<String> readEnum = specEnumOf(tools, "neo_list");
        assertTrue(readEnum.contains(SPEC_SALES_ORDER));
        assertTrue(readEnum.contains(SPEC_MONITOR),
            "a read-only monitor spec must stay readable");

        for (String writeTool : List.of("neo_create", "neo_update", "neo_delete")) {
          List<String> writeEnum = specEnumOf(tools, writeTool);
          assertTrue(writeEnum.contains(SPEC_SALES_ORDER), writeTool + " must keep sales-order");
          assertFalse(writeEnum.contains(SPEC_MONITOR),
              writeTool + " must not offer a read-only spec as a target");
        }
      }
    }

    /**
     * The {@code buildActionTool} judgement call (ETP-4254): button actions are served by the
     * {@code /action/*} sub-endpoint, which is deliberately NOT gated by the
     * {@code ETGO_SF_ENTITY} method flags, so a read-only-CRUD monitor may still legitimately
     * expose an action. neo_action therefore keeps the READ enum.
     */
    @Test
    @DisplayName("neo_action keeps the read enum — actions are not gated by the method flags")
    void actionToolKeepsTheReadEnum() {
      SFSpec writable = createWindowSpec(SPEC_SALES_ORDER);
      when(writable.getADWindow()).thenReturn(null);
      SFSpec readOnly = createWindowSpec(SPEC_MONITOR);
      when(readOnly.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(writable, readOnly));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          setupMixedCatalog(writable, readOnly)) {
        List<McpToolDefinition> tools =
            registry.generateTools(scopesOf("neo:read", "neo:write"));

        List<String> actionEnum = specEnumOf(tools, "neo_action");
        assertTrue(actionEnum.contains(SPEC_SALES_ORDER));
        assertTrue(actionEnum.contains(SPEC_MONITOR),
            "a read-only-CRUD spec may still expose button actions");
      }
    }

    @Test
    @DisplayName("a mixed spec is offered only by the write tool matching its enabled method")
    void mixedSpecUsesPerMethodWriteEnums() {
      SFSpec allMethods = createWindowSpec(SPEC_SALES_ORDER);
      when(allMethods.getADWindow()).thenReturn(null);
      SFSpec putOnly = createWindowSpec(SPEC_MONITOR);
      when(putOnly.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(allMethods, putOnly));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          mockStatic(McpToolRouterSupport.class)) {
        supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(any())).thenReturn(false);
        for (String method : List.of("POST", "PUT", "DELETE")) {
          supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(allMethods, method))
              .thenReturn(true);
        }
        supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(putOnly, "POST"))
            .thenReturn(false);
        supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(putOnly, "PUT"))
            .thenReturn(true);
        supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(putOnly, "DELETE"))
            .thenReturn(false);

        List<McpToolDefinition> tools =
            registry.generateTools(scopesOf("neo:read", "neo:write"));

        assertFalse(specEnumOf(tools, "neo_create").contains(SPEC_MONITOR));
        assertTrue(specEnumOf(tools, "neo_update").contains(SPEC_MONITOR));
        assertFalse(specEnumOf(tools, "neo_delete").contains(SPEC_MONITOR));
      }
    }

    @Test
    @DisplayName("a catalog of only read-only specs registers no write CRUD tools")
    void readOnlyOnlyCatalogRegistersNoWriteTools() {
      SFSpec readOnly = createWindowSpec(SPEC_MONITOR);
      when(readOnly.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(readOnly));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          mockStatic(McpToolRouterSupport.class)) {
        supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(any())).thenReturn(false);
        supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(readOnly, "POST"))
            .thenReturn(false);
        supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(readOnly, "PUT"))
            .thenReturn(false);
        supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(readOnly, "DELETE"))
            .thenReturn(false);

        List<McpToolDefinition> tools =
            registry.generateTools(scopesOf("neo:read", "neo:write"));
        List<String> names = toolNames(tools);

        assertTrue(names.contains("neo_list"), "reads must still be available");
        assertFalse(names.contains("neo_create"));
        assertFalse(names.contains("neo_update"));
        assertFalse(names.contains("neo_delete"));
        // neo_batch has no spec enum to narrow (its ops name their spec inline) and
        // neo_action is not gated by the method flags, so both stay registered.
        assertTrue(names.contains("neo_batch"));
        assertTrue(names.contains("neo_action"));
      }
    }
  }

  // ── neo_widget tool (business widgets, gap G4 / ETP-4284) ─────────────────

  @Nested
  @DisplayName("generateTools — neo_widget tool (ETP-4284)")
  class NeoWidgetToolTests {

    /** The 9 widget enum values the tool must expose, in canonical order. */
    private static final List<String> EXPECTED_WIDGETS = List.of(
        "kpis", "revenue-trend", "pending-tasks", "activity", "recent-invoices",
        "best-products", "best-sellers", "pending-amounts", "top-clients");

    private McpToolDefinition findWidgetTool(List<McpToolDefinition> tools) {
      return tools.stream()
          .filter(t -> McpConstants.TOOL_NEO_WIDGET.equals(t.getName()))
          .findFirst()
          .orElse(null);
    }

    @Test
    @DisplayName("neo:read scope registers neo_widget even with no specs")
    void readScopeRegistersWidgetTool() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

      assertTrue(toolNames(tools).contains(McpConstants.TOOL_NEO_WIDGET),
          "neo_widget must be registered as a read tool");
    }

    @Test
    @DisplayName("neo:* scope registers neo_widget")
    void wildcardScopeRegistersWidgetTool() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:*"));

      assertTrue(toolNames(tools).contains(McpConstants.TOOL_NEO_WIDGET));
    }

    @Test
    @DisplayName("write-only scope (no read) does NOT register neo_widget")
    void writeOnlyScopeDoesNotRegisterWidgetTool() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));

      assertFalse(toolNames(tools).contains(McpConstants.TOOL_NEO_WIDGET),
          "neo_widget must NOT be registered without read access");
    }

    @Test
    @DisplayName("neo_widget exposes the 9 widget enum values")
    @SuppressWarnings("unchecked")
    void widgetToolHasNineEnumValues() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      McpToolDefinition widgetTool = findWidgetTool(tools);
      assertNotNull(widgetTool, "neo_widget tool must be present");

      Map<String, Object> schema = widgetTool.getInputSchema();
      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertNotNull(props);
      Map<String, Object> widgetProp = (Map<String, Object>) props.get(McpConstants.PARAM_WIDGET);
      assertNotNull(widgetProp, "neo_widget must declare a 'widget' property");

      List<String> enumValues = (List<String>) widgetProp.get("enum");
      assertNotNull(enumValues, "widget property must declare an enum");
      assertEquals(9, enumValues.size(), "neo_widget must expose exactly 9 widgets");
      for (String widget : EXPECTED_WIDGETS) {
        assertTrue(enumValues.contains(widget), "enum must contain widget '" + widget + "'");
      }
    }

    @Test
    @DisplayName("neo_widget requires the 'widget' argument and accepts optional 'params'")
    @SuppressWarnings("unchecked")
    void widgetToolRequiresWidgetAndAcceptsParams() {
      mockSpecCriteria(Collections.emptyList());

      List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
      McpToolDefinition widgetTool = findWidgetTool(tools);
      assertNotNull(widgetTool);

      Map<String, Object> schema = widgetTool.getInputSchema();
      List<String> required = (List<String>) schema.get("required");
      assertNotNull(required);
      assertTrue(required.contains(McpConstants.PARAM_WIDGET),
          "'widget' must be a required argument");
      assertFalse(required.contains(McpConstants.PARAM_PARAMS),
          "'params' must be optional");

      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertTrue(props.containsKey(McpConstants.PARAM_PARAMS),
          "neo_widget must declare an optional 'params' property");
    }

    /**
     * ETP-4284 / G4 — still enforced after ETP-4254 replaced the hardcoded
     * {@code "dashboard"} literal with the data-driven {@code isCatalogExcludedSpec} rule
     * (handler-only AND no {@code /action} route). The predicate itself is unit-tested in
     * {@code McpToolRouterSupportTest}; here it is stubbed per spec so this test verifies only
     * the {@code ToolRegistry} wiring (which spec lands in which enum), independently of the
     * entity-query mocking.
     */
    @Test
    @DisplayName("handler-only (dashboard/widget) spec is NOT added to the CRUD spec enum")
    @SuppressWarnings("unchecked")
    void handlerOnlySpecExcludedFromCrudSpecEnum() {
      SFSpec windowSpec = createWindowSpec(SPEC_SALES_ORDER);
      when(windowSpec.getADWindow()).thenReturn(null);

      SFSpec dashboardSpec = createWindowSpec(McpConstants.SPEC_DASHBOARD);
      when(dashboardSpec.getADWindow()).thenReturn(null);

      mockSpecCriteria(List.of(windowSpec, dashboardSpec));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          mockStatic(McpToolRouterSupport.class)) {
        supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(windowSpec))
            .thenReturn(false);
        supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(dashboardSpec))
            .thenReturn(true);
        List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));

        McpToolDefinition listTool = tools.stream()
            .filter(t -> "neo_list".equals(t.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(listTool, "neo_list must still be produced for the window spec");

        Map<String, Object> props =
            (Map<String, Object>) listTool.getInputSchema().get("properties");
        Map<String, Object> specProp = (Map<String, Object>) props.get("spec");
        List<String> enumValues = (List<String>) specProp.get("enum");
        assertNotNull(enumValues);
        assertTrue(enumValues.contains(SPEC_SALES_ORDER));
        assertFalse(enumValues.contains(McpConstants.SPEC_DASHBOARD),
            "handler-only spec must NOT appear in the CRUD spec enum (ETP-4284 / G4)");
      }
    }

    /**
     * ETP-4254 regression guard: a tab-less spec that still serves an {@code /action} route
     * ({@code not-posted-documents}' {@code post} / {@code bulk-post}) is NOT catalog-excluded,
     * so it must reach {@code accessibleWindowSpecs} — the enum {@code neo_action} is built
     * from. Collapsing "handler-only" and "catalog-excluded" into one rule silently removed
     * that action from the agent.
     */
    @Test
    @DisplayName("handler-only spec with an /action route DOES reach the neo_action enum")
    @SuppressWarnings("unchecked")
    void actionServingHandlerOnlySpecReachesActionEnum() {
      SFSpec actionSpec = createWindowSpec("not-posted-documents");
      when(actionSpec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(actionSpec));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          mockStatic(McpToolRouterSupport.class)) {
        supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(actionSpec))
            .thenReturn(false);

        List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:write"));

        McpToolDefinition actionTool = tools.stream()
            .filter(t -> "neo_action".equals(t.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(actionTool, "neo_action must be produced for an action-serving spec");

        Map<String, Object> props =
            (Map<String, Object>) actionTool.getInputSchema().get("properties");
        Map<String, Object> specProp = (Map<String, Object>) props.get("spec");
        List<String> enumValues = (List<String>) specProp.get("enum");
        assertNotNull(enumValues);
        assertTrue(enumValues.contains("not-posted-documents"),
            "a tab-less spec that serves /action must remain callable through neo_action");
      }
    }

    @Test
    @DisplayName("a handler-only-spec catalog produces no CRUD tools")
    void handlerOnlyCatalogProducesNoCrudTools() {
      SFSpec dashboardSpec = createWindowSpec(McpConstants.SPEC_DASHBOARD);
      when(dashboardSpec.getADWindow()).thenReturn(null);
      mockSpecCriteria(List.of(dashboardSpec));

      try (MockedStatic<McpToolRouterSupport> supportMock =
          mockStatic(McpToolRouterSupport.class)) {
        supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(dashboardSpec))
            .thenReturn(true);

        List<McpToolDefinition> tools = registry.generateTools(scopesOf("neo:read"));
        List<String> names = toolNames(tools);

        // No CRUD tools — the only spec is handler-only, so it is skipped. Only the
        // read-scope baseline tools (neo_discover + docs + neo_widget) are present.
        assertFalse(names.contains("neo_list"));
        assertFalse(names.contains("neo_get"));
        assertTrue(names.contains("neo_discover"));
        assertTrue(names.contains("docs"));
        assertTrue(names.contains(McpConstants.TOOL_NEO_WIDGET));
      }
    }

    @Test
    @DisplayName("isCrudTool returns true for neo_widget (spec resolution skipped)")
    void isCrudToolReturnsTrueForNeoWidget() {
      assertTrue(ToolRegistry.isCrudTool(McpConstants.TOOL_NEO_WIDGET));
    }

    @Test
    @DisplayName("resolveSpecName returns null for neo_widget (no spec arg)")
    void resolveSpecNameReturnsNullForNeoWidget() throws Exception {
      JSONObject args = new JSONObject();
      args.put(McpConstants.PARAM_WIDGET, "kpis");

      // isCrudTool == true, so resolveSpecName reads "spec" from args — absent, hence null.
      assertNull(ToolRegistry.resolveSpecName(McpConstants.TOOL_NEO_WIDGET, args));
    }

    @Test
    @DisplayName("WIDGET_ENTITY_BY_NAME maps every enum value to its backing entity")
    void widgetEntityMapHasCanonicalMapping() {
      Map<String, String> map = ToolRegistry.WIDGET_ENTITY_BY_NAME;
      assertEquals(9, map.size(), "exactly 9 widgets must be mapped");
      for (String widget : EXPECTED_WIDGETS) {
        assertNotNull(map.get(widget), "widget '" + widget + "' must map to an entity");
      }
      // Identity mappings for most widgets, but revenue-trend maps to the 'trends' entity.
      assertEquals("kpis", map.get("kpis"));
      assertEquals("trends", map.get("revenue-trend"));
      assertEquals("pending-tasks", map.get("pending-tasks"));
      assertEquals("top-clients", map.get("top-clients"));
    }

    @Test
    @DisplayName("WIDGET_ENTITY_BY_NAME is immutable")
    void widgetEntityMapIsImmutable() {
      try {
        ToolRegistry.WIDGET_ENTITY_BY_NAME.put("hacked", "nope");
        org.junit.jupiter.api.Assertions.fail("WIDGET_ENTITY_BY_NAME must be unmodifiable");
      } catch (UnsupportedOperationException expected) {
        // expected
      }
    }

    @Test
    @DisplayName("each widget has a semantic description surfaced to the agent")
    void everyWidgetHasDescription() {
      Map<String, String> desc = ToolRegistry.WIDGET_DESCRIPTION_BY_NAME;
      assertEquals(9, desc.size());
      for (String widget : EXPECTED_WIDGETS) {
        String text = desc.get(widget);
        assertNotNull(text, "widget '" + widget + "' must have a description");
        assertFalse(text.isBlank(), "widget '" + widget + "' description must not be blank");
      }
    }
  }
}
