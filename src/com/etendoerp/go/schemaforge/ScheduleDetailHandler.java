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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * NeoHandler for finPaymentScheduleDetail entity.
 * Enriches CRUD GET responses with invoice number resolved from
 * FIN_Payment_Schedule → C_Invoice → DocumentNo.
 */
@Named("scheduleDetailHandler")
public class ScheduleDetailHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ScheduleDetailHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }

    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }

    try {
      OBContext.setAdminMode(true);
      JSONObject body = prev.getBody();
      JSONObject responseObj = body.optJSONObject("response");
      if (responseObj == null) {
        return null;
      }

      JSONArray dataArray = responseObj.optJSONArray("data");
      if (dataArray != null) {
        enrichRows(dataArray);
      } else {
        JSONObject single = responseObj.optJSONObject("data");
        if (single != null) {
          enrichRow(single);
        }
      }

      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching schedule detail rows", e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void enrichRows(JSONArray dataArray) throws Exception {
    for (int i = 0; i < dataArray.length(); i++) {
      JSONObject row = dataArray.optJSONObject(i);
      if (row != null) {
        enrichRow(row);
      }
    }
  }

  private void enrichRow(JSONObject row) throws Exception {
    String scheduleId = row.optString("invoicePaymentSchedule", null);
    if (scheduleId == null || scheduleId.isEmpty()) {
      return;
    }

    FIN_PaymentSchedule schedule = OBDal.getInstance().get(
        FIN_PaymentSchedule.class, scheduleId);
    if (schedule == null) {
      return;
    }

    Invoice invoice = schedule.getInvoice();
    if (invoice != null) {
      row.put("invoiceNo", invoice.getDocumentNo());
      row.put("invoiceId", invoice.getId());
      row.put("invoicePaymentSchedule$_identifier", invoice.getDocumentNo());
      row.put("currency$_identifier", invoice.getCurrency().getISOCode());
    }
  }
}
