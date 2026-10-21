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

import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;
import static com.etendoerp.go.mcp.McpJsonSchema.enumProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringArrayProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringProp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The definition of {@code etendo_get_document_pdf} (ETP-5715), split out of {@link ToolRegistry}
 * (java:S1448). Its runtime is {@link McpDocumentPdfTool}.
 *
 * <p>Like the attachment tools it serves every window spec on the root record of the document and
 * is registered on the read scope only.</p>
 */
final class McpDocumentPdfToolDefinition {

  /** Description of {@link McpConstants#TOOL_NEO_GET_DOCUMENT_PDF}; a test pins its key claims. */
  static final String DESCRIPTION =
      "Get the PDF of a document (sales or purchase invoice, order, quotation, shipment, return...) "
      + "exactly as the application has it stored: the PDF the application generated when someone "
      + "opened the document's preview, or, for the documents with no printable of their own (a "
      + "purchase invoice, a goods receipt), the supplier's own document attached as its main "
      + "attachment. Returns "
      + "the file name, size, type and last update, and a short-lived signed download link (10 "
      + "minutes) the user can open — never the file's content. This tool never generates a PDF: if "
      + "the document has none yet, or the stored one predates the document's last change, it "
      + "answers a clear 409 saying what to do (open the document's preview in the application, or "
      + "send the document by email, and ask again). Pass id for one document or ids (at most " + McpDocumentPdfTool.MAX_DOCUMENTS
      + ") for several: one result per document, a failure on one does not stop the others. Not "
      + "available where the application has no Attachments tab (payments, fiscal monitors). "
      + "Requires read access to the document's window.";

  private McpDocumentPdfToolDefinition() {
  }

  /**
   * Builds the tool definition.
   *
   * @param specNames the window specs the caller can address
   * @return the definition
   */
  static McpToolDefinition definition(List<String> specNames) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_SPEC, enumProp(McpConstants.LABEL_SPEC_NAME, specNames));
    props.put(McpConstants.PARAM_ID, stringProp("The id of the document. Pass this OR ids."));
    props.put(McpDocumentPdfTool.PARAM_IDS, stringArrayProp("Several documents of the same spec "
        + "(at most " + McpDocumentPdfTool.MAX_DOCUMENTS + "); one result per document. Pass this "
        + "OR id."));
    props.put(McpConstants.PARAM_ENTITY, stringProp("Optional. The spec's document entity (its "
        + "header). Omit it and the header is used."));
    return new McpToolDefinition(McpConstants.TOOL_NEO_GET_DOCUMENT_PDF, DESCRIPTION,
        buildObjectSchema(props, List.of(McpConstants.PARAM_SPEC)));
  }
}
