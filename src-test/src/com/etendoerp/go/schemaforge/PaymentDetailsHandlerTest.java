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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link PaymentDetailsHandler}.
 *
 * <p>Covers: handle() guards (non-CRUD, POST, GET with recordId),
 * empty/null parentId, successful query with full payment enrichment,
 * null payment detail, null payment method/account, formatDate via
 * reflection, and exception error path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentDetailsHandlerTest {

  private PaymentDetailsHandler handler;

  @Mock
  private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    handler = new PaymentDetailsHandler();
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ── handle(): guard clauses ───────────────────────────────────────────────

  @Nested
  @DisplayName("handle() guard clauses")
  class GuardClauses {

    @Test
    @DisplayName("Non-CRUD endpoint returns null")
    void testNonCrudEndpointReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.SELECTOR)
          .build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("POST request returns null")
    void testPostRequestReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.CRUD)
          .build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("GET with recordId returns null")
    void testGetWithRecordIdReturnsNull() {
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .recordId("some-record-id")
          .build();
      assertNull(handler.handle(ctx));
    }

    @Test
    @DisplayName("GET list with null parentId returns empty response")
    void testGetListNullParentIdReturnsEmpty() throws Exception {
      Map<String, String> params = new HashMap<>();
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .queryParams(params)
          .build();

      NeoResponse response = handler.handle(ctx);
      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());

      JSONObject body = response.getBody();
      JSONObject inner = body.getJSONObject("response");
      assertEquals(0, inner.getInt("count"));
      assertEquals(0, inner.getJSONArray("data").length());
    }

    @Test
    @DisplayName("GET list with empty parentId returns empty response")
    void testGetListEmptyParentIdReturnsEmpty() throws Exception {
      Map<String, String> params = new HashMap<>();
      params.put("parentId", "");
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .queryParams(params)
          .build();

      NeoResponse response = handler.handle(ctx);
      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());

      JSONObject body = response.getBody();
      JSONObject inner = body.getJSONObject("response");
      assertEquals(0, inner.getInt("count"));
      assertEquals(0, inner.getJSONArray("data").length());
    }

    @Test
    @DisplayName("GET list with null queryParams returns empty response")
    void testGetListNullQueryParamsReturnsEmpty() throws Exception {
      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .build();

      NeoResponse response = handler.handle(ctx);
      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());

      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(0, inner.getInt("count"));
    }
  }

  // ── handle(): successful query ────────────────────────────────────────────

  @Nested
  @DisplayName("Successful query with payment details")
  class SuccessfulQuery {

    @Test
    @DisplayName("Returns all fields when payment detail and payment are present")
    @SuppressWarnings("unchecked")
    void testFullPaymentDetailsReturnsAllFields() throws Exception {
      String parentId = "schedule-123";
      Map<String, String> params = new HashMap<>();
      params.put("parentId", parentId);

      FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
      FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
      FIN_Payment payment = mock(FIN_Payment.class);
      FIN_PaymentMethod paymentMethod = mock(FIN_PaymentMethod.class);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

      when(psd.getId()).thenReturn("psd-id-1");
      when(psd.getAmount()).thenReturn(new BigDecimal("250.00"));
      when(psd.getWriteoffAmount()).thenReturn(new BigDecimal("10.00"));
      when(psd.isCanceled()).thenReturn(Boolean.FALSE);
      when(psd.isInvoicePaid()).thenReturn(Boolean.TRUE);
      when(psd.getPaymentDetails()).thenReturn(detail);

      when(detail.getFinPayment()).thenReturn(payment);
      when(payment.getId()).thenReturn("payment-id-1");
      when(payment.getDocumentNo()).thenReturn("PAY/001");
      Date paymentDate = new SimpleDateFormat("yyyy-MM-dd").parse("2026-03-15");
      when(payment.getPaymentDate()).thenReturn(paymentDate);
      when(payment.getStatus()).thenReturn("RPR");
      when(payment.getPaymentMethod()).thenReturn(paymentMethod);
      when(payment.getAccount()).thenReturn(account);

      when(paymentMethod.getId()).thenReturn("pm-id-1");
      when(paymentMethod.getName()).thenReturn("Wire Transfer");
      when(account.getId()).thenReturn("acc-id-1");
      when(account.getName()).thenReturn("Main Bank Account");

      OBQuery<FIN_PaymentScheduleDetail> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(query);
      when(query.setNamedParameter(eq("parentId"), eq(parentId))).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(psd));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .queryParams(params)
          .build();

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject body = response.getBody();
      JSONObject inner = body.getJSONObject("response");
      assertEquals(1, inner.getInt("count"));

      JSONArray data = inner.getJSONArray("data");
      assertEquals(1, data.length());

      JSONObject row = data.getJSONObject(0);
      assertEquals("psd-id-1", row.getString("id"));
      assertEquals(0, new BigDecimal("250.00").compareTo(new BigDecimal(row.get("amount").toString())));
      assertEquals(0, new BigDecimal("10.00").compareTo(new BigDecimal(row.get("writeoffAmount").toString())));
      assertFalse(row.getBoolean("canceled"));
      assertTrue(row.getBoolean("invoicePaid"));
      assertEquals("PAY/001", row.getString("documentNo"));
      assertEquals("15-03-2026", row.getString("paymentDate"));
      assertEquals("RPR", row.getString("status"));
      assertEquals("payment-id-1", row.getString("finPaymentID"));
      assertEquals("PAY/001", row.getString("finPaymentID$_identifier"));
      assertEquals("pm-id-1", row.getString("paymentMethod"));
      assertEquals("Wire Transfer", row.getString("paymentMethod$_identifier"));
      assertEquals("acc-id-1", row.getString("account"));
      assertEquals("Main Bank Account", row.getString("account$_identifier"));
    }

    @Test
    @DisplayName("Null payment detail: only base fields returned")
    @SuppressWarnings("unchecked")
    void testNullPaymentDetailReturnsOnlyBaseFields() throws Exception {
      String parentId = "schedule-456";
      Map<String, String> params = new HashMap<>();
      params.put("parentId", parentId);

      FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
      when(psd.getId()).thenReturn("psd-id-2");
      when(psd.getAmount()).thenReturn(new BigDecimal("100.00"));
      when(psd.getWriteoffAmount()).thenReturn(null);
      when(psd.isCanceled()).thenReturn(null);
      when(psd.isInvoicePaid()).thenReturn(null);
      when(psd.getPaymentDetails()).thenReturn(null);

      OBQuery<FIN_PaymentScheduleDetail> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(query);
      when(query.setNamedParameter(eq("parentId"), eq(parentId))).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(psd));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .queryParams(params)
          .build();

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject row = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertEquals("psd-id-2", row.getString("id"));
      assertEquals(0, new BigDecimal("100.00").compareTo(new BigDecimal(row.get("amount").toString())));
      assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(row.get("writeoffAmount").toString())));
      assertFalse(row.getBoolean("canceled"));
      assertFalse(row.getBoolean("invoicePaid"));
      assertFalse(row.has("documentNo"));
      assertFalse(row.has("paymentDate"));
      assertFalse(row.has("status"));
    }

    @Test
    @DisplayName("Null payment method and account: identifier returns empty string")
    @SuppressWarnings("unchecked")
    void testNullPaymentMethodAndAccountReturnsEmptyIdentifier() throws Exception {
      String parentId = "schedule-789";
      Map<String, String> params = new HashMap<>();
      params.put("parentId", parentId);

      FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
      FIN_PaymentDetail detail = mock(FIN_PaymentDetail.class);
      FIN_Payment payment = mock(FIN_Payment.class);

      when(psd.getId()).thenReturn("psd-id-3");
      when(psd.getAmount()).thenReturn(BigDecimal.ZERO);
      when(psd.getWriteoffAmount()).thenReturn(BigDecimal.ZERO);
      when(psd.isCanceled()).thenReturn(Boolean.FALSE);
      when(psd.isInvoicePaid()).thenReturn(Boolean.FALSE);
      when(psd.getPaymentDetails()).thenReturn(detail);

      when(detail.getFinPayment()).thenReturn(payment);
      when(payment.getId()).thenReturn("payment-id-3");
      when(payment.getDocumentNo()).thenReturn("PAY/003");
      when(payment.getPaymentDate()).thenReturn(null);
      when(payment.getStatus()).thenReturn(null);
      when(payment.getPaymentMethod()).thenReturn(null);
      when(payment.getAccount()).thenReturn(null);

      OBQuery<FIN_PaymentScheduleDetail> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(query);
      when(query.setNamedParameter(eq("parentId"), eq(parentId))).thenReturn(query);
      when(query.list()).thenReturn(Collections.singletonList(psd));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .queryParams(params)
          .build();

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject row = response.getBody().getJSONObject("response").getJSONArray("data").getJSONObject(0);

      assertEquals("PAY/003", row.getString("documentNo"));
      assertEquals("", row.getString("paymentDate"));
      assertEquals("", row.getString("status"));
      assertEquals("", row.getString("paymentMethod$_identifier"));
      assertEquals("", row.getString("account$_identifier"));
      assertFalse(row.has("paymentMethod"));
      assertFalse(row.has("account"));
    }

    @Test
    @DisplayName("Multiple payment schedule details returned in data array")
    @SuppressWarnings("unchecked")
    void testMultipleDetailsReturnedCorrectly() throws Exception {
      String parentId = "schedule-multi";
      Map<String, String> params = new HashMap<>();
      params.put("parentId", parentId);

      FIN_PaymentScheduleDetail psd1 = mock(FIN_PaymentScheduleDetail.class);
      when(psd1.getId()).thenReturn("psd-multi-1");
      when(psd1.getAmount()).thenReturn(new BigDecimal("100.00"));
      when(psd1.getWriteoffAmount()).thenReturn(BigDecimal.ZERO);
      when(psd1.isCanceled()).thenReturn(Boolean.FALSE);
      when(psd1.isInvoicePaid()).thenReturn(Boolean.FALSE);
      when(psd1.getPaymentDetails()).thenReturn(null);

      FIN_PaymentScheduleDetail psd2 = mock(FIN_PaymentScheduleDetail.class);
      when(psd2.getId()).thenReturn("psd-multi-2");
      when(psd2.getAmount()).thenReturn(new BigDecimal("200.00"));
      when(psd2.getWriteoffAmount()).thenReturn(new BigDecimal("5.00"));
      when(psd2.isCanceled()).thenReturn(Boolean.TRUE);
      when(psd2.isInvoicePaid()).thenReturn(Boolean.TRUE);
      when(psd2.getPaymentDetails()).thenReturn(null);

      OBQuery<FIN_PaymentScheduleDetail> query = mock(OBQuery.class);
      when(obDal.createQuery(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(query);
      when(query.setNamedParameter(eq("parentId"), eq(parentId))).thenReturn(query);
      when(query.list()).thenReturn(List.of(psd1, psd2));

      NeoContext ctx = NeoContext.builder()
          .httpMethod("GET")
          .endpointType(NeoEndpointType.CRUD)
          .queryParams(params)
          .build();

      NeoResponse response = handler.handle(ctx);

      assertEquals(200, response.getHttpStatus());
      JSONObject inner = response.getBody().getJSONObject("response");
      assertEquals(2, inner.getInt("count"));
      assertEquals(2, inner.getJSONArray("data").length());
      assertEquals("psd-multi-1", inner.getJSONArray("data").getJSONObject(0).getString("id"));
      assertEquals("psd-multi-2", inner.getJSONArray("data").getJSONObject(1).getString("id"));
    }
  }

  // ── formatDate via reflection ─────────────────────────────────────────────

  @Test
  @DisplayName("formatDate converts Date to dd-MM-yyyy format")
  void testFormatDateConvertsToDdMmYyyy() throws Exception {
    Method formatDate = PaymentDetailsHandler.class.getDeclaredMethod("formatDate", Date.class);
    formatDate.setAccessible(true);

    Date date = new SimpleDateFormat("yyyy-MM-dd").parse("2026-01-25");
    String result = (String) formatDate.invoke(null, date);

    assertEquals("25-01-2026", result);
  }

  @Test
  @DisplayName("formatDate handles end-of-year date correctly")
  void testFormatDateEndOfYear() throws Exception {
    Method formatDate = PaymentDetailsHandler.class.getDeclaredMethod("formatDate", Date.class);
    formatDate.setAccessible(true);

    Date date = new SimpleDateFormat("yyyy-MM-dd").parse("2026-12-31");
    String result = (String) formatDate.invoke(null, date);

    assertEquals("31-12-2026", result);
  }

  // ── Exception path ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Exception during query returns HTTP 500 error response")
  @SuppressWarnings("unchecked")
  void testExceptionDuringQueryReturns500() throws Exception {
    String parentId = "schedule-error";
    Map<String, String> params = new HashMap<>();
    params.put("parentId", parentId);

    OBQuery<FIN_PaymentScheduleDetail> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(FIN_PaymentScheduleDetail.class), anyString())).thenReturn(query);
    when(query.setNamedParameter(eq("parentId"), eq(parentId))).thenReturn(query);
    when(query.list()).thenThrow(new RuntimeException("DB connection failed"));

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .queryParams(params)
        .build();

    NeoResponse response = handler.handle(ctx);

    assertEquals(500, response.getHttpStatus());
    JSONObject errorBody = response.getBody();
    assertTrue(errorBody.has("error"));
    JSONObject errorObj = errorBody.getJSONObject("error");
    assertEquals(500, errorObj.getInt("status"));
    assertTrue(errorObj.getString("message").contains("DB connection failed"));
  }
}
