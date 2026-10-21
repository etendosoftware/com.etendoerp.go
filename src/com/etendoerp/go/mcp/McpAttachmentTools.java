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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.NeoAttachmentPolicy;
import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadEndpoint;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets;
import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;

/**
 * The MCP attachment tools (ETP-5715): {@code etendo_request_attachment_upload},
 * {@code etendo_list_attachments} and {@code etendo_delete_attachment} — what the SPA's "Adjuntos"
 * tab does, for any document spec, on the document itself.
 *
 * <p><b>The bytes never pass through the conversation.</b> Same reason as the image tools
 * ({@link McpImageTools}): a tool argument is model output, so a PDF in an argument costs tokens
 * and is capped far below the 10 MB the SPA accepts. The upload tool therefore hands back a
 * single-use link and whoever holds the file PUTs it there; no inline base64 variant exists.
 * Download is the same idea in reverse: the list answer carries a short-lived signed link per file,
 * never the bytes.</p>
 *
 * <p><b>Same rules as the SPA and REST, one implementation.</b> Policy (10 MB, extension allow-list,
 * magic bytes), storage and main-document replacement are {@code NeoAttachmentStore}'s, the list and
 * the delete are {@link NeoAttachmentsHelper}'s, the link is the document-download token. What the
 * MCP adds is authorization, which REST does not have yet (ADR-0003): every operation resolves its
 * parent document through {@link NeoAttachmentTarget}.</p>
 */
final class McpAttachmentTools {

  static final String PARAM_ATTACHMENT_ID = "attachment_id";
  static final String PARAM_NAME = "name";
  static final String PARAM_MAIN = "main";

  /** How long a download link in a list answer stays valid. */
  static final Duration DOWNLOAD_LINK_TTL = Duration.ofMinutes(10);

  private static final String KEY_DOWNLOAD_URL = "downloadUrl";
  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";
  private static final String KEY_UPLOADED_BY = "uploadedBy";
  private static final String KEY_ERROR_BODY = "error";
  private static final String KEY_MESSAGE = "message";

  private static final int HTTP_BAD_REQUEST = 400;

  /**
   * Longest file name accepted. {@code C_File.name} is {@code varchar(1000)}, but 255 is what a file
   * name can be on the file systems the attachments live on, and the name becomes a path segment.
   */
  static final int MAX_FILE_NAME_LENGTH = 255;

  /**
   * The only characters a file name may carry (letters of any language, combining marks, digits,
   * space and {@code . _ ( ) -}). The name travels into a storage path and is echoed to the agent,
   * so anything that means something to a shell is refused rather than escaped.
   */
  private static final Pattern SAFE_FILE_NAME =
      Pattern.compile("[\\p{L}\\p{M}\\p{N} ._()\\-]+");

  private McpAttachmentTools() {
  }

  /** The document a call addresses: its spec and entity (kept for the upload link) and the target. */
  private static final class Parent {
    private final SFSpec spec;
    private final SFEntity entity;
    private final NeoAttachmentTarget target;

    private Parent(SFSpec spec, SFEntity entity, NeoAttachmentTarget target) {
      this.spec = spec;
      this.entity = entity;
      this.target = target;
    }
  }

  /**
   * Runs one of the three attachment tools.
   *
   * @param toolName one of the three {@code McpConstants.TOOL_NEO_*_ATTACHMENT*} names
   * @param args     the tool arguments
   * @return the MCP tool result — an error content when the body is an error envelope
   * @throws JSONException when a body cannot be built
   */
  static JSONObject handle(String toolName, JSONObject args) throws JSONException {
    JSONObject body;
    try {
      switch (toolName) {
        case McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD:
          body = requestUpload(args);
          break;
        case McpConstants.TOOL_NEO_LIST_ATTACHMENTS:
          body = list(args);
          break;
        default:
          body = delete(args);
          break;
      }
    } catch (NeoAttachmentTarget.Refused e) {
      body = refusal(e);
    }
    return McpToolResponses.imageToolResult(body);
  }

  // ── etendo_request_attachment_upload ───────────────────────────────────────

