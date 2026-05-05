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
 * NeoHandler that returns the top 10 best-selling products by revenue for the requested
 * date range. When no range is supplied it ranks by all-time revenue (original behaviour).
 */
@Named("widgetBestProductsHandler")
public class WidgetBestProductsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetBestProductsHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy =
        WidgetQueryPolicyRegistry.bestProducts();
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
          item.put("id",     row[0]);
          item.put("name",   row[1]);
          item.put("qty",    ((BigDecimal) row[2]).longValue());
          item.put("amount", ((BigDecimal) row[3]).doubleValue());
          if (row[4] != null) item.put("trendPct", ((BigDecimal) row[4]).intValue());
          data.put(item);
        }

        return WidgetQueryHelper.buildDataResponse(data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching best products", e);
      return NeoResponse.error(500, "Best products handler failed: " + e.getMessage());
    }
  }

}
