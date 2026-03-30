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
 * NeoHandler that returns monthly revenue trend data for the dashboard widget.
 */
@Named("widgetRevenueTrendHandler")
public class WidgetRevenueTrendHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetRevenueTrendHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      JSONArray labels = new JSONArray();
      String[] months = {"Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec","Jan","Feb","Mar"};
      for (String month : months) {
        labels.put(month);
      }

      JSONArray values = new JSONArray();
      int[] amounts = {32000,35000,28000,41000,38000,45000,42000,39000,44000,47000,43000,48250};
      for (int amount : amounts) {
        values.put(amount);
      }

      JSONObject trend = new JSONObject();
      trend.put("labels", labels);
      trend.put("values", values);

      JSONArray data = new JSONArray();
      data.put(trend);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error building revenue trend data", e);
      return NeoResponse.error(500, "Revenue trend handler failed: " + e.getMessage());
    }
  }
}
