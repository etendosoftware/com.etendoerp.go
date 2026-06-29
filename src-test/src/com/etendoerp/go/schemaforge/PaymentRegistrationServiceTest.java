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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.ad.system.Client;

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
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obDal.getSession()).thenReturn(session);
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
    when(paymentMethod.getName()).thenReturn("Wire Transfer");
    when(finAccMethod.getPaymentMethod()).thenReturn(paymentMethod);

    when(obDal.createCriteria(FinAccPaymentMethod.class))
        .thenReturn(methodCritValid)
        .thenReturn(methodCritInvalid);

    when(methodCritValid.add(any(Criterion.class))).thenReturn(methodCritValid);
    when(methodCritValid.setMaxResults(1)).thenReturn(methodCritValid);
    when(methodCritValid.list()).thenReturn(Collections.singletonList(finAccMethod));

    when(methodCritInvalid.add(any(Criterion.class))).thenReturn(methodCritInvalid);
    when(methodCritInvalid.setMaxResults(1)).thenReturn(methodCritInvalid);
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
    when(paymentMethod.getName()).thenReturn("Cash");
    when(finAccMethod.getPaymentMethod()).thenReturn(paymentMethod);

    OBCriteria<FinAccPaymentMethod> methodCrit = mock(OBCriteria.class);
    when(obDal.createCriteria(FinAccPaymentMethod.class)).thenReturn(methodCrit);
    when(methodCrit.add(any(Criterion.class))).thenReturn(methodCrit);
    when(methodCrit.setMaxResults(1)).thenReturn(methodCrit);
    when(methodCrit.list()).thenReturn(Collections.singletonList(finAccMethod));

    NeoResponse response = PaymentRegistrationService.handleListAccounts(context, true);

    assertEquals(200, response.getHttpStatus());
    JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);
    assertEquals("acc-1", item.getString("id"));
    assertEquals("Cash Account", item.getString("label"));
    assertTrue(!item.has("currency"), "currency field should be absent when account currency is null");
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
}