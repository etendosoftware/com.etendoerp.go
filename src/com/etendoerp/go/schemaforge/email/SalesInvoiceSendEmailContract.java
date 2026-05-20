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

final class SalesInvoiceSendEmailContract implements EmailContract {

  static final String NAME = "sales-invoice-send";
  private static final String TEMPLATE = "invoice";

  private final EmailContractDataResolver dataResolver;

  SalesInvoiceSendEmailContract(EmailContractDataResolver dataResolver) {
    this.dataResolver = dataResolver;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    return EmailContractCommandSupport.validateCommand(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailDocumentRecord> document = resolveInvoice(command);
    if (!document.isPresent()) {
      return EmailRecipientResolution.rejected(404, "Email document record was not found");
    }
    if (!EmailContractCommandSupport.isValidEmail(document.get().getRecipientEmail())) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(document.get().getRecipientEmail());
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    Optional<EmailDocumentRecord> document = resolveInvoice(command);
    if (!document.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Email document record was not found");
    }
    if (!EmailContractCommandSupport.isHttpUrl(document.get().getDownloadLink())) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Document download link is not configured");
    }
    try {
      JSONObject data = new JSONObject();
      data.put("name", StringUtils.defaultIfBlank(document.get().getRecipientName(), "Customer"));
      data.put("invoice_number", document.get().getDocumentNumber());
      data.put("amount", document.get().getAmount());
      data.put("download_link", document.get().getDownloadLink());
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          TEMPLATE, data, null));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build sales invoice email payload", e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    String tenantId = EmailContractCommandSupport.text(command, "tenantId");
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(NAME, tenantId, recordId),
        EmailThrottleRule.perTenant(100, 3600),
        EmailThrottleRule.perRecord(3, 3600),
        EmailThrottleRule.perRecipient(20, 3600),
        EmailThrottleRule.perDomain(200, 3600),
        EmailThrottleRule.global(2000, 60));
  }

  private Optional<EmailDocumentRecord> resolveInvoice(EmailContractCommand command) {
    return dataResolver.findSalesInvoice(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID));
  }
}
