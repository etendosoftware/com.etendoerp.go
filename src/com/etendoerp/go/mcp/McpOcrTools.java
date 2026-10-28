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

package com.etendoerp.go.mcp;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.NeoAttachmentPolicy;
import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadEndpoint;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.NeoStagedDocuments;
import com.etendoerp.go.schemaforge.NeoStagedDocuments.Staged;
import com.etendoerp.go.schemaforge.OcrLookupsRegistry;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.ocr.CopilotOcrServiceClient;
import com.etendoerp.go.schemaforge.ocr.OcrConfig;
import com.etendoerp.go.schemaforge.ocr.OcrDocType;
import com.etendoerp.go.schemaforge.ocr.OcrExtractionService;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrReviewAssembler;
import com.etendoerp.go.schemaforge.ocr.OcrUnavailableException;

/**
 * The two MCP tools that read a supplier document with the application's own extraction and hand the
 * result to the agent as a supervised review (ETP-5715): {@code etendo_request_document_extraction_upload}
 * and {@code etendo_extract_invoice_document} — the "Subir factura" and "Extraer y completar" of the
 * purchase-invoice window.
 *
 * <p><b>Supervision is the point.</b> Nothing here creates, changes or sends anything: the extraction
 * answers what was read and the decisions a person must take, and the agent creates the invoice only
 * after the user confirmed every one (the review's {@code supervision} block says so, and so do the
 * tool descriptions and the agent prompt). Text read from the document is untrusted: it is sanitized,
 * and the review keeps it under {@code documentData}.</p>
 *
 * <p><b>The bytes never pass through the conversation</b>, as for attachments: the first tool hands
 * back a single-use link, whoever holds the PDF PUTs it there ({@link NeoStagedDocuments} keeps it for
 * 60 minutes) and the second reads it. The same staged file is then attached to the invoice as its main
 * document ({@link McpStagedAttachment}), so it is uploaded once.</p>
 *
 * <p><b>Which specs support it is configuration, not code</b>: {@link OcrConfig}. The extraction runs
 * once per staged document — its result is cached — and costs a paid external model, so a retry, or a
 * recomputation for another vendor, does not read the PDF again.</p>
 */
final class McpOcrTools {

  /** Argument: the staged document an extraction or an attachment refers to. */
  static final String PARAM_STAGED_DOCUMENT_ID = "staged_document_id";
  /** Argument: the vendor the user chose, so the review is recomputed for it without reading the PDF. */
  static final String PARAM_VENDOR_ID = "vendor_id";

  private static final Logger log = LogManager.getLogger(McpOcrTools.class);

  /** Marks "the document was attached while this call waited for it"; never a real extraction. */
  private static final JSONObject CONSUMED = new JSONObject();
  private static final String KEY_STAGED_ID = "stagedDocumentId";
  /** Key of the real expiry of a stored staged document, reported by every extraction answer. */
  static final String KEY_STAGED_EXPIRES_AT = "stagedDocumentExpiresAt";
  /** Key of the expiry of the reservation, reported by the upload-link answer. */
  static final String KEY_RESERVATION_EXPIRES_AT = "reservationExpiresAt";
  private static final String HINT_RESTAGE =
      "Request a new upload link with " + McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD
          + " and upload the document again.";

  private McpOcrTools() {
  }

  /**
   * Runs one of the two tools.
   *
   * @param toolName {@link McpConstants#TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD} or
   *                 {@link McpConstants#TOOL_NEO_EXTRACT_INVOICE_DOCUMENT}
   * @param args     the tool arguments
   * @return the MCP tool result — an error content when the body is an error envelope
   * @throws JSONException when a body cannot be built
   */
  static JSONObject handle(String toolName, JSONObject args) throws JSONException {
    JSONObject body;
    try {
      body = McpConstants.TOOL_NEO_REQUEST_DOCUMENT_EXTRACTION_UPLOAD.equals(toolName)
          ? requestUpload(args) : extract(args);
    } catch (NeoAttachmentTarget.Refused e) {
      body = McpAttachmentTools.refusal(e);
    }
    return McpToolResponses.imageToolResult(body);
  }

