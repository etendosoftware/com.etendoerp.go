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
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.BankStatementsHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.ReconciliationHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * ETP-5468 (CA1/CA7) — how the MCP layer publishes the named actions a handler declares:
 * <ul>
 *   <li>{@code etendo_schema}: {@link McpActionsView#buildDeclaredResponse} renders the eleven
 *       bank-reconciliation contracts;</li>
 *   <li>{@code etendo_discover}: {@link McpToolRouterSupport#buildDiscoverSpec}'s three-way report
 *       branch (callable report / actions_only / not configured);</li>
 *   <li>{@code tools/list}: {@link ToolRegistry} adds the action report spec to the
 *       {@code etendo_action} and {@code etendo_schema} enums ONLY — never to {@code etendo_list},
 *       {@code etendo_get} or the write tools.</li>
 * </ul>
 * The real {@link ReconciliationHandler} declaration is used, so a drift in its contracts shows
 * up here too.
 *
 * @covers com.etendoerp.go.mcp.McpDeclaredActions
 * @covers com.etendoerp.go.mcp.McpActionsView
 * @covers com.etendoerp.go.mcp.McpRoutingException
 * @covers com.etendoerp.go.mcp.McpToolRouter
 * @covers com.etendoerp.go.mcp.McpToolRouterSupport
 * @covers com.etendoerp.go.mcp.McpReportActionsSchema
 * @covers com.etendoerp.go.mcp.ToolRegistryActionSpecs
 * @covers com.etendoerp.go.schemaforge.ReconciliationHandler
 * @covers com.etendoerp.go.schemaforge.BankStatementsHandler
 */
@SuppressWarnings("java:S2187")
@DisplayName("MCP declared actions (ETP-5468)")
class McpDeclaredActionsTest {

  private static final String BANK_REC = "bank-reconciliation";
  private static final String QUALIFIER = "bank-reconciliation";
  private static final List<String> RECONCILIATION_ACTIONS = List.of("pendingLines",
      "candidates", "autoMatch", "reconcileGroup", "reconcileDifference", "undoReconciliation",
      "removeOperation", "reactivateSelected", "applySuggestions", "discardPair", "restorePair");

  private static final Map<String, NeoActionContract> CONTRACTS =
      new ReconciliationHandler().actionContracts();

  // Guards that handleSchema answers with the declared catalog only when isActionOnlyEntity
  // is true.
  private static final Pattern CATALOG_GATE = Pattern.compile(
      "if\\s*\\(\\s*McpReportActionsSchema\\s*\\.\\s*isActionOnlyEntity\\s*\\([^)]*\\)\\s*\\)"
          + "\\s*\\{\\s*return[^;]*McpActionsView\\s*\\.\\s*buildDeclaredResponse\\s*\\(");

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

  // ── etendo_schema rendering ───────────────────────────────────────────────

  @Nested
  @DisplayName("McpActionsView.buildDeclaredResponse (etendo_schema)")
  class DeclaredResponse {

    @Test
    @DisplayName("lists the eleven actions, all invokable, in declaration order")
    void nineActions() throws Exception {
      JSONObject response = McpActionsView.buildDeclaredResponse(BANK_REC, BANK_REC, CONTRACTS);
      assertEquals(BANK_REC, response.getString("spec"));
      assertEquals(BANK_REC, response.getString("entity"));
      assertEquals(11, response.getInt("actionCount"));
      assertEquals(11, response.getInt("invokableCount"));
      JSONArray actions = response.getJSONArray("actions");
      List<String> names = new ArrayList<>();
      for (int i = 0; i < actions.length(); i++) {
        JSONObject a = actions.getJSONObject(i);
        names.add(a.getString("action"));
        assertEquals("etendo_action", a.getString("invokeVia"));
        assertTrue(a.getString("idDescription").contains("financial account id"));
        assertFalse(a.getJSONObject("parameters").getBoolean("additionalProperties"));
      }
      assertEquals(RECONCILIATION_ACTIONS, names);
      assertTrue(response.getString("hint").contains("etendo_action"));
      assertTrue(response.getString("hint").contains("422"));
    }

    @Test
    @DisplayName("each contract carries the correct required list")
    void requiredLists() throws Exception {
      JSONArray actions = McpActionsView.buildDeclaredResponse(BANK_REC, BANK_REC, CONTRACTS)
          .getJSONArray("actions");
      Map<String, List<String>> expected = Map.ofEntries(
          Map.entry("pendingLines", List.of()),
          Map.entry("candidates", List.of("statementLineId")),
          Map.entry("autoMatch", List.of()),
          Map.entry("reconcileGroup", List.of("statementLineId")),
          Map.entry("reconcileDifference", List.of("statementLineId")),
          Map.entry("undoReconciliation", List.of("statementLineId")),
          Map.entry("removeOperation", List.of("statementLineId", "transactionIds")),
          Map.entry("reactivateSelected", List.of("statementLineId", "transactionIds")),
          Map.entry("applySuggestions", List.of("groups")),
          Map.entry("discardPair", List.of("statementLineId", "counterpartLineId")),
          Map.entry("restorePair", List.of("lineId")));
      assertEquals(expected.size(), actions.length(), "one expected list per rendered action");
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

  // ── etendo_schema: catalog-only vs fields + declared actions (ETP-5535) ───

  /**
   * Rows: case name, declared actions, the ETGO_SF_FIELD rows the lookup answers ({@code null} =
   * the lookup throws), expected {@code isActionOnlyEntity}, whether the field lookup runs — an
   * entity declaring nothing (every ordinary window entity) must not pay for the query.
   */
  static Stream<Arguments> actionOnlyCases() {
    List<SFField> oneFieldRow = List.of(mock(SFField.class));
    return Stream.of(
        Arguments.of("nothing declared, no fields", Collections.emptyMap(), List.of(), false,
            false),
        Arguments.of("declared, no field rows (report entity)", CONTRACTS, List.of(), true, true),
        Arguments.of("declared, field rows (sales-quotation/quotation)", CONTRACTS, oneFieldRow,
            false, true),
        Arguments.of("declared, field lookup throws", CONTRACTS, null, false, true));
  }

  @Nested
  @DisplayName("McpReportActionsSchema.isActionOnlyEntity (etendo_schema)")
  class ActionOnlyEntity {

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#actionOnlyCases")
    @SuppressWarnings("unchecked")
    void catalogReplacesTheSchemaOnlyWithoutFields(String name,
        Map<String, NeoActionContract> declared, List<SFField> fieldRows, boolean expected,
        boolean fieldsQueried) {
      SFEntity entity = recEntity();
      when(entity.getId()).thenReturn("entity-1");
      OBDal dal = mock(OBDal.class);
      OBCriteria<SFField> criteria = mock(OBCriteria.class);
      if (fieldRows == null) {
        when(dal.createCriteria(SFField.class)).thenThrow(new IllegalStateException("boom"));
      } else {
        when(dal.createCriteria(SFField.class)).thenReturn(criteria);
        when(criteria.add(any())).thenReturn(criteria);
        when(criteria.setMaxResults(Mockito.anyInt())).thenReturn(criteria);
        when(criteria.list()).thenReturn(fieldRows);
      }

      try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
        obDal.when(OBDal::getInstance).thenReturn(dal);
        assertEquals(expected, McpReportActionsSchema.isActionOnlyEntity(entity, declared), name);
      }
      Mockito.verify(dal, Mockito.times(fieldsQueried ? 1 : 0)).createCriteria(SFField.class);
    }
  }

  /**
   * ETP-5535 — the call sites in {@code handleSchema}. It needs an OBContext, a live DAL and an
   * AD_Tab, so the wiring cannot be reached behaviourally, and reverting either line leaves every
   * test above passing: back to {@code !declaredActions.isEmpty()} and the sales quotation's
   * schema is replaced by its one-action catalog; back to the 3-argument {@code buildResponse} and
   * {@code rejectQuotation} is listed nowhere. That is the case {@link McpSourceScanner} exists for.
   */
  @Nested
  @DisplayName("McpToolRouter.handleSchema wiring (ETP-5535)")
  class HandleSchemaWiring {

    @Test
    @DisplayName("the catalog replaces the schema only behind isActionOnlyEntity, and view:"
        + "\"actions\" receives the declared actions")
    void handleSchemaGatesTheCatalogAndFeedsTheActionsView() {
      String body = McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleSchema");

      assertTrue(CATALOG_GATE.matcher(body).find(),
          "the declared catalog must be returned only when isActionOnlyEntity says so — a "
              + "window entity with fields keeps its schema");
      List<String> gate = McpSourceScanner.callArguments(body,
          "McpReportActionsSchema.isActionOnlyEntity", 0);
      assertEquals(2, gate.size(), "isActionOnlyEntity(sfEntity, declared)");
      String declared = gate.get(1);

      int actionsView = body.indexOf("isActionsView");
      assertTrue(actionsView >= 0, "view:\"actions\" no longer dispatches");
      List<String> view = McpSourceScanner.callArguments(body, "McpActionsView.buildResponse",
          actionsView);
      assertTrue(view.size() >= 4,
          "view:\"actions\" must use a buildResponse overload that appends declared actions");
      assertEquals(declared, view.get(3),
          "the map passed to isActionOnlyEntity must be the one view:\"actions\" appends");
    }

    /**
     * ETP-5714: with an id, the actions view is judged against that record — the record is resolved
     * only for view:"actions" and handed to the 7-argument buildResponse as its last argument.
     */
    @Test
    @DisplayName("view:\"actions\" resolves the record named by id and passes it to buildResponse")
    void actionsViewIsJudgedAgainstTheRecord() {
      String body = McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleSchema");

      int actionsView = body.indexOf("isActionsView");
      List<String> view = McpSourceScanner.callArguments(body, "McpActionsView.buildResponse",
          actionsView);
      assertEquals(7, view.size(), "buildResponse(spec, entity, fields, declared, config, "
          + "excluded, asked)");
      assertTrue(body.indexOf("availabilityContextOf", actionsView) > actionsView,
          "the record is only resolved inside the actions view");
      assertEquals("asked", view.get(6));
    }
  }

  @Nested
  @DisplayName("unavailableFor (ETP-5714)")
  class UnavailableFor {

    @Test
    @DisplayName("an entity with no customization, or one that throws, derives nothing")
    void quietWhenNothingCanBeDerived() {
      SFEntity none = mock(SFEntity.class);
      when(none.getName()).thenReturn("none");
      when(none.getJavaQualifier()).thenReturn(null);

      assertTrue(McpDeclaredActions.unavailableFor(none, mock(org.openbravo.base.structure.BaseOBObject.class))
          .isEmpty());
      assertTrue(McpDeclaredActions.unavailableFor(null, null).isEmpty());
    }

    @Test
    @DisplayName("the customization's own answer is returned, and a null or throwing one is empty")
    void customizationAnswerIsRelayed() {
      org.openbravo.base.structure.BaseOBObject subject =
          mock(org.openbravo.base.structure.BaseOBObject.class);
      SFEntity entity = recEntity();
      try (org.mockito.MockedStatic<com.etendoerp.go.schemaforge.util.NeoHandlerLookup> lookup =
          Mockito.mockStatic(com.etendoerp.go.schemaforge.util.NeoHandlerLookup.class,
              Mockito.CALLS_REAL_METHODS)) {
        com.etendoerp.go.schemaforge.NeoHandler handler =
            mock(com.etendoerp.go.schemaforge.NeoHandler.class);
        lookup.when(() -> com.etendoerp.go.schemaforge.util.NeoHandlerLookup
            .byQualifierQuietly(QUALIFIER)).thenReturn(handler);
        when(handler.unavailableActions(subject)).thenReturn(Map.of("a", "because"));

        assertEquals(Map.of("a", "because"), McpDeclaredActions.unavailableFor(entity, subject));

        when(handler.unavailableActions(subject)).thenReturn(null);
        assertTrue(McpDeclaredActions.unavailableFor(entity, subject).isEmpty());
        when(handler.unavailableActions(subject)).thenThrow(new IllegalStateException("boom"));
        assertTrue(McpDeclaredActions.unavailableFor(entity, subject).isEmpty());
      }
    }
  }

  // ── etendo_discover ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("McpToolRouterSupport.buildDiscoverSpec — report branch (etendo_discover)")
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
      lookup = mockStatic(NeoHandlerLookup.class, Mockito.CALLS_REAL_METHODS);
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
    @DisplayName("a declared-actions spec → callable:false, status actions_only, the 11 actions")
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
          + "etendo_action (entity bank-reconciliation).", out.getString("message"));
      assertEquals(BANK_REC, out.getString("actionEntity"));
      assertEquals(RECONCILIATION_ACTIONS, strings(out.getJSONArray("actions")));
      assertTrue(out.getString("actionsHint").contains("etendo_action"));
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

      lookup = mockStatic(NeoHandlerLookup.class, Mockito.CALLS_REAL_METHODS);
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
    @DisplayName("bank-reconciliation joins etendo_action and etendo_schema only")
    void joinsActionAndSchemaOnly() {
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();

      assertTrue(specEnumOf(tools, "etendo_action").contains(BANK_REC));
      assertTrue(specEnumOf(tools, "etendo_schema").contains(BANK_REC));
      assertTrue(specEnumOf(tools, "etendo_action").contains("sales-order"));
      for (String crudTool : List.of("etendo_list", "etendo_get", "etendo_selectors", "etendo_defaults",
          "etendo_create", "etendo_update", "etendo_delete")) {
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
      List<String> actionEnum = specEnumOf(tools(), "etendo_action");
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
      assertFalse(specEnumOf(tools, "etendo_action").contains(BANK_REC));
      assertFalse(specEnumOf(tools, "etendo_schema").contains(BANK_REC));
    }

    @Test
    @DisplayName("a report spec whose handler declares no actions is not added")
    void reportWithoutDeclarationNotAdded() {
      NeoHandler plain = context -> null;
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(QUALIFIER)).thenReturn(plain);
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();
      assertFalse(specEnumOf(tools, "etendo_action").contains(BANK_REC));
      assertFalse(specEnumOf(tools, "etendo_schema").contains(BANK_REC));
    }

    @Test
    @DisplayName("a failing probe degrades to 'not an action spec' without breaking tools/list")
    void failingProbeIsQuiet() {
      accessMock.when(() -> NeoAccessUtils.hasReportSpecAccess(recSpec, "GET"))
          .thenThrow(new IllegalStateException("boom"));
      specs(windowSpec, recSpec);
      List<McpToolDefinition> tools = tools();
      assertFalse(specEnumOf(tools, "etendo_action").contains(BANK_REC));
      assertTrue(specEnumOf(tools, "etendo_list").contains("sales-order"));
    }
  }

  // ── ETP-5581: the MCP readers of the bank report pairs, under both bindings ──

  /** How a report pair is bound in one test. */
  enum BoundBy {
    /** The row has no qualifier; the class's {@code @NeoExtension} is the only binding. */
    ANNOTATION_ONLY,
    /** The row keeps its former qualifier and no deployed class claims the pair by annotation. */
    QUALIFIER_ONLY
  }

  /**
   * One migrated report pair: its handler class, the spec (= entity) name, the qualifier its row
   * carried before ETP-5581, the agent-actions class its {@code handle} hands ACTION contexts to,
   * and one declared action that takes no required parameter.
   */
  record ReportPair(Class<? extends NeoHandler> type, String spec, String formerQualifier,
      String agentActionsClass, String readAction) {
    @Override
    public String toString() {
      return spec;
    }
  }

  static Stream<Arguments> reportPairs() {
    ReportPair rec = new ReportPair(ReconciliationHandler.class, BANK_REC, "bankReconciliation",
        "com.etendoerp.go.schemaforge.ReconciliationAgentActions", "pendingLines");
    ReportPair statements = new ReportPair(BankStatementsHandler.class, "bank-statements",
        "bank-statements", "com.etendoerp.go.schemaforge.BankStatementAgentActions",
        "listStatements");
    return Stream.of(rec, statements).flatMap(pair -> Stream.of(
        Arguments.of(pair, BoundBy.ANNOTATION_ONLY), Arguments.of(pair, BoundBy.QUALIFIER_ONLY)));
  }

  /**
   * ETP-5581 moved {@code bank-reconciliation} and {@code bank-statements} from
   * {@code Java_Qualifier} to {@code @NeoExtension}. Every MCP reader of those pairs is asserted
   * twice: with the row's qualifier cleared and only the annotation binding (what ships), and with
   * the former qualifier and no annotated bean (the fallback a legacy deployment keeps). The
   * {@code NeoExtensionIndex} is built for real from a mocked bean manager; the handler is a spy
   * of the real class, so what each reader resolves is compared by identity.
   */
  @Nested
  @DisplayName("ETP-5581 — MCP readers resolve the bank report pairs by annotation or by qualifier")
  class BindingModes {

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#reportPairs")
    @DisplayName("McpDeclaredActions.of: the handler's whole declaration")
    void declaredActionsOfTheEntity(ReportPair pair, BoundBy mode) {
      try (PairFixture f = new PairFixture(pair, mode)) {
        assertEquals(new ArrayList<>(f.handler.actionContracts().keySet()),
            new ArrayList<>(McpDeclaredActions.of(f.entity).keySet()));
        f.assertQualifierAsked();
      }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#reportPairs")
    @DisplayName("etendo_discover: actions_only with the actions and their entity (NeoActionContract"
        + ".resolve)")
    void discoverListsTheActions(ReportPair pair, BoundBy mode) throws Exception {
      try (PairFixture f = new PairFixture(pair, mode)) {
        JSONObject out = McpToolRouterSupport.buildDiscoverSpec(f.spec, "R", null, null, null);

        assertFalse(out.getBoolean("callable"));
        assertEquals(McpToolRouterSupport.STATUS_ACTIONS_ONLY, out.getString("status"));
        assertEquals(pair.spec(), out.getString("actionEntity"));
        assertEquals(new ArrayList<>(f.handler.actionContracts().keySet()),
            strings(out.getJSONArray("actions")));
        f.assertQualifierAsked();
      }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#reportPairs")
    @DisplayName("tools/list: the spec is an action report spec (ToolRegistryActionSpecs)")
    void isAnActionReportSpec(ReportPair pair, BoundBy mode) {
      try (PairFixture f = new PairFixture(pair, mode);
          MockedStatic<NeoAccessUtils> access = mockStatic(NeoAccessUtils.class)) {
        access.when(() -> NeoAccessUtils.hasReportSpecAccess(f.spec, "GET")).thenReturn(true);

        assertTrue(ToolRegistryActionSpecs.isActionReportSpec(f.spec));
        f.assertQualifierAsked();
      }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#reportPairs")
    @DisplayName("etendo_schema: McpReportActionsSchema answers the entity's catalog")
    void schemaAnswersTheCatalog(ReportPair pair, BoundBy mode) throws Exception {
      try (PairFixture f = new PairFixture(pair, mode)) {
        JSONObject result = McpReportActionsSchema.reportSpecActionsSchema(pair.spec(),
            new JSONObject().put("entity", pair.spec()));

        assertNotNull(result, "the catalog, not the generic path");
        JSONObject body = new JSONObject(
            result.getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals(pair.spec(), body.getString("entity"));
        assertEquals(f.handler.actionContracts().size(), body.getInt("actionCount"));
        f.assertQualifierAsked();
      }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#reportPairs")
    @DisplayName("etendo_action: the dispatcher's ACTION surface reaches the agent-actions class")
    void actionReachesTheAgentActions(ReportPair pair, BoundBy mode) throws Exception {
      List<Object[]> dispatched = new ArrayList<>();
      NeoResponse answer = NeoResponse.ok(new JSONObject().put("answeredBy", pair.spec()));
      try (PairFixture f = new PairFixture(pair, mode);
          MockedStatic<?> agentActions = mockStatic(Class.forName(pair.agentActionsClass()),
              inv -> {
                if ("dispatch".equals(inv.getMethod().getName())) {
                  dispatched.add(inv.getArguments());
                  return answer;
                }
                return inv.callRealMethod();
              });
          MockedStatic<McpHookExecutor> executor = mockStatic(McpHookExecutor.class,
              Mockito.CALLS_REAL_METHODS);
          MockedStatic<McpSpecScope> scope = mockStatic(McpSpecScope.class);
          MockedStatic<NeoAccountingAccessPolicy> posting =
              mockStatic(NeoAccountingAccessPolicy.class, Mockito.CALLS_REAL_METHODS)) {
        // The record and posting refusals read AD rows and are not what is under test here.
        executor.when(() -> McpHookExecutor.actionRecordRefusal(any(), any(), any()))
            .thenReturn(null);
        posting.when(() -> NeoAccountingAccessPolicy.refusalForPostingAction(any(), any()))
            .thenReturn(null);
        JSONObject args = new JSONObject().put("entity", pair.spec()).put("id", "ACC-1")
            .put("action", pair.readAction());

        JSONObject result = new McpToolRouter().handleAction(pair.spec(), args);

        assertEquals(1, dispatched.size(), "the handler hands the ACTION context over once");
        assertSame(f.handler, dispatched.get(0)[0], "dispatched on the resolved handler");
        NeoContext context = (NeoContext) dispatched.get(0)[1];
        assertEquals(pair.readAction(), context.getFieldName());
        assertEquals("ACC-1", context.getRecordId());
        assertTrue(result.toString().contains("answeredBy"), result.toString());
        f.assertQualifierAsked();
      }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("com.etendoerp.go.mcp.McpDeclaredActionsTest#reportPairs")
    @DisplayName("etendo_report: resolves the handler (resolveOnly) and answers not configured")
    void reportResolvesAndAnswersNotConfigured(ReportPair pair, BoundBy mode) throws Exception {
      try (PairFixture f = new PairFixture(pair, mode);
          MockedStatic<NeoReportCallability> callability =
              mockStatic(NeoReportCallability.class, Mockito.CALLS_REAL_METHODS)) {
        java.lang.reflect.Method handleReport = McpToolRouter.class
            .getDeclaredMethod("handleReport", String.class, JSONObject.class);
        handleReport.setAccessible(true);

        JSONObject result = (JSONObject) handleReport.invoke(new McpToolRouter(), pair.spec(),
            new JSONObject());

        JSONObject body = new JSONObject(
            result.getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals(NeoReportCallability.STATUS_NOT_CONFIGURED, body.getString("status"));
        String name = mode == BoundBy.ANNOTATION_ONLY ? pair.type().getSimpleName()
            : pair.formerQualifier();
        callability.verify(() -> NeoReportCallability.contractOf(Mockito.same(f.handler),
            Mockito.eq(name)));
        Mockito.verify(f.handler, never()).handle(any());
        f.assertQualifierAsked();
      }
    }
  }

  /**
   * The report spec, its one entity row and a spy of the real handler, deployed per
   * {@link BoundBy}. The qualifier resolvers answer the spy for the former qualifier only and
   * record what they are asked.
   */
  static final class PairFixture implements AutoCloseable {
    final ReportPair pair;
    final BoundBy mode;
    final NeoHandler handler;
    final SFSpec spec;
    final SFEntity entity;
    /** Every qualifier the MCP resolver was asked for. */
    private final List<String> asked = new ArrayList<>();
    private final MockedStatic<WeldUtils> weld;
    private final MockedStatic<NeoHandlerLookup> lookup;
    private final MockedStatic<McpToolRouterSupport> support;
    private final MockedStatic<OBDal> obDal;
    private final MockedStatic<OBContext> obContext;

    @SuppressWarnings("unchecked")
    PairFixture(ReportPair pair, BoundBy mode) {
      this.pair = pair;
      this.mode = mode;
      NeoExtensionIndex.invalidate();
      McpConfigCache.invalidateAll();
      try {
        handler = Mockito.spy(pair.type().getDeclaredConstructor().newInstance());
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
      spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-" + pair.spec());
      when(spec.getName()).thenReturn(pair.spec());
      when(spec.getSpecType()).thenReturn("R");
      entity = mock(SFEntity.class);
      when(entity.getId()).thenReturn("entity-" + pair.spec());
      when(entity.getETGOSFSpec()).thenReturn(spec);
      when(entity.getName()).thenReturn(pair.spec());
      when(entity.getJavaQualifier())
          .thenReturn(mode == BoundBy.QUALIFIER_ONLY ? pair.formerQualifier() : null);

      BeanManager bm = mock(BeanManager.class);
      Set<Bean<?>> beans = new HashSet<>();
      if (mode == BoundBy.ANNOTATION_ONLY) {
        Bean<NeoHandler> bean = mock(Bean.class);
        Mockito.doReturn(pair.type()).when(bean).getBeanClass();
        Mockito.doReturn(handler).when(bm).getReference(Mockito.same(bean),
            Mockito.eq(NeoHandler.class), any());
        beans.add(bean);
      }
      when(bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL)).thenReturn(beans);
      weld = mockStatic(WeldUtils.class);
      weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);

      lookup = mockStatic(NeoHandlerLookup.class, Mockito.CALLS_REAL_METHODS);
      lookup.when(() -> NeoHandlerLookup.byQualifier(any())).thenAnswer(this::byQualifier);
      lookup.when(() -> NeoHandlerLookup.byQualifierQuietly(any())).thenAnswer(this::byQualifier);

      support = mockStatic(McpToolRouterSupport.class, Mockito.CALLS_REAL_METHODS);
      support.when(() -> McpToolRouterSupport.findActiveSpecByName(pair.spec())).thenReturn(spec);
      support.when(() -> McpToolRouterSupport.findIncludedEntity(spec.getId(), pair.spec()))
          .thenReturn(entity);
      support.when(() -> McpToolRouterSupport.listIncludedEntities(spec.getId()))
          .thenReturn(List.of(entity));

      OBDal dal = mock(OBDal.class);
      entityCriteria(dal, List.of(entity));
      obDal = mockStatic(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      OBContext context = mock(OBContext.class);
      when(context.getRole()).thenReturn(mock(Role.class));
      obContext = mockStatic(OBContext.class);
      obContext.when(OBContext::getOBContext).thenReturn(context);
      // Stubbing a CALLS_REAL_METHODS static runs the real method once; only the readers count.
      asked.clear();
    }

    private NeoHandler byQualifier(InvocationOnMock invocation) {
      String qualifier = invocation.getArgument(0);
      asked.add(qualifier);
      return mode == BoundBy.QUALIFIER_ONLY && pair.formerQualifier().equals(qualifier)
          ? handler : null;
    }

    /** Annotation-only: no qualifier resolver asked. Qualifier-only: asked for the former one. */
    void assertQualifierAsked() {
      if (mode == BoundBy.ANNOTATION_ONLY) {
        assertTrue(asked.isEmpty(), "no qualifier to fall back on, yet asked: " + asked);
      } else {
        assertTrue(asked.contains(pair.formerQualifier()), "asked: " + asked);
      }
    }

    @Override
    public void close() {
      obContext.close();
      obDal.close();
      support.close();
      lookup.close();
      weld.close();
      NeoExtensionIndex.invalidate();
      McpConfigCache.invalidateAll();
    }
  }

  // ── declared-action 422 envelope ──────────────────────────────────────────

  @Nested
  @DisplayName("McpRoutingException.actionParametersInvalid (undeclared key)")
  class UnknownParameterEnvelope {

    @Test
    @DisplayName("the envelope carries the id suffix, Nothing was run and idDescription")
    void envelopeExplainsId() throws Exception {
      Map<String, NeoActionContract> declared = Map.of("ping", NeoActionContract
          .read("ping", "Pings.").withIdDescription("the thing."));
      JSONObject error = NeoActionContract.validate(declared, "ping",
          new JSONObject().put("zeta", "x")).getBody().getJSONObject("error");

      JSONObject envelope = McpRoutingException
          .actionParametersInvalid("spec", "entity", "ping", error).toEnvelope();

      assertEquals("Action 'ping' does not accept parameter(s) zeta."
          + " Record id ('id' argument, not a parameter): the thing. Nothing was run.",
          envelope.getString("detail"));
      assertEquals("the thing.", envelope.getString("idDescription"));
      assertEquals("[\"zeta\"]", envelope.getJSONArray("unknownParameters").toString());
    }
  }
}
