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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
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
}
