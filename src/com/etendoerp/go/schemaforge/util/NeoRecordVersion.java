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
    if (StringUtils.isBlank(dalEntityName) || StringUtils.isBlank(recordId)
        || StringUtils.isBlank(clientValue) || "null".equals(clientValue)) {
      log.warn("Concurrency check did not run (guard: missing argument) — entity '{}', record"
          + " '{}', client `updated` '{}'. The write proceeds with only core's own check behind"
          + " it (ETP-5073)", dalEntityName, recordId, clientValue);
      return false;
    }
    Date storedUpdated = readStoredUpdated(dalEntityName, recordId);
    if (storedUpdated == null) {
      return false;
    }
    Date clientUpdated = parseClientValue(clientValue);
    if (clientUpdated == null) {
      return false;
    }
    boolean stale = !equalToTheSecond(clientUpdated, storedUpdated);
    logVerdict(stale, dalEntityName, recordId, clientValue, clientUpdated, storedUpdated);
    return stale;
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
   * <p>ETP-5255 raised the failure to WARN and made it log the repaired string next to the raw
   * one. This is the guard most likely to be firing unnoticed in production: the repair does not
   * validate, it only reshapes — {@code convertFromXSDToJavaFormat} strips the colon from a
   * {@code +02:00} offset and, when there is no offset at all, appends {@code +0000} — so a token
   * that lost its zone on the way out is not rejected here, it is silently re-read as UTC. Seeing
   * raw and repaired side by side is what distinguishes "the client sent something unparseable"
   * from "we turned a local timestamp into a UTC one".
   */
  private static Date parseClientValue(String clientValue) {
    String repaired = repairQuietly(clientValue);
    try {
      return new Timestamp(JsonUtils.createDateTimeFormat().parse(repaired).getTime());
    } catch (ParseException | RuntimeException e) {
      log.warn("Concurrency check did not run (guard: unparseable client `updated`) — raw '{}',"
          + " repaired '{}': {}. The write proceeds unchecked", clientValue, repaired,
          e.getMessage());
      return null;
    }
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
   * Records a decided verdict: WARN when stale, DEBUG when not.
   *
   * <p>The passing case is logged too, and that is the point rather than noise. A refusal on its
   * own says nothing about whether the comparison is trustworthy; the same session's successful
   * saves are the baseline it has to be read against — if they carry the same non-zero delta, the
   * delta is not what made this one fail. Raise this class to DEBUG and both appear together.
   *
   * <p>Guarded by {@link Logger#isDebugEnabled()} so the diagnostic string is not built for a
   * verdict nobody is going to read: at INFO this is the hot path of every single update.
   */
  private static void logVerdict(boolean stale, String dalEntityName, String recordId,
      String clientValue, Date clientUpdated, Date storedUpdated) {
    if (!stale && !log.isDebugEnabled()) {
      return;
    }
    String comparison =
        describeComparison(dalEntityName, recordId, clientValue, clientUpdated, storedUpdated);
    if (stale) {
      log.warn("Concurrency check refused the write as stale — {}", comparison);
    } else {
      log.debug("Concurrency check passed — {}", comparison);
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
        dalEntityName, recordId, clientValue, repairQuietly(clientValue), render(clientUpdated),
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
