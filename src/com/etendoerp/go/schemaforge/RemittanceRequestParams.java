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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

/**
 * ETP-6148 — the query string of a remittance header action (a REST {@code GET}), read where it
 * travels: the action context's query parameters when it carries them, otherwise the servlet
 * request. Split out of {@link RemittanceHeaderHandler}.
 */
final class RemittanceRequestParams {

  private RemittanceRequestParams() {
  }

  /**
   * The query parameters of an action request.
   *
   * @param context the action context; a non-empty query map wins
   * @param request the servlet request, read only when the context carries none
   * @param keys    the parameters to read off the servlet request
   * @return the parameters, possibly empty
   */
  static Map<String, String> of(NeoContext context, Supplier<HttpServletRequest> request,
      String... keys) {
    // An action context may carry an empty map rather than null; only a non-empty one is the
    // query string, otherwise read the servlet request.
    if (context.getQueryParams() != null && !context.getQueryParams().isEmpty()) {
      return context.getQueryParams();
    }
    Map<String, String> params = new HashMap<>();
    HttpServletRequest servletRequest = request.get();
    if (servletRequest != null) {
      for (String key : keys) {
        String value = servletRequest.getParameter(key);
        if (value != null) {
          params.put(key, value);
        }
      }
    }
    return params;
  }
}
