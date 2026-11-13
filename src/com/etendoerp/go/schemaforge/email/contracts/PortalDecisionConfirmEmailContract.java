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
import com.etendoerp.go.portal.PortalLink;
import com.etendoerp.go.portal.PortalLinkService;
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
 * Asks the customer to confirm the accept / reject they requested in the portal (ETP-6209 Part C):
 * the "second factor" of the decision.
 *
 * <p><b>One recipient:</b> the first To of the portal link's root send (user decision) — never CC,
 * never an address from the caller. The command names the session's link; the recipient is
 * re-derived here from that link's root, so a forged command cannot redirect it.</p>
 *
 * <p><b>Only the portal may send it</b> ({@link PortalDecisionScope}, set by
 * {@link PortalDecisionService} after its scope and state checks), like the renewal. The decision
 * row is minted pending in {@link #resolve} and becomes usable in {@link #onDelivered}, so the
 * token exists only in the email that left. Filed in the quotation's own send history.</p>
 *
 * <p>Portal bounded context: the shared send path is not touched and names no window.</p>
 */
final class PortalDecisionConfirmEmailContract implements EmailContract {

  static final String MESSAGE_PORTAL_ONLY = "Quotation decisions are sent by the customer portal only";
  static final String MESSAGE_NO_LINK = "The portal link of the decision was not found";
  private static final String CONTENT_TEMPLATE = "custom";
  private static final String KEY_PREFIX = "portal-quotation-decision.";

  private final PortalLinkService linkService;
  private final PortalDecisionService decisionService;
  private final BooleanSupplier portalCaller;

  PortalDecisionConfirmEmailContract() {
    this(new PortalLinkService(), new PortalDecisionService(), PortalDecisionScope::isActive);
  }

  PortalDecisionConfirmEmailContract(PortalLinkService linkService,
      PortalDecisionService decisionService, BooleanSupplier portalCaller) {
    this.linkService = linkService;
    this.decisionService = decisionService;
    this.portalCaller = portalCaller;
  }

  @Override
  public String getName() {
    return PortalDecisionService.CONFIRM_CONTRACT;
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
      return EmailAuthorizationResult.rejected(403, MESSAGE_PORTAL_ONLY);
    }
    return findLink(command).filter(PortalLink::isSent).isPresent()
        ? EmailAuthorizationResult.allowed()
        : EmailAuthorizationResult.rejected(409, MESSAGE_NO_LINK);
  }

  @Override
  public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
    Optional<PortalLink> link = findLink(command);
    if (!link.isPresent()) {
      return EmailRecipientResolution.rejected(409, MESSAGE_NO_LINK);
    }
    String recipient = decisionService.confirmationRecipient(link.get().getRootLinkId());
    return recipient == null
        ? EmailRecipientResolution.noRecipient("The portal link's send has no To recipient")
        : EmailRecipientResolution.serverResolved(EmailRecipientSet.singleTo(recipient));
  }

  @Override
  public EmailContractResolution resolve(EmailContractCommand command,
      EmailRecipientResolution recipient) {
    Optional<PortalLink> link = findLink(command);
    String quotationId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_RECORD_ID);
    String decision = EmailContractCommandSupport.text(command, PortalDecisionService.FIELD_DECISION);
    String reasonCode = EmailContractCommandSupport.text(command,
        PortalDecisionService.FIELD_REASON_CODE);
    String reasonText = EmailContractCommandSupport.text(command,
        PortalDecisionService.FIELD_REASON_TEXT);
    EmailRecipientSet recipients = recipient.getRecipientSet() != null
        ? recipient.getRecipientSet() : EmailRecipientSet.singleTo(recipient.getRecipient());
    String to = recipients.getTo().isEmpty() ? null : recipients.getTo().get(0);
    if (!link.isPresent() || quotationId == null || decision == null || to == null) {
      return EmailContractResolution.rejected(409,
          TransactionalEmailService.STATUS_VALIDATION_FAILED, MESSAGE_NO_LINK);
    }
    PortalLink session = link.get();
    String language = session.getLanguage();
    Optional<PortalDecisionService.MintedDecision> minted = decisionService.mint(session.getId(),
        quotationId, decision, reasonCode, reasonText, to, language);
    if (!minted.isPresent()) {
      return EmailContractResolution.rejected(400,
          TransactionalEmailService.STATUS_VALIDATION_FAILED,
          "A decision link could not be built: the app base URL may not be configured");
    }
    String tenantName = StringUtils.defaultString(session.getClientName());
    String documentNumber = documentNumber(quotationId);
    String action = EmailMessages.get(KEY_PREFIX + "action." + decision, language);
    EmailContent.Builder content = EmailContent.builder()
        .paragraphHtml(EmailEscape.applyBold(EmailMessages.get(KEY_PREFIX + "confirm.body",
            language, EmailEscape.escapeHtml(action), EmailEscape.escapeHtml(documentNumber),
            EmailEscape.escapeHtml(tenantName))));
    if (reasonCode != null) {
      String reason = EmailMessages.get(KEY_PREFIX + "reason." + reasonCode, language);
      content.detail(EmailMessages.get(KEY_PREFIX + "reason.label", language),
          reasonText == null ? reason : reason + " — " + reasonText);
    }
    // The button names the decision ("Confirmar rechazo"): one click on it applies it (the
    // landing auto-submits the confirmation; a bare GET of the link applies nothing).
    content.cta(EmailMessages.get(KEY_PREFIX + "confirm.cta." + decision, language),
            minted.get().getUrl())
        .linkFallbackText(EmailMessages.get("link.fallback", language))
        .note(EmailMessages.get(KEY_PREFIX + "confirm.note", language))
        .signature(EmailMessages.get("signature", language));
    try {
      JSONObject data = new JSONObject();
      String name = StringUtils.trimToNull(session.getBusinessPartnerName());
      if (name != null) {
        data.put("name", name);
      }
      data.put("link", minted.get().getUrl());
      data.put("subject", EmailMessages.get(KEY_PREFIX + "confirm.subject", language,
          documentNumber));
      data.put("body", EmailLayout.render(content.build()));
      return EmailContractResolution.ready(new EmailProviderRequest(recipients, CONTENT_TEMPLATE,
          data, null).withSendCorrelationId(minted.get().getId()));
    } catch (JSONException e) {
      throw new OBException("Could not build the decision confirmation email payload", e);
    }
  }

  /**
   * One email per decision request. The product's limit (the per-quotation cool-down) is applied
   * by {@link PortalDecisionService#request} first; these throttles are the email system's own
   * backstop, at the document emails' ceilings.
   */
  @Override
  public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
      EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
    String tenantId = EmailContractCommandSupport.text(command,
        EmailContractCommandSupport.FIELD_TENANT_ID);
    String decisionId = providerRequest == null ? null : providerRequest.getSendCorrelationId();
    String key = EmailContractCommandSupport.idempotencyKey(getName(), tenantId,
        StringUtils.defaultIfBlank(decisionId, EmailContractCommandSupport.text(command,
            EmailContractCommandSupport.FIELD_RECORD_ID)));
    return EmailContractCommandSupport.deliveryPolicy(key,
        EmailThrottleRule.perRecipient(20, 3600),
        EmailThrottleRule.perTenant(100, 3600),
        EmailThrottleRule.global(2000, 60));
  }

  @Override
  public void onDelivered(EmailSendContext context, String sendLogId) {
    String decisionId = context == null || context.getProviderRequest() == null ? null
        : context.getProviderRequest().getSendCorrelationId();
    if (decisionId != null) {
      decisionService.markSent(decisionId);
    }
  }

  private Optional<PortalLink> findLink(EmailContractCommand command) {
    String linkId = StringUtils.trimToNull(
        EmailContractCommandSupport.text(command, PortalLinkService.FIELD_LINK_ID));
    return linkId == null ? Optional.empty() : linkService.findLink(linkId);
  }

  static String documentNumber(String quotationId) {
    OBContext.setAdminMode(true);
    try {
      Order quotation = OBDal.getInstance().get(Order.class, quotationId);
      return quotation == null ? "" : StringUtils.defaultString(quotation.getDocumentNo());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
