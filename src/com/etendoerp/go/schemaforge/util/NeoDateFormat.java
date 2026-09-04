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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
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
 * <p>This class therefore does four things and nothing else: it tells callers what the
 * canonical form is, it says which properties the canonical form even applies to
 * ({@link #canonicalShapeFor}), it converts the three known shapes into it, and it renders
 * an outbound business timestamp in it ({@link #toWireDateTime} / {@link #toWireDate}) — the
 * direction that five hand-rolled UTC formatters used to get wrong. It is
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

  /** Outbound counterparts of {@link #ISO_DATETIME} / {@link #ISO_DATE}. See {@link #toWireDateTime}. */
  private static final DateTimeFormatter WIRE_DATETIME = DateTimeFormatter.ofPattern(ISO_DATETIME);
  private static final DateTimeFormatter WIRE_DATE = DateTimeFormatter.ofPattern(ISO_DATE);

  private static volatile String cachedUiDatePattern = null;

  private NeoDateFormat() {
  }

  /**
   * Renders a business timestamp as the canonical wire datetime {@value #ISO_DATETIME},
   * read in the server's own zone — the zone the value was written in.
   *
   * <p>Business dates (an accounting date, a statement date, a transaction date) are civil
   * dates: the day is the datum, and it is stored at the server's local start-of-day (see
   * {@code FinancialAccountTransactionsSupport.parseLocalDate}). Reading one back through
   * {@code Instant} + {@code ZoneOffset.UTC} — as five hand-rolled formatters in this module
   * did — reinterprets that civil value as an instant and re-expresses it elsewhere, so a
   * row written at 21:43 local under a negative UTC offset goes out as the NEXT calendar day
   * (ETP-5100: funds transfers made after 21:00 in UTC-3 vanished from the movements list,
   * because the React range filter reads the {@code yyyy-MM-dd} prefix via
   * {@code parseCalendarDate} and the row fell past "today").
   *
   * <p>The trailing {@code 'Z'} those formatters appended was wrong for the same reason: it
   * asserts UTC on a value that carries no zone, and it is not part of the contract this
   * class defines. Formatting in {@link ZoneId#systemDefault()} round-trips what was written.
   *
   * <p>Uses {@code getTime()} rather than {@code toInstant()} so a {@link java.sql.Date}
   * argument is accepted — {@code java.sql.Date.toInstant()} throws.
   *
   * @param ts the timestamp to render; may be {@code null}
   * @return the canonical wire datetime, or {@code null} when {@code ts} is {@code null} —
   *         callers keep their own empty-vs-null convention
   */
  public static String toWireDateTime(java.util.Date ts) {
    return ts == null ? null : zoned(ts).format(WIRE_DATETIME);
  }

  /**
   * Renders a business timestamp as the canonical wire date {@value #ISO_DATE} (day only),
   * in the server's own zone. Same rationale as {@link #toWireDateTime}, for the callers
   * whose payload carries no time half.
   *
   * @param ts the timestamp to render; may be {@code null}
   * @return the canonical wire date, or {@code null} when {@code ts} is {@code null}
   */
  public static String toWireDate(java.util.Date ts) {
    return ts == null ? null : zoned(ts).format(WIRE_DATE);
  }

  private static java.time.ZonedDateTime zoned(java.util.Date ts) {
    return java.time.Instant.ofEpochMilli(ts.getTime()).atZone(ZoneId.systemDefault());
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
    LocalDate isoReading = parseIsoDatePart(datePart);
    if (isoReading != null) {
      return isoReading;
    }
    return parseUiDatePart(datePart);
  }

  /** Parse the date half as strict ISO ({@code yyyy-MM-dd}) only. {@code null} if it does not match. */
  private static LocalDate parseIsoDatePart(String datePart) {
    try {
      return LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException ignored) {
      log.trace("Not an ISO date: {}", datePart);
      return null;
    }
  }

  /** Parse the date half as the strict UI pattern only. {@code null} if it does not match. */
  private static LocalDate parseUiDatePart(String datePart) {
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
   * Whether a value {@link #toCanonical} refused is nevertheless one the DAL parses <b>correctly</b>
   * (ETP-4793 / IMP-24 phase 2).
   *
   * <p>{@code toCanonical} returns {@code null} for two situations that phase 1 could treat alike
   * and phase 2 must not, because it turns the refusal into a hard 422:
   * <ul>
   *   <li>the value is unusable — {@code "06/08/2026"}, {@code "2026-13-40"}, {@code "2026-08"} —
   *       and the lenient DAL parser either reinterprets it or throws. Rejecting is the point;</li>
   *   <li>the value is an ISO datetime carrying a <b>non-zero</b> zone offset. That one is refused
   *       precisely <i>because it is already right</i>: the canonical form has nowhere to put an
   *       offset, and {@code JsonUtils.convertFromXSDToJavaFormat} already rewrites {@code +02:00}
   *       to {@code +0200} and parses it. Turning this into a 422 would break a working call —
   *       which is why the two cases need telling apart before the rejection can ship.</li>
   * </ul>
   *
   * <p>Only the second family returns {@code true}. A zero offset is not in it, because
   * {@link #toCanonical} converts those successfully and never reaches this question.
   *
   * <p>The space separator is accepted alongside {@code T}, even though only the {@code T} form is
   * documented as reaching the DAL intact. The two errors here are not symmetric: a value wrongly
   * passed through keeps the phase-1 behaviour that has been running all along, while a value
   * wrongly rejected is a new 422 on a call that used to work. Where the answer is uncertain this
   * method therefore leans towards pass-through.
   *
   * @param raw the value as it arrived; may be {@code null}
   * @return {@code true} when the value must be passed through rather than rejected
   */
  public static boolean isOffsetDateTime(String raw) {
    if (raw == null) {
      return false;
    }
    String[] halves = splitDateAndTime(raw.trim());
    if (halves[1] == null) {
      return false;
    }
    try {
      LocalDate.parse(halves[0], DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (DateTimeParseException e) {
      log.trace("Not an ISO date half: {}", halves[0]);
      return false;
    }
    String timePart = halves[1].trim();
    Matcher m = TIME_PREFIX.matcher(timePart);
    if (!m.find()) {
      return false;
    }
    Matcher tail = TIME_TAIL.matcher(timePart.substring(m.end()));
    return tail.matches() && !isZeroOffset(tail);
  }

  /**
   * Whether a UI-pattern-shaped date is genuinely ambiguous — undecidable between two different,
   * equally valid calendar readings (ETP-4793 / IMP-24, the caller-supplied-ambiguity gate).
   *
   * <p>{@code toCanonical} repairs any value the UI pattern (e.g. {@code dd-MM-yyyy}) parses,
   * unconditionally — it has no notion of "this could also mean something else". That is correct
   * for {@code "20-09-2026"}: no month 20 exists under any day/month ordering, so
   * {@code dd-MM-yyyy} is the only reading and repairing it is right. It is wrong for
   * {@code "03-04-2026"}: read as {@code dd-MM-yyyy} it is 3 April, read as {@code MM-dd-yyyy} it
   * is 4 March, and both are valid calendar dates. Silently picking one is exactly the class of
   * silent reinterpretation this whole item exists to stop — the caller never said which
   * convention it meant, and the wrong guess is indistinguishable from a right one until it is too
   * late to matter.
   *
   * <p>The check is purely arithmetic once the UI-pattern reading is known: swapping the parsed
   * day and month values gives the alternate reading, and that alternate reading is a valid date
   * whenever the day value is itself {@code <= 12} (every month has at least 12 days, so the
   * swapped month is always in range) and is not equal to the month value (a day equal to its own
   * month, e.g. {@code "05-05-2026"}, denotes the same date under either reading, so there is
   * nothing to disambiguate).
   *
   * <p>An ISO-shaped date part is never ambiguous, because only one reading order is ever
   * attempted for it — this method returns {@code false} before the UI pattern is even tried.
   *
   * @param raw the value as it arrived; may be {@code null}. The time half, if any, is ignored:
   *     ambiguity is a property of the date half alone
   * @return {@code true} only when the UI-pattern reading has a distinct, equally valid
   *     day/month-swapped alternative
   */
  public static boolean isAmbiguousUiDate(String raw) {
    if (raw == null) {
      return false;
    }
    String value = raw.trim();
    if (value.isEmpty()) {
      return false;
    }
    String datePart = splitDateAndTime(value)[0];
    if (parseIsoDatePart(datePart) != null) {
      return false;
    }
    LocalDate uiReading = parseUiDatePart(datePart);
    if (uiReading == null) {
      return false;
    }
    int day = uiReading.getDayOfMonth();
    int month = uiReading.getMonthValue();
    return day != month && day <= 12;
  }

  /**
   * The two candidate ISO dates an ambiguous caller-supplied value could mean, for use in an
   * error message (ETP-4793 / IMP-24).
   *
   * <p>{@code [0]} is the UI-pattern reading (e.g. {@code dd-MM-yyyy}) {@link #toCanonical} would
   * otherwise have picked silently; {@code [1]} is the day/month-swapped alternative. Both are
   * valid calendar dates whenever {@link #isAmbiguousUiDate} says so, which is the only case this
   * method is meant to be called for.
   *
   * @param raw the value as it arrived; may be {@code null}
   * @return {@code {primaryReading, alternateReading}} in ISO {@code yyyy-MM-dd}, or an empty
   *     array — meaning "not ambiguous" — when {@link #isAmbiguousUiDate} is {@code false} for
   *     this value
   */
  public static String[] ambiguousReadings(String raw) {
    if (!isAmbiguousUiDate(raw)) {
      return new String[0];
    }
    String datePart = splitDateAndTime(raw.trim())[0];
    LocalDate primary = parseUiDatePart(datePart);
    LocalDate alternate = LocalDate.of(primary.getYear(), primary.getDayOfMonth(),
        primary.getMonthValue());
    return new String[] { primary.format(DateTimeFormatter.ISO_LOCAL_DATE),
        alternate.format(DateTimeFormatter.ISO_LOCAL_DATE) };
  }

  /**
   * The canonical pattern for a property kind, for use in an error message.
   *
   * @param datetime {@code true} for a datetime property, {@code false} for date-only
   * @return {@link #ISO_DATETIME} or {@link #ISO_DATE}
   */
  public static String canonicalPattern(boolean datetime) {
    return datetime ? ISO_DATETIME : ISO_DATE;
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
   * ({@link Property#isTime()}) and {@code AbsoluteTimeDomainType}
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
   * @return {@link Optional#of} {@link Boolean#FALSE} for date-only, {@link Optional#of}
   *         {@link Boolean#TRUE} for datetime, or {@link Optional#empty()} when this property
   *         must not be touched at all
   */
  public static Optional<Boolean> canonicalShapeFor(Property prop) {
    if (prop == null) {
      return Optional.empty();
    }
    if (prop.isDate()) {
      return Optional.of(Boolean.FALSE);
    }
    if (prop.isDatetime()) {
      return Optional.of(Boolean.TRUE);
    }
    return Optional.empty();
  }
}
