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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;

import static com.etendoerp.go.schemaforge.AbstractFiscalHandler.MODIFIED;
import static com.etendoerp.go.schemaforge.AbstractFiscalHandler.PERIOD_KEY;
import static com.etendoerp.go.schemaforge.AbstractFiscalHandler.SINCE_KEY;

import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * Unit tests for routing logic in {@link AbstractFiscalHandler}.
 *
 * Uses a minimal stub subclass so the base-class routing (declarations path,
 * dispatch exception handling, allowsPost default) can be exercised without
 * a real database connection.
 */
public class AbstractFiscalHandlerTest {

  // ── Stub subclass ─────────────────────────────────────────────────

  private static class StubHandler extends AbstractFiscalHandler {

    private final boolean throwFiscalEx;
    private final RuntimeException fiscalCause;

    StubHandler(NeoServlet servlet, boolean throwFiscalEx) {
      this(servlet, throwFiscalEx, new RuntimeException("fiscal error"));
    }

    StubHandler(NeoServlet servlet, boolean throwFiscalEx, RuntimeException fiscalCause) {
      super(servlet);
      this.throwFiscalEx = throwFiscalEx;
      this.fiscalCause   = fiscalCause;
    }

    @Override
    protected boolean isKnownEntity(String entityName) {
      return "known".equals(entityName) || MODIFIED.equals(entityName);
    }

    @Override
    protected void dispatch(String entityName, String orgId, int year, String period,
        HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
      if (throwFiscalEx) {
        throw new FiscalHandlerException(fiscalCause);
      }
      throw new RuntimeException("unexpected dispatch error");
    }

    @Override
    protected String getModelKey() {
      return "stub";
    }
  }

  private static final String AD_MESSAGE_KEY = "@AEAT349_Phone_Contact_Mandatory@";
  private static final String TRANSLATED_MESSAGE =
      "Debe indicar la persona de contacto y el teléfono de la declaración.";

