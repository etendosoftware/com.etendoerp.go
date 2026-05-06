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

import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.client.application.report.ReportingUtils;
import org.openbravo.client.application.report.ReportingUtils.ExportType;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;

/**
 * Service for generating Jasper reports via the Neo headless API.
 *
 * Handles report-type specs (specType = "R") by resolving the .jrxml template
 * from AD_Process, mapping JSON parameters to Java types, and delegating
 * to {@link ReportingUtils#exportJR} for rendering.
 */
public class NeoReportService {

  private static final Logger log = LogManager.getLogger(NeoReportService.class);

  private static final String[] SUPPORTED_FORMATS = { "PDF", "XLS", "XLSX", "HTML", "CSV" };

  private NeoReportService() {
  }

  /**
   * Metadata about a report output, resolved before writing to the output stream.
   */
  public static class ReportMetadata {
    private final String filename;
    private final String contentType;

    /**
     * Creates a ReportMetadata holding the resolved filename and content-type for a report output.
     *
     * @param filename    the resolved output filename (e.g. "report.pdf")
     * @param contentType the MIME type of the report output (e.g. "application/pdf")
     */
    public ReportMetadata(String filename, String contentType) {
      this.filename = filename;
      this.contentType = contentType;
    }

    public String getFilename() {
      return filename;
    }

    public String getContentType() {
      return contentType;
    }
  }

  /**
   * Resolve report metadata (filename and content type) without generating the report.
   *
   * @param process    the AD_Process with IsReport='Y'
   * @param exportType export format string (PDF, XLS, XLSX, HTML, CSV)
   * @return ReportMetadata with filename and content type
   */
  public static ReportMetadata resolveReportMetadata(Process process, String exportType) {
    validateReportProcess(process);
    ExportType expType = parseExportType(exportType);
    String extension = expType.getExtension();
    String contentType = mapContentType(expType);
    String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    String processNameKebab = toKebabCase(process.getName());
    String filename = processNameKebab + "-" + dateStr + "." + extension;
    return new ReportMetadata(filename, contentType);
  }

