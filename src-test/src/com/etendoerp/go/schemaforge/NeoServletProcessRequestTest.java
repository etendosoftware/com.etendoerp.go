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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.BoundedJsonParser;
import com.etendoerp.go.common.MalformedJsonException;

/**
 * How {@link NeoServlet}'s request entry point maps a failure inside the routed handler to an HTTP
 * status. The collaborators the servlet builds in its field initializers are replaced with
 * {@code mockConstruction} so the routed call can be made to throw.
 *
 * @covers com.etendoerp.go.schemaforge.NeoServlet
 */
class NeoServletProcessRequestTest {

  private static final String SECRET = "hunter2";

  private MockedStatic<OBContext> obContext;
  private MockedConstruction<NeoAuthenticator> authenticators;
  private MockedConstruction<NeoRequestRouter> routers;
  private MockedConstruction<NeoBuiltInEndpointHandler> builtIns;
  private MockedConstruction<NeoPseudoSpecDispatcher> pseudoSpecs;
  private final StringWriter body = new StringWriter();
  private HttpServletResponse response;
  private NeoServlet servlet;

  @BeforeEach
  void setUp() throws Exception {
    obContext = mockStatic(OBContext.class);
    authenticators = mockConstruction(NeoAuthenticator.class,
        (auth, ctx) -> when(auth.authenticateRequest(any(), any())).thenReturn(true));
    routers = mockConstruction(NeoRequestRouter.class,
        (router, ctx) -> when(router.parseRequestPath(any(), any()))
            .thenReturn(new NeoServlet.NeoPathInfo("spec", "entity", null)));
    builtIns = mockConstruction(NeoBuiltInEndpointHandler.class);
    pseudoSpecs = mockConstruction(NeoPseudoSpecDispatcher.class);
    servlet = new NeoServlet();
    response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body, true));
  }

  @AfterEach
  void tearDown() {
    pseudoSpecs.close();
    builtIns.close();
    routers.close();
    authenticators.close();
    obContext.close();
  }

  private void routedCallThrows(Exception e) throws Exception {
    doThrow(e).when(routers.constructed().get(0))
        .handleSpecRequest(any(), anyString(), any(), any());
  }

  private static MalformedJsonException parseFailureOf(String text) {
    try {
      BoundedJsonParser.parseObject(text);
    } catch (MalformedJsonException e) {
      return e;
    }
    throw new AssertionError("expected " + text + " to be malformed");
  }

  @Test
  void aMalformedJsonBodyIsA400InvalidJsonBody() throws Exception {
    routedCallThrows(parseFailureOf("{\"x\":["));

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(body.toString().contains("Invalid JSON body"), body.toString());
  }

  /** jettison's syntax error quotes the whole text; the 400 must not echo the client's body. */
  @Test
  void theErrorDoesNotEchoTheBody() throws Exception {
    routedCallThrows(parseFailureOf("{\"password\":\"" + SECRET + "\":1}"));

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(body.toString().contains("Invalid JSON body"), body.toString());
    assertFalse(body.toString().contains(SECRET), body.toString());
  }

  @Test
  void anyOtherFailureIsStillA500() throws Exception {
    routedCallThrows(new IllegalStateException("boom"));

    servlet.doPost(mock(HttpServletRequest.class), response);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }
}
