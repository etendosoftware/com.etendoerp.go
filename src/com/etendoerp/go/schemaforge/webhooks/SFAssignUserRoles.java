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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-4852 — webhook backing the reworked "assign roles to user" UI: instead of picking ONE
 * shared role, an admin now picks 1+ system-level template roles (Finance/Sales/Purchasing/
 * Inventory, or any future template) to COMPOSE. All the actual mechanism — personal-role
 * find-or-create, {@code AD_Role_Inheritance} reconciliation, {@code AD_User_Roles}/{@code
 * Default_Ad_Role_ID} sync — lives in {@link UserRoleCompositionService}; this class is only a
 * parameter-marshalling + access-gating shim, reached through the NEO pseudo-spec bridge (see
 * {@code docs/neo-headless.md} §4.10–4.11) rather than the Webhooks module's {@code
 * SMFWHE_DEFINEDWEBHOOK_ROLE} grant table, for the same reason {@code SFListMenu}/{@code
 * SFWindowAccessMap}/{@code SFRolesOverview} are.
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/assignuserroles?UserId=<id>&TemplateRoleIds=<id1,id2,...>}
 * (a write action reached as {@code GET} with query parameters — the same convention every other
 * Etendo-GO-authored configuration webhook already uses, e.g. {@code SFUpsertField}).</p>
 *
 * <p><b>Access gate:</b> admin/client-admin only ({@link NeoAccessHelper#isAdminOrClientAdmin}),
 * checked BEFORE entering admin mode — same convention as {@code SFRolesOverview}. A request
 * with no role, or a non-admin role, gets a {@code success:false} result without touching the
 * database, never a raw {@code 403} (this webhook family's own "deny silently, don't 403"
 * convention — see {@code docs/neo-headless.md} §8c).</p>
 *
 * <p><b>Response shape — deliberately NEVER surfaces a domain validation failure (bad user id,
 * bad/non-template role id, Admin role requested, …) as the bridge's generic {@code error}/{@code
 * 500}.</b> {@code NeoGoWebhookBridge} (package-private, {@code com.etendoerp.go.schemaforge})
 * always maps any {@code responseVars["error"]} to HTTP 500 — appropriate for a genuinely
 * unexpected failure, but
 * misleading for an expected "that id isn't a valid template" rejection. So this class catches
 * {@link OBException} (the validation-failure type {@link UserRoleCompositionService} throws)
 * itself and folds it into a {@code result} (HTTP 200) body with {@code success:false} + {@code
 * message}; only a genuinely unexpected {@link RuntimeException} escapes to the bridge's {@code
 * error}/{@code 500} path. Callers must branch on the body's own {@code success} flag, not the
 * HTTP status, to tell "rejected" apart from "crashed".</p>
 *
 * <pre>{@code
 * // Success:
 * {"success": true, "userId": "...", "personalRoleId": "...",
 *  "templateRoleIds": ["...", "..."], "added": 1, "removed": 0}
 * // Validation failure (still HTTP 200 — see class javadoc):
 * {"success": false, "message": "Role is not a template, cannot be composed: ..."}
 * // Access denied (still HTTP 200 — this webhook family's convention):
 * {"success": false, "message": "Not authorized"}
 * }</pre>
 */
public class SFAssignUserRoles extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFAssignUserRoles.class);

  private static final String PARAM_USER_ID = "UserId";
  private static final String PARAM_TEMPLATE_ROLE_IDS = "TemplateRoleIds";

  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_PERSONAL_ROLE_ID = "personalRoleId";
  private static final String FIELD_TEMPLATE_ROLE_IDS = "templateRoleIds";
  private static final String FIELD_ADDED = "added";
  private static final String FIELD_REMOVED = "removed";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode — same convention as
    // SFListMenu/SFWindowAccessMap/SFRolesOverview: access decisions must always be made
    // against the role actually resolved for this request.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put("result", denied().toString());
      return;
    }

    String userId = StringUtils.trimToNull(parameter.get(PARAM_USER_ID));
    List<String> templateRoleIds = parseTemplateRoleIds(parameter.get(PARAM_TEMPLATE_ROLE_IDS));
    if (userId == null) {
      responseVars.put("result", failure("Missing required parameter: " + PARAM_USER_ID).toString());
      return;
    }

    // No admin-mode wrapping here: UserRoleCompositionService enters it itself, for its own
    // duration only — see that class's javadoc for why the innermost unit of work owns it.
    try {
      UserRoleCompositionService.AssignmentResult result =
          new UserRoleCompositionService().assignTemplateRoles(userId, templateRoleIds);
      responseVars.put("result", success(result).toString());
    } catch (OBException e) {
      // Expected domain-validation rejection — see class javadoc for why this is a 200
      // success:false result, not the bridge's 500 error path.
      responseVars.put("result", failure(e.getMessage()).toString());
    } catch (Exception e) {
      log.error("Unexpected error in SFAssignUserRoles for user {}", userId, e);
      responseVars.put("error", e.getMessage());
    }
  }

  private List<String> parseTemplateRoleIds(String rawValue) {
    List<String> ids = new ArrayList<>();
    if (StringUtils.isBlank(rawValue)) {
      return ids;
    }
    for (String part : rawValue.split(",")) {
      String id = StringUtils.trimToNull(part);
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
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

  private JSONObject success(UserRoleCompositionService.AssignmentResult result) {
    try {
      JSONObject body = new JSONObject();
      body.put(FIELD_SUCCESS, true);
      body.put(FIELD_USER_ID, result.userId);
      body.put(FIELD_PERSONAL_ROLE_ID, result.personalRoleId);
      body.put(FIELD_TEMPLATE_ROLE_IDS, new JSONArray(result.appliedTemplateRoleIds));
      body.put(FIELD_ADDED, result.addedCount);
      body.put(FIELD_REMOVED, result.removedCount);
      return body;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build success result", e);
    }
  }
}
