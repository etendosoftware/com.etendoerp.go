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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

/**
 * Loads parent record values used by child-tab default expressions.
 */
final class NeoParentValuesLoader {

  private static final Logger log = LogManager.getLogger(NeoParentValuesLoader.class);

  private NeoParentValuesLoader() {
  }

  static Map<String, Object> load(Tab adTab, String parentId) {
    Map<String, Object> parentValues = new HashMap<>();
    if (!canLoad(adTab, parentId)) {
      return parentValues;
    }
    try {
      loadParentRecordValues(adTab, parentId, parentValues);
    } catch (Exception e) {
      log.debug("Could not load parent record for defaults", e);
    }
    return parentValues;
  }

  private static boolean canLoad(Tab adTab, String parentId) {
    return adTab != null && parentId != null && !parentId.isEmpty()
        && adTab.getTabLevel() != null && adTab.getTabLevel() > 0;
  }

  private static void loadParentRecordValues(Tab adTab, String parentId,
      Map<String, Object> parentValues) {
    Tab parentTab = findParentTab(adTab);
    Entity parentEntity = resolveParentEntity(parentTab);
    BaseOBObject parentRecord = resolveParentRecord(parentEntity, parentId);
    if (parentRecord == null) {
      return;
    }
    copyParentValues(parentEntity, parentRecord, parentValues);
    log.debug("Loaded {} parent values from {} for child defaults",
        parentValues.size(), parentEntity.getName());
  }

  private static Tab findParentTab(Tab adTab) {
    Long currentLevel = adTab.getTabLevel();
    Long currentSeqNo = adTab.getSequenceNumber();
    if (adTab.getWindow() == null || currentLevel == null || currentSeqNo == null) {
      return null;
    }
    return adTab.getWindow().getADTabList().stream()
        .filter(t -> t.isActive()
            && t.getTabLevel() != null && t.getTabLevel().equals(currentLevel - 1)
            && t.getSequenceNumber() != null && t.getSequenceNumber() < currentSeqNo)
        .max(Comparator.comparing(Tab::getSequenceNumber))
        .orElse(null);
  }

  private static Entity resolveParentEntity(Tab parentTab) {
    if (parentTab == null || parentTab.getTable() == null) {
      return null;
    }
    return ModelProvider.getInstance().getEntityByTableId(parentTab.getTable().getId());
  }

  private static BaseOBObject resolveParentRecord(Entity parentEntity, String parentId) {
    if (parentEntity == null) {
      return null;
    }
    return OBDal.getInstance().get(parentEntity.getName(), parentId);
  }

  private static void copyParentValues(Entity parentEntity, BaseOBObject parentRecord,
      Map<String, Object> parentValues) {
    for (Property property : parentEntity.getProperties()) {
      copyParentValue(parentRecord, property, parentValues);
    }
  }

  private static void copyParentValue(BaseOBObject parentRecord, Property property,
      Map<String, Object> parentValues) {
    Object value = parentRecord.get(property.getName());
    String columnName = property.getColumnName();
    if (value == null || columnName == null) {
      return;
    }
    parentValues.put(columnName.toUpperCase(Locale.ROOT), normalizeValue(value));
  }

  private static Object normalizeValue(Object value) {
    return value instanceof BaseOBObject ? ((BaseOBObject) value).getId().toString() : value;
  }
}
