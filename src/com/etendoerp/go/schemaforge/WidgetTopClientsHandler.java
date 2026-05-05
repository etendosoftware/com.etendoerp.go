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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

/**
 * NeoHandler that returns the top 10 clients by revenue for the requested date range.
 * When no range is supplied it falls back to the last 12 months anchored to the most
 * recent invoice date so demo/test databases with stale data still return results.
 */
@Named("widgetTopClientsHandler")
public class WidgetTopClientsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetTopClientsHandler.class);


  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy =
        WidgetQueryPolicyRegistry.topClients();
    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();
        Map<String, String> params = context.getQueryParams();
        String range = params != null ? params.get("range") : null;
        List<Object[]> rows = WidgetQueryHelper.resolveQuery(
            policy.fallbackSql, policy.rangedSql, clientId, range);

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("name", String.valueOf(row[0]));
          item.put("total", ((BigDecimal) row[1]).doubleValue());
          data.put(item);
        }

        return WidgetQueryHelper.buildDataResponse(data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching top clients", e);
      return NeoResponse.error(500, "Top clients handler failed: " + e.getMessage());
    }
  }

}
