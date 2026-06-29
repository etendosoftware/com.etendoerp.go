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

package com.etendoerp.go.schemaforge;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;

/**
 * Shared validation and response helpers for period open/close handlers.
 *
 * @see PeriodOpenCloseHandler
 * @see PeriodControlDocOpenCloseHandler
 */
class PeriodOpenCloseSupport {

  static final String FIELD_OPEN_CLOSE = "openClose";
  static final String FIELD_VALUES = "fieldValues";
  private static final String KEY_STATUS = "status";
  private static final String KEY_MESSAGE = "message";

  private PeriodOpenCloseSupport() {}

  /**
   * Parsed and validated parameters extracted from an openClose ACTION request.
   *
   * <p>If {@link #isAbort()} returns {@code true}, the handler must return
   * {@link #toHandlerReturn()} immediately. Otherwise {@link #openCloseValue} and
   * {@link #recordId} are ready to use.
   */
  static class OpenCloseRequest {

    private static final OpenCloseRequest SKIP = new OpenCloseRequest(null, null, null);

    final String openCloseValue;
    final String recordId;
    private final NeoResponse error;

    private OpenCloseRequest(String openCloseValue, String recordId, NeoResponse error) {
      this.openCloseValue = openCloseValue;
      this.recordId = recordId;
      this.error = error;
    }

    /** Returns {@code true} when the handler must stop and return {@link #toHandlerReturn()}. */
    boolean isAbort() {
      return openCloseValue == null;
    }

    /**
     * Returns the value the {@code handle()} method should propagate when {@link #isAbort()} is
     * {@code true}: {@code null} to skip (not our action) or an error {@link NeoResponse}.
     */
    NeoResponse toHandlerReturn() {
      return error;
    }
  }

  /**
   * Parses and validates an openClose ACTION request from the given context.
   *
   * @return an {@link OpenCloseRequest} whose {@link OpenCloseRequest#isAbort()} indicates whether
   *         the caller must return early.
   */
  static OpenCloseRequest parse(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION
        || !FIELD_OPEN_CLOSE.equals(context.getFieldName())) {
      return OpenCloseRequest.SKIP;
    }

    JSONObject body = context.getRequestBody();
    if (body == null) {
      return new OpenCloseRequest(null, null, NeoResponse.error(400, "Missing request body"));
    }

    JSONObject fieldValues = body.optJSONObject(FIELD_VALUES);
    String openCloseValue = fieldValues != null ? fieldValues.optString(FIELD_OPEN_CLOSE, null) : null;
    if (openCloseValue == null || openCloseValue.isBlank()) {
      return new OpenCloseRequest(null, null,
          NeoResponse.error(400, "Missing required parameter: openClose"));
    }

    String recordId = context.getRecordId();
    if (recordId == null || recordId.isBlank()) {
      return new OpenCloseRequest(null, null, NeoResponse.error(400, "Missing recordId"));
    }

    return new OpenCloseRequest(openCloseValue, recordId, null);
  }

  /**
   * Translates a {@link ProcessInstance} result into a {@link NeoResponse}.
   *
   * <p>Result code 0 is treated as failure (400); any other value as success (200).
   */
  static NeoResponse translateResult(ProcessInstance pInstance, Process process) throws Exception {
    JSONObject result = new JSONObject();
    long resultCode = pInstance.getResult() != null ? pInstance.getResult() : 0L;
    String errorMsg = pInstance.getErrorMsg();

    if (resultCode == 0L) {
      String cleanMsg = errorMsg != null
          ? errorMsg.replaceFirst("@ERROR=", "")
          : "Process failed";
      result.put(KEY_STATUS, "error");
      result.put(KEY_MESSAGE, cleanMsg);
      return new NeoResponse(400, result);
    }

    result.put(KEY_STATUS, "success");
    if (errorMsg != null && !errorMsg.isBlank()) {
      result.put(KEY_MESSAGE, errorMsg.replaceFirst("@SUCCESS=", ""));
    } else {
      result.put(KEY_MESSAGE, "Process " + process.getName() + " executed successfully");
    }
    return NeoResponse.ok(result);
  }
}
