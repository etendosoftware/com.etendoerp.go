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

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.service.json.JsonUtils;

import com.etendoerp.go.schemaforge.NeoErrorClassifier.Classification;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Turns a failure into the response every NEO channel sends for it (ETP-5782), using
 * {@link NeoErrorClassifier} for the decision.
 *
 * <p>Two entry points, for the two ways a failure reaches NEO:
 * <ul>
 *   <li><b>Thrown</b> — {@link #fromException}: a customization, a process, the batch loop or the
 *       servlet itself caught the exception. Classified, logged once, the session rolled back, and
 *       answered with the nested {@code {"error":{"status","code","message"[,"messageKeys"]}}}
 *       body.</li>
 *   <li><b>Swallowed by core</b> — core's JSON write service catches every write failure and
 *       returns it as prose. NEO's own write service ({@link NeoBatchJsonDataService}) keeps the
 *       throwable: {@link #writeFailure} classifies it and attaches the classification to core's
 *       failure body under {@value #CLASSIFICATION_KEY}, where {@link #classificationOf} reads it
 *       back. A failure body without it was not captured, and its callers keep answering it as
 *       before rather than guess from its text.</li>
 * </ul>
 *
 * <p>Logging: a 4xx is the caller's to fix — one WARN line, no stack trace. A 5xx is ours — ERROR
 * with the stack trace.
 */
public final class NeoErrorResponses {

  private static final Logger log = LogManager.getLogger(NeoErrorResponses.class);

  /** Key, inside core's {@code response.error}, of the classification NEO attached to it. */
  public static final String CLASSIFICATION_KEY = "neoClassification";

  /** Hint of a failure raised after the main write committed: re-sending would repeat it. */
  public static final String AFTER_COMMIT_HINT = "The record may already be saved. Read it before "
      + "re-sending the request.";

  private static final String KEY_ERROR = "error";
  private static final String KEY_HINT = "hint";
  static final String KEY_CODE = "code";
  static final String KEY_STATUS = "status";
  static final String KEY_MESSAGE = "message";
  static final String KEY_MESSAGE_KEYS = "messageKeys";

  private NeoErrorResponses() {
  }

  /**
   * The response for a thrown exception.
   *
   * <p>Rolls the session back first — unless a batch owns the transaction, which rolls back
   * itself — to discard uncommitted work. Earlier commits, including the main write before
   * an afterHandle failure, cannot be undone. The translation that follows reads
   * {@code AD_Message} on a usable session.
   *
   * @param t            the exception
   * @param httpMethod   the verb of the failed request; may be {@code null}
   * @param where        what failed, for the log line only
   * @param serverPrefix prefix of the message of a 5xx answer (e.g. {@code "Hook handler error: "});
   *                     never applied to a 4xx, whose message is the caller's to read
   * @return the error response
   */
  public static NeoResponse fromException(Throwable t, String httpMethod, String where,
      String serverPrefix) {
    Classification c = NeoErrorClassifier.classify(t, httpMethod);
    logClassified(c, t, where);
    rollbackUnlessBatchOwned();
    String message = NeoErrorClassifier.displayMessage(c);
    if (c.isServerSide() && serverPrefix != null) {
      message = serverPrefix + message;
    }
    return toResponse(c.status(), c.code(), message, c.messageKeys());
  }

  /**
   * The response for an exception raised AFTER the main operation completed — by
   * {@code afterHandle}, once the default write (or the customization's own handling) succeeded.
   *
   * <p>Outside a batch that write is already committed, so the failure is NOT answered as a 4xx
   * the caller should correct and re-send: re-sending would repeat a write that happened. It stays
   * the 500 it always was, its message sanitized, with {@link #AFTER_COMMIT_HINT} on a write.
   * Inside a batch nothing is committed yet (the batch rolls the whole set back), so the failure
   * is classified as usual.
   *
   * @param t            the exception
   * @param httpMethod   the verb of the request; may be {@code null}
   * @param where        what failed, for the log line only
   * @param serverPrefix prefix of the message (e.g. {@code "Hook handler error: "}); may be
   *                     {@code null}
   * @return the error response
   */
  public static NeoResponse afterCommitFailure(Throwable t, String httpMethod, String where,
      String serverPrefix) {
    if (BatchService.callerOwnsTransaction()) {
      return fromException(t, httpMethod, where, serverPrefix);
    }
    log.error("{} failed after the main operation completed", where, t);
    rollbackUnlessBatchOwned();
    String message = (serverPrefix != null ? serverPrefix : "") + NeoErrorSanitizer.sanitize(t);
    NeoResponse response = toResponse(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        NeoErrorClassifier.CODE_SERVER_ERROR, message, null);
    JSONObject error = response.getBody() != null
        ? response.getBody().optJSONObject(KEY_ERROR)
        : null;
    if (error != null && !"GET".equalsIgnoreCase(httpMethod)) {
      try {
        error.put(KEY_HINT, AFTER_COMMIT_HINT);
      } catch (JSONException e) {
        log.debug("Could not add the after-commit hint: {}", e.getMessage());
      }
    }
    return response;
  }

  /**
   * Rolls the current session back, unless a batch owns the transaction (it rolls back the whole
   * set itself, and closing the session mid-batch would break its bookkeeping). Never throws.
   */
  public static void rollbackUnlessBatchOwned() {
    if (BatchService.callerOwnsTransaction()) {
      return;
    }
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (Exception e) {
      log.debug("Rollback after a refused request failed: {}", e.getMessage());
    }
  }

  /**
   * The nested error body, plus its machine-readable {@code code} and, when known, the AD message
   * keys of the failure.
   *
   * @param status      the HTTP status
   * @param code        the {@code error.code}
   * @param message     the {@code error.message}
   * @param messageKeys the {@code error.messageKeys}; omitted when empty or {@code null}
   * @return the response
   */
  public static NeoResponse toResponse(int status, String code, String message,
      List<String> messageKeys) {
    NeoResponse response = NeoResponse.error(status, message);
    JSONObject error = response.getBody() != null ? response.getBody().optJSONObject(KEY_ERROR) : null;
    if (error != null) {
      try {
        error.put(KEY_CODE, code);
        if (messageKeys != null && !messageKeys.isEmpty()) {
          error.put(KEY_MESSAGE_KEYS, new JSONArray(messageKeys));
        }
      } catch (JSONException e) {
        // The plain error body is still a valid answer.
        log.debug("Could not add the error code: {}", e.getMessage());
      }
    }
    return response;
  }

  /**
   * Core's failure body for a write that threw, with NEO's classification attached. Called from
   * the catch of {@link NeoBatchJsonDataService}, where the throwable still exists.
   *
   * <p>Core's {@code convertExceptionToJson} runs first: it rolls the session back, so the
   * translation done while classifying reads {@code AD_Message} on a usable session.
   *
   * @param t          the throwable as caught
   * @param underlying the throwable core reports ({@code DbUtility.getUnderlyingSQLException(t)})
   * @param httpMethod the verb of the write
   * @return core's failure body, with {@code response.error.}{@value #CLASSIFICATION_KEY}
   */
  static String writeFailure(Throwable t, Throwable underlying, String httpMethod) {
    String coreBody = JsonUtils.convertExceptionToJson(underlying);
    Classification c = NeoErrorClassifier.classify(t, httpMethod);
    if (c.isServerSide() && underlying != null && underlying != t) {
      Classification fromUnderlying = NeoErrorClassifier.classify(underlying, httpMethod);
      if (!fromUnderlying.isServerSide()) {
        c = fromUnderlying;
      }
    }
    logClassified(c, t, "Write");
    try {
      JSONObject json = new JSONObject(coreBody);
      JSONObject response = json.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
      if (response == null) {
        return coreBody;
      }
      JSONObject error = response.optJSONObject(JsonConstants.RESPONSE_ERROR);
      if (error == null) {
        error = new JSONObject();
        response.put(JsonConstants.RESPONSE_ERROR, error);
      }
      JSONObject classification = new JSONObject();
      classification.put(KEY_STATUS, c.status());
      classification.put(KEY_CODE, c.code());
      classification.put(KEY_MESSAGE, NeoErrorClassifier.displayMessage(c));
      classification.put(KEY_MESSAGE_KEYS, new JSONArray(c.messageKeys()));
      error.put(CLASSIFICATION_KEY, classification);
      return json.toString();
    } catch (JSONException e) {
      log.warn("Could not attach the error classification: {}", e.getMessage());
      return coreBody;
    }
  }

  /**
   * The classification NEO attached to a core failure body, or {@code null} when the failure was
   * not captured by NEO's write service.
   *
   * @param innerResponse core's {@code response} object
   * @return the classification object ({@code status}, {@code code}, {@code message},
   *         {@code messageKeys}), or {@code null}
   */
  public static JSONObject classificationOf(JSONObject innerResponse) {
    JSONObject error = innerResponse != null
        ? innerResponse.optJSONObject(JsonConstants.RESPONSE_ERROR)
        : null;
    return error != null ? error.optJSONObject(CLASSIFICATION_KEY) : null;
  }

  /**
   * The response for a captured, classified write failure — or {@code null} when it is not a
   * captured failure. Uncaptured failures retain their existing handling; captured 5xx messages
   * use the sanitized classification rather than core prose.
   *
   * @param innerResponse core's {@code response} object
   * @return the response, or {@code null}
   */
  static NeoResponse fromClassifiedWriteFailure(JSONObject innerResponse) {
    JSONObject classification = classificationOf(innerResponse);
    if (classification == null) {
      return null;
    }
    int status = classification.optInt(KEY_STATUS, 500);
    String code = classification.optString(KEY_CODE, NeoErrorClassifier.CODE_SERVER_ERROR);
    List<String> keys = new ArrayList<>();
    JSONArray rawKeys = classification.optJSONArray(KEY_MESSAGE_KEYS);
    for (int i = 0; rawKeys != null && i < rawKeys.length(); i++) {
      keys.add(rawKeys.optString(i));
    }
    return toResponse(status, code, classification.optString(KEY_MESSAGE, null), keys);
  }

  /**
   * The 409 for a captured unique violation, worded with core's own translated sentence — or
   * {@code null} when the failure was not captured or is not a {@code duplicate_key}.
   *
   * <p>Core already translates a unique violation into the request language ("There is already a
   * … with the same (…). (…) must be unique." / "Ya existe … debe ser único."), and both the SPA's
   * duplicate-identifier message and the import UI's {@code isDuplicateKeyError()} recognise that
   * sentence. The classification's generic {@code DUPLICATE_KEY_ERROR} is English only, so it is
   * used only when core gave no sentence. The class is read from the classification, never from
   * the text: a text check only knows the English wording, and under any other language it let the
   * classification replace core's sentence (ETP-5953).
   *
   * @param innerResponse core's {@code response} object
   * @param translated    core's failure message, already translated
   * @return the response, or {@code null}
   */
  static NeoResponse duplicateWithCoreWording(JSONObject innerResponse, String translated) {
    JSONObject classification = classificationOf(innerResponse);
    if (classification == null
        || !NeoErrorClassifier.CODE_DUPLICATE_KEY.equals(classification.optString(KEY_CODE, null))) {
      return null;
    }
    String message = translated == null || translated.isBlank()
        ? classification.optString(KEY_MESSAGE, NeoErrorSanitizer.DUPLICATE_KEY_ERROR)
        : translated;
    return toResponse(HttpServletResponse.SC_CONFLICT, NeoErrorClassifier.CODE_DUPLICATE_KEY,
        NeoErrorSanitizer.stripRowDump(NeoErrorSanitizer.redactObjectReferences(message)), null);
  }

  /**
   * Re-translates the messages of a batch failure once the batch has rolled back.
   *
   * <p>A refusal classified while a batch still owned the transaction was displayed on a session
   * the failure had already aborted, where {@code AD_Message} cannot be read, so its message may
   * have been replaced with a safe generic message. The retained {@code messageKeys} allow a
   * second translation without retaining or returning raw database text.
   *
   * @param failure the batch failure body ({@code error}, and the operation's response under
   *                {@code error.detail})
   */
  static void retranslateBatchFailure(JSONObject failure) {
    JSONObject error = failure != null ? failure.optJSONObject(KEY_ERROR) : null;
    if (error == null) {
      return;
    }
    retranslate(error);
    JSONObject detail = error.optJSONObject("detail");
    JSONObject detailError = detail != null ? detail.optJSONObject(KEY_ERROR) : null;
    if (detailError != null) {
      String previous = detailError.optString(KEY_MESSAGE, null);
      retranslate(detailError);
      if (java.util.Objects.equals(previous, error.optString(KEY_MESSAGE, null))) {
        try {
          error.put(KEY_MESSAGE, detailError.optString(KEY_MESSAGE, previous));
        } catch (JSONException e) {
          log.debug("Could not synchronize the batch failure message: {}", e.getMessage());
        }
      }
    }
  }

  private static void retranslate(JSONObject error) {
    String message = error.optString(KEY_MESSAGE, null);
    List<String> keys = new ArrayList<>(NeoMessageTranslator.extractMessageKeys(message));
    // Metadata only recovers a failed translation; never discard a valid message's parameters.
    if (keys.isEmpty() && !isTranslationFallback(message)) {
      return;
    }
    JSONArray retainedKeys = error.optJSONArray(KEY_MESSAGE_KEYS);
    for (int i = 0; retainedKeys != null && i < retainedKeys.length(); i++) {
      String key = retainedKeys.optString(i);
      if (!key.isBlank() && !keys.contains(key)) {
        keys.add(key);
      }
    }
    if (keys.isEmpty()) {
      return;
    }
    String source = message != null && !NeoMessageTranslator.extractMessageKeys(message).isEmpty()
        ? message : "@" + String.join("@ @", keys) + "@";
    Classification classification = new Classification(error.optInt(KEY_STATUS, 422),
        error.optString(KEY_CODE, NeoErrorClassifier.CODE_BUSINESS_RULE), source, keys);
    try {
      error.put(KEY_MESSAGE, NeoErrorClassifier.displayMessage(classification));
    } catch (JSONException e) {
      log.debug("Could not re-translate a batch failure: {}", e.getMessage());
    }
  }

  private static boolean isTranslationFallback(String message) {
    return NeoErrorClassifier.MSG_BUSINESS_RULE.equals(message)
        || NeoErrorClassifier.MSG_RECORD_IN_USE.equals(message)
        || NeoErrorClassifier.MSG_INVALID_VALUE.equals(message)
        || NeoErrorClassifier.MSG_FORBIDDEN.equals(message)
        || NeoErrorClassifier.MSG_STALE_RECORD.equals(message);
  }

  private static void logClassified(Classification c, Throwable t, String where) {
    if (c.isServerSide()) {
      log.error("{} failed: {} {}", where, c.status(), c.code(), t);
    } else {
      log.warn("{} refused: {} {} ({}){}", where, c.status(), c.code(),
          t != null ? t.getClass().getSimpleName() : "null",
          c.messageKeys().isEmpty() ? "" : " " + c.messageKeys());
    }
  }

  /**
   * ETP-5783 — a document process refused by the document's own rules is a {@code 422
   * business_rule} (ETP-5782 envelope), not a 400: the request was well formed, the document's
   * state is what the caller has to fix. The flat {@code status}/{@code message}/{@code
   * messageKeys} the SPA has always read stay at the top level next to the nested {@code error}.
   *
   * @param flat        the flat failure body ({@code status}, translated {@code message})
   * @param messageKeys the AD message keys the raw message carried; may be empty
   * @return the 422 response
   * @throws JSONException if the body cannot be written
   */
  static NeoResponse businessRuleRefusal(JSONObject flat, List<String> messageKeys)
      throws JSONException {
    String message = flat.optString(NeoProcessService.MESSAGE);
    NeoResponse response = toResponse(NeoErrorClassifier.STATUS_UNPROCESSABLE,
        NeoErrorClassifier.CODE_BUSINESS_RULE, message, messageKeys);
    JSONObject body = response.getBody();
    body.put(NeoProcessService.STATUS, NeoProcessService.ERROR);
    body.put(NeoProcessService.MESSAGE, message);
    if (flat.has(NeoProcessService.MESSAGE_KEYS)) {
      body.put(NeoProcessService.MESSAGE_KEYS, flat.get(NeoProcessService.MESSAGE_KEYS));
    }
    return response;
  }

  /**
   * ETP-5073 / DOC-04: the structured 409 returned when core's optimistic-locking check fails —
   * somebody else saved this record between our read and our write.
   *
   * <p>A distinguishable code matters more than usual here:
   * {@code NeoCrudHandler.checkJsonServiceResponse} already answers 409 for a duplicate-key
   * conflict, and the two need opposite remedies (change your data vs. re-read and reapply). The client keys the reload-and-reapply offer required by
   * the ticket's first acceptance criterion off {@code error == "stale_record"}, never off status
   * alone.
   *
   * @param translated core's already-translated conflict message, kept as {@code message} so a
   *                   human reading the response still gets Etendo's own wording
   */
  static NeoResponse staleRecord(String translated) {
    try {
      JSONObject errorObj = new JSONObject();
      errorObj.put(KEY_STATUS, HttpServletResponse.SC_CONFLICT);
      errorObj.put(KEY_ERROR, "stale_record");
      errorObj.put(KEY_MESSAGE, translated);
      errorObj.put(NeoCrudHandler.KEY_DETAIL, "This record was modified by someone else after you"
          + " read it. Your write was refused so their change is not lost.");
      errorObj.put(KEY_HINT, "Re-read the record to get the current values and the fresh '"
          + NeoCrudHandler.FIELD_UPDATED + "', reapply your changes on top of it, then retry.");
      errorObj.put(NeoCrudHandler.KEY_SEE_ALSO, "docs(topic:\"updating records\")");
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, errorObj);
    } catch (Exception fallback) {
      log.warn("Could not build stale_record body: {}", fallback.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, translated);
    }
  }
}
