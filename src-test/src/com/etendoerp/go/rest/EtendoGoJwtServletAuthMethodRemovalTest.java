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
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountIdentity;

/**
 * Unit tests for {@code POST /sws/go/auth-methods/remove} (ETP-5115).
 *
 * <p>What these pin is the invariant the endpoint exists to protect: an account must always keep at
 * least one way of signing in, and the check that guarantees it is made here, on the server, over
 * the account's whole method set as it is at that instant. The refusal tests therefore assert that
 * nothing was written — that {@code removeLocalPassword} and {@code unlink} were never reached —
 * rather than merely that a 409 came back. A status code proves the caller was told no; only the
 * absent write proves the account was actually left reachable.
 *
 * <p>The asymmetry in re-authentication is pinned deliberately too: removing the password demands
 * the current password, removing an identity does not. That is a decision, not an omission, so a
 * test guards it in both directions.
 *
 * <p>State is simulated as mutable rather than stubbed flat, because the response has to be built
 * from the account as it is <em>after</em> the removal: the settings screen redraws from the
 * {@code authMethods} that comes back, so a payload still describing the removed method would be a
 * real defect that a fixed stub could never expose.
 */
public class EtendoGoJwtServletAuthMethodRemovalTest {

  private static final String PATH = "/auth-methods/remove";
  private static final String VALID_TOKEN = "valid-token";
  private static final String PASSWORD = "secret";
  private static final String TOKEN_PATTERN = "[0-9a-f]{32}";

  // ===================== The last method can never be removed =====================

  /**
   * An account reachable only by password. The request is otherwise perfect — right method, right
   * current password — and still has to be refused, because granting it would leave nobody able to
   * enter the account.
   */
  @Test
  public void removingTheOnlyPasswordIsRefusedAndWritesNothing() throws Exception {
    Fixture fixture = passwordOnlyAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN,
        body("password", PASSWORD), (dal, identities) -> {
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
          dal.verify(() -> EtendoGoJwtDalHelper.updateSessionToken(any(), anyString()), never());
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
        });

    assertEquals(409, resp.status);
    assertEquals("LAST_AUTH_METHOD", errorCode(resp));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  /**
   * The mirror case: an SSO-born account with a single linked identity and no password. Same
   * refusal, same silence on the write path — the rule counts methods, it does not care which kind
   * they are.
   */
  @Test
  public void removingTheOnlyIdentityIsRefusedAndWritesNothing() throws Exception {
    Fixture fixture = identityOnlyAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("google", null),
        (dal, identities) -> {
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
          dal.verify(() -> EtendoGoJwtDalHelper.updateSessionToken(any(), anyString()), never());
        });

    assertEquals(409, resp.status);
    assertEquals("LAST_AUTH_METHOD", errorCode(resp));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  // ===================== Removing the password =====================

