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
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5019 — webhook backing "promote an invited user to Admin" / "demote an Admin back to
 * their personal role". Same parameter-marshalling + access-gating shim pattern {@link
 * SFAssignUserRoles} already establishes for the sibling role-composition webhook; the actual
 * mechanism lives in {@link UserRoleCompositionService#promoteToAdmin(String, Role, String)} /
 * {@link UserRoleCompositionService#demoteFromAdmin(String, Role, String)}.
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/promoteuserrole?UserId=<id>&Mode=promote|demote}.</p>
 *
 * <p><b>Access gate:</b> admin/client-admin only ({@link
 * NeoAccessHelper#isAdminOrClientAdmin}) — same convention as {@code SFAssignUserRoles}. The
 * finer-grained "owner or current admin" + "target not the owner" rules are enforced inside
 * {@link UserRoleCompositionService}, not here.</p>
 *
 * <p><b>Response shape</b> — same "never surface a domain validation failure as the bridge's
 * generic 500" convention as {@code SFAssignUserRoles}: an {@link OBException} becomes a
 * {@code success:false} body (HTTP 200); only a genuinely unexpected exception escapes to the
 * bridge's {@code error} path.</p>
 */
public class SFPromoteUserRole extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFPromoteUserRole.class);

  private static final String PARAM_USER_ID = "UserId";
  private static final String PARAM_MODE = "Mode";
  private static final String MODE_PROMOTE = "promote";
  private static final String MODE_DEMOTE = "demote";

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_ROLE_ID = "roleId";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put(RESPONSE_VAR_RESULT, WebhookFailureResponses.denied().toString());
      return;
    }

    String userId = StringUtils.trimToNull(parameter.get(PARAM_USER_ID));
    String mode = StringUtils.trimToNull(parameter.get(PARAM_MODE));
    if (userId == null) {
      responseVars.put(RESPONSE_VAR_RESULT,
          WebhookFailureResponses.failure("Missing required parameter: " + PARAM_USER_ID)
              .toString());
      return;
    }
    if (!MODE_PROMOTE.equals(mode) && !MODE_DEMOTE.equals(mode)) {
      responseVars.put(RESPONSE_VAR_RESULT,
          WebhookFailureResponses.failure(
              "Missing or invalid " + PARAM_MODE + " (expected 'promote' or 'demote')")
              .toString());
      return;
    }

    String callerUserId = OBContext.getOBContext() != null
        && OBContext.getOBContext().getUser() != null
        ? OBContext.getOBContext().getUser().getId() : null;
    try {
      UserRoleCompositionService service = new UserRoleCompositionService();
      UserRoleCompositionService.AssignmentResult result = MODE_PROMOTE.equals(mode)
          ? service.promoteToAdmin(callerUserId, currentRole, userId)
          : service.demoteFromAdmin(callerUserId, currentRole, userId);
      responseVars.put(RESPONSE_VAR_RESULT, success(result).toString());
    } catch (OBException e) {
      responseVars.put(RESPONSE_VAR_RESULT,
          WebhookFailureResponses.failure(e.getMessage()).toString());
    } catch (Exception e) {
      log.error("Unexpected error in SFPromoteUserRole for user {}", userId, e);
      responseVars.put("error", e.getMessage());
    }
  }

  private JSONObject success(UserRoleCompositionService.AssignmentResult result) {
    try {
      JSONObject body = new JSONObject();
      body.put(FIELD_SUCCESS, true);
      body.put(FIELD_USER_ID, result.userId);
      body.put(FIELD_ROLE_ID, result.personalRoleId);
      return body;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build success result", e);
    }
  }
}
