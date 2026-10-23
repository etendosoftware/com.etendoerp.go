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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoDocumentPdf;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractRegistry;
import com.etendoerp.go.schemaforge.email.EmailMessageEdits;
import com.etendoerp.go.schemaforge.email.EmailSendDefaults;

/**
 * {@code etendo_send_document_email} (ETP-5715): the application's "Enviar", for one document.
 *
 * <p><b>The same server path, never a parallel one.</b> The tool builds the command the send modal
 * posts ({@link McpDocumentEmailCommand}) and hands it to the same {@code TransactionalEmailService}
 * the {@code /sws/neo/email-contracts/{window}-send} endpoint calls, so the contract's own checks run
 * unchanged: write access to the window, the document and its recipient, the five hourly throttles,
 * idempotency, the suppression list and the audit rows. What the tool adds is only what a browser
 * form would have done for it: strict argument validation ({@link McpDocumentEmailRequest}), the
 * prefilled subject and message, the parent authorization ({@link NeoAttachmentTarget}, write tier:
 * client and readable organization, the same 404 for a missing and a foreign document) and the PDF
 * gate.</p>
 *
 * <p><b>The PDF gate.</b> The email carries a signed "Descargar documento" link that, when the
 * recipient clicks it, serves the record's main attachment. The server does not check that one
 * exists: it sends, and the recipient would find a 404. The application avoids that by storing the
 * rendered PDF before it sends. The MCP cannot render, so it refuses to send a document whose PDF
 * is missing or older than the document ({@link NeoDocumentPdf}), with the answer
 * {@code etendo_get_document_pdf} gives.</p>
 *
 * <p>{@code preview: true} runs the same checks and returns what would be sent, sending nothing.</p>
 */
final class McpDocumentEmailTool {

  private static final Logger log = LogManager.getLogger(McpDocumentEmailTool.class);

  /** The draft advice of this tool: the same sentence as the PDF tool's, with its own retry verb. */
  private static final String DRAFT_SEND_HINT = McpDocumentPdfTool.DRAFT_HINT_PREFIX
      + "ask to send again.";
  private static final String PREVIEW_STORES_NOTHING = "For a window whose preview stores nothing "
      + "(a return receipt) the first send must be done from the application.";
  private static final String KEY_SENT = "sent";
  private static final String KEY_PDF = "pdf";
  private static final String KEY_READY = "ready";
  private static final String KEY_SUBJECT = McpDocumentEmailRequest.PARAM_SUBJECT;
  private static final String KEY_MESSAGE = McpDocumentEmailRequest.PARAM_MESSAGE;

  private McpDocumentEmailTool() {
  }

  /**
   * Runs the tool.
   *
   * @param args the tool arguments
   * @return the MCP tool result, an error content when the body is an error envelope
   * @throws JSONException when a body cannot be built
   */
  static JSONObject handle(JSONObject args) throws JSONException {
    return McpToolResponses.imageToolResult(run(args));
  }

  /**
   * Validates, authorizes and sends (or previews) one document email.
   *
   * @param args {@code spec}, {@code id}, {@code to} and optionally {@code cc}, {@code subject},
   *        {@code message}, {@code preview}, {@code entity}
   * @return the answer body (an error envelope when nothing was sent)
   * @throws JSONException when a body cannot be built
   */
  static JSONObject run(JSONObject args) throws JSONException {
    McpDocumentEmailRequest request;
    try {
      request = McpDocumentEmailRequest.parse(args);
    } catch (McpDocumentEmailRequest.Invalid e) {
      return invalid(e);
    }
    String specName = McpArgumentUtils.optionalString(args, McpConstants.PARAM_SPEC);
    SFSpec spec = McpToolRouterSupport.findActiveSpecByName(specName);
    EmailContractRegistry registry = McpDocumentEmailContracts.registry();
    Optional<DefaultDocumentSendEmailContract> contract = McpDocumentEmailContracts
        .forSpec(specName, registry);
    if (!contract.isPresent()) {
      return notOffered(specName, registry);
    }
    try {
      SFEntity entity = McpAttachmentTools.rootEntityOf(spec, args);
      NeoAttachmentTarget target = NeoAttachmentTarget.resolve(spec, entity, request.getId(),
          true);
      NeoDocumentPdf.Result pdf = NeoDocumentPdf.resolve(target, entity);
      return proceed(contract.get(), request, specName, pdf);
    } catch (NeoAttachmentTarget.Refused e) {
      return refusal(e);
    }
  }

