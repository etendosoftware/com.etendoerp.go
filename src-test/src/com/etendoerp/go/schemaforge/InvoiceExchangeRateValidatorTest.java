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
import static org.mockito.Mockito.never;
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
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link InvoiceExchangeRateValidator#checkRateForCompletion(Invoice)}.
 *
 * <p>Covers every early-exit guard (null invoice/currency/organization, no functional currency,
 * same currency) and the three rate-resolution outcomes (document rate present, general rate
 * present on the accounting date, and no rate at all → the ETP-5840 completion refusal).
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceExchangeRateValidator
 */
public class InvoiceExchangeRateValidatorTest {

  private static final String NO_RATE_MSG = "The document cannot be confirmed: rate missing";
  private static final java.time.LocalDate INVOICE_DATE = java.time.LocalDate.of(2026, 3, 30);
  private static final java.time.LocalDate ACCOUNTING_DATE = java.time.LocalDate.of(2026, 3, 31);

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

  /**
   * ETP-5840: the general-rate fallback is looked up on the ACCOUNTING date (not the invoice date),
   * org→document, scoped to the invoice's own organization and client. A rate there lets the
   * completion through even when the document rows are all zero.
   */
  @Test
  public void testGeneralRateIsLookedUpOnTheAccountingDate() {
    Invoice invoice = invoiceWith("USD", "ORG1", "USD");
    when(invoice.getInvoiceDate()).thenReturn(java.sql.Date.valueOf(INVOICE_DATE));
    when(invoice.getAccountingDate()).thenReturn(java.sql.Date.valueOf(ACCOUNTING_DATE));
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT1");
    when(invoice.getClient()).thenReturn(client);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<DocumentCurrencyRate> rates = Mockito.mockStatic(DocumentCurrencyRate.class)) {
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Currency to = mock(Currency.class);
      when(to.getId()).thenReturn("EUR");
      when(dal.get(Currency.class, "EUR")).thenReturn(to);
      ConversionRateDoc zeroDoc = mock(ConversionRateDoc.class);
      when(zeroDoc.getRate()).thenReturn(BigDecimal.ZERO);
      stubDocRateCriteria(dal, Collections.singletonList(zeroDoc));
      rates.when(() -> DocumentCurrencyRate.lookupRate("EUR", "USD", ACCOUNTING_DATE, "ORG1",
          "CLIENT1")).thenReturn(new BigDecimal("1.10"));
      // Would be the answer if the general rate were not found.
      rates.when(() -> DocumentCurrencyRate.missingRateOnCompletion(invoice,
          Invoice.PROPERTY_ETGOCURRENCYRATE)).thenReturn(NO_RATE_MSG);

      assertNull(InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
    }
  }

  /**
   * No document rate and no general rate on the accounting date: the refusal is the
   * {@code ETGO_ExchangeRateMissingOnComplete} text {@link DocumentCurrencyRate} builds for the
   * invoice's own rate column — the NEO pre-hooks' text — no longer {@code SMFCR_NoRateOnComplete}.
   * Holds when the document rows are null/zero and when the org currency record is missing.
   */
  @Test
  public void testNoRateReturnsTheCompletionRefusalOfTheRateColumn() {
    boolean[] orgCurrencyRecordExists = { true, false };
    for (boolean exists : orgCurrencyRecordExists) {
      Invoice invoice = invoiceWith("USD", "ORG1", "USD");
      when(invoice.getAccountingDate()).thenReturn(java.sql.Date.valueOf(ACCOUNTING_DATE));
      Client client = mock(Client.class);
      when(client.getId()).thenReturn("CLIENT1");
      when(invoice.getClient()).thenReturn(client);
      try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
          MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
          MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
          MockedStatic<DocumentCurrencyRate> rates =
              Mockito.mockStatic(DocumentCurrencyRate.class)) {
        currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        Currency to = exists ? mock(Currency.class) : null;
        if (to != null) {
          when(to.getId()).thenReturn("EUR");
        }
        when(dal.get(Currency.class, "EUR")).thenReturn(to);
        ConversionRateDoc nullRate = mock(ConversionRateDoc.class);
        ConversionRateDoc zeroRate = mock(ConversionRateDoc.class);
        when(zeroRate.getRate()).thenReturn(BigDecimal.ZERO);
        stubDocRateCriteria(dal, Arrays.asList(nullRate, zeroRate));
        rates.when(() -> DocumentCurrencyRate.missingRateOnCompletion(invoice,
            Invoice.PROPERTY_ETGOCURRENCYRATE)).thenReturn(NO_RATE_MSG);

        assertEquals("org currency record exists: " + exists, NO_RATE_MSG,
            InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
        obCtx.verify(OBContext::restorePreviousMode);
      }
    }
  }

  /**
   * Without an accounting date or a client the general rate cannot be scoped, so it is not looked
   * up at all and the invoice is refused.
   */
  @Test
  public void testMissingAccountingDateOrClientSkipsTheGeneralRate() {
    Object[][] cases = {
        { "no accounting date", null, "CLIENT1" },
        { "no client", java.sql.Date.valueOf(ACCOUNTING_DATE), null },
    };
    for (Object[] c : cases) {
      Invoice invoice = invoiceWith("USD", "ORG1", "USD");
      when(invoice.getAccountingDate()).thenReturn((Date) c[1]);
      Client client = null;
      if (c[2] != null) {
        client = mock(Client.class);
        when(client.getId()).thenReturn((String) c[2]);
      }
      when(invoice.getClient()).thenReturn(client);
      try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
          MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class);
          MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
          MockedStatic<DocumentCurrencyRate> rates =
              Mockito.mockStatic(DocumentCurrencyRate.class)) {
        currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency("ORG1")).thenReturn("EUR");
        OBDal dal = mock(OBDal.class);
        obDal.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(Currency.class, "EUR")).thenReturn(mock(Currency.class));
        stubDocRateCriteria(dal, Collections.emptyList());
        rates.when(() -> DocumentCurrencyRate.missingRateOnCompletion(invoice,
            Invoice.PROPERTY_ETGOCURRENCYRATE)).thenReturn(NO_RATE_MSG);

        assertEquals((String) c[0], NO_RATE_MSG,
            InvoiceExchangeRateValidator.checkRateForCompletion(invoice));
        rates.verify(() -> DocumentCurrencyRate.lookupRate(any(), any(), any(), any(), any()),
            never());
      }
    }
  }
}
