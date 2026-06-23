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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

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

  @Nested
  @DisplayName("handle")
  class Handle {

    @Test
    @DisplayName("non-CRUD endpoint returns null without touching body")
    void nonCrudEndpointReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CALLOUT).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("GET method returns null (only POST/PATCH/PUT trigger auto-negate)")
    void getMethodReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("DELETE method returns null")
    void deleteMethodReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("null body returns null")
    void nullBodyReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(null).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("body without invoicedQuantity returns null")
    void bodyWithoutInvoicedQuantityReturnsNull() throws Exception {
      JSONObject body = new JSONObject().put("lineNetAmount", 100.0);
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("non-return invoice: body not mutated, returns null")
    void nonReturnInvoiceBodyNotMutated() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-1")
          .put("invoicedQuantity", 5.0)
          .put("lineNetAmount", 100.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-1")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(false);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(5.0, body.getDouble("invoicedQuantity"), 0.001);
        assertEquals(100.0, body.getDouble("lineNetAmount"), 0.001);
      }
    }

    @Test
    @DisplayName("return invoice: positive invoicedQuantity is negated")
    void returnInvoicePositiveQtyNegated() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-ret-1")
          .put("invoicedQuantity", 5.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-ret-1")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(-5.0, body.getDouble("invoicedQuantity"), 0.001);
      }
    }

    @Test
    @DisplayName("return invoice: already-negative invoicedQuantity stays unchanged")
    void returnInvoiceNegativeQtyUnchanged() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-ret-2")
          .put("invoicedQuantity", -3.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-ret-2")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(-3.0, body.getDouble("invoicedQuantity"), 0.001);
      }
    }

    @Test
    @DisplayName("return invoice: positive lineNetAmount is also negated when present")
    void returnInvoicePositiveLineNetAmountNegated() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-ret-3")
          .put("invoicedQuantity", 2.0)
          .put("lineNetAmount", 50.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-ret-3")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(-2.0, body.getDouble("invoicedQuantity"), 0.001);
        assertEquals(-50.0, body.getDouble("lineNetAmount"), 0.001);
      }
    }

    @Test
    @DisplayName("return invoice: lineNetAmount skipped when not in body")
    void returnInvoiceLineNetAmountSkippedWhenAbsent() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-ret-4")
          .put("invoicedQuantity", 1.0);
      // lineNetAmount intentionally absent

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-ret-4")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(-1.0, body.getDouble("invoicedQuantity"), 0.001);
        // lineNetAmount was absent before and must still be absent after
        assertEquals(false, body.has("lineNetAmount"));
      }
    }

    @Test
    @DisplayName("PATCH: resolves parent via line record when parentId absent from body")
    void patchResolvesParentViaLineRecord() throws Exception {
      JSONObject body = new JSONObject()
          .put("invoicedQuantity", 7.0);
      // no parentId in body — must resolve via InvoiceLine

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);

        when(dal.get(InvoiceLine.class, "line-1")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("invoice-ret-5");
        when(dal.get(Invoice.class, "invoice-ret-5")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
            .recordId("line-1").requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(-7.0, body.getDouble("invoicedQuantity"), 0.001);
      }
    }

    @Test
    @DisplayName("invoice not found in DB: returns null, body untouched")
    void invoiceNotFoundReturnsNull() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-missing")
          .put("invoicedQuantity", 5.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(eq(Invoice.class), eq("invoice-missing"))).thenReturn(null);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(5.0, body.getDouble("invoicedQuantity"), 0.001);
      }
    }

    @Test
    @DisplayName("DB exception is swallowed, handle() still returns null")
    void dbExceptionSwallowed() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-db-err")
          .put("invoicedQuantity", 3.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(Invoice.class, "invoice-db-err"))
            .thenThrow(new RuntimeException("DB down"));

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
      }
    }

    @Test
    @DisplayName("PUT method triggers same auto-negate logic as POST")
    void putMethodTriggersAutoNegate() throws Exception {
      JSONObject body = new JSONObject()
          .put("parentId", "invoice-ret-put")
          .put("invoicedQuantity", 4.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-ret-put")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(-4.0, body.getDouble("invoicedQuantity"), 0.001);
      }
    }
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
