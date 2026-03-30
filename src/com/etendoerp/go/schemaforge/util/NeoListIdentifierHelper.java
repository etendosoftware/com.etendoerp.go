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

import java.util.HashMap;
import java.util.Map;

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

import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Utility methods for enriching GET responses with human-readable labels for
 * List reference fields (AD_Reference type 17).
 *
 * <p>Without enrichment the frontend receives raw search keys (e.g. {@code "GENERIC"})
 * instead of display names (e.g. {@code "Use Generic Account No."}). This mirrors
 * how FK fields already receive {@code $_{identifier}} labels from DefaultJsonDataService.</p>
 */
public class NeoListIdentifierHelper {

  private static final Logger log = LogManager.getLogger(NeoListIdentifierHelper.class);

  private NeoListIdentifierHelper() {
  }

  /**
   * Post-processes a GET response to add {@code field$_identifier} entries for List reference
   * fields (AD_Reference type 17).
   *
   * <p>A per-request cache keyed by referenceId avoids repeated DB queries for the same list
   * when the response contains multiple records with the same field.</p>
   *
   * @param responseJson the full JSON response from DefaultJsonDataService
   * @param sfEntity     the configured entity whose fields drive the enrichment
   */
  public static void enrichListIdentifiers(JSONObject responseJson, SFEntity sfEntity) {
    try {
      if (sfEntity == null) {
        return;
      }

      Map<String, String> listRefFields = collectListRefFields(sfEntity);

      if (listRefFields.isEmpty()) {
        return;
      }

      // Cache: referenceId → Map<searchKey, label>
      Map<String, Map<String, String>> labelCache = new HashMap<>();

      JSONObject inner = responseJson.optJSONObject(JsonConstants.RESPONSE_RESPONSE);
      if (inner == null) {
        return;
      }
      JSONArray dataArray = inner.optJSONArray(JsonConstants.RESPONSE_DATA);
      if (dataArray != null) {
        for (int i = 0; i < dataArray.length(); i++) {
          JSONObject record = dataArray.optJSONObject(i);
          if (record != null) {
            addListIdentifiers(record, listRefFields, labelCache);
          }
        }
      } else {
        JSONObject singleRecord = inner.optJSONObject(JsonConstants.RESPONSE_DATA);
        if (singleRecord != null) {
          addListIdentifiers(singleRecord, listRefFields, labelCache);
        }
      }
    } catch (Exception e) {
      log.debug("Error enriching list identifiers: {}", e.getMessage());
    }
  }

  /**
   * Collects all List reference fields (AD_Reference type 17) for the given entity,
   * returning a map of DAL property name → AD_Reference_Value_ID.
   */
  private static Map<String, String> collectListRefFields(SFEntity sfEntity) {
    Map<String, String> listRefFields = new HashMap<>();
    OBCriteria<SFField> sfFieldCrit = OBDal.getInstance().createCriteria(SFField.class);
    sfFieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", sfEntity.getId()));
    sfFieldCrit.setFilterOnReadableClients(false);
    sfFieldCrit.setFilterOnReadableOrganization(false);
    Tab adTab = sfEntity.getADTab();
    if (adTab == null || adTab.getTable() == null) {
      return listRefFields;
    }
    org.openbravo.base.model.Entity dalEnt = ModelProvider.getInstance()
        .getEntityByTableName(adTab.getTable().getDBTableName());
    if (dalEnt == null) {
      return listRefFields;
    }
    for (SFField sfField : sfFieldCrit.list()) {
      addListRefField(sfField, dalEnt, listRefFields);
    }
    return listRefFields;
  }

  private static void addListRefField(SFField sfField,
      org.openbravo.base.model.Entity dalEnt,
      Map<String, String> listRefFields) {
    Column col = sfField.getADColumn();
    if (!Boolean.TRUE.equals(sfField.isIncluded()) || col == null) {
      return;
    }
    String refId = col.getReference() != null ? col.getReference().getId() : null;
    if (!"17".equals(refId)) {
      return;
    }
    Property prop = dalEnt.getPropertyByColumnName(col.getDBColumnName());
    if (prop == null) {
      return;
    }
    String listRefId = col.getReferenceSearchKey() != null
        ? col.getReferenceSearchKey().getId()
        : col.getReference().getId();
    listRefFields.put(prop.getName(), listRefId);
  }

  private static void addListIdentifiers(JSONObject record,
      Map<String, String> listRefFields,
      Map<String, Map<String, String>> labelCache) {
    try {
      for (Map.Entry<String, String> entry : listRefFields.entrySet()) {
        String propName = entry.getKey();
        String listRefId = entry.getValue();
        String rawValue = record.optString(propName, null);
        if (rawValue == null || rawValue.isEmpty()) {
          continue;
        }
        Map<String, String> labels = labelCache.computeIfAbsent(listRefId,
            id -> NeoSelectorService.getListLabels(id));
        String label = labels.get(rawValue);
        if (label != null) {
          record.put(propName + "$_identifier", label);
        }
      }
    } catch (Exception e) {
      log.debug("Error adding list identifiers to record: {}", e.getMessage());
    }
  }
}
