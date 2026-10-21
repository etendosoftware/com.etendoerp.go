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

import static com.etendoerp.go.mcp.McpJsonSchema.booleanProp;
import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;
import static com.etendoerp.go.mcp.McpJsonSchema.enumProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringProp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.etendoerp.go.schemaforge.NeoAttachmentPolicy;

/**
 * The tool definitions of the three attachment tools (ETP-5715), split out of {@link ToolRegistry}
 * (java:S1448). Their runtime handlers live in {@link McpAttachmentTools}.
 *
 * <p>Built-in and structure-driven, not spec-driven: the three tools serve every document spec,
 * and the spec enum is the same one the CRUD tools advertise. Which windows actually offer the
 * "Adjuntos" tab in the SPA is a per-window UI option the backend does not know; the MCP offers the
 * tools on the root record of every window spec the role can access.</p>
 */
final class McpAttachmentToolDefinitions {

  private static final String LABEL_ENTITY = "Optional. The spec's document entity (its header). "
      + "Omit it and the header is used; the lines of a document cannot carry attachments.";
  private static final String LABEL_RECORD = "The id of the document the attachment belongs to "
      + "(for example a purchase invoice or an order).";

  /** Description of {@link McpConstants#TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD}; a test pins its limits. */
  static final String REQUEST_UPLOAD_DESCRIPTION =
      "Attach a file (a PDF, an image, a Word/Excel document...) to a document, exactly as the "
      + "Attachments tab of the app does. Returns a single-use URL to upload the file to, plus a "
      + "ready-to-run curl command: the file bytes never pass through the conversation. Same rules "
      + "as the app: " + NeoAttachmentPolicy.MAX_SIZE_MB + " MB at most, and only the allowed "
      + "extensions. Works on a document in any status, completed ones included, but not on document types the app gives no Attachments tab. Set main:true to "
      + "make it the document's MAIN attachment — the one used as the supplier's document of a "
      + "purchase invoice; the previous main one is replaced. The URL works exactly once and "
      + "expires in 10 minutes. Requires write access to the document's window.";

  /** Description of {@link McpConstants#TOOL_NEO_LIST_ATTACHMENTS}. */
  static final String LIST_DESCRIPTION =
      "List the attachments of a document: id, name, size, type, created, createdBy, isMain, and a "
      + "short-lived downloadUrl for each (open it to get the file; the content is never returned "
      + "here). Use it to verify an upload, to find an attachment_id, or to give the user a link.";

  /** Description of {@link McpConstants#TOOL_NEO_DELETE_ATTACHMENT}. */
  static final String DELETE_DESCRIPTION =
      "Delete one attachment of a document, as the app's Attachments tab does (any document "
      + "status). The attachment_id must belong to the document you address — take it from "
      + McpConstants.TOOL_NEO_LIST_ATTACHMENTS + ". Cannot be undone. Requires write access to the "
      + "document's window.";

  private McpAttachmentToolDefinitions() {
  }

  /**
   * Registers the three tools, each only for the scopes that may call it.
   *
   * @param tools      the tool list being built
   * @param specNames  the window specs the caller can address
   * @param canRead    whether the session has the read scope
   * @param canWrite   whether the session has the write scope
   */
  static void addTo(List<McpToolDefinition> tools, List<String> specNames, boolean canRead,
      boolean canWrite) {
    if (specNames.isEmpty()) {
      return;
    }
    if (canRead) {
      tools.add(listAttachments(specNames));
      tools.add(McpDocumentPdfToolDefinition.definition(specNames));
    }
    if (canWrite) {
      tools.add(requestAttachmentUpload(specNames));
      tools.add(deleteAttachment(specNames));
    }
  }

  static McpToolDefinition requestAttachmentUpload(List<String> specNames) {
    Map<String, Object> props = documentProps(specNames);
    props.put(McpAttachmentTools.PARAM_NAME, stringProp("The file name WITH its extension, e.g. "
        + "'factura-proveedor.pdf'. The extension decides the type and must be an allowed one. Only letters "
        + "(accents are fine), digits, spaces and . _ ( ) - are accepted."));
    props.put(McpAttachmentTools.PARAM_MAIN, booleanProp("Optional, default false. true makes the "
        + "file the document's main attachment (replacing the previous main one) — what the "
        + "supplier's document of a purchase invoice is."));
    return new McpToolDefinition(McpConstants.TOOL_NEO_REQUEST_ATTACHMENT_UPLOAD,
        REQUEST_UPLOAD_DESCRIPTION, buildObjectSchema(props, List.of(McpConstants.PARAM_SPEC,
            McpConstants.PARAM_ID, McpAttachmentTools.PARAM_NAME)));
  }

  static McpToolDefinition listAttachments(List<String> specNames) {
    return new McpToolDefinition(McpConstants.TOOL_NEO_LIST_ATTACHMENTS, LIST_DESCRIPTION,
        buildObjectSchema(documentProps(specNames),
            List.of(McpConstants.PARAM_SPEC, McpConstants.PARAM_ID)));
  }

  static McpToolDefinition deleteAttachment(List<String> specNames) {
    Map<String, Object> props = documentProps(specNames);
    props.put(McpAttachmentTools.PARAM_ATTACHMENT_ID, stringProp("The attachment to delete, from "
        + McpConstants.TOOL_NEO_LIST_ATTACHMENTS + ". It must belong to the document addressed."));
    return new McpToolDefinition(McpConstants.TOOL_NEO_DELETE_ATTACHMENT, DELETE_DESCRIPTION,
        buildObjectSchema(props, List.of(McpConstants.PARAM_SPEC, McpConstants.PARAM_ID,
            McpAttachmentTools.PARAM_ATTACHMENT_ID)));
  }

  /** The three arguments that address a document, shared by every attachment tool. */
  private static Map<String, Object> documentProps(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_SPEC, enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ID, stringProp(LABEL_RECORD));
    props.put(McpConstants.PARAM_ENTITY, stringProp(LABEL_ENTITY));
    return props;
  }
}
