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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;

/**
 * ETP-5316 — unit tests for the private {@code translatePInstanceResult} of
 * {@link NeoProcessService}: the CallProcess/document-action branch that now publishes
 * {@code messageKeys} alongside the translated {@code message}.
 *
 * <p>Why this matters: a core PL/SQL document-action failure (M_INOUT_POST and friends) is
 * assembled from AD_Message tokens plus run-time data, so the translated sentence embeds AD line
 * numbers (10, 20, 30…) that match no row the user can see and differ per document. The keys are
 * the only stable identity of the failure, and they exist ONLY before
 * {@code safeParseTranslation} runs — which is why the extraction has to happen here and cannot
 * be recovered downstream.
 *
 * <p>The method is {@code private static} and takes DAL entities, so the tests reach it by
 * reflection (same approach as {@link NeoProcessServiceExtractErrorTest}) with Mockito stand-ins
 * for {@link ProcessInstance} / {@link Process}. {@code OBMessageUtils.parseTranslation} is
 * mocked statically so the translated text is deterministic and clearly DIFFERENT from the raw
 * message — otherwise a test could not tell "translated" from "passed through unchanged".
 */
public class NeoProcessServiceMessageKeysTest {

  /** The raw errorMsg core writes to ProcessInstance for the canonical ETP-5316 failure. */
  private static final String RAW_DOC_ACTION_ERROR =
      "@ERROR=@Inline@ 10, 20, 30, 40, @ProductNotNullAndMovementQtyZero@";

  /** What parseTranslation turns the above into once the tokens are resolved. */
  private static final String TRANSLATED_DOC_ACTION_ERROR =
      "En la línea 10, 20, 30, 40, Cuando el producto no esta vacío entonces la cantidad movida "
          + "no debe ser cero.";

  private static Method translatePInstanceResult;

  @BeforeClass
  public static void setUp() throws Exception {
    translatePInstanceResult = NeoProcessService.class
        .getDeclaredMethod("translatePInstanceResult", ProcessInstance.class, Process.class);
    translatePInstanceResult.setAccessible(true);
  }

