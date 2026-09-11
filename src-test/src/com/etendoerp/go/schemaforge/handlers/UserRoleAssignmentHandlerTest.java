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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.etendoerp.go.schemaforge.util.UserRoleSyncSupport;

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
 * that a list with neither present is left untouched, that hideBootstrapUsers degrades to a
 * no-op against a non-array {@code data} value (confirmed ETP-4830: a REAL single-record GET's
 * {@code data} is a {@code JSONArray} of one element too — same as a create response, see {@link
 * UserRoleAssignmentHandler#inviteNewlyCreatedUser}'s javadoc — so this defensive path is never
 * actually exercised in production; it is only reachable here because {@code hideBootstrapUsers}
 * is gated on {@code recordId == null}, not on the response shape), and that a missing/malformed
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
 * responses (list and single-record) via {@link CompanyInvitationService#findLatestInvitationStatus},
 * AND (ETP-4830 pending-invite-pill fix) attached directly onto the {@code POST} create response
 * itself right after the invitation is created, so the pill renders on first paint without
 * requiring a follow-up GET. Also covers the ETP-4830 "create user -&gt; assign personal role ->
 * invite" ordering requirement: {@link UserRoleAssignmentHandler#ensurePersonalRoleForNewlyCreatedUser}
 * must run, and must complete, BEFORE {@link CompanyInvitationService#createInvitationForNewlyCreatedUser}
 * is ever called — see the {@code afterHandleAssignsPersonalRoleBeforeInvitationOnCreate} test and
 * its siblings below the existing invitation tests.
 */
public class UserRoleAssignmentHandlerTest {

  private static final String USER_ID = "user-001";

  /**
   * ETP-4830 — bundles the three collaborators {@link
   * UserRoleAssignmentHandler#ensurePersonalRoleForNewlyCreatedUser} now reaches on every
   * create-user invitation flow (a real {@code User} lookup via {@link OBDal}, a personal role
   * from {@link UserRoleCompositionService#createFreshPersonalRole}, and the {@code AD_User_Roles}
   * sync via {@link UserRoleSyncSupport#syncSingleActiveUserRole}), so the pre-existing invitation
   * tests below — which only care about the invitation itself — don't each have to hand-roll the
   * same three mocks just to keep this new step from reaching real (unmocked) Openbravo statics.
   * NOT used by the dedicated {@code afterHandleAssignsPersonalRoleBeforeInvitationOnCreate} tests
   * further down, which need fine-grained control over each collaborator instead.
   */
  private static final class PersonalRoleMocks implements AutoCloseable {
    private final MockedStatic<OBDal> obDalMock;
    private final MockedConstruction<UserRoleCompositionService> compositionServiceMock;
    private final MockedStatic<UserRoleSyncSupport> syncSupportMock;

    PersonalRoleMocks(String userId, User user, Role personalRole) {
      obDalMock = mockStatic(OBDal.class);
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, userId)).thenReturn(user);
      compositionServiceMock = mockConstruction(UserRoleCompositionService.class,
          (m, constructionCtx) -> when(m.createFreshPersonalRole(any())).thenReturn(personalRole));
      syncSupportMock = mockStatic(UserRoleSyncSupport.class);
    }

    @Override
    public void close() {
      syncSupportMock.close();
      compositionServiceMock.close();
      obDalMock.close();
    }
  }

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

  // ─── handle(): GET list pre-hook — exclude contact-only users (ETP-5019) ─────

  @Test
  public void handleInjectsUsernameNotBlankPredicateOnListGet() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    Map<String, String> queryParams = new HashMap<>();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .queryParams(queryParams)
        .build();

    assertNull(handler.handle(ctx));

    assertEquals("e.username is not null and e.username <> ''",
        queryParams.get(NeoCrudHelper.NEO_WHERE_PARAM));
  }

  @Test
  public void handleContactExclusionPredicateFiltersOnUsernameNotRoleCount() {
    // Regression guard (ETP-5019): a real user can legitimately have zero AD_User_Roles rows
    // (not yet assigned any role) but always has a non-blank username — see the handler's own
    // javadoc for excludeContactOnlyUsers. The injected predicate must never reference roles or
    // AD_User_Roles, only username presence, or it would wrongly hide legitimate
    // not-yet-assigned real users.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    Map<String, String> queryParams = new HashMap<>();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .queryParams(queryParams)
        .build();

    assertNull(handler.handle(ctx));

    String predicate = queryParams.get(NeoCrudHelper.NEO_WHERE_PARAM);
    assertTrue(predicate.contains("username"));
    assertFalse(predicate.toLowerCase().contains("role"));
  }

  @Test
  public void handleAndsExistingNeoWherePredicateWithContactExclusion() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    Map<String, String> queryParams = new HashMap<>();
    queryParams.put(NeoCrudHelper.NEO_WHERE_PARAM, "e.active = true");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .queryParams(queryParams)
        .build();

    assertNull(handler.handle(ctx));

    assertEquals("(e.active = true) and (e.username is not null and e.username <> '')",
        queryParams.get(NeoCrudHelper.NEO_WHERE_PARAM));
  }

  @Test
  public void handleLeavesQueryParamsUntouchedOnSingleRecordGet() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    Map<String, String> queryParams = new HashMap<>();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .recordId(USER_ID)
        .queryParams(queryParams)
        .build();

    assertNull(handler.handle(ctx));

    assertTrue(queryParams.isEmpty());
  }

  @Test
  public void handleToleratesNullQueryParamsOnListGet() {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleContactFilterAndAfterHandleBootstrapHidingCoexistOnListGetFlow()
      throws Exception {
    // Regression guard (ETP-5019): the new pre-hook contact-only filter (query params, handle())
    // and the pre-existing bootstrap-user hiding (response body, afterHandle()) act on different
    // phases of the same list GET and must not interfere with each other.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    Map<String, String> queryParams = new HashMap<>();
    JSONObject body = buildListResponseBody("0", "100", "real-user-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .queryParams(queryParams)
        .previousResult(NeoResponse.ok(body))
        .build();

    assertNull(handler.handle(ctx));
    assertNull(handler.afterHandle(ctx));

    assertEquals("e.username is not null and e.username <> ''",
        queryParams.get(NeoCrudHelper.NEO_WHERE_PARAM));
    JSONObject inner = body.getJSONObject("response");
    assertEquals(1, inner.getJSONArray("data").length());
    assertEquals("real-user-1", inner.getJSONArray("data").getJSONObject(0).getString("id"));
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
  public void handleOwnerSelfDeactivationFallsThroughToSelfLockoutGuardNotOwnerGuard()
      throws Exception {
    // QA re-pass composed-guard check (ETP-4830): the owner guard and the self-lockout guard
    // (BUG-1) were only ever tested in isolation — this proves they actually COMPOSE when the
    // owner targets their OWN record with active=false. The owner guard must no-op (same-user
    // edit) and let the request fall through, and rejectDangerousDeactivation's self-check must
    // then be the one that rejects it — asserted via the response MESSAGE, not just the status
    // code, so a regression that let the owner guard wrongly claim this case (or a regression
    // that let it silently pass through unblocked) would both be caught.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    String ownerId = "owner-006";
    JSONObject requestBody = new JSONObject();
    requestBody.put("active", false);
    NeoContext ctx = ownerGuardContext("PATCH", ownerId, ownerId, requestBody);

    try (MockedStatic<OwnerSupport> ownerMock = mockStatic(OwnerSupport.class);
        MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      ownerMock.when(() -> OwnerSupport.isOwner(ownerId)).thenReturn(true);

      NeoResponse response = handler.handle(ctx);

      assertEquals(400, response.getHttpStatus());
      assertTrue(ownerGuardMessage(response).toLowerCase().contains("deactivate your own"));
      // The self-check short-circuits before any OBDal access, same as the plain
      // handleRejectsSelfDeactivation case above — proves this took the self-lockout path, not
      // some other guard that happens to also return 400.
      obDalMock.verify(OBDal::getInstance, never());
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
  public void afterHandleIgnoresNonArrayDataShape() throws Exception {
    // Defensive only: a REAL single-record GET's "data" is a JSONArray of one element too
    // (confirmed ETP-4830 — see UserRoleAssignmentHandler#inviteNewlyCreatedUser's javadoc), so
    // this exact lone-object shape never actually occurs in production. It's only reachable in
    // this test because hideBootstrapUsers runs whenever recordId == null, regardless of the
    // response's actual shape — optJSONArray must still no-op rather than throw against it.
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

  /**
   * Builds a {@code user} create/single-record-GET response body matching {@code
   * DefaultJsonDataService}'s REAL shape (confirmed ETP-4830 by reading core's {@code
   * update()}/{@code fetch()}): {@code response.data} is ALWAYS a {@code JSONArray}, holding
   * exactly one element here — never a lone {@code JSONObject}. Before this fix the helper built
   * a lone-object shape, which let {@code afterHandleSendsCompanyInvitationAfterCreate} and its
   * siblings pass against a mock that matched the (wrong) production code's own incorrect
   * assumption instead of the real response — the exact failure mode the fix in
   * {@code UserRoleAssignmentHandler#inviteNewlyCreatedUser} addresses.
   */
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
    JSONArray dataArray = new JSONArray();
    dataArray.put(data);
    JSONObject inner = new JSONObject();
    inner.put("data", dataArray);
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
            mockConstruction(CompanyInvitationService.class);
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
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

  /**
   * ETP-4830 regression test: reproduces the real bug found in the live server log
   * ("no 'data' object in the create response — cannot determine the created user's email,
   * invitation not sent"). {@code response.data} is present but empty — {@code
   * DefaultJsonDataService.update()} would never actually produce this (a successful
   * create/update always yields exactly one element), but it is the shape the OLD {@code
   * inner.optJSONObject("data")} extraction degraded to once {@code data} became a real {@code
   * JSONArray}: {@code optJSONObject} always returns {@code null} against a {@code JSONArray}
   * value, array-empty-or-not. Locks in that the fixed {@code dataArray.length() > 0} guard
   * still degrades to a clean no-op — never a thrown exception — for this edge shape.
   */
  @Test
  public void afterHandleSkipsInvitationWhenCreateResponseDataArrayIsEmpty() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject inner = new JSONObject();
    inner.put("data", new JSONArray());
    JSONObject body = new JSONObject();
    body.put("response", inner);
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

  /**
   * ETP-4830 regression test: the pre-fix code read {@code data} via {@code
   * inner.optJSONObject("data")} — the REAL create response shape ({@code data} as a {@code
   * JSONArray}, confirmed against core's {@code DefaultJsonDataService}) always failed that
   * extraction, which is exactly the bug seen in production. This is the single most direct
   * regression guard for that bug: it asserts the created user's email IS correctly extracted
   * from a {@code data} array of one element, and the invitation service IS actually invoked
   * with it — the fixed-shape counterpart of {@link
   * #afterHandleSendsCompanyInvitationAfterCreate} using the exact array-of-one shape
   * {@code DefaultJsonDataService} really emits.
   */
  @Test
  public void afterHandleExtractsEmailFromDataArrayOnCreate() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject recordJson = new JSONObject();
    recordJson.put("id", USER_ID);
    recordJson.put("email", "Array.Shape@Example.com");
    JSONArray dataArray = new JSONArray();
    dataArray.put(recordJson);
    JSONObject inner = new JSONObject();
    inner.put("data", dataArray);
    JSONObject body = new JSONObject();
    body.put("response", inner);
    OBContext requestObContext = mock(OBContext.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class);
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      CompanyInvitationService constructed = invitationServiceMock.constructed().get(0);
      verify(constructed).createInvitationForNewlyCreatedUser(eq(requestObContext),
          eq("array.shape@example.com"), isNull(), isNull());
    }
  }

  // ─── afterHandle: ETP-4830 "assign personal role BEFORE invite" ordering ────

  /**
   * The core ordering requirement this session's task was built around: {@link
   * UserRoleAssignmentHandler#ensurePersonalRoleForNewlyCreatedUser} must run to completion
   * (personal role resolved, {@code Default_Ad_Role_ID} set, {@code AD_User_Roles} synced) BEFORE
   * {@link CompanyInvitationService#createInvitationForNewlyCreatedUser} is ever called — proved
   * via a shared call-order trail across the two independently-mocked collaborators, not just by
   * asserting both were eventually called.
   */
  @Test
  public void afterHandleAssignsPersonalRoleBeforeInvitationOnCreate() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "brand.new@example.com",
        "Brand New");
    OBContext requestObContext = mock(OBContext.class);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();

    User createdUser = mock(User.class);
    Role personalRole = mock(Role.class);
    when(personalRole.getId()).thenReturn("personal-role-new");
    List<String> callOrder = new ArrayList<>();
    JSONObject successResult = new JSONObject();
    successResult.put("status", "success");

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedConstruction<UserRoleCompositionService> compositionServiceMock =
            mockConstruction(UserRoleCompositionService.class, (m, constructionCtx) ->
                when(m.createFreshPersonalRole(createdUser)).thenAnswer(inv -> {
                  callOrder.add("personalRole");
                  return personalRole;
                }));
        MockedStatic<UserRoleSyncSupport> syncMock = mockStatic(UserRoleSyncSupport.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class, (m, constructionCtx) ->
                when(m.createInvitationForNewlyCreatedUser(any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                      callOrder.add("invitation");
                      return successResult;
                    }))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(createdUser);

      assertNull(handler.afterHandle(ctx));

      assertEquals(1, compositionServiceMock.constructed().size());
      assertEquals(Arrays.asList("personalRole", "invitation"), callOrder);
      verify(createdUser).setDefaultRole(personalRole);
      verify(obDal).save(createdUser);
      verify(obDal).flush();
      syncMock.verify(
          () -> UserRoleSyncSupport.syncSingleActiveUserRole(createdUser, personalRole));
    }
  }

  /**
   * Defensive-only: a real create response always includes {@code id} (confirmed against core's
   * {@code DefaultJsonDataService}, same as {@code email}), but the personal-role assignment step
   * must still degrade to a clean no-op rather than block the invitation if it were ever missing.
   */
  @Test
  public void afterHandleSkipsPersonalRoleAssignmentWhenCreateResponseHasNoId() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject recordJson = new JSONObject();
    recordJson.put("email", "no-id@example.com");
    JSONArray dataArray = new JSONArray();
    dataArray.put(recordJson);
    JSONObject inner = new JSONObject();
    inner.put("data", dataArray);
    JSONObject body = new JSONObject();
    body.put("response", inner);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<UserRoleCompositionService> compositionServiceMock =
            mockConstruction(UserRoleCompositionService.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      assertEquals(0, compositionServiceMock.constructed().size());
      assertEquals("The invitation must still be sent even though the personal-role step "
          + "no-opped", 1, invitationServiceMock.constructed().size());
    }
  }

  /**
   * The created user's id is present, but a lookup for it comes back empty (should never happen
   * right after a successful create, but the step must fail safe): no personal role is minted,
   * and the invitation still proceeds.
   */
  @Test
  public void afterHandleSkipsPersonalRoleAssignmentWhenUserNotFound() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "ghost@example.com", "Ghost");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedConstruction<UserRoleCompositionService> compositionServiceMock =
            mockConstruction(UserRoleCompositionService.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(null);

      assertNull(handler.afterHandle(ctx));

      assertEquals(0, compositionServiceMock.constructed().size());
      assertEquals(1, invitationServiceMock.constructed().size());
    }
  }

  /**
   * Best-effort contract, same as every other side effect in this method: a failure while
   * ensuring the personal role must never block the parent {@code AD_User} creation, nor the
   * invitation that follows.
   */
  @Test
  public void afterHandleSwallowsExceptionFromPersonalRoleAssignmentAndStillSendsInvitation()
      throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "flaky-role@example.com", "Flaky");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedConstruction<UserRoleCompositionService> compositionServiceMock =
            mockConstruction(UserRoleCompositionService.class, (m, constructionCtx) ->
                when(m.createFreshPersonalRole(any()))
                    .thenThrow(new RuntimeException("DB unavailable")));
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(User.class, USER_ID)).thenReturn(mock(User.class));

      assertNull(handler.afterHandle(ctx));

      assertEquals(1, invitationServiceMock.constructed().size());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
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
                    .thenThrow(new RuntimeException("DB unavailable")));
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      assertEquals(1, invitationServiceMock.constructed().size());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * ETP-4830 diagnostic fix: {@code createInvitationForNewlyCreatedUser} returns an
   * {@code error: true} JSON on a validation failure instead of throwing (see {@code
   * CompanyInvitationService#errorResponse}) — before this fix the returned value was discarded,
   * so this branch was completely silent (no log, no DB row). This test only asserts the handler
   * still completes without throwing and that the service was actually invoked with the created
   * user's email; the new WARN log line itself
   * ({@code "invitation NOT created for email=... clientId=... — code=... message=..."}) is
   * verified by reading the code path (no log-capture test utility exists yet in this suite), per
   * the task's guidance not to build one just for a 2-line diagnostic fix.
   */
  @Test
  public void afterHandleDoesNotThrowWhenInvitationCreationReturnsError() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "no-role@example.com", "No Role");
    OBContext requestObContext = mockObContextForClient("client-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();
    JSONObject errorResult = new JSONObject();
    errorResult.put("error", true);
    errorResult.put("code", "INVITED_USER_NOT_FOUND");
    errorResult.put("message", "Create the AD_USER and assign its organization roles before "
        + "sending the invitation");

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class, (m, constructionCtx) ->
                when(m.createInvitationForNewlyCreatedUser(any(), any(), any(), any()))
                    .thenReturn(errorResult));
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      CompanyInvitationService constructed = invitationServiceMock.constructed().get(0);
      verify(constructed).createInvitationForNewlyCreatedUser(eq(requestObContext),
          eq("no-role@example.com"), isNull(), isNull());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * ETP-4830 diagnostic fix: a successful creation now also logs (INFO), so a clean run is
   * distinguishable from the silent-failure case above without a DB query. Same log-verification
   * caveat as {@link #afterHandleDoesNotThrowWhenInvitationCreationReturnsError}.
   */
  @Test
  public void afterHandleDoesNotThrowWhenInvitationCreationSucceeds() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "sent@example.com", "Sent User");
    OBContext requestObContext = mockObContextForClient("client-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();
    JSONObject invitationJson = new JSONObject();
    invitationJson.put("status", "SENT");
    JSONObject successResult = new JSONObject();
    successResult.put("status", "success");
    successResult.put("invitation", invitationJson);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class, (m, constructionCtx) ->
                when(m.createInvitationForNewlyCreatedUser(any(), any(), any(), any()))
                    .thenReturn(successResult));
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      CompanyInvitationService constructed = invitationServiceMock.constructed().get(0);
      verify(constructed).createInvitationForNewlyCreatedUser(eq(requestObContext),
          eq("sent@example.com"), isNull(), isNull());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * ETP-4830 pending-invite-pill fix regression test: creating a user correctly sent the
   * invitation and the toast rendered, but the "pending invite" pill in the detail header did NOT
   * show until the record was left and re-entered (a follow-up {@code GET}) — because {@link
   * UserRoleAssignmentHandler#attachInvitationStatus} only ran on the {@code GET} branch of
   * {@code afterHandle}, never on this {@code POST} branch. Asserts the SAME {@code data[0]} row
   * this handler already read the email from now also carries {@code invitationStatus} once the
   * invitation has been created — i.e. the create response itself is enough, no follow-up GET
   * required.
   */
  @Test
  public void afterHandleAttachesInvitationStatusToCreateResponseImmediately() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "sent@example.com", "Sent User");
    OBContext requestObContext = mockObContextForClient("client-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();
    JSONObject invitationJson = new JSONObject();
    invitationJson.put("status", "SENT");
    JSONObject successResult = new JSONObject();
    successResult.put("status", "success");
    successResult.put("invitation", invitationJson);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class, (m, constructionCtx) ->
                when(m.createInvitationForNewlyCreatedUser(any(), any(), any(), any()))
                    .thenReturn(successResult));
        MockedStatic<CompanyInvitationService> invitationStatusMock =
            mockStatic(CompanyInvitationService.class);
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      invitationStatusMock.when(() -> CompanyInvitationService.findLatestInvitationStatus(
          "client-1", "sent@example.com")).thenReturn("SENT");

      assertNull(handler.afterHandle(ctx));

      JSONObject data = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertEquals("SENT", data.getString("invitationStatus"));
    }
  }

  /**
   * ETP-4830 pending-invite-pill fix: the {@code invitationStatus} attach step must be
   * best-effort like every other side effect in this method — a lookup failure must not prevent
   * {@code createInvitationForNewlyCreatedUser} from having been called, nor swallow the parent
   * request. Reuses the error-result fixture from {@link
   * #afterHandleDoesNotThrowWhenInvitationCreationReturnsError} but additionally makes the status
   * lookup itself throw, asserting the handler still completes cleanly and the create response
   * row is simply left without an {@code invitationStatus} field.
   */
  @Test
  public void afterHandleDoesNotThrowWhenInvitationStatusLookupFails() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "flaky@example.com", "Flaky User");
    OBContext requestObContext = mockObContextForClient("client-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .obContext(requestObContext)
        .build();
    JSONObject invitationJson = new JSONObject();
    invitationJson.put("status", "SENT");
    JSONObject successResult = new JSONObject();
    successResult.put("status", "success");
    successResult.put("invitation", invitationJson);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedConstruction<CompanyInvitationService> invitationServiceMock =
            mockConstruction(CompanyInvitationService.class, (m, constructionCtx) ->
                when(m.createInvitationForNewlyCreatedUser(any(), any(), any(), any()))
                    .thenReturn(successResult));
        MockedStatic<CompanyInvitationService> invitationStatusMock =
            mockStatic(CompanyInvitationService.class);
        PersonalRoleMocks personalRoleMocks =
            new PersonalRoleMocks(USER_ID, mock(User.class), mock(Role.class))) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      invitationStatusMock.when(() -> CompanyInvitationService.findLatestInvitationStatus(
          "client-1", "flaky@example.com")).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(handler.afterHandle(ctx));

      CompanyInvitationService constructed = invitationServiceMock.constructed().get(0);
      verify(constructed).createInvitationForNewlyCreatedUser(eq(requestObContext),
          eq("flaky@example.com"), isNull(), isNull());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
      JSONObject data = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(data.has("invitationStatus"));
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

      JSONObject data = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
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

  // ─── afterHandle: isOwner attached to `user` GET responses (ETP-4830 item #4) ─

  @Test
  public void afterHandleAttachesIsOwnerToEveryListRow() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("owner-user", "regular-user");
    JSONObject inner = body.getJSONObject("response");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OwnerSupport> ownerSupportMock = mockStatic(OwnerSupport.class)) {
      ownerSupportMock.when(() -> OwnerSupport.isOwner("owner-user")).thenReturn(true);
      ownerSupportMock.when(() -> OwnerSupport.isOwner("regular-user")).thenReturn(false);

      assertNull(handler.afterHandle(ctx));

      JSONArray data = inner.getJSONArray("data");
      assertTrue(data.getJSONObject(0).getBoolean("isOwner"));
      assertFalse(data.getJSONObject(1).getBoolean("isOwner"));
    }
  }

  @Test
  public void afterHandleAttachesIsOwnerToSingleRecordGet() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "existing@example.com", "Existing");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .recordId(USER_ID)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OwnerSupport> ownerSupportMock = mockStatic(OwnerSupport.class)) {
      ownerSupportMock.when(() -> OwnerSupport.isOwner(USER_ID)).thenReturn(true);

      assertNull(handler.afterHandle(ctx));

      JSONObject data = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(data.getBoolean("isOwner"));
    }
  }

  @Test
  public void afterHandleDoesNotNeedObContextToAttachIsOwner() throws Exception {
    // Unlike invitationStatus (client-scoped), isOwner needs no obContext/clientId at all —
    // OwnerSupport.isOwner reads straight off the row's own id.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("real-user-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OwnerSupport> ownerSupportMock = mockStatic(OwnerSupport.class)) {
      ownerSupportMock.when(() -> OwnerSupport.isOwner("real-user-1")).thenReturn(true);

      assertNull(handler.afterHandle(ctx));

      JSONObject row = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertTrue(row.getBoolean("isOwner"));
    }
  }

  @Test
  public void afterHandleLeavesIsOwnerUnattachedWhenOwnerSupportThrows() throws Exception {
    // Best-effort, same convention as attachInvitationStatus: an unexpected failure is logged
    // and swallowed, never propagated to the caller, and the row simply never gets the field.
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildListResponseBody("real-user-1");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OwnerSupport> ownerSupportMock = mockStatic(OwnerSupport.class)) {
      ownerSupportMock.when(() -> OwnerSupport.isOwner("real-user-1"))
          .thenThrow(new RuntimeException("DAL not available"));

      assertNull(handler.afterHandle(ctx));

      JSONObject row = body.getJSONObject("response").getJSONArray("data").getJSONObject(0);
      assertFalse(row.has("isOwner"));
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
