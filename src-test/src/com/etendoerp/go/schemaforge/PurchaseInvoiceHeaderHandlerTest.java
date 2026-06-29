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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link PurchaseInvoiceHeaderHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code afterHandle()} early-exit paths (non-GET, null/empty data).</li>
 *   <li>{@code afterHandle()} single-record enrichment — linked receipts query.</li>
 *   <li>{@code afterHandle()} list mode — no enrichment (recordId is null).</li>
 *   <li>DB error resilience in enrichLinkedReceipts.</li>
 * </ul>
 */
public class PurchaseInvoiceHeaderHandlerTest {

  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private NeoCloneRecordHandler cloneRecordHandler;

  @Mock
  private RegisterPaymentOutHandler registerPaymentOutHandler;

  @Mock
  private SiiSendHandler siiSendHandler;

  @Mock
  private TbaiXmlgeneratorHandler tbaiXmlgeneratorHandler;

  @Mock
  private TotalDiscountService totalDiscountService;

  @InjectMocks
  private PurchaseInvoiceHeaderHandler handler;

  // ── afterHandle — early exits ─────────────────────────────────────────────

  @Test
  public void afterHandle_nonGet_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandle_getWithNoPreviousResult_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandle_getWithNullBody_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, null))
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  @Test
  public void afterHandle_getWithEmptyDataArray_returnsNull() throws Exception {
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();
    assertNull(handler.afterHandle(ctx));
  }

  // ── afterHandle — list mode (no recordId) ────────────────────────────────

  @Test
  public void afterHandle_listMode_returnsOkBodyWithoutEnrichment() throws Exception {
    JSONObject rec = new JSONObject().put("id", "inv-1");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .previousResult(new NeoResponse(200, body))
        .build();

    NeoResponse result = handler.afterHandle(ctx);
    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
  }

  // ── afterHandle — single record, enrichLinkedReceipts ────────────────────

  @Test
  public void afterHandle_singleRecord_enrichesLinkedReceipts() throws Exception {
    JSONObject rec = new JSONObject().put("id", "inv-abc");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("inv-abc")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("receipt-001");
      when(rs.getString(2)).thenReturn("R-2024-001");
      when(rs.getString(3)).thenReturn("CO");

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      JSONObject enrichedRec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      JSONArray receipts = enrichedRec.getJSONArray("linkedReceipts");
      assertEquals(1, receipts.length());
      assertEquals("receipt-001", receipts.getJSONObject(0).getString("id"));
    }
  }

  // ── classifyDocType (via resolveSubtype) ─────────────────────────────────

  /**
   * Test accessor subclass that exposes resolveSubtype for direct testing.
   */
  private static class TestablePurchaseHandler extends PurchaseInvoiceHeaderHandler {
    public String callResolveSubtype(String docTypeId) {
      return resolveSubtype(docTypeId);
    }
  }

  @Test
  public void resolveSubtype_blankDocTypeId_returnsFac() {
    TestablePurchaseHandler h = new TestablePurchaseHandler();
    assertEquals("FAC", h.callResolveSubtype(null));
    assertEquals("FAC", h.callResolveSubtype(""));
    assertEquals("FAC", h.callResolveSubtype("   "));
  }

  @Test
  public void resolveSubtype_docTypeNotFound_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(DocumentType.class, "dt-unknown")).thenReturn(null);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-unknown"));
    }
  }

  @Test
  public void resolveSubtype_apcCategory_returnsNc() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("APC");
      when(dal.get(DocumentType.class, "dt-apc")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("NC", h.callResolveSubtype("dt-apc"));
    }
  }

  @Test
  public void resolveSubtype_apiWithIsReturn_returnsDev() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("API");
      when(dt.isReturn()).thenReturn(true);
      when(dal.get(DocumentType.class, "dt-api-return")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("DEV", h.callResolveSubtype("dt-api-return"));
    }
  }

  @Test
  public void resolveSubtype_apiWithoutIsReturn_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("API");
      when(dt.isReturn()).thenReturn(false);
      when(dal.get(DocumentType.class, "dt-api")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-api"));
    }
  }

  @Test
  public void resolveSubtype_otherCategory_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARO");
      when(dal.get(DocumentType.class, "dt-other")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-other"));
    }
  }

  @Test
  public void resolveSubtype_dbException_returnsFac() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(DocumentType.class, "dt-fail"))
          .thenThrow(new RuntimeException("DB error"));

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-fail"));
    }
  }

  @Test
  public void afterHandle_singleRecord_dbErrorInEnrichment_returnsNull() throws Exception {
    JSONObject rec = new JSONObject().put("id", "inv-fail");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("inv-fail")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);
      // getConnection() throws BEFORE the try-with-resources in enrichLinkedReceipts,
      // so the exception propagates to afterHandle's outer catch → null returned
      when(roInst.getConnection()).thenThrow(new RuntimeException("DB down"));

      NeoResponse result = handler.afterHandle(ctx);
      assertNull(result);
    }
  }

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    com.etendoerp.go.schemaforge.handlers.DocumentPostingService service =
        mock(com.etendoerp.go.schemaforge.handlers.DocumentPostingService.class);
    NeoContext ctx = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(ctx)).thenReturn(sentinel);

    PurchaseInvoiceHeaderHandler h = new PurchaseInvoiceHeaderHandler();
    h.setPostingService(service);

    assertSame(sentinel, h.handle(ctx));
  }

  // ── handle() — validateLineQtyBeforeComplete integration ─────────────────

  /**
   * When validateLineQtyBeforeComplete returns an error (over-invoiced line), handle()
   * must return that error immediately without proceeding to CRUD validation.
   */
  @Test
  public void handle_lineQtyValidationBlocked_returns400() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-block")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoInvoiceSupport> supportMock =
             Mockito.mockStatic(NeoInvoiceSupport.class);
         MockedStatic<OBMessageUtils> msgMock =
             Mockito.mockStatic(OBMessageUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // draftQty=8, pending=2 → over-invoiced
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-blk");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("8"));
      when(rs.getString(3)).thenReturn("inout-blk");
      when(rs.getString(4)).thenReturn("R-BLK");

      Map<String, BigDecimal> pendingMap = new HashMap<>();
      pendingMap.put("line-blk", new BigDecimal("2"));
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLine(
          Mockito.eq("inout-blk"), Mockito.eq(false))).thenReturn(pendingMap);

      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvoiceLineAlreadyInvoiced"))
          .thenReturn("Over-invoiced: @docNo@ qty @invoiced@ pending @pending@");

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * When validateLineQtyBeforeComplete passes (no over-invoiced lines), handle() proceeds
   * to validateDocTypeLock. A PUT that attempts to change doc type on a saved invoice
   * must return 400 from validateDocTypeLock.
   */
  @Test
  public void handle_lineQtyPassesButDocTypeLocked_returns400() throws Exception {
    JSONObject body = new JSONObject()
        .put("transactionDocument", "dt-new");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-locked")
        .requestBody(body)
        .build();

    // validateLineQtyBeforeComplete: no documentAction=CO → passes (returns null) immediately.
    // validateDocTypeLock: invoice exists with docNo assigned, different doc type → 400.

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      org.openbravo.model.common.enterprise.DocumentType currentDt =
          mock(org.openbravo.model.common.enterprise.DocumentType.class);
      when(dal.get(Invoice.class, "inv-locked")).thenReturn(invoice);
      when(invoice.getDocumentNo()).thenReturn("FAC-001");
      when(invoice.getTransactionDocument()).thenReturn(currentDt);
      when(currentDt.getId()).thenReturn("dt-original");

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * When validateLineQtyBeforeComplete passes, validateDocTypeLock passes, and
   * validateOriginInvoiceRequired blocks (NC subtype without origin invoice),
   * handle() returns 400 from origin invoice validation.
   */
  @Test
  public void handle_originInvoiceRequiredForNcSubtype_returns400() throws Exception {
    JSONObject body = new JSONObject()
        .put("transactionDocument", "dt-apc");
    // no originInvoice field
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(null)
        .requestBody(body)
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      org.openbravo.model.common.enterprise.DocumentType dt =
          mock(org.openbravo.model.common.enterprise.DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("APC");
      when(dal.get(org.openbravo.model.common.enterprise.DocumentType.class, "dt-apc"))
          .thenReturn(dt);

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * Non-CRUD endpoint (ACTION) with no matching downstream handler returns null.
   * Exercises the early-return path that skips CRUD validation entirely.
   */
  @Test
  public void handle_actionEndpointNoMatchingHandler_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction")
        .recordId("inv-1")
        .build();

    NeoResponse result = handler.handle(ctx);
    assertNull(result);
  }

  // ── afterHandle() — persistOriginInvoice called for POST/PUT ─────────────

  /**
   * afterHandle for PUT + CRUD endpoint calls persistOriginInvoice (then GET enrichment
   * is skipped since it is a PUT, not GET). Returns null because extractGetDataArray
   * returns null for non-GET.
   */
  @Test
  public void afterHandle_putCrud_callsPersistAndReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("originInvoice", "");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-put-ah")
        .requestBody(body)
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-put-ah")).thenReturn(invoice);

      @SuppressWarnings("unchecked")
      org.openbravo.dal.service.OBCriteria<org.openbravo.model.common.invoice.ReversedInvoice>
          criteria = mock(org.openbravo.dal.service.OBCriteria.class);
      when(dal.createCriteria(org.openbravo.model.common.invoice.ReversedInvoice.class))
          .thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(java.util.Collections.emptyList());

      NeoResponse result = handler.afterHandle(ctx);
      assertNull(result);
    }
  }

  // ── getInvoiceSubtypeKey ──────────────────────────────────────────────────

  /**
   * Verifies that the AP subtype key is "apInvoiceSubtype".
   */
  @Test
  public void getInvoiceSubtypeKey_returnsApInvoiceSubtype() throws Exception {
    // No OBDal needed since docTypeId will be blank (resolveSubtype returns FAC)
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      // Call via the afterHandle enrichment path by building a minimal GET context
      JSONObject invoiceRec = new JSONObject().put("id", "inv-key");
      JSONArray data = new JSONArray().put(invoiceRec);
      JSONObject body = new JSONObject()
          .put("response", new JSONObject().put("data", data));
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .recordId("inv-key")
          .previousResult(new NeoResponse(200, body))
          .build();

      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      NeoResponse result = handler.afterHandle(ctx);
      assertNotNull(result);
      JSONObject resultRec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      // The key set by enrichInvoiceSubtype must be "apInvoiceSubtype"
      assertNotNull("apInvoiceSubtype key must exist", resultRec.opt("apInvoiceSubtype"));
      assertEquals("FAC", resultRec.getString("apInvoiceSubtype"));
    }
  }
}
