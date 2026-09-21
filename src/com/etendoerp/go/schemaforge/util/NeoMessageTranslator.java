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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /**
   * Matches a single {@code @AD_Message_SearchKey@} token. An AD_Message search key is
   * {@code \w}-only, so the class is closed and cannot swallow the {@code @} delimiter: the
   * pattern is unambiguous and has no backtracking surface (java:S5852) even on a long message.
   */
  private static final Pattern MESSAGE_KEY_TOKEN = Pattern.compile("@(\\w+)@");

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

  /**
   * Extracts, in order of appearance, the AD_Message <em>search keys</em> a raw message carries as
   * {@code @Key@} tokens — the stable identity of the failure, before {@link
   * #safeParseTranslation(String)} replaces them with prose.
   *
   * <p>ETP-5316: a core PL/SQL document-action failure is assembled as a mix of message keys and
   * run-time data, e.g. {@code "@Inline@ 10, 20, 30, @ProductNotNullAndMovementQtyZero@"}. Once
   * translated, the only thing left to key off is the sentence itself, whose embedded AD line
   * numbers (10, 20, 30 — not the position the user sees) make it both unmappable and unhelpful.
   * Returning the keys alongside the translated text lets a client resolve the failure by identity
   * and author its own wording, instead of pattern-matching translated prose.
   *
   * <p>Ordering is preserved and duplicates are dropped, so a caller can take the first key it
   * recognises. Tokens that are not real AD_Message keys (there is no catalog lookup here) are
   * returned too — a client matches against its own allow-list, so an unknown token is inert.
   *
   * @param text
   *          the raw, untranslated message; may be {@code null}
   * @return the distinct keys in order of appearance, or an empty list when there are none
   */
  public static List<String> extractMessageKeys(String text) {
    if (text == null || text.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> keys = new LinkedHashSet<>();
    Matcher matcher = MESSAGE_KEY_TOKEN.matcher(text);
    while (matcher.find()) {
      keys.add(matcher.group(1));
    }
    return new ArrayList<>(keys);
  }
}
