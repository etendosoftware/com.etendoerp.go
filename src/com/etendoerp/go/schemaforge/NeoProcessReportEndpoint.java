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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Process spec endpoint collaborator for {@link NeoServlet}.
 * Handles POST requests against process-type specs by delegating to
 * {@link NeoProcessService}.
 *
 * <p>Report-type spec POST handling was removed per ETP-4255: Etendo Go/NEO/MCP
 * no longer execute Jasper/AD_Process reports. Report generation is NEO-native via
 * {@code NeoHandler} beans; non-callable report specs are served by
 * {@link NeoRequestRouter} with a stable {@code not_configured_for_report_generation}
 * status.</p>
 */
class NeoProcessReportEndpoint {

  private static final Logger log = LogManager.getLogger(NeoProcessReportEndpoint.class);

  private final NeoServlet servlet;

  NeoProcessReportEndpoint(NeoServlet servlet) {
    this.servlet = servlet;
  }

  void handleProcessSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = spec.getProcess();
      if (adProcess == null) {
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Process spec has no linked AD_Process");
        return;
      }

      JSONObject requestBody = NeoRequestBodyParser.parseOptionalJsonObject(
          NeoRequestBodyParser.readRequestBody(request));

      NeoResponse result = NeoProcessService.executeProcess(adProcess, requestBody);
      servlet.writeResponse(response, result);
    } catch (Exception e) {
      log.error("Error executing process spec '{}': {}", spec.getName(), e.getMessage(), e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Process execution error: " + e.getMessage());
    }
  }
}
