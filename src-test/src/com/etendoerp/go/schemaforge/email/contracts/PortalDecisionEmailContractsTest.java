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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.common.NeoCurrencyFormatConfig;
import com.etendoerp.go.portal.PortalDecisionService;
import com.etendoerp.go.portal.PortalLink;
import com.etendoerp.go.portal.PortalLinkService;
import com.etendoerp.go.schemaforge.email.EmailAuthorizationResult;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailContractResolution;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailRecipientSet;
import com.etendoerp.go.schemaforge.email.EmailSendContext;

/**
 * Unit tests for the two emails of a portal quotation decision: the confirmation the customer
 * receives ({@link PortalDecisionConfirmEmailContract}) and the notice the sender receives once it
 * is applied ({@link PortalDecisionNoticeEmailContract}). One class for both: they are the two
 * halves of one flow and share the scope rule, the copy catalog and the fixtures.
 *
 * @covers com.etendoerp.go.schemaforge.email.contracts.PortalDecisionConfirmEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.PortalDecisionNoticeEmailContract
 */
public class PortalDecisionEmailContractsTest {

  private static final String LINK_ID = "LINK-1";
  private static final String ROOT_ID = "ROOT-1";
  private static final String QUOTATION_ID = "QUO-1";
  private static final String DECISION_ID = "DEC-1";
  private static final String DECISION_URL = "https://app.example.test/portal/decision/tok";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDalStatic;
  private PortalLinkService linkService;
  private PortalDecisionService decisionService;

  @Before
  public void setUp() {
    obContext = mockStatic(OBContext.class);
    OBDal obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    BusinessPartner customer = mock(BusinessPartner.class);
    when(customer.getName()).thenReturn("Cliente SL");
    Currency euro = mock(Currency.class);
    when(euro.getISOCode()).thenReturn("EUR");
    when(euro.getSymbol()).thenReturn("€");
    when(euro.isCurrencySymbolAtTheRight()).thenReturn(true);
    when(euro.getStandardPrecision()).thenReturn(2L);
    Order quotation = mock(Order.class);
    when(quotation.getDocumentNo()).thenReturn("PR-7");
    when(quotation.getGrandTotalAmount()).thenReturn(new BigDecimal("2450.00"));
    when(quotation.getCurrency()).thenReturn(euro);
    when(quotation.getBusinessPartner()).thenReturn(customer);
    when(obDal.get(Order.class, QUOTATION_ID)).thenReturn(quotation);
    linkService = mock(PortalLinkService.class);
    decisionService = mock(PortalDecisionService.class);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
    obContext.close();
  }

  // ── both ────────────────────────────────────────────────────────────────────

  @Test
  public void anyCallerOutsideThePortalDecisionScopeGets403() throws Exception {
    // The real scope: the email-contracts endpoint never runs inside PortalDecisionScope.
    for (EmailContract contract : Arrays.<EmailContract>asList(
        new PortalDecisionConfirmEmailContract(), new PortalDecisionNoticeEmailContract())) {
      EmailAuthorizationResult result = contract.authorize(command(new JSONObject()));

      assertFalse(contract.getName(), result.isAllowed());
      assertEquals(contract.getName(), 403, result.getHttpStatus());
      assertEquals(PortalDecisionConfirmEmailContract.MESSAGE_PORTAL_ONLY, result.getMessage());
    }
  }

  @Test
  public void recipientAndMessageEditsAreRejectedEvenInsideTheScope() throws Exception {
    for (EmailContract contract : Arrays.<EmailContract>asList(confirm(), notice())) {
      for (String field : Arrays.asList(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS,
          "messageEdits")) {
        EmailAuthorizationResult result = contract.authorize(
            command(new JSONObject().put(field, new JSONObject())));

        assertFalse(contract.getName() + " " + field, result.isAllowed());
        assertEquals(contract.getName() + " " + field, 400, result.getHttpStatus());
      }
    }
    verifyNoInteractions(linkService, decisionService);
  }

  // ── confirmation ────────────────────────────────────────────────────────────

