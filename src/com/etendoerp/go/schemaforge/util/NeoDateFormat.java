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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * Single definition of the date format NEO speaks over JSON (ETP-4793 / IMP-16).
 *
 * <p>NEO's JSON contract is <b>ISO-8601</b> in both directions: {@code yyyy-MM-dd} for
 * date-only properties and {@code yyyy-MM-dd'T'HH:mm:ss} for datetime ones. That is what
 * the DAL layer parses ({@code JsonUtils.createDateFormat} /
 * {@code JsonUtils.createDateTimeFormat}, consumed by {@code JsonToDataConverter}) and what
 * the React form parses and emits ({@code dateOnly.js}, {@code date-field.jsx}).
 *
 * <p>Three sources nevertheless produce non-ISO date strings inside NEO:
 * <ul>
 *   <li>{@code @#Date@} defaults, which core resolves through
 *       {@code Utility.getContext} → {@code DateTimeData.today} — a generated {@code .xsql}
 *       method whose output format is hardcoded to {@code dd-MM-yyyy}, so no session value
 *       or locale property can influence it;</li>
 *   <li>the {@code dateFormat.java} UI pattern, when a value crosses a legacy boundary;</li>
 *   <li>raw Postgres timestamps ({@code yyyy-MM-dd HH:mm:ss.ffffff+00}).</li>
 * </ul>
 *
 * <p>Leaving those through is not a cosmetic problem. {@code JsonUtils.createDateFormat()}
 * calls {@code setLenient(true)}, so a {@code dd-MM-yyyy} value is <b>not</b> rejected: it is
 * silently reinterpreted, and {@code "06-08-2026"} is stored as year <b>0012</b>. Corrupt rows
 * produced this way are already in the database — see
 * {@code docs/mcp-evaluation/imps/IMP-16.md} §3.6 in the schema_forge repo.
 *
 * <p>This class therefore does two things and nothing else: it tells callers what the
 * canonical form is, and it converts the three known shapes into it. It is deliberately
 * <b>total but conservative</b> — an input it does not recognise yields {@code null}, and
 * every caller must then pass the original value through verbatim rather than blank it. A
 * value we cannot interpret is the caller's problem to report, never this class's to guess.
 */
public final class NeoDateFormat {

  private static final Logger log = LogManager.getLogger(NeoDateFormat.class);

  /** Canonical wire format for a date-only property. */
  public static final String ISO_DATE = "yyyy-MM-dd";

  /** Canonical wire format for a datetime property. */
  public static final String ISO_DATETIME = "yyyy-MM-dd'T'HH:mm:ss";

  /** Fallback UI pattern; matches the Postgres {@code dateFormat()} default. */
  static final String DEFAULT_UI_DATE_PATTERN = "dd-MM-yyyy";

  /** Leading {@code HH:mm[:ss]} of the time half, ignoring fractional seconds and offset. */
  private static final Pattern TIME_PREFIX = Pattern.compile("^(\\d{2}):(\\d{2})(?::(\\d{2}))?");

  private static final String MIDNIGHT = "00:00:00";

  private static volatile String cachedUiDatePattern = null;

  private NeoDateFormat() {
  }

  /**
   * The Etendo UI date pattern from {@code dateFormat.java} (e.g. {@code "dd-MM-yyyy"}).
   *
   * <p>This is both the pattern legacy callouts expect on input and the second shape
   * {@link #toCanonical} accepts, so the two stay defined in one place.
   *
   * @return the configured pattern, or {@value #DEFAULT_UI_DATE_PATTERN} when unreadable
   */
  public static String getUiDatePattern() {
    if (cachedUiDatePattern != null) {
      return cachedUiDatePattern;
    }
    String resolved = DEFAULT_UI_DATE_PATTERN;
    try {
      String p = OBPropertiesProvider.getInstance().getOpenbravoProperties()
          .getProperty("dateFormat.java");
      if (p != null && !p.trim().isEmpty()) {
        resolved = p.trim();
      }
    } catch (Exception e) {
      log.debug("Could not read dateFormat.java, defaulting to {}: {}", DEFAULT_UI_DATE_PATTERN,
          e.getMessage());
    }
    cachedUiDatePattern = resolved;
    return cachedUiDatePattern;
  }

  /**
   * Whether {@code value} is already in the canonical form for its property kind.
   *
   * <p>Cheap enough to call before {@link #toCanonical} so callers can skip logging and
   * map churn on the overwhelmingly common already-ISO case.
   *
   * @param value    the raw string; {@code null} is not canonical
   * @param datetime {@code true} for a datetime property, {@code false} for date-only
   * @return {@code true} when no conversion is needed
   */
  public static boolean isCanonical(String value, boolean datetime) {
    if (value == null) {
      return false;
    }
    String canonical = toCanonical(value, datetime);
    return canonical != null && canonical.equals(value);
  }

  /**
   * Convert a date string in any of the three shapes NEO encounters into the canonical
   * ISO form for the given property kind.
   *
   * <p>Recognised inputs, in precedence order — ISO is tried <b>first</b>, so
   * {@code "2026-08-06"} is never re-read through the UI pattern:
   * <ol>
   *   <li>ISO {@code yyyy-MM-dd}, optionally followed by {@code T} or a space and a time;</li>
   *   <li>the {@link #getUiDatePattern() UI pattern} (e.g. {@code dd-MM-yyyy}), same optional
   *       time half;</li>
   *   <li>a Postgres timestamp — case 1 with fractional seconds and/or a zone offset, which
   *       are parsed and dropped rather than being fed to a lenient parser.</li>
   * </ol>
   *
   * <p>A time half is <b>preserved</b> for a datetime property and dropped for a date-only
   * one; a datetime property with no time half is completed to midnight. Truncating a real
   * time to obtain a date-only string is only ever done when the property itself is
   * date-only, so no information the target column can hold is lost.
   *
   * @param raw      the value as it arrived; may be {@code null} or blank
   * @param datetime {@code true} for a datetime property, {@code false} for date-only
   * @return the canonical string, or {@code null} when the input is not a recognised date —
   *         in which case the caller must leave the original value untouched
   */
  public static String toCanonical(String raw, boolean datetime) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return null;
    }
    String[] halves = splitDateAndTime(value);
    LocalDate date = parseDatePart(halves[0]);
    if (date == null) {
      return null;
    }
    String isoDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    if (!datetime) {
      return isoDate;
    }
    return isoDate + "T" + normalizeTimePart(halves[1]);
  }

  /**
   * Split at the first {@code T} or space. Returns {@code [datePart, timePartOrNull]}.
   *
   * <p>An offset-only tail such as {@code "2026-08-06+02:00"} keeps the offset inside the
   * date part, where {@link #parseDatePart} rejects it — deliberately, since guessing which
   * calendar day such a value denotes is exactly the kind of silent reinterpretation this
   * class exists to stop.
   */
  private static String[] splitDateAndTime(String value) {
    int sep = value.indexOf('T');
    if (sep < 0) {
      sep = value.indexOf(' ');
    }
    if (sep < 0) {
      return new String[] { value, null };
    }
    return new String[] { value.substring(0, sep), value.substring(sep + 1) };
  }

  /** Parse the date half as strict ISO, then as the strict UI pattern. {@code null} if neither. */
  private static LocalDate parseDatePart(String datePart) {
    if (datePart == null || datePart.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException ignored) {
      log.trace("Not an ISO date: {}", datePart);
    }
    DateTimeFormatter uiFormatter = strictUiFormatter();
    if (uiFormatter == null) {
      return null;
    }
    try {
      return LocalDate.parse(datePart, uiFormatter);
    } catch (DateTimeParseException ignored) {
      log.trace("Not a UI-pattern date: {}", datePart);
      return null;
    }
  }

  /**
   * The UI pattern as a strict formatter.
   *
   * <p>{@code y} (year-of-era) is rewritten to {@code u} (proleptic year) because
   * {@link ResolverStyle#STRICT} rejects {@code y} without an era field. Strict resolution is
   * what makes {@code "2026-02-30"} an error instead of February 28th — smart resolution would
   * quietly move the day, which is the same class of bug as the lenient core parser.
   *
   * @return the formatter, or {@code null} when the configured pattern is unusable
   */
  private static DateTimeFormatter strictUiFormatter() {
    String pattern = getUiDatePattern();
    try {
      return DateTimeFormatter.ofPattern(pattern.replace('y', 'u'))
          .withResolverStyle(ResolverStyle.STRICT);
    } catch (IllegalArgumentException e) {
      log.warn("Unusable dateFormat.java pattern '{}': {}", pattern, e.getMessage());
      return null;
    }
  }

  /**
   * Normalize the time half to {@code HH:mm:ss}, dropping fractional seconds and any zone
   * offset. An absent, blank or unrecognised time half yields midnight — for a datetime
   * column whose value carried no usable time, midnight is the only defensible reading, and
   * it is what the DAL would have produced from a bare date anyway.
   */
  private static String normalizeTimePart(String timePart) {
    if (timePart == null) {
      return MIDNIGHT;
    }
    Matcher m = TIME_PREFIX.matcher(timePart.trim());
    if (!m.find()) {
      return MIDNIGHT;
    }
    String seconds = m.group(3) != null ? m.group(3) : "00";
    return m.group(1) + ":" + m.group(2) + ":" + seconds;
  }
}
