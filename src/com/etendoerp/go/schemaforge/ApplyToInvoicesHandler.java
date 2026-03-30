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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * NeoHandler that applies a payment to selected invoices.
 * Intercepts ACTION endpoint with fieldName "applyToInvoices" on POST requests.
 *
 * Request body:
 * <pre>
 * {
 *   "invoices": [
 *     { "scheduleId": "ABC123", "amount": 150.00 },
 *     { "scheduleId": "DEF456", "amount": 200.00 }
 *   ]
 * }
 * </pre>
 *
 * The payment is identified by context.getRecordId(). For each invoice entry,
 * loads the first unallocated FIN_PaymentScheduleDetail and delegates to
 * FIN_AddPayment.savePayment() to create the payment details.
 */
/**
 * Handles POST /action/applyToInvoices — applies a payment to selected invoice
 * schedule entries via {@code FIN_AddPayment.savePayment()}.
 * <p>
 * Not registered as a CDI bean directly — invoked by {@link PaymentInHandler}.
 */
public class ApplyToInvoicesHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ApplyToInvoicesHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION
        || !"applyToInvoices".equals(context.getFieldName())
        || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    try {
      OBContext.setAdminMode(true);
      return doApply(context);
    } catch (Exception e) {
      log.error("Error applying payment to invoices", e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, "Failed to apply payment: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse doApply(NeoContext context) throws Exception {
    String paymentId = context.getRecordId();
    if (paymentId == null || paymentId.isEmpty()) {
      return NeoResponse.error(400, "Payment record ID is required");
    }

    JSONObject body = context.getRequestBody();
    if (body == null || !body.has("invoices")) {
      return NeoResponse.error(400, "Request body must contain 'invoices' array");
    }

    JSONArray invoices = body.getJSONArray("invoices");
    if (invoices.length() == 0) {
      return NeoResponse.error(400, "Invoices array must not be empty");
    }

    FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
    if (payment == null) {
      return NeoResponse.error(404, "Payment not found: " + paymentId);
    }

    List<FIN_PaymentScheduleDetail> selectedDetails = new ArrayList<>();
    HashMap<String, BigDecimal> detailAmounts = new HashMap<>();

    for (int i = 0; i < invoices.length(); i++) {
      JSONObject entry = invoices.getJSONObject(i);
      String scheduleId = entry.getString("scheduleId");
      BigDecimal amount = new BigDecimal(entry.getString("amount"));

      if (amount.compareTo(BigDecimal.ZERO) <= 0) {
        return NeoResponse.error(400,
            "Amount must be positive for scheduleId: " + scheduleId);
      }

      // Find the first unallocated schedule detail for this invoice schedule
      String hql = "FROM FIN_Payment_ScheduleDetail psd "
          + "WHERE psd.invoicePaymentSchedule.id = :scheduleId "
          + "AND psd.paymentDetails IS NULL "
          + "ORDER BY psd.amount DESC";

      List<FIN_PaymentScheduleDetail> unallocated = OBDal.getInstance()
          .getSession()
          .createQuery(hql, FIN_PaymentScheduleDetail.class)
          .setParameter("scheduleId", scheduleId)
          .setMaxResults(1)
          .list();

      if (unallocated.isEmpty()) {
        return NeoResponse.error(400,
            "No unallocated schedule detail found for scheduleId: " + scheduleId);
      }

      FIN_PaymentScheduleDetail psd = unallocated.get(0);

      // Validate amount does not exceed outstanding
      BigDecimal outstanding = psd.getAmount();
      if (amount.compareTo(outstanding) > 0) {
        return NeoResponse.error(400,
            "Amount " + amount + " exceeds outstanding " + outstanding
                + " for scheduleId: " + scheduleId);
      }

      selectedDetails.add(psd);
      detailAmounts.put(psd.getId(), amount);
    }

    // Compute total amount being applied
    BigDecimal totalAmount = BigDecimal.ZERO;
    for (BigDecimal amt : detailAmounts.values()) {
      totalAmount = totalAmount.add(amt);
    }

    // Delegate to FIN_AddPayment.savePayment using the existing payment data
    FIN_AddPayment.savePayment(
        payment,
        payment.isReceipt(),
        payment.getDocumentType(),
        payment.getDocumentNo(),
        payment.getBusinessPartner(),
        payment.getPaymentMethod(),
        payment.getAccount(),
        totalAmount.toPlainString(),
        payment.getPaymentDate(),
        payment.getOrganization(),
        payment.getReferenceNo(),
        selectedDetails,
        detailAmounts,
        false,  // isWriteoff
        false   // isRefund
    );

    OBDal.getInstance().flush();

    // Reload payment to get updated state
    OBDal.getInstance().refresh(payment);

    JSONObject result = new JSONObject();
    result.put("paymentId", payment.getId());
    result.put("documentNo", payment.getDocumentNo());
    result.put("status", payment.getStatus());
    result.put("totalAmount", payment.getAmount());
    result.put("appliedInvoices", invoices.length());

    JSONObject wrapper = new JSONObject();
    wrapper.put("response", result);

    return NeoResponse.ok(wrapper);
  }
}
