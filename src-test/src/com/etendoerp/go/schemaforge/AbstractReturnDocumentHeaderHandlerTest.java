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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link AbstractReturnDocumentHeaderHandler} — the {@code handle()} wiring both
 * return headers inherit (unpost gate, {@code sifDefaults}, posting delegation) — and for the
 * action routing and contracts that {@link ReturnMaterialReceiptHeaderHandler} and
 * {@link ReturnToVendorShipmentHeaderHandler} implement the same way. Every test runs on both
 * handlers; what differs between them stays in their own test classes.
 *
 * @covers com.etendoerp.go.schemaforge.AbstractReturnDocumentHeaderHandler
 * @covers com.etendoerp.go.schemaforge.ReturnMaterialReceiptHeaderHandler
 * @covers com.etendoerp.go.schemaforge.ReturnToVendorShipmentHeaderHandler
 */
class AbstractReturnDocumentHeaderHandlerTest {

  /**
   * One return header under test, wired as its own test class wires it: a clone handler that
   * declines by default and a mocked draft-invoice handler.
   *
   * @param handler  the handler
   * @param cloneHandler its clone handler mock
   * @param specName the NEO spec it serves
   */
  record Header(AbstractReturnDocumentHeaderHandler handler, NeoCloneRecordHandler cloneHandler,
      String specName) {
    @Override
    public String toString() {
      return handler.getClass().getSimpleName();
    }
  }

  static Stream<Header> headers() {
    ReturnMaterialReceiptHeaderHandler receipt = new ReturnMaterialReceiptHeaderHandler();
    receipt.cloneRecordHandler = mock(NeoCloneRecordHandler.class);
    receipt.createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    ReturnToVendorShipmentHeaderHandler shipment = new ReturnToVendorShipmentHeaderHandler();
    shipment.cloneRecordHandler = mock(NeoCloneRecordHandler.class);
    shipment.createDraftInvoiceHandler = mock(CreateDraftInvoiceHandler.class);
    return Stream.of(
        new Header(receipt, receipt.cloneRecordHandler, "return-material-receipt"),
        new Header(shipment, shipment.cloneRecordHandler, "return-to-vendor-shipment"));
  }

  private static NeoContext postAction(String action) {
    return NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(action).build();
  }

  // ── posting delegation (ETP-5378) ────────────────────────────────────────

  // Both return windows own their JAVA_QUALIFIER slot, so the shared @Named("document-posting")
  // handler can never serve them. Before this delegation, post/unpost fell through to the generic
  // AD-button path, which looks for a button column literally named "post", finds none, and
  // answers "404 Action not found: post" — the defect reported from the form view of both return
  // windows. Mirrors SalesInvoiceHeaderHandlerPostingTest.

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void handleReturnsPostingResponseWhenServiceHandlesAction(Header header) {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(context)).thenReturn(sentinel);
    header.handler().setPostingService(service);

