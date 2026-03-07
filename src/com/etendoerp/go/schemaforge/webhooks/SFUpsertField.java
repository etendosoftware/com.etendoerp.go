package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.module.Module;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook to create or update an ETGO_SF_Field record.
 *
 * Required params: EntityID, ColumnID, ModuleID
 * Optional params: IsIncluded, IsReadOnly, DefaultValue, JavaQualifier,
 *                  FieldID (for update), SeqNo
 */
public class SFUpsertField extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFUpsertField.class);

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String fieldId = parameter.get("FieldID");
      String entityId = parameter.get("EntityID");
      String columnId = parameter.get("ColumnID");
      String moduleId = parameter.get("ModuleID");

      BaseOBObject field;
      if (fieldId != null && !fieldId.isEmpty()) {
        field = OBDal.getInstance().get("ETGO_SF_Field", fieldId);
        if (field == null) {
          responseVars.put("error", "Field not found: " + fieldId);
          return;
        }
      } else {
        field = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Field");
        field.set("client", OBContext.getOBContext().getCurrentClient());
        field.set("organization", OBContext.getOBContext().getCurrentOrganization());
        field.set("active", true);
        field.set("included", true);
        field.set("readOnly", false);
      }

      BaseOBObject entity = OBDal.getInstance().get("ETGO_SF_Entity", entityId);
      if (entity == null) {
        responseVars.put("error", "Entity not found: " + entityId);
        return;
      }
      field.set("etgoSfEntity", entity);

      Column column = OBDal.getInstance().get(Column.class, columnId);
      if (column == null) {
        responseVars.put("error", "Column not found: " + columnId);
        return;
      }
      field.set("column", column);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put("error", "Module not found: " + moduleId);
        return;
      }
      field.set("module", module);

      if (parameter.containsKey("IsIncluded")) {
        field.set("included", "Y".equalsIgnoreCase(parameter.get("IsIncluded")));
      }
      if (parameter.containsKey("IsReadOnly")) {
        field.set("readOnly", "Y".equalsIgnoreCase(parameter.get("IsReadOnly")));
      }
      if (parameter.containsKey("DefaultValue")) {
        field.set("defaultValue", parameter.get("DefaultValue"));
      }
      if (parameter.containsKey("JavaQualifier")) {
        field.set("javaQualifier", parameter.get("JavaQualifier"));
      }
      if (parameter.containsKey("SeqNo")) {
        field.set("sequenceNumber", Long.parseLong(parameter.get("SeqNo")));
      }

      OBDal.getInstance().save(field);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Field: id={}", field.getId());
      responseVars.put("message", "Field upserted with ID: " + field.getId());
      responseVars.put("FieldID", (String) field.getId());

    } catch (Exception e) {
      log.error("Error in SFUpsertField", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
