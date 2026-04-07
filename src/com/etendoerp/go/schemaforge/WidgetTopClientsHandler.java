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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns the top 10 clients by revenue for the last 12 months.
 * The 12-month window is anchored to the most recent invoice date (treating it
 * as "today"), so demo/test databases without current-date data still show results.
 */
@Named("widgetTopClientsHandler")
public class WidgetTopClientsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetTopClientsHandler.class);

  private static final String TOP_CLIENTS_QUERY =
      "WITH max_date AS ( "
    + "  SELECT MAX(dateinvoiced) AS last_date "
    + "  FROM c_invoice "
    + "  WHERE issotrx = 'Y' AND docstatus IN ('CO','CL') AND ad_client_id = :clientId "
    + ") "
    + "SELECT bp.name, SUM(i.grandtotal) AS total "
    + "FROM c_invoice i "
    + "JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id "
    + "WHERE i.issotrx = 'Y' AND i.docstatus IN ('CO','CL') "
    + "  AND i.ad_client_id = :clientId "
    + "  AND i.dateinvoiced > (SELECT last_date - CAST('12 months' AS interval) FROM max_date) "
    + "GROUP BY bp.c_bpartner_id, bp.name "
    + "ORDER BY SUM(i.grandtotal) DESC "
    + "LIMIT 10";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        @SuppressWarnings("unchecked")
        NativeQuery<Object[]> query = OBDal.getInstance()
            .getSession()
            .createNativeQuery(TOP_CLIENTS_QUERY);
        query.setParameter("clientId", clientId);

        List<Object[]> rows = query.list();

        JSONArray data = new JSONArray();
        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("name", String.valueOf(row[0]));
          item.put("total", ((BigDecimal) row[1]).doubleValue());
          data.put(item);
        }

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        responseData.put("count", data.length());

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.ok(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching top clients", e);
      return NeoResponse.error(500, "Top clients handler failed: " + e.getMessage());
    }
  }
}
