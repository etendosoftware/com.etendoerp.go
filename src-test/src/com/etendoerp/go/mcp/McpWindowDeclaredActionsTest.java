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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
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
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoHandler;
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
  }

  @AfterEach
  void tearDown() {
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
      + "\"reason\":\"PIS needs a person to authorize at the bank\"}}";

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
