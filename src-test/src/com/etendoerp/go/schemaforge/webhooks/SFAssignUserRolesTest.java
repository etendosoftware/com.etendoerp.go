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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link SFAssignUserRoles}. Mirrors {@code SFWindowAccessMapTest}'s
 * {@code OBContext}-mocking convention for the access gate, and uses
 * {@link org.mockito.Mockito#mockConstruction} to intercept the {@code new
 * UserRoleCompositionService()} the webhook constructs internally — the service's own
 * behavior is covered by {@code UserRoleCompositionServiceIntegrationTest} (real DB); this class
 * only has to prove the webhook wires parameters/results/errors correctly.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFAssignUserRolesTest {

  private MockedStatic<OBContext> obContextMock;
  private OBContext mockContext;
  private SFAssignUserRoles webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    mockContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

    webhook = new SFAssignUserRoles();
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
    parameters.put("UserId", "user-1");

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
    parameters.put("UserId", "user-1");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class)) {
      webhook.get(parameters, responseVars);

      assertTrue(construction.constructed().isEmpty());
    }
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
  }

  // ── happy path ───────────────────────────────────────────────────────────

  @Test
  void adminRoleComposesAndReturnsAssignmentSummary() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "user-1");
    parameters.put("TemplateRoleIds", " tpl-finance , tpl-sales ,, ");

    UserRoleCompositionService.AssignmentResult delegateResult =
        new UserRoleCompositionService.AssignmentResult("user-1", "personal-1",
            Arrays.asList("tpl-finance", "tpl-sales"), 2, 0);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.assignTemplateRoles("user-1",
                Arrays.asList("tpl-finance", "tpl-sales"), currentRole))
                    .thenReturn(delegateResult))) {

      webhook.get(parameters, responseVars);

      assertEquals(1, construction.constructed().size());
    }

    JSONObject result = resultOf(responseVars);
    assertTrue(result.optBoolean("success"));
    assertEquals("user-1", result.optString("userId"));
    assertEquals("personal-1", result.optString("personalRoleId"));
    assertEquals(2, result.optInt("added"));
    assertEquals(0, result.optInt("removed"));
    assertEquals(2, result.optJSONArray("templateRoleIds").length());
  }

  /**
   * REVIEW cycle 1 blocker (B1, ETP-4852): {@code isAdminOrClientAdmin} alone does not stop a
   * client-admin from targeting another tenant's user — the tenant-boundary enforcement lives
   * in {@code UserRoleCompositionService#enforceCallerClientBoundary}, which needs the caller's
   * OWN role. This proves the webhook actually forwards the {@code currentRole} it already
   * resolved for the access gate through to the service, rather than silently dropping it (the
   * exact wiring gap that would have made the boundary check inert for every real request).
   */
  @Test
  void forwardsCallerRoleToTheServiceForTheTenantBoundaryCheck() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "user-1");
    parameters.put("TemplateRoleIds", "tpl-finance");

    UserRoleCompositionService.AssignmentResult delegateResult =
        new UserRoleCompositionService.AssignmentResult("user-1", "personal-1",
            List.of("tpl-finance"), 1, 0);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.assignTemplateRoles("user-1", List.of("tpl-finance"), currentRole))
                .thenReturn(delegateResult))) {
      webhook.get(parameters, responseVars);

      UserRoleCompositionService constructedService = construction.constructed().get(0);
      org.mockito.Mockito.verify(constructedService)
          .assignTemplateRoles("user-1", List.of("tpl-finance"), currentRole);
    }

    JSONObject result = resultOf(responseVars);
    assertTrue(result.optBoolean("success"));
  }

  @Test
  void missingUserIdIsRejectedBeforeConstructingTheService() {
    givenClientAdminRole();

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
  void emptyTemplateRoleIdsParameterMeansRevokeAll() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "user-1");
    // TemplateRoleIds intentionally absent — must resolve to an empty (not null) list.

    UserRoleCompositionService.AssignmentResult delegateResult =
        new UserRoleCompositionService.AssignmentResult("user-1", "personal-1",
            Collections.emptyList(), 0, 3);

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.assignTemplateRoles("user-1", Collections.emptyList(), currentRole))
                .thenReturn(delegateResult))) {
      webhook.get(parameters, responseVars);
    }

    JSONObject result = resultOf(responseVars);
    assertTrue(result.optBoolean("success"));
    assertEquals(3, result.optInt("removed"));
  }

  // ── domain validation failures fold into a 200 success:false result ─────

  @Test
  void domainValidationFailureBecomesSuccessFalseResultNotBridgeError() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "user-1");
    parameters.put("TemplateRoleIds", "not-a-template");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.assignTemplateRoles("user-1", List.of("not-a-template"),
                currentRole))
                    .thenThrow(new OBException("Role is not a template, cannot be composed: "
                        + "not-a-template")))) {
      webhook.get(parameters, responseVars);
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertTrue(result.optString("message").contains("not a template"));
  }

  @Test
  void unexpectedExceptionSurfacesAsBridgeError() {
    Role currentRole = givenClientAdminRole();
    parameters.put("UserId", "user-1");
    parameters.put("TemplateRoleIds", "tpl-finance");

    try (MockedConstruction<UserRoleCompositionService> construction =
        mockConstruction(UserRoleCompositionService.class, (mockService, ctx) ->
            when(mockService.assignTemplateRoles("user-1", List.of("tpl-finance"), currentRole))
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
