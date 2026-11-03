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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.oauth2.PublicApiKeyAttribution;
import com.etendoerp.go.usageevents.PublicApiCall;
import com.etendoerp.go.usageevents.PublicApiCaller;
import com.etendoerp.go.usageevents.PublicApiUsageCounter;

/**
 * Unit specs for {@link NeoPublicApiUsage}: an answered request is counted with the status the client
 * got, only when the authentication pipeline attributed it to a public API key, and the re-read the
 * public gateway makes after an action is not counted twice.
 *
 * <p>New class: no existing test covers it; {@code NeoServletProcessRequestTest} exercises the
 * servlet's {@code finally} around it and stays about the servlet.</p>
 *
 * @covers com.etendoerp.go.schemaforge.NeoPublicApiUsage
 */
class NeoPublicApiUsageTest {

  private static final PublicApiCaller KEY =
      new PublicApiCaller("TENANT", "ORG", "USER", "ROLE", "key-a");
  private static final PublicApiCaller OTHER_KEY =
      new PublicApiCaller("TENANT", "ORG", "USER", "ROLE", "key-b");
  private static final String ACTION_PATH = "/sales-order/order/REC1/action/complete";
  private static final String RECORD_PATH = "/sales-order/order/REC1";

  private PublicApiUsageCounter counter;
  private AtomicLong now;
  private NeoPublicApiUsage usage;

  @BeforeEach
  void setUp() {
    counter = mock(PublicApiUsageCounter.class);
    now = new AtomicLong(1_000_000L);
    usage = new NeoPublicApiUsage(counter, now::get);
  }

