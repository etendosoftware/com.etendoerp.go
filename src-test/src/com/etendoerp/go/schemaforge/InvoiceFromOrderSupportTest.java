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
import java.util.Arrays;
import java.util.Collections;

import org.hibernate.Session;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
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
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;

/**
 * Unit tests for {@link InvoiceFromOrderSupport}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceFromOrderSupport
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

  // ── alignLineListPrices (ETP-5714 round 8): a copied line carries the list price the form stores ──

  private static InvoiceLine pricedLine(String unit, String list, String discount, String gross) {
    InvoiceLine line = mock(InvoiceLine.class);
    when(line.getId()).thenReturn("L-" + unit + "-" + list);
    when(line.getUnitPrice()).thenReturn(unit == null ? null : new BigDecimal(unit));
    when(line.getListPrice()).thenReturn(list == null ? null : new BigDecimal(list));
    when(line.getEtgoDiscount()).thenReturn(discount == null ? null : new BigDecimal(discount));
    when(line.getGrossUnitPrice()).thenReturn(gross == null ? null : new BigDecimal(gross));
    return line;
  }

  /**
   * The native line copy takes the prices of its source line exactly, so a source whose list price is
   * 0 (an order line an agent created with only a price) gives an invoice line with a price and a
   * list price of 0: the grid's "Precio" is the list price and showed 0,00. The form stores the price
   * the user typed as the list price when there is no discount.
   */
  @Test
  public void testAlignLineListPricesGivesAPricedLineWithoutListPriceItsPrice() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InvoiceLine zeroList = pricedLine("50", "0", null, null);
      InvoiceLine nullList = pricedLine("50", null, "0", "0");
      InvoiceLine hasList = pricedLine("45", "50", null, null);
      InvoiceLine discounted = pricedLine("45", "0", "10", null);
      InvoiceLine grossPriced = pricedLine("50", "0", null, "60.5");
      InvoiceLine free = pricedLine("0", "0", null, null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(
          Arrays.asList(zeroList, nullList, hasList, discounted, grossPriced, free));

      new InvoiceFromOrderSupport().alignLineListPrices(invoice, null);

      verify(zeroList).setListPrice(new BigDecimal("50"));
      verify(nullList).setListPrice(new BigDecimal("50"));
      verify(hasList, never()).setListPrice(any(BigDecimal.class));
      // a discounted copy: the form sends unit = list x (1 - d/100), so list = unit / (1 - d/100)
      ArgumentCaptor<BigDecimal> derived = ArgumentCaptor.forClass(BigDecimal.class);
      verify(discounted).setListPrice(derived.capture());
      assertEquals(0, new BigDecimal("50").compareTo(derived.getValue()));
      verify(grossPriced, never()).setListPrice(any(BigDecimal.class));
      verify(free, never()).setListPrice(any(BigDecimal.class));
      verify(dal).save(zeroList);
      verify(dal).flush();
    }
  }

  /** Only the lines of the import are touched; the draft's own lines are left alone, and nothing flushes when nothing changed. */
  @Test
  public void testAlignLineListPricesScopedToTheGivenLines() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InvoiceLine added = pricedLine("50", "0", null, null);
      InvoiceLine existing = pricedLine("70", "0", null, null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Arrays.asList(added, existing));

      new InvoiceFromOrderSupport().alignLineListPrices(invoice,
          Collections.singleton(added.getId()));

      verify(added).setListPrice(new BigDecimal("50"));
      verify(existing, never()).setListPrice(any(BigDecimal.class));

      InvoiceLine fine = pricedLine("50", "50", null, null);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(fine));
      org.mockito.Mockito.clearInvocations(dal);
      new InvoiceFromOrderSupport().alignLineListPrices(invoice, null);
      verify(dal, never()).flush();
    }
  }

  /** Cases the form could not have produced, or that leave nothing to derive: left alone. */
  @Test
  public void testAlignLineListPricesLeavesUnderivableLinesAlone() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InvoiceLine noPrice = pricedLine(null, "0", null, null);
      InvoiceLine fullDiscount = pricedLine("0", "0", "100", null);
      InvoiceLine over = pricedLine("5", "0", "120", null);
      InvoiceLine markup = pricedLine("5", "0", "-10", null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(
          Arrays.asList(noPrice, fullDiscount, over, markup));

      new InvoiceFromOrderSupport().alignLineListPrices(invoice, null);

      for (InvoiceLine line : Arrays.asList(noPrice, fullDiscount, over, markup)) {
        verify(line, never()).setListPrice(any(BigDecimal.class));
      }
      verify(dal, never()).flush();
    }
  }

  /** A return line (negative price) is aligned like any other: the list price keeps the sign. */
  @Test
  public void testAlignLineListPricesKeepsTheSignOfANegativePrice() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InvoiceLine negative = pricedLine("-50", "0", null, null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(negative));

      new InvoiceFromOrderSupport().alignLineListPrices(invoice, null);

      verify(negative).setListPrice(new BigDecimal("-50"));
    }
  }

  /** An empty scope is a scope: nothing is touched, nothing flushed. */
  @Test
  public void testAlignLineListPricesWithAnEmptyScopeTouchesNothing() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      InvoiceLine line = pricedLine("50", "0", null, null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(line));

      new InvoiceFromOrderSupport().alignLineListPrices(invoice, Collections.emptySet());

      verify(line, never()).setListPrice(any(BigDecimal.class));
      verify(dal, never()).flush();
    }
  }

  /** A failing flush is the caller's failure (the import rolls back), never swallowed here. */
  @Test(expected = IllegalStateException.class)
  public void testAlignLineListPricesPropagatesAFailingFlush() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      org.mockito.Mockito.doThrow(new IllegalStateException("flush failed")).when(dal).flush();
      InvoiceLine line = pricedLine("50", "0", null, null);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(line));

      new InvoiceFromOrderSupport().alignLineListPrices(invoice, null);
    }
  }

  // ── copyLineDiscountsFromOrder (ETP-4006 / ETP-4780) ─────────────────────
  //
  // Moved here from CreateDraftInvoiceHandlerTest so both CreateDraftInvoiceHandler
  // (sales) and CreatePurchaseInvoiceHandler (purchase) share coverage of the
  // discount-copy logic itself; each handler's test class separately verifies
  // that it delegates to getSupport().copyLineDiscountsFromOrder(...).

  /**
   * Copies the source order line's discount onto an invoice line that has none yet,
   * skips a source line with a zero discount, and never overwrites an invoice line
   * that already carries a non-zero {@code EM_Etgo_Discount} (set explicitly elsewhere).
   * Exercises the exact regression scenario from ETP-4006, now against the shared
   * {@link InvoiceFromOrderSupport} implementation reused by ETP-4780.
   */
  @Test
  public void testCopyLineDiscountsFromOrderCopiesOnlyMissingInvoiceDiscounts() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      OrderLine discountedOrderLine = mock(OrderLine.class);
      when(discountedOrderLine.getDiscount()).thenReturn(new BigDecimal("10"));

      InvoiceLine emptyInvoiceLine = mock(InvoiceLine.class);
      when(emptyInvoiceLine.getSalesOrderLine()).thenReturn(discountedOrderLine);
      when(emptyInvoiceLine.getEtgoDiscount()).thenReturn(null);

      OrderLine zeroDiscountOrderLine = mock(OrderLine.class);
      when(zeroDiscountOrderLine.getDiscount()).thenReturn(BigDecimal.ZERO);

      InvoiceLine zeroDiscountInvoiceLine = mock(InvoiceLine.class);
      when(zeroDiscountInvoiceLine.getSalesOrderLine()).thenReturn(zeroDiscountOrderLine);
      when(zeroDiscountInvoiceLine.getEtgoDiscount()).thenReturn(null);

      OrderLine alreadyCopiedOrderLine = mock(OrderLine.class);
      when(alreadyCopiedOrderLine.getDiscount()).thenReturn(new BigDecimal("7"));

      InvoiceLine existingDiscountInvoiceLine = mock(InvoiceLine.class);
      when(existingDiscountInvoiceLine.getSalesOrderLine()).thenReturn(alreadyCopiedOrderLine);
      when(existingDiscountInvoiceLine.getEtgoDiscount()).thenReturn(new BigDecimal("3"));

      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Arrays.asList(
          emptyInvoiceLine,
          zeroDiscountInvoiceLine,
          existingDiscountInvoiceLine));

      new InvoiceFromOrderSupport().copyLineDiscountsFromOrder(invoice);

      verify(emptyInvoiceLine).setEtgoDiscount(new BigDecimal("10"));
      verify(dal).save(emptyInvoiceLine);
      verify(zeroDiscountInvoiceLine, never()).setEtgoDiscount(any(BigDecimal.class));
      verify(existingDiscountInvoiceLine, never()).setEtgoDiscount(any(BigDecimal.class));
      verify(dal).flush();
    }
  }

  /**
   * No source order line at all (invoice line not linked to any order, e.g. a manually
   * added line) must be skipped without throwing, and the method must not flush when
   * nothing was copied.
   */
  @Test
  public void testCopyLineDiscountsFromOrderSkipsLineWithNoSourceOrderLine() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      InvoiceLine orphanLine = mock(InvoiceLine.class);
      when(orphanLine.getSalesOrderLine()).thenReturn(null);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Collections.singletonList(orphanLine));

      new InvoiceFromOrderSupport().copyLineDiscountsFromOrder(invoice);

      verify(orphanLine, never()).setEtgoDiscount(any(BigDecimal.class));
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  /**
   * ETP-5713: the line import adds lines to an invoice that already holds others. The copy is then
   * restricted to the new lines: a line outside the set keeps its discount untouched (here, none),
   * even when its source order line carries one, and {@code null} still means every line.
   */
  @Test
  public void testCopyLineDiscountsFromOrderScopedToTheGivenLinesLeavesTheOthersAlone() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      OrderLine discounted = mock(OrderLine.class);
      when(discounted.getDiscount()).thenReturn(new BigDecimal("10"));

      InvoiceLine imported = mock(InvoiceLine.class);
      when(imported.getId()).thenReturn("new-line");
      when(imported.getSalesOrderLine()).thenReturn(discounted);
      when(imported.getEtgoDiscount()).thenReturn(null);

      InvoiceLine alreadyThere = mock(InvoiceLine.class);
      when(alreadyThere.getId()).thenReturn("old-line");
      when(alreadyThere.getSalesOrderLine()).thenReturn(discounted);
      when(alreadyThere.getEtgoDiscount()).thenReturn(null);

      Invoice invoice = mock(Invoice.class);
      when(invoice.getInvoiceLineList()).thenReturn(Arrays.asList(alreadyThere, imported));

      new InvoiceFromOrderSupport().copyLineDiscountsFromOrder(invoice,
          Collections.singleton("new-line"));

      verify(imported).setEtgoDiscount(new BigDecimal("10"));
      verify(alreadyThere, never()).setEtgoDiscount(any(BigDecimal.class));
      verify(dal).flush();

      new InvoiceFromOrderSupport().copyLineDiscountsFromOrder(invoice, null);
      verify(alreadyThere).setEtgoDiscount(new BigDecimal("10"));
    }
  }

  /**
   * ETP-5713: the gross repair of the line import is scoped to the lines it added. A deliberate
   * zero line or a negative line already in the draft keeps the gross it has; only a new line with
   * no gross gets one, and {@code null} still means every line.
   */
  @Test
  public void testEnsureLineGrossAmountsScopedLeavesExistingZeroAndNegativeLinesAlone() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);

      InvoiceLine zero = invoiceLineWithGross("zero", BigDecimal.ZERO);
      InvoiceLine negative = invoiceLineWithGross("negative", new BigDecimal("-5"));
      InvoiceLine added = invoiceLineWithGross("added", BigDecimal.ZERO);
      when(added.getInvoicedQuantity()).thenReturn(new BigDecimal("2"));
      when(added.getGrossUnitPrice()).thenReturn(new BigDecimal("3"));
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getInvoiceLineList()).thenReturn(Arrays.asList(zero, negative, added));

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice,
          Collections.singleton("added"));

      verify(added).setGrossAmount(new BigDecimal("6.00"));
      verify(zero, never()).setGrossAmount(any(BigDecimal.class));
      verify(negative, never()).setGrossAmount(any(BigDecimal.class));

      new InvoiceFromOrderSupport().ensureLineGrossAmounts(invoice, null);
      verify(zero).setGrossAmount(any(BigDecimal.class));
    }
  }

  private static InvoiceLine invoiceLineWithGross(String id, BigDecimal gross) {
    InvoiceLine line = mock(InvoiceLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getGrossAmount()).thenReturn(gross);
    when(line.getInvoicedQuantity()).thenReturn(BigDecimal.ONE);
    when(line.getLineNetAmount()).thenReturn(BigDecimal.ONE);
    return line;
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

  // ── propagateOrderRateToInvoice (ETP-5840: recomputed, never copied) ───────

  /**
   * The generated invoice's rate is recomputed from the INVOICE's own reference date (its accounting
   * date, the invoice rate type) — the order's rate is never read — and the exchange-rates tab is
   * then synced with {@code rateCleared = true}, so a rate left null (none configured for that date)
   * clears the tab instead of keeping a stale row. Same for a sales and a purchase invoice.
   */
  @Test
  public void propagateOrderRateToInvoice_recomputesOnTheInvoiceDateAndSyncsTheTab() {
    for (boolean sales : new boolean[] { true, false }) {
      Order order = mock(Order.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn("inv-1");
      when(invoice.isSalesTransaction()).thenReturn(sales);
      java.util.List<Object> types = new java.util.ArrayList<>();

      try (MockedConstruction<DocumentCurrencyRate> rates = Mockito.mockConstruction(
               DocumentCurrencyRate.class, (rate, context) -> types.add(context.arguments().get(0)));
           MockedStatic<AbstractInvoiceHeaderHandler> sync =
               Mockito.mockStatic(AbstractInvoiceHeaderHandler.class)) {

        new InvoiceFromOrderSupport().propagateOrderRateToInvoice(order, invoice);

        assertEquals(1, rates.constructed().size());
        verify(rates.constructed().get(0)).recompute(invoice);
        DocumentCurrencyRate.RateType type = (DocumentCurrencyRate.RateType) types.get(0);
        assertEquals("sales=" + sales, Invoice.PROPERTY_ACCOUNTINGDATE,
            type.referenceDateProperty());
        assertEquals("sales=" + sales, Invoice.PROPERTY_ETGOCURRENCYRATE, type.rateProperty());
        sync.verify(() -> AbstractInvoiceHeaderHandler
            .autoCreateOrUpdateConversionRateDocument("inv-1", true));
        verify(order, never()).getETGOCurrencyRate();
      }
    }
  }

  /** A null invoice is a no-op: nothing recomputed, no tab sync. */
  @Test
  public void propagateOrderRateToInvoice_nullInvoice_isNoOp() {
    try (MockedConstruction<DocumentCurrencyRate> rates =
             Mockito.mockConstruction(DocumentCurrencyRate.class);
         MockedStatic<AbstractInvoiceHeaderHandler> sync =
             Mockito.mockStatic(AbstractInvoiceHeaderHandler.class)) {

      new InvoiceFromOrderSupport().propagateOrderRateToInvoice(mock(Order.class), null);

      assertEquals(0, rates.constructed().size());
      sync.verifyNoInteractions();
    }
  }
}
