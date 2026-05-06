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
 * NeoHandler that returns recent completed sales invoices for the dashboard widget.
 * Queries sales invoices (c_invoice) joined with business partner, filtered to
 * completed statuses (CO, CL) within the last 7 days, ordered by invoice date
 * descending, limited to the latest 10 records.
 */
@Named("widgetRecentInvoicesHandler")
public class WidgetRecentInvoicesHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetRecentInvoicesHandler.class);

  private static final String RECENT_INVOICES_QUERY =
      "SELECT i.c_invoice_id, bp.name AS client, "
    + "to_char(i.dateinvoiced, 'DD-MM-YYYY') AS date, "
    + "i.grandtotal AS amount, i.docstatus AS status, "
    + "i.documentno AS documentno "
    + "FROM c_invoice i "
    + "JOIN c_bpartner bp ON bp.c_bpartner_id = i.c_bpartner_id "
    + "WHERE i.issotrx = 'Y' AND i.ad_client_id = :clientId "
    + "  AND i.docstatus IN ('CO','CL') "
    + "  AND i.dateinvoiced >= CURRENT_DATE - CAST('7 days' AS interval) "
    + "ORDER BY i.dateinvoiced DESC, i.created DESC "
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
            .createNativeQuery(RECENT_INVOICES_QUERY);
        query.setParameter("clientId", clientId);

        List<Object[]> rows = query.list();

        JSONArray data = new JSONArray();

        for (Object[] row : rows) {
          JSONObject item = new JSONObject();
          item.put("id", String.valueOf(row[0]));
          item.put("client", String.valueOf(row[1]));
          item.put("date", String.valueOf(row[2]));
          item.put("amount", ((BigDecimal) row[3]).doubleValue());
          item.put("status", String.valueOf(row[4]));
          item.put("documentNo", String.valueOf(row[5] != null ? row[5] : ""));
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
      log.error("Error fetching recent invoices", e);
      return NeoResponse.error(500, "Recent invoices handler failed: " + e.getMessage());
    }
  }
}
