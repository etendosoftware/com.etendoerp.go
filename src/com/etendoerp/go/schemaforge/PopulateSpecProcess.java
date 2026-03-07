package com.etendoerp.go.schemaforge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.structure.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
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

  private static final Set<String> SYSTEM_COLUMNS = new HashSet<>(Arrays.asList(
      "AD_CLIENT_ID", "AD_ORG_ID", "ISACTIVE",
      "CREATED", "CREATEDBY", "UPDATED", "UPDATEDBY"
  ));

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
