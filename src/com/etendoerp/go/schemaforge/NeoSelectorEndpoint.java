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

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;

/**
 * Selector endpoint collaborator for {@link NeoServlet}. Lists available
 * selectors for an entity, or queries a specific selector with paging and
 * context parameters extracted from the request.
 */
class NeoSelectorEndpoint {

  NeoResponse handleSelector(String specId,
      NeoPathInfo pathInfo, HttpServletRequest request) {
    if (pathInfo.selectorField == null) {
      // List all available selectors
      return NeoSelectorService.listSelectors(specId, pathInfo.entityName);
    }
    // Query a specific selector
    String search = request.getParameter("q");
    int limit = 20;
    int offset = 0;
    try {
      limit = Integer.parseInt(request.getParameter("limit"));
    } catch (NumberFormatException ignored) {
      // Use default limit if the parameter is absent or not a valid integer
    }
    try {
      offset = Integer.parseInt(request.getParameter("offset"));
    } catch (NumberFormatException ignored) {
      // Use default offset if the parameter is absent or not a valid integer
    }

    // Collect context params (all query params except q, limit, offset)
    Map<String, String> contextParams = new HashMap<>();
    Enumeration<String> paramNames = request.getParameterNames();
    while (paramNames.hasMoreElements()) {
      String paramName = paramNames.nextElement();
      if (!"q".equals(paramName) && !"limit".equals(paramName)
          && !"offset".equals(paramName)) {
        contextParams.put(paramName, request.getParameter(paramName));
      }
    }

    return NeoSelectorService.querySelector(
        specId, pathInfo.entityName, pathInfo.selectorField,
        search, limit, offset, contextParams);
  }
}
