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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * Injects the latest TBAI sync status ({@code tbaiSyncEstado}) into a NEO list response.
 *
 * <p>Reads from {@code tbai_syncinvoice} using {@code DISTINCT ON (c_invoice_id) ORDER BY created DESC}
 * so only one SQL round-trip is needed for the whole page. If the TBAI module is not installed the
 * table will not exist; the exception is caught and logged at DEBUG level so the main response is
 * returned unmodified.
 */
class TbaiSyncStatusInjector {

  private static final Logger log = LogManager.getLogger(TbaiSyncStatusInjector.class);

  private TbaiSyncStatusInjector() {
  }

  /**
   * Injects {@code tbaiSyncEstado} into each record in {@code data} that has a matching row in
   * {@code tbai_syncinvoice}. Records with no TBAI sync row are left unchanged.
   *
   * @param data
   *     the {@code response.data} array from a NEO GET response; modified in-place
   */
  static void inject(JSONArray data) {
    try {
      List<String> ids = new ArrayList<>(data.length());
      for (int i = 0; i < data.length(); i++) {
        String id = data.getJSONObject(i).optString("id", null);
        if (id != null && !id.isEmpty()) {
          ids.add(id);
        }
      }
      if (ids.isEmpty()) {
        return;
      }
      Map<String, String> tbaiMap = fetchLatestByInvoice(ids);
      for (int i = 0; i < data.length(); i++) {
        JSONObject rec = data.getJSONObject(i);
        String id = rec.optString("id", null);
        if (id != null) {
          String estado = tbaiMap.get(id);
          if (estado != null) {
            rec.put("tbaiSyncEstado", estado);
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not inject tbaiSyncEstado (TBAI module may not be installed): {}", e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> fetchLatestByInvoice(List<String> invoiceIds) {
    String sql = "SELECT DISTINCT ON (c_invoice_id) c_invoice_id, estado "
        + "FROM tbai_syncinvoice "
        + "WHERE c_invoice_id IN (:invoiceIds) "
        + "ORDER BY c_invoice_id, created DESC";
    NativeQuery<Object[]> nq = OBDal.getInstance().getSession()
        .createNativeQuery(sql, Object[].class);
    nq.setParameterList("invoiceIds", invoiceIds);
    List<Object[]> rows = nq.list();
    Map<String, String> result = new HashMap<>(rows.size());
    for (Object[] row : rows) {
      if (row[0] != null) {
        result.put((String) row[0], row[1] != null ? (String) row[1] : null);
      }
    }
    return result;
  }
}
