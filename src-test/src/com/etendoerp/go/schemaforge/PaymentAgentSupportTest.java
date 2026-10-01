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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.service.json.JsonUtils;

/**
 * ETP-5558 — what the invoice payment actions settle for an agent before writing (installment,
 * overpayment, method/account) and what they add to the answer afterwards.
 *
 * <p>The DAL is mocked at its edges only: {@link TenantOwnership#loadOwned} answers from an
 * in-memory map of "owned" entities, so {@link PaymentOwnership} runs for real and an id missing
 * from the map behaves like an unknown or another tenant's id.</p>
 */
@DisplayName("ETP-5558 — PaymentAgentSupport")
class PaymentAgentSupportTest {

  private static final String INVOICE = "inv-1";
  private static final String OTHER_INVOICE = "inv-2";
  private static final String ACCOUNT = "acct-1";
  private static final String METHOD = "method-1";
  private static final String DRAFT = "pay-draft";
  private static final String KEY_SCHEDULE_ID = "scheduleId";

  private final Map<String, Object> owned = new HashMap<>();
  private MockedStatic<TenantOwnership> tenantMock;
  private MockedStatic<PaymentRegistrationService> registrationMock;
  private MockedStatic<PaymentAccountMethodsLoader> loaderMock;
  private MockedStatic<OBDal> dalMock;
  private OBDal dal;
  private Session session;
  private Query<Object[]> invoiceQuery;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    tenantMock = mockStatic(TenantOwnership.class);
    tenantMock.when(() -> TenantOwnership.loadOwned(any(), any()))
        .thenAnswer(inv -> owned.get((String) inv.getArgument(1)));
    // Real helpers (allowProperty, nullToZero, basePaymentData); only the DB lookups are stubbed.
    registrationMock = mockStatic(PaymentRegistrationService.class, Mockito.CALLS_REAL_METHODS);
    loaderMock = mockStatic(PaymentAccountMethodsLoader.class);
    dal = mock(OBDal.class);
    session = mock(Session.class);
    invoiceQuery = mock(Query.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(invoiceQuery);
    when(invoiceQuery.setParameter(anyString(), any())).thenReturn(invoiceQuery);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalMock.close();
    loaderMock.close();
    registrationMock.close();
    tenantMock.close();
  }

  // ─── fixtures ─────────────────────────────────────────────────────────────

