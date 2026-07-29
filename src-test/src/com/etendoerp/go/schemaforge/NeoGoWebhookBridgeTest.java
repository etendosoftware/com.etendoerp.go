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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Tests for {@link NeoGoWebhookBridge}. Mirrors {@link NeoSimSearchEndpointTest}'s conventions
 * (plain JUnit4, same package).
 */
public class NeoGoWebhookBridgeTest {

  private static final Map<String, String> PARAMS = Collections.singletonMap("foo", "bar");

  private static NeoServlet servletReturning(Map<String, String> params, HttpServletRequest request) {
    NeoServlet servlet = mock(NeoServlet.class);
    when(servlet.extractQueryParams(request)).thenReturn(params);
    return servlet;
  }

  @Test
  public void successReturnsOkWithFlatResultBody() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    NeoServlet servlet = servletReturning(PARAMS, request);
    NeoGoWebhookBridge bridge = new NeoGoWebhookBridge(servlet);

    BaseWebhookService webhook = mock(BaseWebhookService.class);
    doAnswer(invocation -> {
      Map<String, String> responseVars = invocation.getArgument(1);
      responseVars.put("result", "{\"roles\":[]}");
      return null;
    }).when(webhook).get(any(), any());

    NeoResponse resp = bridge.handle(request, webhook);

    assertEquals(200, resp.getHttpStatus());
    assertEquals("{\"roles\":[]}", resp.getBody().getString("result"));
    verify(webhook).get(eq(PARAMS), any());
  }

  @Test
  public void webhookErrorReturnsInternalErrorWithMessage() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    NeoServlet servlet = servletReturning(PARAMS, request);
    NeoGoWebhookBridge bridge = new NeoGoWebhookBridge(servlet);

    BaseWebhookService webhook = mock(BaseWebhookService.class);
    doAnswer(invocation -> {
      Map<String, String> responseVars = invocation.getArgument(1);
      responseVars.put("error", "denied");
      return null;
    }).when(webhook).get(any(), any());

    NeoResponse resp = bridge.handle(request, webhook);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    JSONObject errorObj = resp.getBody().getJSONObject("error");
    assertEquals("denied", errorObj.getString("message"));
  }

  @Test
  public void thrownExceptionIsCaughtAndReturnsInternalError() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    NeoServlet servlet = servletReturning(PARAMS, request);
    NeoGoWebhookBridge bridge = new NeoGoWebhookBridge(servlet);

    BaseWebhookService webhook = mock(BaseWebhookService.class);
    doThrow(new RuntimeException("boom")).when(webhook).get(any(), any());

    NeoResponse resp = bridge.handle(request, webhook);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    JSONObject errorObj = resp.getBody().getJSONObject("error");
    assertEquals("boom", errorObj.getString("message"));
  }
}
