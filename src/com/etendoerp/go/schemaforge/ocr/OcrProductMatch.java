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

package com.etendoerp.go.schemaforge.ocr;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;

/**
 * Decides what to do with the products the similarity search proposed for one line — a port of the
 * application's {@code classifyProductMatch} (ETP-5715).
 *
 * <p>Exactly one candidate whose name equals the description (trimmed, case- and accent-insensitive,
 * n-tilde distinct) is safe to take as the match. Failing that, the single highest candidate at
 * {@link #AUTO_MATCH_MIN_SIMILARITY} or more is taken too (ETP-5654), even if a runner-up is also
 * above the threshold; two or more candidates tied at the highest value are ambiguous. A missing
 * or non-numeric similarity never qualifies. Otherwise the best candidate is only a suggestion the
 * user must confirm — a partial name ("Mantenimiento Web") is a different product from the long one
 * it resembles.</p>
 */
final class OcrProductMatch {

  /** Minimum similarity (0-100, inclusive) at which a catalog product is taken automatically. */
  static final double AUTO_MATCH_MIN_SIMILARITY = 90;

  /** How a line's product was resolved. */
  enum State {
    /**
     * One candidate with the very same name, or the single highest candidate at
     * {@link #AUTO_MATCH_MIN_SIMILARITY} or more.
     */
    AUTO_MATCHED("auto_matched"),
    /** Candidates exist but none is certain. */
    SUGGESTED("suggested"),
    /** Nothing similar. */
    NONE("none");

    private final String code;

    State(String code) {
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  private final State state;
  private final Candidate chosen;

  private OcrProductMatch(State state, Candidate chosen) {
    this.state = state;
    this.chosen = chosen;
  }

  State state() {
    return state;
  }

  /** The matched or suggested product, {@code null} for {@link State#NONE}. */
  Candidate chosen() {
    return chosen;
  }

  /**
   * Classifies the candidates of one description.
   *
   * @param description the line description
   * @param candidates  the proposals, best first
   * @return the classification
   */
  static OcrProductMatch classify(String description, List<Candidate> candidates) {
    List<Candidate> usable = candidates.stream().filter(c -> c.id() != null)
        .collect(Collectors.toList());
    if (usable.isEmpty()) {
      return new OcrProductMatch(State.NONE, null);
    }
    String wanted = normalizeProductName(description);
    List<Candidate> exact = wanted.isEmpty() ? List.of() : usable.stream()
        .filter(c -> normalizeProductName(c.name()).equals(wanted)).collect(Collectors.toList());
    if (exact.size() == 1) {
      return new OcrProductMatch(State.AUTO_MATCHED, exact.get(0));
    }
    if (exact.isEmpty()) {
      Candidate bySimilarity = pickBySimilarity(usable);
      if (bySimilarity != null) {
        return new OcrProductMatch(State.AUTO_MATCHED, bySimilarity);
      }
    }
    return new OcrProductMatch(State.SUGGESTED, usable.get(0));
  }

  /**
   * The candidate to take by similarity, or {@code null} when taking one is not safe: the highest
   * valid similarity must reach the threshold and be held by exactly one candidate (strict
   * equality on the parsed numbers).
   */
  private static Candidate pickBySimilarity(List<Candidate> usable) {
    double top = Double.NaN;
    for (Candidate c : usable) {
      double sim = similarity(c);
      if (!Double.isNaN(sim) && (Double.isNaN(top) || sim > top)) {
        top = sim;
      }
    }
    if (Double.isNaN(top) || top < AUTO_MATCH_MIN_SIMILARITY) {
      return null;
    }
    Candidate holder = null;
    for (Candidate c : usable) {
      if (similarity(c) == top) {
        if (holder != null) {
          return null;
        }
        holder = c;
      }
    }
    return holder;
  }

  /** The similarity as a number; {@code NaN} when missing, blank or not numeric (e.g. "96.7%"). */
  private static double similarity(Candidate candidate) {
    String raw = candidate.similarity();
    if (raw == null || raw.trim().isEmpty()) {
      return Double.NaN;
    }
    try {
      double value = Double.parseDouble(raw.trim());
      return Double.isFinite(value) ? value : Double.NaN;
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /**
   * Whitespace set of ECMAScript {@code String.prototype.trim}, spelled out because neither
   * {@link String#trim()} (control characters) nor {@link String#strip()} (keeps NBSP) matches it.
   */
  private static final String EDGE_SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A"
      + "\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+";
  private static final Pattern EDGE_SPACE_PATTERN = Pattern.compile(
      "(?:^" + EDGE_SPACE + ")|(?:" + EDGE_SPACE + "$)");

  /**
   * Normalizer of the exact-name step (ETP-5654): NFC, trim (the explicit set above, NBSP
   * included), lowercase, then diacritics stripped (NFD + removal of combining marks), so "Arnés"
   * equals "Arnes" like "60M" equals "60m". The n-tilde is kept distinct ("año" is not "ano") by
   * protecting it while the marks are stripped. The fold also applies to an identical candidate:
   * if the catalog holds both "Arnés X" and "Arnes X", the description "Arnés X" has two
   * normalized-exact matches and goes to the user as a suggestion (the first candidate, which may
   * not be the identical one); the safe direction by design. Parity with
   * {@code normalizeProductName} of the application; {@link #normalize} is left untouched because
   * the vendor review uses it.
   */
  static String normalizeProductName(String value) {
    if (value == null) {
      return "";
    }
    String composed = Normalizer.normalize(value, Normalizer.Form.NFC);
    String folded = EDGE_SPACE_PATTERN.matcher(composed).replaceAll("")
        .toLowerCase(Locale.ROOT).replace('ñ', '\u0001');
    return Normalizer.normalize(folded, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .replace('\u0001', 'ñ');
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
