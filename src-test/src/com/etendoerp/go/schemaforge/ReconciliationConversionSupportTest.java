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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

import com.etendoerp.go.schemaforge.ReconciliationConversionSupport.ConversionPaymentContext;
import com.etendoerp.go.schemaforge.ReconciliationConversionSupport.ConversionPlan;
import com.etendoerp.go.schemaforge.ReconciliationConversionSupport.ExplicitConversion;
import com.etendoerp.go.schemaforge.ReconciliationConversionSupport.PlannedPayment;
import com.etendoerp.go.schemaforge.ReconciliationConversionSupport.ValidatedConversion;
import com.etendoerp.go.schemaforge.ReconciliationPaymentService.ReconciliationPaymentRequest;

/**
 * Unit tests for {@link ReconciliationConversionSupport}, the explicit-conversion mode of
 * {@code reconcileGroup}'s invoice leg (contract: {@code docs/neo-headless.md} §4.12.1.1,
 * "Explicit conversion on reconcileGroup").
 *
 * <ul>
 *   <li>{@code plan}: pure allocation — request-order filling, largest-remainder split of the
 *       converted amount (Σ txn = converted, ties to the earlier invoice) and a rate per payment
 *       that reproduces its transaction amount;</li>
 *   <li>{@code parse}: which bodies select the mode at all;</li>
 *   <li>{@code payInvoices} validation: the 400/404 refusals, their literal messages and their
 *       order, with nothing registered on any refusal;</li>
 *   <li>{@code payInvoices} execution: one {@code registerReconciliationPayment} per paid invoice
 *       with (pay, txn, rate, no write-off), each transaction collected into {@code operationIds},
 *       and the invoice's own exchange rate never consulted.</li>
 * </ul>
 *
 * <p>The DAL is mocked statically ({@link OBDal}, {@link OBContext} for the tenant guard), as are
 * the two write boundaries ({@link ReconciliationPaymentService} and {@link ReactivationSupport}).
 * Account currency is {@code EUR} (2 decimals); invoices are {@code USD} (2 decimals).
 *
 * @covers com.etendoerp.go.schemaforge.ReconciliationConversionSupport
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReconciliationConversionSupport — explicit conversion on reconcileGroup")
class ReconciliationConversionSupportTest {

  private static final String TENANT = "client-1";
  private static final String FOREIGN_TENANT = "client-other";
  private static final String MSG_NOT_FOUND = "Invoice or payment schedule not found: ";
  private static final Date TRANSACTION_DATE = new Date(1_760_000_000_000L);

  private OBDal dal;
  private OBContext obContext;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<ReconciliationPaymentService> paymentService;
  private MockedStatic<ReactivationSupport> reactivation;

  /** Every payment request registered, in call order. */
  private final List<ReconciliationPaymentRequest> requests = new ArrayList<>();
  /** The transaction each registered payment produced, in call order. */
  private final List<FIN_FinaccTransaction> transactions = new ArrayList<>();

  private Currency eur;
  private Currency usd;
  private FIN_FinancialAccount account;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obContext = mock(OBContext.class);
    when(obContext.getReadableClients()).thenReturn(new String[] { TENANT });
    when(obContext.getReadableOrganizations()).thenReturn(new String[] { "org-1" });
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    obContextStatic = mockStatic(OBContext.class);
    obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);
    reactivation = mockStatic(ReactivationSupport.class);
    paymentService = mockStatic(ReconciliationPaymentService.class);
    paymentService.when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
        .thenAnswer(inv -> {
          requests.add(inv.getArgument(0));
          int n = requests.size();
          FIN_FinaccTransaction txn = mock(FIN_FinaccTransaction.class);
          when(txn.getId()).thenReturn("txn-" + n);
          transactions.add(txn);
          FIN_Payment payment = mock(FIN_Payment.class);
          when(payment.getId()).thenReturn("pay-" + n);
          when(payment.getFINFinaccTransactionList()).thenReturn(List.of(txn));
          return payment;
        });

    eur = currency("EUR", 2);
    usd = currency("USD", 2);
    account = account(eur);
  }

  @AfterEach
  void tearDown() {
    paymentService.close();
    reactivation.close();
    obContextStatic.close();
    obDalStatic.close();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static Currency currency(String id, Integer precision) {
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(id);
    when(c.getStandardPrecision()).thenReturn(precision == null ? null : Long.valueOf(precision));
    return c;
  }

  private static FIN_FinancialAccount account(Currency currency) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    when(acc.getCurrency()).thenReturn(currency);
    return acc;
  }

  private static Client client(String id) {
    Client c = mock(Client.class);
    when(c.getId()).thenReturn(id);
    return c;
  }

  /** An invoice of {@code tenant}, resolvable through {@code OBDal.get}. */
  private Invoice invoice(String id, Currency currency, String tenant) {
    Invoice inv = mock(Invoice.class);
    Client owner = client(tenant);
    when(inv.getId()).thenReturn(id);
    when(inv.getCurrency()).thenReturn(currency);
    when(inv.getClient()).thenReturn(owner);
    when(dal.get(Invoice.class, id)).thenReturn(inv);
    return inv;
  }

  private Invoice invoice(String id, Currency currency) {
    return invoice(id, currency, TENANT);
  }

  /** A schedule of {@code tenant} owned by {@code owner}, resolvable through {@code OBDal.get}. */
  private FIN_PaymentSchedule schedule(String id, String outstanding, Invoice owner,
      String tenant) {
    FIN_PaymentSchedule sch = mock(FIN_PaymentSchedule.class);
    Client scheduleClient = client(tenant);
    when(sch.getId()).thenReturn(id);
    when(sch.getOutstandingAmount()).thenReturn(new BigDecimal(outstanding));
    when(sch.getInvoice()).thenReturn(owner);
    when(sch.getClient()).thenReturn(scheduleClient);
    when(dal.get(FIN_PaymentSchedule.class, id)).thenReturn(sch);
    return sch;
  }

  private FIN_PaymentSchedule schedule(String id, String outstanding, Invoice owner) {
    return schedule(id, outstanding, owner, TENANT);
  }

  /** A statement line with the given credit / debit amounts (line amount = cr - dr). */
  private static FIN_BankStatementLine line(String cr, String dr) {
    FIN_BankStatementLine l = mock(FIN_BankStatementLine.class);
    when(l.getCramount()).thenReturn(new BigDecimal(cr));
    when(l.getDramount()).thenReturn(new BigDecimal(dr));
    when(l.getTransactionDate()).thenReturn(TRANSACTION_DATE);
    return l;
  }

  private static JSONObject spec(String invoiceId, String scheduleId) throws JSONException {
    JSONObject o = new JSONObject();
    if (invoiceId != null) {
      o.put("invoiceId", invoiceId);
    }
    if (scheduleId != null) {
      o.put("scheduleId", scheduleId);
    }
    return o;
  }

  private static JSONArray specs(JSONObject... items) {
    JSONArray arr = new JSONArray();
    for (JSONObject o : items) {
      arr.put(o);
    }
    return arr;
  }

  /** A receipt (credit) line context with no operations, no method and no write-off. */
  private ConversionPaymentContext ctx(FIN_BankStatementLine line, JSONArray invoiceSpecs) {
    return new ConversionPaymentContext(account, line, invoiceSpecs, new ArrayList<>(), null,
        false);
  }

  private static ExplicitConversion conversion(String actualPayment, String rate,
      String converted) {
    return new ExplicitConversion(actualPayment, rate, converted);
  }

  /** One USD invoice INV-1 / SCH-1 with {@code outstanding}. */
  private JSONArray oneUsdInvoice(String outstanding) throws JSONException {
    Invoice inv = invoice("INV-1", usd);
    schedule("SCH-1", outstanding, inv);
    return specs(spec("INV-1", "SCH-1"));
  }

  private static String message(NeoResponse response) throws JSONException {
    return response.getBody().getJSONObject("error").getString("message");
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertNotNull(actual, "expected " + expected);
    assertEquals(0, new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " but was " + actual.toPlainString());
  }

  /** Accounting recomputes pay x rate: it must round back to the booked transaction amount. */
  private static void assertReproduces(BigDecimal pay, BigDecimal rate, BigDecimal txn,
      int scale) {
    assertEquals(0, pay.multiply(rate).setScale(scale, RoundingMode.HALF_UP).compareTo(txn),
        pay + " x " + rate + " must round to " + txn);
  }

  private static void assertRefused(NeoResponse response, int status, String expectedMessage)
      throws JSONException {
    assertNotNull(response, "expected a " + status + " refusal");
    assertEquals(status, response.getHttpStatus(), message(response));
    assertEquals(expectedMessage, message(response));
  }

  private void assertNothingRegistered() {
    paymentService.verify(
        () -> ReconciliationPaymentService.registerReconciliationPayment(any()), never());
  }

  private static List<BigDecimal> amounts(String... values) {
    List<BigDecimal> out = new ArrayList<>();
    for (String v : values) {
      out.add(new BigDecimal(v));
    }
    return out;
  }

  // ── plan: pure allocation ─────────────────────────────────────────────────

  @Nested
  @DisplayName("plan (pure allocation)")
  class Plan {

    @Test
    @DisplayName("one invoice 40.91 settled for 27.87 → pay 40.91, txn 27.87, rate 0.681252")
    void singleInvoiceDerivesTheReproducingRate() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("40.91"),
          new BigDecimal("40.91"), new BigDecimal("27.87"), null, 2);

      assertFalse(plan.tooSmall());
      assertEquals(1, plan.payments().size());
      PlannedPayment p = plan.payments().get(0);
      assertEquals(0, p.index());
      assertAmount("40.91", p.paymentAmount());
      assertEquals("27.87", p.transactionAmount().toPlainString());
      assertEquals("0.681252", p.rate().toPlainString());
      assertEquals("27.87",
          new BigDecimal("40.91").multiply(p.rate()).setScale(2, RoundingMode.HALF_UP)
              .toPlainString());
    }

    @Test
    @DisplayName("a typed rate that reproduces the transaction is kept verbatim")
    void typedRateKeptWhenItRoundTrips() {
      BigDecimal typed = new BigDecimal("0.6813");

      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("40.91"),
          new BigDecimal("40.91"), new BigDecimal("27.87"), typed, 2);

      assertSame(typed, plan.payments().get(0).rate());
    }

    @Test
    @DisplayName("a typed rate that does not reproduce the transaction is replaced")
    void typedRateReplacedWhenItDoesNotRoundTrip() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("40.91"),
          new BigDecimal("40.91"), new BigDecimal("27.87"), new BigDecimal("0.7"), 2);

      PlannedPayment p = plan.payments().get(0);
      assertEquals("0.681252", p.rate().toPlainString());
      assertEquals("27.87", p.transactionAmount().toPlainString(),
          "the converted amount wins over the typed rate");
    }

    @Test
    @DisplayName("several invoices: largest remainder, Σ txn == converted, each rate reproduces")
    void severalInvoicesSplitByLargestRemainder() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(
          amounts("100.00", "50.00", "25.00"), new BigDecimal("175.00"),
          new BigDecimal("116.67"), null, 2);

      assertFalse(plan.tooSmall());
      List<PlannedPayment> payments = plan.payments();
      assertEquals(3, payments.size());
      // Floors 66.66 / 33.33 / 16.66 leave 2 minor units; remainders 150 / 75 / 125 give them to
      // the first and the third invoice — not simply to the first two.
      assertEquals("66.67", payments.get(0).transactionAmount().toPlainString());
      assertEquals("33.33", payments.get(1).transactionAmount().toPlainString());
      assertEquals("16.67", payments.get(2).transactionAmount().toPlainString());
      BigDecimal sum = BigDecimal.ZERO;
      for (PlannedPayment p : payments) {
        sum = sum.add(p.transactionAmount());
        assertReproduces(p.paymentAmount(), p.rate(), p.transactionAmount(), 2);
      }
      assertEquals(0, new BigDecimal("116.67").compareTo(sum), "Σ txn must equal converted");
    }

    @Test
    @DisplayName("equal remainders: the leftover units go to the earlier invoices")
    void tiesGoToTheEarlierInvoice() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(
          amounts("10.00", "10.00", "10.00"), new BigDecimal("30.00"), new BigDecimal("20.00"),
          null, 2);

      List<PlannedPayment> payments = plan.payments();
      assertEquals("6.67", payments.get(0).transactionAmount().toPlainString());
      assertEquals("6.67", payments.get(1).transactionAmount().toPlainString());
      assertEquals("6.66", payments.get(2).transactionAmount().toPlainString());
      for (PlannedPayment p : payments) {
        assertReproduces(p.paymentAmount(), p.rate(), p.transactionAmount(), 2);
      }
    }

    @Test
    @DisplayName("partial actualPayment 21.34 of 40.91 against 27.87 → rate 1.305998")
    void partialActualPaymentOnOneInvoice() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("40.91"),
          new BigDecimal("21.34"), new BigDecimal("27.87"), null, 2);

      PlannedPayment p = plan.payments().get(0);
      assertAmount("21.34", p.paymentAmount());
      assertEquals("27.87", p.transactionAmount().toPlainString());
      assertEquals("1.305998", p.rate().toPlainString());
      assertReproduces(p.paymentAmount(), p.rate(), p.transactionAmount(), 2);
    }

    @Test
    @DisplayName("live case: 21.34 + 21.34 USD converted to 27.87 → 13.94 + 13.93")
    void twoEqualInvoicesSplitTheOddCentToTheFirst() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("21.34", "21.34"),
          new BigDecimal("42.68"), new BigDecimal("27.87"), null, 2);

      assertFalse(plan.tooSmall());
      List<PlannedPayment> payments = plan.payments();
      assertEquals(2, payments.size());
      assertEquals("13.94", payments.get(0).transactionAmount().toPlainString());
      assertEquals("13.93", payments.get(1).transactionAmount().toPlainString());
      assertEquals(0, new BigDecimal("27.87").compareTo(
          payments.get(0).transactionAmount().add(payments.get(1).transactionAmount())));
      assertEquals("0.653233", payments.get(0).rate().toPlainString());
      assertEquals("0.652765", payments.get(1).rate().toPlainString());
      for (PlannedPayment p : payments) {
        assertAmount("21.34", p.paymentAmount());
        assertReproduces(p.paymentAmount(), p.rate(), p.transactionAmount(), 2);
      }
    }

    @Test
    @DisplayName("partial actualPayment fills invoices in request order")
    void partialActualPaymentFillsInRequestOrder() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("30.00", "20.00"),
          new BigDecimal("40.00"), new BigDecimal("26.00"), null, 2);

      List<PlannedPayment> payments = plan.payments();
      assertEquals(2, payments.size());
      assertAmount("30.00", payments.get(0).paymentAmount());
      assertAmount("10.00", payments.get(1).paymentAmount());
      assertEquals("19.50", payments.get(0).transactionAmount().toPlainString());
      assertEquals("6.50", payments.get(1).transactionAmount().toPlainString());
    }

    @Test
    @DisplayName("an invoice that gets nothing is left out of the plan")
    void invoiceExhaustedBeforeItsTurnGetsNoPayment() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("10.00", "10.00"),
          new BigDecimal("10.00"), new BigDecimal("6.50"), null, 2);

      assertEquals(1, plan.payments().size());
      assertEquals(0, plan.payments().get(0).index());
      assertEquals("6.50", plan.payments().get(0).transactionAmount().toPlainString());
    }

    @Test
    @DisplayName("a zero-outstanding invoice is skipped and keeps its position in the input")
    void zeroOutstandingInvoiceSkipped() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("0.00", "40.91"),
          new BigDecimal("40.91"), new BigDecimal("27.87"), null, 2);

      assertFalse(plan.tooSmall());
      assertEquals(1, plan.payments().size());
      PlannedPayment p = plan.payments().get(0);
      assertEquals(1, p.index(), "the index points at the second installment of the input");
      assertAmount("40.91", p.paymentAmount());
      assertEquals("27.87", p.transactionAmount().toPlainString());
    }

    @Test
    @DisplayName("a zero-precision account currency splits in whole units")
    void zeroPrecisionAccountCurrency() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(amounts("10.00", "20.00"),
          new BigDecimal("30.00"), new BigDecimal("100"), null, 0);

      List<PlannedPayment> payments = plan.payments();
      assertEquals("33", payments.get(0).transactionAmount().toPlainString());
      assertEquals("67", payments.get(1).transactionAmount().toPlainString());
      for (PlannedPayment p : payments) {
        assertEquals(0, p.transactionAmount().scale());
        assertReproduces(p.paymentAmount(), p.rate(), p.transactionAmount(), 0);
      }
    }

    @Test
    @DisplayName("0.02 across three paid invoices is tooSmall, with no payments")
    void convertedTooSmallForEveryInvoice() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(
          amounts("1.00", "1.00", "1.00"), new BigDecimal("3.00"), new BigDecimal("0.02"), null,
          2);

      assertTrue(plan.tooSmall());
      assertTrue(plan.payments().isEmpty());
    }

    @Test
    @DisplayName("0.03 across the same three invoices is just enough: one cent each")
    void convertedExactlyOneUnitPerInvoice() {
      ConversionPlan plan = ReconciliationConversionSupport.plan(
          amounts("1.00", "1.00", "1.00"), new BigDecimal("3.00"), new BigDecimal("0.03"), null,
          2);

      assertFalse(plan.tooSmall());
      assertEquals(3, plan.payments().size());
      plan.payments().forEach(p -> assertEquals("0.01", p.transactionAmount().toPlainString()));
    }
  }

  // ── parse: which bodies select the mode ───────────────────────────────────

  @Nested
  @DisplayName("parse")
  class Parse {

    @Test
    @DisplayName("a null body or a body with none of the fields → null (default mode)")
    void noFieldIsAbsent() throws JSONException {
      assertNull(ReconciliationConversionSupport.parse(null));
      assertNull(ReconciliationConversionSupport.parse(new JSONObject()
          .put("paymentMethodId", "PM-1").put("writeoffDifference", false)));
    }

    @Test
    @DisplayName("blank strings and JSON null count as absent")
    void blankAndJsonNullAreAbsent() throws JSONException {
      JSONObject body = new JSONObject()
          .put(ReconciliationConversionSupport.KEY_ACTUAL_PAYMENT, "   ")
          .put(ReconciliationConversionSupport.KEY_CONVERSION_RATE, JSONObject.NULL)
          .put(ReconciliationConversionSupport.KEY_CONVERTED_AMOUNT, "");

      assertNull(ReconciliationConversionSupport.parse(body));
    }

    @Test
    @DisplayName("numbers and numeric strings are read as trimmed text")
    void numbersAndNumericStrings() throws JSONException {
      JSONObject body = new JSONObject()
          .put(ReconciliationConversionSupport.KEY_ACTUAL_PAYMENT, 40.91)
          .put(ReconciliationConversionSupport.KEY_CONVERSION_RATE, " 0.681252 ")
          .put(ReconciliationConversionSupport.KEY_CONVERTED_AMOUNT, 100);

      ExplicitConversion parsed = ReconciliationConversionSupport.parse(body);

      assertNotNull(parsed);
      assertEquals("40.91", parsed.actualPayment());
      assertEquals("0.681252", parsed.conversionRate());
      assertEquals("100", parsed.convertedAmount());
    }

    @Test
    @DisplayName("one field is enough to select the mode; the others stay null")
    void oneFieldSelectsTheMode() throws JSONException {
      ExplicitConversion parsed = ReconciliationConversionSupport.parse(new JSONObject()
          .put(ReconciliationConversionSupport.KEY_CONVERTED_AMOUNT, "27.87")
          .put(ReconciliationConversionSupport.KEY_ACTUAL_PAYMENT, JSONObject.NULL));

      assertNotNull(parsed);
      assertNull(parsed.actualPayment());
      assertNull(parsed.conversionRate());
      assertEquals("27.87", parsed.convertedAmount());
    }
  }

  // ── payInvoices: validation ───────────────────────────────────────────────

  @Nested
  @DisplayName("payInvoices — validation (nothing is written on a refusal)")
  class Validation {

    @Test
    @DisplayName("existing operationIds → 400, before any id is even loaded")
    void refusedWithOperationIds() throws Exception {
      List<String> operationIds = new ArrayList<>(List.of("T1"));
      ConversionPaymentContext ctx = new ConversionPaymentContext(account, line("27.87", "0"),
          specs(spec("UNKNOWN", "UNKNOWN")), operationIds, null, false);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(ctx,
          conversion("40.91", null, "27.87"));

      assertRefused(response, 400,
          "Conversion fields cannot be combined with existing transactions or a write-off");
      verifyNoInteractions(dal);
      assertEquals(List.of("T1"), operationIds);
      assertNothingRegistered();
    }

    @Test
    @DisplayName("writeoffDifference:true → 400")
    void refusedWithWriteoff() throws Exception {
      ConversionPaymentContext ctx = new ConversionPaymentContext(account, line("27.87", "0"),
          oneUsdInvoice("40.91"), new ArrayList<>(), null, true);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(ctx,
          conversion("40.91", null, "27.87"));

      assertRefused(response, 400,
          "Conversion fields cannot be combined with existing transactions or a write-off");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("a blank scheduleId → 400")
    void blankScheduleId() throws Exception {
      invoice("INV-1", usd);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", null))),
          conversion("40.91", null, "27.87"));

      assertRefused(response, 400, "invoiceId and scheduleId are required for each invoice");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("an unknown invoice → 404")
    void unknownInvoice() throws Exception {
      schedule("SCH-1", "40.91", null);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-X", "SCH-1"))),
          conversion("40.91", null, "27.87"));

      assertRefused(response, 404, MSG_NOT_FOUND + "INV-X");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("another tenant's schedule → the same 404, and its amount is never echoed")
    void foreignTenantScheduleIs404WithoutAmounts() throws Exception {
      Invoice inv = invoice("INV-1", usd);
      schedule("SCH-F", "987.65", inv, FOREIGN_TENANT);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", "SCH-F"))),
          conversion("40.91", null, "27.87"));

      assertRefused(response, 404, MSG_NOT_FOUND + "INV-1");
      assertFalse(String.valueOf(response.getBody()).contains("987.65"),
          "a foreign outstanding must not be echoed: " + response.getBody());
      assertNothingRegistered();
    }

    @Test
    @DisplayName("another tenant's invoice → 404, even when an earlier invoice would fail later")
    void everyIdIsLoadedBeforeTheCurrencyCheck() throws Exception {
      // The first invoice is in the account currency (a currency refusal), but the second is
      // foreign: loading comes first, so the answer is the 404, not the currency 400.
      Invoice own = invoice("INV-1", eur);
      schedule("SCH-1", "10.00", own);
      Invoice foreign = invoice("INV-F", usd, FOREIGN_TENANT);
      schedule("SCH-F", "987.65", foreign);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", "SCH-1"), spec("INV-F", "SCH-F"))),
          conversion("40.91", null, "27.87"));

      assertRefused(response, 404, MSG_NOT_FOUND + "INV-F");
      assertFalse(String.valueOf(response.getBody()).contains("987.65"));
      assertNothingRegistered();
    }

    @Test
    @DisplayName("a schedule of another invoice → 404")
    void scheduleOfAnotherInvoice() throws Exception {
      invoice("INV-1", usd);
      Invoice other = invoice("INV-2", usd);
      schedule("SCH-2", "40.91", other);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", "SCH-2"))),
          conversion("40.91", null, "27.87"));

      assertRefused(response, 404, MSG_NOT_FOUND + "INV-1");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("invoices of two different currencies → 400")
    void mixedInvoiceCurrencies() throws Exception {
      Invoice first = invoice("INV-1", usd);
      schedule("SCH-1", "10.00", first);
      Invoice second = invoice("INV-2", currency("GBP", 2));
      schedule("SCH-2", "10.00", second);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", "SCH-1"), spec("INV-2", "SCH-2"))),
          conversion("20.00", null, "13.00"));

      assertRefused(response, 400, "Conversion fields require all selected invoices to share "
          + "one currency different from the account currency");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("invoices in the account's own currency → 400")
    void invoiceInTheAccountCurrency() throws Exception {
      Invoice inv = invoice("INV-1", currency("EUR", 2));
      schedule("SCH-1", "40.91", inv);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", "SCH-1"))),
          conversion("40.91", null, "27.87"));

      assertRefused(response, 400, "Conversion fields require all selected invoices to share "
          + "one currency different from the account currency");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("an account with no currency → the same currency 400")
    void accountWithoutCurrency() throws Exception {
      account = account(null);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", null, "27.87"));

      assertRefused(response, 400, "Conversion fields require all selected invoices to share "
          + "one currency different from the account currency");
    }

    @Test
    @DisplayName("the currency check runs before the amount checks")
    void currencyRefusalBeatsAnAmountRefusal() throws Exception {
      Invoice inv = invoice("INV-1", currency("EUR", 2));
      schedule("SCH-1", "40.91", inv);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-1", "SCH-1"))),
          conversion("-1", "abc", "0"));

      assertRefused(response, 400, "Conversion fields require all selected invoices to share "
          + "one currency different from the account currency");
    }

    @ParameterizedTest(name = "conversionRate=''{0}'', convertedAmount=''{1}''")
    @CsvSource(delimiter = '|', value = {
        "      | 27.87",
        "0.68  |      ",
        "0.68  | 27.87",
        "abc   |      " })
    @DisplayName("actualPayment absent while a rate or a converted amount is sent → 400 required")
    void actualPaymentRequiredWithTheOtherFields(String rate, String converted) throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion(null, rate, converted));

      assertRefused(response, 400,
          "actualPayment is required when conversionRate or convertedAmount is sent");
      assertNothingRegistered();
    }

    @ParameterizedTest(name = "actualPayment=''{0}''")
    @ValueSource(strings = { "abc", "0", "-5", "40.92", "40.915" })
    @DisplayName("actualPayment malformed, ≤ 0 or above the outstanding → 400 range")
    void actualPaymentOutOfRange(String actualPayment) throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")),
          conversion(actualPayment, null, "27.87"));

      assertRefused(response, 400, "The amount to pay must be greater than zero and not exceed "
          + "the outstanding amount of the selected invoices");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("actualPayment rounds HALF_UP to the invoice precision before the range check")
    void actualPaymentRoundedToInvoicePrecision() throws Exception {
      ValidatedConversion validated = ReconciliationConversionSupport.validate(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.914", null, "27.87"));

      assertNull(validated.error());
      assertEquals("40.91", validated.actualPayment().toPlainString());
    }

    /** Two USD invoices, 30.00 and 20.00 outstanding: 50.00 owed in all. */
    private JSONArray twoUsdInvoicesOwing50() throws JSONException {
      Invoice first = invoice("INV-1", usd);
      schedule("SCH-1", "30.00", first);
      Invoice second = invoice("INV-2", usd);
      schedule("SCH-2", "20.00", second);
      return specs(spec("INV-1", "SCH-1"), spec("INV-2", "SCH-2"));
    }

    @Test
    @DisplayName("actualPayment equal to the outstanding of all selected invoices is accepted")
    void actualPaymentAtTheOutstandingBoundary() throws Exception {
      ValidatedConversion validated = ReconciliationConversionSupport.validate(
          ctx(line("33.33", "0"), twoUsdInvoicesOwing50()), conversion("50.00", null, "33.33"));

      assertNull(validated.error(), "50.00 = 30.00 + 20.00");
      assertEquals(2, validated.installments().size());
      assertEquals("50.00", validated.actualPayment().toPlainString());
    }

    @Test
    @DisplayName("actualPayment one cent above the outstanding of all selected invoices → 400")
    void actualPaymentJustAboveTheOutstandingBoundary() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("33.33", "0"), twoUsdInvoicesOwing50()), conversion("50.01", null, "33.33"));

      assertRefused(response, 400, "The amount to pay must be greater than zero and not exceed "
          + "the outstanding amount of the selected invoices");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("a schedule named twice is counted once")
    void duplicatedScheduleCountedOnce() throws Exception {
      Invoice inv = invoice("INV-1", usd);
      schedule("SCH-1", "40.91", inv);
      JSONArray twice = specs(spec("INV-1", "SCH-1"), spec("INV-1", "SCH-1"));

      NeoResponse refused = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), twice), conversion("81.82", null, "27.87"));
      assertRefused(refused, 400, "The amount to pay must be greater than zero and not exceed "
          + "the outstanding amount of the selected invoices");

      List<String> operationIds = new ArrayList<>();
      NeoResponse accepted = ReconciliationConversionSupport.payInvoices(
          new ConversionPaymentContext(account, line("27.87", "0"), twice, operationIds, null,
              false),
          conversion("40.91", null, "27.87"));
      assertNull(accepted);
      assertEquals(1, requests.size(), "the duplicated schedule is paid once");
      assertEquals(List.of("txn-1"), operationIds);
    }

    @ParameterizedTest(name = "conversionRate=''{0}'', convertedAmount=''{1}''")
    @CsvSource(delimiter = '|', value = {
        "abc   | 27.87 | Invalid conversion rate format",
        "abc   |       | Invalid conversion rate format",
        "0     | 27.87 | Conversion rate must be greater than zero",
        "0     |       | Conversion rate must be greater than zero",
        "-0.5  | 27.87 | Conversion rate must be greater than zero",
        "-0.5  |       | Conversion rate must be greater than zero",
        "1     |       | A conversion rate other than 1 is required when the invoice and account "
            + "currencies differ",
        "1.000 |       | A conversion rate other than 1 is required when the invoice and account "
            + "currencies differ" })
    @DisplayName("a malformed or non-positive rate (always), or a rate of 1 driving the conversion "
        + "→ the modal's own 400")
    void typedRateRefused(String rate, String converted, String expectedMessage)
        throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", rate, converted));

      assertRefused(response, 400, expectedMessage);
      assertNothingRegistered();
    }

    /**
     * With {@code convertedAmount} present the rate is advisory: a 1:1 pegged pair sent as rate 1
     * stays reconcilable, and the rate is kept verbatim because it reproduces the transaction.
     */
    @ParameterizedTest(name = "conversionRate=''{0}''")
    @ValueSource(strings = { "1", "1.000" })
    @DisplayName("a rate of exactly 1 with convertedAmount present is accepted (pegged pair)")
    void rateOfOneAcceptedWhenConvertedAmountIsPresent(String rate) throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("27.87")), conversion("27.87", rate, "27.87"));

      assertNull(response);
      assertEquals(1, requests.size());
      assertEquals("27.87", requests.get(0).accountAmount().toPlainString());
      assertEquals(rate, requests.get(0).rate().toPlainString(), "kept as typed");
    }

    @ParameterizedTest(name = "conversionRate=''{0}''")
    @ValueSource(strings = { "1", "1.000" })
    @DisplayName("a rate of 1 that does not reproduce convertedAmount is replaced, not refused")
    void rateOfOneIsOnlyAdvisoryWhenConvertedAmountIsPresent(String rate) throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", rate, "27.87"));

      assertNull(response);
      assertEquals("27.87", requests.get(0).accountAmount().toPlainString());
      assertEquals("0.681252", requests.get(0).rate().toPlainString());
    }

    @Test
    @DisplayName("the actualPayment check runs before the rate check")
    void actualPaymentRefusalBeatsARateRefusal() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("0", "abc", "27.87"));

      assertRefused(response, 400, "The amount to pay must be greater than zero and not exceed "
          + "the outstanding amount of the selected invoices");
    }

    @Test
    @DisplayName("the rate check runs before the converted-amount check")
    void rateRefusalBeatsAConvertedRefusal() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", "abc", "0"));

      assertRefused(response, 400, "Invalid conversion rate format");
    }

    @ParameterizedTest(name = "convertedAmount=''{0}''")
    @ValueSource(strings = { "0", "-1", "abc", "27.88", "27.875" })
    @DisplayName("convertedAmount ≤ 0, malformed or above |line| (strict) → 400")
    void convertedAmountOutOfRange(String converted) throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", null, converted));

      assertRefused(response, 400,
          "The converted amount must be greater than zero and not exceed the statement line "
              + "amount");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("a rate-derived converted amount above |line| is refused the same way")
    void rateDerivedConvertedAboveTheLine() throws Exception {
      // 40.91 x 0.7 = 28.637 → 28.64 > 27.87
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", "0.7", null));

      assertRefused(response, 400,
          "The converted amount must be greater than zero and not exceed the statement line "
              + "amount");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("a converted amount equal to |line| on a debit line is accepted")
    void convertedEqualToADebitLine() throws Exception {
      ValidatedConversion validated = ReconciliationConversionSupport.validate(
          ctx(line("0", "27.87"), oneUsdInvoice("40.91")), conversion("40.91", null, "27.87"));

      assertNull(validated.error());
      assertEquals("27.87", validated.convertedAmount().toPlainString());
      assertEquals(2, validated.accountScale());
    }

    @Test
    @DisplayName("a share that rounds to zero → 400 too small, nothing registered")
    void convertedTooSmall() throws Exception {
      Invoice a = invoice("INV-A", usd);
      schedule("SCH-A", "1.00", a);
      Invoice b = invoice("INV-B", usd);
      schedule("SCH-B", "1.00", b);
      Invoice c = invoice("INV-C", usd);
      schedule("SCH-C", "1.00", c);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("0.02", "0"),
              specs(spec("INV-A", "SCH-A"), spec("INV-B", "SCH-B"), spec("INV-C", "SCH-C"))),
          conversion("3.00", null, "0.02"));

      assertRefused(response, 400,
          "The converted amount is too small to allocate across the selected invoices");
      assertNothingRegistered();
    }

    @Test
    @DisplayName("only actualPayment → converted defaults to |line|, rounded DOWN")
    void onlyActualPaymentDefaultsToTheLine() throws Exception {
      ValidatedConversion validated = ReconciliationConversionSupport.validate(
          ctx(line("27.879", "0"), oneUsdInvoice("40.91")), conversion("40.91", null, null));

      assertNull(validated.error());
      assertEquals("27.87", validated.convertedAmount().toPlainString(),
          "rounded DOWN, so the default never exceeds the line");
      assertNull(validated.preferredRate());
    }
  }

  // ── payInvoices: execution ────────────────────────────────────────────────

  @Nested
  @DisplayName("payInvoices — execution")
  class Execution {

    @Test
    @DisplayName("one invoice: registers (40.91, 27.87, 0.681252, no write-off) and collects it")
    void singleInvoiceRegistersAndCollects() throws Exception {
      Invoice inv = invoice("INV-1", usd);
      FIN_PaymentSchedule sch = schedule("SCH-1", "40.91", inv);
      FIN_BankStatementLine line = line("27.87", "0");
      List<String> operationIds = new ArrayList<>();

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          new ConversionPaymentContext(account, line, specs(spec("INV-1", "SCH-1")),
              operationIds, null, false),
          conversion("40.91", null, "27.87"));

      assertNull(response);
      assertEquals(1, requests.size());
      ReconciliationPaymentRequest req = requests.get(0);
      assertSame(inv, req.invoice());
      assertSame(sch, req.schedule());
      assertAmount("40.91", req.paymentAmount());
      assertEquals("27.87", req.accountAmount().toPlainString());
      assertEquals("0.681252", req.rate().toPlainString());
      assertEquals(TRANSACTION_DATE, req.paymentDate());
      assertSame(account, req.account());
      assertTrue(req.isReceipt(), "a credit line is a receipt");
      assertNull(req.chosenMethod());
      assertFalse(req.writeoffDifference(), "explicit conversion never writes off");
      assertEquals(List.of("txn-1"), operationIds);
      reactivation.verify(() -> ReactivationSupport.markAutoCreated(transactions.get(0)));
    }

    @Test
    @DisplayName("only actualPayment: the transaction is the whole line")
    void onlyActualPaymentBooksTheLine() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", null, null));

      assertNull(response);
      assertEquals("27.87", requests.get(0).accountAmount().toPlainString());
      assertEquals("0.681252", requests.get(0).rate().toPlainString());
    }

    @Test
    @DisplayName("only a rate: converted = round(actual x rate) and the rate is kept as typed")
    void onlyRateDerivesTheConvertedAmount() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", "0.6", null));

      assertNull(response);
      ReconciliationPaymentRequest req = requests.get(0);
      assertEquals("24.55", req.accountAmount().toPlainString(), "40.91 x 0.6 = 24.546");
      assertEquals("0.6", req.rate().toPlainString());
    }

    @Test
    @DisplayName("convertedAmount wins over a typed rate that does not reproduce it")
    void convertedWinsOverTheRate() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", "0.6", "27.87"));

      assertNull(response);
      ReconciliationPaymentRequest req = requests.get(0);
      assertEquals("27.87", req.accountAmount().toPlainString());
      assertEquals("0.681252", req.rate().toPlainString());
    }

    @Test
    @DisplayName("partial actualPayment 21.34 of 40.91 against 27.87 → rate 1.305998")
    void partialActualPayment() throws Exception {
      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("21.34", null, "27.87"));

      assertNull(response);
      ReconciliationPaymentRequest req = requests.get(0);
      assertAmount("21.34", req.paymentAmount());
      assertEquals("27.87", req.accountAmount().toPlainString());
      assertEquals("1.305998", req.rate().toPlainString());
    }

    @Test
    @DisplayName("two invoices: one payment each, in request order, Σ txn = converted")
    void twoInvoicesInRequestOrder() throws Exception {
      Invoice first = invoice("INV-1", usd);
      FIN_PaymentSchedule firstSchedule = schedule("SCH-1", "30.00", first);
      Invoice second = invoice("INV-2", usd);
      FIN_PaymentSchedule secondSchedule = schedule("SCH-2", "20.00", second);
      List<String> operationIds = new ArrayList<>();

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          new ConversionPaymentContext(account, line("33.33", "0"),
              specs(spec("INV-1", "SCH-1"), spec("INV-2", "SCH-2")), operationIds, null, false),
          conversion("50.00", null, "33.33"));

      assertNull(response);
      assertEquals(2, requests.size());
      assertSame(first, requests.get(0).invoice());
      assertSame(firstSchedule, requests.get(0).schedule());
      assertAmount("30.00", requests.get(0).paymentAmount());
      assertEquals("20.00", requests.get(0).accountAmount().toPlainString());
      assertEquals("0.666667", requests.get(0).rate().toPlainString());
      assertSame(second, requests.get(1).invoice());
      assertSame(secondSchedule, requests.get(1).schedule());
      assertAmount("20.00", requests.get(1).paymentAmount());
      assertEquals("13.33", requests.get(1).accountAmount().toPlainString());
      assertEquals("0.666500", requests.get(1).rate().toPlainString());
      requests.forEach(r -> assertFalse(r.writeoffDifference()));
      assertEquals(List.of("txn-1", "txn-2"), operationIds);
    }

    @Test
    @DisplayName("a zero-outstanding invoice gets no payment; the next one is paid")
    void zeroOutstandingInvoiceIsNotPaid() throws Exception {
      Invoice paidOff = invoice("INV-0", usd);
      schedule("SCH-0", "0.00", paidOff);
      Invoice open = invoice("INV-1", usd);
      FIN_PaymentSchedule openSchedule = schedule("SCH-1", "40.91", open);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("27.87", "0"), specs(spec("INV-0", "SCH-0"), spec("INV-1", "SCH-1"))),
          conversion("40.91", null, "27.87"));

      assertNull(response);
      assertEquals(1, requests.size());
      assertSame(open, requests.get(0).invoice());
      assertSame(openSchedule, requests.get(0).schedule());
    }

    @Test
    @DisplayName("a debit line pays (isReceipt false) and the chosen method is passed through")
    void debitLineWithChosenMethod() throws Exception {
      FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
      Client methodClient = client(TENANT);
      when(method.getClient()).thenReturn(methodClient);
      when(dal.get(FIN_PaymentMethod.class, "PM-1")).thenReturn(method);

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          new ConversionPaymentContext(account, line("0", "27.87"), oneUsdInvoice("40.91"),
              new ArrayList<>(), "PM-1", false),
          conversion("40.91", null, "27.87"));

      assertNull(response);
      assertFalse(requests.get(0).isReceipt(), "a debit line is a payment");
      assertSame(method, requests.get(0).chosenMethod());
    }

    @Test
    @DisplayName("a zero-precision account currency books whole units")
    void zeroPrecisionAccountCurrency() throws Exception {
      account = account(currency("JPY", 0));

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          ctx(line("15234", "0"), oneUsdInvoice("100.00")), conversion("100", null, null));

      assertNull(response);
      ReconciliationPaymentRequest req = requests.get(0);
      assertEquals("15234", req.accountAmount().toPlainString());
      assertEquals(0, new BigDecimal("152.34").compareTo(req.rate()));
      assertReproduces(req.paymentAmount(), req.rate(), req.accountAmount(), 0);
    }

    @Test
    @DisplayName("a payment without a transaction → 500, and the next invoice is not paid")
    void paymentWithoutTransactionIs500() throws Exception {
      FIN_Payment empty = mock(FIN_Payment.class);
      when(empty.getId()).thenReturn("pay-empty");
      when(empty.getFINFinaccTransactionList()).thenReturn(List.of());
      paymentService.when(() -> ReconciliationPaymentService.registerReconciliationPayment(any()))
          .thenReturn(empty);
      Invoice first = invoice("INV-1", usd);
      schedule("SCH-1", "30.00", first);
      Invoice second = invoice("INV-2", usd);
      schedule("SCH-2", "20.00", second);
      List<String> operationIds = new ArrayList<>();

      NeoResponse response = ReconciliationConversionSupport.payInvoices(
          new ConversionPaymentContext(account, line("33.33", "0"),
              specs(spec("INV-1", "SCH-1"), spec("INV-2", "SCH-2")), operationIds, null, false),
          conversion("50.00", null, "33.33"));

      assertRefused(response, 500, "Payment did not produce a transaction: pay-empty");
      paymentService.verify(
          () -> ReconciliationPaymentService.registerReconciliationPayment(any()), times(1));
      assertTrue(operationIds.isEmpty());
    }

    @Test
    @DisplayName("the invoice's own exchange rate is never read in this mode")
    void invoiceRateNeverResolved() throws Exception {
      try (MockedStatic<PaymentCurrencyConverter> converter =
          mockStatic(PaymentCurrencyConverter.class, CALLS_REAL_METHODS)) {
        NeoResponse response = ReconciliationConversionSupport.payInvoices(
            ctx(line("27.87", "0"), oneUsdInvoice("40.91")), conversion("40.91", null, "27.87"));

        assertNull(response);
        converter.verify(() -> PaymentCurrencyConverter.resolveInvoiceRate(any(), any()),
            never());
      }
      verify(dal, never()).createCriteria(ConversionRateDoc.class);
      assertEquals(1, requests.size());
    }
  }
}
