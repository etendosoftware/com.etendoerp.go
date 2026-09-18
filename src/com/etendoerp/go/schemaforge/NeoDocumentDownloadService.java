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

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.client.application.attachment.AttachImplementation;
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
  private static final String DEFAULT_ATTACH_METHOD = "Default";
  private static final String ERR_INVALID_FILE = "Stored document file is invalid";
  private static final String PROP_ATTACH_PATH = "attach.path";

  private static final String TABLE_C_INVOICE = "C_Invoice";
  private static final String TABLE_C_ORDER = "C_Order";
  private static final String TABLE_M_INOUT = "M_InOut";

  // ETP-4315 — physical Attachment table (C_File) per window spec, mirroring
  // the frontend's WINDOW_ATTACHMENT_TABLE (documentEmailSend.js). A link for a
  // spec not listed here (or for a record whose "main" attachment was since
  // replaced) resolves to 404 rather than falling back to the retired
  // legacy preview-file cache (retired, ETP-4315 Phase 9).
  private static final Map<String, String> WINDOW_ATTACHMENT_TABLE = Map.of(
      "sales-invoice", TABLE_C_INVOICE,
      "purchase-invoice", TABLE_C_INVOICE,
      "sales-order", TABLE_C_ORDER,
      "purchase-order", TABLE_C_ORDER,
      "sales-quotation", TABLE_C_ORDER,
      "goods-shipment", TABLE_M_INOUT,
      "return-to-vendor-shipment", TABLE_M_INOUT,
      "return-material-receipt", TABLE_M_INOUT);

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
    AttachImplementation handler = null;
    File file;
    try {
      if (attachment.getAttachmentConf() == null) {
        file = resolveDefaultStorageFile(attachment);
      } else {
        handler = resolveHandler(attachment);
        file = handler.downloadFile(attachment);
      }
    } catch (OBException e) {
      log.error("Could not resolve the stored file for spec={} record={} attachment={}",
          validated.getSpecName(), validated.getRecordId(), attachment.getId(), e);
      writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERR_INVALID_FILE);
      return;
    }
    try {
      if (!file.exists()) {
        log.error("Stored file missing on disk for spec={} record={} attachment={}: {}",
            validated.getSpecName(), validated.getRecordId(), attachment.getId(), file.getPath());
        writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERR_INVALID_FILE);
        return;
      }
      if (file.length() > MAX_DOWNLOAD_BYTES) {
        writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Stored document file is too large");
        return;
      }
      byte[] fileData = Files.readAllBytes(file.toPath());
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(StringUtils.defaultIfBlank(attachment.getDataType(),
          APPLICATION_PDF));
      response.setHeader("Content-Disposition", contentDisposition(attachment.getName()));
      response.setContentLength(fileData.length);
      response.getOutputStream().write(fileData);
      response.getOutputStream().flush();
    } finally {
      if (handler != null && handler.isTempFile()) {
        deleteTempFile(file);
      }
    }
  }

  /**
   * Resolves the on-disk file for an attachment stored by the default (filesystem) method,
   * without going through {@code CoreAttachImplementation#downloadFile}.
   * <p>
   * ETP-5304 — that method calls {@code getAttachmentDirectory}, which re-queries the attachment
   * row through an {@link org.openbravo.dal.service.OBCriteria} only to read back its
   * {@code path}. The criteria filters on {@code OBContext#getReadableClients()} (a filter admin
   * mode does not lift, and which that code never disables — it only disables the organization
   * one), so under this endpoint's anonymous context it matches nothing, returns no error, and
   * silently falls back to the flat {@code <tableId>-<recordId>} layout. For any attachment
   * uploaded by current code — which always records a nested {@code path} — that directory does
   * not exist and the download fails as a missing file.
   * <p>
   * The re-query exists only to fetch a field of the very row the caller already holds:
   * {@link #resolveMainAttachment} resolved it by native SQL, which no client filter can blind.
   * So this reproduces the same two branches the core would apply (recorded {@code path} when
   * present, flat layout otherwise) while reading the value instead of looking it up again.
   * <p>
   * Attachments bound to an explicit {@code AttachmentConf} keep going through their own
   * handler: a non-filesystem backend does not use this layout at all.
   */
  private static File resolveDefaultStorageFile(Attachment attachment) {
    String attachRoot = StringUtils.trimToNull(OBPropertiesProvider.getInstance()
        .getOpenbravoProperties().getProperty(PROP_ATTACH_PATH));
    if (attachRoot == null) {
      throw new OBException("Property '" + PROP_ATTACH_PATH + "' is not configured");
    }
    // Built lazily on purpose: StringUtils.defaultIfBlank would evaluate the fallback even
    // when a path is recorded (Java argument evaluation is eager), forcing a proxy load of
    // AD_Table on every download and turning a null table into an NPE — which escapes the
    // caller's catch, since that only covers OBException, and takes the servlet down
    // instead of answering the controlled 500.
    String recordedPath = StringUtils.trimToNull(attachment.getPath());
    String relativeDir = recordedPath != null ? recordedPath
        : attachment.getTable().getId() + "-" + attachment.getRecord();
    return new File(attachRoot + File.separator + relativeDir, attachment.getName());
  }

  /**
   * Resolves the storage handler for the attachment, deliberately bypassing
   * {@code AttachImplementationManager#download}.
   * <p>
   * ETP-5304 — that entry point calls {@code checkReadableAccess}, an organization check
   * that does <em>not</em> honour admin mode: {@code SecurityChecker} compares the record's
   * organization against {@code OBContext#getReadableOrganizations()} unconditionally, and
   * the manager re-enters admin mode itself, so no wrapping from this side can affect it.
   * This endpoint is anonymous by design, so its context never holds the document's
   * organization and the check always failed with {@code OBSecurityException} — surfaced to
   * the recipient as a misleading "invalid file" 500.
   * <p>
   * Authorization here is the signed token, not the session: the HMAC binds the link to one
   * client and record, and {@link #resolveMainAttachment} already rejects an attachment whose
   * client differs from the signed one. The handler layer is public API and carries no
   * session assumptions, so it is called directly; the caller owns the temp-file cleanup that
   * the manager would otherwise do (see {@link #deleteTempFile}).
   */
  private static AttachImplementation resolveHandler(Attachment attachment) {
    String method = attachment.getAttachmentConf() == null ? DEFAULT_ATTACH_METHOD
        : attachment.getAttachmentConf().getAttachmentMethod().getValue();
    AttachImplementation handler = NeoAttachmentsHelper.getAttachManager().getHandler(method);
    if (handler == null) {
      throw new OBException("No attachment handler registered for method '" + method + "'");
    }
    return handler;
  }

  /**
   * Mirrors {@code AttachImplementationManager#deleteTempFile} (private there): removes the
   * temporary copy a non-filesystem handler produced, plus its containing directory when the
   * handler created one outside the system temp dir. A failure here is logged, never fatal —
   * the file has already been served.
   */
  private static void deleteTempFile(File file) {
    Path parent = file.toPath().getParent();
    if (!file.delete()) {
      log.warn("Could not delete temporary attachment file {}", file.getPath());
    }
    if (parent == null || parent.equals(Paths.get(System.getProperty("java.io.tmpdir")))) {
      return;
    }
    try {
      Files.delete(parent);
    } catch (IOException e) {
      log.warn("Could not delete temporary attachment directory {}: {}", parent, e.getMessage());
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
