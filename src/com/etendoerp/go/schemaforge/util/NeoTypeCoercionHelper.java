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

package com.etendoerp.go.schemaforge.util;

import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.client.application.ApplicationUtils;

/**
 * Static helpers for JSON type coercion and SmartClient wrapping.
 */
public final class NeoTypeCoercionHelper {

  private static final Logger log = LogManager.getLogger(NeoTypeCoercionHelper.class);

  private NeoTypeCoercionHelper() {
  }

  public static String wrapForSmartclient(JSONObject filteredBody, String dalEntityName,
      String recordId) {
    try {
      JSONObject data = filteredBody != null ? filteredBody : new JSONObject();
      coerceTypes(data, dalEntityName);
      data.put(JsonConstants.ENTITYNAME, dalEntityName);
      if (recordId != null) {
        data.put(JsonConstants.ID, recordId);
      } else {
        data.put(JsonConstants.NEW_INDICATOR, true);
      }
      JSONObject wrapper = new JSONObject();
      wrapper.put(JsonConstants.DATA, data);
      return wrapper.toString();
    } catch (Exception e) {
      log.error("Error wrapping body for Smartclient format: {}", e.getMessage(), e);
      return "{}";
    }
  }

  @SuppressWarnings("unchecked")
  public static void coerceTypes(JSONObject data, String dalEntityName) {
    try {
      Entity entity = ModelProvider.getInstance().getEntity(dalEntityName);
      if (entity == null) {
        return;
      }
      Map<String, Object> coerced = new java.util.HashMap<>();
      Iterator<String> keys = data.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object val = data.opt(key);
        if (val instanceof String) {
          coerceField(entity, key, (String) val, coerced);
        }
      }
      for (Map.Entry<String, Object> entry : coerced.entrySet()) {
        data.put(entry.getKey(), entry.getValue());
      }
      if (!coerced.isEmpty()) {
        log.info("[NEO] coerceTypes: converted {} fields for {}: {}", coerced.size(),
            dalEntityName, coerced.keySet());
      }
    } catch (Exception e) {
      log.error("Error coercing types for {}: {}", dalEntityName, e.getMessage(), e);
    }
  }

  public static void coerceField(Entity entity, String key, String strVal,
      Map<String, Object> coerced) {
    try {
      Property prop = entity.getProperty(key);
      if (prop != null && prop.isPrimitive()) {
        Class<?> type = prop.getPrimitiveObjectType();
        if (type != null && java.math.BigDecimal.class.isAssignableFrom(type) && !strVal.isEmpty()) {
          coerced.put(key, new java.math.BigDecimal(strVal));
        } else if (type != null && Long.class.isAssignableFrom(type) && !strVal.isEmpty()) {
          coerced.put(key, Long.parseLong(strVal));
        }
      }
    } catch (Exception ignored) {
      // Not a DAL property or not primitive — skip
    }
  }

  public static String buildParentWhereClause(Tab childTab, String parentId) {
    if (childTab == null) {
      return null;
    }
    try {
      Tab parentTab = KernelUtils.getInstance().getParentTab(childTab);
      if (parentTab == null) {
        return null;
      }
      String parentProperty = ApplicationUtils.getParentProperty(childTab, parentTab);
      if (StringUtils.isBlank(parentProperty)) {
        return null;
      }
      Entity childEntity = ModelProvider.getInstance()
          .getEntityByTableId(childTab.getTable().getId());
      Property prop = childEntity.getProperty(parentProperty);
      if (prop != null && !prop.isPrimitive()) {
        return "e." + parentProperty + ".id='" + parentId.replace("'", "''") + "'";
      } else {
        return "e." + parentProperty + "='" + parentId.replace("'", "''") + "'";
      }
    } catch (Exception e) {
      log.error("Error building parent where clause for tab '{}': {}",
          childTab.getName(), e.getMessage(), e);
      return null;
    }
  }
}
