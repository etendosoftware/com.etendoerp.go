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

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Shared invoice payment ACTION handling for both sales and purchase invoice headers.
 */
final class PaymentActionHandlerSupport {

  private static final String ACTION_NAME = "registerPayment";
  private static final String LIST_ACTION = "invoicePayments";
  private static final String ACCOUNTS_ACTION = "invoiceAccounts";

  private PaymentActionHandlerSupport() {
  }

  static NeoResponse handle(NeoContext context, boolean isReceipt, Logger log) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())) {
      return null;
    }
    String fieldName = context.getFieldName();

    if (LIST_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListPayments(context);
    }
    if (ACCOUNTS_ACTION.equals(fieldName)) {
      return PaymentRegistrationService.handleListAccounts(context, isReceipt);
    }
    if (!ACTION_NAME.equals(fieldName) || !"POST".equals(context.getHttpMethod())) {
      return null;
    }

    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invoice ID is required");
    }

    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Request body is required");
    }

    String scheduleId = body.optString("scheduleId", null);
    String strAmount = body.optString("actual_payment", null);
    String strDate = body.optString("payment_date", null);
    String accountId = body.optString("fin_financial_account_id", null);

    if (StringUtils.isBlank(scheduleId) || StringUtils.isBlank(strAmount)
        || StringUtils.isBlank(strDate) || StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Missing required fields: scheduleId, actual_payment, payment_date, fin_financial_account_id");
    }

    try {
      OBContext.setAdminMode(true);
      try {
        return PaymentRegistrationService.doRegisterPayment(
            invoiceId, scheduleId, strAmount, strDate, accountId, isReceipt);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Payment registration failed for invoice {}: {}", invoiceId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error registering payment for invoice {}: {}", invoiceId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while registering the payment");
    }
  }
}
