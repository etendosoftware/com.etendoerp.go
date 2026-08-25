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
package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.rest.CompanyInvitationService;

/**
 * Unit tests for {@link SFResendInvitation}. Mirrors {@code SFDebugInvitationBypassTest}'s
 * {@code OBContext}-mocking convention for the access gate. {@link CompanyInvitationService} is
 * injected as a plain Mockito mock (via the package-private constructor) — its own real behavior
 * (eligibility gating, revoke-then-reissue) is covered by {@code CompanyInvitationServiceTest};
 * this class only has to prove the webhook marshals parameters/results/errors correctly and never
 * bypasses its own admin-role check.
 */
class SFResendInvitationTest {

  private MockedStatic<OBContext> obContextMock;
  private OBContext mockContext;
  private CompanyInvitationService service;
  private SFResendInvitation webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    mockContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

    service = mock(CompanyInvitationService.class);
    webhook = new SFResendInvitation(service);
    parameters = new HashMap<>();
    responseVars = new HashMap<>();
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
  }

  private void givenClientAdminRole() {
    Role role = mock(Role.class);
    when(role.isClientAdmin()).thenReturn(true);
    when(mockContext.getRole()).thenReturn(role);
  }

  // ── access gate ──────────────────────────────────────────────────────────

  @Test
  void noRoleAssignedIsDeniedWithoutTouchingTheService() throws Exception {
    when(mockContext.getRole()).thenReturn(null);
    parameters.put("AdUserId", "user-1");

    webhook.get(parameters, responseVars);

    verifyNoInteractions(service);
    JSONObject result = resultOf(responseVars);
    assertTrue(result.getBoolean("error"));
    assertEquals("Not authorized", result.optString("message"));
  }

  @Test
  void restrictedRoleIsDeniedWithoutTouchingTheService() throws Exception {
    Role role = mock(Role.class);
    when(role.isClientAdmin()).thenReturn(false);
    when(mockContext.getRole()).thenReturn(role);
    parameters.put("AdUserId", "user-1");

    webhook.get(parameters, responseVars);

    verifyNoInteractions(service);
    JSONObject result = resultOf(responseVars);
    assertTrue(result.getBoolean("error"));
  }

  // ── delegation ───────────────────────────────────────────────────────────

  @Test
  void delegatesToTheServiceWithMarshalledUserId() throws Exception {
    givenClientAdminRole();
    parameters.put("AdUserId", "user-1");

    JSONObject serviceResult = new JSONObject().put("status", "success")
        .put("invitation", new JSONObject().put("status", "SENT"));
    when(service.resendInvitation(eq(mockContext), eq("user-1"), any(), any()))
        .thenReturn(serviceResult);

    webhook.get(parameters, responseVars);

    verify(service).resendInvitation(mockContext, "user-1", null, null);
    JSONObject result = resultOf(responseVars);
    assertEquals("success", result.getString("status"));
  }

  @Test
  void missingUserIdIsMarshalledAsBlankNotNull() throws Exception {
    givenClientAdminRole();

    JSONObject serviceResult = new JSONObject().put("error", true)
        .put("message", "AD_User_ID is required");
    when(service.resendInvitation(eq(mockContext), eq(""), any(), any()))
        .thenReturn(serviceResult);

    webhook.get(parameters, responseVars);

    verify(service).resendInvitation(mockContext, "", null, null);
    JSONObject result = resultOf(responseVars);
    assertTrue(result.getBoolean("error"));
  }

  // ── error handling ───────────────────────────────────────────────────────

  @Test
  void unexpectedServiceExceptionMapsToBridgeErrorNotAThrownException() throws Exception {
    givenClientAdminRole();
    parameters.put("AdUserId", "user-1");
    when(service.resendInvitation(eq(mockContext), eq("user-1"), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    webhook.get(parameters, responseVars);

    assertEquals("boom", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
  }

  private static JSONObject resultOf(Map<String, String> responseVars) {
    try {
      return new JSONObject(responseVars.get("result"));
    } catch (Exception e) {
      throw new IllegalStateException("Test expected a 'result' entry", e);
    }
  }
}
