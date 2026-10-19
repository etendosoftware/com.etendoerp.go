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

package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.auth.EnvironmentRequestAuthenticator;
import com.etendoerp.go.auth.WarehouseResolver;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.com.etendoerp.go.schemaforge.data.OAuth2Client;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.session.GoSessionAuthenticator;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionRoleReconciler;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.usageevents.LogCapture;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5455 (H9) — {@code /oauth2/api-keys} and {@code /oauth2/clients} are called by the SPA
 * ({@code apiKeysApi.js}, {@code oauth2Api.js}) but decoded the {@code Authorization} header only:
 * under the cookie session they answered 401 — and the SPA logs out on a 401 — and the legacy
 * kill switch did not apply to them. They now authenticate the signed-in user through the shared
 * environment pipeline (API keys under {@code NEO_DATA}, client management under
 * {@code NEO_AUXILIARY} plus the System Administrator check).
 *
 * <p>No database: the pipeline is built over mocked collaborators and the statics are mocked.
 *
 * @covers com.etendoerp.go.oauth2.OAuth2Servlet
 */
class OAuth2ServletManagementAuthTest {

  private static final String SESSION_TOKEN = "session-cookie-token";
  private static final String CSRF = "csrf-token-value-123456";
  private static final String ORIGIN = "https://app.example.test";
  private static final String JWT_TOKEN = "legacy.jwt.token";
  private static final String USER_ID = "user-1";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";
  private static final String LEGACY_BEARER_PROPERTY = "etgo.legacy.bearer.enabled";

  private GoSessionService sessionService;
  private TenantEnvironmentLifecycleService lifecycle;
  private OAuth2Servlet servlet;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<SecureWebServicesUtils> swsStatic;
  private MockedStatic<NeoLanguage> languageStatic;

