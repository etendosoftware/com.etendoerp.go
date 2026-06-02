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

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link Fiscal349BoxesHandler}.
 *
 * Covers HTTP routing validation only — DB-dependent methods
 * (computeOperators, handleGenerate) are integration-tested separately.
 */
public class Fiscal349BoxesHandlerTest {

  private NeoServlet servlet;
  private Fiscal349BoxesHandler handler;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    handler = new Fiscal349BoxesHandler(servlet);
  }

  // ── constructor ───────────────────────────────────────────────────

  @Test
  public void testHandlerInstantiates() {
    assertNotNull(handler);
  }

  // ── unknown entity → 404 ─────────────────────────────────────────

  @Test
  public void testUnknownEntityReturns404() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    handler.handle("unknown_entity", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  // ── non-GET method → 405 ─────────────────────────────────────────

  @Test
  public void testPostToOperatorsReturns405() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    handler.handle("operators", "POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  @Test
  public void testPostToGenerateReturns405() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    handler.handle("generate", "POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  // ── missing year/period → 400 ────────────────────────────────────

  @Test
  public void testMissingYearReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn(null);
    when(req.getParameter("period")).thenReturn("T1");

    handler.handle("operators", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  @Test
  public void testMissingPeriodReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn(null);

    handler.handle("operators", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }

  // ── modified without since → 400 ─────────────────────────────────

  @Test
  public void testModifiedMissingSinceReturns400() throws IOException {
    HttpServletRequest  req  = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getParameter("year")).thenReturn("2026");
    when(req.getParameter("period")).thenReturn("T1");
    when(req.getParameter("since")).thenReturn(null);

    handler.handle("modified", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
  }
}
