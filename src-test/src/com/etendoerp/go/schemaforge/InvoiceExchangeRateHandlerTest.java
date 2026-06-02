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
