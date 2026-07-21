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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_process.ConvertQuotationIntoOrder;
import org.openbravo.model.common.order.Order;

/**
 * Unit tests for SalesQuotationHeaderHandler.handle() and afterHandle().
 *
 * <p>Verifies that both total-discount interception paths are wired correctly:
 * <ul>
 *   <li>applyTotalDiscountBeforeComplete for documentAction=CO (CRUD PATCH path)</li>
 *   <li>syncTotalDiscountOnDocAction for DocAction process-button (SendToEvaluationModal DR→UE path)</li>
 * </ul>
 *
 * <p>ETP-4027: the {@code Convertquotation} action is intercepted in {@code handle()} and
 * delegates to {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
 * {@code recalculatePrices=false} so that quotation prices are preserved in the new order.
 */
public class SalesQuotationHeaderHandlerTest {

  private static final String QUOTATION_ID = "quotation-abc-123";

  // ── helpers ───────────────────────────────────────────────────────────────

  private static SalesQuotationHeaderHandler handlerWith(
      TotalDiscountService svc, NeoCloneRecordHandler clone) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    setField(handler, "cloneRecordHandler", clone);
    return handler;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static JSONObject bodyWith(String key, String value) {
    try {
      return new JSONObject().put(key, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ── applyTotalDiscountBeforeComplete wiring ───────────────────────────────

  /**
   * CRUD PATCH with documentAction=CO fires recalculate via applyTotalDiscountBeforeComplete.
   */
  @Test
  public void testHandle_crudPatchCO_triggersRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID)
        .requestBody(bodyWith("documentAction", "CO"))
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc).recalculate(QUOTATION_ID, false);
  }