    assertSame(sentinel, header.handler().handle(context),
        "Posting response must short-circuit the handler's own logic");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void handleFallsThroughWhenServiceDeclinesTheAction(Header header) {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    when(service.handleAction(context)).thenReturn(null);
    header.handler().setPostingService(service);

    assertNull(header.handler().handle(context),
        "A declined posting action must not short-circuit the handler");
    verify(service).handleAction(context);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void handleSurvivesAnUninjectedPostingService(Header header) {
    NeoContext context = mock(NeoContext.class);

    assertNull(header.handler().handle(context),
        "A null posting service must degrade to the pre-ETP-5378 behavior");
  }

  // ── ETP-5714: the unpost gate is wired in front of the posting service ─────

  /** The behavior of the gate is covered in {@code GoodsPostingContractsTest}; this is the wiring. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void handle_unpostGateRunsBeforeThePostingService(Header header) {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext ctx = NeoContext.builder().httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION).fieldName("unpost").recordId("r-1").build();
    NeoResponse refusal = NeoResponse.error(422, "not posted");
    header.handler().setPostingService(service);

    try (MockedStatic<GoodsPostingContracts> gate = Mockito.mockStatic(GoodsPostingContracts.class)) {
      gate.when(() -> GoodsPostingContracts.checkUnpost(ctx)).thenReturn(refusal);

      assertSame(refusal, header.handler().handle(ctx));
      verify(service, never()).handleAction(any());
    }
  }

  // ── sifDefaults: the SIF fields of its factura rectificativa (ETP-6179) ──

  /** sifDefaults is declared as a read action that creates nothing and documents its answer. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void actionContracts_declaresSifDefaultsAsAReadOnlyPreview(Header header) {
    Map<String, NeoActionContract> contracts = header.handler().actionContracts();

    InvoiceSifPreviewTestSupport.assertReadOnlySifDefaultsContract(
        contracts.get(InvoiceSifPreset.ACTION_SIF_DEFAULTS));
    assertTrue(contracts.get("createReturnInvoice").getParams().stream()
        .anyMatch(p -> InvoiceSifPreset.PARAM_SIF_FIELDS.equals(p.getName())));
  }

  /** sifDefaults without a record, or on a return that does not exist, is refused. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void handle_sifDefaultsRefusesAMissingReturn(Header header) throws Exception {
    try (InvoiceSifPreviewTestSupport.PreviewMocks mocks =
        new InvoiceSifPreviewTestSupport.PreviewMocks(true)) {
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, header.handler().handle(
          InvoiceSifPreviewTestSupport.sifDefaults(header.specName(), null))
          .getHttpStatus());
      assertEquals(HttpServletResponse.SC_NOT_FOUND, header.handler().handle(
          InvoiceSifPreviewTestSupport.sifDefaults(header.specName(), "missing"))
          .getHttpStatus());
    }
  }

  // ── action routing ────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testHandleReturnsNullForNonActionEndpoint(Header header) {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(header.handler().handle(ctx));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testHandleDelegatesToCloneHandlerAndReturnsItsResponse(Header header) {
    NeoContext ctx = postAction("clone");
    NeoResponse cloneResp = new NeoResponse(200, new JSONObject());
    when(header.cloneHandler().handle(ctx)).thenReturn(cloneResp);
    assertSame(cloneResp, header.handler().handle(ctx));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testHandleReturnsNullForUnknownAction(Header header) {
    assertNull(header.handler().handle(postAction("unknownAction")));
  }

  /** fillMissingStorageBins early-returns when the record id is null. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testHandleDocumentActionWithNullRecordIdReturnsNull(Header header) {
    assertNull(header.handler().handle(postAction("documentAction")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testHandleCreateReturnInvoiceMissingRecordIdReturnsBadRequest(Header header) {
    NeoResponse result = header.handler().handle(postAction("createReturnInvoice"));
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void handleRectifiableInvoices_missingRecordIdReturnsBadRequest(Header header) {
    NeoResponse result = header.handler().handle(postAction("rectifiableInvoices"));
    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  // ── ETP-5712: discoverability ──────────────────────────────────────────────

  /**
   * ETP-5712: confirming a return does NOT create the factura rectificativa, and an agent that
   * reads only {@code documentAction} must learn that and the call that does. The goods
   * receipt/shipment keep their own text ({@link InOutCompletionContract#CONTRACT}).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testDocumentActionDescriptionSaysConfirmingDoesNotCreateTheRectificativa(Header header) {
    String text = header.handler().actionContracts().get("documentAction").getDescription();

    assertTrue(text.contains("DRAFT"), text);
    assertTrue(text.contains("createReturnInvoice"), text);
    assertTrue(text.contains("factura rectificativa"), text);
    assertTrue(text.contains("does NOT create"), text);
    assertFalse(text.contains("createGoodsReceipt"),
        "the return text must not be the goods receipt/shipment one");
    assertFalse(InOutCompletionContract.CONTRACT.getDescription().contains("createReturnInvoice"),
        "the shared goods receipt/shipment contract stays untouched");
  }

  // ── afterHandle() — guard conditions ──────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @MethodSource("headers")
  void testAfterHandleReturnsNullForNonGetMethod(Header header) {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    assertNull(header.handler().afterHandle(ctx));
  }
}
