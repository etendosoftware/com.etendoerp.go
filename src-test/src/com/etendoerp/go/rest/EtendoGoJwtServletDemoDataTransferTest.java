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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.payment.DemoDataTransferService;

/**
 * ETP-5480 — the demo-to-productive data transfer (ETP-5364) is permanent: the flag that gated it
 * (ETP-5443) is retired. These specs pin the servlet wiring: both endpoints are routed, the
 * checkout selection is recorded under its request, the purchase projection returns it for
 * resume, and a paid onboarding starts the transfer from the demo persisted on the purchase.
 */
class EtendoGoJwtServletDemoDataTransferTest {

  private static final String STATUS_PATH = "/demo-data-transfer";
  private static final String RETRY_PATH = "/demo-data-transfer/retry";
  private static final String CLIENT_ID = "productive-client";
  private static final String ACCOUNT_ID = "account-id";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String DEMO_CLIENT_ID = "selected-demo-client";
  private static final String REQUEST_ID = "checkout-request";

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
  private final DemoDataTransferService transferService = mock(DemoDataTransferService.class);
  private final CheckoutRequestStore checkoutRequestStore = mock(CheckoutRequestStore.class);

  @BeforeEach
  void setUp() {
    servlet.demoDataTransferService = transferService;
    servlet.checkoutRequestStore = checkoutRequestStore;
  }

  @Test
  void statusEndpointIsRoutedToItsAuthenticatedHandler() throws Exception {
    ResponseCapture resp = mockResponse();

    servlet.doGet(request(STATUS_PATH), resp.response);

    // No credential: the handler's auth gate answers 401, which proves the route exists.
    assertEquals(401, resp.status);
    verifyNoInteractions(transferService);
  }

  @Test
  void retryEndpointIsRoutedToItsAuthenticatedHandler() throws Exception {
    ResponseCapture resp = mockResponse();

    servlet.doPost(request(RETRY_PATH), resp.response);

    assertEquals(401, resp.status);
    verifyNoInteractions(transferService);
  }

  @Test
  void checkoutSelectionIsRecordedUnderTheCheckoutRequest() throws Exception {
    recordSelection(DEMO_CLIENT_ID, true, false);

    verify(transferService).recordSelection(REQUEST_ID, true, false);
  }

  @Test
  void preservesAnExplicitAllFalseSelection() throws Exception {
    JSONObject body = new JSONObject().put("dataTransfer",
        new JSONObject().put("products", false).put("contacts", false));

    recordSelection(body, DEMO_CLIENT_ID);

    verify(transferService).recordSelection(REQUEST_ID, false, false);
  }

  @Test
  void checkoutWithoutAChoiceDoesNotInventOne() throws Exception {
    recordSelection(new JSONObject(), DEMO_CLIENT_ID);

    verifyNoInteractions(transferService);
  }

  @Test
  void purchaseProjectionReturnsThePersistedSelectionForResume() throws Exception {
    when(transferService.selection(REQUEST_ID)).thenReturn(
        new JSONObject().put("products", false).put("contacts", true));
    JSONObject purchase = checkoutResult();

    projectSelection(purchase, DEMO_CLIENT_ID);

    assertTrue(purchase.getBoolean("dataTransferEnabled"));
    assertFalse(purchase.getJSONObject("dataTransfer").getBoolean("products"));
    assertTrue(purchase.getJSONObject("dataTransfer").getBoolean("contacts"));
  }

  @Test
  void productivePurchaseProjectionCarriesNoTransferFields() throws Exception {
    JSONObject purchase = checkoutResult();

    projectSelection(purchase, null);

    assertFalse(purchase.has("dataTransferEnabled"));
    assertFalse(purchase.has("dataTransfer"));
    verifyNoInteractions(transferService);
  }

  @Test
  void paidOnboardingResolvesTheDemoPersistedOnThePurchase() throws Exception {
    assertEquals(DEMO_CLIENT_ID, resolveDemoSourceClientId(true, " " + DEMO_CLIENT_ID + " "));
    assertNull(resolveDemoSourceClientId(true, " "));
    assertNull(resolveDemoSourceClientId(false, DEMO_CLIENT_ID));
  }

