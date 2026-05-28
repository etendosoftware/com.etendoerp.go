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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CorsUtils}.
 */
@ExtendWith(MockitoExtension.class)
class CorsUtilsTest {

  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  @Nested
  @DisplayName("apply")
  class Apply {

    @Test
    void noOriginHeaderDoesNotSetCorsHeaders() {
      when(request.getHeader("Origin")).thenReturn(null);

      CorsUtils.apply(request, response, "GET,POST", "Content-Type", null, false);

      verify(response, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
    }

    @Test
    void blankOriginHeaderDoesNotSetCorsHeaders() {
      when(request.getHeader("Origin")).thenReturn("   ");

      CorsUtils.apply(request, response, "GET", "Content-Type", null, false);

      verify(response, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
    }

    @Test
    void defaultAllowedOriginSetsCorsHeaders() {
      when(request.getHeader("Origin")).thenReturn("http://localhost:3000");
      when(request.getRequestURL()).thenReturn(new StringBuffer("http://server:8080/api"));

      CorsUtils.apply(request, response, "GET,POST", "Content-Type,Authorization", null, false);

      verify(response).setHeader("Access-Control-Allow-Origin", "http://localhost:3000");
      verify(response).setHeader("Access-Control-Allow-Methods", "GET,POST");
      verify(response).setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization");
      verify(response).setHeader("Access-Control-Max-Age", "86400");
      verify(response).setHeader("Vary", "Origin");
    }

    @Test
    void sameOriginAsRequestUrlSetsCorsHeaders() {
      when(request.getHeader("Origin")).thenReturn("http://myserver:8080");
      when(request.getRequestURL()).thenReturn(new StringBuffer("http://myserver:8080/etendo/api"));

      CorsUtils.apply(request, response, "GET", "Content-Type", null, false);

      verify(response).setHeader("Access-Control-Allow-Origin", "http://myserver:8080");
    }

    @Test
    void unknownOriginDoesNotSetCorsHeaders() {
      when(request.getHeader("Origin")).thenReturn("http://evil.example.com");
      when(request.getRequestURL()).thenReturn(new StringBuffer("http://server:8080/api"));

      CorsUtils.apply(request, response, "GET", "Content-Type", null, false);

      verify(response, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
    }

    @Test
    void allowCredentialsSetsHeader() {
      when(request.getHeader("Origin")).thenReturn("http://localhost:5173");
      when(request.getRequestURL()).thenReturn(new StringBuffer("http://server:8080/api"));

      CorsUtils.apply(request, response, "GET", "Content-Type", null, true);

      verify(response).setHeader("Access-Control-Allow-Credentials", "true");
    }

    @Test
    void exposedHeadersSetsHeader() {
      when(request.getHeader("Origin")).thenReturn("http://localhost:3000");
      when(request.getRequestURL()).thenReturn(new StringBuffer("http://server:8080/api"));

      CorsUtils.apply(request, response, "GET", "Content-Type", "X-Custom-Header", false);

      verify(response).setHeader("Access-Control-Expose-Headers", "X-Custom-Header");
    }

    @Test
    void nullExposedHeadersDoesNotSetExposeHeader() {
      when(request.getHeader("Origin")).thenReturn("http://localhost:3000");
      when(request.getRequestURL()).thenReturn(new StringBuffer("http://server:8080/api"));

      CorsUtils.apply(request, response, "GET", "Content-Type", null, false);

      verify(response, never()).setHeader(eq("Access-Control-Expose-Headers"), anyString());
    }
  }
}
