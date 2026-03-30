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

import java.text.SimpleDateFormat;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * NeoHandler that returns pending (outstanding) invoices for a given Business Partner.
 * Intercepts ACTION endpoint with fieldName "pendingInvoices" on GET requests.
 *
 * Query parameter:
 *   bpartnerId — the Business Partner UUID to filter by (required)
 *
 * Returns a JSON array of pending invoice schedule entries with outstanding amounts.
 */
/**
 * Handles GET /action/pendingInvoices — returns outstanding invoice schedules
 * for a given business partner. Used by the Apply to Invoices UI.
 * <p>
 * Not registered as a CDI bean directly — invoked by {@link PaymentInHandler}.
 */
public class PendingInvoicesHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PendingInvoicesHandler.class);
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

  @Override
  public NeoResponse handle(NeoContext context) {
    // Only intercept ACTION endpoint with fieldName "pendingInvoices" and GET method
    if (context.getEndpointType() != NeoEndpointType.ACTION
        || !"pendingInvoices".equals(context.getFieldName())
        || !"GET".equals(context.getHttpMethod())) {
      return null;
    }

    try {
      String bpartnerId = context.getQueryParams() != null
          ? context.getQueryParams().get("bpartnerId")
          : null;

      if (bpartnerId == null || bpartnerId.isEmpty()) {
        return NeoResponse.error(400, "bpartnerId query parameter is required");
      }

      String hql = "SELECT ps FROM FIN_Payment_Schedule ps "
          + "JOIN ps.invoice inv "
          + "WHERE inv.businessPartner.id = :bpId "
          + "AND ps.outstandingAmount > 0 "
          + "AND inv.salesTransaction = true "
          + "AND ps.active = true "
          + "ORDER BY ps.dueDate ASC";

      List<FIN_PaymentSchedule> schedules = OBDal.getInstance()
          .getSession()
          .createQuery(hql, FIN_PaymentSchedule.class)
          .setParameter("bpId", bpartnerId)
          .list();

      JSONArray data = new JSONArray();
      for (FIN_PaymentSchedule ps : schedules) {
        JSONObject row = new JSONObject();
        row.put("scheduleId", ps.getId());
        row.put("invoiceNo", ps.getInvoice().getDocumentNo());
        row.put("invoiceId", ps.getInvoice().getId());
        row.put("dueDate", ps.getDueDate() != null ? DATE_FORMAT.format(ps.getDueDate()) : null);
        row.put("totalAmount", ps.getAmount());
        row.put("outstandingAmount", ps.getOutstandingAmount());
        row.put("paidAmount", ps.getPaidAmount());
        row.put("currency", ps.getInvoice().getCurrency().getISOCode());
        data.put(row);
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("Error fetching pending invoices", e);
      return NeoResponse.error(500, "Failed to fetch pending invoices: " + e.getMessage());
    }
  }
}
