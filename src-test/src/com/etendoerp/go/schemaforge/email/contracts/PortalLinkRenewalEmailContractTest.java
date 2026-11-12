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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.portal.PortalDocumentKind;
import com.etendoerp.go.portal.PortalLink;
import com.etendoerp.go.portal.PortalLinkService;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;
import com.etendoerp.go.schemaforge.email.EmailSendContext;

/**
 * Unit tests for {@link PortalLinkRenewalEmailContract}, the email that carries a renewed customer
 * portal link, and for {@link PortalDocumentKind#copyKey}, which decides how that email names the
 * document.
 *
 * @covers com.etendoerp.go.schemaforge.email.contracts.PortalLinkRenewalEmailContract
 * @covers com.etendoerp.go.portal.PortalDocumentKind
 */
public class PortalLinkRenewalEmailContractTest {

  private static final String EXPIRED_ID = "LINK-1";
  private static final String NEW_ID = "LINK-2";
  private static final String RECORD_ID = "DOC-1";
  private static final String NEW_URL = "https://app.example.test/portal/new-token";

  private MockedStatic<OBDal> obDalStatic;
  private OBDal obDal;
  private PortalLinkService linkService;

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    linkService = mock(PortalLinkService.class);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
  }

  @Test
  public void anyCallerOutsideThePortalRenewalScopeGets403() throws Exception {
    // The real scope: the email-contracts endpoint never runs inside PortalRenewalScope.
    EmailAuthorizationResult result = new PortalLinkRenewalEmailContract()
        .authorize(command(new JSONObject()));

    assertFalse(result.isAllowed());
    assertEquals(403, result.getHttpStatus());
    assertEquals(PortalLinkRenewalEmailContract.MESSAGE_RENEWAL_ONLY, result.getMessage());
  }

  @Test
  public void insideTheScopeOnlyASentLinkIsAuthorized() throws Exception {
    PortalLink sent = link(PortalDocumentKind.INVOICE, true);
    PortalLink pending = link(PortalDocumentKind.INVOICE, false);
    Object[][] cases = { { sent, 200 }, { pending, 409 }, { null, 409 } };
    for (Object[] c : cases) {
      Optional<PortalLink> found = Optional.ofNullable((PortalLink) c[0]);
      when(linkService.findLink(EXPIRED_ID)).thenReturn(found);

      EmailAuthorizationResult result = contract().authorize(command(new JSONObject()));

      assertEquals(((Integer) c[1]) == 200, result.isAllowed());
      if (!result.isAllowed()) {
        assertEquals(c[1], result.getHttpStatus());
      }
    }
  }

  @Test
  public void recipientAndMessageEditsAreRejectedEvenInsideTheScope() throws Exception {
    for (String field : Arrays.asList(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS,
        "messageEdits")) {
      JSONObject body = new JSONObject().put(field, new JSONObject());

      EmailAuthorizationResult result = contract().authorize(command(body));

      assertFalse(field, result.isAllowed());
      assertEquals(field, 400, result.getHttpStatus());
    }
    verify(linkService, never()).findLink(anyString());
  }

  @Test
  public void theHistoryRowIsFiledUnderTheRenewedDocumentsSpec() throws Exception {
    Object[][] cases = { { PortalDocumentKind.QUOTATION, "sales-quotation" },
        { PortalDocumentKind.ORDER, "sales-order" },
        { PortalDocumentKind.SHIPMENT, "goods-shipment" },
        { PortalDocumentKind.INVOICE, "sales-invoice" } };
    for (Object[] c : cases) {
      PortalLink expired = link((PortalDocumentKind) c[0], true);
      when(linkService.findLink(EXPIRED_ID)).thenReturn(Optional.of(expired));

      assertEquals(c[0].toString(), c[1], contract().getSpecName(context(NEW_ID)));
    }
    when(linkService.findLink(EXPIRED_ID)).thenReturn(Optional.empty());
    assertNull(contract().getSpecName(context(NEW_ID)));
  }

  @Test
  public void recipientsAreTheExpiredLinksToAndCcNeverWhoeverOpenedIt() throws Exception {
    PortalLink expired = link(PortalDocumentKind.ORDER, true);
    when(expired.getRecipientsTo()).thenReturn(Arrays.asList("billing@customer.test",
        "not-an-address"));
    when(expired.getRecipientsCc()).thenReturn(Collections.singletonList("boss@customer.test"));
    when(linkService.findLink(EXPIRED_ID)).thenReturn(Optional.of(expired));
    JSONObject body = new JSONObject()
        .put(EmailContractCommandSupport.FIELD_RECIPIENT, "opener@elsewhere.test");

    EmailRecipientResolution recipients = contract().resolveRecipient(command(body));

    assertTrue(recipients.isResolved());
    assertEquals(Collections.singletonList("billing@customer.test"),
        recipients.getRecipientSet().getTo());
    assertEquals(Collections.singletonList("boss@customer.test"),
        recipients.getRecipientSet().getCc());
  }

  @Test
  public void theCopyNamesTheDocumentByItsKind() throws Exception {
    PortalLinkService.MintedLink minted = mock(PortalLinkService.MintedLink.class);
    when(minted.getId()).thenReturn(NEW_ID);
    when(minted.getUrl()).thenReturn(NEW_URL);
    when(minted.getValidity()).thenReturn(Duration.ofHours(48));
    when(linkService.mintRenewal(EXPIRED_ID)).thenReturn(Optional.of(minted));
    Object[][] cases = { { PortalDocumentKind.QUOTATION, "consultar el presupuesto" },
        { PortalDocumentKind.ORDER, "consultar el pedido" },
        { PortalDocumentKind.SHIPMENT, "consultar el albarán" },
        { PortalDocumentKind.INVOICE, "consultar la factura" } };
    for (Object[] c : cases) {
      PortalLink expired = link((PortalDocumentKind) c[0], true);
      when(linkService.findLink(EXPIRED_ID)).thenReturn(Optional.of(expired));

      EmailProviderRequest request = resolve();

      String body = request.getData().getString("body");
      assertTrue(c[0] + ": " + body, body.contains((String) c[1]));
      assertFalse(c[0] + ": " + body, body.contains("rectificativa"));
      assertTrue(body.contains(NEW_URL));
      assertEquals("onDelivered confirms the new link, not the expired one", NEW_ID,
          request.getSendCorrelationId());
    }

    // A credit note travels as an INVOICE link and is named as what it is.
    Invoice creditNote = invoiceOfCategory("ARC");
    when(obDal.get(Invoice.class, RECORD_ID)).thenReturn(creditNote);
    PortalLink creditNoteLink = link(PortalDocumentKind.INVOICE, true);
    when(linkService.findLink(EXPIRED_ID)).thenReturn(Optional.of(creditNoteLink));
    assertTrue(resolve().getData().getString("body")
        .contains("consultar la factura rectificativa"));
  }

  @Test
  public void copyKeyIsCreditNoteOnlyForARectificativeInvoice() {
    // { document category, the localization flag's property exists, its value, expected key }
    Object[][] cases = {
        { "ARC", false, null, "CREDIT_NOTE" },
        { "ARI_RM", false, null, "CREDIT_NOTE" },
        { "ARI", true, Boolean.TRUE, "CREDIT_NOTE" },
        { "ARI", true, Boolean.FALSE, "INVOICE" },
        { "ARI", false, null, "INVOICE" },
    };
    for (Object[] c : cases) {
      DocumentType documentType = documentType((String) c[0], (Boolean) c[1], (Boolean) c[2]);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getTransactionDocument()).thenReturn(documentType);
      when(obDal.get(Invoice.class, RECORD_ID)).thenReturn(invoice);

      assertEquals(Arrays.toString(c), c[3], PortalDocumentKind.INVOICE.copyKey(RECORD_ID));
      if (!(Boolean) c[1]) {
        // Without the localization module the property does not exist; reading it would throw.
        verify(documentType, never()).get("etsgIsRectificative");
      }
    }
    when(obDal.get(Invoice.class, RECORD_ID)).thenReturn(null);
    assertEquals("INVOICE", PortalDocumentKind.INVOICE.copyKey(RECORD_ID));
    // Only an invoice can be a credit note; other kinds never read the database for it.
    assertEquals("ORDER", PortalDocumentKind.ORDER.copyKey(RECORD_ID));
    verify(obDal, never()).get(Order.class, RECORD_ID);
  }

  @Test
  public void onDeliveredConfirmsTheNewLinkWithEveryRecipientCounted() throws Exception {
    contract().onDelivered(context(NEW_ID), "LOG-9");

    verify(linkService).confirm(NEW_ID, "LOG-9", 3);
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private PortalLinkRenewalEmailContract contract() {
    return new PortalLinkRenewalEmailContract(linkService, () -> true);
  }

  private EmailProviderRequest resolve() throws Exception {
    EmailRecipientResolution recipient = EmailRecipientResolution.serverResolved(
        EmailRecipientSet.of(Collections.singletonList("billing@customer.test"),
            Collections.<String>emptyList()));
    EmailContractResolution resolution = contract().resolve(command(new JSONObject()), recipient);
    assertTrue("expected a ready resolution, got: " + resolution.getMessage(),
        resolution.isReady());
    return resolution.getProviderRequest();
  }

  private static EmailContractCommand command(JSONObject body) throws Exception {
    body.put(PortalLinkService.FIELD_LINK_ID, EXPIRED_ID);
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID);
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "CLIENT-1");
    return new EmailContractCommand(PortalLinkService.RENEWAL_CONTRACT, body);
  }

  private static EmailSendContext context(String newLinkId) throws Exception {
    EmailRecipientSet recipients = EmailRecipientSet.of(
        Arrays.asList("billing@customer.test", "ap@customer.test"),
        Collections.singletonList("boss@customer.test"));
    EmailProviderRequest request = new EmailProviderRequest(recipients, "custom",
        new JSONObject(), null).withSendCorrelationId(newLinkId);
    return new EmailSendContext(command(new JSONObject()),
        EmailRecipientResolution.serverResolved(recipients), request);
  }

  private static PortalLink link(PortalDocumentKind kind, boolean sent) {
    PortalLink link = mock(PortalLink.class);
    when(link.getId()).thenReturn(EXPIRED_ID);
    when(link.isSent()).thenReturn(sent);
    when(link.getDocumentKind()).thenReturn(kind);
    when(link.getRecordId()).thenReturn(RECORD_ID);
    when(link.getLanguage()).thenReturn("es_ES");
    when(link.getClientName()).thenReturn("Acme");
    when(link.getBusinessPartnerName()).thenReturn("Customer SL");
    when(link.getRecipientsTo()).thenReturn(Collections.singletonList("billing@customer.test"));
    when(link.getRecipientsCc()).thenReturn(Collections.<String>emptyList());
    return link;
  }

  private static Invoice invoiceOfCategory(String category) {
    DocumentType documentType = documentType(category, false, null);
    Invoice invoice = mock(Invoice.class);
    when(invoice.getTransactionDocument()).thenReturn(documentType);
    return invoice;
  }

  private static DocumentType documentType(String category, boolean hasFlag, Boolean flag) {
    Entity entity = mock(Entity.class);
    when(entity.hasProperty("etsgIsRectificative")).thenReturn(hasFlag);
    DocumentType documentType = mock(DocumentType.class);
    when(documentType.getDocumentCategory()).thenReturn(category);
    when(documentType.getEntity()).thenReturn(entity);
    when(documentType.get("etsgIsRectificative")).thenReturn(flag);
    return documentType;
  }
}
