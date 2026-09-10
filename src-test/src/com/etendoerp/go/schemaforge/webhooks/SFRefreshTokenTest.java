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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.rest.EtendoGoJwtSupport.RoleListData;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for {@link SFRefreshToken}. Mirrors {@code SFPromoteUserRoleTest}'s
 * {@code OBContext}-mocking convention (a shared {@link MockedStatic} for {@link OBContext},
 * opened in {@code @BeforeEach} / closed in {@code @AfterEach}), and additionally mocks {@link
 * OBDal} and {@link SecureWebServicesUtils} statically per test the way {@code
 * EtendoGoJwtServletCoverageTest} does for the exact same {@code
 * SecureWebServicesUtils#generateToken(User, Role)} overload.
 *
 * <p>One deviation from {@code SFPromoteUserRoleTest}: this webhook has no access gate and no
 * request parameters at all (see the class javadoc's security note), so there is no
 * "denied"/"parameter validation" test group — every test instead pins down the identity
 * resolution path (context -&gt; DB user -&gt; role -&gt; token), which is the actual
 * security-critical surface of this class.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFRefreshTokenTest {

  /**
   * ETP-5195, R5 cross-client check — the client id {@code mockContext.getCurrentClient()} is
   * stubbed to in {@code setUp()}. Every test that exercises {@code isEligibleForRole}'s
   * non-null-role branch and expects the pre-existing (same-client) eligibility behavior must
   * stub its {@link Role} mock's {@code getClient()} to a {@link Client} with this SAME id via
   * {@link #stubSameClientRole(Role)} -- otherwise {@code role.getClient()} defaults to {@code
   * null} and the cross-client check NPEs before any of the older checks ever run.
   */
  private static final String CALLER_CLIENT_ID = "client-1";

  private MockedStatic<OBContext> obContextMock;
  private OBContext mockContext;
  private SFRefreshToken webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    mockContext = mock(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);
    Client callerClient = mock(Client.class);
    when(callerClient.getId()).thenReturn(CALLER_CLIENT_ID);
    when(mockContext.getCurrentClient()).thenReturn(callerClient);

    webhook = new SFRefreshToken();
    parameters = new HashMap<>();
    responseVars = new HashMap<>();
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
  }

  private User givenAuthenticatedUser(String userId) {
    User user = mock(User.class);
    when(user.getId()).thenReturn(userId);
    when(mockContext.getUser()).thenReturn(user);
    return user;
  }

  /**
   * ETP-5195, R5 — stubs {@code obDal.createCriteria(UserRoles.class)} (fluent {@code add()},
   * {@code count()}) so {@code isEligibleForRole}'s {@code AD_User_Roles} lookup returns the
   * given row count. Mirrors the {@code OBCriteria} mocking convention already established in
   * {@code UserRoleAssignmentHandlerTest} for the same entity.
   */
  @SuppressWarnings("unchecked")
  private OBCriteria<UserRoles> stubUserRolesCriteria(OBDal obDal, int count) {
    OBCriteria<UserRoles> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(UserRoles.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.count()).thenReturn(count);
    return criteria;
  }

  /**
   * ETP-5195, R5 cross-client check — stubs {@code role.getClient()} to a {@link Client} whose id
   * matches {@link #CALLER_CLIENT_ID} (the id {@code mockContext.getCurrentClient()} resolves to
   * via {@code setUp()}), so {@code isEligibleForRole}'s new cross-client guard lets the
   * pre-existing (same-client) eligibility checks run exactly as before.
   */
  private static void stubSameClientRole(Role role) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CALLER_CLIENT_ID);
    when(role.getClient()).thenReturn(client);
  }

  /**
   * ETP-5195 — session metadata extension: mocks the {@code user}/{@code client}/{@code role}/
   * {@code organization} claims {@link SFRefreshToken#buildSessionMetadata} reads off the
   * decoded token, mirroring {@code AppsServletTest#mockDecodedJwt}'s convention for the same
   * {@link DecodedJWT}/{@link Claim} shape.
   */
  private static DecodedJWT mockDecodedJwt(String userId, String clientId, String roleId,
      String orgId) {
    DecodedJWT decoded = mock(DecodedJWT.class);
    // Claim mocks are built into locals FIRST, then wired one at a time -- nesting mock()/when()
    // calls directly inside this when()'s argument list corrupts Mockito's ongoing-stubbing
    // state and throws UnfinishedStubbingException.
    Claim userClaim = claim(userId);
    Claim clientClaim = claim(clientId);
    Claim roleClaim = claim(roleId);
    Claim organizationClaim = claim(orgId);
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("role")).thenReturn(roleClaim);
    when(decoded.getClaim("organization")).thenReturn(organizationClaim);
    return decoded;
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  /**
   * ETP-5195 — builds a {@link RoleListData} carrying a single role entry, standing in for
   * {@link EtendoGoJwtSupport#loadRoleListData(String)}'s real DB-backed result.
   */
  private static RoleListData roleListDataWith(String roleId, String roleName)
      throws JSONException {
    RoleListData data = new RoleListData();
    data.firstRoleId = roleId;
    data.roleArray = new JSONArray();
    JSONObject roleEntry = new JSONObject();
    roleEntry.put("id", roleId);
    roleEntry.put("name", roleName);
    roleEntry.put("orgList", new JSONArray());
    data.roleArray.put(roleEntry);
    return data;
  }

  // ── caller resolution failures ──────────────────────────────────────────

  @Test
  void obContextItselfNullIsResolvedAsNoAuthenticatedUser() {
    obContextMock.when(OBContext::getOBContext).thenReturn(null);

    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("Unable to resolve the authenticated user", result.optString("message"));
    assertFalse(responseVars.containsKey("error"));
  }

  @Test
  void noUserOnContextIsResolvedAsNoAuthenticatedUser() {
    // mockContext.getUser() left unstubbed -> returns null, OBContext itself is non-null.
    try (MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("Unable to resolve the authenticated user", result.optString("message"));
  }

  @Test
  void userDeletedBetweenTokenIssuanceAndThisCallReturnsUserNotFound() {
    givenAuthenticatedUser("user-1");
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("User not found", result.optString("message"));
    assertFalse(responseVars.containsKey("error"));
  }

  // ── happy path ───────────────────────────────────────────────────────────

  /**
   * Also the structural proof of the class javadoc's security invariant: {@code parameters}
   * carries a decoy user id that does NOT match the caller's own id, yet the token is minted for
   * the CALLER's user (resolved from {@code OBContext}) — proving the parameter map is never
   * consulted to resolve identity.
   */
  @Test
  void happyPathReissuesTokenForCallersOwnUserAndIgnoresAnyUserIdInParameters()
      throws JSONException {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(true);
    stubSameClientRole(currentRole);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    // Decoy: if this webhook ever started reading identity from the parameter map, it would
    // pick up "someone-elses-id" instead of the caller's own "user-1".
    parameters.put("userId", "someone-elses-id");
    parameters.put("UserId", "someone-elses-id");

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);
    stubUserRolesCriteria(obDal, 1);

    // ETP-5195 -- the eligible-role branch now decodes the freshly-minted token and loads the
    // role list to build the `session` metadata object; both must be stubbed or
    // buildSessionMetadata throws and the whole call falls into the bridge error path instead.
    DecodedJWT decoded = mockDecodedJwt("user-1", "client-1", "role-1", "org-1");
    RoleListData roleListData = roleListDataWith("role-1", "Role One");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<EtendoGoJwtSupport> jwtSupportMock = mockStatic(EtendoGoJwtSupport.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole))
          .thenReturn("fresh-jwt-token");
      swsMock.when(() -> SecureWebServicesUtils.decodeToken("fresh-jwt-token")).thenReturn(decoded);
      jwtSupportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);

      webhook.get(parameters, responseVars);

      swsMock.verify(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole), times(1));
      // Never resolved via the decoy id -- OBDal.get was only ever asked for the caller's own id.
      verify(obDal, never()).get(User.class, "someone-elses-id");
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals("fresh-jwt-token", result.getString("token"));
    assertTrue(result.has("session"));
  }

  // ── session metadata (ETP-5195) ──────────────────────────────────────────

  /**
   * Pins down the actual field-by-field wiring of {@code buildSessionMetadata}: every value in
   * {@code session} must come from the DECODED token (never re-derived independently), and
   * {@code roleList} must be exactly what {@link EtendoGoJwtSupport#loadRoleListData(String)}
   * returned.
   */
  @Test
  void eligibleRoleResponseIncludesSessionMetadataDecodedFromTheNewToken() throws JSONException {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(true);
    stubSameClientRole(currentRole);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);
    stubUserRolesCriteria(obDal, 1);

    DecodedJWT decoded = mockDecodedJwt("user-1", "client-9", "role-9", "org-9");
    RoleListData roleListData = roleListDataWith("role-9", "Finance Manager");

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<EtendoGoJwtSupport> jwtSupportMock = mockStatic(EtendoGoJwtSupport.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole))
          .thenReturn("fresh-jwt-token");
      swsMock.when(() -> SecureWebServicesUtils.decodeToken("fresh-jwt-token")).thenReturn(decoded);
      jwtSupportMock.when(() -> EtendoGoJwtSupport.loadRoleListData("user-1"))
          .thenReturn(roleListData);

      webhook.get(parameters, responseVars);
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals("fresh-jwt-token", result.getString("token"));
    JSONObject session = result.getJSONObject("session");
    assertEquals(1, session.getInt("version"));
    assertEquals("user-1", session.getString("userId"));
    assertEquals("client-9", session.getString("clientId"));
    assertEquals("role-9", session.getString("selectedRoleId"));
    assertEquals("org-9", session.getString("selectedOrgId"));
    assertEquals(roleListData.roleArray.toString(), session.getJSONArray("roleList").toString());
  }

  /**
   * Structural proof that the null-role case (see class javadoc) is genuinely untouched by the
   * ETP-5195 session-metadata extension: no {@code session} key in the response, AND neither
   * {@code decodeToken} nor {@code loadRoleListData} is ever invoked for this branch.
   */
  @Test
  void nullRoleResponseHasNoSessionKeyAndNeverDecodesOrLoadsRoleList() throws JSONException {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    when(callerUser.getDefaultRole()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class);
         MockedStatic<EtendoGoJwtSupport> jwtSupportMock = mockStatic(EtendoGoJwtSupport.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, null))
          .thenReturn("token-with-no-role-claim");

      webhook.get(parameters, responseVars);

      swsMock.verify(() -> SecureWebServicesUtils.decodeToken(any()), never());
      jwtSupportMock.verify(() -> EtendoGoJwtSupport.loadRoleListData(any()), never());
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals("token-with-no-role-claim", result.getString("token"));
    assertFalse(result.has("session"));
  }

  /**
   * A failure inside {@code buildSessionMetadata} (decoding the just-minted token) must be
   * caught by {@code get()}'s own surrounding try/catch and surface as the same bridge
   * {@code error} path as any other failure in this method -- mirroring {@code
   * generateTokenFailureSurfacesAsBridgeError}'s shape for a different failure point.
   */
  @Test
  void sessionMetadataDecodeFailureSurfacesAsBridgeError() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(true);
    stubSameClientRole(currentRole);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);
    stubUserRolesCriteria(obDal, 1);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole))
          .thenReturn("fresh-jwt-token");
      swsMock.when(() -> SecureWebServicesUtils.decodeToken("fresh-jwt-token"))
          .thenThrow(new RuntimeException("token decoding blew up"));

      webhook.get(parameters, responseVars);
    }

    assertEquals("token decoding blew up", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
  }

  // ── QA (ETP-5195) — user with no assignable role at all ─────────────────
  //
  // The class javadoc explicitly calls out this as "a genuinely unexpected state" that is
  // deliberately NOT modeled as its own domain rejection — `user.getDefaultRole()` returning
  // `null` is passed straight through to `generateToken(user, currentRole)` with no null-check
  // of its own, relying entirely on `generateToken`'s own behavior (and this class's surrounding
  // try/catch) to produce a sane outcome. Neither existing test exercises `getDefaultRole()`
  // returning `null` — both stub it with a real `Role` mock — so this closes that gap for both
  // branches `generateToken` could plausibly take.

  @Test
  void nullDefaultRoleIsPassedThroughToGenerateTokenUnmodified() throws JSONException {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    when(callerUser.getDefaultRole()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      // Simulates a generateToken overload tolerant of a null role (e.g. embeds no role claim).
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, null))
          .thenReturn("token-with-no-role-claim");

      webhook.get(parameters, responseVars);

      swsMock.verify(() -> SecureWebServicesUtils.generateToken(callerUser, null), times(1));
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals("token-with-no-role-claim", result.getString("token"));
  }

  @Test
  void nullDefaultRoleSurfacesAsBridgeErrorWhenGenerateTokenRejectsIt() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    when(callerUser.getDefaultRole()).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      // Simulates the realistic alternative: generateToken NPEs (or otherwise throws) on a null
      // role rather than tolerating it. Must be caught by webhook.get()'s own try/catch, same as
      // any other generateToken failure, never propagate out of the webhook.
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, null))
          .thenThrow(new NullPointerException("role must not be null"));

      webhook.get(parameters, responseVars);
    }

    assertEquals("role must not be null", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
  }

  // ── R5 (ETP-5195) — role/user eligibility checks ─────────────────────────

  @Test
  void inactiveUserIsRejectedBeforeGeneratingAToken() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(false);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("User is not active", result.optString("message"));
    assertFalse(responseVars.containsKey("error"));
  }

  @Test
  void activeRoleWithNoActiveUserRolesRowIsRejectedAsIneligible() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(true);
    stubSameClientRole(currentRole);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);
    // No active AD_User_Roles assignment for this user/role pair.
    stubUserRolesCriteria(obDal, 0);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("User is not eligible for the assigned role", result.optString("message"));
    assertFalse(responseVars.containsKey("error"));
  }

  @Test
  void inactiveRoleIsRejectedAsIneligibleWithoutQueryingUserRoles() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(false);
    stubSameClientRole(currentRole);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
      // isEligibleForRole short-circuits on role.isActive() before ever querying UserRoles.
      verify(obDal, never()).createCriteria(UserRoles.class);
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("User is not eligible for the assigned role", result.optString("message"));
    assertFalse(responseVars.containsKey("error"));
  }

  /**
   * ETP-5195, R5 cross-client check — a {@code Role} whose {@code getClient()} resolves to a
   * DIFFERENT client than {@code OBContext.getOBContext().getCurrentClient()} must be rejected as
   * ineligible even though it is otherwise perfectly active, with the exact same failure message
   * as every other ineligibility branch. Structurally mirrors {@code
   * inactiveRoleIsRejectedAsIneligibleWithoutQueryingUserRoles} above: the cross-client guard
   * must short-circuit BEFORE the {@code AD_User_Roles} {@code OBCriteria} query is ever reached,
   * proving this is a genuine pre-check and not something layered on top of the DB lookup.
   */
  @Test
  void crossClientRoleIsRejectedAsIneligibleWithoutQueryingUserRoles() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(true);
    Client foreignClient = mock(Client.class);
    when(foreignClient.getId()).thenReturn("client-999");
    when(currentRole.getClient()).thenReturn(foreignClient);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      webhook.get(parameters, responseVars);

      swsMock.verifyNoInteractions();
      // isEligibleForRole's cross-client guard short-circuits before ever querying UserRoles --
      // same structural proof as the sibling inactive-role test above.
      verify(obDal, never()).createCriteria(UserRoles.class);
    }

    JSONObject result = resultOf(responseVars);
    assertFalse(result.optBoolean("success", true));
    assertEquals("User is not eligible for the assigned role", result.optString("message"));
    assertFalse(responseVars.containsKey("error"));
  }

  // ── generateToken failure ───────────────────────────────────────────────

  @Test
  void generateTokenFailureSurfacesAsBridgeError() {
    User callerUser = givenAuthenticatedUser("user-1");
    when(callerUser.isActive()).thenReturn(true);
    Role currentRole = mock(Role.class);
    when(currentRole.isActive()).thenReturn(true);
    stubSameClientRole(currentRole);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);
    stubUserRolesCriteria(obDal, 1);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole))
          .thenThrow(new RuntimeException("token signing blew up"));

      webhook.get(parameters, responseVars);
    }

    assertEquals("token signing blew up", responseVars.get("error"));
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
