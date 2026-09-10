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

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.Traceable;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.JsonUtils;

/**
 * Optimistic-locking comparison for a write (ETP-5073 / DOC-04).
 *
 * <h2>Why this exists rather than reading core's answer</h2>
 *
 * Core already implements the check: {@code JsonToDataConverter.setData} compares the
 * {@code updated} value in the write payload against the stored row and throws
 * {@code OBStaleObjectException}. The problem is not the check, it is that its OUTCOME is
 * unreadable by the time it reaches us. {@code DefaultJsonDataService.update} catches every
 * {@code Throwable} and funnels it through {@code JsonUtils.convertExceptionToJson}, which calls
 * {@code Utility.translateError} BEFORE building the body. What arrives is
 * {@code {"message": "<prose in the session language>", "type": "Error", "title": "..."}} — no
 * error code, no exception type, nothing stable to key on.
 *
 * <p>Two attempts at classifying that string were made and both failed against a live server:
 * matching the untranslated code {@code OBJSON_StaleDate} (gone by then — it is what
 * {@code translateError} consumed), and resolving that code through {@code AD_Message} to compare
 * against the same text (the row exists and the texts are identical, yet the comparison still did
 * not fire). Rather than keep guessing at a string, the comparison is done here, where it is
 * deterministic, needs no message, and cannot be affected by the server's language.
 *
 * <h2>The semantics are core's, deliberately</h2>
 *
 * The caller's value is repaired and parsed with the very same {@code JsonUtils} helpers core
 * uses, and compared with milliseconds zeroed — precisely what
 * {@code JsonToDataConverter#areDatesEqual(d1, d2, true, false)} does (it zeroes
 * {@code MILLISECOND} on both and compares {@code getTimeInMillis()}). Copying the semantics
 * rather than inventing a tolerance is the whole point: anything looser reports conflicts that
 * are not there, anything stricter misses real ones — and a false conflict is worse than no
 * check, because it blocks a legitimate save with an explanation the user cannot act on.
 *
 * <p>Core's own check is deliberately left in place. This is a fast, readable pre-check for the
 * paths that go through Etendo GO; core still guards everything else.
 *
 * <h2>Why the comparison logs both of its inputs (ETP-5255)</h2>
 *
 * Until ETP-5255 the only server-side trace of a refusal was
 * {@code NeoWriteRefusalLog.staleRecord}, which logs the token the CLIENT sent and nothing it was
 * compared against. That makes the two outcomes a reader actually needs to tell apart —
 * "somebody really did save first" and "we refused a save nobody was competing for" —
 * indistinguishable without attaching a debugger to a production server. Users report the second
 * one; the log can only ever show the first.
 *
 * <p>So every decided verdict now logs BOTH sides, and above all the <b>signed delta</b>
 * ({@code client - stored}, in milliseconds), which names the cause at a glance:
 * <ul>
 *   <li>{@code 0} — the two instants are equal and we still said stale: a defect in the
 *       comparison itself, not in the data.</li>
 *   <li>{@code ±3600000} / {@code ±7200000} (or another whole-hour multiple) — a timezone
 *       mismatch. The token lost its UTC offset somewhere and was re-read in the wrong zone;
 *       {@code TimeZone.getDefault().getID()} is logged alongside so the reader can check the
 *       offset against the server's own zone.</li>
 *   <li>sub-second — a precision mismatch: something re-rounded the value after
 *       {@link #equalToTheSecond} had already zeroed the milliseconds it was meant to
 *       tolerate.</li>
 *   <li>an arbitrary, larger value — a genuine concurrent modification. This is the only delta
 *       that means the check did its job.</li>
 * </ul>
 *
 * <p>The raw token and the repaired token are logged as a pair for the same reason
 * {@code NeoWriteRefusalLog.unclassifiedWriteFailure} logs core's raw and translated message
 * together: seeing only the repaired value hides which step mangled it.
 *
 * <p>Nothing here logs the request body or any business field: only {@code updated} timestamps,
 * the entity name and the record id. A row's audit timestamp is not business data — see
 * {@code NeoWriteRefusalLog.staleRecord} for the same reasoning applied to the same value.
 */
public final class NeoRecordVersion {

  private static final Logger log = LogManager.getLogger(NeoRecordVersion.class);

  private NeoRecordVersion() {
    // utility class — no instances
  }

