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
import static org.mockito.Mockito.mockStatic;

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
}
