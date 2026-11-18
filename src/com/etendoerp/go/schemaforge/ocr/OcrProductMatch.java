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
import java.util.function.Function;
import java.util.stream.Collectors;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;

/**
 * Decides what to do with the products the similarity search proposed for one line — a port of the
 * application's {@code classifyProductMatch} (ETP-5715); keep both identical.
 *
 * <p>In order: exactly one candidate whose name equals the description (trimmed, case- and
 * accent-insensitive, n-tilde distinct) is taken; then exactly one candidate the search marks as
 * equal ({@code match_type} "exact", or "normalized" when only accents differ; it compares the
 * search key too); then the candidate with the highest whole-string similarity when it reaches
 * {@link #AUTO_MATCH_MIN_SIMILARITY} and leads the runner-up by {@link #AUTO_MATCH_MIN_GAP}
 * (ETP-5895). The search score is not used for that: it counts a name that merely contains the
 * description as 100, and a partial name ("Mantenimiento Web") is a different product from the long
 * one it resembles. A search that does not report the whole-string similarity falls back to the
 * score at {@link #LEGACY_MIN_SCORE} held by one candidate (ETP-5654). Otherwise the best candidate
 * is only a suggestion the user must confirm.</p>
 */
final class OcrProductMatch {

  /** Whole-string similarity (0-100, inclusive) at which a catalog product is taken automatically. */
  static final double AUTO_MATCH_MIN_SIMILARITY = 85;
  /** Lead, in whole-string similarity points, the best candidate needs over the runner-up. */
  static final double AUTO_MATCH_MIN_GAP = 10;
  /** Score threshold kept for a search that does not report the whole-string similarity. */
  static final double LEGACY_MIN_SCORE = 90;

  /** How a line's product was resolved. */
  enum State {
    /** One candidate equal to the description, or one clearly ahead by whole-string similarity. */
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
    OcrProductMatch suggestion = new OcrProductMatch(State.SUGGESTED, usable.get(0));
    String wanted = normalizeProductName(description);
    List<Candidate> exact = wanted.isEmpty() ? List.of() : usable.stream()
        .filter(c -> normalizeProductName(c.name()).equals(wanted)).collect(Collectors.toList());
    if (exact.size() == 1) {
      return new OcrProductMatch(State.AUTO_MATCHED, exact.get(0));
    }
    if (exact.size() > 1) {
      return suggestion;
    }
    int bestRank = usable.stream().mapToInt(OcrProductMatch::matchRank).max().orElse(0);
    if (bestRank > 0) {
      List<Candidate> equal = usable.stream().filter(c -> matchRank(c) == bestRank)
          .collect(Collectors.toList());
      return equal.size() == 1 ? new OcrProductMatch(State.AUTO_MATCHED, equal.get(0)) : suggestion;
    }
    boolean hasTextSimilarity = usable.stream().anyMatch(c -> c.textSimilarity() != null);
    Candidate bySimilarity = hasTextSimilarity
        ? pickBy(usable, Candidate::textSimilarity, AUTO_MATCH_MIN_SIMILARITY, AUTO_MATCH_MIN_GAP)
        : pickBy(usable, Candidate::similarity, LEGACY_MIN_SCORE, 0);
    return bySimilarity != null ? new OcrProductMatch(State.AUTO_MATCHED, bySimilarity) : suggestion;
  }

  /** 2 when the search marks the candidate exact, 1 when normalized (accents), 0 otherwise. */
  private static int matchRank(Candidate candidate) {
    if ("exact".equals(candidate.matchType())) {
      return 2;
    }
    return "normalized".equals(candidate.matchType()) ? 1 : 0;
  }

  /**
   * The candidate to take, or {@code null} when taking one is not safe: the highest valid value
   * must reach {@code minValue}, be held by exactly one candidate (strict equality on the parsed
   * numbers) and lead the runner-up by {@code minGap}.
   */
  private static Candidate pickBy(List<Candidate> usable, Function<Candidate, String> valueOf,
      double minValue, double minGap) {
    Candidate best = null;
    double top = Double.NaN;
    double runnerUp = Double.NEGATIVE_INFINITY;
    for (Candidate c : usable) {
      double value = parsePercent(valueOf.apply(c));
      if (Double.isNaN(value)) {
        continue;
      }
      if (Double.isNaN(top) || value > top) {
        runnerUp = Double.isNaN(top) ? runnerUp : top;
        top = value;
        best = c;
      } else if (value > runnerUp) {
        runnerUp = value;
      }
    }
    if (best == null || top < minValue || runnerUp == top || top - runnerUp < minGap) {
      return null;
    }
    return best;
  }

  /** The percentage as a number; {@code NaN} when missing, blank or not numeric (e.g. "96.7%"). */
  private static double parsePercent(String raw) {
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
   * Whether the character is in the whitespace set of ECMAScript {@code String.prototype.trim},
   * spelled out because neither {@link String#trim()} (control characters) nor
   * {@link String#strip()} (keeps NBSP) matches it.
   */
  private static boolean isEdgeSpace(char c) {
    return c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r' || c == ' '
        || c == '\u00A0' || c == '\u1680' || (c >= '\u2000' && c <= '\u200A') || c == '\u2028'
        || c == '\u2029' || c == '\u202F' || c == '\u205F' || c == '\u3000' || c == '\uFEFF';
  }

  private static String edgeStrip(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isEdgeSpace(value.charAt(start))) {
      start++;
    }
    while (end > start && isEdgeSpace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  /**
   * Normalizer of the exact-name step (ETP-5654): NFC, trim ({@link #isEdgeSpace}, NBSP
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
    String folded = edgeStrip(composed)
        .toLowerCase(Locale.ROOT).replace('ñ', '\u0001');
    return Normalizer.normalize(folded, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
        .replace('\u0001', 'ñ');
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
