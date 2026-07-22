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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * DB-free unit tests for the ETP-4502 multi-currency (foreign-invoice) branch of
 * {@link ReconciliationFlowSupport#createInvoicePayments}. Only the validation/rejection paths are
 * exercised here: they return a {@link NeoResponse} before any DAL write, so they are reachable by
 * mocking {@link OBDal#get} alone. The happy path (which delegates to
 * {@code ReconciliationPaymentService.registerReconciliationPaymentMultiCurrency} — draft-payment
 * creation, {@code processOrThrow}, transaction persistence) needs an integration harness and is
 * documented as not covered here.
 *
 * <p>The foreign branch is entered when the account has a currency and at least one selected
 * invoice is in a different currency ({@code hasForeignInvoice}). Account currency is {@code USD};
 * a foreign invoice is {@code EUR}; a same-currency invoice is {@code USD}.
 *
 * <p>Edge cases covered ({@code >= 3} required):
 * <ul>
 *   <li>more than one foreign invoice under a single line -> 400 (single-invoice restriction)</li>
 *   <li>foreign invoice with a blank scheduleId -> 400</li>
 *   <li>foreign invoice/schedule not found in the DAL -> 404</li>
 *   <li>foreign invoice with zero outstanding -> 400</li>
 *   <li>foreign invoice with a zero-amount statement line -> 400</li>
 *   <li>same-currency zero line -> legacy path, returns null (NOT the foreign zero-line error)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationFlowSupportForeignInvoiceTest {

  private static final String ACCOUNT_CURRENCY = "USD";
  private static final String FOREIGN_CURRENCY = "EUR";
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  @Mock
  private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static Currency currency(String id) {
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(id);
    return c;
  }

  private FIN_FinancialAccount account(String currencyId) {
    FIN_FinancialAccount acc = mock(FIN_FinancialAccount.class);
    // The Currency mock must be fully built (its own when/thenReturn completed) BEFORE opening
    // acc.getCurrency()'s stub — nesting an unrelated when(...) inside a pending thenReturn(...)
    // argument throws Mockito's UnfinishedStubbingException.
    Currency cur = currencyId == null ? null : currency(currencyId);
    when(acc.getCurrency()).thenReturn(cur);
    return acc;
  }

  private Invoice invoice(String id, String currencyId) {
    Invoice inv = mock(Invoice.class);
    Currency cur = currency(currencyId);
    when(inv.getCurrency()).thenReturn(cur);
    when(obDal.get(eq(Invoice.class), eq(id))).thenReturn(inv);
    return inv;
  }

  private FIN_PaymentSchedule schedule(String id, String outstanding) {
    FIN_PaymentSchedule sch = mock(FIN_PaymentSchedule.class);
    when(sch.getOutstandingAmount())
        .thenReturn(outstanding == null ? null : new BigDecimal(outstanding));
    when(obDal.get(eq(FIN_PaymentSchedule.class), eq(id))).thenReturn(sch);
    return sch;
  }

  /** Statement line with the given credit / debit amounts (line amount = cr - dr). */
  private static FIN_BankStatementLine line(String cr, String dr) {
    FIN_BankStatementLine l = mock(FIN_BankStatementLine.class);
    when(l.getCramount()).thenReturn(cr == null ? null : new BigDecimal(cr));
    when(l.getDramount()).thenReturn(dr == null ? null : new BigDecimal(dr));
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

  private static String message(NeoResponse resp) throws JSONException {
    return resp.getBody().getJSONObject("error").getString("message");
  }

  // ── tests ────────────────────────────────────────────────────────────────

  /**
   * Two foreign invoices under one statement line are rejected: multi-currency reconciliation only
   * supports a single invoice per line.
   */
  @Test
  void moreThanOneForeignInvoice_returns400() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    invoice("inv-2", FOREIGN_CURRENCY);
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"), spec("inv-2", "sch-2"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).contains("single invoice"), message(resp));
    assertTrue(operationIds.isEmpty(), "no operation should be produced on rejection");
  }

  /**
   * A single foreign invoice with a blank scheduleId is rejected with 400 (the invoiceId is present,
   * so the foreign branch is entered, then per-invoice field validation fails).
   */
  @Test
  void singleForeignInvoice_missingScheduleId_returns400() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", null));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).contains("scheduleId"), message(resp));
  }

  /**
   * When the payment schedule cannot be loaded, the foreign path returns 404.
   */
  @Test
  void singleForeignInvoice_scheduleNotFound_returns404() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    // no schedule stubbed for "sch-missing" -> OBDal.get returns null
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-missing"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertEquals(404, resp.getHttpStatus());
  }

  /**
   * A foreign invoice whose schedule has zero outstanding is rejected: there is nothing to settle.
   */
  @Test
  void singleForeignInvoice_zeroOutstanding_returns400() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    schedule("sch-1", "0");
    FIN_BankStatementLine bsl = line("100", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).toLowerCase().contains("outstanding"), message(resp));
  }

  /**
   * A foreign invoice with a positive outstanding but a zero-amount statement line is rejected:
   * there is nothing on the bank side to reconcile.
   */
  @Test
  void singleForeignInvoice_zeroLineAmount_returns400() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", FOREIGN_CURRENCY);
    schedule("sch-1", "30");
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertEquals(400, resp.getHttpStatus());
    assertTrue(message(resp).toLowerCase().contains("zero"), message(resp));
  }

  /**
   * Contrast case: a same-currency invoice does NOT enter the foreign branch. With a zero-amount
   * line and a matching currency, the legacy path short-circuits (remaining {@code <=} tolerance)
   * and returns {@code null} (success/no-op) rather than the foreign "statement line is zero" 400.
   */
  @Test
  void sameCurrencyZeroLine_takesLegacyPath_returnsNull() throws Exception {
    FIN_FinancialAccount acc = account(ACCOUNT_CURRENCY);
    invoice("inv-1", ACCOUNT_CURRENCY);
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertNull(resp, "same-currency zero line should not hit the foreign rejection path");
    assertTrue(operationIds.isEmpty());
  }

  /**
   * An account with no declared currency keeps legacy behavior: the foreign branch is skipped even
   * when the invoice has a currency, so a zero line short-circuits to {@code null}.
   */
  @Test
  void accountWithoutCurrency_neverForeign_returnsNull() throws Exception {
    FIN_FinancialAccount acc = account(null);
    invoice("inv-1", FOREIGN_CURRENCY);
    FIN_BankStatementLine bsl = line("0", "0");
    JSONArray invoiceSpecs = specs(spec("inv-1", "sch-1"));
    List<String> operationIds = new ArrayList<>();

    NeoResponse resp = ReconciliationFlowSupport.createInvoicePayments(
        acc, bsl, invoiceSpecs, operationIds, TOLERANCE);

    assertNull(resp);
  }
}
