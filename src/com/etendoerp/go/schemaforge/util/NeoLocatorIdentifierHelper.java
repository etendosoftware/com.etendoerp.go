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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Enriches GET responses so that any field that is a foreign key to {@code M_Locator}
 * displays the parent warehouse name instead of the raw storage-bin identifier.
 *
 * <p>This is the generic, all-windows counterpart of {@link NeoListIdentifierHelper}: it
 * overwrites the {@code field$_identifier} entry (already produced by the default service with
 * the bin identifier) with the warehouse name resolved by {@link LocatorWarehouseResolver}.</p>
 *
 * <p>Fail-safe: any error leaves the response untouched.</p>
 */
public class NeoLocatorIdentifierHelper {

  private static final Logger log = LogManager.getLogger(NeoLocatorIdentifierHelper.class);

  private static final String IDENTIFIER_SUFFIX = "$_identifier";

  private NeoLocatorIdentifierHelper() {
  }

  /**
   * Post-processes a GET response, replacing the {@code $_identifier} of every locator FK field
   * with its parent warehouse name.
   *
   * @param responseJson the full JSON response from DefaultJsonDataService
   * @param sfEntity     the configured entity whose fields drive the enrichment
   */
  public static void enrichLocatorIdentifiers(JSONObject responseJson, SFEntity sfEntity) {
    try {
      if (sfEntity == null) {
        return;
      }
      Set<String> locatorProps = collectLocatorRefProps(sfEntity);
      if (locatorProps.isEmpty()) {
        return;
      }
      JSONObject inner = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
      if (inner == null) {
        return;
      }
      List<JSONObject> records = collectRecords(inner);
      if (records.isEmpty()) {
        return;
      }
      Set<String> rawIds = collectRawIds(records, locatorProps);
      if (rawIds.isEmpty()) {
        return;
      }
      Map<String, String> warehouseNames = LocatorWarehouseResolver.resolveNames(rawIds);
      if (warehouseNames.isEmpty()) {
        return;
      }
      applyWarehouseNames(records, locatorProps, warehouseNames);
    } catch (Exception e) {
      log.debug("Error enriching locator identifiers: {}", e.getMessage());
    }
  }

  /**
   * Collects the DAL property names of all included locator FK fields for the given entity.
   */
  private static Set<String> collectLocatorRefProps(SFEntity sfEntity) {
    Set<String> props = new HashSet<>();
    Tab adTab = sfEntity.getADTab();
    if (adTab == null || adTab.getTable() == null) {
      return props;
    }
    org.openbravo.base.model.Entity dalEnt = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEnt == null) {
      return props;
    }
    OBCriteria<SFField> sfFieldCrit = OBDal.getInstance().createCriteria(SFField.class);
    sfFieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    sfFieldCrit.setFilterOnReadableClients(false);
    sfFieldCrit.setFilterOnReadableOrganization(false);
    for (SFField sfField : sfFieldCrit.list()) {
      Column col = sfField.getADColumn();
      boolean skip = !Boolean.TRUE.equals(sfField.isIncluded()) || col == null
          || !LocatorWarehouseResolver.isLocatorRef(col);
      if (skip) {
        continue;
      }
      Property prop = dalEnt.getPropertyByColumnName(col.getDBColumnName());
      if (prop != null) {
        props.add(prop.getName());
      }
    }
    return props;
  }

  private static List<JSONObject> collectRecords(JSONObject inner) {
    List<JSONObject> records = new ArrayList<>();
    JSONArray dataArray = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
    if (dataArray != null) {
      for (int i = 0; i < dataArray.length(); i++) {
        JSONObject jsonRecord = dataArray.optJSONObject(i);
        if (jsonRecord != null) {
          records.add(jsonRecord);
        }
      }
    } else {
      JSONObject single = inner.optJSONObject(JsonConstants.RESPONSE_DATA);
      if (single != null) {
        records.add(single);
      }
    }
    return records;
  }

  private static Set<String> collectRawIds(List<JSONObject> records, Set<String> locatorProps) {
    Set<String> ids = new HashSet<>();
    for (JSONObject jsonRecord : records) {
      for (String propName : locatorProps) {
        String rawValue = jsonRecord.optString(propName, null);
        if (rawValue != null && !rawValue.isEmpty()) {
          ids.add(rawValue);
        }
      }
    }
    return ids;
  }

  private static void applyWarehouseNames(List<JSONObject> records, Set<String> locatorProps,
      Map<String, String> warehouseNames) {
    for (JSONObject jsonRecord : records) {
      try {
        for (String propName : locatorProps) {
          String rawValue = jsonRecord.optString(propName, null);
          if (rawValue == null || rawValue.isEmpty()) {
            continue;
          }
          String warehouseName = warehouseNames.get(rawValue);
          if (warehouseName != null) {
            jsonRecord.put(propName + IDENTIFIER_SUFFIX, warehouseName);
          }
        }
      } catch (Exception e) {
        log.debug("Error applying warehouse name to record: {}", e.getMessage());
      }
    }
  }
}