  private static NeoResponse invoke(ProcessInstance pInstance, Process process) throws Exception {
    try {
      return (NeoResponse) translatePInstanceResult.invoke(null, pInstance, process);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  private static ProcessInstance pInstance(Long result, String errorMsg) {
    ProcessInstance pInstance = mock(ProcessInstance.class);
    when(pInstance.getResult()).thenReturn(result);
    when(pInstance.getErrorMsg()).thenReturn(errorMsg);
    return pInstance;
  }

  private static Process process(String name) {
    Process process = mock(Process.class);
    when(process.getName()).thenReturn(name);
    return process;
  }

  private static List<String> keysOf(JSONArray array) throws Exception {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      out.add(array.getString(i));
    }
    return out;
  }

  /**
   * The headline case: the error response carries the AD_Message keys extracted from the RAW
   * message, while {@code message} is still the translated sentence it always was.
   */
  @Test
  public void testErrorBranchPublishesMessageKeysWhenRawMessageCarriesTokens() throws Exception {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation(
          "@Inline@ 10, 20, 30, 40, @ProductNotNullAndMovementQtyZero@"))
          .thenReturn(TRANSLATED_DOC_ACTION_ERROR);

      NeoResponse response = invoke(pInstance(0L, RAW_DOC_ACTION_ERROR), process("Process Shipment"));

      assertEquals(400, response.getHttpStatus());
      assertEquals("error", response.getBody().getString("status"));
      // `message` is untouched by ETP-5316 — a client that ignores messageKeys reads exactly
      // what it read before.
      assertEquals(TRANSLATED_DOC_ACTION_ERROR, response.getBody().getString("message"));
      assertEquals(
          Arrays.asList("Inline", "ProductNotNullAndMovementQtyZero"),
          keysOf(response.getBody().getJSONArray("messageKeys")));
    }
  }

  /**
   * The keys come from the raw text, so they survive a translation that erases every token —
   * which is the whole point: after {@code parseTranslation} there is nothing left to key off.
   */
  @Test
  public void testMessageKeysAreExtractedBeforeTranslationNotFromTheTranslatedText()
      throws Exception {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation("@lockedProduct@"))
          .thenReturn("Some lines have blocked products.");

      NeoResponse response = invoke(pInstance(0L, "@ERROR=@lockedProduct@"), process("P"));

      assertEquals("Some lines have blocked products.",
          response.getBody().getString("message"));
      assertFalse(response.getBody().getString("message").contains("@"));
      assertEquals(Arrays.asList("lockedProduct"),
          keysOf(response.getBody().getJSONArray("messageKeys")));
    }
  }

  /**
   * A message with no token must leave the response shape exactly as it was before ETP-5316 —
   * the field is omitted, never an empty array, so nothing downstream has to special-case it.
   */
  @Test
  public void testErrorBranchOmitsMessageKeysWhenRawMessageHasNoToken() throws Exception {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation("Plain failure, no tokens"))
          .thenReturn("Plain failure, no tokens");

      NeoResponse response = invoke(
          pInstance(0L, "@ERROR=Plain failure, no tokens"), process("P"));

      assertEquals(400, response.getHttpStatus());
      assertEquals("Plain failure, no tokens", response.getBody().getString("message"));
      assertFalse("messageKeys must be absent, not empty",
          response.getBody().has("messageKeys"));
    }
  }

  /**
   * A ProcessInstance that failed without writing an errorMsg keeps the pre-existing
   * "Process failed" fallback, and has no keys to publish.
   */
  @Test
  public void testErrorBranchWithNullErrorMsgKeepsTheFallbackAndHasNoMessageKeys()
      throws Exception {
    NeoResponse response = invoke(pInstance(0L, null), process("P"));

    assertEquals(400, response.getHttpStatus());
    assertEquals("error", response.getBody().getString("status"));
    assertEquals("Process failed", response.getBody().getString("message"));
    assertFalse(response.getBody().has("messageKeys"));
  }

  /**
   * A null {@code result} is read as 0 (failure) by the method's own null-guard — the fallback
   * path must behave identically to an explicit 0.
   */
  @Test
  public void testNullResultIsTreatedAsFailure() throws Exception {
    NeoResponse response = invoke(pInstance(null, null), process("P"));

    assertEquals(400, response.getHttpStatus());
    assertEquals("Process failed", response.getBody().getString("message"));
  }

  /**
   * ETP-5316 is scoped to the error branch. A success response must never grow the field, even
   * when core's success message happens to carry a token — a client keying off messageKeys would
   * otherwise render a failure wording for a document that completed.
   */
  @Test
  public void testSuccessBranchNeverPublishesMessageKeysEvenWithTokensInTheMessage()
      throws Exception {
    NeoResponse response = invoke(
        pInstance(1L, "@SUCCESS=@ProcessOK@ @Inline@ 10"), process("Process Shipment"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("success", response.getBody().getString("status"));
    assertEquals("@ProcessOK@ @Inline@ 10", response.getBody().getString("message"));
    assertFalse(response.getBody().has("messageKeys"));
  }

  @Test
  public void testSuccessBranchWithoutErrorMsgUsesTheProcessNameAndHasNoMessageKeys()
      throws Exception {
    NeoResponse response = invoke(pInstance(1L, null), process("Process Shipment"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("Process Process Shipment executed successfully",
        response.getBody().getString("message"));
    assertFalse(response.getBody().has("messageKeys"));
  }

  @Test
  public void testSuccessBranchWithBlankErrorMsgFallsBackToTheProcessName() throws Exception {
    NeoResponse response = invoke(pInstance(1L, "   "), process("Process Shipment"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("Process Process Shipment executed successfully",
        response.getBody().getString("message"));
    assertFalse(response.getBody().has("messageKeys"));
  }

  /**
   * Repeated structural tokens are collapsed once, in first-seen order, so the client's
   * "take the first key I recognise" rule lands on the real failure rather than on the fourth
   * copy of {@code @Inline@}.
   */
  @Test
  public void testMessageKeysAreDeduplicatedInFirstSeenOrder() throws Exception {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      String raw = "@Inline@ 10, @Inline@ 20, @InoutLineWithoutLocator@, @Inline@ 30";
      msg.when(() -> OBMessageUtils.parseTranslation(raw)).thenReturn("translated");

      NeoResponse response = invoke(pInstance(0L, "@ERROR=" + raw), process("P"));

      assertEquals(Arrays.asList("Inline", "InoutLineWithoutLocator"),
          keysOf(response.getBody().getJSONArray("messageKeys")));
    }
  }

  /**
   * Translation is presentation: when it blows up (no OBContext — every unit test, and any code
   * path running outside a request) the raw text is what the user sees, and the keys must still
   * be published so the client can override that raw text with its own wording.
   */
  @Test
  public void testMessageKeysSurviveAFailedTranslation() throws Exception {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation(
          "@Inline@ 10, 20, 30, 40, @ProductNotNullAndMovementQtyZero@"))
          .thenThrow(new NullPointerException("no OBContext"));

      NeoResponse response = invoke(pInstance(0L, RAW_DOC_ACTION_ERROR), process("P"));

      assertEquals(400, response.getHttpStatus());
      assertTrue(response.getBody().getString("message").contains("@Inline@"));
      assertEquals(
          Arrays.asList("Inline", "ProductNotNullAndMovementQtyZero"),
          keysOf(response.getBody().getJSONArray("messageKeys")));
    }
  }
}
