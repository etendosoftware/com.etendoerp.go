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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;

/**
 * Unit tests for {@link RemittanceLinesHandler}: the {@code remittance} spec's lines are added by
 * «Seleccionar desde factura», never created directly — 403 {@code feature_disabled} with the flag
 * off, 405 {@code ETGO_RemittanceLinesReadOnly} for POST, reads pass through, a PUT/PATCH of «A
 * remesar» ({@code amount}) is checked against the due's outstanding ({@link RemittanceLineAmount})
 * the same way for REST and MCP, and a DELETE runs the generic delete only while the line's
 * remittance is not processed (422 {@code ETGO_RemittanceProcessedNotEditable} otherwise).
 * {@code loadLine} is a stubbed seam and {@link OBContext} is mocked statically for the admin-mode
 * toggle.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceLinesHandler
 */
class RemittanceLinesHandlerTest {

  private static final String LINE_ID = "LINE-1";

  private RemittanceLinesHandler handler;
  private MockedStatic<OBContext> contextStatic;

  @BeforeEach
  void setUp() {
    handler = spy(new RemittanceLinesHandler());
    doReturn(true).when(handler).isFlagEnabled();
    contextStatic = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  @ParameterizedTest
  @ValueSource(strings = { "GET", "POST", "DELETE" })
  void everyRequestAnswers403WhileTheFlagIsOff(String method) throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    NeoResponse response = handler.handle(context(method, NeoEndpointType.CRUD));

    assertEquals(403, response.getHttpStatus());
    assertEquals("feature_disabled", response.getBody().getJSONObject("error").getString("code"));
  }

