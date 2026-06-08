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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;

/**
 * Stateless helpers shared across {@link BankStatementsHandler}: statement
 * status derivation, null-safe / parsing utilities and ISO date formatting.
 *
 * <p>Extracted from the handler so it stays under the per-class method-count
 * limit; every method here is pure (no DB, no OBContext) and trivially testable.
 */
public final class BankStatementsSupport {

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private BankStatementsSupport() {
    // utility class — no instances
  }

  /**
   * Three-state status derived from how many of the statement's lines are
   * already matched to a financial-account transaction:
   * {@code matched == 0} → PENDING; {@code 0 < matched < total} → PARTIAL;
   * {@code matched == total > 0} → RECONCILED; empty statement → PENDING.
   *
   * @param lineCount    total number of lines in the statement
   * @param matchedCount number of those lines already matched to a transaction
   * @return one of {@code "PENDING"}, {@code "PARTIAL"} or {@code "RECONCILED"}
   */
  public static String deriveStatementStatus(int lineCount, int matchedCount) {
    if (lineCount == 0 || matchedCount == 0) return "PENDING";
    if (matchedCount >= lineCount) return "RECONCILED";
    return "PARTIAL";
  }

  /**
   * Returns {@link BigDecimal#ZERO} for {@code null}, otherwise the value as-is.
   *
   * @param value the amount to normalise (may be {@code null})
   * @return {@code value}, or {@link BigDecimal#ZERO} when it is {@code null}
   */
  public static BigDecimal nullSafeBigDecimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * Formats a timestamp as an ISO-8601 UTC instant.
   *
   * @param ts the timestamp to format (may be {@code null})
   * @return the ISO-8601 UTC string (e.g. {@code 2026-06-04T10:00:00Z}), or {@code ""} when {@code ts} is {@code null}
   */
  public static String formatDate(Timestamp ts) {
    if (ts == null) return "";
    return ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  /**
   * Parses an ISO-8601 instant (e.g. {@code 2026-06-04T00:00:00Z}).
   *
   * @param iso      the ISO-8601 instant string to parse (may be {@code null}/blank)
   * @param fallback the value to return when {@code iso} is blank or unparseable
   * @return the parsed {@link Date}, or {@code fallback} on blank/invalid input
   */
  public static Date parseIsoDate(String iso, Date fallback) {
    if (StringUtils.isBlank(iso)) return fallback;
    try {
      return Date.from(Instant.parse(iso));
    } catch (Exception e) {
      return fallback;
    }
  }

  /**
   * Parses a plain decimal string into a non-null {@link BigDecimal}.
   *
   * @param raw the decimal string to parse (may be {@code null}/blank)
   * @return the parsed amount, or {@link BigDecimal#ZERO} on blank/invalid input
   */
  public static BigDecimal parseAmount(String raw) {
    if (StringUtils.isBlank(raw)) return BigDecimal.ZERO;
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * Truncates {@code s} to at most {@code max} characters.
   *
   * @param s   the string to truncate
   * @param max the maximum number of characters to keep
   * @return {@code s} unchanged when shorter than {@code max}, otherwise its first {@code max} characters
   */
  public static String truncate(String s, int max) {
    return s.length() > max ? s.substring(0, max) : s;
  }
}
