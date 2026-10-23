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

package com.etendoerp.go.hubspot;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

/**
 * Background process "HubSpot Sync": reports account lifecycle events to HubSpot
 * ({@link HubSpotSyncService}).
 *
 * <p>Meant to run every minute for the System client. Like the trial notices it is scheduled by
 * hand from Classic ({@code Process Request}): production is updated by delta and never restarted,
 * so a startup hook would never run there. A pass is idempotent; running it twice sends nothing
 * twice.
 */
public class HubSpotSyncProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(HubSpotSyncProcess.class);

  /** {@code AD_PROCESS_ID} of this process. */
  public static final String PROCESS_ID = "C7C6666AD9A24FFC92A426F9D0DC61F7";

  @Override
  protected void doExecute(ProcessBundle bundle) {
    OBContext.setAdminMode(true);
    try {
      HubSpotSyncService.Result result = newService().run(Instant.now());
      OBError message = new OBError();
      message.setType(result.getFailed() > 0 ? "Warning" : "Success");
      message.setTitle("HubSpot Sync");
      message.setMessage(result.toString());
      bundle.setResult(message);
    } catch (RuntimeException e) {
      log.error("HubSpot sync failed", e);
      OBError message = new OBError();
      message.setType("Error");
      message.setTitle("HubSpot Sync");
      message.setMessage(String.valueOf(e.getMessage()));
      bundle.setResult(message);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  HubSpotSyncService newService() {
    return new HubSpotSyncService();
  }
}
