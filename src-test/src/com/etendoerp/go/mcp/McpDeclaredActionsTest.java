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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.ReconciliationHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * ETP-5468 (CA1/CA7) — how the MCP layer publishes the named actions a handler declares:
 * <ul>
 *   <li>{@code neo_schema}: {@link McpActionsView#buildDeclaredResponse} renders the nine
 *       bank-reconciliation contracts;</li>
 *   <li>{@code neo_discover}: {@link McpToolRouterSupport#buildDiscoverSpec}'s three-way report
 *       branch (callable report / actions_only / not configured);</li>
 *   <li>{@code tools/list}: {@link ToolRegistry} adds the action report spec to the
 *       {@code neo_action} and {@code neo_schema} enums ONLY — never to {@code neo_list},
 *       {@code neo_get} or the write tools.</li>
 * </ul>
 * The real {@link ReconciliationHandler} declaration is used, so a drift in its contracts shows
 * up here too.
 */
@SuppressWarnings("java:S2187")
@DisplayName("MCP declared actions (ETP-5468)")
class McpDeclaredActionsTest {

  private static final String BANK_REC = "bank-reconciliation";
  private static final String QUALIFIER = "bank-reconciliation";
  private static final List<String> NINE_ACTIONS = List.of("pendingLines", "candidates",
      "autoMatch", "reconcileGroup", "reconcileDifference", "undoReconciliation",
      "removeOperation", "reactivateSelected", "applySuggestions");

  private static final Map<String, NeoActionContract> CONTRACTS =
      new ReconciliationHandler().actionContracts();

  private static List<String> strings(JSONArray arr) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < arr.length(); i++) {
      out.add(arr.getString(i));
    }
    return out;
  }

  private static SFEntity recEntity() {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn(BANK_REC);
    when(entity.getJavaQualifier()).thenReturn(QUALIFIER);
    when(entity.getADTab()).thenReturn(mock(Tab.class));
    return entity;
  }

  @SuppressWarnings("unchecked")
  private static OBCriteria<SFEntity> entityCriteria(OBDal dal, List<SFEntity> entities) {
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
    when(criteria.setMaxResults(Mockito.anyInt())).thenReturn(criteria);
    when(criteria.list()).thenReturn(entities);
    return criteria;
  }

  // ── neo_schema rendering ───────────────────────────────────────────────

  @Nested
  @DisplayName("McpActionsView.buildDeclaredResponse (neo_schema)")
  class DeclaredResponse {

    @Test
    @DisplayName("lists the nine actions, all invokable, in declaration order")
    void nineActions() throws Exception {
      JSONObject response = McpActionsView.buildDeclaredResponse(BANK_REC, BANK_REC, CONTRACTS);
      assertEquals(BANK_REC, response.getString("spec"));
      assertEquals(BANK_REC, response.getString("entity"));
      assertEquals(9, response.getInt("actionCount"));
      assertEquals(9, response.getInt("invokableCount"));
      JSONArray actions = response.getJSONArray("actions");
      List<String> names = new ArrayList<>();
      for (int i = 0; i < actions.length(); i++) {
        JSONObject a = actions.getJSONObject(i);
        names.add(a.getString("action"));
        assertEquals("neo_action", a.getString("invokeVia"));
        assertTrue(a.getString("idDescription").contains("financial account id"));
        assertFalse(a.getJSONObject("parameters").getBoolean("additionalProperties"));
      }
      assertEquals(NINE_ACTIONS, names);
      assertTrue(response.getString("hint").contains("neo_action"));
      assertTrue(response.getString("hint").contains("422"));
    }

    @Test
    @DisplayName("each contract carries the correct required list")
    void requiredLists() throws Exception {
      JSONArray actions = McpActionsView.buildDeclaredResponse(BANK_REC, BANK_REC, CONTRACTS)
          .getJSONArray("actions");
      Map<String, List<String>> expected = Map.of(
          "pendingLines", List.of(),
          "candidates", List.of("statementLineId"),
          "autoMatch", List.of(),
          "reconcileGroup", List.of("statementLineId"),
          "reconcileDifference", List.of("statementLineId"),
          "undoReconciliation", List.of("statementLineId"),
          "removeOperation", List.of("statementLineId", "transactionIds"),
          "reactivateSelected", List.of("statementLineId", "transactionIds"),
          "applySuggestions", List.of("groups"));
      for (int i = 0; i < actions.length(); i++) {
        JSONObject a = actions.getJSONObject(i);
        assertEquals(expected.get(a.getString("action")),
            strings(a.getJSONObject("parameters").getJSONArray("required")),
            a.getString("action"));
      }
    }

    @Test
    @DisplayName("an empty declaration renders an empty catalog")
    void emptyDeclaration() throws Exception {
      JSONObject response = McpActionsView.buildDeclaredResponse("x", "y",
          Collections.emptyMap());
      assertEquals(0, response.getInt("actionCount"));
      assertEquals(0, response.getJSONArray("actions").length());
    }
  }

  // ── neo_discover ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("McpToolRouterSupport.buildDiscoverSpec — report branch (neo_discover)")
  class Discover {

    private MockedStatic<OBDal> obDal;
    private MockedStatic<NeoHandlerLookup> lookup;
    private MockedStatic<NeoReportCallability> callability;
    private OBDal dal;
    private SFSpec spec;

    @BeforeEach
    void setUp() {
      dal = mock(OBDal.class);
      obDal = mockStatic(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      lookup = mockStatic(NeoHandlerLookup.class);
      callability = mockStatic(NeoReportCallability.class);
      callability.when(() -> NeoReportCallability.buildNotConfiguredMessage(anyString()))
          .thenAnswer(inv -> "not configured: " + inv.getArgument(0));
      spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-rec");
      when(spec.getName()).thenReturn(BANK_REC);
    }

    @AfterEach
    void tearDown() {
      obDal.close();
      lookup.close();
      callability.close();
    }

    @Test
    @DisplayName("a declared-actions spec → callable:false, status actions_only, the 9 actions")
    void actionsOnly() throws Exception {
      callability.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(false);
      entityCriteria(dal, List.of(recEntity()));
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER))
          .thenReturn(new ReconciliationHandler());

      JSONObject out = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

      assertTrue(out.getBoolean("isReport"));
      assertFalse(out.getBoolean("callable"));
      assertEquals(McpToolRouterSupport.STATUS_ACTIONS_ONLY, out.getString("status"));
      assertEquals("actions_only", out.getString("status"));
      assertEquals("Not a report generator; 'bank-reconciliation' serves named actions through "
          + "neo_action (entity bank-reconciliation).", out.getString("message"));
      assertEquals(BANK_REC, out.getString("actionEntity"));
      assertEquals(NINE_ACTIONS, strings(out.getJSONArray("actions")));
      assertTrue(out.getString("actionsHint").contains("neo_action"));
      assertTrue(out.getString("actionsHint").contains("view:\"actions\""));
      assertFalse(out.has("reportTool"));
    }

    @Test
    @DisplayName("a callable report → callable:true + reportTool, no status, actions not probed")
    void callableReport() throws Exception {
      callability.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(true);
      when(spec.getName()).thenReturn("inventory-stock-report");

      JSONObject out = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

      assertTrue(out.getBoolean("callable"));
      assertEquals(McpConstants.GENERATE_PREFIX
          + ToolRegistry.kebabToSnake("inventory-stock-report"), out.getString("reportTool"));
      assertFalse(out.has("status"));
      assertFalse(out.has("actions"));
      assertFalse(out.has("actionEntity"));
      Mockito.verify(dal, never()).createCriteria(SFEntity.class);
    }

    @Test
    @DisplayName("neither callable nor declaring actions → not_configured_for_report_generation")
    void notConfigured() throws Exception {
      callability.when(() -> NeoReportCallability.isReportCallable(spec)).thenReturn(false);
      when(spec.getName()).thenReturn("legacy-jasper");
      entityCriteria(dal, List.of(recEntity()));
      NeoHandler plain = context -> null;
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(anyString())).thenReturn(plain);

      JSONObject out = McpToolRouterSupport.buildDiscoverSpec(spec, "R", null, null, null);

      assertFalse(out.getBoolean("callable"));
      assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, out.getString("status"));
      assertEquals("not configured: legacy-jasper", out.getString("message"));
      assertFalse(out.has("actions"));
      assertFalse(out.has("actionEntity"));
    }

    @Test
    @DisplayName("a window spec gets none of the report / action keys")
    void windowSpecUntouched() throws Exception {
      JSONObject out = McpToolRouterSupport.buildDiscoverSpec(spec, "W", null, null, null);
      assertFalse(out.has("isReport"));
      assertFalse(out.has("actions"));
      assertFalse(out.has("status"));
    }
  }

  // ── tools/list enums ───────────────────────────────────────────────────

  @Nested
  @DisplayName("ToolRegistry — spec enums (tools/list)")
  class Enums {

    private MockedStatic<OBDal> obDal;
    private MockedStatic<NeoAccessUtils> accessMock;
    private MockedStatic<McpToolRouterSupport> supportMock;
    private MockedStatic<NeoReportCallability> callability;
    private MockedStatic<NeoHandlerLookup> lookup;
    private OBDal dal;
    private SFSpec windowSpec;
    private SFSpec recSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
      dal = mock(OBDal.class);
      obDal = mockStatic(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      accessMock = mockStatic(NeoAccessUtils.class);
      accessMock.when(() -> NeoAccessUtils.hasWindowAccessForSpec(any(), anyString()))
          .thenReturn(true);
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(any(), anyString()))
          .thenReturn(true);

      supportMock = mockStatic(McpToolRouterSupport.class);
      supportMock.when(() -> McpToolRouterSupport.isCatalogExcludedSpec(any())).thenReturn(false);
      supportMock.when(() -> McpToolRouterSupport.hasEntityWithMethod(any(), anyString()))
          .thenReturn(true);

      callability = mockStatic(NeoReportCallability.class);
      callability.when(() -> NeoReportCallability.resolveReportContract(any()))
          .thenReturn(Optional.empty());

      lookup = mockStatic(NeoHandlerLookup.class);
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER))
          .thenReturn(new ReconciliationHandler());

      windowSpec = mock(SFSpec.class);
      when(windowSpec.getName()).thenReturn("sales-order");
      when(windowSpec.getSpecType()).thenReturn("W");
      recSpec = mock(SFSpec.class);
      when(recSpec.getId()).thenReturn("spec-rec");
      when(recSpec.getName()).thenReturn(BANK_REC);
      when(recSpec.getSpecType()).thenReturn("R");

      entityCriteria(dal, List.of(recEntity()));
    }

    @AfterEach
    void tearDown() {
      obDal.close();
      accessMock.close();
      supportMock.close();
      callability.close();
      lookup.close();
    }

    @SuppressWarnings("unchecked")
    private void specs(SFSpec... all) {
      OBCriteria<SFSpec> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(SFSpec.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(List.of(all));
    }

    private List<McpToolDefinition> tools() {
      Set<String> scopes = new HashSet<>(List.of("neo:*"));
      return new ToolRegistry().generateTools(scopes);
    }

    @SuppressWarnings("unchecked")
    private List<String> specEnumOf(List<McpToolDefinition> tools, String toolName) {
      McpToolDefinition tool = tools.stream().filter(t -> toolName.equals(t.getName()))
          .findFirst().orElse(null);
      assertNotNull(tool, toolName + " must be registered");
      Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
      Map<String, Object> specProp = (Map<String, Object>) props.get("spec");
      assertNotNull(specProp, toolName + " has a spec argument");
      return (List<String>) specProp.get("enum");
    }

    @Test
    @DisplayName("bank-reconciliation joins neo_action and neo_schema only")
    void joinsActionAndSchemaOnly() {
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();

      assertTrue(specEnumOf(tools, "neo_action").contains(BANK_REC));
      assertTrue(specEnumOf(tools, "neo_schema").contains(BANK_REC));
      assertTrue(specEnumOf(tools, "neo_action").contains("sales-order"));
      for (String crudTool : List.of("neo_list", "neo_get", "neo_selectors", "neo_defaults",
          "neo_create", "neo_update", "neo_delete")) {
        List<String> values = specEnumOf(tools, crudTool);
        assertFalse(values.contains(BANK_REC), crudTool + " must not offer " + BANK_REC);
        assertTrue(values.contains("sales-order"), crudTool);
      }
    }

    @Test
    @DisplayName("the merged enum is sorted")
    void mergedEnumSorted() {
      SFSpec zeta = mock(SFSpec.class);
      when(zeta.getName()).thenReturn("zeta-window");
      when(zeta.getSpecType()).thenReturn("W");
      specs(zeta, recSpec, windowSpec);
      List<String> actionEnum = specEnumOf(tools(), "neo_action");
      List<String> sorted = new ArrayList<>(actionEnum);
      Collections.sort(sorted);
      assertEquals(sorted, actionEnum);
    }

    @Test
    @DisplayName("a role without report-spec access does not see it")
    void deniedRoleDoesNotSeeIt() {
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(recSpec, "GET")).thenReturn(false);
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();
      assertFalse(specEnumOf(tools, "neo_action").contains(BANK_REC));
      assertFalse(specEnumOf(tools, "neo_schema").contains(BANK_REC));
    }

    @Test
    @DisplayName("a report spec whose handler declares no actions is not added")
    void reportWithoutDeclarationNotAdded() {
      NeoHandler plain = context -> null;
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER)).thenReturn(plain);
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();
      assertFalse(specEnumOf(tools, "neo_action").contains(BANK_REC));
      assertFalse(specEnumOf(tools, "neo_schema").contains(BANK_REC));
    }

    @Test
    @DisplayName("a failing probe degrades to 'not an action spec' without breaking tools/list")
    void failingProbeIsQuiet() {
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(recSpec, "GET"))
          .thenThrow(new IllegalStateException("boom"));
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();
      assertFalse(specEnumOf(tools, "neo_action").contains(BANK_REC));
      assertTrue(specEnumOf(tools, "neo_list").contains("sales-order"));
    }
  }
}
