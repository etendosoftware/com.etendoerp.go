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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/** Tests for {@link NeoButtonHandler}. */
public class NeoButtonHandlerTest {

  private final NeoButtonHandler handler = new NeoButtonHandler();

  @Test
  public void nullEntityReturnsNotFound() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "missing", null);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = handler.handleButtonAction(pathInfo, "GET", req, null);

    assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getHttpStatus());
  }

  @Test
  public void getWithNoActionNameListsActions() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", null);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("entity-1");
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse mockResp = new NeoResponse(200, null);

    try (MockedStatic<NeoButtonActionHelper> helperMock = mockStatic(NeoButtonActionHelper.class)) {
      helperMock.when(() -> NeoButtonActionHelper.listButtonActions("entity-1"))
          .thenReturn(mockResp);

      NeoResponse resp = handler.handleButtonAction(pathInfo, "GET", req, entity);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void postWithActionNameExecutesAction() throws Exception {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", "rec-1",
        false, null, true, "complete");
    SFEntity entity = mock(SFEntity.class);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse mockResp = new NeoResponse(200, null);

    try (MockedStatic<NeoButtonActionHelper> helperMock = mockStatic(NeoButtonActionHelper.class)) {
      helperMock.when(() -> NeoButtonActionHelper.executeButtonAction(entity, pathInfo, req))
          .thenReturn(mockResp);

      NeoResponse resp = handler.handleButtonAction(pathInfo, "POST", req, entity);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void getWithActionNameReturnsMethodNotAllowed() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", "rec-1",
        false, null, true, "complete");
    SFEntity entity = mock(SFEntity.class);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = handler.handleButtonAction(pathInfo, "GET", req, entity);

    assertEquals(HttpServletResponse.SC_METHOD_NOT_ALLOWED, resp.getHttpStatus());
  }

  @Test
  public void postWithNoActionNameReturnsBadRequest() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", null);
    SFEntity entity = mock(SFEntity.class);
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = handler.handleButtonAction(pathInfo, "POST", req, entity);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void exceptionReturnsInternalError() {
    NeoPathInfo pathInfo = new NeoPathInfo("spec", "order", null);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenThrow(new RuntimeException("boom"));
    HttpServletRequest req = mock(HttpServletRequest.class);

    NeoResponse resp = handler.handleButtonAction(pathInfo, "GET", req, entity);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
  }
}