  private static JSONObject proceed(DefaultDocumentSendEmailContract contract,
      McpDocumentEmailRequest request, String specName, NeoDocumentPdf.Result pdf)
      throws JSONException {
    String language = StringUtils.defaultIfBlank(request.getLanguage(),
        McpDocumentEmailRequest.DEFAULT_LANGUAGE);
    Optional<EmailSendDefaults> defaults = contract.sendDefaults(request.getId(), language);
    if (!defaults.isPresent()) {
      return error(McpConstants.STATUS_NOT_FOUND, McpConstants.ERROR_NOT_FOUND,
          NeoAttachmentTarget.MSG_RECORD_NOT_FOUND,
          "Check the id with etendo_list: it must be a record of this spec that you can see.");
    }
    if (request.isPreview()) {
      return preview(contract, request, specName, language, defaults.get(), pdf);
    }
    try {
      request.requireRecipientCap(defaults.get().getMaxRecipients());
    } catch (McpDocumentEmailRequest.Invalid e) {
      return invalid(e);
    }
    JSONObject unavailable = pdfProblem(pdf);
    if (unavailable != null) {
      unavailable.put(KEY_SENT, false);
      return unavailable;
    }
    return send(contract, request, specName, language, defaults.get());
  }

  private static JSONObject send(DefaultDocumentSendEmailContract contract,
      McpDocumentEmailRequest request, String specName, String language,
      EmailSendDefaults defaults) throws JSONException {
    JSONObject command = McpDocumentEmailCommand.build(contract.getName(), request, defaults,
        language);
    NeoResponse response;
    try {
      response = McpDocumentEmailContracts.service().send(contract.getName(), command);
    } catch (RuntimeException e) {
      // Only the class is logged, without the throwable: its message (and a stack that prints it)
      // may carry an address or text of the email (S5145). The agent gets a generic answer. The
      // provider is the last step, so a failure after it is possible: the outcome is unknown,
      // never "not sent".
      log.error("etendo_send_document_email failed: {}", e.getClass().getName());
      JSONObject failure = error(McpConstants.STATUS_SERVER_ERROR, McpConstants.ERROR_SERVER,
          "The email could not be confirmed as sent.",
          "Check the document's email history in the application before trying again, so the "
              + "same email is not sent twice.");
      failure.put(KEY_SENT, false);
      return failure;
    }
    return McpDocumentEmailOutcome.of(response, request, specName);
  }

  private static JSONObject preview(DefaultDocumentSendEmailContract contract,
      McpDocumentEmailRequest request, String specName, String language, EmailSendDefaults defaults,
      NeoDocumentPdf.Result pdf) throws JSONException {
    JSONObject body = new JSONObject();
    body.put("preview", true);
    body.put(KEY_SENT, false);
    body.put(McpConstants.PARAM_SPEC, specName);
    body.put(McpConstants.PARAM_ID, request.getId());
    body.put("contract", contract.getName());
    body.put("language", language);
    body.put("removedDuplicates", new JSONArray(request.getRemovedDuplicates()));
    JSONArray base = new JSONArray();
    if (defaults.getBaseRecipient() != null) {
      base.put(defaults.getBaseRecipient());
    }
    body.put("defaults", new JSONObject().put(McpDocumentEmailRequest.PARAM_TO, base)
        .put(KEY_SUBJECT, defaults.getSubject()).put(KEY_MESSAGE, defaults.getMessage()));
    body.put("limits", new JSONObject().put("maxRecipients", defaults.getMaxRecipients())
        .put("maxSubjectLength", EmailMessageEdits.MAX_SUBJECT_LENGTH)
        .put("maxMessageLength", EmailMessageEdits.MAX_MESSAGE_LENGTH));
    JSONObject problem = pdfProblem(pdf);
    body.put(KEY_PDF, pdfSummary(pdf, problem));
    body.put("readyToSend", problem == null);
    body.put(McpConstants.KEY_HINT, problem == null
        ? "Nothing was sent. To send, call again without preview and pass 'to'; subject and "
            + "message are optional and default to the text above."
        : "Nothing was sent, and a send would be refused until the PDF is available: "
            + problem.optString(McpConstants.KEY_HINT));
    return body;
  }

