package com.etendoerp.go.schemaforge.webhooks;

import java.util.Date;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.module.Module;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
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

      SFField field;
      if (fieldId != null && !fieldId.isEmpty()) {
        field = OBDal.getInstance().get(SFField.class, fieldId);
        if (field == null) {
          responseVars.put("error", "Field not found: " + fieldId);
          return;
        }
      } else {
        field = new SFField();
        field.setNewOBObject(true);
        field.setClient(OBContext.getOBContext().getCurrentClient());
        field.setOrganization(OBContext.getOBContext().getCurrentOrganization());
        field.setActive(true);
        field.setCreatedBy(OBContext.getOBContext().getUser());
        field.setUpdatedBy(OBContext.getOBContext().getUser());
        field.setCreated(new Date());
        field.setUpdated(new Date());
        field.setIncluded(true);
        field.setReadOnly(false);
      }

      SFEntity entity = OBDal.getInstance().get(SFEntity.class, entityId);
      if (entity == null) {
        responseVars.put("error", "Entity not found: " + entityId);
        return;
      }
      field.setETGOSFEntity(entity);

      Column column = OBDal.getInstance().get(Column.class, columnId);
      if (column == null) {
        responseVars.put("error", "Column not found: " + columnId);
        return;
      }
      field.setADColumn(column);

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put("error", "Module not found: " + moduleId);
        return;
      }
      field.setADModule(module);

      if (parameter.containsKey("IsIncluded")) {
        field.setIncluded("Y".equalsIgnoreCase(parameter.get("IsIncluded")));
      }
      if (parameter.containsKey("IsReadOnly")) {
        field.setReadOnly("Y".equalsIgnoreCase(parameter.get("IsReadOnly")));
      }
      if (parameter.containsKey("DefaultValue")) {
        field.setDefaultValue(parameter.get("DefaultValue"));
      }
      if (parameter.containsKey("JavaQualifier")) {
        field.setJavaQualifier(parameter.get("JavaQualifier"));
      }
      if (parameter.containsKey("SeqNo")) {
        field.setSeqNo(Long.valueOf(parameter.get("SeqNo")));
      }

      OBDal.getInstance().save(field);
      OBDal.getInstance().flush();

      log.info("Upserted ETGO_SF_Field: id={}", field.getId());
      responseVars.put("message", "Field upserted with ID: " + field.getId());
      responseVars.put("FieldID", field.getId());

    } catch (Exception e) {
      log.error("Error in SFUpsertField", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
