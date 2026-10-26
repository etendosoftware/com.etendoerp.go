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
import static com.etendoerp.go.mcp.McpJsonSchema.stringArrayProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringProp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.etendoerp.go.schemaforge.email.EmailMessageEdits;

/**
 * The definition of {@code etendo_send_document_email} (ETP-5715), split out of
 * {@link ToolRegistry} (java:S1448). Its runtime is {@link McpDocumentEmailTool}.
 *
 * <p>Registered on the write scope only, and only for the specs that have a document-send contract
 * ({@link McpDocumentEmailContracts#supportedSpecs}), so the enum is the list of documents the
 * application itself can send.</p>
 */
final class McpDocumentEmailToolDefinition {

  /** Description of {@link McpConstants#TOOL_NEO_SEND_DOCUMENT_EMAIL}; a test pins its key claims. */
  static final String DESCRIPTION =
      "Send a document (sales or purchase order, sales invoice, quotation, goods shipment, return) "
      + "by email to a customer or supplier, exactly as the application's Send button does: "
      + "this sends a REAL email, to the addresses you pass, and cannot be undone. The email "
      + "carries a link to the document's PDF. Pass 'to' (one or several free addresses), "
      + "optionally 'cc', 'subject' and 'message'; subject and message default to the text the "
      + "application prefills (e.g. \"Pedido de Compra #1000008 — Proveedor\" / \"Hola, "
      + "**Proveedor**: Le enviamos su Pedido de Compra **1000008**.\"), and **double asterisks** "
      + "make a word bold in the message. ALWAYS call it first with preview:true to show the user "
      + "the recipient, subject, message and the PDF, and send only after they agree: preview "
      + "sends nothing. This tool never generates the PDF: if the document has none yet, or the "
      + "stored one is older than the document, it answers 409 no_pdf or pdf_stale and sends "
      + "nothing (the user must open the document's preview in the application). Only an answer "
      + "with sent:true was sent: an identical email already sent is refused as a DUPLICATE, and "
      + "the hourly sending limits (per document, recipient, user and company) "
      + "answer THROTTLED; both say sent:false. At most " + EmailMessageEdits.MAX_SUBJECT_LENGTH
      + " characters of subject and " + EmailMessageEdits.MAX_MESSAGE_LENGTH + " of message, and "
      + "the recipient limit the preview reports between to and cc. An address listed twice (or "
      + "in both to and cc) is sent once and reported in removedDuplicates. Purchase invoices are "
      + "not offered: the application has no send for them. Omitting preview SENDS the email. "
      + "Requires write access to the document's window.";

  private McpDocumentEmailToolDefinition() {
  }

  /**
   * Builds the tool definition.
   *
   * @param offeredSpecs the specs to offer, already reduced to those the application can send
   *        ({@link McpDocumentEmailContracts#supportedSpecs}), so the registry is built once
   * @return the definition
   */
  static McpToolDefinition definition(List<String> offeredSpecs) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(McpConstants.PARAM_SPEC, enumProp(McpConstants.LABEL_SPEC_NAME, offeredSpecs));
    props.put(McpConstants.PARAM_ID, stringProp("The id of the document to send."));
    props.put(McpDocumentEmailRequest.PARAM_TO, stringArrayProp("Recipient addresses, one plain "
        + "address per entry (ana@cliente.example). Required unless preview is true."));
    props.put(McpDocumentEmailRequest.PARAM_CC, stringArrayProp("Optional. Addresses in copy."));
    props.put(McpDocumentEmailRequest.PARAM_SUBJECT, stringProp("Optional, one line. Omit it to "
        + "use the application's default subject."));
    props.put(McpDocumentEmailRequest.PARAM_MESSAGE, stringProp("Optional, plain text; "
        + "**double asterisks** make bold. Omit it to use the application's default greeting and "
        + "sentence. Never HTML."));
    props.put(McpDocumentEmailRequest.PARAM_LANGUAGE, enumProp("Optional. The language of the "
        + "email's subject and message. Omit it for " + McpDocumentEmailRequest.DEFAULT_LANGUAGE
        + ", what the application sends unless its user switched language; pass the user's UI "
        + "locale if you know it.", McpDocumentEmailRequest.SUPPORTED_LANGUAGES));
    props.put(McpDocumentEmailRequest.PARAM_PREVIEW, booleanProp("Optional, default false. true "
        + "returns the recipient, subject, message and PDF that would be used and sends nothing."));
    props.put(McpConstants.PARAM_ENTITY, stringProp("Optional. The spec's document entity (its "
        + "header). Omit it and the header is used."));
    return new McpToolDefinition(McpConstants.TOOL_NEO_SEND_DOCUMENT_EMAIL, DESCRIPTION,
        buildObjectSchema(props, List.of(McpConstants.PARAM_SPEC, McpConstants.PARAM_ID)));
  }
}
