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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.roles.TemplateRoleWindowAccess;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-6047 — {@code POST /sws/neo/invoice-inbox/upload}: uploads 1 to {@value #MAX_FILES} files
 * (multipart, repeated part {@value #PART_NAME}) into the invoice inbox, one
 * {@code ETGO_INCOMING_DOCUMENT} row per accepted file.
 *
 * <p>This class only orchestrates. It is the <b>only toggle point</b> of
 * {@link InvoiceInboxFlag}: the flag is evaluated here, at request entry, before the multipart is
 * read and before any row is created. The per-file work, and the entity identity, live in
 * {@link InvoiceInboxIntake}.
 *
 * <p>Whole-request refusals use the NEO error envelope ({@link NeoErrorResponses#toResponse}) and
 * create nothing: {@code feature_disabled} (403), {@code access_denied} (403),
 * {@code not_multipart} (400), {@code org_not_allowed} (403), {@code request_too_large} (413),
 * {@code no_files} (400), {@code too_many_files} (400). Otherwise the answer is HTTP 200 with a
 * per-file result, so one bad file never costs the others: {@code {accepted, rejected, results}}.
 *
 * <p>The NEO built-in path runs in admin mode, so the DAL does not check the organization or the
 * window on its own: both are validated explicitly here.
 */
final class InvoiceInboxUploadEndpoint {

  /** The Invoice Inbox window ({@code AD_WINDOW}); the caller needs write access to it. */
  static final String WINDOW_ID = TemplateRoleWindowAccess.INVOICE_INBOX_WINDOW_ID;

  /** Most files one request may carry. */
  static final int MAX_FILES = NeoAttachmentPolicy.BULK_MAX_FILES;

  /** The multipart part name that carries the files. */
  static final String PART_NAME = "files";

  /** Whole-request limit in bytes (210 MB); owned by {@link NeoAttachmentPolicy}. */
  static final long MAX_REQUEST_BYTES = NeoAttachmentPolicy.MAX_REQUEST_BYTES;

  static final String CODE_FEATURE_DISABLED = "feature_disabled";
  static final String CODE_ACCESS_DENIED = "access_denied";
  static final String CODE_NOT_MULTIPART = "not_multipart";
  static final String CODE_ORG_NOT_ALLOWED = "org_not_allowed";
  static final String CODE_REQUEST_TOO_LARGE = "request_too_large";
  static final String CODE_NO_FILES = "no_files";
  static final String CODE_TOO_MANY_FILES = "too_many_files";

  private static final Logger log = LogManager.getLogger(InvoiceInboxUploadEndpoint.class);

  /** The organization "*": a document belongs to a real organization, never to it. */
  private static final String STAR_ORG = "0";

  private InvoiceInboxUploadEndpoint() {
  }

  /**
   * Runs the upload for the current request.
   *
   * @param request the multipart POST
   * @return the answer to write; never {@code null}
   */
  static NeoResponse handle(HttpServletRequest request) {
    // The flag is read FIRST, here and nowhere else, at request entry where the request user is
    // the account owner. The extraction worker (ETP-6048) must not re-evaluate it.
    if (!InvoiceInboxFlag.isEnabled()) {
      return refuse(403, CODE_FEATURE_DISABLED, InvoiceInboxMessages.DISABLED);
    }
    if (!NeoAccessHelper.hasWindowAccess(WINDOW_ID, "POST")) {
      return NeoErrorResponses.toResponse(403, CODE_ACCESS_DENIED,
          NeoAttachmentAuthorizer.ACCESS_DENIED, null);
    }
    String contentType = request.getContentType();
    if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
        .startsWith("multipart/")) {
      return refuse(400, CODE_NOT_MULTIPART, InvoiceInboxMessages.NOT_MULTIPART);
    }
    OBContext context = OBContext.getOBContext();
    String clientId = context.getCurrentClient().getId();
    String orgId = context.getCurrentOrganization().getId();
    if (!isWritableOrganization(context, orgId)) {
      return refuse(403, CODE_ORG_NOT_ALLOWED, InvoiceInboxMessages.ORG_NOT_ALLOWED);
    }
    if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
      // Declared size already over the limit: answer without reading the body.
      return requestTooLarge();
    }
    Collection<Part> parts;
    try {
      parts = request.getParts();
    } catch (IllegalStateException e) {
      // The container refuses a part above maxFileSize or a request above maxRequestSize.
      log.warn("Invoice inbox upload refused by the container limits: {}", e.getMessage());
      return requestTooLarge();
    } catch (IOException | ServletException e) {
      log.warn("Invoice inbox upload: the multipart body could not be read: {}", e.getMessage());
      return refuse(400, CODE_NOT_MULTIPART, InvoiceInboxMessages.NOT_MULTIPART);
    }
    List<Part> files = filesOf(parts);
    try {
      if (files.isEmpty()) {
        return NeoErrorResponses.toResponse(400, CODE_NO_FILES,
            InvoiceInboxMessages.text(InvoiceInboxMessages.NO_FILES, "max",
                String.valueOf(MAX_FILES)), List.of(InvoiceInboxMessages.NO_FILES));
      }
      if (files.size() > MAX_FILES) {
        return NeoErrorResponses.toResponse(400, CODE_TOO_MANY_FILES,
            InvoiceInboxMessages.text(InvoiceInboxMessages.TOO_MANY_FILES,
                "count", String.valueOf(files.size()), "max", String.valueOf(MAX_FILES)),
            List.of(InvoiceInboxMessages.TOO_MANY_FILES));
      }
      return process(files, clientId, orgId);
    } finally {
      discard(parts);
    }
  }

  /** Ingests every file, each in its own transaction, and builds the 200 answer. */
  private static NeoResponse process(List<Part> files, String clientId, String orgId) {
    List<InvoiceInboxIntake.Result> results = new ArrayList<>(files.size());
    for (Part part : files) {
      results.add(InvoiceInboxIntake.ingest(part, part.getSubmittedFileName(), clientId, orgId));
    }
    submitAccepted(results);
    try {
      JSONArray array = new JSONArray();
      int accepted = 0;
      for (InvoiceInboxIntake.Result result : results) {
        array.put(result.toJson());
        if (result.isAccepted()) {
          accepted++;
        }
      }
      JSONObject body = new JSONObject();
      body.put("accepted", accepted);
      body.put("rejected", results.size() - accepted);
      body.put("results", array);
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      // The rows are already committed: report it as a server error, the caller must read the inbox.
      log.error("Invoice inbox upload: the answer could not be built", e);
      return NeoResponse.error(500, "The upload was processed but the answer could not be built");
    }
  }

  /**
   * Hands the accepted rows to the background worker (ETP-6048). Every row is already committed, so
   * the worker finds it; a row that cannot be queued stays {@code PROCESSING} for the sweeper.
   */
  private static void submitAccepted(List<InvoiceInboxIntake.Result> results) {
    for (InvoiceInboxIntake.Result result : results) {
      if (result.isAccepted()) {
        submitQuietly(result.getIncomingDocumentId());
      }
    }
  }

  /** Queues one row; whatever goes wrong, the upload still answers and the sweeper takes the row. */
  private static void submitQuietly(String incomingDocumentId) {
    try {
      InvoiceInboxProcessor.submit(incomingDocumentId);
    } catch (Throwable t) { // NOSONAR the answer of a committed upload must never be lost
      log.warn("Invoice inbox upload: could not queue {}: {}", incomingDocumentId,
          t.getClass().getSimpleName());
    }
  }

  /** The parts that carry a file: named {@value #PART_NAME} and with a submitted file name. */
  private static List<Part> filesOf(Collection<Part> parts) {
    List<Part> files = new ArrayList<>();
    for (Part part : parts) {
      if (PART_NAME.equals(part.getName()) && StringUtils.isNotBlank(part.getSubmittedFileName())) {
        files.add(part);
      }
    }
    return files;
  }

  /** A real, writable organization of the role: never "*" and never one outside the role's scope. */
  private static boolean isWritableOrganization(OBContext context, String orgId) {
    return !STAR_ORG.equals(orgId) && context.getWritableOrganizations() != null
        && context.getWritableOrganizations().contains(orgId);
  }

  /** Releases the container's temporary files of the request's parts. */
  private static void discard(Collection<Part> parts) {
    for (Part part : parts) {
      try {
        part.delete();
      } catch (IOException | RuntimeException e) {
        log.debug("Invoice inbox upload: could not delete a multipart temp file: {}",
            e.getMessage());
      }
    }
  }

  private static NeoResponse requestTooLarge() {
    return NeoErrorResponses.toResponse(413, CODE_REQUEST_TOO_LARGE,
        InvoiceInboxMessages.text(InvoiceInboxMessages.REQUEST_TOO_LARGE,
            "maxFileMB", String.valueOf(NeoAttachmentPolicy.MAX_SIZE_MB),
            "maxRequestMB", String.valueOf(MAX_REQUEST_BYTES / (1024L * 1024L))),
        List.of(InvoiceInboxMessages.REQUEST_TOO_LARGE));
  }

  private static NeoResponse refuse(int status, String code, String messageKey) {
    return NeoErrorResponses.toResponse(status, code, InvoiceInboxMessages.text(messageKey),
        List.of(messageKey));
  }
}