  // ── etendo_request_document_extraction_upload ──────────────────────────────

  /**
   * Issues the single-use link a document (a PDF or a JPG, PNG or WEBP image) is uploaded to and
   * reserves the staged document it will fill.
   *
   * <p>The role must be able to write documents of the spec (that is what the invoice created from
   * the document needs), the spec must support extraction, and the file name must be a safe name
   * of a readable type: all of it is checked before the link exists.</p>
   */
  static JSONObject requestUpload(JSONObject args) throws JSONException {
    String specName = McpArgumentUtils.optionalString(args, McpConstants.PARAM_SPEC);
    JSONObject unsupported = unsupportedSpec(specName);
    if (unsupported != null) {
      return unsupported;
    }
    String name = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, McpAttachmentTools.PARAM_NAME));
    String problem = fileNameProblem(name);
    if (problem != null) {
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
          McpConstants.ERROR_VALIDATION, problem,
          "Pass the file name with its extension: a PDF or an image, e.g. factura-proveedor.pdf or "
              + "factura-proveedor.jpg.");
    }
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    SFEntity entity = McpAttachmentTools.rootEntityOf(spec, new JSONObject());
    NeoAttachmentTarget.requireWriteAccess(spec, entity);
    return issue(spec, entity, name);
  }

  /** Why the name cannot be a staged document's, or {@code null}: required, safe characters, a readable type. */
  private static String fileNameProblem(String name) {
    if (name == null) {
      return "'" + McpAttachmentTools.PARAM_NAME + "' is required.";
    }
    if (!NeoAttachmentPolicy.isOcrDocument(name)) {
      return "Only a PDF or an image can be read: the file name must end in one of "
          + String.join(", ", NeoAttachmentPolicy.ocrExtensions()) + ".";
    }
    String safe = McpAttachmentTools.fileNameProblem(name);
    return safe != null ? safe : NeoAttachmentPolicy.validateMetadata(name, 0L);
  }

  private static JSONObject issue(SFSpec spec, SFEntity entity, String name) throws JSONException {
    Session session = Session.of(OBContext.getOBContext());
    NeoStagedDocuments documents = NeoStagedDocuments.getInstance();
    String stagedId;
    try {
      stagedId = documents.reserve(session, spec.getName(), name);
    } catch (NeoStagedDocuments.TooManyStagedDocumentsException e) {
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
          McpConstants.ERROR_VALIDATION, e.getMessage(),
          "Wait for a staged document to expire (60 minutes) or use the ones already staged.");
    }
    NeoAttachmentUploadTickets tickets = NeoAttachmentUploadTickets.getInstance();
    String token;
    try {
      token = tickets.issue(session, NeoAttachmentUploadTickets.Subject.staged(spec.getId(),
          entity.getId(), name, stagedId));
    } catch (NeoAttachmentUploadTickets.TooManyPendingTicketsException e) {
      documents.discard(stagedId);
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
          McpConstants.ERROR_VALIDATION, e.getMessage(),
          "Upload the file for a link you already requested, or wait for one to expire.");
    }
    return uploadAnswer(spec.getName(), name, stagedId, tickets, token, documents, session);
  }

  private static JSONObject uploadAnswer(String specName, String name, String stagedId,
      NeoAttachmentUploadTickets tickets, String token, NeoStagedDocuments documents,
      Session session) throws JSONException {
    String uploadUrl = McpImageTools.publicUrl(
        McpImageTools.NEO_SERVLET_PATH + NeoAttachmentUploadEndpoint.PATH + token);
    JSONObject result = new JSONObject();
    result.put("uploadUrl", uploadUrl);
    result.put("expiresAt", tickets.peek(token)
        .map(ticket -> ticket.getExpiresAt().toString()).orElse(null));
    result.put(KEY_STAGED_ID, stagedId);
    result.put(KEY_RESERVATION_EXPIRES_AT, documents.find(stagedId, session)
        .map(staged -> staged.getExpiresAt().toString()).orElse(null));
    result.put("maxBytes", NeoAttachmentPolicy.MAX_SIZE_MB * 1024L * 1024L);
    result.put("allowedExtensions", String.join(",", NeoAttachmentPolicy.ocrExtensions()));
    result.put(McpConstants.PARAM_SPEC, specName);
    result.put(McpAttachmentTools.PARAM_NAME, name);
    result.put("curlExample", "curl -X PUT --upload-file ./<your-file> \"" + uploadUrl + "\"");
    result.put(McpConstants.KEY_HINT, "PUT the raw file bytes to uploadUrl — no auth header is "
        + "needed, the URL is the credential, and it works exactly once. The reservation "
        + "(reservationExpiresAt) lasts 10 minutes until the file arrives; once uploaded, the "
        + "document is kept 60 minutes from the upload, and every extraction response reports the "
        + "real " + KEY_STAGED_EXPIRES_AT + ". Then call "
        + McpConstants.TOOL_NEO_EXTRACT_INVOICE_DOCUMENT + " with stagedDocumentId. Do not read the file into this conversation. A rejected file (too large, "
        + "not a PDF or a JPG, PNG or WEBP image) does not burn the link: fix it and PUT it to the same URL.");
    return result;
  }

  // ── etendo_extract_invoice_document ────────────────────────────────────────

  /**
   * Reads the staged document (once) and answers the supervised review.
   *
   * <p>Read-only: nothing is created. The first call per staged document runs the extraction service
   * and caches the result on the document; later calls, with or without {@code vendor_id}, reuse it.</p>
   */
  static JSONObject extract(JSONObject args) throws JSONException {
    String specName = McpArgumentUtils.optionalString(args, McpConstants.PARAM_SPEC);
    JSONObject unsupported = unsupportedSpec(specName);
    if (unsupported != null) {
      return unsupported;
    }
    OcrDocType type = OcrConfig.get().forSpec(specName).orElseThrow();
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    NeoAttachmentTarget.requireWriteAccess(spec, McpAttachmentTools.rootEntityOf(spec,
        new JSONObject()));
    Staged staged = stagedOf(args);
    if (staged == null) {
      return notFound();
    }
    if (!staged.isStored()) {
      return notUploaded();
    }
    if (!specName.equals(staged.getSpecName())) {
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
          McpConstants.ERROR_VALIDATION, "The staged document was uploaded for another document "
              + "type.", HINT_RESTAGE);
    }
    return review(type, staged, StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, PARAM_VENDOR_ID)));
  }

  private static Staged stagedOf(JSONObject args) {
    String id = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, PARAM_STAGED_DOCUMENT_ID));
    return id == null ? null : NeoStagedDocuments.getInstance()
        .find(id, Session.of(OBContext.getOBContext())).orElse(null);
  }

  private static JSONObject review(OcrDocType type, Staged staged, String vendorId)
      throws JSONException {
    OcrLookups lookups = OcrLookupsRegistry.forResolver(type.getResolver()).orElse(null);
    if (lookups == null) {
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_SERVER_ERROR,
          McpConstants.ERROR_SERVER, "No lookups are configured for this document type.",
          "Tell the user; this is a configuration problem.");
    }
    OcrLookups.Party chosen = null;
    if (vendorId != null) {
      chosen = lookups.vendorById(vendorId).orElse(null);
      if (chosen == null) {
        return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
            McpConstants.ERROR_VALIDATION, "'" + PARAM_VENDOR_ID + "' is not a vendor this user can "
                + "use.", "Take the id from the vendor decision's candidates, or from etendo_list "
                    + "on the contacts, and ask again.");
      }
    }
    JSONObject extraction;
    try {
      extraction = extractionOf(type, staged);
    } catch (OcrUnavailableException e) {
      log.warn("OCR extraction unavailable: {}", e.getCause() == null ? "no cause"
          : e.getCause().getClass().getSimpleName());
      return unavailable();
    }
    if (extraction == CONSUMED) {
      return notFound();
    }
    return extraction == null ? nothingExtracted(type, staged) : assemble(type, staged,
        lookups, extraction, chosen);
  }

  /**
   * Builds the review. The paid reading is already cached on the staged document at this point, so
   * a failure here (a lookup, a shape nobody foresaw) answers a clean 500 and the next call
   * reassembles from the cache instead of reading, and paying for, the PDF again.
   */
  private static JSONObject assemble(OcrDocType type, Staged staged, OcrLookups lookups,
      JSONObject extraction, OcrLookups.Party chosen) throws JSONException {
    try {
      return new OcrReviewAssembler(type, lookups).assemble(staged.getId(), extraction, chosen)
          .put(KEY_STAGED_EXPIRES_AT, staged.getExpiresAt().toString());
    } catch (RuntimeException e) {
      log.error("OCR review could not be assembled: {}", e.getClass().getSimpleName());
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_SERVER_ERROR,
          McpConstants.ERROR_SERVER, "The document was read but its review could not be built.",
          "Nothing was created. The reading is kept: ask again (it is not read again) or tell "
              + "the user.");
    }
  }

  /** The extraction of the staged document: cached, or read now (once, even with concurrent calls). */
  private static JSONObject extractionOf(OcrDocType type, Staged staged)
      throws OcrUnavailableException, JSONException {
    staged.getLock().lock();
    try {
      if (staged.isConsumed()) {
        return CONSUMED;
      }
      if (staged.hasExtractionResult()) {
        return staged.getExtraction();
      }
      JSONObject read = new OcrExtractionService(new CopilotOcrServiceClient())
          .extract(staged.getFile().toFile(), type);
      if (read == null) {
        staged.cacheNothingExtracted();
      } else {
        staged.cacheExtraction(read);
      }
      return read;
    } finally {
      staged.getLock().unlock();
    }
  }

  private static JSONObject nothingExtracted(OcrDocType type, Staged staged) throws JSONException {
    return new JSONObject().put(McpConstants.PARAM_SPEC, type.getSpec())
        .put(KEY_STAGED_ID, staged.getId())
        .put(KEY_STAGED_EXPIRES_AT, staged.getExpiresAt().toString())
        .put("status", "blocked").put("readyToCreate", false)
        .put("blocking", new JSONArray().put(new JSONObject().put("code", "nothing_extracted")
            .put("message", "No usable data could be read from the document: it may be blank, "
                + "unreadable or not an invoice. Tell the user; to try again, upload the file again "
                + "(this staged document keeps this result).")))
        .put(McpConstants.KEY_HINT, HINT_RESTAGE);
  }

  private static JSONObject unavailable() throws JSONException {
    return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_SERVICE_UNAVAILABLE,
        McpConstants.ERROR_OCR_UNAVAILABLE, "The document reading service is not available right "
            + "now (it did not answer in time or could not be reached).",
        "Nothing was read and nothing was created. Tell the user and try again in a moment: the "
            + "staged document is kept for 60 minutes.");
  }

  // ── Shared ─────────────────────────────────────────────────────────────────

  /** A refusal for a spec that does not support extraction, or {@code null} when it does. */
  private static JSONObject unsupportedSpec(String specName) throws JSONException {
    if (specName != null && OcrConfig.get().forSpec(specName).isPresent()) {
      return null;
    }
    List<String> supported = OcrConfig.get().specs();
    return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
        McpConstants.ERROR_VALIDATION, "Reading a document is not available for this document type.",
        "Supported: " + String.join(", ", supported) + ".");
  }

  /** The staged document is not there, expired, or belongs to someone else: one answer for all. */
  static JSONObject notFound() throws JSONException {
    return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_NOT_FOUND,
        McpConstants.ERROR_NOT_FOUND, "The staged document does not exist, has expired (they are "
            + "kept for 60 minutes) or is not yours.", HINT_RESTAGE);
  }

  /** The staged document exists but its file has not arrived. */
  static JSONObject notUploaded() throws JSONException {
    return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_CONFLICT,
        McpConstants.ERROR_NOT_UPLOADED, "The file has not been uploaded to this staged document "
            + "yet.", "PUT the file to the uploadUrl you were given (it works once, for 10 "
                + "minutes), then ask again.");
  }
}