  @Test
  public void theConfirmationGoesToTheFirstToOfTheRootSendNeverToTheCaller() throws Exception {
    PortalLink session = link();
    when(linkService.findLink(LINK_ID)).thenReturn(Optional.of(session));
    when(decisionService.confirmationRecipient(ROOT_ID)).thenReturn("billing@customer.test");
    JSONObject body = new JSONObject()
        .put(EmailContractCommandSupport.FIELD_RECIPIENT, "opener@elsewhere.test");

    EmailRecipientResolution recipient = confirm().resolveRecipient(command(body));

    assertEquals(Collections.singletonList("billing@customer.test"),
        recipient.getRecipientSet().getTo());
    assertTrue(recipient.getRecipientSet().getCc().isEmpty());

    when(decisionService.confirmationRecipient(ROOT_ID)).thenReturn(null);
    assertTrue("a send with no To has no recipient",
        confirm().resolveRecipient(command(new JSONObject())).isNoRecipient());
  }

  @Test
  public void theConfirmationButtonNamesTheDecisionInTheLinksLanguage() throws Exception {
    // Columns: decision, language, button, subject.
    String[][] cases = {
        { "ACCEPT", "es_ES", "Confirmar aceptación", "Confirma la aceptación del presupuesto PR-7" },
        { "REJECT", "es_ES", "Confirmar rechazo", "Confirma el rechazo del presupuesto PR-7" },
        { "ACCEPT", "en_US", "Confirm acceptance", "Confirm the acceptance of quotation PR-7" },
        { "REJECT", "en_US", "Confirm rejection", "Confirm the rejection of quotation PR-7" },
    };
    PortalDecisionService.MintedDecision minted = mock(PortalDecisionService.MintedDecision.class);
    when(minted.getId()).thenReturn(DECISION_ID);
    when(minted.getUrl()).thenReturn(DECISION_URL);
    when(decisionService.mint(anyString(), anyString(), anyString(), any(), any(), anyString(),
        any())).thenReturn(Optional.of(minted));
    for (String[] c : cases) {
      PortalLink session = link();
      when(session.getLanguage()).thenReturn(c[1]);
      when(linkService.findLink(LINK_ID)).thenReturn(Optional.of(session));

      EmailProviderRequest request = resolveConfirmation(c[0], null, null);

      String body = request.getData().getString("body");
      assertTrue(Arrays.toString(c) + ": " + body, body.contains(c[2]));
      assertTrue(body.contains(DECISION_URL));
      assertEquals(c[3], request.getData().getString("subject"));
      assertEquals("onDelivered confirms the minted decision", DECISION_ID,
          request.getSendCorrelationId());
    }
    // Minted for the session's link, to the one recipient resolved, never another address.
    verify(decisionService, times(4)).mint(eq(LINK_ID), eq(QUOTATION_ID), anyString(), any(),
        any(), eq("billing@customer.test"), any());
  }

  @Test
  public void aRejectionShowsTheCustomersReasonAndForOtherTheTextTheyTyped() throws Exception {
    PortalDecisionService.MintedDecision minted = mock(PortalDecisionService.MintedDecision.class);
    when(minted.getId()).thenReturn(DECISION_ID);
    when(minted.getUrl()).thenReturn(DECISION_URL);
    when(decisionService.mint(anyString(), anyString(), anyString(), any(), any(), anyString(),
        any())).thenReturn(Optional.of(minted));
    PortalLink session = link();
    when(linkService.findLink(LINK_ID)).thenReturn(Optional.of(session));
    String tooLong = String.join("", Collections.nCopies(100, "x"));

    String price = resolveConfirmation("REJECT", "PRICE", null).getData().getString("body");
    String other = resolveConfirmation("REJECT", "OTHER", "Plazo <demasiado> largo")
        .getData().getString("body");
    String blank = resolveConfirmation("REJECT", "OTHER", "   ").getData().getString("body");
    String cut = resolveConfirmation("REJECT", "OTHER", tooLong).getData().getString("body");

    assertTrue(price, price.contains(">Precio<"));
    // "Otros": the customer's own text, escaped, in place of the label.
    assertTrue(other, other.contains(">Plazo &lt;demasiado&gt; largo<"));
    assertFalse(other, other.contains(">Otros<"));
    assertTrue("an empty text falls back to the label: " + blank, blank.contains(">Otros<"));
    int max = PortalDecisionConfirmEmailContract.MAX_REASON_TEXT_CHARS;
    assertTrue(cut, cut.contains(">" + tooLong.substring(0, max - 1) + "\u2026<"));
    assertFalse(cut, cut.contains(tooLong.substring(0, max)));
    // A rejection shows no amount (PO decision: exactly as the design).
    assertFalse(price, price.contains("Importe total"));
  }

