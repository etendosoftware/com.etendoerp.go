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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.module.remittance.Remittance;

/**
 * ETP-6148 — the run of Classic's «Select Invoices and Orders» handler
 * ({@code REMSelectInvoicesAndOrders}) that adds the due amounts {@link RemittanceDueItems} chose as
 * lines of a remittance: the selection rows it takes, its parameters, the run itself and the
 * reading of its answer. Split out of {@link RemittanceDueItems}; the constants that name the
 * process stay there.
 */
final class RemittanceDueSelection {

  private static final String PROCESS_FAILED = "Process failed";
  private static final String KEY_MSG_TEXT = "msgText";

  private RemittanceDueSelection() {
  }

  /**
   * One selection row of the handler's grid.
   *
   * @param id          the invoice payment schedule id
   * @param amount      the amount to remit
   * @param outstanding the outstanding amount of the due
   * @return the row
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject row(String id, BigDecimal amount, BigDecimal outstanding)
      throws JSONException {
    JSONObject selected = new JSONObject();
    selected.put("paymentScheduleID", id);
    selected.put("realpayment", amount.toPlainString());
    selected.put("outstandingamount", outstanding.toPlainString());
    selected.put("rest", "0");
    return selected;
  }

  /**
   * The Classic handler's parameters; no {@code _allRows}, so REM never deletes a line itself.
   *
   * @param remittance the remittance the lines are added to
   * @param selection  the rows built by {@link #row}
   * @return the process parameters
   * @throws JSONException if the JSON cannot be built
   */
  static JSONObject params(Remittance remittance, JSONArray selection) throws JSONException {
    JSONObject grid = new JSONObject();
    grid.put("_selection", selection);
    JSONObject processParams = new JSONObject();
    processParams.put("inpRecordId", remittance.getId());
    processParams.put("inpTabId", RemittanceDueItems.HEADER_TAB_ID);
    processParams.put(RemittanceDueItems.GRID_PARAMETER, grid);
    return processParams;
  }

  /**
   * Seam: the Classic handler run.
   *
   * @param processParams what {@link #params} built
   * @return the handler's answer
   */
  static NeoResponse run(JSONObject processParams) {
    return NeoProcessService.executeObuiappClass(RemittanceDueItems.SELECT_HANDLER_CLASS,
        RemittanceDueItems.SELECT_PROCESS_ID, processParams);
  }

  /**
   * The error text of REM's answer, or {@code null} on success. REM reports through
   * {@code showMsgInView} (not {@code showMsgInProcessView}), which the generic translation does not
   * read as a failure, so it is read here.
   *
   * @param result the handler's answer
   * @return the error text, or {@code null} when REM succeeded
   */
  static String failureText(NeoResponse result) {
    if (result == null || result.getBody() == null) {
      return "No response";
    }
    if (result.getHttpStatus() >= 400) {
      return StringUtils.defaultIfBlank(result.getBody().optString("message", null),
          PROCESS_FAILED);
    }
    JSONObject message = viewMessage(result.getBody());
    if (message != null && "error".equalsIgnoreCase(message.optString("msgType"))) {
      return StringUtils.defaultIfBlank(message.optString(KEY_MSG_TEXT, null), PROCESS_FAILED);
    }
    return null;
  }

  /**
   * The text of REM's success message, or the number of lines when it gave none.
   *
   * @param result  the handler's answer
   * @param created the number of lines created
   * @return the message
   */
  static String successText(NeoResponse result, long created) {
    JSONObject message = result != null && result.getBody() != null
        ? viewMessage(result.getBody())
        : null;
    String text = message != null ? StringUtils.trimToNull(message.optString(KEY_MSG_TEXT, null))
        : null;
    return text != null ? text : String.valueOf(created);
  }

  private static JSONObject viewMessage(JSONObject body) {
    JSONArray actions = body.optJSONArray("responseActions");
    if (actions == null) {
      return null;
    }
    for (int i = 0; i < actions.length(); i++) {
      JSONObject action = actions.optJSONObject(i);
      JSONObject message = action != null ? action.optJSONObject("showMsgInView") : null;
      if (message == null && action != null) {
        message = action.optJSONObject("showMsgInProcessView");
      }
      if (message != null) {
        return message;
      }
    }
    return null;
  }
}
