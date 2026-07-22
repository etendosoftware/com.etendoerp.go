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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.currency.ConversionRateDoc;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link InvoiceExchangeRateHandler}.
 *
 * <p>Covers the POST {@code handle()} derivation logic (currency / toCurrency defaulting and the
 * rate ↔ foreignAmount callout) and the {@code afterHandle()} DEFAULTS injection, plus every
 * early-exit guard.
 */
public class InvoiceExchangeRateHandlerTest {

  private static final String INVOICE_ID = "INV1";
  private static final String FROM_CURRENCY_ID = "USD";
  private static final String ORG_ID = "ORG1";
  private static final String ORG_CURRENCY_ID = "EUR";

  private final InvoiceExchangeRateHandler handler = new InvoiceExchangeRateHandler();

  /** Builds an invoice with currency {@code USD}, org {@code ORG1} and the given grand total. */
  private static Invoice invoiceWith(BigDecimal grandTotal) {
    Currency from = mock(Currency.class);
    when(from.getId()).thenReturn(FROM_CURRENCY_ID);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    Invoice invoice = mock(Invoice.class);
    when(invoice.getCurrency()).thenReturn(from);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.getGrandTotalAmount()).thenReturn(grandTotal);
    return invoice;
  }

  private static NeoContext crudPost(JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(body)
        .build();
  }

  // ----- handle() early-exit guards -----

  @Test
  public void testHandleIgnoresNonCrudEndpoint() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .build();
    assertNull(handler.handle(context));
  }

  @Test
  public void testHandleIgnoresNonPostMethod() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();
    assertNull(handler.handle(context));
  }

  @Test
  public void testHandleIgnoresNullBody() {
    assertNull(handler.handle(crudPost(null)));
  }

  @Test
  public void testHandleIgnoresMissingInvoiceId() throws Exception {
    assertNull(handler.handle(crudPost(new JSONObject())));
  }

  @Test
  public void testHandleIgnoresInvoiceNotFound() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoice", INVOICE_ID);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(null);

      assertNull(handler.handle(crudPost(body)));
    }
  }

  // ----- handle() derivation -----

  @Test
  public void testHandleDefaultsCurrencyToCurrencyAndForeignAmount() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoice", INVOICE_ID);
    body.put("rate", "1.10");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(new BigDecimal("100"));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      assertNull(handler.handle(crudPost(body)));

      assertEquals(FROM_CURRENCY_ID, body.optString("currency"));
      assertEquals(ORG_CURRENCY_ID, body.optString("toCurrency"));
      assertEquals(0, new BigDecimal(body.optString("foreignAmount")).compareTo(new BigDecimal("110")));
    }
  }

  @Test
  public void testHandleDerivesRateFromForeignAmount() throws Exception {
    JSONObject body = new JSONObject();
    body.put("parentId", INVOICE_ID);
    body.put("foreignAmount", "220");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(new BigDecimal("100"));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      assertNull(handler.handle(crudPost(body)));

      assertEquals(0, new BigDecimal(body.optString("rate")).compareTo(new BigDecimal("2.2")));
    }
  }

  @Test
  public void testHandleCreateLeavesPairUntouchedWhenBothProvided() throws Exception {
    // Caller supplied both sides — neither is derived/overwritten.
    JSONObject body = new JSONObject();
    body.put("invoice", INVOICE_ID);
    body.put("rate", "2");
    body.put("foreignAmount", "200");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(new BigDecimal("100"));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      assertNull(handler.handle(crudPost(body)));

      assertEquals(0, new BigDecimal(body.optString("rate")).compareTo(new BigDecimal("2")));
      assertEquals(0, new BigDecimal(body.optString("foreignAmount")).compareTo(new BigDecimal("200")));
    }
  }

  @Test
  public void testHandleCreateIgnoresNonNumericRate() throws Exception {
    // A non-numeric rate is parsed as null (readDecimal swallows the format error) and skipped.
    JSONObject body = new JSONObject();
    body.put("invoice", INVOICE_ID);
    body.put("rate", "not-a-number");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(new BigDecimal("100"));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      assertNull(handler.handle(crudPost(body)));
      assertFalse(body.has("foreignAmount"));
    }
  }

  @Test
  public void testHandleUpdateIgnoresNonNumericRate() throws Exception {
    // Non-numeric rate → readDecimal returns null → nothing to recompute, no doc load.
    JSONObject body = new JSONObject();
    body.put("rate", "abc");
    assertNull(handler.handle(crudPatch("DOC1", body)));
    assertFalse(body.has("foreignAmount"));
  }

  @Test
  public void testHandleZeroGrandTotalLeavesRateAndForeignUntouched() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoice", INVOICE_ID);
    body.put("rate", "1.10");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(BigDecimal.ZERO);
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      assertNull(handler.handle(crudPost(body)));

      assertFalse(body.has("foreignAmount"));
      assertEquals("1.10", body.optString("rate"));
    }
  }

  @Test
  public void testHandleWrapsDerivationFailureInObException() {
    JSONObject body = new JSONObject();
    try {
      body.put("invoice", INVOICE_ID);
      body.put("rate", "1.10");
    } catch (Exception e) {
      fail("setup failed: " + e.getMessage());
    }
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(new BigDecimal("100"));
      when(invoice.getGrandTotalAmount()).thenThrow(new RuntimeException("boom"));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      handler.handle(crudPost(body));
      fail("expected OBException");
    } catch (OBException expected) {
      // expected: derivation failure must roll back the save
    }
  }

  // ----- handle() update (PATCH/PUT) -----

  private static NeoContext crudPatch(String recordId, JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(body)
        .build();
  }

  /**
   * Builds a persisted ConversionRateDoc with the given grand total and currently-stored
   * {@code rate} / {@code foreignAmount}, used to drive the change-detection on update.
   */
  private static ConversionRateDoc docWith(BigDecimal grandTotal, BigDecimal storedRate,
      BigDecimal storedForeign) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getGrandTotalAmount()).thenReturn(grandTotal);
    ConversionRateDoc doc = mock(ConversionRateDoc.class);
    when(doc.getInvoice()).thenReturn(invoice);
    when(doc.getRate()).thenReturn(storedRate);
    when(doc.getForeignAmount()).thenReturn(storedForeign);
    return doc;
  }

  private static OBDal stubDocLookup(MockedStatic<OBDal> obDal, ConversionRateDoc doc) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    when(dal.get(ConversionRateDoc.class, "DOC1")).thenReturn(doc);
    return dal;
  }

  @Test
  public void testHandleUpdateRecomputesStaleForeignWhenRateChanged() throws Exception {
    // UI submits both: rate edited 5 -> 3, foreignAmount carries the stale 500 (= 100 * 5).
    JSONObject body = new JSONObject();
    body.put("rate", "3");
    body.put("foreignAmount", 500);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, docWith(new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("500")));

      assertNull(handler.handle(crudPatch("DOC1", body)));

      assertEquals(0, new BigDecimal(body.optString("foreignAmount")).compareTo(new BigDecimal("300")));
    }
  }

  @Test
  public void testHandleUpdateRecomputesStaleRateWhenForeignChanged() throws Exception {
    // UI submits both: foreignAmount edited 500 -> 300, rate carries the stale 5.
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    body.put("foreignAmount", 300);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, docWith(new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("500")));

      assertNull(handler.handle(crudPatch("DOC1", body)));

      assertEquals(0, new BigDecimal(body.optString("rate")).compareTo(new BigDecimal("3")));
    }
  }

  @Test
  public void testHandleUpdateRateOnlyBodyRecomputesForeign() throws Exception {
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, docWith(new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("200")));

      assertNull(handler.handle(crudPatch("DOC1", body)));

      assertEquals(0, new BigDecimal(body.optString("foreignAmount")).compareTo(new BigDecimal("500")));
    }
  }

  @Test
  public void testHandleUpdateLeavesBodyUntouchedWhenNothingChanged() throws Exception {
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    body.put("foreignAmount", 500);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, docWith(new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("500")));

      assertNull(handler.handle(crudPatch("DOC1", body)));

      assertEquals(0, new BigDecimal(body.optString("rate")).compareTo(new BigDecimal("5")));
      assertEquals(0, new BigDecimal(body.optString("foreignAmount")).compareTo(new BigDecimal("500")));
    }
  }

  // ----- handle() update: reverse sync to invoice.eTGOCurrencyRate -----

  @Test
  public void testHandleUpdateSyncsHeaderRateWhenRateChanged() throws Exception {
    // rate edited 0.5 -> 0.9 (doc->org). Header eTGOCurrencyRate (org->doc) must become 1/0.9.
    JSONObject body = new JSONObject();
    body.put("rate", "0.9");
    ConversionRateDoc doc = docWith(new BigDecimal("100"), new BigDecimal("0.5"), new BigDecimal("50"));
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = stubDocLookup(obDal, doc);

      assertNull(handler.handle(crudPatch("DOC1", body)));

      org.mockito.ArgumentCaptor<BigDecimal> captor = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
      Mockito.verify(doc.getInvoice()).setETGOCurrencyRate(captor.capture());
      assertEquals(0, captor.getValue().compareTo(BigDecimal.ONE.divide(new BigDecimal("0.9"), 12, java.math.RoundingMode.HALF_UP)));
      Mockito.verify(dal).save(doc.getInvoice());
    }
  }

  @Test
  public void testHandleUpdateSyncsHeaderRateWhenForeignAmountChanged() throws Exception {
    // grandTotal=100, foreignAmount edited 50 -> 60 => recomputed docRate = 0.6.
    JSONObject body = new JSONObject();
    body.put("foreignAmount", "60");
    ConversionRateDoc doc = docWith(new BigDecimal("100"), new BigDecimal("0.5"), new BigDecimal("50"));
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = stubDocLookup(obDal, doc);

      assertNull(handler.handle(crudPatch("DOC1", body)));

      org.mockito.ArgumentCaptor<BigDecimal> captor = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
      Mockito.verify(doc.getInvoice()).setETGOCurrencyRate(captor.capture());
      assertEquals(0, captor.getValue().compareTo(BigDecimal.ONE.divide(new BigDecimal("0.6"), 12, java.math.RoundingMode.HALF_UP)));
      Mockito.verify(dal).save(doc.getInvoice());
    }
  }

  @Test
  public void testHandleUpdateDoesNotSyncHeaderRateWhenNothingChanged() throws Exception {
    // Same rate/foreignAmount as already persisted — no recompute, so no header write either.
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    body.put("foreignAmount", 500);
    ConversionRateDoc doc = docWith(new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("500"));
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = stubDocLookup(obDal, doc);

      assertNull(handler.handle(crudPatch("DOC1", body)));

      Mockito.verify(doc.getInvoice(), Mockito.never()).setETGOCurrencyRate(Mockito.any());
      Mockito.verify(dal, Mockito.never()).save(Mockito.any());
    }
  }

  @Test
  public void testHandleUpdateIgnoresMissingRecordId() throws Exception {
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    assertNull(handler.handle(crudPatch("", body)));
  }

  @Test
  public void testHandleUpdateIgnoresUnrelatedEdit() throws Exception {
    JSONObject body = new JSONObject();
    body.put("comments", "note");
    assertNull(handler.handle(crudPatch("DOC1", body)));
    assertFalse(body.has("rate"));
    assertFalse(body.has("foreignAmount"));
  }

  @Test
  public void testHandleUpdateSkipsForeignAmountRecomputeWithZeroGrandTotal() throws Exception {
    // A zero grand total (e.g. a lineless draft invoice) makes foreignAmount = grandTotal
    // * rate meaningless to recompute — it must NOT be touched. This does not mean the
    // whole update is a no-op: see testHandleUpdateSyncsHeaderRateEvenWithZeroGrandTotal.
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, docWith(BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO));

      assertNull(handler.handle(crudPatch("DOC1", body)));
      assertFalse(body.has("foreignAmount"));
    }
  }

  @Test
  public void testHandleUpdateSyncsHeaderRateEvenWithZeroGrandTotal() throws Exception {
    // ETP-4029 regression: editing Rate directly on a lineless draft invoice (grandTotal=0,
    // e.g. right after creating it, before adding any line) must still sync the header's
    // eTGOCurrencyRate — that only needs the rate itself, never the invoice total. The
    // original bug returned before this ever ran whenever grandTotal was zero.
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    ConversionRateDoc doc = docWith(BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = stubDocLookup(obDal, doc);

      assertNull(handler.handle(crudPatch("DOC1", body)));

      org.mockito.ArgumentCaptor<BigDecimal> captor = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
      Mockito.verify(doc.getInvoice()).setETGOCurrencyRate(captor.capture());
      assertEquals(0, captor.getValue().compareTo(BigDecimal.ONE.divide(new BigDecimal("5"), 12, java.math.RoundingMode.HALF_UP)));
      Mockito.verify(dal).save(doc.getInvoice());
      assertFalse(body.has("foreignAmount"));
    }
  }

  @Test
  public void testHandleUpdateForeignAmountAloneCannotDeriveRateWithZeroGrandTotal() throws Exception {
    // The reverse direction genuinely cannot work with no total: rate = foreignAmount /
    // grandTotal is a division by zero. Neither rate nor the header sync should fire.
    JSONObject body = new JSONObject();
    body.put("foreignAmount", "10");
    ConversionRateDoc doc = docWith(BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO);
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, doc);

      assertNull(handler.handle(crudPatch("DOC1", body)));

      assertFalse(body.has("rate"));
      Mockito.verify(doc.getInvoice(), Mockito.never()).setETGOCurrencyRate(Mockito.any());
    }
  }

  @Test
  public void testHandleUpdateIgnoresDocNotFound() throws Exception {
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ConversionRateDoc.class, "DOC1")).thenReturn(null);

      assertNull(handler.handle(crudPatch("DOC1", body)));
      assertFalse(body.has("foreignAmount"));
    }
  }

  @Test
  public void testHandleUpdateRecomputesWhenStoredRateIsNull() throws Exception {
    // Stored rate is null (a freshly seeded row) → the incoming rate is treated as a change.
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      stubDocLookup(obDal, docWith(new BigDecimal("100"), null, null));

      assertNull(handler.handle(crudPatch("DOC1", body)));

      assertEquals(0, new BigDecimal(body.optString("foreignAmount")).compareTo(new BigDecimal("500")));
    }
  }

  @Test
  public void testHandleUpdateReturnsNullWhenDocLoadThrows() throws Exception {
    JSONObject body = new JSONObject();
    body.put("rate", "5");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ConversionRateDoc.class, "DOC1")).thenThrow(new RuntimeException("db down"));

      assertNull(handler.handle(crudPatch("DOC1", body)));
      assertFalse(body.has("foreignAmount"));
      obCtx.verify(OBContext::restorePreviousMode);
    }
  }

  @Test
  public void testHandleCreateReturnsNullWhenInvoiceLoadThrows() throws Exception {
    JSONObject body = new JSONObject();
    body.put("invoice", INVOICE_ID);
    body.put("rate", "5");
    try (MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, INVOICE_ID)).thenThrow(new RuntimeException("db down"));

      assertNull(handler.handle(crudPost(body)));
      assertFalse(body.has("foreignAmount"));
      obCtx.verify(OBContext::restorePreviousMode);
    }
  }

  // ----- afterHandle() -----

  @Test
  public void testAfterHandleIgnoresNonDefaultsEndpoint() {
    NeoContext context = NeoContext.builder().endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.afterHandle(context));
  }

  @Test
  public void testAfterHandleIgnoresNullPreviousResult() {
    NeoContext context = NeoContext.builder().endpointType(NeoEndpointType.DEFAULTS).build();
    assertNull(handler.afterHandle(context));
  }

  @Test
  public void testAfterHandleIgnoresNullPreviousBody() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(handler.afterHandle(context));
  }

  @Test
  public void testAfterHandleIgnoresMissingParentId() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(new JSONObject()))
        .build();
    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class)) {
      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("parentId")).thenReturn(null);

      assertNull(handler.afterHandle(context));
    }
  }

  @Test
  public void testAfterHandleInjectsCurrencyAndToCurrencyDefaults() throws Exception {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(new JSONObject()))
        .build();
    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencyUtils = Mockito.mockStatic(OBCurrencyUtils.class)) {
      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("parentId")).thenReturn(INVOICE_ID);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = invoiceWith(new BigDecimal("100"));
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      currencyUtils.when(() -> OBCurrencyUtils.getOrgCurrency(ORG_ID)).thenReturn(ORG_CURRENCY_ID);

      NeoResponse response = handler.afterHandle(context);

      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertEquals(FROM_CURRENCY_ID, defaults.getString("currency"));
      assertEquals(ORG_CURRENCY_ID, defaults.getString("toCurrency"));
    }
  }

  @Test
  public void testAfterHandleReturnsNullWhenInvoiceCurrencyMissing() {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.DEFAULTS)
        .previousResult(NeoResponse.ok(new JSONObject()))
        .build();
    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      RequestContext requestContext = mock(RequestContext.class);
      HttpServletRequest request = mock(HttpServletRequest.class);
      reqCtx.when(RequestContext::get).thenReturn(requestContext);
      when(requestContext.getRequest()).thenReturn(request);
      when(request.getParameter("parentId")).thenReturn(INVOICE_ID);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = mock(Invoice.class);
      when(invoice.getCurrency()).thenReturn(null);
      when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);

      assertNull(handler.afterHandle(context));
    }
  }

  @Test
  public void testHandlerIsRegisteredWithExpectedQualifier() {
    javax.inject.Named named = InvoiceExchangeRateHandler.class.getAnnotation(javax.inject.Named.class);
    assertTrue(named != null && "invoiceExchangeRateHandler".equals(named.value()));
  }
}
