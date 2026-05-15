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
 * NeoHandler that returns the 10 most recent completed sales invoices for the requested date range.
 * When no range is supplied it falls back to the last 30 days anchored to the most
 * recent invoice date so demo/test databases with stale data still return results.
 */
@Named("widgetRecentInvoicesHandler")
public class WidgetRecentInvoicesHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetRecentInvoicesHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    WidgetQueryPolicyRegistry.WidgetQueryPolicy policy = WidgetQueryPolicyRegistry.recentInvoices();
    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();
        Map<String, String> params = context.getQueryParams();
        String range = params != null ? params.get("range") : null;
        List<Object[]> rows = WidgetQueryHelper.resolveQuery(policy.fallbackSql, policy.rangedSql, clientId, range);

        // Columns: [0]=c_invoice_id, [1]=documentno, [2]=client, [3]=date, [4]=amount, [5]=status
        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          String id = String.valueOf(row[0]);
          JSONObject navigation = new JSONObject();
          navigation.put("type", "record");
          navigation.put("window", "sales-invoice");
          navigation.put("recordId", id);

          JSONObject item = new JSONObject();
          item.put("id", id);
          item.put("documentNo", row[1] != null ? String.valueOf(row[1]) : "");
          item.put("client", row[2] != null ? String.valueOf(row[2]) : "");
          item.put("date", row[3] != null ? String.valueOf(row[3]) : "");
          item.put("amount", row[4] != null ? ((BigDecimal) row[4]).doubleValue() : 0);
          item.put("status", row[5] != null ? String.valueOf(row[5]) : "");
          item.put("navigation", navigation);
          data.put(item);
        }

        return WidgetQueryHelper.buildDataResponse(data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching recent invoices", e);
      return NeoResponse.error(500, "Recent invoices handler failed: " + e.getMessage());
    }
  }
}
