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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.NeoProcessService.BUNDLE_RECORD_ID;
import static com.etendoerp.go.schemaforge.NeoProcessService.INP_TAB_ID;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;

/**
 * ETP-6407 — Scheduling processes read the record id from the table's key column (e.g.
 * {@code M_Inventory_ID} in {@code InventoryCountProcess}), not from {@code recordID}.
 */
final class NeoSchedulingKeyColumn {

  private static final Logger log = LogManager.getLogger(NeoSchedulingKeyColumn.class);

  private NeoSchedulingKeyColumn() {
  }

  /**
   * Adds {@code <KEY_COLUMN> -> recordID} to the bundle params, resolving the table only from
   * {@code inpTabId}. Never overwrites a value the caller sent and never fails the execution:
   * a resolution failure is logged at warn.
   */
  static void addKeyColumnParam(Map<String, Object> bundleParams, JSONObject params,
      Process adProcess) {
    Object recordId = bundleParams.get(BUNDLE_RECORD_ID);
    String tabId = params == null ? null : params.optString(INP_TAB_ID, null);
    if (recordId == null || StringUtils.isBlank(recordId.toString())
        || StringUtils.isBlank(tabId)) {
      return;
    }
    try {
      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      String keyColumn = findKeyColumnName(tab == null ? null : tab.getTable());
      if (keyColumn != null) {
        bundleParams.putIfAbsent(keyColumn, recordId);
      }
    } catch (Exception e) {
      log.warn("Could not resolve key column param for tab {}, process {}, record {}",
          tabId, adProcess == null ? null : adProcess.getId(), recordId, e);
    }
  }

  /** DB name of the table's key column (e.g. "M_Inventory_ID"), or null when it has none. */
  private static String findKeyColumnName(Table table) {
    if (table == null) {
      return null;
    }
    for (Column col : table.getADColumnList()) {
      if (Boolean.TRUE.equals(col.isKeyColumn())) {
        return col.getDBColumnName();
      }
    }
    return null;
  }
}