  @Test
  public void removingThePasswordRotatesTheSessionAndMailsTheNotice() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("password", PASSWORD),
        (dal, identities) -> {
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(eq(fixture.account),
              sessionToken.capture(), any(Date.class)));
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
        });

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals("success", json.getString("status"));
    // The session is rotated: the caller leaves holding a token that was not the one they arrived
    // with, and it is the very token the DAL was told to store.
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches(TOKEN_PATTERN));
    assertNotEquals(VALID_TOKEN, json.getString("token"));
    verify(fixture.emailSender).sendAuthMethodRemoved(fixture.account);
  }

  @Test
  public void removingThePasswordWithoutTheCurrentPasswordIsRejected() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("password", null),
        (dal, identities) -> {
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
        });

    assertEquals(400, resp.status);
    assertEquals("CHANGE_PASSWORD_MISSING_CREDENTIALS", errorCode(resp));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  @Test
  public void removingThePasswordWithTheWrongCurrentPasswordIsRejected() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("password", "not-the-password"),
        (dal, identities) -> {
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
          dal.verify(() -> EtendoGoJwtDalHelper.updateSessionToken(any(), anyString()), never());
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
        });

    assertEquals(401, resp.status);
    assertEquals("INVALID_CURRENT_PASSWORD", errorCode(resp));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  // ===================== Removing an identity =====================

  /**
   * No {@code currentPassword} is sent and the removal still goes through. The asymmetry with the
   * password case is deliberate: no equally cheap proof exists for a provider, the session token
   * carries it, and the notice mail is what makes an unwanted removal visible. Pinned so that
   * tightening it later is a conscious change rather than an accident.
   */
  @Test
  public void removingAnIdentityNeedsNoCurrentPassword() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();
    AccountIdentity google = fixture.identities.get(0);
    ArgumentCaptor<String> sessionToken = ArgumentCaptor.forClass(String.class);

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("google", null),
        (dal, identities) -> {
          identities.verify(() -> AccountIdentityDalHelper.unlink(google));
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
          dal.verify(() -> EtendoGoJwtDalHelper.updateSessionToken(eq(fixture.account),
              sessionToken.capture()));
        });

    assertEquals(200, resp.status);
    JSONObject json = new JSONObject(resp.body());
    assertEquals("success", json.getString("status"));
    assertEquals(json.getString("token"), sessionToken.getValue());
    assertTrue(sessionToken.getValue().matches(TOKEN_PATTERN));
    verify(fixture.emailSender).sendAuthMethodRemoved(fixture.account);
  }

  /** Only the named identity goes; a second linked provider is left strictly alone. */
  @Test
  public void removingAnIdentityTouchesOnlyThatIdentity() throws Exception {
    AccountIdentity google = mockIdentity("google", "user@gmail.test");
    AccountIdentity microsoft = mockIdentity("microsoft", "user@outlook.test");
    Fixture fixture = new Fixture(false, google, microsoft);

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("google", null),
        (dal, identities) -> {
          identities.verify(() -> AccountIdentityDalHelper.unlink(google));
          identities.verify(() -> AccountIdentityDalHelper.unlink(microsoft), never());
        });

    assertEquals(200, resp.status);
    JSONArray remaining = authMethods(resp).getJSONArray("identities");
    assertEquals(1, remaining.length());
    assertEquals("microsoft", remaining.getJSONObject(0).getString("provider"));
  }

  // ===================== A method the account does not have =====================

  @Test
  public void removingAnUnlinkedProviderReturnsNotFound() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("microsoft", null),
        (dal, identities) -> {
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
        });

    assertEquals(404, resp.status);
    assertEquals("AUTH_METHOD_NOT_FOUND", errorCode(resp));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  /**
   * Two identities and no password: the account has methods to spare, so the 404 here can only come
   * from the method genuinely being absent and not from the last-method guard.
   */
  @Test
  public void removingAPasswordFromAnAccountWithoutOneReturnsNotFound() throws Exception {
    AccountIdentity google = mockIdentity("google", "user@gmail.test");
    AccountIdentity microsoft = mockIdentity("microsoft", "user@outlook.test");
    Fixture fixture = new Fixture(false, google, microsoft);

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("password", PASSWORD),
        (dal, identities) -> {
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
          identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
        });

    assertEquals(404, resp.status);
    assertEquals("AUTH_METHOD_NOT_FOUND", errorCode(resp));
  }

  // ===================== Request validation =====================

  @Test
  public void missingMethodReturnsBadRequestBeforeTheAccountIsLookedUp() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, "{}", (dal, identities) -> {
      dal.verify(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(anyString()), never());
      identities.verifyNoInteractions();
    });

    assertEquals(400, resp.status);
    assertEquals("CHANGE_PASSWORD_MISSING_CREDENTIALS", errorCode(resp));
  }

  /** Whitespace is not a method: the field is trimmed before it is judged present. */
  @Test
  public void blankMethodReturnsBadRequest() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, "{\"method\":\"   \"}", null);

    assertEquals(400, resp.status);
    assertEquals("CHANGE_PASSWORD_MISSING_CREDENTIALS", errorCode(resp));
  }

  @Test
  public void invalidJsonBodyReturnsBadRequest() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, "not json at all", null);

    assertEquals(400, resp.status);
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  @Test
  public void invalidBearerTokenReturnsUnauthorizedWithoutReadingTheMethods() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, "bad-token", body("password", PASSWORD),
        (dal, identities) -> {
          // The 401 must cost nothing beyond the failed lookup: no method set is read, so no
          // information about the account can leak through timing or through a partial write.
          identities.verifyNoInteractions();
          dal.verify(() -> EtendoGoJwtDalHelper.hasLocalPassword(any()), never());
          dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
              never());
        });

    assertEquals(401, resp.status);
    assertFalse(resp.body().contains("authMethods"));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  @Test
  public void missingAuthorizationHeaderReturnsUnauthorizedWithoutReadingTheBody()
      throws Exception {
    Fixture fixture = passwordAndGoogleAccount();
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getPathInfo()).thenReturn(PATH);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<AccountIdentityDalHelper> identities =
             mockStatic(AccountIdentityDalHelper.class)) {
      fixture.servlet.doPost(req, resp.response);

      dal.verifyNoInteractions();
      identities.verifyNoInteractions();
    }

    assertEquals(401, resp.status);
    verify(req, never()).getReader();
  }

  // ===================== The response redraws the screen =====================

  @Test
  public void theResponseReportsTheMethodsThatRemainAfterRemovingThePassword() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("password", PASSWORD), null);

    assertEquals(200, resp.status);
    JSONObject authMethods = authMethods(resp);
    assertFalse(authMethods.getJSONObject("password").getBoolean("enabled"));
    JSONArray identities = authMethods.getJSONArray("identities");
    assertEquals(1, identities.length());
    assertEquals("google", identities.getJSONObject(0).getString("provider"));
    // One method left, so the screen must draw it without a remove control.
    assertEquals(0, authMethods.getJSONArray("removable").length());
  }

  @Test
  public void theResponseReportsTheMethodsThatRemainAfterRemovingAnIdentity() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();

    ResponseCapture resp = post(fixture, VALID_TOKEN, body("google", null), null);

    assertEquals(200, resp.status);
    JSONObject authMethods = authMethods(resp);
    assertTrue(authMethods.getJSONObject("password").getBoolean("enabled"));
    assertEquals(0, authMethods.getJSONArray("identities").length());
    assertEquals(0, authMethods.getJSONArray("removable").length());
  }

  // ===================== The invariant is re-read, never taken from the request =====================

  /**
   * A caller that ships its own view of what is removable changes nothing. The {@code removable}
   * list {@code /me} publishes is for drawing the screen; trusting anything the client says about
   * it is exactly how two tabs would each be allowed through and empty the account between them.
   */
  @Test
  public void aBodyClaimingTheLastMethodIsRemovableIsStillRefused() throws Exception {
    Fixture fixture = passwordOnlyAccount();
    String smuggled = "{\"method\":\"password\",\"currentPassword\":\"" + PASSWORD + "\","
        + "\"removable\":[\"password\"],\"methodCount\":5,"
        + "\"authMethods\":{\"password\":{\"enabled\":true},"
        + "\"identities\":[{\"provider\":\"google\"}],\"removable\":[\"password\",\"google\"]}}";

    ResponseCapture resp = post(fixture, VALID_TOKEN, smuggled, (dal, identities) -> {
      dal.verify(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()),
          never());
      identities.verify(() -> AccountIdentityDalHelper.unlink(any()), never());
    });

    assertEquals(409, resp.status);
    assertEquals("LAST_AUTH_METHOD", errorCode(resp));
    verify(fixture.emailSender, never()).sendAuthMethodRemoved(any());
  }

  /** And the same in the other direction: a body denying the removal is equally ignored. */
  @Test
  public void aBodyClaimingNothingIsRemovableStillRemoves() throws Exception {
    Fixture fixture = passwordAndGoogleAccount();
    String smuggled = "{\"method\":\"google\",\"removable\":[],"
        + "\"authMethods\":{\"password\":{\"enabled\":false},\"identities\":[],"
        + "\"removable\":[]}}";
    AccountIdentity google = fixture.identities.get(0);

    ResponseCapture resp = post(fixture, VALID_TOKEN, smuggled,
        (dal, identities) -> identities.verify(() -> AccountIdentityDalHelper.unlink(google)));

    assertEquals(200, resp.status);
    assertEquals("success", new JSONObject(resp.body()).getString("status"));
  }

  // ===================== Fixture =====================

  /**
   * The account under test, with its method set held as mutable state.
   *
   * <p>{@code hasLocalPassword} and {@code identitiesFor} answer from this state rather than
   * returning a fixed value, and the write helpers mutate it, so the {@code authMethods} the
   * endpoint builds after the removal describes the account as it then is — which is the whole
   * point of that payload.
   */
  private static final class Fixture {
    final Account account = mock(Account.class);
    final TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
    final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(emailSender);
    final AtomicBoolean hasPassword;
    final List<AccountIdentity> identities = new ArrayList<>();

    Fixture(boolean hasPassword, AccountIdentity... identities) throws Exception {
      this.hasPassword = new AtomicBoolean(hasPassword);
      for (AccountIdentity identity : identities) {
        this.identities.add(identity);
      }
      when(account.getId()).thenReturn("acct-1");
      when(account.getEmail()).thenReturn("user@test.com");
      when(account.getName()).thenReturn("Test User");
      when(account.getPasswordHash()).thenReturn(hasPassword ? testPasswordHash(PASSWORD) : null);
    }
  }

  /** Static-mock assertions, which have to run before the mocks are closed. */
  @FunctionalInterface
  private interface StaticChecks {
    void run(MockedStatic<EtendoGoJwtDalHelper> dal, MockedStatic<AccountIdentityDalHelper> ident)
        throws Exception;
  }

  private static Fixture passwordOnlyAccount() throws Exception {
    return new Fixture(true);
  }

  private static Fixture identityOnlyAccount() throws Exception {
    AccountIdentity google = mockIdentity("google", "user@gmail.test");
    return new Fixture(false, google);
  }

  private static Fixture passwordAndGoogleAccount() throws Exception {
    AccountIdentity google = mockIdentity("google", "user@gmail.test");
    return new Fixture(true, google);
  }

  private static ResponseCapture post(Fixture fixture, String token, String body,
      StaticChecks checks) throws Exception {
    ResponseCapture resp = mockResponse();
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getPathInfo()).thenReturn(PATH);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + token);
    when(req.getContentType()).thenReturn("application/json");
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
         MockedStatic<AccountIdentityDalHelper> ident =
             mockStatic(AccountIdentityDalHelper.class)) {
      dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(anyString()))
          .thenAnswer(call -> VALID_TOKEN.equals(call.getArgument(0)) ? fixture.account : null);
      dal.when(() -> EtendoGoJwtDalHelper.hasLocalPassword(fixture.account))
          .thenAnswer(call -> fixture.hasPassword.get());
      dal.when(() -> EtendoGoJwtDalHelper.getPasswordChangedAt(fixture.account)).thenReturn(null);
      dal.when(() -> EtendoGoJwtDalHelper.removeLocalPassword(any(), anyString(), any()))
          .thenAnswer(call -> {
            fixture.hasPassword.set(false);
            return null;
          });
      ident.when(() -> AccountIdentityDalHelper.identitiesFor(fixture.account))
          .thenAnswer(call -> new ArrayList<>(fixture.identities));
      ident.when(() -> AccountIdentityDalHelper.identityForProvider(eq(fixture.account),
          anyString())).thenAnswer(call -> findIdentity(fixture, call.getArgument(1)));
      ident.when(() -> AccountIdentityDalHelper.unlink(any())).thenAnswer(call -> {
        fixture.identities.remove((AccountIdentity) call.getArgument(0));
        return null;
      });

      fixture.servlet.doPost(req, resp.response);

      if (checks != null) {
        checks.run(dal, ident);
      }
    }
    return resp;
  }

  private static AccountIdentity findIdentity(Fixture fixture, String provider) {
    for (AccountIdentity identity : fixture.identities) {
      if (identity.getAuthProvider().equals(provider)) {
        return identity;
      }
    }
    return null;
  }

  private static String body(String method, String currentPassword) {
    StringBuilder json = new StringBuilder("{\"method\":\"").append(method).append('"');
    if (currentPassword != null) {
      json.append(",\"currentPassword\":\"").append(currentPassword).append('"');
    }
    return json.append('}').toString();
  }

  private static String errorCode(ResponseCapture resp) throws Exception {
    return new JSONObject(resp.body()).getJSONObject("error").getString("code");
  }

  private static JSONObject authMethods(ResponseCapture resp) throws Exception {
    return new JSONObject(resp.body()).getJSONObject("authMethods");
  }

  private static AccountIdentity mockIdentity(String provider, String email) {
    AccountIdentity identity = mock(AccountIdentity.class);
    when(identity.getAuthProvider()).thenReturn(provider);
    when(identity.getExternalEmail()).thenReturn(email);
    when(identity.getLinked()).thenReturn(null);
    when(identity.getLastSSOLogin()).thenReturn(null);
    return identity;
  }

  private static String testPasswordHash(String password) throws Exception {
    byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(salt);
    byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(salt) + ":"
        + Base64.getEncoder().encodeToString(hash);
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    PrintWriter writer = new PrintWriter(body);
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    doAnswer(inv -> null).when(response).setContentType(anyString());
    doAnswer(inv -> null).when(response).setCharacterEncoding(anyString());
    when(response.getWriter()).thenReturn(writer);
    return capture;
  }

  private static final class ResponseCapture {
    final HttpServletResponse response;
    private final StringWriter body;
    int status;

    ResponseCapture(HttpServletResponse response, StringWriter body) {
      this.response = response;
      this.body = body;
    }

    String body() {
      return body.toString();
    }
  }
}
