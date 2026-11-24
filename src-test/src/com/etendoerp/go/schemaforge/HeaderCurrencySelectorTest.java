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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * Unit tests for {@link HeaderCurrencySelector} (ETP-6365): the SELECTOR of a document header's
 * {@code currency} lists what {@code currencyOptions} lists — the organization currency first, then
 * the currencies with a conversion range against it — in the generic selector's shape, searched by
 * {@code q} on ISO code and name, paged by {@code limit}/{@code offset}; and the five header
 * customizations that route {@code currencyOptions} serve it from {@code handle()}.
 *
 * <p>The DAL edges ({@link OBContext}, {@link OBDal}'s connection, {@link OBCurrencyUtils}) and
 * {@link CurrencyOptionsHandler}'s currency list are mocked; the filter and the page run for real.
 *
 * @covers com.etendoerp.go.schemaforge.HeaderCurrencySelector
 * @covers com.etendoerp.go.schemaforge.SalesOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.SalesQuotationHeaderHandler
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler
 */
public class HeaderCurrencySelectorTest {

  private static NeoContext selector(String field, Map<String, String> query) {
    return NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.SELECTOR)
        .fieldName(field).queryParams(query).build();
  }

  /**
   * Serves {@code context} for organization {@code org-1}, whose currency is {@code orgCurrency}
   * ({@code eur} when present), with CHF and USD selectable: EUR "Euro", CHF "Swiss Franc", USD "US
   * Dollar".
   */
  private static NeoResponse serve(NeoContext context, String orgCurrency) throws Exception {
    OBContext obCtx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("client-1");
    when(obCtx.getCurrentClient()).thenReturn(client);
    Connection conn = mock(Connection.class);
    PreparedStatement ps = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true, true, true, false);
    when(rs.getString(1)).thenReturn("eur", "chf", "usd");
    when(rs.getString(2)).thenReturn("EUR", "CHF", "USD");
    when(rs.getString(3)).thenReturn("Euro", "Swiss Franc", "US Dollar");
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenReturn(conn);
    Map<String, String> selectable = new LinkedHashMap<>();
    selectable.put("chf", "CHF");
    selectable.put("usd", "USD");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currencies = mockStatic(OBCurrencyUtils.class);
        MockedStatic<CurrencyOptionsHandler> options = mockStatic(CurrencyOptionsHandler.class);
        MockedStatic<RequestContext> request = mockStatic(RequestContext.class);
        MockedStatic<NeoLanguage> language = mockStatic(NeoLanguage.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(obCtx);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      currencies.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn(orgCurrency);
      options.when(() -> CurrencyOptionsHandler.readableOrganization(any(), any()))
          .thenReturn("org-1");
      options.when(() -> CurrencyOptionsHandler.selectableCurrencies(eq(conn), eq("eur"),
          eq("client-1"), eq("org-1"))).thenReturn(selectable);
      language.when(NeoLanguage::requestCode).thenReturn("en_US");
      return HeaderCurrencySelector.handleIfCurrencySelector(context);
    }
  }

  private static List<String> isoCodes(NeoResponse response) throws Exception {
    JSONArray items = response.getBody().getJSONArray("items");
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      assertEquals("label is the ISO code", item.getString("isoCode"), item.getString("label"));
      codes.add(item.getString("isoCode"));
    }
    return codes;
  }

  /** Both names of the field are served, organization currency first; other fields are not. */
  @Test
  public void servesTheCurrencyFieldOnlyWithTheOrganizationCurrencyFirst() throws Exception {
    for (String field : new String[] { "currency", "C_Currency_ID", "c_currency_id" }) {
      NeoResponse response = serve(selector(field, null), "eur");
      assertEquals(field, List.of("EUR", "CHF", "USD"), isoCodes(response));
      assertEquals(field, 3, response.getBody().getInt("totalCount"));
      assertEquals("eur", response.getBody().getJSONArray("items").getJSONObject(0)
          .getString("id"));
    }
    assertNull(serve(selector("priceList", null), "eur"));
    assertNull(serve(NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("currency").build(), "eur"));
  }

  /** {@code q} matches the ISO code or the name, ignoring case; no match is an empty page. */
  @Test
  public void searchMatchesIsoCodeAndNameIgnoringCase() throws Exception {
    Object[][] cases = {
        { "chf", List.of("CHF") },
        { "franc", List.of("CHF") },
        { "DOLLAR", List.of("USD") },
        { "u", List.of("EUR", "USD") },
        { "  ", List.of("EUR", "CHF", "USD") },
        { "zzz", List.of() },
    };
    for (Object[] c : cases) {
      NeoResponse response = serve(selector("currency", Map.of("q", (String) c[0])), "eur");
      assertEquals("q=" + c[0], c[1], isoCodes(response));
      assertEquals("q=" + c[0], ((List<?>) c[1]).size(),
          response.getBody().getInt("totalCount"));
    }
  }

  /** {@code limit}/{@code offset} page the list; {@code hasMore} tells whether more follow. */
  @Test
  public void limitAndOffsetPageTheList() throws Exception {
    NeoResponse first = serve(selector("currency", Map.of("limit", "2")), "eur");
    assertEquals(List.of("EUR", "CHF"), isoCodes(first));
    assertTrue(first.getBody().getBoolean("hasMore"));
    assertEquals(3, first.getBody().getInt("totalCount"));

    NeoResponse last = serve(selector("currency", Map.of("limit", "2", "offset", "2")), "eur");
    assertEquals(List.of("USD"), isoCodes(last));
    assertFalse(last.getBody().getBoolean("hasMore"));
  }

  /** No organization currency: not served here, the generic selector answers as before. */
  @Test
  public void answersNullWithoutAnOrganizationCurrency() throws Exception {
    assertNull(serve(selector("currency", null), null));
  }

  /** The five header customizations that route currencyOptions serve this selector. */
  @Test
  public void everyHeaderHandlerRoutesTheCurrencySelector() {
    NeoHandler[] headers = { new SalesOrderHeaderHandler(), new SalesQuotationHeaderHandler(),
        new SalesInvoiceHeaderHandler(), new PurchaseOrderHeaderHandler(),
        new PurchaseInvoiceHeaderHandler() };
    NeoContext context = selector("currency", null);
    NeoResponse served = NeoResponse.ok(new JSONObject());
    try (MockedStatic<PaymentMethodSelectorSupport> payment =
        mockStatic(PaymentMethodSelectorSupport.class);
        MockedStatic<HeaderCurrencySelector> currency = mockStatic(HeaderCurrencySelector.class)) {
      currency.when(() -> HeaderCurrencySelector.handleIfCurrencySelector(context))
          .thenReturn(served);
      for (NeoHandler header : headers) {
        assertSame(header.getClass().getSimpleName(), served, header.handle(context));
      }
    }
  }
}
