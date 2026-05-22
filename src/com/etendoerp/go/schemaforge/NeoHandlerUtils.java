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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.erpCommon.utility.CSResponse;
import org.openbravo.erpCommon.utility.DocumentNoData;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Shared helpers for {@link NeoHandler} implementations.
 */
final class NeoHandlerUtils {

  private NeoHandlerUtils() {}

  /**
   * Extracts {@code response.data} from a GET response context.
   *
   * Returns null when: the request is not GET, the previous result or its body is absent,
   * the response wrapper is missing, or the data array is null/empty.
   * When non-null is returned, {@code context.getPreviousResult().getBody()} is guaranteed non-null.
   */
  static JSONArray extractGetDataArray(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    JSONObject responseWrapper = prev.getBody().optJSONObject("response");
    if (responseWrapper == null) {
      return null;
    }
    JSONArray dataArr = responseWrapper.optJSONArray("data");
    return (dataArr == null || dataArr.length() == 0) ? null : dataArr;
  }

  /**
   * Fetches the next document number for an M_InOut (or similar) record.
   *
   * <p>Tries the doctype-based sequence first, then falls back to the table-level sequence.
   * Returns {@code null} when no sequence resolves.
   *
   * @param docTypeId   document type ID, or empty/null to skip doctype lookup
   * @param clientId    client ID for the sequence namespace
   * @param tableDbName DB table name used as key {@code "DocumentNo_<tableDbName>"}
   * @param log         caller's logger for debug messages
   */
  static String fetchDocumentNo(String docTypeId, String clientId, String tableDbName, Logger log) {
    DalConnectionProvider conn = new DalConnectionProvider(false);
    if (docTypeId != null && !docTypeId.isEmpty()) {
      try {
        CSResponse cs = DocumentNoData.nextDocType(conn, docTypeId, clientId, "Y");
        if (cs != null && StringUtils.isNotBlank(cs.razon)) {
          return cs.razon;
        }
      } catch (Exception ex) {
        log.debug("nextDocType failed for doctype {}: {}", docTypeId, ex.getMessage());
      }
    }
    try {
      CSResponse cs = DocumentNoData.nextDoc(conn, "DocumentNo_" + tableDbName, clientId, "Y");
      if (cs != null && StringUtils.isNotBlank(cs.razon)) {
        return cs.razon;
      }
    } catch (Exception ex) {
      log.debug("nextDoc fallback failed for table {}: {}", tableDbName, ex.getMessage());
    }
    return null;
  }

  /**
   * Collects non-blank {@code id} values from a JSON array of records.
   */
  static List<String> collectIds(JSONArray dataArr) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }
}
