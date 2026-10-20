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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.service.json.JsonUtils;

import com.etendoerp.go.schemaforge.NeoErrorClassifier.Classification;
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
   * itself — so a refused request writes nothing (the request filter commits whatever the session
   * holds when the servlet answers instead of throwing), and so the translation that follows reads
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
    JSONObject error = response.getBody() != null ? response.getBody().optJSONObject("error") : null;
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
   * The response for a captured, classified write failure — or {@code null} when it is not one a
   * caller can act on (not captured, or a 5xx), which its callers keep answering as before.
   *
   * @param innerResponse core's {@code response} object
   * @param where         what failed, for the log line only
   * @return the response, or {@code null}
   */
  static NeoResponse fromClassifiedWriteFailure(JSONObject innerResponse, String where) {
    JSONObject classification = classificationOf(innerResponse);
    if (classification == null) {
      return null;
    }
    int status = classification.optInt(KEY_STATUS, 500);
    if (status >= 500 && status != 503) {
      return null;
    }
    String code = classification.optString(KEY_CODE, NeoErrorClassifier.CODE_SERVER_ERROR);
    List<String> keys = new ArrayList<>();
    JSONArray rawKeys = classification.optJSONArray(KEY_MESSAGE_KEYS);
    for (int i = 0; rawKeys != null && i < rawKeys.length(); i++) {
      keys.add(rawKeys.optString(i));
    }
    if (status >= 500) {
      log.error("{} failed: {} {}", where, status, code);
    } else {
      log.warn("{} refused: {} {}{}", where, status, code,
          keys.isEmpty() ? "" : " " + keys);
    }
    return toResponse(status, code, classification.optString(KEY_MESSAGE, null), keys);
  }

  /**
   * Re-translates the messages of a batch failure once the batch has rolled back.
   *
   * <p>A refusal classified while a batch still owned the transaction was displayed on a session
   * the failure had already aborted, where {@code AD_Message} cannot be read, so its message may
   * still be the raw {@code @Key@}. Only a message that still holds such a token is touched.
   *
   * @param failure the batch failure body ({@code error}, and the operation's response under
   *                {@code error.detail})
   */
  static void retranslateBatchFailure(JSONObject failure) {
    JSONObject error = failure != null ? failure.optJSONObject("error") : null;
    if (error == null) {
      return;
    }
    retranslate(error);
    JSONObject detail = error.optJSONObject("detail");
    JSONObject detailError = detail != null ? detail.optJSONObject("error") : null;
    if (detailError != null) {
      retranslate(detailError);
    }
  }

  private static void retranslate(JSONObject error) {
    String message = error.optString(KEY_MESSAGE, null);
    if (message == null || NeoMessageTranslator.extractMessageKeys(message).isEmpty()) {
      return;
    }
    try {
      error.put(KEY_MESSAGE, NeoErrorClassifier.primaryLine(
          NeoMessageTranslator.translateDbMessage(message)));
    } catch (JSONException e) {
      log.debug("Could not re-translate a batch failure: {}", e.getMessage());
    }
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
}
