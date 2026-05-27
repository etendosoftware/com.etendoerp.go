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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.common.JwtAuthUtils;

/** Tests for {@link NeoFavoritesServlet}. */
public class NeoFavoritesServletTest {

  private final NeoFavoritesServlet servlet = new NeoFavoritesServlet();

  @Test
  public void doGetAuthFailureReturns() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    try (MockedStatic<CorsUtils> corsMock = mockStatic(CorsUtils.class);
         MockedStatic<JwtAuthUtils> authMock = mockStatic(JwtAuthUtils.class)) {
      authMock.when(() -> JwtAuthUtils.authenticateOrFail(any(), any(), any(), anyString()))
          .thenReturn(false);

      servlet.doGet(req, resp);
      // No exception — early return on auth failure
    }
  }

  @Test
  public void doGetSuccessReturnsFavorites() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    StringWriter body = new StringWriter();
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(body));

    try (MockedStatic<CorsUtils> corsMock = mockStatic(CorsUtils.class);
         MockedStatic<JwtAuthUtils> authMock = mockStatic(JwtAuthUtils.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<NeoFavoritesService> svcMock = mockStatic(NeoFavoritesService.class)) {
      authMock.when(() -> JwtAuthUtils.authenticateOrFail(any(), any(), any(), anyString()))
          .thenReturn(true);
      svcMock.when(NeoFavoritesService::getFavoritesJson).thenReturn("[{\"name\":\"test\"}]");

      servlet.doGet(req, resp);

      assertEquals("[{\"name\":\"test\"}]", body.toString());
    }
  }

  @Test
  public void doGetExceptionHandled() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<CorsUtils> corsMock = mockStatic(CorsUtils.class);
         MockedStatic<JwtAuthUtils> authMock = mockStatic(JwtAuthUtils.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoFavoritesService> svcMock = mockStatic(NeoFavoritesService.class)) {
      authMock.when(() -> JwtAuthUtils.authenticateOrFail(any(), any(), any(), anyString()))
          .thenReturn(true);
      svcMock.when(NeoFavoritesService::getFavoritesJson)
          .thenThrow(new RuntimeException("db error"));
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doGet(req, resp);
      // No exception propagated — error is handled
    }
  }

  @Test
  public void doPutAuthFailureReturns() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    try (MockedStatic<CorsUtils> corsMock = mockStatic(CorsUtils.class);
         MockedStatic<JwtAuthUtils> authMock = mockStatic(JwtAuthUtils.class)) {
      authMock.when(() -> JwtAuthUtils.authenticateOrFail(any(), any(), any(), anyString()))
          .thenReturn(false);

      servlet.doPut(req, resp);
    }
  }

  @Test
  public void doOptionsReturnsNoContent() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    try (MockedStatic<CorsUtils> corsMock = mockStatic(CorsUtils.class)) {
      servlet.doOptions(req, resp);

      verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
  }
}
