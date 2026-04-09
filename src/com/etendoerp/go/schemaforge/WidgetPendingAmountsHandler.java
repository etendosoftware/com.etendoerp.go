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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that returns pending amounts to collect and to pay
 * for the dashboard widget. Queries outstanding amounts on completed
 * sales invoices (to collect) and purchase invoices (to pay).
 */
@Named("widgetPendingAmountsHandler")
public class WidgetPendingAmountsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WidgetPendingAmountsHandler.class);

  private static final String PENDING_SQL =
      "SELECT COUNT(*), COALESCE(SUM(outstandingamt), 0) "
    + "FROM c_invoice "
    + "WHERE issotrx = :isSoTrx AND docstatus = 'CO' "
    + "AND outstandingamt > 0 AND ad_client_id = :clientId";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(405, "Method not allowed");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = OBContext.getOBContext().getCurrentClient().getId();

        JSONObject toCollect = queryPending(clientId, "Y");
        JSONObject toPay = queryPending(clientId, "N");

        JSONObject data = new JSONObject();
        data.put("toCollect", toCollect);
        data.put("toPay", toPay);

        JSONObject responseData = new JSONObject();
        responseData.put("data", data);
        responseData.put("count", 1);

        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);

        return NeoResponse.ok(wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching pending amounts", e);
      return NeoResponse.error(500, "Pending amounts handler failed: " + e.getMessage());
    }
  }

  private JSONObject queryPending(String clientId, String isSoTrx) throws Exception {
    @SuppressWarnings("unchecked")
    NativeQuery<Object[]> query = OBDal.getInstance()
        .getSession()
        .createNativeQuery(PENDING_SQL);
    query.setParameter("isSoTrx", isSoTrx);
    query.setParameter("clientId", clientId);

    Object[] row = (Object[]) query.uniqueResult();
    long count = ((Number) row[0]).longValue();
    BigDecimal amount = row[1] instanceof BigDecimal
        ? (BigDecimal) row[1]
        : new BigDecimal(row[1].toString());

    JSONObject obj = new JSONObject();
    obj.put("count", count);
    obj.put("amount", amount.doubleValue());
    return obj;
  }
}