  /**
   * Whether the caller is writing against a version of the record that is no longer current.
   *
   * <p>Answers {@code false} — "not stale, let the write proceed" — for every case it cannot
   * decide: a blank token, a record that is not {@code Traceable}, a row with no {@code updated},
   * a row that cannot be read, or a token it cannot parse. That direction is chosen on purpose:
   * core's check still runs behind this one, so a "don't know" here degrades to core's answer
   * rather than to a fabricated conflict.
   *
   * <p>ETP-5255: each of those "cannot decide" exits is logged at WARN, naming the guard that
   * fired. They all return {@code false}, which is indistinguishable from a real pass at the
   * call site — so without a line saying so, a check that NEVER RAN looks exactly like one that
   * PASSED, and a whole class of skipped checks stays invisible. That is not hypothetical here: the
   * token some Etendo GO paths hand out is built by {@code NeoDateFormat.toCanonical}, whose ISO
   * datetime form ({@code yyyy-MM-dd'T'HH:mm:ss}) carries neither an offset nor milliseconds,
   * while {@code JsonUtils.createDateTimeFormat()} expects a trailing zone — so the parse
   * guard is a live suspect for silently skipping this check, or for skewing it by the server's
   * UTC offset.
   *
   * @param dalEntityName the DAL entity name (e.g. {@code "Order"})
   * @param recordId      the record being written
   * @param clientValue   the {@code updated} value the caller echoed back from its read
   * @return whether the write must be refused as a concurrent-modification conflict
   */
  public static boolean isStale(String dalEntityName, String recordId, String clientValue) {
    return isStale(dalEntityName, recordId, clientValue, null);
  }

  /**
   * As {@link #isStale(String, String, String)}, naming the endpoint in every line it logs.
   *
   * <p>ETP-5255: the DAL entity name alone does not identify the caller. Several endpoints write
   * the same entity — a window tab, a custom handler and the MCP write path all reach
   * {@code BusinessPartner} — so a conflict line without the route says what row moved but not
   * which surface got it wrong, which is the only part a fix needs. Build the value with
   * {@link #routeOf} so both call paths spell it the same way; {@code null} is accepted and
   * degrades to the entity/record pair, keeping the older three-argument form usable.
   *
   * @param requestPath the endpoint being served, e.g. {@code "PUT /contacts/customer/1000042"}
   */
  public static boolean isStale(String dalEntityName, String recordId, String clientValue,
      String requestPath) {
    if (StringUtils.isBlank(dalEntityName) || StringUtils.isBlank(recordId)
        || StringUtils.isBlank(clientValue) || "null".equals(clientValue)) {
      log.warn("Concurrency check did not run (guard: missing argument) — {}, entity '{}',"
          + " record '{}', client `updated` '{}'. The write proceeds with only core's own check"
          + " behind it (ETP-5073)", route(requestPath), dalEntityName, recordId, clientValue);
      return false;
    }
    Date storedUpdated = readStoredUpdated(dalEntityName, recordId);
    if (storedUpdated == null) {
      return false;
    }
    Date clientUpdated = parseClientValue(clientValue, dalEntityName, recordId, requestPath);
    if (clientUpdated == null) {
      return false;
    }
    boolean stale = !equalToTheSecond(clientUpdated, storedUpdated);
    logVerdict(stale, dalEntityName, recordId, clientValue, clientUpdated, storedUpdated,
        requestPath);
    return stale;
  }

  /**
   * The endpoint, spelled one way, for {@link #isStale(String, String, String, String)}.
   *
   * <p>Shared rather than formatted at each call site so two callers cannot describe the same
   * request differently — a log query that has to match two shapes matches neither reliably.
   * The shape mirrors what {@code NeoWriteRefusalLog} already emits, so the refusal line and the
   * comparison line for one request can be correlated by eye.
   *
   * @param httpMethod  the verb, or the MCP path's equivalent
   * @param specName    the spec (window) being served
   * @param entityName  the spec entity (tab), not the DAL entity
   * @param recordId    the record being written
   * @return e.g. {@code "PUT /contacts/customer/1000042"}
   */
  public static String routeOf(String httpMethod, String specName, String entityName,
      String recordId) {
    return String.format("%s /%s/%s/%s", httpMethod, specName, entityName, recordId);
  }

  /**
   * The route for a log line, or a stand-in when the caller did not supply one.
   */
  private static String route(String requestPath) {
    return StringUtils.isBlank(requestPath) ? "(route not supplied)" : requestPath;
  }

