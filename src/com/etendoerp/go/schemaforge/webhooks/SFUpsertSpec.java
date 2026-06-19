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

package com.etendoerp.go.schemaforge.webhooks;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.jspecify.annotations.NonNull;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook service to create or update an ETGO_SF_Spec record.
 * This represents the top-level API configuration for a Window or Process.
 * <p>
 * Required params: Name, ModuleID
 * Optional params: Description, AgentPrompt, SpecID (for update), SpecType (W or P, default W)
 * When SpecType=W (default): WindowID is required
 * When SpecType=P: ProcessID is required
 */
public class SFUpsertSpec extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFUpsertSpec.class);

  /**
   * Response variable key used to communicate error messages to the webhook caller.
   */
  private static final String ERROR = "error";

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
      String agentPrompt = parameter.get("AgentPrompt");
      String specType = getSpecType(parameter);

      if (!isValidSpecType(specType)) {
        throw new OBException("Invalid SpecType: " + specType + ". Must be W or P.");
      }

      SFSpec spec = loadOrCreateSpec(specId, name);

      spec.setName(name);
      spec.setSpecType(specType);

      if (isProcess(specType)) {
        assignProcess(processId, spec);
      } else {
        assignWindow(windowId, spec);
      }

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        throw new OBException("Module not found: " + moduleId);
      }
      spec.setADModule(module);

      if (description != null && !description.isEmpty()) {
        spec.setDescription(description);
      }

      if (agentPrompt != null) {
        spec.setAgentPrompt(StringUtils.trimToNull(agentPrompt));
      }

      OBDal.getInstance().save(spec);
      OBDal.getInstance().flush();

      String typeLabel = getSpecTypeLabel(specType);
      log.info("Upserted ETGO_SF_Spec ({}): id={}, name={}", typeLabel, spec.getId(), name);
      responseVars.put("message", typeLabel + " Spec upserted with ID: " + spec.getId());
      responseVars.put("SpecID", spec.getId());
      responseVars.put("SpecType", specType);

    } catch (Exception e) {
      log.error("Error in SFUpsertSpec", e);
      responseVars.put(ERROR, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Load the existing spec by id, or create a new one (rejecting a duplicate name).
   */
  private static SFSpec loadOrCreateSpec(String specId, String name) {
    if (!StringUtils.isEmpty(specId)) {
      SFSpec spec = OBDal.getInstance().get(SFSpec.class, specId);
      if (spec == null) {
        throw new OBException("Spec not found: " + specId);
      }
      return spec;
    }

    // Check for duplicate name
    OBCriteria<SFSpec> dupCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
    dupCriteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, name));
    dupCriteria.setMaxResults(1);
    List<SFSpec> existing = dupCriteria.list();
    if (!existing.isEmpty()) {
      throw new OBException("A spec with name '" + name + "' already exists (ID: " + existing.get(0).getId() + ")");
    }

    SFSpec spec = OBProvider.getInstance().get(SFSpec.class);
    spec.setNewOBObject(true);
    spec.setClient(OBContext.getOBContext().getCurrentClient());
    spec.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    spec.setActive(true);
    spec.setCreatedBy(OBContext.getOBContext().getUser());
    spec.setUpdatedBy(OBContext.getOBContext().getUser());
    spec.setCreationDate(new Date());
    spec.setUpdated(new Date());
    return spec;
  }

  private static void assignWindow(String windowId, SFSpec spec) {
    if (StringUtils.isEmpty(windowId)) {
      throw new OBException("WindowID is required when SpecType is W");
    }
    Window window = OBDal.getInstance().get(Window.class, windowId);
    if (window == null) {
      throw new OBException("Window not found: " + windowId);
    }
    spec.setADWindow(window);
    spec.setProcess(null);
  }

  private static void assignProcess(String processId, SFSpec spec) {
    if (StringUtils.isEmpty(processId)) {
      throw new OBException("ProcessID is required when SpecType is P");
    }
    Process process = OBDal.getInstance().get(Process.class, processId);
    if (process == null) {
      throw new OBException("Process not found: " + processId);
    }
    spec.setProcess(process);
    spec.setADWindow(null);
  }

  private static @NonNull String getSpecTypeLabel(String specType) {
    return isProcess(specType) ? "Process" : "Window";
  }

  private static boolean isValidSpecType(String specType) {
    return isWindow(specType) || isProcess(specType);
  }

  private static boolean isWindow(String specType) {
    return StringUtils.equals(specType, "W");
  }

  private static boolean isProcess(String specType) {
    return StringUtils.equals(specType, "P");
  }

  private static String getSpecType(Map<String, String> parameter) {
    String specType = parameter.get("SpecType");
    if (specType == null || specType.isEmpty()) {
      specType = "W";
    }
    return specType;
  }
}
