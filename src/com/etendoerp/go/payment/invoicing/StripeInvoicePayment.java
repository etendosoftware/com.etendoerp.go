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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.payment.StripeCurrencyScale;
import com.etendoerp.go.payment.StripeJson;
import com.etendoerp.go.payment.SubscriptionLifecycleApplier;

/**
 * One payment the ERP has to invoice, read from a Stripe {@code invoice.paid} event (ETP-6322).
 *
 * <p>Only invoices that collected money are payments: a free first month or a full discount pays
 * zero and needs no invoice, so {@link #from} answers empty for it. An invoice that does not state
 * what it collected is not a payment either: there is no amount to invoice.
 *
 * <p>Amounts are in the currency's major unit (euros, not cents), with the currency's own number of
 * decimals. The plans are sold without VAT, so the base of the invoice is {@link #amountPaid()};
 * {@link #taxAmount()} is only what Stripe itself reports, which stays empty while Stripe does not
 * compute tax.
 *
 * <p>Reads both the current and the older Stripe API shapes: the subscription and the price moved
 * under {@code parent} and {@code pricing}, and {@code tax} became {@code total_taxes}.
 */
public final class StripeInvoicePayment {

  static final String INVOICE_PAID = "invoice.paid";

  /** {@code ETGO_PAYMENT.PAYMENT_TYPE}: the first payment of a purchase. */
  public static final String TYPE_NEW = "NEW";
  /** A renewal of the subscription, monthly or annual. */
  public static final String TYPE_RENEWAL = "RENEWAL";
  /** A charge for a change of plan in the middle of a period (the proration). */
  public static final String TYPE_PLAN_CHANGE = "PLAN_CHANGE";
  /** Any other reason Stripe gives (a manual invoice, an upcoming-invoice charge). */
  public static final String TYPE_OTHER = "OTHER";

  private static final String AMOUNT_PAID = "amount_paid";
  private static final String AMOUNT = "amount";
  private static final String PERIOD = "period";
  private static final String START = "start";
  private static final String END = "end";
  private static final String SUBSCRIPTION_DETAILS = "subscription_details";

  private final String invoiceId;
  private final String invoiceNumber;
  private final String eventId;
  private final String subscriptionId;
  private final String customerId;
  private final String customerEmail;
  private final String requestId;
  private final String billingReason;
  private final String paymentType;
  private final String currency;
  private final BigDecimal amountPaid;
  private final BigDecimal subtotal;
  private final BigDecimal taxAmount;
  private final BigDecimal totalAmount;
  private final Instant paidAt;
  private final Instant periodStart;
  private final Instant periodEnd;
  private final JSONArray lines;

  private StripeInvoicePayment(JSONObject event, JSONObject invoice) throws JSONException {
    String currencyCode = StripeJson.text(invoice, "currency").toUpperCase(Locale.ROOT);
    int exponent = StripeCurrencyScale.exponent(currencyCode);
    this.invoiceId = StripeJson.text(invoice, "id");
    this.invoiceNumber = StringUtils.trimToNull(StripeJson.text(invoice, "number"));
    this.eventId = StringUtils.trimToNull(StripeJson.text(event, "id"));
    this.subscriptionId = StringUtils.trimToNull(
        SubscriptionLifecycleApplier.subscriptionIdOf(INVOICE_PAID, invoice));
    this.customerId = StringUtils.trimToNull(StripeJson.text(invoice, "customer"));
    this.customerEmail = StringUtils.trimToNull(StripeJson.text(invoice, "customer_email"));
    this.requestId = requestIdOf(invoice);
    this.billingReason = StringUtils.trimToNull(StripeJson.text(invoice, "billing_reason"));
    this.paymentType = paymentTypeOf(billingReason);
    this.currency = currencyCode;
    this.amountPaid = major(invoice, AMOUNT_PAID, exponent);
    this.subtotal = major(invoice, "subtotal", exponent);
    this.taxAmount = taxOf(invoice, exponent);
    this.totalAmount = major(invoice, "total", exponent);
    JSONObject transitions = invoice.optJSONObject("status_transitions");
    this.paidAt = firstNonNull(epochSeconds(transitions, "paid_at"),
        SubscriptionLifecycleApplier.eventCreatedAt(event));
    this.lines = linesOf(invoice, exponent);
    Instant[] period = servicePeriod(invoice);
    this.periodStart = period[0];
    this.periodEnd = period[1];
  }

