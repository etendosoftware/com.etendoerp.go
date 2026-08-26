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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.email;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.common.ConfigPropertyReader;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;

/**
 * Base contract for document-send transactional emails resolved from trusted server records.
 */
public class DefaultDocumentSendEmailContract implements EmailContract {

  /**
   * Provider template that renders caller-supplied {@code subject}/{@code body} instead of
   * carrying copy of its own.
   */
  public static final String CONTENT_TEMPLATE = "custom";

  /**
   * Provider template used by the document-send family when no contract overrides it.
   * <p>
   * ETP-4786: this was {@code "document"}, a template the provider gateway does not expose. The
   * gateway answered every send with
   * {@code 400 Unknown template 'document'. Available: ['reset-password', 'login-alert',
   * 'invoice', 'custom']}, surfacing in the UI as "the email provider could not send the
   * document". {@code custom} is the bring-your-own-content template, so this contract also
   * supplies {@code subject} and {@code body} (see {@link #buildTemplateData}). Override with
   * {@link #PROP_DOCUMENT_TEMPLATE} once the gateway publishes a branded document template.
   */
  public static final String DEFAULT_TEMPLATE = CONTENT_TEMPLATE;

  public static final String PROP_DOCUMENT_TEMPLATE =
      "etendo.go.email.provider.documentTemplate";
  public static final String ENV_DOCUMENT_TEMPLATE =
      "ETGO_EMAIL_PROVIDER_DOCUMENT_TEMPLATE";

  private static final String DOCUMENT_RECORD_NOT_FOUND = "Email document record was not found";
  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";

  private final String name;
  private final String template;
  private final String documentType;
  private final String documentNumberAlias;
  private final boolean includeAmount;
  private final EmailDocumentRecordResolver documentResolver;

  protected DefaultDocumentSendEmailContract(String name, String documentType,
      EmailDocumentRecordResolver documentResolver) {
    this(name, resolveDefaultTemplate(), documentType, null, false, documentResolver);
  }

  protected DefaultDocumentSendEmailContract(String name, String template, String documentType,
      String documentNumberAlias,
      EmailDocumentRecordResolver documentResolver) {
    this(name, template, documentType, documentNumberAlias, false, documentResolver);
  }

  protected DefaultDocumentSendEmailContract(String name, String template, String documentType,
      String documentNumberAlias, boolean includeAmount,
      EmailDocumentRecordResolver documentResolver) {
    this.name = StringUtils.trimToNull(name);
    this.template = StringUtils.trimToNull(template);
    this.documentType = StringUtils.trimToNull(documentType);
    this.documentNumberAlias = StringUtils.trimToNull(documentNumberAlias);
    this.includeAmount = includeAmount;
    this.documentResolver = Objects.requireNonNull(documentResolver, "documentResolver");
  }

