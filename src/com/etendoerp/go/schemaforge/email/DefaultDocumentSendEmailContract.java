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

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

class DefaultDocumentSendEmailContract implements EmailContract {

  static final String DEFAULT_TEMPLATE = "document";

  private static final String DOCUMENT_RECORD_NOT_FOUND = "Email document record was not found";

  private final String name;
  private final String template;
  private final String documentType;
  private final String documentNumberAlias;
  private final boolean includeAmount;
  private final EmailDocumentRecordResolver documentResolver;

  DefaultDocumentSendEmailContract(String name, String documentType,
      EmailDocumentRecordResolver documentResolver) {
    this(name, DEFAULT_TEMPLATE, documentType, null, false, documentResolver);
  }

  DefaultDocumentSendEmailContract(String name, String template, String documentType,
      String documentNumberAlias,
      EmailDocumentRecordResolver documentResolver) {
    this(name, template, documentType, documentNumberAlias, false, documentResolver);
  }

  DefaultDocumentSendEmailContract(String name, String template, String documentType,
      String documentNumberAlias, boolean includeAmount,
      EmailDocumentRecordResolver documentResolver) {
    this.name = StringUtils.trimToNull(name);
    this.template = StringUtils.trimToNull(template);
    this.documentType = StringUtils.trimToNull(documentType);
    this.documentNumberAlias = StringUtils.trimToNull(documentNumberAlias);
    this.includeAmount = includeAmount;
    this.documentResolver = documentResolver;
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

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailDocumentRecord> document = resolveDocument(command);
    if (!document.isPresent()) {
      return EmailRecipientResolution.rejected(404, DOCUMENT_RECORD_NOT_FOUND);
    }
    if (!EmailContractCommandSupport.isValidEmail(document.get().getRecipientEmail())) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(document.get().getRecipientEmail());
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
    if (!EmailContractCommandSupport.isHttpUrl(document.get().getDownloadLink())) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Document download link is not configured");
    }
    try {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          template, buildTemplateData(document.get()), null));
    } catch (JSONException e) {
      throw new OBException("Could not build document email payload for " + name, e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(name, tenantId, recordId),
        EmailThrottleRule.perTenant(100, 3600),
        EmailThrottleRule.perRecord(3, 3600),
        EmailThrottleRule.perRecipient(20, 3600),
        EmailThrottleRule.perDomain(200, 3600),
        EmailThrottleRule.global(2000, 60));
  }

  private JSONObject buildTemplateData(EmailDocumentRecord document) throws JSONException {
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
    data.put("download_link", document.getDownloadLink());
    return data;
  }

  private Optional<EmailDocumentRecord> resolveDocument(EmailContractCommand command) {
    if (documentResolver == null) {
      return Optional.empty();
    }
    return documentResolver.resolve(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID));
  }
}
