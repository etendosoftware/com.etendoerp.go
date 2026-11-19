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

package com.etendoerp.go.payment.invoicing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HexFormat;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.ApiKeyRing;

/**
 * The payments API's wire contract (ETP-6322): its own keys, its routes and how refusals reach the
 * caller.
 *
 * @covers com.etendoerp.go.payment.invoicing.PaymentsApiServlet
 * @covers com.etendoerp.go.common.ApiKeyRing
 */
class PaymentsApiServletTest {

  private static final String KEY = "test-erp-key";
  private static final String ID = "0123456789abcdef0123456789abcdef";

  private final PaymentLedger ledger = mock(PaymentLedger.class);
  private final PaymentsApiServlet servlet = new PaymentsApiServlet(
      new ApiKeyRing(PaymentsApiServlet.PROPERTY, PaymentsApiServlet.ENV), ledger);
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final StringWriter body = new StringWriter();
  private int status;
  private MockedStatic<OBContext> obContext;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty(PaymentsApiServlet.PROPERTY,
        "erp:" + HexFormat.of().formatHex(ApiKeyRing.sha256(KEY)));
    obContext = mockStatic(OBContext.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    Mockito.doAnswer(call -> status = call.getArgument(0)).when(response)
        .setStatus(Mockito.anyInt());
  }

  @AfterEach
  void tearDown() {
    obContext.close();
    System.clearProperty(PaymentsApiServlet.PROPERTY);
  }

  private JSONObject call(String method, String path, String key, String json) throws Exception {
    when(request.getMethod()).thenReturn(method);
    when(request.getPathInfo()).thenReturn(path);
    when(request.getHeader(ApiKeyRing.HEADER)).thenReturn(key);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    servlet.service(request, response);
    return new JSONObject(body.toString());
  }

  private JSONObject call(String method, String path, String json) throws Exception {
    return call(method, path, KEY, json);
  }

  @Test
  void aCallWithoutAKnownKeyIsRefused() throws Exception {
    JSONObject answer = call("GET", "/payments", "wrong", "");

    assertEquals(401, status);
    assertEquals(PaymentsApiServlet.CODE_INVALID_API_KEY,
        answer.getJSONObject("error").getString("code"));
    verifyNoInteractions(ledger);
  }

  @Test
  void aSignupKeyDoesNotOpenThePaymentsApi() throws Exception {
    System.setProperty("etgo.signup.api.keys", "web:" + HexFormat.of().formatHex(
        ApiKeyRing.sha256("signup-key")));
    try {
      call("GET", "/payments", "signup-key", "");
      assertEquals(401, status);
    } finally {
      System.clearProperty("etgo.signup.api.keys");
    }
  }

  @Test
  void theListAnswersAPageWithItsCursor() throws Exception {
    when(request.getParameter("status")).thenReturn("pending_invoice");
    when(request.getParameter("limit")).thenReturn("1");
    when(ledger.list("pending_invoice", null, "1")).thenReturn(new PaymentLedger.Page(
        List.of(new JSONObject().put("id", "A")), "A"));

    JSONObject answer = call("GET", "/payments/", "");

    assertEquals(200, status);
    assertEquals("A", answer.getJSONArray("data").getJSONObject(0).getString("id"));
    assertTrue(answer.getBoolean("hasMore"));
    assertEquals("A", answer.getString("nextCursor"));
  }

  @Test
  void theLastPageSaysThereIsNoMore() throws Exception {
    when(ledger.list(any(), any(), any())).thenReturn(new PaymentLedger.Page(List.of(), null));

    JSONObject answer = call("GET", "/payments", "");

    assertFalse(answer.getBoolean("hasMore"));
    assertTrue(answer.isNull("nextCursor"));
  }

  @Test
  void onePaymentIsReadByItsId() throws Exception {
    when(ledger.get(ID.toUpperCase())).thenReturn(new JSONObject().put("id", ID.toUpperCase()));

    assertEquals(ID.toUpperCase(), call("GET", "/payments/" + ID, "").getString("id"));
  }

  @Test
  void theInvoiceNumberIsPatchedWithTheCallersName() throws Exception {
    when(ledger.setInvoiceNumber(ID.toUpperCase(), "F-1", "erp"))
        .thenReturn(new JSONObject().put("invoiceNumber", "F-1"));

    JSONObject answer = call("PATCH", "/payments/" + ID, "{\"invoiceNumber\":\"F-1\"}");

    assertEquals(200, status);
    assertEquals("F-1", answer.getString("invoiceNumber"));
  }

  @Test
  void anEventIsPostedWithTheCallersName() throws Exception {
    when(ledger.report(eq(ID.toUpperCase()), any(), eq("erp")))
        .thenReturn(new JSONObject().put("status", "mail_sent"));

    call("POST", "/payments/" + ID + "/events", "{\"event\":\"mail_sent\"}");

    assertEquals(200, status);
    verify(ledger).report(eq(ID.toUpperCase()), any(), eq("erp"));
  }

  @Test
  void aRefusalKeepsItsStatusCodeAndField() throws Exception {
    when(ledger.setInvoiceNumber(anyString(), anyString(), anyString())).thenThrow(
        new PaymentsApiException(409, PaymentLedger.CODE_INVOICE_NUMBER_CONFLICT,
            PaymentLedger.FIELD_INVOICE_NUMBER, "Another number"));

    JSONObject error = call("PATCH", "/payments/" + ID, "{\"invoiceNumber\":\"F-2\"}")
        .getJSONObject("error");

    assertEquals(409, status);
    assertEquals(PaymentLedger.CODE_INVOICE_NUMBER_CONFLICT, error.getString("code"));
    assertEquals(PaymentLedger.FIELD_INVOICE_NUMBER, error.getString("field"));
  }

  @Test
  void aBodyThatIsNotJsonIsABadRequest() throws Exception {
    JSONObject error = call("PATCH", "/payments/" + ID, "not json").getJSONObject("error");

    assertEquals(400, status);
    assertEquals(PaymentLedger.CODE_INVALID_REQUEST, error.getString("code"));
  }

  @Test
  void unknownPathsAndMethodsAreRefused() throws Exception {
    call("GET", "/invoices", "");
    assertEquals(404, status);

    body.getBuffer().setLength(0);
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    call("DELETE", "/payments", "");
    assertEquals(405, status);

    body.getBuffer().setLength(0);
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    call("GET", "/payments/" + ID + "/events", "");
    assertEquals(405, status);
  }

  @Test
  void aServerFailureHidesItsDetails() throws Exception {
    when(ledger.get(anyString())).thenThrow(new IllegalStateException("SQL with fiscal data"));

    JSONObject error = call("GET", "/payments/" + ID, "").getJSONObject("error");

    assertEquals(500, status);
    assertEquals(PaymentsApiServlet.CODE_SERVER_ERROR, error.getString("code"));
    assertFalse(error.getString("message").contains("fiscal"));
  }
}
