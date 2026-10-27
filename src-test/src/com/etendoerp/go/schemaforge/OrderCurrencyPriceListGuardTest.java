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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for {@link OrderCurrencyPriceListGuard}: an MCP update of a draft order may not move
 * the order to a currency its price list is not in. REST, the SPA and batch are untouched.
 *
 * <p>Also pins that the purchase-order and the sales-order headers both run the guard from their
 * {@code handle()}.
 *
 * @covers com.etendoerp.go.schemaforge.OrderCurrencyPriceListGuard
 * @covers com.etendoerp.go.schemaforge.PurchaseOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.SalesOrderHeaderHandler
 */
public class OrderCurrencyPriceListGuardTest {

  private static final String ORDER_ID = "ORD1";
  private static final String EUR = "EUR_ID";
  private static final String USD = "USD_ID";

  private MockedStatic<OBDal> dalStatic;
  private OBDal dal;
  private Order order;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    dalStatic = Mockito.mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    order = mock(Order.class);
    when(order.getDocumentStatus()).thenReturn("DR");
    Currency eur = currency(EUR, "EUR");
    Currency usd = currency(USD, "USD");
    PriceList eurList = priceList("PL_EUR", "Tarifa EUR", eur);
    when(order.getCurrency()).thenReturn(eur);
    when(order.getPriceList()).thenReturn(eurList);
    when(dal.get(Order.class, ORDER_ID)).thenReturn(order);
    when(dal.get(Currency.class, USD)).thenReturn(usd);
  }

  @After
  public void tearDown() {
    dalStatic.close();
  }

  private static Currency currency(String id, String iso) {
    Currency c = mock(Currency.class);
    when(c.getId()).thenReturn(id);
    when(c.getISOCode()).thenReturn(iso);
    return c;
  }

  private static PriceList priceList(String id, String name, Currency currency) {
    PriceList pl = mock(PriceList.class);
    when(pl.getId()).thenReturn(id);
    when(pl.getName()).thenReturn(name);
    when(pl.getCurrency()).thenReturn(currency);
    return pl;
  }

  /** Makes the price list resolvable by the readable-filtered criteria; {@code null} = unreadable. */
  @SuppressWarnings("unchecked")
  private void readableOnly(PriceList readable) {
    OBCriteria<PriceList> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(PriceList.class)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(readable);
  }

  private static NeoContext ctx(String method, boolean mcp, JSONObject body) {
    return NeoContext.builder().specName("mock-spec").entityName("header").httpMethod(method)
        .endpointType(NeoEndpointType.CRUD).recordId(ORDER_ID).requestBody(body).mcpOrigin(mcp)
        .build();
  }

  private static NeoResponse run(NeoContext context) {
    return OrderCurrencyPriceListGuard.refuseCurrencyOutsidePriceList(context);
  }

  @Test
  public void sparseBodyUsesTheStoredPriceList() throws Exception {
    NeoResponse refusal = run(ctx("PUT", true, new JSONObject().put("currency", USD)));

    assertNotNull(refusal);
    assertEquals(422, refusal.getHttpStatus());
    String text = refusal.getBody().toString();
    assertTrue(text, text.contains("Tarifa EUR") && text.contains("EUR") && text.contains("USD"));
    assertTrue(refusal.getBody().has("hint"));
  }

  @Test
  public void bodyPriceListWinsOverTheStoredOne() throws Exception {
    PriceList other = priceList("PL_EUR2", "Otra EUR", currency(EUR, "EUR"));
    readableOnly(other);
    NeoResponse refusal = run(ctx("PUT", true,
        new JSONObject().put("currency", USD).put("priceList", "PL_EUR2")));
    assertNotNull(refusal);
    assertTrue(refusal.getBody().toString().contains("Otra EUR"));
  }

  @Test
  public void aForeignOrUnreadablePriceListAbstainsAndEchoesNothing() throws Exception {
    readableOnly(null);
    NeoResponse refusal = run(ctx("PUT", true,
        new JSONObject().put("currency", USD).put("priceList", "FOREIGN_PL")));
    assertNull(refusal);
    verify(dal, never()).get(PriceList.class, "FOREIGN_PL");
  }

  @Test
  public void priceListOfTheNewCurrencyPasses() throws Exception {
    PriceList usdList = priceList("PL_USD", "Tarifa USD", currency(USD, "USD"));
    readableOnly(usdList);
    assertNull(run(ctx("PUT", true,
        new JSONObject().put("currency", USD).put("priceList", "PL_USD"))));
  }

  @Test
  public void referenceObjectCurrencyIsRead() throws Exception {
    assertNotNull(run(ctx("PUT", true,
        new JSONObject().put("currency", new JSONObject().put("id", USD)))));
  }

  @Test
  public void orderWithoutPriceListPasses() throws Exception {
    when(order.getPriceList()).thenReturn(null);
    assertNull(run(ctx("PUT", true, new JSONObject().put("currency", USD))));
  }

  @Test
  public void unchangedCurrencyPasses() throws Exception {
    assertNull(run(ctx("PUT", true, new JSONObject().put("currency", EUR))));
  }

  @Test
  public void nonDraftOrderPasses() throws Exception {
    when(order.getDocumentStatus()).thenReturn("CO");
    assertNull(run(ctx("PUT", true, new JSONObject().put("currency", USD))));
  }

  @Test
  public void bodyWithoutCurrencyOrNullCurrencyPasses() throws Exception {
    assertNull(run(ctx("PUT", true, new JSONObject().put("description", "x"))));
    assertNull(run(ctx("PUT", true, new JSONObject().put("currency", JSONObject.NULL))));
  }

  @Test
  public void restAndOtherMethodsAreUntouched() throws Exception {
    JSONObject body = new JSONObject().put("currency", USD);
    assertNull(run(ctx("PUT", false, body)));
    assertNull(run(ctx("PATCH", false, body)));
    assertNull(run(ctx("PATCH", true, body)));
    assertNull(run(ctx("POST", true, body)));
    verify(dal, never()).get(any(Class.class), anyString());
  }

  @Test
  public void aFailureToDecideLetsTheWriteThrough() throws Exception {
    when(dal.get(Order.class, ORDER_ID)).thenThrow(new IllegalStateException("boom"));
    assertNull(run(ctx("PUT", true, new JSONObject().put("currency", USD))));
  }

  @Test
  public void purchaseOrderHeaderRunsTheGuard() throws Exception {
    NeoResponse refusal = new PurchaseOrderHeaderHandler()
        .handle(ctx("PUT", true, new JSONObject().put("currency", USD)));
    assertNotNull(refusal);
    assertEquals(422, refusal.getHttpStatus());
  }

  @Test
  public void salesOrderHeaderRunsTheGuard() throws Exception {
    NeoResponse refusal = new SalesOrderHeaderHandler()
        .handle(ctx("PUT", true, new JSONObject().put("currency", USD)));
    assertNotNull(refusal);
    assertEquals(422, refusal.getHttpStatus());
  }
}
