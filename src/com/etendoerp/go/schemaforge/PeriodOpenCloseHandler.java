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

import java.util.UUID;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControlLog;
import org.openbravo.service.db.CallProcess;

/**
 * NeoHandler for the {@code periodControl} entity (C_Period records).
 *
 * <p>Intercepts the {@code openClose} ACTION endpoint. When the user selects an action
 * (Open / Close / Permanently Close) in the process parameter dialog, this handler:
 *
 * <ol>
 *   <li>Reads the chosen action code from the request body ({@code fieldValues.openClose}).</li>
 *   <li>Creates a {@code C_PeriodControl_Log} row with the action and period context.</li>
 *   <li>Calls stored procedure {@code C_Period_Process} (AD Process 167) passing the log ID
 *       as the {@code AD_PInstance.Record_ID}.</li>
 *   <li>Returns the translated process result.</li>
 * </ol>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'period-openclose'} on the
 * {@code periodControl} ETGO_SF_ENTITY record for the
 * {@code open-close-period-control} spec.
 */
@Named("period-openclose")
public class PeriodOpenCloseHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PeriodOpenCloseHandler.class);

  private static final String PROCESS_167_ID = "167";
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
        Period period = OBDal.getInstance().get(Period.class, recordId);
        if (period == null) {
          return NeoResponse.error(404, "Period not found: " + recordId);
        }

        // Create the C_PeriodControl_Log entry that C_Period_Process reads.
        // setPeriodNo stores the C_Period FK (the DAL property "periodNo" maps to
        // the Periodno column which is a FK, despite the name).
        PeriodControlLog logEntry = OBProvider.getInstance().get(PeriodControlLog.class);
        logEntry.setNewOBObject(true);
        logEntry.setId(UUID.randomUUID().toString().toUpperCase().replace("-", ""));
        logEntry.setClient(period.getClient());
        logEntry.setOrganization(period.getOrganization());
        logEntry.setActive(true);
        // cascade / isRecursive — true so the process handles sub-orgs
        logEntry.setCascade(true);
        logEntry.setYear(period.getYear());
        logEntry.setCalendar(period.getYear().getCalendar());
        logEntry.setPeriodNo(period);
        logEntry.setPeriodAction(openCloseValue);
        logEntry.setPeriod(period);
        // documentCategory null means "all document types"
        OBDal.getInstance().save(logEntry);
        OBDal.getInstance().flush();

        Process process167 = OBDal.getInstance().get(Process.class, PROCESS_167_ID);
        if (process167 == null) {
          return NeoResponse.error(500, "AD Process 167 (C_Period_Process) not found");
        }

        ProcessInstance pInstance = CallProcess.getInstance()
            .call(process167, logEntry.getId(), null);

        OBDal.getInstance().getSession().refresh(pInstance);

        return translateResult(pInstance, process167);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error executing period open/close for period {}", recordId, e);
      return NeoResponse.error(500, "Period open/close failed: " + e.getMessage());
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
