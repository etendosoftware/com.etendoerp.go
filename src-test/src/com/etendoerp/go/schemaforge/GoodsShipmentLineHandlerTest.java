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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.junit.After;
import org.junit.Before;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * Unit tests for {@link GoodsShipmentLineHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} — returns the 422 for a capped create or update, and null otherwise so
 *       the default CRUD proceeds.</li>
 *   <li>{@code afterHandle()} — guard conditions that short-circuit before DB access:
 *       non-GET method, missing previous result, null body, and empty data array.</li>
 *   <li>ETP-6002 CP-22 — {@code invoiceLineId} declared as a create directive (the MCP no longer
 *       reports it in {@code unknownFields}) and the sales link: the first shipment of an invoice
 *       line in its column, a later partial one in {@code M_MatchSI}.</li>
 *   <li>ETP-6002 CP-14 — a quotation line is never a shipment line's order line.</li>
 *   <li>ETP-6002 QA BUG-1 — a create with {@code invoiceLineId} and no {@code salesOrderLine}
 *       takes the invoice line's order line (MCP parity with the window).</li>
 *   <li>ETP-6002 CP-30 — the quantity of a line is capped by the invoice line it ships
 *       ({@link ShipmentLineInvoiceCap}): the create pre-hook (refusal, default to the pending,
 *       REST and MCP), the update of a linked line, and the re-check under the invoice lock after
 *       the create, inside and outside {@code /batch}, failures included.</li>
 * </ul>
 *
 * <p>Tests that require DB access (orderLineQty / productCode enrichment via SQL)
 * are not included here — those are covered by integration tests.
 *
 * @covers com.etendoerp.go.schemaforge.GoodsShipmentLineHandler
 * @covers com.etendoerp.go.schemaforge.SalesQuotations
 * @covers com.etendoerp.go.schemaforge.ShipmentLineInvoiceCap
 */
public class GoodsShipmentLineHandlerTest {

  /** ETP-5819: the handler only serves the caller's own invoice lines; every id here is one. */
  private MockedStatic<TenantOwnership> ownership;

  @Before
  public void ownEveryRecord() {
    ownership = Mockito.mockStatic(TenantOwnership.class);
    ownership.when(() -> TenantOwnership.loadOwned(Mockito.eq(InvoiceLine.class),
        Mockito.anyString())).thenReturn(Mockito.mock(InvoiceLine.class));
  }

  @After
  public void closeOwnership() {
    ownership.close();
  }

