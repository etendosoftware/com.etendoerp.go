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
 * NeoHandler that returns pending tasks and alerts for the dashboard widget.
 */
@Named("widgetPendingTasksHandler")
public class WidgetPendingTasksHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetPendingTasksHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      JSONArray data = new JSONArray();

      JSONObject t1 = new JSONObject();
      t1.put("type", "warning");
      t1.put("text", "3 overdue invoices");
      t1.put("link", "/sales-invoice");
      t1.put("amount", "$12,400");
      data.put(t1);

      JSONObject t2 = new JSONObject();
      t2.put("type", "info");
      t2.put("text", "2 orders pending shipment");
      t2.put("link", "/goods-shipment");
      data.put(t2);

      JSONObject t3 = new JSONObject();
      t3.put("type", "info");
      t3.put("text", "5 purchase orders to confirm");
      t3.put("link", "/purchase-order");
      data.put(t3);

      JSONObject t4 = new JSONObject();
      t4.put("type", "warning");
      t4.put("text", "1 low stock alert");
      t4.put("link", "/physical-inventory");
      t4.put("detail", "Cerveza Ale 0.5L");
      data.put(t4);

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error building pending tasks data", e);
      return NeoResponse.error(500, "Pending tasks handler failed: " + e.getMessage());
    }
  }
}
