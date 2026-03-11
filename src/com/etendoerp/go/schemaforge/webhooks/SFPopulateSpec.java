package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.PopulateSpecHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that populates ETGO_SF_Entity and ETGO_SF_Field records
 * for a given ETGO_SF_Spec. Same logic as PopulateSpecProcess but invoked
 * via webhook.
 *
 * Required params: SpecID, ModuleID
 * Optional params: IncludeAllMethods (Y/N, default N),
 *                  ExcludeSystemColumns (Y/N, default Y)
 */
public class SFPopulateSpec extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFPopulateSpec.class);

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String specId = parameter.get("SpecID");

      boolean includeAllMethods = "Y".equalsIgnoreCase(
          parameter.getOrDefault("IncludeAllMethods", "N"));
      boolean excludeSystemColumns = !"N".equalsIgnoreCase(
          parameter.getOrDefault("ExcludeSystemColumns", "Y"));

      int[] counts = PopulateSpecHelper.populate(specId, excludeSystemColumns, includeAllMethods);

      OBDal.getInstance().flush();

      log.info("Webhook populated spec {}: {} entities, {} fields",
          specId, counts[0], counts[1]);

      responseVars.put("message",
          "Populated " + counts[0] + " entities and " + counts[1] + " fields");
      responseVars.put("EntitiesCreated", String.valueOf(counts[0]));
      responseVars.put("FieldsCreated", String.valueOf(counts[1]));

    } catch (Exception e) {
      log.error("Error in SFPopulateSpec", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
