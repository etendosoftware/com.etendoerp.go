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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

import com.etendoerp.go.schemaforge.ReconciliationFlowSupport.InvoiceInstallment;

/**
 * The explicit-conversion mode of {@code reconcileGroup}'s invoice leg (ETP-5657): parity with
 * Classic's Add Payment from Match Statement, where the user states what the bank actually moved
 * instead of letting the invoice's own exchange rate decide.
 *
 * <p><b>When it applies.</b> The request carries at least one of {@value #KEY_ACTUAL_PAYMENT},
 * {@value #KEY_CONVERSION_RATE} or {@value #KEY_CONVERTED_AMOUNT} (unsigned magnitudes; the
 * statement line's sign gives the direction). Without any of them,
 * {@link ReconciliationWriteoffSupport#payInvoicesFromBody} keeps the default greedy path
 * ({@link ReconciliationFlowSupport#createInvoicePayments}) untouched.
 *
 * <p><b>What it books.</b> {@code actualPayment} (invoice currency) is spread over the selected
 * invoices in request order, each absorbing at most its outstanding. {@code convertedAmount}
 * (account currency) is split across those payments by largest remainder, proportional to each
 * payment, so the transactions add up to it exactly. Each payment gets a rate that reproduces its
 * own transaction amount ({@link PaymentCurrencyConverter#consistentRate}), so accounting books a
 * realized exchange difference and never a Currency Balancing line. Core then stores the payment's
 * financial-transaction amount and rate, the transaction's foreign fields and both
 * {@code C_Conversion_Rate_Document} rows, exactly as for any other reconciliation payment.
 *
 * <p><b>Precedence.</b> {@code convertedAmount} wins when present: the rate is then advisory, only
 * the preferred per-payment rate, so a rate of exactly 1 is accepted (a 1:1 pegged pair). With only
 * {@code conversionRate}, the converted amount is {@code actualPayment x rate} rounded to the
 * account currency's precision, and that rate must not be 1. With only {@code actualPayment}, it is
 * the statement line amount rounded down to the account currency's precision (Classic's default).
 * A rate that does not match the converted amount is never refused. The invoice's own exchange rate is never read here,
 * so an invoice with no configured rate is reconcilable in this mode.
 *
 * <p><b>Order of work.</b> Every request-supplied id is loaded tenant-guarded BEFORE anything is
 * computed, so a foreign id answers the same 404 as a missing one and no amount of a foreign record
 * reaches a message. Payments are only registered once the whole plan is valid.
 */
final class ReconciliationConversionSupport {

  static final String KEY_ACTUAL_PAYMENT = "actualPayment";
  /** Same body key as the two-step payment modal's rate: one literal, owned by the converter. */
  static final String KEY_CONVERSION_RATE = PaymentCurrencyConverter.KEY_CONVERSION_RATE;
  static final String KEY_CONVERTED_AMOUNT = "convertedAmount";

  private static final String FIELD_SCHEDULE_ID = "scheduleId";

  static final String MSG_CURRENCY_MISMATCH = "Conversion fields require all selected invoices to "
      + "share one currency different from the account currency";
  static final String MSG_NOT_COMBINABLE =
      "Conversion fields cannot be combined with existing transactions or a write-off";
  static final String MSG_ACTUAL_PAYMENT_REQUIRED =
      "actualPayment is required when conversionRate or convertedAmount is sent";
  static final String MSG_ACTUAL_PAYMENT_RANGE = "The amount to pay must be greater than zero and "
      + "not exceed the outstanding amount of the selected invoices";
  static final String MSG_CONVERTED_AMOUNT_RANGE = "The converted amount must be greater than zero "
      + "and not exceed the statement line amount";
  static final String MSG_CONVERTED_TOO_SMALL =
      "The converted amount is too small to allocate across the selected invoices";

  private ReconciliationConversionSupport() {
  }

  /**
   * The explicit-conversion fields of a {@code reconcileGroup} body, as sent (trimmed). A field the
   * request did not carry (absent, JSON {@code null} or blank) is {@code null}.
   */
  record ExplicitConversion(String actualPayment, String conversionRate, String convertedAmount) {
  }

