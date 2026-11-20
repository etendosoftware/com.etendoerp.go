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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.payment.HttpUrlConnectionStripeApiClient;
import com.etendoerp.go.payment.StripeApiClient;
import com.etendoerp.go.payment.StripeJson;
import com.etendoerp.go.payment.StripeResponse;
import com.etendoerp.go.payment.StripeTransportException;

/**
 * How a payment was paid, asked to Stripe while the payment is recorded (ETP-6322): the card brand,
 * its last four digits, the wallet (Apple Pay, Google Pay) when there was one, the receipt and the
 * charge. The {@code invoice.paid} event does not carry any of it.
 *
 * <p><b>Never fails the caller.</b> It runs inside the payment webhook, and a slow or failing
 * Stripe must not make the webhook fail: that would hold back the subscription the same event
 * renews. Whatever goes wrong answers {@code null}, and the payment is recorded without its method.
 * The timeouts are short for the same reason.
 *
 * <p>Reads the current API shape, where the invoice lists its {@code payments} and each one points
 * at a payment intent whose latest charge holds the details, and the older one, where the invoice
 * names its {@code charge} directly.
 */
public class PaymentMethodReader {

  private static final Logger log = LogManager.getLogger(PaymentMethodReader.class);

  static final int CONNECT_TIMEOUT_MS = 3_000;
  static final int READ_TIMEOUT_MS = 5_000;

  static final String EXPAND = "?" + URLEncoder.encode("expand[]", StandardCharsets.UTF_8)
      + "=payments.data.payment.payment_intent.latest_charge";

  private static final String CHARGE = "charge";
  private static final String CHARGE_PREFIX = "ch_";

  private final StripeApiClient stripe;

  /** A reader over the configured Stripe account, with short timeouts. */
  public PaymentMethodReader() {
    this(new HttpUrlConnectionStripeApiClient(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS));
  }

  PaymentMethodReader(StripeApiClient stripe) {
    this.stripe = stripe;
  }

  /**
   * The payment method of a paid invoice.
   *
   * @param invoiceId the Stripe invoice id
   * @return {@code {type, brand, last4, wallet, receiptUrl, chargeId}}, the fields Stripe did not
   *     report as {@code null}; or {@code null} when it could not be read
   */
  public JSONObject read(String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      StripeResponse invoice = stripe.get("/v1/invoices/" + encode(invoiceId) + EXPAND);
      if (!invoice.isSuccess()) {
        log.warn("Stripe answered {} for the invoice {}; its payment method is not recorded",
            invoice.status(), invoiceId);
        return null;
      }
      JSONObject charge = chargeOf(invoice.json());
      return charge == null ? null : describe(charge);
    } catch (StripeTransportException | JSONException | RuntimeException e) { // NOSONAR
      log.warn("Could not read the payment method of the invoice {}", invoiceId, e);
      return null;
    }
  }

  /** The charge object, fetched by id when the invoice only names it. */
  private JSONObject chargeOf(JSONObject invoice) throws StripeTransportException {
    Object found = chargeReference(invoice);
    if (found instanceof JSONObject) {
      return (JSONObject) found;
    }
    String chargeId = found instanceof String ? (String) found : null;
    if (StringUtils.isBlank(chargeId)) {
      return null;
    }
    StripeResponse charge = stripe.get("/v1/charges/" + encode(chargeId));
    return charge.isSuccess() ? charge.json() : null;
  }

  /** A charge object, or a charge id, or null. */
  static Object chargeReference(JSONObject invoice) {
    JSONObject payments = invoice == null ? null : invoice.optJSONObject("payments");
    JSONArray data = payments == null ? null : payments.optJSONArray("data");
    for (int i = 0; data != null && i < data.length(); i++) {
      JSONObject entry = data.optJSONObject(i);
      JSONObject payment = entry == null ? null : entry.optJSONObject("payment");
      Object reference = paymentCharge(payment);
      if (reference != null) {
        return reference;
      }
    }
    if (invoice == null) {
      return null;
    }
    Object legacy = invoice.opt(CHARGE);
    if (legacy instanceof JSONObject) {
      return legacy;
    }
    String id = StripeJson.text(invoice, CHARGE);
    return id.startsWith(CHARGE_PREFIX) ? id : null;
  }

  private static Object paymentCharge(JSONObject payment) {
    if (payment == null) {
      return null;
    }
    JSONObject intent = payment.optJSONObject("payment_intent");
    if (intent != null) {
      Object latest = intent.opt("latest_charge");
      if (latest instanceof JSONObject) {
        return latest;
      }
      String id = StripeJson.text(intent, "latest_charge");
      if (id.startsWith(CHARGE_PREFIX)) {
        return id;
      }
    }
    Object charge = payment.opt(CHARGE);
    if (charge instanceof JSONObject) {
      return charge;
    }
    String id = StripeJson.text(payment, CHARGE);
    return id.startsWith(CHARGE_PREFIX) ? id : null;
  }

  /** The fields of a charge the ERP prints on the invoice. */
  static JSONObject describe(JSONObject charge) throws JSONException {
    JSONObject details = charge.optJSONObject("payment_method_details");
    String type = details == null ? null : StringUtils.trimToNull(StripeJson.text(details,
        "type"));
    JSONObject card = details == null ? null : details.optJSONObject("card");
    JSONObject wallet = card == null ? null : card.optJSONObject("wallet");
    return new JSONObject()
        .put("type", orNull(type))
        .put("brand", orNull(card == null ? null : StripeJson.text(card, "brand")))
        .put("last4", orNull(card == null ? null : StripeJson.text(card, "last4")))
        .put("wallet", orNull(wallet == null ? null : StripeJson.text(wallet, "type")))
        .put("receiptUrl", orNull(StripeJson.text(charge, "receipt_url")))
        .put("chargeId", orNull(StripeJson.text(charge, "id")));
  }

  private static Object orNull(String value) {
    return StringUtils.isBlank(value) ? JSONObject.NULL : value;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
  }
}
