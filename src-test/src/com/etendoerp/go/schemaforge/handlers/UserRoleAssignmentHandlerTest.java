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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
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

import com.etendoerp.go.rest.EtendoGoAccountProvisioning;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

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
 * <p>Admin-initiated user creation (ETP-4829): covers {@link UserRoleAssignmentHandler#handle}
 * deriving a unique username from the email and client on a {@code user} {@code POST}, rejecting a blank/missing email
 * or a weak admin-set {@code password}, and {@link UserRoleAssignmentHandler#afterHandle}
 * provisioning an {@code etgo_account} (pending, or active with that password) from the created
 * record's response body plus the original request body.
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
  public void handleRejectsPostWithWeakPassword() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject requestBody = new JSONObject();
    requestBody.put("email", "new.user@example.com");
    requestBody.put("password", "weak");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .build();

    NeoResponse response = handler.handle(ctx);
    assertEquals(400, response.getHttpStatus());
  }

  @Test
  public void handleAcceptsPostWithStrongPassword() throws Exception {
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

  // ─── afterHandle: pending-account provisioning after a `user` POST create ────

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
  public void afterHandleProvisionsPendingAccountAfterCreateWithNoPassword() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "New.User@Example.com",
        "New User");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(new JSONObject())
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<EtendoGoAccountProvisioning> provisioningMock =
            mockStatic(EtendoGoAccountProvisioning.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      provisioningMock.verify(() -> EtendoGoAccountProvisioning.ensureAccountForCreatedUser(
          eq("new.user@example.com"), eq("New User"), isNull(), eq(USER_ID)));
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  @Test
  public void afterHandlePassesThroughAdminSetPasswordFromRequestBody() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "New.User@Example.com",
        "New User");
    JSONObject requestBody = new JSONObject();
    requestBody.put("password", "  Str0ng!Pass  ");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(requestBody)
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<EtendoGoAccountProvisioning> provisioningMock =
            mockStatic(EtendoGoAccountProvisioning.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      provisioningMock.verify(() -> EtendoGoAccountProvisioning.ensureAccountForCreatedUser(
          eq("new.user@example.com"), eq("New User"), eq("Str0ng!Pass"), eq(USER_ID)));
    }
  }

  @Test
  public void afterHandleFallsBackToEmailAsNameWhenNameIsMissing() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "noname@example.com", null);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .requestBody(new JSONObject())
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<EtendoGoAccountProvisioning> provisioningMock =
            mockStatic(EtendoGoAccountProvisioning.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      assertNull(handler.afterHandle(ctx));

      provisioningMock.verify(() -> EtendoGoAccountProvisioning.ensureAccountForCreatedUser(
          eq("noname@example.com"), eq("noname@example.com"), isNull(), eq(USER_ID)));
    }
  }

  @Test
  public void afterHandleSkipsProvisioningWhenCreateResponseHasNoEmail() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, null, "New User");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<EtendoGoAccountProvisioning> provisioningMock =
            mockStatic(EtendoGoAccountProvisioning.class)) {

      assertNull(handler.afterHandle(ctx));

      provisioningMock.verifyNoInteractions();
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleSwallowsExceptionFromProvisioningOnCreate() throws Exception {
    UserRoleAssignmentHandler handler = new UserRoleAssignmentHandler();
    JSONObject body = buildCreatedRecordResponseBody(USER_ID, "boom@example.com", "Boom");
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(NeoResponse.ok(body))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<EtendoGoAccountProvisioning> provisioningMock =
            mockStatic(EtendoGoAccountProvisioning.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);
      provisioningMock.when(() -> EtendoGoAccountProvisioning.ensureAccountForCreatedUser(
          any(), any(), any(), any())).thenThrow(new RuntimeException("DB unavailable"));

      assertNull(handler.afterHandle(ctx));

      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
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
