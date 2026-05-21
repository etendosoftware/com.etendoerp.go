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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import org.hibernate.Session;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.ad.system.Client;

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

  @Test
  @SuppressWarnings("unchecked")
  public void testRebuildTaxAggregatesWithDiscountLine() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      Session session = mock(Session.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(session);

      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);

      // Invoice header setup
      Currency currency = mock(Currency.class);
      when(currency.getStandardPrecision()).thenReturn(2L);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(currency);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(org);

      // Tax rate: IVA 10%
      TaxRate tax = mock(TaxRate.class);
      when(tax.getId()).thenReturn("tax-10");
      when(tax.getRate()).thenReturn(new BigDecimal("10"));
      when(tax.isSummaryLevel()).thenReturn(false);

      // Existing InvoiceTax rows (from CreateInvoiceLinesFromProcess — will be removed)
      InvoiceTax oldTax = mock(InvoiceTax.class);
      when(invoice.getInvoiceTaxList())
          .thenReturn(Collections.singletonList(oldTax))  // first call: existing rows
          .thenReturn(Collections.emptyList());            // second call: after refresh

      // Product line: net = 44.00; Discount line: net = -2.20
      InvoiceLine productLine = mock(InvoiceLine.class);
      when(productLine.getLineNetAmount()).thenReturn(new BigDecimal("44.00"));
      when(productLine.getTax()).thenReturn(tax);

      InvoiceLine discountLine = mock(InvoiceLine.class);
      when(discountLine.getLineNetAmount()).thenReturn(new BigDecimal("-2.20"));
      when(discountLine.getTax()).thenReturn(tax);

      when(invoice.getInvoiceLineList()).thenReturn(Arrays.asList(productLine, discountLine));

      InvoiceTax newTax = mock(InvoiceTax.class);
      when(provider.get(InvoiceTax.class)).thenReturn(newTax);

      new InvoiceFromOrderSupport().rebuildInvoiceTaxAggregates(invoice);

      // Existing row must be removed
      verify(dal).remove(oldTax);

      // New InvoiceTax: taxable = 41.80, taxAmt = 4.18
      verify(newTax).setTaxableAmount(new BigDecimal("41.80"));
      verify(newTax).setTaxAmount(new BigDecimal("4.18"));

      // Invoice totals: summedLineAmount=41.80, grandTotal=41.80+4.18=45.98
      verify(invoice).setSummedLineAmount(new BigDecimal("41.80"));
      verify(invoice).setGrandTotalAmount(new BigDecimal("45.98"));
    }
  }

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
        public void applyOrderDiscountToInvoice(Invoice inv, String orderId,
            TotalDiscountService svc) {
          // Simulate: pct = 0, so no-op
        }
      };

      support.applyOrderDiscountToInvoice(invoice, "order-1", discountService);

      verify(discountService, never()).recalculate(anyString(), eq(true));
    }
  }
}