  @Test
  public void anAcceptanceShowsTheCompanyTheNumberAndTheTotalAsTheAppShowsIt() throws Exception {
    PortalDecisionService.MintedDecision minted = mock(PortalDecisionService.MintedDecision.class);
    when(minted.getId()).thenReturn(DECISION_ID);
    when(minted.getUrl()).thenReturn(DECISION_URL);
    when(decisionService.mint(anyString(), anyString(), anyString(), any(), any(), anyString(),
        any())).thenReturn(Optional.of(minted));
    PortalLink session = link();
    when(linkService.findLink(LINK_ID)).thenReturn(Optional.of(session));

    String body = resolveConfirmation("ACCEPT", null, null).getData().getString("body");

    assertTrue(body, body.contains(">Acme<"));
    assertTrue(body, body.contains(">PR-7<"));
    assertTrue(body, body.contains("Importe total"));
    // As the app shows it (the pinned "." and "," separators; symbol on its side).
    assertTrue(body, body.contains(">2.450,00\u00A0€<"));
    assertFalse(body, body.contains(">Motivo<"));
    // The Etendo footer closes the card.
    assertTrue(body, body.contains("Creado con Etendo"));
  }

  @Test
  public void onDeliveredMarksTheDecisionRequestSent() {
    EmailRecipientSet to = EmailRecipientSet.singleTo("billing@customer.test");
    PortalDecisionConfirmEmailContract contract = confirm();

    contract.onDelivered(delivered(to, DECISION_ID), "LOG-1");
    contract.onDelivered(delivered(to, null), "LOG-2");

    verify(decisionService).markSent(DECISION_ID);
    verify(decisionService, never()).markSent(null);
  }

  // ── notice ──────────────────────────────────────────────────────────────────

  @Test
  public void theNoticeGoesToTheSenderOfTheQuotation() throws Exception {
    PortalDecisionService.Decision decision = decision("REJECT", null, "es_ES");
    when(decisionService.findById(DECISION_ID)).thenReturn(decision);
    when(decisionService.senderEmail(decision)).thenReturn("seller@tenant.test");
    JSONObject body = new JSONObject()
        .put(PortalDecisionService.FIELD_DECISION_ID, DECISION_ID)
        .put(EmailContractCommandSupport.FIELD_RECIPIENT, "opener@elsewhere.test");

    EmailRecipientResolution recipient = notice().resolveRecipient(command(body));

    assertEquals(Collections.singletonList("seller@tenant.test"),
        recipient.getRecipientSet().getTo());
    when(decisionService.senderEmail(decision)).thenReturn(null);
    assertTrue(notice().resolveRecipient(command(body)).isNoRecipient());
  }

