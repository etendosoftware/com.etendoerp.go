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
import java.io.OutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Process and report spec endpoint collaborator for {@link NeoServlet}.
 * Handles POST requests against process-type and report-type specs by
 * delegating to {@link NeoProcessService} and {@link NeoReportService}.
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

  /**
   * Handle a report-type spec POST. Reads the request body for exportType and params,
   * resolves report metadata, sets response headers, then streams the report output.
   */
  void handleReportSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = spec.getProcess();
      if (adProcess == null) {
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report spec has no linked AD_Process");
        return;
      }

      JSONObject body = NeoRequestBodyParser.parseJsonObjectOrEmpty(
          NeoRequestBodyParser.readRequestBody(request));
      String exportType = body.optString("exportType", "PDF");
      JSONObject params = body.optJSONObject("params");
      if (params == null) {
        params = new JSONObject();
      }

      NeoReportService.ReportMetadata meta =
          NeoReportService.resolveReportMetadata(adProcess, exportType);

      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(meta.getContentType());
      response.setHeader("Content-Disposition",
          "attachment; filename=\"" + meta.getFilename() + "\"");

      OutputStream out = response.getOutputStream();
      NeoReportService.generateReport(adProcess, params, exportType, out);
      out.flush();

    } catch (Exception e) {
      log.error("Error generating report for spec '{}': {}",
          spec.getName(), e.getMessage(), e);
      if (!response.isCommitted()) {
        servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report generation failed: " + e.getMessage());
      }
    }
  }
}
