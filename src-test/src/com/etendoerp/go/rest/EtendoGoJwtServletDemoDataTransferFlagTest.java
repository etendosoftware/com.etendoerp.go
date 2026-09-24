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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.payment.DemoDataTransferFlag;
import com.etendoerp.go.payment.DemoDataTransferService;

/**
 * ETP-5443 — flag {@code demo-data-transfer} gates every ETP-5364 transfer toggle point in the
 * servlet. With it OFF the servlet must behave as it did before ETP-5364: the two endpoints are
 * 404 "Unknown endpoint" (indistinguishable from not existing), the checkout selection is not
 * recorded and a paid onboarding starts no transfer. With it ON each point reaches the service.
 *
 * <p>The flag is stubbed at {@link DemoDataTransferFlag#isEnabled()} — the one gate every toggle
 * point calls — so these specs pin the wiring; {@code DemoDataTransferFlagTest} pins the gate.
 */
class EtendoGoJwtServletDemoDataTransferFlagTest {

  private static final String STATUS_PATH = "/demo-data-transfer";
  private static final String RETRY_PATH = "/demo-data-transfer/retry";
  private static final String CLIENT_ID = "productive-client";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String REQUEST_ID = "checkout-request";

  private final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
  private final DemoDataTransferService transferService = mock(DemoDataTransferService.class);
  private MockedStatic<DemoDataTransferFlag> flag;

  @BeforeEach
  void setUp() {
    servlet.demoDataTransferService = transferService;
    flag = mockStatic(DemoDataTransferFlag.class);
  }

  @AfterEach
  void tearDown() {
    flag.close();
  }

  private void flagIs(boolean enabled) {
    flag.when(DemoDataTransferFlag::isEnabled).thenReturn(enabled);
  }

  @Test
  void flagOffStatusEndpointIsAnUnknownEndpoint() throws Exception {
    flagIs(false);
    ResponseCapture resp = mockResponse();

    servlet.doGet(request(STATUS_PATH), resp.response);

    assertEquals(404, resp.status);
    assertEquals("Unknown endpoint: " + STATUS_PATH, errorMessage(resp));
    verifyNoInteractions(transferService);
  }

  @Test
  void flagOffRetryEndpointIsAnUnknownEndpoint() throws Exception {
    flagIs(false);
    ResponseCapture resp = mockResponse();

    servlet.doPost(request(RETRY_PATH), resp.response);

    assertEquals(404, resp.status);
    assertEquals("Unknown endpoint: " + RETRY_PATH, errorMessage(resp));
    verifyNoInteractions(transferService);
  }

  @Test
  void flagOnStatusEndpointIsRoutedToItsAuthenticatedHandler() throws Exception {
    flagIs(true);
    ResponseCapture resp = mockResponse();

    servlet.doGet(request(STATUS_PATH), resp.response);

    // No credential: the handler's auth gate answers 401, which proves the route exists.
    assertEquals(401, resp.status);
    verifyNoInteractions(transferService);
  }

  @Test
  void flagOffCheckoutSelectionIsNotRecorded() throws Exception {
    flagIs(false);

    servlet.recordDemoDataTransferSelection(selectionBody(), checkoutResult());

    verifyNoInteractions(transferService);
  }

  @Test
  void flagOnCheckoutSelectionIsRecordedUnderTheCheckoutRequest() throws Exception {
    flagIs(true);

    servlet.recordDemoDataTransferSelection(selectionBody(), checkoutResult());

    verify(transferService).recordSelection(REQUEST_ID, true, false);
  }

  @Test
  void flagOnPreservesAnExplicitAllFalseSelection() throws Exception {
    flagIs(true);
    JSONObject body = new JSONObject().put("dataTransfer",
        new JSONObject().put("products", false).put("contacts", false));

    servlet.recordDemoDataTransferSelection(body, checkoutResult());

    verify(transferService).recordSelection(REQUEST_ID, false, false);
  }

  @Test
  void checkoutWithoutAChoiceDoesNotInventOne() throws Exception {
    flagIs(true);

    servlet.recordDemoDataTransferSelection(new JSONObject(), checkoutResult());

    verifyNoInteractions(transferService);
  }

