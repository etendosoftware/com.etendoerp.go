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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.roles.RoleWriteConflicts;

/**
 * Shared {@code {"success": false, "message": "..."}} response builder — extracted from the
 * identical private {@code denied()}/{@code failure(String)} pair previously duplicated verbatim
 * across {@link SFAssignUserRoles} and {@link SFDebugInvitationBypass} (SonarQube duplication
 * finding, ETP-4830). Both webhooks use the same "deny silently, don't 403 / don't 500 a
 * validation rejection" convention (see either class's own javadoc), so both need the exact same
 * {@code success:false} shape.
 *
 * <p>Deliberately narrow: only the "not authorized" / generic validation-failure shape is shared
 * here. Each webhook's own success-response builder stays private to that class, since the shape
 * of a successful response is genuinely different per webhook.
 */
final class WebhookFailureResponses {

  private static final Logger log = LogManager.getLogger(WebhookFailureResponses.class);

  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_CODE = "code";

  private WebhookFailureResponses() {
    // static utility
  }

  /**
   * @param message
   *          the failure message to report, or {@code null} to fall back to a generic message
   * @return a {@code {"success": false, "message": "..."}} JSON body
   */
  static JSONObject failure(String message) {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_SUCCESS, false);
      result.put(FIELD_MESSAGE, message != null ? message : "Request could not be completed");
      return result;
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build failure result", e);
    }
  }

  /**
   * ETP-5278 — same shape as {@link #failure(String)} plus a machine-readable {@code code}, so the
   * frontend can pick a translated message instead of showing raw backend text (ETP-5206).
   */
  static JSONObject failure(String message, String code) {
    try {
      return failure(message).put(FIELD_CODE, code);
    } catch (JSONException e) {
      throw new IllegalStateException("Unable to build failure result", e);
    }
  }

  /**
   * ETP-5278 — the failure body for a role-composition write that lost a race against another
   * write on the same user (see {@code RoleWriteConflicts}).
   */
  static JSONObject concurrentModification() {
    return failure("The user's roles were changed by another request at the same time",
        RoleWriteConflicts.CODE);
  }

  /**
   * ETP-5278 — shared failure handling for the role-composition write webhooks ({@code
   * SFAssignUserRoles}, {@code SFPromoteUserRole}): when {@code error} is a write that lost a
   * race against another write on the same user ({@link RoleWriteConflicts}), rolls the
   * transaction back and answers {@link #concurrentModification()} under {@code resultVar}.
   *
   * @return {@code true} if {@code error} was handled here; {@code false} leaves it to the caller
   */
  static boolean rejectConcurrentRoleWrite(Throwable error, Map<String, String> responseVars,
      String resultVar, String webhookName, String userId) {
    if (!RoleWriteConflicts.isConcurrencyFailure(error)) {
      return false;
    }
    log.warn("Concurrent role write rejected in {} for user {}", webhookName, userId, error);
    OBDal.getInstance().rollbackAndClose();
    responseVars.put(resultVar, concurrentModification().toString());
    return true;
  }

  /**
   * @return the standard "Not authorized" failure body shared by {@link SFAssignUserRoles} and
   *         {@link SFDebugInvitationBypass} — NOT a package-wide contract; {@code
   *         SFResendInvitation} deliberately uses a different-shaped denied response
   */
  static JSONObject denied() {
    return failure("Not authorized");
  }
}
