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
package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.rest.DebugInvitationBypassService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-4830 (item #4) — dev/QA-only webhook backing two actions that let a tester exercise the
 * invite-email flow (ETP-4830) and the frontend's pending-invitation pill states without a real
 * email round-trip. All actual account-provisioning / invitation-mutation logic lives in
 * {@link DebugInvitationBypassService} ({@code com.etendoerp.go.rest} package, so it can reuse
 * {@code EtendoGoJwtDalHelper}/{@code CompanyInvitationDalHelper} directly — both package-private
 * by design); this class is only a parameter-marshalling + access-gating shim, the same split
 * {@code SFAssignUserRoles} (shim) / {@code UserRoleCompositionService} (real logic) already uses.
 *
 * <p><b>Reached through the NEO pseudo-spec bridge</b> ({@code docs/neo-headless.md} §4.10–4.11),
 * not the Webhooks module's {@code SMFWHE_DEFINEDWEBHOOK_ROLE} grant table — same reasoning as
 * every other Etendo-GO-authored webhook on this bridge.</p>
 *
 * <p><b>GATED OFF BY DEFAULT — this is the security-critical part of this class.</b>
 * {@code NeoPseudoSpecDispatcher#dispatchDebugInvitationBypass} checks
 * {@code GoRuntimeProperties.readBoolean("etendo.go.debug.invitationBypass",
 * "ETGO_DEBUG_INVITATION_BYPASS", false)} BEFORE this class is even constructed — an environment
 * that has not explicitly opted in (its own local {@code Openbravo.properties}/env var) gets a
 * plain 404, zero DB access, zero writes. The {@link #get} method's own admin/client-admin role
 * check below is defense-in-depth only, never the real boundary: a valid NEO bearer token with an
 * admin role is reachable in production too, which is exactly why the flag — not a role check —
 * is required.</p>
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/debuginvitationbypass?Action=forceAccept&Email=...} or
 * {@code GET /sws/neo/debuginvitationbypass?Action=forceStatus&Email=...&Status=SENT} (a write
 * action reached as {@code GET} with query parameters — the same convention every other
 * Etendo-GO-authored configuration webhook already uses, e.g. {@code SFAssignUserRoles}).</p>
 *
 * <pre>{@code
 * // forceAccept success:
 * {"success": true, "email": "...", "accountId": "...", "accountCreated": true,
 *  "temporaryPassword": "...", "invitationId": "...", "invitationStatus": "ACCEPTED"}
 * // forceStatus success:
 * {"success": true, "invitationId": "...", "email": "...", "status": "SENT"}
 * // Validation failure or access denied (still HTTP 200, matching SFAssignUserRoles's own
 * // "don't 500 a validation rejection" convention):
 * {"success": false, "message": "..."}
 * }</pre>
 */
public class SFDebugInvitationBypass extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFDebugInvitationBypass.class);

  private static final String PARAM_ACTION = "Action";
  private static final String PARAM_EMAIL = "Email";
  private static final String PARAM_AD_USER_ID = "AdUserId";
  private static final String PARAM_NAME = "Name";
  private static final String PARAM_INVITATION_ID = "InvitationId";
  private static final String PARAM_STATUS = "Status";

  private static final String ACTION_FORCE_ACCEPT = "forceAccept";
  private static final String ACTION_FORCE_STATUS = "forceStatus";

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_MESSAGE = "message";

  private final DebugInvitationBypassService service;

  public SFDebugInvitationBypass() {
    this(new DebugInvitationBypassService());
  }

  SFDebugInvitationBypass(DebugInvitationBypassService service) {
    this.service = service;
  }

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Defense-in-depth only — see class javadoc for why the real gate is the caller-side flag
    // check, not this. Same convention as SFAssignUserRoles: capture the role BEFORE entering
    // admin mode (NeoServlet already wraps the whole dispatch in admin mode by the time this
    // runs, so resolveCurrentRole() here still reads the real caller role, not "Admin").
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put(RESPONSE_VAR_RESULT, denied().toString());
      return;
    }

    String action = StringUtils.trimToEmpty(parameter.get(PARAM_ACTION));
    try {
      JSONObject result;
      if (ACTION_FORCE_ACCEPT.equalsIgnoreCase(action)) {
        result = service.forceAccept(parameter.get(PARAM_EMAIL), parameter.get(PARAM_AD_USER_ID),
            parameter.get(PARAM_NAME));
      } else if (ACTION_FORCE_STATUS.equalsIgnoreCase(action)) {
        result = service.forceStatus(parameter.get(PARAM_INVITATION_ID), parameter.get(PARAM_EMAIL),
            parameter.get(PARAM_STATUS));
      } else {
        result = failure("Unknown or missing Action (expected forceAccept or forceStatus): "
            + action);
      }
      responseVars.put(RESPONSE_VAR_RESULT, result.toString());
    } catch (JSONException e) {
      log.error("Error building SFDebugInvitationBypass response for action {}", action, e);
      responseVars.put("error", e.getMessage());
    } catch (RuntimeException e) {
      log.error("Unexpected error in SFDebugInvitationBypass for action {}", action, e);
      responseVars.put("error", e.getMessage());
    }
  }

  private JSONObject denied() {
    return failure("Not authorized");
  }

  private JSONObject failure(String message) {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_SUCCESS, false);
      result.put(FIELD_MESSAGE, message != null ? message : "Request could not be completed");
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build failure result", e);
    }
  }
}