  /**
   * CRUD PATCH with documentAction=WP does not fire recalculate.
   */
  @Test
  public void testHandle_crudPatchNotCO_noRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID)
        .requestBody(bodyWith("documentAction", "WP"))
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  // ── syncTotalDiscountOnDocAction wiring ───────────────────────────────────

  /**
   * ACTION DocAction fires recalculate via syncTotalDiscountOnDocAction
   * (mirrors the SendToEvaluationModal DR→UE path).
   */
  @Test
  public void testHandle_actionDocAction_triggersRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("DocAction")
        .recordId(QUOTATION_ID).build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc).recalculate(QUOTATION_ID, false);
  }

  /**
   * ACTION documentAction with empty body → neither path fires recalculate.
   */
  @Test
  public void testHandle_actionDocumentActionNoBody_noRecalculate() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    when(clone.handle(any())).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("documentAction")
        .recordId(QUOTATION_ID).requestBody(new JSONObject())
        .build();

    handlerWith(svc, clone).handle(ctx);

    verify(svc, never()).recalculate(anyString(), anyBoolean());
  }

  // ── clone dispatch ────────────────────────────────────────────────────────

  /**
   * handle() short-circuits when the clone handler responds.
   */
  @Test
  public void testHandle_cloneResponds_shortCircuits() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "clone"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("cloneRecord")
        .recordId(QUOTATION_ID).build();
    when(clone.handle(ctx)).thenReturn(expected);

    assertSame(expected, handlerWith(svc, clone).handle(ctx));
  }

  /**
   * handle() returns null when no downstream handler matches.
   */
  @Test
  public void testHandle_noMatchingHandler_returnsNull() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(QUOTATION_ID).build();
    when(clone.handle(ctx)).thenReturn(null);

    assertNull(handlerWith(svc, clone).handle(ctx));
  }

  // ── ETP-4027: injected handler dispatch (was previously newed, now @Inject) ─

  /**
   * Helper that builds a fully-wired handler where all six injected delegates
   * are mocks. Each mock returns null by default (Mockito behaviour), which
   * means the dispatch will fall through unless a specific mock is configured
   * to return a response.
   */
  private static SalesQuotationHeaderHandler fullyWiredHandler(
      TotalDiscountService svc,
      NeoCloneRecordHandler clone,
      CurrencyOptionsHandler currencyOptions,
      CreateDraftInvoiceHandler createDraftInvoice,
      RejectQuotationHandler rejectQuotation,
      CreateRejectReasonHandler createRejectReason) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    setField(handler, "cloneRecordHandler", clone);
    setField(handler, "currencyOptionsHandler", currencyOptions);
    setField(handler, "createDraftInvoiceHandler", createDraftInvoice);
    setField(handler, "rejectQuotationHandler", rejectQuotation);
    setField(handler, "createRejectReasonHandler", createRejectReason);
    return handler;
  }

  /**
   * ACTION currencyOptions → dispatches to the injected {@link CurrencyOptionsHandler}.
   *
   * <p>Before ETP-4027 this handler was instantiated with {@code new CurrencyOptionsHandler()},
   * which bypassed CDI. After ETP-4027 it is {@code @Inject}ed. This test verifies the
   * injected instance is the one that receives the call.
   */
  @Test
  public void testHandle_currencyOptionsAction_dispatchesToCurrencyOptionsHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("currencies", "[]"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.ACTION)
        .fieldName("currencyOptions").recordId(QUOTATION_ID).build();
    when(currencyOptionsHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(currencyOptionsHandler).handle(ctx);
  }

  /**
   * ACTION createDraftInvoice → dispatches to the injected {@link CreateDraftInvoiceHandler}.
   *
   * <p>Before ETP-4027 this was {@code new CreateDraftInvoiceHandler()}, skipping CDI and
   * leaving its own {@code @Inject} fields null (causing NPE at runtime). After ETP-4027 the
   * handler is injected — this test confirms the injected instance receives the call.
   */
  @Test
  public void testHandle_createDraftInvoiceAction_dispatchesToCreateDraftInvoiceHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.created(new JSONObject().put("id", "inv-1"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createDraftInvoice").recordId(QUOTATION_ID).build();
    when(createDraftInvoiceHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(createDraftInvoiceHandler).handle(ctx);
  }

  /**
   * ACTION rejectQuotation → dispatches to the injected {@link RejectQuotationHandler}.
   *
   * <p>Same CDI-bypass bug as createDraftInvoice — fixed in ETP-4027.
   */
  @Test
  public void testHandle_rejectQuotationAction_dispatchesToRejectQuotationHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("rejected", true));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("rejectQuotation").recordId(QUOTATION_ID).build();
    when(rejectQuotationHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(rejectQuotationHandler).handle(ctx);
  }

  /**
   * ACTION createRejectReason → dispatches to the injected {@link CreateRejectReasonHandler}.
   *
   * <p>Same CDI-bypass bug — fixed in ETP-4027.
   */
  @Test
  public void testHandle_createRejectReasonAction_dispatchesToCreateRejectReasonHandler() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoResponse expected = NeoResponse.created(new JSONObject().put("reason", "reason-1"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createRejectReason").recordId(QUOTATION_ID).build();
    when(createRejectReasonHandler.handle(ctx)).thenReturn(expected);

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertSame(expected, result);
    verify(createRejectReasonHandler).handle(ctx);
  }

  /**
   * Unknown action field name — all handlers return null, so handle() returns null.
   * Guards against over-eager short-circuiting on any unrecognised action name.
   */
  @Test
  public void testHandle_unknownAction_returnsNull() throws Exception {
    TotalDiscountService svc = mock(TotalDiscountService.class);
    NeoCloneRecordHandler clone = mock(NeoCloneRecordHandler.class);
    CurrencyOptionsHandler currencyOptionsHandler = mock(CurrencyOptionsHandler.class);
    CreateDraftInvoiceHandler createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    RejectQuotationHandler rejectQuotationHandler = mock(RejectQuotationHandler.class);
    CreateRejectReasonHandler createRejectReasonHandler = mock(CreateRejectReasonHandler.class);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unknownAction").recordId(QUOTATION_ID).build();

    NeoResponse result = fullyWiredHandler(svc, clone, currencyOptionsHandler,
        createDraftInvoiceHandler, rejectQuotationHandler, createRejectReasonHandler).handle(ctx);

    assertNull(result);
    // Every handler was consulted — none short-circuited early.
    verify(currencyOptionsHandler).handle(ctx);
    verify(createDraftInvoiceHandler).handle(ctx);
    verify(rejectQuotationHandler).handle(ctx);
    verify(createRejectReasonHandler).handle(ctx);
  }

  // ── ETP-4027: Convertquotation interception in handle() ──────────────────

  /**
   * When {@code handle()} receives a {@code Convertquotation} ACTION, it must call
   * {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
   * {@code recalculatePrices=false} and return a non-null response, short-circuiting
   * the default NEO button handler.
   *
   * <p>Passing {@code false} ensures the prices agreed in the quotation are kept in the
   * new sales order. The default behaviour ({@code true}) would re-fetch prices from the
   * active price list.
   */
  @Test
  public void testHandle_convertQuotationAction_preservesQuotationPrices() throws Exception {
    ConvertQuotationIntoOrder process = mock(ConvertQuotationIntoOrder.class);
    Order order = mock(Order.class);
    when(order.getId()).thenReturn("order-001");
    when(order.getDocumentNo()).thenReturn("SO-001");
    when(process.convertQuotationIntoSalesOrder(eq(false), eq("q-001"))).thenReturn(order);

    SalesQuotationHeaderHandler handler = fullyWiredHandler(
        mock(TotalDiscountService.class),
        mock(NeoCloneRecordHandler.class),
        mock(CurrencyOptionsHandler.class),
        mock(CreateDraftInvoiceHandler.class),
        mock(RejectQuotationHandler.class),
        mock(CreateRejectReasonHandler.class));
    setField(handler, "convertQuotationProcess", process);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("Convertquotation").recordId("q-001").build();

    NeoResponse result = handler.handle(ctx);

    // Non-null proves handle() short-circuited the default button handler.
    assertNotNull(result);
    // recalculatePrices=false is the critical invariant — quotation prices must be kept.
    verify(process).convertQuotationIntoSalesOrder(false, "q-001");
  }

  // ── afterHandle / transferCurrencyRateToNewOrder ──────────────────────────

  /**
   * When context is not ACTION+Convertquotation (e.g. a plain CRUD call),
   * afterHandle() returns null without touching the DB.
   */
  @Test
  public void testAfterHandle_nonCreateOrderAction_doesNotTransferRate() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId(QUOTATION_ID).build();

      NeoResponse result = handler.afterHandle(ctx);

      assertNull(result);
      verify(dal, never()).getConnection();
    }
  }

  /**
   * When recordId is null, transferCurrencyRateToNewOrder returns immediately
   * without any DB access (early-return guard on null quotationId).
   */
  @Test
  public void testAfterHandle_nullQuotationId_noDbAccess() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId(null).build();

      handler.afterHandle(ctx);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * When recordId is empty string, transferCurrencyRateToNewOrder returns immediately
   * without any DB access (early-return guard on empty quotationId).
   */
  @Test
  public void testAfterHandle_emptyQuotationId_noDbAccess() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("").build();

      handler.afterHandle(ctx);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * When context is ACTION with fieldName="Convertquotation" and a valid non-empty recordId,
   * afterHandle() calls transferCurrencyRateToNewOrder, which acquires a DB connection and
   * executes the SELECT for the quotation rate. The SELECT returns no row (rs.next()=false),
   * so no UPDATE is prepared. Only one getConnection() call occurs.
   */
  @Test
  public void testAfterHandle_createOrderAction_callsTransfer() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(false);
      when(ps.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(anyString())).thenReturn(ps);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-001").build();

      NeoResponse result = handler.afterHandle(ctx);

      assertNull(result);
      // transferCurrencyRateToNewOrder acquires one connection (SELECT for rate).
      verify(dal, Mockito.times(1)).getConnection();
    }
  }

  /**
   * When conn.prepareStatement throws an exception, transferCurrencyRateToNewOrder
   * must swallow it (log at WARN) and not rethrow — the overall flow must not break.
   */
  @Test
  public void testAfterHandle_exceptionSwallowed() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString()))
          .thenThrow(new java.sql.SQLException("db error"));

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-007").build();

      // Must not throw.
      NeoResponse result = handler.afterHandle(ctx);

      assertNull(result);
    }
  }

  /**
   * When recordId is null, transferCurrencyRateToNewOrder returns immediately
   * without any DB access.
   */
  @Test
  public void testTransferCurrencyRate_nullQuotationId_noDbAccess() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId(null).build();

      handler.afterHandle(ctx);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * When recordId is empty string, transferCurrencyRateToNewOrder returns immediately
   * without any DB access.
   */
  @Test
  public void testTransferCurrencyRate_emptyQuotationId_noDbAccess() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("").build();

      handler.afterHandle(ctx);

      verify(dal, never()).getConnection();
    }
  }

  /**
   * When the SELECT for the quotation rate returns no row (rs.next()=false),
   * transferCurrencyRateToNewOrder exits after the SELECT — no UPDATE statement is
   * prepared. Exactly one prepareStatement call occurs (the SELECT).
   */
  @Test
  public void testTransferCurrencyRate_nullRate_noUpdate() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement selectPs = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(false);
      when(selectPs.executeQuery()).thenReturn(rs);
      when(conn.prepareStatement(anyString())).thenReturn(selectPs);

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-002").build();

      handler.afterHandle(ctx);

      // One prepareStatement call: transferCurrencyRate SELECT only.
      // No second call for an UPDATE because rs.next()=false.
      verify(conn, Mockito.times(1)).prepareStatement(anyString());
    }
  }

  /**
   * Happy path: quotation has rate "1.5" → SELECT returns the rate, UPDATE is
   * executed with the correct parameters (BigDecimal rate at position 1, quotationId at position 2).
   *
   * <p>Two prepareStatement calls total: SELECT (selectPs) then UPDATE (updatePs).
   */
  @Test
  public void testTransferCurrencyRate_happyPath_updatesNewOrder() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement selectPs = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(rs.next()).thenReturn(true);
      when(rs.getString(1)).thenReturn("1.5");
      when(selectPs.executeQuery()).thenReturn(rs);

      PreparedStatement updatePs = mock(PreparedStatement.class);
      when(updatePs.executeUpdate()).thenReturn(1);

      when(conn.prepareStatement(anyString()))
          .thenReturn(selectPs)   // first call → SELECT rate from quotation
          .thenReturn(updatePs);  // second call → UPDATE new order rate

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-003").build();

      handler.afterHandle(ctx);

      verify(updatePs).setBigDecimal(1, new BigDecimal("1.5"));
      verify(updatePs).setString(2, "q-003");
      verify(updatePs).executeUpdate();
    }
  }

  /**
   * When conn.prepareStatement throws an exception, transferCurrencyRateToNewOrder
   * must swallow it (log at WARN) and not rethrow — the overall flow must not break.
   */
  @Test
  public void testTransferCurrencyRate_exceptionSwallowed() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString()))
          .thenThrow(new java.sql.SQLException("simulated failure"));

      SalesQuotationHeaderHandler handler = fullyWiredHandler(
          mock(TotalDiscountService.class), mock(NeoCloneRecordHandler.class),
          mock(CurrencyOptionsHandler.class), mock(CreateDraftInvoiceHandler.class),
          mock(RejectQuotationHandler.class), mock(CreateRejectReasonHandler.class));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
          .fieldName("Convertquotation").recordId("q-004").build();

      // Must not throw.
      NeoResponse result = handler.afterHandle(ctx);

      assertNull(result);
    }
  }

  // ── afterHandle GET — total discount adjustment (ETP-4029 follow-up) ─────
  //
  // Exercised through the inherited AbstractOrderHeaderHandler#afterHandle GET path — this
  // handler's own afterHandle only adds the Convertquotation currency-rate transfer, then
  // delegates via super.afterHandle(context), same as the order handlers.

  private static SalesQuotationHeaderHandler handlerWithTotalDiscountMock(
      TotalDiscountService svc) throws Exception {
    SalesQuotationHeaderHandler handler = new SalesQuotationHeaderHandler();
    setField(handler, "totalDiscountService", svc);
    return handler;
  }

  private static NeoContext getCtx(String recordId) {
    return NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).recordId(recordId).build();
  }

  private static JSONObject quotationRecordWithDiscount(boolean processed, double discount,
      double grandTotal) throws Exception {
    return new JSONObject().put("id", "quot-disc-1").put("processed", processed).put(
        "etgoTotalDiscount", discount).put("grandTotalAmount", grandTotal);
  }

  /** Stubs OBDal so the DB-backed hasLinkedDocuments check (run unconditionally after the
   *  discount loop) finds nothing, keeping these tests focused on the discount math. */
  private static void stubNoLinkedDocuments(MockedStatic<OBDal> obDalMock) throws Exception {
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(false);
  }

  @Test
  public void testAfterHandle_processedQuotation_notAdjusted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(true, 10.0, 198.99))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesQuotationHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.001);
    }
  }

  @Test
  public void testAfterHandle_draftQuotationWithNoDiscount_notAdjusted() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(false, 0.0, 221.10))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = new SalesQuotationHeaderHandler().afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(221.10, grand, 0.001);
    }
  }

  @Test
  public void testAfterHandle_draftQuotationWithMaterializedDiscountLine_notAdjustedTwice()
      throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("quot-disc-1", false)).thenReturn(true);
      SalesQuotationHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(false, 10.0, 198.99))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.001);
    }
  }

  /**
   * Verifies that a draft quotation with etgoTotalDiscount=10 and grandTotalAmount=221.10 is
   * adjusted to 198.99 (221.10 x 0.90) via the shared AbstractOrderHeaderHandler logic — the
   * same gap confirmed manually on sales-order #1000207 before this fix.
   */
  @Test
  public void testAfterHandle_draftQuotationWithDiscount_adjustsGrandTotal() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      stubNoLinkedDocuments(obDalMock);
      TotalDiscountService mockService = mock(TotalDiscountService.class);
      when(mockService.hasDiscountLine("quot-disc-1", false)).thenReturn(false);
      SalesQuotationHeaderHandler handler = handlerWithTotalDiscountMock(mockService);

      JSONObject body = new JSONObject().put("response",
          new JSONObject().put("data", new JSONArray().put(
              quotationRecordWithDiscount(false, 10.0, 221.10))));
      NeoContext ctx = getCtx("quot-disc-1");
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = handler.afterHandle(ctx);

      double grand = result.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0)
          .getDouble("grandTotalAmount");
      assertEquals(198.99, grand, 0.005);
    }
  }
}
