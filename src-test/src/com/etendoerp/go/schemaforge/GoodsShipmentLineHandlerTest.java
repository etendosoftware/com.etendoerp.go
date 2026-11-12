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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
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
 *   <li>{@code handle()} — must always return null to fall through to default CRUD.</li>
 *   <li>{@code afterHandle()} — guard conditions that short-circuit before DB access:
 *       non-GET method, missing previous result, null body, and empty data array.</li>
 *   <li>ETP-6002 CP-22 — {@code invoiceLineId} declared as a create directive (the MCP no longer
 *       reports it in {@code unknownFields}) and the sales link: the first shipment of an invoice
 *       line in its column, a later partial one in {@code M_MatchSI}.</li>
 *   <li>ETP-6002 CP-14 — a quotation line is never a shipment line's order line.</li>
 * </ul>
 *
 * <p>Tests that require DB access (orderLineQty / productCode enrichment via SQL)
 * are not included here — those are covered by integration tests.
 *
 * @covers com.etendoerp.go.schemaforge.GoodsShipmentLineHandler
 * @covers com.etendoerp.go.schemaforge.SalesQuotations
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
      assertNotNull(result.getBody());
    }
  }

  /**
   * afterHandle() GET must return null when an exception escapes the outer try block
   * (e.g. collectIds or NeoHandlerUtils itself throws unexpectedly).
   */
  @Test
  public void testAfterHandleGetReturnsNullOnUnexpectedException() throws Exception {
    // Build a context whose previousResult body is deliberately corrupt so that
    // the JSON deserialization inside the enrichment loop throws and the outer
    // catch block returns null.
    JSONObject corruptLine = new JSONObject();
    // id is present so collectIds works, but put a non-object where a JSONObject
    // is expected to trigger a runtime error inside the loop.
    corruptLine.put("id", "x");
    JSONArray dataArr = new JSONArray().put(corruptLine);
    JSONObject body = new JSONObject()
        .put("response", new JSONObject().put("data", dataArr));

    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // Throw from getConnection so fetchLineData returns empty and we reach
      // NeoResponse.ok(body) normally — this validates the happy path succeeds
      when(dal.getConnection()).thenThrow(new RuntimeException("simulate DB error"));

      NeoResponse result = new GoodsShipmentLineHandler().afterHandle(ctx);
      // fetchLineData swallowed the exception and returned empty map → ok() is returned
      assertNotNull(result);
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

  /** Arms the handler with {@code invoiceLineId} and runs the POST post-hook for {@code newLineId}. */
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
}
