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

package com.etendoerp.go.payment.invoicing;

import static com.etendoerp.go.payment.invoicing.PaymentStatus.COMPLETED;
import static com.etendoerp.go.payment.invoicing.PaymentStatus.ERROR;
import static com.etendoerp.go.payment.invoicing.PaymentStatus.INVOICED;
import static com.etendoerp.go.payment.invoicing.PaymentStatus.MAIL_SENT;
import static com.etendoerp.go.payment.invoicing.PaymentStatus.PENDING_INVOICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Which invoicing events the ERP may report on a payment in each state (ETP-6322).
 *
 * @covers com.etendoerp.go.payment.invoicing.PaymentStatus
 */
class PaymentStatusTest {

  @Test
  void theForwardPathIsAccepted() {
    assertNull(PaymentStatus.refusalFor(PENDING_INVOICE, true, INVOICED));
    assertNull(PaymentStatus.refusalFor(INVOICED, true, MAIL_SENT));
    assertNull(PaymentStatus.refusalFor(MAIL_SENT, true, COMPLETED));
    assertNull(PaymentStatus.refusalFor(INVOICED, true, COMPLETED), "the mail step is optional");
  }

  @Test
  void aRetriedReportOfTheCurrentStateIsAccepted() {
    assertNull(PaymentStatus.refusalFor(INVOICED, true, INVOICED));
    assertNull(PaymentStatus.refusalFor(MAIL_SENT, true, MAIL_SENT));
    assertNull(PaymentStatus.refusalFor(COMPLETED, true, COMPLETED));
  }

  @Test
  void everyStepButAnErrorNeedsTheInvoiceNumber() {
    assertEquals(PaymentStatus.Refusal.INVOICE_NUMBER_MISSING,
        PaymentStatus.refusalFor(PENDING_INVOICE, false, INVOICED));
    assertEquals(PaymentStatus.Refusal.INVOICE_NUMBER_MISSING,
        PaymentStatus.refusalFor(ERROR, false, MAIL_SENT));
    assertNull(PaymentStatus.refusalFor(PENDING_INVOICE, false, ERROR));
  }

  @Test
  void aCompletedPaymentAcceptsNothingElse() {
    assertEquals(PaymentStatus.Refusal.COMPLETED,
        PaymentStatus.refusalFor(COMPLETED, true, ERROR));
    assertEquals(PaymentStatus.Refusal.COMPLETED,
        PaymentStatus.refusalFor(COMPLETED, true, MAIL_SENT));
  }

  @Test
  void aStepBackIsRefused() {
    assertEquals(PaymentStatus.Refusal.INVALID_TRANSITION,
        PaymentStatus.refusalFor(MAIL_SENT, true, INVOICED));
  }

  @Test
  void afterAnErrorTheErpRetriesAnyStep() {
    assertNull(PaymentStatus.refusalFor(ERROR, true, INVOICED));
    assertNull(PaymentStatus.refusalFor(ERROR, true, MAIL_SENT));
    assertNull(PaymentStatus.refusalFor(ERROR, true, COMPLETED));
    assertNull(PaymentStatus.refusalFor(MAIL_SENT, true, ERROR));
  }

  @Test
  void statusesAreReadAndWrittenInTheApiForm() {
    assertEquals(PENDING_INVOICE, PaymentStatus.parse(" pending_invoice "));
    assertEquals(MAIL_SENT, PaymentStatus.parse("MAIL_SENT"));
    assertNull(PaymentStatus.parse("paid"));
    assertNull(PaymentStatus.parse(null));
    assertEquals("mail_sent", PaymentStatus.toApi(MAIL_SENT));
  }
}
