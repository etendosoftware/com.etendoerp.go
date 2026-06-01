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
import java.net.URLEncoder;
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

/**
 * Serves email document downloads from signed links.
 * <p>
 * Package-private by design: only NeoServlet exposes this behavior over HTTP.
 */
final class NeoDocumentDownloadService {

  private static final Logger log = LogManager.getLogger(NeoDocumentDownloadService.class);
  private static final String DEFAULT_FILE_NAME = "document.pdf";
  private static final String APPLICATION_PDF = "application/pdf";
  private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
  private static final int MAX_ENCODED_DOWNLOAD_CHARS = ((MAX_DOWNLOAD_BYTES + 2) / 3) * 4;

  private NeoDocumentDownloadService() {
  }

  static void handle(String token, HttpServletResponse response) throws IOException {
    Optional<Claims> claims = DocumentDownloadTokenService.validate(token);
    if (!claims.isPresent()) {
      writePlainError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or expired link");
      return;
    }
    Claims validated = claims.get();
    PreviewFile previewFile = NeoPreviewFileService.findPreviewFileForClient(
        validated.getClientId(), validated.getSpecName(), validated.getRecordId());
    if (previewFile == null || StringUtils.isBlank(previewFile.getFileData())) {
      writePlainError(response, HttpServletResponse.SC_NOT_FOUND, "Document file not found");
      return;
    }
    if (previewFile.getFileData().length() > MAX_ENCODED_DOWNLOAD_CHARS) {
      writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Stored document file is too large");
      return;
    }
    try {
      byte[] fileData = Base64.getDecoder().decode(previewFile.getFileData());
      if (fileData.length > MAX_DOWNLOAD_BYTES) {
        writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Stored document file is too large");
        return;
      }
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(StringUtils.defaultIfBlank(previewFile.getMIMEType(),
          APPLICATION_PDF));
      response.setHeader("Content-Disposition", contentDisposition(previewFile.getFileName()));
      response.setContentLength(fileData.length);
      response.getOutputStream().write(fileData);
      response.getOutputStream().flush();
    } catch (IllegalArgumentException e) {
      log.error("Stored preview file has invalid base64 data for spec={} record={}",
          validated.getSpecName(), validated.getRecordId(), e);
      writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Stored document file is invalid");
    }
  }

  private static String contentDisposition(String fileName) {
    String sanitized = sanitizeFileName(fileName);
    String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded;
  }

  private static String sanitizeFileName(String fileName) {
    String normalized = StringUtils.trimToNull(fileName);
    if (normalized == null) {
      return DEFAULT_FILE_NAME;
    }
    return normalized.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
  }

  private static void writePlainError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("text/plain");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(message);
  }
}