  private static HttpServletRequest request(PublicApiCaller caller, String path) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(PublicApiKeyAttribution.REQUEST_ATTRIBUTE)).thenReturn(caller);
    when(request.getPathInfo()).thenReturn(path);
    return request;
  }

  private static HttpServletResponse response(int status) {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(status);
    return response;
  }

  private void answer(PublicApiCaller caller, String path, String method, int status) {
    usage.recordRequest(request(caller, path), response(status), method);
  }

  private static PublicApiCall callOf(String action, String method, int status) {
    return PublicApiCall.of("sales-order", "order", action, method, status);
  }

  // ── what is counted ────────────────────────────────────────────────────

  @Test
  void aRequestWithoutAPublicKeyIsNotCounted() {
    answer(null, RECORD_PATH, "GET", 200);

    verify(counter, never()).count(any(), any());
  }

  @Test
  void anAnsweredRequestIsCountedWithItsFinalStatusAndNormalizedMethod() {
    answer(KEY, "/sales-order/order", "get", 200);

    verify(counter).count(KEY, callOf(null, "GET", 200));
  }

  @Test
  void everyStatusFamilyIsCounted() {
    for (int status : new int[] { 200, 201, 204, 400, 403, 404, 422, 500, 503 }) {
      answer(KEY, "/sales-order/order", "POST", status);
      verify(counter).count(KEY, callOf(null, "POST", status));
    }
  }

  @Test
  void anActionRouteCarriesItsActionName() {
    answer(KEY, ACTION_PATH, "POST", 403);

    verify(counter).count(KEY, callOf("complete", "POST", 403));
  }

  @Test
  void aNullMethodIsStillCounted() {
    answer(KEY, "/sales-order/order", null, 200);

    verify(counter).count(KEY, PublicApiCall.of("sales-order", "order", null, null, 200));
  }

  @Test
  void aFailureCountingNeverReachesTheCaller() {
    doThrow(new IllegalStateException("boom")).when(counter).count(any(), any());
    HttpServletRequest broken = mock(HttpServletRequest.class);
    when(broken.getAttribute(PublicApiKeyAttribution.REQUEST_ATTRIBUTE))
        .thenThrow(new IllegalStateException("boom"));

    assertDoesNotThrow(() -> answer(KEY, RECORD_PATH, "GET", 200));
    assertDoesNotThrow(() -> usage.recordRequest(broken, response(200), "GET"));
  }

  // ── the re-read of an action ───────────────────────────────────────────

  @Test
  void aSuccessfulActionFollowedByAnImmediateGetOfTheSameRecordCountsOnce() {
    answer(KEY, ACTION_PATH, "POST", 200);
    answer(KEY, RECORD_PATH, "GET", 200);

    verify(counter, times(1)).count(any(), any());
    verify(counter).count(KEY, callOf("complete", "POST", 200));
    assertEquals(0, usage.rereadCredits());
  }

  @Test
  void theCreditLastsThirtySecondsAndNotMore() {
    answer(KEY, ACTION_PATH, "POST", 200);
    now.addAndGet(NeoPublicApiUsage.REREAD_WINDOW_MS);
    answer(KEY, RECORD_PATH, "GET", 200);
    verify(counter, times(1)).count(any(), any());

    answer(KEY, ACTION_PATH, "POST", 200);
    now.addAndGet(NeoPublicApiUsage.REREAD_WINDOW_MS + 1);
    answer(KEY, RECORD_PATH, "GET", 200);
    verify(counter).count(KEY, callOf(null, "GET", 200));
  }

  @Test
  void aGetByAnotherKeyIsCounted() {
    answer(KEY, ACTION_PATH, "POST", 200);
    answer(OTHER_KEY, RECORD_PATH, "GET", 200);

    verify(counter).count(OTHER_KEY, callOf(null, "GET", 200));
  }

  @Test
  void aGetOfAnotherRecordIsCounted() {
    answer(KEY, ACTION_PATH, "POST", 200);
    answer(KEY, "/sales-order/order/REC2", "GET", 200);

    verify(counter).count(KEY, callOf(null, "GET", 200));
    assertEquals(1, usage.rereadCredits());
  }

  @Test
  void aGetOfTheSameRecordInAnotherSpecIsCounted() {
    answer(KEY, ACTION_PATH, "POST", 200);
    answer(KEY, "/purchase-order/order/REC1", "GET", 200);

    verify(counter, times(1)).count(KEY, PublicApiCall.of("purchase-order", "order", null, "GET",
        200));
  }

  @Test
  void theSameKeyIdOfAnotherTenantDoesNotConsumeTheCredit() {
    PublicApiCaller otherTenant = new PublicApiCaller("OTHER-TENANT", "ORG", "USER", "ROLE", "key-a");
    answer(KEY, ACTION_PATH, "POST", 200);

    answer(otherTenant, RECORD_PATH, "GET", 200);

    assertEquals(1, usage.rereadCredits(), "the credit of the real tenant is intact");
    verify(counter).count(otherTenant, callOf(null, "GET", 200));
    answer(KEY, RECORD_PATH, "GET", 200);
    assertEquals(0, usage.rereadCredits());
  }

  @Test
  void aGetOfAListNeverConsumesTheCredit() {
    answer(KEY, ACTION_PATH, "POST", 200);
    answer(KEY, "/sales-order/order", "GET", 200);

    assertEquals(1, usage.rereadCredits());
    verify(counter).count(KEY, callOf(null, "GET", 200));
  }

  @Test
  void aFailedActionGrantsNoCredit() {
    for (int status : new int[] { 400, 403, 404, 500 }) {
      answer(KEY, ACTION_PATH, "POST", status);
    }
    assertEquals(0, usage.rereadCredits());

    answer(KEY, RECORD_PATH, "GET", 200);
    verify(counter).count(KEY, callOf(null, "GET", 200));
  }

  @Test
  void aWriteThatIsNotAnActionGrantsNoCredit() {
    answer(KEY, RECORD_PATH, "POST", 200);
    answer(KEY, RECORD_PATH, "PUT", 200);

    assertEquals(0, usage.rereadCredits());
  }

  @Test
  void theCreditIsSingleUse() {
    answer(KEY, ACTION_PATH, "POST", 200);
    answer(KEY, RECORD_PATH, "GET", 200);
    answer(KEY, RECORD_PATH, "GET", 200);

    // the action and the second GET; the first GET rode on the credit
    verify(counter, times(2)).count(any(), any());
    verify(counter).count(KEY, callOf(null, "GET", 200));
  }

  @Test
  void theEntityOfTheCreditIsMatchedCaseBlind() {
    answer(KEY, "/sales-order/Order/REC1/action/complete", "POST", 200);
    answer(KEY, "/sales-order/order/REC1", "GET", 200);

    verify(counter, times(1)).count(any(), any());
  }

  @Test
  void pastTheCreditCapNoCreditIsGrantedSoTheReadIsCountedNeverLost() {
    for (int i = 0; i < NeoPublicApiUsage.MAX_REREAD_CREDITS; i++) {
      answer(KEY, "/sales-order/order/R" + i + "/action/complete", "POST", 200);
    }
    assertEquals(NeoPublicApiUsage.MAX_REREAD_CREDITS, usage.rereadCredits());

    answer(KEY, "/sales-order/order/OVER/action/complete", "POST", 200);
    assertEquals(NeoPublicApiUsage.MAX_REREAD_CREDITS, usage.rereadCredits());
    answer(KEY, "/sales-order/order/OVER", "GET", 200);

    verify(counter).count(KEY, callOf(null, "GET", 200));
  }

  @Test
  void expiredCreditsMakeRoomAtTheCap() {
    for (int i = 0; i < NeoPublicApiUsage.MAX_REREAD_CREDITS; i++) {
      answer(KEY, "/sales-order/order/R" + i + "/action/complete", "POST", 200);
    }
    now.addAndGet(NeoPublicApiUsage.REREAD_WINDOW_MS + 1);

    answer(KEY, "/sales-order/order/NEW/action/complete", "POST", 200);

    assertEquals(1, usage.rereadCredits());
    answer(KEY, "/sales-order/order/NEW", "GET", 200);
    assertEquals(0, usage.rereadCredits());
  }

  // ── the re-read credit key ─────────────────────────────────────────────

  private static NeoServlet.NeoPathInfo path(String spec, String entity, String recordId) {
    return new NeoServlet.NeoPathInfo(spec, entity, recordId);
  }

  @Test
  void theCreditKeyIs64HexCharactersWhateverTheRecordIdLength() {
    for (String id : new String[] { "1", "REC1", "x".repeat(36), "y".repeat(10_000) }) {
      String key = NeoPublicApiUsage.creditKey(KEY, path("sales-order", "order", id));

      assertEquals(64, key.length(), "id length " + id.length());
      assertTrue(key.matches("[0-9a-f]{64}"), key);
    }
  }

  @Test
  void theSameRecordGivesTheSameKeyAndAnyOtherPartGivesAnother() {
    String base = NeoPublicApiUsage.creditKey(KEY, path("sales-order", "order", "REC1"));

    assertEquals(base, NeoPublicApiUsage.creditKey(KEY, path("sales-order", "order", "REC1")));
    assertNotEquals(base, NeoPublicApiUsage.creditKey(KEY, path("sales-order", "order", "REC2")));
    assertNotEquals(base,
        NeoPublicApiUsage.creditKey(OTHER_KEY, path("sales-order", "order", "REC1")));
    assertNotEquals(base, NeoPublicApiUsage.creditKey(KEY, path("purchase-order", "order", "REC1")));
    assertNotEquals(base, NeoPublicApiUsage.creditKey(KEY, path("sales-order", "lines", "REC1")));
    assertNotEquals(base, NeoPublicApiUsage.creditKey(
        new PublicApiCaller("OTHER", "ORG", "USER", "ROLE", "key-a"),
        path("sales-order", "order", "REC1")));
  }

  @Test
  void theEntityOfTheKeyIsCaseInsensitive() {
    assertEquals(NeoPublicApiUsage.creditKey(KEY, path("sales-order", "Order", "REC1")),
        NeoPublicApiUsage.creditKey(KEY, path("sales-order", "ORDER", "REC1")));
  }

  // ── batch: one request per operation ───────────────────────────────────

  /** A request whose attributes are really stored, so noteBatchOperations and record meet. */
  private static HttpServletRequest storingRequest(PublicApiCaller caller, String path) {
    Map<String, Object> attributes = new HashMap<>();
    if (caller != null) {
      attributes.put(PublicApiKeyAttribution.REQUEST_ATTRIBUTE, caller);
    }
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
    org.mockito.Mockito.doAnswer(inv -> {
      attributes.put(inv.getArgument(0), inv.getArgument(1));
      return null;
    }).when(request).setAttribute(org.mockito.ArgumentMatchers.anyString(), any());
    when(request.getPathInfo()).thenReturn(path);
    return request;
  }

  private static JSONArray operations(String... specAndEntity) throws Exception {
    JSONArray ops = new JSONArray();
    for (int i = 0; i < specAndEntity.length; i += 2) {
      ops.put(new JSONObject().put("id", "op" + i).put("spec", specAndEntity[i])
          .put("entity", specAndEntity[i + 1]));
    }
    return ops;
  }

  private void verifyBatchOperation(String spec, String entity, int status, int times) {
    verify(counter, times(times)).count(KEY, PublicApiCall.batchOperation(spec, entity, status));
  }

  @Test
  void aNonPublicRequestStoresNothingForABatch() throws Exception {
    HttpServletRequest request = storingRequest(null, "/batch");

    NeoPublicApiUsage.noteBatchOperations(request, operations("sales-order", "order"));

    verify(request, never()).setAttribute(org.mockito.ArgumentMatchers.anyString(), any());
  }

  @Test
  void noteBatchOperationsToleratesNullAndNonObjectOperations() throws Exception {
    HttpServletRequest request = storingRequest(KEY, "/batch");
    assertDoesNotThrow(() -> NeoPublicApiUsage.noteBatchOperations(request, null));
    assertDoesNotThrow(() -> NeoPublicApiUsage.noteBatchOperations(null, operations("a", "b")));

    JSONArray odd = new JSONArray().put((Object) null).put("text").put(42)
        .put(new JSONObject().put("spec", "sales-order").put("entity", "order"));
    assertDoesNotThrow(() -> NeoPublicApiUsage.noteBatchOperations(request, odd));
    usage.recordRequest(request, response(200), "POST");

    // one record per operation, odd ones with no spec/entity, the real one with its own
    verify(counter, times(4)).count(any(), any());
    verifyBatchOperation("sales-order", "order", 200, 1);
    verifyBatchOperation(null, null, 200, 3);
  }

  @Test
  void aBatchOfNOperationsMakesNRecordCallsOfKindBatchOperationWithTheirOwnSpecAndEntity()
      throws Exception {
    HttpServletRequest request = storingRequest(KEY, "/batch");
    NeoPublicApiUsage.noteBatchOperations(request,
        operations("sales-order", "order", "sales-order", "lines", "product", "product"));

    usage.recordRequest(request, response(200), "POST");

    verify(counter, times(3)).count(any(), any());
    verifyBatchOperation("sales-order", "order", 200, 1);
    verifyBatchOperation("sales-order", "lines", 200, 1);
    verifyBatchOperation("product", "product", 200, 1);
  }

  @Test
  void everyOperationCarriesTheStatusOfTheBatch() throws Exception {
    for (int status : new int[] { 200, 202, 409, 422, 500 }) {
      HttpServletRequest request = storingRequest(KEY, "/batch");
      NeoPublicApiUsage.noteBatchOperations(request, operations("sales-order", "order",
          "sales-order", "lines"));

      usage.recordRequest(request, response(status), "POST");

      verifyBatchOperation("sales-order", "order", status, 1);
      verifyBatchOperation("sales-order", "lines", status, 1);
    }
  }

  @Test
  void anEmptyBodyBatchAnswering400IsCountedOnceAsARequest() {
    HttpServletRequest request = storingRequest(KEY, "/batch");

    usage.recordRequest(request, response(400), "POST");

    verify(counter, times(1)).count(any(), any());
    org.mockito.ArgumentCaptor<PublicApiCall> call =
        org.mockito.ArgumentCaptor.forClass(PublicApiCall.class);
    verify(counter).count(org.mockito.ArgumentMatchers.eq(KEY), call.capture());
    assertEquals(PublicApiCall.KIND_REQUEST, call.getValue().kind());
    assertEquals(400, call.getValue().status());
  }

  @Test
  void theRereadCreditIsNeitherGrantedNorConsumedByABatch() throws Exception {
    answer(KEY, ACTION_PATH, "POST", 200);
    assertEquals(1, usage.rereadCredits());

    HttpServletRequest batch = storingRequest(KEY, "/batch");
    NeoPublicApiUsage.noteBatchOperations(batch, operations("sales-order", "order"));
    usage.recordRequest(batch, response(200), "POST");

    assertEquals(1, usage.rereadCredits(), "the action's credit is untouched");
    verifyBatchOperation("sales-order", "order", 200, 1);

    // and the credit still rides on the next re-read of that record
    answer(KEY, RECORD_PATH, "GET", 200);
    assertEquals(0, usage.rereadCredits());
    verify(counter, times(2)).count(any(), any());
  }
}
