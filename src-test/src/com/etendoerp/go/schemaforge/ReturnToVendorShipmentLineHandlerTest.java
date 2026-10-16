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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

/**
 * Unit tests for {@link ReturnToVendorShipmentLineHandler}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code handle()} — GET/DELETE pass-through, PUT/PATCH/POST quantity negation, product
 *       field removal rules, and null-body guard.</li>
 *   <li>{@code afterHandle()} — guard conditions, movementQuantity sign flip, and the SQL
 *       enrichment paths for orderQuantity and productCode injection.</li>
 * </ul>
 *
 * @covers com.etendoerp.go.schemaforge.ReturnToVendorShipmentLineHandler
 * @covers com.etendoerp.go.schemaforge.ReturnableQuantity
 */
public class ReturnToVendorShipmentLineHandlerTest {

  private static final ReturnToVendorShipmentLineHandler HANDLER =
      new ReturnToVendorShipmentLineHandler();

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext getCtx() {
    return NeoContext.builder()
        .specName("return-to-vendor-shipment")
        .entityName("lines")
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static JSONObject lineBody(String lineId, double movementQty) throws Exception {
    JSONObject line = new JSONObject()
        .put("id", lineId)
        .put("movementQuantity", movementQty);
    return new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put(line)));
  }

  // ── handle() — non-write methods pass through ─────────────────────────────

  /**
   * GET requests are pass-through: handle returns null and does not modify any body.
   */
  @Test
  public void testHandleGetReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  /**
   * DELETE requests are pass-through: handle returns null.
   */
  @Test
  public void testHandleDeleteReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE").endpointType(NeoEndpointType.CRUD).build();
    assertNull(HANDLER.handle(ctx));
  }

  // ── handle() — PUT negation ────────────────────────────────────────────────

  /**
   * PUT with a positive movementQuantity: the value is negated to negative.
   */
  @Test
  public void testHandlePutNegatesPositiveMovementQuantity() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", 5.0);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    NeoResponse result = HANDLER.handle(ctx);

    assertNull(result);
    assertEquals(-5.0, body.getDouble("movementQuantity"), 0.0001);
  }

  /**
   * PUT with a positive movementQuantity: the "product" field is removed from the body
   * so the SL_InOutLine_Product callout cannot overwrite the quantity.
   */
  @Test
  public void testHandlePutRemovesProductField() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementQuantity", 3.0)
        .put("product", "prod-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertFalse("product field should be removed for PUT", body.has("product"));
  }

  // ── handle() — PATCH negation and product removal ─────────────────────────

  /**
   * PATCH with a positive movementQuantity: the value is negated and "product" is removed.
   */
  @Test
  public void testHandlePatchNegatesQtyAndRemovesProduct() throws Exception {
    JSONObject body = new JSONObject()
        .put("movementQuantity", 2.0)
        .put("product", "prod-3");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertEquals(-2.0, body.getDouble("movementQuantity"), 0.0001);
    assertFalse("product field should be removed for PATCH", body.has("product"));
  }

  // ── handle() — already-negative and missing field ─────────────────────────

  /**
   * PUT with an already-negative movementQuantity: the value is NOT negated again.
   */
  @Test
  public void testHandlePutDoesNotNegateAlreadyNegativeQty() throws Exception {
    JSONObject body = new JSONObject().put("movementQuantity", -4.0);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    HANDLER.handle(ctx);

    assertEquals(-4.0, body.getDouble("movementQuantity"), 0.0001);
  }

  /**
   * PUT with no movementQuantity field in the body: returns null and leaves product untouched.
   */
  @Test
  public void testHandlePutWithNoMovementQuantityFieldReturnsNull() throws Exception {
    JSONObject body = new JSONObject().put("product", "prod-4");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    NeoResponse result = HANDLER.handle(ctx);

    assertNull(result);
    assertTrue("product field must remain when movementQuantity is absent", body.has("product"));
  }

  /**
   * PUT with a null request body: returns null without throwing a NullPointerException.
   */
  @Test
  public void testHandlePutWithNullBodyReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").endpointType(NeoEndpointType.CRUD)
        .requestBody(null).build();

    NeoResponse result = HANDLER.handle(ctx);

    assertNull(result);
  }

  // ── handle() — over-return cap on a quantity edit (ETP-5711) ───────────────

  /**
   * A line of 4 returned out of a source line of 10 whose other returns hold 3: the line may grow
   * up to 7 (its own 4 does not count against it) and not one unit more.
   */
  @Test
  public void testQuantityEditWithinWhatIsLeftIsAcceptedExcludingTheLineItself() throws Exception {
    java.util.List<String> excluded = new java.util.ArrayList<>();

    NeoResponse result = ReturnedQuantityTestSupport.editLineQuantity(HANDLER, "PATCH", "7",
        "10", "3", "4", excluded);

    assertNull("an edit within the remainder passes through to the default CRUD", result);
    assertEquals(java.util.List.of("ret-line-1"), excluded);
  }

  @Test
  public void testQuantityEditAboveWhatIsLeftIsRefusedWith422() throws Exception {
    for (String method : new String[] {"PATCH", "PUT"}) {
      NeoResponse result = ReturnedQuantityTestSupport.editLineQuantity(HANDLER, method, "8",
          "10", "3", "4", new java.util.ArrayList<>());

      assertNotNull(method, result);
      assertEquals(method, 422, result.getHttpStatus());
      JSONObject error = result.getBody().getJSONObject("error");
      assertEquals("returnQuantityExceedsAvailable", error.getString("code"));
      assertEquals(8, error.getDouble("requested"), 0.0001);
      assertEquals(7, error.getDouble("available"), 0.0001);
      assertTrue(error.getString("message"), error.getString("message").contains("Agua"));
    }
  }

  /** The SPA may send the stored (negative) sign; the cap compares magnitudes. */
  @Test
  public void testQuantityEditWithNegativeStoredSignIsCappedByMagnitude() throws Exception {
    NeoResponse result = ReturnedQuantityTestSupport.editLineQuantity(HANDLER, "PUT", "-8",
        "10", "3", "4", new java.util.ArrayList<>());

    assertNotNull(result);
    assertEquals(422, result.getHttpStatus());
  }

  /** Create (POST) and edits that do not touch the quantity never ask the database. */
  @Test
  public void testQuantityCapIgnoresCreateAndEditsWithoutQuantity() {
    JSONObject withoutQuantity = new JSONObject();
    NeoContext patchOther = NeoContext.builder().httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD).recordId("ret-line-1")
        .requestBody(withoutQuantity).build();

    assertNull(HANDLER.handle(patchOther));
  }

  // ── afterHandle() — guard conditions ──────────────────────────────────────

  /**
   * Non-GET endpoint: afterHandle returns null immediately (no enrichment).
   */
  @Test
  public void testAfterHandleReturnsNullForNonGetMethod() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(HANDLER.afterHandle(ctx));
  }

  /**
   * GET without a previous result set: afterHandle returns null.
   */
  @Test
  public void testAfterHandleReturnsNullWhenNoPreviousResult() {
    NeoContext ctx = getCtx();
    assertNull(HANDLER.afterHandle(ctx));
  }

  // ── afterHandle() — movementQuantity sign flip ────────────────────────────

  /**
   * GET response with a record whose movementQuantity is negative (as stored in the DB):
   * afterHandle flips it to positive so the frontend sees an absolute value.
   * OBDal is mocked so the SQL call succeeds with empty results (allowing execution to reach
   * the sign-flip loop after fetchLineData).
   */
  @Test
  public void testAfterHandleFlipsNegativeMovementQuantityToPositive() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no enrichment data needed

      JSONObject body = lineBody("line-1", -3.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(3.0, rec.getDouble("movementQuantity"), 0.0001);
    }
  }

  /**
   * GET response with a record whose movementQuantity is already positive:
   * afterHandle leaves it unchanged.
   * OBDal is mocked so the SQL call succeeds with empty results.
   */
  @Test
  public void testAfterHandleDoesNotFlipPositiveMovementQuantity() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = lineBody("line-2", 5.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(5.0, rec.getDouble("movementQuantity"), 0.0001);
    }
  }

  // ── afterHandle() — write-response sign flip, GET-only enrichment (ETP-5336) ──

  /**
   * ETP-5336's actual repro on THIS window: a PATCH echoing the updated RTV line must ALSO get
   * the stored NEGATIVE quantity flipped to POSITIVE — before this fix it was GET-only, so the
   * frontend's optimistic row update rendered the raw negative value until the next refetch.
   * {@code afterHandle} returns {@code null} on a write (the flip is applied in place on the
   * SAME JSONObject), and the GET-only source-document SQL enrichment must NOT run.
   */
  @Test
  public void testAfterHandlePatchResponseFlipsSignInPlaceAndSkipsSourceEnrichment()
      throws Exception {
    JSONObject body = lineBody("line-patch", -8.0);
    NeoResponse previous = NeoResponse.ok(body);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH").endpointType(NeoEndpointType.CRUD)
        .previousResult(previous)
        .build();

    NeoResponse result = HANDLER.afterHandle(ctx);

    assertNull("a write response returns null — the mutation happens in place on "
        + "previousResult, the caller keeps its own reference", result);
    JSONObject rec = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertEquals("the stored NEGATIVE sign must flip to POSITIVE on a PATCH echo too, not "
        + "just on GET", 8.0, rec.getDouble("movementQuantity"), 0.0001);
    assertFalse("GET-only source-document enrichment must not run on a write response",
        rec.has("orderQuantity"));
    assertFalse("GET-only source-document enrichment must not run on a write response",
        rec.has("productCode"));
  }

  // ── afterHandle() — SQL enrichment: DB returns rows ───────────────────────

  /**
   * GET with a line id, DB returns orderQuantity and productCode:
   * both are injected into the record.
   */
  @Test
  public void testAfterHandleInjectsOrderQtyAndProductCodeFromDb() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-3");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("10.00"));
      when(rs.getString(3)).thenReturn("VND-PROD-001");

      JSONObject body = lineBody("line-3", -10.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals(new BigDecimal("10.00"), rec.get("orderQuantity"));
      assertEquals("VND-PROD-001", rec.getString("productCode"));
    }
  }

  /**
   * GET with a line id, DB returns no rows for that id:
   * neither orderQuantity nor productCode is injected.
   */
  @Test
  public void testAfterHandleDoesNotInjectWhenDbReturnsNoRows() throws Exception {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = mock(ResultSet.class);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      JSONObject body = lineBody("line-4", -2.0);
      NeoContext ctx = getCtx();
      ctx.setPreviousResult(NeoResponse.ok(body));

      NeoResponse result = HANDLER.afterHandle(ctx);

      assertNotNull(result);
      JSONObject rec = result.getBody()
          .getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse("orderQuantity should not be present", rec.has("orderQuantity"));
      assertFalse("productCode should not be present", rec.has("productCode"));
    }
  }

  // ── handle() — a line is never created directly (ETP-5711) ───────────────

  /**
   * The window only adds lines through "Add from document" / create-from-document, which link every
   * line to the line it returns and cap it. A direct POST of a line (REST, batch or MCP) would create
   * an unlinked, uncapped one: it is refused whatever the body says.
   */
  @Test
  public void testHandlePostIsRefusedWith422AndPointsAtTheImportActions() throws Exception {
    JSONObject body = new JSONObject().put("parentId", "return-1").put("product", "prod-1")
        .put("movementQuantity", 6.0);
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    NeoResponse refusal = HANDLER.handle(ctx);

    assertNotNull(refusal);
    assertEquals(422, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals("returnLineRequiresImport", error.getString("code"));
    assertTrue(error.getString("message"), error.getString("message").contains("importReceiptLines"));
    assertTrue(error.getString("message"), error.getString("message").contains("createPurchaseReturn"));
    assertEquals("the refused body is not touched", 6.0, body.getDouble("movementQuantity"), 0.0001);
    assertFalse(body.has("storageBin"));
  }

  /** Only the creation is refused: editing a line and every non-CRUD POST keep working. */
  @Test
  public void testHandleStillAllowsEditsAndNonCrudPosts() throws Exception {
    for (String method : new String[] {"PUT", "PATCH"}) {
      NeoContext edit = NeoContext.builder().httpMethod(method).endpointType(NeoEndpointType.CRUD)
          .requestBody(new JSONObject().put("description", "x")).build();
      assertNull(method, HANDLER.handle(edit));
    }
    NeoContext callout = NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.CALLOUT).requestBody(new JSONObject()).build();
    assertNull(HANDLER.handle(callout));
  }
}
