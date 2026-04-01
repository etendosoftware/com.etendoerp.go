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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

/**
 * AD_Process triggered by the "Populate" button on the ETGO_SF_Spec tab.
 * Reads the linked AD_Window, finds all its tabs and columns,
 * and creates ETGO_SF_Entity and ETGO_SF_Field records accordingly.
 * Running it again deletes existing children and re-creates them.
 */
public class PopulateSpecProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(PopulateSpecProcess.class);

  @Override
  public void doExecute(ProcessBundle bundle) throws Exception {
    OBContext.setAdminMode();
    try {
      String specId = (String) bundle.getParams().get("ETGO_SF_Spec_ID");
      if (specId == null) {
        specId = (String) bundle.getParams().get("Etgo_SF_Spec_ID");
      }
      if (specId == null) {
        throw new IllegalArgumentException("Missing ETGO_SF_Spec_ID parameter");
      }

      int[] counts = PopulateSpecHelper.populate(specId, true);

      OBDal.getInstance().flush();

      OBError result = new OBError();
      result.setType("Success");
      result.setTitle("Populate Complete");
      result.setMessage("Created " + counts[0] + " entities and " + counts[1] + " fields");
      bundle.setResult(result);

    } catch (Exception e) {
      log.error("Error in PopulateSpecProcess", e);
      OBDal.getInstance().rollbackAndClose();
      OBError error = new OBError();
      error.setType("Error");
      error.setTitle("Populate Failed");
      error.setMessage(e.getMessage());
      bundle.setResult(error);
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
