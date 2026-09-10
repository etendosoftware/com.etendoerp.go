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

import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.portal.PortalAccessService;
import com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;
import com.etendoerp.go.schemaforge.email.render.EmailEscape;
import com.etendoerp.go.schemaforge.email.render.EmailMessages;
import com.etendoerp.go.schemaforge.email.render.EmailPalette;

/**
 * Contract for sending sales invoice document notifications.
 *
 * <p>The one document-send contract that can carry a Business Partner self-service portal link
 * (ETP-5267). It is the natural trigger: the invoice email is already going to this Business
 * Partner, so the link reaches them without their ever having to request access.
 */
public final class SalesInvoiceSendEmailContract extends DefaultDocumentSendEmailContract {

  static final String NAME = "sales-invoice-send";

  private static final String LINK_STYLE =
      "\" style=\"color:" + EmailPalette.LIGHT_LINK + ";text-decoration:none;\">";

  private final PortalAccessService portalAccessService;

  /**
   * Creates the sales invoice send contract.
   *
   * @param documentResolver resolver for trusted sales invoice records
   */
  public SalesInvoiceSendEmailContract(EmailDocumentRecordResolver documentResolver) {
    this(documentResolver, new PortalAccessService());
  }

  SalesInvoiceSendEmailContract(EmailDocumentRecordResolver documentResolver,
      PortalAccessService portalAccessService) {
    // ETP-5003 — off the provider's branded "invoice" template and onto the shared layout, so an
    // edited send no longer downgrades the design. invoice_number and the amount stay in the
    // payload for the gateway's own records.
    super(NAME, CONTENT_TEMPLATE, "Sales Invoice", "invoice_number", true,
        Objects.requireNonNull(documentResolver, "documentResolver"));
    this.portalAccessService = Objects.requireNonNull(portalAccessService, "portalAccessService");
  }

  /**
   * Appends the Business Partner's portal link, when this sender is configured to send portal
   * links.
   *
   * <p><b>The gate is evaluated inside {@code PortalAccessService#findOrCreateLink}</b> (plan
   * §2.5), and a closed gate means the service answers empty <em>before writing anything</em> — no
   * {@code etgo_portal_access} row is minted, and this email is byte-identical to the one this
   * contract produced before ETP-5267. That is the assertion the plan's tests make on the row count
   * rather than on the email body, because minting on a gated-off send is how the feature would
   * leak early.
   *
   * <p>Resolved from the invoice's own {@code (client, organization, business partner)} — not from
   * anything in the command — so the link can only ever belong to the Business Partner the invoice
   * is addressed to.
   *
   * <p>Never fails the send: a missing invoice, an unresolvable Business Partner or an unconfigured
   * portal secret all mean "no link", never "no email". The customer's invoice is the point; the
   * portal link is an addition to it.
   */
  @Override
  protected Optional<String> resolveAdditionalParagraphHtml(EmailContractCommand command,
      EmailDocumentRecord document, String language) {
    Invoice invoice = resolveInvoice(document);
    if (invoice == null) {
      return Optional.empty();
    }
    return portalAccessService
        .findOrCreateLink(invoice.getClient(), invoice.getOrganization(),
            invoice.getBusinessPartner())
        .map(link -> renderPortalParagraph(link, language));
  }

  private static Invoice resolveInvoice(EmailDocumentRecord document) {
    String recordId = document == null ? null : StringUtils.trimToNull(document.getRecordId());
    return recordId == null ? null : OBDal.getInstance().get(Invoice.class, recordId);
  }

  /**
   * Renders the portal sentence and its inline anchor.
   *
   * <p>An anchor in a paragraph rather than a second call-to-action button: {@link EmailMessages}'s
   * layout carries exactly one button and it belongs to the document itself. Two buttons of similar
   * weight would also make the portal compete with the download the email exists for.
   *
   * <p>Both the copy and the URL are escaped before interpolation, and {@code applyBold} runs after
   * escaping — the order {@code EmailEscape#applyBold} documents as mandatory, so a {@code **}
   * marker in the catalog still becomes emphasis while nothing else in the string can emit markup.
   */
  private static String renderPortalParagraph(String link, String language) {
    String intro = EmailEscape.applyBold(
        EmailEscape.escapeHtml(EmailMessages.get("document.portal.intro", language)));
    String label = EmailEscape.escapeHtml(EmailMessages.get("document.portal.cta", language));
    return intro + " <a class=\"sf-link\" href=\"" + EmailEscape.escapeHtml(link) + LINK_STYLE
        + label + "</a>";
  }
}
