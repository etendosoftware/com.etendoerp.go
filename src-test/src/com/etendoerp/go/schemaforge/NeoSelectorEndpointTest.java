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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;

/** Tests for {@link NeoSelectorEndpoint}. */
public class NeoSelectorEndpointTest {

  private final NeoSelectorEndpoint endpoint = new NeoSelectorEndpoint();

  @Test
  public void noSelectorFieldListsSelectors() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", null);
    HttpServletRequest req = mock(HttpServletRequest.class);
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<NeoSelectorService> svcMock = mockStatic(NeoSelectorService.class)) {
      svcMock.when(() -> NeoSelectorService.listSelectors("spec-1", "order"))
          .thenReturn(expected);

      NeoResponse resp = endpoint.handleSelector("spec-1", pathInfo, req);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void withSelectorFieldQueriesSelector() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", null,
        true, "businessPartner");
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("q")).thenReturn("test");
    when(req.getParameter("limit")).thenReturn("10");
    when(req.getParameter("offset")).thenReturn("5");
    when(req.getParameterNames()).thenReturn(Collections.emptyEnumeration());
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<NeoSelectorService> svcMock = mockStatic(NeoSelectorService.class)) {
      svcMock.when(() -> NeoSelectorService.querySelector(
          eq("spec-1"), eq("order"), eq("businessPartner"),
          eq("test"), eq(10), eq(5), any()))
          .thenReturn(expected);

      NeoResponse resp = endpoint.handleSelector("spec-1", pathInfo, req);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void invalidLimitUsesDefault() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", null,
        true, "bp");
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("q")).thenReturn(null);
    when(req.getParameter("limit")).thenReturn("abc");
    when(req.getParameter("offset")).thenReturn("xyz");
    when(req.getParameterNames()).thenReturn(Collections.emptyEnumeration());
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<NeoSelectorService> svcMock = mockStatic(NeoSelectorService.class)) {
      svcMock.when(() -> NeoSelectorService.querySelector(
          anyString(), anyString(), anyString(),
          any(), eq(20), eq(0), any()))
          .thenReturn(expected);

      NeoResponse resp = endpoint.handleSelector("spec-1", pathInfo, req);
      assertEquals(200, resp.getHttpStatus());
    }
  }
}
