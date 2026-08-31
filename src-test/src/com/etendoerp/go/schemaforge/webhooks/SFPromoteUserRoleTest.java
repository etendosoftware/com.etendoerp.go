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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.roles.UserRoleCompositionService;

/**
 * Unit tests for {@link SFPromoteUserRole}. Mirrors {@code SFAssignUserRolesTest}'s
 * {@code OBContext}-mocking convention for the access gate (rather than mocking {@code
 * NeoAccessHelper} directly — no webhook test in this package does that; the real static helper
 * is exercised through a mocked {@link OBContext}), and uses {@link
 * org.mockito.Mockito#mockConstruction} to intercept the {@code new UserRoleCompositionService()}
 * the webhook constructs internally — the service's own behavior is covered by {@code
 * UserRoleCompositionServiceTest} (Tasks 1-2, ETP-5019); this class only has to prove the webhook
 * wires parameters/results/errors correctly, and dispatches to the RIGHT service method per
 * {@code Mode} value.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFPromoteUserRoleTest {

  private MockedStatic<OBContext> obContextMock;
  private OBContext mockContext;
  private SFPromoteUserRole webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    mockContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

    webhook = new SFPromoteUserRole();
    parameters = new HashMap<>();
    responseVars = new HashMap<>();
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
  }

  private Role givenRestrictedRole() {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("restricted-role");
    when(mockContext.getRole()).thenReturn(role);
    return role;
  }

  private Role givenClientAdminRole() {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("admin-role");
    when(role.isClientAdmin()).thenReturn(true);
    when(mockContext.getRole()).thenReturn(role);
    return role;
  }

  // ── access gate ─────────────────────────────────────────────────────────

  @Test
  void noRoleAssignedIsDeniedWithoutTouchingTheService() {
    when(mockContext.getRole()).thenReturn(null);
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "promote");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("Not authorized", result.optString("message"));
  }

  @Test
  void restrictedRoleIsDeniedWithoutTouchingTheService() {
    givenRestrictedRole();
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "promote");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
  }

  // ── parameter validation ────────────────────────────────────────────────

  @Test
  void missingUserIdIsRejectedBeforeConstructingTheService() {
    givenClientAdminRole();
    parameters.put("Mode", "promote");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertTrue(result.optString("message").contains("UserId"));
  }

  @Test
  void unknownModeIsRejectedBeforeConstructingTheService() {
    givenClientAdminRole();
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "not-a-real-mode");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertTrue(result.optString("message").toLowerCase().contains("mode"));
  }

  @Test
  void missingModeIsRejectedBeforeConstructingTheService() {
    givenClientAdminRole();
    parameters.put("UserId", "target-1");
    // Mode intentionally absent.

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
  }

  // ── dispatch by Mode ────────────────────────────────────────────────────

  /**
   * Proves {@code Mode=promote} calls {@link UserRoleCompositionService#promoteToAdmin} —
   * and only that method, never {@code demoteFromAdmin} — with the caller's user id, resolved
   * role, and target user id forwarded verbatim.
   */
  @Test
  void promoteModeCallsPromoteToAdminWithForwardedArgs() {
    Role currentRole = givenClientAdminRole();
    User callerUser = mock(User.class);
    when(callerUser.getId()).thenReturn("acting-admin-1");
    when(mockContext.getUser()).thenReturn(callerUser);
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "promote");

    UserRoleCompositionService.AssignmentResult delegateResult =
        new UserRoleCompositionService.AssignmentResult("target-1", "admin-role-1",
            Collections.emptyList(), 0, 0);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.promoteToAdmin("acting-admin-1", currentRole, "target-1"))
                .thenReturn(delegateResult))) {
      webhook.get(parameters, responseVars);

      assertEquals(1, construction.constructed().size());
      UserRoleCompositionService constructedService = construction.constructed().get(0);
      verify(constructedService).promoteToAdmin("acting-admin-1", currentRole, "target-1");
      verify(constructedService, never()).demoteFromAdmin(anyString(), any(), anyString());
    }

    JSONObject result = resultOf(responseVars);
    assertTrue(result.optBoolean("success"));
    assertEquals("target-1", result.optString("userId"));
    assertEquals("admin-role-1", result.optString("roleId"));
  }

  /**
   * Proves {@code Mode=demote} calls {@link UserRoleCompositionService#demoteFromAdmin} —
   * and only that method, never {@code promoteToAdmin} — with the caller's user id, resolved
   * role, and target user id forwarded verbatim.
   */
  @Test
  void demoteModeCallsDemoteFromAdminWithForwardedArgs() {
    Role currentRole = givenClientAdminRole();
    User callerUser = mock(User.class);
    when(callerUser.getId()).thenReturn("acting-admin-1");
    when(mockContext.getUser()).thenReturn(callerUser);
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "demote");

    UserRoleCompositionService.AssignmentResult delegateResult =
        new UserRoleCompositionService.AssignmentResult("target-1", "personal-role-1",
            Collections.emptyList(), 0, 0);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.demoteFromAdmin("acting-admin-1", currentRole, "target-1"))
                .thenReturn(delegateResult))) {
      webhook.get(parameters, responseVars);

      assertEquals(1, construction.constructed().size());
      UserRoleCompositionService constructedService = construction.constructed().get(0);
      verify(constructedService).demoteFromAdmin("acting-admin-1", currentRole, "target-1");
      verify(constructedService, never()).promoteToAdmin(anyString(), any(), anyString());
    }

    JSONObject result = resultOf(responseVars);
    assertTrue(result.optBoolean("success"));
    assertEquals("target-1", result.optString("userId"));
    assertEquals("personal-role-1", result.optString("roleId"));
  }

  @Test
  void callerUserIdIsNullWhenNoUserOnContext() {
    Role currentRole = givenClientAdminRole();
    // mockContext.getUser() left unstubbed -> returns null.
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "promote");

    UserRoleCompositionService.AssignmentResult delegateResult =
        new UserRoleCompositionService.AssignmentResult("target-1", "admin-role-1",
            Collections.emptyList(), 0, 0);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.promoteToAdmin(null, currentRole, "target-1"))
                .thenReturn(delegateResult))) {
      webhook.get(parameters, responseVars);

      UserRoleCompositionService constructedService = construction.constructed().get(0);
      verify(constructedService).promoteToAdmin(null, currentRole, "target-1");
    }

    JSONObject result = resultOf(responseVars);
    assertTrue(result.optBoolean("success"));
  }

  // ── domain validation failures fold into a 200 success:false result ─────

  @Test
  void domainValidationFailureBecomesSuccessFalseResultNotBridgeError() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "promote");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.promoteToAdmin(null, currentRole, "target-1"))
                .thenThrow(new OBException("The owner already has the Admin role: "
                    + "target-1")))) {
      webhook.get(parameters, responseVars);
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertTrue(result.optString("message").contains("already has the Admin role"));
  }

  @Test
  void unexpectedExceptionSurfacesAsBridgeError() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "target-1");
    parameters.put("Mode", "demote");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.demoteFromAdmin(null, currentRole, "target-1"))
                .thenThrow(new RuntimeException("boom")))) {
      webhook.get(parameters, responseVars);
    }

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
