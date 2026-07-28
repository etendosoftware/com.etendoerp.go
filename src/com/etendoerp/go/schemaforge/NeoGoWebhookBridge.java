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

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Generic bridge that runs an Etendo GO-authored {@link BaseWebhookService}'s {@code get(...)}
 * logic through NEO Headless's own JWT authentication instead of the Webhooks module's HTTP
 * dispatch, the same way {@link NeoSimSearchEndpoint} already does for the "SimSearch" webhook.
 *
 * <p><b>Why this exists (2026-07-27):</b> the Webhooks module additionally requires a per-
 * {@code (webhook, role)} grant row in {@code SMFWHE_DEFINEDWEBHOOK_ROLE} before a request ever
 * reaches this class's {@code get()} method — provisioned by hand per role per environment. That
 * table is reset to its XML-only baseline by {@code update.database}, silently wiping every
 * tenant-specific grant a data-fix or onboarding step had inserted (see
 * {@code cli/src/data-fixes/sql/20260727T114306Z__R16-tenant-roles-and-webhook-access.sql} in
 * {@code etendo_schema_forge} for the corrective data-fix this problem previously required). A
 * request reaching NEO Headless (any {@code /sws/neo/*} path) only needs a valid bearer token —
 * no separate per-role grant — so routing our own webhooks through this bridge instead removes
 * the dependency on that wipeable table entirely. No security check is actually removed: each
 * webhook's own {@code get()} still enforces its real access rule internally (e.g.
 * {@code NeoAccessHelper.isAdminOrClientAdmin} in {@code SFListMenu}/{@code SFWindowAccessMap}/
 * {@code SFRolesOverview}), exactly as it does today when reached via {@code /webhooks/*}.</p>
 *
 * <p>Unlike {@link NeoSimSearchEndpoint} (which calls a bespoke static method because
 * {@code SimSearch} doesn't share its shape with anything else), every Etendo GO webhook bridged
 * through this class shares the exact same {@code BaseWebhookService.get(Map, Map)} contract —
 * {@code responseVars.put("result", jsonString)} on success, {@code responseVars.put("error",
 * message)} on failure — so one generic bridge serves all of them, including any added later.
 * Deliberately NOT a generic "call any webhook by name" passthrough: {@link NeoServlet} only
 * routes a fixed, explicit allow-list of Etendo-GO-authored classes here (see its
 * {@code processRequest} pseudo-spec branches) — bypassing the grant gate for a third-party
 * module's webhook is not this bridge's call to make.</p>
 *
 * <p>Reproduces the Webhooks module's own response envelope exactly (verified this session by
 * disassembling {@code WebhookServiceHandler.buildResponse} in {@code webhookevents-3.1.0.jar}):
 * a flat {@code {"result": "<value>"}} or {@code {"error": "<message>"}} JSON object built
 * directly from the {@code responseVars} map — so existing frontend callers only need their
 * request URL updated, never their response-parsing logic.</p>
 */
class NeoGoWebhookBridge {

  private static final Logger log = LogManager.getLogger(NeoGoWebhookBridge.class);

  private static final String RESULT = "result";
  private static final String ERROR = "error";

  private final NeoServlet servlet;

  NeoGoWebhookBridge(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Invokes {@code webhook.get(...)} with the request's query parameters and translates its
   * {@code responseVars} output into a {@link NeoResponse}, mirroring the Webhooks module's own
   * response shape (see class javadoc).
   */
  NeoResponse handle(HttpServletRequest request, BaseWebhookService webhook) {
    Map<String, String> params = servlet.extractQueryParams(request);
    Map<String, String> responseVars = new HashMap<>();
    try {
      webhook.get(params, responseVars);
    } catch (Exception e) {
      log.error("Error invoking {} via NEO webhook bridge", webhook.getClass().getSimpleName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }

    if (responseVars.containsKey(ERROR)) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, responseVars.get(ERROR));
    }

    try {
      JSONObject body = new JSONObject();
      body.put(RESULT, responseVars.get(RESULT));
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      log.error("Error building NEO bridge response for {}", webhook.getClass().getSimpleName(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}
