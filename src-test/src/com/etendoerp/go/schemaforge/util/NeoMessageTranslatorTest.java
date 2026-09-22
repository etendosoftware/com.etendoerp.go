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

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * Unit tests for {@link NeoMessageTranslator} — the single place where NEO turns raw
 * {@code @AD_Message_Key@} tokens into session-language text before they reach the browser.
 */
public class NeoMessageTranslatorTest {

  private static final String KEY = "@AEAT349_Phone_Contact_Mandatory@";
  private static final String TRANSLATED =
      "Debe indicar la persona de contacto y el teléfono de la declaración.";

  /**
   * The happy path this class exists for: an AD_Message key must come back as its translated
   * text, never as the literal key.
   */
  @Test
  public void testResolvesAdMessageKey() {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation(KEY)).thenReturn(TRANSLATED);

      assertEquals(TRANSLATED, NeoMessageTranslator.safeParseTranslation(KEY));
    }
  }

  /**
   * Without a live OBContext (every mocked unit test, and any code path running outside a
   * request) {@code parseTranslation} blows up. Translation is presentation, so the helper must
   * degrade to the raw text rather than turn a handled error into an unhandled one.
   */
  @Test
  public void testFallsBackToRawTextWhenNoContext() {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation(KEY))
          .thenThrow(new NullPointerException("no OBContext"));

      assertEquals(KEY, NeoMessageTranslator.safeParseTranslation(KEY));
    }
  }

  /**
   * Null and empty inputs short-circuit before any DB/context work, so a message-less error
   * never becomes an exception inside the error handler itself.
   */
  @Test
  public void testNullAndEmptyPassThroughUntouched() {
    assertNull(NeoMessageTranslator.safeParseTranslation(null));
    assertEquals("", NeoMessageTranslator.safeParseTranslation(""));
  }

  /**
   * Plain text with no token is returned as-is (delegated verbatim to {@code parseTranslation},
   * which scans for {@code @} and finds nothing).
   */
  @Test
  public void testPlainTextIsUnchanged() {
    try (MockedStatic<OBMessageUtils> msg = mockStatic(OBMessageUtils.class)) {
      msg.when(() -> OBMessageUtils.parseTranslation("plain error")).thenReturn("plain error");

      assertEquals("plain error", NeoMessageTranslator.safeParseTranslation("plain error"));
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────
  // ETP-5316 — extractMessageKeys: the AD_Message search keys a raw message carries, read
  // BEFORE translation. Pure string work: no OBContext, no DB, so no static mocking needed.
  // ─────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The real failure this method exists for: a core document-action error is assembled as a mix
   * of AD_Message tokens and run-time data. The two keys must come back in order, and the AD
   * line numbers (10, 20, 30 — not the row positions the user sees) must not leak into them.
   */
  @Test
  public void testExtractsKeysFromRealDocumentActionFailure() {
    List<String> keys = NeoMessageTranslator.extractMessageKeys(
        "@Inline@ 10, 20, 30, @ProductNotNullAndMovementQtyZero@");

    assertEquals(Arrays.asList("Inline", "ProductNotNullAndMovementQtyZero"), keys);
  }

  /**
   * A null message is the error handler's own edge case (a ProcessInstance with no errorMsg), so
   * it must degrade to "no keys" rather than throw inside the handler.
   */
  @Test
  public void testExtractReturnsEmptyListForNull() {
    assertEquals(Collections.emptyList(), NeoMessageTranslator.extractMessageKeys(null));
  }

  @Test
  public void testExtractReturnsEmptyListForEmptyString() {
    assertEquals(Collections.emptyList(), NeoMessageTranslator.extractMessageKeys(""));
  }

  /**
   * Already-translated prose (the common case for an OBUIAPP-style message) carries no token, so
   * there is nothing to publish and the field is omitted from the response upstream.
   */
  @Test
  public void testExtractReturnsEmptyListWhenNoTokenPresent() {
    assertTrue(NeoMessageTranslator
        .extractMessageKeys("There are lines without a quantity.").isEmpty());
  }

  @Test
  public void testExtractReadsASingleToken() {
    assertEquals(Collections.singletonList("lockedProduct"),
        NeoMessageTranslator.extractMessageKeys("@lockedProduct@"));
  }

  /**
   * Order of appearance is the contract: the client takes the FIRST key it recognises, so a
   * reordering here would change which message the user reads.
   */
  @Test
  public void testExtractPreservesOrderOfAppearance() {
    assertEquals(
        Arrays.asList("Inline", "InActiveProducts", "MovementQtyCheck"),
        NeoMessageTranslator.extractMessageKeys(
            "@Inline@ 10 @InActiveProducts@ 20 @MovementQtyCheck@"));
  }

  /**
   * Core repeats the structural {@code @Inline@} token once per offending line. Duplicates are
   * dropped, and the surviving entry keeps the position of its FIRST occurrence — otherwise a
   * repeated structural token could outrank the real failure it precedes.
   */
  @Test
  public void testExtractDropsDuplicatesKeepingTheFirstPosition() {
    assertEquals(
        Arrays.asList("Inline", "ProductNotNullAndMovementQtyZero"),
        NeoMessageTranslator.extractMessageKeys(
            "@Inline@ 10, @Inline@ 20, @ProductNotNullAndMovementQtyZero@, @Inline@ 30"));
  }

  /**
   * No whitespace is required after a token — the closing {@code @} is the delimiter, and the
   * digit that follows it in core's output must not be swallowed into the key.
   */
  @Test
  public void testExtractHandlesATokenImmediatelyFollowedByADigit() {
    assertEquals(Arrays.asList("Inline", "lockedProduct"),
        NeoMessageTranslator.extractMessageKeys("@Inline@10 @lockedProduct@"));
  }

  /**
   * A token that ends a sentence keeps its key clean: {@code \w} cannot match the trailing dot.
   */
  @Test
  public void testExtractHandlesATokenAdjacentToPunctuation() {
    assertEquals(Collections.singletonList("productWithoutAttributeSet"),
        NeoMessageTranslator.extractMessageKeys("@productWithoutAttributeSet@."));
  }

  /**
   * An e-mail address is the obvious false positive for an {@code @…@} scan. A single {@code @}
   * is not a token, and a dot cannot be part of a key, so nothing is extracted — a user-supplied
   * address inside an error message never becomes a bogus "key" the client might act on.
   */
  @Test
  public void testExtractIgnoresEmailLikeText() {
    assertTrue(NeoMessageTranslator.extractMessageKeys("user@example.com").isEmpty());
    assertTrue(NeoMessageTranslator
        .extractMessageKeys("Could not notify user@example.com about the failure").isEmpty());
  }
}
