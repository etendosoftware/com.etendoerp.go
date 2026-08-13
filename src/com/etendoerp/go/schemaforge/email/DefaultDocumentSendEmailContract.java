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

/**
 * Base contract for document-send transactional emails resolved from trusted server records.
 */
public class DefaultDocumentSendEmailContract implements EmailContract {

  public static final String DEFAULT_TEMPLATE = "document";

  private static final String DOCUMENT_RECORD_NOT_FOUND = "Email document record was not found";

  private final String name;
  private final String template;
  private final String documentType;
  private final String documentNumberAlias;
  private final boolean includeAmount;
  private final EmailDocumentRecordResolver documentResolver;

  protected DefaultDocumentSendEmailContract(String name, String documentType,
      EmailDocumentRecordResolver documentResolver) {
    this(name, DEFAULT_TEMPLATE, documentType, null, false, documentResolver);
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
    try {
      EmailRecipientSet recipients = recipient.getRecipientSet() != null
          ? recipient.getRecipientSet()
          : EmailRecipientSet.singleTo(recipient.getRecipient());
      return EmailContractResolution.ready(new EmailProviderRequest(recipients,
          template, buildTemplateData(document.get(), downloadLink.get()), null));
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
        resolveSendIdempotencyKey(tenantId, documentRecordId, finalRecipients),
        java.util.Arrays.asList(
            EmailThrottleRule.perTenant(100, 3600),
            EmailThrottleRule.perUser(50, 3600),
            EmailThrottleRule.perRecord(3, 3600),
            EmailThrottleRule.perRecipient(20, 3600),
            EmailThrottleRule.perDomain(200, 3600),
            EmailThrottleRule.global(2000, 60)));
  }

  private JSONObject buildTemplateData(EmailDocumentRecord document, String downloadLink)
      throws JSONException {
    JSONObject data = new JSONObject();
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
    return data;
  }

  /**
   * Resolves an existing absolute document link or creates a signed download link for the trusted
   * document record.
   */
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
   * {recipientSetHash}}. The caller-supplied idempotency key is ignored for document sends.
   */
  private String resolveSendIdempotencyKey(String tenantId, String recordId,
      EmailRecipientSet finalRecipients) {
    String normalizedTenant = StringUtils.defaultIfBlank(tenantId, "global");
    return name + ":" + normalizedTenant + ":" + recordId + ":send:"
        + EmailContractCommandSupport.VERSION + ":" + finalRecipients.recipientSetHash();
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
