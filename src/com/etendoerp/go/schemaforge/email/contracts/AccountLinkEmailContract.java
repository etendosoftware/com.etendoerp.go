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

package com.etendoerp.go.schemaforge.email.contracts;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContactRecord;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractDataResolver;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDeliveryPolicy;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailThrottleRule;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

final class AccountLinkEmailContract implements EmailContract {

  private static final String ACCOUNT_RECORD_NOT_FOUND = "Email account record was not found";

  private final String name;
  private final String template;
  private final EmailContractDataResolver dataResolver;
  private final int recipientThrottleLimit;
  private final int throttleWindowSeconds;
  private final String configuredLinkPath;

  AccountLinkEmailContract(String name, String template, EmailContractDataResolver dataResolver,
      int recipientThrottleLimit, int throttleWindowSeconds) {
    this(name, template, dataResolver, recipientThrottleLimit, throttleWindowSeconds, null);
  }

  AccountLinkEmailContract(String name, String template, EmailContractDataResolver dataResolver,
      int recipientThrottleLimit, int throttleWindowSeconds, String configuredLinkPath) {
    this.name = name;
    this.template = template;
    this.dataResolver = dataResolver;
    this.recipientThrottleLimit = recipientThrottleLimit;
    this.throttleWindowSeconds = throttleWindowSeconds;
    this.configuredLinkPath = configuredLinkPath;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult validation = configuredLinkPath == null
        ? EmailContractCommandSupport.validateCommand(command,
            EmailContractCommandSupport.FIELD_ACCOUNT_ID,
            EmailContractCommandSupport.FIELD_LINK)
        : EmailContractCommandSupport.validateCommand(command,
            EmailContractCommandSupport.FIELD_ACCOUNT_ID);
    if (!validation.isAllowed()) {
      return validation;
    }
    return resolveAccount(command).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(404, ACCOUNT_RECORD_NOT_FOUND);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailContactRecord> contact = resolveAccount(command);
    if (!contact.isPresent()) {
      return EmailRecipientResolution.rejected(404, ACCOUNT_RECORD_NOT_FOUND);
    }
    if (!EmailContractCommandSupport.isValidEmail(contact.get().getEmail())) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(contact.get().getEmail());
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    String link = resolveLink(command);
    if (configuredLinkPath != null && link == null) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Configured app base URL is required for this email contract");
    }
    if (!EmailContractCommandSupport.isHttpUrl(link)) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Email contract link must be an absolute HTTP URL");
    }
    Optional<EmailContactRecord> contact = resolveAccount(command);
    if (!contact.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          ACCOUNT_RECORD_NOT_FOUND);
    }
    try {
      JSONObject data = new JSONObject();
      data.put("name", StringUtils.defaultIfBlank(contact.get().getName(), "User"));
      data.put("link", link);
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          template, data, null));
    } catch (JSONException e) {
      throw new OBException("Could not build account email payload", e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String accountId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_ACCOUNT_ID);
    String recordId = EmailContractCommandSupport.firstNonBlank(
        EmailContractCommandSupport.text(command, EmailContractCommandSupport.FIELD_RECORD_ID),
        accountId);
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(name, tenantId, recordId),
        EmailThrottleRule.perTenant(30, 900),
        EmailThrottleRule.perRecipient(recipientThrottleLimit, throttleWindowSeconds),
        EmailThrottleRule.perDomain(60, 900),
        EmailThrottleRule.global(500, 60));
  }

  private Optional<EmailContactRecord> resolveAccount(EmailContractCommand command) {
    return dataResolver.findAccountContact(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_ACCOUNT_ID));
  }

  private String resolveLink(EmailContractCommand command) {
    if (configuredLinkPath == null) {
      return EmailContractCommandSupport.text(command, EmailContractCommandSupport.FIELD_LINK);
    }
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (baseUrl == null) {
      return null;
    }
    return PublicUrlResolver.appendPath(baseUrl, configuredLinkPath);
  }
}
