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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoCalloutEndpoint}.
 * Covers handleCallout validation paths, cascade merging, afterCallout hook,
 * and exception handling.
 */
class NeoCalloutEndpointTest {

  private NeoServlet servlet;
  private NeoCalloutEndpoint endpoint;
  private SFSpec spec;
  private NeoPathInfo pathInfo;

  private MockedStatic<NeoCalloutService> calloutServiceMock;
  private MockedStatic<NeoDefaultsCascadeHelper> cascadeHelperMock;
  private MockedStatic<NeoRequestBodyParser> bodyParserMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    servlet = mock(NeoServlet.class);
    endpoint = new NeoCalloutEndpoint(servlet);
    spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC-001");

    pathInfo = new NeoPathInfo("mySpec", "Header", null,
        false, null, false, null, false, true);

    calloutServiceMock = mockStatic(NeoCalloutService.class);
    cascadeHelperMock = mockStatic(NeoDefaultsCascadeHelper.class);
    bodyParserMock = mockStatic(NeoRequestBodyParser.class);
    obContextMock = mockStatic(OBContext.class);

    OBContext ctx = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(ctx);
  }

  @AfterEach
  void tearDown() {
    if (obContextMock != null) {
      obContextMock.close();
    }
    if (bodyParserMock != null) {
      bodyParserMock.close();
    }
    if (cascadeHelperMock != null) {
      cascadeHelperMock.close();
    }
    if (calloutServiceMock != null) {
      calloutServiceMock.close();
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────

  private SFEntity mockEntityWithTab(Tab tab) {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getADTab()).thenReturn(tab);
    when(sfEntity.getJavaQualifier()).thenReturn("");
    when(servlet.findEntity(anyString(), anyString())).thenReturn(sfEntity);
    return sfEntity;
  }

  private void stubBodyParser(String body, JSONObject result) throws Exception {
    bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(any()))
        .thenReturn(body);
    bodyParserMock.when(() -> NeoRequestBodyParser.parseJsonObject(body))
        .thenReturn(result);
  }

  private void stubCascadeNoResults() {
    NeoDefaultsService.CalloutCascadeResult emptyResult =
        new NeoDefaultsService.CalloutCascadeResult();
    cascadeHelperMock.when(() -> NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
        any(), any(), anyString(), any(), any())).thenReturn(emptyResult);
  }

  // ── handleCallout: validation paths ─────────────────────────────────

  @Nested
  @DisplayName("handleCallout validation")
  class HandleCalloutValidation {

    @Test
    @DisplayName("Returns 404 when entity is not found")
    void entityNotFound() {
      when(servlet.findEntity(anyString(), anyString())).thenReturn(null);
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 500 when entity has no linked tab")
    void noLinkedTab() {
      SFEntity sfEntity = mock(SFEntity.class);
      when(sfEntity.getADTab()).thenReturn(null);
      when(servlet.findEntity(anyString(), anyString())).thenReturn(sfEntity);
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 400 when body is blank")
    void blankBody() throws Exception {
      mockEntityWithTab(mock(Tab.class));
      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(any()))
          .thenReturn("  ");
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 400 when body is invalid JSON")
    void invalidJson() throws Exception {
      mockEntityWithTab(mock(Tab.class));
      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(any()))
          .thenReturn("not-json");
      bodyParserMock.when(() -> NeoRequestBodyParser.parseJsonObject("not-json"))
          .thenThrow(new JSONException("Invalid JSON"));
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }

    @Test
    @DisplayName("Returns 400 when 'field' key is missing")
    void missingFieldKey() throws Exception {
      mockEntityWithTab(mock(Tab.class));
      JSONObject body = new JSONObject();
      body.put("formState", new JSONObject());
      stubBodyParser("{\"formState\":{}}", body);
      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }
  }

  // ── handleCallout: successful execution ─────────────────────────────

  @Nested
  @DisplayName("handleCallout success")
  class HandleCalloutSuccess {

    @Test
    @DisplayName("Successful callout returns 200 and applies cascade")
    void successfulCallout() throws Exception {
      Tab tab = mock(Tab.class);
      mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "businessPartner");
      requestBody.put("formState", new JSONObject());
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", new JSONObject().put("priceList", "PL-001"));
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
      assertTrue(response.getBody().has("updates"));
    }

    @Test
    @DisplayName("Non-200 callout result skips cascade and hook")
    void non200SkipsCascade() throws Exception {
      Tab tab = mock(Tab.class);
      mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "businessPartner");
      stubBodyParser(requestBody.toString(), requestBody);

      NeoResponse errorResult = NeoResponse.error(
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Callout failed");
      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(errorResult);

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
      cascadeHelperMock.verify(() -> NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
          any(), any(), anyString(), any(), any()), never());
    }
  }

  // ── handleCallout: exception path ───────────────────────────────────

  @Test
  @DisplayName("Exception during callout processing returns 500")
  void exceptionReturns500() throws Exception {
    Tab tab = mock(Tab.class);
    mockEntityWithTab(tab);

    JSONObject requestBody = new JSONObject();
    requestBody.put("field", "documentNo");
    stubBodyParser(requestBody.toString(), requestBody);

    calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
        .thenThrow(new RuntimeException("Unexpected failure"));

    HttpServletRequest request = mock(HttpServletRequest.class);

    NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.getHttpStatus());
  }

  // ── Cascade merging ─────────────────────────────────────────────────

  @Nested
  @DisplayName("mergeCalloutResponse / mergeJsonSection")
  class MergingTests {

    @Test
    @DisplayName("Updates from cascade are merged into base response")
    void updatesMerged() throws Exception {
      Tab tab = mock(Tab.class);
      mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "businessPartner");
      requestBody.put("formState", new JSONObject());
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject baseUpdates = new JSONObject();
      baseUpdates.put("priceList", "PL-001");
      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", baseUpdates);
      responseBody.put("combos", new JSONObject());
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);

      JSONObject cascadeUpdates = new JSONObject();
      cascadeUpdates.put("paymentTerm", "30 days");
      JSONObject cascadeJson = new JSONObject();
      cascadeJson.put("updates", cascadeUpdates);
      cascadeJson.put("combos", new JSONObject());

      NeoDefaultsService.CalloutCascadeResult cascadeResult =
          new NeoDefaultsService.CalloutCascadeResult();
      cascadeResult.mergeUpdates(cascadeUpdates);

      cascadeHelperMock.when(() -> NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
          any(), any(), anyString(), any(), any())).thenReturn(cascadeResult);

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
      JSONObject resultUpdates = response.getBody().getJSONObject("updates");
      assertEquals("PL-001", resultUpdates.getString("priceList"));
      assertEquals("30 days", resultUpdates.getString("paymentTerm"));
    }

    @Test
    @DisplayName("Existing keys in base are not overwritten by cascade")
    void existingKeysNotOverwritten() throws Exception {
      Tab tab = mock(Tab.class);
      mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "businessPartner");
      requestBody.put("formState", new JSONObject());
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject baseUpdates = new JSONObject();
      baseUpdates.put("priceList", "PL-ORIGINAL");
      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", baseUpdates);
      responseBody.put("combos", new JSONObject());
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);

      JSONObject cascadeUpdates = new JSONObject();
      cascadeUpdates.put("priceList", "PL-CASCADE");
      cascadeUpdates.put("warehouse", "WH-001");

      NeoDefaultsService.CalloutCascadeResult cascadeResult =
          new NeoDefaultsService.CalloutCascadeResult();
      cascadeResult.mergeUpdates(cascadeUpdates);

      cascadeHelperMock.when(() -> NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
          any(), any(), anyString(), any(), any())).thenReturn(cascadeResult);

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      JSONObject resultUpdates = response.getBody().getJSONObject("updates");
      assertEquals("PL-ORIGINAL", resultUpdates.getString("priceList"),
          "Original key must not be overwritten");
      assertEquals("WH-001", resultUpdates.getString("warehouse"),
          "New key from cascade must be added");
    }

    @Test
    @DisplayName("Combos from cascade are merged into base response")
    void combosMerged() throws Exception {
      Tab tab = mock(Tab.class);
      mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "businessPartner");
      requestBody.put("formState", new JSONObject());
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject baseCombos = new JSONObject();
      baseCombos.put("paymentMethod", new JSONObject().put("selected", "PM-001"));
      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", new JSONObject());
      responseBody.put("combos", baseCombos);
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);

      JSONObject cascadeCombos = new JSONObject();
      cascadeCombos.put("warehouse", new JSONObject().put("selected", "WH-001"));

      NeoDefaultsService.CalloutCascadeResult cascadeResult =
          new NeoDefaultsService.CalloutCascadeResult();
      cascadeResult.mergeCombos(cascadeCombos);

      cascadeHelperMock.when(() -> NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
          any(), any(), anyString(), any(), any())).thenReturn(cascadeResult);

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      JSONObject resultCombos = response.getBody().getJSONObject("combos");
      assertTrue(resultCombos.has("paymentMethod"), "Original combo must remain");
      assertTrue(resultCombos.has("warehouse"), "Cascade combo must be added");
    }
  }

  // ── mergeJsonSection: static edge cases (private, tested via reflection) ──

  @Nested
  @DisplayName("mergeJsonSection static behavior")
  class MergeJsonSectionTests {

    private void invokeMergeJsonSection(JSONObject base, JSONObject addition, String sectionKey)
        throws Exception {
      Method method = NeoCalloutEndpoint.class.getDeclaredMethod(
          "mergeJsonSection", JSONObject.class, JSONObject.class, String.class);
      method.setAccessible(true);
      method.invoke(null, base, addition, sectionKey);
    }

    @Test
    @DisplayName("Null addition section is safely ignored")
    void nullAdditionSection() throws Exception {
      JSONObject base = new JSONObject();
      base.put("updates", new JSONObject().put("a", 1));
      JSONObject addition = new JSONObject();

      invokeMergeJsonSection(base, addition, "updates");

      assertEquals(1, base.getJSONObject("updates").getInt("a"));
    }

    @Test
    @DisplayName("Null base section adopts addition section entirely")
    void nullBaseSection() throws Exception {
      JSONObject base = new JSONObject();
      JSONObject addSection = new JSONObject().put("x", "y");
      JSONObject addition = new JSONObject();
      addition.put("combos", addSection);

      invokeMergeJsonSection(base, addition, "combos");

      assertEquals("y", base.getJSONObject("combos").getString("x"));
    }

    @Test
    @DisplayName("Existing keys in base section are not overwritten")
    void existingKeysPreserved() throws Exception {
      JSONObject baseSection = new JSONObject().put("k1", "original");
      JSONObject base = new JSONObject().put("updates", baseSection);

      JSONObject addSection = new JSONObject().put("k1", "overwrite").put("k2", "new");
      JSONObject addition = new JSONObject().put("updates", addSection);

      invokeMergeJsonSection(base, addition, "updates");

      assertEquals("original", base.getJSONObject("updates").getString("k1"));
      assertEquals("new", base.getJSONObject("updates").getString("k2"));
    }
  }

  // ── afterCallout hook ───────────────────────────────────────────────

  @Nested
  @DisplayName("afterCallout hook")
  class AfterCalloutHookTests {

    @Test
    @DisplayName("Blank qualifier skips handler lookup entirely")
    void blankQualifierSkipsHook() throws Exception {
      Tab tab = mock(Tab.class);
      SFEntity sfEntity = mockEntityWithTab(tab);
      when(sfEntity.getJavaQualifier()).thenReturn("");

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "documentNo");
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", new JSONObject());
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      HttpServletRequest request = mock(HttpServletRequest.class);

      endpoint.handleCallout(spec, pathInfo, request);

      verify(servlet, never()).lookupHandler(anyString());
    }

    @Test
    @DisplayName("Handler returning null does not alter callout response")
    void handlerReturnsNull() throws Exception {
      Tab tab = mock(Tab.class);
      SFEntity sfEntity = mockEntityWithTab(tab);
      when(sfEntity.getJavaQualifier()).thenReturn("com.example.MyHandler");

      NeoHandler handler = mock(NeoHandler.class);
      when(handler.afterCallout(any())).thenReturn(null);
      when(servlet.lookupHandler("com.example.MyHandler")).thenReturn(handler);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "documentNo");
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", new JSONObject().put("total", "100"));
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
      assertEquals("100", response.getBody().getJSONObject("updates").getString("total"));
    }

    @Test
    @DisplayName("Handler enriches callout response via afterCallout merge")
    void handlerEnrichesResponse() throws Exception {
      Tab tab = mock(Tab.class);
      SFEntity sfEntity = mockEntityWithTab(tab);
      when(sfEntity.getJavaQualifier()).thenReturn("com.example.MyHandler");

      JSONObject handlerBody = new JSONObject();
      handlerBody.put("updates", new JSONObject().put("discount", "10%"));
      handlerBody.put("combos", new JSONObject());
      NeoResponse handlerResponse = NeoResponse.ok(handlerBody);

      NeoHandler handler = mock(NeoHandler.class);
      when(handler.afterCallout(any())).thenReturn(handlerResponse);
      when(servlet.lookupHandler("com.example.MyHandler")).thenReturn(handler);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "documentNo");
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", new JSONObject().put("total", "100"));
      responseBody.put("combos", new JSONObject());
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      JSONObject updates = response.getBody().getJSONObject("updates");
      assertEquals("100", updates.getString("total"), "Original field kept");
      assertEquals("10%", updates.getString("discount"), "Handler field added");
    }

    @Test
    @DisplayName("afterCallout exception is non-fatal, original result preserved")
    void afterCalloutExceptionNonFatal() throws Exception {
      Tab tab = mock(Tab.class);
      SFEntity sfEntity = mockEntityWithTab(tab);
      when(sfEntity.getJavaQualifier()).thenReturn("com.example.MyHandler");

      NeoHandler handler = mock(NeoHandler.class);
      when(handler.afterCallout(any()))
          .thenThrow(new RuntimeException("Hook exploded"));
      when(servlet.lookupHandler("com.example.MyHandler")).thenReturn(handler);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "documentNo");
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", new JSONObject().put("total", "200"));
      responseBody.put("combos", new JSONObject());
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      HttpServletRequest request = mock(HttpServletRequest.class);

      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus(),
          "Hook exception must not change HTTP status");
      assertEquals("200", response.getBody().getJSONObject("updates").getString("total"),
          "Original response must be preserved");
    }
  }

  // ── ETP-4917: strips read-only-on-create fields from the final response ──

  @Nested
  @DisplayName("ETP-4917: read-only-on-create field stripping")
  class ReadOnlyOnCreateStripping {

    /**
     * Builds a {@link NeoFieldFilter} via the same reflective constructor
     * {@code NeoFieldFilterTest} uses, so this test does not need a live DAL/DB to exercise
     * {@code forEntity}. Mirrors the exact ETP-4917 config: {@code debit}/{@code credit} are
     * included + read-only, no default, no handler -> rejectable on create.
     */
    private NeoFieldFilter buildRejectableFilter(Set<String> rejectableOnCreate) throws Exception {
      Constructor<NeoFieldFilter> ctor = NeoFieldFilter.class.getDeclaredConstructor(
          Set.class, Set.class, Set.class, Map.class, Map.class, boolean.class);
      ctor.setAccessible(true);
      Set<String> included = new HashSet<>(rejectableOnCreate);
      included.add("id");
      included.add("foreignCurrencyDebit");
      Set<String> writable = Set.of("id", "foreignCurrencyDebit");
      return ctor.newInstance(included, writable, rejectableOnCreate,
          Collections.emptyMap(), Collections.emptyMap(), true);
    }

    /**
     * Reproduces the ETP-4917 bug end to end through {@code handleCallout}: the legacy callout
     * for a G/L journal line answers with the user-edited field ({@code foreignCurrencyDebit})
     * plus the derived accounted-amount fields ({@code debit}/{@code credit}), which are
     * read-only-on-create with no handler/default for this entity. Before the fix, both leaked
     * into the response the frontend merges into local state and later spreads into a create
     * request, causing NeoCrudHandler's IMP-28 clause 2 to reject the create with a 422. After
     * the fix, {@code debit}/{@code credit} must be gone from the response while
     * {@code foreignCurrencyDebit} survives untouched.
     */
    @Test
    @DisplayName("debit/credit are stripped from the callout response, foreignCurrencyDebit survives")
    void stripsDebitCreditFromGLJournalLineCallout() throws Exception {
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("FIN_Gl_Journal_Line");
      Tab tab = mock(Tab.class);
      when(tab.getTable()).thenReturn(table);
      SFEntity sfEntity = mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "foreignCurrencyDebit");
      requestBody.put("value", "100.00");
      requestBody.put("formState", new JSONObject());
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject updates = new JSONObject();
      updates.put("foreignCurrencyDebit", new JSONObject().put("value", "100.00"));
      updates.put("debit", new JSONObject().put("value", "100.00"));
      updates.put("credit", new JSONObject().put("value", "0.00"));
      JSONObject responseBody = new JSONObject();
      responseBody.put("updates", updates);
      responseBody.put("combos", new JSONObject());
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      NeoFieldFilter filter = buildRejectableFilter(new HashSet<>(Set.of("debit", "credit")));

      try (MockedStatic<NeoFieldFilter> fieldFilterMock = mockStatic(NeoFieldFilter.class)) {
        fieldFilterMock.when(() -> NeoFieldFilter.forEntity(sfEntity, "FIN_Gl_Journal_Line"))
            .thenReturn(filter);

        HttpServletRequest request = mock(HttpServletRequest.class);
        NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

        assertEquals(200, response.getHttpStatus());
        JSONObject resultUpdates = response.getBody().getJSONObject("updates");
        assertTrue(resultUpdates.has("foreignCurrencyDebit"),
            "the field the user is editing must survive");
        assertFalse(resultUpdates.has("debit"),
            "read-only-on-create field must be stripped from the callout response");
        assertFalse(resultUpdates.has("credit"),
            "read-only-on-create field must be stripped from the callout response");
      }
    }

    @Test
    @DisplayName("no NeoFieldFilter config (inactive) leaves the response untouched")
    void inactiveFilterLeavesResponseUntouched() throws Exception {
      Table table = mock(Table.class);
      when(table.getName()).thenReturn("SomeTable");
      Tab tab = mock(Tab.class);
      when(tab.getTable()).thenReturn(table);
      SFEntity sfEntity = mockEntityWithTab(tab);

      JSONObject requestBody = new JSONObject();
      requestBody.put("field", "amount");
      requestBody.put("formState", new JSONObject());
      stubBodyParser(requestBody.toString(), requestBody);

      JSONObject updates = new JSONObject().put("amount", new JSONObject().put("value", "5"));
      JSONObject responseBody = new JSONObject().put("updates", updates);
      NeoResponse calloutResult = NeoResponse.ok(responseBody);

      calloutServiceMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResult);
      stubCascadeNoResults();

      HttpServletRequest request = mock(HttpServletRequest.class);
      // No NeoFieldFilter mocking here: the real forEntity(...) runs, fails to resolve a DAL
      // entity for "SomeTable" outside a live OBDal/ModelProvider context, and falls back to
      // an inactive filter — exercising the same no-op path a genuinely unconfigured entity hits.
      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(200, response.getHttpStatus());
      assertEquals("5",
          response.getBody().getJSONObject("updates").getJSONObject("amount").getString("value"));
    }
  }

  // ── parseRequestBody ────────────────────────────────────────────────

  @Nested
  @DisplayName("parseRequestBody static delegation")
  class ParseRequestBodyTests {

    @Test
    @DisplayName("Returns NeoResponse error when parsing fails")
    void parseFailureReturnsError() throws Exception {
      bodyParserMock.when(() -> NeoRequestBodyParser.parseJsonObject("bad"))
          .thenThrow(new JSONException("Unexpected token"));

      Tab tab = mock(Tab.class);
      mockEntityWithTab(tab);
      bodyParserMock.when(() -> NeoRequestBodyParser.readRequestBody(any()))
          .thenReturn("bad");

      HttpServletRequest request = mock(HttpServletRequest.class);
      NeoResponse response = endpoint.handleCallout(spec, pathInfo, request);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.getHttpStatus());
    }
  }
}
