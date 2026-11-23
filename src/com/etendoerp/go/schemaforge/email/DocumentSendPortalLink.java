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

import java.time.Instant;
import java.util.Optional;

import com.etendoerp.go.portal.PortalDocumentKind;
import com.etendoerp.go.portal.PortalLinkService;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;
import com.etendoerp.go.schemaforge.email.render.ValidityWindow;

/**
 * The customer portal link of a document email (ETP-6209): minting it, the sentence that announces
 * it and the button it turns into. Kept out of {@link DefaultDocumentSendEmailContract} so the
 * shared send path only asks whether its contract declared a portal document kind.
 */
final class DocumentSendPortalLink {

  private DocumentSendPortalLink() {
  }

  /**
   * Mints the pending link of this send, when the contract declared a portal document kind. It
   * becomes usable only in {@code onDelivered}, i.e. only if the email leaves.
   *
   * @param service the portal link service
   * @param kind the contract's declared kind, {@code null} for a contract with no portal link
   * @param recordId the document
   * @param recipients the send's recipients (To and CC renew the link later)
   * @param language the email's language
   * @param contractName the sending contract
   * @return the minted link, or empty
   */
  static Optional<PortalLinkService.MintedLink> mint(PortalLinkService service,
      PortalDocumentKind kind, String recordId, EmailRecipientSet recipients, String language,
      String contractName) {
    if (kind == null) {
      return Optional.empty();
    }
    return service.mintForSend(kind, recordId, recipients.getTo(), recipients.getCc(),
        language, contractName);
  }

  /**
   * Appends the portal sentence to the contract's own extra paragraph, if any.
   *
   * @param extraParagraphHtml the contract's extra paragraph, or {@code null}
   * @param link the minted link, or {@code null}
   * @param language the email's language
   * @return both paragraphs, either one, or empty
   */
  static Optional<String> withParagraph(String extraParagraphHtml,
      PortalLinkService.MintedLink link, String language) {
    String portal = link == null ? null : paragraph(link, language);
    if (extraParagraphHtml == null) {
      return Optional.ofNullable(portal);
    }
    return Optional.of(portal == null ? extraParagraphHtml
        : extraParagraphHtml + "<br><br>" + portal);
  }

  /**
   * The email's one button: the portal when a link was minted, else the document download
   * (ETP-6209 Part B: with a link, the portal shows the same printable).
   *
   * @param link the minted link, or {@code null}
   * @param downloadLink the document's download link
   * @param language the email's language
   * @return the button
   */
  static CallToAction callToAction(PortalLinkService.MintedLink link, String downloadLink,
      String language) {
    return new CallToAction(
        EmailMessages.get(link != null ? "document.portal.cta" : "document.cta", language),
        link != null ? link.getUrl() : downloadLink, link != null);
  }

  /**
   * Renders the portal sentence: where the document can be seen and for how long the link works.
   * The link itself is the email's call-to-action button, so the sentence carries no anchor. The
   * copy is escaped before {@code applyBold} (the order {@code EmailEscape#applyBold} documents as
   * mandatory) and the validity interpolates the configured lifetime, never a literal.
   */
  private static String paragraph(PortalLinkService.MintedLink link, String language) {
    Instant now = Instant.now();
    long hours = ValidityWindow.hoursUntil(now, now.plus(link.getValidity()));
    return EmailEscape.applyBold(EmailEscape.escapeHtml(
        EmailMessages.get("document.portal.intro", language, hours)));
  }

  /** The email's one button: its label, where it leads and whether that is the portal. */
  static final class CallToAction {
    private final String label;
    private final String url;
    private final boolean portal;

    CallToAction(String label, String url, boolean portal) {
      this.label = label;
      this.url = url;
      this.portal = portal;
    }

    /**
     * Whether the button leads to the customer portal, i.e. the email carries a portal link. Only
     * then does it close with the Etendo footer (ETP-6209); with the {@code bp-portal-link} flag
     * off no link is minted and the email stays exactly as it was.
     *
     * @return {@code true} for the portal button
     */
    boolean isPortal() {
      return portal;
    }

    String getLabel() {
      return label;
    }

    String getUrl() {
      return url;
    }
  }
}
