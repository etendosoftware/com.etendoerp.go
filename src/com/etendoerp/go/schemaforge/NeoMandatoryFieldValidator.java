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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

/**
 * Finds user-submitted mandatory fields that still have no value after default injection.
 */
public final class NeoMandatoryFieldValidator {

  private static final Logger log = LogManager.getLogger(NeoMandatoryFieldValidator.class);
  private static final Set<String> SAFE_PRIMITIVE_REFS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("22", "29", "12", "11", "20")));

  private NeoMandatoryFieldValidator() {
  }

  /**
   * Finds mandatory fields that were submitted by the user and still have no value after
   * default injection and callout cascade.
   *
   * @param body                request payload after defaults injection
   * @param adTab               AD tab being saved
   * @param userSubmittedFields DAL property names present in the original request, or null to
   *                            check all mandatory fields
   * @return missing mandatory DAL property names
   */
  public static List<String> findMissingMandatoryFields(JSONObject body, Tab adTab,
      Set<String> userSubmittedFields) {
    List<String> missing = new ArrayList<>();
    if (body == null || adTab == null || adTab.getTable() == null) {
      return missing;
    }
    try {
      Entity dalEntity = ModelProvider.getInstance()
          .getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) {
        return missing;
      }
      collectMissingFields(body, adTab, userSubmittedFields, missing, dalEntity);
    } catch (Exception e) {
      log.error("Error checking missing mandatory fields for tab {}", adTab.getName(), e);
      throw new OBException("Error checking missing mandatory fields", e);
    }
    return missing;
  }

  /**
   * Finds all mandatory fields that still have no value after default injection and callout
   * cascade.
   *
   * @param body  request payload after defaults injection
   * @param adTab AD tab being saved
   * @return missing mandatory DAL property names
   */
  public static List<String> findMissingMandatoryFields(JSONObject body, Tab adTab) {
    return findMissingMandatoryFields(body, adTab, null);
  }

  private static void collectMissingFields(JSONObject body, Tab adTab,
      Set<String> userSubmittedFields, List<String> missing, Entity dalEntity) {
    for (Column column : adTab.getTable().getADColumnList()) {
      Property property = resolveMandatoryProperty(column, dalEntity);
      if (property == null) {
        continue;
      }
      String propertyName = property.getName();
      if (shouldCheckSubmittedField(userSubmittedFields, propertyName)
          && isMissingValue(body, propertyName)) {
        missing.add(propertyName);
      }
    }
  }

  private static Property resolveMandatoryProperty(Column column, Entity dalEntity) {
    if (!column.isActive() || !column.isMandatory()
        || Boolean.TRUE.equals(column.isKeyColumn()) || isSafePrimitive(column)) {
      return null;
    }
    Property property = dalEntity.getPropertyByColumnName(column.getDBColumnName());
    if (property == null || property.isAuditInfo()) {
      return null;
    }
    return property;
  }

  private static boolean isSafePrimitive(Column column) {
    String referenceId = column.getReference() != null ? column.getReference().getId() : null;
    return SAFE_PRIMITIVE_REFS.contains(referenceId);
  }

  private static boolean shouldCheckSubmittedField(Set<String> userSubmittedFields,
      String propertyName) {
    return userSubmittedFields == null || userSubmittedFields.contains(propertyName);
  }

  private static boolean isMissingValue(JSONObject body, String propertyName) {
    if (!body.has(propertyName)) {
      return true;
    }
    Object value = body.opt(propertyName);
    return value == null || JSONObject.NULL.equals(value)
        || (value instanceof String && ((String) value).trim().isEmpty());
  }
}
