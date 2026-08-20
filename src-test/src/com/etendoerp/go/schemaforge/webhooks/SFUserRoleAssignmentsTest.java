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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
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
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.roles.UserRoleCompositionService;

/**
 * Unit tests for {@link SFUserRoleAssignments}. Mirrors {@code SFAssignUserRolesTest}'s
 * {@code OBContext}-mocking convention for the access gate, and uses {@link
 * org.mockito.Mockito#mockConstruction} to intercept the {@code new
 * UserRoleCompositionService()} the webhook constructs internally — {@link
 * UserRoleCompositionService}'s own resolution logic is covered by {@code
 * UserRoleCompositionServiceTest}/{@code UserRoleCompositionServiceIntegrationTest}; this class
 * only has to prove the webhook wires parameters/results/errors/modes correctly.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFUserRoleAssignmentsTest {

  private MockedStatic<OBContext> obContextMock;
  private OBContext mockContext;
  private SFUserRoleAssignments webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    mockContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

    webhook = new SFUserRoleAssignments();
    parameters = new HashMap<>();
    responseVars = new HashMap<>();
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
  }

  private Role givenClientAdminRole(String clientId) {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("admin-role");
    when(role.isClientAdmin()).thenReturn(true);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(role.getClient()).thenReturn(client);
    when(mockContext.getRole()).thenReturn(role);
    return role;
  }

  private Role givenRestrictedRole() {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("restricted-role");
    when(mockContext.getRole()).thenReturn(role);
    return role;
  }

  private static JSONObject resultOf(Map<String, String> responseVars) {
    try {
      return new JSONObject(responseVars.get("result"));
    } catch (Exception e) {
      throw new IllegalStateException("Test expected a 'result' entry", e);
    }
  }

  // ── access gate ─────────────────────────────────────────────────────────

  @Test
  void noRoleAssignedIsDeniedWithBulkEmptyShapeWithoutTouchingTheService() {
    when(mockContext.getRole()).thenReturn(null);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertTrue(result.has("assignments"));
    assertEquals(0, result.optJSONObject("assignments").length());
  }

  @Test
  void restrictedRoleIsDeniedWithSingleEmptyShapeWhenUserIdRequested() {
    givenRestrictedRole();
    parameters.put("UserId", "user-1");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertEquals("user-1", result.optString("userId"));
    assertEquals(0, result.optJSONArray("templateRoleIds").length());
  }

  // ── bulk mode ────────────────────────────────────────────────────────────

  @Test
  void bulkModeReturnsAssignmentsForEveryUserOfCallerOwnClient() {
    givenClientAdminRole("client-A");

    Map<String, List<String>> assignments = new LinkedHashMap<>();
    assignments.put("user-1", List.of("tpl-finance", "tpl-sales"));
    assignments.put("user-2", List.of());

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.getAppliedTemplateRoleIdsForClient("client-A"))
                .thenReturn(assignments))) {
      webhook.get(parameters, responseVars);

      assertEquals(1, construction.constructed().size());
    }

    JSONObject result = resultOf(responseVars);
    JSONObject assignmentsJson = result.optJSONObject("assignments");
    assertEquals(2, assignmentsJson.length());
    JSONArray user1Roles = assignmentsJson.optJSONArray("user-1");
    assertEquals(2, user1Roles.length());
    assertEquals("tpl-finance", user1Roles.optString(0));
    assertEquals(0, assignmentsJson.optJSONArray("user-2").length());
  }

  // ── single mode ──────────────────────────────────────────────────────────

  @Test
  void singleModeReturnsTemplateRoleIdsForRequestedUser() {
    Role currentRole = givenClientAdminRole("client-A");
    parameters.put("UserId", "user-1");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.getAppliedTemplateRoleIds("user-1", currentRole))
                .thenReturn(List.of("tpl-finance")))) {
      webhook.get(parameters, responseVars);

      org.mockito.Mockito.verify(construction.constructed().get(0))
          .getAppliedTemplateRoleIds("user-1", currentRole);
    }

    JSONObject result = resultOf(responseVars);
    assertEquals("user-1", result.optString("userId"));
    assertEquals(1, result.optJSONArray("templateRoleIds").length());
    assertEquals("tpl-finance", result.optJSONArray("templateRoleIds").optString(0));
  }

  /**
   * Proves the webhook forwards the caller's OWN resolved role through to the service's
   * boundary-checking overload — the exact wiring {@code SFAssignUserRoles}'
   * {@code forwardsCallerRoleToTheServiceForTheTenantBoundaryCheck} test guards on the write
   * side. Without this, {@code UserRoleCompositionService#enforceCallerClientBoundary} would
   * never actually run for a real request.
   */
  @Test
  void forwardsCallerRoleToTheServiceForTheTenantBoundaryCheck() {
    Role currentRole = givenClientAdminRole("client-A");
    parameters.put("UserId", "user-1");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.getAppliedTemplateRoleIds("user-1", currentRole))
                .thenReturn(List.of()))) {
      webhook.get(parameters, responseVars);

      org.mockito.Mockito.verify(construction.constructed().get(0))
          .getAppliedTemplateRoleIds("user-1", currentRole);
    }
  }

  /**
   * A cross-tenant read attempt ({@code UserRoleCompositionService#enforceCallerClientBoundary}
   * rejecting via {@code OBException}) folds into the single-mode empty-result shape — never a
   * raw error, and never a distinguishing message that would leak "this user exists in another
   * tenant" back to the caller.
   */
  @Test
  void crossTenantReadAttemptFoldsIntoEmptySingleResultNotBridgeError() {
    Role currentRole = givenClientAdminRole("client-A");
    parameters.put("UserId", "user-in-other-tenant");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.getAppliedTemplateRoleIds("user-in-other-tenant", currentRole))
                .thenThrow(new OBException(
                    "User belongs to a different client, cannot be targeted: "
                        + "user-in-other-tenant")))) {
      webhook.get(parameters, responseVars);
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals("user-in-other-tenant", result.optString("userId"));
    assertEquals(0, result.optJSONArray("templateRoleIds").length());
  }

  @Test
  void unknownUserIdFoldsIntoEmptySingleResultNotBridgeError() {
    Role currentRole = givenClientAdminRole("client-A");
    parameters.put("UserId", "missing-user");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.getAppliedTemplateRoleIds("missing-user", currentRole))
                .thenThrow(new OBException("User not found: missing-user")))) {
      webhook.get(parameters, responseVars);
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals(0, result.optJSONArray("templateRoleIds").length());
  }

  // ── unexpected failure ───────────────────────────────────────────────────

  @Test
  void unexpectedExceptionSurfacesAsBridgeError() {
    givenClientAdminRole("client-A");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.getAppliedTemplateRoleIdsForClient("client-A"))
                .thenThrow(new RuntimeException("boom")))) {
      webhook.get(parameters, responseVars);
    }

    assertEquals("boom", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
  }
}
