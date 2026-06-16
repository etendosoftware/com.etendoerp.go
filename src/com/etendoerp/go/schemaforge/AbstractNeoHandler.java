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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * Shared base for {@link NeoHandler} implementations, factoring out the boilerplate
 * common to every handler: HTTP write-method detection, lenient JSON field reading and
 * admin-mode toggling. The admin-mode hooks are instance methods (not static) so unit
 * tests can stub them on a Mockito spy without touching {@code OBContext}.
 */
abstract class AbstractNeoHandler implements NeoHandler {

  protected static final String METHOD_POST = "POST";
  protected static final String METHOD_PUT = "PUT";
  protected static final String METHOD_PATCH = "PATCH";

  /** True for the HTTP verbs that mutate state (POST / PUT / PATCH). */
  protected boolean isWriteMethod(String method) {
    return METHOD_POST.equals(method) || METHOD_PUT.equals(method) || METHOD_PATCH.equals(method);
  }

  /**
   * Reads a trimmed string field, treating absent, JSON-null and blank as {@code null}
   * (Jettison's {@code optString} otherwise returns the literal {@code "null"} for a JSON null).
   */
  protected static String optTrimmed(JSONObject body, String key) {
    if (body == null || !body.has(key) || body.isNull(key)) {
      return null;
    }
    return StringUtils.trimToNull(body.optString(key, ""));
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }
}