  /**
   * The {@code reconcileGroup} inputs this mode works with (Sonar S107): the target {@code account},
   * the statement {@code line} already resolved by
   * {@link ReconciliationLineTargetSupport#resolveForMatch} (the pending remainder for a partial
   * line), the {@code invoices} specs in request order, the client-supplied {@code operationIds}
   * (the new transactions are appended to it), and the {@code paymentMethodId} /
   * {@code writeoffDifference} options of the body.
   */
  record ConversionPaymentContext(FIN_FinancialAccount account, FIN_BankStatementLine line,
      JSONArray invoiceSpecs, List<String> operationIds, String paymentMethodId,
      boolean writeoffDifference) {
  }

  /**
   * One payment of a {@link ConversionPlan}: the position of its installment in the plan input,
   * the amount paid in the invoice currency, the transaction amount in the account currency and the
   * rate that converts the first into the second.
   */
  record PlannedPayment(int index, BigDecimal paymentAmount, BigDecimal transactionAmount,
      BigDecimal rate) {
  }

  /**
   * Outcome of {@link #plan}: the payments to register, or {@code tooSmall} (with no payments) when
   * the converted amount cannot give every paid invoice at least one minor unit.
   */
  record ConversionPlan(List<PlannedPayment> payments, boolean tooSmall) {
  }

  /**
   * A request that passed {@link #validate}: the installments in request order (duplicated
   * schedules dropped), the amount to pay (invoice currency, at its precision), the converted amount
   * (account currency, at its precision), the typed rate or {@code null}, and the account
   * currency's precision. On failure only {@code error} is set.
   */
  record ValidatedConversion(List<InvoiceInstallment> installments, BigDecimal actualPayment,
      BigDecimal convertedAmount, BigDecimal preferredRate, int accountScale, NeoResponse error) {

    static ValidatedConversion failed(NeoResponse error) {
      return new ValidatedConversion(List.of(), null, null, null, 0, error);
    }
  }

  /** The installments of a request, or the error of the first spec that did not resolve. */
  private record InstallmentLoad(List<InvoiceInstallment> installments, NeoResponse error) {
  }

  /**
   * Reads the explicit-conversion fields of {@code body}.
   *
   * @return the fields, or {@code null} when the request carries none of them (default mode)
   */
  static ExplicitConversion parse(JSONObject body) {
    if (body == null) {
      return null;
    }
    String actualPayment = rawField(body, KEY_ACTUAL_PAYMENT);
    String conversionRate = rawField(body, KEY_CONVERSION_RATE);
    String convertedAmount = rawField(body, KEY_CONVERTED_AMOUNT);
    if (actualPayment == null && conversionRate == null && convertedAmount == null) {
      return null;
    }
    return new ExplicitConversion(actualPayment, conversionRate, convertedAmount);
  }

  /**
   * Pays the selected invoices as {@code conversion} states and appends each new transaction id to
   * the context's {@code operationIds}, so {@code reconcileGroup}'s usual tail matches them to the
   * line.
   *
   * @return {@code null} on success, else the error to return (nothing is written on a validation
   *     error; a later failure is rolled back by {@code runPostAction})
   */
  static NeoResponse payInvoices(ConversionPaymentContext ctx, ExplicitConversion conversion)
      throws Exception {
    ValidatedConversion validated = validate(ctx, conversion);
    if (validated.error() != null) {
      return validated.error();
    }
    List<BigDecimal> outstanding = new ArrayList<>();
    for (InvoiceInstallment installment : validated.installments()) {
      outstanding.add(outstandingOf(installment));
    }
    ConversionPlan plan = plan(outstanding, validated.actualPayment(),
        validated.convertedAmount(), validated.preferredRate(), validated.accountScale());
    if (plan.tooSmall()) {
      return badRequest(MSG_CONVERTED_TOO_SMALL);
    }
    return execute(ctx, validated.installments(), plan.payments());
  }

