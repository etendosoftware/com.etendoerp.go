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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.Part;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * ETP-6047 — turns ONE uploaded file into one {@code ETGO_INCOMING_DOCUMENT} row in status
 * {@code PROCESSING} with the file attached as the row's main document, in a transaction of its
 * own. It holds the entity identity so the endpoint ({@link InvoiceInboxUploadEndpoint}) stays an
 * orchestrator.
 *
 * <p>Creating the {@code PROCESSING} row is the whole "enqueue": extraction and matching are the
 * work of a later story (ETP-6048). It never touches {@code C_Invoice}.
 *
 * <p>Every call is all-or-nothing for its file: the row and the attachment commit together or
 * neither does. A failing file never affects the files around it, because each call commits or
 * rolls back (and closes) the session itself.
 */
final class InvoiceInboxIntake {

  /** Rejection code: the extension is not in {@link #SUPPORTED_EXTENSIONS}. */
  static final String CODE_UNSUPPORTED_TYPE = "unsupported_type";
  /** Rejection code: the file has no bytes. */
  static final String CODE_EMPTY_FILE = "empty_file";
  /** Rejection code: the attachment policy refused the content (size, signature). */
  static final String CODE_INVALID_CONTENT = "invalid_content";
  /** Rejection code: the row or the attachment could not be stored. */
  static final String CODE_STORE_FAILED = "store_failed";

  /**
   * The inbox accepts a narrower set than the shared attachment policy: documents an extraction
   * service can read. Local on purpose, the shared policy is not touched.
   */
  // WEBP is excluded on purpose: the ticket specifies PDF, JPG and PNG only (the shared attachment
  // policy does accept WEBP, this set is deliberately narrower).
  static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png");

  /**
   * Longest file name kept, in UTF-8 BYTES: the filesystem limits a name segment to 255 bytes
   * (not characters), and the temp copy in {@code materialize} is named after it. 240 leaves
   * headroom, and 240 bytes always mean at most 240 characters, so the 255-character
   * {@code FILE_NAME} column is satisfied too.
   */
  static final int MAX_FILE_NAME_BYTES = 240;

  /** Longest extension kept when a long name is truncated; a longer "extension" is not one. */
  private static final int MAX_EXTENSION_LENGTH = 16;

  static final String SOURCE_UPLOAD = "UPLOAD";
  static final String TARGET_PURCHASE_INVOICE = "PURCHASE_INVOICE";
  static final String STATUS_PROCESSING = "PROCESSING";

  private static final Logger log = LogManager.getLogger(InvoiceInboxIntake.class);

  private InvoiceInboxIntake() {
  }

  /** The outcome for one file: accepted with the new row id, or rejected with a code and message. */
  static final class Result {
    private final String fileName;
    private final String incomingDocumentId;
    private final String code;
    private final String message;

    private Result(String fileName, String incomingDocumentId, String code, String message) {
      this.fileName = fileName;
      this.incomingDocumentId = incomingDocumentId;
      this.code = code;
      this.message = message;
    }

    static Result accepted(String fileName, String incomingDocumentId) {
      return new Result(fileName, incomingDocumentId, null, null);
    }

    static Result rejected(String fileName, String code, String message) {
      return new Result(fileName, null, code, message);
    }

    boolean isAccepted() {
      return incomingDocumentId != null;
    }

    String getCode() {
      return code;
    }

    String getMessage() {
      return message;
    }

    String getIncomingDocumentId() {
      return incomingDocumentId;
    }

    String getFileName() {
      return fileName;
    }

    /** The per-file entry of the response's {@code results} array. */
    JSONObject toJson() throws org.codehaus.jettison.json.JSONException {
      JSONObject json = new JSONObject();
      json.put("fileName", fileName);
      if (isAccepted()) {
        json.put("status", "accepted");
        json.put("incomingDocumentId", incomingDocumentId);
      } else {
        json.put("status", "rejected");
        json.put("code", code);
        json.put("message", message);
      }
      return json;
    }
  }

  /**
   * Ingests one file. Never throws: every failure becomes a rejected {@link Result}.
   *
   * <p>Only ids cross the iterations of the caller's loop: the client and organization are
   * re-read here by id, because the previous file's commit closed the session.
   *
   * @param part       the multipart part holding the bytes
   * @param submitted  the file name as submitted
   * @param clientId   the tenant of the request
   * @param orgId      the (already authorized) organization of the request
   * @return the outcome for this file
   */
  static Result ingest(Part part, String submitted, String clientId, String orgId) {
    String name = cleanFileName(submitted);
    if (!SUPPORTED_EXTENSIONS.contains(NeoAttachmentPolicy.extensionOf(name))) {
      return Result.rejected(name, CODE_UNSUPPORTED_TYPE, InvoiceInboxMessages.unsupportedType(name));
    }
    long size = part.getSize();
    if (size <= 0) {
      return Result.rejected(name, CODE_EMPTY_FILE, InvoiceInboxMessages.emptyFile(name));
    }
    AtomicReference<File> tempFile = new AtomicReference<>();
    String rowId = null;
    boolean storeStarted = false;
    try {
      IncomingDocument row = newRow(name, clientId, orgId);
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      rowId = row.getId();
      storeStarted = true;
      NeoResponse stored = NeoAttachmentStore.store(new NeoAttachmentUpload(
          IncomingDocument.TABLE_NAME, rowId, null, name, size,
          fileName -> materialize(part, fileName, tempFile), true));
      if (stored.getHttpStatus() >= 400) {
        OBDal.getInstance().rollbackAndClose();
        if (stored.getHttpStatus() >= 500) {
          // A 5xx can come after the core copied the bytes (main marker, lookup): see warnOrphan.
          warnOrphan(name, rowId, "the attachment store answered " + stored.getHttpStatus());
        }
        return rejectedByStore(name, stored);
      }
      OBDal.getInstance().commitAndClose();
      return Result.accepted(name, rowId);
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Invoice inbox: could not ingest '{}'", name, e);
      if (storeStarted) {
        warnOrphan(name, rowId, "ingestion failed: " + e.getClass().getSimpleName());
      }
      return Result.rejected(name, CODE_STORE_FAILED, InvoiceInboxMessages.storeFailed(name));
    } finally {
      NeoAttachmentsHelper.cleanupTempFile(tempFile.get());
    }
  }

