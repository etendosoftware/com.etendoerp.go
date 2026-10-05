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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
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
      svcMock.when(() -> PaymentDraftEditService.deleteDraftPayment("pay-1", "inv-1"))
          .thenReturn(expected);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);
      assertEquals(204, resp.getHttpStatus());
    }
  }
  // ── ETP-5558: REST parity — the agent support never runs off MCP ──────────

  private static final String MSG_PAYMENT_NOT_SAVED =
      "The payment was not saved; nothing was registered — it is safe to retry";
  private static final String MSG_DRAFT_NOT_DELETED =
      "The draft was not deleted; nothing changed — it is safe to retry";

  private NeoContext agentContext(String fieldName, JSONObject body) {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, fieldName, "POST", "inv-1", body);
    when(ctx.isMcpOrigin()).thenReturn(true);
    return ctx;
  }

  private static JSONObject registerBody() throws Exception {
    JSONObject body = new JSONObject();
    body.put("actual_payment", "100.00");
    body.put("payment_date", "2026-01-15");
    body.put("fin_financial_account_id", "acct-1");
    body.put("process", "confirm");
    return body;
  }

  private static JSONObject paymentIdBody() throws Exception {
    return new JSONObject().put("paymentId", "pay-1");
  }

  /** An OBDal whose current transaction reports {@code status}, read at every call. */
  private static OBDal dalWithTransaction(AtomicReference<TransactionStatus> status) {
    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    Transaction tx = mock(Transaction.class);
    when(obDal.getSession()).thenReturn(session);
    when(session.getTransaction()).thenReturn(tx);
    when(tx.getStatus()).thenAnswer(inv -> status.get());
    return obDal;
  }

  @Test
  public void testRestRegisterWithoutScheduleIdKeepsTheOld400() throws Exception {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1",
        registerBody());

    try (MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class)) {
      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
      assertEquals("Missing required fields: scheduleId, actual_payment, payment_date, "
          + "fin_financial_account_id", resp.getBody().getJSONObject("error").getString("message"));
      agentMock.verifyNoInteractions();
    }
  }

  @Test
  public void testRestDeleteAnswersA204WithNoBody() throws Exception {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "deletePayment", "POST", "inv-1",
        paymentIdBody());

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentDraftEditService> svcMock =
             mockStatic(PaymentDraftEditService.class)) {
      svcMock.when(() -> PaymentDraftEditService.deleteDraftPayment("pay-1", "inv-1"))
          .thenReturn(NeoResponse.noContent());

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertEquals(204, resp.getHttpStatus());
      assertNull(resp.getBody());
      agentMock.verifyNoInteractions();
    }
  }

  @Test
  public void testRestInvoiceAccountsIsByteIdentical() throws Exception {
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "invoiceAccounts", "GET", "inv-1", null);
    JSONObject item = new JSONObject().put("id", "acct-1")
        .put("paymentMethodIds", new JSONArray().put("m-1"))
        .put("defaultPaymentMethod", new JSONObject().put("id", "m-1"));
    JSONObject body = new JSONObject().put("defaultMethodId", "m-inv")
        .put("items", new JSONArray().put(item));
    String before = body.toString();
    NeoResponse accounts = new NeoResponse(200, body);

    try (MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      svcMock.when(() -> PaymentRegistrationService.handleListAccounts(ctx, true))
          .thenReturn(accounts);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertSame(accounts, resp);
      assertEquals(before, resp.getBody().toString());
      agentMock.verifyNoInteractions();
    }
  }

  @Test
  public void testRestRegisterIsNeverRefusedForOverpaymentOrMethod() throws Exception {
    JSONObject body = registerBody();
    body.put("scheduleId", "sched-1");
    body.put("actual_payment", "999999.99");
    body.put("fin_paymentmethod_id", "method-the-account-rejects");
    NeoContext ctx = buildContext(NeoEndpointType.ACTION, "registerPayment", "POST", "inv-1", body);
    NeoResponse expected = new NeoResponse(201, new JSONObject());

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      svcMock.when(() -> PaymentRegistrationService.doRegisterPaymentAdvanced("inv-1", body, true))
          .thenReturn(expected);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertSame(expected, resp);
      agentMock.verifyNoInteractions();
    }
  }

  // ── ETP-5558: MCP path ────────────────────────────────────────────────────

  @Test
  public void testAgentRefusalReturnsBeforeAnythingIsDispatched() throws Exception {
    JSONObject body = registerBody();
    NeoContext ctx = agentContext("registerPayment", body);
    NeoResponse refusal = NeoResponse.error(422, "several installments");
    OBDal obDal = dalWithTransaction(new AtomicReference<>(TransactionStatus.ACTIVE));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      agentMock.when(() -> PaymentAgentSupport.checkRegister("inv-1", body, true))
          .thenReturn(refusal);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertSame(refusal, resp);
      svcMock.verifyNoInteractions();
      agentMock.verify(() -> PaymentAgentSupport.enrich(anyString(), any(), anyString(), any(),
          any()), never());
      verify(obDal, never()).flush();
    }
  }

  @Test
  public void testAgentRegisterAnswerIsEnriched() throws Exception {
    JSONObject body = registerBody();
    NeoContext ctx = agentContext("registerPayment", body);
    NeoResponse plain = new NeoResponse(201, new JSONObject());
    NeoResponse enriched = new NeoResponse(201, new JSONObject().put("enrichedMarker", true));
    OBDal obDal = dalWithTransaction(new AtomicReference<>(TransactionStatus.ACTIVE));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentRegistrationService> svcMock =
             mockStatic(PaymentRegistrationService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      svcMock.when(() -> PaymentRegistrationService.doRegisterPaymentAdvanced("inv-1", body, true))
          .thenReturn(plain);
      agentMock.when(() -> PaymentAgentSupport.enrich("registerPayment", plain, "inv-1", body,
          null)).thenReturn(enriched);

      assertSame(enriched, PaymentActionHandlerSupport.handle(ctx, true, log));
      agentMock.verify(() -> PaymentAgentSupport.describeDraft(anyString(), anyString()), never());
    }
  }

  @Test
  public void testAgentConfirmAnswerIsEnriched() throws Exception {
    JSONObject body = paymentIdBody();
    NeoContext ctx = agentContext("confirmPayment", body);
    NeoResponse plain = new NeoResponse(201, new JSONObject());
    NeoResponse enriched = new NeoResponse(201, new JSONObject().put("enrichedMarker", true));
    OBDal obDal = dalWithTransaction(new AtomicReference<>(TransactionStatus.ACTIVE));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentDraftEditService> svcMock =
             mockStatic(PaymentDraftEditService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      svcMock.when(() -> PaymentDraftEditService.confirmDraftPayment("pay-1", "inv-1"))
          .thenReturn(plain);
      agentMock.when(() -> PaymentAgentSupport.enrich("confirmPayment", plain, "inv-1", body,
          null)).thenReturn(enriched);

      assertSame(enriched, PaymentActionHandlerSupport.handle(ctx, true, log));
      agentMock.verify(() -> PaymentAgentSupport.checkRegister(anyString(), any(), anyBoolean()),
          never());
    }
  }

  @Test
  public void testAgentDeleteAnswerCarriesWhatWasDeleted() throws Exception {
    JSONObject body = paymentIdBody();
    NeoContext ctx = agentContext("deletePayment", body);
    JSONObject deleted = new JSONObject().put("id", "pay-1").put("documentNo", "P-1");
    NeoResponse enriched = NeoResponse.ok(new JSONObject().put("deleted", deleted));
    OBDal obDal = dalWithTransaction(new AtomicReference<>(TransactionStatus.ACTIVE));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentDraftEditService> svcMock =
             mockStatic(PaymentDraftEditService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      NeoResponse noContent = NeoResponse.noContent();
      svcMock.when(() -> PaymentDraftEditService.deleteDraftPayment("pay-1", "inv-1"))
          .thenReturn(noContent);
      agentMock.when(() -> PaymentAgentSupport.describeDraft("pay-1", "inv-1")).thenReturn(deleted);
      agentMock.when(() -> PaymentAgentSupport.enrich("deletePayment", noContent, "inv-1", body,
          deleted)).thenReturn(enriched);

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertEquals(200, resp.getHttpStatus());
      assertSame(deleted, resp.getBody().getJSONObject("deleted"));
    }
  }

  // ── ETP-5558: the answer never costs the mutation (fbe4a0465, 3ed1c2332) ──

  /** Runs an agent action whose enrich throws {@code failure}, dooming the tx when asked. */
  private NeoResponse runWithFailingEnrich(String fieldName, JSONObject body,
      RuntimeException failure, boolean dooms, OBDal[] dalOut) throws Exception {
    NeoContext ctx = agentContext(fieldName, body);
    AtomicReference<TransactionStatus> status = new AtomicReference<>(TransactionStatus.ACTIVE);
    OBDal obDal = dalWithTransaction(status);
    dalOut[0] = obDal;
    NeoResponse plain = "deletePayment".equals(fieldName) ? NeoResponse.noContent()
        : NeoResponse.createdWithData(new JSONObject().put("id", "pay-1"));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock =
             mockStatic(PaymentAgentSupport.class, Mockito.CALLS_REAL_METHODS);
         MockedStatic<PaymentRegistrationService> regMock =
             mockStatic(PaymentRegistrationService.class);
         MockedStatic<PaymentDraftEditService> editMock =
             mockStatic(PaymentDraftEditService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      agentMock.when(() -> PaymentAgentSupport.checkRegister(anyString(), any(), anyBoolean()))
          .thenReturn(null);
      agentMock.when(() -> PaymentAgentSupport.describeDraft(anyString(), anyString()))
          .thenReturn(null);
      agentMock.when(() -> PaymentAgentSupport.enrich(anyString(), any(), anyString(), any(),
          any())).thenAnswer(inv -> {
            if (dooms) {
              status.set(TransactionStatus.MARKED_ROLLBACK);
            }
            throw failure;
          });
      regMock.when(() -> PaymentRegistrationService.doRegisterPaymentAdvanced(anyString(), any(),
          anyBoolean())).thenReturn(plain);
      editMock.when(() -> PaymentDraftEditService.confirmDraftPayment(anyString(), anyString()))
          .thenReturn(plain);
      editMock.when(() -> PaymentDraftEditService.deleteDraftPayment(anyString(), anyString()))
          .thenReturn(plain);
      return PaymentActionHandlerSupport.handle(ctx, true, log);
    }
  }

  @Test
  public void testEnrichFailureKeepsTheCompletedPayment() throws Exception {
    OBDal[] dal = new OBDal[1];

    NeoResponse resp = runWithFailingEnrich("registerPayment", registerBody(),
        new IllegalStateException("cannot read the outcome"), false, dal);

    assertTrue("a written payment answers 2xx", resp.getHttpStatus() < 300);
    assertFalse(resp.getBody().getJSONObject("response").getJSONObject("data")
        .getBoolean("enriched"));
    verify(dal[0], never()).rollbackAndClose();
  }

  @Test
  public void testEnrichFailureOnConfirmKeepsTheConfirmedPayment() throws Exception {
    OBDal[] dal = new OBDal[1];

    NeoResponse resp = runWithFailingEnrich("confirmPayment", paymentIdBody(),
        new IllegalStateException("cannot read the outcome"), false, dal);

    assertEquals(201, resp.getHttpStatus());
    verify(dal[0], never()).rollbackAndClose();
  }

  @Test
  public void testRollbackOnlyRegisterIsReportedAsNotSaved() throws Exception {
    OBDal[] dal = new OBDal[1];

    NeoResponse resp = runWithFailingEnrich("registerPayment", registerBody(),
        new HibernateException("could not initialize proxy"), true, dal);

    // The invariant: a doomed transaction is never answered with a 2xx.
    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    assertEquals(MSG_PAYMENT_NOT_SAVED,
        resp.getBody().getJSONObject("error").getString("message"));
    verify(dal[0]).rollbackAndClose();
  }

  @Test
  public void testRollbackOnlyConfirmIsReportedAsNotSaved() throws Exception {
    OBDal[] dal = new OBDal[1];

    NeoResponse resp = runWithFailingEnrich("confirmPayment", paymentIdBody(),
        new HibernateException("could not initialize proxy"), true, dal);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    assertEquals(MSG_PAYMENT_NOT_SAVED,
        resp.getBody().getJSONObject("error").getString("message"));
    verify(dal[0]).rollbackAndClose();
  }

  @Test
  public void testRollbackOnlyDeleteIsReportedAsNotDeleted() throws Exception {
    OBDal[] dal = new OBDal[1];

    NeoResponse resp = runWithFailingEnrich("deletePayment", paymentIdBody(),
        new HibernateException("could not initialize proxy"), true, dal);

    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    assertEquals(MSG_DRAFT_NOT_DELETED,
        resp.getBody().getJSONObject("error").getString("message"));
    verify(dal[0]).rollbackAndClose();
  }

  // ── ETP-5558: describeDraftQuietly ───────────────────────────────────────

  private NeoResponse runDeleteWithFailingDescribe(boolean dooms, OBDal[] dalOut,
      boolean[] dispatched) throws Exception {
    JSONObject body = paymentIdBody();
    NeoContext ctx = agentContext("deletePayment", body);
    AtomicReference<TransactionStatus> status = new AtomicReference<>(TransactionStatus.ACTIVE);
    OBDal obDal = dalWithTransaction(status);
    dalOut[0] = obDal;

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock =
             mockStatic(PaymentAgentSupport.class, Mockito.CALLS_REAL_METHODS);
         MockedStatic<PaymentDraftEditService> editMock =
             mockStatic(PaymentDraftEditService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      agentMock.when(() -> PaymentAgentSupport.describeDraft("pay-1", "inv-1")).thenAnswer(inv -> {
        if (dooms) {
          status.set(TransactionStatus.MARKED_ROLLBACK);
        }
        throw new HibernateException("could not load the draft");
      });
      editMock.when(() -> PaymentDraftEditService.deleteDraftPayment("pay-1", "inv-1"))
          .thenAnswer(inv -> {
            dispatched[0] = true;
            return NeoResponse.noContent();
          });
      return PaymentActionHandlerSupport.handle(ctx, true, log);
    }
  }

  @Test
  public void testDescribeFailureStillDeletesAndAnswersThePlain204() throws Exception {
    OBDal[] dal = new OBDal[1];
    boolean[] dispatched = new boolean[1];

    NeoResponse resp = runDeleteWithFailingDescribe(false, dal, dispatched);

    assertTrue(dispatched[0]);
    assertEquals(204, resp.getHttpStatus());
    assertNull(resp.getBody());
    verify(dal[0], never()).rollbackAndClose();
  }

  @Test
  public void testRollbackOnlyDescribeNeverDispatchesTheDelete() throws Exception {
    OBDal[] dal = new OBDal[1];
    boolean[] dispatched = new boolean[1];

    NeoResponse resp = runDeleteWithFailingDescribe(true, dal, dispatched);

    assertFalse("the delete would be undone at commit", dispatched[0]);
    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    assertEquals(MSG_DRAFT_NOT_DELETED,
        resp.getBody().getJSONObject("error").getString("message"));
    verify(dal[0]).rollbackAndClose();
  }

  @Test
  public void testRollbackCheckWithoutSessionFallsIntoTheOuterCatch() throws Exception {
    JSONObject body = registerBody();
    NeoContext ctx = agentContext("registerPayment", body);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenThrow(new IllegalStateException("no session"));
    boolean[] dispatched = new boolean[1];

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<PaymentAgentSupport> agentMock = mockStatic(PaymentAgentSupport.class);
         MockedStatic<PaymentRegistrationService> regMock =
             mockStatic(PaymentRegistrationService.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      agentMock.when(() -> PaymentAgentSupport.checkRegister(anyString(), any(), anyBoolean()))
          .thenReturn(null);
      regMock.when(() -> PaymentRegistrationService.doRegisterPaymentAdvanced(anyString(), any(),
          anyBoolean())).thenAnswer(inv -> {
            dispatched[0] = true;
            return new NeoResponse(201, new JSONObject());
          });

      NeoResponse resp = PaymentActionHandlerSupport.handle(ctx, true, log);

      assertFalse(dispatched[0]);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
      verify(obDal).rollbackAndClose();
      ctxMock.verify(OBContext::restorePreviousMode);
    }
  }
}
