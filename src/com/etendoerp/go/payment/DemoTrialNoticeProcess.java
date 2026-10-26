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

package com.etendoerp.go.payment;

import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

/**
 * Background process "Demo Trial Notices" (ETP-5453): emails each demo owner a few days before
 * the trial ends and once it has ended.
 *
 * <p>Meant to run once a day for the System client. It is scheduled by hand from Classic
 * ({@code Process Request}), not at startup: production is updated by delta and never restarted,
 * so a startup hook would never run there. A run is idempotent, so running it again by hand, or
 * twice on one day, sends nothing new. Some
 * environments failing makes the run a warning, not an error: the others were still notified and
 * the failed ones are retried on the next run.</p>
 */
public class DemoTrialNoticeProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(DemoTrialNoticeProcess.class);

  /** {@code AD_PROCESS_ID} of this process. */
  public static final String PROCESS_ID = "303424FC361648B1B037877361E416FB";

  @Override
  protected void doExecute(ProcessBundle bundle) {
    OBContext.setAdminMode(true);
    try {
      DemoTrialNoticeService.Result result = newService().run(Instant.now());
      OBError message = new OBError();
      message.setType(result.getFailed() > 0 ? "Warning" : "Success");
      message.setTitle("Demo Trial Notices");
      message.setMessage(result.toString());
      bundle.setResult(message);
    } catch (RuntimeException e) {
      log.error("Demo trial notices failed", e);
      OBDal.getInstance().rollbackAndClose();
      OBError error = new OBError();
      error.setType("Error");
      error.setTitle("Demo Trial Notices Failed");
      error.setMessage(String.valueOf(e.getMessage()));
      bundle.setResult(error);
      // A scheduled run is recorded as an error only when the exception escapes; see
      // UsageAggregationProcess#fail for why setting the result alone is not enough.
      throw new OBException(e.getMessage(), e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Visible for testing. */
  DemoTrialNoticeService newService() {
    return new DemoTrialNoticeService();
  }
}
