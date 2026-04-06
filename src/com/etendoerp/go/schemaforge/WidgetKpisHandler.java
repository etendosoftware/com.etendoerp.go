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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

/**
 * NeoHandler that returns KPI summary data for the dashboard widget.
 */
@Named("widgetKpisHandler")
public class WidgetKpisHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetKpisHandler.class);

  /** KPI format type for monetary/currency values. */
  private static final String CURRENCY = "currency";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      JSONArray data = new JSONArray();
      data.put(kpi("revenueThisMonth", "Revenue this month", 48250, CURRENCY, 12.5, "DollarSign"));
      data.put(kpi("expensesThisMonth", "Expenses this month", 31800, CURRENCY, 3.2, "CreditCard"));
      data.put(kpi("netProfit", "Net Profit", 16450, CURRENCY, 28.7, "TrendingUp"));
      data.put(kpi("pendingInvoices", "Pending Invoices", 7, "number", -2, "Clock"));

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error building KPI data", e);
      return NeoResponse.error(500, "KPI handler failed: " + e.getMessage());
    }
  }

  private static JSONObject kpi(String key, String label, Number value, String format,
      double trend, String icon) throws Exception {
    JSONObject obj = new JSONObject();
    obj.put("key", key);
    obj.put("label", label);
    obj.put("value", value);
    obj.put("format", format);
    obj.put("trend", trend);
    obj.put("icon", icon);
    return obj;
  }
}
