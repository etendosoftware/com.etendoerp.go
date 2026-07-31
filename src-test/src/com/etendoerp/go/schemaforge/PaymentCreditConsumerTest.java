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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * Unit tests for {@link PaymentCreditConsumer#consumeAbono} — the ETP-4738 server-side
 * eligibility guard on "saldo a favor" consumption. The selector only ever offers negative-total
 * Facturas Rectificativas, but nothing stops a crafted request from sending an arbitrary
 * {@code psdId}, so {@code consume} must reject anything the selector would not have listed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentCreditConsumerTest {

  private static final String PSD_ID = "psd-1";
  private static final String DOC_TYPE_ID = "dt-rect-1";
  private static final String PAYMENT_ID = "pay-new-1";
  private static final String OTHER_PAYMENT_ID = "pay-other-1";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<FIN_AddPayment> finAddPaymentMock;
  private MockedStatic<RectificativeDocTypeSupport> rectDocTypeMock;

  private OBDal dal;
  private FIN_Payment payment;
  private FIN_PaymentScheduleDetail psd;
  private FIN_PaymentSchedule schedule;
  private Invoice invoice;
  private DocumentType docType;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    payment = mock(FIN_Payment.class);
    psd = mock(FIN_PaymentScheduleDetail.class);
    schedule = mock(FIN_PaymentSchedule.class);
    invoice = mock(Invoice.class);
    docType = mock(DocumentType.class);

    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.get(FIN_PaymentScheduleDetail.class, PSD_ID)).thenReturn(psd);

    finAddPaymentMock = mockStatic(FIN_AddPayment.class);
    finAddPaymentMock.when(() -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()))
        .thenReturn(BigDecimal.ZERO);

    rectDocTypeMock = mockStatic(RectificativeDocTypeSupport.class);

    when(payment.getId()).thenReturn(PAYMENT_ID);
    when(psd.getId()).thenReturn(PSD_ID);
    when(psd.getInvoicePaymentSchedule()).thenReturn(schedule);
    when(schedule.getInvoice()).thenReturn(invoice);
    when(invoice.getTransactionDocument()).thenReturn(docType);
    when(docType.getId()).thenReturn(DOC_TYPE_ID);
  }

  @AfterEach
  void tearDown() {
    closeQuietly(rectDocTypeMock);
    closeQuietly(finAddPaymentMock);
    closeQuietly(obDalMock);
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
  }

  private JSONArray abonoSource(BigDecimal use) throws Exception {
    return new JSONArray(java.util.Collections.singletonList(
        new JSONObject().put("kind", "abono").put("psdId", PSD_ID).put("use", use.toPlainString())));
  }

  @Test
  @DisplayName("An eligible abono (rectificative doc type, negative invoice total) is consumed "
      + "as a negative payment detail")
  void consumeAbono_eligibleRectificativeNegativeTotal_linksNegativeDetail() throws Exception {
    when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("-30.00"));
    rectDocTypeMock.when(() -> RectificativeDocTypeSupport.isRectificativeDocType(DOC_TYPE_ID))
        .thenReturn(true);

    BigDecimal funded = PaymentCreditConsumer.consume(payment, abonoSource(new BigDecimal("30")));

    assertEquals(new BigDecimal("30"), funded);
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(psd), eq(payment), eq(new BigDecimal("-30")), eq(false)));
  }

  @Test
  @DisplayName("An abono whose invoice total is NOT negative is rejected (ETP-4738)")
  void consumeAbono_positiveTotal_throwsOBException() throws Exception {
    when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("30.00"));
    rectDocTypeMock.when(() -> RectificativeDocTypeSupport.isRectificativeDocType(DOC_TYPE_ID))
        .thenReturn(true);

    JSONArray sources = abonoSource(new BigDecimal("30"));
    assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(payment, sources));
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()), never());
  }

  @Test
  @DisplayName("An abono whose document type is not a Factura Rectificativa is rejected "
      + "(ETP-4738), even with a negative total")
  void consumeAbono_nonRectificativeDocType_throwsOBException() throws Exception {
    when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("-30.00"));
    rectDocTypeMock.when(() -> RectificativeDocTypeSupport.isRectificativeDocType(DOC_TYPE_ID))
        .thenReturn(false);

    JSONArray sources = abonoSource(new BigDecimal("30"));
    assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(payment, sources));
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()), never());
  }

  @Test
  @DisplayName("A PSD not linked to any invoice payment schedule is rejected")
  void consumeAbono_noInvoiceLink_throwsOBException() throws Exception {
    when(psd.getInvoicePaymentSchedule()).thenReturn(null);

    JSONArray sources = abonoSource(new BigDecimal("30"));
    assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(payment, sources));
  }

  @Test
  @DisplayName("A PSD already linked to the SAME payment being registered/edited bypasses "
      + "eligibility re-validation (a draft may re-save a legacy pre-ETP-4738 source)")
  void consumeAbono_alreadyLinkedToSamePayment_bypassesValidation() throws Exception {
    // Deliberately ineligible: positive total AND non-rectificative doc type.
    when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("30.00"));
    rectDocTypeMock.when(() -> RectificativeDocTypeSupport.isRectificativeDocType(DOC_TYPE_ID))
        .thenReturn(false);

    FIN_PaymentDetail existingLink = mock(FIN_PaymentDetail.class);
    when(existingLink.getFinPayment()).thenReturn(payment);
    when(psd.getPaymentDetails()).thenReturn(existingLink);

    BigDecimal funded = PaymentCreditConsumer.consume(payment, abonoSource(new BigDecimal("30")));

    assertEquals(new BigDecimal("30"), funded);
    finAddPaymentMock.verify(() -> FIN_AddPayment.updatePaymentDetail(
        eq(psd), eq(payment), eq(new BigDecimal("-30")), eq(false)));
  }

  @Test
  @DisplayName("A PSD linked to a DIFFERENT payment is still validated (the bypass only applies "
      + "to the payment currently consuming it)")
  void consumeAbono_linkedToDifferentPayment_stillValidates() throws Exception {
    when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("30.00"));
    rectDocTypeMock.when(() -> RectificativeDocTypeSupport.isRectificativeDocType(DOC_TYPE_ID))
        .thenReturn(false);

    FIN_Payment otherPayment = mock(FIN_Payment.class);
    when(otherPayment.getId()).thenReturn(OTHER_PAYMENT_ID);
    FIN_PaymentDetail existingLink = mock(FIN_PaymentDetail.class);
    when(existingLink.getFinPayment()).thenReturn(otherPayment);
    when(psd.getPaymentDetails()).thenReturn(existingLink);

    JSONArray sources = abonoSource(new BigDecimal("30"));
    assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(payment, sources));
  }

  @Test
  @DisplayName("A blank psdId is a no-op that funds nothing")
  void consumeAbono_blankPsdId_returnsZero() throws Exception {
    JSONArray sources = new JSONArray(java.util.Collections.singletonList(
        new JSONObject().put("kind", "abono").put("use", "30")));

    BigDecimal funded = PaymentCreditConsumer.consume(payment, sources);

    assertEquals(BigDecimal.ZERO, funded);
    finAddPaymentMock.verify(
        () -> FIN_AddPayment.updatePaymentDetail(any(), any(), any(), anyBoolean()), never());
  }

  @Test
  @DisplayName("A psdId that does not resolve to a PSD is rejected")
  void consumeAbono_psdNotFound_throwsOBException() throws Exception {
    when(dal.get(FIN_PaymentScheduleDetail.class, "missing")).thenReturn(null);
    JSONArray sources = new JSONArray(java.util.Collections.singletonList(
        new JSONObject().put("kind", "abono").put("psdId", "missing").put("use", "30")));

    assertThrows(OBException.class, () -> PaymentCreditConsumer.consume(payment, sources));
  }
}
