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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link FollowUpActionHandler} — the HTTP envelope of the follow-up create path
 * (ETP-5576): which requests it serves, the tenant guard, the status and body of every outcome,
 * the caller's request-body choices handed to the creator, the {@code input} block of a rejection
 * that asks for a choice, and the rollback on every non-2xx. It replaces the guard / error-path / 201 cases of the removed
 * {@code CreateInvoiceShipmentHandlerTest}; the handler is identity-free, so the former "wrong
 * spec name" guard became "action name not served by any registered flow".
 *
 * <p>The flows are real {@link FollowUpFlow}s over mocked resolvers/creators, and the real
 * {@link FollowUpDocumentService} runs between them, so "nothing ran" is asserted on the resolver
 * and the creator themselves.
 *
 * @covers com.etendoerp.go.schemaforge.FollowUpActionHandler
 */
class FollowUpActionHandlerTest {

  private static final List<PendingResolver.SourceLine> PENDING = Arrays.asList(
      new PendingResolver.SourceLine("il-1", BigDecimal.ONE),
      new PendingResolver.SourceLine("il-2", new BigDecimal("2")));

  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<SessionHandler> sessionHandlerStatic;
  private MockedStatic<TenantOwnership> tenantStatic;
  private SessionHandler sessionHandler;

  private PendingResolver shipmentResolver;
  private TargetCreator shipmentCreator;
  private PendingResolver receiptResolver;
  private TargetCreator receiptCreator;
  private FollowUpActionHandler handler;

  @BeforeEach
  void setUp() {
    obContextStatic = mockStatic(OBContext.class);
    sessionHandlerStatic = mockStatic(SessionHandler.class);
    tenantStatic = mockStatic(TenantOwnership.class);
    sessionHandler = mock(SessionHandler.class);
    sessionHandlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);

