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

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Text rules of the bounce detection (ETP-5581): how a statement line description is normalized
 * before it is compared, and the bank vocabulary that marks a line as the bounce of an earlier
 * movement (the {@code return_text} signal).
 *
 * <p>Pure functions, no DAL. The vocabulary is generic banking language, never the name of an
 * entity or a bank: it is the same for every account and every tenant. It is versioned so a change
 * to it is a deliberate, reviewable act — the detection is a proposal, never applied on its own,
 * but a word added here changes which pairs every account is offered.
 *
 * <p>Copied unchanged from the first iteration's {@code ReturnLineText} (commit {@code 1a5140cb9})
 * except for the names; the learned-text helper was dropped with the learned-text signal.
 */
final class BankBounceLineText {

  /** Bumped on every change to {@link #BOUNCE_VOCABULARY}. */
  static final int BOUNCE_VOCABULARY_VERSION = 1;

  /**
   * Words that mark a statement line as a bounce, already normalized (upper case, no accents,
   * German umlauts transliterated). Spanish, English and German bank wording seen on real
   * statements; {@code DEVOL} is the abbreviation several Spanish banks print.
   */
  static final List<String> BOUNCE_VOCABULARY = List.of(
      "RETROCESION", "DEVOLUCION", "DEVOL", "DEVUELTO", "RECHAZO", "IMPAGADO", "IMPAGO",
      "ANULACION", "RETURN", "RETURNED", "REVERSAL", "CHARGEBACK", "RUECKLASTSCHRIFT");

  /**
   * A vocabulary word this long or longer also matches as the prefix of a token, so plural and
   * inflected forms ({@code DEVOLUCIONES}, {@code RETURNS}) are recognized. Shorter words
   * ({@code DEVOL}) must match a whole token, or they would fire inside unrelated words.
   */
  private static final int PREFIX_MATCH_MIN_LENGTH = 6;

  private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z\\d]+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Utility class: static members only, never instantiated. */
  private BankBounceLineText() {
  }

  /**
   * Upper case, accents removed (German umlauts transliterated first, so {@code Rücklastschrift}
   * reads {@code RUECKLASTSCHRIFT}), every run of non-alphanumeric characters collapsed to one
   * space, trimmed.
   *
   * @param text the text; {@code null} reads as the empty string
   * @return the normalized text, never {@code null}
   */
  static String normalize(String text) {
    if (StringUtils.isBlank(text)) {
      return "";
    }
    String upper = text.toUpperCase(Locale.ROOT)
        .replace("Ä", "AE")
        .replace("Ö", "OE")
        .replace("Ü", "UE")
        .replace("ß", "SS");
    String stripped = DIACRITICS.matcher(Normalizer.normalize(upper, Normalizer.Form.NFD))
        .replaceAll("");
    return NON_ALPHANUMERIC.matcher(stripped).replaceAll(" ").trim();
  }

  /**
   * Whether the description carries a word of {@link #BOUNCE_VOCABULARY}.
   *
   * @param description the statement line description
   * @return {@code true} when a token is, or starts with, a vocabulary word
   */
  static boolean hasBounceWording(String description) {
    String normalized = normalize(description);
    if (normalized.isEmpty()) {
      return false;
    }
    for (String token : normalized.split(" ")) {
      for (String word : BOUNCE_VOCABULARY) {
        if (token.equals(word)
            || (word.length() >= PREFIX_MATCH_MIN_LENGTH && token.startsWith(word))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The last whitespace-separated token of the description, upper case, trailing punctuation
   * removed — where a PSD2-initiated payment prints its end-to-end id.
   *
   * @param description the statement line description
   * @return the token, empty when there is none
   */
  static String lastToken(String description) {
    String trimmed = StringUtils.trimToEmpty(description);
    if (trimmed.isEmpty()) {
      return "";
    }
    String[] tokens = WHITESPACE.split(trimmed);
    String last = tokens[tokens.length - 1];
    return StringUtils.stripEnd(last, ".,;:)]").toUpperCase(Locale.ROOT);
  }

  /**
   * Whether both counterparty names are present and equal once normalized.
   *
   * @param a one name
   * @param b the other name
   * @return {@code true} when both are present and equal
   */
  static boolean sameCounterparty(String a, String b) {
    String left = normalize(a);
    return !left.isEmpty() && left.equals(normalize(b));
  }
}
