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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.rest.CompanyInvitationService;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.OwnerSupport;

/**
 * Unit tests for {@link UserRoleAssignmentHandler} — two independent post-hook concerns on the
 * {@code user} entity's single {@code JAVA_QUALIFIER} slot (see the handler's class javadoc).
 *
 * <p>Role sync (ETP-4512): covers the method/endpoint guard clauses in
 * {@link UserRoleAssignmentHandler#afterHandle}, the happy path where a brand-new
 * {@code AD_User_Roles} row is created for a user with none yet, the role-change path (existing
 * row for a different role is removed, exactly one new row exists for the new role), the
 * idempotency guarantee (already in sync -> no writes at all), the role-cleared path
 * ({@code Default_Ad_Role_ID} set to {@code null} -> existing row removed, no new row created),
 * and that unresolvable users never touch {@code AD_User_Roles}.
 *
 * <p>Bootstrap-user hiding (2026-07-27): covers that a {@code user} list GET has the "Admin"
 * ({@code id="100"}) and "System" ({@code id="0"}) rows removed with {@code totalRows} adjusted,
 * that a list with neither present is left untouched, that a single-record GET (whose
 * {@code data} is a lone object, not an array) is never altered, and that a missing/malformed
 * previous result degrades to a no-op rather than throwing.
 *
 * <p>Admin-initiated user creation (ETP-4829/ETP-4830): covers {@link
 * UserRoleAssignmentHandler#handle} deriving a unique username from the email and client on a
 * {@code user} {@code POST}, rejecting a blank/missing email, and — since ETP-4830 removed the
 * temporary admin-set-password bypass — no longer validating or otherwise reacting to a {@code
 * password} field at all (weak or strong, it is simply ignored). Covers {@link
 * UserRoleAssignmentHandler#afterHandle} sending a company invitation (via {@link
 * CompanyInvitationService#createInvitationForNewlyCreatedUser}) from the created record's
 * response body's {@code email}, skipping it when that email is absent, and swallowing any
 * failure. Also covers the ETP-4830 {@code invitationStatus} field attached to {@code user} GET
 * responses (list and single-record) via {@link CompanyInvitationService#findLatestInvitationStatus}.
 */
public class UserRoleAssignmentHandlerTest {

  private static final String USER_ID = "user-001";

  // ─── handle(): pre-hook no-op for everything except a `user` POST ────────────

