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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.Test;

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

    StubHandler(NeoServlet servlet, boolean throwFiscalEx) {
      super(servlet);
      this.throwFiscalEx = throwFiscalEx;
    }

    @Override
    protected boolean isKnownEntity(String entityName) {
      return "known".equals(entityName);
    }

    @Override
    protected void dispatch(String entityName, String orgId, int year, String period,
        HttpServletRequest request, HttpServletResponse response) throws FiscalHandlerException {
      if (throwFiscalEx) {
        throw new FiscalHandlerException(new RuntimeException("fiscal error"));
      }
      throw new RuntimeException("unexpected dispatch error");
    }

    @Override
    protected String getModelKey() {
      return "stub";
    }
  }

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
    // "declarations" is handled before isKnownEntity; FiscalDeclCrudHandler will
    // fail because servlet is a mock with no real behaviour → exception is caught.
    handler.handle("declarations", "GET", req, resp);

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

  // ── general catch (Exception) path ───────────────────────────────

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
    when(req.getParameter("period")).thenReturn("T1");

    // dispatch() throws a plain RuntimeException → caught by catch (Exception e).
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
    when(req.getParameter("period")).thenReturn("T1");

    StubHandler handler = new StubHandler(servlet, true);
    handler.handle("known", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        anyString());
  }
}
