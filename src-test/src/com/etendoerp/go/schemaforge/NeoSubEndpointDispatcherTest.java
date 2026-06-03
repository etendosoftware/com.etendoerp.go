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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoSubEndpointDispatcher}.
 */
class NeoSubEndpointDispatcherTest {

  private NeoServlet servlet;
  private NeoSubEndpointDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    servlet = mock(NeoServlet.class);
    dispatcher = new NeoSubEndpointDispatcher(servlet);
  }

  @Test
  @DisplayName("Returns false when no sub-endpoint flags are set (falls through to CRUD)")
  void noSubEndpointReturnsFalse() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo("spec", "entity", "id");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = dispatcher.handleWindowSubEndpoint(spec, pathInfo, "GET", request, response);

    assertFalse(handled);
  }

  @Test
  @DisplayName("Selector rejects non-GET methods with 405")
  void selectorRejectsNonGet() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
        "spec", "entity", null, true, null);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = dispatcher.handleWindowSubEndpoint(spec, pathInfo, "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Selectors only support GET"));
  }

  @Test
  @DisplayName("Evaluate-display rejects non-POST methods with 405")
  void evaluateDisplayRejectsNonPost() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
        "spec", "entity", null, false, null, false, null, true);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = dispatcher.handleWindowSubEndpoint(spec, pathInfo, "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Method not allowed"));
  }

  @Test
  @DisplayName("Callout rejects non-POST methods with 405")
  void calloutRejectsNonPost() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
        "spec", "entity", null, false, null, false, null, false, true, false);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = dispatcher.handleWindowSubEndpoint(spec, pathInfo, "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Callout endpoint only supports POST"));
  }

  @Test
  @DisplayName("Defaults rejects non-GET methods with 405")
  void defaultsRejectsNonGet() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
        "spec", "entity", null, false, null, false, null, false, false, true);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = dispatcher.handleWindowSubEndpoint(spec, pathInfo, "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Defaults endpoint only supports GET"));
  }

  @Test
  @DisplayName("Action rejects unsupported methods with 405")
  void actionRejectsUnsupportedMethods() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    NeoServlet.NeoPathInfo pathInfo = new NeoServlet.NeoPathInfo(
        "spec", "entity", "id", false, null, true, "docAction");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    boolean handled = dispatcher.handleWindowSubEndpoint(spec, pathInfo, "DELETE", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        contains("Actions support GET (list) and POST (execute)"));
  }
}