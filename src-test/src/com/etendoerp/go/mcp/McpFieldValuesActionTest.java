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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.openbravo.model.financialmgmt.calendar.Period;

import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoExtensionRequest;
import com.etendoerp.go.schemaforge.NeoExtensionResult;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.PeriodOpenCloseHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * ETP-5587 — a declared contract that describes an AD button whose parameters the SPA posts under
 * {@code fieldValues}.
 *
 * <p>{@code periodControl.openClose} could not be pressed through MCP: {@code etendo_schema}
 * advertised it under {@code docAction} with the C/N/O/P reference list, while
 * {@code PeriodOpenCloseHandler} reads {@code fieldValues.openClose} — the body the SPA's process
 * dialog posts — and the SPA offers O/C/P only. Every call answered 400 "Missing required
 * parameter: openClose", and firing the button by its column name ({@code OpenClose}) skipped the
 * handler and failed in the OBUIAPP process behind it.</p>
 *
 * @covers com.etendoerp.go.mcp.McpActionsView
 * @covers com.etendoerp.go.schemaforge.util.NeoActionContract
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects the outer class.
@SuppressWarnings("java:S2187")
@DisplayName("ETP-5587 — declared button actions read from fieldValues")
class McpFieldValuesActionTest {

  private static final String SPEC = "open-close-period-control";
  private static final String ENTITY = "periodControl";
  private static final String ACTION = "openClose";
  private static final String COLUMN = "OpenClose";
  private static final String TABLE = "C_Period";
  private static final String PARAMETERS = "parameters";
  private static final String FIELD_VALUES = "fieldValues";

  private MockedStatic<NeoExtensionIndex> indexMock;
  private MockedStatic<NeoHandlerLookup> lookupMock;
  private MockedStatic<NeoButtonActionHelper> buttonMock;
  private MockedStatic<ModelProvider> modelMock;