  /**
   * Checks the request in the only safe order: the combination rule, then every installment loaded
   * tenant-guarded, then the currencies, then the amounts. See the class javadoc for the rules.
   */
  static ValidatedConversion validate(ConversionPaymentContext ctx, ExplicitConversion conversion)
      throws JSONException {
    if (!ctx.operationIds().isEmpty() || ctx.writeoffDifference()) {
      return ValidatedConversion.failed(badRequest(MSG_NOT_COMBINABLE));
    }
    InstallmentLoad load = loadInstallments(ctx.invoiceSpecs());
    if (load.error() != null) {
      return ValidatedConversion.failed(load.error());
    }
    Currency invoiceCurrency = sharedForeignCurrency(load.installments(), ctx.account());
    if (invoiceCurrency == null) {
      return ValidatedConversion.failed(badRequest(MSG_CURRENCY_MISMATCH));
    }
    return validateAmounts(ctx, conversion, load.installments(), invoiceCurrency);
  }

  /**
   * The amount half of {@link #validate}: {@code actualPayment} present, and in (0, sum of
   * outstanding] at the invoice currency's precision; a typed rate positive and — only when it
   * drives the conversion, i.e. {@code convertedAmount} is absent — not exactly 1; the converted
   * amount (see the class javadoc for its precedence) in (0, |line|] at the account currency's
   * precision, with no tolerance — above the line, Core would split it into a remainder of the
   * opposite sign.
   */
  private static ValidatedConversion validateAmounts(ConversionPaymentContext ctx,
      ExplicitConversion conversion, List<InvoiceInstallment> installments,
      Currency invoiceCurrency) {
    int invoiceScale = PaymentCurrencyConverter.standardScale(invoiceCurrency);
    int accountScale = PaymentCurrencyConverter.standardScale(ctx.account().getCurrency());

    BigDecimal outstanding = BigDecimal.ZERO;
    for (InvoiceInstallment installment : installments) {
      outstanding = outstanding.add(outstandingOf(installment));
    }
    if (conversion.actualPayment() == null) {
      return ValidatedConversion.failed(badRequest(MSG_ACTUAL_PAYMENT_REQUIRED));
    }
    BigDecimal actualPayment = scaledAmount(conversion.actualPayment(), invoiceScale);
    if (actualPayment == null || actualPayment.signum() <= 0
        || actualPayment.compareTo(outstanding.setScale(invoiceScale, RoundingMode.HALF_UP)) > 0) {
      return ValidatedConversion.failed(badRequest(MSG_ACTUAL_PAYMENT_RANGE));
    }

    BigDecimal typedRate = null;
    if (conversion.conversionRate() != null) {
      // The "not 1" rule only binds a rate that DRIVES the conversion. When convertedAmount is
      // present it wins and the rate is advisory (just the first consistentRate candidate), so a
      // 1:1 pegged pair the SPA sends as rate 1 must not be refused; malformed / <= 0 still is.
      boolean rateDrivesConversion = conversion.convertedAmount() == null;
      PaymentCurrencyConverter.RateResolution rate =
          PaymentCurrencyConverter.parseRate(conversion.conversionRate(), rateDrivesConversion);
      if (rate.error() != null) {
        return ValidatedConversion.failed(rate.error());
      }
      typedRate = rate.rate();
    }

    BigDecimal lineAmount = lineAmount(ctx.line()).abs();
    BigDecimal converted = conversion.convertedAmount() != null
        ? scaledAmount(conversion.convertedAmount(), accountScale)
        : defaultConverted(actualPayment, typedRate, lineAmount, accountScale);
    if (converted == null || converted.signum() <= 0 || converted.compareTo(lineAmount) > 0) {
      return ValidatedConversion.failed(badRequest(MSG_CONVERTED_AMOUNT_RANGE));
    }
    return new ValidatedConversion(installments, actualPayment, converted, typedRate,
        accountScale, null);
  }

  /**
   * The converted amount when the request did not state one: {@code actualPayment x rate} when a
   * rate was typed, else the whole statement line (Classic pins the converted amount to the line).
   * The line is rounded DOWN to the account precision so the default can never exceed it.
   */
  private static BigDecimal defaultConverted(BigDecimal actualPayment, BigDecimal typedRate,
      BigDecimal lineAmount, int accountScale) {
    if (typedRate != null) {
      return actualPayment.multiply(typedRate).setScale(accountScale, RoundingMode.HALF_UP);
    }
    return lineAmount.setScale(accountScale, RoundingMode.DOWN);
  }

