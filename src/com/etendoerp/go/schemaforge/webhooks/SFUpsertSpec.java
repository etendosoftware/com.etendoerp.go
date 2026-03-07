package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook to create or update an ETGO_SF_Spec record.
 *
 * Required params: Name, WindowID, ModuleID
 * Optional params: Description, SpecID (for update)
 */
public class SFUpsertSpec extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFUpsertSpec.class);

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String specId = parameter.get("SpecID");
      String name = parameter.get("Name");
      String windowId = parameter.get("WindowID");
      String moduleId = parameter.get("ModuleID");
      String description = parameter.get("Description");

      BaseOBObject spec;
      if (specId != null && !specId.isEmpty()) {
        spec = OBDal.getInstance().get("ETGO_SF_Spec", specId);
        if (spec == null) {
          responseVars.put("error", "Spec not found: " + specId);
          return;
        }
      } else {
        spec = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Spec");
        spec.set("client", OBContext.getOBContext().getCurrentClient());
        spec.set("organization", OBContext.getOBContext().getCurrentOrganization());
        spec.set("active", true);
      }

      spec.set("name", name);

      Window window = OBDal.getInstance().get(Window.class, windowId);
      if (window == null) {
        responseVars.put("error", "Window not found: " + windowId);
        return;
      }
      spec.set("window", window);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put("error", "Module not found: " + moduleId);
        return;
      }
      spec.set("module", module);

      if (description != null && !description.isEmpty()) {
        spec.set("description", description);
      }

      OBDal.getInstance().save(spec);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Spec: id={}, name={}", spec.getId(), name);
      responseVars.put("message", "Spec upserted with ID: " + spec.getId());
      responseVars.put("SpecID", (String) spec.getId());

    } catch (Exception e) {
      log.error("Error in SFUpsertSpec", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
