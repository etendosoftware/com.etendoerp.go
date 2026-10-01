/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.CurrencyOptionsHandler;
import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoExtensionResult;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * ETP-5558 Step 3 — handler-declared actions on <b>window</b> entities (FR-1).
 *
 * <p>ETP-5468 published declared actions for report specs only, and there a declaration replaces
 * the whole schema — right for {@code bank-reconciliation}, whose AD tab only gates the role. A
 * window entity is the opposite: its AD buttons are real ({@code documentAction} completes the
 * invoice) and the handler's actions sit beside them. The invoice payment actions were served to
 * the SPA all along and invisible to the agent, which therefore built payments by hand through the
 * route BUG-1 corrupted data with.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("ETP-5558 — declared actions on window entities")
class McpWindowDeclaredActionsTest {

  private static final String SPEC = "sales-invoice";
  private static final String ENTITY = "header";
  private static int seq;

  private MockedStatic<NeoExtensionIndex> indexMock;
  private MockedStatic<NeoHandlerLookup> lookupMock;
  private MockedStatic<NeoButtonActionHelper> buttonMock;
  private MockedStatic<ModelProvider> modelMock;
  private NeoHandler handler;

  @BeforeEach
  void setUp() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
    handler = mock(NeoHandler.class);
    indexMock = mockStatic(NeoExtensionIndex.class);
    lookupMock = mockStatic(NeoHandlerLookup.class);
    lookupMock.when(() -> NeoHandlerLookup.byQualifierQuietly(anyString())).thenReturn(handler);
    when(handler.actionContracts()).thenReturn(contracts());
    // The AD buttons neo_action can fire, as NeoButtonActionHelper.findButtonColumn resolves them:
    // by DB column name or by field name. The DAL names each column the way neo_schema does.
    buttonMock = mockStatic(NeoButtonActionHelper.class);
    ModelProvider provider = mock(ModelProvider.class);
    Entity dal = mock(Entity.class);
    modelMock = mockStatic(ModelProvider.class);
    modelMock.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntityByTableName("C_Invoice")).thenReturn(dal);
    for (String[] b : new String[][] { { "DocAction", "documentAction" },
        { "EM_APRM_Addpayment", "aPRMAddpayment" },
        { "EM_Psd2_Generate_Bank_Payment", "psd2GenerateBankPayment" } }) {
      Column column = buttonColumn(b[0]);
      Property property = mock(Property.class);
      when(property.getName()).thenReturn(b[1]);
      when(dal.getPropertyByColumnName(b[0])).thenReturn(property);
      buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), eq(b[0])))
          .thenReturn(column);
      buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), eq(b[1])))
          .thenReturn(column);
    }
  }

  private static Column buttonColumn(String dbName) {
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("C_Invoice");
    Reference button = mock(Reference.class);
    when(button.getId()).thenReturn("28");
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(dbName);
    when(column.getTable()).thenReturn(table);
    when(column.getReference()).thenReturn(button);
    when(column.isActive()).thenReturn(true);
    return column;
  }

  @AfterEach
  void tearDown() {
    modelMock.close();
    buttonMock.close();
    lookupMock.close();
    indexMock.close();
    McpConfigCache.invalidateAll();
  }

  private static Map<String, NeoActionContract> contracts() {
    Map<String, NeoActionContract> c = new LinkedHashMap<>();
    c.put("registerPayment", NeoActionContract.write("registerPayment", "Registers a payment",
        NeoActionContract.Param.required("scheduleId", NeoActionContract.TYPE_STRING, "schedule"),
        NeoActionContract.Param.required("actual_payment", NeoActionContract.TYPE_NUMBER, "amount"))
        .withIdDescription("the invoice id"));
    c.put("pisTemplates", NeoActionContract.read("pisTemplates", "PIS templates"));
    c.put("currencyOptions", NeoActionContract.read("currencyOptions", "Currencies")
        .withHttpMethod("GET"));
    return c;
  }

  private static SFEntity entity(String specType, String mcpConfig) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-" + (++seq));
    when(spec.getName()).thenReturn(SPEC);
    when(spec.getSpecType()).thenReturn(specType);
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(0L);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("C_Invoice");
    List<Column> columns = new java.util.ArrayList<>();
    for (String db : List.of("DocAction", "EM_APRM_Addpayment", "EM_Psd2_Generate_Bank_Payment")) {
      columns.add(buttonColumn(db));
    }
    when(table.getADColumnList()).thenReturn(columns);
    when(tab.getTable()).thenReturn(table);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("ent-" + (++seq));
    when(entity.getName()).thenReturn(ENTITY);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.getJavaQualifier()).thenReturn("salesInvoiceHeaderHandler");
    when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(mcpConfig);
    when(entity.isGet()).thenReturn(true);
    return entity;
  }

  private static final String HIDE_PIS = "{\"actions\":{\"hidden\":[\"pisTemplates\","
      + "\"psd2GenerateBankPayment\"],\"redirect\":{\"aPRMAddpayment\":\"registerPayment\"},"
      + "\"reason\":\"PIS needs a person to authorize at the bank\","
      + "\"redirectReason\":\"the classic Add Payment button is not the Etendo GO payment flow\"}}";

  private static JSONArray buttons() throws Exception {
    JSONArray fields = new JSONArray();
    fields.put(new JSONObject("{\"name\":\"documentAction\",\"type\":\"button\","
        + "\"invokeVia\":\"neo_action\"}"));
    fields.put(new JSONObject("{\"name\":\"aPRMAddpayment\",\"type\":\"button\","
        + "\"invokable\":false,\"notInvokableReason\":\"discarded\"}"));
    fields.put(new JSONObject("{\"name\":\"psd2GenerateBankPayment\",\"type\":\"button\","
        + "\"invokable\":false}"));
    fields.put(new JSONObject("{\"name\":\"documentNo\",\"type\":\"string\"}"));
    return fields;
  }

  private static List<String> namesOf(JSONArray actions) throws Exception {
    List<String> names = new java.util.ArrayList<>();
    for (int i = 0; i < actions.length(); i++) {
      JSONObject a = actions.getJSONObject(i);
      names.add(a.has("action") && !a.has("type") ? a.getString("action") : a.getString("name"));
    }
    return names;
  }

  // ── resolution ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolution")
  class Resolution {

    @Test
    @DisplayName("@NeoExtension wins over the qualifier, like the dispatcher")
    void annotationFirst() {
      NeoHandler annotated = mock(NeoHandler.class);
      Map<String, NeoActionContract> only = Map.of("x", NeoActionContract.read("x", "x"));
      when(annotated.actionContracts()).thenReturn(only);
      indexMock.when(() -> NeoExtensionIndex.resolve(SPEC, ENTITY, "salesInvoiceHeaderHandler"))
          .thenReturn(annotated);

      assertEquals(only.keySet(), McpDeclaredActions.of(entity("W", null)).keySet());
    }

    @Test
    @DisplayName("an action MCP_CONFIG.actions hides is not declared to the MCP")
    void hiddenIsFilteredOut() {
      assertEquals(List.of("registerPayment", "currencyOptions"),
          List.copyOf(McpDeclaredActions.of(entity("W", HIDE_PIS)).keySet()));
    }

    @Test
    @DisplayName("a report spec still replaces the schema; a window spec merges")
    void replaceOnlyForReportSpecs() {
      assertTrue(McpDeclaredActions.replacesSchema(entity("R", null)));
      assertFalse(McpDeclaredActions.replacesSchema(entity("W", null)));
    }
  }

  // ── neo_schema view:"actions" ─────────────────────────────────────────

  @Nested
  @DisplayName("neo_schema view:\"actions\" on a window entity")
  class ActionsView {

    @Test
    @DisplayName("merges AD buttons and declared actions, and counts the declared as invokable")
    void mergesAndCounts() throws Exception {
      SFEntity e = entity("W", HIDE_PIS);
      JSONObject view = McpActionsView.buildResponse(SPEC, ENTITY, buttons(),
          McpDeclaredActions.of(e), McpActionsSection.forEntity(e));

      List<String> names = namesOf(view.getJSONArray("actions"));
      assertEquals(List.of("documentAction", "aPRMAddpayment", "registerPayment",
          "currencyOptions"), names, "hidden PIS button and PIS action are gone; order kept");
      assertEquals(4, view.getInt("actionCount"));
      assertEquals(3, view.getInt("invokableCount"),
          "documentAction plus the two declared actions");
    }

    @Test
    @DisplayName("a redirected button tells the agent which declared action to use")
    void redirectedButton() throws Exception {
      SFEntity e = entity("W", HIDE_PIS);
      JSONArray actions = McpActionsView.buildResponse(SPEC, ENTITY, buttons(),
          McpDeclaredActions.of(e), McpActionsSection.forEntity(e)).getJSONArray("actions");

      JSONObject add = actions.getJSONObject(1);
      assertEquals("aPRMAddpayment", add.getString("name"));
      assertEquals("registerPayment", add.getString("useInstead"));
      assertTrue(add.getString("notInvokableReason").contains("registerPayment"),
          add.toString());
      assertTrue(add.getString("notInvokableReason").contains("Add Payment button"),
          "a redirect carries its own reason: " + add);
      assertFalse(add.getString("notInvokableReason").contains("authorize at the bank"),
          "not the PIS reason: " + add);
    }

    @Test
    @DisplayName("a button the customization excludes is not listed, even with no MCP_CONFIG row")
    void excludedButtonNotListed() throws Exception {
      when(handler.agentExcludedActions()).thenReturn(Set.of("psd2GenerateBankPayment"));
      SFEntity e = entity("W", null);
      JSONObject view = McpActionsView.buildResponse(SPEC, ENTITY, buttons(),
          McpDeclaredActions.of(e), McpActionsSection.forEntity(e),
          McpDeclaredActions.excludedOf(e));
      assertFalse(namesOf(view.getJSONArray("actions")).contains("psd2GenerateBankPayment"));
    }

    @Test
    @DisplayName("with nothing declared and nothing configured, the response is as before")
    void unchangedWithoutDeclarations() throws Exception {
      JSONObject view = McpActionsView.buildResponse(SPEC, ENTITY, buttons(), Map.of(),
          McpActionsSection.forEntity(entity("W", null)));
      assertEquals(3, view.getInt("actionCount"));
      assertEquals(1, view.getInt("invokableCount"));
      assertFalse(view.toString().contains("useInstead"));
    }
  }

  // ── neo_action precheck ───────────────────────────────────────────────

  @Nested
  @DisplayName("neo_action precheck")
  class Precheck {

    @Test
    @DisplayName("a hidden action is refused 405, naming the reason — even though the handler serves it")
    void hiddenIsRefused() throws Exception {
      McpRoutingException e = assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity("W", HIDE_PIS), "pisTemplates",
              new JSONObject()));
      JSONObject env = e.toEnvelope();
      assertEquals(405, env.getInt(McpConstants.KEY_STATUS));
      assertEquals("method_not_allowed", env.getString(McpConstants.KEY_ERROR));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("authorize at the bank"));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("Nothing was run"));
    }

    @Test
    @DisplayName("a redirected button is refused with the action to use")
    void redirectedIsRefused() throws Exception {
      McpRoutingException e = assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity("W", HIDE_PIS), "aPRMAddpayment",
              new JSONObject()));
      assertTrue(e.toEnvelope().getString(McpConstants.KEY_HINT).contains("registerPayment"));
    }

    @Test
    @DisplayName("a declared action is validated before dispatch: an undeclared key is a 422")
    void declaredIsValidated() throws Exception {
      JSONObject params = new JSONObject("{\"scheduleId\":\"S\",\"actual_payment\":10,"
          + "\"pis\":true}");
      McpRoutingException e = assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity("W", null), "registerPayment", params));
      JSONObject env = e.toEnvelope();
      assertEquals(422, env.getInt(McpConstants.KEY_STATUS));
      assertEquals("validation_error", env.getString(McpConstants.KEY_ERROR));
      assertEquals("pis", env.getJSONArray("unknownParameters").getString(0));
      assertTrue(env.getString(McpConstants.KEY_HINT).contains("view:'actions'"));
    }

    @Test
    @DisplayName("a valid declared call passes and returns its contract (for the HTTP method)")
    void validDeclaredPasses() throws Exception {
      NeoActionContract c = McpDeclaredActions.precheck(entity("W", null), "currencyOptions",
          new JSONObject());
      assertNotNull(c);
      assertEquals("GET", c.getHttpMethod());
    }

    @Test
    @DisplayName("an AD button is not judged here: no contract, no refusal")
    void adButtonPasses() throws Exception {
      assertNull(McpDeclaredActions.precheck(entity("W", null), "documentAction",
          new JSONObject("{\"docAction\":\"CO\"}")));
    }

    @Test
    @DisplayName("an unusable MCP_CONFIG refuses every action (fail closed)")
    void unusableConfigFailsClosed() {
      assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity("W", "{\"actions\":{\"hidden\":[\"x\"]}}"),
              "documentAction", new JSONObject()));
    }
  }

  // ── reject cycle 1: the same action under another spelling, fail closed, honest discovery ──

  @Nested
  @DisplayName("neo_action through handleAction (the real path)")
  class RealPath {

    private MockedStatic<McpToolRouterSupport> supportMock;
    private SFEntity current;

    @BeforeEach
    void route() {
      supportMock = mockStatic(McpToolRouterSupport.class);
      supportMock.when(() -> McpToolRouterSupport.validateArgs(any(), any(String[].class)))
          .thenCallRealMethod();
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-route");
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), eq(ENTITY)))
          .thenAnswer(inv -> current);
    }

    @AfterEach
    void close() {
      supportMock.close();
    }

    private McpRoutingException refused(String mcpConfig, String action) throws Exception {
      current = entity("W", mcpConfig);
      JSONObject args = new JSONObject().put("entity", ENTITY).put("id", "INV-1")
          .put("action", action);
      McpRoutingException e = assertThrows(McpRoutingException.class,
          () -> new McpToolRouter().handleAction(SPEC, args));
      buttonMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(any(), anyString(),
          anyString(), any()), never());
      return e;
    }

    @Test
    @DisplayName("a hidden button called by its DB column name is refused, and never fired")
    void hiddenButtonByColumnName() throws Exception {
      JSONObject env = refused(HIDE_PIS, "EM_Psd2_Generate_Bank_Payment").toEnvelope();
      assertEquals(405, env.getInt(McpConstants.KEY_STATUS));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("authorize at the bank"));
    }

    @Test
    @DisplayName("a redirected button called by its DB column name is refused with the action to use")
    void redirectedButtonByColumnName() throws Exception {
      JSONObject env = refused(HIDE_PIS, "EM_APRM_Addpayment").toEnvelope();
      assertEquals(405, env.getInt(McpConstants.KEY_STATUS));
      assertTrue(env.getString(McpConstants.KEY_HINT).contains("registerPayment"));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("Add Payment button"));
    }

    /**
     * Live (ETP-5558): the curated invoice header does not include the aPRMAddpayment and
     * psd2GenerateBankPayment fields, so findButtonColumn finds neither and the alias answered a
     * bare 404 — safe, but without the registerPayment hint. The tab's own columns still name them.
     */
    @Test
    @DisplayName("a button whose field is not included is still matched by its DB column name")
    void notIncludedButtonByColumnName() throws Exception {
      buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), anyString()))
          .thenReturn(null);
      JSONObject redirected = refused(HIDE_PIS, "EM_APRM_Addpayment").toEnvelope();
      assertEquals(405, redirected.getInt(McpConstants.KEY_STATUS));
      assertTrue(redirected.getString(McpConstants.KEY_HINT).contains("registerPayment"));
      when(handler.agentExcludedActions()).thenReturn(Set.of("psd2GenerateBankPayment"));
      assertEquals(405, refused(null, "EM_Psd2_Generate_Bank_Payment").toEnvelope()
          .getInt(McpConstants.KEY_STATUS));
    }

    @Test
    @DisplayName("an excluded button called by its DB column name is refused in code, without the row")
    void excludedButtonByColumnNameWithoutTheRow() throws Exception {
      when(handler.agentExcludedActions()).thenReturn(Set.of("psd2GenerateBankPayment"));
      assertEquals(405, refused(null, "EM_Psd2_Generate_Bank_Payment").toEnvelope()
          .getInt(McpConstants.KEY_STATUS));
    }

    @Test
    @DisplayName("a button the configuration lists by DB column name is refused by its field name")
    void configuredByColumnNameCalledByFieldName() throws Exception {
      String byColumn = "{\"actions\":{\"hidden\":[\"EM_Psd2_Generate_Bank_Payment\"],"
          + "\"reason\":\"PIS needs a person to authorize at the bank\"}}";
      assertEquals(405, refused(byColumn, "psd2GenerateBankPayment").toEnvelope()
          .getInt(McpConstants.KEY_STATUS));
    }

    @Test
    @DisplayName("an action the customization excludes from agents is refused in code — even with"
        + " no MCP_CONFIG.actions row")
    void excludedActionRefusedWithoutTheRow() throws Exception {
      when(handler.agentExcludedActions()).thenReturn(Set.of("cancelPisPayment"));
      JSONObject env = refused(null, "cancelPisPayment").toEnvelope();
      assertEquals(405, env.getInt(McpConstants.KEY_STATUS));
      assertEquals("method_not_allowed", env.getString(McpConstants.KEY_ERROR));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("not available through MCP"));
    }

    @Test
    @DisplayName("undeclared actions the UI offers stay callable: cloneRecord, createShipment, post,"
        + " unpost")
    void undeclaredUiActionsStillPass() throws Exception {
      when(handler.agentExcludedActions()).thenReturn(Set.of("cancelPisPayment"));
      for (String action : List.of("cloneRecord", "createShipment", "post", "unpost")) {
        assertNull(McpDeclaredActions.precheck(entity("W", HIDE_PIS), action, new JSONObject()),
            action);
      }
    }

    @Test
    @DisplayName("an AD button of that entity still passes, under either spelling")
    void adButtonStillPasses() throws Exception {
      assertNull(McpDeclaredActions.precheck(entity("W", null), "DocAction", new JSONObject()));
      assertNull(McpDeclaredActions.precheck(entity("W", null), "documentAction",
          new JSONObject()));
    }

    @Test
    @DisplayName("an entity that declares nothing is not judged: legacy buttons keep their path")
    void undeclaringEntityUnchanged() throws Exception {
      when(handler.actionContracts()).thenReturn(Map.of());
      assertNull(McpDeclaredActions.precheck(entity("W", null), "somethingElse",
          new JSONObject()));
    }

    // ── ETP-5558 (a1a863f83): the record-ownership guard on the MCP action path ──

    private JSONObject actOn(String recordId, NeoResponse guardAnswer,
        MockedStatic<NeoExtensionDispatcher> dispatch) throws Exception {
      current = entity("W", null);
      // McpToolRouterSupport is statically mocked on this path, private helpers included, so the
      // conversion is asserted by its arguments (a 404 carrying the guard's body) rather than run.
      supportMock.when(() -> McpToolRouterSupport.toMcpHandlerError(any(),
          org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> new JSONObject()
              .put(McpConstants.KEY_STATUS, (int) inv.getArgument(1)).put(McpConstants.KEY_ERROR,
                  "not_found"));
      try (MockedStatic<NeoActionRecordGuard> guard = mockStatic(NeoActionRecordGuard.class)) {
        guard.when(() -> NeoActionRecordGuard.refusalFor(current, recordId))
            .thenReturn(guardAnswer);
        JSONObject args = new JSONObject().put("entity", ENTITY).put("id", recordId)
            .put("action", "DocAction");
        return new McpToolRouter().handleAction(SPEC, args);
      }
    }

    @Test
    @DisplayName("an action on another tenant's record is a 404 not_found, and nothing runs")
    void foreignRecordIsRefused() throws Exception {
      try (MockedStatic<NeoExtensionDispatcher> dispatch =
               mockStatic(NeoExtensionDispatcher.class)) {
        JSONObject result = actOn("INV-FOREIGN", NeoResponse.error(404, "Record not found"),
            dispatch);

        assertTrue(result.getBoolean("isError"), result.toString());
        JSONObject env = new JSONObject(result.getJSONArray("content").getJSONObject(0)
            .getString("text"));
        assertEquals(404, env.getInt(McpConstants.KEY_STATUS), env.toString());
        supportMock.verify(() -> McpToolRouterSupport.toMcpHandlerError(
            org.mockito.ArgumentMatchers.argThat(body -> body != null
                && "Record not found".equals(body.optJSONObject("error") == null ? null
                    : body.optJSONObject("error").optString("message"))),
            eq(404)));
        dispatch.verify(() -> NeoExtensionDispatcher.dispatch(any()), never());
        buttonMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(any(), anyString(),
            anyString(), any()), never());
      }
    }

    @Test
    @DisplayName("an action on an own record is dispatched as before")
    void ownRecordIsDispatched() throws Exception {
      try (MockedStatic<NeoExtensionDispatcher> dispatch =
               mockStatic(NeoExtensionDispatcher.class)) {
        dispatch.when(() -> NeoExtensionDispatcher.dispatch(any())).thenReturn(
            new NeoExtensionResult(null, NeoResponse.ok(new JSONObject().put("ran", true)), null));

        JSONObject result = actOn("INV-OWN", null, dispatch);

        assertFalse(result.has("isError"), result.toString());
        assertTrue(result.toString().contains("ran"), result.toString());
        dispatch.verify(() -> NeoExtensionDispatcher.dispatch(any()));
      }
    }
  }

  @Nested
  @DisplayName("an unusable MCP_CONFIG is reported where the actions are advertised")
  class UnusableConfig {

    private static final String BROKEN = "{\"actions\":{\"hidden\":[\"x\"]}}";

    @Test
    @DisplayName("view:\"actions\" lists them as not invokable, with the reason")
    void viewIsHonest() throws Exception {
      SFEntity e = entity("W", BROKEN);
      JSONObject view = McpActionsView.buildResponse(SPEC, ENTITY, buttons(),
          McpDeclaredActions.of(e), McpActionsSection.forEntity(e));
      assertEquals(0, view.getInt("invokableCount"));
      JSONArray actions = view.getJSONArray("actions");
      for (int i = 0; i < actions.length(); i++) {
        JSONObject a = actions.getJSONObject(i);
        assertFalse(a.has("invokeVia"), a.toString());
        assertTrue(a.getString("notInvokableReason").contains(McpActionsSection.UNUSABLE_REASON),
            a.toString());
      }
    }

    @Test
    @DisplayName("neo_discover says the declared actions cannot be run")
    void discoverIsHonest() throws Exception {
      JSONObject item = McpSupportInternals.buildDiscoverEntity(entity("W", BROKEN));
      assertFalse(item.getBoolean("actionsInvokable"));
      assertTrue(item.getString("actionsNotInvokableReason")
          .contains(McpActionsSection.UNUSABLE_REASON));
    }
  }

  @Nested
  @DisplayName("currencyOptions")
  class CurrencyOptions {

    @Test
    @DisplayName("the handler serves it on the method its contract declares, and refuses POST")
    void servedOverTheDeclaredMethod() throws Exception {
      String declared = new SalesInvoiceHeaderHandler().actionContracts().get("currencyOptions")
          .getHttpMethod();
      try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
          MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        NeoResponse served = new CurrencyOptionsHandler().handle(ctx(declared));
        NeoResponse post = new CurrencyOptionsHandler().handle(ctx("POST"));

        assertEquals(405, post.getHttpStatus(), "the handler only answers GET");
        assertEquals(404, served.getHttpStatus(),
            "past the method gate: the (missing) invoice is looked up");
      }
    }

    private NeoContext ctx(String method) {
      return McpHookExecutor.buildActionHookContext(SPEC, ENTITY, "INV-404", "currencyOptions",
          new JSONObject(), null, null, method);
    }
  }

  // ── MCP_CONFIG.actions.values (2a507a327) ─────────────────────────────

  @Nested
  @DisplayName("MCP_CONFIG.actions.values — a list-backed button narrowed to the UI's values")
  class Values {

    private static final String NARROW_PROCESS = "{\"actions\":{\"values\":"
        + "{\"aPRMProcessPayment\":[\"P\"]},\"reason\":\"the UI button only processes\"}}";
    private static final String NARROW_DOC_ACTION = "{\"actions\":{\"values\":"
        + "{\"documentAction\":[\"CO\",\"PR\"]},\"reason\":\"the UI completes or prepares\"}}";

    private JSONArray processButton() throws Exception {
      JSONArray fields = new JSONArray();
      fields.put(new JSONObject("{\"name\":\"aPRMProcessPayment\",\"type\":\"button\","
          + "\"invokeVia\":\"neo_action\",\"actionValues\":[{\"value\":\"P\","
          + "\"label\":\"Process\"},{\"value\":\"R\",\"label\":\"Reactivate\"},"
          + "{\"value\":\"RE\",\"label\":\"Reactivate and delete\"},"
          + "{\"value\":\"V\",\"label\":\"Void\"}]}"));
      fields.put(new JSONObject("{\"name\":\"documentAction\",\"type\":\"button\","
          + "\"invokeVia\":\"neo_action\",\"actionValues\":[{\"value\":\"CO\"},"
          + "{\"value\":\"VO\"}]}"));
      fields.put(new JSONObject("{\"name\":\"posted\",\"type\":\"button\","
          + "\"invokeVia\":\"neo_action\"}"));
      return fields;
    }

    private JSONObject buttonNamed(JSONArray actions, String name) throws Exception {
      for (int i = 0; i < actions.length(); i++) {
        if (name.equals(actions.getJSONObject(i).optString("name"))) {
          return actions.getJSONObject(i);
        }
      }
      throw new AssertionError(name + " not listed in " + actions);
    }

    private List<String> valuesOf(JSONObject button) throws Exception {
      JSONArray values = button.getJSONArray(McpConstants.KEY_ACTION_VALUES);
      List<String> out = new java.util.ArrayList<>();
      for (int i = 0; i < values.length(); i++) {
        out.add(values.getJSONObject(i).getString("value"));
      }
      return out;
    }

    @Test
    @DisplayName("validation: a section with only values (and reason) is valid")
    void onlyValuesIsValid() throws Exception {
      assertTrue(McpActionsSection.validate(
          new JSONObject(NARROW_PROCESS).getJSONObject("actions")).isEmpty());
    }

    @Test
    @DisplayName("validation: values must be a non-empty object of non-empty arrays of non-blank"
        + " strings, and reason stays mandatory")
    void invalidValues() throws Exception {
      for (String bad : List.of(
          "{\"values\":{},\"reason\":\"r\"}",
          "{\"values\":[\"P\"],\"reason\":\"r\"}",
          "{\"values\":\"P\",\"reason\":\"r\"}",
          "{\"values\":{\"b\":[]},\"reason\":\"r\"}",
          "{\"values\":{\"b\":\"P\"},\"reason\":\"r\"}",
          "{\"values\":{\"b\":[\" \"]},\"reason\":\"r\"}",
          "{\"values\":{\"b\":[1]},\"reason\":\"r\"}",
          "{\"values\":{\"b\":[\"P\"]}}")) {
        assertFalse(McpActionsSection.validate(new JSONObject(bad)).isEmpty(), bad);
      }
    }

    @Test
    @DisplayName("validation: each violation yields its own problem")
    void oneProblemPerViolation() throws Exception {
      List<String> problems = McpActionsSection.validate(new JSONObject(
          "{\"values\":{\"a\":[],\"b\":[\"\",\"P\",\" \"]},\"reason\":\"r\"}"));
      assertEquals(3, problems.size(), problems.toString());
      assertTrue(problems.stream().anyMatch(p -> p.contains("values.a")), problems.toString());
      assertTrue(problems.stream().anyMatch(p -> p.contains("values.b[0]")), problems.toString());
      assertTrue(problems.stream().anyMatch(p -> p.contains("values.b[2]")), problems.toString());
    }

    @Test
    @DisplayName("View.allowedValuesOf gives the configured set, and null for a button not listed")
    void allowedValuesOf() {
      McpActionsSection.View view = McpActionsSection.forEntity(entity("W", NARROW_DOC_ACTION));
      assertEquals(Set.of("CO", "PR"), view.allowedValuesOf("documentAction"));
      assertNull(view.allowedValuesOf("aPRMProcessPayment"));
      assertNull(view.allowedValuesOf(null));
    }

    @Test
    @DisplayName("view: a narrowed button lists only the allowed values; the others are untouched")
    void viewNarrowsTheButton() throws Exception {
      SFEntity e = entity("W", NARROW_PROCESS);
      JSONArray actions = McpActionsView.buildResponse(SPEC, ENTITY, processButton(), Map.of(),
          McpActionsSection.forEntity(e)).getJSONArray("actions");

      JSONObject process = buttonNamed(actions, "aPRMProcessPayment");
      assertEquals(List.of("P"), valuesOf(process));
      assertEquals("Process", process.getJSONArray(McpConstants.KEY_ACTION_VALUES)
          .getJSONObject(0).getString("label"), "the kept entry is the original one");
      assertEquals(List.of("CO", "VO"), valuesOf(buttonNamed(actions, "documentAction")));
      assertFalse(buttonNamed(actions, "posted").has(McpConstants.KEY_ACTION_VALUES),
          "a button without actionValues gets none");
    }

    @Test
    @DisplayName("view: without a values key the response is unchanged")
    void viewUnchangedWithoutValues() throws Exception {
      JSONArray plain = McpActionsView.buildResponse(SPEC, ENTITY, processButton(), Map.of(),
          McpActionsSection.forEntity(entity("W", null))).getJSONArray("actions");
      assertEquals(List.of("P", "R", "RE", "V"),
          valuesOf(buttonNamed(plain, "aPRMProcessPayment")));
      assertEquals(McpActionsView.apply(processButton()).toString(), plain.toString());
    }

    /** The full view's field array: the buttons above plus a plain field and a hidden button. */
    private JSONArray fullFields() throws Exception {
      JSONArray fields = processButton();
      fields.put(new JSONObject("{\"name\":\"amount\",\"type\":\"amount\","
          + "\"column\":\"Amount\"}"));
      fields.put(new JSONObject("{\"name\":\"psd2GenerateBankPayment\",\"type\":\"button\","
          + "\"column\":\"EM_Psd2_Generate_Bank_Payment\",\"invokeVia\":\"neo_action\"}"));
      fields.put(new JSONObject("{\"name\":\"aPRMAddpayment\",\"type\":\"button\","
          + "\"column\":\"EM_APRM_Addpayment\",\"invokeVia\":\"neo_action\"}"));
      return fields;
    }

    private List<String> namesIn(JSONArray fields) throws Exception {
      List<String> out = new java.util.ArrayList<>();
      for (int i = 0; i < fields.length(); i++) {
        out.add(fields.getJSONObject(i).getString("name"));
      }
      return out;
    }

    @Test
    @DisplayName("full view: a narrowed button lists only the allowed values (blind run a00c)")
    void fullViewNarrowsTheButton() throws Exception {
      // 20261001T1949-local-a00c: the agent read view:"full" and offered Void (V) to its user.
      JSONArray full = McpActionsView.applyConfig(fullFields(),
          McpActionsSection.forEntity(entity("W", NARROW_PROCESS)), Set.of());
      assertEquals(List.of("P"), valuesOf(buttonNamed(full, "aPRMProcessPayment")));
      assertEquals(List.of("CO", "VO"), valuesOf(buttonNamed(full, "documentAction")));
      assertEquals(namesIn(fullFields()), namesIn(full), "nothing else is added or removed");
    }

    @Test
    @DisplayName("full view: the narrowing also matches the button by its DB column")
    void fullViewNarrowsByColumn() throws Exception {
      JSONArray fields = new JSONArray().put(new JSONObject("{\"name\":\"aPRMProcessPayment\","
          + "\"column\":\"EM_APRM_Process_Payment\",\"type\":\"button\",\"actionValues\":"
          + "[{\"value\":\"P\"},{\"value\":\"V\"}]}"));
      JSONArray full = McpActionsView.applyConfig(fields, McpActionsSection.forEntity(entity("W",
          "{\"actions\":{\"values\":{\"EM_APRM_Process_Payment\":[\"P\"]},\"reason\":\"r\"}}")),
          Set.of());
      assertEquals(List.of("P"), valuesOf(full.getJSONObject(0)));
    }

    @Test
    @DisplayName("full view: hidden and agent-excluded buttons are left out, plain fields kept")
    void fullViewLeavesHiddenOut() throws Exception {
      JSONArray full = McpActionsView.applyConfig(fullFields(),
          McpActionsSection.forEntity(entity("W", HIDE_PIS)), Set.of("posted"));
      assertEquals(List.of("aPRMProcessPayment", "documentAction", "amount", "aPRMAddpayment"),
          namesIn(full));
    }

    @Test
    @DisplayName("full view: a hidden button named by its DB column is left out too")
    void fullViewHiddenByColumn() throws Exception {
      JSONArray full = McpActionsView.applyConfig(fullFields(), McpActionsSection.forEntity(
          entity("W", "{\"actions\":{\"hidden\":[\"EM_Psd2_Generate_Bank_Payment\"],"
              + "\"reason\":\"r\"}}")), Set.of());
      assertFalse(namesIn(full).contains("psd2GenerateBankPayment"), namesIn(full).toString());
    }

    @Test
    @DisplayName("full view: a redirected button is withdrawn and carries useInstead")
    void fullViewRedirects() throws Exception {
      JSONObject addPayment = buttonNamed(McpActionsView.applyConfig(fullFields(),
          McpActionsSection.forEntity(entity("W", HIDE_PIS)), Set.of()), "aPRMAddpayment");
      assertEquals("registerPayment", addPayment.getString("useInstead"));
      assertFalse(addPayment.has(McpSchemaFieldBuilder.KEY_INVOKE_VIA));
      assertFalse(addPayment.getBoolean(McpSchemaFieldBuilder.KEY_INVOKABLE));
    }

    @Test
    @DisplayName("full view: an unusable MCP_CONFIG withdraws every button, not the plain fields")
    void fullViewUnusableWithdrawsButtons() throws Exception {
      JSONArray full = McpActionsView.applyConfig(fullFields(),
          McpActionsSection.forEntity(entity("W", "{\"actions\":{\"bogus\":1}}")), Set.of());
      for (int i = 0; i < full.length(); i++) {
        JSONObject f = full.getJSONObject(i);
        boolean button = "button".equals(f.getString("type"));
        assertEquals(!button, !f.has(McpSchemaFieldBuilder.KEY_INVOKABLE), f.toString());
        assertFalse(button && f.has(McpSchemaFieldBuilder.KEY_INVOKE_VIA), f.toString());
      }
    }

    @Test
    @DisplayName("full view: no configuration, no exclusion leaves the array as it was")
    void fullViewUnchangedWithoutConfig() throws Exception {
      assertEquals(fullFields().toString(), McpActionsView.applyConfig(fullFields(),
          McpActionsSection.forEntity(entity("W", null)), Set.of()).toString());
      assertEquals(fullFields().toString(),
          McpActionsView.applyConfig(fullFields(), null, null).toString());
      assertEquals(0, McpActionsView.applyConfig(null, null, null).length());
    }

    private McpRoutingException refusedValue(String config, String action, JSONObject params) {
      return assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity("W", config), action, params));
    }

    @Test
    @DisplayName("precheck: a docAction outside the set is a 422 with allowedValues")
    void docActionOutsideTheSetIsRefused() throws Exception {
      JSONObject env = refusedValue(NARROW_DOC_ACTION, "documentAction",
          new JSONObject().put("docAction", "VO")).toEnvelope();
      assertEquals(422, env.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_VALIDATION, env.getString(McpConstants.KEY_ERROR));
      JSONArray allowed = env.getJSONArray("allowedValues");
      assertEquals(Set.of("CO", "PR"), Set.of(allowed.getString(0), allowed.getString(1)));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("'VO'"));
      assertTrue(env.getString(McpConstants.KEY_DETAIL).contains("Nothing was run"));
    }

    @Test
    @DisplayName("precheck: an action key outside the set is refused too")
    void actionKeyOutsideTheSetIsRefused() throws Exception {
      JSONObject env = refusedValue(NARROW_DOC_ACTION, "documentAction",
          new JSONObject().put("action", "VO")).toEnvelope();
      assertEquals(422, env.getInt(McpConstants.KEY_STATUS));
    }

    @Test
    @DisplayName("precheck: the value is checked under every alias of the button")
    void checkedUnderEveryAlias() throws Exception {
      // Configured by field name, called by DB column name.
      assertEquals(422, refusedValue(NARROW_DOC_ACTION, "DocAction",
          new JSONObject().put("docAction", "VO")).toEnvelope().getInt(McpConstants.KEY_STATUS));
      // Configured by DB column name, called by field name.
      String byColumn = "{\"actions\":{\"values\":{\"DocAction\":[\"CO\"]},"
          + "\"reason\":\"r\"}}";
      assertEquals(422, refusedValue(byColumn, "documentAction",
          new JSONObject().put("docAction", "VO")).toEnvelope().getInt(McpConstants.KEY_STATUS));
    }

    @Test
    @DisplayName("precheck: an allowed value, no value, null parameters and docAction:null pass")
    void allowedOrAbsentValuesPass() throws Exception {
      SFEntity e = entity("W", NARROW_DOC_ACTION);
      assertNull(McpDeclaredActions.precheck(e, "documentAction",
          new JSONObject().put("docAction", "CO")));
      assertNull(McpDeclaredActions.precheck(e, "DocAction",
          new JSONObject().put("action", "PR")));
      assertNull(McpDeclaredActions.precheck(e, "documentAction", new JSONObject()),
          "{} keeps the button's default, as the SPA sends it");
      assertNull(McpDeclaredActions.precheck(e, "documentAction", null));
      assertNull(McpDeclaredActions.precheck(e, "documentAction",
          new JSONObject().put("docAction", JSONObject.NULL)));
    }
  }

  // ── the shipped rows of b86eade1d, judged by precheck ──────────────────

  @Nested
  @DisplayName("precheck with the shipped payment and movement rows (b86eade1d)")
  class ShippedRows {

    private static final String PAYMENT_IN = "26AAEE85345F4D549907007E8821360A";
    private static final String FA_TRANSACTION = "AF50E181A0094E439C7B46B25A9E38FC";

    /** An entity on {@code tableName} carrying the real sourcedata MCP_CONFIG of {@code rowId}. */
    private SFEntity shipped(String tableName, String rowId, String[][] buttons) throws Exception {
      Entity dal = mock(Entity.class);
      when(ModelProvider.getInstance().getEntityByTableName(tableName)).thenReturn(dal);
      List<Column> columns = new java.util.ArrayList<>();
      for (String[] b : buttons) {
        Column column = buttonColumn(b[0]);
        when(column.getTable().getDBTableName()).thenReturn(tableName);
        Property property = mock(Property.class);
        when(property.getName()).thenReturn(b[1]);
        when(dal.getPropertyByColumnName(b[0])).thenReturn(property);
        columns.add(column);
      }
      SFEntity entity = entity("W", McpConfigSourcedataTest.payloadOf(rowId).toString());
      Table table = mock(Table.class);
      when(table.getDBTableName()).thenReturn(tableName);
      when(table.getADColumnList()).thenReturn(columns);
      when(entity.getADTab().getTable()).thenReturn(table);
      return entity;
    }

    private SFEntity payment() throws Exception {
      return shipped("FIN_Payment", PAYMENT_IN, new String[][] {
          { "em_etpr_remove_payment", "eTPRRemovePayment" },
          { "EM_APRM_Process_Payment", "aPRMProcessPayment" },
          { "EM_Etpr_Reactivate_Payment", "etprReactivatePayment" },
          { "Posted", "posted" } });
    }

    private SFEntity transaction() throws Exception {
      return shipped("FIN_Finacc_Transaction", FA_TRANSACTION, new String[][] {
          { "EM_Etpr_Remove_Transaction", "etprRemoveTransaction" },
          { "Posted", "posted" } });
    }

    private int refusalStatus(SFEntity entity, String action) {
      McpRoutingException e = assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity, action, new JSONObject()), action);
      try {
        return e.toEnvelope().getInt(McpConstants.KEY_STATUS);
      } catch (Exception json) {
        throw new AssertionError(json);
      }
    }

    @Test
    @DisplayName("eTPRRemovePayment passes precheck under both names; the PIS actions stay 405")
    void removePaymentPassesAndPisIsHidden() throws Exception {
      // The UI's Eliminar runs at every status but RPVOID/pisLocked; the handler gates those.
      assertNull(McpDeclaredActions.precheck(payment(), "eTPRRemovePayment", new JSONObject()));
      assertNull(McpDeclaredActions.precheck(payment(), "em_etpr_remove_payment",
          new JSONObject()));
      assertEquals(405, refusalStatus(payment(), "retryPisPayment"));
      assertEquals(405, refusalStatus(payment(), "pisPaymentStatus"));
    }

    @Test
    @DisplayName("Confirmar (value P) and Reactivar stay callable on the payment header")
    void uiActionsStayCallable() throws Exception {
      assertNull(McpDeclaredActions.precheck(payment(), "aPRMProcessPayment",
          new JSONObject().put("docAction", "P")));
      assertNull(McpDeclaredActions.precheck(payment(), "EM_APRM_Process_Payment",
          new JSONObject()));
      assertNull(McpDeclaredActions.precheck(payment(), "etprReactivatePayment",
          new JSONObject()));
      assertEquals(422, assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(payment(), "EM_APRM_Process_Payment",
              new JSONObject().put("docAction", "V"))).toEnvelope()
          .getInt(McpConstants.KEY_STATUS));
    }

    @Test
    @DisplayName("a movement's remove button is refused 405 under its DB name")
    void removeTransactionIsHidden() throws Exception {
      assertEquals(405, refusalStatus(transaction(), "EM_Etpr_Remove_Transaction"));
      assertEquals(405, refusalStatus(transaction(), "etprRemoveTransaction"));
      assertEquals(405, refusalStatus(transaction(), "Posted"));
    }

    @Test
    @DisplayName("post and unpost on a movement are not refused: no alias scan turns post into"
        + " posted")
    void postAndUnpostStayCallable() throws Exception {
      assertNull(McpDeclaredActions.precheck(transaction(), "post", new JSONObject()));
      assertNull(McpDeclaredActions.precheck(transaction(), "unpost", new JSONObject()));
    }
  }

  // ── the section contract ──────────────────────────────────────────────

  @Nested
  @DisplayName("MCP_CONFIG.actions validation")
  class Validation {

    @Test
    @DisplayName("valid with hidden, redirect and reason")
    void valid() throws Exception {
      assertTrue(McpActionsSection.validate(new JSONObject(HIDE_PIS).getJSONObject("actions"))
          .isEmpty());
    }

    @Test
    @DisplayName("reason is mandatory; hidden must be an array of names; redirect values strings")
    void invalid() throws Exception {
      assertFalse(McpActionsSection.validate(new JSONObject("{\"hidden\":[\"a\"]}")).isEmpty());
      assertFalse(McpActionsSection.validate(
          new JSONObject("{\"hidden\":\"a\",\"reason\":\"r\"}")).isEmpty());
      assertFalse(McpActionsSection.validate(
          new JSONObject("{\"hidden\":[\"\"],\"reason\":\"r\"}")).isEmpty());
      assertFalse(McpActionsSection.validate(
          new JSONObject("{\"redirect\":{\"a\":1},\"reason\":\"r\"}")).isEmpty());
      assertFalse(McpActionsSection.validate(new JSONObject("{\"reason\":\"r\"}")).isEmpty(),
          "names neither hidden nor redirect");
      assertFalse(McpActionsSection.validate(new JSONObject(
          "{\"redirect\":{\"a\":\"b\"},\"reason\":\"r\",\"redirectReason\":\" \"}")).isEmpty(),
          "a blank redirectReason is refused");
    }
  }

  // ── wiring ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("call sites")
  class CallSites {

    private String method(String name) {
      return McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), name);
    }

    @Test
    @DisplayName("neo_action prechecks before dispatching to the handler")
    void actionPrechecksFirst() {
      String body = method("handleAction");
      Matcher pre = Pattern.compile("McpDeclaredActions\\s*\\.\\s*precheck\\s*\\(").matcher(body);
      Matcher dispatch = Pattern.compile("NeoExtensionDispatcher\\s*\\.\\s*dispatch\\s*\\(")
          .matcher(body);
      assertTrue(pre.find(), "handleAction must call McpDeclaredActions.precheck");
      assertTrue(dispatch.find());
      assertTrue(pre.start() < dispatch.start(), "the precheck must run before the handler");
    }

    @Test
    @DisplayName("neo_action calls a declared action on the HTTP method its contract names")
    void actionUsesTheDeclaredMethod() {
      String body = method("handleAction");
      Matcher method = Pattern.compile("(\\w+)\\s*=\\s*\\w+\\s*!=\\s*null\\s*\\?\\s*\\w+\\s*\\."
          + "\\s*getHttpMethod\\s*\\(\\s*\\)").matcher(body);
      assertTrue(method.find(), "handleAction must read the contract's HTTP method");
      assertTrue(Pattern.compile("buildActionHookContext\\s*\\([^;]*\\b" + method.group(1)
          + "\\s*\\)").matcher(body).find(), "and build the handler context with it — "
          + "currencyOptions refuses anything but GET");
    }

    @Test
    @DisplayName("neo_schema replaces only when replacesSchema says so, and merges otherwise")
    void schemaMergesForWindows() {
      String body = method("handleSchema");
      assertTrue(Pattern.compile("McpDeclaredActions\\s*\\.\\s*replacesSchema\\s*\\(")
          .matcher(body).find());
      Matcher declared = Pattern.compile("(\\w+)\\s*=\\s*McpDeclaredActions\\s*\\.\\s*of\\s*\\(")
          .matcher(body);
      assertTrue(declared.find(), "handleSchema must resolve the declared actions");
      assertTrue(Pattern.compile("McpActionsView\\s*\\.\\s*buildResponse\\s*\\([^;]*\\b"
          + declared.group(1) + "\\b").matcher(body).find(),
          "view:\"actions\" must receive the declared actions to merge");
      Matcher excluded = Pattern.compile(
          "(\\w+)\\s*=\\s*McpDeclaredActions\\s*\\.\\s*excludedOf\\s*\\(").matcher(body);
      assertTrue(excluded.find(), "handleSchema must resolve the actions the customization excludes");
      assertTrue(Pattern.compile("McpActionsView\\s*\\.\\s*buildResponse\\s*\\([^;]*\\b"
          + excluded.group(1) + "\\b").matcher(body).find(),
          "and pass them to view:\"actions\", so they are not listed");
    }

    @Test
    @DisplayName("neo_schema shapes the buttons once, before every projection (full view too)")
    void schemaShapesButtonsBeforeEveryView() {
      String body = McpSourceScanner.stripComments(method("handleSchema"));
      Matcher shaped = Pattern.compile("(\\w+)\\s*=\\s*McpActionsView\\s*\\.\\s*applyConfig\\s*"
          + "\\(\\s*\\1\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)").matcher(body);
      assertTrue(shaped.find(), "handleSchema must reassign the field array through applyConfig");
      assertTrue(Pattern.compile("\\b" + shaped.group(2)
          + "\\s*=\\s*McpActionsSection\\s*\\.\\s*forEntity\\s*\\(").matcher(body).find(),
          "with the entity's MCP_CONFIG.actions");
      assertTrue(Pattern.compile("\\b" + shaped.group(3)
          + "\\s*=\\s*McpDeclaredActions\\s*\\.\\s*excludedOf\\s*\\(").matcher(body).find(),
          "and the customization's agent-excluded actions");
      Matcher built = Pattern.compile("\\b" + shaped.group(1)
          + "\\s*=\\s*McpSchemaFieldBuilder\\s*\\.\\s*buildSchemaFieldsArray\\s*\\(")
          .matcher(body);
      assertTrue(built.find());
      assertTrue(built.start() < shaped.start(), "after the array is built");
      for (String dispatch : List.of("McpActionsView\\s*\\.\\s*isActionsView\\s*\\(",
          "McpSchemaCreateView\\s*\\.\\s*isCreateView\\s*\\(",
          "McpSchemaCreateView\\s*\\.\\s*isFullView\\s*\\(",
          "McpSchemaCreateView\\s*\\.\\s*applyFieldWhitelist\\s*\\(")) {
        Matcher m = Pattern.compile(dispatch).matcher(body);
        assertTrue(m.find(), dispatch);
        assertTrue(shaped.start() < m.start(), "shaped before " + dispatch);
      }
    }

    @Test
    @DisplayName("neo_discover lists the declared actions of a window entity")
    void discoverListsThem() throws Exception {
      JSONObject item = McpSupportInternals.buildDiscoverEntity(entity("W", HIDE_PIS));
      assertEquals(List.of("registerPayment", "currencyOptions"),
          namesOfStrings(item.getJSONArray("actions")));
    }

    private List<String> namesOfStrings(JSONArray arr) throws Exception {
      List<String> out = new java.util.ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
        out.add(arr.getString(i));
      }
      return out;
    }
  }
}
