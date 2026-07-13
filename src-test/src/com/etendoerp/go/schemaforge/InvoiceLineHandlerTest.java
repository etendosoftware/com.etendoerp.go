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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
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

    // ── ETP-4029: syncConversionRateDocumentAfterLineSave ──────────────────

    /**
     * PATCH/PUT: the line already exists, so {@code context.getRecordId()} is the line ID
     * and can be resolved directly via {@code OBDal.get(InvoiceLine.class, ...)} — no need
     * to consult {@code getPreviousResult()}.
     */
    @Test
    @DisplayName("PATCH: resolves parent invoice via recordId and syncs conversion rate doc")
    void patchResolvesParentInvoiceViaRecordIdAndSyncs() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId("line-1").build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "line-1")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("invoice-parent-1");

        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(inv -> null);

        assertNull(handler.afterHandle(ctx));

        headerMock.verify(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument("invoice-parent-1"));
      }
    }

    /**
     * Critical case (the actual production bug fixed today, per manual QA on invoice
     * 200611A7D11E4EE3B3B9DEEA607015D9): POST creating a new line. {@code context.getRecordId()}
     * is null/blank at this point, so the parent invoice ID must come from
     * {@code context.getPreviousResult()}'s CRUD response body, where {@code response.data} is
     * the REAL shape produced by {@code DefaultJsonDataService.add()} — a {@code JSONArray}
     * containing exactly one element (the created line), never a plain {@code JSONObject}.
     */
    @Test
    @DisplayName("POST: resolves parent invoice from previousResult's one-element JSONArray data")
    void postResolvesParentInvoiceFromPreviousResultArrayShape() throws Exception {
      JSONObject createdLine = new JSONObject().put("id", "new-line-1");
      JSONArray dataArray = new JSONArray().put(createdLine);
      JSONObject response = new JSONObject().put("data", dataArray);
      JSONObject respBody = new JSONObject().put("response", response);
      NeoResponse prevResult = new NeoResponse(201, respBody);

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .recordId(null).previousResult(prevResult).build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "new-line-1")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("invoice-from-post");

        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(inv -> null);

        assertNull(handler.afterHandle(ctx));

        headerMock.verify(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument("invoice-from-post"));
      }
    }

    /**
     * Old/broken assumption: {@code response.data} as a plain {@code JSONObject} instead of a
     * one-element {@code JSONArray}. This is NOT the real shape {@code DefaultJsonDataService}
     * produces, and current code no longer relies on it anywhere. This test proves the handler
     * does NOT crash on this malformed shape, but ALSO does not silently "succeed" by extracting
     * an ID from it — {@code extractCreatedIdFromPreviousResult} must return null (since
     * {@code optJSONArray("data")} returns null for a non-array value), so no invoice ID is
     * resolved and the sync is skipped entirely.
     */
    @Test
    @DisplayName("POST: old JSONObject-shaped data does not crash and does not resolve an ID")
    void postWithOldObjectShapedDataDoesNotCrashNorResolveId() throws Exception {
      JSONObject dataAsPlainObject = new JSONObject().put("id", "should-not-be-used");
      JSONObject response = new JSONObject().put("data", dataAsPlainObject);
      JSONObject respBody = new JSONObject().put("response", response);
      NeoResponse prevResult = new NeoResponse(201, respBody);

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .recordId(null).previousResult(prevResult).build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(
                org.mockito.ArgumentMatchers.<String>isNull()))
            .thenAnswer(inv -> null);

        assertNull(handler.afterHandle(ctx));

        // No line lookup should even be attempted — the ID could not be resolved at all.
        Mockito.verify(dal, never()).get(eq(InvoiceLine.class), any());
        // The shared upsert core is invoked with a null invoiceId (its own no-op guard
        // handles that), proving the malformed shape fails closed rather than resolving
        // a bogus ID from the plain JSONObject.
        headerMock.verify(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(
                org.mockito.ArgumentMatchers.<String>isNull()));
      }
    }

    @Test
    @DisplayName("PUT: also triggers conversion rate sync (same as POST/PATCH)")
    void putTriggersConversionRateSync() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
          .recordId("line-put").build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "line-put")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("invoice-put");

        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(inv -> null);

        assertNull(handler.afterHandle(ctx));

        headerMock.verify(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument("invoice-put"));
      }
    }

    @Test
    @DisplayName("recordId blank and no previousResult: sync is skipped, no exception")
    void blankRecordIdAndNoPreviousResultSkipsSyncWithoutException() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .recordId(null).build();
      // no previousResult set

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(
                org.mockito.ArgumentMatchers.<String>isNull()))
            .thenAnswer(inv -> null);

        assertNull(handler.afterHandle(ctx));

        Mockito.verify(dal, never()).get(eq(InvoiceLine.class), any());
      }
    }

    @Test
    @DisplayName("line found but has no parent invoice: sync resolves null invoiceId, no exception")
    void lineWithoutInvoiceResolvesNullInvoiceId() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId("line-orphan").build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        when(dal.get(InvoiceLine.class, "line-orphan")).thenReturn(line);
        when(line.getInvoice()).thenReturn(null);

        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(
                org.mockito.ArgumentMatchers.<String>isNull()))
            .thenAnswer(inv -> null);

        assertNull(handler.afterHandle(ctx));

        headerMock.verify(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(
                org.mockito.ArgumentMatchers.<String>isNull()));
      }
    }

    @Test
    @DisplayName("exception while resolving parent invoice id is swallowed, afterHandle still returns null")
    void exceptionDuringResolveIsSwallowed() {
      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId("line-err").build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        when(dal.get(InvoiceLine.class, "line-err")).thenThrow(new RuntimeException("DB down"));

        assertNull(handler.afterHandle(ctx));
      }
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
