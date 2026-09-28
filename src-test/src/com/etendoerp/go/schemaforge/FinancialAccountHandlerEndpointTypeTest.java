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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * ETP-5468 phase 1 — {@link FinancialAccountHandler} must only apply its account create / update /
 * delete rules to the entity's own CRUD. Sub-endpoints (button actions, callouts, display-logic
 * evaluation, selectors) also reach the hook with {@code httpMethod=POST}; before this change a
 * button call through {@code neo_action} was run through {@code validateAndEnrichCreate} and the
 * post-hook tried to provision a "new" account.
 */
@DisplayName("FinancialAccountHandler — CRUD vs sub-endpoint (ETP-5468)")
class FinancialAccountHandlerEndpointTypeTest {

  private static final String SPEC = "financial-account";

  private FinancialAccountHandler handler;
  private final NeoResponse createSentinel = NeoResponse.error(299, "create-validated");

  @BeforeEach
  void setUp() throws Exception {
    handler = spy(new FinancialAccountHandler());
    doNothing().when(handler).enterAdminMode();
    doNothing().when(handler).exitAdminMode();
    doNothing().when(handler).doRollbackAndClose();
    doReturn(createSentinel).when(handler).validateAndEnrichCreate(any());
    // afterHandle: stop right after the admin-mode entry — no created id, nothing provisioned.
    doReturn(null).when(handler).extractCreatedId(any());
  }

  @AfterEach
  void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  private static NeoContext ctx(String method, NeoEndpointType type) {
    return NeoContext.builder()
        .specName(SPEC)
        .entityName("account")
        .httpMethod(method)
        .recordId("ACC-1")
        .requestBody(new JSONObject())
        .endpointType(type)
        .fieldName(type == NeoEndpointType.ACTION ? "aPRMProcessed" : null)
        .build();
  }

  // ── isCrudRequest ──────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @EnumSource(NeoEndpointType.class)
  @DisplayName("isCrudRequest is true only for CRUD")
  void isCrudRequestPerType(NeoEndpointType type) {
    assertEquals(type == NeoEndpointType.CRUD,
        NeoEndpointTypes.isCrud(ctx("POST", type)));
  }

  @Test
  @DisplayName("isCrudRequest treats a null endpoint type as CRUD (batch / clone callers)")
  void nullIsCrud() {
    assertTrue(NeoEndpointTypes.isCrud(ctx("POST", null)));
  }

  // ── handle ─────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = NeoEndpointType.class,
      names = { "ACTION", "CALLOUT", "EVALUATE_DISPLAY", "SELECTOR", "DEFAULTS" })
  @DisplayName("handle: a POST to a sub-endpoint skips the account rules entirely")
  void handleSkipsSubEndpoints(NeoEndpointType type) throws Exception {
    assertNull(handler.handle(ctx("POST", type)));
    verify(handler, never()).validateAndEnrichCreate(any());
    verify(handler, never()).enterAdminMode();
    verify(handler, never()).doRollbackAndClose();
  }

  @Test
  @DisplayName("handle: PUT/DELETE on a sub-endpoint are skipped too")
  void handleSkipsSubEndpointUpdateAndDelete() throws Exception {
    assertNull(handler.handle(ctx("PUT", NeoEndpointType.ACTION)));
    assertNull(handler.handle(ctx("DELETE", NeoEndpointType.CALLOUT)));
    verify(handler, never()).validateAndEnrichUpdate(any(), any());
    verify(handler, never()).deleteAccount(any());
  }

  @Test
  @DisplayName("handle: a CRUD POST still runs the create validation")
  void handleCrudPostValidates() throws Exception {
    assertSame(createSentinel, handler.handle(ctx("POST", NeoEndpointType.CRUD)));
    verify(handler).validateAndEnrichCreate(any());
    verify(handler).enterAdminMode();
    verify(handler).exitAdminMode();
  }

  @Test
  @DisplayName("handle: a POST with no endpoint type (internal callers) still runs the create validation")
  void handleNullTypePostValidates() throws Exception {
    assertSame(createSentinel, handler.handle(ctx("POST", null)));
    verify(handler).validateAndEnrichCreate(any());
  }

  @Test
  @DisplayName("handle: another spec is never touched")
  void handleOtherSpec() throws Exception {
    NeoContext other = NeoContext.builder().specName("sales-order").httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD).build();
    assertNull(handler.handle(other));
    verify(handler, never()).validateAndEnrichCreate(any());
  }

  // ── afterHandle ────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = NeoEndpointType.class,
      names = { "ACTION", "CALLOUT", "EVALUATE_DISPLAY", "SELECTOR" })
  @DisplayName("afterHandle: a POST to a sub-endpoint never provisions an account")
  void afterHandleSkipsSubEndpoints(NeoEndpointType type) {
    assertNull(handler.afterHandle(ctx("POST", type)));
    verify(handler, never()).enterAdminMode();
    verify(handler, never()).extractCreatedId(any());
  }

  @Test
  @DisplayName("afterHandle: a CRUD POST still goes through the provisioning path")
  void afterHandleCrudPostProvisions() {
    assertNull(handler.afterHandle(ctx("POST", NeoEndpointType.CRUD)));
    verify(handler).enterAdminMode();
    verify(handler).extractCreatedId(any());
    verify(handler).exitAdminMode();
  }

  @Test
  @DisplayName("afterHandle: a POST with no endpoint type still goes through the provisioning path")
  void afterHandleNullTypeProvisions() {
    assertNull(handler.afterHandle(ctx("POST", null)));
    verify(handler).extractCreatedId(any());
  }

  @Test
  @DisplayName("afterHandle: a non-POST sub-endpoint is not provisioned either")
  void afterHandleNonPostSubEndpoint() {
    assertNull(handler.afterHandle(ctx("PUT", NeoEndpointType.ACTION)));
    assertFalse(Mockito.mockingDetails(handler).getInvocations().stream()
        .anyMatch(inv -> "extractCreatedId".equals(inv.getMethod().getName())));
  }
}
