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

final class AccountLinkEmailContract implements EmailContract {

  private final String name;
  private final String template;
  private final EmailContractDataResolver dataResolver;

  AccountLinkEmailContract(String name, String template, EmailContractDataResolver dataResolver) {
    this.name = name;
    this.template = template;
    this.dataResolver = dataResolver;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    return EmailContractCommandSupport.validateCommand(command,
        EmailContractCommandSupport.FIELD_ACCOUNT_ID, EmailContractCommandSupport.FIELD_LINK);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailContactRecord> contact = resolveAccount(command);
    if (!contact.isPresent()) {
      return EmailRecipientResolution.rejected(404, "Email account record was not found");
    }
    if (!EmailContractCommandSupport.isValidEmail(contact.get().getEmail())) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(contact.get().getEmail());
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    String link = EmailContractCommandSupport.text(command, EmailContractCommandSupport.FIELD_LINK);
    if (!EmailContractCommandSupport.isHttpUrl(link)) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Email contract link must be an absolute HTTP URL");
    }
    Optional<EmailContactRecord> contact = resolveAccount(command);
    if (!contact.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Email account record was not found");
    }
    try {
      JSONObject data = new JSONObject();
      data.put("name", StringUtils.defaultIfBlank(contact.get().getName(), "User"));
      data.put("link", link);
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          template, data, null));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build account email payload", e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String accountId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_ACCOUNT_ID);
    String tenantId = EmailContractCommandSupport.text(command, "tenantId");
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(name, tenantId, accountId),
        EmailThrottleRule.perTenant(30, 900),
        EmailThrottleRule.perRecipient("reset-password".equals(name) ? 3 : 2, 900),
        EmailThrottleRule.perDomain(60, 900),
        EmailThrottleRule.global(500, 60));
  }

  private Optional<EmailContactRecord> resolveAccount(EmailContractCommand command) {
    return dataResolver.findAccountContact(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_ACCOUNT_ID));
  }
}