  /**
   * The payment an event reports.
   *
   * @param type  the Stripe event type
   * @param event the full event
   * @return the payment, or empty when the event is not an {@code invoice.paid} that collected
   *     money in a known currency
   */
  public static Optional<StripeInvoicePayment> from(String type, JSONObject event) {
    if (!INVOICE_PAID.equals(StringUtils.trimToEmpty(type)) || event == null) {
      return Optional.empty();
    }
    JSONObject data = event.optJSONObject("data");
    JSONObject invoice = data == null ? null : data.optJSONObject("object");
    if (invoice == null || StringUtils.isBlank(StripeJson.text(invoice, "id"))
        || StringUtils.isBlank(StripeJson.text(invoice, "currency"))
        || !invoice.has(AMOUNT_PAID) || invoice.optLong(AMOUNT_PAID, 0L) <= 0L) {
      return Optional.empty();
    }
    try {
      return Optional.of(new StripeInvoicePayment(event, invoice));
    } catch (JSONException e) {
      throw new IllegalStateException("Could not read the Stripe invoice " + StripeJson.text(
          invoice, "id"), e);
    }
  }

  /**
   * The payment type a Stripe {@code billing_reason} stands for.
   *
   * @param billingReason Stripe's reason, may be null
   * @return one of the {@code TYPE_*} constants
   */
  static String paymentTypeOf(String billingReason) {
    switch (StringUtils.trimToEmpty(billingReason)) {
      case "subscription_create":
        return TYPE_NEW;
      case "subscription_cycle":
        return TYPE_RENEWAL;
      case "subscription_update":
        return TYPE_PLAN_CHANGE;
      default:
        return TYPE_OTHER;
    }
  }

  private static String requestIdOf(JSONObject invoice) {
    JSONObject parent = invoice.optJSONObject("parent");
    JSONObject details = parent == null ? invoice.optJSONObject(SUBSCRIPTION_DETAILS)
        : parent.optJSONObject(SUBSCRIPTION_DETAILS);
    JSONObject metadata = details == null ? null : details.optJSONObject("metadata");
    if (metadata == null) {
      metadata = invoice.optJSONObject("metadata");
    }
    return metadata == null ? null : StringUtils.trimToNull(StripeJson.text(metadata,
        "request_id"));
  }

  /** {@code total_taxes} on current API versions, {@code tax} on older ones; null if neither. */
  private static BigDecimal taxOf(JSONObject invoice, int exponent) {
    JSONArray taxes = invoice.optJSONArray("total_taxes");
    if (taxes != null) {
      long sum = 0L;
      for (int i = 0; i < taxes.length(); i++) {
        JSONObject tax = taxes.optJSONObject(i);
        sum += tax == null ? 0L : tax.optLong(AMOUNT, 0L);
      }
      return BigDecimal.valueOf(sum).movePointLeft(exponent);
    }
    return major(invoice, "tax", exponent);
  }

  private static JSONArray linesOf(JSONObject invoice, int exponent) throws JSONException {
    JSONArray result = new JSONArray();
    JSONObject container = invoice.optJSONObject("lines");
    JSONArray data = container == null ? null : container.optJSONArray("data");
    for (int i = 0; data != null && i < data.length(); i++) {
      JSONObject line = data.optJSONObject(i);
      if (line == null) {
        continue;
      }
      JSONObject out = new JSONObject();
      out.put("description", StringUtils.trimToEmpty(StripeJson.text(line, "description")));
      out.put("quantity", line.optLong("quantity", 1L));
      BigDecimal amount = major(line, AMOUNT, exponent);
      out.put(AMOUNT, amount == null ? JSONObject.NULL : amount.toPlainString());
      String priceId = priceIdOf(line);
      out.put("priceId", priceId == null ? JSONObject.NULL : priceId);
      JSONObject period = line.optJSONObject(PERIOD);
      out.put("periodStart", isoOrNull(epochSeconds(period, START)));
      out.put("periodEnd", isoOrNull(epochSeconds(period, END)));
      result.put(out);
    }
    return result;
  }

