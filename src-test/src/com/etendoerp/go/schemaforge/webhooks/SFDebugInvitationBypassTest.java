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

import com.etendoerp.go.rest.DebugInvitationBypassService;

/**
 * Unit tests for {@link SFDebugInvitationBypass}. Mirrors {@code SFAssignUserRolesTest}'s
 * {@code OBContext}-mocking convention for the access gate. {@link DebugInvitationBypassService}
 * is injected as a plain Mockito mock (via the package-private constructor) — its own real
 * behavior is covered by {@code DebugInvitationBypassServiceTest}; this class only has to prove
 * the webhook marshals parameters/results/errors correctly and never bypasses its own
 * defense-in-depth admin check.
 *
 * <p>The flag-off 404 case is NOT tested here — this webhook is never even constructed when the
 * flag is off (see {@code NeoPseudoSpecDispatcherTest#debugInvitationBypassIsA404WhenFlagIsOff}),
 * so there is nothing for this class to assert about it.</p>
 */
class SFDebugInvitationBypassTest {

  private MockedStatic<OBContext> obContextMock;
  private OBContext mockContext;
  private DebugInvitationBypassService service;
  private SFDebugInvitationBypass webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    mockContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

    service = mock(DebugInvitationBypassService.class);
    webhook = new SFDebugInvitationBypass(service);
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

  // ── access gate (defense-in-depth only — see class javadoc) ────────────────

  @Test
  void noRoleAssignedIsDeniedWithoutTouchingTheService() throws Exception {
    when(mockContext.getRole()).thenReturn(null);
    parameters.put("Action", "forceAccept");
    parameters.put("Email", "user@example.com");

    webhook.get(parameters, responseVars);

    verifyNoInteractions(service);
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("Not authorized", result.optString("message"));
  }

  @Test
  void restrictedRoleIsDeniedWithoutTouchingTheService() throws Exception {
    Role role = mock(Role.class);
    when(role.isClientAdmin()).thenReturn(false);
    when(mockContext.getRole()).thenReturn(role);
    parameters.put("Action", "forceAccept");
    parameters.put("Email", "user@example.com");

    webhook.get(parameters, responseVars);

    verifyNoInteractions(service);
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
  }

  // ── forceAccept delegation ───────────────────────────────────────────────

  @Test
  void forceAcceptDelegatesToTheServiceWithMarshalledParams() throws Exception {
    givenClientAdminRole();
    parameters.put("Action", "forceAccept");
    parameters.put("Email", "user@example.com");
    parameters.put("AdUserId", "user-1");
    parameters.put("Name", "QA Tester");

    JSONObject serviceResult = new JSONObject().put("success", true).put("accountId", "acct-1");
    when(service.forceAccept("user@example.com", "user-1", "QA Tester")).thenReturn(serviceResult);

    webhook.get(parameters, responseVars);

    verify(service).forceAccept("user@example.com", "user-1", "QA Tester");
    JSONObject result = resultOf(responseVars);
    assertTrue(result.getBoolean("success"));
    assertEquals("acct-1", result.getString("accountId"));
  }

  @Test
  void actionMatchingIsCaseInsensitive() throws Exception {
    givenClientAdminRole();
    parameters.put("Action", "FORCEACCEPT");
    parameters.put("Email", "user@example.com");
    when(service.forceAccept(any(), any(), any()))
        .thenReturn(new JSONObject().put("success", true));

    webhook.get(parameters, responseVars);

    verify(service).forceAccept("user@example.com", null, null);
  }

  // ── forceStatus delegation ───────────────────────────────────────────────

  @Test
  void forceStatusDelegatesToTheServiceWithMarshalledParams() throws Exception {
    givenClientAdminRole();
    parameters.put("Action", "forceStatus");
    parameters.put("InvitationId", "inv-1");
    parameters.put("Email", "user@example.com");
    parameters.put("Status", "SENT");

    JSONObject serviceResult = new JSONObject().put("success", true).put("status", "SENT");
    when(service.forceStatus("inv-1", "user@example.com", "SENT")).thenReturn(serviceResult);

    webhook.get(parameters, responseVars);

    verify(service).forceStatus("inv-1", "user@example.com", "SENT");
    JSONObject result = resultOf(responseVars);
    assertTrue(result.getBoolean("success"));
    assertEquals("SENT", result.getString("status"));
  }

  // ── unknown action / error handling ─────────────────────────────────────

  @Test
  void unknownActionFailsWithoutTouchingTheService() throws Exception {
    givenClientAdminRole();
    parameters.put("Action", "deleteEverything");

    webhook.get(parameters, responseVars);

    verifyNoInteractions(service);
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertTrue(result.getString("message").contains("deleteEverything"));
  }

  @Test
  void missingActionFailsWithoutTouchingTheService() throws Exception {
    givenClientAdminRole();

    webhook.get(parameters, responseVars);

    verifyNoInteractions(service);
    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
  }

  @Test
  void unexpectedServiceExceptionMapsToBridgeErrorNotAThrownException() throws Exception {
    givenClientAdminRole();
    parameters.put("Action", "forceAccept");
    parameters.put("Email", "user@example.com");
    when(service.forceAccept(eq("user@example.com"), any(), any()))
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
