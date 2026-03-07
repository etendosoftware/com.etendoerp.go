package com.etendoerp.go.schemaforge.webhooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.PopulateSpecHelper;
import com.smf.webhookevents.process.WebhookHandler;

/**
 * Webhook handler that populates ETGO_SF_Entity and ETGO_SF_Field records
 * for a given ETGO_SF_Spec. Same logic as PopulateSpecProcess but invoked
 * via webhook.
 *
 * Required params: SpecID, ModuleID
 * Optional params: IncludeAllMethods (Y/N, default N),
 *                  ExcludeSystemColumns (Y/N, default Y)
 */
public class SFPopulateSpec extends WebhookHandler {

  private static final Logger log = LogManager.getLogger(SFPopulateSpec.class);

  @Override
  public void execute(JSONObject jsonContent) throws Exception {
    OBContext.setAdminMode();
    try {
      String specId = jsonContent.getString("SpecID");
      // ModuleID is documented as required but populate uses the spec's own module
      // We validate it exists but the helper reads from the spec record
      String moduleId = jsonContent.getString("ModuleID");

      boolean includeAllMethods = "Y".equalsIgnoreCase(
          jsonContent.optString("IncludeAllMethods", "N"));
      boolean excludeSystemColumns = !"N".equalsIgnoreCase(
          jsonContent.optString("ExcludeSystemColumns", "Y"));

      int[] counts = PopulateSpecHelper.populate(specId, excludeSystemColumns, includeAllMethods);

      OBDal.getInstance().flush();

      log.info("Webhook populated spec {}: {} entities, {} fields",
          specId, counts[0], counts[1]);

      // Return counts in the response
      jsonContent.put("EntitiesCreated", counts[0]);
      jsonContent.put("FieldsCreated", counts[1]);

    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