  /**
   * Generate a report and write it directly to the given output stream.
   *
   * @param process      the AD_Process with IsReport='Y'
   * @param params       JSON object with parameter values keyed by DB column name
   * @param exportType   export format string (PDF, XLS, XLSX, HTML, CSV)
   * @param outputStream the stream to write the report output to
   */
  public static void generateReport(Process process, JSONObject params,
      String exportType, OutputStream outputStream) {
    try {
      OBContext.setAdminMode();
      try {
        validateReportProcess(process);
        ExportType expType = parseExportType(exportType);

        // Resolve template path
        String sourcePath = OBPropertiesProvider.getInstance()
            .getOpenbravoProperties().getProperty("source.path");
        String templatePath = sourcePath + "/" + process.getJRTemplateName();

        // Map JSON params to Java types based on AD_Process_Para definitions
        Map<String, Object> jasperParams = mapParameters(process, params);

        // Generate report
        ReportingUtils.exportJR(templatePath, expType, jasperParams,
            outputStream, true, null, null, null);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error generating report for process {}", process.getName(), e);
      throw new RuntimeException("Report generation failed: " + e.getMessage(), e);
    }
  }

  /**
   * Describe a report: returns process metadata plus supported formats.
   *
   * @param process the AD_Process to describe
   * @return NeoResponse with report metadata
   */
  public static NeoResponse describeReport(Process process) {
    try {
      OBContext.setAdminMode();
      try {
        // Reuse the process describe logic
        NeoResponse processDescribe = NeoProcessService.describeProcess(process);
        if (processDescribe.getHttpStatus() != 200) {
          return processDescribe;
        }

        // Extend with report-specific fields
        JSONObject body = processDescribe.getBody();
        body.put("isReport", true);

        JSONArray formats = new JSONArray();
        for (String fmt : SUPPORTED_FORMATS) {
          formats.put(fmt);
        }
        body.put("supportedFormats", formats);

        return NeoResponse.ok(body);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error describing report {}", process.getName(), e);
      return NeoResponse.error(500, "Error describing report: " + e.getMessage());
    }
  }

  // ---- Validation ----

  /**
   * Validate that the given process is a report with a Jasper template configured.
   * Throws OBException with a clear message if validation fails.
   */
  private static void validateReportProcess(Process process) {
    if (!Boolean.TRUE.equals(process.isReport())) {
      throw new OBException(
          "Process '" + process.getName() + "' is not a report (AD_Process.IsReport is not 'Y')");
    }
    if (StringUtils.isBlank(process.getJRTemplateName())) {
      throw new OBException(
          "Process '" + process.getName()
              + "' has no Jasper template configured (AD_Process.JRName is null)");
    }
  }

  // ---- Parameter mapping ----

  /**
   * Map JSON parameters to Java types based on AD_Process_Para reference IDs.
   */
  private static Map<String, Object> mapParameters(Process process,
      JSONObject params) throws Exception {
    Map<String, Object> jasperParams = new HashMap<>();
    if (params == null) {
      return jasperParams;
    }

    // Build a lookup map: dbColumnName -> ProcessParameter
    Map<String, ProcessParameter> paramDefs = new HashMap<>();
    List<ProcessParameter> paramList = process.getADProcessParameterList();
    for (ProcessParameter param : paramList) {
      if (Boolean.TRUE.equals(param.isActive()) && param.getDBColumnName() != null) {
        paramDefs.put(param.getDBColumnName(), param);
      }
    }

    @SuppressWarnings("unchecked")
    Iterator<String> keys = params.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (params.isNull(key)) {
        continue;
      }

      Object rawValue = params.get(key);
      ProcessParameter paramDef = paramDefs.get(key);

      if (paramDef == null) {
        // Unknown parameter, pass as string
        jasperParams.put(key, rawValue.toString());
        continue;
      }

      String refId = paramDef.getReference() != null
          ? paramDef.getReference().getId() : null;
      jasperParams.put(key, convertValue(rawValue, refId));
    }

    return jasperParams;
  }

  /**
   * Convert a JSON value to the appropriate Java type based on AD_Reference_ID.
   */
  private static Object convertValue(Object rawValue, String refId) {
    if (rawValue == null || refId == null) {
      return rawValue != null ? rawValue.toString() : null;
    }

    String strValue = rawValue.toString();

    switch (refId) {
      case "15": // Date
      case "16": // DateTime
        try {
          // Try ISO format first, then Etendo format
          SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd");
          return isoFmt.parse(strValue);
        } catch (Exception e) {
          try {
            SimpleDateFormat dtFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return dtFmt.parse(strValue);
          } catch (Exception e2) {
            log.warn("Could not parse date value: {}", strValue);
            return strValue;
          }
        }

      case "11": // Integer
        try {
          return Long.parseLong(strValue);
        } catch (NumberFormatException e) {
          return strValue;
        }

      case "22": // Number
      case "12": // Amount
        try {
          return new BigDecimal(strValue);
        } catch (NumberFormatException e) {
          return strValue;
        }

      case "20": // Yes/No
        return "Y".equals(strValue) || "true".equalsIgnoreCase(strValue)
            || Boolean.TRUE.equals(rawValue);

      case "10": // String
      case "17": // List
      case "19": // TableDir
      case "18": // Table
      case "30": // Search
      default:
        return strValue;
    }
  }

  // ---- Utility methods ----

  /**
   * Parse export type string to ReportingUtils.ExportType enum.
   */
  private static ExportType parseExportType(String exportType) {
    if (exportType == null) {
      return ExportType.PDF;
    }
    try {
      return ExportType.valueOf(exportType.toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Unknown export type '{}', defaulting to PDF", exportType);
      return ExportType.PDF;
    }
  }

  /**
   * Map ExportType to HTTP content type.
   */
  private static String mapContentType(ExportType expType) {
    switch (expType) {
      case PDF:
        return "application/pdf";
      case XLS:
        return "application/vnd.ms-excel";
      case XLSX:
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case HTML:
        return "text/html";
      case CSV:
        return "text/csv";
      case TXT:
        return "text/plain";
      case XML:
        return "application/xml";
      default:
        return "application/octet-stream";
    }
  }

  /**
   * Convert a process name to kebab-case for filenames.
   */
  private static String toKebabCase(String name) {
    if (name == null) {
      return "report";
    }
    return name.toLowerCase()
        .trim()
        .replaceAll("[\\s_]+", "-")
        .replaceAll("[^a-z0-9-]", "")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
  }
}
