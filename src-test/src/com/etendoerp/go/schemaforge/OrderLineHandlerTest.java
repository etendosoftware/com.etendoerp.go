/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Unit tests for {@link OrderLineHandler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderLineHandlerTest {

  private OrderLineHandler handler;

  @Mock private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<DiscountLineFilter> discountFilterMock;
  private MockedStatic<LineCalloutTaxRateHelper> taxRateHelperMock;
  private MockedStatic<NeoCommercialLinePolicy> commercialLinePolicyMock;

  @BeforeEach
  void setUp() {
    handler = new OrderLineHandler();
    obDalMock = mockStatic(OBDal.class);
    discountFilterMock = mockStatic(DiscountLineFilter.class);
    taxRateHelperMock = mockStatic(LineCalloutTaxRateHelper.class);
    commercialLinePolicyMock = mockStatic(NeoCommercialLinePolicy.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    discountFilterMock.close();
    taxRateHelperMock.close();
    commercialLinePolicyMock.close();
  }

  @Nested
  @DisplayName("handle")
  class Handle {
    @Test
    void nonCrudEndpointReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.SELECTOR).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    void getRequestReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    void postWithNoBodyReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(null).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    void postWithNoGrossUnitPriceReturnsNull() throws Exception {
      JSONObject body = new JSONObject();
      body.put("unitPrice", "100");
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();
      assertNull(handler.handle(ctx));
    }
  }

  @Nested
  @DisplayName("afterHandle")
  class AfterHandle {
    @Test
    void nonCrudEndpointReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.SELECTOR).build();
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void getRequestCallsDiscountFilter() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

      discountFilterMock.when(() -> DiscountLineFilter.filterFromResponse(any()))
          .thenReturn(null);

      handler.afterHandle(ctx);
      discountFilterMock.verify(() -> DiscountLineFilter.filterFromResponse(ctx));
    }

    @Test
    void patchWithNoBodyReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .requestBody(null).build();
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void patchWithoutUnitPriceReturnsNull() throws Exception {
      JSONObject body = new JSONObject();
      body.put("description", "test");
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void patchWithoutRecordIdReturnsNull() throws Exception {
      JSONObject body = new JSONObject();
      body.put("unitPrice", "100");
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).recordId(null).build();
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void patchWithNonTaxInclusivePriceListReturnsNull() throws Exception {
      JSONObject body = new JSONObject();
      body.put("unitPrice", "100");

      OrderLine line = mock(OrderLine.class);
      Order order = mock(Order.class);
      PriceList priceList = mock(PriceList.class);

      when(obDal.get(OrderLine.class, "line-id")).thenReturn(line);
      when(line.getSalesOrder()).thenReturn(order);
      when(order.getPriceList()).thenReturn(priceList);
      when(priceList.isPriceIncludesTax()).thenReturn(false);

      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).recordId("line-id").build();

      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void deleteReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
      assertNull(handler.afterHandle(ctx));
    }
  }

  @Nested
  @DisplayName("afterCallout")
  class AfterCallout {
    @Test
    void delegatesToTaxRateHelper() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-order").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CALLOUT).build();

      taxRateHelperMock.when(() -> LineCalloutTaxRateHelper.augmentTaxRate(any()))
          .thenReturn(null);

      handler.afterCallout(ctx);
      taxRateHelperMock.verify(() -> LineCalloutTaxRateHelper.augmentTaxRate(ctx));
    }
  }
}
