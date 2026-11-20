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
import com.etendoerp.go.portal.PortalLogoReference;
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
import com.etendoerp.go.schemaforge.email.render.EmailFooter;
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
  private static final String CONFIRM_PREFIX = KEY_PREFIX + "confirm.";
  /**
   * Longest "Otros" text shown in the Motivo column, ellipsis included: one of three equal columns
   * of a 620px card, so a long text must not stretch the table. The full text stays in the
   * decision row and in the quotation's notes.
   */
  static final int MAX_REASON_TEXT_CHARS = 80;
  private static final String ELLIPSIS = "\u2026";

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
    QuotationFacts quotation = quotationFacts(quotationId);
    EmailContent content = buildContent(decision, language, tenantName,
        issuer(session, tenantName), quotation, reasonValue(reasonCode, reasonText, language),
        minted.get().getUrl());
    try {
      JSONObject data = new JSONObject();
      String name = StringUtils.trimToNull(session.getBusinessPartnerName());
      if (name != null) {
        data.put("name", name);
      }
      data.put("link", minted.get().getUrl());
      data.put("subject", EmailMessages.get(CONFIRM_PREFIX + "subject." + decision, language,
          quotation.number));
      data.put("body", EmailLayout.render(content));
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

  /**
   * Composes the card (ETP-6209 design): who asks, what is confirmed, the quotation's facts, the
   * button and what to do when the request is not theirs. Accept shows the total; reject shows the
   * customer's reason instead (PO decision: exactly the Figma, no amount).
   */
  private static EmailContent buildContent(String decision, String language, String tenantName,
      EmailContent.Builder issuer, QuotationFacts quotation, String reason, String url) {
    boolean accept = PortalDecisionService.DECISION_ACCEPT.equals(decision);
    EmailContent.Builder content = issuer
        .badge(EmailMessages.get(CONFIRM_PREFIX + "badge", language))
        .title(EmailMessages.get(CONFIRM_PREFIX + "title." + decision, language))
        .subtitle(EmailMessages.get(CONFIRM_PREFIX + "subtitle." + decision, language))
        .summaryTitle(EmailMessages.get(CONFIRM_PREFIX + "summary.title", language))
        .detail(EmailMessages.get(CONFIRM_PREFIX + "summary.company", language), tenantName)
        .detail(EmailMessages.get(CONFIRM_PREFIX + "summary.number", language), quotation.number);
    if (accept) {
      content.detail(EmailMessages.get(CONFIRM_PREFIX + "summary.total", language),
          quotation.total);
    } else {
      content.detail(EmailMessages.get(KEY_PREFIX + "reason.label", language), reason);
    }
    // The button names the decision ("Confirmar rechazo"): one click on it applies it (the
    // landing auto-submits the confirmation; a bare GET of the link applies nothing).
    return content.ctaNote(EmailMessages.get(CONFIRM_PREFIX + "note." + decision, language))
        .cta(EmailMessages.get(CONFIRM_PREFIX + "cta." + decision, language), url)
        .linkFallbackText(EmailMessages.get(CONFIRM_PREFIX + "fallback", language))
        .warning(EmailMessages.get(CONFIRM_PREFIX + "warning.title", language),
            EmailMessages.get(CONFIRM_PREFIX + "warning." + decision, language))
        .footer(EmailFooter.etendo(accept ? EmailFooter.Piece.DECISION_ACCEPT
            : EmailFooter.Piece.DECISION_REJECT, language))
        .build();
  }

  /**
   * Starts the card with its issuer: the company name and, when the tenant has a PNG/JPEG logo,
   * its token-free public URL ({@link PortalLogoReference}) — never the portal's own
   * {@code /logo}, whose token must not travel in an image request. No logo: the initials.
   */
  private static EmailContent.Builder issuer(PortalLink session, String tenantName) {
    EmailContent.Builder content = EmailContent.builder();
    Optional<PortalLogoReference.PublicLogo> logo = PortalLogoReference.find(
        session.getClientId(), session.getOrganizationId());
    if (logo.isPresent()) {
      content.issuer(tenantName, logo.get().getUrl())
          .issuerLogoSize(logo.get().getWidth(), logo.get().getHeight());
    } else {
      content.issuer(tenantName, null);
    }
    return content;
  }

  /**
   * The Motivo column: the customer's portal reason in the email's language — never the tenant's
   * internal reject reason. For "Otros" it is the text the customer typed (whitespace collapsed,
   * cut to {@value #MAX_REASON_TEXT_CHARS} characters with an ellipsis; the layout escapes it),
   * falling back to the "Otros" label when that text is empty.
   *
   * @param reasonCode the portal reason code, or {@code null} (accept)
   * @param reasonText the "Otros" text, or {@code null}
   * @param language the email's language
   * @return the value, or {@code null} when there is no reason
   */
  static String reasonValue(String reasonCode, String reasonText, String language) {
    if (reasonCode == null) {
      return null;
    }
    String text = StringUtils.trimToNull(StringUtils.normalizeSpace(reasonText));
    if (PortalDecisionService.REASON_OTHER.equals(reasonCode) && text != null) {
      return StringUtils.abbreviate(text, ELLIPSIS, MAX_REASON_TEXT_CHARS);
    }
    return EmailMessages.get(KEY_PREFIX + "reason." + reasonCode, language);
  }

  /**
   * The quotation's number and total, read in admin mode (the portal has no Etendo session). The
   * total is formatted as the app shows it ("2.450,00 €", {@link EmailAmountFormat}).
   */
  static QuotationFacts quotationFacts(String quotationId) {
    OBContext.setAdminMode(true);
    try {
      Order quotation = OBDal.getInstance().get(Order.class, quotationId);
      if (quotation == null) {
        return new QuotationFacts("", null);
      }
      return new QuotationFacts(StringUtils.defaultString(quotation.getDocumentNo()),
          EmailAmountFormat.format(quotation.getGrandTotalAmount(), quotation.getCurrency()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** What the card states about the quotation. */
  static final class QuotationFacts {
    private final String number;
    private final String total;

    QuotationFacts(String number, String total) {
      this.number = number;
      this.total = total;
    }
  }
}
