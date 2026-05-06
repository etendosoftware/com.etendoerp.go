package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
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
/**
 * NeoHandler for payment details that resolves the complex many-to-many relationship
 * between payment schedules and payments in Openbravo/Etendo:
 *   FIN_Payment_ScheduleDetail → FIN_Payment_Detail → FIN_Payment
 *
 * This handler intercepts GET list requests for the "paymentDetails" entity,
 * queries the full chain, and returns all relevant fields.
 */
@Named("paymentDetailsHandler")
public class PaymentDetailsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(PaymentDetailsHandler.class);
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!isApplicableRequest(context)) {
      return null;
    }

    String parentId = extractParentId(context);
    if (parentId == null || parentId.isEmpty()) {
      return emptyResponse();
    }

    try {
      List<FIN_PaymentScheduleDetail> details = queryDetails(parentId);
      JSONArray data = buildDataArray(details);
      return buildSuccessResponse(data);
    } catch (Exception e) {
      log.error("[PaymentDetailsHandler] Error fetching payment details for parentId={}", parentId, e);
      return NeoResponse.error(500, "Error fetching payment details: " + e.getMessage());
    }
  }

  private boolean isApplicableRequest(NeoContext context) {
    return NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "GET".equals(context.getHttpMethod())
        && context.getRecordId() == null;
  }

  private String extractParentId(NeoContext context) {
    return context.getQueryParams() != null ? context.getQueryParams().get("parentId") : null;
  }

  private List<FIN_PaymentScheduleDetail> queryDetails(String parentId) {
    return OBDal.getInstance()
        .createQuery(FIN_PaymentScheduleDetail.class,
            "as psd where psd.invoicePaymentSchedule.id = :parentId and psd.active = true and psd.paymentDetails is not null")
        .setNamedParameter("parentId", parentId)
        .list();
  }

  private JSONArray buildDataArray(List<FIN_PaymentScheduleDetail> details) throws Exception {
    JSONArray data = new JSONArray();
    for (FIN_PaymentScheduleDetail psd : details) {
      data.put(buildRow(psd));
    }
    return data;
  }

  private JSONObject buildRow(FIN_PaymentScheduleDetail psd) throws Exception {
    JSONObject row = new JSONObject();
    row.put("id", psd.getId());
    row.put("amount", psd.getAmount() != null ? psd.getAmount() : BigDecimal.ZERO);
    row.put("writeoffAmount", psd.getWriteoffAmount() != null ? psd.getWriteoffAmount() : BigDecimal.ZERO);
    row.put("canceled", psd.isCanceled() != null && psd.isCanceled());
    row.put("invoicePaid", psd.isInvoicePaid() != null && psd.isInvoicePaid());
    enrichWithPayment(row, psd.getPaymentDetails());
    return row;
  }

  private void enrichWithPayment(JSONObject row, FIN_PaymentDetail detail) throws Exception {
    if (detail == null) {
      return;
    }
    FIN_Payment payment = detail.getFinPayment();
    if (payment == null) {
      return;
    }
    row.put("documentNo", payment.getDocumentNo() != null ? payment.getDocumentNo() : "");
    row.put("paymentDate", payment.getPaymentDate() != null ? formatDate(payment.getPaymentDate()) : "");
    row.put("status", payment.getStatus() != null ? payment.getStatus() : "");
    row.put("finPaymentID", payment.getId());
    row.put("finPaymentID$_identifier", payment.getDocumentNo() != null ? payment.getDocumentNo() : payment.getId());
    enrichWithPaymentMethod(row, payment);
    enrichWithAccount(row, payment);
  }

  private void enrichWithPaymentMethod(JSONObject row, FIN_Payment payment) throws Exception {
    if (payment.getPaymentMethod() != null) {
      row.put("paymentMethod", payment.getPaymentMethod().getId());
      row.put("paymentMethod$_identifier", payment.getPaymentMethod().getName());
    } else {
      row.put("paymentMethod$_identifier", "");
    }
  }

  private void enrichWithAccount(JSONObject row, FIN_Payment payment) throws Exception {
    if (payment.getAccount() != null) {
      row.put("account", payment.getAccount().getId());
      row.put("account$_identifier", payment.getAccount().getName());
    } else {
      row.put("account$_identifier", "");
    }
  }

  private static String formatDate(Date date) {
    return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT);
  }

  private NeoResponse buildSuccessResponse(JSONArray data) throws JSONException {
    JSONObject responseData = new JSONObject();
    responseData.put("data", data);
    responseData.put("count", data.length());
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return NeoResponse.ok(wrapper);
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
