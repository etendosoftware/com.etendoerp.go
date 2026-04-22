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

package com.etendoerp.go.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletResponse;

import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Shared HTTP response helpers for Etendo GO servlets.
 */
public class ServletResponseUtils {

  private static final Logger log = LogManager.getLogger(ServletResponseUtils.class);

  private ServletResponseUtils() {
  }

  /**
   * Writes a JSON error response of the form {@code {"error": message}} with the given status code.
   *
   * @param response the HTTP response to write to
   * @param status   the HTTP status code (4xx / 5xx)
   * @param message  the error message included in the body
   * @throws IOException if the response writer cannot be obtained or fails to write
   */
  public static void sendError(HttpServletResponse response, int status, String message) throws IOException {
    try {
      JSONObject body = new JSONObject();
      body.put("error", message);
      response.setStatus(status);
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(body.toString());
    } catch (JSONException ex) {
      log.error("Failed to write error response", ex);
    }
  }
}