  /**
   * Resolves the provider template used by contracts that do not pin one of their own.
   *
   * @return configured document template, or {@link #DEFAULT_TEMPLATE} when unset
   */
  static String resolveDefaultTemplate() {
    return StringUtils.defaultIfBlank(ConfigPropertyReader.readConfigValue(PROP_DOCUMENT_TEMPLATE,
        ENV_DOCUMENT_TEMPLATE, null), DEFAULT_TEMPLATE);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult validation = EmailContractCommandSupport.validateCommand(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    if (!validation.isAllowed()) {
      return validation;
    }
    return resolveDocument(command).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(404, DOCUMENT_RECORD_NOT_FOUND);
  }

  /**
   * Per-contract hook: the document-send family accepts recipient edits by default.
   *
   * @return {@code true} when {@code recipientEdits} is accepted
   */
  protected boolean isRecipientEditingEnabled() {
    return true;
  }

  /**
   * Per-contract hook: maximum number of recipients across the to and cc channels.
   *
   * @return maximum total recipient count
   */
  protected int maxRecipientsTotal() {
    return 10;
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    if (!document.isPresent()) {
      return EmailRecipientResolution.rejected(404, DOCUMENT_RECORD_NOT_FOUND);
    }
    Optional<EmailRecipientEdits> edits;
    try {
      edits = EmailRecipientEdits.fromBody(command.getBody());
    } catch (EmailRecipientEdits.InvalidRecipientEditsException e) {
      return EmailRecipientResolution.rejected(400, e.getMessage());
    }
    if (edits.isPresent() && !isRecipientEditingEnabled()) {
      return EmailRecipientResolution.rejected(400,
          "recipientEdits is not accepted by this contract");
    }
    List<String> baseTo = new ArrayList<>();
    String baseEmail = document.get().getRecipientEmail();
    if (EmailContractCommandSupport.isValidEmail(baseEmail)) {
      baseTo.add(baseEmail);
    }
    if (!edits.isPresent()) {
      if (baseTo.isEmpty()) {
        return EmailRecipientResolution.noRecipient("Document has no recipient email");
      }
      return EmailRecipientResolution.serverResolved(baseTo.get(0));
    }
    EmailRecipientSet finalSet = edits.get().applyTo(baseTo);
    if (finalSet.isToEmpty()) {
      return EmailRecipientResolution.noRecipient("Final recipient list is empty");
    }
    if (finalSet.totalCount() > maxRecipientsTotal()) {
      return EmailRecipientResolution.rejected(400,
          "Recipient count exceeds the maximum of " + maxRecipientsTotal());
    }
    return EmailRecipientResolution.serverResolved(finalSet);
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    if (!document.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          DOCUMENT_RECORD_NOT_FOUND);
    }
    Optional<String> downloadLink = resolveDownloadLink(command, document.get());
    if (!downloadLink.isPresent()) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Document download link is not configured");
    }
    Optional<EmailMessageEdits> messageEdits;
    try {
      messageEdits = EmailMessageEdits.fromBody(command.getBody());
    } catch (EmailMessageEdits.InvalidMessageEditsException e) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, e.getMessage());
    }
    // ETP-5003 — every document email now renders through the shared layout, so there is no
    // branded-template branch left: an edited send and an untouched one produce the same design.
    // Before this, editing the message silently downgraded a branded invoice to two bare
    // paragraphs.
    String language = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_LANGUAGE);
    try {
      EmailRecipientSet recipients = recipient.getRecipientSet() != null
          ? recipient.getRecipientSet()
          : EmailRecipientSet.singleTo(recipient.getRecipient());
      return EmailContractResolution.ready(new EmailProviderRequest(recipients, CONTENT_TEMPLATE,
          buildTemplateData(document.get(), downloadLink.get(), language, messageEdits), null));
    } catch (JSONException e) {
      throw new OBException("Could not build document email payload for " + name, e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    String tenantId = document.map(EmailDocumentRecord::getClientId).orElse(null);
    String documentRecordId = resolveEffectiveRecordId(document.orElse(null), recordId);
    EmailRecipientSet finalRecipients = recipient.getRecipientSet() != null
        ? recipient.getRecipientSet()
        : providerRequest.getRecipients();
    return EmailDeliveryPolicy.serverDerived(
        resolveSendIdempotencyKey(tenantId, documentRecordId, finalRecipients,
            messageEditsQuietly(command)),
        java.util.Arrays.asList(
            EmailThrottleRule.perTenant(100, 3600),
            EmailThrottleRule.perUser(50, 3600),
            EmailThrottleRule.perRecord(3, 3600),
            EmailThrottleRule.perRecipient(20, 3600),
            EmailThrottleRule.perDomain(200, 3600),
            EmailThrottleRule.global(2000, 60)));
  }

  @Override
  public Optional<EmailMessageDefaults> messageDefaults(EmailContractCommand command) {
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    if (!document.isPresent()) {
      return Optional.empty();
    }
    String language = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_LANGUAGE);
    // The plain-text form of the same sentence resolve() renders: the operator edits text, and the
    // layout adds the emphasis, the button and the signature around it.
    String message = EmailMessages.get("document.body", language, documentTypeLabel(language),
        document.get().getDocumentNumber());
    return Optional.of(
        new EmailMessageDefaults(buildSubject(document.get(), language), message));
  }

  private JSONObject buildTemplateData(EmailDocumentRecord document, String downloadLink,
      String language, Optional<EmailMessageEdits> messageEdits) throws JSONException {
    JSONObject data = new JSONObject();
    // Kept beside the rendered content: the gateway logs these for traceability, and they cost
    // nothing now that the copy no longer depends on them.
    data.put("name", StringUtils.defaultIfBlank(document.getRecipientName(), "Customer"));
    data.put("document_type", documentType);
    data.put("document_number", document.getDocumentNumber());
    if (documentNumberAlias != null) {
      data.put(documentNumberAlias, document.getDocumentNumber());
    }
    if (includeAmount) {
      data.put("amount", document.getAmount());
    }
    data.put("download_link", downloadLink);
    data.put(FIELD_SUBJECT, resolveSubject(document, language, messageEdits));
    data.put(FIELD_BODY,
        EmailLayout.render(buildContent(document, downloadLink, language, messageEdits)));
    return data;
  }

  /**
   * Composes the document email from shared layout blocks.
   *
   * <p>An operator-authored message replaces only the introductory paragraph. The download button
   * and its link fallback are always appended after it, because the whole point of the email is
   * the document and an operator rewriting the greeting must not be able to remove it (ETP-4717,
   * reopened).</p>
   */
  private EmailContent buildContent(EmailDocumentRecord document, String downloadLink,
      String language, Optional<EmailMessageEdits> messageEdits) {
    EmailContent.Builder content = EmailContent.builder();
    String recipientName = StringUtils.trimToNull(document.getRecipientName());
    if (recipientName != null) {
      content.greetingHtml(EmailMessages.get("document.greeting", language,
          emphasised(recipientName)));
    }
    // toHtmlBody() has already escaped the operator's text and turned newlines into <br>.
    String override = messageEdits.map(EmailMessageEdits::toHtmlBody).orElse(null);
    content.paragraphHtml(override != null ? override
        : EmailMessages.get("document.body", language,
            EmailEscape.escapeHtml(documentTypeLabel(language)),
            emphasised(document.getDocumentNumber())));
    return content.cta(EmailMessages.get("document.cta", language), downloadLink)
        .linkFallbackText(EmailMessages.get("link.fallback", language))
        .signature(EmailMessages.get("signature", language))
        .build();
  }

  private String resolveSubject(EmailDocumentRecord document, String language,
      Optional<EmailMessageEdits> messageEdits) {
    String override = messageEdits.map(EmailMessageEdits::getSubject).orElse(null);
    return override != null ? override : buildSubject(document, language);
  }

  /**
   * Human-facing label of the document type, read from the message catalog under the contract's own
   * name.
   *
   * <p>It used to be a fixed Spanish string overridden by each subclass, which is how the subject
   * the send modal displays and the subject actually delivered came to disagree under
   * {@code en_US}.</p>
   *
   * @param language the recipient language
   * @return the localized document type label
   */
  protected String documentTypeLabel(String language) {
    // Falls back to the type the contract was constructed with rather than emitting the raw key:
    // a contract added without its catalog entry would otherwise put "foo-send.documentType" in
    // the subject line of a customer's email.
    return StringUtils.defaultIfBlank(
        EmailMessages.getOptional(name + ".documentType", language), documentType);
  }

  /**
   * Builds the default subject, deliberately mirroring the shape the send modal displays
   * ({@code {documentType} #{documentNo} — {businessPartner}}): the modal posts its own derived
   * subject back as the override, so the two must agree.
   */
  private String buildSubject(EmailDocumentRecord document, String language) {
    String label = documentTypeLabel(language);
    String recipientName = StringUtils.trimToNull(document.getRecipientName());
    return recipientName == null
        ? EmailMessages.get("document.subject", language, label, document.getDocumentNumber())
        : EmailMessages.get("document.subject.withRecipient", language, label,
            document.getDocumentNumber(), recipientName);
  }

  private static String emphasised(String value) {
    return "<strong>" + EmailEscape.escapeHtml(value) + "</strong>";
  }

  private Optional<String> resolveDownloadLink(EmailContractCommand command,
      EmailDocumentRecord document) {
    String configuredLink = document.getDownloadLink();
    if (EmailContractCommandSupport.isHttpUrl(configuredLink)) {
      return Optional.of(configuredLink);
    }
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    String documentRecordId = resolveEffectiveRecordId(document, recordId);
    // The download-link token key stays stable per record so re-sends with edited recipients do
    // not mint new tokens; it must not depend on the recipient-set hash.
    String downloadTokenKey = EmailContractCommandSupport.idempotencyKey(name,
        document.getClientId(), documentRecordId);
    if (StringUtils.isAnyBlank(documentRecordId, document.getClientId(), downloadTokenKey)) {
      return Optional.empty();
    }
    return DocumentDownloadTokenService.createDownloadLink(name, inferSpecName(), documentRecordId,
        document.getClientId(), downloadTokenKey);
  }

  /**
   * Server-derived send idempotency key: {@code {contract}:{tenant}:{record}:send:v1:
   * {recipientSetHash}}, with {@code :{contentHash}} appended only when the operator authored the
   * copy. The caller-supplied idempotency key is ignored for document sends.
   * <p>
   * The content hash matters: without it, correcting the message and re-sending to the same
   * recipients produces the same key as the previous send and is answered {@code DUPLICATE}, so the
   * corrected email is never delivered. It is appended conditionally so untouched sends keep
   * exactly the key they had before ETP-4717.
   */
  private String resolveSendIdempotencyKey(String tenantId, String recordId,
      EmailRecipientSet finalRecipients, Optional<EmailMessageEdits> messageEdits) {
    String normalizedTenant = StringUtils.defaultIfBlank(tenantId, "global");
    String key = name + ":" + normalizedTenant + ":" + recordId + ":send:"
        + EmailContractCommandSupport.VERSION + ":" + finalRecipients.recipientSetHash();
    return messageEdits.isPresent() ? key + ":" + messageEdits.get().contentHash() : key;
  }

  /**
   * Re-reads {@code messageEdits} for delivery-policy purposes. {@link #resolve} already rejected
   * malformed payloads with a 400, so a parse failure here cannot reach a real send and degrades to
   * "no edits".
   */
  private Optional<EmailMessageEdits> messageEditsQuietly(EmailContractCommand command) {
    try {
      return EmailMessageEdits.fromBody(command == null ? null : command.getBody());
    } catch (EmailMessageEdits.InvalidMessageEditsException e) {
      return Optional.empty();
    }
  }

  private String resolveEffectiveRecordId(EmailDocumentRecord document, String fallbackRecordId) {
    return EmailContractCommandSupport.firstNonBlank(
        document == null ? null : document.getRecordId(), fallbackRecordId);
  }

  private String inferSpecName() {
    return StringUtils.removeEnd(name, "-send");
  }

  private Optional<EmailDocumentRecord> resolveDocument(EmailContractCommand command) {
    if (documentResolver == null) {
      return Optional.empty();
    }
    return documentResolver.resolve(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID));
  }
}