  /**
   * Issues a single-use upload link for one file onto one document.
   *
   * <p>The document is authorized (write tier, tenant, record exists) and the file name is checked
   * against the extension allow-list BEFORE the link exists, so a doomed upload fails here, with
   * the policy's own message, and not after the bytes have been sent.</p>
   */
  static JSONObject requestUpload(JSONObject args) throws JSONException {
    if (StringUtils.isBlank(McpArgumentUtils.optionalString(args, PARAM_NAME))) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          "'" + PARAM_NAME + "' is required.",
          "Pass the file name with its extension, e.g. factura-proveedor.pdf.");
    }
    Parent parent = authorize(args, true);
    NeoAttachmentTarget target = parent.target;
    NeoAttachmentUploadTickets.Subject subject = new NeoAttachmentUploadTickets.Subject(
        parent.spec.getId(), parent.entity.getId(), target.getRecordId(),
        McpArgumentUtils.optionalString(args, PARAM_NAME), args.optBoolean(PARAM_MAIN, false));
    String badName = fileNameProblem(subject.getFileName());
    if (badName != null) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          badName, "Rename the file (e.g. factura-proveedor.pdf) and ask again.");
    }
    String rejection = NeoAttachmentPolicy.validateMetadata(subject.getFileName(), 0L);
    if (rejection != null) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          rejection, "Upload a file with one of the allowed extensions.");
    }
    NeoAttachmentUploadTickets tickets = NeoAttachmentUploadTickets.getInstance();
    String token;
    try {
      token = tickets.issue(currentSession(), subject);
    } catch (NeoAttachmentUploadTickets.TooManyPendingTicketsException e) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          e.getMessage(),
          "Upload the file for a link you already requested, or wait for one to expire.");
    }
    return uploadAnswer(target, subject, tickets, token);
  }

  /**
   * Why a file name cannot be accepted, or {@code null} when it can: longer than
   * {@link #MAX_FILE_NAME_LENGTH} (characters, as {@code C_File.name} counts them, and UTF-8 bytes,
   * as a file system does), made only of an extension, or outside the safe character set.
   */
  private static String fileNameProblem(String fileName) {
    if (fileName.length() > MAX_FILE_NAME_LENGTH
        || fileName.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_NAME_LENGTH) {
      return "The file name is too long: at most " + MAX_FILE_NAME_LENGTH
          + " characters (and " + MAX_FILE_NAME_LENGTH + " bytes in UTF-8, so accented letters count twice).";
    }
    if (StringUtils.isBlank(StringUtils.substringBeforeLast(fileName, "."))
        && fileName.contains(".")) {
      return "The file name is only an extension: give it a name before the extension, "
          + "e.g. factura-proveedor.pdf.";
    }
    if (!SAFE_FILE_NAME.matcher(fileName).matches()) {
      return "The file name may only contain letters, digits, spaces and . _ ( ) - (accents are "
          + "fine); quotes, $, backticks, ; | & < > and control characters are refused.";
    }
    return null;
  }

  private static JSONObject uploadAnswer(NeoAttachmentTarget target,
      NeoAttachmentUploadTickets.Subject subject, NeoAttachmentUploadTickets tickets, String token)
      throws JSONException {
    String uploadUrl = McpImageTools.publicUrl(
        McpImageTools.NEO_SERVLET_PATH + NeoAttachmentUploadEndpoint.PATH + token);
    JSONObject result = new JSONObject();
    result.put("uploadUrl", uploadUrl);
    result.put("expiresAt", tickets.peek(token)
        .map(ticket -> ticket.getExpiresAt().toString()).orElse(null));
    result.put("maxBytes", NeoAttachmentPolicy.MAX_SIZE_MB * 1024L * 1024L);
    result.put("allowedExtensions", String.join(", ", NeoAttachmentPolicy.allowedExtensions()));
    result.put("spec", target.getSpecName());
    result.put("entity", target.getEntityName());
    result.put(McpConstants.PARAM_ID, target.getRecordId());
    result.put(KEY_NAME, subject.getFileName());
    result.put(PARAM_MAIN, subject.isMarkAsMain());
    // A fixed placeholder, never the file name: the name is agent input and this line is meant to be
    // run in a shell. The URL is ours (a token we generated on a base we resolved).
    result.put("curlExample", "curl -X PUT --upload-file ./<your-file> \"" + uploadUrl + "\"");
    result.put(McpConstants.KEY_HINT, "PUT the raw file bytes to uploadUrl — no auth header is "
        + "needed, the URL itself is the credential, and it works exactly once (10 minutes). The "
        + "answer of that PUT carries the attachment id; the file then shows in the document's "
        + "Attachments tab, and in " + McpConstants.TOOL_NEO_LIST_ATTACHMENTS + ". Do not read the "
        + "file into this conversation. A rejected file (too large, wrong type) does not burn the "
        + "link: fix the file and PUT it to the same URL.");
    return result;
  }

  // ── etendo_list_attachments ────────────────────────────────────────────────

  /**
   * Lists the attachments of a document, each with a short-lived download link.
   *
   * <p>The list is {@link NeoAttachmentsHelper#handleList} — the SPA's own — and {@code isMain}
   * comes from {@link NeoAttachmentsHelper#handleGetMain}, so the tab and this answer cannot
   * disagree.</p>
   */
  static JSONObject list(JSONObject args) throws JSONException {
    NeoAttachmentTarget target = authorize(args, false).target;
    NeoResponse listed = NeoAttachmentsHelper.handleList(target.getTableName(),
        target.getRecordId());
    if (listed.getHttpStatus() >= HTTP_BAD_REQUEST) {
      return failure(listed);
    }
    String mainId = NeoAttachmentsHelper.handleGetMain(target.getTableName(), target.getRecordId())
        .getBody().optString(KEY_ID, "");
    JSONArray source = listed.getBody().getJSONArray("items");
    JSONArray items = new JSONArray();
    boolean linksAvailable = true;
    for (int i = 0; i < source.length(); i++) {
      JSONObject item = toItem(source.getJSONObject(i), mainId);
      String url = downloadUrl(target, item.getString(KEY_ID));
      linksAvailable = linksAvailable && url != null;
      if (url != null) {
        item.put(KEY_DOWNLOAD_URL, url);
      }
      items.put(item);
    }
    return listAnswer(target, items, linksAvailable);
  }

  private static JSONObject listAnswer(NeoAttachmentTarget target, JSONArray items,
      boolean linksAvailable) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("spec", target.getSpecName());
    result.put(McpConstants.PARAM_ID, target.getRecordId());
    result.put("count", items.length());
    result.put("items", items);
    if (!linksAvailable) {
      result.put(McpConstants.KEY_HINT, "Download links are not available: the signing secret "
          + "for document links is not configured on this server.");
    } else if (items.length() > 0) {
      result.put(McpConstants.KEY_HINT, "Each downloadUrl is a link the user can open; it expires "
          + "in " + DOWNLOAD_LINK_TTL.toMinutes() + " minutes (list again for fresh ones). Do not "
          + "try to read the file's content through this API.");
    }
    return result;
  }

  /** The projection the agent reads: id, name, size, type, created, createdBy, isMain. */
  private static JSONObject toItem(JSONObject raw, String mainId) throws JSONException {
    JSONObject item = new JSONObject();
    String id = raw.getString(KEY_ID);
    item.put(KEY_ID, id);
    item.put(KEY_NAME, raw.optString(KEY_NAME));
    item.put("size", raw.optLong("size"));
    item.put("type", raw.opt("dataType"));
    item.put("description", raw.opt("description"));
    item.put("created", raw.opt("uploadedAt"));
    JSONObject by = raw.optJSONObject(KEY_UPLOADED_BY);
    item.put("createdBy", by == null ? JSONObject.NULL : by.opt(KEY_NAME));
    item.put("isMain", id.equals(mainId));
    return item;
  }

  /**
   * The signed, short-lived link to one attachment — the document-download token with the
   * attachment id, served by the existing {@code /sws/neo/document-download/} endpoint.
   *
   * @return the link, or {@code null} when the signing secret is not configured
   */
  private static String downloadUrl(NeoAttachmentTarget target, String attachmentId) {
    String clientId = (String) OBContext.getOBContext().getCurrentClient().getId();
    Optional<String> token = DocumentDownloadTokenService.createAttachmentToken(
        target.getSpecName(), target.getRecordId(), clientId, attachmentId,
        DOWNLOAD_LINK_TTL.getSeconds());
    return token.map(value -> McpImageTools.publicUrl(McpImageTools.NEO_SERVLET_PATH
        + DocumentDownloadTokenService.DOWNLOAD_PATH + value)).orElse(null);
  }

  // ── etendo_delete_attachment ───────────────────────────────────────────────

  /**
   * Deletes one attachment of a document.
   *
   * <p>The attachment id is honoured only if it belongs to the addressed record
   * ({@link NeoAttachmentTarget#requireOwnAttachment}); one of another record answers the same 404
   * as an unknown id. The removal itself is {@link NeoAttachmentsHelper#handleDelete}, so the
   * fiscal-declaration guard and the storage clean-up are the SPA's.</p>
   */
  static JSONObject delete(JSONObject args) throws JSONException {
    String attachmentId = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, PARAM_ATTACHMENT_ID));
    if (attachmentId == null) {
      return errorEnvelope(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
          "'" + PARAM_ATTACHMENT_ID + "' is required.",
          "Take it from " + McpConstants.TOOL_NEO_LIST_ATTACHMENTS + ".");
    }
    NeoAttachmentTarget target = authorize(args, true).target;
    Attachment attachment = target.requireOwnAttachment(attachmentId);
    String name = attachment.getName();
    NeoResponse removed = NeoAttachmentsHelper.handleDelete(attachmentId);
    if (removed.getHttpStatus() >= HTTP_BAD_REQUEST) {
      return failure(removed);
    }
    JSONObject result = new JSONObject();
    result.put("deleted", true);
    result.put(PARAM_ATTACHMENT_ID, attachmentId);
    result.put(KEY_NAME, name);
    result.put(McpConstants.PARAM_ID, target.getRecordId());
    return result;
  }

  // ── Shared ─────────────────────────────────────────────────────────────────

  /** Resolves and authorizes the document the call addresses (spec, optional entity, id). */
  private static Parent authorize(JSONObject args, boolean write) {
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(
        McpArgumentUtils.optionalString(args, McpConstants.PARAM_SPEC));
    SFEntity entity = rootEntityOf(spec, args);
    String recordId = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, McpConstants.PARAM_ID));
    return new Parent(spec, entity, NeoAttachmentTarget.resolve(spec, entity, recordId, write));
  }

  /**
   * The spec's document entity: the one the caller names, or — when none is named — the first
   * included entity at tab level 0 (the header). Lines are refused later, by the target.
   */
  private static SFEntity rootEntityOf(SFSpec spec, JSONObject args) {
    String entityName = StringUtils.trimToNull(
        McpArgumentUtils.optionalString(args, McpConstants.PARAM_ENTITY));
    if (entityName != null) {
      return McpToolRouterSupport.findIncludedEntity(spec.getId(), entityName);
    }
    SFEntity first = null;
    for (SFEntity entity : McpToolRouterSupport.listIncludedEntities(spec.getId())) {
      if (first == null) {
        first = entity;
      }
      if (entity.getADTab() != null && Long.valueOf(0L).equals(entity.getADTab().getTabLevel())) {
        return entity;
      }
    }
    if (first == null) {
      throw new NeoAttachmentTarget.Refused(McpConstants.STATUS_UNPROCESSABLE,
          "This spec has no entity that can carry attachments.");
    }
    return first;
  }

  private static NeoAttachmentUploadTickets.Session currentSession() {
    OBContext context = OBContext.getOBContext();
    Object warehouse = context.getWarehouse() == null ? null : context.getWarehouse().getId();
    return new NeoAttachmentUploadTickets.Session(
        (String) context.getCurrentClient().getId(),
        (String) context.getCurrentOrganization().getId(),
        (String) context.getUser().getId(),
        (String) context.getRole().getId(),
        (String) warehouse);
  }

  /** A target refusal as the IMP-5 envelope: 403 forbidden, 404 not_found, otherwise validation. */
  private static JSONObject refusal(NeoAttachmentTarget.Refused refused) throws JSONException {
    int status = refused.getStatus();
    if (status == McpConstants.STATUS_FORBIDDEN) {
      return errorEnvelope(status, McpConstants.ERROR_FORBIDDEN, refused.getMessage(),
          "This role cannot change this document's attachments. Ask for the access, or ask "
              + "someone who has it.");
    }
    if (status == McpConstants.STATUS_METHOD_NOT_ALLOWED) {
      return errorEnvelope(status, McpConstants.ERROR_METHOD_NOT_ALLOWED, refused.getMessage(),
          "This document type has no Attachments tab in the application; use another route for "
              + "the file, or attach it to the document it belongs to.");
    }
    if (status == McpConstants.STATUS_NOT_FOUND) {
      return errorEnvelope(status, McpConstants.ERROR_NOT_FOUND, refused.getMessage(),
          "Check the id with etendo_list: it must be a record of this spec that you can see.");
    }
    return errorEnvelope(status, McpConstants.ERROR_VALIDATION, refused.getMessage(),
        "Address the document itself (the spec's header entity), not one of its lines.");
  }

  /** A failed REST-level answer from the shared helpers, re-spelled as the IMP-5 envelope. */
  private static JSONObject failure(NeoResponse response) throws JSONException {
    JSONObject error = response.getBody() == null ? null
        : response.getBody().optJSONObject(KEY_ERROR_BODY);
    String detail = error == null ? "The attachment operation failed."
        : error.optString(KEY_MESSAGE, "The attachment operation failed.");
    int status = response.getHttpStatus();
    String code = status == McpConstants.STATUS_NOT_FOUND ? McpConstants.ERROR_NOT_FOUND
        : McpConstants.ERROR_VALIDATION;
    return errorEnvelope(status, code, detail, "Correct the request and retry.");
  }

  /** The IMP-5 envelope shape every other MCP error uses. */
  private static JSONObject errorEnvelope(int status, String code, String detail, String hint)
      throws JSONException {
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, status);
    error.put(McpConstants.KEY_ERROR, code);
    error.put(McpConstants.KEY_DETAIL, detail);
    error.put(McpConstants.KEY_HINT, hint);
    error.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return error;
  }
}
