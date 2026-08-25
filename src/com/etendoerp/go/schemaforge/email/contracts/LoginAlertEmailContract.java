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
import com.etendoerp.go.schemaforge.email.render.AccountEmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

final class LoginAlertEmailContract implements EmailContract {

  static final String NAME = "login-alert";
  /** The provider's bring-your-own-content template: the layout is rendered here, not there. */
  private static final String CONTENT_TEMPLATE = "custom";
  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";
  private static final String USER_RECORD_NOT_FOUND = "Email user record was not found";

  private final EmailContractDataResolver dataResolver;

  LoginAlertEmailContract(EmailContractDataResolver dataResolver) {
    this.dataResolver = dataResolver;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult editsRejection =
        EmailContractCommandSupport.rejectRecipientEditsIfPresent(command);
    if (!editsRejection.isAllowed()) {
      return editsRejection;
    }
    EmailAuthorizationResult messageRejection =
        EmailContractCommandSupport.rejectMessageEditsIfPresent(command);
    if (!messageRejection.isAllowed()) {
      return messageRejection;
    }
    EmailAuthorizationResult validation = EmailContractCommandSupport.validateCommand(command,
        EmailContractCommandSupport.FIELD_USER_ID);
    if (!validation.isAllowed()) {
      return validation;
    }
    return resolveUser(command).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(404, USER_RECORD_NOT_FOUND);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<EmailContactRecord> contact = resolveUser(command);
    if (!contact.isPresent()) {
      return EmailRecipientResolution.rejected(404, USER_RECORD_NOT_FOUND);
    }
    if (!EmailContractCommandSupport.isValidEmail(contact.get().getEmail())) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(contact.get().getEmail());
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    Optional<EmailContactRecord> contact = resolveUser(command);
    if (!contact.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          USER_RECORD_NOT_FOUND);
    }
    try {
      String language = EmailContractCommandSupport.text(command,
          EmailContractCommandSupport.FIELD_LANGUAGE);
      String ip = StringUtils.defaultIfBlank(EmailContractCommandSupport.text(command,
          EmailContractCommandSupport.FIELD_IP), "unknown");
      String date = StringUtils.defaultIfBlank(EmailContractCommandSupport.text(command,
          EmailContractCommandSupport.FIELD_DATE), now());

      JSONObject data = new JSONObject();
      data.put("name", StringUtils.defaultIfBlank(contact.get().getName(), "User"));
      data.put("ip", ip);
      data.put("date", date);
      data.put(FIELD_SUBJECT, AccountEmailContent.subject(NAME, language));
      data.put(FIELD_BODY, EmailLayout.render(AccountEmailContent.buildWithNotes(NAME, language,
          contact.get().getName(), null, new String[] { "note.warning" }, null, ip, date)));
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          CONTENT_TEMPLATE, data, null));
    } catch (JSONException e) {
      throw new OBException("Could not build login alert email payload", e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String eventId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_LOGIN_EVENT_ID);
    String userId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_USER_ID);
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    String idempotencyKey = eventId == null ? null
        : EmailContractCommandSupport.idempotencyKey(NAME, tenantId, userId + ":" + eventId);
    return EmailContractCommandSupport.deliveryPolicy(idempotencyKey,
        EmailThrottleRule.perUser(10, 3600),
        EmailThrottleRule.perRecipient(10, 3600),
        EmailThrottleRule.perDomain(100, 3600),
        EmailThrottleRule.global(1000, 60));
  }

  private Optional<EmailContactRecord> resolveUser(EmailContractCommand command) {
    return dataResolver.findUserContact(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_USER_ID));
  }

  private static String now() {
    return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }
}
