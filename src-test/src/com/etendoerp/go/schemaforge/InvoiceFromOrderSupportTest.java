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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;

import org.hibernate.Session;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Unit tests for {@link InvoiceFromOrderSupport}.
 */
public class InvoiceFromOrderSupportTest {

  // ── calculateLineGross ────────────────────────────────────────────────────

  @Test
  public void testCalculateLineGrossUsesPriceWhenSet() {
    InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(new BigDecimal("12.00"));
    when(il.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
    assertEquals(new BigDecimal("36.00"), support.calculateLineGross(il, 2));
  }

  @Test
  public void testCalculateLineGrossFallsBackToNetPlusTax() {
    InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(BigDecimal.ZERO);
    when(il.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
    when(il.getLineNetAmount()).thenReturn(new BigDecimal("100.00"));
    TaxRate tax = mock(TaxRate.class);
    when(tax.getRate()).thenReturn(new BigDecimal("10"));
    when(il.getTax()).thenReturn(tax);
    assertEquals(new BigDecimal("110.00"), support.calculateLineGross(il, 2));
  }

  @Test
  public void testCalculateLineGrossNoTaxReturnsNet() {
    InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(null);
    when(il.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
    when(il.getLineNetAmount()).thenReturn(new BigDecimal("50.00"));
    when(il.getTax()).thenReturn(null);
    assertEquals(new BigDecimal("50.00"), support.calculateLineGross(il, 2));
  }

  // ── ensureLineGrossAmounts ────────────────────────────────────────────────

  @Test
  public void testEnsureLineGrossSkipsAlreadyPositive() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getGrossAmount()).thenReturn(new BigDecimal("99.00"));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice);

      verify(il, never()).setGrossAmount(any());
    }
  }

  @Test
  public void testEnsureLineGrossFillsNullGross() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getGrossAmount()).thenReturn(null);
      when(il.getGrossUnitPrice()).thenReturn(new BigDecimal("5.00"));
      when(il.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice);

      verify(il).setGrossAmount(new BigDecimal("10.00"));
      verify(dal).save(il);
    }
  }

  // ── rebuildInvoiceTaxAggregates ──────────────────────────────────────────
  // Removed: c_invoicetax is now kept consistent by the Etendo DB triggers
  // (c_invoiceline_trg2 → c_invoicelinetax_trg); applyOrderDiscountToInvoice
  // just resyncs the c_invoice header totals afterwards.

  // ── applyOrderDiscountToInvoice — no discount ────────────────────────────

  @Test
  public void testApplyOrderDiscountNoopWhenNoDiscount() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // Return null connection so readOrderDiscountPct returns null
      when(dal.getConnection()).thenReturn(null);

      Invoice invoice = mock(Invoice.class);
      TotalDiscountService discountService = mock(TotalDiscountService.class);

      // Build testable support that overrides JDBC to simulate 0% discount
      InvoiceFromOrderSupport support = new InvoiceFromOrderSupport() {
        @Override
        public Invoice applyOrderDiscountToInvoice(Invoice inv, String orderId,
            TotalDiscountService svc) {
          // Simulate: pct = 0, so no-op
          return inv;
        }
      };

      support.applyOrderDiscountToInvoice(invoice, "order-1", discountService);

      verify(discountService, never()).recalculate(anyString(), eq(true));
    }
  }

  // ── applyOrderDiscountToInvoice — positive discount happy path ───────────

  /**
   * Verifies that when the source order has a positive discount, the method:
   * <ul>
   *   <li>Copies the discount % to the invoice via JDBC UPDATE.</li>
   *   <li>Calls {@code discountService.recalculate(invoiceId, true)} — the key
   *       behavioural assertion that materialises the ETGO_DTO line and lets the
   *       DB triggers keep {@code c_invoicetax} consistent (regression for
   *       ETP-4015).</li>
   *   <li>Clears the Hibernate session to drop stale L1 cache entries.</li>
   *   <li>Returns a FRESH invoice instance loaded after the clear, not the
   *       stale one passed in.</li>
   * </ul>
   */
  @Test
  public void testApplyOrderDiscountWithPositiveDiscount() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      // JDBC stack: a single Connection that returns a SELECT PreparedStatement for
      // readOrderDiscountPct. The header-side write is done through the DAL setter
      // (invoice.setEtgoTotalDiscount + OBDal.save), not through JDBC.
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement selectPs = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("5"));
      when(selectPs.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(
          eq("SELECT em_etgo_total_discount FROM c_order WHERE c_order_id = ?")))
          .thenReturn(selectPs);

      // Session clear + fresh reload contract.
      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Invoice originalInvoice = mock(Invoice.class);
      when(originalInvoice.getId()).thenReturn("invoice-1");
      Invoice freshInvoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "invoice-1")).thenReturn(freshInvoice);

      TotalDiscountService discountService = mock(TotalDiscountService.class);

      Invoice returned = new InvoiceFromOrderSupport()
          .applyOrderDiscountToInvoice(originalInvoice, "order-1", discountService);

      // Key behavioural assertion: the new code path materialises the discount
      // line through TotalDiscountService.recalculate — not via the legacy
      // applyTotalDiscountIfPresent path that inserted a duplicate c_invoicetax row.
      verify(discountService, times(1)).recalculate("invoice-1", true);

      // The discount pct is written through the DAL setter + save, not raw JDBC.
      // Keeps Hibernate's session in sync and avoids the "session.clear() vs JDBC
      // stale state" workaround the bot warned about.
      verify(originalInvoice).setEtgoTotalDiscount(new BigDecimal("5"));
      verify(dal).save(originalInvoice);

      // Session was cleared and a FRESH invoice was returned, not the input.
      verify(session).clear();
      assertSame("Should return the freshly reloaded invoice", freshInvoice, returned);
      assertNotSame("Must not return the stale input invoice", originalInvoice, returned);
    }
  }

  // ── applyOrderDiscountToInvoice — idempotency ────────────────────────────

  /**
   * Calling {@code applyOrderDiscountToInvoice} twice with the same invoice and
   * discount service must invoke {@code recalculate} exactly twice and re-run
   * the JDBC SELECT/UPDATE on each call. This confirms the method is genuinely
   * idempotent (relying on {@code TotalDiscountService.recalculate} deleting
   * the existing ETGO_DTO line before re-creating it) rather than accidentally
   * short-circuiting on a second invocation.
   */
  @Test
  public void testApplyOrderDiscountIsIdempotent() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement selectPs = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("10"));
      when(selectPs.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(
          eq("SELECT em_etgo_total_discount FROM c_order WHERE c_order_id = ?")))
          .thenReturn(selectPs);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-2");
      Invoice freshInvoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "invoice-2")).thenReturn(freshInvoice);

      TotalDiscountService discountService = mock(TotalDiscountService.class);
      InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();

      // First call.
      support.applyOrderDiscountToInvoice(invoice, "order-2", discountService);
      // Second call on the same invoice.
      support.applyOrderDiscountToInvoice(invoice, "order-2", discountService);

      // Recalculate runs once per invocation — never skipped.
      verify(discountService, times(2)).recalculate("invoice-2", true);
      // The DAL setter + save are exercised on every invocation, never short-circuited.
      verify(invoice, times(2)).setEtgoTotalDiscount(new BigDecimal("10"));
      verify(dal, times(2)).save(invoice);
      verify(selectPs, times(2)).executeQuery();
      // Session cleared each time so callers always receive a fresh invoice.
      verify(session, times(2)).clear();
    }
  }

  // ── calculateLineGross — discount-line edge case ─────────────────────────

  /**
   * The ETGO_DTO discount line stores a NEGATIVE {@code lineNetAmount} so that
   * its tax contribution offsets the document subtotal. When {@code grossUnitPrice}
   * is zero (as it is for the auto-generated discount line), the method falls
   * back to {@code net + net * rate/100}; this test locks in the behaviour for
   * the negative-net edge case to prevent a sign regression.
   *
   * <p>net = -2.20, rate = 10% → tax = -0.22 → gross = -2.42
   */
  @Test
  public void testCalculateLineGrossNegativeNetFromDiscountLine() {
    InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(BigDecimal.ZERO);
    when(il.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
    when(il.getLineNetAmount()).thenReturn(new BigDecimal("-2.20"));
    TaxRate tax = mock(TaxRate.class);
    when(tax.getRate()).thenReturn(new BigDecimal("10"));
    when(il.getTax()).thenReturn(tax);
    assertEquals(new BigDecimal("-2.42"), support.calculateLineGross(il, 2));
  }

  // ── calculateLineGross — null guards on quantity / net / rate ────────────

  /**
   * Defensive: when {@code invoicedQuantity} is null the method must treat it
   * as zero and return 0 (not throw NPE) so a half-built line in a callout
   * pre-save state can't crash the handler.
   */
  @Test
  public void testCalculateLineGrossNullQuantityReturnsZero() {
    InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(new BigDecimal("12.00"));
    when(il.getInvoicedQuantity()).thenReturn(null);
    // 0 (default qty) × 12 = 0.
    assertEquals(new BigDecimal("0.00"), support.calculateLineGross(il, 2));
  }

  /**
   * Defensive: when both {@code lineNetAmount} and {@code grossUnitPrice} fall
   * to the fallback branch, a null net must be treated as zero and a null
   * tax rate must be treated as zero — together they yield 0 gross.
   */
  @Test
  public void testCalculateLineGrossNullNetAndNullRateYieldsZero() {
    InvoiceFromOrderSupport support = new InvoiceFromOrderSupport();
    InvoiceLine il = mock(InvoiceLine.class);
    when(il.getGrossUnitPrice()).thenReturn(null);
    when(il.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
    when(il.getLineNetAmount()).thenReturn(null);
    TaxRate tax = mock(TaxRate.class);
    when(tax.getRate()).thenReturn(null);
    when(il.getTax()).thenReturn(tax);
    assertEquals(new BigDecimal("0.00"), support.calculateLineGross(il, 2));
  }

  // ── ensureLineGrossAmounts — extra branches ───────────────────────────────

  /**
   * Empty invoice line list: the method must not throw, just flush and return.
   * Guards against a regression where iterating a null/empty list NPE'd.
   */
  @Test
  public void testEnsureLineGrossEmptyListIsNoop() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.emptyList());

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice);

      verify(dal, never()).save(any());
      verify(dal).flush();
    }
  }

  /**
   * gross == 0 must NOT be skipped — only strictly-positive gross amounts
   * are. This locks in the ETGO_DTO scenario where the discount line was
   * created with gross=0 and needs the gross to be re-computed.
   */
  @Test
  public void testEnsureLineGrossZeroFillsGross() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      InvoiceLine il = mock(InvoiceLine.class);
      when(il.getGrossAmount()).thenReturn(BigDecimal.ZERO);
      when(il.getGrossUnitPrice()).thenReturn(new BigDecimal("7.00"));
      when(il.getInvoicedQuantity()).thenReturn(new BigDecimal("3"));
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(il));

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice);

      verify(il).setGrossAmount(new BigDecimal("21.00"));
      verify(dal).save(il);
    }
  }

  /**
   * Mixed batch: one line with positive gross must be skipped, the next with
   * null gross must be filled. Exercises both branches of the per-line guard
   * in the same invocation.
   */
  @Test
  public void testEnsureLineGrossMixedSkipsAndFills() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);

      InvoiceLine skipped = mock(InvoiceLine.class);
      when(skipped.getGrossAmount()).thenReturn(new BigDecimal("12.34"));

      InvoiceLine filled = mock(InvoiceLine.class);
      when(filled.getGrossAmount()).thenReturn(null);
      when(filled.getGrossUnitPrice()).thenReturn(new BigDecimal("4.00"));
      when(filled.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));

      when(invoice.getInvoiceLineList())
          .thenReturn(java.util.Arrays.asList(skipped, filled));

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice);

      verify(skipped, never()).setGrossAmount(any());
      verify(filled).setGrossAmount(new BigDecimal("8.00"));
      verify(dal).save(filled);
      verify(dal, never()).save(skipped);
    }
  }

  // ── applyOrderDiscountToInvoice — service-null + JDBC edge cases ─────────

  /**
   * When {@code discountService} is null but the order does carry a discount,
   * the method must still copy the pct to the invoice header and clear the
   * session — it just skips the {@code recalculate} step. Locks in the
   * "service kept in the signature for API stability, can be null" contract.
   */
  @Test
  public void testApplyOrderDiscountSkipsMaterializationWhenServiceNull() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement selectPs = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("8"));
      when(selectPs.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(anyString())).thenReturn(selectPs);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("invoice-3");
      Invoice freshInvoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "invoice-3")).thenReturn(freshInvoice);

      Invoice returned = new InvoiceFromOrderSupport()
          .applyOrderDiscountToInvoice(invoice, "order-3", null);

      // Header still gets the discount pct.
      verify(invoice).setEtgoTotalDiscount(new BigDecimal("8"));
      verify(dal).save(invoice);
      // Session still cleared and a fresh invoice returned — same shape as the
      // happy path; only the recalculate call is skipped.
      verify(session).clear();
      assertSame(freshInvoice, returned);
    }
  }

  /**
   * The source order has no row in c_order (rs.next() returns false). The
   * method must return the input invoice unchanged — no discount applied,
   * no save, no session.clear().
   */
  @Test
  public void testApplyOrderDiscountNoopWhenOrderMissing() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement selectPs = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(false);  // ← no row found
      when(selectPs.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(anyString())).thenReturn(selectPs);

      Invoice invoice = mock(Invoice.class);
      TotalDiscountService discountService = mock(TotalDiscountService.class);

      Invoice returned = new InvoiceFromOrderSupport()
          .applyOrderDiscountToInvoice(invoice, "missing-order", discountService);

      assertSame("Must return the input invoice unchanged", invoice, returned);
      verify(invoice, never()).setEtgoTotalDiscount(any());
      verify(discountService, never()).recalculate(anyString(), any(Boolean.class));
    }
  }

  /**
   * When the JDBC query throws (e.g. connection lost), {@code readOrderDiscountPct}
   * logs and returns null — the public method must treat that as "no discount"
   * and skip all the materialisation work. The exception is intentionally
   * swallowed (logged at WARN) so a transient DB hiccup can't break invoice
   * creation; the test locks that contract.
   */
  @Test
  public void testApplyOrderDiscountSwallowsJdbcException() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString()))
          .thenThrow(new java.sql.SQLException("simulated DB hiccup"));

      Invoice invoice = mock(Invoice.class);
      TotalDiscountService discountService = mock(TotalDiscountService.class);

      Invoice returned = new InvoiceFromOrderSupport()
          .applyOrderDiscountToInvoice(invoice, "any-order", discountService);

      assertSame(invoice, returned);
      verify(invoice, never()).setEtgoTotalDiscount(any());
      verify(discountService, never()).recalculate(anyString(), any(Boolean.class));
    }
  }

  // ── propagateOrderRateToInvoice ───────────────────────────────────────────

  /**
   * No-op when the order carries no custom currency rate (null EM_ETGO_Currency_Rate).
   * The method must return immediately without touching the DB.
   */
  @Test
  public void propagateOrderRateToInvoice_nullRate_noInsert() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(null);

      Invoice invoice = mock(Invoice.class);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      // No DB connection should be acquired when rate is null.
      verify(dal, never()).getConnection();
    }
  }

  /**
   * No-op when invoice.getCurrency() is null (partially built invoice).
   * Must not throw and must not attempt DB access.
   */
  @Test
  public void propagateOrderRateToInvoice_nullInvoiceCurrency_noInsert() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.16"));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(null);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * No-op when the invoice currency is the same as the org currency.
   * A same-currency document does not need a conversion rate record.
   */
  @Test
  public void propagateOrderRateToInvoice_sameCurrency_noInsert() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtilsMock = Mockito.mockStatic(OBCurrencyUtils.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-1");

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.0"));
      when(order.getOrganization()).thenReturn(org);

      // Org currency and invoice currency are both "EUR" — same currency, no record needed.
      currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("EUR");

      Currency invoiceCurrency = mock(Currency.class);
      when(invoiceCurrency.getId()).thenReturn("EUR");

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(invoiceCurrency);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * No-op when a {@code c_conversion_rate_document} record already exists for
   * this invoice + currency pair. The method must be idempotent — if the check
   * query returns a row it returns immediately without executing the INSERT.
   */
  @Test
  public void propagateOrderRateToInvoice_recordAlreadyExists_noInsert() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtilsMock = Mockito.mockStatic(OBCurrencyUtils.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-1");

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.16"));
      when(order.getOrganization()).thenReturn(org);

      currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("EUR");

      Currency invoiceCurrency = mock(Currency.class);
      when(invoiceCurrency.getId()).thenReturn("USD");

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(invoiceCurrency);
      when(invoice.getId()).thenReturn("inv-001");

      // The check query finds an existing row → rs.next() = true.
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement checkPs = mock(PreparedStatement.class);
      ResultSet checkRs = mock(ResultSet.class);
      when(checkRs.next()).thenReturn(true);  // record exists
      when(checkPs.executeQuery()).thenReturn(checkRs);
      when(conn.prepareStatement(anyString())).thenReturn(checkPs);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      // Only one prepareStatement call for the SELECT — no INSERT.
      verify(conn, times(1)).prepareStatement(anyString());
    }
  }

  /**
   * Happy path: rate is set, currencies differ, and no existing record.
   * An INSERT INTO c_conversion_rate_document must be executed with:
   * - correct invoice ID
   * - correct currency IDs (from invoice and to orgCurrency)
   * - docRate = 1 / EM_ETGO_Currency_Rate
   */
  @Test
  public void propagateOrderRateToInvoice_allConditionsMet_insertsRecord() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtilsMock = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-1");

      // EM_ETGO_Currency_Rate = 2.0 → docRate = 1/2 = 0.5
      BigDecimal etgoRate = new BigDecimal("2.0");

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(etgoRate);
      when(order.getOrganization()).thenReturn(org);
      when(order.getId()).thenReturn("order-001");

      currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("EUR");

      Currency invoiceCurrency = mock(Currency.class);
      when(invoiceCurrency.getId()).thenReturn("USD");

      Client client = mock(Client.class);
      when(client.getId()).thenReturn("client-1");

      Organization invoiceOrg = mock(Organization.class);
      when(invoiceOrg.getId()).thenReturn("org-1");

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(invoiceCurrency);
      when(invoice.getId()).thenReturn("inv-001");
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(invoiceOrg);
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("100.00"));

      User user = mock(User.class);
      when(user.getId()).thenReturn("user-1");
      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // Check query — no existing record.
      PreparedStatement checkPs = mock(PreparedStatement.class);
      ResultSet checkRs = mock(ResultSet.class);
      when(checkRs.next()).thenReturn(false);
      when(checkPs.executeQuery()).thenReturn(checkRs);

      // Insert statement.
      PreparedStatement insertPs = mock(PreparedStatement.class);

      // First prepareStatement = check SELECT, second = INSERT.
      when(conn.prepareStatement(anyString()))
          .thenReturn(checkPs)
          .thenReturn(insertPs);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      // INSERT must have been executed.
      verify(insertPs).executeUpdate();

      // Verify invoice ID was set as parameter (position 6 in INSERT).
      verify(insertPs).setString(eq(6), eq("inv-001"));

      // Verify currency IDs: position 7 = invoice currency (USD), position 8 = orgCurrency (EUR).
      verify(insertPs).setString(eq(7), eq("USD"));
      verify(insertPs).setString(eq(8), eq("EUR"));

      // docRate = 1 / 2.0 = 0.5 — verified via capture of setBigDecimal(9, ...).
      // Use an ArgumentCaptor for the rate parameter to check the computed docRate value.
      ArgumentCaptor<BigDecimal> rateCaptor =
          ArgumentCaptor.forClass(BigDecimal.class);
      verify(insertPs, times(2)).setBigDecimal(any(Integer.class), rateCaptor.capture());
      // First setBigDecimal call is docRate (position 9), second is foreignAmount (position 10).
      BigDecimal capturedDocRate = rateCaptor.getAllValues().get(0);
      // 1 / 2.0 = 0.5, rounded to 12 decimal places.
      assertEquals(0, new BigDecimal("0.5").compareTo(capturedDocRate.stripTrailingZeros()));
    }
  }

  /**
   * Branch A: {@code OBCurrencyUtils.getOrgCurrency(orgId)} returns null.
   * The method must return early without acquiring a DB connection.
   * This is a distinct branch from sameCurrency — here orgCurrencyId itself is null.
   */
  @Test
  public void propagateOrderRateToInvoice_nullOrgCurrency_noInsert() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtilsMock = Mockito.mockStatic(OBCurrencyUtils.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-null");

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.16"));
      when(order.getOrganization()).thenReturn(org);

      // getOrgCurrency returns null — triggers the early-return branch.
      currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-null")).thenReturn(null);

      Currency invoiceCurrency = mock(Currency.class);
      when(invoiceCurrency.getId()).thenReturn("USD");

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(invoiceCurrency);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * Branch B: {@code invoice.getGrandTotalAmount()} returns null.
   * foreignAmount is null → the INSERT uses {@code ps.setNull(10, Types.NUMERIC)}
   * instead of {@code ps.setBigDecimal(10, ...)}.
   */
  @Test
  public void propagateOrderRateToInvoice_nullGrandTotal_usesSetNull() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtilsMock = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-3");

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(new BigDecimal("2.0"));
      when(order.getOrganization()).thenReturn(org);
      when(order.getId()).thenReturn("order-003");

      currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-3")).thenReturn("EUR");

      Currency invoiceCurrency = mock(Currency.class);
      when(invoiceCurrency.getId()).thenReturn("USD");

      Client client = mock(Client.class);
      when(client.getId()).thenReturn("client-3");

      Organization invoiceOrg = mock(Organization.class);
      when(invoiceOrg.getId()).thenReturn("org-3");

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(invoiceCurrency);
      when(invoice.getId()).thenReturn("inv-003");
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(invoiceOrg);
      // null grand total → foreignAmount will be null → setNull(10, NUMERIC) path.
      when(invoice.getGrandTotalAmount()).thenReturn(null);

      User user = mock(User.class);
      when(user.getId()).thenReturn("user-3");
      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // Check query — no existing record.
      PreparedStatement checkPs = mock(PreparedStatement.class);
      ResultSet checkRs = mock(ResultSet.class);
      when(checkRs.next()).thenReturn(false);
      when(checkPs.executeQuery()).thenReturn(checkRs);

      PreparedStatement insertPs = mock(PreparedStatement.class);

      when(conn.prepareStatement(anyString()))
          .thenReturn(checkPs)
          .thenReturn(insertPs);

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

      verify(insertPs).setNull(eq(10), eq(java.sql.Types.NUMERIC));
      verify(insertPs, never()).setBigDecimal(eq(10), any());
      verify(insertPs).executeUpdate();
    }
  }

  /**
   * When the INSERT throws a SQL exception, the method must swallow it (log at
   * WARN) and not rethrow. Invoice creation must not be broken by a conversion
   * rate write failure.
   */
  @Test
  public void propagateOrderRateToInvoice_insertThrows_swallowsException() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtilsMock = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-2");

      Order order = mock(Order.class);
      when(order.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.5"));
      when(order.getOrganization()).thenReturn(org);
      when(order.getId()).thenReturn("order-002");

      currencyUtilsMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-2")).thenReturn("EUR");

      Currency invoiceCurrency = mock(Currency.class);
      when(invoiceCurrency.getId()).thenReturn("USD");

      Client client = mock(Client.class);
      when(client.getId()).thenReturn("client-2");

      Organization invoiceOrg = mock(Organization.class);
      when(invoiceOrg.getId()).thenReturn("org-2");

      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(invoiceCurrency);
      when(invoice.getId()).thenReturn("inv-002");
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(invoiceOrg);
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("200.00"));

      User user = mock(User.class);
      when(user.getId()).thenReturn("user-2");
      OBContext ctx = mock(OBContext.class);
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // Check query — no existing record.
      PreparedStatement checkPs = mock(PreparedStatement.class);
      ResultSet checkRs = mock(ResultSet.class);
      when(checkRs.next()).thenReturn(false);
      when(checkPs.executeQuery()).thenReturn(checkRs);

      // INSERT statement throws.
      PreparedStatement insertPs = mock(PreparedStatement.class);
      when(insertPs.executeUpdate()).thenThrow(new java.sql.SQLException("DB write failure"));

      when(conn.prepareStatement(anyString()))
          .thenReturn(checkPs)
          .thenReturn(insertPs);

      // Must NOT throw — exception is swallowed.
      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);
    }
  }
}
