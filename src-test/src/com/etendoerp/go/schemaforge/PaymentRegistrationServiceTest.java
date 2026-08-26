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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_Payment_Credit;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

/**
 * Unit tests for {@link PaymentRegistrationService}.
 *
 * <p>Covers the three public static entry points:
 * <ul>
 *   <li>{@code doRegisterPayment} - validation guards (null invoice, null schedule,
 *       invalid amount, invalid date, null account, currency mismatch, empty PSDs,
 *       null payment method)</li>
 *   <li>{@code handleListAccounts} - blank invoiceId, null invoice, accounts with
 *       and without valid payment methods</li>
 *   <li>{@code handleListPayments} - blank invoiceId, normal results, exception path</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentRegistrationServiceTest {

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;
  @Mock
  private Session session;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obDal.getSession()).thenReturn(session);

    // paymentListItem (exercised by every handleListPayments test with a non-empty result)
    // calls PisPaymentService.linkedPisPayment for the "viaPis" badge — stub it here once
    // so every test gets a real, non-null OBCriteria instead of Mockito's default null.
    OBCriteria<PisPayment> pisPaymentCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(PisPayment.class)).thenReturn(pisPaymentCriteria);
    when(pisPaymentCriteria.add(any(Criterion.class))).thenReturn(pisPaymentCriteria);
    when(pisPaymentCriteria.setMaxResults(anyInt())).thenReturn(pisPaymentCriteria);
    when(pisPaymentCriteria.uniqueResult()).thenReturn(null);

    // paymentListItem also calls creditSourcesUsedByPayment for every non-processed row, which
    // queries FIN_Payment_Credit — stub it here once so every test gets a real, non-null
    // OBCriteria (with an empty result, i.e. "consumes no credit") instead of Mockito's default
    // null, same reasoning as the PisPayment stub above.
    OBCriteria<FIN_Payment_Credit> creditCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_Payment_Credit.class)).thenReturn(creditCriteria);
    when(creditCriteria.add(any(Criterion.class))).thenReturn(creditCriteria);
    when(creditCriteria.list()).thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ========================================================================
  // doRegisterPayment tests
  // ========================================================================

  /**
   * Verifies that a null invoice returns HTTP 404.
   */
  @Test
  void testDoRegisterPaymentNullInvoiceReturns404() throws Exception {
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(null);

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(404, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Invoice not found"));
  }

  /**
   * Verifies that a null payment schedule returns HTTP 404.
   */
  @Test
  void testDoRegisterPaymentNullScheduleReturns404() throws Exception {
    Invoice invoice = mock(Invoice.class);
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(null);

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(404, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Payment schedule not found"));
  }

  /**
   * Verifies that an invalid (non-numeric) amount returns HTTP 400.
   */
  @Test
  void testDoRegisterPaymentInvalidAmountReturns400() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "not-a-number", "2026-01-15", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Invalid amount format"));
  }

  /**
   * Verifies that an invalid date format returns HTTP 400.
   */
  @Test
  void testDoRegisterPaymentInvalidDateReturns400() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "not-a-date", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Invalid date format"));
  }

  /**
   * Verifies that a null financial account returns HTTP 400.
   */
  @Test
  void testDoRegisterPaymentNullAccountReturns400() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(null);

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("Financial account not found"));
  }

  /**
   * Verifies that a currency mismatch between invoice and account returns HTTP 400.
   */
  @Test
  void testDoRegisterPaymentCurrencyMismatchReturns400() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    Currency invoiceCurrency = mock(Currency.class);
    when(invoiceCurrency.getId()).thenReturn("USD-ID");
    when(invoiceCurrency.getISOCode()).thenReturn("USD");

    Currency accountCurrency = mock(Currency.class);
    when(accountCurrency.getId()).thenReturn("EUR-ID");
    when(accountCurrency.getISOCode()).thenReturn("EUR");

    when(invoice.getCurrency()).thenReturn(invoiceCurrency);
    when(account.getCurrency()).thenReturn(accountCurrency);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(account);

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message.contains("EUR"));
    assertTrue(message.contains("USD"));
    assertTrue(message.contains("does not match"));
  }

  /**
   * Verifies that when no pending PSDs exist for the schedule, HTTP 400 is returned.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testDoRegisterPaymentEmptyPSDsReturns400() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    Currency sameCurrency = mock(Currency.class);
    when(sameCurrency.getId()).thenReturn("USD-ID");
    when(invoice.getCurrency()).thenReturn(sameCurrency);
    when(account.getCurrency()).thenReturn(sameCurrency);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(account);

    // Mock the OBCriteria for findPendingPSDs - returns empty list
    OBCriteria<FIN_PaymentScheduleDetail> psdCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(psdCriteria);
    when(psdCriteria.add(any(Criterion.class))).thenReturn(psdCriteria);
    when(psdCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(psdCriteria);
    when(psdCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("No pending payment schedule details"));
  }

  /**
   * Verifies that when no payment method is resolved for the account, HTTP 400 is returned.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testDoRegisterPaymentNullPaymentMethodReturns400() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    Organization org = mock(Organization.class);

    Currency sameCurrency = mock(Currency.class);
    when(sameCurrency.getId()).thenReturn("USD-ID");
    when(invoice.getCurrency()).thenReturn(sameCurrency);
    when(account.getCurrency()).thenReturn(sameCurrency);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.getPaymentMethod()).thenReturn(null);
    when(bp.getPaymentMethod()).thenReturn(null);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(account);

    // findPendingPSDs returns one PSD
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getAmount()).thenReturn(new BigDecimal("100.00"));

    OBCriteria<FIN_PaymentScheduleDetail> psdCriteria = mock(OBCriteria.class);
    // resolvePaymentMethod criteria (invoice method check + fallback) - both return empty
    OBCriteria<FinAccPaymentMethod> methodCriteria2 = mock(OBCriteria.class);

    // createCriteria calls: 1st = findPendingPSDs, 2nd = resolvePaymentMethod fallback
    // (no invoice method check since both invoice and BP payment methods are null)
    when(obDal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(psdCriteria);
    when(psdCriteria.add(any(Criterion.class))).thenReturn(psdCriteria);
    when(psdCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(psdCriteria);
    when(psdCriteria.list()).thenReturn(Collections.singletonList(psd));

    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCriteria2);
    when(methodCriteria2.add(any(Criterion.class))).thenReturn(methodCriteria2);
    when(methodCriteria2.setMaxResults(1)).thenReturn(methodCriteria2);
    when(methodCriteria2.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("No payment method configured"));
  }

  /**
   * Verifies that matching currencies (same ID) pass the currency check and proceed
   * to the next validation (PSDs).
   */
  @Test
  @SuppressWarnings("unchecked")
  void testDoRegisterPaymentSameCurrencyPassesCurrencyCheck() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    Currency sameCurrency = mock(Currency.class);
    when(sameCurrency.getId()).thenReturn("SAME-ID");
    when(invoice.getCurrency()).thenReturn(sameCurrency);
    when(account.getCurrency()).thenReturn(sameCurrency);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(account);

    // Empty PSDs so we stop at that check (after currency passes)
    OBCriteria<FIN_PaymentScheduleDetail> psdCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(psdCriteria);
    when(psdCriteria.add(any(Criterion.class))).thenReturn(psdCriteria);
    when(psdCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(psdCriteria);
    when(psdCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    // Should fail on empty PSDs, not on currency mismatch
    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("No pending payment schedule details"));
  }

  /**
   * Verifies that null invoice currency bypasses the currency mismatch check.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testDoRegisterPaymentNullInvoiceCurrencySkipsCurrencyCheck() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    Currency accountCurrency = mock(Currency.class);
    when(accountCurrency.getId()).thenReturn("EUR-ID");
    when(invoice.getCurrency()).thenReturn(null);
    when(account.getCurrency()).thenReturn(accountCurrency);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(account);

    // Empty PSDs to stop at that validation
    OBCriteria<FIN_PaymentScheduleDetail> psdCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(psdCriteria);
    when(psdCriteria.add(any(Criterion.class))).thenReturn(psdCriteria);
    when(psdCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(psdCriteria);
    when(psdCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    // Should fail on empty PSDs, NOT on currency mismatch
    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("No pending payment schedule details"));
  }

  /**
   * Verifies that null account currency bypasses the currency mismatch check.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testDoRegisterPaymentNullAccountCurrencySkipsCurrencyCheck() throws Exception {
    Invoice invoice = mock(Invoice.class);
    FIN_PaymentSchedule schedule = mock(FIN_PaymentSchedule.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    Currency invoiceCurrency = mock(Currency.class);
    when(invoiceCurrency.getId()).thenReturn("USD-ID");
    when(invoice.getCurrency()).thenReturn(invoiceCurrency);
    when(account.getCurrency()).thenReturn(null);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(obDal.get(FIN_PaymentSchedule.class, "sched-1")).thenReturn(schedule);
    when(obDal.get(FIN_FinancialAccount.class, "acc-1")).thenReturn(account);

    OBCriteria<FIN_PaymentScheduleDetail> psdCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(psdCriteria);
    when(psdCriteria.add(any(Criterion.class))).thenReturn(psdCriteria);
    when(psdCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(psdCriteria);
    when(psdCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.doRegisterPayment(
        "inv-1", "sched-1", "100.00", "2026-01-15", "acc-1", true);

    assertEquals(400, response.getHttpStatus());
    assertTrue(response.getBody().getJSONObject("error").getString("message")
        .contains("No pending payment schedule details"));
  }

  // ========================================================================
  // resolvePaymentMethod tests (direct calls - package-visible static method)
  //
  // These exercise PaymentRegistrationService#resolvePaymentMethod directly instead of going
  // through doRegisterPayment, since the method is package-visible and this test class lives in
  // the same package. This keeps the tests focused on the priority logic itself without having
  // to also stub the rest of the doRegisterPayment flow (period check, draft payment creation).
  // ========================================================================

  /**
   * Verifies that the account-fallback branch orders {@code FinAccPaymentMethod} by
   * {@link FinAccPaymentMethod#PROPERTY_DEFAULT} descending before taking the first result, so
   * the account's own default-for-direction method wins over an arbitrary one (mirrors Classic's
   * account-level fallback in {@code TransactionAddPaymentDefaultValues}).
   *
   * <p><b>Limitation:</b> this only proves the ORDER BY clause is requested with the correct
   * property and direction (via {@code verify(...)}). It cannot prove that a real database
   * actually returns the default-flagged row first - that behavioral guarantee requires a live-DB
   * integration test (e.g. OBBaseTest), which is out of scope for this Mockito-based unit test.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testResolvePaymentMethodFallbackOrdersByAccountDefaultFlag() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Invoice invoice = mock(Invoice.class);

    // No invoice/BP payment method at all, so resolveInvoiceMethod returns null and the
    // fallback branch is reached directly (no isMethodAllowed call in between).
    when(invoice.getPaymentMethod()).thenReturn(null);
    when(invoice.getBusinessPartner()).thenReturn(null);

    FIN_PaymentMethod defaultMethod = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod defaultFapm = mock(FinAccPaymentMethod.class);
    when(defaultFapm.getPaymentMethod()).thenReturn(defaultMethod);

    OBCriteria<FinAccPaymentMethod> fallbackCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(fallbackCriteria);
    when(fallbackCriteria.add(any(Criterion.class))).thenReturn(fallbackCriteria);
    when(fallbackCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(fallbackCriteria);
    when(fallbackCriteria.setMaxResults(1)).thenReturn(fallbackCriteria);
    when(fallbackCriteria.list()).thenReturn(Collections.singletonList(defaultFapm));

    FIN_PaymentMethod result =
        PaymentRegistrationService.resolvePaymentMethod(account, invoice, true);

    assertEquals(defaultMethod, result);
    verify(fallbackCriteria).addOrderBy(FinAccPaymentMethod.PROPERTY_DEFAULT, false);
  }

  /**
   * Regression test for the bug reproduced live in Etendo Classic: the business partner (or
   * invoice) has its own payment method configured (e.g. "Efectivo"/Cash), but that method is NOT
   * configured on the reconciliation account (which only allows Recibo/Transferencia/Tarjeta).
   *
   * <p>Classic's {@code TransactionAddPaymentDefaultValues.getDefaultPaymentMethod} validates the
   * BP's method against the BP's OWN linked financial account instead of the account actually
   * being reconciled, so it still defaults the popup to the BP's method - and creating the payment
   * then fails with "Selected payment method doesn't exist". This test proves the fix: the
   * invoice/BP method is validated against THIS account via {@link
   * PaymentRegistrationService#isMethodAllowed}, and when it is not allowed, resolution falls
   * through to the account-fallback branch instead of returning the disallowed method.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testResolvePaymentMethodInvoiceMethodNotAllowedFallsBackToAccountDefault() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Invoice invoice = mock(Invoice.class);

    FIN_PaymentMethod cashMethod = mock(FIN_PaymentMethod.class);
    when(invoice.getPaymentMethod()).thenReturn(cashMethod);

    // isMethodAllowed(account, cashMethod, ...) criteria: Cash is NOT configured for this
    // account, so the criteria returns no rows.
    OBCriteria<FinAccPaymentMethod> allowedCheckCriteria = mock(OBCriteria.class);
    // Fallback criteria: the account's own default-for-direction method (Transferencia).
    OBCriteria<FinAccPaymentMethod> fallbackCriteria = mock(OBCriteria.class);

    when(obDal.createCriteria(FinAccPaymentMethod.class))
        .thenReturn(allowedCheckCriteria)
        .thenReturn(fallbackCriteria);

    when(allowedCheckCriteria.add(any(Criterion.class))).thenReturn(allowedCheckCriteria);
    when(allowedCheckCriteria.setMaxResults(1)).thenReturn(allowedCheckCriteria);
    when(allowedCheckCriteria.list()).thenReturn(Collections.emptyList());

    FIN_PaymentMethod transferMethod = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod transferFapm = mock(FinAccPaymentMethod.class);
    when(transferFapm.getPaymentMethod()).thenReturn(transferMethod);

    when(fallbackCriteria.add(any(Criterion.class))).thenReturn(fallbackCriteria);
    when(fallbackCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(fallbackCriteria);
    when(fallbackCriteria.setMaxResults(1)).thenReturn(fallbackCriteria);
    when(fallbackCriteria.list()).thenReturn(Collections.singletonList(transferFapm));

    FIN_PaymentMethod result =
        PaymentRegistrationService.resolvePaymentMethod(account, invoice, true);

    assertEquals(transferMethod, result,
        "the disallowed invoice/BP method must NOT be returned; resolution must fall through "
            + "to the account-fallback branch instead");
    assertTrue(result != cashMethod,
        "the resolved method must not be the invoice's own method, since it isn't allowed "
            + "for this account");
    verify(allowedCheckCriteria).list();
    verify(fallbackCriteria).addOrderBy(FinAccPaymentMethod.PROPERTY_DEFAULT, false);
  }

  // ========================================================================
  // handleListAccounts tests
  // ========================================================================

  /**
   * Verifies that a blank invoice ID returns HTTP 400.
   */
  @Test
  void testHandleListAccountsBlankInvoiceIdReturns400() {
    NeoContext context = NeoContext.builder()
        .recordId("")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a null record ID returns HTTP 400.
   */
  @Test
  void testHandleListAccountsNullInvoiceIdReturns400() {
    NeoContext context = NeoContext.builder()
        .recordId(null)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a null invoice returns HTTP 404.
   */
  @Test
  void testHandleListAccountsNullInvoiceReturns404() {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(null);

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(404, response.getHttpStatus());
  }

  /**
   * Verifies that accounts with valid payment methods are included in the response,
   * and accounts without valid methods are excluded.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsFiltersAccountsByPaymentMethod() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);

    Set<String> naturalTree = new HashSet<>(Arrays.asList("org-1", "org-parent"));
    when(osp.getNaturalTree("org-1")).thenReturn(naturalTree);

    // Account with valid payment method
    FIN_FinancialAccount validAccount = mock(FIN_FinancialAccount.class);
    when(validAccount.getId()).thenReturn("acc-valid");
    when(validAccount.getName()).thenReturn("Valid Account");
    Currency accCurrency = mock(Currency.class);
    when(accCurrency.getISOCode()).thenReturn("USD");
    when(accCurrency.getId()).thenReturn("USD-ID");
    when(validAccount.getCurrency()).thenReturn(accCurrency);

    // Account without valid payment method
    FIN_FinancialAccount invalidAccount = mock(FIN_FinancialAccount.class);
    when(invalidAccount.getId()).thenReturn("acc-invalid");
    when(invalidAccount.getName()).thenReturn("Invalid Account");

    // Main accounts criteria
    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Arrays.asList(validAccount, invalidAccount));

    // Payment method criteria - first call returns a method, second call returns empty
    OBCriteria<FinAccPaymentMethod> methodCritValid = mock(OBCriteria.class);
    OBCriteria<FinAccPaymentMethod> methodCritInvalid = mock(OBCriteria.class);

    FinAccPaymentMethod finAccMethod = mock(FinAccPaymentMethod.class);
    FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);
    when(paymentMethod.getId()).thenReturn("pm-wire");
    when(paymentMethod.getName()).thenReturn("Wire Transfer");
    when(finAccMethod.getPaymentMethod()).thenReturn(paymentMethod);

    when(obDal.createCriteria(FinAccPaymentMethod.class))
        .thenReturn(methodCritValid)
        .thenReturn(methodCritInvalid);

    when(methodCritValid.add(any(Criterion.class))).thenReturn(methodCritValid);
    when(methodCritValid.list()).thenReturn(Collections.singletonList(finAccMethod));

    when(methodCritInvalid.add(any(Criterion.class))).thenReturn(methodCritInvalid);
    when(methodCritInvalid.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(1, body.getInt("totalCount"));
    assertEquals(1, body.getJSONArray("items").length());

    JSONObject item = body.getJSONArray("items").getJSONObject(0);
    assertEquals("acc-valid", item.getString("id"));
    assertEquals("Valid Account", item.getString("label"));
    assertEquals("USD", item.getString("currency"));
    assertEquals("Wire Transfer", item.getString("defaultPaymentMethod"));
    assertEquals(1, item.getJSONArray("paymentMethodIds").length());
    assertEquals("pm-wire", item.getJSONArray("paymentMethodIds").getString(0));
    assertTrue(!body.has("defaultMethodId"),
        "defaultMethodId should be absent when invoice and BP have no payment method");
  }

  /**
   * Verifies that an empty natural tree still executes the query successfully.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsEmptyNaturalTreeReturnsEmptyItems() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("totalCount"));
  }

  /**
   * Verifies that an unexpected exception in handleListAccounts returns HTTP 500.
   */
  @Test
  void testHandleListAccountsExceptionReturns500() {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    obDalMock.when(OBDal::getInstance).thenThrow(new RuntimeException("DB down"));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(500, response.getHttpStatus());
  }

  /**
   * Verifies that accounts with null currency omit currency fields but still appear
   * in the result.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsAccountWithNullCurrencyOmitsCurrencyFields() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(new HashSet<>(Collections.singleton("org-1")));

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("acc-1");
    when(account.getName()).thenReturn("Cash Account");
    when(account.getCurrency()).thenReturn(null);

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.singletonList(account));

    FinAccPaymentMethod finAccMethod = mock(FinAccPaymentMethod.class);
    FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);
    when(paymentMethod.getId()).thenReturn("pm-cash");
    when(paymentMethod.getName()).thenReturn("Cash");
    when(finAccMethod.getPaymentMethod()).thenReturn(paymentMethod);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Collections.singletonList(finAccMethod));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);
    assertEquals("acc-1", item.getString("id"));
    assertEquals("Cash Account", item.getString("label"));
    assertTrue(!item.has("currency"), "currency field should be absent when account currency is null");
    assertEquals(1, item.getJSONArray("paymentMethodIds").length());
    assertEquals("pm-cash", item.getJSONArray("paymentMethodIds").getString(0));
  }

  /**
   * Verifies that an account with two or more {@code FinAccPaymentMethod} rows for the
   * direction surfaces every matching method id in {@code paymentMethodIds}, not just the
   * first one used for {@code defaultPaymentMethod}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsEmitsAllPaymentMethodIds() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(new HashSet<>(Collections.singleton("org-1")));

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("acc-multi");
    when(account.getName()).thenReturn("Multi Method Account");
    when(account.getCurrency()).thenReturn(null);

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.singletonList(account));

    FIN_PaymentMethod methodA = mock(FIN_PaymentMethod.class);
    when(methodA.getId()).thenReturn("pm-A");
    when(methodA.getName()).thenReturn("Wire Transfer");
    FIN_PaymentMethod methodB = mock(FIN_PaymentMethod.class);
    when(methodB.getId()).thenReturn("pm-B");

    FinAccPaymentMethod fapmA = mock(FinAccPaymentMethod.class);
    when(fapmA.getPaymentMethod()).thenReturn(methodA);
    FinAccPaymentMethod fapmB = mock(FinAccPaymentMethod.class);
    when(fapmB.getPaymentMethod()).thenReturn(methodB);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Arrays.asList(fapmA, fapmB));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);
    JSONArray methodIds = item.getJSONArray("paymentMethodIds");
    assertEquals(2, methodIds.length());
    assertEquals("pm-A", methodIds.getString(0));
    assertEquals("pm-B", methodIds.getString(1));
    // defaultPaymentMethod still comes from the first matching row, for backward compat.
    assertEquals("Wire Transfer", item.getString("defaultPaymentMethod"));
  }

  /**
   * Verifies that the top-level {@code defaultMethodId} mirrors the invoice's own
   * payment method id when set directly on the invoice.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsDefaultMethodIdFromInvoice() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    FIN_PaymentMethod invoiceMethod = mock(FIN_PaymentMethod.class);
    when(invoiceMethod.getId()).thenReturn("pm-invoice");

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getPaymentMethod()).thenReturn(invoiceMethod);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertEquals("pm-invoice", response.getBody().getString("defaultMethodId"));
  }

  /**
   * Verifies that {@code defaultMethodId} falls back to the business partner's payment
   * method when the invoice's own {@code getPaymentMethod()} is null.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsDefaultMethodIdFallsBackToBusinessPartner() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    FIN_PaymentMethod bpMethod = mock(FIN_PaymentMethod.class);
    when(bpMethod.getId()).thenReturn("pm-bp");

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getPaymentMethod()).thenReturn(null);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(bp.getPaymentMethod()).thenReturn(bpMethod);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertEquals("pm-bp", response.getBody().getString("defaultMethodId"));
  }

  /**
   * Verifies that {@code defaultMethodId} is entirely absent from the response when
   * neither the invoice nor its business partner has a payment method.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsDefaultMethodIdAbsentWhenNoMethodResolved() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    BusinessPartner bp = mock(BusinessPartner.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getPaymentMethod()).thenReturn(null);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(bp.getPaymentMethod()).thenReturn(null);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertTrue(!response.getBody().has("defaultMethodId"),
        "defaultMethodId must be absent when neither invoice nor BP has a payment method");
  }

  /**
   * Verifies that an account whose currency differs from the invoice's currency is now LISTED
   * (multi-currency support): the two-step modal supplies a conversion rate, so foreign-currency
   * accounts must remain selectable. The account still carries its {@code currency}/{@code
   * currencyId} fields so the UI can decide when to show the conversion fields.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsIncludesAccountWithDifferentCurrency() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    Currency invoiceCurrency = mock(Currency.class);
    when(invoiceCurrency.getId()).thenReturn("USD-ID");

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getCurrency()).thenReturn(invoiceCurrency);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(new HashSet<>(Collections.singleton("org-1")));

    // Account with a different currency (EUR) than the invoice (USD) — must now be included.
    FIN_FinancialAccount foreignAccount = mock(FIN_FinancialAccount.class);
    when(foreignAccount.getId()).thenReturn("acc-eur");
    when(foreignAccount.getName()).thenReturn("EUR Account");
    Currency accCurrency = mock(Currency.class);
    when(accCurrency.getId()).thenReturn("EUR-ID");
    when(accCurrency.getISOCode()).thenReturn("EUR");
    when(foreignAccount.getCurrency()).thenReturn(accCurrency);

    // Account with a null currency — also included.
    FIN_FinancialAccount nullCurrencyAccount = mock(FIN_FinancialAccount.class);
    when(nullCurrencyAccount.getId()).thenReturn("acc-null-currency");
    when(nullCurrencyAccount.getName()).thenReturn("No Currency Account");
    when(nullCurrencyAccount.getCurrency()).thenReturn(null);

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list())
        .thenReturn(Arrays.asList(foreignAccount, nullCurrencyAccount));

    // Both accounts reach the method lookup now (no early currency return).
    FinAccPaymentMethod finAccMethod = mock(FinAccPaymentMethod.class);
    FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);
    when(paymentMethod.getId()).thenReturn("pm-cash");
    when(paymentMethod.getName()).thenReturn("Cash");
    when(finAccMethod.getPaymentMethod()).thenReturn(paymentMethod);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Collections.singletonList(finAccMethod));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(2, body.getInt("totalCount"),
        "foreign-currency account must now be listed alongside the null-currency one");
    JSONArray items = body.getJSONArray("items");
    assertEquals(2, items.length());
    JSONObject foreignItem = items.getJSONObject(0);
    assertEquals("acc-eur", foreignItem.getString("id"));
    assertEquals("EUR", foreignItem.getString("currency"),
        "the foreign account must still expose its ISO currency for the UI conversion fields");
    assertEquals("EUR-ID", foreignItem.getString("currencyId"));
  }

  /**
   * Verifies that {@code defaultForMethodIds} contains only the payment method id(s)
   * whose {@code FinAccPaymentMethod.isDefault()} flag is {@code true}, while
   * {@code paymentMethodIds} still contains every configured method id.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsDefaultForMethodIdsReflectsIsDefaultFlag() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(new HashSet<>(Collections.singleton("org-1")));

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("acc-multi");
    when(account.getName()).thenReturn("Multi Method Account");
    when(account.getCurrency()).thenReturn(null);

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.singletonList(account));

    FIN_PaymentMethod methodA = mock(FIN_PaymentMethod.class);
    when(methodA.getId()).thenReturn("pm-A");
    when(methodA.getName()).thenReturn("Wire Transfer");
    FIN_PaymentMethod methodB = mock(FIN_PaymentMethod.class);
    when(methodB.getId()).thenReturn("pm-B");

    FinAccPaymentMethod fapmA = mock(FinAccPaymentMethod.class);
    when(fapmA.getPaymentMethod()).thenReturn(methodA);
    when(fapmA.isDefault()).thenReturn(Boolean.TRUE);
    FinAccPaymentMethod fapmB = mock(FinAccPaymentMethod.class);
    when(fapmB.getPaymentMethod()).thenReturn(methodB);
    when(fapmB.isDefault()).thenReturn(Boolean.FALSE);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Arrays.asList(fapmA, fapmB));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);

    JSONArray methodIds = item.getJSONArray("paymentMethodIds");
    assertEquals(2, methodIds.length());
    assertEquals("pm-A", methodIds.getString(0));
    assertEquals("pm-B", methodIds.getString(1));

    JSONArray defaultForMethodIds = item.getJSONArray("defaultForMethodIds");
    assertEquals(1, defaultForMethodIds.length());
    assertEquals("pm-A", defaultForMethodIds.getString(0));
  }

  /**
   * Verifies that {@code defaultForMethodIds} is present but empty when none of the
   * account's {@code FinAccPaymentMethod} rows are flagged as default (covers both an
   * explicit {@code false} and an unstubbed/{@code null} {@code isDefault()}).
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsDefaultForMethodIdsEmptyWhenNoRowIsDefault() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(new HashSet<>(Collections.singleton("org-1")));

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("acc-none-default");
    when(account.getName()).thenReturn("No Default Account");
    when(account.getCurrency()).thenReturn(null);

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.singletonList(account));

    FIN_PaymentMethod methodA = mock(FIN_PaymentMethod.class);
    when(methodA.getId()).thenReturn("pm-A");
    when(methodA.getName()).thenReturn("Wire Transfer");
    FIN_PaymentMethod methodB = mock(FIN_PaymentMethod.class);
    when(methodB.getId()).thenReturn("pm-B");

    // fapmA explicitly flagged false, fapmB left unstubbed (isDefault() returns null under
    // LENIENT strictness) — both must be excluded from defaultForMethodIds.
    FinAccPaymentMethod fapmA = mock(FinAccPaymentMethod.class);
    when(fapmA.getPaymentMethod()).thenReturn(methodA);
    when(fapmA.isDefault()).thenReturn(Boolean.FALSE);
    FinAccPaymentMethod fapmB = mock(FinAccPaymentMethod.class);
    when(fapmB.getPaymentMethod()).thenReturn(methodB);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Arrays.asList(fapmA, fapmB));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);

    assertTrue(item.has("defaultForMethodIds"),
        "defaultForMethodIds must be present even when empty");
    JSONArray defaultForMethodIds = item.getJSONArray("defaultForMethodIds");
    assertEquals(0, defaultForMethodIds.length());
  }

  /**
   * Verifies that {@code bpPreferredAccountId} for a sales invoice (receipt=true) is
   * sourced from {@code businessPartner.getAccount()}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsBpPreferredAccountIdFromAccountForReceipt() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    FIN_FinancialAccount bpAccount = mock(FIN_FinancialAccount.class);
    when(bpAccount.getId()).thenReturn("acc-bp-preferred");

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(bp.getAccount()).thenReturn(bpAccount);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertEquals("acc-bp-preferred", response.getBody().getString("bpPreferredAccountId"));
  }

  /**
   * Verifies that {@code bpPreferredAccountId} for a purchase invoice (receipt=false) is
   * sourced from {@code businessPartner.getPOFinancialAccount()} instead of
   * {@code getAccount()}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsBpPreferredAccountIdFromPOAccountForPayment() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    FIN_FinancialAccount bpPOAccount = mock(FIN_FinancialAccount.class);
    when(bpPOAccount.getId()).thenReturn("acc-bp-po-preferred");

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(bp.getPOFinancialAccount()).thenReturn(bpPOAccount);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, false);

    assertEquals(200, response.getHttpStatus());
    assertEquals("acc-bp-po-preferred", response.getBody().getString("bpPreferredAccountId"));
  }

  /**
   * Verifies that {@code bpPreferredAccountId} is entirely absent from the response when
   * the invoice has no business partner.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsBpPreferredAccountIdAbsentWhenNoBusinessPartner() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getBusinessPartner()).thenReturn(null);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertTrue(!response.getBody().has("bpPreferredAccountId"),
        "bpPreferredAccountId must be absent when the invoice has no business partner");
  }

  /**
   * Verifies that {@code bpPreferredAccountId} is entirely absent from the response when
   * the business partner exists but its preferred account for the direction is null.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListAccountsBpPreferredAccountIdAbsentWhenBpAccountIsNull() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    BusinessPartner bp = mock(BusinessPartner.class);

    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(bp.getAccount()).thenReturn(null);
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(Collections.emptySet());

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    assertTrue(!response.getBody().has("bpPreferredAccountId"),
        "bpPreferredAccountId must be absent when the business partner's preferred account is null");
  }

  // ========================================================================
  // handleListPayments tests
  // ========================================================================

  /**
   * Verifies that a blank invoice ID returns HTTP 400.
   */
  @Test
  void testHandleListPaymentsBlankInvoiceIdReturns400() {
    NeoContext context = NeoContext.builder()
        .recordId("  ")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that a null invoice ID returns HTTP 400.
   */
  @Test
  void testHandleListPaymentsNullInvoiceIdReturns400() {
    NeoContext context = NeoContext.builder()
        .recordId(null)
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(400, response.getHttpStatus());
  }

  /**
   * Verifies that handleListPayments returns payment data for a valid invoice.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsNormalResultsReturns200WithPayments() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn("pay-1");
    when(payment.getDocumentNo()).thenReturn("PAY/001");
    when(payment.getAmount()).thenReturn(new BigDecimal("250.00"));
    when(payment.getPaymentDate()).thenReturn(null);
    when(payment.getStatus()).thenReturn("PPM");
    when(payment.isReceipt()).thenReturn(true);
    when(payment.getAccount()).thenReturn(null);
    when(payment.getPaymentMethod()).thenReturn(null);

    List<FIN_Payment> payments = Collections.singletonList(payment);

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(payments);

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    JSONObject respObj = body.getJSONObject("response");
    assertEquals(1, respObj.getInt("count"));

    JSONObject payItem = respObj.getJSONArray("data").getJSONObject(0);
    assertEquals("pay-1", payItem.getString("id"));
    assertEquals("PAY/001", payItem.getString("documentNo"));
    assertEquals("PPM", payItem.getString("status"));
    assertTrue(payItem.getBoolean("receipt"));
  }

  /**
   * Verifies that handleListPayments includes account and payment method details
   * when they are non-null.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsWithAccountAndMethodIncludesDetails() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    Currency currency = mock(Currency.class);

    when(payment.getId()).thenReturn("pay-2");
    when(payment.getDocumentNo()).thenReturn("PAY/002");
    when(payment.getAmount()).thenReturn(new BigDecimal("500.00"));
    when(payment.getPaymentDate()).thenReturn(new java.util.Date());
    when(payment.getStatus()).thenReturn("RPR");
    when(payment.isReceipt()).thenReturn(false);
    when(payment.getAccount()).thenReturn(account);
    when(payment.getPaymentMethod()).thenReturn(method);

    when(account.getId()).thenReturn("acc-1");
    when(account.getName()).thenReturn("Bank EUR");
    when(account.getCurrency()).thenReturn(currency);
    when(currency.getISOCode()).thenReturn("EUR");
    when(method.getName()).thenReturn("Wire Transfer");
    // ETP-4841: a foreign payment carries the rate that was typed when it was registered.
    when(payment.getFinancialTransactionConvertRate()).thenReturn(new BigDecimal("0.89"));

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(Collections.singletonList(payment));

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    JSONObject payItem = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertEquals("acc-1", payItem.getString("accountId"));
    assertEquals("Bank EUR", payItem.getString("accountName"));
    assertEquals("EUR", payItem.getString("accountCurrency"));
    assertEquals("Wire Transfer", payItem.getString("paymentMethod"));
    assertEquals(0, new BigDecimal("0.89")
            .compareTo(new BigDecimal(payItem.getString("conversionRate"))),
        "the row must expose the payment's stored conversion rate (ETP-4841)");
  }

  /**
   * ETP-4841: every payment row exposes {@code conversionRate}, so the edit modal reseeds its
   * (editable) rate field from the payment itself instead of the system spot rate — which is what
   * made a rate typed on a draft disappear when the draft was reopened. A single-currency payment
   * carries ONE, where the modal hides the field.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsExposesConversionRateOneForSingleCurrencyPayment() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn("pay-5");
    when(payment.getDocumentNo()).thenReturn("PAY/005");
    when(payment.getAmount()).thenReturn(new BigDecimal("100.00"));
    when(payment.getPaymentDate()).thenReturn(null);
    when(payment.getStatus()).thenReturn("RPR");
    when(payment.isReceipt()).thenReturn(true);
    when(payment.getAccount()).thenReturn(null);
    when(payment.getPaymentMethod()).thenReturn(null);
    when(payment.getFinancialTransactionConvertRate()).thenReturn(BigDecimal.ONE);

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(Collections.singletonList(payment));

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    JSONObject payItem = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertEquals(0, BigDecimal.ONE.compareTo(new BigDecimal(payItem.getString("conversionRate"))));
  }

  /**
   * A legacy payment with no stored financial-transaction rate must not break the listing: the
   * {@code conversionRate} key is simply absent (JSON nulls are dropped), never a 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsNullConversionRateOmitsTheKey() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn("pay-6");
    when(payment.getDocumentNo()).thenReturn("PAY/006");
    when(payment.getAmount()).thenReturn(new BigDecimal("10.00"));
    when(payment.getPaymentDate()).thenReturn(null);
    when(payment.getStatus()).thenReturn("PPM");
    when(payment.isReceipt()).thenReturn(true);
    when(payment.getAccount()).thenReturn(null);
    when(payment.getPaymentMethod()).thenReturn(null);
    when(payment.getFinancialTransactionConvertRate()).thenReturn(null);

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(Collections.singletonList(payment));

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    JSONObject payItem = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertFalse(payItem.has("conversionRate"));
  }

  /**
   * Verifies that handleListPayments returns empty data when no payments exist.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsEmptyResultsReturns200WithEmptyData() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(Collections.emptyList());

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getJSONObject("response").getInt("count"));
  }

  /**
   * Verifies that handleListPayments returns HTTP 500 on unexpected exception.
   */
  @Test
  void testHandleListPaymentsExceptionReturns500() {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    when(session.createQuery(anyString(), eq(FIN_Payment.class)))
        .thenThrow(new RuntimeException("HQL error"));

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(500, response.getHttpStatus());
    assertNotNull(response.getBody());
  }

  /**
   * Verifies that handleListPayments handles null payment date gracefully.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsNullPaymentDateHandledGracefully() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getId()).thenReturn("pay-3");
    when(payment.getDocumentNo()).thenReturn("PAY/003");
    when(payment.getAmount()).thenReturn(BigDecimal.TEN);
    when(payment.getPaymentDate()).thenReturn(null);
    when(payment.getStatus()).thenReturn("PPM");
    when(payment.isReceipt()).thenReturn(true);
    when(payment.getAccount()).thenReturn(null);
    when(payment.getPaymentMethod()).thenReturn(null);

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(Collections.singletonList(payment));

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    JSONObject payItem = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertTrue(payItem.isNull("paymentDate"));
  }

  /**
   * Verifies that handleListPayments works with account having null currency.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentsAccountWithNullCurrencyHandledGracefully() throws Exception {
    NeoContext context = NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();

    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    when(payment.getId()).thenReturn("pay-4");
    when(payment.getDocumentNo()).thenReturn("PAY/004");
    when(payment.getAmount()).thenReturn(BigDecimal.ONE);
    when(payment.getPaymentDate()).thenReturn(null);
    when(payment.getStatus()).thenReturn("PPM");
    when(payment.isReceipt()).thenReturn(true);
    when(payment.getAccount()).thenReturn(account);
    when(payment.getPaymentMethod()).thenReturn(null);
    when(account.getId()).thenReturn("acc-1");
    when(account.getName()).thenReturn("Cash");
    when(account.getCurrency()).thenReturn(null);

    Query<FIN_Payment> hqlQuery = mock(Query.class);
    when(session.createQuery(anyString(), eq(FIN_Payment.class))).thenReturn(hqlQuery);
    when(hqlQuery.setParameter(anyString(), any())).thenReturn(hqlQuery);
    when(hqlQuery.setMaxResults(50)).thenReturn(hqlQuery);
    when(hqlQuery.list()).thenReturn(Collections.singletonList(payment));

    NeoResponse response = PaymentRegistrationService.handleListPayments(context);

    assertEquals(200, response.getHttpStatus());
    JSONObject payItem = response.getBody().getJSONObject("response")
        .getJSONArray("data").getJSONObject(0);
    assertTrue(payItem.isNull("accountCurrency"));
  }


  // ── linkPSDsToPayment() write-off flag (ETP-4797) ────────────────────────

  /**
   * Verifies every pre-ETP-4797 caller's behaviour with the flag off: the write-off flag reaching
   * Core must be {@code false}, so a shortfall duplicates the schedule detail (invoice stays
   * partially paid) rather than being written off.
   */
  @Test
  void testLinkPSDsToPaymentDefaultsToNoWriteoff() {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getAmount()).thenReturn(new BigDecimal("12.50"));

    try (MockedStatic<FIN_AddPayment> addPayment = mockStatic(FIN_AddPayment.class)) {
      PaymentRegistrationService.linkPSDsToPayment(
          Collections.singletonList(psd), payment, new BigDecimal("12.00"), false);

      addPayment.verify(() -> FIN_AddPayment.updatePaymentDetail(
          eq(psd), eq(payment), eq(new BigDecimal("12.00")), eq(false)));
    }
  }

  /**
   * Verifies the ETP-4797 path: the flag is handed to Core untouched, and the amount assigned is
   * still only what the payment funds (12,00 of a 12,50 installment). Core turns the 0,50 remainder
   * into {@code writeoffAmount}; asserting the arguments is what this layer is responsible for.
   */
  @Test
  void testLinkPSDsToPaymentForwardsTheWriteoffFlag() {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getAmount()).thenReturn(new BigDecimal("12.50"));

    try (MockedStatic<FIN_AddPayment> addPayment = mockStatic(FIN_AddPayment.class)) {
      PaymentRegistrationService.linkPSDsToPayment(
          Collections.singletonList(psd), payment, new BigDecimal("12.00"), true);

      addPayment.verify(() -> FIN_AddPayment.updatePaymentDetail(
          eq(psd), eq(payment), eq(new BigDecimal("12.00")), eq(true)));
    }
  }

  /**
   * Verifies that a PSD fully covered by the amount is assigned its own full value, so Core sees no
   * difference and cannot write anything off even with the flag on. Only the PSD where the funds
   * run out can produce a write-off — which is why passing the flag for the whole list is safe.
   */
  @Test
  void testLinkPSDsToPaymentAssignsFullAmountToCoveredPsds() {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail covered = mock(FIN_PaymentScheduleDetail.class);
    when(covered.getAmount()).thenReturn(new BigDecimal("10.00"));
    FIN_PaymentScheduleDetail partial = mock(FIN_PaymentScheduleDetail.class);
    when(partial.getAmount()).thenReturn(new BigDecimal("5.00"));

    try (MockedStatic<FIN_AddPayment> addPayment = mockStatic(FIN_AddPayment.class)) {
      PaymentRegistrationService.linkPSDsToPayment(
          Arrays.asList(covered, partial), payment, new BigDecimal("12.00"), true);

      // Fully covered: assigned its whole 10.00, leaving no difference for Core to write off.
      addPayment.verify(() -> FIN_AddPayment.updatePaymentDetail(
          eq(covered), eq(payment), eq(new BigDecimal("10.00")), eq(true)));
      // Boundary PSD: only the remaining 2.00 of its 5.00 — Core writes off the other 3.00.
      addPayment.verify(() -> FIN_AddPayment.updatePaymentDetail(
          eq(partial), eq(payment), eq(new BigDecimal("2.00")), eq(true)));
    }
  }

  /** Verifies that PSDs beyond the funded amount are never touched (the loop breaks). */
  @Test
  void testLinkPSDsToPaymentStopsOnceTheAmountIsExhausted() {
    FIN_Payment payment = mock(FIN_Payment.class);
    FIN_PaymentScheduleDetail first = mock(FIN_PaymentScheduleDetail.class);
    when(first.getAmount()).thenReturn(new BigDecimal("12.00"));
    FIN_PaymentScheduleDetail beyond = mock(FIN_PaymentScheduleDetail.class);

    try (MockedStatic<FIN_AddPayment> addPayment = mockStatic(FIN_AddPayment.class)) {
      PaymentRegistrationService.linkPSDsToPayment(
          Arrays.asList(first, beyond), payment, new BigDecimal("12.00"), true);

      addPayment.verify(() -> FIN_AddPayment.updatePaymentDetail(
          eq(first), eq(payment), eq(new BigDecimal("12.00")), eq(true)));
      addPayment.verify(() -> FIN_AddPayment.updatePaymentDetail(
          eq(beyond), any(), any(), anyBoolean()), never());
    }
  }
  // ========================================================================
  // PSD2 contract for the payment modal (ETP-4891)
  // ========================================================================

  /**
   * Builds the standard invoice/org/tree stubbing every listing test needs.
   */
  private void stubInvoiceInTree() {
    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("client-1");
    when(invoice.getOrganization()).thenReturn(org);
    when(org.getId()).thenReturn("org-1");
    when(obContext.getOrganizationStructureProvider("client-1")).thenReturn(osp);
    when(osp.getNaturalTree("org-1")).thenReturn(new HashSet<>(Collections.singleton("org-1")));
  }

  private static NeoContext listContext() {
    return NeoContext.builder()
        .recordId("inv-1")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * The payment modal needs all THREE PSD2 states, not two: an account that was connected and then
   * switched off keeps its Salt Edge link and is `bankReconnectable`, and a transfer aimed at it is
   * blocked; an account that was never connected has neither flag and keeps the ordinary manual
   * flow. Emitting only `bankConnected` made those two indistinguishable.
   *
   * @param connectionStatus the stored em_psd2_connection_status
   * @param saltEdgeAccountId the stored Salt Edge account id (null/blank when never linked)
   */
  @SuppressWarnings("unchecked")
  private JSONObject listOneAccount(String connectionStatus, String saltEdgeAccountId)
      throws Exception {
    stubInvoiceInTree();

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("acc-1");
    when(account.getName()).thenReturn("Banco");
    when(account.getPSD2ConnectionStatus()).thenReturn(connectionStatus);
    when(account.getPSD2SaltEdgeAccountID()).thenReturn(saltEdgeAccountId);

    OBCriteria<FIN_FinancialAccount> accountCriteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccount.class)).thenReturn(accountCriteria);
    when(accountCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.add(any(Criterion.class))).thenReturn(accountCriteria);
    when(accountCriteria.addOrderBy(anyString(), anyBoolean())).thenReturn(accountCriteria);
    when(accountCriteria.list()).thenReturn(Collections.singletonList(account));

    FIN_PaymentMethod transfer = mock(FIN_PaymentMethod.class);
    when(transfer.getId()).thenReturn("pm-transfer");
    when(transfer.getName()).thenReturn("Transferencia bancaria");
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    when(link.getPaymentMethod()).thenReturn(transfer);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Collections.singletonList(link));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(listContext(), false);
    assertEquals(200, response.getHttpStatus());
    return response.getBody().getJSONArray("items").getJSONObject(0);
  }

  /** An active connection ('CO') is connected and NOT reconnectable. */
  @Test
  void testHandleListAccountsActiveConnectionIsConnectedNotReconnectable() throws Exception {
    JSONObject item = listOneAccount("CO", "SE-ACC-001");
    assertTrue(item.getBoolean("bankConnected"));
    assertFalse(item.getBoolean("bankReconnectable"));
  }

  /** Switched off but still linked → reconnectable. This is the state that blocks a transfer. */
  @Test
  void testHandleListAccountsInactiveConnectionWithSurvivingLinkIsReconnectable() throws Exception {
    JSONObject item = listOneAccount("DI", "SE-ACC-001");
    assertFalse(item.getBoolean("bankConnected"));
    assertTrue(item.getBoolean("bankReconnectable"));
  }

  /** Never connected → neither flag, so the ordinary manual payment flow is untouched. */
  @Test
  void testHandleListAccountsNeverConnectedIsNeitherConnectedNorReconnectable() throws Exception {
    JSONObject item = listOneAccount(null, null);
    assertFalse(item.getBoolean("bankConnected"));
    assertFalse(item.getBoolean("bankReconnectable"));
  }

  /** A blank (not null) Salt Edge id is still "no link" — StringUtils.isNotBlank, not != null. */
  @Test
  void testHandleListAccountsBlankSaltEdgeIdIsNotReconnectable() throws Exception {
    JSONObject item = listOneAccount("DI", "   ");
    assertFalse(item.getBoolean("bankReconnectable"));
  }

  /**
   * The listed payment methods carry the authoritative {@code isBankTransfer} flag, so the SPA no
   * longer has to guess from the label with a regex. It matters because that gate now BLOCKS a
   * payment: a method merely NAMED like a transfer must report {@code false}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListPaymentMethodsEmitsIsBankTransferFromTheFlagNotTheName() throws Exception {
    stubInvoiceInTree();

    // Flagged as the bank transfer.
    FIN_PaymentMethod transfer = mock(FIN_PaymentMethod.class);
    when(transfer.getId()).thenReturn("pm-transfer");
    when(transfer.getName()).thenReturn("Transferencia bancaria");
    when(transfer.isPSD2IsBankTransfer()).thenReturn(Boolean.TRUE);

    // Named like one, but NOT flagged — the anti-regex case.
    FIN_PaymentMethod internal = mock(FIN_PaymentMethod.class);
    when(internal.getId()).thenReturn("pm-internal");
    when(internal.getName()).thenReturn("Transferencia interna");
    when(internal.isPSD2IsBankTransfer()).thenReturn(Boolean.FALSE);

    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Organization accOrg = mock(Organization.class);
    when(accOrg.getId()).thenReturn("org-1");
    when(account.getOrganization()).thenReturn(accOrg);

    FinAccPaymentMethod transferLink = mock(FinAccPaymentMethod.class);
    when(transferLink.getAccount()).thenReturn(account);
    when(transferLink.getPaymentMethod()).thenReturn(transfer);
    FinAccPaymentMethod internalLink = mock(FinAccPaymentMethod.class);
    when(internalLink.getAccount()).thenReturn(account);
    when(internalLink.getPaymentMethod()).thenReturn(internal);

    OBCriteria<FinAccPaymentMethod> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(crit);
    when(crit.setFilterOnReadableOrganization(anyBoolean())).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(transferLink, internalLink));

    NeoResponse response =
        PaymentRegistrationService.handleListPaymentMethods(listContext(), false);

    assertEquals(200, response.getHttpStatus());
    JSONArray items = response.getBody().getJSONArray("items");
    assertEquals(2, items.length());
    JSONObject first = items.getJSONObject(0);
    JSONObject second = items.getJSONObject(1);
    assertEquals("pm-transfer", first.getString("id"));
    assertEquals("Transferencia bancaria", first.getString("label"));
    assertTrue(first.getBoolean("isBankTransfer"));
    assertEquals("pm-internal", second.getString("id"));
    assertEquals("Transferencia interna", second.getString("label"));
    assertFalse(second.getBoolean("isBankTransfer"),
        "a method merely named like a transfer must not be reported as one");
  }
  // ========================================================================
  // resolveProcessAction (ETP-4891)
  // ========================================================================

  /**
   * "D" ("Process Made Payment(s) and Withdrawal" in Classic) is the only way left to get Core to
   * create the {@code FIN_Finacc_Transaction} for a transfer, now that Automatic Withdrawn is
   * permanently off for that method. It is required for every transfer payment OUT except one that
   * can defer to a live PIS handshake — a connected account, and ONLY when the caller passes
   * {@code mayDeferToPis=true}.
   */
  private static FIN_Payment paymentFor(boolean isReceipt, FIN_PaymentMethod method,
      FIN_FinancialAccount account) {
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.isReceipt()).thenReturn(isReceipt);
    when(payment.getPaymentMethod()).thenReturn(method);
    when(payment.getAccount()).thenReturn(account);
    return payment;
  }

  private static FIN_PaymentMethod transferMethod() {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.isPSD2IsBankTransfer()).thenReturn(Boolean.TRUE);
    return method;
  }

  private static FIN_PaymentMethod nonTransferMethod() {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.isPSD2IsBankTransfer()).thenReturn(Boolean.FALSE);
    when(method.getName()).thenReturn("Efectivo");
    return method;
  }

  private static FIN_FinancialAccount accountWithStatus(String connectionStatus) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getPSD2ConnectionStatus()).thenReturn(connectionStatus);
    return account;
  }

  @Test
  void testResolveProcessActionTransferOutNotConnectedGetsWithdrawal() {
    FIN_Payment payment = paymentFor(false, transferMethod(), accountWithStatus("NC"));
    assertEquals("D", PaymentRegistrationService.resolveProcessAction(payment, true));
  }

  @Test
  void testResolveProcessActionTransferOutNeverConnectedGetsWithdrawal() {
    // No PSD2 status at all (null) — never linked, must not be confused with "connected".
    FIN_Payment payment = paymentFor(false, transferMethod(), accountWithStatus(null));
    assertEquals("D", PaymentRegistrationService.resolveProcessAction(payment, true));
  }

  @Test
  void testResolveProcessActionTransferOutConnectedDefersToPisWhenAllowed() {
    FIN_Payment payment = paymentFor(false, transferMethod(),
        accountWithStatus(BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED));
    assertEquals("P", PaymentRegistrationService.resolveProcessAction(payment, true));
  }

  /**
   * The whole reason {@code mayDeferToPis} exists: a caller that never initiates a PIS handshake
   * (confirmDraftPayment, the quick-pay path, bank reconciliation, the New Movement wizard) must
   * get the transaction created NOW even on a connected account — nothing else will ever create it
   * for that specific call. Connection state alone is not enough; the caller's intent is.
   */
  @Test
  void testResolveProcessActionTransferOutConnectedButCallerCannotDeferStillWithdraws() {
    FIN_Payment payment = paymentFor(false, transferMethod(),
        accountWithStatus(BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED));
    assertEquals("D", PaymentRegistrationService.resolveProcessAction(payment, false));
  }

  @Test
  void testResolveProcessActionReceiptIsAlwaysPlainProcessRegardlessOfMethodOrConnection() {
    // Automatic Deposit was never touched by ETP-4891 — Core's own trigger still governs receipts.
    FIN_Payment payment = paymentFor(true, transferMethod(), accountWithStatus("NC"));
    assertEquals("P", PaymentRegistrationService.resolveProcessAction(payment, true));
    assertEquals("P", PaymentRegistrationService.resolveProcessAction(payment, false));
  }

  @Test
  void testResolveProcessActionNonTransferMethodIsAlwaysPlainProcess() {
    FIN_Payment payment = paymentFor(false, nonTransferMethod(), accountWithStatus("NC"));
    assertEquals("P", PaymentRegistrationService.resolveProcessAction(payment, true));
    assertEquals("P", PaymentRegistrationService.resolveProcessAction(payment, false));
  }

  @Test
  void testResolveProcessActionFallsBackToNameWhenFlagAbsent() {
    // Mirrors isBankTransferMethod's own name fallback for a legacy template with no flag set yet.
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getName()).thenReturn("Transferencia bancaria");
    FIN_Payment payment = paymentFor(false, method, accountWithStatus("NC"));
    assertEquals("D", PaymentRegistrationService.resolveProcessAction(payment, true));
  }
}
