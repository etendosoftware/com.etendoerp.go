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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.ad.utility.Attachment;

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

  // ETP-4315 — physical Attachment table (C_File) per window spec, mirroring
  // the frontend's WINDOW_ATTACHMENT_TABLE (documentEmailSend.js). A link for a
  // spec not listed here (or for a record whose "main" attachment was since
  // replaced) resolves to 404 rather than falling back to the retired
  // legacy preview-file cache (retired, ETP-4315 Phase 9).
  private static final Map<String, String> WINDOW_ATTACHMENT_TABLE = Map.of(
      "sales-invoice", "C_Invoice",
      "purchase-invoice", "C_Invoice",
      "sales-order", "C_Order",
      "purchase-order", "C_Order",
      "sales-quotation", "C_Order",
      "goods-shipment", "M_InOut",
      "return-to-vendor-shipment", "M_InOut",
      "return-material-receipt", "M_InOut");

  private NeoDocumentDownloadService() {
  }

  static void handle(String token, HttpServletResponse response) throws IOException {
    Optional<Claims> claims = DocumentDownloadTokenService.validate(token);
    if (!claims.isPresent()) {
      writePlainError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid or expired link");
      return;
    }
    Claims validated = claims.get();
    Attachment attachment = resolveMainAttachment(validated);
    if (attachment == null) {
      writePlainError(response, HttpServletResponse.SC_NOT_FOUND, "Document file not found");
      return;
    }
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      NeoAttachmentsHelper.getAttachManager().download(attachment.getId(), buffer);
      byte[] fileData = buffer.toByteArray();
      if (fileData.length > MAX_DOWNLOAD_BYTES) {
        writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Stored document file is too large");
        return;
      }
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(StringUtils.defaultIfBlank(attachment.getDataType(),
          APPLICATION_PDF));
      response.setHeader("Content-Disposition", contentDisposition(attachment.getName()));
      response.setContentLength(fileData.length);
      response.getOutputStream().write(fileData);
      response.getOutputStream().flush();
    } catch (OBException e) {
      log.error("Could not stream main attachment for spec={} record={}",
          validated.getSpecName(), validated.getRecordId(), e);
      writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Stored document file is invalid");
    }
  }

  /**
   * Resolves the attachment currently marked as "main" for the token's
   * (specName, recordId) — the same file the sidebar/preview would show —
   * or {@code null} if the spec is unmapped, the table is unknown, no
   * attachment is currently marked (e.g. it was replaced since the link was
   * sent), or the resolved attachment does not belong to the token's client.
   */
  private static Attachment resolveMainAttachment(Claims validated) {
    String tableName = WINDOW_ATTACHMENT_TABLE.get(validated.getSpecName());
    if (tableName == null) {
      return null;
    }
    try {
      String tableId = NeoAttachmentsHelper.resolveTableId(tableName);
      Attachment attachment = NeoAttachmentsHelper.findMainAttachment(tableId, validated.getRecordId());
      if (attachment == null || attachment.getClient() == null
          || !attachment.getClient().getId().equals(validated.getClientId())) {
        return null;
      }
      return attachment;
    } catch (OBException e) {
      log.warn("Could not resolve attachment table '{}' for spec={}: {}",
          tableName, validated.getSpecName(), e.getMessage());
      return null;
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
