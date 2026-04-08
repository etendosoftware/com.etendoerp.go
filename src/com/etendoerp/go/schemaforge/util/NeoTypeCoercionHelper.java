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

  /**
   * Wraps a filtered JSON body in the SmartClient format expected by the DAL servlet.
   * <p>
   * The body is coerced to the correct field types for the given entity, enriched with
   * the entity name and – when provided – the record identifier, then wrapped in a
   * top-level {@code data} object.
   *
   * @param filteredBody  the request body as a {@link JSONObject}, or {@code null} to use an empty object
   * @param dalEntityName the DAL entity name used for type coercion
   * @param recordId      the record identifier to embed, or {@code null} for new records
   * @return the wrapped JSON string, or {@code "{}"} if an error occurs
   */
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

  /**
   * Coerces the string values in {@code data} to their correct primitive Java types
   * according to the DAL entity model.
   * <p>
   * Only fields whose DAL {@link Property} is primitive and whose value is a plain
   * {@link String} are processed; all other fields are left unchanged.
   *
   * @param data          the {@link JSONObject} whose field values should be converted in place
   * @param dalEntityName the DAL entity name used to look up property types
   */
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

  /**
   * Attempts to coerce a single string field value to its correct primitive type and
   * stores the result in {@code coerced}.
   * <p>
   * Currently handles {@link java.math.BigDecimal} and {@link Long} properties. If the
   * property is not found, is not primitive, or the conversion fails the field is silently
   * skipped.
   *
   * @param entity  the DAL {@link Entity} that owns the field
   * @param key     the field / property name
   * @param strVal  the raw string value to convert
   * @param coerced accumulator map where successfully coerced values are placed
   */
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

  /**
   * Builds an HQL {@code WHERE} clause fragment that constrains the child entity by its
   * parent record.
   * <p>
   * The clause is constructed from the parent-property relationship defined between
   * {@code childTab} and its immediate parent tab. Returns {@code null} when the
   * relationship cannot be determined or an error occurs.
   *
   * @param childTab the child {@link Tab} whose parent relationship is resolved
   * @param parentId the identifier of the parent record
   * @return an HQL fragment such as {@code e.someProperty.id='...'}, or {@code null}
   */
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
