package com.etendoerp.go.schemaforge.webhooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.structure.Column;

import com.smf.webhookevents.process.WebhookHandler;

/**
 * Webhook handler to create or update an ETGO_SF_Field record.
 *
 * Required params: EntityID, ColumnID, ModuleID
 * Optional params: IsIncluded, IsReadOnly, DefaultValue, JavaQualifier,
 *                  FieldID (for update), SeqNo
 */
public class SFUpsertField extends WebhookHandler {

  private static final Logger log = LogManager.getLogger(SFUpsertField.class);

  @Override
  public void execute(JSONObject jsonContent) throws Exception {
    OBContext.setAdminMode();
    try {
      String fieldId = jsonContent.optString("FieldID", null);
      String entityId = jsonContent.getString("EntityID");
      String columnId = jsonContent.getString("ColumnID");
      String moduleId = jsonContent.getString("ModuleID");

      BaseOBObject field;
      if (fieldId != null && !fieldId.isEmpty()) {
        field = OBDal.getInstance().get("ETGO_SF_Field", fieldId);
        if (field == null) {
          throw new IllegalArgumentException("Field not found: " + fieldId);
        }
      } else {
        field = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Field");
        field.set("client", OBContext.getOBContext().getCurrentClient());
        field.set("organization", OBContext.getOBContext().getCurrentOrganization());
        field.set("active", true);
        field.set("included", true);
        field.set("readOnly", false);
      }

      // Set required FK references
      BaseOBObject entity = OBDal.getInstance().get("ETGO_SF_Entity", entityId);
      if (entity == null) {
        throw new IllegalArgumentException("Entity not found: " + entityId);
      }
      field.set("etgoSfEntity", entity);

      Column column = OBDal.getInstance().get(Column.class, columnId);
      if (column == null) {
        throw new IllegalArgumentException("Column not found: " + columnId);
      }
      field.set("column", column);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        throw new IllegalArgumentException("Module not found: " + moduleId);
      }
      field.set("module", module);

      // Optional fields
      if (jsonContent.has("IsIncluded")) {
        field.set("included", "Y".equalsIgnoreCase(jsonContent.getString("IsIncluded")));
      }
      if (jsonContent.has("IsReadOnly")) {
        field.set("readOnly", "Y".equalsIgnoreCase(jsonContent.getString("IsReadOnly")));
      }
      if (jsonContent.has("DefaultValue")) {
        field.set("defaultValue", jsonContent.getString("DefaultValue"));
      }
      if (jsonContent.has("JavaQualifier")) {
        field.set("javaQualifier", jsonContent.getString("JavaQualifier"));
      }
      if (jsonContent.has("SeqNo")) {
        field.set("sequenceNumber", (long) jsonContent.getInt("SeqNo"));
      }

      OBDal.getInstance().save(field);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Field: id={}", field.getId());
      jsonContent.put("FieldID", field.getId());

    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