  @Test
  public void handleReturnsNullForNonCrudEndpoint() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .httpMethod("POST")
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullForNonPostMethod() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .build();
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleForcesUsernameToMirrorEmailOnPost() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "  New.User@Example.com  ");
    requestBody.put("username", "should-be-overwritten");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    assertNull(handler.handle(ctx));

    assertEquals("new.user@example.com", requestBody.getString("username"));
  }

  @Test
  public void handleUsesSharedClientUsernameConventionWhenContextIsAvailable() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "user@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();
    OBContext obContext = mock(OBContext.class);
    Client client = mock(Client.class);
    when(obContext.getCurrentClient()).thenReturn(client);
    when(client.getName()).thenReturn("Second Client");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtSupport> usernameMock = mockStatic(EtendoGoJwtSupport.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
      usernameMock.when(() -> EtendoGoJwtSupport.buildClientUsername(
          "user@example.com", "Second Client")).thenReturn("user@example.com+secondclient");

      assertNull(handler.handle(ctx));
      assertEquals("user@example.com+secondclient", requestBody.getString("username"));
    }
  }

  @Test
  public void handleRejectsPostWithBlankEmail() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "   ");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleIgnoresWeakPasswordFieldSinceAdminSetPasswordBypassWasRemoved()
      throws Exception {
    // ETP-4830 removed the temporary admin-set-password bypass entirely: invite-email is now
    // the only way to activate an admin-created user's etgo_account, so a weak `password` on
    // the create form is no longer rejected here — it is simply not read by this handler at all.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "new.user@example.com");
    requestBody.put("password", "weak");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleIgnoresStrongPasswordFieldToo() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "new.user@example.com");
    requestBody.put("password", "Str0ng!Pass");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleAcceptsPostWithNoPassword() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "new.user@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleReturnsNullWhenPostRequestBodyIsMissing() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .build();
    assertNull(handler.handle(ctx));
  }

  // ─── handle(): PUT/PATCH email-immutability guard (ETP-4830 QA BUG-2) ────────

  @Test
  public void handleRejectsEmailChangeOnExistingUser() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "changed@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(USER_ID)
        .requestBody(requestBody)
        .build();

    User user = mock(User.class);
    when(user.getEmail()).thenReturn("original@example.com");

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
    }
  }

  @Test
  public void handleAllowsResubmittingByteForByteUnchangedEmail() throws Exception {
    // A naive client re-submitting its own unchanged form value must not 400 — and since the
    // request has no "active" key either, the deactivation guard is never even reached.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "same@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .requestBody(requestBody)
        .build();

    User user = mock(User.class);
    when(user.getEmail()).thenReturn("same@example.com");

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleCreateIsUnaffectedByEmailImmutabilityGuardOnPost() throws Exception {
    // The email-immutability guard only runs from validateUpdate() (PUT/PATCH) — handleCreate()
    // (POST) never touches OBDal at all, so a brand-new user's email is always writable on create.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "new.user@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      assertNull(handler.handle(ctx));
      assertEquals("new.user@example.com", requestBody.getString("username"));
      obDalMock.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void handleAllowsPatchWithNoEmailKeyAtAll() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "New name only");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(USER_ID)
        .requestBody(requestBody)
        .build();

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      assertNull(handler.handle(ctx));
      obDalMock.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void handleFailsClosedWhenEmailGuardThrows() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "someone@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .requestBody(requestBody)
        .build();

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenThrow(new RuntimeException("DB unavailable"));

      NeoResponse response = handler.handle(ctx);

      // Fail CLOSED: an unexpected error must surface as a 500, never silently allow the
      // email change through unverified.
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ─── handle(): PUT/PATCH self/last-admin lockout guard (ETP-4830 QA BUG-1) ───

  @Test
  public void handleRejectsSelfDeactivation() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", false);

    User actingUser = mock(User.class);
    when(actingUser.getId()).thenReturn(USER_ID);
    OBContext requestObContext = mock(OBContext.class);
    when(requestObContext.getUser()).thenReturn(actingUser);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .requestBody(requestBody)
        .obContext(requestObContext)
        .build();

    // The self-check short-circuits before any OBDal access at all.
    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
      obDalMock.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void handleRejectsDeactivatingLastActiveClientAdmin() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", false);

    String targetId = "target-admin-001";
    String actingId = "acting-admin-002";

    User actingUser = mock(User.class);
    when(actingUser.getId()).thenReturn(actingId);
    OBContext requestObContext = mock(OBContext.class);
    when(requestObContext.getUser()).thenReturn(actingUser);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(targetId)
        .requestBody(requestBody)
        .obContext(requestObContext)
        .build();

    Client client = mock(Client.class);
    User targetUser = mock(User.class);
    when(targetUser.getId()).thenReturn(targetId);
    when(targetUser.getClient()).thenReturn(client);

    UserRoles targetAdminRow = mock(UserRoles.class);
    User targetAdminRowUser = mock(User.class);
    when(targetAdminRowUser.getId()).thenReturn(targetId);
    when(targetAdminRow.getUserContact()).thenReturn(targetAdminRowUser);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, targetId)).thenReturn(targetUser);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(targetAdminRow));

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
    }
  }

  @Test
  public void handleAllowsDeactivatingNonSelfNonLastAdminUser() throws Exception {
    // Should-still-work case: the target holds the client-admin role, but so does at least one
    // other active user — deactivating the target does not leave the client admin-less.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", false);

    String targetId = "target-admin-003";
    String actingId = "acting-admin-004";
    String otherAdminId = "other-admin-005";

    User actingUser = mock(User.class);
    when(actingUser.getId()).thenReturn(actingId);
    OBContext requestObContext = mock(OBContext.class);
    when(requestObContext.getUser()).thenReturn(actingUser);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(targetId)
        .requestBody(requestBody)
        .obContext(requestObContext)
        .build();

    Client client = mock(Client.class);
    User targetUser = mock(User.class);
    when(targetUser.getId()).thenReturn(targetId);
    when(targetUser.getClient()).thenReturn(client);

    UserRoles targetAdminRow = mock(UserRoles.class);
    User targetAdminRowUser = mock(User.class);
    when(targetAdminRowUser.getId()).thenReturn(targetId);
    when(targetAdminRow.getUserContact()).thenReturn(targetAdminRowUser);

    UserRoles otherAdminRow = mock(UserRoles.class);
    User otherAdminRowUser = mock(User.class);
    when(otherAdminRowUser.getId()).thenReturn(otherAdminId);
    when(otherAdminRow.getUserContact()).thenReturn(otherAdminRowUser);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, targetId)).thenReturn(targetUser);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(targetAdminRow, otherAdminRow));

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleAllowsDeactivatingUserWithNoClientAdminRoleAtAll() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", false);

    String targetId = "regular-user-006";
    String actingId = "acting-admin-007";
    String otherAdminId = "other-admin-008";

    User actingUser = mock(User.class);
    when(actingUser.getId()).thenReturn(actingId);
    OBContext requestObContext = mock(OBContext.class);
    when(requestObContext.getUser()).thenReturn(actingUser);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(targetId)
        .requestBody(requestBody)
        .obContext(requestObContext)
        .build();

    Client client = mock(Client.class);
    User targetUser = mock(User.class);
    when(targetUser.getId()).thenReturn(targetId);
    when(targetUser.getClient()).thenReturn(client);

    UserRoles otherAdminRow = mock(UserRoles.class);
    User otherAdminRowUser = mock(User.class);
    when(otherAdminRowUser.getId()).thenReturn(otherAdminId);
    when(otherAdminRow.getUserContact()).thenReturn(otherAdminRowUser);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, targetId)).thenReturn(targetUser);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(otherAdminRow));

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleAllowsPutWithoutExplicitActiveFalse() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", true);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .requestBody(requestBody)
        .build();

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      assertNull(handler.handle(ctx));
      obDalMock.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void handleFailsClosedWhenDeactivationGuardThrows() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", false);

    String targetId = "target-admin-009";
    String actingId = "acting-admin-010";

    User actingUser = mock(User.class);
    when(actingUser.getId()).thenReturn(actingId);
    OBContext requestObContext = mock(OBContext.class);
    when(requestObContext.getUser()).thenReturn(actingUser);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(targetId)
        .requestBody(requestBody)
        .obContext(requestObContext)
        .build();

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      ownerMock.when(() -> OwnerSupport.isOwner(any())).thenReturn(false);
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, targetId)).thenThrow(new RuntimeException("DB unavailable"));

      NeoResponse response = handler.handle(ctx);

      // Fail CLOSED: an unexpected error must surface as a 500, never silently allow a
      // lockout-risking deactivation through.
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ─── handle(): PUT/PATCH owner-protection guard (ETP-4830 "owner" concept) ───

  private NeoContext ownerGuardContext(String httpMethod, String ownerId, String actingUserId,
      JSONObject requestBody) {
    OBContext requestObContext = null;
    if (actingUserId != null) {
      User actingUser = mock(User.class);
      when(actingUser.getId()).thenReturn(actingUserId);
      requestObContext = mock(OBContext.class);
      when(requestObContext.getUser()).thenReturn(actingUser);
    }
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod(httpMethod)
        .recordId(ownerId)
        .requestBody(requestBody)
        .obContext(requestObContext)
        .build();
  }

  private String ownerGuardMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  @Test
  public void handleBlanketRejectsNonOwnerPatchOnOwnerRecord_NameField() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String ownerId = "owner-001";
    String actingId = "other-admin-001";
    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Renamed by someone else");
    NeoContext ctx = ownerGuardContext("PATCH", ownerId, actingId, requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(ownerId)).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
      assertTrue(ownerGuardMessage(response).toLowerCase().contains("owner"));
    }
  }

  @Test
  public void handleBlanketRejectsNonOwnerPatchOnOwnerRecord_EmailField() throws Exception {
    // Owner protection runs FIRST — the rejection must be the owner guard's own message, not the
    // (also 400) email-immutability guard's, and OBDal/email-lookup must never even be reached.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String ownerId = "owner-002";
    String actingId = "other-admin-002";
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "changed-by-someone-else@example.com");
    NeoContext ctx = ownerGuardContext("PATCH", ownerId, actingId, requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(ownerId)).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
      assertTrue(ownerGuardMessage(response).toLowerCase().contains("owner"));
      obDalMock.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void handleBlanketRejectsNonOwnerPatchOnOwnerRecord_ActiveField() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String ownerId = "owner-003";
    String actingId = "other-admin-003";
    JSONObject requestBody = new JSONObject();
    // Not even a deactivation attempt — the owner guard is blanket, it does not care which
    // field(s) the request touches.
    requestBody.put("active", true);
    NeoContext ctx = ownerGuardContext("PUT", ownerId, actingId, requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(ownerId)).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
      assertTrue(ownerGuardMessage(response).toLowerCase().contains("owner"));
      obDalMock.verify(OBDal::getInstance, never());
    }
  }

  @Test
  public void handleAllowsOwnerEditingTheirOwnRecord() throws Exception {
    // The owner editing their OWN record must fall through to the other guards unchanged — this
    // request touches only "name", so once the owner guard no-ops, nothing else short-circuits it.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String ownerId = "owner-004";
    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Updated by the owner themselves");
    NeoContext ctx = ownerGuardContext("PATCH", ownerId, ownerId, requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(ownerId)).thenReturn(true);

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleOwnerGuardIsNoOpWhenTargetIsNotFlaggedAsOwner() throws Exception {
    // Baseline (every pre-existing user until the backfill data-fix runs): is_owner=false/unset
    // means the guard never triggers at all, regardless of who the requester is.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String targetId = "regular-user-999";
    String actingId = "some-admin-999";
    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Anyone can rename a non-owner");
    NeoContext ctx = ownerGuardContext("PATCH", targetId, actingId, requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(targetId)).thenReturn(false);

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleOwnerGuardFailsClosedWhenIsOwnerLookupThrows() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String ownerId = "owner-005";
    JSONObject requestBody = new JSONObject();
    requestBody.put("name", "Irrelevant");
    NeoContext ctx = ownerGuardContext("PATCH", ownerId, "other-admin-005", requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(ownerId))
          .thenThrow(new RuntimeException("DB unavailable"));

      NeoResponse response = handler.handle(ctx);

      // Fail CLOSED, same reasoning as the other write-path guards in this class.
      assertEquals(500, response.getHttpStatus());
    }
  }

  // ─── afterHandle: endpoint/method guards ─────────────────────────────────────

  @Test
  public void afterHandleReturnsNullForNonCrudEndpoint() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleReturnsNullForCrudGetMethod() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  // ─── afterHandle: bootstrap-user hiding on a `user` list GET ─────────────────

  private static JSONObject buildListResponseBody(String... userIds) throws Exception {
    JSONArray data = new JSONArray();
    for (String id : userIds) {
      JSONObject row = new JSONObject();
      row.put("id", id);
      data.put(row);
    }
    JSONObject inner = new JSONObject();
    inner.put("data", data);
    inner.put("totalRows", userIds.length);
    JSONObject body = new JSONObject();
    body.put("response", inner);
    return body;
  }

  @Test
  public void afterHandleRemovesAdminAndSystemBootstrapUsersFromListResponse() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("0", "100", "real-user-1", "real-user-2");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(handler.afterHandle(ctx));

    JSONObject inner = body.getJSONObject("response");
    JSONArray data = inner.getJSONArray("data");
    assertEquals(2, data.length());
    assertEquals("real-user-1", data.getJSONObject(0).getString("id"));
    assertEquals("real-user-2", data.getJSONObject(1).getString("id"));
    assertEquals(2, inner.getInt("totalRows"));
  }

  @Test
  public void afterHandleLeavesListResponseUntouchedWhenNoBootstrapUsersPresent() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("real-user-1", "real-user-2");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(handler.afterHandle(ctx));

    JSONObject inner = body.getJSONObject("response");
    assertEquals(2, inner.getJSONArray("data").length());
    assertEquals(2, inner.getInt("totalRows"));
  }

  @Test
  public void afterHandleIgnoresSingleRecordGetResponseShape() throws Exception {
    // A single-record GET's "response" has a lone JSON object under "data", not an array —
    // optJSONArray naturally no-ops there, so this must never throw or alter the body.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject singleRecord = new JSONObject();
    singleRecord.put("id", "100");
    JSONObject inner = new JSONObject();
    inner.put("data", singleRecord);
    JSONObject body = new JSONObject();
    body.put("response", inner);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(handler.afterHandle(ctx));

    assertEquals("100", body.getJSONObject("response").getJSONObject("data").getString("id"));
  }

  @Test
  public void afterHandleSwallowsExceptionWhenPreviousResultIsMissing() {
    // No previousResult set at all (defensive — should never happen for a real CRUD GET, but
    // hideBootstrapUsers must degrade to a no-op rather than throw).
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();

    assertNull(handler.afterHandle(ctx));
  }

  // ─── afterHandle: company invitation after a `user` POST create (ETP-4830) ──

  private static JSONObject buildCreatedRecordResponseBody(String id, String email, String name)
      throws Exception {
    JSONObject data = new JSONObject();
    data.put("id", id);
    if (email != null) {
      data.put("email", email);
    }
    if (name != null) {
      data.put("name", name);
    }
    JSONObject inner = new JSONObject();
    inner.put("data", data);
    JSONObject body = new JSONObject();
    body.put("response", inner);
    return body;
  }

  @Test
  public void afterHandleSendsCompanyInvitationAfterCreate() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "New.User@Example.com",
        "New User");
    OBContext requestObContext = mock(OBContext.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(new JSONObject())
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      CompanyInvitationService constructed = invitationServiceMock.constructed().get(0);
      verify(constructed).createInvitationForNewlyCreatedUser(eq(requestObContext),
          eq("new.user@example.com"), isNull(), isNull());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  @Test
  public void afterHandleSkipsInvitationWhenCreateResponseHasNoEmail() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, null, "New User");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class)) {

      assertNull(handler.afterHandle(ctx));

      assertEquals(0, invitationServiceMock.constructed().size());
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleSwallowsExceptionFromInvitationOnCreate() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "boom@example.com", "Boom");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class, (m, constructionCtx) ->
                when(m.createInvitationForNewlyCreatedUser(any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB unavailable")))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      assertEquals(1, invitationServiceMock.constructed().size());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: invitationStatus attached to `user` GET responses (ETP-4830) ─

  private static OBContext mockObContextForClient(String clientId) {
    OBContext obContext = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(obContext.getCurrentClient()).thenReturn(client);
    return obContext;
  }

  @Test
  public void afterHandleAttachesInvitationStatusToEveryListRow() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("real-user-1", "real-user-2");
    JSONObject inner = body.getJSONObject("response");
    inner.getJSONArray("data").getJSONObject(0).put("email", "invited@example.com");
    inner.getJSONArray("data").getJSONObject(1).put("email", "no-invite@example.com");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .obContext(mockObContextForClient("client-1"))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<CompanyInvitationService> invitationServiceMock =
            mockStatic(CompanyInvitationService.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      invitationServiceMock.when(() -> CompanyInvitationService.findLatestInvitationStatus(
          "client-1", "invited@example.com")).thenReturn("SENT");
      invitationServiceMock.when(() -> CompanyInvitationService.findLatestInvitationStatus(
          "client-1", "no-invite@example.com")).thenReturn(null);

      assertNull(handler.afterHandle(ctx));

      JSONArray data = inner.getJSONArray("data");
      assertEquals("SENT", data.getJSONObject(0).getString("invitationStatus"));
      assertTrue(data.getJSONObject(1).isNull("invitationStatus"));
    }
  }

  @Test
  public void afterHandleAttachesInvitationStatusToSingleRecordGet() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "existing@example.com", "Existing");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .recordId(USER_ID)
        .previousResult(NeoResponse.ok(body))
        .obContext(mockObContextForClient("client-1"))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<CompanyInvitationService> invitationServiceMock =
            mockStatic(CompanyInvitationService.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      invitationServiceMock.when(() -> CompanyInvitationService.findLatestInvitationStatus(
          "client-1", "existing@example.com")).thenReturn("ACCEPTED");

      assertNull(handler.afterHandle(ctx));

      JSONObject data = body.getJSONObject("response").getJSONObject("data");
      assertEquals("ACCEPTED", data.getString("invitationStatus"));
    }
  }

  @Test
  public void afterHandleLeavesRowsUntouchedWhenObContextHasNoClient() throws Exception {
    // No obContext at all was set on the NeoContext (the pre-existing bootstrap-hiding tests
    // exercise this same shape) — invitationStatus must never be guessed without a tenant scope.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("real-user-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(handler.afterHandle(ctx));

    JSONObject row = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertFalse(row.has("invitationStatus"));
  }

  @Test
  public void afterHandleReturnsNullWhenRecordIdIsNull() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  // ─── afterHandle: happy path — no prior AD_User_Roles row ────────────────────

  @Test
  public void afterHandleCreatesOneRowForUserWithNoExistingRole() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    Client roleClient = mock(Client.class);
    Organization roleOrg = mock(Organization.class);
    Role targetRole = mock(Role.class);
    when(targetRole.getId()).thenReturn("role-finance");
    when(targetRole.getClient()).thenReturn(roleClient);
    when(targetRole.getOrganization()).thenReturn(roleOrg);

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(targetRole);

    UserRoles newRow = mock(UserRoles.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(UserRoles.class)).thenReturn(newRow);

      assertNull(handler.afterHandle(ctx));

      verify(newRow).setNewOBObject(true);
      verify(newRow).setClient(roleClient);
      verify(newRow).setOrganization(roleOrg);
      verify(newRow).setUserContact(user);
      verify(newRow).setRole(targetRole);
      verify(newRow).setRoleAdmin(false);
      verify(obDal).save(newRow);
      // flush() runs once (unconditionally) after the no-op removal loop, once more after the
      // insert — mirrors the role-change path's two flushes, not a single combined one.
      verify(obDal, times(2)).flush();
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: role change — existing row for a different role ───────────

  @Test
  public void afterHandleReplacesExistingRowWhenRoleChanges() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(USER_ID)
        .build();

    Role oldRole = mock(Role.class);
    when(oldRole.getId()).thenReturn("role-sales");

    Role newRole = mock(Role.class);
    when(newRole.getId()).thenReturn("role-finance");
    when(newRole.getClient()).thenReturn(mock(Client.class));
    when(newRole.getOrganization()).thenReturn(mock(Organization.class));

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(newRole);

    UserRoles existingRow = mock(UserRoles.class);
    when(existingRow.getRole()).thenReturn(oldRole);

    UserRoles newRow = mock(UserRoles.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existingRow));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(UserRoles.class)).thenReturn(newRow);

      assertNull(handler.afterHandle(ctx));

      // The old row is removed...
      verify(obDal).remove(existingRow);
      // ...and exactly one new row is created for the new role.
      verify(newRow).setRole(newRole);
      verify(obDal).save(newRow);
      // flush() happens once for the removal batch, once more after the insert.
      verify(obDal, times(2)).flush();
    }
  }

  // ─── afterHandle: idempotency — already in sync ──────────────────────────────

  @Test
  public void afterHandleIsNoOpWhenAlreadyInSync() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    Role role = mock(Role.class);
    when(role.getId()).thenReturn("role-finance");

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(role);

    UserRoles existingRow = mock(UserRoles.class);
    when(existingRow.getRole()).thenReturn(role);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existingRow));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verify(obDal, never()).remove(any());
      verify(obDal, never()).save(any());
      verify(obDal, never()).flush();
      verify(obProvider, never()).get(UserRoles.class);
    }
  }

  // ─── afterHandle: role cleared — Default_Ad_Role_ID set to null ──────────────

  @Test
  public void afterHandleRemovesExistingRowWhenRoleIsCleared() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    User user = mock(User.class);
    when(user.getDefaultRole()).thenReturn(null);

    UserRoles existingRow = mock(UserRoles.class);
    when(existingRow.getRole()).thenReturn(mock(Role.class));

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(user);

      @SuppressWarnings("unchecked")
      OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existingRow));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verify(obDal).remove(existingRow);
      verify(obDal, times(1)).flush();
      // No new row is created when the target role is null.
      verify(obProvider, never()).get(UserRoles.class);
      verify(obDal, never()).save(any());
    }
  }

  // ─── afterHandle: user cannot be resolved ────────────────────────────────────

  @Test
  public void afterHandleSkipsWhenUserNotFound() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(null);

      assertNull(handler.afterHandle(ctx));

      verify(obDal, never()).createCriteria(UserRoles.class);
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: failures are swallowed (best-effort side effect) ──────────

  @Test
  public void afterHandleSwallowsExceptionAndStillRestoresContextMode() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(USER_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }
}
