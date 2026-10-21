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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.service.db.DalConnectionProvider;

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

  /**
   * ETP-5577 — the AD_Messages core raises when a document's date has no open period:
   * {@code PeriodNotAvailable} (completions, posting) and {@code PeriodNotValid} (the
   * {@code GL_JOURNAL} insert/update trigger). Both have the same way out — open the period.
   */
  public static final List<String> PERIOD_REFUSAL_KEYS =
      List.of("PeriodNotAvailable", "PeriodNotValid");

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
   * Translates a raw failure message the way core's datasource does, but in an explicit language:
   * {@code @Key@} tokens are resolved through {@code AD_Message}, and a constraint violation is
   * replaced by the AD message named after the constraint, when there is one (ETP-5782).
   *
   * <p>Why not {@link OBMessageUtils#messageBD(String)}: it reads the language from the request's
   * session variables, which NEO's token-authenticated requests do not carry, and it has already
   * echoed a message code straight back to a client (ETP-5073). The language here is the
   * {@link OBContext} one, which {@code NeoAuthenticator} sets from {@code Accept-Language}.
   *
   * <p>Never throws, and needs a usable session: a translation that fails — no context, an aborted
   * transaction — returns {@code text} unchanged.
   *
   * @param text the raw message; may be {@code null}
   * @return the translated message, or {@code text} when nothing could be resolved
   */
  public static String translateDbMessage(String text) {
    if (text == null || text.isBlank()) {
      return text;
    }
    try {
      OBContext context = OBContext.getOBContext();
      String language = context != null && context.getLanguage() != null
          ? context.getLanguage().getLanguage()
          : null;
      OBError translated = OBMessageUtils.translateError(new DalConnectionProvider(false), null,
          language, text);
      String message = translated != null ? translated.getMessage() : null;
      return message == null || message.isBlank() ? text : message;
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
    return extractKeys(text);
  }

  /**
   * Which of {@code candidateKeys} a failure message is, recognised from its {@code @Key@} token
   * or from the message's own text in ANY language.
   *
   * <p>ETP-5577: some core paths hand NEO the error already translated — {@code ProcessInvoiceUtil}
   * and {@code FIN_AddPaymentFromJournal} translate it themselves (the latter in its own language,
   * not the user's), and a datasource write reports a trigger's message as prose. The raw token is
   * gone, so the AD_Message text is the only identity left. Matching every translation, not just
   * the session language, is what makes the English text a Spanish user receives recognisable.
   *
   * <p>Never throws: the keys are a convenience on an error that is already being answered, so a
   * failed catalog lookup only means no key.
   *
   * @param text          the message as received; may be {@code null}
   * @param candidateKeys the AD_Message search keys to look for
   * @return the recognised keys, in {@code candidateKeys} order; empty when none
   */
  public static List<String> recognizeMessageKeys(String text, List<String> candidateKeys) {
    if (text == null || text.isBlank()) {
      return Collections.emptyList();
    }
    List<String> tokens = extractKeys(text);
    List<String> recognized = new ArrayList<>();
    for (String key : candidateKeys) {
      if (tokens.contains(key) || messageTexts(key).stream()
          .anyMatch(t -> t != null && !t.isBlank() && text.contains(t.trim()))) {
        recognized.add(key);
      }
    }
    return recognized;
  }

  /** AD_Message texts per key; they only change on a rebuild. Failed reads are not cached. */
  private static final Map<String, List<String>> MESSAGE_TEXTS = new ConcurrentHashMap<>();

  /**
   * Every text of an AD_Message: the base one and each translation. Empty when unreadable. Cached:
   * this runs on error responses, possibly in a session whose transaction is already aborted.
   */
  static List<String> messageTexts(String key) {
    List<String> cached = MESSAGE_TEXTS.get(key);
    if (cached != null) {
      return cached;
    }
    List<String> loaded = loadMessageTexts(key);
    if (!loaded.isEmpty()) {
      MESSAGE_TEXTS.put(key, Collections.unmodifiableList(loaded));
    }
    return loaded;
  }

  private static List<String> loadMessageTexts(String key) {
    try {
      OBContext.setAdminMode(true);
      try {
        List<String> texts = new ArrayList<>(OBDal.getInstance().getSession()
            .createQuery("select m.messageText from ADMessage m where m.searchKey = :key",
                String.class)
            .setParameter("key", key)
            .list());
        texts.addAll(OBDal.getInstance().getSession()
            .createQuery("select t.messageText from ADMessageTrl t where t.message.searchKey = :key",
                String.class)
            .setParameter("key", key)
            .list());
        return texts;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  private static List<String> extractKeys(String text) {
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
