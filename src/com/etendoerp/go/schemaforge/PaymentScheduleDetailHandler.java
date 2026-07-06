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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Post-hook for {@code FIN_Payment_ScheduleDetail} lines (used by both payment-in
 * {@code finPaymentScheduleDetail} and payment-out {@code lines} entities).
 *
 * <p>Injects {@code invoiceDocumentNo} into every line row so the UI can display
 * the originating invoice/order document number without requiring a virtual AD column
 * or a secondary frontend fetch.
 *
 * <p>Uses a single batched native query over all IDs in the current page.
 */
@Named("paymentScheduleDetailHandler")
public class PaymentScheduleDetailHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PaymentScheduleDetailHandler.class);

  private static final String INVOICE_DOC_SQL =
      "SELECT fpsd.fin_payment_scheduledetail_id AS detail_id, "
      + "  COALESCE(ci.documentno, co.documentno) AS doc_no "
      + "FROM fin_payment_scheduledetail fpsd "
      + "LEFT JOIN fin_payment_schedule fps "
      + "  ON fps.fin_payment_schedule_id = COALESCE(fpsd.fin_payment_schedule_invoice, fpsd.fin_payment_schedule_order) "
      + "LEFT JOIN c_invoice ci ON ci.c_invoice_id = fps.c_invoice_id "
      + "LEFT JOIN c_order   co ON co.c_order_id   = fps.c_order_id "
      + "WHERE fpsd.fin_payment_scheduledetail_id IN (:ids)";

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null || previousResult == null) {
        return null;
      }
      List<String> ids = NeoHandlerUtils.collectIds(dataArr);
      if (ids.isEmpty()) {
        return null;
      }
      Map<String, String> docNos = fetchDocumentNos(ids);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String docNo = docNos.get(rec.optString("id", null));
        if (docNo != null) {
          rec.put("invoiceDocumentNo", docNo);
        }
      }
      return NeoResponse.ok(previousResult.getBody());
    } catch (Exception e) {
      log.error("Error enriching payment schedule detail lines with invoice document number", e);
      return context.getPreviousResult();
    }
  }

  private Map<String, String> fetchDocumentNos(List<String> ids) {
    Map<String, String> result = new HashMap<>();
    OBContext.setAdminMode(true);
    try {
      @SuppressWarnings("unchecked")
      NativeQuery<Object[]> query = OBDal.getInstance().getSession()
          .createNativeQuery(INVOICE_DOC_SQL);
      query.setParameterList("ids", ids);
      for (Object[] row : query.list()) {
        if (row[1] != null) {
          result.put((String) row[0], (String) row[1]);
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }
}
