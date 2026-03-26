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
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook to create or update an ETGO_SF_Entity record.
 *
 * Required params: SpecID, TabID, ModuleID
 * Optional params: Name, IsIncluded, IsGet, IsGetbyid, IsPost, IsPut, IsPatch, IsDelete,
 *                  JavaQualifier, EntityID (for update), SeqNo
 */
public class SFUpsertEntity extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFUpsertEntity.class);
  private static final String ERROR_KEY = "error";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String entityId = parameter.get("EntityID");
      String specId = parameter.get("SpecID");
      String tabId = parameter.get("TabID");
      String moduleId = parameter.get("ModuleID");

      // Validate all referenced objects before creating or loading the entity.
      SFSpec spec = OBDal.getInstance().get(SFSpec.class, specId);
      if (spec == null) {
        responseVars.put(ERROR_KEY, "Spec not found: " + specId);
        return;
      }

      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      if (tab == null) {
        responseVars.put(ERROR_KEY, "Tab not found: " + tabId);
        return;
      }

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put(ERROR_KEY, "Module not found: " + moduleId);
        return;
      }

      boolean isNew = entityId == null || entityId.isEmpty();
      SFEntity entity;
      if (!isNew) {
        entity = OBDal.getInstance().get(SFEntity.class, entityId);
        if (entity == null) {
          responseVars.put(ERROR_KEY, "Entity not found: " + entityId);
          return;
        }
      } else {
        entity = createNewEntity();
      }

      entity.setETGOSFSpec(spec);
      entity.setADTab(tab);
      entity.setADModule(module);

      applyName(entity, parameter, tab, isNew);
      applyOptionalParams(entity, parameter);

      OBDal.getInstance().save(entity);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Entity: id={}", entity.getId());
      responseVars.put("message", "Entity upserted with ID: " + entity.getId());
      responseVars.put("EntityID", entity.getId());

    } catch (Exception e) {
      log.error("Error in SFUpsertEntity", e);
      responseVars.put(ERROR_KEY, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private SFEntity createNewEntity() {
    SFEntity entity = OBProvider.getInstance().get(SFEntity.class);
    entity.setNewOBObject(true);
    entity.setClient(OBContext.getOBContext().getCurrentClient());
    entity.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    entity.setActive(true);
    entity.setCreatedBy(OBContext.getOBContext().getUser());
    entity.setUpdatedBy(OBContext.getOBContext().getUser());
    entity.setCreationDate(new Date());
    entity.setUpdated(new Date());
    entity.setIncluded(true);
    entity.setGet(false);
    entity.setGetByID(false);
    entity.setPost(false);
    entity.setPut(false);
    entity.setPatch(false);
    entity.setDelete(false);
    return entity;
  }

  private void applyName(SFEntity entity, Map<String, String> parameter, Tab tab, boolean isNew) {
    if (parameter.containsKey("Name")) {
      entity.setName(parameter.get("Name"));
    } else if (isNew) {
      entity.setName(tab.getName());
    }
  }

  private void applyOptionalParams(SFEntity entity, Map<String, String> parameter) {
    if (parameter.containsKey("IsIncluded")) {
      entity.setIncluded("Y".equalsIgnoreCase(parameter.get("IsIncluded")));
    }
    if (parameter.containsKey("IsGet")) {
      entity.setGet("Y".equalsIgnoreCase(parameter.get("IsGet")));
    }
    if (parameter.containsKey("IsGetbyid")) {
      entity.setGetByID("Y".equalsIgnoreCase(parameter.get("IsGetbyid")));
    }
    if (parameter.containsKey("IsPost")) {
      entity.setPost("Y".equalsIgnoreCase(parameter.get("IsPost")));
    }
    if (parameter.containsKey("IsPut")) {
      entity.setPut("Y".equalsIgnoreCase(parameter.get("IsPut")));
    }
    if (parameter.containsKey("IsPatch")) {
      entity.setPatch("Y".equalsIgnoreCase(parameter.get("IsPatch")));
    }
    if (parameter.containsKey("IsDelete")) {
      entity.setDelete("Y".equalsIgnoreCase(parameter.get("IsDelete")));
    }
    if (parameter.containsKey("JavaQualifier")) {
      entity.setJavaQualifier(parameter.get("JavaQualifier"));
    }
    if (parameter.containsKey("SeqNo")) {
      entity.setSeqNo(Long.valueOf(parameter.get("SeqNo")));
    }
  }
}