  @BeforeEach
  void setUp() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
    indexMock = mockStatic(NeoExtensionIndex.class);
    lookupMock = mockStatic(NeoHandlerLookup.class);
    lookupMock.when(() -> NeoHandlerLookup.byQualifierQuietly(anyString()))
        .thenReturn(new PeriodOpenCloseHandler());
    buttonMock = mockStatic(NeoButtonActionHelper.class);
    ModelProvider provider = mock(ModelProvider.class);
    Entity dal = mock(Entity.class);
    modelMock = mockStatic(ModelProvider.class);
    modelMock.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntityByTableName(TABLE)).thenReturn(dal);
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(ACTION);
    when(dal.getPropertyByColumnName(COLUMN)).thenReturn(property);
    Column column = buttonColumn();
    buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), eq(COLUMN)))
        .thenReturn(column);
    buttonMock.when(() -> NeoButtonActionHelper.findButtonColumn(anyString(), eq(ACTION)))
        .thenReturn(column);
  }

  @AfterEach
  void tearDown() {
    modelMock.close();
    buttonMock.close();
    lookupMock.close();
    indexMock.close();
    McpConfigCache.invalidateAll();
  }

  private static Column buttonColumn() {
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(TABLE);
    Reference button = mock(Reference.class);
    when(button.getId()).thenReturn("28");
    Column column = mock(Column.class);
    when(column.getDBColumnName()).thenReturn(COLUMN);
    when(column.getTable()).thenReturn(table);
    when(column.getReference()).thenReturn(button);
    when(column.isActive()).thenReturn(true);
    return column;
  }

  private static SFEntity entity() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-period");
    when(spec.getName()).thenReturn(SPEC);
    when(spec.getSpecType()).thenReturn("W");
    List<Column> columns = new ArrayList<>(List.of(buttonColumn()));
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(TABLE);
    when(table.getADColumnList()).thenReturn(columns);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("ent-period");
    when(entity.getName()).thenReturn(ENTITY);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getADTab()).thenReturn(tab);
    when(entity.getJavaQualifier()).thenReturn("period-openclose");
    when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(null);
    return entity;
  }

  private static NeoActionContract openClose() {
    return new PeriodOpenCloseHandler().actionContracts().get(ACTION);
  }

  /** The button as McpSchemaActionFields describes it from its AD reference list. */
  private static JSONObject adButton() throws Exception {
    return new JSONObject().put("name", ACTION).put("column", COLUMN).put("type", "button")
        .put("invokeVia", "etendo_action")
        .put(McpConstants.KEY_ACTION_PARAMETER, McpConstants.PARAM_DOC_ACTION)
        .put(McpConstants.KEY_ACTION_VALUES, new JSONArray()
            .put(new JSONObject().put("value", "C")).put(new JSONObject().put("value", "N"))
            .put(new JSONObject().put("value", "O")).put(new JSONObject().put("value", "P")));
  }

  private static List<String> enumOf(JSONObject schema) throws Exception {
    JSONArray values = schema.getJSONObject("properties").getJSONObject(ACTION)
        .getJSONArray("enum");
    List<String> out = new ArrayList<>();
    for (int i = 0; i < values.length(); i++) {
      out.add(values.getString(i));
    }
    return out;
  }

  // ── the contract the handler declares ──────────────────────────────────

  @Nested
  @DisplayName("PeriodOpenCloseHandler's contract")
  class HandlerContract {

    @Test
    @DisplayName("declares openClose with the SPA's parameter and only the values it offers")
    void declaresWhatTheSpaSends() throws Exception {
      NeoActionContract c = openClose();
      assertNotNull(c, "openClose is declared");
      assertTrue(c.isMutating());
      assertTrue(c.isFieldValuesBody(), "the handler reads fieldValues.openClose");
      JSONObject schema = c.toJson().getJSONObject(PARAMETERS);
      assertEquals(List.of("O", "C", "P"), enumOf(schema));
      assertEquals(ACTION, schema.getJSONArray("required").getString(0));
    }

    @Test
    @DisplayName("withFieldValuesBody survives the other copies; plain contracts stay flat")
    void flagIsCarried() {
      NeoActionContract plain = NeoActionContract.write("x", "x");
      assertFalse(plain.isFieldValuesBody());
      assertTrue(plain.withFieldValuesBody().withIdDescription("id").withHttpMethod("GET")
          .isFieldValuesBody());
      assertTrue(plain.withIdDescription("id").withFieldValuesBody().isFieldValuesBody());
    }
  }

  // ── the body etendo_action sends ──────────────────────────────────────────

  @Nested
  @DisplayName("McpToolRouter.actionBody")
  class ActionBody {

    @Test
    @DisplayName("wraps the parameters under fieldValues, as the SPA posts them")
    void wraps() throws Exception {
      JSONObject flat = new JSONObject().put(ACTION, "O");
      JSONObject body = McpToolRouter.actionBody(openClose(), flat);
      assertEquals(1, body.length(), body.toString());
      assertSame(flat, body.getJSONObject(FIELD_VALUES));
    }

    @Test
    @DisplayName("an empty call is still wrapped, never null")
    void wrapsEmpty() throws Exception {
      JSONObject body = McpToolRouter.actionBody(openClose(), null);
      assertEquals(0, body.getJSONObject(FIELD_VALUES).length());
    }

    @Test
    @DisplayName("a contract that reads its body flat, and an AD button, keep the parameters as sent")
    void flatOtherwise() throws Exception {
      JSONObject flat = new JSONObject().put("docAction", "CO");
      assertSame(flat, McpToolRouter.actionBody(null, flat));
      assertSame(flat, McpToolRouter.actionBody(NeoActionContract.write("x", "x"), flat));
      assertEquals(0, McpToolRouter.actionBody(null, null).length());
    }
  }

  // ── the precheck ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("etendo_action precheck")
  class Precheck {

    @Test
    @DisplayName("the button's DB column name is judged by the contract that describes it")
    void columnNameFindsTheContract() throws Exception {
      NeoActionContract c = McpDeclaredActions.precheck(entity(), COLUMN,
          new JSONObject().put(ACTION, "O"));
      assertNotNull(c, "OpenClose is the openClose button");
      assertEquals(ACTION, c.getName());
    }

    @Test
    @DisplayName("docAction, which the catalogue used to advertise, is refused naming the key")
    void docActionIsRefused() throws Exception {
      SFEntity entity = entity();
      JSONObject params = new JSONObject().put("docAction", "O");
      McpRoutingException e = assertThrows(McpRoutingException.class,
          () -> McpDeclaredActions.precheck(entity, ACTION, params));
      assertEquals(422, e.toEnvelope().getInt(McpConstants.KEY_STATUS));
      assertTrue(e.toEnvelope().toString().contains("docAction"), e.toEnvelope().toString());
    }

    @Test
    @DisplayName("N, in the reference list but not offered by the SPA, is refused")
    void notOfferedValueIsRefused() throws Exception {
      for (String action : List.of(ACTION, COLUMN)) {
        SFEntity entity = entity();
        JSONObject params = new JSONObject().put(ACTION, "N");
        McpRoutingException e = assertThrows(McpRoutingException.class,
            () -> McpDeclaredActions.precheck(entity, action, params));
        assertEquals(422, e.toEnvelope().getInt(McpConstants.KEY_STATUS), action);
      }
    }
  }

  // ── etendo_schema ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("etendo_schema")
  class Schema {

    @Test
    @DisplayName("every projection describes the button by its contract, not its reference list")
    void buttonDescribedByContract() throws Exception {
      JSONArray fields = new JSONArray().put(adButton())
          .put(new JSONObject().put("name", "name").put("type", "string"));
      McpActionsView.describeDeclaredButtons(fields, Map.of(ACTION, openClose()));
      JSONObject button = fields.getJSONObject(0);
      assertFalse(button.has(McpConstants.KEY_ACTION_PARAMETER), button.toString());
      assertFalse(button.has(McpConstants.KEY_ACTION_VALUES), button.toString());
      assertEquals(List.of("O", "C", "P"), enumOf(button.getJSONObject(PARAMETERS)));
      assertEquals(ACTION, button.getString("declaredAction"));
      assertEquals(2, fields.getJSONObject(1).length(), "a non-button field is untouched");
    }

    @Test
    @DisplayName("a button is matched by its DB column too")
    void matchedByColumn() throws Exception {
      JSONArray fields = new JSONArray().put(adButton().put("name", "somethingElse"));
      McpActionsView.describeDeclaredButtons(fields, Map.of(COLUMN, openClose()));
      assertTrue(fields.getJSONObject(0).has(PARAMETERS));
    }

    @Test
    @DisplayName("a button no contract describes keeps its reference list")
    void undescribedButtonUnchanged() throws Exception {
      JSONArray fields = new JSONArray().put(adButton());
      McpActionsView.describeDeclaredButtons(fields,
          Map.of("other", NeoActionContract.write("other", "x")));
      assertEquals(McpConstants.PARAM_DOC_ACTION,
          fields.getJSONObject(0).getString(McpConstants.KEY_ACTION_PARAMETER));
      assertSame(fields, McpActionsView.describeDeclaredButtons(fields, Map.of()));
    }

    @Test
    @DisplayName("handleSchema describes the buttons by their contracts before every projection")
    void handleSchemaDescribesBeforeEveryView() {
      String body = McpSourceScanner.stripComments(McpSourceScanner.methodBody(
          McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleSchema"));
      Matcher declared = Pattern.compile("(\\w+)\\s*=\\s*McpDeclaredActions\\s*\\.\\s*of\\s*\\(")
          .matcher(body);
      assertTrue(declared.find(), "handleSchema resolves the declared actions");
      Matcher described = Pattern.compile("McpActionsView\\s*\\.\\s*describeDeclaredButtons\\s*"
          + "\\(\\s*(\\w+)\\s*,\\s*" + declared.group(1) + "\\s*\\)").matcher(body);
      assertTrue(described.find(), "and describes the field array's buttons by them");
      Matcher built = Pattern.compile("\\b" + described.group(1)
          + "\\s*=\\s*McpSchemaFieldBuilder\\s*\\.\\s*buildSchemaFieldsArray\\s*\\(").matcher(body);
      assertTrue(built.find() && built.start() < described.start(), "after it is built");
      for (String dispatch : List.of("McpActionsView\\s*\\.\\s*isActionsView\\s*\\(",
          "McpSchemaCreateView\\s*\\.\\s*isCreateView\\s*\\(",
          "McpSchemaCreateView\\s*\\.\\s*isFullView\\s*\\(")) {
        Matcher m = Pattern.compile(dispatch).matcher(body);
        assertTrue(m.find() && described.start() < m.start(), "described before " + dispatch);
      }
    }

    @Test
    @DisplayName("view:\"actions\" lists the action once — the contract, not the button too")
    void listedOnce() throws Exception {
      JSONArray fields = new JSONArray().put(adButton());
      JSONObject view = McpActionsView.buildResponse(SPEC, ENTITY, fields,
          Map.of(ACTION, openClose()), null);
      JSONArray actions = view.getJSONArray(McpActionsView.KEY_ACTIONS);
      assertEquals(1, actions.length(), actions.toString());
      assertEquals(ACTION, actions.getJSONObject(0).getString("action"));
      assertEquals(List.of("O", "C", "P"),
          enumOf(actions.getJSONObject(0).getJSONObject(PARAMETERS)));
      assertEquals(1, view.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }
  }

  // ── the real path ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("etendo_action through handleAction, reaching the real handler")
  class RealPath {

    private MockedStatic<McpToolRouterSupport> supportMock;
    private final List<NeoExtensionRequest> dispatched = new ArrayList<>();

    @BeforeEach
    void route() {
      supportMock = mockStatic(McpToolRouterSupport.class);
      supportMock.when(() -> McpToolRouterSupport.validateArgs(any(), any(String[].class)))
          .thenCallRealMethod();
      SFEntity current = entity();
      SFSpec spec = current.getETGOSFSpec();
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);
      supportMock.when(() -> McpToolRouterSupport.findIncludedEntity(anyString(), eq(ENTITY)))
          .thenReturn(current);
      supportMock.when(() -> McpToolRouterSupport.toMcpHandlerError(any(), anyInt()))
          .thenAnswer(inv -> new JSONObject().put(McpConstants.KEY_STATUS, (int) inv.getArgument(1))
              .put("body", (Object) inv.getArgument(0)));
    }

    @AfterEach
    void close() {
      supportMock.close();
    }

    /** Runs etendo_action with the dispatcher handing the context to the real handler. */
    private String act(String action, JSONObject parameters) throws Exception {
      try (MockedStatic<NeoActionRecordGuard> guard = mockStatic(NeoActionRecordGuard.class);
          MockedStatic<NeoExtensionDispatcher> dispatch = mockStatic(NeoExtensionDispatcher.class);
          MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
          MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(eq(Period.class), anyString())).thenReturn(null);
        NeoHandler real = new PeriodOpenCloseHandler();
        dispatch.when(() -> NeoExtensionDispatcher.dispatch(any())).thenAnswer(inv -> {
          NeoExtensionRequest request = inv.getArgument(0);
          dispatched.add(request);
          return new NeoExtensionResult(real, real.handle(request.context()), null);
        });
        JSONObject args = new JSONObject().put("entity", ENTITY).put("id", "PERIOD-NOV-26")
            .put("action", action).put(PARAMETERS, parameters);
        return new McpToolRouter().handleAction(SPEC, args).toString();
      }
    }

    @Test
    @DisplayName("the handler receives {fieldValues:{openClose}} and gets past its parameter check")
    void handlerReadsTheBody() throws Exception {
      String result = act(ACTION, new JSONObject().put(ACTION, "O"));
      // The period lookup is the first thing after the parameter check: reaching it (404, the
      // mocked DAL has no such period) proves the body parsed; before ETP-5587 this was the 400.
      assertTrue(result.contains("Period not found"), result);
      assertFalse(result.contains("Missing required parameter"), result);
      JSONObject body = dispatched.get(0).context().getRequestBody();
      assertEquals("O", body.getJSONObject(FIELD_VALUES).getString(ACTION), body.toString());
      assertFalse(body.has(ACTION), "the value travels under fieldValues only: " + body);
    }

    @Test
    @DisplayName("called by its DB column name, it reaches the handler under the declared name")
    void columnNameReachesTheHandler() throws Exception {
      String result = act(COLUMN, new JSONObject().put(ACTION, "C"));
      assertTrue(result.contains("Period not found"), result);
      assertEquals(ACTION, dispatched.get(0).context().getFieldName());
      buttonMock.verify(() -> NeoButtonActionHelper.executeButtonActionCore(any(), anyString(),
          anyString(), any()), org.mockito.Mockito.never());
    }
  }
}