  @Test
  void paidOnboardingStartsFromTheExactDemoIdPersistedOnThePurchase() {
    servlet.startDemoDataTransferBestEffort(REQUEST_ID, DEMO_CLIENT_ID, CLIENT_ID,
        ACCOUNT_ID, ACCOUNT_EMAIL);
    verify(checkoutRequestStore).findTransferSelection(REQUEST_ID, ACCOUNT_ID, ACCOUNT_EMAIL);
    verify(transferService).start(REQUEST_ID, DEMO_CLIENT_ID, CLIENT_ID);
    verifyNoMoreInteractions(checkoutRequestStore, transferService);
  }

  @Test
  void aFailedStartNeverReachesTheOnboardingCaller() {
    doThrow(new IllegalStateException("boom")).when(transferService)
        .start(REQUEST_ID, DEMO_CLIENT_ID, CLIENT_ID);
    servlet.startDemoDataTransferBestEffort(REQUEST_ID, DEMO_CLIENT_ID, CLIENT_ID,
        ACCOUNT_ID, ACCOUNT_EMAIL);
    verify(transferService).start(REQUEST_ID, DEMO_CLIENT_ID, CLIENT_ID);
  }

  @Test
  void productivePurchaseDoesNotRecordOrStartDemoTransfer() throws Exception {
    recordSelection(null, false, false);
    servlet.startDemoDataTransferBestEffort(REQUEST_ID, null, CLIENT_ID, ACCOUNT_ID,
        ACCOUNT_EMAIL);

    verifyNoInteractions(transferService, checkoutRequestStore);
  }

  private void projectSelection(JSONObject purchase, String demoClientId) throws Exception {
    Method method = EtendoGoJwtServlet.class.getDeclaredMethod(
        "addDemoDataTransferSelection", JSONObject.class, String.class, String.class);
    method.setAccessible(true);
    method.invoke(servlet, purchase, REQUEST_ID, demoClientId);
  }

  private static String resolveDemoSourceClientId(boolean paidUpgrade, String demoClientId)
      throws Exception {
    Class<?> requestClass = Class.forName(
        "com.etendoerp.go.rest.EtendoGoJwtServlet$OnboardingRequestData");
    Constructor<?> constructor = requestClass.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object onboardingRequest = constructor.newInstance();
    Field field = requestClass.getDeclaredField("demoClientId");
    field.setAccessible(true);
    field.set(onboardingRequest, demoClientId);
    Method method = EtendoGoJwtServlet.class.getDeclaredMethod("resolveDemoSourceClientId",
        boolean.class, requestClass);
    method.setAccessible(true);
    return (String) method.invoke(null, paidUpgrade, onboardingRequest);
  }

  private static JSONObject selectionBody() throws Exception {
    return new JSONObject().put("clientName", "Acme")
        .put("dataTransfer", new JSONObject().put("products", true).put("contacts", false));
  }

  private static JSONObject checkoutResult() throws Exception {
    return new JSONObject().put("requestId", REQUEST_ID);
  }

  private void recordSelection(JSONObject body, String demoClientId) throws Exception {
    invokeRecordSelection(body, demoClientId, false, false);
  }

  private void recordSelection(String demoClientId, boolean products,
      boolean contacts) throws Exception {
    invokeRecordSelection(selectionBody(), demoClientId, products, contacts);
  }

  private void invokeRecordSelection(JSONObject body, String demoClientId, boolean products,
      boolean contacts) throws Exception {
    Class<?> selectionClass = Class.forName(
        "com.etendoerp.go.rest.EtendoGoJwtServlet$CheckoutSelection");
    Constructor<?> constructor = selectionClass.getDeclaredConstructor(String.class,
        boolean.class, boolean.class);
    constructor.setAccessible(true);
    Object selection = constructor.newInstance(demoClientId, products, contacts);
    Method method = EtendoGoJwtServlet.class.getDeclaredMethod(
        "recordDemoDataTransferSelection", JSONObject.class, JSONObject.class, selectionClass);
    method.setAccessible(true);
    method.invoke(servlet, body, checkoutResult(), selection);
  }

  private static HttpServletRequest request(String pathInfo) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(pathInfo);
    return request;
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(inv -> null).when(response).setContentType(anyString());
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    return capture;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
