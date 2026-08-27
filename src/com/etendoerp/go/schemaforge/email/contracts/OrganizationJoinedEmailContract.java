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

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

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
import com.etendoerp.go.schemaforge.email.render.AccountEmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;

/**
 * Tells an invited user they are now part of an organization (ETP-5003).
 *
 * <p>Sent once the invitation is accepted, which is a different moment from the welcome email: the
 * welcome confirms an account exists, this one confirms the account belongs somewhere. An operator
 * joining an admin's environment receives both, in that order, and neither points at onboarding —
 * an operator never runs it.</p>
 */
final class OrganizationJoinedEmailContract implements EmailContract {

  static final String NAME = "organization-joined";
  private static final String ACCOUNT_RECORD_NOT_FOUND = "Email account record was not found";
  /** The provider's bring-your-own-content template: the layout is rendered here, not there. */
  private static final String CONTENT_TEMPLATE = "custom";
  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";
  private static final String FIELD_COMPANY_NAME = "companyName";
  private static final String DASHBOARD_LINK_PATH = "dashboard";

  private final EmailContractDataResolver dataResolver;

  OrganizationJoinedEmailContract(EmailContractDataResolver dataResolver) {
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
    Optional<EmailContactRecord> contact = resolveAccount(command);
    if (!contact.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, ACCOUNT_RECORD_NOT_FOUND);
    }
    String companyName = StringUtils.trimToNull(
        EmailContractCommandSupport.text(command, FIELD_COMPANY_NAME));
    if (companyName == null) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "Email contract requires the organization name");
    }
    try {
      String language = EmailContractCommandSupport.text(command,
          EmailContractCommandSupport.FIELD_LANGUAGE);
      String link = resolveDashboardLink();

      JSONObject data = new JSONObject();
      data.put("name", StringUtils.defaultIfBlank(contact.get().getName(), "User"));
      data.put(FIELD_COMPANY_NAME, companyName);
      if (link != null) {
        data.put("link", link);
      }
      if (language != null) {
        data.put("language", language);
      }
      data.put(FIELD_SUBJECT, AccountEmailContent.subject(NAME, language, companyName));
      data.put(FIELD_BODY, EmailLayout.render(AccountEmailContent.build(NAME, language,
          contact.get().getName(), link, emphasised(companyName))));
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          CONTENT_TEMPLATE, data, null));
    } catch (JSONException e) {
      throw new OBException("Could not build organization joined email payload", e);
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
    // Keyed on the invitation record, so accepting once sends once even if the accept endpoint is
    // retried by a flaky client.
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(NAME, tenantId, recordId),
        EmailThrottleRule.perTenant(30, 900),
        EmailThrottleRule.perRecipient(2, 900),
        EmailThrottleRule.perDomain(60, 900),
        EmailThrottleRule.global(500, 60));
  }

  private Optional<EmailContactRecord> resolveAccount(EmailContractCommand command) {
    return dataResolver.findAccountContact(EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_ACCOUNT_ID));
  }

  /**
   * Dashboard link for the call to action, or {@code null} when the public app URL is not
   * configured — the email is still worth sending without a button.
   */
  private static String resolveDashboardLink() {
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    return baseUrl == null ? null : PublicUrlResolver.appendPath(baseUrl, DASHBOARD_LINK_PATH);
  }

  private static String emphasised(String value) {
    return "<strong>" + EmailEscape.escapeHtml(value) + "</strong>";
  }
}
