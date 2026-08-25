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
import org.openbravo.dal.core.OBContext;
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
 * <p><b>Tenant boundary of the TARGET user (fixed in REVIEW cycle 1, ETP-4852):</b> {@code
 * isAdminOrClientAdmin} answers "may this caller use this webhook at all" — it does NOT limit
 * WHICH {@code userId} a client-admin may target, and a per-tenant client-admin is treated the
 * same as the literal System Administrator by that check alone. So {@code currentRole} (already
 * resolved above) is passed straight through to {@link
 * UserRoleCompositionService#assignTemplateRoles(String, List, Role)}, which enforces that a
 * non-system {@code currentRole} may only target a {@code userId} in its OWN client — see that
 * method's javadoc.</p>
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

  /** The {@code responseVars} map key the NEO pseudo-spec bridge reads the JSON body from. */
  private static final String RESPONSE_VAR_RESULT = "result";

  private static final String FIELD_SUCCESS = "success";
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
      responseVars.put(RESPONSE_VAR_RESULT, WebhookFailureResponses.denied().toString());
      return;
    }

    String userId = StringUtils.trimToNull(parameter.get(PARAM_USER_ID));
    List<String> templateRoleIds = parseTemplateRoleIds(parameter.get(PARAM_TEMPLATE_ROLE_IDS));
    if (userId == null) {
      responseVars.put(RESPONSE_VAR_RESULT,
          WebhookFailureResponses.failure("Missing required parameter: " + PARAM_USER_ID)
              .toString());
      return;
    }

    // No admin-mode wrapping here: UserRoleCompositionService enters it itself, for its own
    // duration only — see that class's javadoc for why the innermost unit of work owns it.
    //
    // currentRole is passed through explicitly (not re-resolved ambiently inside the service)
    // so UserRoleCompositionService can enforce the caller's tenant boundary against userId —
    // isAdminOrClientAdmin above treats a per-tenant client-admin the same as the literal
    // System Administrator, so without this a client-admin for Tenant A could target any user
    // in Tenant B (REVIEW cycle 1 finding, ETP-4852). See
    // UserRoleCompositionService#enforceCallerClientBoundary.
    //
    // Same reasoning for the caller's own AD_User_ID (ETP-4830): resolved from the SAME
    // OBContext currentRole came from, still BEFORE admin mode, so
    // UserRoleCompositionService#enforceOwnerProtection can tell "the owner reassigning their
    // own roles" apart from "anyone else targeting the owner".
    String callerUserId = currentRole != null && OBContext.getOBContext() != null
        && OBContext.getOBContext().getUser() != null
        ? OBContext.getOBContext().getUser().getId() : null;
    try {
      UserRoleCompositionService.AssignmentResult result = new UserRoleCompositionService()
          .assignTemplateRoles(userId, templateRoleIds, currentRole, callerUserId);
      responseVars.put(RESPONSE_VAR_RESULT, success(result).toString());
    } catch (OBException e) {
      // Expected domain-validation rejection — see class javadoc for why this is a 200
      // success:false result, not the bridge's 500 error path.
      responseVars.put(RESPONSE_VAR_RESULT,
          WebhookFailureResponses.failure(e.getMessage()).toString());
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
