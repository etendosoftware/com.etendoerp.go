package com.etendoerp.go.schemaforge.webhooks;

import java.util.Date;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook to create or update an ETGO_SF_Spec record.
 *
 * Required params: Name, ModuleID
 * Optional params: Description, SpecID (for update), SpecType (W or P, default W)
 * When SpecType=W (default): WindowID is required
 * When SpecType=P: ProcessID is required
 */
public class SFUpsertSpec extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFUpsertSpec.class);

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      OBContext ctx = OBContext.getOBContext();
      log.info("SFUpsertSpec session info: user={}, client={} ({}), org={} ({}), role={} ({})",
        ctx.getUser().getId(),
        ctx.getCurrentClient().getId(), ctx.getCurrentClient().getName(),
        ctx.getCurrentOrganization().getId(), ctx.getCurrentOrganization().getName(),
        ctx.getRole().getId(), ctx.getRole().getName());

      String specId = parameter.get("SpecID");
      String name = parameter.get("Name");
      String windowId = parameter.get("WindowID");
      String processId = parameter.get("ProcessID");
      String moduleId = parameter.get("ModuleID");
      String description = parameter.get("Description");
      String specType = parameter.get("SpecType");
      if (specType == null || specType.isEmpty()) {
        specType = "W";
      }

      if (!"W".equals(specType) && !"P".equals(specType)) {
        responseVars.put("error", "Invalid SpecType: " + specType + ". Must be W or P.");
        return;
      }

      SFSpec spec;
      if (specId != null && !specId.isEmpty()) {
        spec = OBDal.getInstance().get(SFSpec.class, specId);
        if (spec == null) {
          responseVars.put("error", "Spec not found: " + specId);
          return;
        }
      } else {
        spec =  OBProvider.getInstance().get(SFSpec.class);
        spec.setNewOBObject(true);
        spec.setClient(OBContext.getOBContext().getCurrentClient());
        spec.setOrganization(OBContext.getOBContext().getCurrentOrganization());
        spec.setActive(true);
        spec.setCreatedBy(OBContext.getOBContext().getUser());
        spec.setUpdatedBy(OBContext.getOBContext().getUser());
        spec.setCreated(new Date());
        spec.setUpdated(new Date());
      }

      spec.setName(name);
      spec.setSpecType(specType);

      if ("P".equals(specType)) {
        if (processId == null || processId.isEmpty()) {
          responseVars.put("error", "ProcessID is required when SpecType is P");
          return;
        }
        Process process = OBDal.getInstance().get(Process.class, processId);
        if (process == null) {
          responseVars.put("error", "Process not found: " + processId);
          return;
        }
        spec.setProcess(process);
        spec.setADWindow(null);
      } else {
        if (windowId == null || windowId.isEmpty()) {
          responseVars.put("error", "WindowID is required when SpecType is W");
          return;
        }
        Window window = OBDal.getInstance().get(Window.class, windowId);
        if (window == null) {
          responseVars.put("error", "Window not found: " + windowId);
          return;
        }
        spec.setADWindow(window);
        spec.setProcess(null);
      }

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put("error", "Module not found: " + moduleId);
        return;
      }
      spec.setADModule(module);

      if (description != null && !description.isEmpty()) {
        spec.setDescription(description);
      }

      OBDal.getInstance().save(spec);
      OBDal.getInstance().flush();

      String typeLabel = "P".equals(specType) ? "Process" : "Window";
      log.info("Upserted ETGO_SF_Spec ({}): id={}, name={}", typeLabel, spec.getId(), name);
      responseVars.put("message", typeLabel + " Spec upserted with ID: " + spec.getId());
      responseVars.put("SpecID", spec.getId());
      responseVars.put("SpecType", specType);

    } catch (Exception e) {
      log.error("Error in SFUpsertSpec", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
