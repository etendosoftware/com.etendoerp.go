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
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;
import com.etendoerp.go.usageevents.SessionLoginUsage;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5462 — the two environment-entry paths of {@link EtendoGoJwtServlet} record exactly one
 * {@code session.login} usage event, and only on success:
 * <ul>
 *   <li>{@code GET /sws/go/login?userId=} (legacy bearer) → action {@code login}, ids from the claims
 *       of the environment JWT just issued, no auth method;</li>
 *   <li>{@code POST /sws/go/session/environment} (cookie session) → action {@code cookie-login}, ids
 *       and auth method from the <i>rotated</i> session record.</li>
 * </ul>
 *
 * <p>{@link SessionLoginUsage} is mocked statically and its {@code record(...)} answer snapshots the
 * response at the moment it is called. That one snapshot proves two contracts at once: recording
 * happens <b>after</b> the 200 has been written (status set, body flushed), and a recording failure
 * cannot change what the caller already got. Everything else — DAL lookups, role list, token
 * generation, the session service — is stubbed exactly as in {@code GoSessionEndpointsTest} and
 * {@code EtendoGoJwtServletTest}, so no database is touched.</p>
 */
public class EtendoGoJwtServletSessionLoginUsageTest {

  private static final String ORIGIN = "https://app.example.test";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String EMAIL = "user@example.test";
  private static final String LEGACY_FLAG = "etgo.legacy.bearer.enabled";

  private static final String CLIENT = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String ORG = "0A1B2C3D4E5F60718293A4B5C6D7E8F9";
  private static final String USER = "F0E1D2C3B4A5968778695A4B3C2D1E0F";
  private static final String ROLE = "11112222333344445555666677778888";

  @After
  public void clearLegacyFlag() {
    System.clearProperty(LEGACY_FLAG);
  }

  // ── capture ───────────────────────────────────────────────────────────

  /** One {@code SessionLoginUsage.record(...)} call plus the response state at that instant. */
  private static final class RecordCall {
    String action;
    String clientId;
    String orgId;
    String userId;
    String roleId;
    String authMethod;
    int statusAtCall;
    String bodyAtCall;
  }

  /** Mock response capturing status and body. */
  private static final class Captured {
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final StringWriter body = new StringWriter();
    int status;

    Captured() throws Exception {
      doAnswer(inv -> {
        status = inv.getArgument(0);
        return null;
      }).when(response).setStatus(anyInt());
      when(response.getWriter()).thenReturn(new PrintWriter(body));
    }
  }

  /** Stub {@code record(...)} to snapshot the call; optionally throw after the snapshot. */
  private static void captureRecord(MockedStatic<SessionLoginUsage> usage, Captured resp,
      List<RecordCall> calls, boolean fail) {
    usage.when(() -> SessionLoginUsage.record(any(), any(), any(), any(), any(), any(), anyLong()))
        .thenAnswer(inv -> {
          RecordCall call = new RecordCall();
          call.action = inv.getArgument(0);
          call.clientId = inv.getArgument(1);
          call.orgId = inv.getArgument(2);
          call.userId = inv.getArgument(3);
          call.roleId = inv.getArgument(4);
          call.authMethod = inv.getArgument(5);
          call.statusAtCall = resp.status;
          call.bodyAtCall = resp.body.toString();
          calls.add(call);
          if (fail) {
            throw new IllegalStateException("usage recorder exploded");
          }
          return null;
        });
  }

  private static String signedEnvironmentJwt() {
    return JWT.create()
        .withClaim("client", CLIENT)
        .withClaim("organization", ORG)
        .withClaim("user", USER)
        .withClaim("role", ROLE)
        .withClaim("warehouse", "W1")
        .sign(Algorithm.HMAC256("test-secret-not-a-real-key"));
  }

  // ── GET /login (legacy bearer) ────────────────────────────────────────

  /** Knobs for one {@code GET /login} run; the defaults are the success path. */
  private static final class Login {
    String bearer = "valid-token";
    String userId = "U1";
    boolean accountFound = true;
    boolean owned = true;
    boolean userFound = true;
    boolean roleListThrows;
    String issuedToken = signedEnvironmentJwt();
    boolean recordThrows;

    final List<RecordCall> calls = new ArrayList<>();
    Captured resp;
  }

