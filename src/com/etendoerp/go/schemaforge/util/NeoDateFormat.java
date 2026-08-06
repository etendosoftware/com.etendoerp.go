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
import org.openbravo.base.model.Property;
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
 * <p>This class therefore does three things and nothing else: it tells callers what the
 * canonical form is, it says which properties the canonical form even applies to
 * ({@link #canonicalShapeFor}), and it converts the three known shapes into it. It is
 * deliberately <b>total but conservative</b> — an input it does not recognise yields
 * {@code null}, and every caller must then pass the original value through verbatim rather
 * than blank it. A value we cannot interpret is the caller's problem to report, never this
 * class's to guess.
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

  /** Fractional seconds plus an optional zone offset, i.e. everything after {@code HH:mm:ss}. */
  private static final Pattern TIME_TAIL = Pattern
      .compile("^(?:\\.\\d+)?(?:(Z)|([+-])(\\d{2}):?(\\d{2})?)?$");

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
    String time = normalizeTimePart(halves[1]);
    if (time == null) {
      return null;
    }
    return isoDate + "T" + time;
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
   * Normalize the time half to {@code HH:mm:ss}, dropping fractional seconds and a
   * <b>zero</b> zone offset ({@code Z}, {@code +00}, {@code +00:00}).
   *
   * <p>An absent or blank time half yields midnight — for a datetime column whose value
   * carried no usable time, midnight is the only defensible reading, and it is what the DAL
   * would have produced from a bare date anyway.
   *
   * <p>A <b>non-zero</b> offset, or any tail this method cannot account for, yields
   * {@code null} so the whole conversion is refused and the caller passes the original value
   * through. This is not caution for its own sake: the canonical form has no place to put an
   * offset, and {@code JsonUtils.createDateTimeFormat} already parses
   * {@code 2026-08-06T14:30:00+02:00} correctly (via
   * {@code JsonUtils.convertFromXSDToJavaFormat}, which rewrites {@code +02:00} to
   * {@code +0200}). Dropping that offset would shift the instant by two hours — turning this
   * class into the source of exactly the silent reinterpretation it exists to remove.
   *
   * @return {@code HH:mm:ss}, or {@code null} when the time half must not be rewritten
   */
  private static String normalizeTimePart(String timePart) {
    if (timePart == null || timePart.trim().isEmpty()) {
      return MIDNIGHT;
    }
    String value = timePart.trim();
    Matcher m = TIME_PREFIX.matcher(value);
    if (!m.find()) {
      return null;
    }
    Matcher tail = TIME_TAIL.matcher(value.substring(m.end()));
    if (!tail.matches() || !isZeroOffset(tail)) {
      return null;
    }
    String seconds = m.group(3) != null ? m.group(3) : "00";
    return m.group(1) + ":" + m.group(2) + ":" + seconds;
  }

  /**
   * Whether a matched {@link #TIME_TAIL} carries no offset, {@code Z}, or an all-zero one.
   *
   * <p>{@code Z} and {@code +00:00} both mean UTC, and a canonical value with no offset is
   * read as UTC by {@code JsonUtils.convertFromXSDToJavaFormat} (it appends {@code +0000}),
   * so dropping a zero offset is an identity — not an approximation.
   */
  private static boolean isZeroOffset(Matcher tail) {
    if (tail.group(2) == null) {
      return true;
    }
    String hours = tail.group(3);
    String minutes = tail.group(4);
    return "00".equals(hours) && (minutes == null || "00".equals(minutes));
  }

  /**
   * Whether the canonical form applies to {@code prop}, and in which of the two shapes.
   *
   * <p>Etendo has <b>five</b> date-ish domain types, not two, and
   * {@code JsonToDataConverter} branches on all of them. Only two of the five carry a
   * calendar date this class can speak about:
   * <ul>
   *   <li>{@code DateDomainType} ({@link Property#isDate()}) → {@link #ISO_DATE};</li>
   *   <li>{@code DatetimeDomainType} ({@link Property#isDatetime()}) → {@link #ISO_DATETIME}.</li>
   * </ul>
   *
   * <p>The other three are deliberately excluded. {@code TimestampDomainType}
   * ({@link Property#isTimestamp()}) and {@code AbsoluteTimeDomainType}
   * ({@link Property#isAbsoluteTime()}) are <b>time-of-day</b> values: the converter keeps
   * only the part after the {@code T} and supplies today's date itself, so rewriting such a
   * value into {@code yyyy-MM-dd} would destroy the only half it reads.
   * {@code AbsoluteDateTimeDomainType} ({@link Property#isAbsoluteDateTime()}) is excluded
   * because it is explicitly timezone-free, and normalizing it here would need a policy on
   * offsets that no caller has asked for yet.
   *
   * <p>The exclusion is not theoretical safety margin: those properties simply keep today's
   * behaviour, which is what a change of this blast radius should do for every case it has no
   * evidence about.
   *
   * @param prop the DAL property the value is destined for; may be {@code null}
   * @return {@link Boolean#FALSE} for date-only, {@link Boolean#TRUE} for datetime, or
   *         {@code null} when this property must not be touched at all
   */
  public static Boolean canonicalShapeFor(Property prop) {
    if (prop == null) {
      return null;
    }
    if (prop.isDate()) {
      return Boolean.FALSE;
    }
    if (prop.isDatetime()) {
      return Boolean.TRUE;
    }
    return null;
  }
}
