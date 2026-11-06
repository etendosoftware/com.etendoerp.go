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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;

/**
 * Unit tests for {@link RemittanceLinesHandler}: the {@code remittance} spec's lines are added by
 * «Seleccionar desde factura», never written directly — 403 {@code feature_disabled} with the flag
 * off, 405 {@code ETGO_RemittanceLinesReadOnly} for POST/PUT/PATCH, reads pass through, and a
 * DELETE runs the generic delete only while the line's remittance is not processed (422
 * {@code ETGO_RemittanceProcessedNotEditable} otherwise). {@code loadLine} is a stubbed seam and
 * {@link OBContext} is mocked statically for the admin-mode toggle.
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

  @ParameterizedTest
  @ValueSource(strings = { "POST", "PUT", "PATCH" })
  void everyCrudWriteAnswers405(String method) throws Exception {
    NeoResponse response = handler.handle(context(method, NeoEndpointType.CRUD));

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