  @Test
  void purchaseProjectionReturnsThePersistedSelectionForResume() throws Exception {
    flagIs(true);
    when(transferService.selection(REQUEST_ID)).thenReturn(
        new JSONObject().put("products", false).put("contacts", true));
    JSONObject purchase = checkoutResult();

    projectSelection(purchase);

    assertTrue(purchase.getBoolean("dataTransferEnabled"));
    assertFalse(purchase.getJSONObject("dataTransfer").getBoolean("products"));
    assertTrue(purchase.getJSONObject("dataTransfer").getBoolean("contacts"));
  }

  @Test
  void flagOffPurchaseProjectionOmitsTheSelection() throws Exception {
    flagIs(false);
    JSONObject purchase = checkoutResult();

    projectSelection(purchase);

    assertFalse(purchase.getBoolean("dataTransferEnabled"));
    assertFalse(purchase.has("dataTransfer"));
    verifyNoInteractions(transferService);
  }

  @Test
  void flagOffPaidOnboardingStartsNoTransferAndLooksUpNoDemo() {
    flagIs(false);
    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      servlet.startDemoDataTransferBestEffort(REQUEST_ID, ACCOUNT_EMAIL, CLIENT_ID);
      dal.verifyNoInteractions();
    }
    verifyNoInteractions(transferService);
  }

  @Test
  void flagOnPaidOnboardingStartsTheTransferFromTheAccountsDemo() {
    flagIs(true);
    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findOnlyFreeTenantIdByAccountEmail(ACCOUNT_EMAIL))
          .thenReturn("demo-client");
      servlet.startDemoDataTransferBestEffort(REQUEST_ID, ACCOUNT_EMAIL, CLIENT_ID);
    }
    verify(transferService).start(REQUEST_ID, "demo-client", CLIENT_ID);
  }

  @Test
  void flagOnRoutesSelectedPaidDataOnlyThroughTheAsyncWorker() {
    flagIs(true);

    assertFalse(EtendoGoJwtServlet.shouldRunSynchronousDataTransfer(true, true, true));
    assertFalse(EtendoGoJwtServlet.shouldRunSynchronousDataTransfer(true, true, false));
  }

  @Test
  void flagOffKeepsTheExistingSynchronousCopyOnlyForPaidSelectedData() {
    flagIs(false);

    assertTrue(EtendoGoJwtServlet.shouldRunSynchronousDataTransfer(true, true, false));
    assertTrue(EtendoGoJwtServlet.shouldRunSynchronousDataTransfer(true, false, true));
    assertFalse(EtendoGoJwtServlet.shouldRunSynchronousDataTransfer(true, false, false));
    assertFalse(EtendoGoJwtServlet.shouldRunSynchronousDataTransfer(false, true, true));
  }

  @Test
  void flagOnAFailedStartNeverReachesTheOnboardingCaller() {
    flagIs(true);
    doThrow(new IllegalStateException("boom")).when(transferService)
        .start(REQUEST_ID, "demo-client", CLIENT_ID);
    try (MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findOnlyFreeTenantIdByAccountEmail(ACCOUNT_EMAIL))
          .thenReturn("demo-client");
      servlet.startDemoDataTransferBestEffort(REQUEST_ID, ACCOUNT_EMAIL, CLIENT_ID);
    }
    verify(transferService).start(REQUEST_ID, "demo-client", CLIENT_ID);
  }

  private static String errorMessage(ResponseCapture resp) throws Exception {
    return new JSONObject(resp.body()).getJSONObject("error").getString("message");
  }

  private void projectSelection(JSONObject purchase) throws Exception {
    Method method = EtendoGoJwtServlet.class.getDeclaredMethod(
        "addDemoDataTransferSelection", JSONObject.class, String.class);
    method.setAccessible(true);
    method.invoke(servlet, purchase, REQUEST_ID);
  }

  private static JSONObject selectionBody() throws Exception {
    return new JSONObject().put("clientName", "Acme")
        .put("dataTransfer", new JSONObject().put("products", true).put("contacts", false));
  }

  private static JSONObject checkoutResult() throws Exception {
    return new JSONObject().put("requestId", REQUEST_ID);
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
