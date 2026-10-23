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
import static org.junit.Assert.assertTrue;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.gl.GLJournal;

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
 *   <li>ETP-5793: another tenant's journal (PATCH date mirror) and accounting schema (currency) are
 *       treated as unknown.</li>
 * </ul>
 *
 * @covers com.etendoerp.go.schemaforge.GlJournalHeaderHandler
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

  // ETP-5669: the process defaults a missing inpdocaction to CO, so the document-action button
  // must never fall through to the generic executor with no (or another) docAction.

  @Test
  public void documentActionWithNullBodyIsRefused() {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .httpMethod("POST")
        .requestBody(null)
        .build();
    assertEquals(422, handler.handle(ctx).getHttpStatus());
  }

  @Test
  public void documentActionWithBlankDocActionIsRefused() throws Exception {
    JSONObject body = new JSONObject();
    body.put("docAction", "");
    assertEquals(422, handler.handle(actionContext("documentAction", body)).getHttpStatus());
  }

  @Test
  public void documentActionWithAnotherDocActionIsRefused() throws Exception {
    JSONObject body = new JSONObject();
    body.put("documentAction", "VO");
    NeoResponse response = handler.handle(actionContext("documentAction", body));
    assertEquals(422, response.getHttpStatus());
    assertTrue(response.getBody().toString().contains("docAction 'VO' is not allowed"));
  }

  @Test
  public void dbColumnSpellingIsRefusedWithoutDocAction() {
    // REST /action/DocAction with no docAction used to reach the generic executor.
    assertEquals(422, handler.handle(actionContext("DocAction", new JSONObject())).getHttpStatus());
  }

  @Test
  public void dbColumnSpellingIsHandledLikeTheFieldName() throws Exception {
    RequestContext rc = requestContextWithRequest();
    JSONObject body = new JSONObject();
    body.put("docAction", "RE");
    try (MockedStatic<RequestContext> rcMock = mockStatic(RequestContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      rcMock.when(RequestContext::get).thenReturn(rc);
      neoMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("stop before the process runs"));
      NeoContext ctx = NeoContext.builder()
          .endpointType(NeoEndpointType.ACTION)
          .fieldName("DocAction")
          .httpMethod("POST")
          .requestBody(body)
          .recordId("GL-JOURNAL-001")
          .build();
      handler.handle(ctx);
    }
    verify(rc).setRequestParameter("inpdocaction", "RE");
  }

  // ETP-5669: the MCP action surface — documentAction (CO/RE only), post, unpost.

  @Test
  public void declaresDocumentActionPostAndUnpost() {
    assertEquals(java.util.List.of("documentAction", "post", "unpost"),
        new java.util.ArrayList<>(handler.actionContracts().keySet()));
    assertTrue(handler.actionContracts().get("post").getDescription().contains("Completing does not post"));
  }

  @Test
  public void documentActionContractAcceptsOnlyCoAndRe() throws Exception {
    java.util.Map<String, com.etendoerp.go.schemaforge.util.NeoActionContract> contracts =
        handler.actionContracts();
    assertNull(com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
        "documentAction", new JSONObject().put("docAction", "RE")));
    assertEquals(422, com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
        "documentAction", new JSONObject().put("docAction", "VO")).getHttpStatus());
    assertEquals(422, com.etendoerp.go.schemaforge.util.NeoActionContract.validate(contracts,
        "documentAction", new JSONObject()).getHttpStatus());
  }

  // ETP-5669: CO refuses a journal whose stored period does not contain its accounting date.

  private static org.openbravo.model.financialmgmt.gl.GLJournal journalIn(String periodName,
      String start, String end, String dateAcct) throws Exception {
    java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd");
    org.openbravo.model.financialmgmt.calendar.Period period =
        mock(org.openbravo.model.financialmgmt.calendar.Period.class);
    when(period.getName()).thenReturn(periodName);
    when(period.getStartingDate()).thenReturn(iso.parse(start));
    when(period.getEndingDate()).thenReturn(iso.parse(end));
    org.openbravo.model.financialmgmt.gl.GLJournal journal =
        mock(org.openbravo.model.financialmgmt.gl.GLJournal.class);
    when(journal.getPeriod()).thenReturn(period);
    when(journal.getAccountingDate()).thenReturn(iso.parse(dateAcct));
    return journal;
  }

  @Test
  public void periodMismatchNamesThePeriodAndTheDateUnderItsOwnKey() throws Exception {
    NeoResponse response = GlJournalHeaderHandler.periodMismatch(
        journalIn("Apr-26", "2026-04-01", "2026-04-30", "2026-06-20"));
    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertTrue(body.getString("message").contains("Apr-26"));
    assertTrue(body.getString("message").contains("2026-06-20"));
    assertEquals("[\"ETGO_GLJournalPeriodMismatch\"]", body.getJSONArray("messageKeys").toString());
    assertEquals("Apr-26", body.getJSONObject("messageParams").getString("period"));
    // Not re-tagged as a "period not open" refusal (that one sends the user to the calendar).
    NeoResponse.identifyPeriodRefusal(response);
    assertEquals("[\"ETGO_GLJournalPeriodMismatch\"]", body.getJSONArray("messageKeys").toString());
  }

  @Test
  public void periodMismatchIsNullWhenThePeriodContainsTheDate() throws Exception {
    assertNull(GlJournalHeaderHandler.periodMismatch(
        journalIn("Jun-26", "2026-06-01", "2026-06-30", "2026-06-30")));
    assertNull(GlJournalHeaderHandler.periodMismatch(null));
  }

  @Test
  public void completeWithAMismatchedPeriodNeverRunsTheProcess() throws Exception {
    GlJournalHeaderHandler spyHandler = spy(new GlJournalHeaderHandler());
    doReturn(journalIn("Apr-26", "2026-04-01", "2026-04-30", "2026-06-20"))
        .when(spyHandler).loadJournal("GL-JOURNAL-001");
    RequestContext rc = requestContextWithRequest();
    try (MockedStatic<RequestContext> rcMock = mockStatic(RequestContext.class);
        MockedStatic<NeoDefaultsService> neoMock = mockStatic(NeoDefaultsService.class)) {
      rcMock.when(RequestContext::get).thenReturn(rc);
      assertEquals(422, spyHandler.handle(docActionContext("CO")).getHttpStatus());
      neoMock.verify(() -> NeoDefaultsService.buildVariablesSecureApp(any()), never());
    }
  }

  private static NeoContext actionContext(String fieldName, JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(fieldName)
        .httpMethod("POST")
        .requestBody(body)
        .recordId("GL-JOURNAL-001")
        .build();
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

  // ─── resolveDocAction(): fieldValues present with non-CO/RE docAction ──────

  @Test
  public void handleActionWithFieldValuesDocActionNotCoOrReIsRefused() throws Exception {
    JSONObject fieldValues = new JSONObject();
    fieldValues.put("documentAction", "VD");
    JSONObject body = new JSONObject();
    body.put("fieldValues", fieldValues);
    assertEquals(422, handler.handle(actionContext("documentAction", body)).getHttpStatus());
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
    // The journal process reads the action from the inpdocaction request parameter, not from the
    // body. Without it the process silently completes the journal instead of reactivating it.
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

  // PATCH/PUT: NEO's filterWriteRequest drops the hidden (system) documentDate from the body after
  // this pre-hook, so the handler writes it on the record itself, in the same transaction.

  private static NeoContext patchContext(String recordId, JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  private static org.openbravo.model.financialmgmt.gl.GLJournal journal(boolean processed) {
    org.openbravo.model.financialmgmt.gl.GLJournal j =
        mock(org.openbravo.model.financialmgmt.gl.GLJournal.class);
    when(j.isProcessed()).thenReturn(processed);
    return j;
  }

  @Test
  public void patchWritesDocumentDateOnTheRecord() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    org.openbravo.model.financialmgmt.gl.GLJournal j = journal(false);
    doReturn(j).when(h).loadJournal("GL-1");
    JSONObject body = new JSONObject();
    body.put("accountingDate", "2026-09-30");
    assertNull(h.handle(patchContext("GL-1", body)));
    verify(j).setDocumentDate(new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-09-30"));
  }

  @Test
  public void patchOnAProcessedJournalLeavesTheRecordAlone() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    org.openbravo.model.financialmgmt.gl.GLJournal j = journal(true);
    doReturn(j).when(h).loadJournal("GL-1");
    JSONObject body = new JSONObject();
    body.put("accountingDate", "2026-09-30");
    h.handle(patchContext("GL-1", body));
    verify(j, never()).setDocumentDate(any());
  }

  // ETP-5793: the real loadJournal / resolveSchemaCurrency, not the seams, decide the tenant.

  private static Client client(String id) {
    Client c = mock(Client.class);
    when(c.getId()).thenReturn(id);
    return c;
  }

  /** An OBContext whose role reads client {@code OWN} only (plus the system client). */
  private static OBContext ownTenantContext() {
    OBContext ctx = mock(OBContext.class);
    when(ctx.getReadableClients()).thenReturn(new String[] { "OWN", "0" });
    when(ctx.getReadableOrganizations()).thenReturn(new String[] { "0" });
    return ctx;
  }

  private static GLJournal draftJournalOf(String clientId) {
    GLJournal j = journal(false);
    Client c = client(clientId);
    when(j.getClient()).thenReturn(c);
    return j;
  }

  @Test
  public void patchNeverTouchesAnotherTenantsJournal() throws Exception {
    GLJournal foreign = draftJournalOf("OTHER");
    OBDal dal = mock(OBDal.class);
    when(dal.get(GLJournal.class, "GL-F")).thenReturn(foreign);
    OBContext ctx = ownTenantContext();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      JSONObject body = new JSONObject();
      body.put("accountingDate", "2026-09-30");
      assertNull(new GlJournalHeaderHandler().handle(patchContext("GL-F", body)));
    }
    verify(foreign, never()).setDocumentDate(any());
  }

  @Test
  public void patchStillWritesTheOwnTenantsJournal() throws Exception {
    GLJournal own = draftJournalOf("OWN");
    OBDal dal = mock(OBDal.class);
    when(dal.get(GLJournal.class, "GL-1")).thenReturn(own);
    OBContext ctx = ownTenantContext();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      JSONObject body = new JSONObject();
      body.put("accountingDate", "2026-09-30");
      new GlJournalHeaderHandler().handle(patchContext("GL-1", body));
    }
    verify(own).setDocumentDate(new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-09-30"));
  }

  @Test
  public void anotherTenantsSchemaYieldsNoCurrency() {
    Currency eur = mock(Currency.class);
    when(eur.getId()).thenReturn("EUR-ID");
    when(eur.getISOCode()).thenReturn("EUR");
    AcctSchema own = mock(AcctSchema.class);
    Client ownClient = client("OWN");
    when(own.getClient()).thenReturn(ownClient);
    when(own.getCurrency()).thenReturn(eur);
    AcctSchema foreign = mock(AcctSchema.class);
    Client otherClient = client("OTHER");
    when(foreign.getClient()).thenReturn(otherClient);
    when(foreign.getCurrency()).thenReturn(eur);
    OBDal dal = mock(OBDal.class);
    when(dal.get(AcctSchema.class, "SCHEMA-OWN")).thenReturn(own);
    when(dal.get(AcctSchema.class, "SCHEMA-FOREIGN")).thenReturn(foreign);
    OBContext ctx = ownTenantContext();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      GlJournalHeaderHandler h = new GlJournalHeaderHandler();
      assertEquals(0, h.resolveSchemaCurrency("SCHEMA-FOREIGN").length);
      assertEquals("EUR-ID", h.resolveSchemaCurrency("SCHEMA-OWN")[0]);
    }
  }

  @Test
  public void patchWithAnUnparseableDateLeavesTheRecordAlone() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    JSONObject body = new JSONObject();
    body.put("accountingDate", "not-a-date");
    h.handle(patchContext("GL-1", body));
    verify(h, never()).loadJournal(any());
  }

  @Test
  public void patchWithoutAccountingDateNeverLoadsTheRecord() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    JSONObject body = new JSONObject();
    body.put("description", "x");
    h.handle(patchContext("GL-1", body));
    verify(h, never()).loadJournal(any());
  }

  @Test
  public void postMirrorsInTheBodyOnlyAndNeverLoadsARecord() throws Exception {
    GlJournalHeaderHandler h = spy(new GlJournalHeaderHandler());
    JSONObject body = new JSONObject();
    body.put("multigeneralLedger", "Y");
    body.put("accountingDate", "2026-09-30");
    h.handle(crudContext("POST", body));
    assertEquals("2026-09-30", body.getString("documentDate"));
    verify(h, never()).loadJournal(any());
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
