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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
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
 * Webhook to create or update an ETGO_SF_Spec record.
 *
 * Required params: Name, ModuleID
 * Optional params: Description, SpecID (for update), SpecType (W or P, default W)
 * When SpecType=W (default): WindowID is required
 * When SpecType=P: ProcessID is required
 */
public class SFUpsertSpec extends BaseWebhookService {

  private static final String ERROR_KEY = "error";
  private static final String WINDOW_SPEC_TYPE = "W";
  private static final String PROCESS_SPEC_TYPE = "P";

  private static final Logger log = LogManager.getLogger(SFUpsertSpec.class);

  /**
   * Handles the webhook request to create or update an {@code ETGO_SF_Spec} record.
   * <p>
   * Reads the following parameters from {@code parameter}:
   * <ul>
   *   <li><b>Name</b> (required) – name of the spec.</li>
   *   <li><b>ModuleID</b> (required) – ID of the module that owns the spec.</li>
   *   <li><b>SpecType</b> (optional, default {@code W}) – {@code W} for Window or {@code P} for Process.</li>
   *   <li><b>SpecID</b> (optional) – when provided, updates the existing spec instead of creating one.</li>
   *   <li><b>WindowID</b> (required when SpecType is {@code W}).</li>
   *   <li><b>ProcessID</b> (required when SpecType is {@code P}).</li>
   *   <li><b>Description</b> (optional).</li>
   * </ul>
   * On success, {@code responseVars} will contain {@code message}, {@code SpecID} and {@code SpecType}.
   * On error, {@code responseVars} will contain {@code error} with a human-readable message.
   *
   * @param parameter    map of incoming webhook parameters.
   * @param responseVars map where the response values are written.
   */
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
      specType = normalizeSpecType(specType);

      if (!isValidSpecType(specType)) {
        putError(responseVars, "Invalid SpecType: " + specType + ". Must be W or P.");
        return;
      }

      SFSpec spec = loadSpec(specId, name, responseVars);
      if (spec == null) {
        return;
      }

      spec.setName(name);
      spec.setSpecType(specType);

      if (!applySpecType(spec, specType, processId, windowId, responseVars)) {
        return;
      }

      Module module = loadModule(moduleId, responseVars);
      if (module == null) {
        return;
      }
      spec.setADModule(module);

      if (description != null && !description.isEmpty()) {
        spec.setDescription(description);
      }

      OBDal.getInstance().save(spec);
      OBDal.getInstance().flush();

