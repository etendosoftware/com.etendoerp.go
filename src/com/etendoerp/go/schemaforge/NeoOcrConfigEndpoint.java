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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;

import com.etendoerp.go.schemaforge.ocr.OcrConfig;
import com.etendoerp.go.schemaforge.ocr.OcrDocType;

/**
 * Serves {@code GET /sws/neo/ocrconfig?docType=<spec>} (ETP-5715): what the application's upload
 * panel asks the extraction tool — the question and the strict answer schema — read from the same
 * {@link OcrConfig} the MCP tools use, so the two cannot drift apart.
 *
 * <p>It reaches every authenticated NEO caller, like the other pseudo-specs: the question and the
 * schema describe how an invoice is read and carry no tenant data, and what the panel then does with
 * a document is gated by the window and the copilot service as before.</p>
 */
class NeoOcrConfigEndpoint {

  private static final Logger log = LogManager.getLogger(NeoOcrConfigEndpoint.class);
  private static final String PARAM_DOC_TYPE = "docType";

  /**
   * Handles the request.
   *
   * @param request the GET request
   * @return 200 with {@code {docType, toolName, question, schema}}, 400 without {@code docType},
   *     404 for a document type that has no OCR, 500 when the configuration is not packaged
   */
  NeoResponse handle(HttpServletRequest request) {
    String docType = request.getParameter(PARAM_DOC_TYPE);
    if (StringUtils.isBlank(docType)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing required parameter: " + PARAM_DOC_TYPE);
    }
    if (OcrConfig.get().isUnavailable()) {
      // A deployment fault (the resource is not packaged), not an unknown document type: 500, so
      // the application can tell "retry later" from "this type has no OCR".
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The OCR configuration is not available on this server");
    }
    OcrDocType type = OcrConfig.get().forSpec(docType).orElse(null);
    if (type == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "No OCR configuration for this document type");
    }
    try {
      return NeoResponse.ok(type.toClientJson());
    } catch (JSONException e) {
      log.error("The OCR configuration of a document type cannot be built: {}", e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The OCR configuration cannot be read");
    }
  }
}