  /**
   * The file name as the inbox stores, logs and answers with it. The name is user input:
   * directory components go ({@code NeoAttachmentPolicy.sanitizeFileName}, which keeps path
   * traversal out), then CR/LF and every other control character (log forging, header splitting),
   * and a name over {@value #MAX_FILE_NAME_BYTES} UTF-8 bytes is truncated keeping its extension
   * (kept only when the last dot is not the first character and the extension is at most
   * {@value #MAX_EXTENSION_LENGTH} characters). The cut falls on a code point boundary, so it never
   * splits a surrogate pair. Empty, {@code .} and {@code ..} names become {@code attachment}.
   */
  static String cleanFileName(String submitted) {
    String name = NeoAttachmentPolicy.sanitizeFileName(submitted)
        .replaceAll("[\\p{Cc}\\u2028\\u2029]", "").trim();
    if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
      return "attachment";
    }
    if (utf8Length(name) <= MAX_FILE_NAME_BYTES) {
      return name;
    }
    int dot = name.lastIndexOf('.');
    String extension = dot > 0 && name.length() - dot <= MAX_EXTENSION_LENGTH
        ? name.substring(dot) : "";
    String base = extension.isEmpty() ? name : name.substring(0, dot);
    return truncateToBytes(base, MAX_FILE_NAME_BYTES - utf8Length(extension)) + extension;
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  /** The longest prefix of {@code value} that fits {@code maxBytes} in UTF-8, cut between code points. */
  private static String truncateToBytes(String value, int maxBytes) {
    int bytes = 0;
    int end = 0;
    while (end < value.length()) {
      int codePoint = value.codePointAt(end);
      int next = end + Character.charCount(codePoint);
      bytes += utf8Length(value.substring(end, next));
      if (bytes > maxBytes) {
        break;
      }
      end = next;
    }
    return value.substring(0, end);
  }

  private static IncomingDocument newRow(String name, String clientId, String orgId) {
    IncomingDocument row = OBProvider.getInstance().get(IncomingDocument.class);
    row.setClient(OBDal.getInstance().get(Client.class, clientId));
    row.setOrganization(OBDal.getInstance().get(Organization.class, orgId));
    row.setActive(true);
    row.setFileName(name);
    row.setSource(SOURCE_UPLOAD);
    row.setTargetType(TARGET_PURCHASE_INVOICE);
    row.setStatus(STATUS_PROCESSING);
    row.setAdjusted(false);
    row.setReviewed(false);
    row.setProcessingAttempts(0L);
    return row;
  }

  /**
   * Logs that a failed file may have left its bytes in the attachments directory. The core copies
   * the file there during {@code NeoAttachmentStore.store}, so ANY failure after that point (the
   * main-document marker, the lookup of the new attachment, the commit) rolls the rows back but
   * not the file. No compensation is attempted (accepted risk); the log makes it findable.
   */
  private static void warnOrphan(String name, String rowId, String reason) {
    log.warn("Invoice inbox: '{}' (incoming document {}) failed after the upload may have reached "
        + "the attachments directory, a file may remain there: {}", name, rowId, reason);
  }

  private static Result rejectedByStore(String name, NeoResponse stored) {
    int status = stored.getHttpStatus();
    boolean contentProblem = status == 400 || status == 422;
    if (contentProblem) {
      String message = messageOf(stored);
      if (StringUtils.isNotBlank(message)) {
        return Result.rejected(name, CODE_INVALID_CONTENT, message);
      }
    }
    log.warn("Invoice inbox: the attachment store refused '{}' with status {}", name, status);
    return Result.rejected(name, CODE_STORE_FAILED, InvoiceInboxMessages.storeFailed(name));
  }

  private static String messageOf(NeoResponse response) {
    JSONObject body = response.getBody();
    JSONObject error = body == null ? null : body.optJSONObject("error");
    return error == null ? null : error.optString("message", null);
  }

  /**
   * Copies the part into a directory of its own, readable by its owner only (never the shared
   * temp dir), so two files with the same name cannot overwrite each other. A failed copy removes
   * what it created.
   */
  private static File materialize(Part part, String fileName, AtomicReference<File> holder)
      throws IOException {
    Path directory = NeoAttachmentUploadEndpoint.createOwnerOnlyTempDirectory();
    File file = new File(directory.toFile(), fileName);
    try {
      // Guard path traversal beyond the sanitized name: the file must sit directly in the directory.
      if (!directory.toFile().getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
        throw new IOException("Unsafe file name");
      }
      try (InputStream in = part.getInputStream()) {
        Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      holder.set(file);
      return file;
    } catch (IOException | RuntimeException e) {
      try {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(directory);
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }
}