  /**
   * Builds a GET/CRUD {@link NeoContext} targeting the goods-shipment line entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("goods-shipment")
        .entityName("lines")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle always returns null, allowing the default CRUD to proceed.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  /**
   * Verifies that handle returns null even for POST/ACTION contexts.
   */
  @Test
  public void testHandleAlwaysReturnsNullForPostContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .build();
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response has no "response" wrapper object.
   */
  @Test
  public void testAfterHandleReturnsNullWhenResponseWrapperAbsent() throws Exception {
    JSONObject body = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("id", "line-1")));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  // ── handle() POST + invoiceLineId ─────────────────────────────────────────

  /**
   * When handle() receives a POST with an invoiceLineId in the body it must still
   * return null so that the default CRUD continues.
   */
  @Test
  public void testHandlePostWithInvoiceLineIdReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-42");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  /**
   * When handle() receives a POST with a null body the invoiceLineId ThreadLocal
   * must not be set and the method must return null.
   */
  @Test
  public void testHandlePostWithNullBodyReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(null)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  /**
   * When handle() receives a POST with an empty invoiceLineId value the ThreadLocal
   * must not be set and the method must return null.
   */
  @Test
  public void testHandlePostWithEmptyInvoiceLineIdReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("invoiceLineId", "");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    assertNull(new GoodsShipmentLineHandler().handle(ctx));
  }

  // ── afterHandle() POST path ───────────────────────────────────────────────

  /**
   * afterHandle() with POST and no invoiceLineId in ThreadLocal must return null
   * cleanly (linkInvoiceLineIfPresent exits early, ThreadLocal is cleaned up).
   */
  @Test
  public void testAfterHandlePostWithNoPendingInvoiceLineIdReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
  }

  /**
   * afterHandle() with POST when invoiceLineId was stored by handle() but the
   * previousResult is null — linkInvoiceLineIfPresent exits the guard early and
   * the method must return null. The ThreadLocal must be cleared by the finally block.
   */
  @Test
  public void testAfterHandlePostWithInvoiceLineIdButNullPreviousResultReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    // Arm the ThreadLocal via handle() first
    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-99");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    handler.handle(handleCtx);

    // afterHandle() without a previousResult — linkInvoiceLineIfPresent returns early
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  /**
   * afterHandle() with POST when invoiceLineId was stored by handle() but the
   * previousResult has a null body — guard in linkInvoiceLineIfPresent exits early.
   */
  @Test
  public void testAfterHandlePostWithInvoiceLineIdButNullBodyReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    JSONObject body = new JSONObject().put("invoiceLineId", "inv-line-77");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    handler.handle(handleCtx);

    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(new NeoResponse(201, null))
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  /**
   * afterHandle() with POST when previousResult has a body but no "response" wrapper
   * — guard in linkInvoiceLineIfPresent exits early; returns null.
   */
  @Test
  public void testAfterHandlePostNoResponseWrapperReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    JSONObject reqBody = new JSONObject().put("invoiceLineId", "inv-line-55");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(reqBody)
        .build();
    handler.handle(handleCtx);

    JSONObject respBody = new JSONObject().put("id", "line-new");
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(respBody))
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  /**
   * afterHandle() with POST when previousResult has a "response" wrapper but the
   * "data" array is empty — guard in linkInvoiceLineIfPresent exits early.
   */
  @Test
  public void testAfterHandlePostEmptyDataArrayReturnsNull() throws Exception {
    GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

    JSONObject reqBody = new JSONObject().put("invoiceLineId", "inv-line-33");
    NeoContext handleCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(reqBody)
        .build();
    handler.handle(handleCtx);

    JSONObject respBody = new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()));
    NeoContext afterCtx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(respBody))
        .build();
    assertNull(handler.afterHandle(afterCtx));
  }

  // ── afterHandle() GET enrichment path ────────────────────────────────────

  /**
   * afterHandle() GET with a non-empty data array but fetchLineData returning an
   * empty map (simulated by making OBDal.getConnection() throw so fetchLineData
   * returns an empty result silently) — the response is still returned as
   * NeoResponse.ok with the original body; lines have no extra fields added.
   */
  @Test
  public void testAfterHandleGetWithFetchLineDataThrowingReturnsOkWithOriginalBody() throws Exception {
    JSONObject line = new JSONObject().put("id", "line-1");
    JSONArray dataArr = new JSONArray().put(line);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // getConnection() throws — fetchLineData catches internally and returns empty map
      when(dal.getConnection()).thenThrow(new RuntimeException("DB unavailable"));

      NeoResponse result = new GoodsShipmentLineHandler().afterHandle(ctx);

      assertNotNull("Should return ok response even when DB is unavailable", result);
      assertSame(body, result.getBody());
      assertFalse("no enrichment without line data", line.has("invoicedQuantity"));
    }
  }

  /**
   * afterHandle() GET must return null when an exception escapes the outer try block: here the
   * data array holds a value that is not a record, so reading it as one throws.
   */
  @Test
  public void testAfterHandleGetReturnsNullOnUnexpectedException() throws Exception {
    JSONArray dataArr = new JSONArray().put("not-a-record");
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("simulate DB error"));

      assertNull(new GoodsShipmentLineHandler().afterHandle(ctx));
    }
  }

  // ── afterCallout() — stock-derived movementQuantity (ETP-5062) ───────────

  /**
   * ETP-5062: reverses the ETP-4671 carve-out documented by the (now removed)
   * {@code testAfterCalloutDoesNotStripMovementQuantity}. That decision assumed prefilling a
   * manually-added Goods Shipment line with the product's full on-hand quantity was a helpful
   * default ("pick from what's in stock"). In practice a user can confirm the shipment without
   * noticing the preselected value and move the entire warehouse stock by accident — the same
   * risk ETP-4671 already treated as unacceptable for Goods Receipt. {@code afterCallout()} must
   * now strip the stock-derived {@code movementQuantity} for Goods Shipment too, exactly like
   * {@link GoodsReceiptLineHandler} already does, so a manually-added line always starts at the
   * row's own default (0) instead of silently jumping to the on-hand qty.
   */
  @Test
  public void testAfterCalloutStripsStockDerivedMovementQuantity() throws Exception {
    // Simulates the exact shape NeoCalloutService.transformResponse() produces after running
    // SL_InOutLine_Product for a product with 297 units on hand and no pending order import.
    JSONObject updates = new JSONObject()
        .put("movementQuantity", new JSONObject().put("value", 297))
        .put("uOM", new JSONObject().put("value", "Unit"));
    JSONObject prevBody = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(prevBody)).build();

    assertNull(new GoodsShipmentLineHandler().afterCallout(ctx));

    assertFalse("movementQuantity must be stripped — a manually-added shipment line must not "
        + "silently prefill the moved quantity with the product's current on-hand stock",
        prevBody.getJSONObject("updates").has("movementQuantity"));
    assertTrue("Unrelated fields (e.g. a resolved UOM) must survive untouched",
        prevBody.getJSONObject("updates").has("uOM"));
  }

  /**
   * afterCallout() must leave the response untouched when the callout returned no updates at
   * all (e.g. a callout on a field other than product).
   */
  @Test
  public void testAfterCalloutLeavesResponseUntouchedWhenNoUpdates() throws Exception {
    JSONObject prevBody = new JSONObject().put("combos", new JSONObject());
    NeoContext ctx = NeoContext.builder().endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(prevBody)).build();

    assertNull(new GoodsShipmentLineHandler().afterCallout(ctx));
    assertFalse(prevBody.has("updates"));
  }

  // ── handle() — storageBin default injection (ETP-4863) ───────────────────

  /**
   * Reproduces ETP-4863: confirming a Goods Shipment (Albarán de Venta) with the header on
   * the PRINCIPAL warehouse must never leave the created line's {@code storageBin} pointing
   * at a different (e.g. stale session-cached "secondary") warehouse. {@link
   * GoodsReceiptLineHandler} already anchors its line's locator to the header's own warehouse
   * (ETP-4671); {@link GoodsShipmentLineHandler} must do the same on POST when {@code
   * storageBin} is missing, instead of relying on the raw AD {@code @OnHandLocatorDefault@}
   * default, which falls back to a stale session-cached warehouse when the tab does not
   * declare the matching {@code AD_AuxiliaryInput}.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostInjectsWarehouseDefaultLocatorWhenStorageBinMissing() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1").put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(new GoodsShipmentLineHandler().handle(ctx));
      assertEquals("A shipment line's storageBin must always resolve to the header warehouse's "
          + "own default locator, never to a stale session-cached warehouse",
          "loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  /**
   * handle() POST must not override an explicit storageBin already supplied on the request
   * when it ALREADY belongs to the header's warehouse (user selection or an "Import from..."
   * flow) — same guard as {@link GoodsReceiptLineHandler} (ETP-4863 BUG-1: the guarantee is
   * about the warehouse, not about forcing every line onto the warehouse's single "default"
   * locator).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostDoesNotOverrideExplicitStorageBin() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "shipment-1")
        .put("storageBin", "loc-explicit");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("shipment-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      Locator existingLocator = mock(Locator.class);
      when(dal.get(eq(Locator.class), eq("loc-explicit"))).thenReturn(existingLocator);
      when(existingLocator.getWarehouse()).thenReturn(warehouse);

      assertNull(new GoodsShipmentLineHandler().handle(ctx));
      assertEquals("loc-explicit", body.getString("storageBin"));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  // ── ETP-6002 CP-22 — invoiceLineId: directive and sales link ──────────────

  /**
   * {@code invoiceLineId} is a create directive of the shipment line: the MCP write verbs read the
   * directives to decide what is unknown, so declaring it is what keeps it out of
   * {@code unknownFields} while the link it asks for is written.
   */
  @Test
  public void createDirectivesDeclareInvoiceLineId() {
    java.util.Map<String, String> directives = new GoodsShipmentLineHandler().createDirectives();

    assertEquals(java.util.Set.of("invoiceLineId"), directives.keySet());
    assertTrue(directives.get("invoiceLineId").contains("sales invoice line"));
  }

  /** Arms the handler with {@code invoiceLineId}, then runs the POST post-hook for the new line. */
  private static void createWithInvoiceLine(GoodsShipmentLineHandler handler, String invoiceLineId,
      String newLineId) throws Exception {
    handler.handle(NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(new JSONObject().put("invoiceLineId", invoiceLineId)).build());
    JSONObject created = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray().put(new JSONObject().put("id", newLineId))));
    assertNull(handler.afterHandle(NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD).previousResult(new NeoResponse(201, created)).build()));
  }

  /**
   * The first shipment of an invoice line is recorded in its column and no match row is written
   * (M_INOUT_POST writes it on completion); a later partial shipment, whose invoice line already
   * points at another shipment line, gets its {@code M_MatchSI} row now. Before ETP-6002 the
   * second one was not linked at all, so an MCP partial shipment without an order line left the
   * rest of the invoice line looking unshipped forever.
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void createLinksTheInvoiceLineInItsColumnThenInTheSalesMatchTable() throws Exception {
    for (int updated : new int[] { 1, 0 }) {
      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class)) {
        context.when(OBContext::getOBContext).thenReturn(null);
        OBDal dal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        org.hibernate.Session session = mock(org.hibernate.Session.class);
        when(dal.getSession()).thenReturn(session);
        org.hibernate.query.NativeQuery query = mock(org.hibernate.query.NativeQuery.class);
        List<String> statements = new ArrayList<>();
        when(session.createNativeQuery(Mockito.anyString())).thenAnswer(call -> {
          statements.add(call.getArgument(0));
          return query;
        });
        when(query.setParameter(Mockito.anyString(), Mockito.any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(updated);

        createWithInvoiceLine(new GoodsShipmentLineHandler(), "inv-line-1", "ship-line-2");

        String label = updated == 1 ? "first shipment" : "later partial shipment";
        assertTrue(label, statements.get(0).contains("UPDATE C_InvoiceLine")
            && statements.get(0).contains("M_InOutLine_ID IS NULL"));
        assertEquals(label, updated == 1 ? 1 : 2, statements.size());
        if (updated == 0) {
          assertTrue(label, statements.get(1).contains("INSERT INTO m_matchsi"));
        }
        Mockito.verify(query, Mockito.atLeastOnce()).setParameter("inoutLineId", "ship-line-2");
        Mockito.verify(query, Mockito.atLeastOnce()).setParameter("invoiceLineId", "inv-line-1");
      }
    }
  }

  // ── ETP-6002 CP-14 — a quotation line is never a shipment line's order line ──

  /** An order line of an order whose target doc type has {@code DocSubTypeSO = subType}. */
  private static OrderLine orderLineOfSubType(String subType) {
    DocumentType docType = mock(DocumentType.class);
    when(docType.getSOSubType()).thenReturn(subType);
    Order order = mock(Order.class);
    when(order.getTransactionDocument()).thenReturn(docType);
    OrderLine orderLine = mock(OrderLine.class);
    when(orderLine.getSalesOrder()).thenReturn(order);
    return orderLine;
  }

  /**
   * The window's «Añadir desde Factura» popup copies the invoice line's order line; an invoice
   * made straight from a quotation carries quotation lines, and completing the shipment then
   * marked the quotation delivered and related it to the shipment (QA CP-14). The pre-hook drops
   * a quotation line from any CRUD write and keeps a sales-order line (order ↔ shipment is
   * direct). Reads, actions and an order line that is not the caller's own are left alone.
   */
  @Test
  public void dropsAQuotationOrderLineFromAWriteAndKeepsAnOrderLine() throws Exception {
    OrderLine quotationLine = orderLineOfSubType("OB");
    OrderLine salesOrderLine = orderLineOfSubType("SO");
    ownership.when(() -> TenantOwnership.loadOwned(OrderLine.class, "ol-quotation"))
        .thenReturn(quotationLine);
    ownership.when(() -> TenantOwnership.loadOwned(OrderLine.class, "ol-order"))
        .thenReturn(salesOrderLine);
    Object[][] cases = {
        // label, method, endpoint, order line id, kept
        { "POST of a quotation line", "POST", NeoEndpointType.CRUD, "ol-quotation", false },
        { "PATCH to a quotation line", "PATCH", NeoEndpointType.CRUD, "ol-quotation", false },
        { "POST of a sales-order line", "POST", NeoEndpointType.CRUD, "ol-order", true },
        { "an order line that is not the caller's", "POST", NeoEndpointType.CRUD, "ol-foreign",
            true },
        { "not a write", "GET", NeoEndpointType.CRUD, "ol-quotation", true },
        { "not a CRUD call", "POST", NeoEndpointType.ACTION, "ol-quotation", true },
    };
    for (Object[] c : cases) {
      JSONObject body = new JSONObject().put("salesOrderLine", c[3]).put("invoiceLineId", "il-1");
      NeoContext ctx = NeoContext.builder().httpMethod((String) c[1])
          .endpointType((NeoEndpointType) c[2]).requestBody(body).build();

      GoodsShipmentLineHandler.dropQuotationOrderLine(ctx);

      assertEquals((String) c[0], c[4], body.has("salesOrderLine"));
      assertTrue((String) c[0] + ": the invoice line link stays", body.has("invoiceLineId"));
    }
  }

  // ── ETP-6002 QA BUG-1 — salesOrderLine derived from invoiceLineId ─────────

  /** An invoice line whose own order line is {@code orderLine} (or none). */
  private static InvoiceLine invoiceLineFrom(OrderLine orderLine) {
    InvoiceLine invoiceLine = mock(InvoiceLine.class);
    when(invoiceLine.getSalesOrderLine()).thenReturn(orderLine);
    return invoiceLine;
  }

  /**
   * QA BUG-1: over MCP {@code salesOrderLine} is a system field the agent never sees, so a line
   * created with only {@code invoiceLineId} from an invoice of a regular sales order carried no
   * order line (the order stayed undelivered and {@code availableOrderLines} offered it again).
   * The pre-hook now takes the invoice line's order line, as the window sends it. A quotation
   * line is never taken (CP-14), an explicit {@code salesOrderLine} is kept, another tenant's
   * invoice line is ignored, and only a CRUD create does it.
   */
  @Test
  public void deriveOrderLineFromInvoiceLineOnlyForARegularOrderOnACreate() throws Exception {
    OrderLine orderLine = orderLineOfSubType("SO");
    when(orderLine.getId()).thenReturn("ol-order");
    OrderLine quotationLine = orderLineOfSubType("OB");
    when(quotationLine.getId()).thenReturn("ol-quotation");
    InvoiceLine fromOrder = invoiceLineFrom(orderLine);
    InvoiceLine fromQuotation = invoiceLineFrom(quotationLine);
    InvoiceLine withoutOrder = invoiceLineFrom(null);
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, "il-order"))
        .thenReturn(fromOrder);
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, "il-quotation"))
        .thenReturn(fromQuotation);
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, "il-none"))
        .thenReturn(withoutOrder);
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, "il-foreign"))
        .thenReturn(null);
    Object[][] cases = {
        // label, method, endpoint, invoiceLineId, salesOrderLine sent, expected salesOrderLine
        { "regular order line copied", "POST", NeoEndpointType.CRUD, "il-order", null,
            "ol-order" },
        { "quotation line not copied", "POST", NeoEndpointType.CRUD, "il-quotation", null, null },
        { "invoice line without an order line", "POST", NeoEndpointType.CRUD, "il-none", null,
            null },
        { "another tenant's invoice line ignored", "POST", NeoEndpointType.CRUD, "il-foreign",
            null, null },
        { "explicit salesOrderLine kept", "POST", NeoEndpointType.CRUD, "il-order", "ol-sent",
            "ol-sent" },
        { "an update never derives", "PATCH", NeoEndpointType.CRUD, "il-order", null, null },
        { "not a CRUD call", "POST", NeoEndpointType.ACTION, "il-order", null, null },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      JSONObject body = new JSONObject().put("invoiceLineId", c[3]);
      if (c[4] != null) {
        body.put("salesOrderLine", c[4]);
      }
      NeoContext ctx = NeoContext.builder().httpMethod((String) c[1])
          .endpointType((NeoEndpointType) c[2]).requestBody(body).build();

      GoodsShipmentLineHandler.deriveOrderLineFromInvoiceLine(ctx);

      assertEquals(label, c[5], body.has("salesOrderLine") ? body.getString("salesOrderLine")
          : null);
    }
  }

  /**
   * The window's path and the quotation drop still compose in {@code handle}: a quotation line
   * the window sent is dropped and NOT re-derived from the same invoice line; a regular order
   * line the window sent stays.
   */
  @Test
  public void handleDropsAQuotationLineAndDoesNotDeriveItBack() throws Exception {
    OrderLine quotationLine = orderLineOfSubType("OB");
    when(quotationLine.getId()).thenReturn("ol-quotation");
    InvoiceLine fromQuotation = invoiceLineFrom(quotationLine);
    ownership.when(() -> TenantOwnership.loadOwned(OrderLine.class, "ol-quotation"))
        .thenReturn(quotationLine);
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, "il-quotation"))
        .thenReturn(fromQuotation);
    JSONObject body = new JSONObject().put("invoiceLineId", "il-quotation")
        .put("salesOrderLine", "ol-quotation");

    GoodsShipmentLineHandler.dropQuotationOrderLine(NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD).requestBody(body).build());
    GoodsShipmentLineHandler.deriveOrderLineFromInvoiceLine(NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).requestBody(body).build());

    assertFalse(body.has("salesOrderLine"));
  }

  // ── ETP-6002 CP-30 — the quantity is capped by the invoice line's pending: helpers ──

  private static final String INVOICE_ID = "inv-1";
  private static final String INVOICE_LINE_ID = "il-1";
  private static final String SHIPMENT_LINE_ID = "ship-1";
  private static final String FIELD_QTY = "movementQuantity";

  /** Makes {@code il-1} the caller's own line of the invoice {@code inv-1}. */
  private void ownInvoiceLineOfInvoice() {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn(INVOICE_ID);
    InvoiceLine invoiceLine = mock(InvoiceLine.class);
    when(invoiceLine.getInvoice()).thenReturn(invoice);
    when(invoiceLine.getId()).thenReturn(INVOICE_LINE_ID);
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, INVOICE_LINE_ID))
        .thenReturn(invoiceLine);
  }

  /** A completed invoice with {@code qty} pending on {@code lineId}. */
  private static PendingResolver.Source pendingOf(String invoiceId, String lineId, String qty) {
    return PendingResolver.Source.available(invoiceId,
        List.of(new PendingResolver.SourceLine(lineId, new BigDecimal(qty))));
  }

  private static PendingResolver.Source nothingPending(String invoiceId) {
    return PendingResolver.Source.unavailable(invoiceId, FollowUpException.Reason.NOTHING_PENDING);
  }

  private static PendingResolver.Source notCompleted(String invoiceId) {
    return PendingResolver.Source.unavailable(invoiceId, FollowUpException.Reason.NOT_COMPLETED);
  }

  /** The POST post-hook context of a line just created with id {@code newLineId}. */
  private static NeoContext postCreated(String newLineId) throws Exception {
    JSONObject created = new JSONObject().put("response", new JSONObject()
        .put("data", new JSONArray().put(new JSONObject().put("id", newLineId))));
    return NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .previousResult(new NeoResponse(201, created)).build();
  }

  /**
   * Runs a POST post-hook with no result: it clears the invoice line a previous request may have
   * left armed on this thread, so what a case arms is only what its own pre-hook armed.
   */
  private static void clearArmedLink(GoodsShipmentLineHandler handler) {
    handler.afterHandle(NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD).build());
  }

  /** The invoice lines linked to a movement line, as {@code invoice line id, invoice id} pairs. */
  private static ResultSet linkRows(String... lineAndInvoice) throws Exception {
    int[] cursor = { -1 };
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenAnswer(inv -> (++cursor[0]) * 2 < lineAndInvoice.length);
    when(rs.getString(anyInt())).thenAnswer(inv -> {
      int column = inv.getArgument(0);
      return lineAndInvoice[cursor[0] * 2 + column - 1];
    });
    return rs;
  }

  private static void assertQuantity(String label, String expected, Object actual) {
    assertEquals(label, 0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
  }

  // ── CP-30 — the create pre-hook ───────────────────────────────────────────

  /**
   * The create of a line carrying one of the caller's {@code invoiceLineId}: a quantity up to the
   * pending passes and the link is armed; above it, 0, negative, nothing pending or an invoice
   * that is not completed (no line pending) answer 422 {@code importQuantityExceedsPending} and
   * nothing is armed. Without a quantity the line takes the pending — on REST when the body has
   * none, on MCP when the key set the caller sent ({@code ATTR_CLIENT_PROVIDED_FIELDS}) lacks it,
   * whatever the defaults pass put there. A value the caller sent is never overwritten. A
   * non-numeric quantity and another tenant's invoice line are left as they were, without
   * locking the invoice. QA CP-30 is the "one above the pending" row: an invoice line with 2
   * pending accepted a line of 3, because only the popup limited the quantity. Every refusal
   * names the pending it was checked against.
   */
  @Test
  public void createWithAnInvoiceLineIsCappedByItsPendingAndDefaultsToIt() throws Exception {
    ownInvoiceLineOfInvoice();
    ownership.when(() -> TenantOwnership.loadOwned(InvoiceLine.class, "il-foreign"))
        .thenReturn(null);
    PendingResolver.Source twoPending = pendingOf(INVOICE_ID, INVOICE_LINE_ID, "2");
    Set<String> mcpWithoutQty = Set.of("invoiceLineId", "description");
    Set<String> mcpWithQty = Set.of("invoiceLineId", FIELD_QTY);
    Object[][] cases = {
        // label, invoiceLineId, quantity in the body (null: absent), MCP keys sent (null: REST),
        // the invoice's verdict, refused, quantity after the pre-hook (null: absent), locked, linked
        { "equal to the pending", INVOICE_LINE_ID, 2, null, twoPending, false, "2", true, true },
        { "one above the pending", INVOICE_LINE_ID, 3, null, twoPending, true, "3", true, false },
        { "nothing pending", INVOICE_LINE_ID, 1, null, nothingPending(INVOICE_ID), true, "1",
            true, false },
        { "zero", INVOICE_LINE_ID, 0, null, twoPending, true, "0", true, false },
        { "negative", INVOICE_LINE_ID, -1, null, twoPending, true, "-1", true, false },
        { "invoice not completed", INVOICE_LINE_ID, 1, null, notCompleted(INVOICE_ID), true, "1",
            true, false },
        { "REST, no quantity: takes the pending", INVOICE_LINE_ID, null, null, twoPending, false,
            "2", true, true },
        { "REST, no quantity and nothing pending", INVOICE_LINE_ID, null, null,
            nothingPending(INVOICE_ID), true, null, true, false },
        { "MCP, a defaulted quantity is not the caller's: takes the pending", INVOICE_LINE_ID, 7,
            mcpWithoutQty, twoPending, false, "2", true, true },
        { "MCP, the caller's quantity is kept", INVOICE_LINE_ID, 1, mcpWithQty, twoPending, false,
            "1", true, true },
        { "MCP, the caller's quantity above the pending", INVOICE_LINE_ID, 3, mcpWithQty,
            twoPending, true, "3", true, false },
        { "REST, the caller's quantity is kept", INVOICE_LINE_ID, 1, null, twoPending, false, "1",
            true, true },
        { "non-numeric quantity: left to the write", INVOICE_LINE_ID, "abc", null, twoPending,
            false, "abc", false, true },
        { "another tenant's invoice line: ignored", "il-foreign", 99, null, twoPending, false,
            "99", false, false },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      JSONObject body = new JSONObject().put("invoiceLineId", c[1]);
      if (c[2] != null) {
        body.put(FIELD_QTY, c[2]);
      }
      NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();
      if (c[3] != null) {
        ctx.setAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS, c[3]);
      }
      PendingResolver.Source verdict = (PendingResolver.Source) c[4];
      boolean refused = (Boolean) c[5];
      GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();
      clearArmedLink(handler);

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class);
          MockedStatic<InvoiceLineLinker> linker = Mockito.mockStatic(InvoiceLineLinker.class);
          MockedConstruction<InvoicePendingResolver> resolvers =
              Mockito.mockConstruction(InvoicePendingResolver.class, (resolver, construction) ->
                  when(resolver.loadSources(any())).thenReturn(Map.of(INVOICE_ID, verdict)))) {
        obDalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));

        NeoResponse refusal = handler.handle(ctx);
        boolean locked = !resolvers.constructed().isEmpty();
        NeoResponse after = handler.afterHandle(postCreated("ship-new"));

        assertEquals(label, refused, refusal != null);
        if (refused) {
          assertEquals(label, 422, refusal.getHttpStatus());
          assertEquals(label, DocumentLineImport.ERROR_EXCEEDS_PENDING,
              refusal.getBody().getJSONObject("error").getString("code"));
          // A quantity of 0 or below is refused for its sign, not for the pending.
          boolean notPositive = c[2] instanceof Integer && (Integer) c[2] <= 0;
          assertEquals(label + ": quantityNotPositive", notPositive,
              refusal.getBody().getJSONObject("error").optBoolean("quantityNotPositive", false));
          assertQuantity(label + ": the pending it names", verdict == twoPending ? "2" : "0",
              refusal.getBody().getJSONObject("error").get("pending"));
        }
        String expected = (String) c[6];
        assertEquals(label, expected, body.has(FIELD_QTY) ? body.get(FIELD_QTY).toString() : null);
        assertEquals(label + ": locks the invoice", c[7], locked);
        if (locked) {
          verify(resolvers.constructed().get(0)).lockSource(INVOICE_ID);
        }
        assertNull(label + ": the post-hook keeps the created line", after);
        linker.verify(() -> InvoiceLineLinker.linkInvoiceLineToInOutLine(anyString(), anyString(),
            any()), times((Boolean) c[8] ? 1 : 0));
      }
    }
  }

  // ── CP-30 — the update of a linked line ───────────────────────────────────

  /**
   * The update of {@code movementQuantity} on a line linked to invoice lines (the column or
   * {@code M_MatchSI}, both read by one statement bound twice to the line): raising it is allowed
   * up to the pending plus what the line holds, for EVERY linked invoice line, and the first that
   * does not fit refuses with {@code maximumAllowed}; lowering or keeping it is allowed. A negative
   * quantity is refused even against an invoice no longer completed, while such an invoice does
   * not cap a raise. Nothing of the update path was covered before.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void updateOfALinkedLineIsCappedByEveryInvoiceLineItShips() throws Exception {
    Object[][] cases = {
        // label, held, requested, links (invoice line, invoice)..., verdicts, refused line, maximum
        { "raise within pending + held", "2", 5, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", pendingOf("inv-1", "il-1", "3")), null, null },
        { "raise above pending + held", "2", 6, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", pendingOf("inv-1", "il-1", "3")), "il-1", "5" },
        { "raise with nothing pending", "2", 3, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", nothingPending("inv-1")), "il-1", "2" },
        { "lower", "5", 3, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", nothingPending("inv-1")), null, null },
        { "keep", "5", 5, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", nothingPending("inv-1")), null, null },
        { "negative", "2", -1, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", pendingOf("inv-1", "il-1", "3")), "il-1", "5" },
        { "negative on an invoice no longer completed", "2", -1, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", notCompleted("inv-1")), "il-1", "2" },
        { "raise on an invoice no longer completed", "2", 10, new String[] { "il-1", "inv-1" },
            Map.of("inv-1", notCompleted("inv-1")), null, null },
        { "two links, both fit", "2", 3, new String[] { "il-1", "inv-1", "il-2", "inv-2" },
            Map.of("inv-1", pendingOf("inv-1", "il-1", "10"),
                "inv-2", pendingOf("inv-2", "il-2", "1")), null, null },
        { "two links, the second does not fit", "2", 4,
            new String[] { "il-1", "inv-1", "il-2", "inv-2" },
            Map.of("inv-1", pendingOf("inv-1", "il-1", "10"),
                "inv-2", pendingOf("inv-2", "il-2", "1")), "il-2", "3" },
        { "two links, neither fits: the first refuses", "2", 4,
            new String[] { "il-1", "inv-1", "il-2", "inv-2" },
            Map.of("inv-1", pendingOf("inv-1", "il-1", "1"), "inv-2", nothingPending("inv-2")),
            "il-1", "3" },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getMovementQuantity()).thenReturn(new BigDecimal((String) c[1]));
      ownership.when(() -> TenantOwnership.loadOwned(ShipmentInOutLine.class, SHIPMENT_LINE_ID))
          .thenReturn(line);
      ResultSet links = linkRows((String[]) c[3]);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(ps.executeQuery()).thenReturn(links);
      Connection conn = mock(Connection.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      OBDal dal = mock(OBDal.class);
      when(dal.getConnection()).thenReturn(conn);
      Map<String, PendingResolver.Source> verdicts = (Map<String, PendingResolver.Source>) c[4];
      NeoContext ctx = NeoContext.builder().httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId(SHIPMENT_LINE_ID).requestBody(new JSONObject().put(FIELD_QTY, c[2])).build();

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class);
          MockedConstruction<InvoicePendingResolver> resolvers =
              Mockito.mockConstruction(InvoicePendingResolver.class, (resolver, construction) ->
                  when(resolver.loadSources(any())).thenReturn(verdicts))) {
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        NeoResponse refusal = new GoodsShipmentLineHandler().handle(ctx);

        String refusedLine = (String) c[5];
        if (refusedLine == null) {
          assertNull(label, refusal);
        } else {
          assertNotNull(label, refusal);
          assertEquals(label, 422, refusal.getHttpStatus());
          JSONObject error = refusal.getBody().getJSONObject("error");
          assertEquals(label, DocumentLineImport.ERROR_EXCEEDS_PENDING, error.getString("code"));
          assertEquals(label, refusedLine, error.getString("sourceLineId"));
          assertQuantity(label, (String) c[6], error.get("maximumAllowed"));
          assertQuantity(label, String.valueOf(c[2]), error.get("requested"));
        }
        for (String invoiceId : verdicts.keySet()) {
          verify(resolvers.constructed().get(0)).lockSource(invoiceId);
        }
        verify(dal).refresh(line);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertTrue(label, sql.getValue().contains("il.m_inoutline_id = ?")
            && sql.getValue().contains(InOutInvoiceLinks.MatchTable.SALES.tableName()));
        verify(ps).setString(1, SHIPMENT_LINE_ID);
        verify(ps).setString(2, SHIPMENT_LINE_ID);
      }
    }
  }

  /**
   * An update that does not raise anything to check is never capped and, but for the line with no
   * invoice link, reads nothing: a body without {@code movementQuantity}, a quantity lowered to 0
   * and a non-numeric one perform no query; a line with no invoice link (the way to ship extra
   * units) is read and left as it is, without locking any invoice.
   */
  @Test
  public void updateWithoutAQuantityToCheckOrWithoutAnInvoiceLinkIsNotCapped() throws Exception {
    ShipmentInOutLine line = mock(ShipmentInOutLine.class);
    when(line.getMovementQuantity()).thenReturn(new BigDecimal("2"));
    ownership.when(() -> TenantOwnership.loadOwned(ShipmentInOutLine.class, SHIPMENT_LINE_ID))
        .thenReturn(line);
    Object[][] cases = {
        // label, body, reads the links
        { "no movementQuantity", new JSONObject().put("description", "x"), false },
        { "lowered to 0", new JSONObject().put(FIELD_QTY, 0), false },
        { "non-numeric", new JSONObject().put(FIELD_QTY, "abc"), false },
        { "a line with no invoice link", new JSONObject().put(FIELD_QTY, 99), true },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      ResultSet noLinks = linkRows();
      PreparedStatement ps = mock(PreparedStatement.class);
      when(ps.executeQuery()).thenReturn(noLinks);
      Connection conn = mock(Connection.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      OBDal dal = mock(OBDal.class);
      when(dal.getConnection()).thenReturn(conn);
      NeoContext ctx = NeoContext.builder().httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
          .recordId(SHIPMENT_LINE_ID).requestBody((JSONObject) c[1]).build();

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class);
          MockedConstruction<InvoicePendingResolver> resolvers =
              Mockito.mockConstruction(InvoicePendingResolver.class)) {
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        assertNull(label, new GoodsShipmentLineHandler().handle(ctx));

        verify(conn, times((Boolean) c[2] ? 1 : 0)).prepareStatement(anyString());
        assertTrue(label + ": no invoice is locked", resolvers.constructed().isEmpty());
      }
    }
  }

  // ── CP-30 — the re-check after the create ─────────────────────────────────

  /**
   * Within the cap — exactly at it, below it, or a line the follow-up does not list — the line
   * keeps the link written under the invoice lock and the post-hook keeps the default result.
   * The existing tests only reach the link without an invoice (no lock) or the excess.
   */
  @Test
  public void aCreateWithinTheCapOnTheRecheckKeepsItsLink() throws Exception {
    ownInvoiceLineOfInvoice();
    Object[][] cases = {
        // label, what is left of the invoice line once linked
        { "exactly at the cap", Map.of(INVOICE_LINE_ID, BigDecimal.ZERO) },
        { "below the cap", Map.of(INVOICE_LINE_ID, new BigDecimal("3")) },
        { "not listed by the follow-up", Map.of() },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      @SuppressWarnings("unchecked")
      Map<String, BigDecimal> remaining = (Map<String, BigDecimal>) c[1];
      OBDal dal = mock(OBDal.class);

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class);
          MockedStatic<InvoiceLineLinker> linker = Mockito.mockStatic(InvoiceLineLinker.class);
          MockedConstruction<InvoicePendingResolver> resolvers =
              Mockito.mockConstruction(InvoicePendingResolver.class, (resolver, construction) ->
                  when(resolver.remainingByLine(INVOICE_ID)).thenReturn(remaining))) {
        obDalMock.when(OBDal::getInstance).thenReturn(dal);

        assertNull(label, ShipmentLineInvoiceCap.linkUnderCap(INVOICE_LINE_ID, "ship-2"));

        verify(resolvers.constructed().get(0)).lockSource(INVOICE_ID);
        linker.verify(() -> InvoiceLineLinker.linkInvoiceLineToInOutLine(INVOICE_LINE_ID,
            "ship-2", InOutInvoiceLinks.MatchTable.SALES));
        linker.verify(() -> InvoiceLineLinker.unlinkInvoiceLineFromInOutLine(anyString(),
            anyString(), any()), never());
        verify(dal, never()).remove(any());
        verify(dal, never()).commitAndClose();
        context.verify(OBContext::restorePreviousMode);
      }
    }
  }

  /**
   * Review B1: outside a batch the insert commits before the link exists, so two racing creates
   * both pass the pre-hook. Of two racing creates that together exceed what is pending, exactly
   * one keeps its line; which one depends on the binding cap, not on who locks first (under the
   * order line's cap the first to lock already sees both drafts and can be the one removed).
   *
   * <p>The create the re-check finds in excess, once it holds the invoice lock and has written its
   * link, is answered the 422 by the handler's post-hook in place of the created line, naming
   * nothing pending. Outside a batch the line was committed, so it is unlinked, removed and that
   * removal committed; inside {@code /batch} nothing is compensated — the 422 rolls the whole
   * batch back.
   */
  @Test
  public void aCreateFoundInExcessIsAnsweredTheRefusalAndCompensatedOnlyOutsideABatch()
      throws Exception {
    ownInvoiceLineOfInvoice();
    PendingResolver.Source fivePending = pendingOf(INVOICE_ID, INVOICE_LINE_ID, "5");
    Map<String, BigDecimal> overShipped = Map.of(INVOICE_LINE_ID, new BigDecimal("-5"));
    for (boolean inBatch : new boolean[] { false, true }) {
      String label = inBatch ? "inside /batch" : "outside a batch";
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getMovementQuantity()).thenReturn(new BigDecimal("5"));
      OBDal dal = mock(OBDal.class);
      when(dal.get(ShipmentInOutLine.class, "ship-2")).thenReturn(line);
      JSONObject body = new JSONObject().put("invoiceLineId", INVOICE_LINE_ID).put(FIELD_QTY, 5);
      NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
          .requestBody(body).build();
      GoodsShipmentLineHandler handler = new GoodsShipmentLineHandler();

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class);
          MockedStatic<InvoiceLineLinker> linker = Mockito.mockStatic(InvoiceLineLinker.class);
          MockedConstruction<InvoicePendingResolver> resolvers =
              Mockito.mockConstruction(InvoicePendingResolver.class, (resolver, construction) -> {
                when(resolver.loadSources(any())).thenReturn(Map.of(INVOICE_ID, fivePending));
                when(resolver.remainingByLine(INVOICE_ID)).thenReturn(overShipped);
              })) {
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        NeoResponse answer;
        if (inBatch) {
          BatchService.beginCallerOwnedTransaction();
        }
        try {
          assertNull(label + ": the pre-hook lets it through", handler.handle(ctx));
          answer = handler.afterHandle(postCreated("ship-2"));
        } finally {
          BatchService.endCallerOwnedTransaction();
        }

        assertNotNull(label, answer);
        assertEquals(label, 422, answer.getHttpStatus());
        assertEquals(label, DocumentLineImport.ERROR_EXCEEDS_PENDING,
            answer.getBody().getJSONObject("error").getString("code"));
        assertQuantity(label, "0", answer.getBody().getJSONObject("error").get("pending"));
        // The pre-hook's resolver is the first; the re-check's, under the lock, the second.
        verify(resolvers.constructed().get(1)).lockSource(INVOICE_ID);
        linker.verify(() -> InvoiceLineLinker.linkInvoiceLineToInOutLine(INVOICE_LINE_ID,
            "ship-2", InOutInvoiceLinks.MatchTable.SALES));
        int compensated = inBatch ? 0 : 1;
        linker.verify(() -> InvoiceLineLinker.unlinkInvoiceLineFromInOutLine(INVOICE_LINE_ID,
            "ship-2", InOutInvoiceLinks.MatchTable.SALES), times(compensated));
        verify(dal, times(compensated)).remove(line);
        verify(dal, times(compensated)).commitAndClose();
      }
    }
  }

  /**
   * A failure while linking, re-checking or removing the line in excess is answered as a 500,
   * never as the success of the create: the line may exceed what was invoiced, so the caller must
   * not read it as saved and correct. Nothing is committed by the cap and admin mode is restored.
   */
  @Test
  public void aFailureOfTheRecheckIsAServerErrorNeverASuccess() throws Exception {
    ownInvoiceLineOfInvoice();
    String[] steps = { "link", "recheck", "unlink", "remove" };
    for (String step : steps) {
      ShipmentInOutLine line = mock(ShipmentInOutLine.class);
      when(line.getMovementQuantity()).thenReturn(new BigDecimal("5"));
      OBDal dal = mock(OBDal.class);
      when(dal.get(ShipmentInOutLine.class, "ship-2")).thenReturn(line);
      if ("remove".equals(step)) {
        doThrow(new IllegalStateException("remove failed")).when(dal).remove(line);
      }
      Map<String, BigDecimal> overShipped = Map.of(INVOICE_LINE_ID, new BigDecimal("-5"));

      try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
          MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class);
          MockedStatic<InvoiceLineLinker> linker = Mockito.mockStatic(InvoiceLineLinker.class);
          MockedConstruction<InvoicePendingResolver> resolvers =
              Mockito.mockConstruction(InvoicePendingResolver.class, (resolver, construction) -> {
                if ("recheck".equals(step)) {
                  when(resolver.remainingByLine(INVOICE_ID))
                      .thenThrow(new OBException("query failed"));
                } else {
                  when(resolver.remainingByLine(INVOICE_ID)).thenReturn(overShipped);
                }
              })) {
        obDalMock.when(OBDal::getInstance).thenReturn(dal);
        if ("link".equals(step)) {
          linker.when(() -> InvoiceLineLinker.linkInvoiceLineToInOutLine(INVOICE_LINE_ID,
              "ship-2", InOutInvoiceLinks.MatchTable.SALES))
              .thenThrow(new IllegalStateException("link failed"));
        }
        if ("unlink".equals(step)) {
          linker.when(() -> InvoiceLineLinker.unlinkInvoiceLineFromInOutLine(INVOICE_LINE_ID,
              "ship-2", InOutInvoiceLinks.MatchTable.SALES))
              .thenThrow(new IllegalStateException("unlink failed"));
        }

        NeoResponse answer = ShipmentLineInvoiceCap.linkUnderCap(INVOICE_LINE_ID, "ship-2");

        assertNotNull(step, answer);
        assertEquals(step, 500, answer.getHttpStatus());
        assertEquals(step, NeoErrorClassifier.CODE_SERVER_ERROR,
            answer.getBody().getJSONObject("error").getString("code"));
        verify(dal, never()).commitAndClose();
        context.verify(OBContext::restorePreviousMode);
      }
    }
  }
}
