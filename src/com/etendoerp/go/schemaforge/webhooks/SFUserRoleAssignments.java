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

import java.util.LinkedHashMap;
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
 * ETP-4906 — read-path companion to {@code SFAssignUserRoles} (ETP-4852): "which template roles
 * does user X currently have applied" — the one genuinely new backend surface the multi-role
 * assignment UI needed, everything else being reused as-is from {@code SFAssignUserRoles}/{@code
 * SFRolesOverview}/{@code SFListMenu}. All the actual resolution logic lives in {@link
 * UserRoleCompositionService#getAppliedTemplateRoleIds(String, Role)}/{@link
 * UserRoleCompositionService#getAppliedTemplateRoleIdsForClient(String)}; this class is only a
 * parameter-marshalling + access-gating shim, reached through the NEO pseudo-spec bridge (see
 * {@code docs/neo-headless.md} §4.10–4.11) rather than the Webhooks module's {@code
 * SMFWHE_DEFINEDWEBHOOK_ROLE} grant table — the same convention every other Etendo-GO-authored
 * webhook in this package follows.
 *
 * <p><b>Endpoint:</b> {@code GET /sws/neo/userroleassignments[?UserId=<id>]} — two modes:</p>
 * <ul>
 *   <li>No {@code UserId} → bulk mode, for the Users grid: every user of the caller's OWN client,
 *   mapped to their applied template role ids.</li>
 *   <li>{@code UserId=<id>} → single mode, for the user form: that one user's applied template
 *   role ids.</li>
 * </ul>
 *
 * <p><b>Access gate:</b> admin/client-admin only ({@link NeoAccessHelper#isAdminOrClientAdmin}),
 * checked BEFORE entering any per-request identity/lookup — same convention as {@code
 * SFAssignUserRoles}/{@code SFRolesOverview}. A request with no role, or a non-admin role, gets
 * this webhook's empty result shape, never a raw {@code 403} (this webhook family's "deny
 * silently, don't 403" convention — see {@code docs/neo-headless.md} §8c/§8d).</p>
 *
 * <p><b>Tenant boundary of the TARGET user (single mode only).</b> {@code isAdminOrClientAdmin}
 * answers "may this caller use this webhook at all" — it does NOT limit WHICH {@code userId} a
 * client-admin may target. So {@code currentRole} (already resolved above) is passed straight
 * through to {@link UserRoleCompositionService#getAppliedTemplateRoleIds(String, Role)}, which
 * enforces the exact same {@code enforceCallerClientBoundary} check {@code SFAssignUserRoles}'
 * write path uses — see that method's javadoc. Bulk mode needs no such check: it is always scoped
 * to {@code currentRole.getClient().getId()}, never a caller-supplied client id (no such
 * parameter exists), mirroring {@code SFRolesOverview}'s identical convention.</p>
 *
 * <p><b>Every expected domain rejection (unknown/missing user id, cross-tenant target) folds into
 * this webhook's own empty-result shape, never the bridge's generic {@code error}/{@code
 * 500}.</b> {@code NeoGoWebhookBridge} always maps {@code responseVars["error"]} to HTTP 500 —
 * correct for a genuine crash, misleading (and a same-tenant-vs-other-tenant information leak) for
 * an expected "that id doesn't resolve for you" rejection. This class catches {@link OBException}
 * (the type {@link UserRoleCompositionService} throws for exactly these cases) and folds it into
 * the same empty shape the access gate itself returns; only a genuinely unexpected {@link
 * RuntimeException} escapes to the bridge's {@code error}/{@code 500} path.</p>
 *
 * <pre>{@code
 * // Bulk mode (no UserId):
 * {"assignments": {"<userId>": ["<templateRoleId>", ...], ...}}
 * // Single mode (UserId=<id>):
 * {"userId": "...", "templateRoleIds": ["...", "..."]}
 * // Access denied / cross-tenant / unknown user — still HTTP 200, this webhook family's
 * // "deny silently" convention, shaped per the mode that was requested:
 * {"assignments": {}}                              // bulk mode
 * {"userId": "...", "templateRoleIds": []}          // single mode
 * }</pre>
 */
public class SFUserRoleAssignments extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFUserRoleAssignments.class);

  private static final String PARAM_USER_ID = "UserId";

  /** The {@code responseVars} map key the NEO pseudo-spec bridge reads the JSON body from. */
  private static final String RESPONSE_VAR_RESULT = "result";

  private static final String FIELD_ASSIGNMENTS = "assignments";
  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_TEMPLATE_ROLE_IDS = "templateRoleIds";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE any lookup — same convention as
    // SFAssignUserRoles/SFRolesOverview/SFListMenu: access decisions must always be made against
    // the role actually resolved for this request.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();
    String userId = StringUtils.trimToNull(parameter.get(PARAM_USER_ID));

    if (currentRole == null || !NeoAccessHelper.isAdminOrClientAdmin(currentRole)) {
      responseVars.put(RESPONSE_VAR_RESULT, deniedResult(userId).toString());
      return;
    }

    try {
      UserRoleCompositionService service = new UserRoleCompositionService();
      if (userId == null) {
        Map<String, List<String>> assignments =
            service.getAppliedTemplateRoleIdsForClient(currentRole.getClient().getId());
        responseVars.put(RESPONSE_VAR_RESULT, bulkResult(assignments).toString());
        return;
      }

      // Enforces the SAME tenant-boundary check SFAssignUserRoles' write path uses — see
      // UserRoleCompositionService#enforceCallerClientBoundary via
      // #getAppliedTemplateRoleIds(String, Role). currentRole is forwarded explicitly, never
      // re-resolved ambiently, for the exact same reason SFAssignUserRoles forwards its own.
      List<String> templateRoleIds = service.getAppliedTemplateRoleIds(userId, currentRole);
      responseVars.put(RESPONSE_VAR_RESULT, singleResult(userId, templateRoleIds).toString());
    } catch (OBException e) {
      // Expected domain rejection (unknown user id, cross-tenant target, …) — deny silently,
      // folded into this webhook's own empty-result shape rather than the bridge's error/500
      // path or a raw distinguishing message — see class javadoc for why (avoids leaking
      // "exists in another tenant" vs "doesn't exist at all").
      log.debug("SFUserRoleAssignments rejected for UserId={}: {}", userId, e.getMessage());
      responseVars.put(RESPONSE_VAR_RESULT, deniedResult(userId).toString());
    } catch (Exception e) {
      log.error("Unexpected error in SFUserRoleAssignments for UserId={}", userId, e);
      responseVars.put("error", e.getMessage());
    }
  }

  /**
   * Builds the "deny silently" empty result, shaped per the mode that was requested: the bulk
   * empty shape when no {@code UserId} was supplied, the single-mode empty shape otherwise.
   */
  private JSONObject deniedResult(String userId) {
    return userId == null ? bulkResult(new LinkedHashMap<>()) : singleResult(userId, List.of());
  }

  private JSONObject singleResult(String userId, List<String> templateRoleIds) {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_USER_ID, userId);
      result.put(FIELD_TEMPLATE_ROLE_IDS, new JSONArray(templateRoleIds));
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build single-user result", e);
    }
  }

  private JSONObject bulkResult(Map<String, List<String>> assignments) {
    try {
      JSONObject assignmentsJson = new JSONObject();
      for (Map.Entry<String, List<String>> entry : assignments.entrySet()) {
        assignmentsJson.put(entry.getKey(), new JSONArray(entry.getValue()));
      }
      JSONObject result = new JSONObject();
      result.put(FIELD_ASSIGNMENTS, assignmentsJson);
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build bulk assignments result", e);
    }
  }
}
