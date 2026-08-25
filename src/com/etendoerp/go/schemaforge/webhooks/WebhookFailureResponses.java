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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

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

  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_MESSAGE = "message";

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
   * @return the standard "Not authorized" failure body used by every access-gated webhook in this
   *         package
   */
  static JSONObject denied() {
    return failure("Not authorized");
  }
}
