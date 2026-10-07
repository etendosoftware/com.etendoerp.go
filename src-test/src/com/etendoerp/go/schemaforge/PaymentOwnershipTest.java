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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Payment_Credit;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;

/**
 * ETP-5558 security fix — a payment id that arrives in an invoice payment action must name a
 * payment of THAT invoice, readable by the caller.
 *
 * <p>The draft confirm/delete paths loaded the payment with a bare {@code OBDal.get} inside admin
 * mode, which adds no client/organization predicate, and never compared it with the invoice in the
 * URL: any known draft id could be confirmed or deleted through any invoice, across tenants. Every
 * refusal answers the same 404 as a missing payment, so an id cannot be probed for existence.</p>
 */
@DisplayName("ETP-5558 — payment ids are scoped to the invoice and the tenant")
class PaymentOwnershipTest {

  private static final String OWN_CLIENT = "client-own";
  private static final String OTHER_CLIENT = "client-other";
  private static final String OWN_ORG = "org-own";
  private static final String INVOICE = "inv-1";
  private static final String OTHER_INVOICE = "inv-2";
  private static final String PAYMENT = "pay-1";

  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> ctxMock;
  private MockedStatic<PaymentRemovalUtil> removalMock;
  private MockedStatic<PaymentRegistrationService> registrationMock;
  private MockedStatic<FIN_AddPayment> addPaymentMock;
  private OBDal dal;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    OBContext context = mock(OBContext.class);
    when(context.getReadableClients()).thenReturn(new String[] { OWN_CLIENT });
    when(context.getReadableOrganizations()).thenReturn(new String[] { OWN_ORG });
    ctxMock = mockStatic(OBContext.class);
    ctxMock.when(OBContext::getOBContext).thenReturn(context);
    removalMock = mockStatic(PaymentRemovalUtil.class);
    registrationMock = mockStatic(PaymentRegistrationService.class);
    addPaymentMock = mockStatic(FIN_AddPayment.class);
    @SuppressWarnings("unchecked")
    OBCriteria<FIN_Payment_Credit> noCredit = mock(OBCriteria.class);
    when(noCredit.list()).thenReturn(List.of());
    when(dal.createCriteria(FIN_Payment_Credit.class)).thenReturn(noCredit);
  }

  @AfterEach
  void tearDown() {
    addPaymentMock.close();
    registrationMock.close();
    removalMock.close();
    ctxMock.close();
    dalMock.close();
  }

  private static Client client(String id) {
    Client c = mock(Client.class);
    when(c.getId()).thenReturn(id);
    return c;
  }

  private static Organization org() {
    Organization o = mock(Organization.class);
    when(o.getId()).thenReturn(OWN_ORG);
    return o;
  }

  private static Invoice invoice(String id) {
    Invoice inv = mock(Invoice.class);
    when(inv.getId()).thenReturn(id);
    return inv;
  }

  private static BusinessPartner bp(String id) {
    BusinessPartner b = mock(BusinessPartner.class);
    when(b.getId()).thenReturn(id);
    return b;
  }

  /** A draft whose installment detail is against {@code invoiceId}, owned by {@code clientId}. */
  private FIN_Payment draft(String invoiceId, String clientId) {
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    Invoice inv = invoice(invoiceId);
    when(schedule.getInvoice()).thenReturn(inv);
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getInvoicePaymentSchedule()).thenReturn(schedule);
    FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
    when(detail.getFINPaymentScheduleDetailList()).thenReturn(new ArrayList<>(List.of(psd)));
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(PAYMENT);
    Client nested1 = client(clientId);
    when(payment.getClient()).thenReturn(nested1);
    Organization nested2 = org();
    when(payment.getOrganization()).thenReturn(nested2);
    when(payment.isProcessed()).thenReturn(false);
    when(payment.getFINPaymentDetailList()).thenReturn(new ArrayList<>(List.of(detail)));
    when(dal.get(FIN_Payment.class, PAYMENT)).thenReturn(payment);
    return payment;
  }

  // ── deletePayment ─────────────────────────────────────────────────────

  @Test
  @DisplayName("deletePayment through another invoice answers 404 and deletes nothing")
  void deleteCrossInvoiceRefused() {
    draft(OTHER_INVOICE, OWN_CLIENT);
    NeoResponse r = PaymentDraftEditService.deleteDraftPayment(PAYMENT, INVOICE);
    assertEquals(404, r.getHttpStatus());
    removalMock.verify(() -> PaymentRemovalUtil.remove(any()), never());
    addPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(),
        anyBoolean()), never());
  }

  @Test
  @DisplayName("deletePayment of another tenant's draft answers 404 and deletes nothing")
  void deleteCrossClientRefused() {
    draft(INVOICE, OTHER_CLIENT);
    NeoResponse r = PaymentDraftEditService.deleteDraftPayment(PAYMENT, INVOICE);
    assertEquals(404, r.getHttpStatus());
    removalMock.verify(() -> PaymentRemovalUtil.remove(any()), never());
  }

  @Test
  @DisplayName("deletePayment of the invoice's own draft still deletes it")
  void deleteOwnDraft() {
    FIN_Payment payment = draft(INVOICE, OWN_CLIENT);
    NeoResponse r = PaymentDraftEditService.deleteDraftPayment(PAYMENT, INVOICE);
    assertEquals(204, r.getHttpStatus());
    removalMock.verify(() -> PaymentRemovalUtil.remove(payment));
  }

  // ── confirmPayment ────────────────────────────────────────────────────

  @Test
  @DisplayName("confirmPayment through another invoice answers 404 and processes nothing")
  void confirmCrossInvoiceRefused() throws Exception {
    draft(OTHER_INVOICE, OWN_CLIENT);
    assertEquals(404, PaymentDraftEditService.confirmDraftPayment(PAYMENT, INVOICE)
        .getHttpStatus());
    registrationMock.verify(() -> PaymentRegistrationService.processOrThrow(any()), never());
  }

  @Test
  @DisplayName("confirmPayment of another tenant's draft answers 404 and processes nothing")
  void confirmCrossClientRefused() throws Exception {
    draft(INVOICE, OTHER_CLIENT);
    assertEquals(404, PaymentDraftEditService.confirmDraftPayment(PAYMENT, INVOICE)
        .getHttpStatus());
    registrationMock.verify(() -> PaymentRegistrationService.processOrThrow(any()), never());
  }

  @Test
  @DisplayName("confirmPayment of the invoice's own draft still processes it")
  void confirmOwnDraft() throws Exception {
    FIN_Payment payment = draft(INVOICE, OWN_CLIENT);
    PaymentDraftEditService.confirmDraftPayment(PAYMENT, INVOICE);
    registrationMock.verify(() -> PaymentRegistrationService.processOrThrow(payment));
  }

  // ── the shared guard (registerPayment edit mode, invoiceCreditSources) ──

  @Test
  @DisplayName("the guard answers the draft only for its own invoice and tenant")
  void guard() {
    FIN_Payment own = draft(INVOICE, OWN_CLIENT);
    assertSame(own, PaymentOwnership.invoicePayment(PAYMENT, INVOICE));
    assertNull(PaymentOwnership.invoicePayment(PAYMENT, OTHER_INVOICE));
    assertNull(PaymentOwnership.invoicePayment(null, INVOICE));
    draft(INVOICE, OTHER_CLIENT);
    assertNull(PaymentOwnership.invoicePayment(PAYMENT, INVOICE));
  }

  @Test
  @DisplayName("an installment is accepted only when it is the invoice's own")
  void scheduleOfTheInvoice() {
    Invoice own = invoice(INVOICE);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    Client nested3 = client(OWN_CLIENT);
    when(schedule.getClient()).thenReturn(nested3);
    Organization nested4 = org();
    when(schedule.getOrganization()).thenReturn(nested4);
    when(dal.get(FIN_PaymentSchedule.class, "sch-1")).thenReturn(schedule);

    Invoice other = invoice(OTHER_INVOICE);
    when(schedule.getInvoice()).thenReturn(other);
    assertNull(PaymentOwnership.scheduleOf("sch-1", own), "another invoice's installment");
    when(schedule.getInvoice()).thenReturn(own);
    assertSame(schedule, PaymentOwnership.scheduleOf("sch-1", own));
  }

  // ── creditSources: the credit consumed must be the business partner's own ──

  private FIN_Payment newPayment(String bpId) {
    FIN_Payment p = mock(FIN_Payment.class);
    when(p.getId()).thenReturn("pay-new");
    BusinessPartner partner = bp(bpId);
    when(p.getBusinessPartner()).thenReturn(partner);
    return p;
  }

  private FIN_Payment creditPayment(String bpId, String clientId) {
    FIN_Payment credit = mock(FIN_Payment.class);
    when(credit.getId()).thenReturn("pay-credit");
    Client nested5 = client(clientId);
    when(credit.getClient()).thenReturn(nested5);
    Organization nested6 = org();
    when(credit.getOrganization()).thenReturn(nested6);
    BusinessPartner partner = bp(bpId);
    when(credit.getBusinessPartner()).thenReturn(partner);
    when(dal.get(FIN_Payment.class, "pay-credit")).thenReturn(credit);
    return credit;
  }

  private static JSONArray credit(String kind, String key, String id) throws Exception {
    return new JSONArray().put(new JSONObject().put("kind", kind).put(key, id).put("use", "10"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "other-bp", "other-client" })
  @DisplayName("accumulated credit of another business partner or tenant is refused, untouched")
  void foreignAccumulatedCreditRefused(String which) throws Exception {
    FIN_Payment credit = "other-bp".equals(which) ? creditPayment("bp-2", OWN_CLIENT)
        : creditPayment("bp-1", OTHER_CLIENT);
    OBException e = assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(
        newPayment("bp-1"), credit("credit", "paymentId", "pay-credit")));
    assertTrue(e.getMessage().contains("Credit payment not found"), e.getMessage());
    verify(credit, never()).setUsedCredit(any());
  }

  @Test
  @DisplayName("abono of another business partner is refused before anything is linked")
  void foreignAbonoRefused() throws Exception {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getId()).thenReturn("psd-1");
    Client nested7 = client(OWN_CLIENT);
    when(psd.getClient()).thenReturn(nested7);
    Organization nested8 = org();
    when(psd.getOrganization()).thenReturn(nested8);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    Invoice creditNote = invoice("inv-cn");
    when(creditNote.getGrandTotalAmount()).thenReturn(new BigDecimal("-50"));
    BusinessPartner other = bp("bp-2");
    when(creditNote.getBusinessPartner()).thenReturn(other);
    when(schedule.getInvoice()).thenReturn(creditNote);
    when(psd.getInvoicePaymentSchedule()).thenReturn(schedule);
    when(dal.get(FIN_PaymentScheduleDetail.class, "psd-1")).thenReturn(psd);

    assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(newPayment("bp-1"),
        credit("abono", "psdId", "psd-1")));
    addPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(),
        anyBoolean()), never());
  }

  // ── every id-based load in the payment actions goes through the guard ──

  private static String source(String file) throws IOException {
    for (Path base : List.of(Paths.get("src/com/etendoerp/go/schemaforge"),
        Paths.get("modules/com.etendoerp.go/src/com/etendoerp/go/schemaforge"))) {
      Path p = base.resolve(file);
      if (Files.isRegularFile(p)) {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
      }
    }
    throw new IOException("source not found: " + file);
  }

  /**
   * A bare {@code OBDal.get} on a request-supplied id adds no tenant predicate, and these actions
   * run in admin mode. Ids that come from the request — the invoice in the URL, the installment,
   * the account, a payment or PSD in the body, a PIS transfer — go through
   * {@link TenantOwnership#loadOwned} or {@link PaymentOwnership}.
   */
  @ParameterizedTest
  @ValueSource(strings = { "PaymentRegistrationService.java", "PaymentCreditSourcesService.java",
      "PaymentDraftEditService.java", "PaymentCreditConsumer.java", "PisPaymentService.java" })
  @DisplayName("no bare OBDal.get on a request-supplied invoice, installment, account or payment")
  void noBareLoads(String file) throws IOException {
    String src = source(file);
    for (String type : List.of("Invoice", "FIN_PaymentSchedule", "FIN_FinancialAccount",
        "FIN_PaymentScheduleDetail", "PisPayment")) {
      assertFalse(src.matches("(?s).*OBDal\\.getInstance\\(\\)\\s*\\.get\\(\\s*" + type
          + "\\.class.*"), file + " loads a " + type + " by request id without the tenant guard");
    }
  }

  @Test
  @DisplayName("invoicePayments checks the invoice is the caller's before listing its payments")
  void listPaymentsGuarded() throws IOException {
    String src = source("PaymentRegistrationService.java");
    int start = src.indexOf("static NeoResponse handleListPayments(");
    String body = src.substring(start, src.indexOf("createQuery", start));
    assertTrue(body.contains("TenantOwnership.loadOwned(Invoice.class, invoiceId)"),
        "the HQL lists by invoice id in admin mode, so the invoice must be owned first");
  }

  @Test
  @DisplayName("PisDeferredPaymentService's retry loads the PIS transfer through the tenant guard")
  void pisRetryGuarded() throws IOException {
    assertFalse(source("PisDeferredPaymentService.java").contains(
        "OBDal.getInstance().get(PisPayment.class, pisPaymentId)"));
  }

  @Test
  @DisplayName("registerPayment edit mode resolves the draft through the invoice guard")
  void editModeGuarded() throws IOException {
    String src = source("PaymentRegistrationService.java");
    assertFalse(src.contains("OBDal.getInstance().get(FIN_Payment.class, editPaymentId)"));
    assertTrue(src.contains("PaymentOwnership.invoicePayment(editPaymentId"));
  }

  @Test
  @DisplayName("invoiceCreditSources ignores an editPaymentId that is not a draft of the invoice")
  void creditSourcesEditIdGuarded() {
    draft(OTHER_INVOICE, OWN_CLIENT);
    assertNull(PaymentOwnership.ownDraftIdOrNull(PAYMENT, INVOICE));
    draft(INVOICE, OWN_CLIENT);
    assertEquals(PAYMENT, PaymentOwnership.ownDraftIdOrNull(PAYMENT, INVOICE));
    assertNull(PaymentOwnership.ownDraftIdOrNull(null, INVOICE));
  }

  @Test
  @DisplayName("invoiceCreditSources reads editPaymentId through that guard")
  void creditSourcesCallSite() throws IOException {
    assertTrue(source("PaymentCreditSourcesService.java")
        .contains("PaymentOwnership.ownDraftIdOrNull("));
  }
}
