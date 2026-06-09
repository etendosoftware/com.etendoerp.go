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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

/**
 * One-off AD_Process that populates the {@code EM_ETGO_*} aggregate columns of
 * every existing {@link FIN_BankStatement}. New statements maintain the columns
 * automatically (write flows + line observer), but rows that predate the columns
 * stay NULL until this runs — so it should be executed once right after the
 * deploy that adds the columns.
 *
 * <p>Iterates the active statements in pages, flushing and clearing the DAL
 * session every {@link #BATCH_SIZE} rows to keep memory flat on large datasets.
 * The line observer is suppressed for the run: each statement is recomputed once
 * via {@link BankStatementAggregates#recompute}.
 */
public class BackfillBankStatementAggregatesProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(BackfillBankStatementAggregatesProcess.class);

  private static final int BATCH_SIZE = 100;

  @Override
  public void doExecute(ProcessBundle bundle) throws Exception {
    OBContext.setAdminMode();
    BankStatementLineAggregateHandler.suppress();
    int processed = 0;
    try {
      OBCriteria<FIN_BankStatement> crit =
          OBDal.getInstance().createCriteria(FIN_BankStatement.class);
      crit.add(org.hibernate.criterion.Restrictions.eq(FIN_BankStatement.PROPERTY_ACTIVE, true));
      crit.setFilterOnReadableOrganization(false);
      crit.addOrderBy(FIN_BankStatement.PROPERTY_ID, true);

      for (FIN_BankStatement st : crit.list()) {
        BankStatementAggregates.recompute(st);
        processed++;
        if (processed % BATCH_SIZE == 0) {
          OBDal.getInstance().flush();
          OBDal.getInstance().getSession().clear();
          log.debug("Backfilled {} bank statements so far", processed);
        }
      }
      OBDal.getInstance().flush();

      OBError result = new OBError();
      result.setType("Success");
      result.setTitle("Backfill Complete");
      result.setMessage("Recomputed aggregates for " + processed + " bank statements");
      bundle.setResult(result);

    } catch (Exception e) {
      log.error("Error in BackfillBankStatementAggregatesProcess after {} statements", processed, e);
      OBDal.getInstance().rollbackAndClose();
      OBError error = new OBError();
      error.setType("Error");
      error.setTitle("Backfill Failed");
      error.setMessage(e.getMessage());
      bundle.setResult(error);
    } finally {
      BankStatementLineAggregateHandler.resume();
      OBContext.restorePreviousMode();
    }
  }
}