  /**
   * Allocates the payment and its conversion. Pure: no DAL, no request — unit-testable on plain
   * numbers.
   *
   * <p>{@code actualPayment} is spread over {@code outstanding} in order, each entry absorbing at
   * most its own outstanding; entries that get nothing are left out of the plan. {@code converted}
   * is then split across the paid entries by largest remainder, proportional to each payment, so the
   * transaction amounts add up to it exactly (ties go to the earlier entry). Each payment's rate is
   * {@link PaymentCurrencyConverter#consistentRate} with {@code preferredRate} as first candidate.
   *
   * @param outstanding   each installment's outstanding (invoice currency), in request order
   * @param actualPayment the total to pay (invoice currency); expected not above the sum of
   *                      {@code outstanding} — any excess is simply not allocated
   * @param converted     the account-currency total, at {@code accountScale}
   * @param preferredRate the rate the user typed, or {@code null}
   * @param accountScale  the account currency's precision
   * @return the payments, or {@link ConversionPlan#tooSmall()} when a paid entry would get a zero
   *     transaction amount
   */
  static ConversionPlan plan(List<BigDecimal> outstanding, BigDecimal actualPayment,
      BigDecimal converted, BigDecimal preferredRate, int accountScale) {
    List<BigDecimal> payments = allocatePayments(outstanding, actualPayment);
    List<BigDecimal> transactions = splitConverted(payments, converted, accountScale);
    List<PlannedPayment> planned = new ArrayList<>();
    for (int i = 0; i < payments.size(); i++) {
      BigDecimal payment = payments.get(i);
      if (payment.signum() <= 0) {
        continue;
      }
      BigDecimal transaction = transactions.get(i);
      if (transaction.signum() <= 0) {
        return new ConversionPlan(List.of(), true);
      }
      planned.add(new PlannedPayment(i, payment, transaction,
          PaymentCurrencyConverter.consistentRate(payment, transaction, preferredRate,
              accountScale)));
    }
    return new ConversionPlan(planned, false);
  }

  /** {@code pay_i = min(remaining, outstanding_i)}, in order. */
  private static List<BigDecimal> allocatePayments(List<BigDecimal> outstanding,
      BigDecimal actualPayment) {
    List<BigDecimal> payments = new ArrayList<>(outstanding.size());
    BigDecimal remaining = actualPayment;
    for (BigDecimal owed : outstanding) {
      BigDecimal payment = remaining.min(owed.abs()).max(BigDecimal.ZERO);
      payments.add(payment);
      remaining = remaining.subtract(payment);
    }
    return payments;
  }