  @Test
  void aCrudCreateAnswers405() throws Exception {
    NeoResponse response = handler.handle(context("POST", NeoEndpointType.CRUD));

    assertEquals(405, response.getHttpStatus());
    assertEquals(RemittanceLinesHandler.MSG_READ_ONLY,
        response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0));
  }

  @Test
  void aWriteWithoutAnEndpointTypeIsTreatedAsCrud() {
    assertEquals(405, handler.handle(context("POST", null)).getHttpStatus());
  }

  @Test
  void readsAndSubEndpointsPassThrough() {
    assertNull(handler.handle(context("GET", NeoEndpointType.CRUD)));
    assertNull(handler.handle(context("POST", NeoEndpointType.DEFAULTS)));
    assertNull(handler.handle(context("POST", NeoEndpointType.SELECTOR)));
  }

  // ---------------------------------------------------------------- PUT / PATCH «A remesar»

  @ParameterizedTest
  @ValueSource(strings = { "PUT", "PATCH" })
  void aPartOfTheDueRunsTheGenericWrite(String method) throws Exception {
    doReturn(lineWithDue(false, "133.10")).when(handler).loadLine(LINE_ID);

    assertNull(handler.handle(update(method, new JSONObject().put("amount", 50), false)));
    assertNull(handler.handle(update(method, new JSONObject().put("amount", "133.10"), false)),
        "the whole outstanding is still a part of it");
    verify(handler, Mockito.times(2)).loadLine(LINE_ID);
    contextStatic.verify(() -> OBContext.setAdminMode(true), Mockito.times(2));
    contextStatic.verify(OBContext::restorePreviousMode, Mockito.times(2));
  }

  /**
   * Alex NIT (ETP-6148 round 3): the hook reads «A remesar» under its API key {@code amount} only.
   * The column name ({@code Amount}, {@code AMOUNT}) or any other key is not judged here; the
   * generic write drops it as it does any key that is not a writable field.
   */
  @Test
  void onlyTheApiKeyAmountIsJudged() throws Exception {
    doReturn(lineWithDue(false, "133.10")).when(handler).loadLine(LINE_ID);

    assertNull(handler.handle(update("PATCH", new JSONObject().put("Amount", -5)
        .put("AMOUNT", 999999).put("lineNo", 1).put("invoiceDocumentNo", "X"), false)));
    NeoResponse response = handler.handle(update("PATCH",
        new JSONObject().put("Amount", 10).put("amount", -5), false));
    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceLineAmountInvalid", messageKey(response));
  }

  @ParameterizedTest
  @ValueSource(strings = { "PUT", "PATCH" })
  void anAmountAboveTheOutstandingIsRefused422WithTheMax(String method) throws Exception {
    doReturn(lineWithDue(false, "133.10")).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.handle(update(method,
        new JSONObject().put("amount", "133.11"), false));

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceLineAmountInvalid", messageKey(response));
    assertEquals(0, new BigDecimal("133.10").compareTo(new BigDecimal(
        response.getBody().getJSONObject("error").get("max").toString())));
    // the message lookup toggles admin mode too: at least the handler's own restore
    contextStatic.verify(OBContext::restorePreviousMode, Mockito.atLeastOnce());
  }

  @Test
  void aZeroANegativeOrANonNumericAmountIsRefused422() throws Exception {
    doReturn(lineWithDue(false, "133.10")).when(handler).loadLine(LINE_ID);

    for (Object amount : new Object[] { 0, "-5", "abc", JSONObject.NULL, "" }) {
      NeoResponse response = handler.handle(update("PUT", new JSONObject().put("amount", amount),
          false));

      assertEquals(422, response.getHttpStatus(), String.valueOf(amount));
      assertEquals(RemittanceLineAmount.MSG_INVALID, messageKey(response), String.valueOf(amount));
    }
  }

  @Test
  void anAmountWrappedInDataIsCheckedTheSameWay() throws Exception {
    doReturn(lineWithDue(false, "133.10")).when(handler).loadLine(LINE_ID);

    NeoResponse refused = handler.handle(update("PUT",
        new JSONObject().put("data", new JSONObject().put("amount", 999)), false));
    assertEquals(422, refused.getHttpStatus());
    assertEquals(RemittanceLineAmount.MSG_INVALID, messageKey(refused));

    assertNull(handler.handle(update("PUT",
        new JSONObject().put("data", new JSONObject().put("amount", 10)), false)));
  }

  @Test
  void restAndMcpUpdatesGetTheSameAnswer() throws Exception {
    doReturn(lineWithDue(false, "133.10")).when(handler).loadLine(LINE_ID);

    for (Object amount : new Object[] { 40, 500 }) {
      NeoResponse rest = handler.handle(update("PATCH", new JSONObject().put("amount", amount),
          false));
      NeoResponse mcp = handler.handle(update("PATCH", new JSONObject().put("amount", amount),
          true));

      if (rest == null) {
        assertNull(mcp, "the MCP edit passes where the REST one does");
      } else {
        assertEquals(rest.getHttpStatus(), mcp.getHttpStatus());
        assertEquals(messageKey(rest), messageKey(mcp));
      }
    }
  }

  @Test
  void anUpdateOfALineOfAProcessedRemittanceIsRefused422WhateverItChanges() throws Exception {
    RemittanceLine line = lineWithDue(true, "133.10");
    doReturn(line).when(handler).loadLine(LINE_ID);

    for (JSONObject body : new JSONObject[] { new JSONObject().put("amount", 10),
        new JSONObject().put("description", "x") }) {
      NeoResponse response = handler.handle(update("PUT", body, false));

      assertEquals(422, response.getHttpStatus());
      assertEquals("ETGO_RemittanceProcessedNotEditable", messageKey(response));
    }
    verify(line, never()).getPaymentScheduleDetail();
  }

  @Test
  void anUpdateWithoutAmountIsLeftToTheGenericWrite() throws Exception {
    RemittanceLine line = lineWithDue(false, "133.10");
    doReturn(line).when(handler).loadLine(LINE_ID);

    assertNull(handler.handle(update("PUT", new JSONObject().put("description", "x"), false)));
    assertNull(handler.handle(update("PUT", null, false)), "no body at all");
    assertNull(handler.handle(update("PUT",
        new JSONObject().put("data", new JSONObject().put("description", "x")), false)));
    verify(line, never()).getPaymentScheduleDetail();
  }

  @Test
  void theAmountOfALineWithoutADueCannotBeChanged() throws Exception {
    RemittanceLine line = lineOf(false);
    doReturn(line).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.handle(update("PUT", new JSONObject().put("amount", 10),
        false));

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceLineAmountNotEditable", messageKey(response));
  }

  @Test
  void anUpdateOfAnUnknownLineIsLeftToTheGenericWrite() throws Exception {
    doReturn(null).when(handler).loadLine(LINE_ID);

    assertNull(handler.handle(update("PUT", new JSONObject().put("amount", 999999), false)));
  }

  @Test
  void anUpdateAnswers403WhileTheFlagIsOffWithoutLoadingTheLine() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    NeoResponse response = handler.handle(update("PATCH", new JSONObject().put("amount", 1),
        false));

    assertEquals(403, response.getHttpStatus());
    verify(handler, never()).loadLine(any());
  }

  // ---------------------------------------------------------------- DELETE

  @Test
  void deletingALineOfAnUnprocessedRemittanceRunsTheGenericDelete() {
    doReturn(lineOf(false)).when(handler).loadLine(LINE_ID);

    assertNull(handler.handle(delete()));
    verify(handler).loadLine(LINE_ID);
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void deletingALineOfAProcessedRemittanceIsRefused422() throws Exception {
    doReturn(lineOf(true)).when(handler).loadLine(LINE_ID);

    NeoResponse response = handler.handle(delete());

    assertEquals(422, response.getHttpStatus());
    assertEquals("ETGO_RemittanceProcessedNotEditable",
        response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0));
    assertEquals(RemittanceHeaderHandler.MSG_PROCESSED, RemittanceLinesHandler.MSG_PROCESSED);
  }

  @Test
  void deletingAnUnknownLineIsLeftToTheGenericDelete() {
    doReturn(null).when(handler).loadLine(LINE_ID);
    assertNull(handler.handle(delete()));

    RemittanceLine orphan = mock(RemittanceLine.class);
    doReturn(orphan).when(handler).loadLine(LINE_ID);
    assertNull(handler.handle(delete()), "a line without a remittance is not judged here");
  }

  @Test
  void aDeleteAnswers403WhileTheFlagIsOffWithoutLoadingTheLine() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    NeoResponse response = handler.handle(delete());

    assertEquals(403, response.getHttpStatus());
    verify(handler, never()).loadLine(any());
  }

  @Test
  void loadLineOfABlankIdIsNull() {
    assertNull(new RemittanceLinesHandler().loadLine(null));
    assertNull(new RemittanceLinesHandler().loadLine("  "));
  }

  private static NeoContext delete() {
    NeoContext ctx = context("DELETE", NeoEndpointType.CRUD);
    when(ctx.getRecordId()).thenReturn(LINE_ID);
    return ctx;
  }

  private static NeoContext update(String method, JSONObject body, boolean mcp) {
    NeoContext ctx = context(method, NeoEndpointType.CRUD);
    when(ctx.getRecordId()).thenReturn(LINE_ID);
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.isMcpOrigin()).thenReturn(mcp);
    return ctx;
  }

  private static RemittanceLine lineWithDue(boolean processed, String dueAmount) {
    FIN_PaymentScheduleDetail due = mock(FIN_PaymentScheduleDetail.class);
    when(due.getAmount()).thenReturn(new BigDecimal(dueAmount));
    RemittanceLine line = lineOf(processed);
    when(line.getPaymentScheduleDetail()).thenReturn(due);
    return line;
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }

  private static RemittanceLine lineOf(boolean processed) {
    Remittance remittance = mock(Remittance.class);
    when(remittance.isProcessed()).thenReturn(processed);
    RemittanceLine line = mock(RemittanceLine.class);
    when(line.getRemittance()).thenReturn(remittance);
    return line;
  }

  private static NeoContext context(String method, NeoEndpointType endpoint) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("remittance");
    when(ctx.getEntityName()).thenReturn("lines");
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getEndpointType()).thenReturn(endpoint);
    return ctx;
  }
}
