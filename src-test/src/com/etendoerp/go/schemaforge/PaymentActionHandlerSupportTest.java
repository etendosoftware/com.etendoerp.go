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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/** Tests for {@link PaymentActionHandlerSupport}. */
public class PaymentActionHandlerSupportTest {

  private static final Logger log = LogManager.getLogger(PaymentActionHandlerSupportTest.class);

  private NeoContext buildContext(NeoEndpointType type, String fieldName, String method,
      String recordId, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(type);
    when(ctx.getFieldName()).thenReturn(fieldName);
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getRecordId()).thenReturn(recordId);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  @Test
  public void testNonActionEndpointReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.CRUD, "registerPayment", "POST", "inv-1", null);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
    assertNull(resp);
  }

  @Test
  public void testListPaymentsDelegates() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "invoicePayments", "GET", "inv-1", null);
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      svcMock.when(() -> PaymentRegistrationService.handleListPayments(ctx)).thenReturn(expected);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void testListAccountsDelegates() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "invoiceAccounts", "GET", "inv-1", null);
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      svcMock.when(() -> PaymentRegistrationService.handleListAccounts(ctx, true))
          .thenReturn(expected);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void testNonRegisterPaymentActionReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "someOtherAction", "POST", "inv-1", null);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
    assertNull(resp);
  }

  @Test
  public void testRegisterPaymentGetMethodReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "GET", "inv-1", null);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
    assertNull(resp);
  }

  @Test
  public void testRegisterPaymentBlankInvoiceIdReturnsBadRequest() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "", null);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

    assertNotNull(resp);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void testRegisterPaymentNullBodyReturnsBadRequest() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1", null);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

    assertNotNull(resp);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void testRegisterPaymentMissingFieldsReturnsBadRequest() throws Exception {
    JSONObject body = new JSONObject();
    body.put("scheduleId", "sched-1");
    // Missing actual_payment, payment_date, fin_financial_account_id
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1", body);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

    assertNotNull(resp);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void testRegisterPaymentSuccessDelegates() throws Exception {
    JSONObject body = new JSONObject();
    body.put("scheduleId", "sched-1");
    body.put("actual_payment", "100.00");
    body.put("payment_date", "2026-01-15");
    body.put("fin_financial_account_id", "acct-1");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1", body);

    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      svcMock.when(() -> PaymentRegistrationService.doRegisterPayment(
          "inv-1", "sched-1", "100.00", "2026-01-15", "acct-1", true))
          .thenReturn(expected);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(200, resp.getHttpStatus());
    }
  }

  @Test
  public void testRegisterPaymentOBExceptionReturnsBadRequest() throws Exception {
    JSONObject body = new JSONObject();
    body.put("scheduleId", "sched-1");
    body.put("actual_payment", "100.00");
    body.put("payment_date", "2026-01-15");
    body.put("fin_financial_account_id", "acct-1");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1", body);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      svcMock.when(() -> PaymentRegistrationService.doRegisterPayment(
          anyString(), anyString(), anyString(), anyString(), anyString(), eq(true)))
          .thenThrow(new org.openbravo.base.exception.OBException("Payment failed"));

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
    }
  }

  @Test
  public void testRegisterPaymentGenericExceptionReturnsInternalError() throws Exception {
    JSONObject body = new JSONObject();
    body.put("scheduleId", "sched-1");
    body.put("actual_payment", "100.00");
    body.put("payment_date", "2026-01-15");
    body.put("fin_financial_account_id", "acct-1");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1", body);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      svcMock.when(() -> PaymentRegistrationService.doRegisterPayment(
          anyString(), anyString(), anyString(), anyString(), anyString(), eq(true)))
          .thenThrow(new RuntimeException("Unexpected error"));

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    }
  }

  // ── deletePayment action routing ──────────────────────────────────────────

  @Test
  public void testDeletePaymentGetMethodReturnsNull() {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "deletePayment", "GET", "inv-1", null);
    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
    assertNull(resp);
  }

  @Test
  public void testDeletePaymentMissingPaymentIdReturnsBadRequest() throws Exception {
    JSONObject body = new JSONObject();
    // Missing paymentId.
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "deletePayment", "POST", "inv-1", body);

    NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

    assertNotNull(resp);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void testDeletePaymentSuccessDelegates() throws Exception {
    JSONObject body = new JSONObject();
    body.put("paymentId", "pay-1");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "deletePayment", "POST", "inv-1", body);

    NeoResponse expected = NeoResponse.noContent();

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<PaymentDraftEditService> svcMock =
             mockStatic(PaymentDraftEditService.class)) {
      svcMock.when(() -> PaymentDraftEditService.deleteDraftPayment("pay-1"))
          .thenReturn(expected);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(204, resp.getHttpStatus());
    }
  }
}
