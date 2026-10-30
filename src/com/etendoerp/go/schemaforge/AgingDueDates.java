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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-5852 (M-153): the open installments of each document in an aging detail, for MCP. Core's
 * detail rows carry the invoice date only, and an invoice may fall due in several installments,
 * each in its own bucket; an agent asked "when is it due" had no answer. Each document gets
 * {@code dueDates:[{dueDate, outstanding}]}, oldest first, from {@code FIN_Payment_Schedule}
 * ({@code outstanding} in the document's own currency, the buckets being in the report's);
 * a document with no open installment (a payment, a credit) gets an empty list.
 */
final class AgingDueDates {

  private static final String KEY_DOCS = "docs";
  private static final String KEY_DUE_DATES = "dueDates";
  private static final String SQL = "SELECT ps.c_invoice_id, to_char(ps.duedate, 'YYYY-MM-DD'),"
      + " ps.outstandingamt FROM fin_payment_schedule ps"
      + " WHERE ps.c_invoice_id IN (:ids) AND ps.outstandingamt <> 0"
      + " ORDER BY ps.c_invoice_id, ps.duedate";

  private AgingDueDates() {
  }

  /**
   * Adds {@code dueDates} to every document under {@code rows[].docs}; rows without documents
   * (no {@code showDetails}) are left as they are.
   */
  static void attach(JSONArray rows) throws JSONException {
    List<JSONObject> docs = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONArray rowDocs = rows.getJSONObject(i).optJSONArray(KEY_DOCS);
      for (int j = 0; rowDocs != null && j < rowDocs.length(); j++) {
        docs.add(rowDocs.getJSONObject(j));
      }
    }
    if (docs.isEmpty()) {
      return;
    }
    Map<String, JSONArray> byInvoice = load(docs);
    for (JSONObject doc : docs) {
      JSONArray dueDates = byInvoice.get(doc.optString("invoiceId", ""));
      doc.put(KEY_DUE_DATES, dueDates == null ? new JSONArray() : dueDates);
    }
  }

  private static Map<String, JSONArray> load(List<JSONObject> docs) throws JSONException {
    List<String> ids = new ArrayList<>();
    for (JSONObject doc : docs) {
      String id = doc.optString("invoiceId", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    Map<String, JSONArray> byInvoice = new HashMap<>();
    if (ids.isEmpty()) {
      return byInvoice;
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows = OBDal.getInstance().getSession().createNativeQuery(SQL)
        .setParameterList("ids", ids)
        .list();
    for (Object[] row : rows) {
      JSONObject due = new JSONObject();
      due.put("dueDate", row[1]);
      due.put("outstanding", row[2] instanceof BigDecimal ? row[2] : new BigDecimal(String.valueOf(row[2])));
      byInvoice.computeIfAbsent(String.valueOf(row[0]), k -> new JSONArray()).put(due);
    }
    return byInvoice;
  }
}