  /**
   * The PDF's problem as this tool words it, or {@code null} when the PDF is there and current.
   *
   * <p>The status and code are those of {@code etendo_get_document_pdf}; only the hint changes,
   * because that tool's "or send the document by email" is circular here. The last sentence is true
   * for every window without naming one: a window whose preview stores nothing (a return receipt)
   * has no PDF until the application sends it once, and no declared property tells the backend
   * which windows those are.</p>
   */
  private static JSONObject pdfProblem(NeoDocumentPdf.Result pdf) throws JSONException {
    JSONObject problem = McpDocumentPdfTool.unavailable(pdf);
    if (problem == null) {
      return null;
    }
    if (pdf.getStatus() == NeoDocumentPdf.Status.MISSING) {
      problem.put(McpConstants.KEY_HINT, missingHint(pdf));
    } else if (pdf.getStatus() == NeoDocumentPdf.Status.STALE) {
      problem.put(McpConstants.KEY_HINT, "Open the document's preview in the application so its "
          + "PDF is refreshed, then ask to send again. " + PREVIEW_STORES_NOTHING);
    }
    return problem;
  }

  /** Same states as {@code etendo_get_document_pdf}: a draft, another status, status unknown. */
  private static String missingHint(NeoDocumentPdf.Result pdf) {
    String documentStatus = pdf.getDocumentStatus();
    if (pdf.isDraft()) {
      return DRAFT_SEND_HINT;
    }
    String advice = "Open the document's preview in the application so its PDF is stored, then "
        + "ask to send again";
    return (documentStatus == null ? advice + " (a draft never stores a PDF: complete it first). "
        : advice + ". ") + PREVIEW_STORES_NOTHING;
  }

  private static JSONObject pdfSummary(NeoDocumentPdf.Result pdf, JSONObject problem)
      throws JSONException {
    JSONObject summary = new JSONObject();
    if (problem != null) {
      return summary.put(KEY_READY, false).put(McpConstants.KEY_ERROR,
          problem.optString(McpConstants.KEY_ERROR)).put(McpConstants.KEY_DETAIL,
              problem.optString(McpConstants.KEY_DETAIL));
    }
    JSONObject stored = pdf.getFile();
    return summary.put(KEY_READY, true).put("file", new JSONObject()
        .put("name", stored.optString("name")).put("size", stored.optLong("size"))
        .put("updated", stored.opt("updatedAt")));
  }

  private static JSONObject invalid(McpDocumentEmailRequest.Invalid e) throws JSONException {
    JSONObject body = error(McpConstants.STATUS_UNPROCESSABLE, McpConstants.ERROR_VALIDATION,
        e.getMessage(), e.getHint());
    body.put(KEY_SENT, false);
    return body;
  }

  private static JSONObject notOffered(String specName, EmailContractRegistry registry)
      throws JSONException {
    JSONObject body = error(McpConstants.STATUS_METHOD_NOT_ALLOWED,
        McpConstants.ERROR_METHOD_NOT_ALLOWED,
        "The application offers no 'Send by email' for this document type.",
        "Documents that can be sent: " + String.join(", ", addressable(registry)) + ".");
    body.put(KEY_SENT, false);
    body.put(McpConstants.PARAM_SPEC, specName);
    return body;
  }

  /** The sendable specs the caller can actually address (active and published to the MCP). */
  private static List<String> addressable(EmailContractRegistry registry) {
    List<String> specs = new ArrayList<>();
    for (String candidate : McpDocumentEmailContracts.allSpecs(registry)) {
      try {
        McpToolRouterSupport.findActiveSpecByName(candidate);
        specs.add(candidate);
      } catch (McpRoutingException e) {
        // Registered but not published to the MCP: not something the agent can address.
        log.debug("Send contract of an unpublished spec left out of the hint: {}", candidate);
      }
    }
    return specs;
  }

  /** A target refusal as the error envelope, worded for SENDING a document. */
  private static JSONObject refusal(NeoAttachmentTarget.Refused refused) throws JSONException {
    int status = refused.getStatus();
    String code = McpConstants.ERROR_VALIDATION;
    String hint = "Address the document itself (the spec's header entity), not one of its lines.";
    if (status == McpConstants.STATUS_FORBIDDEN) {
      code = McpConstants.ERROR_FORBIDDEN;
      hint = "This role cannot send this document: sending needs write access to its window.";
    } else if (status == McpConstants.STATUS_METHOD_NOT_ALLOWED) {
      code = McpConstants.ERROR_METHOD_NOT_ALLOWED;
      hint = "The application gives this document type no PDF to send.";
    } else if (status == McpConstants.STATUS_NOT_FOUND) {
      code = McpConstants.ERROR_NOT_FOUND;
      hint = "Check the id with etendo_list: it must be a record of this spec that you can see.";
    }
    JSONObject body = error(status, code, refused.getMessage(), hint);
    body.put(KEY_SENT, false);
    return body;
  }

  private static JSONObject error(int status, String code, String detail, String hint)
      throws JSONException {
    return McpAttachmentTools.errorEnvelope(status, code, detail, hint);
  }
}
