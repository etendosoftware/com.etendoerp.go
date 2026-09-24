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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Unit tests for {@link GoodsMovementsHeaderHandler}.
 *
 * <p>The handler is a create-only pre-hook that materializes the movement DocumentNo into the
 * create body when the caller has no real value. Aligned with classic, the number always comes
 * from the generic {@code DocumentNo_M_Movement} table sequence
 * ({@code Utility.getDocumentNoConnection(..., "M_Movement", true)}); the doc-type overload of
 * {@code Utility.getDocumentNo} is never used.
 *
 * <p>ETP-5491 regression coverage: the number must be consumed exactly once per movement, i.e.
 * ONLY on a CRUD {@code POST} create ({@code recordId == null}). The "Procesar" button
 * ({@code POST /{entity}/{id}/action/processNow}) and any update must never consume a number.
 *
 * <p>All statics ({@link OBDal}, {@link OBContext}, {@link Utility},
 * {@link GoodsMovementProcessGuard}) are mocked for every test, so a
 * test that expects "no number consumed" really reaches the sequence call when the guard is
 * missing — it cannot pass by accident through a swallowed NPE.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsMovementsHeaderHandlerTest {

  private static final String DOCUMENT_NO = "documentNo";
  private static final String POST = "POST";
  private static final String PATCH = "PATCH";
  private static final String CLIENT_ID = "client-1";
  private static final String TABLE_M_MOVEMENT = "M_Movement";
  private static final String RECORD_ID = "MOVEMENT-1";
  private static final String PROCESS_NOW = "processNow";

  @Mock
  private NeoContext ctx;
  @Mock
  private OBDal dal;
  @Mock
  private Connection conn;
  @Mock
  private OBContext obContext;
  @Mock
  private Client client;
  @Mock
  private DocumentPostingService postingService;

  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<Utility> utilMock;
  private MockedStatic<GoodsMovementProcessGuard> guardMock;
  private MockedConstruction<DalConnectionProvider> providerConstruction;

  private final GoodsMovementsHeaderHandler handler = new GoodsMovementsHeaderHandler();

  @BeforeEach
  void setUp() {
    dalMock = Mockito.mockStatic(OBDal.class);
    obContextMock = Mockito.mockStatic(OBContext.class);
    utilMock = Mockito.mockStatic(Utility.class);
    // Default answer: validateBeforeProcess -> null (no stock rejection).
    guardMock = Mockito.mockStatic(GoodsMovementProcessGuard.class);
    providerConstruction = Mockito.mockConstruction(DalConnectionProvider.class);

    dalMock.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getConnection(false)).thenReturn(conn);
    when(dal.getConnection()).thenReturn(conn);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getId()).thenReturn(CLIENT_ID);
  }

  @AfterEach
  void tearDown() {
    providerConstruction.close();
    guardMock.close();
    utilMock.close();
    obContextMock.close();
    dalMock.close();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Stubs the class-level context as a CRUD request (create when {@code recordId} is null). */
  private NeoContext crudContext(String method, String recordId, JSONObject body) {
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.CRUD);
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getRecordId()).thenReturn(recordId);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /** Stubs the class-level context as a CRUD create ({@code POST}, no record id). */
  private NeoContext createContext(JSONObject body) {
    return crudContext(POST, null, body);
  }

  private void stubGenericSequence(String value) {
    utilMock.when(() -> Utility.getDocumentNoConnection(any(), any(), eq(CLIENT_ID),
        eq(TABLE_M_MOVEMENT), eq(true))).thenReturn(value);
  }

  /** Asserts that no overload of the document-number API was invoked (no number consumed). */
  private void verifyNoDocumentNoConsumed() {
    utilMock.verify(() -> Utility.getDocumentNoConnection(any(), any(), any(), any(), anyBoolean()),
        never());
    utilMock.verify(() -> Utility.getDocumentNo(any(), any(), any(), any(), any(), any(), any(),
        anyBoolean(), anyBoolean()), never());
    utilMock.verify(() -> Utility.getDocumentNo(any(), any(), any(), any(), any(), any(),
        anyBoolean(), anyBoolean()), never());
    utilMock.verify(() -> Utility.getDocumentNo(any(), anyString(), anyString(), anyBoolean()),
        never());
  }

  // ---------------------------------------------------------------------------------------------
  // Guards — pre-existing behavior
  // ---------------------------------------------------------------------------------------------

  /**
   * Non-POST requests fall straight through without consuming the sequence.
   */
  @Test
  void testNonPostMethodIsSkipped() {
    JSONObject body = new JSONObject();
    assertNull(handler.handle(crudContext(PATCH, null, body)));
    verifyNoDocumentNoConsumed();
  }

  /**
   * A null body is a no-op (no NPE, no sequence consumption).
   */
  @Test
  void testNullBodyIsSkipped() {
    assertNull(handler.handle(createContext(null)));
    verifyNoDocumentNoConsumed();
  }

  /**
   * A real caller-supplied DocumentNo always wins — the sequence is never touched.
   */
  @Test
  void testRealDocumentNoWins() throws JSONException {
    stubGenericSequence("SHOULD-NOT-BE-USED");
    JSONObject body = new JSONObject().put(DOCUMENT_NO, "MANUAL-1");

    assertNull(handler.handle(createContext(body)));

    assertEquals("MANUAL-1", body.getString(DOCUMENT_NO));
    verifyNoDocumentNoConsumed();
  }

  // ---------------------------------------------------------------------------------------------
  // ETP-5491 — a number is consumed only on CRUD create
  // ---------------------------------------------------------------------------------------------

  /**
   * ETP-5491 (double increment): the "Procesar" button sends
   * {@code POST /{entity}/{id}/action/processNow} with {@code {fieldValues:{processNow:'Y'}}}.
   * That is an ACTION on an existing record, not a create — it must not consume a number and the
   * body must reach the process untouched.
   */
  @Test
  void testProcessNowActionPostDoesNotConsumeDocumentNo() throws JSONException {
    stubGenericSequence("10000011");
    JSONObject body = new JSONObject().put("fieldValues",
        new JSONObject().put(PROCESS_NOW, "Y"));
    String before = body.toString();
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getHttpMethod()).thenReturn(POST);
    when(ctx.getRecordId()).thenReturn(RECORD_ID);
    when(ctx.getFieldName()).thenReturn(PROCESS_NOW);
    when(ctx.getRequestBody()).thenReturn(body);

    assertNull(handler.handle(ctx));

    assertFalse(body.has(DOCUMENT_NO));
    assertEquals(before, body.toString());
    verifyNoDocumentNoConsumed();
  }

  /**
   * ETP-5491: a CRUD update ({@code PUT} on an existing record) never consumes a number.
   */
  @Test
  void testCrudPutUpdateDoesNotConsumeDocumentNo() {
    stubGenericSequence("10000012");
    JSONObject body = new JSONObject();

    assertNull(handler.handle(crudContext("PUT", RECORD_ID, body)));

    assertFalse(body.has(DOCUMENT_NO));
    verifyNoDocumentNoConsumed();
  }

  /**
   * ETP-5491: a CRUD {@code POST} addressed to an existing record ({@code recordId} set) is not a
   * create and must not consume a number either.
   */
  @Test
  void testCrudPostWithRecordIdDoesNotConsumeDocumentNo() {
    stubGenericSequence("10000013");
    JSONObject body = new JSONObject();

    assertNull(handler.handle(crudContext(POST, RECORD_ID, body)));

    assertFalse(body.has(DOCUMENT_NO));
    verifyNoDocumentNoConsumed();
  }

  // ---------------------------------------------------------------------------------------------
  // ETP-5491 — create consumes exactly one number from DocumentNo_M_Movement (classic alignment)
  // ---------------------------------------------------------------------------------------------

  /**
   * ETP-5491: a CRUD create consumes exactly one number from the generic
   * {@code DocumentNo_M_Movement} sequence (updateNext=true) and never calls the doc-type
   * overload of {@code Utility.getDocumentNo}.
   */
  @Test
  void testCreateConsumesExactlyOneGenericSequenceNumber() throws JSONException {
    stubGenericSequence("10000017");
    JSONObject body = new JSONObject();

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000017", body.getString(DOCUMENT_NO));
    utilMock.verify(() -> Utility.getDocumentNoConnection(any(), any(), eq(CLIENT_ID),
        eq(TABLE_M_MOVEMENT), eq(true)), times(1));
    utilMock.verify(() -> Utility.getDocumentNo(any(), any(), any(), any(), any(), any(), any(),
        anyBoolean(), anyBoolean()), never());
  }

  /**
   * ETP-5491: the {@code <1000>} preview placeholder is replaced by the generic sequence value.
   */
  @Test
  void testPreviewPlaceholderIsReplacedByGenericSequence() throws JSONException {
    stubGenericSequence("10000018");
    JSONObject body = new JSONObject().put(DOCUMENT_NO, "<1000>");

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000018", body.getString(DOCUMENT_NO));
  }

  /**
   * ETP-5491: a blank sequence result leaves the body exactly as the caller sent it.
   */
  @Test
  void testBlankSequenceLeavesBodyUnchanged() throws JSONException {
    stubGenericSequence("");
    JSONObject body = new JSONObject().put(DOCUMENT_NO, "<1000>");
    String before = body.toString();

    assertNull(handler.handle(createContext(body)));

    assertEquals(before, body.toString());
  }

  // ---------------------------------------------------------------------------------------------
  // No-value variants, blank sequence and error path
  // ---------------------------------------------------------------------------------------------

  /**
   * The {@code <preview>} placeholder is replaced by the real sequence value.
   */
  @Test
  void testPreviewPlaceholderIsMaterialized() throws JSONException {
    stubGenericSequence("10000003");
    JSONObject body = new JSONObject().put(DOCUMENT_NO, "<10000003>");

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000003", body.getString(DOCUMENT_NO));
  }

  /**
   * An absent DocumentNo is materialized.
   */
  @Test
  void testAbsentDocumentNoIsMaterialized() throws JSONException {
    stubGenericSequence("10000004");
    JSONObject body = new JSONObject();

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000004", body.getString(DOCUMENT_NO));
  }

  /**
   * A JSON-null DocumentNo is materialized.
   */
  @Test
  void testJsonNullDocumentNoIsMaterialized() throws JSONException {
    stubGenericSequence("10000005");
    JSONObject body = new JSONObject().put(DOCUMENT_NO, JSONObject.NULL);

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000005", body.getString(DOCUMENT_NO));
  }

  /**
   * A blank DocumentNo is materialized.
   */
  @Test
  void testBlankDocumentNoIsMaterialized() throws JSONException {
    stubGenericSequence("10000006");
    JSONObject body = new JSONObject().put(DOCUMENT_NO, "   ");

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000006", body.getString(DOCUMENT_NO));
  }

  /**
   * When every sequence yields a blank value, the field is left unset and no error is raised.
   */
  @Test
  void testBlankSequenceLeavesDocumentNoUnset() {
    stubGenericSequence("");
    JSONObject body = new JSONObject();

    assertNull(handler.handle(createContext(body)));

    assertFalse(body.has(DOCUMENT_NO));
  }

  /**
   * Any failure during materialization is swallowed; the create still proceeds.
   */
  @Test
  void testErrorDuringMaterializationIsSwallowed() {
    utilMock.when(() -> Utility.getDocumentNoConnection(any(), any(), eq(CLIENT_ID),
        eq(TABLE_M_MOVEMENT), eq(true))).thenThrow(new RuntimeException("boom"));
    JSONObject body = new JSONObject();

    assertNull(handler.handle(createContext(body)));

    assertFalse(body.has(DOCUMENT_NO));
  }

  // ---------------------------------------------------------------------------------------------
  // ETP-5436 — posting service and process guard ordering
  // ---------------------------------------------------------------------------------------------

  /**
   * ETP-5436: a matched post/unpost action delegates to the injected
   * {@link DocumentPostingService} and its response is returned as-is, short-circuiting
   * before the documentNo materialization logic.
   */
  @Test
  void testHandleReturnsPostingResponseWhenServiceHandlesAction() {
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(postingService.handleAction(ctx)).thenReturn(sentinel);
    handler.setPostingService(postingService);

    assertSame(sentinel, handler.handle(ctx));
    verifyNoDocumentNoConsumed();
  }

  /**
   * ETP-5436: when the posting service does not claim the action (returns {@code null}),
   * the handler falls through to its documentNo materialization exactly as before — the posting
   * check is purely additive.
   */
  @Test
  void testPostingServiceReturningNullDoesNotBlockDocumentNoMaterialization() throws JSONException {
    when(postingService.handleAction(any())).thenReturn(null);
    handler.setPostingService(postingService);
    stubGenericSequence("10000099");
    JSONObject body = new JSONObject();

    assertNull(handler.handle(createContext(body)));

    assertEquals("10000099", body.getString(DOCUMENT_NO));
    verify(postingService).handleAction(any());
  }

  /**
   * ETP-5436: a non-POST request still skips materialization even with a posting service
   * present and consulted (returns null) — proves the two checks compose without
   * interfering with each other.
   */
  @Test
  void testPostingServiceReturningNullNonPostMethodStillSkipsDocumentNoMaterialization() {
    when(postingService.handleAction(any())).thenReturn(null);
    handler.setPostingService(postingService);
    JSONObject body = new JSONObject();

    assertNull(handler.handle(crudContext(PATCH, null, body)));

    verifyNoDocumentNoConsumed();
    verify(postingService).handleAction(any());
  }

  /**
   * ETP-5436 (order matters): {@link GoodsMovementProcessGuard}'s rejection must
   * short-circuit {@link GoodsMovementsHeaderHandler#handle} BEFORE the posting service is
   * ever consulted.
   */
  @Test
  void testProcessGuardRejectionShortCircuitsBeforePostingServiceIsConsulted() {
    handler.setPostingService(postingService);
    NeoResponse guardRejection = NeoResponse.error(400, "insufficient stock");
    guardMock.when(() -> GoodsMovementProcessGuard.validateBeforeProcess(ctx))
        .thenReturn(guardRejection);

    assertSame(guardRejection, handler.handle(ctx));

    verify(postingService, never()).handleAction(any());
  }
}
