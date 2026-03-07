package com.etendoerp.go.schemaforge.webhooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Window;

import com.smf.webhookevents.process.WebhookHandler;

/**
 * Webhook handler to create or update an ETGO_SF_Spec record.
 *
 * Required params: Name, WindowID, ModuleID
 * Optional params: Description, SpecID (for update)
 */
public class SFUpsertSpec extends WebhookHandler {

  private static final Logger log = LogManager.getLogger(SFUpsertSpec.class);

  @Override
  public void execute(JSONObject jsonContent) throws Exception {
    OBContext.setAdminMode();
    try {
      String specId = jsonContent.optString("SpecID", null);
      String name = jsonContent.getString("Name");
      String windowId = jsonContent.getString("WindowID");
      String moduleId = jsonContent.getString("ModuleID");
      String description = jsonContent.optString("Description", null);

      BaseOBObject spec;
      if (specId != null && !specId.isEmpty()) {
        // Update existing
        spec = OBDal.getInstance().get("ETGO_SF_Spec", specId);
        if (spec == null) {
          throw new IllegalArgumentException("Spec not found: " + specId);
        }
      } else {
        // Create new
        spec = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Spec");
        spec.set("client", OBContext.getOBContext().getCurrentClient());
        spec.set("organization", OBContext.getOBContext().getCurrentOrganization());
        spec.set("active", true);
      }

      spec.set("name", name);

      Window window = OBDal.getInstance().get(Window.class, windowId);
      if (window == null) {
        throw new IllegalArgumentException("Window not found: " + windowId);
      }
      spec.set("window", window);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        throw new IllegalArgumentException("Module not found: " + moduleId);
      }
      spec.set("module", module);

      if (description != null && !description.isEmpty()) {
        spec.set("description", description);
      }

      OBDal.getInstance().save(spec);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Spec: id={}, name={}", spec.getId(), name);

      // Return the created/updated ID in the response
      jsonContent.put("SpecID", spec.getId());

    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
