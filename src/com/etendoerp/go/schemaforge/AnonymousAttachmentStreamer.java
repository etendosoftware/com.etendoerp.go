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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.client.application.attachment.AttachImplementation;
import org.openbravo.model.ad.utility.Attachment;

/**
 * Finds and streams a document's main attachment in a request that has <b>no Etendo session</b>:
 * the signed email download link ({@link NeoDocumentDownloadService}) and the customer portal's
 * {@code /documents/{kind}/{id}/pdf} (ETP-6209). Both callers authorize by their own credential (a
 * signed token, a portal link) bound to one client, never by an {@code OBContext}, so neither can
 * use the session-based attachment API:
 * <ul>
 * <li>{@code NeoAttachmentsHelper#handleGetMain} reads another tenant's attachment as "none" through
 * {@code TenantOwnership.isVisibleToCurrentTenant}, and an anonymous context is nobody's tenant;</li>
 * <li>{@code AttachImplementationManager#download} runs an organization check that ignores admin
 * mode (see {@link #resolveHandler}).</li>
 * </ul>
 * The lookup here is native SQL, which no client filter can blind, followed by an explicit check
 * that the attachment belongs to the caller's client: that check is the tenant boundary, so every
 * caller must go through {@link #findMainAttachmentOfClient}.
 *
 * <p>Generic by construction: no table, spec or entity is named here; the caller says which.
 */
public final class AnonymousAttachmentStreamer {

  private static final Logger log = LogManager.getLogger(AnonymousAttachmentStreamer.class);
  private static final String DEFAULT_FILE_NAME = "document.pdf";
  private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
  private static final String DEFAULT_ATTACH_METHOD = "Default";
  private static final String ERR_INVALID_FILE = "Stored document file is invalid";
  private static final String PROP_ATTACH_PATH = "attach.path";

  private AnonymousAttachmentStreamer() {
  }

  /**
   * The attachment marked as main for a record, by the header's {@code AD_Table.TableName}.
   *
   * @param tableName the header's {@code AD_Table.TableName}
   * @param recordId the record
   * @param clientId the client the attachment must belong to
   * @return the attachment, or {@code null} when none is marked, the table is unknown, or the
   *     attachment does not belong to {@code clientId}
   */
  public static Attachment findMainAttachmentOfClient(String tableName, String recordId,
      String clientId) {
    if (StringUtils.isAnyBlank(tableName, recordId, clientId)) {
      return null;
    }
    try {
      return findMainAttachmentOfClientByTableId(
          NeoAttachmentsHelper.resolveTableId(tableName), recordId, clientId);
    } catch (OBException e) {
      log.warn("Could not resolve the attachment table {}: {}", tableName, e.getMessage());
      return null;
    }
  }

  /**
   * As {@link #findMainAttachmentOfClient}, by {@code AD_Table_ID}.
   */
  static Attachment findMainAttachmentOfClientByTableId(String tableId, String recordId,
      String clientId) {
    Attachment attachment = NeoAttachmentsHelper.findMainAttachment(tableId, recordId);
    if (attachment == null || attachment.getClient() == null
        || !attachment.getClient().getId().equals(clientId)) {
      return null;
    }
    return attachment;
  }

  /**
   * Streams the attachment's file as the response body, or writes a plain-text 500 when the stored
   * file cannot be read.
   *
   * @param attachment an attachment already resolved and client-checked by the caller
   * @param response the response the file is written to
   * @param logContext what to name in the logs (e.g. {@code "spec=sales-invoice record=..."})
   * @return {@code true} when the file was written to the response
   * @throws IOException when the response cannot be written
   */
  public static boolean stream(Attachment attachment, HttpServletResponse response,
      String logContext) throws IOException {
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
      log.error("Could not resolve the stored file for {} attachment={}", logContext,
          attachment.getId(), e);
      writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERR_INVALID_FILE);
      return false;
    }
    try {
      if (!file.exists()) {
        log.error("Stored file missing on disk for {} attachment={}: {}", logContext,
            attachment.getId(), file.getPath());
        writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERR_INVALID_FILE);
        return false;
      }
      if (file.length() > MAX_DOWNLOAD_BYTES) {
        writePlainError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Stored document file is too large");
        return false;
      }
      byte[] fileData = Files.readAllBytes(file.toPath());
      response.setStatus(HttpServletResponse.SC_OK);
      NeoDownloadHeaders.apply(response,
          StringUtils.defaultIfBlank(attachment.getName(), DEFAULT_FILE_NAME));
      response.setContentLength(fileData.length);
      response.getOutputStream().write(fileData);
      response.getOutputStream().flush();
      return true;
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
   * one), so under an anonymous context it matches nothing, returns no error, and silently falls
   * back to the flat {@code <tableId>-<recordId>} layout. For any attachment uploaded by current
   * code — which always records a nested {@code path} — that directory does not exist and the
   * download fails as a missing file.
   * <p>
   * The re-query exists only to fetch a field of the very row the caller already holds, resolved
   * by native SQL, which no client filter can blind. So this reproduces the same two branches the
   * core would apply (recorded {@code path} when present, flat layout otherwise) while reading the
   * value instead of looking it up again.
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
   * The callers are anonymous by design, so their context never holds the document's
   * organization and the check always failed with {@code OBSecurityException} — surfaced to
   * the recipient as a misleading "invalid file" 500.
   * <p>
   * Authorization is the caller's credential, not the session, and
   * {@link #findMainAttachmentOfClient} already rejected an attachment of another client. The
   * handler layer is public API and carries no session assumptions, so it is called directly;
   * {@link #stream} owns the temp-file cleanup the manager would otherwise do.
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
    try {
      Files.delete(file.toPath());
    } catch (IOException e) {
      log.warn("Could not delete temporary attachment file {}: {}", file.getPath(), e.getMessage());
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

  static void writePlainError(HttpServletResponse response, int status, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("text/plain");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(message);
  }
}
