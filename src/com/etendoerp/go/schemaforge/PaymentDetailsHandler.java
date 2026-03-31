package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.List;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * NeoHandler that enriches the paymentDetails response with fields from FIN_Payment.
 *
 * The NEO default handler for FIN_Payment_ScheduleDetail only returns fields directly
 * on that table (amount, writeoffAmount, canceled, invoicePaid). Fields shown in
 * Etendo classic's Payment Details tab — Document No., Payment Date, Payment Method,
 * Financial Account, Status, Payment — come from FIN_Payment via:
 *   FIN_Payment_ScheduleDetail → FIN_Payment_Detail → FIN_Payment
 *
 * This handler intercepts GET list requests for the "paymentDetails" entity,
 * queries the full chain, and returns all relevant fields.
 */
@Named("paymentDetailsHandler")
public class PaymentDetailsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PaymentDetailsHandler.class);
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd-MM-yyyy");

  @Override
  public NeoResponse handle(NeoContext context) {
    // Only intercept CRUD GET list requests
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    if (!"GET".equals(context.getHttpMethod())) {
      return null;
    }
    if (context.getRecordId() != null) {
      return null;
    }

    String parentId = context.getQueryParams() != null
        ? context.getQueryParams().get("parentId")
        : null;

    if (parentId == null || parentId.isEmpty()) {
      return emptyResponse();
    }

    try {
      // parentId is a FIN_Payment_Schedule ID (invoice payment schedule)
      List<FIN_PaymentScheduleDetail> details = OBDal.getInstance()
          .createQuery(FIN_PaymentScheduleDetail.class,
              "as psd where psd.invoicePaymentSchedule.id = :parentId and psd.active = true and psd.paymentDetails is not null")
          .setNamedParameter("parentId", parentId)
          .list();

      JSONArray data = new JSONArray();
      for (FIN_PaymentScheduleDetail psd : details) {
        JSONObject row = new JSONObject();
        row.put("id", psd.getId());
        row.put("amount", psd.getAmount() != null ? psd.getAmount() : BigDecimal.ZERO);
        row.put("writeoffAmount", psd.getWriteoffAmount() != null ? psd.getWriteoffAmount() : BigDecimal.ZERO);
        row.put("canceled", psd.isCanceled() != null && psd.isCanceled());
        row.put("invoicePaid", psd.isInvoicePaid() != null && psd.isInvoicePaid());

        // Follow FIN_Payment_ScheduleDetail → FIN_Payment_Detail → FIN_Payment
        FIN_PaymentDetail detail = psd.getPaymentDetails();
        if (detail != null) {
          FIN_Payment payment = detail.getFinPayment();
          if (payment != null) {
            row.put("documentNo", payment.getDocumentNo() != null ? payment.getDocumentNo() : "");
            row.put("paymentDate", payment.getPaymentDate() != null
                ? DATE_FORMAT.format(payment.getPaymentDate()) : "");
            row.put("status", payment.getStatus() != null ? payment.getStatus() : "");
            row.put("finPaymentID", payment.getId());
            row.put("finPaymentID$_identifier", payment.getDocumentNo() != null
                ? payment.getDocumentNo() : payment.getId());

            if (payment.getPaymentMethod() != null) {
              row.put("paymentMethod", payment.getPaymentMethod().getId());
              row.put("paymentMethod$_identifier", payment.getPaymentMethod().getName());
            } else {
              row.put("paymentMethod$_identifier", "");
            }

            if (payment.getAccount() != null) {
              row.put("account", payment.getAccount().getId());
              row.put("account$_identifier", payment.getAccount().getName());
            } else {
              row.put("account$_identifier", "");
            }
          }
        }

        data.put(row);
      }

      JSONObject responseData = new JSONObject();
      responseData.put("data", data);
      responseData.put("count", data.length());

      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);

      return NeoResponse.ok(wrapper);

    } catch (Exception e) {
      log.error("[PaymentDetailsHandler] Error fetching payment details for parentId={}", parentId, e);
      return NeoResponse.error(500, "Error fetching payment details: " + e.getMessage());
    }
  }

  private NeoResponse emptyResponse() {
    try {
      JSONObject responseData = new JSONObject();
      responseData.put("data", new JSONArray());
      responseData.put("count", 0);
      JSONObject wrapper = new JSONObject();
      wrapper.put("response", responseData);
      return NeoResponse.ok(wrapper);
    } catch (Exception e) {
      return NeoResponse.error(500, e.getMessage());
    }
  }
}