  private static String priceIdOf(JSONObject line) {
    JSONObject pricing = line.optJSONObject("pricing");
    JSONObject details = pricing == null ? null : pricing.optJSONObject("price_details");
    String current = details == null ? "" : StripeJson.text(details, "price");
    if (StringUtils.isNotEmpty(current)) {
      return current;
    }
    JSONObject price = line.optJSONObject("price");
    return price == null ? null : StringUtils.trimToNull(StripeJson.text(price, "id"));
  }

  /**
   * The period the payment pays for: the earliest start and the latest end of its lines. The
   * invoice's own {@code period_start}/{@code period_end} are not that: for a subscription they
   * frame the previous period.
   */
  private static Instant[] servicePeriod(JSONObject invoice) {
    Instant start = null;
    Instant end = null;
    JSONObject container = invoice.optJSONObject("lines");
    JSONArray data = container == null ? null : container.optJSONArray("data");
    for (int i = 0; data != null && i < data.length(); i++) {
      JSONObject line = data.optJSONObject(i);
      JSONObject period = line == null ? null : line.optJSONObject(PERIOD);
      Instant lineStart = epochSeconds(period, START);
      Instant lineEnd = epochSeconds(period, END);
      if (lineStart != null && (start == null || lineStart.isBefore(start))) {
        start = lineStart;
      }
      if (lineEnd != null && (end == null || lineEnd.isAfter(end))) {
        end = lineEnd;
      }
    }
    return new Instant[] { start, end };
  }

  private static BigDecimal major(JSONObject json, String key, int exponent) {
    if (json == null || !json.has(key) || json.isNull(key)) {
      return null;
    }
    return BigDecimal.valueOf(json.optLong(key, 0L)).movePointLeft(exponent);
  }

  private static Instant epochSeconds(JSONObject json, String key) {
    if (json == null || !json.has(key) || json.isNull(key)) {
      return null;
    }
    long seconds = json.optLong(key, 0L);
    return seconds > 0L ? Instant.ofEpochSecond(seconds) : null;
  }

  private static Object isoOrNull(Instant instant) {
    return instant == null ? JSONObject.NULL : instant.toString();
  }

  private static Instant firstNonNull(Instant first, Instant second) {
    return first != null ? first : second;
  }

  /** @return the Stripe invoice id ({@code in_…}), never blank */
  public String invoiceId() {
    return invoiceId;
  }

  /** @return Stripe's own invoice number, or null */
  public String invoiceNumber() {
    return invoiceNumber;
  }

  /** @return the id of the event that reported the payment, or null */
  public String eventId() {
    return eventId;
  }

  /** @return the Stripe subscription, or null for an invoice outside a subscription */
  public String subscriptionId() {
    return subscriptionId;
  }

  /** @return the Stripe customer, or null */
  public String customerId() {
    return customerId;
  }

  /** @return the address Stripe billed, or null; the account's address is preferred */
  public String customerEmail() {
    return customerEmail;
  }

  /** @return the purchase's request id Etendo put in the subscription metadata, or null */
  public String requestId() {
    return requestId;
  }

  /** @return Stripe's {@code billing_reason}, or null */
  public String billingReason() {
    return billingReason;
  }

  /** @return one of the {@code TYPE_*} constants */
  public String paymentType() {
    return paymentType;
  }

  /** @return the ISO currency code, upper case */
  public String currency() {
    return currency;
  }

  /** @return what was collected, in the major unit; the base of the invoice */
  public BigDecimal amountPaid() {
    return amountPaid;
  }

  /** @return Stripe's subtotal, or null */
  public BigDecimal subtotal() {
    return subtotal;
  }

  /** @return the tax Stripe reports, or null */
  public BigDecimal taxAmount() {
    return taxAmount;
  }

  /** @return Stripe's total, or null */
  public BigDecimal totalAmount() {
    return totalAmount;
  }

  /** @return when the invoice was paid, or the event's creation when Stripe does not say */
  public Instant paidAt() {
    return paidAt;
  }

  /** @return the start of the period paid for, or null */
  public Instant periodStart() {
    return periodStart;
  }

  /** @return the end of the period paid for, or null */
  public Instant periodEnd() {
    return periodEnd;
  }

  /** @return the invoice lines, as stored in {@code ETGO_PAYMENT.LINES} */
  public JSONArray lines() {
    return lines;
  }
}
