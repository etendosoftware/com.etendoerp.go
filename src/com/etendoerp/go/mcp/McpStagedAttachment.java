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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoStagedAttachment;
import com.etendoerp.go.schemaforge.NeoStagedDocuments;
import com.etendoerp.go.schemaforge.NeoStagedDocuments.Staged;

/**
 * {@code etendo_request_attachment_upload} with a {@code staged_document_id} (ETP-5715): attaches the
 * PDF the user already uploaded for data extraction to the document created from it, as the main
 * attachment when asked — what the application's supplier-invoice upload leaves after "Extraer y
 * completar", without sending the file a second time.
 *
 * <p>The document is authorized by the caller ({@link NeoAttachmentTarget}, write tier); here the
 * staged document must be the session's own, have its bytes, and have been staged for the same spec as
 * the document it is attached to. The attach itself is {@link NeoStagedAttachment}'s, the SPA's upload
 * path. Once attached, the staged copy is removed: it has served.</p>
 */
final class McpStagedAttachment {

  private McpStagedAttachment() {
  }

  /**
   * Attaches a staged document to an authorized one.
   *
   * @param target   the document the PDF belongs to, already authorized for writing
   * @param stagedId the staged document id
   * @param main     whether it becomes the document's main attachment
   * @return the answer body, an error envelope when it fails
   * @throws JSONException when a body cannot be built
   */
  static JSONObject attach(NeoAttachmentTarget target, String stagedId, boolean main)
      throws JSONException {
    Session session = Session.of(OBContext.getOBContext());
    NeoStagedDocuments documents = NeoStagedDocuments.getInstance();
    Staged staged = documents.find(stagedId, session).orElse(null);
    if (staged == null) {
      return McpOcrTools.notFound();
    }
    if (!staged.isStored()) {
      return McpOcrTools.notUploaded();
    }
    if (!StringUtils.equals(staged.getSpecName(), target.getSpecName())) {
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_UNPROCESSABLE,
          McpConstants.ERROR_VALIDATION, "The staged document was uploaded for another document "
              + "type and cannot be attached here.",
          "Attach it to a document of the type it was staged for, or upload the file again.");
    }
    // Never wait: an extraction holds this lock while it reads the file, and attaching meanwhile
    // would delete the file under it. Two attaches of the same document cannot overlap either.
    if (!staged.getLock().tryLock()) {
      return McpAttachmentTools.errorEnvelope(McpConstants.STATUS_CONFLICT,
          McpConstants.ERROR_CONFLICT, "The staged document is being read or attached right now.",
          "Wait for the extraction to answer, then attach it.");
    }
    try {
      if (staged.isConsumed()) {
        return McpOcrTools.notFound();
      }
      NeoResponse stored = NeoStagedAttachment.attach(target, staged, main);
      if (stored.getHttpStatus() >= McpConstants.STATUS_BAD_REQUEST) {
        return McpAttachmentTools.failure(stored);
      }
      staged.markConsumed();
      documents.discard(stagedId);
      return attached(target, stored.getBody(), main);
    } finally {
      staged.getLock().unlock();
    }
  }

  private static JSONObject attached(NeoAttachmentTarget target, JSONObject stored, boolean main)
      throws JSONException {
    JSONObject result = new JSONObject();
    result.put("attached", true);
    result.put("attachmentId", stored.opt("id"));
    result.put("name", stored.optString("name"));
    result.put("isMain", main);
    result.put(McpConstants.PARAM_SPEC, target.getSpecName());
    result.put(McpConstants.PARAM_ID, target.getRecordId());
    result.put(McpConstants.KEY_HINT, "The staged PDF is now an attachment of the document"
        + (main ? ", marked as its main one" : "") + ". Check it with "
        + McpConstants.TOOL_NEO_LIST_ATTACHMENTS + " if you need to.");
    return result;
  }
}
