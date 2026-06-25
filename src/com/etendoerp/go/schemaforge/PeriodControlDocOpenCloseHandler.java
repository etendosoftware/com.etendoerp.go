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

  @Override
  public NeoResponse handle(NeoContext context) {
    PeriodOpenCloseSupport.OpenCloseRequest req = PeriodOpenCloseSupport.parse(context);
    if (req.isAbort()) {
      return req.toHandlerReturn();
    }

    String openCloseValue = req.openCloseValue;
    String recordId = req.recordId;

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

        ProcessInstance pInstance = CallProcess.getInstance().call(process168, recordId, null);
        OBDal.getInstance().getSession().refresh(pInstance);

        return PeriodOpenCloseSupport.translateResult(pInstance, process168);
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
}
