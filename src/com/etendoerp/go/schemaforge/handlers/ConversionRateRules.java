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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * ETP-6365: the pure rules of the manual conversion ranges of the {@code conversion-rates} spec —
 * no database, no DAL, so every rule is unit-testable on plain values.
 *
 * <p>A <b>range</b> is the pair of rows a manual rate is made of: the forward row
 * (organization currency to X, multiply by the rate) and its inverse (X to the organization
 * currency, multiply by 1/rate), with the same {@code ValidFrom} / {@code ValidTo}. A range is
 * <b>open</b> when it has no end ({@code ValidTo} empty, or year {@value #OPEN_YEAR} or later: core's
 * {@code C_CONVERSION_RATE_TRG} stores an empty end as 9999-12-31 and the downloader writes
 * 2999-12-31).</p>
 *
 * <p>Creating a range from {@code F} closes every earlier open range of the pair at
 * {@code F - 1 minute} (core's {@code ConversionRateEventHandler} compares ranges with an
 * inclusive {@code BETWEEN}, so the closing instant must be strictly before {@code F}). Any other
 * range that reaches {@code F} or later is an overlap and nothing is planned.</p>
 */
final class ConversionRateRules {

  /** A {@code ValidTo} in this year or later means "no end". */
  static final int OPEN_YEAR = 2999;

  /** The end written for an open range: what core's trigger stores for an empty one. */
  static final LocalDateTime OPEN_END = LocalDateTime.of(9999, 12, 31, 0, 0);

  /** Decimal places of the inverse rate. */
  static final int RATE_SCALE = 12;

  private ConversionRateRules() {
  }

  /**
   * One range of a currency pair, seen in the orientation of {@code forwardId}.
   *
   * @param forwardId the row in the orientation the caller works in, or {@code null} when only
   *                  the other direction exists
   * @param inverseId the row in the opposite direction, or {@code null} when missing
   * @param validFrom start, inclusive
   * @param validTo   end, inclusive; {@code null} or year {@value #OPEN_YEAR}+ when open
   * @param synced    whether the downloader wrote it ({@code EM_SMFCR_Is_Synced})
   */
  record Range(String forwardId, String inverseId, LocalDateTime validFrom, LocalDateTime validTo,
      boolean synced) {

    boolean isOpen() {
      return validTo == null || validTo.getYear() >= OPEN_YEAR;
    }

    LocalDateTime endOrMax() {
      return isOpen() ? LocalDateTime.MAX : validTo;
    }

    /** Both row ids, the missing one left out. */
    List<String> rowIds() {
      List<String> ids = new ArrayList<>(2);
      if (forwardId != null) {
        ids.add(forwardId);
      }
      if (inverseId != null) {
        ids.add(inverseId);
      }
      return ids;
    }

    boolean contains(String rowId) {
      return rowId != null && (rowId.equals(forwardId) || rowId.equals(inverseId));
    }
  }

  /**
   * A range whose end moves to {@code newValidTo} (both directions).
   *
   * @param range      the range
   * @param newValidTo its new end
   */
  record Close(Range range, LocalDateTime newValidTo) {
  }

  /**
   * What a write would do to the other ranges of the pair.
   *
   * @param closes  the ranges whose end moves, in the order they must be written
   * @param overlap {@code true} when the write collides with another range: nothing is planned
   */
  record Plan(List<Close> closes, boolean overlap) {

    static Plan ofOverlap() {
      return new Plan(Collections.emptyList(), true);
    }
  }

  /** The end given to a range closed by one that starts at {@code from}. */
  static LocalDateTime closingInstant(LocalDateTime from) {
    return from.minusMinutes(1);
  }

  /**
   * Plans a new open range starting at {@code from}.
   *
   * @param ranges every range of the pair
   * @param from   the new start, minute precision
   * @return the open ranges to close, or an overlap
   */
  static Plan planCreate(List<Range> ranges, LocalDateTime from) {
    LocalDateTime closing = closingInstant(from);
    List<Close> closes = new ArrayList<>();
    for (Range range : ranges) {
      if (range.isOpen() && !closing.isBefore(range.validFrom())) {
        closes.add(new Close(range, closing));
      } else if (overlaps(range, from, LocalDateTime.MAX)) {
        return Plan.ofOverlap();
      }
    }
    return new Plan(closes, false);
  }

  /**
   * Plans moving the start of {@code target} to {@code newFrom}. The range that {@code target}
   * closed when it was created (the one ending exactly one minute before its start) follows it:
   * its end becomes {@code newFrom - 1 minute}, shrinking or growing.
   *
   * @param others  every other range of the pair
   * @param target  the range being edited
   * @param newFrom its new start, minute precision
   * @return the adjacent range to re-close, or an overlap
   */
  static Plan planMove(List<Range> others, Range target, LocalDateTime newFrom) {
    LocalDateTime end = target.endOrMax();
    if (newFrom.isAfter(end)) {
      return Plan.ofOverlap();
    }
    Range previous = adjacentBefore(others, target.validFrom());
    for (Range other : others) {
      if (other != previous && overlaps(other, newFrom, end)) {
        return Plan.ofOverlap();
      }
    }
    if (previous == null) {
      return new Plan(Collections.emptyList(), false);
    }
    LocalDateTime previousEnd = closingInstant(newFrom);
    if (previousEnd.isBefore(previous.validFrom())) {
      return Plan.ofOverlap();
    }
    return new Plan(List.of(new Close(previous, previousEnd)), false);
  }

  /** The closed range ending one minute before {@code start}, or {@code null}. */
  static Range adjacentBefore(List<Range> ranges, LocalDateTime start) {
    LocalDateTime expectedEnd = closingInstant(start);
    for (Range range : ranges) {
      if (!range.isOpen() && expectedEnd.equals(range.validTo())) {
        return range;
      }
    }
    return null;
  }

  /** Whether {@code range} shares at least one instant with {@code [from, to]}, both inclusive. */
  static boolean overlaps(Range range, LocalDateTime from, LocalDateTime to) {
    return !range.validFrom().isAfter(to) && !range.endOrMax().isBefore(from);
  }

  /**
   * The inverse of a rate, {@value #RATE_SCALE} decimals, trailing zeros stripped.
   *
   * @param rate a rate greater than zero
   * @return {@code 1 / rate}
   */
  static BigDecimal inverse(BigDecimal rate) {
    return BigDecimal.ONE.divide(rate, RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  /**
   * A rate sent by a caller: a JSON number or a numeric string ({@code "1.08"}, or {@code "1,08"}
   * when it has no dot).
   *
   * @param raw the value
   * @return the rate, or {@code null} when it is not a number greater than zero
   */
  static BigDecimal parseRate(Object raw) {
    if (raw == null) {
      return null;
    }
    String text = StringUtils.trimToNull(String.valueOf(raw));
    if (text == null || "null".equals(text)) {
      return null;
    }
    if (text.indexOf('.') < 0) {
      text = text.replace(',', '.');
    }
    try {
      BigDecimal rate = new BigDecimal(text);
      return rate.signum() > 0 ? rate : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * A start sent by a caller, in any shape {@link NeoDateFormat#toCanonical} recognises — the
   * canonical {@code yyyy-MM-dd'T'HH:mm:ss}, {@code yyyy-MM-dd HH:mm[:ss]}, or a bare date
   * (midnight) — as server-local wall clock, truncated to the minute.
   *
   * @param raw the value
   * @return the start, or {@code null} when it is absent or not a date
   */
  static LocalDateTime parseValidFrom(Object raw) {
    if (raw == null) {
      return null;
    }
    String canonical = NeoDateFormat.toCanonical(String.valueOf(raw), true);
    if (canonical == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(canonical).truncatedTo(ChronoUnit.MINUTES);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
