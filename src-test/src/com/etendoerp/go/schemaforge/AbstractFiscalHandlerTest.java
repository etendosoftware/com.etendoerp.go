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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;

import static com.etendoerp.go.schemaforge.AbstractFiscalHandler.MODIFIED;
import static com.etendoerp.go.schemaforge.AbstractFiscalHandler.PERIOD_KEY;
import static com.etendoerp.go.schemaforge.AbstractFiscalHandler.SINCE_KEY;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * Unit tests for routing logic in {@link AbstractFiscalHandler}.
 *
 * Uses a minimal stub subclass so the base-class routing (declarations path,
 * dispatch exception handling, allowsPost default) can be exercised without
 * a real database connection.
 *
 * @covers com.etendoerp.go.schemaforge.AbstractFiscalHandler
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

    /** Declaration model code this stub answers for; settable per test. */
    String declModel = "stub";
    /** Payload returned by computeLivePayload; {@code null} makes it throw. */
    org.codehaus.jettison.json.JSONObject livePayload;
    /** Every (orgId, year, period) computeLivePayload was called with. */
    final java.util.List<String> computeCalls = new java.util.ArrayList<>();

    @Override
    protected String getDeclModel() {
      return declModel;
    }

    /** Per-invoice arrays the snapshot drops; settable per test. */
    java.util.Map<String, String> excludedLists = java.util.Collections.emptyMap();

    {
      // What a real subclass does in its constructor: install its snapshot definition. The live
      // payload comes from this stub's computeLivePayload override, never from the support.
      snapshotSupport = new FiscalSnapshotSupport() {
        @Override
        public String declModel() {
          return declModel;
        }

        @Override
        public org.codehaus.jettison.json.JSONObject computeLivePayload(AbstractFiscalHandler handler,
            String orgId, int year, String period) {
          throw new UnsupportedOperationException("StubHandler overrides computeLivePayload");
        }

        @Override
        public java.util.Map<String, String> excludedLists() {
          return excludedLists;
        }
      };
    }

    @Override
    org.codehaus.jettison.json.JSONObject computeLivePayload(String orgId, int year,
        String period) {
      computeCalls.add(orgId + "|" + year + "|" + period);
      if (livePayload == null) {
        throw new IllegalStateException("compute failed");
      }
      return livePayload;
    }

    @Override
    protected String resolveEffectiveOrg() {
      return "leaf-org";
    }
  }

  private static final String AD_MESSAGE_KEY = "@AEAT349_Phone_Contact_Mandatory@";
  private static final String TRANSLATED_MESSAGE =
      "Debe indicar la persona de contacto y el teléfono de la declaración.";

  private NeoServlet servlet;

  /**
   * ETP-5546 — {@link AbstractFiscalHandler#handle} now gates every /fiscal303 and /fiscal349
   * sub-route on the Tax Report window grant before any of the routing below runs. Default every
   * test to "granted" here so the pre-existing routing tests keep exercising what they were
   * written for; the denial itself gets its own tests below with this stub overridden to deny.
   */
  private MockedStatic<NeoAccessHelper> accessMock;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
        eq(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID), anyString())).thenReturn(true);
  }

  @After
  public void tearDown() {
    accessMock.close();
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

  // ── window-access gate (ETP-5546) ─────────────────────────────────

  /**
   * ETP-5546 — a role without the Tax Report window grant (the "Modelos Fiscales" access proxy,
   * ETP-5116) gets 403 from {@code handle()} before any routing — declarations, incidents, boxes
   * or a known entity alike, since this gate runs first. Covers the "declarations" sub-route
   * explicitly, which otherwise bypasses {@code isKnownEntity}/the year-period gate entirely.
   */
  @Test
  public void testWindowAccessDeniedReturnsForbiddenForDeclarations() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
        eq(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID), eq("GET"))).thenReturn(false);

    StubHandler handler = new StubHandler(servlet, false);
    handler.handle("declarations", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_FORBIDDEN), eq("Access denied"));
  }

  /**
   * ETP-5546 — same gate, tiered against the HTTP method: a role whose grant is read-only
   * ({@code IsReadWrite = 'N'}) is denied a write (e.g. {@code POST /fiscal303/submit}), proven
   * here via the generic "known" entity POST path, even though {@link #testAllowsPostDefaultReturnsFalse}
   * shows the stub's own {@code allowsPost} would otherwise 405 it — the access gate runs first and
   * must short-circuit before that routing is ever reached.
   */
  @Test
  public void testWindowAccessDeniedReturnsForbiddenForWriteMethod() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    accessMock.when(() -> NeoAccessHelper.hasWindowAccess(
        eq(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID), eq("POST"))).thenReturn(false);

    StubHandler handler = new StubHandler(servlet, false);
    handler.handle("known", "POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_FORBIDDEN), eq("Access denied"));
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

  // ── resolvePeriods — annual mode (ETP-5716) ───────────────────────

  private static Period period(long no) {
    Period p = mock(Period.class);
    when(p.getPeriodNo()).thenReturn(no);
    return p;
  }

  private static List<Period> periodsOf(long... numbers) {
    List<Period> list = new java.util.ArrayList<>();
    for (long n : numbers) {
      list.add(period(n));
    }
    return list;
  }

  /** "0A" binds months 1-12 and returns the 12 periods of the fiscal year. */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolvePeriodsAnnualBindsWholeYear() {
    StubHandler handler = new StubHandler(servlet, false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      List<Period> twelve = periodsOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
      when(q.list()).thenReturn(twelve);

      List<Period> result =
          handler.resolvePeriods("org1", 2025, AbstractFiscalHandler.ANNUAL_PERIOD);

      assertEquals(twelve, result);
      verify(q).setParameter(eq("from"), eq(1L));
      verify(q).setParameter(eq("to"), eq(12L));
      verify(q).setParameter(eq("year"), eq("2025"));
    }
  }

  /** An annual declaration over an incomplete calendar fails, naming the missing months. */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolvePeriodsAnnualFailsWhenAMonthIsMissing() {
    StubHandler handler = new StubHandler(servlet, false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      List<Period> incomplete = periodsOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 11);
      when(q.list()).thenReturn(incomplete);

      try {
        handler.resolvePeriods("org1", 2025, AbstractFiscalHandler.ANNUAL_PERIOD);
        fail("Expected IncompleteFiscalYearException for an incomplete fiscal year");
      } catch (IncompleteFiscalYearException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("[7, 12]"));
        assertTrue(e.getMessage(), e.getMessage().contains("2025"));
        assertEquals(2025, e.getYear());
        assertEquals(List.of(7L, 12L), e.getMissingPeriods());
      }
    }
  }

  /**
   * ETP-5716 (review B3): "single ordinary per exercise" is a per-model hook, not a model literal
   * in the shared CRUD handler — linkSubmittedSnapshotProviders wires it from each handler's
   * allowsSingleOrdinary() (only the 390 says yes) into every handler's declarations CRUD.
   */
  @Test
  public void testSingleOrdinaryRuleComesFromTheModelHandlers() {
    Fiscal390BoxesHandler h390 = new Fiscal390BoxesHandler(servlet);
    Fiscal303BoxesHandler h303 = new Fiscal303BoxesHandler(servlet);
    assertTrue(h390.allowsSingleOrdinary());
    assertTrue(!h303.allowsSingleOrdinary());
    AbstractFiscalHandler.linkSubmittedSnapshotProviders(h303, h390);
    for (AbstractFiscalHandler h : List.of(h303, h390)) {
      assertTrue(h.declHandler().singleOrdinarySupport.isSingleOrdinaryModel(h390.getDeclModel()));
      assertTrue(!h.declHandler().singleOrdinarySupport.isSingleOrdinaryModel(h303.getDeclModel()));
      assertTrue(!h.declHandler().singleOrdinarySupport.isSingleOrdinaryModel("unknown"));
    }
  }

  /** A year with no period at all is rejected too (no silent empty compute): every month. */
  @Test
  public void testRequireFullYearRejectsEmptyCalendar() {
    try {
      AbstractFiscalHandler.requireFullYear(Collections.emptyList(), "org1", 2025);
      fail("Expected IncompleteFiscalYearException for an empty calendar");
    } catch (IncompleteFiscalYearException e) {
      assertEquals(12, e.getMissingPeriods().size());
      // The text sent to the client never carries the organization id (the log message does).
      assertTrue(e.getMessage(), e.getMessage().contains("org1"));
      assertTrue(e.userText(), !e.userText().contains("org1"));
    }
  }

  /**
   * ETP-5716 — wherever it is raised under dispatch (wrapped by runDispatch), an incomplete
   * fiscal year is answered as the structured 422 INCOMPLETE_FISCAL_YEAR, not the generic 500.
   */
  @Test
  public void testIncompleteFiscalYearYields422WithCode() throws Exception {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2025");
    when(req.getParameter(PERIOD_KEY)).thenReturn("0A");
    StubHandler handler = new StubHandler(servlet, true,
        new IncompleteFiscalYearException("ORG1", 2025, List.of(11L, 12L)));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      OBContext    ctx = mock(OBContext.class);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG1");
      when(ctx.getCurrentOrganization()).thenReturn(org);
      ctxMock.when(OBContext::getOBContext).thenReturn(ctx);
      handler.handle("known", "GET", req, resp);
    }

    org.mockito.ArgumentCaptor<NeoResponse> captor =
        org.mockito.ArgumentCaptor.forClass(NeoResponse.class);
    verify(servlet).writeResponse(eq(resp), captor.capture());
    verify(servlet, org.mockito.Mockito.never()).sendError(eq(resp),
        eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), anyString());
    assertEquals(IncompleteFiscalYearException.HTTP_STATUS, captor.getValue().getHttpStatus());
    org.codehaus.jettison.json.JSONObject error = captor.getValue().getBody().getJSONObject("error");
    assertEquals("INCOMPLETE_FISCAL_YEAR", error.getString("code"));
    assertEquals(2025, error.getInt("year"));
    assertEquals(2, error.getJSONArray("missingPeriods").length());
    assertEquals(11, error.getJSONArray("missingPeriods").getInt(0));
    assertTrue(error.getString("message"), !error.getString("message").contains("ORG1"));
  }

  /** Quarter/month codes never run the full-year check: a partial result is returned as-is. */
  @SuppressWarnings("unchecked")
  @Test
  public void testResolvePeriodsQuarterDoesNotRequireFullYear() {
    StubHandler handler = new StubHandler(servlet, false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      Session session = mock(Session.class);
      when(obDal.getSession()).thenReturn(session);
      Query<Period> q = mock(Query.class);
      when(session.createQuery(anyString(), eq(Period.class))).thenReturn(q);
      when(q.setParameter(anyString(), any())).thenReturn(q);
      List<Period> two = periodsOf(4, 5);
      when(q.list()).thenReturn(two);

      assertEquals(two, handler.resolvePeriods("org1", 2025, "T2"));
    }
  }

  // ── findLatestTaxReport / pickLatestTaxReport (ETP-5716) ──────────

  private static final java.util.regex.Pattern AEAT390_KEY =
      java.util.regex.Pattern.compile("^AEAT390_(\\d{4})_Y$");

  private static org.openbravo.module.taxreportlauncher.TaxReport report(String key, String orgId,
      boolean active) {
    org.openbravo.module.taxreportlauncher.TaxReport r =
        mock(org.openbravo.module.taxreportlauncher.TaxReport.class);
    when(r.getSearchKey()).thenReturn(key);
    when(r.isActive()).thenReturn(active);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(r.getOrganization()).thenReturn(org);
    return r;
  }

  /** The exercise's own report wins when it exists; it is not a fallback. */
  @Test
  public void testPickLatestTaxReportPrefersExactYear() {
    org.openbravo.module.taxreportlauncher.TaxReport y2024 = report("AEAT390_2024_Y", "0", true);
    org.openbravo.module.taxreportlauncher.TaxReport y2025 = report("AEAT390_2025_Y", "0", true);

    AbstractFiscalHandler.ResolvedTaxReport r = AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(y2024, y2025), AEAT390_KEY, 2025, "org1");

    assertEquals(y2025, r.report);
    assertEquals("AEAT390_2025_Y", r.searchKey);
    assertEquals(2025, r.layoutYear);
    assertTrue(!r.isFallback());
  }

  /**
   * The normal 2026 path (product decision: no AEAT390_2026_Y entry): 2026 resolves to the latest
   * earlier report, flagged as a fallback.
   */
  @Test
  public void testPickLatestTaxReportFallsBackToLatestEarlierYear() {
    org.openbravo.module.taxreportlauncher.TaxReport y2023 = report("AEAT390_2023_Y", "0", true);
    org.openbravo.module.taxreportlauncher.TaxReport y2025 = report("AEAT390_2025_Y", "0", true);
    org.openbravo.module.taxreportlauncher.TaxReport y2024 = report("AEAT390_2024_Y", "0", true);

    AbstractFiscalHandler.ResolvedTaxReport r = AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(y2023, y2025, y2024), AEAT390_KEY, 2026, "org1");

    assertEquals("AEAT390_2025_Y", r.searchKey);
    assertEquals(2025, r.layoutYear);
    assertTrue(r.isFallback());
  }

  /** A later year's report is never used for an earlier exercise (no "today" involved). */
  @Test
  public void testPickLatestTaxReportIgnoresLaterYears() {
    AbstractFiscalHandler.ResolvedTaxReport r = AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(report("AEAT390_2025_Y", "0", true),
            report("AEAT390_2023_Y", "0", true)),
        AEAT390_KEY, 2024, "org1");

    assertEquals("AEAT390_2023_Y", r.searchKey);
  }

  /** An inactive newer row never shadows an active older one. */
  @Test
  public void testPickLatestTaxReportSkipsInactiveRows() {
    AbstractFiscalHandler.ResolvedTaxReport r = AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(report("AEAT390_2025_Y", "org1", false),
            report("AEAT390_2024_Y", "0", true)),
        AEAT390_KEY, 2026, "org1");

    assertEquals("AEAT390_2024_Y", r.searchKey);
  }

  /** Same layout year in org 0 and in the org: the org's own row wins, whatever the order. */
  @Test
  public void testPickLatestTaxReportTiePrefersOrgRow() {
    org.openbravo.module.taxreportlauncher.TaxReport system = report("AEAT390_2025_Y", "0", true);
    org.openbravo.module.taxreportlauncher.TaxReport own = report("AEAT390_2025_Y", "org1", true);

    assertEquals(own, AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(system, own), AEAT390_KEY, 2026, "org1").report);
    assertEquals(own, AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(own, system), AEAT390_KEY, 2026, "org1").report);
  }

  /** Keys not fully matching the pattern (the yearless AEAT390_Y, other models) are ignored. */
  @Test
  public void testPickLatestTaxReportIgnoresNonMatchingKeys() {
    AbstractFiscalHandler.ResolvedTaxReport r = AbstractFiscalHandler.pickLatestTaxReport(
        java.util.Arrays.asList(report("AEAT390_Y", "0", true),
            report("AEAT390_2025_Y_COPY", "0", true), report("AEAT303_Q_2025", "0", true),
            report(null, "0", true)),
        AEAT390_KEY, 2026, "org1");

    assertEquals(null, r);
  }

  /** findLatestTaxReport queries active rows of the org or org 0 and applies the pick rule. */
  @SuppressWarnings("unchecked")
  @Test
  public void testFindLatestTaxReportQueriesAndPicks() {
    StubHandler handler = new StubHandler(servlet, false);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<org.openbravo.module.taxreportlauncher.TaxReport> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(org.openbravo.module.taxreportlauncher.TaxReport.class))
          .thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      List<org.openbravo.module.taxreportlauncher.TaxReport> rows =
          Collections.singletonList(report("AEAT390_2025_Y", "0", true));
      when(crit.list()).thenReturn(rows);

      AbstractFiscalHandler.ResolvedTaxReport r =
          handler.findLatestTaxReport("org1", "AEAT390%_Y", AEAT390_KEY, 2026);

      assertEquals("AEAT390_2025_Y", r.searchKey);
      verify(crit, org.mockito.Mockito.times(3)).add(any());
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

  // ── submission snapshot wiring (ETP-5438) ───────────────────────────

  private static org.openbravo.base.structure.BaseOBObject declFor(String model) {
    org.openbravo.base.structure.BaseOBObject decl =
        mock(org.openbravo.base.structure.BaseOBObject.class);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_MODEL)).thenReturn(model);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_FISCAL_YEAR)).thenReturn(2026L);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_PERIOD)).thenReturn("T2");
    return decl;
  }

  /**
   * Every declaration PUT arrives through /fiscal303/declarations, so EACH handler's CRUD delegate
   * must route the snapshot to the handler owning the declaration's model, with the org resolved
   * exactly like the read endpoint resolves it.
   */
  @Test
  public void testLinkSubmittedSnapshotProvidersRoutesByDeclarationModel() throws Exception {
    StubHandler h303 = new StubHandler(servlet, false);
    h303.declModel = "303";
    h303.livePayload = new org.codehaus.jettison.json.JSONObject("{\"boxes\":{}}");
    StubHandler h349 = new StubHandler(servlet, false);
    h349.declModel = "349";
    h349.livePayload = new org.codehaus.jettison.json.JSONObject("{\"operators\":[]}");
    AbstractFiscalHandler.linkSubmittedSnapshotProviders(h303, h349);

    org.openbravo.base.structure.BaseOBObject decl349 = declFor("349");
    h303.declHandler().snapshots.takeSubmittedSnapshot(decl349);

    assertEquals(java.util.Collections.singletonList("leaf-org|2026|T2"), h349.computeCalls);
    assertTrue(h303.computeCalls.isEmpty());
    org.mockito.Mockito.verify(decl349).set(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT,
        "{\"operators\":[]}");
  }

  /** A model no handler serves gets no snapshot, and nothing is computed. */
  @Test
  public void testLinkSubmittedSnapshotProvidersUnknownModelTakesNoSnapshot() throws Exception {
    StubHandler h303 = new StubHandler(servlet, false);
    h303.declModel = "303";
    AbstractFiscalHandler.linkSubmittedSnapshotProviders(h303);

    org.openbravo.base.structure.BaseOBObject decl = declFor("390");
    h303.declHandler().snapshots.takeSubmittedSnapshot(decl);

    assertTrue(h303.computeCalls.isEmpty());
    org.mockito.Mockito.verify(decl, org.mockito.Mockito.never())
        .set(org.mockito.ArgumentMatchers.eq(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT),
            org.mockito.ArgumentMatchers.any());
  }

  /** A failing compute propagates, so the caller can reject the submission. */
  @Test(expected = IllegalStateException.class)
  public void testLinkSubmittedSnapshotProvidersPropagatesComputeFailure() throws Exception {
    StubHandler h303 = new StubHandler(servlet, false);
    h303.declModel = "303";
    AbstractFiscalHandler.linkSubmittedSnapshotProviders(h303);

    h303.declHandler().snapshots.takeSubmittedSnapshot(declFor("303"));
  }

  /**
   * ETP-5438 scope decision — the snapshot the manual PUT path takes through the provider drops
   * the model's per-invoice arrays and keeps their row counts, so it never grows with invoices.
   */
  @Test
  public void testLinkedProviderSnapshotDropsPerInvoiceArrays() throws Exception {
    StubHandler h303 = new StubHandler(servlet, false);
    h303.declModel = "303";
    h303.excludedLists = java.util.Collections.singletonMap("sources", "sourceCount");
    h303.livePayload = new org.codehaus.jettison.json.JSONObject(
        "{\"boxes\":{\"46\":\"1.00\"},\"sources\":[{\"ref\":\"A\"},{\"ref\":\"B\"}]}");
    AbstractFiscalHandler.linkSubmittedSnapshotProviders(h303);

    org.openbravo.base.structure.BaseOBObject decl = declFor("303");
    h303.declHandler().snapshots.takeSubmittedSnapshot(decl);

    org.mockito.Mockito.verify(decl).set(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT,
        "{\"boxes\":{\"46\":\"1.00\"},\"sourceCount\":2}");
  }

  // ── malformed client JSON → 400 ───────────────────────────────────

  private static MalformedJsonException malformedBody() {
    try {
      BoundedJsonParser.parseObject("{\"x\":[");
    } catch (MalformedJsonException e) {
      return e;
    }
    throw new AssertionError("expected the body to be malformed");
  }

  /** A body the declarations delegate cannot parse is the client's error: 400, not 500. */
  @Test
  public void testDeclarationsMalformedBodyIs400() throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    MalformedJsonException malformed = malformedBody();

    try (MockedConstruction<FiscalDeclCrudHandler> decl = mockConstruction(
        FiscalDeclCrudHandler.class, (m, ctx) -> doThrow(malformed).when(m)
            .handleDeclarations(anyString(), any(), any()))) {
      new StubHandler(servlet, false).handle("declarations", "POST",
          mock(HttpServletRequest.class), resp);
    }

    verify(servlet).sendError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
    verify(servlet, never()).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }

  /** {@code runDispatch} answers a malformed body with a 400 instead of wrapping it as a 500. */
  @Test
  public void testRunDispatchMalformedBodyIs400() throws Exception {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    MalformedJsonException malformed = malformedBody();

    new StubHandler(servlet, false).runDispatch(resp, () -> {
      throw malformed;
    });

    verify(servlet).sendError(resp, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
  }
}
