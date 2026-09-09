package com.etendoerp.go.schemaforge;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ETP-5112: the server-side record of a write NEO refused, naming the endpoint that sent it.
 *
 * <h2>Why this exists at all</h2>
 *
 * A refused write used to be nearly invisible from the server side. The rejection happens in
 * 8-14 ms, before the database is touched, so there is no stack trace and no SQL, and the only
 * trace left was {@code LogNeoTelemetrySink}'s generic {@code status=failed} line — which says a
 * write failed but not WHICH screen sent it or why. ETP-5112 was reported by a user who could not
 * save, not by anyone reading a log, and by then it had been failing 100% of the time on ~15
 * screens.
 *
 * <p>Every line here rebuilds the endpoint from the {@link NeoContext} rather than reading it off
 * the request: {@code method}, {@code spec}, {@code entity} and {@code recordId} together identify
 * the caller (e.g. {@code PATCH /organization/information/<id>} points straight at the
 * Organization tab). Deliberately never the request body and never field values — the body of a
 * write carries business data and belongs nowhere near a log.
 *
 * <h2>Why it is its own class</h2>
 *
 * These three methods were originally private to {@code NeoCrudHandler}, which sat exactly on
 * Sonar's 35-method ceiling (java:S1448) and went over it. Logging is the one concern in that class
 * that is genuinely separable — it holds no state, touches no request flow, and returns nothing —
 * so it is what moved rather than something load-bearing.
 */
final class NeoWriteRefusalLog {

  private static final Logger log = LogManager.getLogger(NeoWriteRefusalLog.class);

  private NeoWriteRefusalLog() {
    // utility class — no instances
  }

  /**
   * An update that arrived with no concurrency token at all.
   *
   * <p>Logged at ERROR rather than WARN even though a 400 is formally a client fault. The telemetry
   * sink already emits WARN for exactly this event ({@code level()} maps {@code status=failed} to
   * {@code Level.WARN}) and that is the line everybody missed, so repeating the level would repeat
   * the outcome. It also matches how {@code NeoCrudHandler} already reports an update it could not
   * carry out. The condition is always a client bug that needs a code fix, never a transient or
   * user-correctable state.
   *
   * @param context      the request being refused
   * @param fieldUpdated the name of the concurrency-token field, so the line names it exactly as
   *                     the response body does
   */
  static void missingUpdated(NeoContext context, String fieldUpdated) {
    log.error("Update rejected: no '{}' token on {} /{}/{}/{} — the caller did not read the"
        + " record before writing it (ETP-5073 concurrency check)",
        fieldUpdated, context.getHttpMethod(), context.getSpecName(), context.getEntityName(),
        context.getRecordId());
  }

  /**
   * An update whose token no longer matches the stored row.
   *
   * <p>The token IS logged here, unlike in the missing-token case where there is none. It is a
   * row's {@code updated} timestamp — not business data — and without it the line cannot be acted
   * on: telling a stale read apart from a token the client mangled means seeing what was sent.
   * That distinction is not hypothetical. During ETP-5112 a false conflict was traced to core's
   * {@code JsonToDataConverter} holding its parser in a {@code private final static
   * SimpleDateFormat}: two concurrent writes parsing through that one shared, non-thread-safe
   * instance corrupted each other's date, and the check then failed against a record nobody had
   * touched. The tell was two requests logged in the same millisecond carrying an identical token,
   * one passing and one failing — invisible without the token in the line.
   *
   * <p>WARN, not ERROR: unlike a missing token this can be legitimate — somebody really did save
   * first — and the user can act on it by reloading. Only the impossible version of it (a conflict
   * on a record with no other writer) is a defect, and that is what the token lets a reader spot.
   */
  static void staleRecord(NeoContext context, String clientValue) {
    log.warn("Update refused as stale on {} /{}/{}/{} — caller sent '{}' but the row has moved on;"
        + " a conflict here with no other writer means the token was corrupted, not outdated",
        context.getHttpMethod(), context.getSpecName(), context.getEntityName(),
        context.getRecordId(), clientValue);
  }

  /**
   * A write core refused for a reason none of {@code checkJsonServiceResponse}'s branches
   * recognised.
   *
   * <p>This is the blind spot ETP-5112 exposed. A save on the Organization screen answered 500
   * carrying core's translated "the record has already been changed by another user" — a
   * concurrency conflict that reached the generic bucket because the branch meant to catch it
   * ({@code isStaleRecordMessage}) only matches the UNTRANSLATED code, and by then
   * {@code Utility.translateError} had already consumed it. Nothing named the endpoint, so the only
   * trace was the telemetry sink's {@code status=failed httpStatus=500}.
   *
   * <p>Both strings are logged. {@code errMsg} is what core actually produced and is the only
   * stable thing to key on; {@code translated} is what the classifiers examined, so seeing the pair
   * is what shows WHY a branch did not match — with just one of them, this exact defect reads as an
   * ordinary server error. Neither carries field values: they are core's own message text, already
   * sanitised for the client by the caller.
   *
   * <p>ERROR at 500 and WARN at 409: a 500 here is by definition unclassified — either a genuine
   * fault or, as in this case, a condition that deserved its own branch and did not get one. A 409
   * is a duplicate key, which is the caller's data to fix and needs no attention from us.
   */
  static void unclassifiedWriteFailure(int httpStatus, String errMsg, String translated) {
    String message = "Write refused by core with no matching classifier — status {},"
        + " raw '{}', translated '{}'";
    if (httpStatus == HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
      log.error(message, httpStatus, errMsg, translated);
    } else {
      log.warn(message, httpStatus, errMsg, translated);
    }
  }
}