  private static Login runLogin(Login s) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getPathInfo()).thenReturn("/login");
    when(req.getMethod()).thenReturn("GET");
    when(req.getHeader("Authorization")).thenReturn(s.bearer == null ? null : "Bearer " + s.bearer);
    when(req.getParameter("userId")).thenReturn(s.userId);

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(EMAIL);
    User user = mock(User.class);
    Role role = mock(Role.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "U1")).thenReturn(s.userFound ? user : null);
    when(obDal.get(Role.class, "R1")).thenReturn(role);
    EtendoGoJwtSupport.RoleListData roleList = new EtendoGoJwtSupport.RoleListData("R1",
        new JSONArray().put(new JSONObject().put("id", "R1")));

    s.resp = new Captured();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> supp = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class);
        MockedStatic<SessionLoginUsage> usage = mockStatic(SessionLoginUsage.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(s.bearer))
          .thenReturn(s.accountFound ? account : null);
      supp.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(EMAIL, "U1"))
          .thenReturn(s.owned);
      if (s.roleListThrows) {
        supp.when(() -> EtendoGoJwtSupport.loadRoleListData("U1"))
            .thenThrow(new IllegalStateException("db down"));
      } else {
        supp.when(() -> EtendoGoJwtSupport.loadRoleListData("U1")).thenReturn(roleList);
      }
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn(s.issuedToken);
      captureRecord(usage, s.resp, s.calls, s.recordThrows);

      new EtendoGoJwtServlet().doGet(req, s.resp.response);
    }
    return s;
  }

  @Test
  public void loginSuccessRecordsOneLoginEventWithTheIssuedTokenIds() throws Exception {
    Login s = runLogin(new Login());

    assertEquals(200, s.resp.status);
    assertEquals(s.issuedToken, new JSONObject(s.resp.body.toString()).getString("token"));
    assertEquals(1, s.calls.size());
    RecordCall call = s.calls.get(0);
    assertEquals(SessionLoginUsage.ACTION_LOGIN, call.action);
    assertEquals(CLIENT, call.clientId);
    assertEquals(ORG, call.orgId);
    assertEquals(USER, call.userId);
    assertEquals(ROLE, call.roleId);
    assertNull("the legacy path has no platform auth method", call.authMethod);
  }

  @Test
  public void loginRecordsOnlyAfterTheResponseIsWritten() throws Exception {
    Login s = runLogin(new Login());

    RecordCall call = s.calls.get(0);
    assertEquals("status must already be 200 when recording", 200, call.statusAtCall);
    assertEquals("the body must already be flushed when recording",
        s.resp.body.toString(), call.bodyAtCall);
    assertTrue(call.bodyAtCall.contains("\"token\""));
  }

  @Test
  public void loginWithAnUndecodableIssuedTokenStill200sWithoutRecording() throws Exception {
    Login reference = runLogin(new Login());
    Login s = new Login();
    s.issuedToken = "not-a-jwt";

    runLogin(s);

    assertEquals(200, s.resp.status);
    assertEquals("not-a-jwt", new JSONObject(s.resp.body.toString()).getString("token"));
    assertEquals(new JSONObject(reference.resp.body.toString()).getJSONArray("roleList")
        .toString(), new JSONObject(s.resp.body.toString()).getJSONArray("roleList").toString());
    assertTrue(s.calls.isEmpty());
  }

  @Test
  public void loginWhenRecordingThrowsStill200sWithTheSameBody() throws Exception {
    Login reference = runLogin(new Login());
    Login s = new Login();
    s.issuedToken = reference.issuedToken;
    s.recordThrows = true;

    runLogin(s);

    assertEquals(1, s.calls.size());
    assertEquals(200, s.resp.status);
    assertEquals("no error envelope may be written over the answer",
        reference.resp.body.toString(), s.resp.body.toString());
  }

  private static void assertLoginNotRecorded(Login s, int expectedStatus) throws Exception {
    runLogin(s);
    assertEquals(expectedStatus, s.resp.status);
    assertTrue("record must not be called on a " + expectedStatus, s.calls.isEmpty());
  }

  @Test
  public void loginFailuresNeverRecord() throws Exception {
    Login noToken = new Login();
    noToken.bearer = null;
    assertLoginNotRecorded(noToken, 401);

    Login noUserId = new Login();
    noUserId.userId = null;
    assertLoginNotRecorded(noUserId, 400);

    Login emptyUserId = new Login();
    emptyUserId.userId = "";
    assertLoginNotRecorded(emptyUserId, 400);

    Login noAccount = new Login();
    noAccount.accountFound = false;
    assertLoginNotRecorded(noAccount, 401);

    Login notOwner = new Login();
    notOwner.owned = false;
    assertLoginNotRecorded(notOwner, 403);

    Login noUser = new Login();
    noUser.userFound = false;
    assertLoginNotRecorded(noUser, 404);

    Login dbDown = new Login();
    dbDown.roleListThrows = true;
    assertLoginNotRecorded(dbDown, 500);
  }

  @Test
  public void loginWithTheLegacyBearerDisabledNeverRecords() throws Exception {
    System.setProperty(LEGACY_FLAG, "false");
    assertLoginNotRecorded(new Login(), 401);
  }

  // ── POST /session/environment (cookie session) ────────────────────────

  /** Knobs for one {@code POST /session/environment} run; the defaults are the success path. */
  private static final class Env {
    String body;
    String csrf = CSRF;
    boolean sessionFound = true;
    boolean accountFound = true;
    boolean owned = true;
    boolean userFound = true;
    boolean roleListThrows;
    String requestedRole = "R1";
    boolean rotateReturnsNull;
    boolean recordThrows;
    GoSessionRecord rotatedRecord = rotatedRecord("sso");

    final GoSessionService sessions = mock(GoSessionService.class);
    final List<RecordCall> calls = new ArrayList<>();
    Captured resp;

    Env() throws Exception {
      body = new JSONObject().put("userId", "U1").put("roleId", "R1").toString();
    }
  }

  /** The record the session service hands back: deliberately different ids from the JWT claims. */
  private static GoSessionRecord rotatedRecord(String authMethod) {
    GoSessionRecord rotated = new GoSessionRecord();
    rotated.setUserId(USER);
    rotated.setRoleId(ROLE);
    rotated.setCtxClientId(CLIENT);
    rotated.setCtxOrgId(ORG);
    rotated.setWarehouseId("W-ROT");
    rotated.setAuthMethod(authMethod);
    return rotated;
  }

  private static Claim claim(String value) {
    Claim c = mock(Claim.class);
    when(c.asString()).thenReturn(value);
    return c;
  }

  private static Env runEnv(Env s) throws Exception {
    GoSessionRecord current = new GoSessionRecord();
    current.setAccountId("ACC1");
    current.setCsrfToken(CSRF);
    when(s.sessions.resolve("tok")).thenReturn(s.sessionFound ? current : null);
    when(s.sessions.rotate(any())).thenReturn(s.rotateReturnsNull ? null
        : new IssuedGoSession("newtok", "newref", "newcsrf", s.rotatedRecord));

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn("/session/environment");
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(s.body)));
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(s.csrf);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/sws/go/session/environment"));
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, "tok") });

    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(EMAIL);
    User user = mock(User.class);
    Role role = mock(Role.class);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, "U1")).thenReturn(s.userFound ? user : null);
    when(obDal.get(Role.class, "R1")).thenReturn(role);
    EtendoGoJwtSupport.RoleListData roleList = new EtendoGoJwtSupport.RoleListData("R1",
        new JSONArray().put(new JSONObject().put("id", "R1")
            .put("orgList", new JSONArray().put(new JSONObject().put("id", "O1")))));

    DecodedJWT decoded = mock(DecodedJWT.class);
    Claim userClaim = claim("U1");
    Claim roleClaim = claim("R1");
    Claim clientClaim = claim("C1");
    Claim orgClaim = claim("O1");
    Claim warehouseClaim = claim("W1");
    when(decoded.getClaim("user")).thenReturn(userClaim);
    when(decoded.getClaim("role")).thenReturn(roleClaim);
    when(decoded.getClaim("client")).thenReturn(clientClaim);
    when(decoded.getClaim("organization")).thenReturn(orgClaim);
    when(decoded.getClaim("warehouse")).thenReturn(warehouseClaim);

    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
        s.sessions);
    s.resp = new Captured();
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoJwtSupport> supp = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class);
        MockedStatic<SessionLoginUsage> usage = mockStatic(SessionLoginUsage.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById("ACC1"))
          .thenReturn(s.accountFound ? account : null);
      supp.when(() -> EtendoGoJwtSupport.isEnvironmentUserOwnedByAccount(any(), eq("U1")))
          .thenReturn(s.owned);
      if (s.roleListThrows) {
        supp.when(() -> EtendoGoJwtSupport.loadRoleListData("U1"))
            .thenThrow(new IllegalStateException("db down"));
      } else {
        supp.when(() -> EtendoGoJwtSupport.loadRoleListData("U1")).thenReturn(roleList);
      }
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      sws.when(() -> SecureWebServicesUtils.generateToken(user, role)).thenReturn("jwt");
      sws.when(() -> SecureWebServicesUtils.decodeToken("jwt")).thenReturn(decoded);
      captureRecord(usage, s.resp, s.calls, s.recordThrows);

      servlet.doPost(req, s.resp.response);
    }
    return s;
  }

  @Test
  public void environmentSuccessRecordsOneCookieLoginFromTheRotatedRecord() throws Exception {
    Env s = runEnv(new Env());

    assertEquals(200, s.resp.status);
    assertEquals(1, s.calls.size());
    RecordCall call = s.calls.get(0);
    assertEquals(SessionLoginUsage.ACTION_COOKIE_LOGIN, call.action);
    // The rotated record's ids, not the claims of the intermediate JWT (C1/O1/U1/R1).
    assertEquals(CLIENT, call.clientId);
    assertEquals(ORG, call.orgId);
    assertEquals(USER, call.userId);
    assertEquals(ROLE, call.roleId);
    assertEquals("sso", call.authMethod);
  }

  @Test
  public void environmentPassesThePasswordAuthMethodThrough() throws Exception {
    Env s = new Env();
    s.rotatedRecord = rotatedRecord("password");
    runEnv(s);
    assertEquals("password", s.calls.get(0).authMethod);
  }

  @Test
  public void environmentRecordsOnlyAfterTheResponseIsWritten() throws Exception {
    Env s = runEnv(new Env());

    RecordCall call = s.calls.get(0);
    assertEquals(200, call.statusAtCall);
    assertEquals(s.resp.body.toString(), call.bodyAtCall);
    assertTrue(call.bodyAtCall.contains("\"csrfToken\""));
  }

  @Test
  public void environmentWhenRecordingThrowsStill200sWithTheSameBody() throws Exception {
    Env reference = runEnv(new Env());
    Env s = new Env();
    s.recordThrows = true;

    runEnv(s);

    assertEquals(1, s.calls.size());
    assertEquals(200, s.resp.status);
    assertEquals(reference.resp.body.toString(), s.resp.body.toString());
  }

  private static void assertEnvNotRecorded(Env s, int expectedStatus) throws Exception {
    runEnv(s);
    assertEquals(expectedStatus, s.resp.status);
    assertTrue("record must not be called on a " + expectedStatus, s.calls.isEmpty());
  }

  @Test
  public void environmentFailuresNeverRecord() throws Exception {
    Env badJson = new Env();
    badJson.body = "not json";
    assertEnvNotRecorded(badJson, 400);

    Env noUserId = new Env();
    noUserId.body = "{}";
    assertEnvNotRecorded(noUserId, 400);

    Env noCsrf = new Env();
    noCsrf.csrf = null;
    assertEnvNotRecorded(noCsrf, 403);

    Env noSession = new Env();
    noSession.sessionFound = false;
    assertEnvNotRecorded(noSession, 401);

    Env noAccount = new Env();
    noAccount.accountFound = false;
    assertEnvNotRecorded(noAccount, 401);

    Env notOwner = new Env();
    notOwner.owned = false;
    assertEnvNotRecorded(notOwner, 403);

    Env noUser = new Env();
    noUser.userFound = false;
    assertEnvNotRecorded(noUser, 404);

    Env roleNotAvailable = new Env();
    roleNotAvailable.body = new JSONObject().put("userId", "U1").put("roleId", "R-OTHER")
        .toString();
    assertEnvNotRecorded(roleNotAvailable, 403);

    Env concurrent = new Env();
    concurrent.rotateReturnsNull = true;
    assertEnvNotRecorded(concurrent, 409);

    Env dbDown = new Env();
    dbDown.roleListThrows = true;
    assertEnvNotRecorded(dbDown, 500);
  }

  @Test
  public void theConflictPathReachesRotateAndStillDoesNotRecord() throws Exception {
    // The 409 path did reach rotate, so the missing record is the servlet's decision on a failed
    // rotation, not an early exit before the interesting code.
    Env concurrent = new Env();
    concurrent.rotateReturnsNull = true;
    runEnv(concurrent);
    verify(concurrent.sessions).rotate(any());
    assertTrue(concurrent.calls.isEmpty());
  }
}
