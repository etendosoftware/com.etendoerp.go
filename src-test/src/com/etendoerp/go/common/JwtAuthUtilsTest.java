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
package com.etendoerp.go.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.auth.AuthScheme;
import com.etendoerp.go.auth.EnvironmentAuthOutcome;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.SurfacePolicy;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for {@link JwtAuthUtils}.
 *
 * <p>ETP-5455 — it is now a thin adapter over {@link EnvironmentRequestAuthenticator}: what is
 * pinned here is that it asks for {@link SurfacePolicy#NEO_DATA} (so favorites and fiscal test
 * mode get NEO's commercial-access check, which their private copy never had) and that every
 * refusal is written with the pipeline's own status and message. The scheme matrix itself lives
 * in {@code EnvironmentRequestAuthenticatorTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthUtilsTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;
  @Mock private Logger log;

  private MockedStatic<SecureWebServicesUtils> swsMock;

  @BeforeEach
  void setUp() {
    swsMock = mockStatic(SecureWebServicesUtils.class);
  }

  @AfterEach
  void tearDown() {
    swsMock.close();
  }

  /** The public entry point, through the real pipeline, for requests refused before any DB. */
  @Nested
  @DisplayName("authenticateOrFail over the real pipeline")
  class AuthenticateOrFail {
    @Test
    void missingHeaderReturnsFalse() throws Exception {
      when(request.getHeader("Authorization")).thenReturn(null);
      StringWriter sw = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(sw));

      boolean result = JwtAuthUtils.authenticateOrFail(request, response, log, "test-endpoint");
      assertFalse(result);
      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void invalidTokenReturnsFalse() throws Exception {
      when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
      swsMock.when(() -> SecureWebServicesUtils.decodeToken("bad-token")).thenReturn(null);
      StringWriter sw = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(sw));

      boolean result = JwtAuthUtils.authenticateOrFail(request, response, log, "mcp");
      assertFalse(result);
      verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
  }

  @Nested
  @DisplayName("authenticateOrFail wiring")
  class Wiring {
    private EnvironmentRequestAuthenticator pipeline;

    @BeforeEach
    void setUpPipeline() {
      pipeline = mock(EnvironmentRequestAuthenticator.class);
    }

    @Test
    void asksForTheNeoDataPolicyAndLetsAnAuthenticatedRequestThrough() throws Exception {
      when(pipeline.authenticate(request, SurfacePolicy.NEO_DATA)).thenReturn(
          EnvironmentAuthOutcome.authenticated(AuthScheme.COOKIE, mock(OBContext.class),
              "user-1", "role-1", "client-1", "org-1"));

      assertTrue(JwtAuthUtils.authenticateOrFail(pipeline, request, response, log, "favorites GET"));

      verify(pipeline).authenticate(request, SurfacePolicy.NEO_DATA);
      verify(response, never()).setStatus(anyInt());
    }

    /** 401, 403 and 402 all reach the client with the pipeline's own status and message. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = EnvironmentAuthOutcome.Status.class, names = { "UNAUTHENTICATED",
        "CSRF_REJECTED", "PAYMENT_REQUIRED" })
    void writesEveryRefusalWithThePipelinesStatusAndMessage(EnvironmentAuthOutcome.Status status)
        throws Exception {
      String message = "refused as " + status.name();
      when(pipeline.authenticate(any(), eq(SurfacePolicy.NEO_DATA)))
          .thenReturn(EnvironmentAuthOutcome.refused(status, message, AuthScheme.COOKIE));
      StringWriter body = new StringWriter();
      when(response.getWriter()).thenReturn(new PrintWriter(body));

      assertFalse(JwtAuthUtils.authenticateOrFail(pipeline, request, response, log, "favorites GET"));

      verify(response).setStatus(status.getHttpStatus());
      assertTrue(body.toString().contains(message), body.toString());
    }
  }
}
