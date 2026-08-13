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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.enterprise.inject.Vetoed;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;

/**
 * Unit tests for {@link PeriodOpenCloseSupport} (parse + translateResult)
 * and the template method in {@link AbstractPeriodOpenCloseHandler}.
 */
public class PeriodOpenCloseSupportTest {

  private static final String SPEC = "open-close-period-control";
  private static final String ENTITY = "periodControl";
  private static final String FIELD = "openClose";

  // ── parse() — dispatch ────────────────────────────────────────────────────

  /**
   * CRUD endpoints must produce a SKIP result (isAbort=true, toHandlerReturn=null)
   * so the default pipeline can handle them undisturbed.
   */
  @Test
  public void testParseNonActionEndpointSkips() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .fieldName(FIELD).recordId("r-1").build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertTrue(req.isAbort());
    assertNull(req.toHandlerReturn());
  }

  /**
   * ACTION with a fieldName other than {@code openClose} must also produce
   * SKIP so other handlers in the chain can claim the action.
   */
  @Test
  public void testParseWrongFieldNameSkips() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("someOtherAction").recordId("r-1").build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertTrue(req.isAbort());
    assertNull(req.toHandlerReturn());
  }

  /**
   * A null request body (POST with no payload) must abort with HTTP 400.
   * The openClose ACTION always requires a body containing fieldValues.
   */
  @Test
  public void testParseNullBodyAbortsWith400() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertTrue(req.isAbort());
    assertNotNull(req.toHandlerReturn());
    assertEquals(400, req.toHandlerReturn().getHttpStatus());
  }

  /**
   * fieldValues present but openClose key absent must abort with 400.
   */
  @Test
  public void testParseMissingOpenCloseValueAbortsWith400() throws Exception {
    JSONObject body = new JSONObject().put("fieldValues", new JSONObject());
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").requestBody(body).build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertTrue(req.isAbort());
    assertEquals(400, req.toHandlerReturn().getHttpStatus());
  }

  /**
   * openClose value that is blank (whitespace-only) must abort with 400.
   */
  @Test
  public void testParseBlankOpenCloseValueAbortsWith400() throws Exception {
    JSONObject fieldValues = new JSONObject().put("openClose", "   ");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").requestBody(body).build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertTrue(req.isAbort());
    assertEquals(400, req.toHandlerReturn().getHttpStatus());
  }

  /**
   * Valid body but missing recordId must abort with 400. We cannot locate
   * the period/document-control record without an id.
   */
  @Test
  public void testParseMissingRecordIdAbortsWith400() throws Exception {
    JSONObject fieldValues = new JSONObject().put("openClose", "O");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).requestBody(body).build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertTrue(req.isAbort());
    assertEquals(400, req.toHandlerReturn().getHttpStatus());
  }

  /**
   * Happy path: valid ACTION, correct fieldName, non-blank openClose value,
   * and a recordId. The request must NOT abort and both fields must be set.
   */
  @Test
  public void testParseHappyPath() throws Exception {
    JSONObject fieldValues = new JSONObject().put("openClose", "O");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("period-1").requestBody(body).build();
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(ctx);
    assertFalse(req.isAbort());
    assertEquals("O", req.openCloseValue);
    assertEquals("period-1", req.recordId);
  }

  // ── translateResult() ─────────────────────────────────────────────────────

  /**
   * ProcessInstance result code 0 means failure. Must return 400 with
   * status="error" and "Process failed" when errorMsg is null.
   */
  @Test
  public void testTranslateResultZeroReturns400WithDefaultMessage() throws Exception {
    ProcessInstance pi = mock(ProcessInstance.class);
    when(pi.getResult()).thenReturn(0L);
    when(pi.getErrorMsg()).thenReturn(null);
    Process proc = mock(Process.class);

    NeoResponse r = PeriodOpenCloseSupport.translateResult(pi, proc);
    assertEquals(400, r.getHttpStatus());
    assertEquals("error", r.getBody().getString("status"));
    assertEquals("Process failed", r.getBody().getString("message"));
  }

  /**
   * Error message prefixed with {@code @ERROR=} must have the prefix stripped
   * before being returned to the caller.
   */
  @Test
  public void testTranslateResultZeroStripsAtErrorPrefix() throws Exception {
    ProcessInstance pi = mock(ProcessInstance.class);
    when(pi.getResult()).thenReturn(0L);
    when(pi.getErrorMsg()).thenReturn("@ERROR=Period already closed");
    Process proc = mock(Process.class);

    NeoResponse r = PeriodOpenCloseSupport.translateResult(pi, proc);
    assertEquals(400, r.getHttpStatus());
    assertEquals("Period already closed", r.getBody().getString("message"));
  }

  /**
   * Null result code must be treated as 0 (failure), matching the
   * {@code pInstance.getResult() != null ? ... : 0L} guard in the source.
   */
  @Test
  public void testTranslateResultNullResultCodeTreatedAsFailure() throws Exception {
    ProcessInstance pi = mock(ProcessInstance.class);
    when(pi.getResult()).thenReturn(null);
    when(pi.getErrorMsg()).thenReturn(null);
    Process proc = mock(Process.class);

    NeoResponse r = PeriodOpenCloseSupport.translateResult(pi, proc);
    assertEquals(400, r.getHttpStatus());
    assertEquals("error", r.getBody().getString("status"));
  }

  /**
   * Non-zero result code means success. Must return 200 with status="success"
   * and a message that includes the process name when errorMsg is null.
   */
  @Test
  public void testTranslateResultSuccessReturns200WithProcessName() throws Exception {
    ProcessInstance pi = mock(ProcessInstance.class);
    when(pi.getResult()).thenReturn(1L);
    when(pi.getErrorMsg()).thenReturn(null);
    Process proc = mock(Process.class);
    when(proc.getName()).thenReturn("C_Period_Process");

    NeoResponse r = PeriodOpenCloseSupport.translateResult(pi, proc);
    assertEquals(200, r.getHttpStatus());
    assertEquals("success", r.getBody().getString("status"));
    assertTrue(r.getBody().getString("message").contains("C_Period_Process"));
  }

  /**
   * Success errorMsg prefixed with {@code @SUCCESS=} must have the prefix
   * stripped and be returned as the message body.
   */
  @Test
  public void testTranslateResultSuccessStripsAtSuccessPrefix() throws Exception {
    ProcessInstance pi = mock(ProcessInstance.class);
    when(pi.getResult()).thenReturn(1L);
    when(pi.getErrorMsg()).thenReturn("@SUCCESS=Period opened");
    Process proc = mock(Process.class);

    NeoResponse r = PeriodOpenCloseSupport.translateResult(pi, proc);
    assertEquals(200, r.getHttpStatus());
    assertEquals("Period opened", r.getBody().getString("message"));
  }

  // ── AbstractPeriodOpenCloseHandler via test double ────────────────────────

  /** Minimal concrete subclass used to test the abstract template method. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestHandler extends AbstractPeriodOpenCloseHandler {
    NeoResponse doHandleResult;
    NeoResponse onErrorResult;
    boolean doHandleCalled;
    boolean shouldThrow;

    @Override
    protected NeoResponse doHandle(String openCloseValue, String recordId) {
      doHandleCalled = true;
      if (shouldThrow) {
        throw new RuntimeException("forced error");
      }
      return doHandleResult;
    }

    @Override
    protected NeoResponse onError(String recordId, Exception e) {
      return onErrorResult;
    }
  }

  /**
   * Non-ACTION context must fall through without calling doHandle.
   */
  @Test
  public void testHandleNonActionSkipsWithoutCallingDoHandle() {
    TestHandler h = new TestHandler();
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .fieldName(FIELD).recordId("r-1").build();
    assertNull(h.handle(ctx));
    assertFalse(h.doHandleCalled);
  }

  /**
   * Wrong fieldName must skip without calling doHandle.
   */
  @Test
  public void testHandleWrongFieldSkips() {
    TestHandler h = new TestHandler();
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("other").recordId("r-1").build();
    assertNull(h.handle(ctx));
    assertFalse(h.doHandleCalled);
  }

  /**
   * A missing body must abort with 400 before reaching doHandle — the
   * abstract class delegates validation to {@link PeriodOpenCloseSupport#parse}.
   */
  @Test
  public void testHandleInvalidBodyReturns400WithoutCallingDoHandle() {
    TestHandler h = new TestHandler();
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").build(); // no body
    NeoResponse r = h.handle(ctx);
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    assertFalse(h.doHandleCalled);
  }

  /**
   * Valid request must call doHandle under OBContext admin mode and return
   * whatever doHandle returns. Verifies setAdminMode and restorePreviousMode
   * are both called exactly once.
   */
  @Test
  public void testHandleCallsDoHandleUnderAdminMode() throws Exception {
    TestHandler h = new TestHandler();
    h.doHandleResult = NeoResponse.ok(new JSONObject().put("status", "ok"));

    JSONObject fieldValues = new JSONObject().put("openClose", "O");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").requestBody(body).build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      NeoResponse r = h.handle(ctx);
      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      assertTrue(h.doHandleCalled);
      ctxMock.verify(OBContext::setAdminMode);
      ctxMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * When doHandle throws, the exception must be caught and routed to
   * {@code onError}, and restorePreviousMode must still be called.
   */
  @Test
  public void testHandleExceptionRoutesToOnError() throws Exception {
    TestHandler h = new TestHandler();
    h.shouldThrow = true;
    h.onErrorResult = NeoResponse.error(500, "handler error");

    JSONObject fieldValues = new JSONObject().put("openClose", "O");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").requestBody(body).build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::setAdminMode).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      NeoResponse r = h.handle(ctx);
      assertNotNull(r);
      assertEquals(500, r.getHttpStatus());
      ctxMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * afterHandle must always return null — it is a no-op in the base class
   * and subclasses can override it, but the default must not intercept anything.
   */
  @Test
  public void testAfterHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName(SPEC).entityName(ENTITY)
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(FIELD).recordId("r-1").build();
    assertNull(new TestHandler().afterHandle(ctx));
  }
}