  @Test
  public void theNoticeSaysWhoDecidedWhatInTheDecisionsLanguage() throws Exception {
    // Columns: decision, language, subject, a fragment of the body.
    String[][] cases = {
        { "REJECT", "es_ES", "Cliente SL ha rechazado el presupuesto PR-7",
            "<strong>Cliente SL</strong> ha <strong>rechazado</strong> el presupuesto" },
        { "ACCEPT", "es_ES", "Cliente SL ha aceptado el presupuesto PR-7",
            "<strong>Cliente SL</strong> ha <strong>aceptado</strong> el presupuesto" },
        { "ACCEPT", "en_US", "Cliente SL has accepted quotation PR-7",
            "<strong>Cliente SL</strong> has <strong>accepted</strong> quotation" },
    };
    for (String[] c : cases) {
      PortalDecisionService.Decision decision = decision(c[0], null, c[1]);
      when(decisionService.findById(DECISION_ID)).thenReturn(decision);
      EmailRecipientResolution recipient = EmailRecipientResolution.serverResolved(
          EmailRecipientSet.singleTo("seller@tenant.test"));

      EmailContractResolution resolution = notice().resolve(command(new JSONObject()
          .put(PortalDecisionService.FIELD_DECISION_ID, DECISION_ID)), recipient);

      assertTrue(resolution.getMessage(), resolution.isReady());
      JSONObject data = resolution.getProviderRequest().getData();
      assertEquals(c[2], data.getString("subject"));
      assertTrue(data.getString("body"), data.getString("body").contains(c[3]));
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private PortalDecisionConfirmEmailContract confirm() {
    // Separators and logo pinned: the test must not depend on Openbravo.properties or the DAL.
    return new PortalDecisionConfirmEmailContract(linkService, decisionService, () -> true,
        () -> new NeoCurrencyFormatConfig(".", ","), (clientId, orgId) -> Optional.empty());
  }

  private PortalDecisionNoticeEmailContract notice() {
    return new PortalDecisionNoticeEmailContract(decisionService, () -> true);
  }

  private EmailProviderRequest resolveConfirmation(String decision, String reasonCode,
      String reasonText) throws Exception {
    JSONObject body = new JSONObject().put(PortalDecisionService.FIELD_DECISION, decision);
    if (reasonCode != null) {
      body.put(PortalDecisionService.FIELD_REASON_CODE, reasonCode);
    }
    if (reasonText != null) {
      body.put(PortalDecisionService.FIELD_REASON_TEXT, reasonText);
    }
    EmailContractResolution resolution = confirm().resolve(command(body),
        EmailRecipientResolution.serverResolved(EmailRecipientSet.singleTo(
            "billing@customer.test")));
    assertTrue("expected a ready resolution, got: " + resolution.getMessage(),
        resolution.isReady());
    return resolution.getProviderRequest();
  }

  private static EmailContractCommand command(JSONObject body) throws Exception {
    body.put(PortalLinkService.FIELD_LINK_ID, LINK_ID);
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, QUOTATION_ID);
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "CLIENT-1");
    return new EmailContractCommand(PortalDecisionService.CONFIRM_CONTRACT, body);
  }

  private static EmailSendContext delivered(EmailRecipientSet recipients, String decisionId) {
    EmailProviderRequest request = new EmailProviderRequest(recipients, "custom",
        new JSONObject(), null).withSendCorrelationId(decisionId);
    return new EmailSendContext(new EmailContractCommand(PortalDecisionService.CONFIRM_CONTRACT,
        new JSONObject()), EmailRecipientResolution.serverResolved(recipients), request);
  }

  private static PortalLink link() {
    PortalLink link = mock(PortalLink.class);
    when(link.getId()).thenReturn(LINK_ID);
    when(link.getRootLinkId()).thenReturn(ROOT_ID);
    when(link.isSent()).thenReturn(true);
    when(link.getLanguage()).thenReturn("es_ES");
    when(link.getClientName()).thenReturn("Acme");
    when(link.getBusinessPartnerName()).thenReturn("Cliente SL");
    return link;
  }

  private static PortalDecisionService.Decision decision(String outcome, String reasonCode,
      String language) {
    PortalDecisionService.Decision decision = mock(PortalDecisionService.Decision.class);
    when(decision.getId()).thenReturn(DECISION_ID);
    when(decision.getQuotationId()).thenReturn(QUOTATION_ID);
    when(decision.getDecision()).thenReturn(outcome);
    when(decision.getReasonCode()).thenReturn(reasonCode);
    when(decision.getLanguage()).thenReturn(language);
    return decision;
  }
}
