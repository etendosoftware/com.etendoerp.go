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

package com.etendoerp.go.schemaforge.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoReportService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Static helpers for process and report spec execution.
 */
public final class NeoProcessReportHelper {

  private static final Logger log = LogManager.getLogger(NeoProcessReportHelper.class);

  private NeoProcessReportHelper() {
  }

  /**
   * Resolves the Java qualifier of the custom report handler associated with the given spec
   * by inspecting the Java qualifier declared on the first matching {@link SFEntity}.
   *
   * @param spec the {@link SFSpec} for which the report handler qualifier should be resolved
   * @return the non-blank Java qualifier string if one is configured, or {@code null} if no
   *         entity with a qualifier exists or an error occurs
   */
  public static String resolveReportHandlerQualifier(SFSpec spec) {
    try {
      OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      List<SFEntity> entities = criteria.list();
      for (SFEntity entity : entities) {
        String qualifier = entity.getJavaQualifier();
        if (StringUtils.isNotBlank(qualifier)) {
          return qualifier;
        }
      }
    } catch (Exception e) {
      log.warn("Error checking report handler qualifier for spec '{}': {}", spec.getName(),
          e.getMessage());
    }
    return null;
  }

  /**
   * Handles the execution of a process spec by resolving the linked AD_Process, reading the
   * JSON request body, invoking {@link NeoProcessService#executeProcess}, and writing the
   * result to the servlet response.
   *
   * @param spec     the {@link SFSpec} of type {@code P} to execute
   * @param request  the HTTP request whose body may contain JSON parameters for the process
   * @param response the HTTP servlet response to which the process result is written
   * @throws IOException if an I/O error occurs while reading the request or writing the response
   */
  public static void handleProcessSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = NeoAccessHelper.resolveProcess(spec);
      if (adProcess == null) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Process spec has no linked AD_Process");
        return;
      }
      JSONObject requestBody = null;
      String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (StringUtils.isNotBlank(bodyStr)) {
        requestBody = new JSONObject(bodyStr);
      }
      NeoResponse result = NeoProcessService.executeProcess(adProcess, requestBody);
      writeResponse(response, result);
    } catch (Exception e) {
      log.error("Error executing process spec '{}': {}", spec.getName(), e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Process execution error: " + e.getMessage());
    }
  }

  /**
   * Handles the generation and streaming of a report spec by resolving the linked AD_Process,
   * reading the export type and parameters from the request body, and writing the generated
   * report binary directly to the servlet response output stream.
   *
   * @param spec     the {@link SFSpec} of type {@code R} to execute
   * @param request  the HTTP request whose body may contain {@code exportType} and {@code params}
   * @param response the HTTP servlet response to which the report binary is streamed
   * @throws IOException if an I/O error occurs while reading the request or writing the response
   */
  public static void handleReportSpec(SFSpec spec, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    try {
      Process adProcess = NeoAccessHelper.resolveProcess(spec);
      if (adProcess == null) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report spec has no linked AD_Process");
        return;
      }
      String bodyStr = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      JSONObject body = StringUtils.isNotBlank(bodyStr)
          ? new JSONObject(bodyStr) : new JSONObject();
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
      log.error("Error generating report for spec '{}': {}", spec.getName(), e.getMessage(), e);
      if (!response.isCommitted()) {
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Report generation failed: " + e.getMessage());
      }
    }
  }

  private static void writeResponse(HttpServletResponse response, NeoResponse neoResponse)
      throws IOException {
    if (neoResponse == null) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }
    for (java.util.Map.Entry<String, String> header : neoResponse.getHeaders().entrySet()) {
      response.setHeader(header.getKey(), header.getValue());
    }
    response.setStatus(neoResponse.getHttpStatus());
    if (neoResponse.getBody() != null) {
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(neoResponse.getBody().toString());
    }
  }

  private static void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse errorResponse = NeoResponse.error(status, message);
    response.setStatus(errorResponse.getHttpStatus());
    if (errorResponse.getBody() != null) {
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(errorResponse.getBody().toString());
    }
  }
}
