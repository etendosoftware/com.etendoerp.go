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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

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
    Role currentRole = mock(Role.class);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    // Decoy: if this webhook ever started reading identity from the parameter map, it would
    // pick up "someone-elses-id" instead of the caller's own "user-1".
    parameters.put("userId", "someone-elses-id");
    parameters.put("UserId", "someone-elses-id");

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedStatic<SecureWebServicesUtils> swsMock = mockStatic(SecureWebServicesUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      swsMock.when(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole))
          .thenReturn("fresh-jwt-token");

      webhook.get(parameters, responseVars);

      swsMock.verify(() -> SecureWebServicesUtils.generateToken(callerUser, currentRole), times(1));
      // Never resolved via the decoy id -- OBDal.get was only ever asked for the caller's own id.
      verify(obDal, never()).get(User.class, "someone-elses-id");
    }

    assertFalse(responseVars.containsKey("error"));
    JSONObject result = resultOf(responseVars);
    assertEquals("fresh-jwt-token", result.getString("token"));
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

  // ── generateToken failure ───────────────────────────────────────────────

  @Test
  void generateTokenFailureSurfacesAsBridgeError() {
    User callerUser = givenAuthenticatedUser("user-1");
    Role currentRole = mock(Role.class);
    when(callerUser.getDefaultRole()).thenReturn(currentRole);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "user-1")).thenReturn(callerUser);

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
