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

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoDocumentPdf;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * {@code etendo_get_document_pdf} (ETP-5715): the PDF the application ALREADY stored for a document,
 * as metadata plus a short-lived signed link — what "download the PDF" does in the app, for one
 * document or for up to {@value #MAX_DOCUMENTS}.
 *
 * <p><b>It never renders.</b> The application produces a document's PDF in the browser when its
 * preview is opened and stores it as the record's main attachment; the MCP hands out that file, or
 * says there is none. {@link NeoDocumentPdf} decides which file and whether it is still current; the
 * document is authorized, per id, by {@link NeoAttachmentTarget} (read tier: the role's access to the
 * spec, the client and readable organization of the record, the same 404 for a missing and a
 * foreign one, and the 405 of the windows the application gives no Attachments tab). The link is
 * piece A's attachment token ({@link McpAttachmentTools#downloadUrl}), so downloading is the
 * existing endpoint and the bytes never cross the conversation.</p>
 *
 * <p><b>Two distinct "no PDF" answers, both 409</b> — not 404, which is reserved for "that document
 * does not exist or is not yours", so an agent can tell them apart by status alone:
 * {@code no_pdf} (nothing stored) and {@code pdf_stale} (stored, but older than the document).</p>
 */
final class McpDocumentPdfTool {

  /** Argument: several document ids of one spec. */
  static final String PARAM_IDS = "ids";
  /** Most documents one call answers. */
  static final int MAX_DOCUMENTS = 50;

  private static final Logger log = LogManager.getLogger(McpDocumentPdfTool.class);

  private static final String KEY_FILE = "file";
  private static final String KEY_OK = "ok";
  private static final String KEY_DOWNLOAD_URL = "downloadUrl";
  private static final String KEY_ERROR_BODY = "error";

  private static final String MSG_NO_SECRET = "Download links are not available: the signing secret "
      + "for document links is not configured on this server.";

  private McpDocumentPdfTool() {
  }

  /**
   * Runs the tool.
   *
   * @param args the tool arguments
   * @return the MCP tool result — an error content when the body is an error envelope
   * @throws JSONException when a body cannot be built
   */
  static JSONObject handle(JSONObject args) throws JSONException {
    return McpToolResponses.imageToolResult(get(args));
  }

  /**
   * Answers one document or a list of them.
   *
   * @param args {@code spec}, then {@code id} or {@code ids}, optionally {@code entity}
   * @return the answer body (an error envelope when it fails as a whole)
   * @throws JSONException when a body cannot be built
   */
  static JSONObject get(JSONObject args) throws JSONException {
    String id = StringUtils.trimToNull(McpArgumentUtils.optionalString(args, McpConstants.PARAM_ID));
    Object rawIds = args.opt(PARAM_IDS);
    String problem = argumentProblem(id, rawIds);
    if (problem != null) {
      return error(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION, problem,
          "Pass 'id' for one document or 'ids' (a list of at most " + MAX_DOCUMENTS + ").");
    }
    try {
      SFSpec spec = McpToolRouterSupport.findActiveSpecByName(
          McpArgumentUtils.optionalString(args, McpConstants.PARAM_SPEC));
      SFEntity entity = McpAttachmentTools.rootEntityOf(spec, args);
      String specName = McpArgumentUtils.optionalString(args, McpConstants.PARAM_SPEC);
      return rawIds == null ? single(spec, entity, specName, id)
          : several(spec, entity, specName, (JSONArray) rawIds);
    } catch (NeoAttachmentTarget.Refused e) {
      return refusal(e);
    }
  }

  /** What is wrong with the way the document(s) are named, or {@code null} when nothing is. */
  private static String argumentProblem(String id, Object rawIds) {
    if (id != null && rawIds != null) {
      return "Pass 'id' or 'ids', not both.";
    }
    if (id == null && rawIds == null) {
      return "'id' (one document) or 'ids' (several) is required.";
    }
    if (rawIds == null) {
      return null;
    }
    if (!(rawIds instanceof JSONArray)) {
      return "'ids' must be a list of document ids.";
    }
    int count = ((JSONArray) rawIds).length();
    if (count == 0) {
      return "'ids' is empty: list at least one document.";
    }
    return count > MAX_DOCUMENTS
        ? "At most " + MAX_DOCUMENTS + " documents per call; got " + count + ". Split the list."
        : null;
  }

  private static JSONObject single(SFSpec spec, SFEntity entity, String specName, String id)
      throws JSONException {
    JSONObject answer = documentAnswer(spec, entity, id);
    if (!answer.has(KEY_ERROR_BODY)) {
      answer.put(McpConstants.PARAM_SPEC, specName);
      answer.put(McpConstants.KEY_HINT, answer.has(KEY_DOWNLOAD_URL)
          ? "Give the user the downloadUrl: it opens the PDF and expires in "
              + McpAttachmentTools.DOWNLOAD_LINK_TTL.toMinutes() + " minutes. Do not try to read the "
              + "file through this API."
          : MSG_NO_SECRET);
    }
    return answer;
  }

  private static JSONObject several(SFSpec spec, SFEntity entity, String specName,
      JSONArray rawIds) throws JSONException {
    Set<String> ids = new LinkedHashSet<>();
    for (int i = 0; i < rawIds.length(); i++) {
      ids.add(StringUtils.trimToEmpty(rawIds.optString(i, "")));
    }
    JSONArray results = new JSONArray();
    int found = 0;
    boolean linksMissing = false;
    for (String id : ids) {
      JSONObject answer = id.isEmpty() ? blankId() : documentAnswer(spec, entity, id);
      boolean ok = !answer.has(KEY_ERROR_BODY);
      found += ok ? 1 : 0;
      linksMissing = linksMissing || (ok && !answer.has(KEY_DOWNLOAD_URL));
      results.put(tagged(answer, id, ok));
    }
    JSONObject body = new JSONObject();
    body.put(McpConstants.PARAM_SPEC, specName);
    body.put("count", ids.size());
    body.put("found", found);
    body.put("failed", ids.size() - found);
    body.put("results", results);
    if (linksMissing) {
      body.put(McpConstants.KEY_HINT, MSG_NO_SECRET);
    }
    return body;
  }

  /** A per-document answer in the bulk shape: its id and ok flag, and the error fields flattened. */
  private static JSONObject tagged(JSONObject answer, String id, boolean ok) throws JSONException {
    JSONObject item = new JSONObject();
    item.put(McpConstants.PARAM_ID, id);
    item.put(KEY_OK, ok);
    Iterator<?> keys = answer.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      item.put(key, answer.get(key));
    }
    return item;
  }

  private static JSONObject blankId() throws JSONException {
    return error(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
        "A document id is empty.", "Take the ids from etendo_list.");
  }

  /** The answer for one document: the PDF's description and link, or an error envelope. */
  private static JSONObject documentAnswer(SFSpec spec, SFEntity entity, String recordId)
      throws JSONException {
    try {
      NeoAttachmentTarget target = NeoAttachmentTarget.resolve(spec, entity, recordId, false);
      return verdict(target, NeoDocumentPdf.resolve(target, entity));
    } catch (NeoAttachmentTarget.Refused e) {
      return refusal(e);
    } catch (RuntimeException e) {
      // One document must never take the others' answers down with it. Only the exception class is
      // logged: its message may carry record data, and the agent gets a generic 500.
      log.error("etendo_get_document_pdf failed for one document: {}", e.getClass().getName());
      return error(McpConstants.STATUS_SERVER_ERROR, McpConstants.ERROR_SERVER,
          "The PDF of this document could not be looked up.",
          "Retry; if it persists, report it with etendo_feedback.");
    }
  }

  private static JSONObject verdict(NeoAttachmentTarget target, NeoDocumentPdf.Result result)
      throws JSONException {
    JSONObject problem = unavailable(result);
    return problem != null ? problem : found(target, result.getFile());
  }

  /**
   * The 409 (or 500) answer for a stored-file decision that is not {@code FOUND}.
   *
   * @param result the decision of {@link NeoDocumentPdf#resolve}
   * @return the error envelope, or {@code null} when the PDF is there and current
   * @throws JSONException when a body cannot be built
   */
  static JSONObject unavailable(NeoDocumentPdf.Result result) throws JSONException {
    switch (result.getStatus()) {
      case FOUND:
        return null;
      case STALE:
        return error(McpConstants.STATUS_CONFLICT, McpConstants.ERROR_PDF_STALE,
            "The stored PDF of this document is out of date: the document, or the company data it "
                + "prints, changed after the PDF was generated.",
            "The PDF is generated by the application, not by this API: ask the user to open the "
                + "document's preview in the application (or to send the document by email, for a "
                + "window whose preview stores nothing) so the PDF is regenerated, then ask again.");
      case MISSING:
        return error(McpConstants.STATUS_CONFLICT, McpConstants.ERROR_NO_PDF,
            "This document has no PDF yet.", missingHint(result));
      case NO_SUPPLIER_DOCUMENT:
        return error(McpConstants.STATUS_CONFLICT, McpConstants.ERROR_NO_PDF,
            "This document has no supplier document attached.",
            "This document type has no printable: its PDF is the supplier's own document (their "
                + "invoice or delivery note), attached as the main attachment. Attach it with "
                + McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD + " (main:true) and ask again.");
      default:
        return error(McpConstants.STATUS_SERVER_ERROR, McpConstants.ERROR_SERVER,
            StringUtils.defaultIfBlank(result.getMessage(), "The stored PDF could not be looked up."),
            "Retry; if it persists, report it with etendo_feedback.");
    }
  }

  /** What to do about a draft, for both PDF tools: only one thing is true of it. */
  static final String DRAFT_HINT_PREFIX = "This document is a draft and a draft never stores a "
      + "PDF: complete it first, then open its preview in the application and ";

  /** The draft advice of {@code etendo_get_document_pdf}: the retry verb is "ask again". */
  static final String DRAFT_HINT = DRAFT_HINT_PREFIX + "ask again.";

  /**
   * The advice for a document with no PDF, by what is known of its state: a draft gets only the
   * draft sentence; a document of a known other status gets the preview advice; an unknown status
   * (no {@code documentStatus} property) keeps the generic text, which is true either way.
   */
  private static String missingHint(NeoDocumentPdf.Result result) {
    String documentStatus = result.getDocumentStatus();
    if (result.isDraft()) {
      return DRAFT_HINT;
    }
    String advice = "Open the document's preview in the application, or send the document by "
        + "email, and ask again";
    String tail = ". Which of the two stores one depends on the window: a return receipt's "
        + "preview stores nothing, only sending it does. This tool does not generate PDFs.";
    return documentStatus == null
        ? advice + " (a draft never stores a PDF: complete it first)" + tail : advice + tail;
  }

  private static JSONObject found(NeoAttachmentTarget target, JSONObject stored)
      throws JSONException {
    JSONObject file = new JSONObject();
    file.put(McpConstants.PARAM_ID, stored.optString(McpConstants.PARAM_ID));
    file.put("name", stored.optString("name"));
    file.put("size", stored.optLong("size"));
    file.put("type", stored.opt("dataType"));
    file.put("updated", stored.opt("updatedAt"));
    JSONObject answer = new JSONObject();
    answer.put(McpConstants.PARAM_ID, target.getRecordId());
    answer.put(KEY_FILE, file);
    String url = McpAttachmentTools.downloadUrl(target, file.getString(McpConstants.PARAM_ID));
    if (url != null) {
      answer.put(KEY_DOWNLOAD_URL, url);
      answer.put("linkExpiresInMinutes", McpAttachmentTools.DOWNLOAD_LINK_TTL.toMinutes());
    }
    return answer;
  }

  /** A target refusal as the IMP-5 envelope, worded for READING a document's PDF. */
  private static JSONObject refusal(NeoAttachmentTarget.Refused refused) throws JSONException {
    int status = refused.getStatus();
    if (status == McpConstants.STATUS_FORBIDDEN) {
      return error(status, McpConstants.ERROR_FORBIDDEN, refused.getMessage(),
          "This role cannot read this document. Ask for the access, or ask someone who has it.");
    }
    if (status == McpConstants.STATUS_METHOD_NOT_ALLOWED) {
      return error(status, McpConstants.ERROR_METHOD_NOT_ALLOWED, refused.getMessage(),
          "The application gives this document type no PDF to download (no Attachments tab, no "
              + "printable): there is no PDF to get.");
    }
    if (status == McpConstants.STATUS_NOT_FOUND) {
      return error(status, McpConstants.ERROR_NOT_FOUND, refused.getMessage(),
          "Check the id with etendo_list: it must be a record of this spec that you can see.");
    }
    return error(status, McpConstants.ERROR_VALIDATION, refused.getMessage(),
        "Address the document itself (the spec's header entity), not one of its lines.");
  }

  private static JSONObject error(int status, String code, String detail, String hint)
      throws JSONException {
    JSONObject envelope = McpAttachmentTools.errorEnvelope(status, code, detail, hint);
    envelope.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_READING);
    return envelope;
  }
}