  /**
   * The stored {@code updated}, or {@code null} when it cannot be established.
   *
   * <p>Both failures are WARN rather than DEBUG (ETP-5255): a row that is not {@code Traceable}
   * and a row that could not be read both silently disable the concurrency check for that write,
   * and the caller has no way to tell that from a clean pass. The two are logged separately
   * because they call for opposite responses — a non-traceable entity means the check does not
   * apply to it at all and the caller should stop asking, whereas a read failure is a fault to
   * investigate.
   */
  private static Date readStoredUpdated(String dalEntityName, String recordId) {
    try {
      BaseOBObject stored = OBDal.getInstance().get(dalEntityName, recordId);
      if (!(stored instanceof Traceable)) {
        log.warn("Concurrency check did not run (guard: entity is not Traceable) — {} {} carries"
            + " no audit `updated`, so there is nothing to compare against", dalEntityName,
            recordId);
        return null;
      }
      return ((Traceable) stored).getUpdated();
    } catch (Exception e) {
      log.warn("Concurrency check did not run (guard: stored `updated` unreadable) — {} {}: {}."
          + " The write proceeds unchecked", dalEntityName, recordId, e.getMessage());
      return null;
    }
  }

  /**
   * Parses the caller's token exactly as core does: the XSD-to-Java repair first, then the shared
   * datetime format. A value we cannot parse is NOT a conflict — core will reject it on its own
   * terms, and reporting it as a concurrency failure would send the user to reload a record that
   * was never the problem.
   *
   * <p>ETP-5255 raised the failure to ERROR and made it log the repaired string next to the raw
   * one. The repair does not validate, it only reshapes — {@code convertFromXSDToJavaFormat}
   * strips the colon from a {@code +02:00} offset and, when there is no offset at all, appends
   * {@code +0000} — so a token that lost its zone on the way out is not rejected here, it is
   * silently re-read as UTC. Seeing raw and repaired side by side is what distinguishes "the
   * client sent something unparseable" from "we turned a local timestamp into a UTC one".
   *
   * <p>The failure is ERROR rather than WARN on measured evidence, not on principle. The level was
   * deliberately left at WARN when the verdict logging was raised, on the grounds that no guard
   * had ever been observed firing and a burst of unexplained ERRORs risks getting the whole signal
   * muted. Probing core's parser settled it: {@code 2026-08-28T12:30:15Z} — the single most
   * canonical way to write UTC, and valid XML Schema — landed here, because the repair appended
   * {@code +0000} to a token that already ended in {@code Z}. So this guard is reachable by a
   * well-formed emitter, and every hit means a write went through with no concurrency check at
   * all. That is the invisible failure this ticket exists to surface, and WARN is the level the
   * team's log tracking does not read. {@link #normalizeZoneDesignator} closes the {@code Z} case
   * itself; the level stays raised because whatever is left here is a real defect.
   */
  private static Date parseClientValue(String clientValue, String dalEntityName, String recordId,
      String requestPath) {
    String repaired = repairedForCompare(clientValue);
    if (!hasZoneOffset(clientValue)) {
      log.error("Client `updated` carries no zone offset — {}, entity '{}', record '{}', raw '{}',"
          + " repaired '{}', server timezone '{}'. This is NOT refused and NOT skipped: core's XSD"
          + " repair appends '+0000' rather than rejecting it, so the token parses cleanly and is"
          + " then compared AS UTC — the verdict below is wrong by exactly the server's offset."
          + " Always a defect on the emitting side, since a client cannot lose an offset that was"
          + " in the response it read: find whoever built this record's `updated` and route it"
          + " through NeoDateFormat.toAuditToken (ETP-5255 class A)", route(requestPath),
          dalEntityName, recordId, clientValue, repaired, TimeZone.getDefault().getID());
    }
    try {
      return new Timestamp(JsonUtils.createDateTimeFormat().parse(repaired).getTime());
    } catch (ParseException | RuntimeException e) {
      log.error("Concurrency check DID NOT RUN (guard: unparseable client `updated`) — {}, raw"
          + " '{}', repaired '{}': {}. The write proceeds unchecked, so a genuine conflict here is"
          + " applied silently — this is the one guard whose own firing is the defect. A"
          + " well-formed token cannot reach this line: what core's format accepts is Z, +hh:mm"
          + " and +hhmm, and Z is normalized before the repair (see normalizeZoneDesignator), so"
          + " the token is malformed or truncated on the emitting side — an hour-only '+02'"
          + " offset, or a date with no time component", route(requestPath), clientValue,
          repaired, e.getMessage());
      return null;
    }
  }