      String typeLabel = PROCESS_SPEC_TYPE.equals(specType) ? "Process" : "Window";
      log.info("Upserted ETGO_SF_Spec ({}): id={}, name={}", typeLabel, spec.getId(), name);
      responseVars.put("message", typeLabel + " Spec upserted with ID: " + spec.getId());
      responseVars.put("SpecID", spec.getId());
      responseVars.put("SpecType", specType);

    } catch (Exception e) {
      log.error("Error in SFUpsertSpec", e);
      putError(responseVars, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Returns {@code W} when {@code specType} is {@code null} or blank; otherwise returns the value as-is.
   *
   * @param specType raw spec type value from the request parameter.
   * @return the normalized spec type, defaulting to {@link #WINDOW_SPEC_TYPE}.
   */
  private String normalizeSpecType(String specType) {
    return (specType == null || specType.isEmpty()) ? WINDOW_SPEC_TYPE : specType;
  }

  /**
   * Returns {@code true} when {@code specType} is either {@code W} or {@code P}.
   *
   * @param specType the spec type to validate.
   * @return {@code true} if the spec type is supported.
   */
  private boolean isValidSpecType(String specType) {
    return WINDOW_SPEC_TYPE.equals(specType) || PROCESS_SPEC_TYPE.equals(specType);
  }

  /**
   * Loads an existing {@link SFSpec} by {@code specId}, or creates a new transient instance.
   * <p>
   * When {@code specId} is blank a duplicate-name check is performed; if a spec with the same name
   * already exists an error is written to {@code responseVars} and {@code null} is returned.
   *
   * @param specId       ID of the spec to update, or blank/null to create a new one.
   * @param name         name used for the duplicate check when creating.
   * @param responseVars map where error messages are written on failure.
   * @return the loaded or newly instantiated {@link SFSpec}, or {@code null} on error.
   */
  private SFSpec loadSpec(String specId, String name, Map<String, String> responseVars) {
    if (hasText(specId)) {
      SFSpec spec = OBDal.getInstance().get(SFSpec.class, specId);
      if (spec == null) {
        putError(responseVars, "Spec not found: " + specId);
      }
      return spec;
    }

    OBCriteria<SFSpec> dupCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
    dupCriteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, name));
    dupCriteria.setMaxResults(1);
    List<SFSpec> existing = dupCriteria.list();
    if (!existing.isEmpty()) {
      putError(responseVars, "A spec with name '" + name + "' already exists (ID: " + existing.get(0).getId() + ")");
      return null;
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

  /**
   * Delegates to {@link #applyProcessSpecType} or {@link #applyWindowSpecType} based on
   * {@code specType}.
   *
   * @param spec         the spec being populated.
   * @param specType     {@code W} or {@code P}.
   * @param processId    ID of the process (used when {@code specType} is {@code P}).
   * @param windowId     ID of the window (used when {@code specType} is {@code W}).
   * @param responseVars map where error messages are written on failure.
   * @return {@code true} if the spec type was applied successfully.
   */
  private boolean applySpecType(SFSpec spec, String specType, String processId, String windowId,
      Map<String, String> responseVars) {
    if (PROCESS_SPEC_TYPE.equals(specType)) {
      return applyProcessSpecType(spec, processId, responseVars);
    }
    return applyWindowSpecType(spec, windowId, responseVars);
  }

  /**
   * Assigns the {@link Process} identified by {@code processId} to {@code spec} and clears any
   * previously linked window.
   *
   * @param spec         the spec to update.
   * @param processId    ID of the process to link.
   * @param responseVars map where error messages are written on failure.
   * @return {@code true} if the process was found and linked successfully.
   */
  private boolean applyProcessSpecType(SFSpec spec, String processId, Map<String, String> responseVars) {
    if (!hasText(processId)) {
      putError(responseVars, "ProcessID is required when SpecType is P");
      return false;
    }

    Process process = OBDal.getInstance().get(Process.class, processId);
    if (process == null) {
      putError(responseVars, "Process not found: " + processId);
      return false;
    }
    spec.setProcess(process);
    spec.setADWindow(null);
    return true;
  }

  /**
   * Assigns the {@link Window} identified by {@code windowId} to {@code spec} and clears any
   * previously linked process.
   *
   * @param spec         the spec to update.
   * @param windowId     ID of the window to link.
   * @param responseVars map where error messages are written on failure.
   * @return {@code true} if the window was found and linked successfully.
   */
  private boolean applyWindowSpecType(SFSpec spec, String windowId, Map<String, String> responseVars) {
    if (!hasText(windowId)) {
      putError(responseVars, "WindowID is required when SpecType is W");
      return false;
    }

    Window window = OBDal.getInstance().get(Window.class, windowId);
    if (window == null) {
      putError(responseVars, "Window not found: " + windowId);
      return false;
    }
    spec.setADWindow(window);
    spec.setProcess(null);
    return true;
  }

  /**
   * Loads the {@link Module} identified by {@code moduleId}.
   * Writes an error to {@code responseVars} and returns {@code null} when not found.
   *
   * @param moduleId     ID of the module to load.
   * @param responseVars map where error messages are written on failure.
   * @return the {@link Module}, or {@code null} if not found.
   */
  private Module loadModule(String moduleId, Map<String, String> responseVars) {
    Module module = OBDal.getInstance().get(Module.class, moduleId);
    if (module == null) {
      putError(responseVars, "Module not found: " + moduleId);
    }
    return module;
  }

  /**
   * Writes {@code message} into {@code responseVars} under the {@value #ERROR_KEY} key.
   *
   * @param responseVars map where the error message is stored.
   * @param message      human-readable description of the error.
   */
  private void putError(Map<String, String> responseVars, String message) {
    responseVars.put(ERROR_KEY, message);
  }

  /**
   * Returns {@code true} when {@code value} is neither {@code null} nor empty.
   *
   * @param value the string to check.
   * @return {@code true} if the string has content.
   */
  private boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }
}