    shipmentResolver = invoiceResolver();
    shipmentCreator = mock(TargetCreator.class);
    receiptResolver = invoiceResolver();
    receiptCreator = mock(TargetCreator.class);
    List<FollowUpFlow> registered = Arrays.asList(
        FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT, shipmentResolver, shipmentCreator),
        FollowUpFlow.of(FollowUpTarget.GOODS_RECEIPT, receiptResolver, receiptCreator));
    handler = new FollowUpActionHandler(() -> registered);
  }

  @AfterEach
  void tearDown() {
    tenantStatic.close();
    sessionHandlerStatic.close();
    obContextStatic.close();
  }

  // ── guards ────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0} {1} {2} -> not served")
  @CsvSource({
      "CRUD,POST,createShipment",
      "ACTION,GET,createShipment",
      "ACTION,POST,documentAction",
  })
  void requestsNotServedByAnyFlowFallThrough(String endpoint, String method, String action) {
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.valueOf(endpoint)).httpMethod(method)
        .fieldName(action).recordId("inv-1").build();

    assertNull(handler.handle(ctx));
    tenantStatic.verifyNoInteractions();
    verifyNoInteractions(shipmentCreator, receiptCreator);
    verify(shipmentResolver, never()).lockSource(anyString());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   " })
  void blankRecordIdIsABadRequest(String recordId) {
    NeoResponse response = handler.handle(actionCtx("createShipment", recordId));

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    tenantStatic.verifyNoInteractions();
    verify(shipmentResolver, never()).lockSource(anyString());
  }

  // ── tenant guard ──────────────────────────────────────────────────────────

  @Test
  void recordOutsideTheCallersTenantIsNotFoundAndNothingRuns() throws Exception {
    tenantStatic.when(() -> TenantOwnership.loadOwned(any(), anyString())).thenReturn(null);

    NeoResponse response = handler.handle(actionCtx("createShipment", "inv-foreign"));

    assertEquals(404, response.getHttpStatus());
    assertEquals("FOLLOW_UP_SOURCE_NOT_FOUND",
        response.getBody().getJSONObject("error").getString("code"));
    // The flow's resolver names the entity the guard checks.
    tenantStatic.verify(() -> TenantOwnership.loadOwned(Invoice.class, "inv-foreign"));
    verify(shipmentResolver, never()).lockSource(anyString());
    verify(shipmentResolver, never()).loadSources(anyCollection());
    verifyNoInteractions(shipmentCreator);
    verify(sessionHandler).rollback();
  }

  // ── failures ──────────────────────────────────────────────────────────────

  /** A rejection that asks for no choice carries no {@code input} member. */
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "MISSING_SETUP,400,FOLLOW_UP_MISSING_SETUP",
      "INVALID_INPUT,400,FOLLOW_UP_INVALID_INPUT",
  })
  void businessRejectionAnswersItsReasonStatusCodeAndMessageAndRollsBack(String reason,
      int status, String code) throws Exception {
    ownedInvoice("inv-1");
    pendingLines(shipmentResolver, "inv-1");
    when(shipmentCreator.createTarget("inv-1", PENDING, FollowUpInputs.none()))
        .thenThrow(new FollowUpException(FollowUpException.Reason.valueOf(reason),
            "No storage bin found for warehouse: Main"));

    NeoResponse response = handler.handle(actionCtx("createShipment", "inv-1"));

    assertEquals(status, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(code, error.getString("code"));
    assertEquals(status, error.getInt("status"));
    assertEquals("No storage bin found for warehouse: Main", error.getString("message"));
    assertFalse(error.has("input"));
    verify(sessionHandler).rollback();
  }

  /**
   * A rejection that asks for a choice answers 409 with the key to send back and every option;
   * an option without a name is serialized as JSON {@code null}, not omitted.
   */
  @Test
  void warehouseRequiredAnswers409WithTheInputToChooseAndRollsBack() throws Exception {
    ownedInvoice("inv-1");
    pendingLines(shipmentResolver, "inv-1");
    FollowUpException.RequiredInput choice = new FollowUpException.RequiredInput("warehouseId",
        Arrays.asList(new FollowUpException.RequiredInput.Option("wh-a", "Almacen A"),
            new FollowUpException.RequiredInput.Option("wh-b", null)));
    when(shipmentCreator.createTarget("inv-1", PENDING, FollowUpInputs.none()))
        .thenThrow(new FollowUpException(FollowUpException.Reason.WAREHOUSE_REQUIRED,
            "Choose a warehouse", choice));

    NeoResponse response = handler.handle(actionCtx("createShipment", "inv-1"));

    assertEquals(409, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("FOLLOW_UP_WAREHOUSE_REQUIRED", error.getString("code"));
    assertEquals(409, error.getInt("status"));
    assertEquals("Choose a warehouse", error.getString("message"));
    JSONObject input = error.getJSONObject("input");
    assertEquals("warehouseId", input.getString("key"));
    JSONArray options = input.getJSONArray("options");
    assertEquals(2, options.length());
    assertEquals("wh-a", options.getJSONObject(0).getString("id"));
    assertEquals("Almacen A", options.getJSONObject(0).getString("name"));
    assertEquals("wh-b", options.getJSONObject(1).getString("id"));
    assertTrue(options.getJSONObject(1).has("name"));
    assertTrue(options.getJSONObject(1).isNull("name"));
    verify(sessionHandler).rollback();
  }

  @Test
  void anUnavailableSourceAnswersTheResolverReasonAndNeverCreates() throws Exception {
    ownedInvoice("inv-1");
    Map<String, PendingResolver.Source> verdicts = Collections.singletonMap("inv-1",
        PendingResolver.Source.unavailable("inv-1", FollowUpException.Reason.NOTHING_PENDING));
    when(shipmentResolver.loadSources(anyCollection())).thenReturn(verdicts);

    NeoResponse response = handler.handle(actionCtx("createShipment", "inv-1"));

    assertEquals(400, response.getHttpStatus());
    assertEquals("FOLLOW_UP_NOTHING_PENDING",
        response.getBody().getJSONObject("error").getString("code"));
    verifyNoInteractions(shipmentCreator);
    verify(sessionHandler).rollback();
  }

  @Test
  void otherDalErrorIsABadRequestWithItsMessageAndRollsBack() throws Exception {
    ownedInvoice("inv-1");
    pendingLines(shipmentResolver, "inv-1");
    when(shipmentCreator.createTarget("inv-1", PENDING, FollowUpInputs.none()))
        .thenThrow(new OBException("Product is not active"));

    NeoResponse response = handler.handle(actionCtx("createShipment", "inv-1"));

    assertEquals(400, response.getHttpStatus());
    assertEquals("Product is not active",
        response.getBody().getJSONObject("error").getString("message"));
    verify(sessionHandler).rollback();
  }

  @Test
  void unexpectedErrorIsA500WithAGenericMessageAndRollsBack() throws Exception {
    ownedInvoice("inv-1");
    pendingLines(shipmentResolver, "inv-1");
    when(shipmentCreator.createTarget("inv-1", PENDING, FollowUpInputs.none()))
        .thenThrow(new IllegalStateException("secret internals"));

    NeoResponse response = handler.handle(actionCtx("createShipment", "inv-1"));

    assertEquals(500, response.getHttpStatus());
    assertEquals("An internal error occurred while creating the document",
        response.getBody().getJSONObject("error").getString("message"));
    verify(sessionHandler).rollback();
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  // ── success ───────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "createShipment,shipment,goods-shipment,goodsShipment",
      "createGoodsReceipt,receipt,goods-receipt,goodsReceipt",
  })
  void createsWithTheFlowServingTheActionAndAnswers201(String action, String key, String spec,
      String entity) throws Exception {
    boolean shipment = "createShipment".equals(action);
    PendingResolver resolver = shipment ? shipmentResolver : receiptResolver;
    TargetCreator creator = shipment ? shipmentCreator : receiptCreator;
    TargetCreator other = shipment ? receiptCreator : shipmentCreator;
    ownedInvoice("inv-1");
    pendingLines(resolver, "inv-1");
    TargetCreator.Result created = new TargetCreator.Result("io-1", "DOC-0001", 2);
    when(creator.createTarget("inv-1", PENDING, FollowUpInputs.none())).thenReturn(created);

    NeoResponse response = handler.handle(actionCtx(action, "inv-1"));

    assertEquals(201, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("io-1", data.getString("id"));
    assertEquals("DOC-0001", data.getString("documentNo"));
    assertEquals(key, data.getString("followUp"));
    assertEquals(spec, data.getString("spec"));
    assertEquals(entity, data.getString("entity"));
    assertEquals(2, data.getInt("lineCount"));
    verify(resolver).lockSource("inv-1");
    verifyNoInteractions(other);
    verify(sessionHandler, never()).rollback();
    obContextStatic.verify(() -> OBContext.setAdminMode(true));
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void theRequestBodyReachesTheCreatorAsTheCallersInputs() throws Exception {
    ownedInvoice("inv-1");
    pendingLines(shipmentResolver, "inv-1");
    TargetCreator.Result created = new TargetCreator.Result("io-1", "DOC-0001", 2);
    ArgumentCaptor<FollowUpInputs> inputs = ArgumentCaptor.forClass(FollowUpInputs.class);
    when(shipmentCreator.createTarget(eq("inv-1"), eq(PENDING), inputs.capture()))
        .thenReturn(created);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("createShipment").recordId("inv-1")
        .requestBody(new JSONObject().put("warehouseId", " wh-1 ")).build();

    NeoResponse response = handler.handle(ctx);

    assertEquals(201, response.getHttpStatus());
    assertEquals("wh-1", inputs.getValue().get("warehouseId"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static PendingResolver invoiceResolver() {
    PendingResolver resolver = mock(PendingResolver.class);
    doReturn(Invoice.class).when(resolver).sourceEntity();
    return resolver;
  }

  private static void pendingLines(PendingResolver resolver, String id) {
    Map<String, PendingResolver.Source> verdicts =
        Collections.singletonMap(id, PendingResolver.Source.available(id, PENDING));
    when(resolver.loadSources(anyCollection())).thenReturn(verdicts);
  }

  private void ownedInvoice(String id) {
    Invoice invoice = mock(Invoice.class);
    tenantStatic.when(() -> TenantOwnership.loadOwned(Invoice.class, id)).thenReturn(invoice);
  }

  private static NeoContext actionCtx(String action, String recordId) {
    return NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(action).recordId(recordId).build();
  }
}
