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
  private static final String ERROR_KEY = "error";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String fieldId = parameter.get("FieldID");
      String entityId = parameter.get("EntityID");
      String columnId = parameter.get("ColumnID");
      String moduleId = parameter.get("ModuleID");

      // Validate all referenced objects before creating or loading the field.
      SFEntity entity = OBDal.getInstance().get(SFEntity.class, entityId);
      if (entity == null) {
        responseVars.put(ERROR_KEY, "Entity not found: " + entityId);
        return;
      }

      Column column = OBDal.getInstance().get(Column.class, columnId);
      if (column == null) {
        responseVars.put(ERROR_KEY, "Column not found: " + columnId);
        return;
      }

      Module module = OBDal.getInstance().get(Module.class, moduleId);
      if (module == null) {
        responseVars.put(ERROR_KEY, "Module not found: " + moduleId);
        return;
      }

      SFField field;
      if (fieldId != null && !fieldId.isEmpty()) {
        field = OBDal.getInstance().get(SFField.class, fieldId);
        if (field == null) {
          responseVars.put(ERROR_KEY, "Field not found: " + fieldId);
          return;
        }
      } else {
        field = OBProvider.getInstance().get(SFField.class);
        field.setNewOBObject(true);
        field.setClient(OBContext.getOBContext().getCurrentClient());
        field.setOrganization(OBContext.getOBContext().getCurrentOrganization());
        field.setActive(true);
        field.setCreatedBy(OBContext.getOBContext().getUser());
        field.setUpdatedBy(OBContext.getOBContext().getUser());
        field.setCreationDate(new Date());
        field.setUpdated(new Date());
        field.setIncluded(true);
        field.setReadOnly(false);
      }

      field.setETGOSFEntity(entity);
      field.setADColumn(column);
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
      responseVars.put(ERROR_KEY, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
