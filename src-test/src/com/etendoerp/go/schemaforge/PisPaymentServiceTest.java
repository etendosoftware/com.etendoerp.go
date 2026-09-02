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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.common.businesspartner.BankAccount;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;
import com.etendoerp.psd2.bank.integration.utils.PISTransactionUtils;

/**
 * Unit tests for {@link PisPaymentService}.
 *
 * <p>Covers: {@code handlePisPaymentStatus} (status refresh short-circuit and failure
 * tolerance), {@code handleCancelPisPayment} (undo eligibility and the reactivate+remove
 * sequence), {@code handlePisTemplates}, {@code handleListSupplierBankAccounts},
 * {@code validatePisEligibility}, {@code extractPisInput} and {@code linkedPisPayment}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PisPaymentServiceTest {

  @Mock
  private OBDal obDal;
  @Mock
  private OBContext obContext;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  private NeoContext pisIdContext(String pisPaymentId) throws Exception {
    JSONObject body = new JSONObject().put("pisPaymentId", pisPaymentId);
    return NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
  }

  // ========================================================================
  // handlePisPaymentStatus tests
  // ========================================================================

  @Test
  void testHandlePisPaymentStatusBlankPaymentIdReturns400() throws Exception {
    NeoResponse response = PisPaymentService.handlePisPaymentStatus(pisIdContext(""));

    assertEquals(400, response.getHttpStatus());
  }

  @Test
  void testHandlePisPaymentStatusNotFoundReturns404() throws Exception {
    when(obDal.get(PisPayment.class, "missing")).thenReturn(null);

    NeoResponse response = PisPaymentService.handlePisPaymentStatus(pisIdContext("missing"));

    assertEquals(404, response.getHttpStatus());
  }

  /**
   * Verifies that when the locally stored PIS status is NOT terminal (e.g. "authorizing"),
   * the handler actively refreshes it from Salt Edge through the private
   * {@code refreshPisStatusFromSaltEdge} helper, which composes the public PSD2 statics
   * ({@link BankIntegrationUtils#getPsd2ApiKey}, {@link BankIntegrationPISUtils#showPayment},
   * {@link PISPaymentDao#updateStatusWithAttributes}). The response reflects the status
   * persisted onto the entity by that refresh (simulated here via a second stubbed
   * {@code getStatus()} return, mirroring the refresh mutating the entity in place).
   */
  @Test
  void testHandlePisPaymentStatusNonTerminalRefreshesFromSaltEdge() throws Exception {
    NeoContext context = pisIdContext("pis-1");

    PisPayment pisPayment = mock(PisPayment.class);
    when(obDal.get(PisPayment.class, "pis-1")).thenReturn(pisPayment);
    // First read (terminal-status check) sees "authorizing"; after the refresh call the
    // entity is presumed updated in place, so the second read (building the response)
    // reflects the new value persisted by updateStatusWithAttributes.
    when(pisPayment.getStatus()).thenReturn("authorizing", BankIntegrationConstants.PIS_STATUS_EXECUTED);
    when(pisPayment.getSaltedgePayment()).thenReturn("se-pay-1");
    // The financial transaction hangs off the payment, so it is only created once one exists.
    when(pisPayment.getPayment()).thenReturn(mock(FIN_Payment.class));

    Client client = mock(Client.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    BankIntegrationPISUtils.PISPaymentStatus refreshed =
        mock(BankIntegrationPISUtils.PISPaymentStatus.class);
    when(refreshed.getStatus()).thenReturn(BankIntegrationConstants.PIS_STATUS_EXECUTED);

    try (MockedStatic<BankIntegrationUtils> bankUtilsMock = mockStatic(BankIntegrationUtils.class);
         MockedStatic<BankIntegrationPISUtils> pisUtilsMock = mockStatic(BankIntegrationPISUtils.class);
         MockedStatic<PISPaymentDao> pisDaoMock = mockStatic(PISPaymentDao.class);
         MockedStatic<PISTransactionUtils> pisTxMock = mockStatic(PISTransactionUtils.class)) {
      bankUtilsMock.when(() -> BankIntegrationUtils.getPsd2ApiKey(client)).thenReturn("api-key-1");
      pisUtilsMock.when(() -> BankIntegrationPISUtils.showPayment("api-key-1", "se-pay-1"))
          .thenReturn(refreshed);

      NeoResponse response = PisPaymentService.handlePisPaymentStatus(context);

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankIntegrationConstants.PIS_STATUS_EXECUTED,
          response.getBody().getString("status"));
      pisUtilsMock.verify(() -> BankIntegrationPISUtils.showPayment("api-key-1", "se-pay-1"));
      // executed => the financial transaction is created
      pisTxMock.verify(() -> PISTransactionUtils.createFinancialTransactionIfEligible(pisPayment));
    }
  }

  /**
   * Verifies the short-circuit optimization: when the locally stored status is already
   * terminal ("executed"), the handler does NOT call out to Salt Edge at all.
   */
  @Test
  void testHandlePisPaymentStatusTerminalStatusSkipsRefresh() throws Exception {
    NeoContext context = pisIdContext("pis-2");

    PisPayment pisPayment = mock(PisPayment.class);
    when(obDal.get(PisPayment.class, "pis-2")).thenReturn(pisPayment);
    when(pisPayment.getStatus()).thenReturn(BankIntegrationConstants.PIS_STATUS_EXECUTED);

    try (MockedStatic<BankIntegrationPISUtils> pisUtilsMock =
             mockStatic(BankIntegrationPISUtils.class)) {
      NeoResponse response = PisPaymentService.handlePisPaymentStatus(context);

      assertEquals(200, response.getHttpStatus());
      assertEquals(BankIntegrationConstants.PIS_STATUS_EXECUTED,
          response.getBody().getString("status"));
      pisUtilsMock.verify(() -> BankIntegrationPISUtils.showPayment(anyString(), anyString()),
          never());
    }
  }

  /**
   * Verifies that a failure while refreshing from Salt Edge (e.g. the API key lookup blows
   * up) is swallowed: the handler still returns HTTP 200 with whatever status was already
   * stored locally before the failed refresh attempt, instead of propagating the exception.
   */
  @Test
  void testHandlePisPaymentStatusRefreshFailureFallsBackToStoredStatus() throws Exception {
    NeoContext context = pisIdContext("pis-3");

    PisPayment pisPayment = mock(PisPayment.class);
    when(obDal.get(PisPayment.class, "pis-3")).thenReturn(pisPayment);
    when(pisPayment.getStatus()).thenReturn("authorizing");

    Client client = mock(Client.class);
    when(obContext.getCurrentClient()).thenReturn(client);

    try (MockedStatic<BankIntegrationUtils> bankUtilsMock = mockStatic(BankIntegrationUtils.class)) {
      bankUtilsMock.when(() -> BankIntegrationUtils.getPsd2ApiKey(client))
          .thenThrow(new RuntimeException("Salt Edge unreachable"));

      NeoResponse response = PisPaymentService.handlePisPaymentStatus(context);

      assertEquals(200, response.getHttpStatus());
      assertEquals("authorizing", response.getBody().getString("status"));
    }
  }

  // ========================================================================
  // handleCancelPisPayment tests
  // ========================================================================

  @Test
  void testHandleCancelPisPaymentBlankIdReturns400() throws Exception {
    NeoResponse response = PisPaymentService.handleCancelPisPayment(pisIdContext(""));

    assertEquals(400, response.getHttpStatus());
  }

  @Test
  void testHandleCancelPisPaymentNotFoundReturns404() throws Exception {
    when(obDal.get(PisPayment.class, "missing")).thenReturn(null);

    NeoResponse response = PisPaymentService.handleCancelPisPayment(pisIdContext("missing"));

    assertEquals(404, response.getHttpStatus());
  }

  /**
   * A transfer already past {@code authorizing} (e.g. "executed") can no longer be safely
   * undone — the handler must reject with 400 and roll back without touching the payment.
   */
  @Test
  void testHandleCancelPisPaymentNonCancellableStatusReturns400AndRollsBack() throws Exception {
    PisPayment pisPayment = mock(PisPayment.class);
    when(obDal.get(PisPayment.class, "pis-4")).thenReturn(pisPayment);
    when(pisPayment.getStatus()).thenReturn(BankIntegrationConstants.PIS_STATUS_EXECUTED);

    NeoResponse response = PisPaymentService.handleCancelPisPayment(pisIdContext("pis-4"));

    assertEquals(400, response.getHttpStatus());
    verify(obDal).rollbackAndClose();
    verify(pisPayment, never()).setStatus(anyString());
  }

  /**
   * The happy path: a still-cancellable PIS payment is marked "failed", detached from the
   * {@link FIN_Payment}, and the payment itself is reactivated then permanently removed via
   * {@code com.etendoerp.payment.removal}.
   */
  @Test
  void testHandleCancelPisPaymentCancellableReactivatesAndRemovesPayment() throws Exception {
    PisPayment pisPayment = mock(PisPayment.class);
    FIN_Payment linkedPayment = mock(FIN_Payment.class);
    FIN_Payment reactivatedPayment = mock(FIN_Payment.class);

    when(obDal.get(PisPayment.class, "pis-5")).thenReturn(pisPayment);
    when(pisPayment.getStatus()).thenReturn("requested");
    when(pisPayment.getPayment()).thenReturn(linkedPayment);
    when(linkedPayment.getId()).thenReturn("pay-1");
    when(obDal.get(FIN_Payment.class, "pay-1")).thenReturn(reactivatedPayment);

    try (MockedStatic<PaymentRemovalUtil> removalMock = mockStatic(PaymentRemovalUtil.class)) {
      NeoResponse response = PisPaymentService.handleCancelPisPayment(pisIdContext("pis-5"));

      assertEquals(200, response.getHttpStatus());
      assertTrue(response.getBody().getBoolean("cancelled"));
      verify(pisPayment).setStatus("failed");
      verify(pisPayment).setPayment(null);
      removalMock.verify(() -> PaymentRemovalUtil.reactivate("pay-1", "R"));
      removalMock.verify(() -> PaymentRemovalUtil.remove(reactivatedPayment));
    }
  }

  /**
   * A PIS payment that never got as far as creating a {@link FIN_Payment} (e.g. failed before
   * the draft was linked) is still marked undone, but no reactivate/remove call is made.
   */
  @Test
  void testHandleCancelPisPaymentWithNoLinkedPaymentSkipsRemoval() throws Exception {
    PisPayment pisPayment = mock(PisPayment.class);
    when(obDal.get(PisPayment.class, "pis-6")).thenReturn(pisPayment);
    when(pisPayment.getStatus()).thenReturn("requested");
    when(pisPayment.getPayment()).thenReturn(null);

    try (MockedStatic<PaymentRemovalUtil> removalMock = mockStatic(PaymentRemovalUtil.class)) {
      NeoResponse response = PisPaymentService.handleCancelPisPayment(pisIdContext("pis-6"));

      assertEquals(200, response.getHttpStatus());
      removalMock.verifyNoInteractions();
    }
  }

  // ========================================================================
  // handlePisTemplates tests
  // ========================================================================

  @Test
  @SuppressWarnings("unchecked")
  void testHandlePisTemplatesReturnsItemsFromReferenceList() throws JSONException {
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("en_US");
    when(obContext.getLanguage()).thenReturn(language);

    org.openbravo.model.ad.domain.List sepa = mock(org.openbravo.model.ad.domain.List.class);
    when(sepa.getSearchKey()).thenReturn("SEPA");
    org.openbravo.model.ad.domain.Reference reference = mock(org.openbravo.model.ad.domain.Reference.class);
    when(reference.getName()).thenReturn("Template List for Bank Payments");
    when(sepa.getReference()).thenReturn(reference);

    OBCriteria<org.openbravo.model.ad.domain.List> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(org.openbravo.model.ad.domain.List.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(sepa));

    try (MockedStatic<org.openbravo.erpCommon.utility.Utility> utilityMock =
             mockStatic(org.openbravo.erpCommon.utility.Utility.class)) {
      utilityMock.when(() -> org.openbravo.erpCommon.utility.Utility
              .getListValueName("Template List for Bank Payments", "SEPA", "en_US"))
          .thenReturn("SEPA Transfer");

      NeoResponse response = PisPaymentService.handlePisTemplates();

      assertEquals(200, response.getHttpStatus());
      JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);
      assertEquals("SEPA", item.getString("value"));
      assertEquals("SEPA Transfer", item.getString("label"));
    }
  }

  // ========================================================================
  // handleListSupplierBankAccounts tests
  // ========================================================================

  private NeoContext invoiceContext(String invoiceId) {
    return NeoContext.builder()
        .recordId(invoiceId)
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  @Test
  void testHandleListSupplierBankAccountsBlankInvoiceIdReturns400() {
    NeoResponse response = PisPaymentService.handleListSupplierBankAccounts(invoiceContext(""));

    assertEquals(400, response.getHttpStatus());
  }

  @Test
  void testHandleListSupplierBankAccountsInvoiceNotFoundReturns404() {
    when(obDal.get(Invoice.class, "inv-missing")).thenReturn(null);

    NeoResponse response =
        PisPaymentService.handleListSupplierBankAccounts(invoiceContext("inv-missing"));

    assertEquals(404, response.getHttpStatus());
  }

  @Test
  void testHandleListSupplierBankAccountsNoBusinessPartnerReturnsEmpty() throws JSONException {
    Invoice invoice = mock(Invoice.class);
    when(obDal.get(Invoice.class, "inv-1")).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(null);

    NeoResponse response = PisPaymentService.handleListSupplierBankAccounts(invoiceContext("inv-1"));

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("totalCount"));
  }

  /**
   * Accounts with a blank IBAN are skipped entirely (they cannot be a PIS destination), and
   * the first remaining account (oldest by creation date, per the query order) is flagged
   * {@code default: true}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListSupplierBankAccountsSkipsBlankIbanAndMarksFirstAsDefault() throws JSONException {
    Invoice invoice = mock(Invoice.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    when(obDal.get(Invoice.class, "inv-2")).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(bp);

    BankAccount noIban = mock(BankAccount.class);
    when(noIban.getIBAN()).thenReturn("");

    BankAccount withIban = mock(BankAccount.class);
    when(withIban.getIBAN()).thenReturn("ES9121000418450200051332");
    when(withIban.getName()).thenReturn("Main account");

    OBCriteria<BankAccount> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(BankAccount.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(noIban, withIban));

    NeoResponse response = PisPaymentService.handleListSupplierBankAccounts(invoiceContext("inv-2"));

    assertEquals(200, response.getHttpStatus());
    assertEquals(1, response.getBody().getInt("totalCount"));
    JSONObject item = response.getBody().getJSONArray("items").getJSONObject(0);
    assertEquals("ES9121000418450200051332", item.getString("iban"));
    assertEquals("Main account", item.getString("name"));
    assertTrue(item.getBoolean("default"));
  }

  // ========================================================================
  // validatePisEligibility tests
  // ========================================================================

  private FIN_FinancialAccount connectedAccount(String accountIsoCode) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getPSD2ConnectionStatus())
        .thenReturn(BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED);
    if (accountIsoCode != null) {
      Currency currency = mock(Currency.class);
      when(currency.getISOCode()).thenReturn(accountIsoCode);
      when(account.getCurrency()).thenReturn(currency);
    }
    return account;
  }

  /** The common case: a connected account denominated in EUR. */
  private FIN_FinancialAccount connectedAccount() {
    return connectedAccount("EUR");
  }

  private FIN_PaymentMethod transferMethod() {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getName()).thenReturn("Bank Transfer");
    return method;
  }

  private Invoice invoiceWithCurrency(String isoCode) {
    Invoice invoice = mock(Invoice.class);
    if (isoCode != null) {
      Currency currency = mock(Currency.class);
      when(currency.getISOCode()).thenReturn(isoCode);
      when(invoice.getCurrency()).thenReturn(currency);
    }
    return invoice;
  }

  @Test
  void testValidatePisEligibilityThrowsWhenAccountNotConnected() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getPSD2ConnectionStatus()).thenReturn("NC");

    assertThrows(OBException.class, () -> PisPaymentService.validatePisEligibility(
        account, transferMethod(), invoiceWithCurrency("EUR")));
  }

  @Test
  void testValidatePisEligibilityThrowsWhenMethodIsNotTransfer() {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.getName()).thenReturn("Cash");

    assertThrows(OBException.class, () -> PisPaymentService.validatePisEligibility(
        connectedAccount(), method, invoiceWithCurrency("EUR")));
  }

  /**
   * The currency gate is on the ACCOUNT (ETP-5084): a CHF bank account cannot be instructed over
   * PIS regardless of what the invoice is denominated in.
   */
  @Test
  void testValidatePisEligibilityThrowsWhenAccountCurrencyUnsupported() {
    assertThrows(OBException.class, () -> PisPaymentService.validatePisEligibility(
        connectedAccount("CHF"), transferMethod(), invoiceWithCurrency("EUR")));
  }

  @Test
  void testValidatePisEligibilityThrowsWhenAccountHasNoCurrency() {
    assertThrows(OBException.class, () -> PisPaymentService.validatePisEligibility(
        connectedAccount(null), transferMethod(), invoiceWithCurrency("EUR")));
  }

  /**
   * Without an invoice currency the amount could not be converted to the account currency, and
   * instructing the bank with an unconverted figure would move the wrong amount of money.
   */
  @Test
  void testValidatePisEligibilityThrowsWhenInvoiceHasNoCurrency() {
    assertThrows(OBException.class, () -> PisPaymentService.validatePisEligibility(
        connectedAccount("EUR"), transferMethod(), invoiceWithCurrency(null)));
  }

  @Test
  void testValidatePisEligibilityPassesForEveryEligibleAccountCurrency() {
    for (String accountIso : new String[] { "EUR", "USD", "GBP" }) {
      PisPaymentService.validatePisEligibility(
          connectedAccount(accountIso), transferMethod(), invoiceWithCurrency(accountIso));
    }
  }

  /**
   * ETP-5084 — the case the ticket is about: a USD invoice paid from a connected EUR account. Before
   * this change the gate read the INVOICE currency and rejected it outright ("only supported for EUR
   * and GBP invoices"), even though the transfer is instructed in EUR after conversion.
   */
  @Test
  void testValidatePisEligibilityPassesForForeignInvoiceOnEligibleAccount() {
    PisPaymentService.validatePisEligibility(
        connectedAccount("EUR"), transferMethod(), invoiceWithCurrency("USD"));
  }

  /** The mirror case: an EUR invoice paid from a connected GBP account. */
  @Test
  void testValidatePisEligibilityPassesForEurInvoiceOnGbpAccount() {
    PisPaymentService.validatePisEligibility(
        connectedAccount("GBP"), transferMethod(), invoiceWithCurrency("EUR"));
  }

  // ========================================================================
  // extractPisInput tests
  // ========================================================================

  /**
   * Only the creditor fields actually present in the request body are copied into the
   * PSD2-facing payload — a template switch never leaks a stale field from a previous
   * selection because the caller only sends the fields the current template needs.
   */
  @Test
  void testExtractPisInputOnlyIncludesPresentFields() throws Exception {
    JSONObject body = new JSONObject();
    body.put("pisTemplate", "SEPA");
    body.put("pisCreditorIban", "ES9121000418450200051332");
    // pisCreditorBban / pisCreditorAccountNumber / pisCreditorSortCode intentionally absent.

    JSONObject input = PisPaymentService.extractPisInput(body);

    assertEquals("SEPA", input.getString("template"));
    assertEquals("ES9121000418450200051332",
        input.getString(BankIntegrationConstants.CREDITOR_IBAN));
    assertFalse(input.has(BankIntegrationConstants.CREDITOR_BBAN));
    assertFalse(input.has(BankIntegrationConstants.CREDITOR_ACCOUNT_NUMBER));
    assertFalse(input.has(BankIntegrationConstants.CREDITOR_SORT_CODE));
  }

  @Test
  void testExtractPisInputWithEmptyBodyReturnsEmptyInput() throws Exception {
    JSONObject input = PisPaymentService.extractPisInput(new JSONObject());

    assertFalse(input.has("template"));
    assertFalse(input.has(BankIntegrationConstants.CREDITOR_IBAN));
  }

  // ========================================================================
  // linkedPisPayment tests
  // ========================================================================
  //
  // Returns the row rather than a boolean: the SPA needs its id, because retrying a transfer acts
  // on the PIS attempt rather than on the payment.

  @Test
  @SuppressWarnings("unchecked")
  void testLinkedPisPaymentReturnsTheRowWhenCriteriaFindsOne() {
    FIN_Payment payment = mock(FIN_Payment.class);
    PisPayment linked = mock(PisPayment.class);
    OBCriteria<PisPayment> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(PisPayment.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(linked);

    assertSame(linked, PisPaymentService.linkedPisPayment(payment));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testLinkedPisPaymentReturnsNullWhenNoRow() {
    FIN_Payment payment = mock(FIN_Payment.class);
    OBCriteria<PisPayment> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(PisPayment.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(null);

    assertNull(PisPaymentService.linkedPisPayment(payment));
  }

  // ========================================================================
  // handlePisTemplates error branch
  // ========================================================================

  /**
   * Any failure while building the reference-list query (e.g. the DAL throws) is mapped to a
   * 500 by the catch-all, rather than propagating out of the handler.
   */
  @Test
  void testHandlePisTemplatesErrorReturns500() {
    Language language = mock(Language.class);
    when(language.getLanguage()).thenReturn("en_US");
    when(obContext.getLanguage()).thenReturn(language);
    when(obDal.createCriteria(org.openbravo.model.ad.domain.List.class))
        .thenThrow(new RuntimeException("DAL down"));

    NeoResponse response = PisPaymentService.handlePisTemplates();

    assertEquals(500, response.getHttpStatus());
  }

  // ========================================================================
  // handleCancelPisPayment generic-exception branch
  // ========================================================================

  /**
   * A non-{@link OBException} failure during the reactivate/remove sequence is caught by the
   * generic handler: it rolls back and returns 500 (distinct from the 400 OBException path).
   */
  @Test
  void testHandleCancelPisPaymentGenericExceptionReturns500AndRollsBack() throws Exception {
    PisPayment pisPayment = mock(PisPayment.class);
    FIN_Payment linkedPayment = mock(FIN_Payment.class);
    when(obDal.get(PisPayment.class, "pis-err")).thenReturn(pisPayment);
    when(pisPayment.getStatus()).thenReturn("requested");
    when(pisPayment.getPayment()).thenReturn(linkedPayment);
    when(linkedPayment.getId()).thenReturn("pay-err");

    try (MockedStatic<PaymentRemovalUtil> removalMock = mockStatic(PaymentRemovalUtil.class)) {
      removalMock.when(() -> PaymentRemovalUtil.reactivate("pay-err", "R"))
          .thenThrow(new RuntimeException("reactivation blew up"));

      NeoResponse response = PisPaymentService.handleCancelPisPayment(pisIdContext("pis-err"));

      assertEquals(500, response.getHttpStatus());
      verify(obDal).rollbackAndClose();
    }
  }

  // ========================================================================
  // handleListSupplierBankAccounts error branch + name fallbacks
  // ========================================================================

  /** A DAL failure while listing bank accounts is mapped to a 500 by the catch-all. */
  @Test
  void testHandleListSupplierBankAccountsErrorReturns500() {
    Invoice invoice = mock(Invoice.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    when(obDal.get(Invoice.class, "inv-err")).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(bp);
    when(obDal.createCriteria(BankAccount.class)).thenThrow(new RuntimeException("DAL down"));

    NeoResponse response =
        PisPaymentService.handleListSupplierBankAccounts(invoiceContext("inv-err"));

    assertEquals(500, response.getHttpStatus());
  }

  /**
   * The display name for a supplier bank account falls back through name → bank name → account
   * number: a blank name uses the bank name, and when both name and bank name are blank the
   * account number is used.
   */
  @Test
  @SuppressWarnings("unchecked")
  void testHandleListSupplierBankAccountsNameFallsBackToBankNameThenAccountNo()
      throws JSONException {
    Invoice invoice = mock(Invoice.class);
    BusinessPartner bp = mock(BusinessPartner.class);
    when(obDal.get(Invoice.class, "inv-fb")).thenReturn(invoice);
    when(invoice.getBusinessPartner()).thenReturn(bp);

    BankAccount blankNameUsesBank = mock(BankAccount.class);
    when(blankNameUsesBank.getIBAN()).thenReturn("ES0000000000000000000001");
    when(blankNameUsesBank.getName()).thenReturn("");
    when(blankNameUsesBank.getBankName()).thenReturn("Banco Falso");

    BankAccount blankNameAndBankUsesAccountNo = mock(BankAccount.class);
    when(blankNameAndBankUsesAccountNo.getIBAN()).thenReturn("ES0000000000000000000002");
    when(blankNameAndBankUsesAccountNo.getName()).thenReturn(null);
    when(blankNameAndBankUsesAccountNo.getBankName()).thenReturn(null);
    when(blankNameAndBankUsesAccountNo.getAccountNo()).thenReturn("ACC-777");

    OBCriteria<BankAccount> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(BankAccount.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(blankNameUsesBank, blankNameAndBankUsesAccountNo));

    NeoResponse response = PisPaymentService.handleListSupplierBankAccounts(invoiceContext("inv-fb"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("Banco Falso",
        response.getBody().getJSONArray("items").getJSONObject(0).getString("name"));
    assertEquals("ACC-777",
        response.getBody().getJSONArray("items").getJSONObject(1).getString("name"));
  }
}
