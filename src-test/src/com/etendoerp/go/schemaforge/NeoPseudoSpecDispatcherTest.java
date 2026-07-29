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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.schemaforge.webhooks.SFListMenu;
import com.etendoerp.go.schemaforge.webhooks.SFRolesOverview;
import com.etendoerp.go.schemaforge.webhooks.SFWindowAccessMap;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Unit tests for {@link NeoPseudoSpecDispatcher}. Mirrors {@link NeoBuiltInEndpointHandlerTest}'s
 * conventions: {@link NeoServlet} and its collaborators are all Mockito mocks — the dispatcher is
 * the only real object under test — so none of the real {@code NeoServlet} collaborator
 * constructors (several of which touch the DB/DAL) ever run.
 */
public class NeoPseudoSpecDispatcherTest {

  private NeoServlet servlet;
  private BatchService batchService;
  private NeoSimSearchEndpoint simSearchEndpoint;
  private NeoGoWebhookBridge goWebhookBridge;
  private NeoPseudoSpecDispatcher dispatcher;
  private HttpServletRequest request;
  private HttpServletResponse response;

  @Before
  public void setUp() {
    servlet = mock(NeoServlet.class);
    batchService = mock(BatchService.class);
    simSearchEndpoint = mock(NeoSimSearchEndpoint.class);
    goWebhookBridge = mock(NeoGoWebhookBridge.class);
    dispatcher = new NeoPseudoSpecDispatcher(servlet, batchService, simSearchEndpoint, goWebhookBridge);
    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
  }

  private static NeoServlet.NeoPathInfo pathInfo(String specName) {
    return new NeoServlet.NeoPathInfo(specName, null, null);
  }

  // -------------------------------------------------------------------------
  // fallthrough
  // -------------------------------------------------------------------------

  @Test
  public void handleReturnsFalseForUnknownSpec() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("unknown"), "GET", request, response);

    assertFalse(handled);
  }

  // -------------------------------------------------------------------------
  // batch
  // -------------------------------------------------------------------------

  @Test
  public void batchPostDelegatesToBatchService() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("batch"), "POST", request, response);

    assertTrue(handled);
    verify(batchService).handle(request, response);
  }

  @Test
  public void batchRejectsNonPostMethod() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("batch"), "GET", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Batch endpoint only supports POST"));
    verify(batchService, never()).handle(any(), any());
  }

  // -------------------------------------------------------------------------
  // simsearch
  // -------------------------------------------------------------------------

  @Test
  public void simSearchGetWritesEndpointResponse() throws Exception {
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(simSearchEndpoint.handle(request)).thenReturn(payload);

    boolean handled = dispatcher.handle(pathInfo("simsearch"), "GET", request, response);

    assertTrue(handled);
    verify(servlet).writeResponse(response, payload);
  }

  @Test
  public void simSearchRejectsNonGetMethod() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("simsearch"), "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Simsearch endpoint only supports GET"));
    verify(simSearchEndpoint, never()).handle(any());
  }

  // -------------------------------------------------------------------------
  // Etendo GO webhook bridge (listmenu / windowaccessmap / rolesoverview)
  // -------------------------------------------------------------------------

  @Test
  public void listMenuGetDispatchesThroughBridgeWithSFListMenu() throws Exception {
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(goWebhookBridge.handle(eq(request), any(BaseWebhookService.class))).thenReturn(payload);

    boolean handled = dispatcher.handle(pathInfo("listmenu"), "GET", request, response);

    assertTrue(handled);
    ArgumentCaptor<BaseWebhookService> webhookCaptor = ArgumentCaptor.forClass(BaseWebhookService.class);
    verify(goWebhookBridge).handle(eq(request), webhookCaptor.capture());
    assertTrue(webhookCaptor.getValue() instanceof SFListMenu);
    verify(servlet).writeResponse(response, payload);
  }

  @Test
  public void listMenuRejectsNonGetMethod() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("listmenu"), "POST", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Listmenu endpoint only supports GET"));
    verify(goWebhookBridge, never()).handle(any(), any());
  }

  @Test
  public void windowAccessMapGetDispatchesThroughBridgeWithSFWindowAccessMap() throws Exception {
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(goWebhookBridge.handle(eq(request), any(BaseWebhookService.class))).thenReturn(payload);

    boolean handled = dispatcher.handle(pathInfo("windowaccessmap"), "GET", request, response);

    assertTrue(handled);
    ArgumentCaptor<BaseWebhookService> webhookCaptor = ArgumentCaptor.forClass(BaseWebhookService.class);
    verify(goWebhookBridge).handle(eq(request), webhookCaptor.capture());
    assertTrue(webhookCaptor.getValue() instanceof SFWindowAccessMap);
    verify(servlet).writeResponse(response, payload);
  }

  @Test
  public void windowAccessMapRejectsNonGetMethod() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("windowaccessmap"), "DELETE", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Windowaccessmap endpoint only supports GET"));
    verify(goWebhookBridge, never()).handle(any(), any());
  }

  @Test
  public void rolesOverviewGetDispatchesThroughBridgeWithSFRolesOverview() throws Exception {
    NeoResponse payload = NeoResponse.ok(new JSONObject());
    when(goWebhookBridge.handle(eq(request), any(BaseWebhookService.class))).thenReturn(payload);

    boolean handled = dispatcher.handle(pathInfo("rolesoverview"), "GET", request, response);

    assertTrue(handled);
    ArgumentCaptor<BaseWebhookService> webhookCaptor = ArgumentCaptor.forClass(BaseWebhookService.class);
    verify(goWebhookBridge).handle(eq(request), webhookCaptor.capture());
    assertTrue(webhookCaptor.getValue() instanceof SFRolesOverview);
    verify(servlet).writeResponse(response, payload);
  }

  @Test
  public void rolesOverviewRejectsNonGetMethod() throws Exception {
    boolean handled = dispatcher.handle(pathInfo("rolesoverview"), "PUT", request, response);

    assertTrue(handled);
    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED),
        eq("Rolesoverview endpoint only supports GET"));
    verify(goWebhookBridge, never()).handle(any(), any());
  }
}
