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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;
import org.openbravo.service.db.CallProcess;

/**
 * NeoHandler for the {@code documents} entity (C_PeriodControl records).
 *
 * <p>Intercepts the {@code openClose} ACTION endpoint. When the user selects an action
 * (Open / Close / Permanently Close) in the process parameter dialog, this handler:
 *
 * <ol>
 *   <li>Reads the chosen action code from the request body ({@code fieldValues.openClose}).</li>
 *   <li>Sets {@code C_PeriodControl.PeriodAction} to the chosen value (the procedure reads
 *       this directly).</li>
 *   <li>Calls stored procedure {@code C_PeriodControl_Process} (AD Process 168) passing the
 *       C_PeriodControl_ID as the {@code AD_PInstance.Record_ID}.</li>
 *   <li>Returns the translated process result.</li>
 * </ol>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'period-control-doc-openclose'} on the
 * {@code documents} ETGO_SF_ENTITY record for the
 * {@code open-close-period-control} spec.
 */
@Named("period-control-doc-openclose")
public class PeriodControlDocOpenCloseHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PeriodControlDocOpenCloseHandler.class);

  private static final String PROCESS_168_ID = "168";
  private static final String FIELD_OPEN_CLOSE = "openClose";
  private static final String FIELD_VALUES = "fieldValues";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION
        || !FIELD_OPEN_CLOSE.equals(context.getFieldName())) {
      return null;
    }

    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(400, "Missing request body");
    }

    JSONObject fieldValues = body.optJSONObject(FIELD_VALUES);
    String openCloseValue = fieldValues != null ? fieldValues.optString(FIELD_OPEN_CLOSE, null) : null;

    if (openCloseValue == null || openCloseValue.isBlank()) {
      return NeoResponse.error(400, "Missing required parameter: openClose");
    }

    String recordId = context.getRecordId();
    if (recordId == null || recordId.isBlank()) {
      return NeoResponse.error(400, "Missing recordId");
    }

    try {
      OBContext.setAdminMode();
      try {
        PeriodControl periodControl = OBDal.getInstance().get(PeriodControl.class, recordId);
        if (periodControl == null) {
          return NeoResponse.error(404, "PeriodControl not found: " + recordId);
        }

        // C_PeriodControl_Process reads PeriodAction directly from the DB record
        periodControl.setPeriodAction(openCloseValue);
        OBDal.getInstance().save(periodControl);
        OBDal.getInstance().flush();

        Process process168 = OBDal.getInstance().get(Process.class, PROCESS_168_ID);
        if (process168 == null) {
          return NeoResponse.error(500, "AD Process 168 (C_PeriodControl_Process) not found");
        }

        ProcessInstance pInstance = CallProcess.getInstance()
            .call(process168, recordId, null);

        OBDal.getInstance().getSession().refresh(pInstance);

        return translateResult(pInstance, process168);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error executing period control doc open/close for record {}", recordId, e);
      return NeoResponse.error(500, "Period control doc open/close failed: " + e.getMessage());
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  private static NeoResponse translateResult(ProcessInstance pInstance, Process process)
      throws Exception {
    JSONObject result = new JSONObject();
    long resultCode = pInstance.getResult() != null ? pInstance.getResult() : 0L;
    String errorMsg = pInstance.getErrorMsg();

    if (resultCode == 0L) {
      String cleanMsg = errorMsg != null
          ? errorMsg.replaceFirst("@ERROR=", "")
          : "Process failed";
      result.put("status", "error");
      result.put("message", cleanMsg);
      return new NeoResponse(400, result);
    }

    result.put("status", "success");
    if (errorMsg != null && !errorMsg.isBlank()) {
      result.put("message", errorMsg.replaceFirst("@SUCCESS=", ""));
    } else {
      result.put("message", "Process " + process.getName() + " executed successfully");
    }
    return NeoResponse.ok(result);
  }
}