  /**
   * Whether the token names its zone, tested on the RAW value — before the XSD repair, which is
   * what destroys the evidence by appending {@code +0000}.
   *
   * <p>Only the part after the {@code T} is examined: the date half carries {@code -} separators
   * that would otherwise read as a negative offset. Accepts every shape an emitter might plausibly
   * write — {@code Z}, {@code +02:00}, {@code +0200}, {@code +02} — deliberately wider than what
   * core's format can actually parse, because the question here is "did the emitter state a zone
   * at all", not "is the offset well formed". Two of those shapes do NOT survive the repair
   * ({@code Z} is normalized first; a bare {@code +02} is not, and fails in the guard below), and
   * that asymmetry is the point: answering {@code true} keeps this detector quiet about a token
   * that named its zone, and lets the unparseable guard report it as the malformed value it is.
   *
   * <p>A value with no {@code T} has no time component, so it has no zone either and cannot be a
   * concurrency token.
   */
  private static boolean hasZoneOffset(String value) {
    int timeStart = StringUtils.indexOf(value, 'T');
    if (timeStart < 0) {
      return false;
    }
    String timePart = value.substring(timeStart + 1);
    return StringUtils.containsAny(timePart, 'Z', 'z', '+', '-');
  }

  /**
   * The exact string the concurrency check parses: the zone designator normalised, then core's
   * XSD-to-Java repair.
   *
   * <p>Exists so there is only ONE derivation of it. There used to be two — the parse built its
   * own and the verdict line rebuilt it with a bare {@code repairQuietly} — and the moment
   * {@link #normalizeZoneDesignator} was introduced they diverged: a {@code Z} token was compared
   * as {@code …+0000} but *reported* as {@code …Z+0000}, so the diagnostic named a string that had
   * never been parsed, in precisely the case the normalisation had just fixed. A log line that
   * misreports its own input is worse than no log line, because it sends the reader after a
   * parsing bug that does not exist.
   *
   * <p>Pure and cheap, so recomputing it per call site is fine; what matters is that every call
   * site computes the SAME thing.
   */
  private static String repairedForCompare(String clientValue) {
    return repairQuietly(normalizeZoneDesignator(clientValue));
  }

  /**
   * Rewrites a trailing {@code Z}/{@code z} zone designator as {@code +00:00} before the XSD
   * repair sees it.
   *
   * <p>ETP-5255: without this, the most canonical way to say UTC does not survive the repair.
   * {@code convertFromXSDToJavaFormat} appends {@code +0000} to {@code 2026-08-28T12:30:15Z},
   * producing {@code 2026-08-28T12:30:15Z+0000}, which the shared datetime format cannot parse —
   * so the concurrency check did not run at all and the write went through unchecked. A
   * well-formed, explicitly zoned token was being dropped on the floor, and the only trace was a
   * WARN nobody reads.
   *
   * <p>{@code +00:00} rather than {@code +0000}, deliberately: the repair recognises the
   * colon-separated form and rewrites it to {@code +0000} cleanly, whereas an RFC822 offset is not
   * recognised and gets a second {@code +0000} appended. That doubled form does parse, but only
   * because {@code SimpleDateFormat} discards trailing text once the pattern is satisfied — it
   * parses {@code +0200XYZZY} just as happily. Feeding the repair the shape it understands keeps
   * this off that accident.
   *
   * <p>Scope is exactly what XML Schema {@code dateTime} permits for a zone, which is {@code Z} or
   * {@code +hh:mm}. An hour-only {@code +02} is legal ISO 8601 but not legal XSD, so it is left to
   * fail in the guard above rather than quietly accepted here: widening what counts as a valid
   * token is a behaviour change that belongs to whoever owns the emitting contract, not to a
   * concurrency check.
   */
  private static String normalizeZoneDesignator(String clientValue) {
    if (StringUtils.indexOf(clientValue, 'T') < 0) {
      return clientValue;
    }
    if (!StringUtils.endsWithAny(clientValue, "Z", "z")) {
      return clientValue;
    }
    return StringUtils.substring(clientValue, 0, clientValue.length() - 1) + "+00:00";
  }

  /**
   * The XSD-to-Java repair, degrading to the original string if it throws.
   *
   * <p>Only ever feeds a log line or a parse that is already inside its own try, so a failure here
   * must not become the reason a write is refused. Returning the raw value keeps the diagnostic
   * honest: it then reads "raw and repaired are the same", which is exactly what happened.
   */
  private static String repairQuietly(String clientValue) {
    try {
      return JsonUtils.convertFromXSDToJavaFormat(clientValue);
    } catch (RuntimeException e) {
      return clientValue;
    }
  }

