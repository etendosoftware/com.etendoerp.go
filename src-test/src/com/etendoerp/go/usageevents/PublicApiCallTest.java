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

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit specs for {@link PublicApiCall} (path normalization, the token pseudo-target, the overflow
 * call) and {@link PublicApiCaller} (an id-only value, equal by value so it can key a bucket).
 *
 * @covers com.etendoerp.go.usageevents.PublicApiCall
 * @covers com.etendoerp.go.usageevents.PublicApiCaller
 */
class PublicApiCallTest {

  @Test
  void segmentsKeepOnlySafeCharacters() {
    PublicApiCall call = PublicApiCall.of("sales order/../x", "or'der;--", "act ion!", "get", 200);

    assertEquals("salesorder..x", call.target());
    assertEquals("order--", call.entity());
    assertEquals("action", call.actionName());
    assertEquals("GET", call.method());
  }

  @Test
  void segmentsKeepDotsDashesAndUnderscores() {
    assertEquals("a_b-c.d", PublicApiCall.segment("a_b-c.d"));
  }

  @Test
  void aSegmentIsClippedToSixtyCharacters() {
    assertEquals(PublicApiCall.SEGMENT_MAX, PublicApiCall.segment("x".repeat(200)).length());
  }

  @Test
  void anEmptyOrJunkOnlySegmentBecomesNull() {
    assertNull(PublicApiCall.segment(null));
    assertNull(PublicApiCall.segment(""));
    assertNull(PublicApiCall.segment("///%%%   "));
    PublicApiCall call = PublicApiCall.of("//", null, "!!", "GET", 200);
    assertNull(call.target());
    assertNull(call.entity());
    assertNull(call.actionName());
  }

  @Test
  void theMethodIsUpperCasedAndClippedToTen() {
    assertEquals("PATCH", PublicApiCall.of("a", "b", null, "patch", 200).method());
    assertEquals(10, PublicApiCall.of("a", "b", null, "x".repeat(40), 200).method().length());
    assertNull(PublicApiCall.of("a", "b", null, null, 200).method());
  }

  @Test
  void aBatchOperationIsAPostOfItsOwnNormalizedSpecAndEntity() {
    PublicApiCall call = PublicApiCall.batchOperation("sales order/..", "or'der;", 207);

    assertEquals("salesorder..", call.target());
    assertEquals("order", call.entity());
    assertNull(call.actionName());
    assertEquals("POST", call.method());
    assertEquals(207, call.status());
    assertEquals(PublicApiCall.KIND_BATCH_OPERATION, call.kind());
    assertEquals("batchOperation", call.kind());
  }

  @Test
  void aBatchOperationWithNoSpecOrEntityKeepsNullsAndItsKind() {
    PublicApiCall call = PublicApiCall.batchOperation(null, "///", 200);

    assertNull(call.target());
    assertNull(call.entity());
    assertEquals(PublicApiCall.KIND_BATCH_OPERATION, call.kind());
  }

  @Test
  void aPlainCallIsOfKindRequestAndOverflowKeepsTheKind() {
    assertEquals(PublicApiCall.KIND_REQUEST, PublicApiCall.of("a", "b", null, "GET", 200).kind());
    assertEquals(PublicApiCall.KIND_BATCH_OPERATION,
        PublicApiCall.batchOperation("a", "b", 200).toOverflow().kind());
    assertEquals(PublicApiCall.KIND_TOKEN_EXCHANGE,
        PublicApiCall.tokenExchange(401).toOverflow().kind());
    assertEquals(PublicApiCall.KIND_REQUEST,
        PublicApiCall.of("a", "b", null, "GET", 200).toOverflow().kind());
  }

  @Test
  void callsOfDifferentKindsAreNeverEqual() {
    assertNotEquals(PublicApiCall.of("a", "b", null, "POST", 200),
        PublicApiCall.batchOperation("a", "b", 200));
  }

  @Test
  void errorsAreStatusesFrom400() {
    assertFalse(PublicApiCall.of("a", "b", null, "GET", 399).isError());
    assertTrue(PublicApiCall.of("a", "b", null, "GET", 400).isError());
    assertTrue(PublicApiCall.of("a", "b", null, "GET", 503).isError());
  }

  @Test
  void theTokenTargetIsTheOauth2Pseudo() {
    assertEquals("/oauth2/token", PublicApiCall.TARGET_TOKEN);
    PublicApiCall call = PublicApiCall.tokenExchange(401);
    assertEquals(PublicApiCall.TARGET_TOKEN, call.target());
    assertEquals(PublicApiCall.KIND_TOKEN_EXCHANGE, call.kind());
    // A request path can never reproduce the token target: the slash does not survive of().
    assertNotEquals(PublicApiCall.TARGET_TOKEN,
        PublicApiCall.of(PublicApiCall.TARGET_TOKEN, null, null, "POST", 401).target());
  }

  @Test
  void overflowReplacesThePathDimensionsAndKeepsMethodAndStatus() {
    PublicApiCall overflow = PublicApiCall.of("a", "b", "c", "put", 500).toOverflow();

    assertEquals("_other", overflow.target());
    assertEquals("_other", overflow.entity());
    assertNull(overflow.actionName());
    assertEquals("PUT", overflow.method());
    assertEquals(500, overflow.status());
  }

  @Test
  void aCallerIsEqualByValueAndHoldsIdsOnly() {
    PublicApiCaller one = new PublicApiCaller("C", "O", "U", "R", "K");
    PublicApiCaller same = new PublicApiCaller("C", "O", "U", "R", "K");

    assertEquals(one, same);
    assertEquals(one.hashCode(), same.hashCode());
    assertEquals(5, PublicApiCaller.class.getRecordComponents().length);
    assertEquals("K", one.keyId());
  }
}
