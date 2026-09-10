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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

/**
 * Unit tests for {@link PurchaseInvoiceHeaderHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code afterHandle()} early-exit paths (non-GET, null/empty data).</li>
 *   <li>{@code afterHandle()} single-record enrichment — linked receipts query.</li>
 *   <li>{@code afterHandle()} list mode — total-discount adjustment AND subtype enrichment
 *       (ETP-4738 follow-up: {@code apInvoiceSubtype} is now injected on every row, not just
 *       detail) apply to every record; the strictly detail-only enrichments (linked receipts,
 *       origin invoice, docTypeLocked, isRectificative, hasRectifications) do not
 *       (recordId is null).</li>
 *   <li>{@code afterHandle()} total-discount adjustment for draft invoices (grandTotalAmount /
 *       outstandingAmount), inherited from {@link AbstractInvoiceHeaderHandler}.</li>
 *   <li>{@code afterHandle()} leaves the {@code eTGOTbaiStatus} column untouched and injects no
 *       synthetic {@code tbaiSyncEstado} (ETP-5216: the TicketBAI/Batuz status is now the stored
 *       computed column {@code EM_ETGO_Tbai_Status} on {@code C_Invoice}).</li>
 *   <li>{@code afterHandle()} {@link SifSubRecordAttachments} wiring — detail-only, absent from
 *       list and write responses (ETP-5087: without it the SIF tab of a purchase invoice sent to
 *       Batuz had no sub-record id and showed neither request nor response XML).</li>
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
    JSONObject resultRec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    // ETP-4738 follow-up: subtype IS now enriched on list rows (no transactionDocument on this
    // fixture → resolves to FAC without touching OBDal).
    assertEquals("FAC", resultRec.getString("apInvoiceSubtype"));
    assertFalse(resultRec.has("docTypeLocked"));
  }

  // ── afterHandle — total discount adjustment (ETP-4029 follow-up) ─────────

  private static JSONObject invoiceRecord(boolean processed, double discount, double grandTotal,
      double outstanding) throws Exception {
    return new JSONObject().put("id", "pinv-1").put("processed", processed).put(
        "etgoTotalDiscount", discount).put("grandTotalAmount", grandTotal).put(
        "outstandingAmount", outstanding);
  }

  private static NeoContext getCtx() {
    return NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
  }

  @Test
  public void afterHandle_processedInvoice_notAdjusted() throws Exception {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(invoiceRecord(true, 10.0, 470.63, 470.63))));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
        .getDouble("grandTotalAmount");
    assertEquals(470.63, grand, 0.001);
  }

  @Test
  public void afterHandle_draftWithNoDiscount_notAdjusted() throws Exception {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(invoiceRecord(false, 0.0, 470.63, 470.63))));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
        .getDouble("grandTotalAmount");
    assertEquals(470.63, grand, 0.001);
  }

  @Test
  public void afterHandle_draftWithMaterializedDiscountLine_notAdjustedTwice() throws Exception {
    when(totalDiscountService.hasDiscountLine("pinv-1", true)).thenReturn(true);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(invoiceRecord(false, 10.0, 108.90, 108.90))));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
        .getDouble("grandTotalAmount");
    assertEquals(108.90, grand, 0.001);
  }

  @Test
  public void afterHandle_draftWithDiscount_adjustsGrandTotalAndOutstanding() throws Exception {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(invoiceRecord(false, 10.0, 121.00, 121.00))));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    JSONObject rec = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals(108.90, rec.getDouble("grandTotalAmount"), 0.005);
    assertEquals(108.90, rec.getDouble("outstandingAmount"), 0.005);
  }

  @Test
  public void afterHandle_listMode_adjustsDiscountForEveryRecord() throws Exception {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "pinv-1").put("processed", false).put("etgoTotalDiscount", 10.0)
            .put("grandTotalAmount", 100.0).put("outstandingAmount", 100.0))
        .put(new JSONObject().put("id", "pinv-2").put("processed", false).put("etgoTotalDiscount", 20.0)
            .put("grandTotalAmount", 200.0).put("outstandingAmount", 200.0));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals(90.0, resultData.getJSONObject(0).getDouble("grandTotalAmount"), 0.001);
    assertEquals(160.0, resultData.getJSONObject(1).getDouble("grandTotalAmount"), 0.001);
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

  // ── afterHandle — TicketBAI status is a real column, never injected (ETP-5216) ──

  /**
   * ETP-5216: the TicketBAI/Batuz status is no longer synthesized server-side. It is the stored
   * computed AD column {@code EM_ETGO_Tbai_Status} on {@code C_Invoice}, so it reaches the
   * frontend as the ordinary contract field {@code eTGOTbaiStatus} — filterable and sortable,
   * which an injected field never was.
   *
   * <p>This test replaces the ETP-5087 injector tests rather than deleting them: it pins the
   * property those tests were really protecting (the fiscal status survives {@code afterHandle}
   * intact) while forbidding the mechanism that made ETP-4391 invisible. If anyone re-introduces
   * a per-row injection, the {@code tbaiSyncEstado} assertion fails here.
   */
  @Test
  public void afterHandle_listMode_passesThroughTbaiStatusColumnAndInjectsNothing() throws Exception {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "pinv-1").put("documentNo", "PI-001")
            .put("eTGOTbaiStatus", "Rechazado"))
        .put(new JSONObject().put("id", "pinv-2").put("documentNo", "PI-002")
            .put("eTGOTbaiStatus", "Recibido"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");
    assertEquals("Rechazado", resultData.getJSONObject(0).getString("eTGOTbaiStatus"));
    assertEquals("Recibido", resultData.getJSONObject(1).getString("eTGOTbaiStatus"));
    assertFalse("afterHandle must not re-introduce a synthetic tbaiSyncEstado field",
        resultData.getJSONObject(0).has("tbaiSyncEstado"));
    assertFalse("afterHandle must not re-introduce a synthetic tbaiSyncEstado field",
        resultData.getJSONObject(1).has("tbaiSyncEstado"));
  }

  /**
   * ETP-5216: an invoice with no resolved submission carries {@code 'Pendiente'} straight from the
   * database — {@code ETGO_GET_TBAI_STATUS} returns that literal for "no sync row / no estado", so
   * the field is always present and the handler has nothing to default. The old code left the key
   * absent and let the frontend {@code ??} invent the value, which is what let a dead injector
   * render as plausible data for months (ETP-4391).
   */
  @Test
  public void afterHandle_singleRecord_leavesTbaiStatusColumnUntouched() throws Exception {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "pinv-detail").put("eTGOTbaiStatus", "Pendiente"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("pinv-detail")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);
      dalMock.when(OBDal::getInstance).thenReturn(roInst);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("Pendiente", rec.getString("eTGOTbaiStatus"));
      assertFalse("detail view must not re-introduce a synthetic tbaiSyncEstado field",
          rec.has("tbaiSyncEstado"));
    }
  }

  // ── afterHandle — SifSubRecordAttachments wiring (ETP-5087) ──────────────

  /**
   * ETP-5087: the SIF tab of a PURCHASE invoice sent to Batuz showed neither the request nor the
   * response XML. Same root cause as the {@code tbaiSyncEstado} gap above — the call lived only in
   * {@code SalesInvoiceHeaderHandler#afterHandle}, so AP detail responses never carried
   * {@code tbaiSyncInvoiceId} (nor {@code aeatsiiFacturaId} / {@code invoiceVerifactuId}) and the
   * frontend had no sub-record id to point the attachments endpoint at.
   *
   * <p>The static {@code enrich} is stubbed to write fixture ids into the record it receives, so
   * the assertions prove both that it is invoked with the detail record and the C_Invoice id, and
   * that whatever it writes survives into the response body.
   */
  @Test
  public void afterHandle_singleRecord_injectsSifSubRecordAttachmentIds() throws Exception {
    JSONObject rec = new JSONObject().put("id", "pinv-detail");
    JSONArray data = new JSONArray().put(rec);
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .recordId("pinv-detail")
        .previousResult(new NeoResponse(200, body))
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<SifSubRecordAttachments> sifMock =
             Mockito.mockStatic(SifSubRecordAttachments.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);
      dalMock.when(OBDal::getInstance).thenReturn(roInst);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      sifMock.when(() -> SifSubRecordAttachments.enrich(any(), anyString())).thenAnswer(inv -> {
        JSONObject target = inv.getArgument(0);
        target.put("tbaiSyncInvoiceId", "tbai-sync-001");
        target.put("aeatsiiFacturaId", "sii-fact-001");
        target.put("invoiceVerifactuId", "vfac-001");
        return null;
      });

      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      sifMock.verify(() -> SifSubRecordAttachments.enrich(rec, "pinv-detail"));
      JSONObject enriched = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("tbai-sync-001", enriched.getString("tbaiSyncInvoiceId"));
      assertEquals("sii-fact-001", enriched.getString("aeatsiiFacturaId"));
      assertEquals("vfac-001", enriched.getString("invoiceVerifactuId"));
    }
  }

  /**
   * ETP-5087 / ETP-4888: the sub-record ids are a detail-only enrichment — a list GET must never
   * carry them, and must never pay for the three extra queries per row. Mirrors
   * {@code SalesInvoiceHeaderHandlerTest#testSifSubRecordAttachmentsNotCalledForListView}.
   */
  @Test
  public void afterHandle_listMode_doesNotInjectSifSubRecordAttachmentIds() throws Exception {
    JSONArray data = new JSONArray()
        .put(new JSONObject().put("id", "pinv-1"))
        .put(new JSONObject().put("id", "pinv-2"));
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", data));
    NeoContext ctx = getCtx(); // recordId = null — list view
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<SifSubRecordAttachments> sifMock =
             Mockito.mockStatic(SifSubRecordAttachments.class)) {
      NeoResponse result = handler.afterHandle(ctx);

      assertNotNull(result);
      sifMock.verifyNoInteractions();
      JSONArray resultData = result.getBody().getJSONObject("response").getJSONArray("data");
      for (int i = 0; i < resultData.length(); i++) {
        JSONObject resultRec = resultData.getJSONObject(i);
        assertFalse(resultRec.has("aeatsiiFacturaId"));
        assertFalse(resultRec.has("tbaiSyncInvoiceId"));
        assertFalse(resultRec.has("invoiceVerifactuId"));
      }
    }
  }

  /**
   * ETP-5087: like the {@code tbaiSyncEstado} injection, the sub-record lookup is GET-only — a
   * save must never pay for three fiscal-table round-trips. Pins the interaction so a refactor
   * that hoists the enrichment above the {@code extractGetDataArray() == null} guard fails here.
   */
  @Test
  public void afterHandle_writeMethods_neverInjectSifSubRecordAttachmentIds() {
    try (MockedStatic<SifSubRecordAttachments> sifMock =
             Mockito.mockStatic(SifSubRecordAttachments.class)) {
      for (String writeMethod : new String[] { "POST", "PUT", "PATCH" }) {
        NeoContext ctx = NeoContext.builder().httpMethod(writeMethod).build();
        assertNull(handler.afterHandle(ctx));
      }
      sifMock.verifyNoInteractions();
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
  public void resolveSubtype_apcCategory_returnsRectificativa() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("APC");
      when(dal.get(DocumentType.class, "dt-apc")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("RECTIFICATIVA", h.callResolveSubtype("dt-apc"));
    }
  }

  @Test
  public void resolveSubtype_apiWithIsReturn_returnsRectificativa() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("API");
      when(dt.isReturn()).thenReturn(true);
      when(dal.get(DocumentType.class, "dt-api-return")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("RECTIFICATIVA", h.callResolveSubtype("dt-api-return"));
    }
  }

  /**
   * ETP-4737: the new unified rectificative doc type is driven primarily by the
   * {@code EM_Etsg_Isrectificative} flag, independent of {@code documentCategory} — proven here
   * with an otherwise-FAC category ("API", no isReturn) that only classifies as RECTIFICATIVA
   * because the flag is set.
   */
  @Test
  public void resolveSubtype_rectificativeFlagSet_returnsRectificativaRegardlessOfCategory() {
    AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("API");
      when(dt.isReturn()).thenReturn(false);
      when(dt.isEtsgIsRectificative()).thenReturn(true);
      when(dal.get(DocumentType.class, "dt-new-rectificativa")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("RECTIFICATIVA", h.callResolveSubtype("dt-new-rectificativa"));
    } finally {
      AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(null);
    }
  }

  /**
   * When the rectificative column is not present (SIF General not installed), classification
   * falls back to the legacy category-based rule even though the mock would otherwise report the
   * flag as set.
   */
  @Test
  public void resolveSubtype_rectificativeColumnAbsent_fallsBackToCategory() {
    AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(false);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("API");
      when(dt.isReturn()).thenReturn(false);
      when(dal.get(DocumentType.class, "dt-api-no-column")).thenReturn(dt);

      TestablePurchaseHandler h = new TestablePurchaseHandler();
      assertEquals("FAC", h.callResolveSubtype("dt-api-no-column"));
    } finally {
      AbstractInvoiceHeaderHandler.setRectificativeColumnPresentForTests(null);
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
   * Regression for ETP-4496: saving a Purchase Invoice with Document Type = Credit Note
   * (APC category / NC subtype) without an Origin Invoice must NOT be blocked at save time.
   * {@code validateOriginInvoiceRequired} is no longer invoked from {@code handle()}'s CRUD
   * path — Etendo Classic treats the origin invoice as optional, and ETP-4036 had already
   * deliberately removed this exact save-time block, until a later shared-code refactor
   * (ETP-4035) accidentally reintroduced it.
   *
   * <p>With that call gone, the request falls through validateLineQtyBeforeComplete (no
   * documentAction=CO), applyTotalDiscountBeforeComplete/completeInvoiceIfNeeded (same reason),
   * and validateDocTypeLock (not a PUT), reaching {@code NeoHeaderActionRouter.dispatch}, where
   * none of the mocked downstream handlers answer — proving handle() never short-circuits with
   * a 400 from origin invoice validation.
   */
  @Test
  public void handle_creditNoteWithoutOriginInvoice_doesNotBlock() throws Exception {
    JSONObject body = new JSONObject()
        .put("transactionDocument", "dt-apc");
    // no originInvoice field
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(null)
        .requestBody(body)
        .build();

    NeoResponse result = handler.handle(ctx);

    assertNull("origin-invoice validation must no longer block Credit Note save", result);
  }

  /**
   * Regression for ETP-4496: the same removed call site also used to block Purchase Return
   * Invoices (API category + isReturn / DEV subtype) without an Origin Invoice. Confirms the
   * fix is not NC-specific — saving a Return Invoice without an origin invoice must not be
   * blocked either.
   */
  @Test
  public void handle_returnInvoiceWithoutOriginInvoice_doesNotBlock() throws Exception {
    JSONObject body = new JSONObject()
        .put("transactionDocument", "dt-api-return");
    // no originInvoice field
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(null)
        .requestBody(body)
        .build();

    NeoResponse result = handler.handle(ctx);

    assertNull("origin-invoice validation must no longer block Return Invoice save", result);
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
    // ETP-4919: persistOriginInvoice is now a no-op when the captured id set is blank (there is
    // no supported way to unlink an origin invoice through this endpoint) — so a non-blank id is
    // required here for the test to actually exercise the persist path instead of vacuously
    // hitting that early-return.
    JSONObject body = new JSONObject().put("originInvoice", "inv-origin-ah");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-put-ah")
        .requestBody(body)
        .build();
    // ETP-4737: persistOriginInvoice now only acts on the value captureOriginInvoice() captured
    // in handle() (the pre-hook) — call it here too so this test actually exercises the persist
    // path instead of vacuously hitting the "not captured" early-return.
    handler.captureOriginInvoice(ctx);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-put-ah")).thenReturn(invoice);
      Invoice originInvoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-origin-ah")).thenReturn(originInvoice);

      @SuppressWarnings("unchecked")
      org.openbravo.dal.service.OBCriteria<org.openbravo.model.common.invoice.ReversedInvoice>
          criteria = mock(org.openbravo.dal.service.OBCriteria.class);
      when(dal.createCriteria(org.openbravo.model.common.invoice.ReversedInvoice.class))
          .thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      // Pretend the link already exists (ETP-4919 dedupe check) — proves persistOriginInvoice
      // reached the DB layer without needing to mock the OBProvider/save() creation path too.
      when(criteria.list()).thenReturn(
          java.util.Collections.singletonList(
              mock(org.openbravo.model.common.invoice.ReversedInvoice.class)));

      NeoResponse result = handler.afterHandle(ctx);
      assertNull(result);

      // persistOriginInvoice actually ran for PUT: given a non-blank originInvoice id, it looked
      // up both the invoice and the origin invoice and checked for an existing reverse link.
      // atLeastOnce(): autoCreateOrUpdateConversionRateDocument (called unconditionally earlier
      // in afterHandle()) also does its own dal.get(Invoice.class, recordId) lookup.
      Mockito.verify(dal, Mockito.atLeastOnce()).get(Invoice.class, "inv-put-ah");
      Mockito.verify(dal).get(Invoice.class, "inv-origin-ah");
      Mockito.verify(dal).createCriteria(org.openbravo.model.common.invoice.ReversedInvoice.class);
    }
  }

  /**
   * ETP-4737 regression: the write-method guard around {@code persistOriginInvoice} used to be
   * {@code "POST".equals(method) || "PUT".equals(method)}, which silently excluded PATCH — but
   * {@code ImportFromSourceInvoiceModal.afterImport} (schema_forge frontend) always links the
   * origin invoice via a PATCH, not POST/PUT. Fixed to {@code NeoHandlerUtils.isWriteMethod},
   * which includes PATCH. This test proves PATCH now reaches {@code persistOriginInvoice} (it
   * would previously have skipped straight past it, leaving {@code C_Invoice_Reverse} empty).
   */
  @Test
  public void afterHandle_patchCrud_callsPersistAndReturnsNull() throws Exception {
    // ETP-4919: persistOriginInvoice is now a no-op when the captured id set is blank (there is
    // no supported way to unlink an origin invoice through this endpoint) — so a non-blank id is
    // required here for the test to actually exercise the persist path instead of vacuously
    // hitting that early-return.
    JSONObject body = new JSONObject().put("originInvoice", "inv-origin-ph");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-patch-ah")
        .requestBody(body)
        .build();
    // Mirrors the real handle() pre-hook so persistOriginInvoice has a captured value to act on.
    handler.captureOriginInvoice(ctx);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-patch-ah")).thenReturn(invoice);
      Invoice originInvoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-origin-ph")).thenReturn(originInvoice);

      @SuppressWarnings("unchecked")
      org.openbravo.dal.service.OBCriteria<org.openbravo.model.common.invoice.ReversedInvoice>
          criteria = mock(org.openbravo.dal.service.OBCriteria.class);
      when(dal.createCriteria(org.openbravo.model.common.invoice.ReversedInvoice.class))
          .thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      // Pretend the link already exists (ETP-4919 dedupe check) — proves persistOriginInvoice
      // reached the DB layer without needing to mock the OBProvider/save() creation path too.
      when(criteria.list()).thenReturn(
          java.util.Collections.singletonList(
              mock(org.openbravo.model.common.invoice.ReversedInvoice.class)));

      NeoResponse result = handler.afterHandle(ctx);
      assertNull(result);

      // persistOriginInvoice actually ran for PATCH: given a non-blank originInvoice id, it
      // looked up both the invoice and the origin invoice and checked for an existing reverse
      // link. atLeastOnce(): autoCreateOrUpdateConversionRateDocument (called unconditionally
      // earlier in afterHandle()) also does its own dal.get(Invoice.class, recordId) lookup.
      Mockito.verify(dal, Mockito.atLeastOnce()).get(Invoice.class, "inv-patch-ah");
      Mockito.verify(dal).get(Invoice.class, "inv-origin-ph");
      Mockito.verify(dal).createCriteria(org.openbravo.model.common.invoice.ReversedInvoice.class);
    }
  }

  // ── handle() — ETP-4388: discount recalculation must precede completion ─────

  /**
   * Regression for the review finding on ETP-4388: {@code completeInvoiceIfNeeded} must not
   * short-circuit {@code handle()} before {@code applyTotalDiscountBeforeComplete} runs, or the
   * discount line would be stale/missing when the document is completed. Verifies both that
   * {@code totalDiscountService.recalculate(...)} is actually invoked for a completion request,
   * and that it runs BEFORE {@code ProcessInvoiceUtil.process(...)}.
   */
  @Test
  public void handle_completionAction_recalculatesDiscountBeforeCompleting() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-discount-co")
        .requestBody(body)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError success = new OBError();
    success.setType("Success");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        Mockito.eq("inv-discount-co"), Mockito.eq("CO"), Mockito.eq(""), Mockito.eq(""), Mockito.eq(""),
        any(), any()))
        .thenReturn(success);

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      // validateLineQtyBeforeComplete guard: no linked shipment lines → passes.
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);
      when(dal.get(Process.class, "111")).thenReturn(mock(Process.class));

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      InOrder order = Mockito.inOrder(totalDiscountService, processInvoiceUtil);
      order.verify(totalDiscountService).recalculate("inv-discount-co", true);
      order.verify(processInvoiceUtil).process(
          Mockito.eq("inv-discount-co"), Mockito.eq("CO"), Mockito.eq(""), Mockito.eq(""), Mockito.eq(""),
          any(), any());
    }
  }

  /**
   * Non-completion requests must not trigger discount recalculation at all — only the CO path
   * does (guarded by {@code applyTotalDiscountBeforeComplete}'s own completion check).
   */
  @Test
  public void handle_nonCompletionAction_doesNotRecalculateDiscount() throws Exception {
    JSONObject body = new JSONObject().put("someOtherField", "x");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-not-completing")
        .requestBody(body)
        .build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      handler.handle(ctx);

      Mockito.verifyNoInteractions(totalDiscountService);
    }
  }

  /**
   * ACTION-shape completion request (POST /action/documentAction with
   * {@code fieldValues.documentAction=CO} — the shape sent by the draft-mode confirm button) must
   * also recalculate the discount BEFORE completing. The CRUD-shape ordering is covered above;
   * this closes the shape-coverage gap noted during the ETP-4388 QA pass (only the CRUD shape was
   * exercised for the discount-before-completion regression, on either AR or AP).
   */
  @Test
  public void handle_actionShapeCompletionAction_recalculatesDiscountBeforeCompleting()
      throws Exception {
    JSONObject fieldValues = new JSONObject().put("documentAction", "CO");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId("inv-action-discount-co")
        .requestBody(body)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError success = new OBError();
    success.setType("Success");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        Mockito.eq("inv-action-discount-co"), Mockito.eq("CO"), Mockito.eq(""), Mockito.eq(""), Mockito.eq(""),
        any(), any()))
        .thenReturn(success);

    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      // validateLineQtyBeforeComplete guard: no linked shipment lines → passes.
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);
      when(dal.get(Process.class, "111")).thenReturn(mock(Process.class));

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      InOrder order = Mockito.inOrder(totalDiscountService, processInvoiceUtil);
      order.verify(totalDiscountService).recalculate("inv-action-discount-co", true);
      order.verify(processInvoiceUtil).process(
          Mockito.eq("inv-action-discount-co"), Mockito.eq("CO"), Mockito.eq(""), Mockito.eq(""), Mockito.eq(""),
          any(), any());
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

  // ── ETP-5238: paymentMethod SELECTOR is independent of Financial Account ────

  /**
   * A SELECTOR context wired with an AD Tab/Window pair whose {@code isSalesTransaction()} is
   * {@code false} — the purchase-invoice window declares {@link
   * com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport.DirectionFallback#WINDOW},
   * so this is what the handler resolves direction from once the {@code IsSOTrx} request param
   * is absent.
   */
  private static NeoContext purchaseWindowSelectorCtx() {
    Window window = mock(Window.class);
    when(window.isSalesTransaction()).thenReturn(false);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return NeoContext.builder()
        .specName("purchase-invoice").entityName("header").httpMethod("GET")
        .endpointType(NeoEndpointType.SELECTOR).fieldName("paymentMethod")
        .adTab(tab).build();
  }

  private void stubPaymentMethodSelectorRequest(MockedStatic<RequestContext> reqCtx,
      Map<String, String> params) {
    RequestContext requestContext = mock(RequestContext.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    reqCtx.when(RequestContext::get).thenReturn(requestContext);
    when(requestContext.getRequest()).thenReturn(request);
    when(request.getParameter(anyString())).thenReturn(null);

    Map<String, String[]> parameterMap = new HashMap<>();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      parameterMap.put(entry.getKey(), new String[] { entry.getValue() });
    }
    when(request.getParameterMap()).thenReturn(parameterMap);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Criterion> stubPaymentMethodCriteria(MockedStatic<OBDal> obDal,
      List<FIN_PaymentMethod> rows) {
    OBDal obDalMock = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(obDalMock);
    OBCriteria<FIN_PaymentMethod> criteria = mock(OBCriteria.class);
    when(obDalMock.createCriteria(FIN_PaymentMethod.class)).thenReturn(criteria);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(criteria.add(captor.capture())).thenReturn(criteria);
    when(criteria.addOrderBy(any(), anyBoolean())).thenReturn(criteria);
    when(criteria.setMaxResults(org.mockito.ArgumentMatchers.anyInt())).thenReturn(criteria);
    when(criteria.setFirstResult(org.mockito.ArgumentMatchers.anyInt())).thenReturn(criteria);
    when(criteria.count()).thenReturn(rows.size());
    when(criteria.list()).thenReturn(rows);
    return captor;
  }

  /** Renders every captured restriction (read only AFTER invoking the method under test). */
  private List<String> renderedCriteria(ArgumentCaptor<Criterion> captor) {
    return captor.getAllValues().stream().map(Criterion::toString)
        .collect(java.util.stream.Collectors.toList());
  }

  /**
   * The paymentMethod SELECTOR request must be served by
   * {@code com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport}, short-circuiting
   * before {@code postingService}/{@code totalDiscountService} or any ACTION delegate is reached,
   * and — since this window declares the {@code WINDOW} direction fallback — the criteria built
   * when {@code IsSOTrx} is absent must be against {@code payoutAllow}, never {@code payinAllow}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorIndependentOfFinancialAccount() throws Exception {
    NeoContext ctx = purchaseWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      stubPaymentMethodSelectorRequest(reqCtx, Collections.emptyMap());

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = handler.handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payoutAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
      assertFalse("must not filter on payinAllow for a purchase document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
    }
  }

  /**
   * Same window, but with the request explicitly sending {@code IsSOTrx=N} (the normal case in
   * production) — must resolve identically to the absent-param case above.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleServesPaymentMethodSelectorBuildsPayoutCriteriaWhenIsSOTrxIsN() throws Exception {
    NeoContext ctx = purchaseWindowSelectorCtx();

    try (MockedStatic<RequestContext> reqCtx = Mockito.mockStatic(RequestContext.class);
        MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBContext> obCtxMock = Mockito.mockStatic(OBContext.class)) {
      Map<String, String> params = new HashMap<>();
      params.put("IsSOTrx", "N");
      stubPaymentMethodSelectorRequest(reqCtx, params);

      ArgumentCaptor<Criterion> captor = stubPaymentMethodCriteria(obDal, Collections.emptyList());

      NeoResponse response = handler.handle(ctx);

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      List<String> rendered = renderedCriteria(captor);
      assertTrue("expected a payoutAllow filter, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payoutallow")));
      assertFalse("must not filter on payinAllow for a purchase document, got: " + rendered,
          rendered.stream().anyMatch(s -> s.toLowerCase().contains("payinallow")));
    }
  }

  /**
   * Non-selector requests are completely unaffected by the new SELECTOR branch (no-regression).
   */
  @Test
  public void testHandleStillReturnsNullForNonSelectorRequestAfterPaymentMethodWiring() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(ctx));
  }
}
