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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;

/**
 * Unit tests for {@link GlJournalHeaderHandler}.
 *
 * <p>Covers the testable pure-logic paths that do not require a running Etendo DB:
 * <ul>
 *   <li>{@code afterHandle()} — always returns null.</li>
 *   <li>{@code handle()} pass-through for non-ACTION / non-POST endpoints.</li>
 *   <li>Complete-action detection: wrong endpoint type, wrong fieldName, wrong docAction value.</li>
 *   <li>Early-exit in {@code runDocumentAction()} when the record id is absent (400).</li>
 *   <li>ETP-5611: {@code inpdocaction} set on the request for CO and RE; date mirror; schema
 *       currency on POST and DEFAULTS.</li>
 * </ul>
 */
public class GlJournalHeaderHandlerTest {

  private final GlJournalHeaderHandler handler = new GlJournalHeaderHandler();

  // ─── afterHandle() ───────────────────────────────────────────────────────────

  @Test
  public void afterHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.afterHandle(ctx));
  }

  // ─── handle(): non-ACTION, non-POST endpoints ─────────────────────────────

  @Test
  public void handleIgnoresCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresDefaultsEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .httpMethod("GET")
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── handle(): isCompleteAction paths ────────────────────────────────────

  @Test
  public void handleIgnoresActionWithWrongFieldName() {
    // ACTION endpoint but field is not "documentAction" → not a complete-action
    // Falls through to the POST guard → not POST → null.
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("someOtherField")
        .httpMethod("POST")
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresActionWithNullBody() {
    // ACTION + correct fieldName but body is null → isCompleteAction returns false.
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(null)
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresActionWhenDocActionIsNotCO() throws Exception {
    // ACTION + documentAction field, body says "PO" (post) not "CO" → not a complete-action.
    JSONObject body = new JSONObject();
    body.put("documentAction", "PO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── completeJournal(): missing record id → 400 ──────────────────────────

  @Test
  public void handleCompleteActionWithNullRecordIdReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId(null)
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleCompleteActionWithEmptyRecordIdReturns400() throws Exception {
    JSONObject body = new JSONObject();
    body.put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("")
        .build();
    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleCompleteActionFromFieldValuesNested() throws Exception {
    // Draft-mode sends documentAction under fieldValues, not at body root.
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "CO");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("")
        .build();
    NeoResponse response = handler.handle(ctx);
    // Detected as a complete action; no recordId → 400
    assertEquals(400, response.getHttpStatus());
  }

  // ─── handle(): POST short-circuits before DB ──────────────────────────────

  @Test
  public void handlePostSkipsInjectionWhenMultiGlIsY() throws Exception {
    // Multi-ledger journals do not require a C_AcctSchema_ID — handler must exit early.
    JSONObject body = new JSONObject();
    body.put("multigeneralLedger", "Y");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handlePostSkipsInjectionWhenAccountingSchemaAlreadyPresent() throws Exception {
    // Explicit caller-provided value must not be overwritten by the handler.
    JSONObject body = new JSONObject();
    body.put("accountingSchema", "SOME_ACCT_SCHEMA_ID");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── isCompleteAction(): fieldValues present with non-CO docAction ─────────

  @Test
  public void handleActionWithFieldValuesDocActionNotCO() throws Exception {
    // isCompleteAction() ternary true-branch: fieldValues present but docAction ≠ CO → false.
    // httpMethod=GET so handle() short-circuits before any DB call.
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "VD");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("GET")
        .requestBody(body)
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── completeJournal(): exception path → 500 ─────────────────────────────

  @Test
  public void handleCompleteActionExceptionReturns500() throws Exception {
    // Complete action with non-empty recordId; buildVariablesSecureApp throws →
    // completeJournal() catch block returns 500.
    JSONObject body = new JSONObject();
    body.put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("GL-JOURNAL-001")
        .build();
    RequestContext rc = requestContextWithRequest();
    try (MockedStatic<RequestContext> rcMock = mockStatic(RequestContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      rcMock.when(RequestContext::get).thenReturn(rc);
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("session unavailable"));
      NeoResponse response = handler.handle(ctx);
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ─── handle(): POST injection — session has no schema → warn path ─────────

  @Test
  public void handlePostInjectionAcctSchemaEmpty() throws Exception {
    // buildVariablesSecureApp returns a VSA with no $C_AcctSchema_ID in session →
    // handler logs warn and returns null without modifying the body.
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(vars);
      assertNull(handler.handle(ctx));
    }
  }

  // ─── handle(): POST injection — session has schema → inject into body ─────

  @Test
  public void handlePostInjectsAcctSchemaId() throws Exception {
    // buildVariablesSecureApp returns a VSA with $C_AcctSchema_ID set → handler
    // injects the value into the request body and returns null.
    JSONObject body = new JSONObject();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    vars.setSessionValue("$C_AcctSchema_ID", "ACCT-SCHEMA-001");
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(vars);
      assertNull(handler.handle(ctx));
      assertEquals("ACCT-SCHEMA-001", body.getString("accountingSchema"));
    }
  }

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    com.etendoerp.go.schemaforge.handlers.DocumentPostingService service =
        mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
    NeoContext ctx = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(ctx)).thenReturn(sentinel);

    GlJournalHeaderHandler h = new GlJournalHeaderHandler();
    h.setPostingService(service);

    assertSame(sentinel, h.handle(ctx));
  }

  // ─── ETP-5611: document action → inpdocaction request parameter ─────────

  private static RequestContext requestContextWithRequest() {
    RequestContext rc = mock(RequestContext.class);
    when(rc.getRequest()).thenReturn(mock(HttpServletRequest.class));
    return rc;
  }

  private static NeoContext docActionContext(String docAction) throws Exception {
    JSONObject body = new JSONObject();
    body.put("docAction", docAction);
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("GL-JOURNAL-001")
        .build();
  }

  /** Runs the action with the process stubbed to fail right after the parameter is set. */
  private NeoResponse runActionAndCaptureParam(String docAction, RequestContext rc) throws Exception {
    try (MockedStatic<RequestContext> rcMock = mockStatic(RequestContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      rcMock.when(RequestContext::get).thenReturn(rc);
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("stop before the process runs"));
      return handler.handle(docActionContext(docAction));
    }
  }

  @Test
  public void reactivateActionSetsInpdocactionRe() throws Exception {
    // FIN_AddPaymentFromJournal reads the action from the HTTP param `inpdocaction`, not the body;
    // without it the process silently completes instead of reactivating.
    RequestContext rc = requestContextWithRequest();
    NeoResponse response = runActionAndCaptureParam("RE", rc);
    assertNotNull(response);
    verify(rc).setRequestParameter("inpdocaction", "RE");
  }

  @Test
  public void completeActionSetsInpdocactionCoExplicitly() throws Exception {
    // The parameter outlives the operation inside a /batch request, so CO must not rely on its
    // absence: an earlier RE in the same request would otherwise turn this CO into a reactivate.
    RequestContext rc = requestContextWithRequest();
    runActionAndCaptureParam("CO", rc);
    verify(rc).setRequestParameter("inpdocaction", "CO");
  }

  @Test
  public void reactivateDetectedUnderFieldValues() throws Exception {
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "RE");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(body)
        .recordId("")
        .build();
    assertEquals(400, handler.handle(ctx).getHttpStatus());
  }

  @Test
  public void documentActionWithoutHttpRequestReturns500WithoutRunningTheProcess() throws Exception {
    RequestContext rc = mock(RequestContext.class);
    when(rc.getRequest()).thenReturn(null);
    try (MockedStatic<RequestContext> rcMock = mockStatic(RequestContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      rcMock.when(RequestContext::get).thenReturn(rc);
      NeoResponse response = handler.handle(docActionContext("RE"));
      assertEquals(500, response.getHttpStatus());
      neoMock.verify(() -> NeoDefaultsService.buildVariablesSecureApp(any()), never());
      verify(rc, never()).setRequestParameter(any(), any());
    }
  }

  // ─── ETP-5611: single "Fecha" mirrored into documentDate ────────────────

  private static NeoContext crudContext(String method, JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(method)
        .requestBody(body)
        .build();
  }

  @Test
  public void patchMirrorsAccountingDateIntoDocumentDate() throws Exception {
    JSONObject body = new JSONObject();
    body.put("accountingDate", "2026-09-30");
    assertNull(handler.handle(crudContext("PATCH", body)));
    assertEquals("2026-09-30", body.getString("documentDate"));
  }

  @Test
  public void postOverwritesAClientSentDocumentDate() throws Exception {
    // The UI sends documentDate=@#Date@ (today) on create; the chosen Fecha must win.
    JSONObject body = new JSONObject();
    body.put("multigeneralLedger", "Y"); // skip the schema/currency injection (needs a session)
    body.put("accountingDate", "2026-09-30");
    body.put("documentDate", "2026-10-05");
    assertNull(handler.handle(crudContext("POST", body)));
    assertEquals("2026-09-30", body.getString("documentDate"));
  }

  @Test
  public void patchWithoutAccountingDateLeavesDocumentDateAlone() throws Exception {
    JSONObject body = new JSONObject();
    body.put("description", "x");
    assertNull(handler.handle(crudContext("PATCH", body)));
    assertFalse(body.has("documentDate"));
  }

  // ─── ETP-5611: currency is always the accounting schema currency ────────

  private static VariablesSecureApp sessionWithSchema(String schemaId) {
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    if (schemaId != null) {
      vars.setSessionValue("$C_AcctSchema_ID", schemaId);
    }
    return vars;
  }

  @Test
  public void postForcesSchemaCurrencyOverAClientValue() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    doReturn(new String[] { "EUR-ID", "EUR" }).when(h).resolveSchemaCurrency("SCHEMA-1");
    JSONObject body = new JSONObject();
    body.put("currency", "USD-ID");
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(sessionWithSchema("SCHEMA-1"));
      assertNull(h.handle(crudContext("POST", body)));
    }
    assertEquals("SCHEMA-1", body.getString("accountingSchema"));
    assertEquals("EUR-ID", body.getString("currency"));
  }

  @Test
  public void postUsesTheCurrencyOfAnExplicitAccountingSchema() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    doReturn(new String[] { "USD-ID", "USD" }).when(h).resolveSchemaCurrency("SCHEMA-US");
    JSONObject body = new JSONObject();
    body.put("accountingSchema", "SCHEMA-US");
    assertNull(h.handle(crudContext("POST", body)));
    assertEquals("SCHEMA-US", body.getString("accountingSchema"));
    assertEquals("USD-ID", body.getString("currency"));
  }

  @Test
  public void multiLedgerPostKeepsTheClientCurrency() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    JSONObject body = new JSONObject();
    body.put("multigeneralLedger", "Y");
    body.put("currency", "USD-ID");
    assertNull(h.handle(crudContext("POST", body)));
    assertEquals("USD-ID", body.getString("currency"));
    verify(h, never()).resolveSchemaCurrency(any());
  }

  private static NeoContext defaultsContext(JSONObject defaults) throws Exception {
    JSONObject responseBody = new JSONObject();
    responseBody.put("defaults", defaults);
    return NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(responseBody))
        .build();
  }

  @Test
  public void defaultsShowTheSchemaCurrencyNotTheOrgCurrency() throws Exception {
    // @C_Currency_ID@ resolves to the org currency first (LoginUtils); the form must show the
    // schema currency the POST will persist.
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    doReturn(new String[] { "EUR-ID", "EUR" }).when(h).resolveSchemaCurrency("SCHEMA-1");
    JSONObject defaults = new JSONObject();
    defaults.put("currency", "ORG-CUR");
    defaults.put("currency$_identifier", "ARS");
    NeoResponse response;
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(sessionWithSchema("SCHEMA-1"));
      response = h.afterHandle(defaultsContext(defaults));
    }
    assertNotNull(response);
    JSONObject out = response.getBody().getJSONObject("defaults");
    assertEquals("EUR-ID", out.getString("currency"));
    assertEquals("EUR", out.getString("currency$_identifier"));
  }

  @Test
  public void defaultsUntouchedWhenTheSessionHasNoSchema() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    JSONObject defaults = new JSONObject();
    defaults.put("currency", "ORG-CUR");
    try (MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(sessionWithSchema(null));
      assertNull(h.afterHandle(defaultsContext(defaults)));
    }
    assertEquals("ORG-CUR", defaults.getString("currency"));
  }
}
