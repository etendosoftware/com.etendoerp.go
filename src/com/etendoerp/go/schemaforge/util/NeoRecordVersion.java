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
import java.util.Calendar;
import java.util.Date;

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
   * @param dalEntityName the DAL entity name (e.g. {@code "Order"})
   * @param recordId      the record being written
   * @param clientValue   the {@code updated} value the caller echoed back from its read
   * @return whether the write must be refused as a concurrent-modification conflict
   */
  public static boolean isStale(String dalEntityName, String recordId, String clientValue) {
    if (StringUtils.isBlank(dalEntityName) || StringUtils.isBlank(recordId)
        || StringUtils.isBlank(clientValue) || "null".equals(clientValue)) {
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
    return !equalToTheSecond(clientUpdated, storedUpdated);
  }

  /** The stored {@code updated}, or {@code null} when it cannot be established. */
  private static Date readStoredUpdated(String dalEntityName, String recordId) {
    try {
      BaseOBObject stored = OBDal.getInstance().get(dalEntityName, recordId);
      if (!(stored instanceof Traceable)) {
        return null;
      }
      return ((Traceable) stored).getUpdated();
    } catch (Exception e) {
      log.debug("Could not read the stored `updated` of {} {}: {}", dalEntityName, recordId,
          e.getMessage());
      return null;
    }
  }

  /**
   * Parses the caller's token exactly as core does: the XSD-to-Java repair first, then the shared
   * datetime format. A value we cannot parse is NOT a conflict — core will reject it on its own
   * terms, and reporting it as a concurrency failure would send the user to reload a record that
   * was never the problem.
   */
  private static Date parseClientValue(String clientValue) {
    try {
      String repaired = JsonUtils.convertFromXSDToJavaFormat(clientValue);
      return new Timestamp(JsonUtils.createDateTimeFormat().parse(repaired).getTime());
    } catch (ParseException | RuntimeException e) {
      log.debug("Could not parse the caller's `updated` value '{}': {}", clientValue,
          e.getMessage());
      return null;
    }
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
