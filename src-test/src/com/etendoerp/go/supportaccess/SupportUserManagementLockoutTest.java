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
package com.etendoerp.go.supportaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.rest.CompanyInvitationService;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.handlers.ContactHandler;
import com.etendoerp.go.schemaforge.handlers.UserRoleAssignmentHandler;

/**
 * ETP-5351 (Vigia H1) — a support session cannot leave persistent access behind: no user
 * creation, no email/username/defaultRole writes, no role grants, no invitations. Outside a
 * support session the same guard lets everything through. No database.
 */
class SupportUserManagementLockoutTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);
  private static final String TARGET = "TARGET00000000000000000000000001";
  private static final String EMAIL = "email";

  private static OBContext context(String userId) {
    User acting = mock(User.class);
    when(acting.getId()).thenReturn(userId);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(acting);
    when(context.getCurrentClient()).thenReturn(client);
    return context;
  }

  private static Role role() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    Role role = mock(Role.class);
    when(role.getClient()).thenReturn(client);
    return role;
  }

  private static NeoContext request(String method, JSONObject body, OBContext obContext) {
    return NeoContext.builder().endpointType(NeoEndpointType.CRUD).httpMethod(method)
        .recordId(TARGET).requestBody(body).obContext(obContext).build();
  }

  @Test
  void guardRecognisesOnlyTheSupportActor() {
    assertTrue(SupportAccessGuard.isActingSupportUser(context(SUPPORT_USER)));
    assertFalse(SupportAccessGuard.isActingSupportUser(context(TARGET)));
    assertFalse(SupportAccessGuard.isActingSupportUser((OBContext) null));
    assertTrue(SupportAccessGuard.isActingSupportUser(SUPPORT_USER, role()));
    assertFalse(SupportAccessGuard.isActingSupportUser(TARGET, role()));
    assertFalse(SupportAccessGuard.isActingSupportUser(SUPPORT_USER, null));
  }

  /** Runs the body with a stored user (email Op@X.com, username op, default role ROLE1). */
  private static void withStoredUser(Runnable body) {
    Role defaultRole = mock(Role.class);
    when(defaultRole.getId()).thenReturn("ROLE1");
    User stored = mock(User.class);
    when(stored.getEmail()).thenReturn("Op@X.com");
    when(stored.getUsername()).thenReturn("op");
    when(stored.getDefaultRole()).thenReturn(defaultRole);
    Role wsRole = mock(Role.class);
    when(wsRole.getId()).thenReturn("WSR1");
    when(stored.get("active")).thenReturn(true);
    when(stored.get("locked")).thenReturn(false);
    when(stored.get("isPasswordExpired")).thenReturn(false);
    when(stored.get("eTGOIsOwner")).thenReturn(false);
    when(stored.get("grantPortalAccess")).thenReturn(false);
    when(stored.get("smfswsDefaultWsRole")).thenReturn(wsRole);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, TARGET)).thenReturn(stored);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenReturn(obDal);
      body.run();
    }
  }

  @Test
  void onlyAChangedAccessFieldCounts() {
    withStoredUser(() -> {
      try {
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put(EMAIL, " op@x.com "), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("username", "OP"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("defaultRole", "ROLE1"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("phone", "1"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(null, TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put(EMAIL, "o@y.com"), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("username", "u"), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("defaultRole", "R2"), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put(EMAIL, ""), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put(EMAIL, "a@b.c"), "UNKNOWN"));
        // password: any non-blank value is a change; blank/null/absent is not
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("password", "x"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("password", ""), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("password", "  "), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("password", JSONObject.NULL), TARGET));
        // flags: stored active=true, locked=false, owner=false, ws role WSR1
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("active", true), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("active", "Y"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("locked", "false"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject().put("eTGOIsOwner", "N"), TARGET));
        assertFalse(SupportAccessGuard.changesAccessFields(
            new JSONObject().put("smfswsDefaultWsRole", new JSONObject().put("id", "WSR1")), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("active", false), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("locked", "Y"), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("isPasswordExpired", true), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("eTGOIsOwner", true), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("grantPortalAccess", "Y"), TARGET));
        assertTrue(SupportAccessGuard.changesAccessFields(new JSONObject().put("smfswsDefaultWsRole", "WSR2"), TARGET));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
  }

  @Test
  void userSpecRefusesCreateAndChangedAccessFieldsInSupportSession() {
    withStoredUser(() -> {
      try {
        UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
        OBContext support = context(SUPPORT_USER);
        assertEquals(403, handler.handle(request("POST", new JSONObject().put(EMAIL, "a@b.c"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PATCH", new JSONObject().put(EMAIL, "o@y.com"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PUT", new JSONObject().put("username", "u"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PATCH", new JSONObject().put("defaultRole", "R2"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PATCH", new JSONObject().put("password", "known"), support)).getHttpStatus());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
  }

  @Test
  void userSpecCreateIsNotRefusedOutsideSupportSession() throws Exception {
    NeoResponse response = new UserRoleAssignmentHandler()
        .handle(request("POST", new JSONObject(), context(TARGET)));
    // Missing email is a plain 400, proving the support guard did not fire.
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  void contactSpecRefusesCreateAndChangedAccessFieldsInSupportSession() {
    withStoredUser(() -> {
      try {
        ContactHandler handler = new ContactHandler();
        OBContext support = context(SUPPORT_USER);
        assertEquals(403, handler.handle(request("POST", new JSONObject().put("name", "n"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PATCH", new JSONObject().put(EMAIL, "o@x.com"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PATCH", new JSONObject().put("password", "known"), support)).getHttpStatus());
        assertEquals(403, handler.handle(request("PATCH", new JSONObject().put("locked", true), support)).getHttpStatus());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
  }

  @Test
  void contactDeleteIsRefusedInSupportSessionAndUnchangedForOthers() {
    NeoContext asSupport = request("DELETE", null, context(SUPPORT_USER));
    NeoContext asOther = request("DELETE", null, context(TARGET));
    assertEquals(403, new ContactHandler().handle(asSupport).getHttpStatus());
    assertEquals(null, new ContactHandler().handle(asOther));
  }

  @Test
  void actionsOnUserAndContactAreRefusedOnlyInSupportSession() {
    NeoContext asSupport = NeoContext.builder().endpointType(NeoEndpointType.ACTION).httpMethod("POST")
        .recordId(TARGET).fieldName("grantPortalAccess").obContext(context(SUPPORT_USER)).build();
    NeoContext asOther = NeoContext.builder().endpointType(NeoEndpointType.ACTION).httpMethod("POST")
        .recordId(TARGET).fieldName("grantPortalAccess").obContext(context(TARGET)).build();
    assertEquals(403, new UserRoleAssignmentHandler().handle(asSupport).getHttpStatus());
    assertEquals(403, new ContactHandler().handle(asSupport).getHttpStatus());
    assertEquals(null, new UserRoleAssignmentHandler().handle(asOther));
    assertEquals(null, new ContactHandler().handle(asOther));
  }

  @Test
  void roleServiceRefusesSupportCaller() {
    UserRoleCompositionService service = new UserRoleCompositionService();
    Role callerRole = role();
    assertThrows(OBException.class,
        () -> service.promoteToAdmin(SUPPORT_USER, callerRole, TARGET));
    assertThrows(OBException.class,
        () -> service.demoteFromAdmin(SUPPORT_USER, callerRole, TARGET));
    assertThrows(OBException.class, () -> service.assignTemplateRoles(TARGET,
        java.util.List.of(), callerRole, SUPPORT_USER));
  }

  @Test
  void invitationsRefuseSupportInviter() throws Exception {
    CompanyInvitationService service = new CompanyInvitationService();
    JSONObject created = service.createInvitationForNewlyCreatedUser(context(SUPPORT_USER),
        "nuevo@example.com", null, null);
    assertEquals(403, created.getInt("httpStatus"));
    assertEquals(SupportAccessGuard.ERROR_CODE_FORBIDDEN, created.getString("code"));
    JSONObject resent = service.resendInvitation(context(SUPPORT_USER), TARGET, null, null);
    assertEquals(SupportAccessGuard.ERROR_CODE_FORBIDDEN, resent.getString("code"));
  }
}
