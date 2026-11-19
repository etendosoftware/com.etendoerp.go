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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What {@link ReturnMaterialReceiptLineHandler} and {@link ReturnToVendorShipmentLineHandler} do
 * the same way, through {@link ReturnableQuantity} (ETP-5711): a line is never created directly,
 * a quantity edit is capped by what its source line has left, and every other request passes
 * through to the default CRUD. Every test runs on both handlers; the rule itself is pinned by
 * {@code ReturnableQuantityTest} and each handler's own sign/enrichment logic by its own test.
 *
 * @covers com.etendoerp.go.schemaforge.ReturnMaterialReceiptLineHandler
 * @covers com.etendoerp.go.schemaforge.ReturnToVendorShipmentLineHandler
 * @covers com.etendoerp.go.schemaforge.ReturnableQuantity
 */
class ReturnLineHandlersTest {

  /**
   * One return line handler under test.
   *
   * @param handler       the handler
   * @param importAction  the header action that imports lines from a document
   * @param createAction  the action that creates a whole return from a document
   */
  record LineHandler(NeoHandler handler, String importAction, String createAction) {
    @Override
    public String toString() {
      return handler.getClass().getSimpleName();
    }
  }

  static Stream<LineHandler> handlers() {
    return Stream.of(
        new LineHandler(new ReturnMaterialReceiptLineHandler(), "importShipmentLines",
            "createReturn"),
        new LineHandler(new ReturnToVendorShipmentLineHandler(), "importReceiptLines",
            "createPurchaseReturn"));
  }

  // ── handle() — pass-through ───────────────────────────────────────────────

  /** GET requests are pass-through: handle returns null, delegating to default CRUD. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testHandleGetReturnsNull(LineHandler line) {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(line.handler().handle(ctx));
  }

  // ── handle() — a line is never created directly (ETP-5711) ───────────────

  /**
   * The window only adds lines through "Add from document" / create-from-document, which link every
   * line to the line it returns and cap it. A direct POST of a line (REST, batch or MCP) would create
   * an unlinked, uncapped one: it is refused whatever the body says.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testHandlePostIsRefusedWith422AndPointsAtTheImportActions(LineHandler line)
      throws Exception {
    JSONObject body = new JSONObject().put("parentId", "return-1").put("product", "prod-1")
        .put("movementQuantity", 6.0);
    NeoContext ctx = NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.CRUD)
        .requestBody(body).build();

    NeoResponse refusal = line.handler().handle(ctx);

    assertNotNull(refusal);
    assertEquals(422, refusal.getHttpStatus());
    JSONObject error = refusal.getBody().getJSONObject("error");
    assertEquals("returnLineRequiresImport", error.getString("code"));
    String message = error.getString("message");
    assertTrue(message.contains(line.importAction()), message);
    assertTrue(message.contains(line.createAction()), message);
    assertEquals(6.0, body.getDouble("movementQuantity"), 0.0001,
        "the refused body is not touched");
    assertFalse(body.has("storageBin"));
  }

  /** Only the creation is refused: editing a line and every non-CRUD POST keep working. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testHandleStillAllowsEditsAndNonCrudPosts(LineHandler line) throws Exception {
    for (String method : new String[] {"PUT", "PATCH"}) {
      NeoContext edit = NeoContext.builder().httpMethod(method).endpointType(NeoEndpointType.CRUD)
          .requestBody(new JSONObject().put("description", "x")).build();
      assertNull(line.handler().handle(edit), method);
    }
    NeoContext callout = NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.CALLOUT).requestBody(new JSONObject()).build();
    assertNull(line.handler().handle(callout));
  }

  // ── handle() — over-return cap on a quantity edit (ETP-5711) ───────────────

  /**
   * A line of 4 returned out of a source line of 10 whose other returns hold 3: the line may grow
   * up to 7 (its own 4 does not count against it) and not one unit more.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testQuantityEditWithinWhatIsLeftIsAcceptedExcludingTheLineItself(LineHandler line)
      throws Exception {
    List<String> excluded = new ArrayList<>();

    NeoResponse result = ReturnedQuantityTestSupport.editLineQuantity(line.handler(), "PATCH",
        "7", "10", "3", "4", excluded);

    assertNull(result, "an edit within the remainder passes through to the default CRUD");
    assertEquals(List.of("ret-line-1"), excluded);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testQuantityEditAboveWhatIsLeftIsRefusedWith422(LineHandler line) throws Exception {
    for (String method : new String[] {"PATCH", "PUT"}) {
      NeoResponse result = ReturnedQuantityTestSupport.editLineQuantity(line.handler(), method,
          "8", "10", "3", "4", new ArrayList<>());

      assertNotNull(result, method);
      assertEquals(422, result.getHttpStatus(), method);
      JSONObject error = result.getBody().getJSONObject("error");
      assertEquals("returnQuantityExceedsAvailable", error.getString("code"));
      assertEquals(8, error.getDouble("requested"), 0.0001);
      assertEquals(7, error.getDouble("available"), 0.0001);
      assertTrue(error.getString("message").contains("Agua"), error.getString("message"));
    }
  }

  /** The SPA may send the stored (negative) sign; the cap compares magnitudes. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testQuantityEditWithNegativeStoredSignIsCappedByMagnitude(LineHandler line)
      throws Exception {
    NeoResponse result = ReturnedQuantityTestSupport.editLineQuantity(line.handler(), "PUT",
        "-8", "10", "3", "4", new ArrayList<>());

    assertNotNull(result);
    assertEquals(422, result.getHttpStatus());
  }

  /** Create (POST) and edits that do not touch the quantity never ask the database. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testQuantityCapIgnoresCreateAndEditsWithoutQuantity(LineHandler line) {
    NeoContext patchOther = NeoContext.builder().httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD).recordId("ret-line-1")
        .requestBody(new JSONObject()).build();

    assertNull(line.handler().handle(patchOther));
  }

  // ── afterHandle() — guard conditions ──────────────────────────────────────

  /** Non-GET request: afterHandle returns null immediately (no enrichment). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void testAfterHandleReturnsNullForNonGetMethod(LineHandler line) {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(line.handler().afterHandle(ctx));
  }
}
