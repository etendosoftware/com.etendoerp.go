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

import java.time.Instant;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.Invitation;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDeliveryPolicy;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;
import com.etendoerp.go.schemaforge.email.render.ValidityWindow;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailThrottleRule;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Versioned company-invitation transactional email contract (ETP-4894).
 *
 * Resolves recipient email and company name server-side from the persisted ETGO_INVITATION record.
 */
public final class CompanyInvitationEmailContract implements EmailContract {

  private static final String CONTRACT_NAME = "company-invitation";
  private static final String INVITATION_RECORD_NOT_FOUND = "Invitation record was not found";
  private static final String PROVIDER_TEMPLATE = "custom";
  private static final String FIELD_SUBJECT = "subject";
  private static final String FIELD_BODY = "body";

  @Override
  public String getName() {
    return CONTRACT_NAME;
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
        EmailContractCommandSupport.FIELD_RECORD_ID,
        EmailContractCommandSupport.FIELD_LINK);
    if (!validation.isAllowed()) {
      return validation;
    }
    return resolveInvitation(command).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(404, INVITATION_RECORD_NOT_FOUND);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<Invitation> invitation = resolveInvitation(command);
    if (!invitation.isPresent()) {
      return EmailRecipientResolution.rejected(404, INVITATION_RECORD_NOT_FOUND);
    }
    String email = invitation.get().getEmail();
    if (!EmailContractCommandSupport.isValidEmail(email)) {
      return EmailContractCommandSupport.invalidRecipient();
    }
    return EmailRecipientResolution.serverResolved(email);
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
    Optional<Invitation> invitation = resolveInvitation(command);
    if (!invitation.isPresent()) {
      return EmailContractResolution.rejected(404,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          INVITATION_RECORD_NOT_FOUND);
    }
    Invitation inv = invitation.get();
    String companyName = inv.getClient() != null && StringUtils.isNotBlank(inv.getClient().getName())
        ? inv.getClient().getName()
        : "Etendo Go";
    String language = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_LANGUAGE);

    try {
      JSONObject data = new JSONObject();
      data.put("companyName", companyName);
      data.put("link", link);
      data.put("email", inv.getEmail());
      if (language != null) {
        data.put("language", language);
      }
      populateContent(data, language, companyName, link, resolveRecipientName(inv), inv);
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          PROVIDER_TEMPLATE, data, null));
    } catch (JSONException e) {
      throw new OBException("Could not build company invitation email payload", e);
    }
  }

  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String invitationId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    return EmailContractCommandSupport.deliveryPolicy(
        EmailContractCommandSupport.idempotencyKey(CONTRACT_NAME, tenantId, invitationId),
        EmailThrottleRule.perTenant(30, 900),
        EmailThrottleRule.perRecipient(3, 900),
        EmailThrottleRule.perDomain(60, 900),
        EmailThrottleRule.global(500, 60));
  }

  private static Optional<Invitation> resolveInvitation(EmailContractCommand command) {
    String recordId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    if (StringUtils.isBlank(recordId)) {
      return Optional.empty();
    }
    return Optional.ofNullable(OBDal.getInstance().get(Invitation.class, recordId));
  }

  /**
   * Fills subject and body with the shared layout (ETP-5003).
   *
   * <p>Both the company name and the recipient name are emphasised, matching the design, so they
   * are escaped here and handed to the layout as pre-escaped markup. Every other string in the
   * message comes from the module's own properties catalog.</p>
   */
  private static void populateContent(JSONObject data, String language, String companyName,
      String link, String recipientName, Invitation invitation) throws JSONException {
    EmailContent.Builder content = EmailContent.builder();
    if (StringUtils.isNotBlank(recipientName)) {
      content.greetingHtml(
          EmailMessages.get("invitation.greeting", language, strong(recipientName)));
    }
    content.paragraphHtml(EmailMessages.get("invitation.body", language, strong(companyName)))
        .cta(EmailMessages.get("invitation.cta", language), link)
        .linkFallbackText(EmailMessages.get("link.fallback", language))
        .note(EmailMessages.get("invitation.note.expiry", language, validityDays(invitation)))
        .note(EmailMessages.get("invitation.note.ignore", language))
        .signature(EmailMessages.get("signature", language));

    data.put(FIELD_SUBJECT, EmailMessages.get("invitation.subject", language, companyName));
    data.put(FIELD_BODY, EmailLayout.render(content.build()));
  }

  /**
   * Days the invitation link stays valid, read from the record rather than restated as a literal.
   *
   * @param invitation the invitation record
   * @return whole days until expiry, at least one
   */
  private static long validityDays(Invitation invitation) {
    return ValidityWindow.daysUntil(Instant.now(),
        invitation.getExpiresAt() == null ? null : invitation.getExpiresAt().toInstant());
  }

  /**
   * Escapes a value and wraps it for emphasis inside layout copy.
   *
   * @param value the untrusted value
   * @return the emphasised markup
   */
  private static String strong(String value) {
    return "<strong>" + EmailEscape.escapeHtml(value) + "</strong>";
  }

  /**
   * Resolves the greeting name for the invited user, which is absent when the invitation targets an
   * email address with no Etendo user behind it yet. The layout simply omits the greeting then.
   *
   * @param invitation the invitation record
   * @return the recipient name, or {@code null}
   */
  private static String resolveRecipientName(Invitation invitation) {
    if (invitation.getUser() == null) {
      return null;
    }
    return StringUtils.trimToNull(invitation.getUser().getName());
  }
}
