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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.StripeTransportException;

/**
 * How a payment was paid, asked to Stripe while it is recorded (ETP-6322), and that a failing
 * Stripe never fails the webhook.
 *
 * @covers com.etendoerp.go.payment.invoicing.PaymentMethodReader
 */
class PaymentMethodReaderTest {

  private static final String CHARGE = "{\"id\":\"ch_1\","
      + "\"receipt_url\":\"https://pay.stripe.com/r/1\","
      + "\"payment_method_details\":{\"type\":\"card\",\"card\":{\"brand\":\"visa\","
      + "\"last4\":\"4242\",\"wallet\":{\"type\":\"apple_pay\"}}}}";

  private final StripeApiClient stripe = mock(StripeApiClient.class);
  private final PaymentMethodReader reader = new PaymentMethodReader(stripe);

  @Test
  void theCurrentApiShapeIsReadFromTheExpandedPaymentIntent() throws Exception {
    when(stripe.get("/v1/invoices/in_1" + PaymentMethodReader.EXPAND)).thenReturn(
        new StripeResponse(200, "{\"payments\":{\"data\":[{\"payment\":{\"type\":"
            + "\"payment_intent\",\"payment_intent\":{\"latest_charge\":" + CHARGE + "}}}]}}"));

    JSONObject method = reader.read("in_1");

    assertEquals("card", method.getString("type"));
    assertEquals("visa", method.getString("brand"));
    assertEquals("4242", method.getString("last4"));
    assertEquals("apple_pay", method.getString("wallet"));
    assertEquals("https://pay.stripe.com/r/1", method.getString("receiptUrl"));
    assertEquals("ch_1", method.getString("chargeId"));
  }

  @Test
  void aChargeTheInvoiceOnlyNamesIsFetched() throws Exception {
    when(stripe.get("/v1/invoices/in_2" + PaymentMethodReader.EXPAND))
        .thenReturn(new StripeResponse(200, "{\"charge\":\"ch_1\"}"));
    when(stripe.get("/v1/charges/ch_1")).thenReturn(new StripeResponse(200, CHARGE));

    assertEquals("4242", reader.read("in_2").getString("last4"));
  }

  @Test
  void aMethodWithoutCardOrWalletReportsThemAsNull() throws Exception {
    JSONObject method = PaymentMethodReader.describe(new JSONObject(
        "{\"id\":\"ch_2\",\"payment_method_details\":{\"type\":\"sepa_debit\"}}"));

    assertEquals("sepa_debit", method.getString("type"));
    assertTrue(method.isNull("brand"));
    assertTrue(method.isNull("last4"));
    assertTrue(method.isNull("wallet"));
    assertTrue(method.isNull("receiptUrl"));
  }

  @Test
  void aFailingStripeAnswersNull() throws Exception {
    when(stripe.get(anyString())).thenThrow(new StripeTransportException("timeout"));

    assertNull(reader.read("in_3"));
  }

  @Test
  void aRefusedRequestAnswersNull() throws Exception {
    when(stripe.get(anyString())).thenReturn(new StripeResponse(404, "{}"));

    assertNull(reader.read("in_4"));
    verify(stripe).get("/v1/invoices/in_4" + PaymentMethodReader.EXPAND);
  }

  @Test
  void anInvoiceWithoutAChargeAnswersNull() throws Exception {
    when(stripe.get(anyString())).thenReturn(new StripeResponse(200, "{\"payments\":"
        + "{\"data\":[]}}"));

    assertNull(reader.read("in_5"));
  }

  @Test
  void noInvoiceAsksNothing() {
    assertNull(reader.read(" "));
    verifyNoInteractions(stripe);
  }
}
