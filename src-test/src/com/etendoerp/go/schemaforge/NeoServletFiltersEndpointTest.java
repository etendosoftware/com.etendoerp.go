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

import static org.junit.Assert.assertTrue;
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

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for the real /filters servlet route.
 */
public class NeoServletFiltersEndpointTest {

  @Test
  public void doGetFiltersEndpointWritesStoredPresets() throws Exception {
    NeoServlet servlet = new NeoServlet();
    HttpServletRequest request = request("/filters/sales-order", "GET", null);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter writer = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(writer));

    OBContext authContext = mock(OBContext.class);

    JSONObject presets = new JSONObject()
        .put("Open", new JSONObject().put("status", "OP"));
    try (MockedStatic<SecureWebServicesUtils> secureMock = mockStatic(SecureWebServicesUtils.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<NeoFiltersService> filtersMock = mockStatic(NeoFiltersService.class)) {
      stubJwtAndContext(secureMock, obContextMock, authContext);
      filtersMock.when(() -> NeoFiltersService.getWindowPresets("sales-order"))
          .thenReturn(NeoResponse.ok(presets));

      servlet.doGet(request, response);
    }

    verify(response).setStatus(200);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding("UTF-8");
    assertTrue(writer.toString().contains("\"Open\""));
    assertTrue(writer.toString().contains("\"status\":\"OP\""));
  }

  @Test
  public void doPutFiltersEndpointSavesPresetAndFlushesDal() throws Exception {
    NeoServlet servlet = new NeoServlet();
    HttpServletRequest request = request("/filters/sales-order/Closed", "PUT",
        "{\"status\":\"CL\"}");
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBContext authContext = mock(OBContext.class);
    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<SecureWebServicesUtils> secureMock = mockStatic(SecureWebServicesUtils.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<NeoFiltersService> filtersMock = mockStatic(NeoFiltersService.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubJwtAndContext(secureMock, obContextMock, authContext);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doPut(request, response);

      filtersMock.verify(() -> NeoFiltersService.savePreset("sales-order", "Closed",
          "{\"status\":\"CL\"}"));
      verify(obDal).flush();
    }

    verify(response).setStatus(204);
  }

  @Test
  public void doDeleteFiltersEndpointDeletesPresetAndFlushesDal() throws Exception {
    NeoServlet servlet = new NeoServlet();
    HttpServletRequest request = request("/filters/sales-order/Closed", "DELETE", null);
    HttpServletResponse response = mock(HttpServletResponse.class);
    OBContext authContext = mock(OBContext.class);
    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<SecureWebServicesUtils> secureMock = mockStatic(SecureWebServicesUtils.class);
        MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<NeoFiltersService> filtersMock = mockStatic(NeoFiltersService.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubJwtAndContext(secureMock, obContextMock, authContext);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      servlet.doDelete(request, response);

      filtersMock.verify(() -> NeoFiltersService.deletePreset("sales-order", "Closed"));
      verify(obDal).flush();
    }

    verify(response).setStatus(204);
  }

  private static void stubJwtAndContext(MockedStatic<SecureWebServicesUtils> secureMock,
      MockedStatic<OBContext> obContextMock, OBContext authContext) throws Exception {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim userClaim = claim("user");
    Claim roleClaim = claim("role");
    Claim orgClaim = claim("organization");
    Claim whClaim = claim("warehouse");
    Claim clientClaim = claim("client");

    secureMock.when(() -> SecureWebServicesUtils.decodeToken("token")).thenReturn(jwt);
    when(jwt.getClaim("user")).thenReturn(userClaim);
    when(jwt.getClaim("role")).thenReturn(roleClaim);
    when(jwt.getClaim("organization")).thenReturn(orgClaim);
    when(jwt.getClaim("warehouse")).thenReturn(whClaim);
    when(jwt.getClaim("client")).thenReturn(clientClaim);

    when(userClaim.asString()).thenReturn("user-id");
    when(roleClaim.asString()).thenReturn("role-id");
    when(orgClaim.asString()).thenReturn("org-id");
    when(whClaim.asString()).thenReturn("wh-id");
    when(clientClaim.asString()).thenReturn("client-id");

    secureMock.when(() -> SecureWebServicesUtils.createContext("user-id", "role-id",
        "org-id", "wh-id", "client-id")).thenReturn(authContext);
    when(authContext.getWarehouse()).thenReturn(null);
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  private static HttpServletRequest request(String pathInfo, String method, String body)
      throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    when(request.getMethod()).thenReturn(method);
    when(request.getHeader("Authorization")).thenReturn("Bearer token");
    if (body != null) {
      when(request.getInputStream()).thenReturn(inputStream(body));
    }
    return request;
  }

  private static ServletInputStream inputStream(String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream in = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      @Override
      public int read() {
        return in.read();
      }

      @Override
      public boolean isFinished() {
        return in.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(javax.servlet.ReadListener readListener) {
      }
    };
  }

}