  @BeforeEach
  void setUp() {
    sessionService = mock(GoSessionService.class);
    lifecycle = mock(TenantEnvironmentLifecycleService.class);
    servlet = new OAuth2Servlet(sessionService, new EnvironmentRequestAuthenticator(
        new GoSessionAuthenticator(sessionService), lifecycle, mock(WarehouseResolver.class),
        mock(GoSessionRoleReconciler.class)));
    obContextStatic = mockStatic(OBContext.class);
    swsStatic = mockStatic(SecureWebServicesUtils.class);
    languageStatic = mockStatic(NeoLanguage.class);
    swsStatic.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString())).thenReturn(mock(OBContext.class));
  }

  @AfterEach
  void tearDown() {
    languageStatic.close();
    swsStatic.close();
    obContextStatic.close();
    System.clearProperty(LEGACY_BEARER_PROPERTY);
  }

  // ===================== /clients (System Administrator) =====================

  /** The cookie authenticates now; a non-admin role is then refused for its role, not its scheme. */
  @Test
  void clientsUnderACookieSessionWithANonAdminRoleIs403NotA401() throws Exception {
    when(sessionService.resolve(SESSION_TOKEN)).thenReturn(session("role-not-admin"));

    Response resp = run(cookie("GET", "/clients", null, null));

    assertEquals(403, resp.status);
    assertEquals("System Administrator role required", resp.description());
  }

  @Test
  void clientCreationUnderTheCookieWithoutTheCsrfProofIs403() throws Exception {
    when(sessionService.resolve(SESSION_TOKEN)).thenReturn(session("0"));

    Response resp = run(cookie("POST", "/clients", null, "{}"));

    assertEquals(403, resp.status);
    assertEquals("CSRF validation failed", resp.description());
  }

  @Test
  void clientCreationUnderTheCookieFromAForeignOriginIs403ForItsOrigin() throws Exception {
    when(sessionService.resolve(SESSION_TOKEN)).thenReturn(session("0"));
    HttpServletRequest req = cookie("POST", "/clients", CSRF, "{}");
    when(req.getHeader("Origin")).thenReturn("https://evil.example.test");

    Response resp = run(req);

    assertEquals(403, resp.status);
    assertEquals("Origin not allowed", resp.description());
  }

  @Test
  void clientsRefuseAJwtWhenTheLegacySwitchIsOff() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");
    stubJwt("0");

    Response resp = run(bearer("GET", "/clients"));

    assertEquals(401, resp.status);
  }

  /** While the switch is on, the legacy admin JWT keeps working exactly as before. */
  @Test
  void clientsStillRefuseANonAdminJwtWith403() throws Exception {
    stubJwt("role-not-admin");

    Response resp = run(bearer("GET", "/clients"));

    assertEquals(403, resp.status);
    assertEquals("System Administrator role required", resp.description());
  }

  // ===================== /api-keys (environment data) =====================

  /** Decision 2: a blocked tenant's API keys are tenant data, so they are inaccessible too. */
  @Test
  void apiKeysOfACommerciallyBlockedEnvironmentAre402UnderTheCookie() throws Exception {
    when(sessionService.resolve(SESSION_TOKEN)).thenReturn(session("role-1"));
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.SUBSCRIPTION_REQUIRED);

    Response resp = run(cookie("GET", "/api-keys", null, null));

    assertEquals(402, resp.status, "a blocked tenant's API keys must be refused before the handler"
        + " (" + Response.REACHED_HANDLER + " = the request got past authentication)");
    assertEquals("Environment access is not available: SUBSCRIPTION_REQUIRED",
        resp.description());
  }

  @Test
  void apiKeysRefuseAJwtWhenTheLegacySwitchIsOff() throws Exception {
    System.setProperty(LEGACY_BEARER_PROPERTY, "false");
    stubJwt("role-1");

    Response resp = run(bearer("GET", "/api-keys"));

    assertEquals(401, resp.status);
  }

  // ===================== /api-keys malformed JSON body =====================

  /**
   * An authenticated API-key create or update whose body is not JSON (or breaks the parser) is the
   * caller's {@code invalid_request} 400, not the 500 a {@code JSONException} from building the
   * response means; the key is never looked up or written.
   */
  @Test
  void apiKeyWritesWithAMalformedJsonBodyAre400() throws Exception {
    when(sessionService.resolve(SESSION_TOKEN)).thenReturn(session("0"));
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.ALLOWED);
    OBDal dal = activeApiKeyOwnerDal();

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      for (String raw : new String[] { "{\"x\":[", "[[", "not json" }) {
        Response created = run(cookie("POST", "/api-keys", CSRF, raw));
        assertEquals(400, created.status, "POST /api-keys " + raw);
        assertEquals("Malformed request body", created.description());

        Response updated = new Response();
        servlet.doPut(cookie("PUT", "/api-keys/key-1", CSRF, raw), updated.response);
        assertEquals(400, updated.status, "PUT /api-keys/key-1 " + raw);
        assertEquals("Malformed request body", updated.description());
      }
      verify(dal, never()).save(any());
    }
  }

  /**
   * A well-formed update whose {@code isActive} is not a boolean is a quiet 400
   * {@code invalid_request} naming the field (WARN, no stack trace), not the 500 + ERROR a
   * {@code JSONException} from {@code getBoolean} fell into; the key is never written.
   */
  @Test
  void apiKeyUpdateWithANonBooleanIsActiveIsAQuiet400() throws Exception {
    when(sessionService.resolve(SESSION_TOKEN)).thenReturn(session("0"));
    when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
        .thenReturn(Decision.ALLOWED);
    OBDal dal = activeApiKeyOwnerDal();

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
         LogCapture logs = LogCapture.of(OAuth2Servlet.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      for (String raw : new String[] { "{\"isActive\":\"maybe\"}", "{\"isActive\":1}",
          "{\"isActive\":[]}" }) {
        Response updated = new Response();
        servlet.doPut(cookie("PUT", "/api-keys/key-1", CSRF, raw), updated.response);
        assertEquals(400, updated.status, "PUT /api-keys/key-1 " + raw);
        // The JSON is well formed; the description names the field, not a malformed body.
        assertEquals("isActive must be a boolean", updated.description(), raw);
      }
      assertEquals(List.of(), logs.messages(Level.ERROR));
      assertFalse(logs.events(Level.WARN).isEmpty(), "the rejection is logged at WARN");
      for (LogEvent warn : logs.events(Level.WARN)) {
        assertNull(warn.getThrown(), warn.getMessage().getFormattedMessage());
      }
      verify(dal, never()).save(any());
    }
  }

  /** A DAL whose user, role "0", client, org and role assignment are all active, plus key-1. */
  @SuppressWarnings("unchecked")
  private static OBDal activeApiKeyOwnerDal() {
    OBDal dal = mock(OBDal.class);
    User user = mock(User.class);
    when(user.isActive()).thenReturn(true);
    Role role = mock(Role.class);
    when(role.isActive()).thenReturn(true);
    Client client = mock(Client.class);
    when(client.isActive()).thenReturn(true);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization org = mock(Organization.class);
    when(org.isActive()).thenReturn(true);
    when(org.getId()).thenReturn(ORG_ID);
    when(dal.get(User.class, USER_ID)).thenReturn(user);
    when(dal.get(Role.class, "0")).thenReturn(role);
    when(dal.get(Client.class, CLIENT_ID)).thenReturn(client);
    when(dal.get(Organization.class, ORG_ID)).thenReturn(org);

    UserRoles assignment = mock(UserRoles.class);
    when(assignment.getClient()).thenReturn(client);
    when(assignment.getOrganization()).thenReturn(org);
    OBQuery<UserRoles> assignments = mock(OBQuery.class);
    when(assignments.list()).thenReturn(List.of(assignment));
    when(dal.createQuery(eq(UserRoles.class), anyString())).thenReturn(assignments);

    OAuth2Client key = mock(OAuth2Client.class);
    when(key.getId()).thenReturn("key-1");
    when(key.getName()).thenReturn("Key");
    when(key.isActive()).thenReturn(true);
    OBQuery<OAuth2Client> keys = mock(OBQuery.class);
    when(keys.uniqueResult()).thenReturn(key);
    when(dal.createQuery(eq(OAuth2Client.class), anyString())).thenReturn(keys);
    return dal;
  }

  // ===================== fixtures =====================

  /**
   * Runs the request. A request that gets past authentication reaches the handler, whose DAL
   * lookups have no context in this DB-free test and throw; that is recorded as
   * {@link Response#REACHED_HANDLER} so the assertion names what happened instead of the test
   * dying on an unrelated NullPointerException.
   */
  private Response run(HttpServletRequest req) throws Exception {
    Response resp = new Response();
    try {
      if ("GET".equals(req.getMethod())) {
        servlet.doGet(req, resp.response);
      } else {
        servlet.doPost(req, resp.response);
      }
    } catch (RuntimeException reachedHandler) {
      resp.status = Response.REACHED_HANDLER;
    }
    return resp;
  }

  private static GoSessionRecord session(String roleId) {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setAccountId("account-1");
    sessionRecord.setUserId(USER_ID);
    sessionRecord.setRoleId(roleId);
    sessionRecord.setCtxClientId(CLIENT_ID);
    sessionRecord.setCtxOrgId(ORG_ID);
    sessionRecord.setCsrfToken(CSRF);
    return sessionRecord;
  }

  private static HttpServletRequest cookie(String method, String path, String csrf, String body)
      throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getHeader("Origin")).thenReturn(ORIGIN);
    when(req.getHeader(GoSessionSecurity.CSRF_HEADER)).thenReturn(csrf);
    when(req.getRequestURL()).thenReturn(new StringBuffer(ORIGIN + "/oauth2" + path));
    when(req.getCookies()).thenReturn(
        new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, SESSION_TOKEN) });
    if (body != null) {
      when(req.getContentType()).thenReturn("application/json");
      when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
    }
    return req;
  }

  private static HttpServletRequest bearer(String method, String path) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method);
    when(req.getPathInfo()).thenReturn(path);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + JWT_TOKEN);
    return req;
  }

  private void stubJwt(String roleId) {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim user = claim(USER_ID);
    Claim role = claim(roleId);
    Claim client = claim(CLIENT_ID);
    Claim org = claim(ORG_ID);
    when(jwt.getClaim("user")).thenReturn(user);
    when(jwt.getClaim("role")).thenReturn(role);
    when(jwt.getClaim("client")).thenReturn(client);
    when(jwt.getClaim("organization")).thenReturn(org);
    swsStatic.when(() -> SecureWebServicesUtils.decodeToken(JWT_TOKEN)).thenReturn(jwt);
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  private static final class Response {
    /** Status recorded when the request got past authentication into the handler. */
    static final int REACHED_HANDLER = -1;

    final HttpServletResponse response = mock(HttpServletResponse.class);
    private final StringWriter body = new StringWriter();
    int status;

    Response() throws Exception {
      doAnswer(inv -> {
        status = inv.getArgument(0);
        return null;
      }).when(response).setStatus(anyInt());
      when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    String description() throws Exception {
      return new JSONObject(body.toString()).getString("error_description");
    }
  }
}
