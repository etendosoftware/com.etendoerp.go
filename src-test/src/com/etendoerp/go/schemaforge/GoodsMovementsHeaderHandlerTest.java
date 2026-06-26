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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.sql.Connection;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Unit tests for {@link GoodsMovementsHeaderHandler}.
 *
 * <p>The handler is a POST pre-hook that materializes the {@code DocumentNo_M_Movement} sequence
 * into the create body when the caller has no real value. These tests lock down every branch:
 * the method/body guards, the "real value wins" short-circuit, the three no-value variants
 * (absent / JSON-null / blank / {@code <preview>}) that trigger materialization, the blank-sequence
 * fallback that leaves the field untouched, and the swallow-on-error path.
 */
public class GoodsMovementsHeaderHandlerTest {

  private final GoodsMovementsHeaderHandler handler = new GoodsMovementsHeaderHandler();

  private NeoContext context(String method, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  /**
   * Non-POST requests fall straight through without consuming the sequence.
   */
  @Test
  public void nonPostMethodIsSkipped() {
    JSONObject body = new JSONObject();
    try (MockedStatic<Utility> util = Mockito.mockStatic(Utility.class)) {
      assertNull(handler.handle(context("PATCH", body)));
      util.verify(() -> Utility.getDocumentNoConnection(any(), any(), any(), any(), anyBoolean()), never());
    }
  }

  /**
   * A null body is a no-op (no NPE, no sequence consumption).
   */
  @Test
  public void nullBodyIsSkipped() {
    try (MockedStatic<Utility> util = Mockito.mockStatic(Utility.class)) {
      assertNull(handler.handle(context("POST", null)));
      util.verify(() -> Utility.getDocumentNoConnection(any(), any(), any(), any(), anyBoolean()), never());
    }
  }

  /**
   * A real caller-supplied DocumentNo always wins — the sequence is never touched.
   */
  @Test
  public void realDocumentNoWins() throws JSONException {
    JSONObject body = new JSONObject().put("documentNo", "MANUAL-1");
    try (MockedStatic<Utility> util = Mockito.mockStatic(Utility.class)) {
      assertNull(handler.handle(context("POST", body)));
      assertEquals("MANUAL-1", body.getString("documentNo"));
      util.verify(() -> Utility.getDocumentNoConnection(any(), any(), any(), any(), anyBoolean()), never());
    }
  }

  /**
   * The {@code <preview>} placeholder is replaced by the real sequence value.
   */
  @Test
  public void previewPlaceholderIsMaterialized() throws JSONException {
    JSONObject body = new JSONObject().put("documentNo", "<10000003>");
    runWithSequence(body, "10000003", false);
    assertEquals("10000003", body.getString("documentNo"));
  }

  /**
   * An absent DocumentNo is materialized.
   */
  @Test
  public void absentDocumentNoIsMaterialized() throws JSONException {
    JSONObject body = new JSONObject();
    runWithSequence(body, "10000004", false);
    assertEquals("10000004", body.getString("documentNo"));
  }

  /**
   * A JSON-null DocumentNo is materialized.
   */
  @Test
  public void jsonNullDocumentNoIsMaterialized() throws JSONException {
    JSONObject body = new JSONObject().put("documentNo", JSONObject.NULL);
    runWithSequence(body, "10000005", false);
    assertEquals("10000005", body.getString("documentNo"));
  }

  /**
   * A blank DocumentNo is materialized.
   */
  @Test
  public void blankDocumentNoIsMaterialized() throws JSONException {
    JSONObject body = new JSONObject().put("documentNo", "   ");
    runWithSequence(body, "10000006", false);
    assertEquals("10000006", body.getString("documentNo"));
  }

  /**
   * When the sequence yields a blank value, the field is left unset and no error is raised.
   */
  @Test
  public void blankSequenceLeavesDocumentNoUnset() {
    JSONObject body = new JSONObject();
    runWithSequence(body, "", false);
    assertFalse(body.has("documentNo"));
  }

  /**
   * Any failure during materialization is swallowed; the create still proceeds.
   */
  @Test
  public void errorDuringMaterializationIsSwallowed() {
    JSONObject body = new JSONObject();
    runWithSequence(body, null, true);
    assertFalse(body.has("documentNo"));
  }

  /**
   * Runs {@link GoodsMovementsHeaderHandler#handle} with the full static-mock stack
   * ({@link OBDal}, {@link OBContext}, {@link Utility}) and the {@link DalConnectionProvider}
   * constructor stubbed out.
   *
   * @param body
   *     the create body passed to the handler
   * @param sequenceValue
   *     value returned by {@code Utility.getDocumentNoConnection} (ignored when
   *     {@code throwOnSequence} is {@code true})
   * @param throwOnSequence
   *     when {@code true}, the sequence call throws to exercise the catch path
   */
  private void runWithSequence(JSONObject body, String sequenceValue, boolean throwOnSequence) {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(
        OBDal.class); MockedStatic<OBContext> ctxMock = Mockito.mockStatic(
        OBContext.class); MockedStatic<Utility> utilMock = Mockito.mockStatic(
        Utility.class); MockedConstruction<DalConnectionProvider> ignored = Mockito.mockConstruction(
        DalConnectionProvider.class)) {

      OBDal dal = mock(OBDal.class);
      Connection conn = mock(Connection.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection(false)).thenReturn(conn);

      OBContext obContext = mock(OBContext.class);
      Client client = mock(Client.class);
      when(client.getId()).thenReturn("client-1");
      when(obContext.getCurrentClient()).thenReturn(client);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      if (throwOnSequence) {
        utilMock.when(
            () -> Utility.getDocumentNoConnection(any(), any(), eq("client-1"), eq("M_Movement"), eq(true))).thenThrow(
            new RuntimeException("boom"));
      } else {
        utilMock.when(
            () -> Utility.getDocumentNoConnection(any(), any(), eq("client-1"), eq("M_Movement"), eq(true))).thenReturn(
            sequenceValue);
      }

      assertNull(handler.handle(context("POST", body)));
    }
  }
}
