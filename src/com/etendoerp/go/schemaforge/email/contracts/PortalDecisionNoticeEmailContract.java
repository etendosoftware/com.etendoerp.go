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

import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.portal.PortalDecisionScope;
import com.etendoerp.go.portal.PortalDecisionService;
import com.etendoerp.go.portal.PortalDocumentKind;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailDeliveryPolicy;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;
import com.etendoerp.go.schemaforge.email.EmailSendContext;
import com.etendoerp.go.schemaforge.email.EmailThrottleRule;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.email.render.EmailContent;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailLayout;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;

/**
 * Tells the user who sent a quotation that the customer confirmed a decision on it from the portal
 * (ETP-6209 Part C). Sent after the confirmation's commit; never fails it.
 *
 * <p><b>Recipient:</b> the sender's ETGO_ACCOUNT email ({@link PortalDecisionService#senderEmail}),
 * re-derived from the decision named in the command — never an address from the caller. Language:
 * the decision's (the link's email language). Only the portal may send it
 * ({@link PortalDecisionScope}). Filed in the quotation's send history.</p>
 */
final class PortalDecisionNoticeEmailContract implements EmailContract {

  private static final String CONTENT_TEMPLATE = "custom";
  private static final String KEY_PREFIX = "portal-quotation-decision.";
  private static final String MESSAGE_NO_DECISION = "The confirmed decision was not found";

  private final PortalDecisionService decisionService;
  private final BooleanSupplier portalCaller;

  PortalDecisionNoticeEmailContract() {
    this(new PortalDecisionService(), PortalDecisionScope::isActive);
  }

  PortalDecisionNoticeEmailContract(PortalDecisionService decisionService,
      BooleanSupplier portalCaller) {
    this.decisionService = decisionService;
    this.portalCaller = portalCaller;
  }

  @Override
  public String getName() {
    return PortalDecisionService.NOTICE_CONTRACT;
  }

  @Override
  public boolean logsSendHistory() {
    return true;
  }

  @Override
  public String getSpecName(EmailSendContext context) {
    return PortalDocumentKind.QUOTATION.getSpecName();
  }

  @Override
  public EmailAuthorizationResult authorize(EmailContractCommand command) {
    EmailAuthorizationResult edits = EmailContractCommandSupport.rejectRecipientEditsIfPresent(command);
    if (!edits.isAllowed()) {
      return edits;
    }
    EmailAuthorizationResult message = EmailContractCommandSupport.rejectMessageEditsIfPresent(command);
    if (!message.isAllowed()) {
      return message;
    }
    if (!portalCaller.getAsBoolean()) {
      return EmailAuthorizationResult.rejected(403,
          PortalDecisionConfirmEmailContract.MESSAGE_PORTAL_ONLY);
    }
    return find(command) != null ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(409, MESSAGE_NO_DECISION);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    String email = withAdmin(() -> decisionService.senderEmail(find(command)));
    return email == null
        ? EmailRecipientResolution.noRecipient("The quotation's sender has no account email")
        : EmailRecipientResolution.serverResolved(EmailRecipientSet.singleTo(email));
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    PortalDecisionService.Decision decision = find(command);
    if (decision == null) {
      return EmailContractResolution.rejected(409,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, MESSAGE_NO_DECISION);
    }
    String language = decision.getLanguage();
    String[] names = withAdmin(() -> {
      Order quotation = OBDal.getInstance().get(Order.class, decision.getQuotationId());
      return new String[] {
          quotation == null ? "" : StringUtils.defaultString(quotation.getDocumentNo()),
          quotation == null || quotation.getBusinessPartner() == null ? ""
              : StringUtils.defaultString(quotation.getBusinessPartner().getName()) };
    });
    String outcome = EmailMessages.get(KEY_PREFIX + "outcome." + decision.getDecision(), language);
    EmailContent.Builder content = EmailContent.builder()
        .paragraphHtml(EmailEscape.applyBold(EmailMessages.get(KEY_PREFIX + "notice.body",
            language, EmailEscape.escapeHtml(names[1]), EmailEscape.escapeHtml(outcome),
            EmailEscape.escapeHtml(names[0]))));
    if (decision.getReasonCode() != null) {
      String reason = EmailMessages.get(KEY_PREFIX + "reason." + decision.getReasonCode(), language);
      content.detail(EmailMessages.get(KEY_PREFIX + "reason.label", language),
          decision.getReasonText() == null ? reason : reason + " — " + decision.getReasonText());
    }
    content.note(EmailMessages.get(KEY_PREFIX + "notice.note", language))
        .signature(EmailMessages.get("signature", language));
    try {
      JSONObject data = new JSONObject();
      data.put("subject", EmailMessages.get(KEY_PREFIX + "notice.subject", language, names[1],
          outcome, names[0]));
      data.put("body", EmailLayout.render(content.build()));
      EmailRecipientSet recipients = recipient.getRecipientSet() != null
          ? recipient.getRecipientSet() : EmailRecipientSet.singleTo(recipient.getRecipient());
      return EmailContractResolution.ready(new EmailProviderRequest(recipients, CONTENT_TEMPLATE,
          data, null).withSendCorrelationId(decision.getId()));
    } catch (JSONException e) {
      throw new OBException("Could not build the decision notice email payload", e);
    }
  }

  /** One notice per confirmed decision. */
  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    String key = EmailContractCommandSupport.idempotencyKey(getName(), tenantId,
        EmailContractCommandSupport.text(command, PortalDecisionService.FIELD_DECISION_ID));
    return EmailContractCommandSupport.deliveryPolicy(key,
        EmailThrottleRule.perRecipient(200, 3600),
        EmailThrottleRule.perTenant(500, 3600),
        EmailThrottleRule.global(2000, 60));
  }

  private PortalDecisionService.Decision find(EmailContractCommand command) {
    String id = StringUtils.trimToNull(EmailContractCommandSupport.text(command,
        PortalDecisionService.FIELD_DECISION_ID));
    return id == null ? null : withAdmin(() -> decisionService.findById(id));
  }

  private static <T> T withAdmin(java.util.function.Supplier<T> body) {
    OBContext.setAdminMode(true);
    try {
      return body.get();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
