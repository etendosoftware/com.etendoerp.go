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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
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

    // ── ETP-4737: sourceInvoiceLineId capture + strip (Import from Source Invoice) ──
    //
    // sourceInvoiceLineId is a virtual signal field (not in decisions.json/contract) for the
    // "Import from Source Invoice" popup. It must be captured + stripped from the raw POST body
    // BEFORE the generic field filter runs, or afterHandle()'s persistSourceInvoiceLine would
    // silently find nothing left to persist (same bypass-the-filter pattern as
    // AbstractInvoiceHeaderHandler#captureOriginInvoice).

    @Test
    @DisplayName("POST body with sourceInvoiceLineId only (no invoicedQuantity) is stripped, handle returns null")
    void postSourceInvoiceLineIdCapturedAndStripped() throws Exception {
      JSONObject body = new JSONObject().put("sourceInvoiceLineId", "source-line-1");

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();

      assertNull(handler.handle(ctx));
      assertEquals(false, body.has("sourceInvoiceLineId"));
    }

    @Test
    @DisplayName("PATCH body with sourceInvoiceLineId is NOT stripped (capture is POST-only)")
    void patchSourceInvoiceLineIdNotStripped() throws Exception {
      JSONObject body = new JSONObject().put("sourceInvoiceLineId", "source-line-2");

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();

      assertNull(handler.handle(ctx));
      assertEquals("source-line-2", body.getString("sourceInvoiceLineId"));
    }

    @Test
    @DisplayName("POST body without sourceInvoiceLineId: nothing breaks, returns null")
    void postBodyWithoutSourceInvoiceLineIdDoesNotCrash() throws Exception {
      JSONObject body = new JSONObject().put("someOtherField", "value");

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();

      assertNull(handler.handle(ctx));
      assertEquals(true, body.has("someOtherField"));
    }

    @Test
    @DisplayName("POST body with both sourceInvoiceLineId and invoicedQuantity: field is stripped, invoicedQuantity logic still runs")
    void postBothFieldsPresent_sourceLineIdStrippedInvoicedQtyLogicStillRuns() throws Exception {
      JSONObject body = new JSONObject()
          .put("sourceInvoiceLineId", "source-line-3")
          .put("parentId", "invoice-ret-src")
          .put("invoicedQuantity", 5.0);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Invoice invoice = mock(Invoice.class);
        DocumentType docType = mock(DocumentType.class);
        when(dal.get(Invoice.class, "invoice-ret-src")).thenReturn(invoice);
        when(invoice.getTransactionDocument()).thenReturn(docType);
        when(docType.isReturn()).thenReturn(true);

        NeoContext ctx = NeoContext.builder()
            .specName("sales-invoice").entityName("lines")
            .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
            .requestBody(body).build();

        assertNull(handler.handle(ctx));
        assertEquals(false, body.has("sourceInvoiceLineId"));
        assertEquals(-5.0, body.getDouble("invoicedQuantity"), 0.001);
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

    // ── ETP-4941: productCode enrichment ──────────────────────────────────

    /**
     * GET on sales/purchase invoice lines must inject {@code productCode} (M_Product.Value)
     * into each line, resolved against {@code c_invoiceline} — mutates the response body in
     * place so it's visible regardless of whether DiscountLineFilter later replaces the
     * response.
     */
    @Test
    void getRequestEnrichesProductCodeFromCInvoiceline() throws Exception {
      JSONObject line = new JSONObject().put("id", "inv-line-1");
      JSONArray dataArr = new JSONArray().put(line);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", dataArr));

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .previousResult(NeoResponse.ok(body)).build();

      discountFilterMock.when(() -> DiscountLineFilter.filterFromResponse(any()))
          .thenReturn(null);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dal.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("inv-line-1");
        when(rs.getString(2)).thenReturn("SKU-INV-1");

        handler.afterHandle(ctx);

        assertEquals("SKU-INV-1", line.getString("productCode"));

        org.mockito.ArgumentCaptor<String> sqlCaptor =
            org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(conn).prepareStatement(sqlCaptor.capture());
        assertEquals(true, sqlCaptor.getValue().contains("c_invoiceline"));
      }
    }

    /**
     * A line whose product has no SKU (blank {@code M_Product.Value}) must NOT get a
     * {@code productCode} field written — the frontend's {@code resolveProductCode} then falls
     * back to "—", per the ETP-4941 acceptance criteria (never the line number).
     */
    @Test
    void getRequestLeavesProductCodeAbsentWhenSkuBlank() throws Exception {
      JSONObject line = new JSONObject().put("id", "inv-line-no-sku");
      JSONArray dataArr = new JSONArray().put(line);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", dataArr));

      NeoContext ctx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("GET").endpointType(NeoEndpointType.CRUD)
          .previousResult(NeoResponse.ok(body)).build();

      discountFilterMock.when(() -> DiscountLineFilter.filterFromResponse(any()))
          .thenReturn(null);

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dal.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("inv-line-no-sku");
        when(rs.getString(2)).thenReturn("");

        handler.afterHandle(ctx);

        assertEquals(false, line.has("productCode"));
      }
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

    // ── ETP-4737: persistSourceInvoiceLine — consumes the value captured in handle() ──

    /**
     * End-to-end roundtrip on the SAME handler instance: handle() captures + strips
     * sourceInvoiceLineId from the raw POST body, afterHandle() then persists it onto the newly
     * created line via the self-referencing FK ({@code EM_Etgo_Source_Invoiceline_ID}). This is
     * the exact regression the fix addresses — before it, the generic field filter stripped the
     * value before afterHandle() ever got a chance to read it, making the persist a permanent
     * silent no-op (confirmed via an empty column in production before the fix).
     */
    @Test
    @DisplayName("POST: sourceInvoiceLineId captured in handle() is persisted in afterHandle() (ETP-4737)")
    void postPersistsCapturedSourceInvoiceLineId() throws Exception {
      JSONObject createBody = new JSONObject().put("sourceInvoiceLineId", "src-line-1");
      NeoContext createCtx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(createBody).build();

      assertNull(handler.handle(createCtx));
      assertEquals(false, createBody.has("sourceInvoiceLineId"));

      JSONObject createdLine = new JSONObject().put("id", "new-line-1");
      JSONArray dataArray = new JSONArray().put(createdLine);
      JSONObject response = new JSONObject().put("data", dataArray);
      JSONObject respBody = new JSONObject().put("response", response);
      NeoResponse prevResult = new NeoResponse(201, respBody);

      NeoContext afterCtx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .recordId(null).previousResult(prevResult).build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine newLine = mock(InvoiceLine.class);
        Invoice parentInvoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "new-line-1")).thenReturn(newLine);
        when(newLine.getInvoice()).thenReturn(parentInvoice);
        when(parentInvoice.getId()).thenReturn("invoice-parent-src");
        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(inv -> null);

        InvoiceLine sourceLine = mock(InvoiceLine.class);
        when(dal.get(InvoiceLine.class, "src-line-1")).thenReturn(sourceLine);

        assertNull(handler.afterHandle(afterCtx));

        Mockito.verify(newLine).setETGOSourceInvoiceLine(sourceLine);
        Mockito.verify(dal).save(newLine);
        Mockito.verify(dal).flush();
      }
    }

    /**
     * When the body never carried sourceInvoiceLineId, {@code pendingSourceInvoiceLineId} stays
     * null and {@code persistSourceInvoiceLine} must no-op without touching OBDal for the source
     * line lookup — no exception, nothing persisted.
     */
    @Test
    @DisplayName("POST: no sourceInvoiceLineId captured — afterHandle persist step is a no-op")
    void postWithoutCapturedSourceInvoiceLineIdSkipsPersist() throws Exception {
      JSONObject createdLine = new JSONObject().put("id", "new-line-2");
      JSONArray dataArray = new JSONArray().put(createdLine);
      JSONObject response = new JSONObject().put("data", dataArray);
      JSONObject respBody = new JSONObject().put("response", response);
      NeoResponse prevResult = new NeoResponse(201, respBody);

      NeoContext afterCtx = NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .recordId(null).previousResult(prevResult).build();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);

        InvoiceLine newLine = mock(InvoiceLine.class);
        Invoice parentInvoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "new-line-2")).thenReturn(newLine);
        when(newLine.getInvoice()).thenReturn(parentInvoice);
        when(parentInvoice.getId()).thenReturn("invoice-parent-nosrc");
        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(inv -> null);

        // NOTE: handle() was never called on this fresh handler instance — pendingSourceInvoiceLineId
        // is at its default (null) value, exactly like a real line save that never carried the field.
        assertNull(handler.afterHandle(afterCtx));

        Mockito.verify(newLine, never()).setETGOSourceInvoiceLine(any());
        Mockito.verify(dal, never()).save(newLine);
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

  @Nested
  @DisplayName("shouldAutoFillExemptionCause — exempt detection (ETP-4751)")
  class ShouldAutoFillExemptionCause {

    private boolean invokeShouldAutoFill(String invoiceId) throws Exception {
      java.lang.reflect.Method m = InvoiceLineHandler.class
          .getDeclaredMethod("shouldAutoFillExemptionCause", String.class);
      m.setAccessible(true);
      return (boolean) m.invoke(handler, invoiceId);
    }

    /**
     * Sales invoice, no cause set, an exempt active line present → auto-fill qualifies (true).
     * Also asserts the SQL detects exempt taxes via c_invoiceline and NEVER via the lingering
     * c_invoicetax branch (ETP-4751 — Go drafts don't recompute c_invoicetax, so it would report
     * a stale exempt=true after the exempt line was removed).
     */
    @Test
    @DisplayName("exempt active line → true, and SQL is line-only (no c_invoicetax)")
    void exemptLine_true_lineOnlySql() throws Exception {
      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
        java.sql.Connection conn = mock(java.sql.Connection.class);
        when(dal.getConnection()).thenReturn(conn);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        org.mockito.ArgumentCaptor<String> sqlCaptor =
            org.mockito.ArgumentCaptor.forClass(String.class);
        when(conn.prepareStatement(sqlCaptor.capture())).thenReturn(ps);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("issotrx")).thenReturn("Y");
        when(rs.getString("em_aeatsii_cause_exemption_id")).thenReturn(null);
        when(rs.getBoolean("has_exempt")).thenReturn(true);

        assertEquals(true, invokeShouldAutoFill("inv-1"));

        String sql = sqlCaptor.getValue().toLowerCase();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("c_invoiceline"),
            "must detect exempt taxes via c_invoiceline");
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("c_invoicetax"),
            "must NOT use the lingering c_invoicetax branch (ETP-4751)");
      }
    }

    /**
     * Sales invoice with no exempt active line → does not qualify (false). This is the
     * "exempt line removed" case: with the c_invoicetax branch gone, has_exempt is driven purely
     * by the current active lines, so removing the last exempt line correctly returns false.
     */
    @Test
    @DisplayName("no exempt active line → false")
    void noExemptLine_false() throws Exception {
      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
        java.sql.Connection conn = mock(java.sql.Connection.class);
        when(dal.getConnection()).thenReturn(conn);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("issotrx")).thenReturn("Y");
        when(rs.getString("em_aeatsii_cause_exemption_id")).thenReturn(null);
        when(rs.getBoolean("has_exempt")).thenReturn(false);

        assertEquals(false, invokeShouldAutoFill("inv-1"));
      }
    }
  }

  /**
   * ETP-4751 — the {@code autoFillExemptionCauseAfterLineSave} ORCHESTRATION driven end-to-end
   * through {@link InvoiceLineHandler#afterHandle} on a line save. The leaf detection
   * ({@code shouldAutoFillExemptionCause}) is covered above; this suite covers the three
   * mutually-exclusive outcomes of the orchestration and the {@code augmentResponseWithSignal}
   * body mutation, which had no direct coverage:
   * <ul>
   *   <li>exempt tax present + no header cause + <b>no</b> default cause → response augmented with
   *       {@code exemptionCauseWarning=true} (the Etendo-GO-dormant-autofill / warning path);</li>
   *   <li>exempt tax present + no header cause + a default cause exists → the default is written and
   *       the response is augmented with {@code exemptionCauseAutoFilled=true} (autofill path);</li>
   *   <li>invoice does not qualify (e.g. purchase invoice / cause already set) → {@code null},
   *       the original response is left untouched (no-op path).</li>
   * </ul>
   * Driven via a PATCH so {@code resolveParentInvoiceIdAfterSave} resolves the parent invoice
   * directly from the line record ({@code context.getRecordId()}), keeping the test independent
   * of the POST previousResult-parsing path (already covered elsewhere).
   */
  @Nested
  @DisplayName("autoFillExemptionCauseAfterLineSave — signal orchestration (ETP-4751)")
  class AutoFillExemptionCauseAfterLineSave {

    /**
     * Builds a PATCH line-save context whose previousResult carries a minimal CRUD body, so
     * augmentResponseWithSignal has a body to mutate. The header sync hook is stubbed to a no-op.
     */
    private NeoContext patchCtxWithBody() throws Exception {
      JSONObject savedLine = new JSONObject().put("id", "line-1");
      JSONArray data = new JSONArray().put(savedLine);
      JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
      return NeoContext.builder()
          .specName("sales-invoice").entityName("lines")
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId("line-1")
          .previousResult(NeoResponse.ok(body))
          .build();
    }

    /** Wires the OBDal read-only connection that shouldAutoFillExemptionCause + findDefault query. */
    private void wireQueries(OBDal dal, java.sql.Connection conn, boolean qualifies,
        String defaultCauseId) throws Exception {
      when(dal.getConnection()).thenReturn(conn);

      // shouldAutoFillExemptionCause SELECT
      java.sql.PreparedStatement qualifyPs = mock(java.sql.PreparedStatement.class);
      java.sql.ResultSet qualifyRs = mock(java.sql.ResultSet.class);
      when(qualifyPs.executeQuery()).thenReturn(qualifyRs);
      when(qualifyRs.next()).thenReturn(true);
      when(qualifyRs.getString("issotrx")).thenReturn("Y");
      when(qualifyRs.getString("em_aeatsii_cause_exemption_id")).thenReturn(null);
      when(qualifyRs.getBoolean("has_exempt")).thenReturn(qualifies);

      // findDefaultCauseExemptionId SELECT
      java.sql.PreparedStatement defaultPs = mock(java.sql.PreparedStatement.class);
      java.sql.ResultSet defaultRs = mock(java.sql.ResultSet.class);
      when(defaultPs.executeQuery()).thenReturn(defaultRs);
      when(defaultRs.next()).thenReturn(defaultCauseId != null);
      if (defaultCauseId != null) {
        when(defaultRs.getString(1)).thenReturn(defaultCauseId);
      }

      // Route each SQL to the matching statement by content.
      when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
        String sql = ((String) inv.getArgument(0)).toLowerCase();
        return sql.contains("aeatsii_cause_exemption") && sql.contains("isdefault")
            ? defaultPs
            : qualifyPs;
      });
    }

    /** WARNING path: exempt, no header cause, NO default cause → exemptionCauseWarning=true. */
    @Test
    @DisplayName("exempt + no default cause → response augmented with exemptionCauseWarning=true")
    void warningWhenNoDefaultCause() throws Exception {
      NeoContext ctx = patchCtxWithBody();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
        ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(i -> null);

        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);

        // resolveParentInvoiceIdAfterSave: line-1 → invoice inv-1
        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "line-1")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("inv-1");

        java.sql.Connection conn = mock(java.sql.Connection.class);
        wireQueries(dal, conn, true, null);

        NeoResponse result = handler.afterHandle(ctx);

        org.junit.jupiter.api.Assertions.assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertTrue(
            result.getBody().getBoolean(InvoiceLineHandler.FIELD_EXEMPTION_CAUSE_WARNING));
        org.junit.jupiter.api.Assertions.assertFalse(
            result.getBody().has(InvoiceLineHandler.FIELD_EXEMPTION_CAUSE_AUTOFILLED));
        // No UPDATE statement is prepared when there is no default cause (nothing written).
        Mockito.verify(conn, never()).prepareStatement(
            org.mockito.ArgumentMatchers.matches("(?is)^update c_invoice.*"));
      }
    }

    /** AUTOFILL path: exempt, no header cause, default cause exists → autoFilled=true + UPDATE run. */
    @Test
    @DisplayName("exempt + default cause exists → cause written + exemptionCauseAutoFilled=true")
    void autoFillsWhenDefaultCauseExists() throws Exception {
      NeoContext ctx = patchCtxWithBody();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
        ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
        OBContext obContext = mock(OBContext.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn("user-1");
        when(obContext.getUser()).thenReturn(user);
        ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(i -> null);

        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "line-1")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("inv-1");

        java.sql.Connection conn = mock(java.sql.Connection.class);
        // The UPDATE statement also goes through conn.prepareStatement; capture it.
        java.sql.PreparedStatement updatePs = mock(java.sql.PreparedStatement.class);
        when(dal.getConnection()).thenReturn(conn);

        // shouldAutoFill SELECT
        java.sql.PreparedStatement qualifyPs = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet qualifyRs = mock(java.sql.ResultSet.class);
        when(qualifyPs.executeQuery()).thenReturn(qualifyRs);
        when(qualifyRs.next()).thenReturn(true);
        when(qualifyRs.getString("issotrx")).thenReturn("Y");
        when(qualifyRs.getString("em_aeatsii_cause_exemption_id")).thenReturn(null);
        when(qualifyRs.getBoolean("has_exempt")).thenReturn(true);
        // findDefault SELECT
        java.sql.PreparedStatement defaultPs = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet defaultRs = mock(java.sql.ResultSet.class);
        when(defaultPs.executeQuery()).thenReturn(defaultRs);
        when(defaultRs.next()).thenReturn(true);
        when(defaultRs.getString(1)).thenReturn("cause-default-1");

        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
          String sql = ((String) inv.getArgument(0)).toLowerCase();
          if (sql.startsWith("update c_invoice")) {
            return updatePs;
          }
          if (sql.contains("isdefault")) {
            return defaultPs;
          }
          return qualifyPs;
        });

        NeoResponse result = handler.afterHandle(ctx);

        org.junit.jupiter.api.Assertions.assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertTrue(
            result.getBody().getBoolean(InvoiceLineHandler.FIELD_EXEMPTION_CAUSE_AUTOFILLED));
        org.junit.jupiter.api.Assertions.assertFalse(
            result.getBody().has(InvoiceLineHandler.FIELD_EXEMPTION_CAUSE_WARNING));
        // The default cause was written to the invoice.
        Mockito.verify(updatePs).setString(1, "cause-default-1");
        Mockito.verify(updatePs).setString(3, "inv-1");
        Mockito.verify(updatePs).executeUpdate();
      }
    }

    /** NO-OP path: invoice does not qualify (e.g. cause already set) → null, no signal added. */
    @Test
    @DisplayName("does not qualify → null (original response untouched, no signal)")
    void noOpWhenInvoiceDoesNotQualify() throws Exception {
      NeoContext ctx = patchCtxWithBody();

      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
           MockedStatic<AbstractInvoiceHeaderHandler> headerMock =
               mockStatic(AbstractInvoiceHeaderHandler.class)) {
        ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
        ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
        headerMock.when(() ->
            AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(anyString()))
            .thenAnswer(i -> null);

        OBDal dal = mock(OBDal.class);
        dalMock.when(OBDal::getInstance).thenReturn(dal);
        dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);

        InvoiceLine line = mock(InvoiceLine.class);
        Invoice invoice = mock(Invoice.class);
        when(dal.get(InvoiceLine.class, "line-1")).thenReturn(line);
        when(line.getInvoice()).thenReturn(invoice);
        when(invoice.getId()).thenReturn("inv-1");

        java.sql.Connection conn = mock(java.sql.Connection.class);
        when(dal.getConnection()).thenReturn(conn);
        java.sql.PreparedStatement qualifyPs = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet qualifyRs = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(qualifyPs);
        when(qualifyPs.executeQuery()).thenReturn(qualifyRs);
        when(qualifyRs.next()).thenReturn(true);
        // cause already selected → does not qualify
        when(qualifyRs.getString("issotrx")).thenReturn("Y");
        when(qualifyRs.getString("em_aeatsii_cause_exemption_id")).thenReturn("existing-cause");
        when(qualifyRs.getBoolean("has_exempt")).thenReturn(true);

        NeoResponse result = handler.afterHandle(ctx);

        assertNull(result);
      }
    }
  }
}
