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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.data.PreviewFile;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;
import com.etendoerp.go.schemaforge.email.EmailSafetyStore;

/**
 * Serves email document downloads from signed links tied to successful email audit records.
 */
class NeoDocumentDownloadService {

  private static final Logger log = LogManager.getLogger(NeoDocumentDownloadService.class);
  private static final String DEFAULT_FILE_NAME = "document.pdf";

  private NeoDocumentDownloadService() {
  }

  static void handle(String token, EmailSafetyStore safetyStore, HttpServletResponse response)
      throws IOException {
    Optional<Claims> claims = DocumentDownloadTokenService.validate(token);
    if (!claims.isPresent()) {
      writePlainError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or expired link");
      return;
    }
    Claims validated = claims.get();
    if (!safetyStore.findSentAudit(validated.getContractName(),
        validated.getClientId(), validated.getIdempotencyKey()).isPresent()) {
      writePlainError(response, HttpServletResponse.SC_FORBIDDEN,
          "Download link is not active");
      return;
    }
    PreviewFile previewFile = NeoPreviewFileService.findPreviewFileForClient(
        validated.getClientId(), validated.getSpecName(), validated.getRecordId());
    if (previewFile == null || StringUtils.isBlank(previewFile.getFileData())) {
      writePlainError(response, HttpServletResponse.SC_NOT_FOUND, "Document file not found");
      return;
    }
    try {
      byte[] fileData = Base64.getDecoder().decode(previewFile.getFileData());
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(StringUtils.defaultIfBlank(previewFile.getMIMEType(),
          "application/pdf"));
      response.setHeader("Content-Disposition", "attachment; filename=\""
          + sanitizeFileName(previewFile.getFileName()) + "\"");
      response.getOutputStream().write(fileData);
    } catch (IllegalArgumentException e) {
      log.warn("Stored preview file has invalid base64 data for spec={} record={}",
          validated.getSpecName(), validated.getRecordId());
      writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Stored document file is invalid");
    }
  }

  private static String sanitizeFileName(String fileName) {
    String normalized = StringUtils.trimToNull(fileName);
    if (normalized == null) {
      return DEFAULT_FILE_NAME;
    }
    return normalized.replaceAll("[\\\\/\\r\\n\"]", "_");
  }

  private static void writePlainError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("text/plain");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(message);
  }
}
