/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link ReactivatePaymentHandler}.
 *
 * <p>Covers the ETP-4479 addition: on a single-record GET, {@code afterHandle} injects a
 * nullable {@code financialTransactionId} so the payment-in / payment-out detail view can
 * navigate to the reconciled {@code FIN_Finacc_Transaction}. The Reactivate/Confirm action
 * behavior is pre-existing and only re-verified at the {@code @Named} qualifier level here.
 */
public class ReactivatePaymentHandlerTest {

  private static NeoContext getCtx(String recordId, String method) {
    return NeoContext.builder()
        .specName("payment-in")
        .entityName("finPayment")
        .httpMethod(method)
        .endpointType(NeoEndpointType.CRUD)
        .recordId(recordId)
        .build();
  }

  private static JSONObject singleRecordBody(String id) throws JSONException {
    JSONArray data = new JSONArray().put(new JSONObject().put("id", id));
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoContext ctxWithPreviousResult(String recordId, JSONObject body) {
    NeoContext ctx = getCtx(recordId, "GET");
    ctx.setPreviousResult(NeoResponse.ok(body));
    return ctx;
  }

  // ── @Named qualifier ──────────────────────────────────────────────────────

  /**
   * The class must carry {@code @Named("payment-reactivate")} so {@code lookupHandler()} can
   * match it against the {@code ETGO_SF_ENTITY.Java_Qualifier} value shared by both the
   * payment-in and payment-out windows.
   */
  @Test
  public void carriesPaymentReactivateNamedQualifier() {
    Named named = ReactivatePaymentHandler.class.getAnnotation(Named.class);
    assertNotNull("ReactivatePaymentHandler must be annotated @Named", named);
    assertEquals("payment-reactivate", named.value());
  }

  // ── afterHandle — guard conditions ────────────────────────────────────────

  /**
   * A list response (no recordId) must be left untouched — the financial-transaction lookup
   * only applies to the single-record detail view.
   */
  @Test
  public void afterHandleReturnsNullForListResponse() throws JSONException {
    JSONObject body = singleRecordBody("pay-1");
    NeoContext ctx = getCtx(null, "GET");
    ctx.setPreviousResult(NeoResponse.ok(body));

    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  /**
   * Non-GET methods (e.g. the Reactivate/Confirm ACTION requests handled by the pre-hook)
   * must not be touched by the post-hook.
   */
  @Test
  public void afterHandleReturnsNullForNonGetMethod() throws JSONException {
    JSONObject body = singleRecordBody("pay-1");
    NeoContext ctx = getCtx("pay-1", "PATCH");
    ctx.setPreviousResult(NeoResponse.ok(body));

    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  /**
   * When there is no previous result (e.g. the default CRUD handler failed upstream), the
   * post-hook must not attempt to inject anything.
   */
  @Test
  public void afterHandleReturnsNullWhenPreviousResultMissing() {
    NeoContext ctx = getCtx("pay-1", "GET");
    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  /**
   * An empty data array (record not found) must be left untouched rather than throwing.
   */
  @Test
  public void afterHandleReturnsNullWhenDataArrayIsEmpty() throws JSONException {
    JSONObject body = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray()));
    NeoContext ctx = ctxWithPreviousResult("pay-1", body);

    assertNull(new ReactivatePaymentHandler().afterHandle(ctx));
  }

  // ── afterHandle — single record ───────────────────────────────────────────

  /**
   * Reconciled payment (has a linked {@code FIN_Finacc_Transaction}): the transaction id is
   * injected into the record.
   */
  @Test
  public void afterHandleInjectsTransactionIdWhenReconciled() throws Exception {
    ReactivatePaymentHandler handler = spy(new ReactivatePaymentHandler());
    Mockito.doReturn("trx-1").when(handler).resolveFinancialTransactionId("pay-1");

    JSONObject body = singleRecordBody("pay-1");
    NeoContext ctx = ctxWithPreviousResult("pay-1", body);

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    assertEquals(200, result.getHttpStatus());
    String injected = result.getBody()
        .getJSONObject("response").getJSONArray("data")
        .getJSONObject(0).getString("financialTransactionId");
    assertEquals("trx-1", injected);
  }

  /**
   * Not-yet-reconciled payment (e.g. status is not {@code RPPC}): no linked transaction, so
   * the field is present but JSON {@code null} — never absent, never an error.
   */
  @Test
  public void afterHandleInjectsJsonNullWhenNotReconciled() throws Exception {
    ReactivatePaymentHandler handler = spy(new ReactivatePaymentHandler());
    Mockito.doReturn(null).when(handler).resolveFinancialTransactionId("pay-2");

    JSONObject body = singleRecordBody("pay-2");
    NeoContext ctx = ctxWithPreviousResult("pay-2", body);

    NeoResponse result = handler.afterHandle(ctx);

    assertNotNull(result);
    JSONObject record = result.getBody()
        .getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertTrue(record.has("financialTransactionId"));
    assertEquals(JSONObject.NULL, record.get("financialTransactionId"));
  }

  /**
   * If the transaction lookup throws (unexpected DB error), afterHandle must not propagate
   * the exception — it logs and returns {@code null} so the original payment response is
   * still served to the UI.
   */
  @Test
  public void afterHandleReturnsNullWhenResolutionThrows() throws Exception {
    ReactivatePaymentHandler handler = spy(new ReactivatePaymentHandler());
    Mockito.doThrow(new RuntimeException("boom")).when(handler).resolveFinancialTransactionId("pay-3");

    JSONObject body = singleRecordBody("pay-3");
    NeoContext ctx = ctxWithPreviousResult("pay-3", body);

    assertNull(handler.afterHandle(ctx));
  }

  // ── resolveFinancialTransactionId — OBDal query behavior ──────────────────

  /**
   * No FIN_Payment found for the given id (stale/invalid id) — resolves to {@code null}
   * without querying FIN_Finacc_Transaction.
   */
  @Test
  public void resolveFinancialTransactionIdReturnsNullWhenPaymentNotFound() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(FIN_Payment.class, "missing")).thenReturn(null);

      String result = new ReactivatePaymentHandler().resolveFinancialTransactionId("missing");

      assertNull(result);
    }
  }

  /**
   * FIN_Payment exists but no FIN_Finacc_Transaction references it yet (not reconciled) —
   * resolves to {@code null}.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void resolveFinancialTransactionIdReturnsNullWhenNoTransactionLinked() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(dal.get(FIN_Payment.class, "pay-1")).thenReturn(payment);

      OBCriteria<FIN_FinaccTransaction> crit = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(crit);
      when(crit.list()).thenReturn(Collections.emptyList());

      String result = new ReactivatePaymentHandler().resolveFinancialTransactionId("pay-1");

      assertNull(result);
    }
  }

  /**
   * FIN_Payment exists and is reconciled — resolves to the linked transaction id.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void resolveFinancialTransactionIdReturnsIdWhenLinked() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      FIN_Payment payment = mock(FIN_Payment.class);
      when(dal.get(FIN_Payment.class, "pay-1")).thenReturn(payment);

      FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
      when(trx.getId()).thenReturn("trx-1");
      OBCriteria<FIN_FinaccTransaction> crit = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_FinaccTransaction.class)).thenReturn(crit);
      List<FIN_FinaccTransaction> results = Collections.singletonList(trx);
      when(crit.list()).thenReturn(results);

      String result = new ReactivatePaymentHandler().resolveFinancialTransactionId("pay-1");

      assertEquals("trx-1", result);
    }
  }
}
