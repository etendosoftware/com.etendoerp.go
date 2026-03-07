package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Tab;

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

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String entityId = parameter.get("EntityID");
      String specId = parameter.get("SpecID");
      String tabId = parameter.get("TabID");
      String moduleId = parameter.get("ModuleID");

      BaseOBObject entity;
      if (entityId != null && !entityId.isEmpty()) {
        entity = OBDal.getInstance().get("ETGO_SF_Entity", entityId);
        if (entity == null) {
          responseVars.put("error", "Entity not found: " + entityId);
          return;
        }
      } else {
        entity = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Entity");
        entity.set("client", OBContext.getOBContext().getCurrentClient());
        entity.set("organization", OBContext.getOBContext().getCurrentOrganization());
        entity.set("active", true);
        entity.set("included", true);
        entity.set("get", false);
        entity.set("getbyid", false);
        entity.set("post", false);
        entity.set("put", false);
        entity.set("patch", false);
        entity.set("delete", false);
      }

      BaseOBObject spec = OBDal.getInstance().get("ETGO_SF_Spec", specId);
      if (spec == null) {
        responseVars.put("error", "Spec not found: " + specId);
        return;
      }
      entity.set("etgoSfSpec", spec);

      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      if (tab == null) {
        responseVars.put("error", "Tab not found: " + tabId);
        return;
      }
      entity.set("tab", tab);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put("error", "Module not found: " + moduleId);
        return;
      }
      entity.set("module", module);

      if (parameter.containsKey("Name")) {
        entity.set("name", parameter.get("Name"));
      } else if (entityId == null || entityId.isEmpty()) {
        entity.set("name", tab.getName());
      }

      setYesNoIfPresent(entity, parameter, "IsIncluded", "included");
      setYesNoIfPresent(entity, parameter, "IsGet", "get");
      setYesNoIfPresent(entity, parameter, "IsGetbyid", "getbyid");
      setYesNoIfPresent(entity, parameter, "IsPost", "post");
      setYesNoIfPresent(entity, parameter, "IsPut", "put");
      setYesNoIfPresent(entity, parameter, "IsPatch", "patch");
      setYesNoIfPresent(entity, parameter, "IsDelete", "delete");

      if (parameter.containsKey("JavaQualifier")) {
        entity.set("javaQualifier", parameter.get("JavaQualifier"));
      }
      if (parameter.containsKey("SeqNo")) {
        entity.set("sequenceNumber", Long.parseLong(parameter.get("SeqNo")));
      }

      OBDal.getInstance().save(entity);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Entity: id={}", entity.getId());
      responseVars.put("message", "Entity upserted with ID: " + entity.getId());
      responseVars.put("EntityID", (String) entity.getId());

    } catch (Exception e) {
      log.error("Error in SFUpsertEntity", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void setYesNoIfPresent(BaseOBObject obj, Map<String, String> params,
      String paramKey, String dalProperty) {
    if (params.containsKey(paramKey)) {
      obj.set(dalProperty, "Y".equalsIgnoreCase(params.get(paramKey)));
    }
  }
}