  private Invoice invoice(String id, FIN_PaymentSchedule... schedules) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn(id);
    when(invoice.getFINPaymentScheduleList()).thenReturn(new ArrayList<>(List.of(schedules)));
    owned.put(id, invoice);
    return invoice;
  }

  /** An installment of {@code invoiceId}; each detail is pending unless linked with {@link #link}. */
  private FIN_PaymentSchedule schedule(String id, String invoiceId, Date dueDate, String amount,
      FIN_PaymentScheduleDetail... details) {
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    Invoice owner = mock(Invoice.class);
    when(owner.getId()).thenReturn(invoiceId);
    when(schedule.getId()).thenReturn(id);
    when(schedule.getInvoice()).thenReturn(owner);
    when(schedule.getDueDate()).thenReturn(dueDate);
    when(schedule.getAmount()).thenReturn(new BigDecimal(amount));
    when(schedule.getFINPaymentScheduleDetailInvoicePaymentScheduleList())
        .thenReturn(new ArrayList<>(List.of(details)));
    for (FIN_PaymentScheduleDetail psd : details) {
      when(psd.getInvoicePaymentSchedule()).thenReturn(schedule);
    }
    owned.put(id, schedule);
    return schedule;
  }

  private static FIN_PaymentScheduleDetail psd(String amount) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getAmount()).thenReturn(new BigDecimal(amount));
    return psd;
  }

  /** Links {@code psd} to a payment detail, which is what makes it no longer pending. */
  private static FIN_PaymentScheduleDetail link(FIN_PaymentScheduleDetail psd) {
    when(psd.getPaymentDetails()).thenReturn(mock(FIN_PaymentDetail.class));
    return psd;
  }

  /** A draft payment whose details pay {@code paid}. */
  private FIN_Payment draft(String id, FIN_PaymentScheduleDetail... paid) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn(id);
    List<FIN_PaymentDetail> details = new ArrayList<>();
    if (paid.length > 0) {
      FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
      when(detail.getFINPaymentScheduleDetailList()).thenReturn(new ArrayList<>(List.of(paid)));
      details.add(detail);
    }
    when(payment.getFINPaymentDetailList()).thenReturn(details);
    owned.put(id, payment);
    return payment;
  }

  private static Date day(int year, int month, int dayOfMonth) {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(year, month - 1, dayOfMonth);
    return cal.getTime();
  }

  private static JSONObject body(String... keyValues) throws Exception {
    JSONObject body = new JSONObject();
    for (int i = 0; i < keyValues.length; i += 2) {
      body.put(keyValues[i], keyValues[i + 1]);
    }
    return body;
  }

  private static JSONObject error(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error");
  }

  private static BigDecimal decimal(JSONObject json, String key) throws Exception {
    return new BigDecimal(json.get(key).toString());
  }

  // ─── checkRegister: the installment (FR-2) ────────────────────────────────

  @Nested
  @DisplayName("checkRegister — installment resolution")
  class ScheduleResolution {

    @Test
    @DisplayName("one pending installment fills scheduleId and lets the call proceed")
    void onePendingInstallmentIsFilledIn() throws Exception {
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 3, 1), "100", psd("100")));
      JSONObject body = body("actual_payment", "100");

      assertNull(PaymentAgentSupport.checkRegister(INVOICE, body, true));
      assertEquals("s-1", body.getString(KEY_SCHEDULE_ID));
    }

    @Test
    @DisplayName("several pending installments are refused with the list in due-date order")
    void severalPendingInstallmentsAreListedByDueDate() throws Exception {
      Date early = day(2026, 1, 15);
      Date late = day(2026, 6, 30);
      invoice(INVOICE,
          schedule("s-undated", INVOICE, null, "30", psd("30")),
          schedule("s-late", INVOICE, late, "50", psd("50")),
          schedule("s-early", INVOICE, early, "20", psd("20")));
      JSONObject body = body("actual_payment", "10");

      NeoResponse refusal = PaymentAgentSupport.checkRegister(INVOICE, body, true);

      assertEquals(422, refusal.getHttpStatus());
      JSONArray installments = error(refusal).getJSONArray("installments");
      assertEquals(3, installments.length());
      assertEquals("s-early", installments.getJSONObject(0).getString("id"));
      assertEquals("s-late", installments.getJSONObject(1).getString("id"));
      // A null due date sorts last (Comparator.nullsLast), it does not throw.
      assertEquals("s-undated", installments.getJSONObject(2).getString("id"));
      assertEquals(0, new BigDecimal("20")
          .compareTo(decimal(installments.getJSONObject(0), "outstandingAmount")));
      assertEquals(JsonUtils.createDateFormat().format(early),
          installments.getJSONObject(0).getString("dueDate"));
      assertFalse(installments.getJSONObject(2).has("dueDate"));
      assertFalse(body.has(KEY_SCHEDULE_ID), "nothing is guessed");
    }

    @Test
    @DisplayName("no pending installment is refused with MSG_NO_PENDING_PSD")
    void noPendingInstallmentIsRefused() throws Exception {
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 3, 1), "100", link(psd("100"))));

      NeoResponse refusal = PaymentAgentSupport.checkRegister(INVOICE,
          body("actual_payment", "100"), true);

      assertEquals(422, refusal.getHttpStatus());
      assertEquals(PaymentRegistrationService.MSG_NO_PENDING_PSD, error(refusal).getString("message"));
    }

    @Test
    @DisplayName("an installment whose details are all linked to a draft is not pending")
    void installmentFullyLinkedToADraftDoesNotCount() throws Exception {
      invoice(INVOICE,
          schedule("s-linked", INVOICE, day(2026, 1, 1), "40", link(psd("25")), link(psd("15"))),
          schedule("s-open", INVOICE, day(2026, 2, 1), "60", link(psd("10")), psd("50")));
      JSONObject body = body("actual_payment", "50");

      assertNull(PaymentAgentSupport.checkRegister(INVOICE, body, false));
      assertEquals("s-open", body.getString(KEY_SCHEDULE_ID));
    }

    @Test
    @DisplayName("editing a draft takes the installment the draft pays, not the pending one")
    void editTakesTheInstallmentTheDraftPays() throws Exception {
      FIN_PaymentScheduleDetail paidByDraft = link(psd("70"));
      invoice(INVOICE,
          schedule("s-pending", INVOICE, day(2026, 1, 1), "30", psd("30")),
          schedule("s-drafted", INVOICE, day(2026, 2, 1), "70", paidByDraft));
      draft(DRAFT, paidByDraft);
      JSONObject body = body("actual_payment", "70", "paymentId", DRAFT);

      assertNull(PaymentAgentSupport.checkRegister(INVOICE, body, true));
      assertEquals("s-drafted", body.getString(KEY_SCHEDULE_ID));
    }

    @Test
    @DisplayName("editing with an unknown paymentId answers 404, not 'no pending installment'")
    void editWithUnknownPaymentIdIs404() throws Exception {
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 1, 1), "30", link(psd("30"))));

      assertPaymentNotFound(body("actual_payment", "30", "paymentId", "pay-unknown"));
    }

    @Test
    @DisplayName("editing with another invoice's draft answers 404")
    void editWithAnotherInvoicesDraftIs404() throws Exception {
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 1, 1), "30", psd("30")));
      FIN_PaymentScheduleDetail foreign = link(psd("80"));
      schedule("s-foreign", OTHER_INVOICE, day(2026, 1, 1), "80", foreign);
      draft(DRAFT, foreign);

      assertPaymentNotFound(body("actual_payment", "30", "paymentId", DRAFT));
    }

    @Test
    @DisplayName("editing with a draft that pays no installment answers 404")
    void editWithDraftPayingNothingIs404() throws Exception {
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 1, 1), "30", psd("30")));
      draft(DRAFT);

      assertPaymentNotFound(body("actual_payment", "30", "paymentId", DRAFT));
    }

    private void assertPaymentNotFound(JSONObject body) throws Exception {
      NeoResponse refusal = PaymentAgentSupport.checkRegister(INVOICE, body, true);

      assertEquals(404, refusal.getHttpStatus());
      assertEquals(PaymentRegistrationService.MSG_PAYMENT_NOT_FOUND,
          error(refusal).getString("message"));
      assertFalse(body.has(KEY_SCHEDULE_ID));
    }

    @Test
    @DisplayName("an explicit scheduleId is left untouched, even with several pending")
    void explicitScheduleIdIsUntouched() throws Exception {
      invoice(INVOICE,
          schedule("s-1", INVOICE, day(2026, 1, 1), "30", psd("30")),
          schedule("s-2", INVOICE, day(2026, 2, 1), "40", psd("40")));
      JSONObject body = body(KEY_SCHEDULE_ID, "s-2", "actual_payment", "40");

      assertNull(PaymentAgentSupport.checkRegister(INVOICE, body, true));
      assertEquals("s-2", body.getString(KEY_SCHEDULE_ID));
    }

    @Test
    @DisplayName("an unknown or another tenant's invoice is left to the service's own 404")
    void unknownInvoiceProceeds() throws Exception {
      JSONObject body = body("actual_payment", "999999");

      assertNull(PaymentAgentSupport.checkRegister("inv-unknown", body, true));
      assertFalse(body.has(KEY_SCHEDULE_ID));
    }
  }

  // ─── checkRegister: the overpayment (FR-9) ────────────────────────────────

  @Nested
  @DisplayName("checkRegister — overpayment")
  class Overpayment {

    @BeforeEach
    void installment() {
      // Capacity on create: the 100 still pending. The installment's whole amount is 150.
      FIN_PaymentScheduleDetail drafted = link(psd("50"));
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 1, 1), "150", psd("100"), drafted));
      draft(DRAFT, drafted);
    }

    private NeoResponse register(JSONObject body) throws Exception {
      body.put(KEY_SCHEDULE_ID, "s-1");
      return PaymentAgentSupport.checkRegister(INVOICE, body, true);
    }

    @Test
    @DisplayName("funds equal to the capacity pass")
    void fundsEqualToCapacityPass() throws Exception {
      assertNull(register(body("actual_payment", "100")));
    }

    @Test
    @DisplayName("capacity + 0.01 is refused with outstandingAmount, excess and allowedValues")
    void oneCentOverIsRefused() throws Exception {
      NeoResponse refusal = register(body("actual_payment", "100.01"));

      assertNotNull(refusal);
      assertEquals(422, refusal.getHttpStatus());
      JSONObject error = error(refusal);
      assertEquals(0, new BigDecimal("100.00").compareTo(decimal(error, "outstandingAmount")));
      assertEquals(0, new BigDecimal("0.01").compareTo(decimal(error, "excess")));
      JSONArray allowed = error.getJSONArray("allowedValues");
      assertEquals(2, allowed.length());
      assertEquals("leave-credit", allowed.getString(0));
      assertEquals("refund", allowed.getString(1));
    }

    @Test
    @DisplayName("credit sources count towards the funds, like requestedFunding")
    void creditSourcesCount() throws Exception {
      JSONArray within = new JSONArray().put(
          new JSONObject().put("kind", "credit").put("paymentId", "pay-credit").put("use", "40"));
      JSONObject fits = body("actual_payment", "60");
      fits.put("creditSources", within);
      assertNull(register(fits));

      JSONArray over = new JSONArray().put(
          new JSONObject().put("kind", "credit").put("paymentId", "pay-credit").put("use", "41"));
      JSONObject exceeds = body("actual_payment", "60");
      exceeds.put("creditSources", over);
      NeoResponse refusal = register(exceeds);
      assertEquals(422, refusal.getHttpStatus());
      assertEquals(0, new BigDecimal("1.00").compareTo(decimal(error(refusal), "excess")));
    }

    @Test
    @DisplayName("create caps at the pending details")
    void createCapsAtPendingDetails() throws Exception {
      assertEquals(422, register(body("actual_payment", "120")).getHttpStatus());
    }

    @Test
    @DisplayName("edit caps at the installment's whole amount")
    void editCapsAtScheduleAmount() throws Exception {
      assertNull(register(body("actual_payment", "120", "paymentId", DRAFT)));

      NeoResponse refusal = register(body("actual_payment", "150.01", "paymentId", DRAFT));
      assertEquals(422, refusal.getHttpStatus());
      assertEquals(0, new BigDecimal("150.00")
          .compareTo(decimal(error(refusal), "outstandingAmount")));
    }

    @Test
    @DisplayName("an explicit overpaymentAction passes")
    void explicitOverpaymentActionPasses() throws Exception {
      assertNull(register(body("actual_payment", "500", "overpaymentAction", "refund")));
    }

    @Test
    @DisplayName("amounts are rounded to cents HALF_UP before comparing")
    void amountsAreRoundedHalfUp() throws Exception {
      assertNull(register(body("actual_payment", "100.004")));

      NeoResponse refusal = register(body("actual_payment", "100.005"));
      assertNotNull(refusal, "100.005 rounds HALF_UP to 100.01");
      assertEquals(0, new BigDecimal("0.01").compareTo(decimal(error(refusal), "excess")));
    }
  }

  // ─── checkRegister: method ↔ account (FR-5) ───────────────────────────────

  @Nested
  @DisplayName("checkRegister — method and account")
  class MethodAndAccount {

    private FIN_FinancialAccount account;
    private FIN_PaymentMethod method;

    @BeforeEach
    void accountAndMethod() {
      invoice(INVOICE, schedule("s-1", INVOICE, day(2026, 1, 1), "100", psd("100")));
      account = mock(FIN_FinancialAccount.class);
      when(account.getId()).thenReturn(ACCOUNT);
      when(account.getName()).thenReturn("Bank");
      owned.put(ACCOUNT, account);
      method = mock(FIN_PaymentMethod.class);
      when(method.getId()).thenReturn(METHOD);
      owned.put(METHOD, method);

      FIN_PaymentMethod valid = mock(FIN_PaymentMethod.class);
      when(valid.getId()).thenReturn("method-ok");
      when(valid.getName()).thenReturn("Transfer");
      FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
      when(link.getPaymentMethod()).thenReturn(valid);
      loaderMock.when(() -> PaymentAccountMethodsLoader.loadAllowedMethodsByAccount(any(),
          anyString())).thenReturn(Map.of(ACCOUNT, List.of(link)));
    }

    private NeoResponse register(String methodId, String accountId) throws Exception {
      JSONObject body = body(KEY_SCHEDULE_ID, "s-1", "actual_payment", "10",
          "fin_financial_account_id", accountId);
      if (methodId != null) {
        body.put("fin_paymentmethod_id", methodId);
      }
      return PaymentAgentSupport.checkRegister(INVOICE, body, true);
    }

    @Test
    @DisplayName("a method the account accepts passes")
    void acceptedMethodPasses() throws Exception {
      registrationMock.when(() -> PaymentRegistrationService.isMethodAllowed(account, method,
          FinAccPaymentMethod.PROPERTY_PAYINALLOW)).thenReturn(true);

      assertNull(register(METHOD, ACCOUNT));
    }

    @Test
    @DisplayName("a method the account rejects is refused with validMethods")
    void rejectedMethodIsRefused() throws Exception {
      registrationMock.when(() -> PaymentRegistrationService.isMethodAllowed(any(), any(),
          anyString())).thenReturn(false);

      NeoResponse refusal = register(METHOD, ACCOUNT);

      assertEquals(422, refusal.getHttpStatus());
      JSONArray valid = error(refusal).getJSONArray("validMethods");
      assertEquals(1, valid.length());
      assertEquals("method-ok", valid.getJSONObject(0).getString("id"));
      assertEquals("Transfer", valid.getJSONObject(0).getString("name"));
      loaderMock.verify(() -> PaymentAccountMethodsLoader.loadAllowedMethodsByAccount(
          List.of(account), FinAccPaymentMethod.PROPERTY_PAYINALLOW));
    }

    @Test
    @DisplayName("a blank method passes without checking the account")
    void blankMethodPasses() throws Exception {
      assertNull(register("", ACCOUNT));
      registrationMock.verify(() -> PaymentRegistrationService.isMethodAllowed(any(), any(),
          anyString()), never());
    }

    @Test
    @DisplayName("an unknown or another tenant's account is left to the service's 400")
    void foreignAccountProceeds() throws Exception {
      assertNull(register(METHOD, "acct-foreign"));
      registrationMock.verify(() -> PaymentRegistrationService.isMethodAllowed(any(), any(),
          anyString()), never());
    }

    @Test
    @DisplayName("an unknown method is refused with the list")
    void unknownMethodIsRefused() throws Exception {
      NeoResponse refusal = register("method-unknown", ACCOUNT);

      assertEquals(422, refusal.getHttpStatus());
      assertEquals(1, error(refusal).getJSONArray("validMethods").length());
      registrationMock.verify(() -> PaymentRegistrationService.isMethodAllowed(any(), any(),
          anyString()), never());
    }
  }

  // ─── withAgentDefaults (pure JSON) ────────────────────────────────────────

  @Nested
  @DisplayName("withAgentDefaults — invoiceAccounts for an agent")
  class AgentDefaults {

    private JSONObject account(String id, JSONArray methodIds, JSONArray defaultFor)
        throws Exception {
      JSONObject item = new JSONObject().put("id", id)
          .put("defaultPaymentMethod", new JSONObject().put("id", "by-name"));
      if (methodIds != null) {
        item.put("paymentMethodIds", methodIds);
      }
      if (defaultFor != null) {
        item.put("defaultForMethodIds", defaultFor);
      }
      return item;
    }

    private NeoResponse accounts(String invoiceMethodId, JSONObject... items) throws Exception {
      JSONObject body = new JSONObject().put("items", new JSONArray(List.of(items)));
      if (invoiceMethodId != null) {
        body.put("defaultMethodId", invoiceMethodId);
      }
      return new NeoResponse(200, body);
    }

    @Test
    @DisplayName("each account gets the method registerPayment would use on it")
    void perAccountDefaultMethod() throws Exception {
      NeoResponse response = accounts("m-inv",
          account("a-accepts", new JSONArray(List.of("m-x", "m-inv")), new JSONArray(List.of("m-x"))),
          account("a-flagged", new JSONArray(List.of("m-y", "m-z")), new JSONArray(List.of("m-z"))),
          account("a-first", new JSONArray(List.of("m-w", "m-v")), null),
          account("a-none", null, null));

      JSONObject body = PaymentAgentSupport.withAgentDefaults(response).getBody();
      JSONArray items = body.getJSONArray("items");

      assertEquals("m-inv", items.getJSONObject(0).getString("defaultMethodId"));
      assertEquals("m-z", items.getJSONObject(1).getString("defaultMethodId"));
      assertEquals("m-w", items.getJSONObject(2).getString("defaultMethodId"));
      assertFalse(items.getJSONObject(3).has("defaultMethodId"));
      for (int i = 0; i < items.length(); i++) {
        assertFalse(items.getJSONObject(i).has("defaultPaymentMethod"));
      }
      assertFalse(body.has("defaultMethodId"));
      assertEquals("m-inv", body.getString("invoiceMethodId"));
      assertTrue(body.getBoolean("invoiceMethodAccepted"));
    }

    @Test
    @DisplayName("invoiceMethodAccepted is false when no account accepts the invoice's method")
    void invoiceMethodNotAccepted() throws Exception {
      NeoResponse response = accounts("m-inv",
          account("a-1", new JSONArray(List.of("m-x")), null));

      JSONObject body = PaymentAgentSupport.withAgentDefaults(response).getBody();

      assertEquals("m-x", body.getJSONArray("items").getJSONObject(0).getString("defaultMethodId"));
      assertEquals("m-inv", body.getString("invoiceMethodId"));
      assertFalse(body.getBoolean("invoiceMethodAccepted"));
    }

    @Test
    @DisplayName("without an invoice method there is no invoiceMethodId")
    void noInvoiceMethod() throws Exception {
      JSONObject body = PaymentAgentSupport.withAgentDefaults(
          accounts(null, account("a-1", new JSONArray(List.of("m-x")), null))).getBody();

      assertFalse(body.has("invoiceMethodId"));
      assertFalse(body.has("invoiceMethodAccepted"));
    }

    @Test
    @DisplayName("a non-200 answer passes through as the same instance, untouched")
    void nonOkPassesThrough() throws Exception {
      NeoResponse notFound = NeoResponse.error(404, "Invoice not found");
      String before = notFound.getBody().toString();

      assertSame(notFound, PaymentAgentSupport.withAgentDefaults(notFound));
      assertEquals(before, notFound.getBody().toString());
      assertNull(PaymentAgentSupport.withAgentDefaults(null));
    }
  }

  // ─── enrich (FR-11, FR-12) ────────────────────────────────────────────────

  @Nested
  @DisplayName("enrich — the outcome the SPA reads back on its own")
  class Enrich {

    private FIN_Payment payment;

    @BeforeEach
    void paymentAndInvoice() {
      payment = mock(FIN_Payment.class);
      FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
      when(method.getId()).thenReturn(METHOD);
      when(method.getName()).thenReturn("Transfer");
      when(payment.getPaymentMethod()).thenReturn(method);
      when(payment.getGeneratedCredit()).thenReturn(new BigDecimal("5"));
      when(payment.getUsedCredit()).thenReturn(new BigDecimal("2"));
      FIN_PaymentDetail d1 = mock(FIN_PaymentDetail.class);
      when(d1.getWriteoffAmount()).thenReturn(new BigDecimal("1.50"));
      FIN_PaymentDetail d2 = mock(FIN_PaymentDetail.class);
      FIN_PaymentDetail d3 = mock(FIN_PaymentDetail.class);
      when(d3.getWriteoffAmount()).thenReturn(new BigDecimal("0.50"));
      when(payment.getFINPaymentDetailList()).thenReturn(List.of(d1, d2, d3));
      when(dal.get(FIN_Payment.class, "pay-1")).thenReturn(payment);
      when(invoiceQuery.uniqueResult()).thenReturn(
          new Object[] { "FC-1", new BigDecimal("70.00"), new BigDecimal("30.00"), Boolean.FALSE });
    }

    private NeoResponse result() throws Exception {
      return NeoResponse.createdWithData(new JSONObject().put("id", "pay-1"));
    }

    private JSONObject data(NeoResponse response) throws Exception {
      return response.getBody().getJSONObject("response").getJSONObject("data");
    }

    private void assertInvoiceState(JSONObject invoice) throws Exception {
      assertEquals(INVOICE, invoice.getString("id"));
      assertEquals("FC-1", invoice.getString("documentNo"));
      assertEquals(0, new BigDecimal("70.00").compareTo(decimal(invoice, "outstandingAmount")));
      assertEquals(0, new BigDecimal("30.00").compareTo(decimal(invoice, "totalPaid")));
      assertFalse(invoice.getBoolean("paymentComplete"));
    }

    @Test
    @DisplayName("register adds method, credit, write-off, the invoice state and a draft note")
    void registerIsEnriched() throws Exception {
      when(payment.isProcessed()).thenReturn(false);
      JSONObject body = new JSONObject().put("creditSources", new JSONArray().put(
          new JSONObject().put("kind", "credit").put("paymentId", "pay-credit").put("use", "30")));

      JSONObject data = data(PaymentAgentSupport.enrich("registerPayment", result(), INVOICE,
          body, null));

      assertEquals(METHOD, data.getJSONObject("paymentMethod").getString("id"));
      assertEquals("Transfer", data.getJSONObject("paymentMethod").getString("name"));
      assertEquals(0, new BigDecimal("30").compareTo(decimal(data, "creditUsed")));
      assertEquals(0, new BigDecimal("5").compareTo(decimal(data, "creditGenerated")));
      assertEquals(0, new BigDecimal("3").compareTo(decimal(data, "creditAvailable")));
      assertEquals(0, new BigDecimal("2.00").compareTo(decimal(data, "writeoffAmount")));
      assertInvoiceState(data.getJSONObject("invoice"));
      assertTrue(data.has("note"));
    }

    @Test
    @DisplayName("after a refund no credit is left available")
    void refundLeavesNoCredit() throws Exception {
      when(payment.isProcessed()).thenReturn(true);
      when(payment.getUsedCredit()).thenReturn(new BigDecimal("5"));

      JSONObject data = data(PaymentAgentSupport.enrich("registerPayment", result(), INVOICE,
          new JSONObject(), null));

      assertEquals(0, BigDecimal.ZERO.compareTo(decimal(data, "creditAvailable")));
      assertFalse(data.has("note"), "a processed payment carries no draft note");
    }

    @Test
    @DisplayName("confirm adds the same fields minus creditUsed")
    void confirmIsEnrichedWithoutCreditUsed() throws Exception {
      when(payment.isProcessed()).thenReturn(true);

      JSONObject data = data(PaymentAgentSupport.enrich("confirmPayment", result(), INVOICE,
          new JSONObject().put("paymentId", "pay-1"), null));

      assertFalse(data.has("creditUsed"));
      assertTrue(data.has("paymentMethod"));
      assertTrue(data.has("creditGenerated"));
      assertTrue(data.has("creditAvailable"));
      assertTrue(data.has("writeoffAmount"));
      assertInvoiceState(data.getJSONObject("invoice"));
    }

    @Test
    @DisplayName("delete answers 200 with what was deleted and the invoice state")
    void deleteAnswersWithWhatWasRemoved() throws Exception {
      JSONObject deleted = new JSONObject().put("id", DRAFT).put("documentNo", "P-7")
          .put("amount", "40").put("status", "RPAP");

      NeoResponse answer = PaymentAgentSupport.enrich("deletePayment", NeoResponse.noContent(),
          INVOICE, new JSONObject(), deleted);

      assertEquals(200, answer.getHttpStatus());
      JSONObject removed = answer.getBody().getJSONObject("deleted");
      assertEquals(DRAFT, removed.getString("id"));
      assertEquals("P-7", removed.getString("documentNo"));
      assertEquals("40", removed.getString("amount"));
      assertEquals("RPAP", removed.getString("status"));
      assertInvoiceState(answer.getBody().getJSONObject("invoice"));
    }

    @Test
    @DisplayName("an error result passes through unchanged")
    void errorPassesThrough() throws Exception {
      NeoResponse failure = NeoResponse.error(400, "Payment failed");

      assertSame(failure, PaymentAgentSupport.enrich("registerPayment", failure, INVOICE,
          new JSONObject(), null));
      assertSame(failure, PaymentAgentSupport.enrich("deletePayment", failure, INVOICE,
          new JSONObject(), new JSONObject().put("id", DRAFT)));
      assertNull(PaymentAgentSupport.enrich("registerPayment", null, INVOICE, new JSONObject(),
          null));
      verify(session, never()).createQuery(anyString(), eq(Object[].class));
    }

    @Test
    @DisplayName("the invoice state is a scalar HQL read, never session.refresh")
    void invoiceStateIsAScalarQuery() throws Exception {
      when(payment.isProcessed()).thenReturn(true);

      PaymentAgentSupport.enrich("confirmPayment", result(), INVOICE, new JSONObject(), null);

      verify(session).createQuery(contains("from Invoice i where i.id = :id"),
          eq(Object[].class));
      verify(invoiceQuery).setParameter("id", INVOICE);
      verify(session, never()).refresh(any());
    }
  }

  // ─── markNotEnriched ──────────────────────────────────────────────────────

  @Nested
  @DisplayName("markNotEnriched")
  class MarkNotEnriched {

    @Test
    @DisplayName("adds enriched:false to response.data when there is one")
    void marksTheData() throws Exception {
      NeoResponse result = NeoResponse.createdWithData(new JSONObject().put("id", "pay-1"));

      NeoResponse marked = PaymentAgentSupport.markNotEnriched(result);

      assertSame(result, marked);
      assertFalse(marked.getBody().getJSONObject("response").getJSONObject("data")
          .getBoolean("enriched"));
      assertFalse(marked.getBody().has("enriched"));
    }

    @Test
    @DisplayName("adds enriched:false to the top level otherwise")
    void marksTheTopLevel() throws Exception {
      NeoResponse result = NeoResponse.ok(new JSONObject().put("deleted", true));

      assertFalse(PaymentAgentSupport.markNotEnriched(result).getBody().getBoolean("enriched"));
    }

    @Test
    @DisplayName("a 204 with no body passes through unchanged")
    void noBodyPassesThrough() {
      NeoResponse noContent = NeoResponse.noContent();

      assertSame(noContent, PaymentAgentSupport.markNotEnriched(noContent));
      assertNull(noContent.getBody());
      assertNull(PaymentAgentSupport.markNotEnriched(null));
    }
  }
}
