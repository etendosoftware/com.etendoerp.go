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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;

import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for {@link JwtAuthUtils}.
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

  @Nested
  @DisplayName("authenticate")
  class Authenticate {
    @Test
    void missingAuthHeaderThrowsOBException() {
      when(request.getHeader("Authorization")).thenReturn(null);
      assertThrows(OBException.class, () -> JwtAuthUtils.authenticate(request));
    }

    @Test
    void nonBearerHeaderThrowsOBException() {
      when(request.getHeader("Authorization")).thenReturn("Basic abc123");
      assertThrows(OBException.class, () -> JwtAuthUtils.authenticate(request));
    }

    @Test
    void invalidTokenThrowsOBException() throws Exception {
      when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
      swsMock.when(() -> SecureWebServicesUtils.decodeToken("invalid-token")).thenReturn(null);
      assertThrows(OBException.class, () -> JwtAuthUtils.authenticate(request));
    }
  }

  @Nested
  @DisplayName("authenticateOrFail")
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
    }
  }
}