  /**
   * Largest-remainder split of {@code converted} proportional to {@code payments}, worked in minor
   * units of the account currency so the parts add up to {@code converted} exactly.
   */
  private static List<BigDecimal> splitConverted(List<BigDecimal> payments, BigDecimal converted,
      int accountScale) {
    BigDecimal total = payments.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.signum() <= 0) {
      return new ArrayList<>(Collections.nCopies(payments.size(),
          BigDecimal.ZERO.setScale(accountScale)));
    }
    List<BigDecimal> parts = new ArrayList<>(payments.size());
    BigDecimal units = converted.movePointRight(accountScale).setScale(0, RoundingMode.HALF_UP);
    List<BigDecimal> remainders = new ArrayList<>(payments.size());
    BigDecimal allocated = BigDecimal.ZERO;
    for (BigDecimal payment : payments) {
      BigDecimal[] quotientAndRemainder = units.multiply(payment).divideAndRemainder(total);
      parts.add(quotientAndRemainder[0]);
      remainders.add(quotientAndRemainder[1]);
      allocated = allocated.add(quotientAndRemainder[0]);
    }
    int leftover = units.subtract(allocated).intValueExact();
    List<Integer> byRemainder = new ArrayList<>();
    for (int i = 0; i < payments.size(); i++) {
      byRemainder.add(i);
    }
    // List.sort is stable, so equal remainders keep request order: the earlier entry wins a tie.
    byRemainder.sort(Comparator.comparing(remainders::get, Comparator.reverseOrder()));
    for (int k = 0; k < leftover; k++) {
      int i = byRemainder.get(k);
      parts.set(i, parts.get(i).add(BigDecimal.ONE));
    }
    parts.replaceAll(p -> p.movePointLeft(accountScale).setScale(accountScale,
        RoundingMode.HALF_UP));
    return parts;
  }

  /**
   * Registers one payment per planned entry through the same service the default path uses (rate
   * and transaction amount stored verbatim, no write-off) and collects each new transaction.
   */
  private static NeoResponse execute(ConversionPaymentContext ctx,
      List<InvoiceInstallment> installments, List<PlannedPayment> payments) throws Exception {
    FIN_PaymentMethod chosenMethod =
        ReconciliationFlowSupport.resolveChosenMethod(ctx.paymentMethodId());
    FIN_BankStatementLine line = ctx.line();
    boolean isReceipt = lineAmount(line).signum() >= 0;
    for (PlannedPayment planned : payments) {
      InvoiceInstallment installment = installments.get(planned.index());
      FIN_Payment payment = ReconciliationPaymentService.registerReconciliationPayment(
          new ReconciliationPaymentService.ReconciliationPaymentRequest(installment.invoice(),
              installment.schedule(), planned.paymentAmount(), planned.transactionAmount(),
              planned.rate(), line.getTransactionDate(), ctx.account(), isReceipt, chosenMethod,
              false));
      NeoResponse txnError = ReconciliationFlowSupport.collectTransaction(payment,
          ctx.operationIds());
      if (txnError != null) {
        return txnError;
      }
    }
    return null;
  }

  /**
   * Loads every spec tenant-guarded ({@link ReconciliationFlowSupport#loadInstallment}), stopping
   * at the first that does not resolve. A schedule named twice is kept once: its outstanding can
   * only be paid once, and counting it twice would let {@code actualPayment} exceed what is owed.
   */
  private static InstallmentLoad loadInstallments(JSONArray invoiceSpecs) throws JSONException {
    List<InvoiceInstallment> installments = new ArrayList<>();
    Set<String> scheduleIds = new HashSet<>();
    for (int i = 0; i < invoiceSpecs.length(); i++) {
      JSONObject spec = invoiceSpecs.getJSONObject(i);
      InvoiceInstallment installment = ReconciliationFlowSupport.loadInstallment(spec);
      if (installment.error() != null) {
        return new InstallmentLoad(List.of(), installment.error());
      }
      if (scheduleIds.add(spec.optString(FIELD_SCHEDULE_ID))) {
        installments.add(installment);
      }
    }
    return new InstallmentLoad(installments, null);
  }

  /**
   * The one currency every installment's invoice shares, or {@code null} when they do not share
   * one, or when it is the account's own currency (nothing to convert).
   */
  private static Currency sharedForeignCurrency(List<InvoiceInstallment> installments,
      FIN_FinancialAccount account) {
    Currency accountCurrency = account.getCurrency();
    Currency shared = installments.isEmpty() ? null : installments.get(0).invoice().getCurrency();
    if (shared == null || accountCurrency == null
        || shared.getId().equals(accountCurrency.getId())) {
      return null;
    }
    for (InvoiceInstallment installment : installments) {
      Currency currency = installment.invoice().getCurrency();
      if (currency == null || !shared.getId().equals(currency.getId())) {
        return null;
      }
    }
    return shared;
  }

  private static BigDecimal outstandingOf(InvoiceInstallment installment) {
    return nullSafe(installment.schedule().getOutstandingAmount()).abs();
  }

  private static BigDecimal lineAmount(FIN_BankStatementLine line) {
    return nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
  }

  /** {@code raw} as a number rounded HALF_UP to {@code scale}, or {@code null} when malformed. */
  private static BigDecimal scaledAmount(String raw, int scale) {
    if (raw == null) {
      return null;
    }
    try {
      return new BigDecimal(raw).setScale(scale, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The trimmed value of {@code key}, or {@code null} when absent, JSON null or blank. */
  private static String rawField(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return null;
    }
    String raw = body.optString(key, "").trim();
    return raw.isEmpty() ? null : raw;
  }

  private static NeoResponse badRequest(String message) {
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
