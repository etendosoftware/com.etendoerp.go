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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link InvoiceExchangeRateValidator#checkRateForCompletion(Invoice)}.
 *
 * <p>Covers every early-exit guard (null invoice/currency/organization, no functional currency,
 * same currency) and the three rate-resolution outcomes (document rate present, general rate
 * present, and no rate at all → blocking message).
 */
public class InvoiceExchangeRateValidatorTest {

  private static final String NO_RATE_MSG = "No conversion rate found for completion:";

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Builds an invoice with a currency (id {@code fromId}) and a non-null organization (id orgId).
   */
  private static Invoice invoiceWith(String fromId, String orgId, String isoCode) {
    Currency from = mock(Currency.class);
    when(from.getId()).thenReturn(fromId);
    when(from.getISOCode()).thenReturn(isoCode);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCurrency()).thenReturn(from);
    when(invoice.getOrganization()).thenReturn(org);
    return invoice;
  }

  /** Stubs the document-rate criteria to return the given ConversionRateDoc rows. */
  @SuppressWarnings("unchecked")
  private static void stubDocRateCriteria(OBDal dal, java.util.List<ConversionRateDoc> rows) {
    OBCriteria<ConversionRateDoc> crit = mock(OBCriteria.class);
    when(dal.createCriteria(ConversionRateDoc.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(rows);
  }

  // ── guard clauses ─────────────────────────────────────────────────────────

  @Test
  public void testNullInvoiceReturnsNull() {
    assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(null));
  }

  @Test
  public void testNullCurrencyReturnsNull() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCurrency()).thenReturn(null);
    assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
  }

  @Test
  public void testNullOrganizationReturnsNull() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCurrency()).thenReturn(mock(Currency.class));
    when(invoice.getOrganization()).thenReturn(null);
    assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
  }

  @Test
  public void testNullOrgCurrencyReturnsNull() {
    Invoice invoice = invoiceWith("USD", "ORG1", "USD");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn(null);

      assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
      obCtx.verify(() -> OBContext.setAdminMode(true));
      obCtx.verify(OBContext::restorePreviousMode);
    }
  }

  @Test
  public void testSameCurrencyReturnsNull() {
    Invoice invoice = invoiceWith("EUR", "ORG1", "EUR");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");

      assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
    }
  }

  // ── rate resolution ───────────────────────────────────────────────────────

  @Test
  public void testDifferentCurrencyWithDocumentRateReturnsNull() {
    Invoice invoice = invoiceWith("USD", "ORG1", "USD");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Currency.class, "EUR")).thenReturn(mock(Currency.class));

      ConversionRateDoc doc = mock(ConversionRateDoc.class);
      when(doc.getRate()).thenReturn(new BigDecimal("1.10"));
      stubDocRateCriteria(dal, Collections.singletonList(doc));

      assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
    }
  }

  @Test
  public void testDifferentCurrencyNoDocRateButGeneralRateReturnsNull() {
    Invoice invoice = invoiceWith("USD", "ORG1", "USD");
    when(invoice.getInvoiceDate()).thenReturn(new Date());
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> finUtils = Mockito.mockStatic(FinancialUtils.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Currency to = mock(Currency.class);
      when(dal.get(Currency.class, "EUR")).thenReturn(to);

      // No document rate: rows present but rate is zero (must be ignored).
      ConversionRateDoc zeroDoc = mock(ConversionRateDoc.class);
      when(zeroDoc.getRate()).thenReturn(BigDecimal.ZERO);
      stubDocRateCriteria(dal, Collections.singletonList(zeroDoc));

      finUtils.when(() -> FinancialUtils.getConversionRate(any(), any(Currency.class),
          eq(to), any(), any())).thenReturn(mock(org.openbravo.model.common.currency.ConversionRate.class));

      assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
    }
  }

  @Test
  public void testDifferentCurrencyNoRateReturnsMessage() {
    Invoice invoice = invoiceWith("USD", "ORG1", "USD");
    when(invoice.getInvoiceDate()).thenReturn(new Date());
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> finUtils = Mockito.mockStatic(FinancialUtils.class);
        MockedStatic<OBMessageUtils> msgUtils = Mockito.mockStatic(OBMessageUtils.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Currency to = mock(Currency.class);
      when(to.getISOCode()).thenReturn("EUR");
      when(dal.get(Currency.class, "EUR")).thenReturn(to);

      stubDocRateCriteria(dal, Collections.emptyList());
      finUtils.when(() -> FinancialUtils.getConversionRate(any(), any(Currency.class),
          any(Currency.class), any(), any())).thenReturn(null);
      msgUtils.when(() -> OBMessageUtils.messageBD("SMFCR_NoRateOnComplete")).thenReturn(NO_RATE_MSG);

      String result = InvoiceExchangeRateValidator.checkRateForCompletion(invoice);

      assertNotNull(result);
      assertTrue(result.startsWith(NO_RATE_MSG));
      assertEquals(NO_RATE_MSG + " USD → EUR", result);
      obCtx.verify(OBContext::restorePreviousMode);
    }
  }

  @Test
  public void testDocumentRateIgnoresNullAndZeroRows() {
    Invoice invoice = invoiceWith("USD", "ORG1", "USD");
    when(invoice.getInvoiceDate()).thenReturn(new Date());
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<FinancialUtils> finUtils = Mockito.mockStatic(FinancialUtils.class);
        MockedStatic<OBMessageUtils> msgUtils = Mockito.mockStatic(OBMessageUtils.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Currency to = mock(Currency.class);
      when(to.getISOCode()).thenReturn("EUR");
      when(dal.get(Currency.class, "EUR")).thenReturn(to);

      ConversionRateDoc nullRate = mock(ConversionRateDoc.class);
      when(nullRate.getRate()).thenReturn(null);
      ConversionRateDoc zeroRate = mock(ConversionRateDoc.class);
      when(zeroRate.getRate()).thenReturn(BigDecimal.ZERO);
      stubDocRateCriteria(dal, Arrays.asList(nullRate, zeroRate));

      finUtils.when(() -> FinancialUtils.getConversionRate(any(), any(Currency.class),
          any(Currency.class), any(), any())).thenReturn(null);
      msgUtils.when(() -> OBMessageUtils.messageBD("SMFCR_NoRateOnComplete")).thenReturn(NO_RATE_MSG);

      // Neither the null nor the zero rate counts as a document rate → blocking message.
      assertNotNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
    }
  }
}
