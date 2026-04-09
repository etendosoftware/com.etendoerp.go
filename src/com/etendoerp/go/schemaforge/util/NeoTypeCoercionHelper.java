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
   * Wraps a filtered request body in the SmartClient envelope format expected by the Openbravo
   * DataSource layer, applying type coercion to numeric fields and injecting the entity name
   * and record ID (or new-record indicator) into the payload.
   *
   * @param filteredBody  the pre-filtered {@link JSONObject} containing the record fields to wrap;
   *                      may be {@code null}, in which case an empty object is used
   * @param dalEntityName the DAL entity name used to look up property types for coercion
   * @param recordId      the existing record ID to inject as {@code id}; if {@code null}, a
   *                      {@code _new} indicator is injected instead
   * @return the serialized SmartClient-wrapped JSON string, or {@code "{}"} on error
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
   * Coerces string values in the given JSON object to their correct Java types ({@link java.math.BigDecimal}
   * or {@link Long}) based on the DAL entity's property metadata, modifying the object in place.
   * Fields that are not primitive DAL properties are left unchanged.
   *
   * @param data          the {@link JSONObject} whose string values should be coerced; modified in place
   * @param dalEntityName the DAL entity name used to resolve property types via {@link ModelProvider}
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
   * Attempts to coerce a single string field value to its proper numeric type
   * ({@link java.math.BigDecimal} or {@link Long}) using the property metadata of the given entity,
   * placing the coerced value into the {@code coerced} map if conversion is applicable.
   *
   * @param entity  the DAL {@link Entity} used to look up the property by name
   * @param key     the field name to look up and potentially coerce
   * @param strVal  the raw string value to convert
   * @param coerced the accumulator map into which the coerced entry is placed when conversion succeeds
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
   * Builds an HQL {@code WHERE} clause fragment that filters child records by their parent
   * reference, resolving the parent-link property from the AD tab hierarchy.
   *
   * @param childTab the child {@link Tab} whose parent relationship should be resolved;
   *                 if {@code null}, returns {@code null}
   * @param parentId the ID of the parent record to filter by; single quotes are escaped
   * @return an HQL predicate string such as {@code "e.salesOrder.id='123'"} or
   *         {@code "e.salesOrder='123'"} depending on whether the property is an association,
   *         or {@code null} if the parent tab or parent property cannot be resolved
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
