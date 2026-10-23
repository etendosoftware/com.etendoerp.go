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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.PendingUpload;
import com.etendoerp.go.schemaforge.NeoStagedDocuments.Staged;

/**
 * Stores the bytes of a link whose file is staged for data extraction (ETP-5715): the second leg of
 * {@code etendo_request_document_extraction_upload}, run by {@link NeoAttachmentUploadEndpoint}.
 *
 * <p>The file goes through the same policy as an attachment — 10 MB, extension, magic bytes — and, on
 * top of it, must be a PDF: that is the only kind the extraction reads. The document is kept under the
 * staging directory of {@link NeoStagedDocuments}, and nothing is written to the database.</p>
 */
final class NeoStagedUpload {

  /** The only extension a staged document may have. */
  static final String PDF_EXTENSION = ".pdf";

  private static final String KEY_NAME = "name";

  private NeoStagedUpload() {
  }

  /**
   * Whether a file name is a PDF's.
   *
   * @param fileName the name
   * @return {@code true} when it ends in {@value #PDF_EXTENSION}, in any case
   */
  static boolean isPdfName(String fileName) {
    return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(PDF_EXTENSION);
  }

  /**
   * Validates and keeps the file the request carries.
   *
   * @param ticket  the claimed link: who it runs as, which staged document it fills, the file name
   * @param request the PUT whose body is the raw file
   * @return 201 with the staged document id, or 400/403/500 — a 400 is the policy's own text
   * @throws IOException when the body cannot be read
   */
  static NeoResponse store(PendingUpload ticket, HttpServletRequest request) throws IOException {
    String name = ticket.getSubject().getFileName();
    String id = ticket.getSubject().getStagedDocumentId();
    String rejection = isPdfName(name) ? NeoAttachmentPolicy.validateMetadata(name,
        request.getContentLengthLong()) : "Only a PDF can be staged for extraction.";
    if (rejection != null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, rejection);
    }
    NeoStagedDocuments staged = NeoStagedDocuments.getInstance();
    Optional<Staged> found = staged.find(id, ticket.getSession());
    if (found.isEmpty()) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE);
    }
    Path directory = staged.directoryFor(id);
    File file = NeoAttachmentUploadEndpoint.copyBody(request, directory, name);
    rejection = NeoAttachmentPolicy.validateContent(name, file);
    if (rejection != null) {
      Files.deleteIfExists(file.toPath());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, rejection);
    }
    if (!staged.markStored(id, file.toPath())) {
      return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN,
          NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE);
    }
    return NeoResponse.created(stored(id, name, file.length()));
  }

  private static JSONObject stored(String id, String name, long size) {
    try {
      return new JSONObject().put(KEY_NAME, name).put("message", "uploaded")
          .put("stagedDocumentId", id).put("size", size);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
