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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/** Tests for {@link NeoDefaultsEndpoint}. */
public class NeoDefaultsEndpointTest {

  @Test
  public void entityNotFoundReturnsError() {
    NeoServlet servlet = mock(NeoServlet.class);
    when(servlet.findEntity(anyString(), anyString())).thenReturn(null);

    NeoDefaultsEndpoint endpoint = new NeoDefaultsEndpoint(servlet);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    NeoPathInfo pathInfo = new NeoPathInfo("order", "header", null);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = endpoint.handleDefaults(spec, pathInfo, req);

    assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getHttpStatus());
  }

  @Test
  public void tabNotFoundReturnsError() {
    NeoServlet servlet = mock(NeoServlet.class);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(null);
    when(servlet.findEntity(anyString(), anyString())).thenReturn(entity);

    NeoDefaultsEndpoint endpoint = new NeoDefaultsEndpoint(servlet);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    NeoPathInfo pathInfo = new NeoPathInfo("order", "header", null);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = endpoint.handleDefaults(spec, pathInfo, req);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
  }

  @Test
  public void successDelegatesToNeoDefaultsService() {
    NeoServlet servlet = mock(NeoServlet.class);
    Tab tab = mock(Tab.class);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getADTab()).thenReturn(tab);
    when(servlet.findEntity(anyString(), anyString())).thenReturn(entity);

    NeoDefaultsEndpoint endpoint = new NeoDefaultsEndpoint(servlet);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    NeoPathInfo pathInfo = new NeoPathInfo("order", "header", null);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("parentId")).thenReturn("parent-1");

    OBContext obCtx = mock(OBContext.class);
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<NeoDefaultsService> svcMock = mockStatic(NeoDefaultsService.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      svcMock.when(() -> NeoDefaultsService.resolveDefaults(any(NeoContext.class), anyString()))
          .thenReturn(expected);

      NeoResponse resp = endpoint.handleDefaults(spec, pathInfo, req);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void exceptionReturnsInternalError() {
    NeoServlet servlet = mock(NeoServlet.class);
    when(servlet.findEntity(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

    NeoDefaultsEndpoint endpoint = new NeoDefaultsEndpoint(servlet);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    NeoPathInfo pathInfo = new NeoPathInfo("order", "header", null);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = endpoint.handleDefaults(spec, pathInfo, req);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
  }
}