  /**
   * Records a decided verdict: ERROR when stale, DEBUG when not.
   *
   * <p>The passing case is logged too, and that is the point rather than noise. A refusal on its
   * own says nothing about whether the comparison is trustworthy; the same session's successful
   * saves are the baseline it has to be read against — if they carry the same non-zero delta, the
   * delta is not what made this one fail. Raise this class to DEBUG and both appear together.
   *
   * <p>Guarded by {@link Logger#isDebugEnabled()} so the diagnostic string is not built for a
   * verdict nobody is going to read: at INFO this is the hot path of every single update.
   *
   * <p>ETP-5255 raised the refusal from WARN to ERROR. A clash CAN be legitimate — somebody
   * really did save first, and the user recovers by reloading — which is the argument for WARN,
   * and it loses to one fact: the team reads production through log-analysis tooling that
   * surfaces ERROR only, so a WARN here is written, retained and never read. At the moment of
   * logging, a real conflict and a fabricated one are indistinguishable, and the four windows
   * investigated under this ticket showed the fabricated kind is the common one. A legitimate
   * conflict logged at ERROR costs a glance; a false one logged at WARN is a user-visible defect
   * nobody finds. The delta in the line is what tells the two apart afterwards.
   */
  private static void logVerdict(boolean stale, String dalEntityName, String recordId,
      String clientValue, Date clientUpdated, Date storedUpdated, String requestPath) {
    if (!stale && !log.isDebugEnabled()) {
      return;
    }
    String comparison =
        describeComparison(dalEntityName, recordId, clientValue, clientUpdated, storedUpdated);
    if (stale) {
      log.error("Concurrency check refused the write as stale on {} — {}", route(requestPath),
          comparison);
    } else {
      log.debug("Concurrency check passed on {} — {}", route(requestPath), comparison);
    }
  }

  /**
   * The single line carrying both sides of the comparison. See this class's javadoc for how to
   * read the delta.
   *
   * <p>Extracted rather than inlined into {@link #isStale} to keep that method's cognitive
   * complexity where it was: the verdict is the one thing a reader goes to {@code isStale} for,
   * and an eleven-argument format call in the middle of it buries the two lines that decide it.
   */
  private static String describeComparison(String dalEntityName, String recordId,
      String clientValue, Date clientUpdated, Date storedUpdated) {
    long clientMillis = clientUpdated.getTime();
    long storedMillis = storedUpdated.getTime();
    return String.format(
        "entity '%s', record '%s'; client raw '%s', repaired '%s', parsed %s (%d ms);"
            + " stored %s (%d ms); delta %+d ms; server timezone '%s'",
        dalEntityName, recordId, clientValue, repairedForCompare(clientValue),
        render(clientUpdated),
        clientMillis, render(storedUpdated), storedMillis, clientMillis - storedMillis,
        TimeZone.getDefault().getID());
  }

  /**
   * A timestamp rendered for a human, with milliseconds AND the zone offset.
   *
   * <p>Both halves matter: the offset is what makes a whole-hour delta legible as a timezone
   * problem instead of an unexplained hour, and the milliseconds are the field
   * {@link #equalToTheSecond} deliberately ignores — a reader has to see the value the comparison
   * threw away to trust that it threw away the right thing.
   *
   * <p>The formatter is built per call, never cached in a static field. That is not caution for
   * its own sake: a shared {@code SimpleDateFormat} in exactly this role is what caused the false
   * conflicts investigated in ETP-5112 (core's {@code JsonToDataConverter} holds its parser in a
   * {@code private final static}, and two concurrent writes through it corrupted each other's
   * date). A diagnostic must not be able to reproduce the bug it exists to explain.
   */
  private static String render(Date value) {
    return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(value);
  }

  /**
   * Millisecond-insensitive equality, mirroring core's {@code areDatesEqual(d1, d2, true, false)}.
   *
   * <p>The tolerance is not a guess: Postgres keeps microseconds on the column while the value
   * that travels to the client is formatted to the second, so a strict comparison would make
   * every single write look stale.
   */
  private static boolean equalToTheSecond(Date first, Date second) {
    Calendar a = Calendar.getInstance();
    a.setTime(first);
    a.set(Calendar.MILLISECOND, 0);
    Calendar b = Calendar.getInstance();
    b.setTime(second);
    b.set(Calendar.MILLISECOND, 0);
    return a.getTimeInMillis() == b.getTimeInMillis();
  }
}
