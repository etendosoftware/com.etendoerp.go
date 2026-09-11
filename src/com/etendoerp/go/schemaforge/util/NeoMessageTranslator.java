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

import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * Single place where NEO resolves {@code @AD_Message_Key@} tokens into the text of the current
 * session language before the string crosses the HTTP boundary.
 *
 * <p>Etendo business logic (processes, AEAT tax reports, callouts) raises errors carrying the
 * raw AD_Message <em>search key</em> wrapped in {@code @…@} rather than the translated text.
 * Any NEO response that forwards such a message verbatim shows the literal key in the browser
 * (e.g. {@code @AEAT349_Phone_Contact_Mandatory@}), so every user-facing error path must funnel
 * through {@link #safeParseTranslation(String)}.
 *
 * <p>The "safe" part: {@link OBMessageUtils#parseTranslation(String)} needs a live
 * {@link org.openbravo.dal.core.OBContext} (for the language) and a database connection. Unit
 * tests exercise these handlers without either, so a failure to translate degrades to returning
 * the original text unchanged instead of propagating — translation is presentation, never a
 * reason to turn a handled error into an unhandled one.
 */
public final class NeoMessageTranslator {

  private NeoMessageTranslator() {
  }

  /**
   * Resolves {@code @Key@} AD_Message tokens in {@code text} using the current
   * {@link org.openbravo.dal.core.OBContext} language. Tokens embedded in a longer sentence are
   * resolved too — {@code parseTranslation} scans the whole string, not just an exact match.
   *
   * @param text
   *          the raw message; may be {@code null} or contain no token at all
   * @return the translated text, or {@code text} unchanged when it is null/empty or when no
   *         OBContext is available (e.g. in unit tests that call handlers without a live
   *         Etendo application context)
   */
  public static String safeParseTranslation(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    try {
      return OBMessageUtils.parseTranslation(text);
    } catch (Exception e) {
      return text;
    }
  }
}
