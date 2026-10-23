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

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;

/**
 * Decides what to do with the products the similarity search proposed for one line — a port of the
 * application's {@code classifyProductMatch} (ETP-5715).
 *
 * <p>Exactly one candidate whose name equals the description (trimmed, case-insensitive) is safe to
 * take as the match; otherwise the best candidate is only a suggestion the user must confirm — a
 * partial name ("Mantenimiento Web") is a different product from the long one it resembles.</p>
 */
final class OcrProductMatch {

  /** How a line's product was resolved. */
  enum State {
    /** One candidate with the very same name. */
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
    String wanted = normalize(description);
    List<Candidate> exact = wanted.isEmpty() ? List.of() : usable.stream()
        .filter(c -> normalize(c.name()).equals(wanted)).collect(Collectors.toList());
    if (exact.size() == 1) {
      return new OcrProductMatch(State.AUTO_MATCHED, exact.get(0));
    }
    return new OcrProductMatch(State.SUGGESTED, usable.get(0));
  }

  static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
