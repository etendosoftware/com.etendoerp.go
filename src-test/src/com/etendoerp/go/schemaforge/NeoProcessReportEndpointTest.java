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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Tests for {@link NeoProcessReportEndpoint#handleProcessSpec}: how the POST against a process
 * spec answers a request body it cannot parse.
 *
 * @covers com.etendoerp.go.schemaforge.NeoProcessReportEndpoint
 */
public class NeoProcessReportEndpointTest {

  private NeoServlet servlet;
  private NeoProcessReportEndpoint endpoint;
  private SFSpec spec;
  private HttpServletResponse response;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    endpoint = new NeoProcessReportEndpoint(servlet);
    spec = mock(SFSpec.class);
    when(spec.getProcess()).thenReturn(mock(Process.class));
    response = mock(HttpServletResponse.class);
  }

  private static HttpServletRequest requestWithBody(String text) throws Exception {
    ByteArrayInputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(new ServletInputStream() {
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
      public void setReadListener(ReadListener listener) {
        // not used
      }
    });
    return request;
  }

  @Test
  public void aMalformedBodyIsA400AndTheProcessDoesNotRun() throws Exception {
    try (MockedStatic<NeoProcessService> process = mockStatic(NeoProcessService.class)) {
      endpoint.handleProcessSpec(spec, requestWithBody("{\"x\":["), response);

      verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_BAD_REQUEST),
          startsWith("Invalid JSON body"));
      verify(servlet, never()).sendError(any(), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
          anyString());
      process.verifyNoInteractions();
    }
  }

  @Test
  public void aWellFormedBodyRunsTheProcess() throws Exception {
    NeoResponse ok = NeoResponse.ok(new org.codehaus.jettison.json.JSONObject());
    try (MockedStatic<NeoProcessService> process = mockStatic(NeoProcessService.class)) {
      process.when(() -> NeoProcessService.executeProcess(any(), any())).thenReturn(ok);

      endpoint.handleProcessSpec(spec, requestWithBody("{\"a\":1}"), response);

      verify(servlet).writeResponse(response, ok);
      verify(servlet, never()).sendError(any(), anyInt(), anyString());
    }
  }
}
