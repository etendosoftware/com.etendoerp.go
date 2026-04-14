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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;

/**
 * NeoHandler for the {@code internalConsumptionLine} entity.
 *
 * <p>Post-processes the storage bin (M_Locator_ID) selector response to display the
 * parent warehouse name instead of the locator's own identifier. This keeps the UX
 * consistent: users select by warehouse, not by raw bin key.
 *
 * <p>All other endpoints pass through to the default service unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'internalConsumptionLineHandler'} on
 * ETGO_SF_ENTITY record {@code 1EB67B71AE6445F787649951DFAEE661}.
 */
@Named("internalConsumptionLineHandler")
public class InternalConsumptionLineHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(InternalConsumptionLineHandler.class);

  /** DB column name used in the selector URL path: /selectors/M_Locator_ID */
  private static final String SELECTOR_FIELD_STORAGE_BIN = "M_Locator_ID";
  private static final String FIELD_ITEMS = "items";
  private static final String FIELD_LABEL = "label";

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.SELECTOR) {
      return null;
    }
    if (!SELECTOR_FIELD_STORAGE_BIN.equals(context.getFieldName())) {
      return null;
    }
    try {
      JSONObject body = context.getPreviousResult().getBody();
      JSONArray items = body.optJSONArray(FIELD_ITEMS);
      if (items == null || items.length() == 0) {
        return null;
      }

      List<String> locatorIds = collectIds(items);
      Map<String, String> warehouseNames = resolveWarehouseNames(locatorIds);
      replaceLabels(items, warehouseNames);

      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching storage bin selector with warehouse names", e);
      return null;
    }
  }

  private static List<String> collectIds(JSONArray items) throws JSONException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      String id = items.getJSONObject(i).optString("id");
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static Map<String, String> resolveWarehouseNames(List<String> locatorIds) {
    Map<String, String> result = new HashMap<>();
    if (locatorIds.isEmpty()) {
      return result;
    }
    OBContext.setAdminMode();
    try {
      List<Locator> locators = OBDal.getInstance().createCriteria(Locator.class)
          .add(Restrictions.in("id", locatorIds))
          .list();
      for (Locator loc : locators) {
        if (loc.getWarehouse() != null) {
          result.put(loc.getId(), loc.getWarehouse().getIdentifier());
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }

  private static void replaceLabels(JSONArray items, Map<String, String> warehouseNames)
      throws JSONException {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      String warehouseName = warehouseNames.get(item.optString("id"));
      if (warehouseName != null) {
        item.put(FIELD_LABEL, warehouseName);
      }
    }
  }
}
