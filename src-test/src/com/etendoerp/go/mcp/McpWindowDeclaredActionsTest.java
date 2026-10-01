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
      assertTrue(Pattern.compile("McpActionsView\\s*\\.\\s*buildResponse\\s*\\([^;]*"
          + "McpDeclaredActions\\s*\\.\\s*excludedOf\\s*\\(").matcher(body).find(),
          "and the actions the customization excludes, so they are not listed");
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
