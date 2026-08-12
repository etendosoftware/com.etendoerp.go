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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.etendoerp.go.schemaforge.webhooks.SFAssignUserRoles;
import com.etendoerp.go.schemaforge.webhooks.SFListMenu;
import com.etendoerp.go.schemaforge.webhooks.SFRolesOverview;
import com.etendoerp.go.schemaforge.webhooks.SFWindowAccessMap;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Dispatches NEO's global pseudo-specs — endpoints that bypass ETGO_SF_SPEC/ETGO_SF_ENTITY
 * resolution entirely (batch, simsearch, and Etendo GO's own webhook-bridge endpoints).
 *
 * <p>Extracted out of {@link NeoServlet#processRequest} the same way {@link
 * NeoBuiltInEndpointHandler} already is: {@code batchService}/{@code simSearchEndpoint}/
 * {@code goWebhookBridge} are constructor-injected (mirroring {@code NeoBuiltInEndpointHandler}'s
 * own shape) so this dispatch logic is unit-testable against a mocked {@link NeoServlet} without
 * ever constructing a real one — instantiating a real {@code NeoServlet} runs every collaborator's
 * constructor (DB/DAL-touching in several cases), which is not safe in a unit test.</p>
 */
class NeoPseudoSpecDispatcher {

  private final NeoServlet servlet;
  private final BatchService batchService;
  private final NeoSimSearchEndpoint simSearchEndpoint;
  private final NeoGoWebhookBridge goWebhookBridge;

  NeoPseudoSpecDispatcher(NeoServlet servlet, BatchService batchService,
      NeoSimSearchEndpoint simSearchEndpoint, NeoGoWebhookBridge goWebhookBridge) {
    this.servlet = servlet;
    this.batchService = batchService;
    this.simSearchEndpoint = simSearchEndpoint;
    this.goWebhookBridge = goWebhookBridge;
  }

  /**
   * Returns {@code true} once one of the pseudo-specs has handled the request (response already
   * written), so the caller knows to return without falling through to spec/entity resolution.
   */
  boolean handle(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Generic transactional batch endpoint: POST /sws/neo/batch
    //   Runs an ordered list of CRUD ops in one OBDal transaction with
    //   $ref:<opId> substitution between ops. Same primitive is consumed by
    //   the React UI (composite-document ingest) and external agents (MCP).
    //   Find-or-create logic stays with the caller — no per-window server code.
    if ("batch".equals(pathInfo.specName)) {
      return dispatchBatch(method, request, response);
    }

    // Global similarity-search endpoint: GET /sws/neo/simsearch
    //   Same trigram matching as the "SimSearch" webhook, reached through NEO's own
    //   JWT auth instead of the Webhooks module's per-role grant table. See
    //   NeoSimSearchEndpoint for the authorization-model rationale.
    if ("simsearch".equals(pathInfo.specName)) {
      return dispatchSimSearch(method, request, response);
    }

    // Etendo GO's own webhooks, reached through NEO's own JWT auth instead of the Webhooks
    // module's per-role SMFWHE_DEFINEDWEBHOOK_ROLE grant table (wiped by update.database — see
    // NeoGoWebhookBridge's class javadoc for the full rationale). A fixed, explicit allow-list,
    // never a generic "call any webhook by name" passthrough — bypassing the grant gate for a
    // third-party module's webhook is not this bridge's call to make.
    if ("listmenu".equals(pathInfo.specName)) {
      return dispatchGoWebhook("Listmenu", method, request, response, new SFListMenu());
    }
    if ("windowaccessmap".equals(pathInfo.specName)) {
      return dispatchGoWebhook("Windowaccessmap", method, request, response, new SFWindowAccessMap());
    }
    if ("rolesoverview".equals(pathInfo.specName)) {
      return dispatchGoWebhook("Rolesoverview", method, request, response, new SFRolesOverview());
    }
    // ETP-4852: compose a user's access from 1+ system-level template roles. See
    // SFAssignUserRoles's class javadoc for the full mechanism and response shape.
    if ("assignuserroles".equals(pathInfo.specName)) {
      return dispatchGoWebhook("Assignuserroles", method, request, response,
          new SFAssignUserRoles());
    }
    return false;
  }

  private boolean dispatchBatch(String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (!"POST".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Batch endpoint only supports POST");
      return true;
    }
    batchService.handle(request, response);
    return true;
  }

  private boolean dispatchSimSearch(String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Simsearch endpoint only supports GET");
      return true;
    }
    servlet.writeResponse(response, simSearchEndpoint.handle(request));
    return true;
  }

  /**
   * Dispatches one of Etendo GO's own webhooks through {@link #goWebhookBridge} instead of the
   * Webhooks module's HTTP path — see {@link NeoGoWebhookBridge}'s class javadoc for why.
   */
  private boolean dispatchGoWebhook(String endpointName, String method, HttpServletRequest request,
      HttpServletResponse response, BaseWebhookService webhook) throws IOException {
    if (!"GET".equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          endpointName + " endpoint only supports GET");
      return true;
    }
    servlet.writeResponse(response, goWebhookBridge.handle(request, webhook));
    return true;
  }
}