  private NeoServlet servlet;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
  }

  // ── declarations entity ───────────────────────────────────────────

  /**
   * Requests to the "declarations" entity must be delegated to
   * {@link FiscalDeclCrudHandler}. When the inner handler fails,
   * the base class must catch the exception and respond with 500.
   */
  @Test
  public void testDeclarationsPathCatchesException() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));

    StubHandler handler = new StubHandler(servlet, false);
    // "declarations" is handled before isKnownEntity and delegates to
    // FiscalDeclCrudHandler, whose first action reads OBContext. Force that to
    // fail deterministically so the base class catch → sendError(500) path is
    // exercised regardless of test ordering or ambient static state.
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::getOBContext).thenThrow(new OBException("no context"));
      handler.handle("declarations", "GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  // ── incidents entity (ETP-4456) ───────────────────────────────────

  /**
   * Requests to the "incidents" entity must be delegated to
   * {@link FiscalDeclCrudHandler#handleIncidents}, bypassing {@code isKnownEntity} and the
   * year/period requirement entirely — same routing shape as "declarations". Deliberately no
   * "year"/"period" stubbed on the request, proving this entity never hits that gate (if it did,
   * {@code handleIncidents} would never even run and this would surface as a 400 instead). The
   * {@code id} param IS supplied so {@code handleIncidents} gets past its own param check and
   * reaches {@code OBContext}, which is forced to throw so the base class's
   * {@code catch (Exception e)} → 500 path is exercised deterministically.
   */
  @Test
  public void testIncidentsPathCatchesException() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("id")).thenReturn("decl1");

    StubHandler handler = new StubHandler(servlet, false);
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      ctxMock.when(OBContext::getOBContext).thenThrow(new OBException("no context"));
      handler.handle("incidents", "GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  // ── allowsPost default ────────────────────────────────────────────

  /**
   * The default {@code allowsPost} implementation returns {@code false},
   * so POST to a known entity must be rejected with 405.
   */
  @Test
  public void testAllowsPostDefaultReturnsFalse() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    StubHandler handler = new StubHandler(servlet, false);
    handler.handle("known", "POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        anyString());
  }

  /**
   * When OBContext is unavailable (typical in unit tests) the
   * {@code catch (Exception e)} block in {@code handle()} must intercept the
   * NullPointerException and return 500 rather than letting it propagate.
   */
  @Test
  public void testDispatchExceptionYields500() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    StubHandler handler = new StubHandler(servlet, false);
    handler.handle("known", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  /**
   * When {@code dispatch()} throws a {@link FiscalHandlerException} the specific
   * catch block must handle it and return 500 with the exception message.
   */
  @Test
  public void testFiscalHandlerExceptionYields500() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    StubHandler handler = new StubHandler(servlet, true);
    handler.handle("known", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  // ── error message translation (ETP-5027) ──────────────────────────

  /**
   * Regression guard for ETP-5027. Etendo tax-report logic raises errors carrying a raw
   * AD_Message key (here {@code @AEAT349_Phone_Contact_Mandatory@}, thrown by
   * {@code AEAT3492010Report}); {@code dispatch} wraps it in a {@link FiscalHandlerException}.
   * The message that reaches the browser must be the TRANSLATED text — previously the raw key
   * was forwarded verbatim and users saw the literal code. It must also be free of the
   * {@code FiscalHandlerException} wrapper's class-name prefix, which
   * {@code RuntimeException(Throwable)} bakes into {@code getMessage()}.
   */
  @Test
  public void testFiscalHandlerExceptionMessageIsTranslated() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    StubHandler handler = new StubHandler(servlet, true, new OBException(AD_MESSAGE_KEY));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      OBContext      ctx = mock(OBContext.class);
      Organization   org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG1");
      when(ctx.getCurrentOrganization()).thenReturn(org);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY))
          .thenReturn(TRANSLATED_MESSAGE);

      handler.handle("known", "GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        eq(TRANSLATED_MESSAGE));
  }

  /**
   * Without a live OBContext (the situation in every mocked unit test) translation cannot run.
   * The handler must still produce the underlying message rather than propagate — and it must
   * be the CAUSE's message, i.e. the bare key with no {@code OBException: } class-name prefix
   * from the {@link FiscalHandlerException} wrapper.
   */
  @Test
  public void testUserMessageFallsBackToUnwrappedRawText() {
    StubHandler handler = new StubHandler(servlet, false);
    FiscalHandlerException ex = new FiscalHandlerException(new OBException(AD_MESSAGE_KEY));

    String message;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY))
          .thenThrow(new NullPointerException("no OBContext"));
      message = handler.userMessage(ex);
    }

    assertEquals(AD_MESSAGE_KEY, message);
  }

  /**
   * Only our own wrapper is unwrapped — any other exception keeps its own message, which is
   * still run through translation so embedded tokens are resolved.
   */
  @Test
  public void testUserMessageKeepsMessageOfNonWrapperException() {
    StubHandler handler = new StubHandler(servlet, false);

    String message;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      msgMock.when(() -> OBMessageUtils.parseTranslation("plain failure"))
          .thenReturn("plain failure");
      message = handler.userMessage(new OBException("plain failure"));
    }

    assertEquals("plain failure", message);
  }

  /**
   * A wrapper around a message-less cause must not degrade into a blank error: the wrapper's
   * own (class-name-derived) message is kept as the last resort.
   */
  @Test
  public void testUserMessageFallsBackWhenCauseHasNoMessage() {
    StubHandler handler = new StubHandler(servlet, false);
    FiscalHandlerException ex = new FiscalHandlerException(new RuntimeException());

    String message;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(anyString()))
          .thenAnswer(inv -> inv.getArgument(0));
      message = handler.userMessage(ex);
    }

    assertEquals(ex.getMessage(), message);
    assertTrue(message != null && !message.isEmpty());
  }

  /**
   * ETP-5027 (QA F6): an exception with NO message at all — a bare
   * {@code NullPointerException} is the everyday case — used to travel as null all the way to
   * {@code NeoResponse.error}, where jettison drops the null key and the browser receives
   * {@code {"error":{"status":500}}} with nothing to display. {@code userMessage} is the single
   * funnel for fiscal error text, so it must never return null or blank.
   */
  @Test
  public void testUserMessageNeverReturnsNullForAMessagelessException() {
    StubHandler handler = new StubHandler(servlet, false);

    String message;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(anyString()))
          .thenAnswer(inv -> inv.getArgument(0));
      message = handler.userMessage(new NullPointerException());
    }

    assertEquals(AbstractFiscalHandler.GENERIC_ERROR_MESSAGE, message);
  }

  /** A blank (whitespace-only) message is as useless as a null one and gets the same floor. */
  @Test
  public void testUserMessageReplacesABlankMessage() {
    StubHandler handler = new StubHandler(servlet, false);

    String message;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(anyString()))
          .thenAnswer(inv -> inv.getArgument(0));
      message = handler.userMessage(new OBException("   "));
    }

    assertEquals(AbstractFiscalHandler.GENERIC_ERROR_MESSAGE, message);
  }

  /** A translation that resolves to blank must not be forwarded either. */
  @Test
  public void testUserMessageReplacesABlankTranslation() {
    StubHandler handler = new StubHandler(servlet, false);

    String message;
    try (MockedStatic<OBMessageUtils> msgMock = mockStatic(OBMessageUtils.class)) {
      msgMock.when(() -> OBMessageUtils.parseTranslation(AD_MESSAGE_KEY)).thenReturn("");
      message = handler.userMessage(new OBException(AD_MESSAGE_KEY));
    }

    assertEquals(AbstractFiscalHandler.GENERIC_ERROR_MESSAGE, message);
  }

  // ── writeGeneratedFile ────────────────────────────────────────────

  @Test
  public void testWriteGeneratedFileThrowsWhenNoFileKey() {
    StubHandler handler = new StubHandler(servlet, false);
    try {
      handler.writeGeneratedFile(new HashMap<>(), "output.349", mock(HttpServletResponse.class));
      fail("Expected OBException for missing file content");
    } catch (OBException e) {
      // expected
    } catch (Exception e) {
      fail("Expected OBException, got: " + e);
    }
  }

  @Test
  public void testWriteGeneratedFileWritesBytes() throws Exception {
    StubHandler handler = new StubHandler(servlet, false);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ServletOutputStream sos = new ServletOutputStream() {
      @Override public void write(int b) { baos.write(b); }
      @Override public boolean isReady() { return true; }
      @Override public void setWriteListener(WriteListener wl) { throw new UnsupportedOperationException(); }
    };
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getOutputStream()).thenReturn(sos);

    HashMap<String, Object> result = new HashMap<>();
    result.put("file", "CONTENT");
    handler.writeGeneratedFile(result, "out.349", resp);

    assertArrayEquals("CONTENT".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
        baos.toByteArray());
    verify(resp).setContentType("text/plain");
    verify(resp).setCharacterEncoding("ISO-8859-1");
  }

  // ── handleModified ────────────────────────────────────────────────

  @Test
  public void testHandleModifiedEmptyPeriodsWritesFalse() throws Exception {
    StubHandler handler = new StubHandler(servlet, false);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      handler.handleModified("org1", 2026, "T1", new Date(0), resp);
    }

    verify(resp).setContentType(anyString());
  }

  @Test
  public void testHandleModifiedNullDatesWritesFalse() throws Exception {
    StubHandler handler = new StubHandler(servlet, false);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    Period period = mock(Period.class);
    when(period.getStartingDate()).thenReturn(null);
    when(period.getEndingDate()).thenReturn(null);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      @SuppressWarnings("unchecked")
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.list()).thenReturn(Collections.singletonList(period));

      handler.handleModified("org1", 2026, "T1", new Date(0), resp);
    }

    verify(resp).setContentType(anyString());
  }

  // ── resolvePeriods ────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void testResolvePeriodsQuarterlyMapsCorrectMonths() {
    StubHandler handler = new StubHandler(servlet, false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      List<Period> expected = Collections.emptyList();
      when(q.list()).thenReturn(expected);

      List<Period> result = handler.resolvePeriods("org1", 2026, "T2");
      assertEquals(expected, result);
      // Verify :from and :to were bound (quarterly T2 → months 4–6)
      verify(q).setParameter(eq("from"), eq(4L));
      verify(q).setParameter(eq("to"),   eq(6L));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolvePeriodsMonthlyBindsSameMonth() {
    StubHandler handler = new StubHandler(servlet, false);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      when(q.list()).thenReturn(Collections.emptyList());

      handler.resolvePeriods("org1", 2026, "03");
      verify(q).setParameter(eq("from"), eq(3L));
      verify(q).setParameter(eq("to"),   eq(3L));
    }
  }

  // ── handle() routing — missing/invalid params ─────────────────────

  @Test
  public void testUnknownEntityReturns404() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    new StubHandler(servlet, false).handle("unknown-entity", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  @Test
  public void testMissingYearReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn(null);
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    new StubHandler(servlet, false).handle("known", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testInvalidYearStringReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("notanumber");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    new StubHandler(servlet, false).handle("known", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testMissingSinceForModifiedReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");
    when(req.getParameter(SINCE_KEY)).thenReturn(null);

    new StubHandler(servlet, false).handle(MODIFIED, "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  // ── dispatch() reachable when OBContext is mocked ─────────────────

  /**
   * When OBContext is successfully mocked, {@code dispatch()} is actually reached.
   * The stub throws {@link FiscalHandlerException}, which the base class must catch
   * and translate to a 500 (covers the {@code catch(FiscalHandlerException)} branch,
   * L104-L106 in {@link AbstractFiscalHandler#handle}).
   */
  @Test
  public void testDispatchFiscalExceptionWithOBContextMocked() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      OBContext ctx = mock(OBContext.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      Organization org = mock(Organization.class);
      when(ctx.getCurrentOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org1");

      new StubHandler(servlet, true).handle("known", "GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  /**
   * Same setup but the stub throws a plain {@link RuntimeException} from dispatch(),
   * exercising the {@code catch(Exception)} branch (L107-L111).
   */
  @Test
  public void testDispatchRuntimeExceptionWithOBContextMocked() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter(PERIOD_KEY)).thenReturn("T1");

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      OBContext ctx = mock(OBContext.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      Organization org = mock(Organization.class);
      when(ctx.getCurrentOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org1");

      new StubHandler(servlet, false).handle("known", "GET", req, resp);
    }

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  // ── handleModified — count query path ────────────────────────────

  /**
   * When periods are resolved and their dates are non-null, {@code handleModified}
   * runs the Invoice count query. This test exercises that path (L134-L148).
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  public void testHandleModifiedCountQueryWithResults() throws Exception {
    StubHandler handler = new StubHandler(servlet, false);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    Period period = mock(Period.class);
    when(period.getStartingDate()).thenReturn(new Date(0));
    when(period.getEndingDate()).thenReturn(new Date(86400000L));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);

      // Period typed query (resolvePeriods)
      Query<Period> periodQ = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(periodQ);
      when(periodQ.setParameter(anyString(), any())).thenReturn(periodQ);
      when(periodQ.list()).thenReturn(Collections.singletonList(period));

      // Count query — raw (no type parameter)
      Query countQ = mock(Query.class);
      when(session.createQuery(anyString())).thenReturn(countQ);
      when(countQ.setParameter(anyString(), any())).thenReturn(countQ);
      when(countQ.uniqueResult()).thenReturn(3L);

      handler.handleModified("org1", 2026, "T1", new Date(0), resp);
    }

    verify(resp).setContentType(anyString());
  }

  // ── resolveAcctSchema ─────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveAcctSchemaReturnsFirst() {
    StubHandler handler = new StubHandler(servlet, false);
    AcctSchema schema = mock(AcctSchema.class);
    org.openbravo.model.ad.system.Client client =
        mock(org.openbravo.model.ad.system.Client.class);
    when(client.getId()).thenReturn("client1");
    OBContext ctx = mock(OBContext.class);
    when(ctx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<AcctSchema> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(AcctSchema.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      when(crit.list()).thenReturn(Collections.singletonList(schema));

      AcctSchema result = handler.resolveAcctSchema();
      assertEquals(schema, result);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testResolveAcctSchemaThrowsWhenEmpty() {
    StubHandler handler = new StubHandler(servlet, false);
    org.openbravo.model.ad.system.Client client =
        mock(org.openbravo.model.ad.system.Client.class);
    when(client.getId()).thenReturn("client1");
    OBContext ctx = mock(OBContext.class);
    when(ctx.getCurrentClient()).thenReturn(client);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<AcctSchema> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(AcctSchema.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setMaxResults(1)).thenReturn(crit);
      when(crit.list()).thenReturn(Collections.emptyList());

      try {
        handler.resolveAcctSchema();
        fail("Expected OBException for missing AcctSchema");
      } catch (OBException e) {
        assertTrue(e.getMessage().contains("client1"));
      }
    }
  }
}
