package com.etendoerp.go.schemaforge.webhooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Tab;

import com.smf.webhookevents.process.WebhookHandler;

/**
 * Webhook handler to create or update an ETGO_SF_Entity record.
 *
 * Required params: SpecID, TabID, ModuleID
 * Optional params: Name, IsIncluded, IsGet, IsGetbyid, IsPost, IsPut, IsPatch, IsDelete,
 *                  JavaQualifier, EntityID (for update), SeqNo
 */
public class SFUpsertEntity extends WebhookHandler {

  private static final Logger log = LogManager.getLogger(SFUpsertEntity.class);

  @Override
  public void execute(JSONObject jsonContent) throws Exception {
    OBContext.setAdminMode();
    try {
      String entityId = jsonContent.optString("EntityID", null);
      String specId = jsonContent.getString("SpecID");
      String tabId = jsonContent.getString("TabID");
      String moduleId = jsonContent.getString("ModuleID");

      BaseOBObject entity;
      if (entityId != null && !entityId.isEmpty()) {
        entity = OBDal.getInstance().get("ETGO_SF_Entity", entityId);
        if (entity == null) {
          throw new IllegalArgumentException("Entity not found: " + entityId);
        }
      } else {
        entity = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Entity");
        entity.set("client", OBContext.getOBContext().getCurrentClient());
        entity.set("organization", OBContext.getOBContext().getCurrentOrganization());
        entity.set("active", true);
        // Set defaults for new records
        entity.set("included", true);
        entity.set("get", false);
        entity.set("getbyid", false);
        entity.set("post", false);
        entity.set("put", false);
        entity.set("patch", false);
        entity.set("delete", false);
      }

      // Set required FK references
      BaseOBObject spec = OBDal.getInstance().get("ETGO_SF_Spec", specId);
      if (spec == null) {
        throw new IllegalArgumentException("Spec not found: " + specId);
      }
      entity.set("etgoSfSpec", spec);

      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      if (tab == null) {
        throw new IllegalArgumentException("Tab not found: " + tabId);
      }
      entity.set("tab", tab);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        throw new IllegalArgumentException("Module not found: " + moduleId);
      }
      entity.set("module", module);

      // Optional fields
      if (jsonContent.has("Name")) {
        entity.set("name", jsonContent.getString("Name"));
      } else if (entityId == null || entityId.isEmpty()) {
        // Default name from tab for new records
        entity.set("name", tab.getName());
      }

      setYesNoIfPresent(entity, jsonContent, "IsIncluded", "included");
      setYesNoIfPresent(entity, jsonContent, "IsGet", "get");
      setYesNoIfPresent(entity, jsonContent, "IsGetbyid", "getbyid");
      setYesNoIfPresent(entity, jsonContent, "IsPost", "post");
      setYesNoIfPresent(entity, jsonContent, "IsPut", "put");
      setYesNoIfPresent(entity, jsonContent, "IsPatch", "patch");
      setYesNoIfPresent(entity, jsonContent, "IsDelete", "delete");

      if (jsonContent.has("JavaQualifier")) {
        entity.set("javaQualifier", jsonContent.getString("JavaQualifier"));
      }
      if (jsonContent.has("SeqNo")) {
        entity.set("sequenceNumber", (long) jsonContent.getInt("SeqNo"));
      }

      OBDal.getInstance().save(entity);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Entity: id={}", entity.getId());
      jsonContent.put("EntityID", entity.getId());

    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void setYesNoIfPresent(BaseOBObject obj, JSONObject json,
      String jsonKey, String dalProperty) throws Exception {
    if (json.has(jsonKey)) {
      String val = json.getString(jsonKey);
      obj.set(dalProperty, "Y".equalsIgnoreCase(val));
    }
  }
}
