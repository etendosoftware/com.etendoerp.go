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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InvoiceLineHandler}.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceLineHandlerTest {

  private InvoiceLineHandler handler;

  private MockedStatic<DiscountLineFilter> discountFilterMock;
  private MockedStatic<LineCalloutTaxRateHelper> taxRateHelperMock;

  @BeforeEach
  void setUp() {
    handler = new InvoiceLineHandler();
    discountFilterMock = mockStatic(DiscountLineFilter.class);
    taxRateHelperMock = mockStatic(LineCalloutTaxRateHelper.class);
  }

  @AfterEach
  void tearDown() {
    discountFilterMock.close();
    taxRateHelperMock.close();
  }

  @Test
  @DisplayName("handle() always returns null (passthrough)")
  void handleReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .specName("sales-invoice").entityName("lines")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }

  @Nested
  @DisplayName("afterHandle")
  class AfterHandle {
    @Test
    void nonCrudEndpointReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.SELECTOR).build();
      assertNull(handler.afterHandle(ctx));
    }

    @Test
    void getRequestCallsDiscountFilter() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();

      discountFilterMock.when(() -> DiscountLineFilter.filterFromResponse(any()))
          .thenReturn(null);

      handler.afterHandle(ctx);

      discountFilterMock.verify(() -> DiscountLineFilter.filterFromResponse(ctx));
    }

    @Test
    void postRequestDoesNotCallFilter() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();

      assertNull(handler.afterHandle(ctx));
      discountFilterMock.verify(() -> DiscountLineFilter.filterFromResponse(any()), never());
    }
  }

  @Nested
  @DisplayName("afterCallout")
  class AfterCallout {
    @Test
    void delegatesToLineCalloutTaxRateHelper() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CALLOUT).build();

      taxRateHelperMock.when(() -> LineCalloutTaxRateHelper.augmentTaxRate(any()))
          .thenReturn(null);

      handler.afterCallout(ctx);

      taxRateHelperMock.verify(() -> LineCalloutTaxRateHelper.augmentTaxRate(ctx));
    }
  }
}
