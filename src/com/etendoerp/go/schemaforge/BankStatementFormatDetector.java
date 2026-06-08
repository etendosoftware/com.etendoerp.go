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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Detects the format of an uploaded bank-statement file by sniffing its first
 * non-blank line — neither the file extension nor a user choice is consulted.
 *
 * <p>Extracted from {@link BankStatementsHandler} so the handler stays under the
 * per-class method-count limit; every method here is pure (no DB, no OBContext).
 */
public final class BankStatementFormatDetector {

  /** Supported statement file formats (plus {@code UNKNOWN}). */
  public enum StatementFormat { C43, GENERIC_CSV, UNKNOWN }

  /** Record markers that identify a Cuaderno 43 line by its first two chars. */
  private static final Set<String> C43_CODES = Set.of("11", "22", "33", "99");

  /** Known header tokens used to recognise the generic CSV format. */
  private static final String[] CSV_HEADER_TOKENS = {
      "transaction date", "amount in", "amount out",
      "reference no.", "business partner name", "description"
  };

  private BankStatementFormatDetector() {
    // utility class — no instances
  }

  /**
   * Sniffs the first non-blank line of {@code fileBytes} to decide which parser
   * to dispatch.
   *
   * <p>Heuristics, in priority order:
   * <ul>
   *   <li><b>Cuaderno 43</b>: the line is exactly 80 chars and starts with
   *       one of {@code 11}, {@code 22}, {@code 33}, {@code 99}.</li>
   *   <li><b>Generic CSV</b>: the line contains at least two of the known
   *       header tokens (case-insensitive).</li>
   *   <li>Otherwise {@code UNKNOWN}.</li>
   * </ul>
   *
   * @param fileBytes the raw uploaded file bytes (may be {@code null}/empty)
   * @return the detected {@link StatementFormat}
   */
  public static StatementFormat detectFormat(byte[] fileBytes) {
    if (fileBytes == null || fileBytes.length == 0) return StatementFormat.UNKNOWN;
    // Only the head of the file is needed; caps cost on large uploads too.
    int sampleLen = Math.min(fileBytes.length, 4096);
    String head = new String(fileBytes, 0, sampleLen, StandardCharsets.UTF_8);
    String firstLine = firstNonBlankLine(head);
    if (firstLine == null) return StatementFormat.UNKNOWN;
    if (looksLikeC43Record(firstLine)) return StatementFormat.C43;
    if (looksLikeCsvHeader(firstLine)) return StatementFormat.GENERIC_CSV;
    return StatementFormat.UNKNOWN;
  }

  private static String firstNonBlankLine(String text) {
    for (String line : text.split("\\r?\\n", -1)) {
      if (StringUtils.isNotBlank(line)) return line;
    }
    return null;
  }

  private static boolean looksLikeC43Record(String line) {
    return line.length() == 80 && C43_CODES.contains(line.substring(0, 2));
  }

  private static boolean looksLikeCsvHeader(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    int hits = 0;
    for (String token : CSV_HEADER_TOKENS) {
      if (lower.contains(token)) hits++;
    }
    return hits >= 2;
  }
}
