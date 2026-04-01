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
 * NeoHandler that returns recent activity messages for the dashboard widget.
 */
@Named("widgetActivityHandler")
public class WidgetActivityHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetActivityHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      JSONArray data = new JSONArray();
      data.put(activity("1", "System",
          "Invoice INV-2026-0142 was paid by Empresa ABC",
          "2026-03-09T08:30:00", "system"));
      data.put(activity("2", "Ana Garcia",
          "New quotation QT-0089 created for $8,500",
          "2026-03-09T07:15:00", "note"));
      data.put(activity("3", "System",
          "Purchase Order PO-0234 received (15 items)",
          "2026-03-08T16:45:00", "system"));
      data.put(activity("4", "Pedro Lopez",
          "Stock adjustment completed for warehouse Madrid",
          "2026-03-08T14:20:00", "note"));

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      log.error("Error building activity data", e);
      return NeoResponse.error(500, "Activity handler failed: " + e.getMessage());
    }
  }

  private static JSONObject activity(String id, String author, String text,
      String timestamp, String type) throws Exception {
    JSONObject obj = new JSONObject();
    obj.put("id", id);
    obj.put("author", author);
    obj.put("text", text);
    obj.put("timestamp", timestamp);
    obj.put("type", type);
    return obj;
  }
}
