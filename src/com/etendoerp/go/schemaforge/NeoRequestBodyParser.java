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
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * JSON request-body parsing helpers extracted from {@link NeoServlet}.
 * Move here: readRequestBody, parseOptionalJsonObject, parseJsonObjectOrEmpty,
 * parseJsonObject.
 */
public final class NeoRequestBodyParser {

  private NeoRequestBodyParser() {
  }

  static String readRequestBody(HttpServletRequest request) throws IOException {
    return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }

  static JSONObject parseOptionalJsonObject(String bodyStr) throws Exception {
    if (StringUtils.isBlank(bodyStr)) {
      return null;
    }
    return parseJsonObject(bodyStr);
  }

  static JSONObject parseJsonObjectOrEmpty(String bodyStr) throws Exception {
    JSONObject parsed = parseOptionalJsonObject(bodyStr);
    return parsed != null ? parsed : new JSONObject();
  }

  static JSONObject parseJsonObject(String bodyStr) throws Exception {
    return new JSONObject(bodyStr);
  }
}
