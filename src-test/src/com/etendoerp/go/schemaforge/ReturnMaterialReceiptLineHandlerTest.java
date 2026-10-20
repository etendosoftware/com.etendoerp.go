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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for {@link ReturnMaterialReceiptLineHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — pass-through pre-hook, storageBin default, and (ETP-5729 CP-12) which
 *       source line and quantity a write asks {@link ReturnableQuantityGuard} about, per branch
 *       of {@code refuseOverReturn}, plus the 500 and 409 outcomes.</li>
 *   <li>{@code afterHandle()} — guard conditions (non-GET, no previous result, empty data),
 *       and the SQL enrichment paths: both fields, null productCode, null qty, empty lineIds,
 *       and SQL exception resilience.</li>
 * </ul>
 *
 * <p>Tests that require a live Etendo DB are covered by integration tests.
 *
 * @covers com.etendoerp.go.schemaforge.ReturnMaterialReceiptLineHandler
 */
public class ReturnMaterialReceiptLineHandlerTest {

  private static final ReturnMaterialReceiptLineHandler HANDLER =
      new ReturnMaterialReceiptLineHandler();

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Builds a GET/CRUD context targeting the return-material-receipt lines entity.
   */
  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("return-material-receipt")
        .entityName("lines")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Builds a minimal response body wrapping a single line record with the given id.
   */
  private static JSONObject lineBody(String lineId) throws Exception {
    JSONObject line = new JSONObject().put("id", lineId).put("movementQty", 3.0);
    return new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(line)));
  }

  /**
   * Builds a minimal response body wrapping a single line record carrying a
   * {@code movementQuantity} field — the field {@link ReturnLineQuantityPolicy} actually reads
   * (unlike {@link #lineBody(String)}'s {@code movementQty}, which only feeds the unrelated
   * SQL-enrichment tests below).
   */
  private static JSONObject lineBodyWithMovementQuantity(String lineId, double qty)
      throws Exception {
    JSONObject line = new JSONObject().put("id", lineId).put("movementQuantity", qty);
    return new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(line)));
  }

  // ── handle() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that handle always returns null, delegating to default CRUD.
   */
  @Test
  public void testHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * Verifies that handle returns null even for POST/ACTION contexts.
   */
  @Test
  public void testHandleReturnsNullForPostContext() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.handle(ctx));
  }

  // ── handle() — storageBin default injection (ETP-4863) ───────────────────

  /**
   * Reproduces ETP-4863: confirming a Return Material Receipt (Devolución de Venta / RMA of a
   * sale) with the header on the PRINCIPAL warehouse must never leave the created line's
   * {@code storageBin} pointing at a different (e.g. stale session-cached "secondary")
   * warehouse. Unlike {@link GoodsShipmentLineHandler} (extends {@code
   * AbstractInOutLineHandler}), this handler implements {@link NeoHandler} directly — same
   * shape as {@link ReturnToVendorShipmentLineHandler} — and its {@code handle()} was a plain
   * {@code return null;} with no locator-defaulting logic at all. handle() must default {@code
   * storageBin} to the header warehouse's own default locator on POST when missing, exactly
   * like the other three M_InOutLine-based line handlers.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostInjectsWarehouseDefaultLocatorWhenStorageBinMissing() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "rma-1").put("product", "prod-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      Locator locator = mock(Locator.class);
      when(dal.get(eq(ShipmentInOut.class), eq("rma-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-principal");
      when(locator.getId()).thenReturn("loc-default-wh-principal");
      OBCriteria criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Locator.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.addOrder(any())).thenReturn(criteria);
      when(criteria.setMaxResults(1)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(locator);

      assertNull(HANDLER.handle(ctx));
      assertEquals("A return-material-receipt line's storageBin must always resolve to the "
          + "header warehouse's own default locator, never to a stale session-cached warehouse",
          "loc-default-wh-principal", body.getString("storageBin"));
    }
  }

  /**
   * handle() POST must not override an explicit storageBin already supplied on the request
   * when it ALREADY belongs to the header's warehouse (ETP-4863 BUG-1: the guarantee is about
   * the warehouse, not about forcing every line onto the warehouse's single "default" locator).
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testHandlePostDoesNotOverrideExplicitStorageBin() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "rma-1")
        .put("storageBin", "loc-explicit");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ShipmentInOut header = mock(ShipmentInOut.class);
      Warehouse warehouse = mock(Warehouse.class);
      when(dal.get(eq(ShipmentInOut.class), eq("rma-1"))).thenReturn(header);
      when(header.getWarehouse()).thenReturn(warehouse);
      when(warehouse.getId()).thenReturn("wh-1");
      Locator existingLocator = mock(Locator.class);
      when(dal.get(eq(Locator.class), eq("loc-explicit"))).thenReturn(existingLocator);
      when(existingLocator.getWarehouse()).thenReturn(warehouse);

      assertNull(HANDLER.handle(ctx));
      assertEquals("loc-explicit", body.getString("storageBin"));
      Mockito.verify(dal, Mockito.never()).createCriteria(Locator.class);
    }
  }

  // ── handle() — returnable quantity of the source shipment line ───────────
  //
  // ReturnableQuantityGuard is stubbed here: these cases pin WHICH source line and quantity the
  // handler asks about (or that it asks nothing). The guard's own SQL is in
  // ReturnableQuantityGuardTest.

  private static NeoContext writeCtx(String method, String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName("return-material-receipt").entityName("lines")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .recordId(recordId).requestBody(body)
        .build();
  }

  /** Matches a requested-quantity map holding exactly one source line with that magnitude. */
  private static ArgumentMatcher<Map<String, BigDecimal>> onlySource(String id, String qty) {
    return m -> m != null && m.size() == 1 && m.get(id) != null
        && m.get(id).compareTo(new BigDecimal(qty)) == 0;
  }

  /** A write that carries neither a quantity nor a link changes nothing returnable: no check. */
  @Test
  public void testHandleSkipsTheReturnableCheckWhenTheWriteTouchesNeitherQtyNorLink()
      throws Exception {
    try (MockedStatic<ReturnableQuantityGuard> guard =
             Mockito.mockStatic(ReturnableQuantityGuard.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      assertNull(HANDLER.handle(
          writeCtx("PATCH", "rl-1", new JSONObject().put("description", "x"))));
      guard.verifyNoInteractions();
      obContext.verifyNoInteractions();
    }
  }

  /**
   * {@code canceledInoutLine} sent as JSON null, {@code ""}, {@code "null"} or an object without an
   * id unlinks the line: there is no source line left to check, even with a quantity in the body.
   */
  @Test
  public void testHandleTreatsAnEmptyCanceledInoutLineAsAnUnlink() throws Exception {
    Object[] unlinks = { JSONObject.NULL, "", "null", new JSONObject() };
    for (Object unlink : unlinks) {
      try (MockedStatic<ReturnableQuantityGuard> guard =
               Mockito.mockStatic(ReturnableQuantityGuard.class);
           MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
        JSONObject body = new JSONObject().put("canceledInoutLine", unlink)
            .put("movementQuantity", 5);
        assertNull("unlink " + unlink, HANDLER.handle(writeCtx("PATCH", "rl-1", body)));
        guard.verifyNoInteractions();
      }
    }
  }

  /**
   * Re-pointing an existing line without sending a quantity is checked with the quantity it
   * already carries, against the NEW source line only — the stored one is never a fallback.
   */
  @Test
  public void testHandleChecksARePointedLineWithTheQuantityItAlreadyCarries() throws Exception {
    ReturnableQuantityGuard.StoredReturnLine stored =
        new ReturnableQuantityGuard.StoredReturnLine("src-old", new BigDecimal("4"));
    try (MockedStatic<ReturnableQuantityGuard> guard =
             Mockito.mockStatic(ReturnableQuantityGuard.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      guard.when(() -> ReturnableQuantityGuard.findStoredReturnLine("rl-1")).thenReturn(stored);

      assertNull(HANDLER.handle(writeCtx("PATCH", "rl-1",
          new JSONObject().put("canceledInoutLine", "src-new"))));

      guard.verify(() -> ReturnableQuantityGuard.refuseIfExceeded(
          argThat(onlySource("src-new", "4")), eq("rl-1")));
    }
  }

  /**
   * A new line carrying both a link (here as an {@code {"id": ...}} reference) and a quantity is
   * checked with what it sends, compared as a magnitude, without reading any stored line.
   */
  @Test
  public void testHandleChecksANewLineAgainstTheSourceLineItLinks() throws Exception {
    try (MockedStatic<ReturnableQuantityGuard> guard =
             Mockito.mockStatic(ReturnableQuantityGuard.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      JSONObject body = new JSONObject()
          .put("canceledInoutLine", new JSONObject().put("id", " src-2 "))
          .put("movementQuantity", -3);

      assertNull(HANDLER.handle(writeCtx("POST", null, body)));

      guard.verify(() -> ReturnableQuantityGuard.findStoredReturnLine(any()), Mockito.never());
      guard.verify(() -> ReturnableQuantityGuard.refuseIfExceeded(
          argThat(onlySource("src-2", "3")), isNull()));
    }
  }

  /** Editing only the quantity of an existing line checks it against the stored source line. */
  @Test
  public void testHandleChecksAQuantityOnlyEditAgainstTheStoredSourceLine() throws Exception {
    ReturnableQuantityGuard.StoredReturnLine stored =
        new ReturnableQuantityGuard.StoredReturnLine("src-1", new BigDecimal("2"));
    try (MockedStatic<ReturnableQuantityGuard> guard =
             Mockito.mockStatic(ReturnableQuantityGuard.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      guard.when(() -> ReturnableQuantityGuard.findStoredReturnLine("rl-1")).thenReturn(stored);

      assertNull(HANDLER.handle(writeCtx("PATCH", "rl-1",
          new JSONObject().put("movementQuantity", 7))));

      guard.verify(() -> ReturnableQuantityGuard.refuseIfExceeded(
          argThat(onlySource("src-1", "7")), eq("rl-1")));
    }
  }

  /**
   * A quantity edit on a stored line that is not linked to a shipment line — or on a line that
   * does not exist — has nothing to check.
   */
  @Test
  public void testHandleSkipsTheCheckWhenTheStoredLineHasNoSource() throws Exception {
    ReturnableQuantityGuard.StoredReturnLine[] storedLines = {
        new ReturnableQuantityGuard.StoredReturnLine(null, new BigDecimal("2")), null };
    for (ReturnableQuantityGuard.StoredReturnLine stored : storedLines) {
      try (MockedStatic<ReturnableQuantityGuard> guard =
               Mockito.mockStatic(ReturnableQuantityGuard.class);
           MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
        guard.when(() -> ReturnableQuantityGuard.findStoredReturnLine("rl-1")).thenReturn(stored);

        assertNull(HANDLER.handle(writeCtx("PATCH", "rl-1",
            new JSONObject().put("movementQuantity", 7))));

        guard.verify(() -> ReturnableQuantityGuard.refuseIfExceeded(any(), any()),
            Mockito.never());
      }
    }
  }

  /**
   * A quantity of 0 returns nothing, and an unparsable or null one is NEO's to reject: neither
   * is checked, even on a linked line.
   */
  @Test
  public void testHandleSkipsTheCheckForAZeroOrUnparsableQuantity() throws Exception {
    Object[] quantities = { 0, "abc", JSONObject.NULL };
    for (Object qty : quantities) {
      try (MockedStatic<ReturnableQuantityGuard> guard =
               Mockito.mockStatic(ReturnableQuantityGuard.class);
           MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
        JSONObject body = new JSONObject().put("canceledInoutLine", "src-1")
            .put("movementQuantity", qty);
        assertNull("qty " + qty, HANDLER.handle(writeCtx("PUT", "rl-1", body)));
        guard.verifyNoInteractions();
      }
    }
  }

  /**
   * A new line (no record id) that lacks either the link or the quantity has no stored line to
   * fill the gap: no admin mode, no query.
   */
  @Test
  public void testHandleDoesNotQueryForANewLineMissingItsLinkOrQuantity() throws Exception {
    JSONObject[] bodies = { new JSONObject().put("movementQuantity", 2),
        new JSONObject().put("canceledInoutLine", "src-1") };
    for (JSONObject body : bodies) {
      try (MockedStatic<ReturnableQuantityGuard> guard =
               Mockito.mockStatic(ReturnableQuantityGuard.class);
           MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
        assertNull(HANDLER.handle(writeCtx("POST", null, body)));
        guard.verifyNoInteractions();
        obContext.verifyNoInteractions();
      }
    }
  }

  /**
   * Fails closed: when the returnable quantity cannot be read the write is refused with a 500
   * (never let through), the body is left untouched and admin mode is restored.
   */
  @Test
  public void testHandleAnswers500WhenTheReturnableQuantityCannotBeRead() throws Exception {
    try (MockedStatic<ReturnableQuantityGuard> guard =
             Mockito.mockStatic(ReturnableQuantityGuard.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      guard.when(() -> ReturnableQuantityGuard.findStoredReturnLine("rl-1"))
          .thenThrow(new SQLException("connection lost"));
      JSONObject body = new JSONObject().put("movementQuantity", 7);

      NeoResponse result = HANDLER.handle(writeCtx("PATCH", "rl-1", body));

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      assertEquals(7, body.getInt("movementQuantity"));
      obContext.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * End to end through the real guard: a line pushing its shipment line past what was shipped is
   * refused with the structured 409, its own id is the one excluded from the sum, and the body
   * is not sign-flipped (nothing is written).
   */
  @Test
  public void testHandleRefusesAnOverReturnWithTheStructured409() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> obContext = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("src-1");
      when(rs.getString(2)).thenReturn("Widget");
      when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("5"));
      when(rs.getBigDecimal(4)).thenReturn(BigDecimal.ZERO);
      JSONObject body = new JSONObject().put("canceledInoutLine", "src-1")
          .put("movementQuantity", 6);

      NeoResponse result = HANDLER.handle(writeCtx("PUT", "rl-1", body));

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_CONFLICT, result.getHttpStatus());
      JSONObject error = result.getBody().getJSONObject("error");
      assertEquals("ETGO_ReturnQtyExceedsShipped", error.getJSONArray("messageKeys").getString(0));
      assertEquals("Widget", error.getJSONObject("messageParams").getString("product"));
      Mockito.verify(ps).setString(1, "rl-1");
      assertEquals(6, body.getInt("movementQuantity"));
    }
  }

  // ── afterCallout() — strip stock-derived movementQuantity (ETP-5336) ─────

  /**
   * The classic {@code SL_InOutLine_Product} callout echoes back the product's on-hand
   * quantity as {@code updates.movementQuantity} on product selection. For a return line that
   * must never overwrite what the user typed, so afterCallout strips just that one key.
   */
  @Test
  public void testAfterCalloutStripsMovementQuantityFromUpdates() throws Exception {
    JSONObject updates = new JSONObject().put("movementQuantity", 12.0).put("otherField", "x");
    JSONObject body = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterCallout(ctx));

    assertFalse("movementQuantity must be stripped from the callout updates",
        updates.has("movementQuantity"));
    assertEquals("other updates keys must be left untouched", "x",
        updates.getString("otherField"));
  }

  /**
   * A non-CALLOUT endpoint (e.g. the CRUD write itself) must leave any "updates"-shaped body
   * completely alone — stripping is scoped to the callout response only.
   */
  @Test
  public void testAfterCalloutNoOpWhenEndpointIsNotCallout() throws Exception {
    JSONObject updates = new JSONObject().put("movementQuantity", 12.0);
    JSONObject body = new JSONObject().put("updates", updates);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterCallout(ctx));

    assertTrue("a non-CALLOUT endpoint must leave the updates object untouched",
        updates.has("movementQuantity"));
  }

  /**
   * A callout response with no "updates" object at all (or no previous result) must be a
   * silent no-op, never an exception.
   */
  @Test
  public void testAfterCalloutNoOpWhenUpdatesIsNull() throws Exception {
    JSONObject body = new JSONObject().put("someOtherKey", "x");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CALLOUT)
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterCallout(ctx));
  }

  @Test
  public void testAfterCalloutNoOpWhenNoPreviousResult() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CALLOUT)
        .build();
    assertNull(HANDLER.afterCallout(ctx));
  }

  // ── afterHandle() — write-response sign flip, GET-only enrichment (ETP-5336) ──

  /**
   * ETP-5336's actual repro: a PATCH echoing the updated line must ALSO get the stored
   * NEGATIVE quantity flipped to POSITIVE — not just a GET. {@code afterHandle} returns
   * {@code null} on a write (the dispatcher keeps using the caller's own previousResult), but
   * the flip is applied in place on the SAME JSONObject, so it must be visible on {@code body}.
   * The GET-only source-document SQL enrichment (orderQuantity/productCode) must NOT run — if
   * it did, it would blow up on {@code OBDal.getInstance()} with no active session/mock here.
   */
  @Test
  public void testAfterHandlePatchResponseFlipsSignInPlaceAndSkipsSourceEnrichment()
      throws Exception {
    JSONObject body = lineBodyWithMovementQuantity("line-patch", -12.0);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    NeoResponse result = HANDLER.afterHandle(ctx);

    assertNull("a write response returns null — the mutation happens in place on "
        + "previousResult, the caller keeps its own reference", result);
    JSONObject rec = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("the stored NEGATIVE sign must flip to POSITIVE on a PATCH echo too, not "
        + "just on GET", 12.0, rec.getDouble("movementQuantity"), 0.0001);
    assertFalse("GET-only source-document enrichment must not run on a write response",
        rec.has("orderQuantity"));
    assertFalse("GET-only source-document enrichment must not run on a write response",
        rec.has("productCode"));
  }

  @Test
  public void testAfterHandlePostResponseFlipsSignInPlace() throws Exception {
    JSONObject body = lineBodyWithMovementQuantity("line-post", -6.0);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(HANDLER.afterHandle(ctx));

    JSONObject rec = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals(6.0, rec.getDouble("movementQuantity"), 0.0001);
  }

  @Test
  public void testAfterHandlePutResponseFlipsSignAndPreservesOriginalStatus() throws Exception {
    JSONObject body = lineBodyWithMovementQuantity("line-put", -3.5);
    NeoResponse previous = new NeoResponse(200, body);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .previousResult(previous)
        .build();

    assertNull(HANDLER.afterHandle(ctx));

    assertSame("the caller's previousResult reference must be untouched by a null return",
        previous, ctx.getPreviousResult());
    assertEquals(200, ctx.getPreviousResult().getHttpStatus());
    JSONObject rec = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals(3.5, rec.getDouble("movementQuantity"), 0.0001);
  }

  // ── afterHandle() guard conditions ────────────────────────────────────────

  /**
   * Verifies that afterHandle returns null for non-GET requests.
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when no previous result is set on the context.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the previous result carries a null body.
   */
  @Test
  public void testAfterHandleReturnsNullWhenBodyIsNull() {
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(new NeoResponse(200, null));
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the data array in the response is empty.
   */
  @Test
  public void testAfterHandleReturnsNullWhenDataArrayIsEmpty() throws Exception {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * Verifies that afterHandle returns null when the response has no "response" wrapper object.
   */
  @Test
  public void testAfterHandleReturnsNullWhenResponseWrapperAbsent() throws Exception {
    JSONObject body = new JSONObject().put("data",
        new JSONArray().put(new JSONObject().put("id", "line-1")));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── afterHandle() — SQL enrichment paths ──────────────────────────────────

  /**
   * Verifies that both orderQuantity and productCode are injected when the SQL row
   * contains non-null values for both.
   */
  @Test
  public void testAfterHandleInjectsBothQtyAndProductCodeWhenBothPresent() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5.00"));
      when(rs.getString(3)).thenReturn("PROD-CODE-A");

      JSONObject body = lineBody("line-1");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(new BigDecimal("5.00"), rec.get("orderQuantity"));
      assertEquals("PROD-CODE-A", rec.getString("productCode"));
    }
  }

  /**
   * Verifies that only orderQuantity is injected (no productCode key) when the SQL row
   * returns a null product code — meaning the product was not found in M_Product.
   */
  @Test
  public void testAfterHandleInjectsOnlyQtyWhenProductCodeIsNull() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-2");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("2.00"));
      when(rs.getString(3)).thenReturn(null); // null productCode

      JSONObject body = lineBody("line-2");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(rec.has("orderQuantity"));
      assertFalse(rec.has("productCode"));
    }
  }

  /**
   * Verifies that only productCode is injected (no orderQuantity key) when the SQL row
   * returns a null effective_qty — meaning no original line was found.
   */
  @Test
  public void testAfterHandleInjectsOnlyProductCodeWhenQtyIsNull() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-3");
      when(rs.getBigDecimal(2)).thenReturn(null); // null qty
      when(rs.getString(3)).thenReturn("PROD-CODE-B");

      JSONObject body = lineBody("line-3");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(rec.has("orderQuantity"));
      assertEquals("PROD-CODE-B", rec.getString("productCode"));
    }
  }

  /**
   * Verifies that when the data array contains records with no id, the SQL is still called
   * (ids list is empty) and afterHandle returns a valid unmodified response.
   */
  @Test
  public void testAfterHandlePassesThroughWhenAllLinesHaveNoId() throws Exception {
    JSONObject lineNoId = new JSONObject().put("movementQty", 1.0);
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(lineNoId)));
    NeoContext ctx = getCtx();
    ctx.setPreviousResult(NeoResponse.ok(body));

    // No OBDal mock needed — fetchLineData returns empty map without hitting DB
    NeoResponse result = HANDLER.afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    JSONObject rec = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertFalse(rec.has("orderQuantity"));
    assertFalse(rec.has("productCode"));
  }

  /**
   * Verifies that when an unexpected exception escapes fetchLineData (e.g. getConnection throws
   * before the inner try/catch), afterHandle returns the original previousResult unchanged.
   */
  @Test
  public void testAfterHandleReturnsOriginalResponseOnUnexpectedException() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("unexpected DB error"));

      JSONObject body = lineBody("line-x");
      NeoContext ctx = getCtx();
      NeoResponse original = NeoResponse.ok(body);
      ctx.setPreviousResult(original);

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      assertSame(original, result);
    }
  }

  /**
   * Verifies that a SQL exception inside fetchLineData is caught silently —
   * afterHandle returns a valid response without throwing.
   */
  @Test
  public void testAfterHandleSqlExceptionCaughtSilently() throws Exception {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenThrow(new SQLException("connection lost"));

      JSONObject body = lineBody("line-err");
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }
}
