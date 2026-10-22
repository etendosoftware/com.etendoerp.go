/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.signup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.rest.AccountRegistrationService;
import com.etendoerp.go.rest.AccountRegistrationService.EmailStatus;
import com.etendoerp.go.rest.SignupAccountService;
import com.etendoerp.go.rest.SignupAccountService.LinkRefusal;
import com.etendoerp.go.rest.SignupAccountService.LoginLink;
import com.etendoerp.go.rest.SignupAccountService.RetryOutcome;

/**
 * Routing, key authentication and the error contract of the signup API (ETP-5847). The services
 * are mocked: what is under test is what the caller receives.
 *
 * @covers com.etendoerp.go.signup.SignupApiServlet
 * @covers com.etendoerp.go.signup.SignupOptions
 */
class SignupApiServletTest {

  private static final String KEY = "test-web-key";
  private static final String ACCOUNT_ID = "0123456789ABCDEF0123456789ABCDEF";

  private final AccountRegistrationService registration = mock(AccountRegistrationService.class);
  private final SignupAccountService signups = mock(SignupAccountService.class);
  private final SignupApiServlet servlet = new SignupApiServlet(registration, signups);

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final HttpServletResponse response = mock(HttpServletResponse.class);
  private final StringWriter body = new StringWriter();
  private final Map<String, String> headers = new HashMap<>();
  private int status;

  private MockedStatic<OBContext> obContext;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty(SignupApiKeys.PROPERTY, "web:" + SignupApiKeysTest.hashOf(KEY));
    obContext = mockStatic(OBContext.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    Mockito.doAnswer(call -> status = call.getArgument(0)).when(response).setStatus(Mockito.anyInt());
    Mockito.doAnswer(call -> headers.put(call.getArgument(0), call.getArgument(1)))
        .when(response).setHeader(anyString(), anyString());
  }

  @AfterEach
  void tearDown() {
    obContext.close();
    System.clearProperty(SignupApiKeys.PROPERTY);
  }

  private void call(String method, String path, String key, String json) throws Exception {
    when(request.getMethod()).thenReturn(method);
    when(request.getPathInfo()).thenReturn(path);
    when(request.getHeader(SignupApiKeys.HEADER)).thenReturn(key);
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    servlet.service(request, response);
  }

  private void call(String method, String path, String json) throws Exception {
    call(method, path, KEY, json);
  }

  private JSONObject json() throws Exception {
    return new JSONObject(body.toString());
  }

  private JSONObject error() throws Exception {
    return json().getJSONObject("error");
  }

  private static String validAccount() throws Exception {
    return new JSONObject()
        .put("name", "Ana García")
        .put("email", "ana@empresa.es")
        .put("companyName", "Empresa SL")
        .put("countryCode", "ES")
        .put("sendEmails", false)
        .toString();
  }

  @Test
  void aCallWithoutAKeyIsRefusedBeforeAnything() throws Exception {
    call("POST", "/accounts", null, validAccount());

    assertEquals(401, status);
    assertEquals("INVALID_API_KEY", error().getString("code"));
    verifyNoInteractions(registration, signups);
  }

  @Test
  void aCallWithAnUnknownKeyIsRefused() throws Exception {
    call("GET", "/config", "guessed", "");

    assertEquals(401, status);
    assertEquals("INVALID_API_KEY", error().getString("code"));
  }

  @Test
  void configPublishesTheOptionsAndIsNeverCached() throws Exception {
    call("GET", "/config", "");

    assertEquals(200, status);
    assertEquals("ES", json().getJSONArray("countries").getJSONObject(0).getString("code"));
    assertEquals(40, json().getJSONObject("limits").getInt("companyName"));
    assertFalse(json().has("password"));
    assertEquals("no-store", headers.get("Cache-Control"));
  }

  @Test
  void anUnknownPathIsNotFound() throws Exception {
    call("GET", "/nope", "");

    assertEquals(404, status);
    assertEquals("NOT_FOUND", error().getString("code"));
  }

  @Test
  void theWrongMethodIsRefused() throws Exception {
    call("GET", "/accounts", "");

    assertEquals(405, status);
    assertEquals("METHOD_NOT_ALLOWED", error().getString("code"));
  }

  @Test
  void aBodyThatIsNotJsonIsAnInvalidRequest() throws Exception {
    call("POST", "/accounts", "not json");

    assertEquals(400, status);
    assertEquals("INVALID_REQUEST", error().getString("code"));
  }

  @Test
  void aValidationErrorNamesTheField() throws Exception {
    call("POST", "/accounts", new JSONObject(validAccount()).put("taxId", "123").toString());

    assertEquals(400, status);
    assertEquals("INVALID_TAX_ID_FORMAT", error().getString("code"));
    assertEquals("taxId", error().getString("field"));
    verifyNoInteractions(signups);
  }

  @Test
  void aNewAddressIsCreatedUnderTheKeysChannelAndAnsweredAtOnce() throws Exception {
    when(registration.emailStatus("ana@empresa.es")).thenReturn(EmailStatus.AVAILABLE);
    when(signups.create(any())).thenReturn(ACCOUNT_ID);

    call("POST", "/accounts/", validAccount());

    assertEquals(202, status);
    assertEquals(ACCOUNT_ID, json().getString("accountId"));
    assertEquals("provisioning", json().getString("status"));
    verify(signups).create(argThat(account -> "web".equals(account.channel())
        && !account.sendEmails() && "ana@empresa.es".equals(account.email())
        && account.onboardingDraft().contains("\"clientName\":\"Empresa SL\"")));
  }

  @Test
  void aRegisteredAddressIsTold() throws Exception {
    when(registration.emailStatus("ana@empresa.es")).thenReturn(EmailStatus.REGISTERED);

    call("POST", "/accounts", validAccount());

    assertEquals(409, status);
    assertEquals("EMAIL_ALREADY_REGISTERED", error().getString("code"));
    assertEquals("email", error().getString("field"));
    verify(signups, never()).create(any());
  }

  @Test
  void anAddressWaitingForItsConfirmationIsTold() throws Exception {
    when(registration.emailStatus("ana@empresa.es")).thenReturn(EmailStatus.PENDING_VERIFICATION);

    call("POST", "/accounts", validAccount());

    assertEquals(409, status);
    assertEquals("EMAIL_PENDING_VERIFICATION", error().getString("code"));
  }

  @Test
  void aReservedCompanyNameIsRefused() throws Exception {
    when(registration.isReservedCompanyName("Empresa SL")).thenReturn(true);

    call("POST", "/accounts", validAccount());

    assertEquals(400, status);
    assertEquals("RESERVED_COMPANY_NAME", error().getString("code"));
  }

  @Test
  void aServerFailureIsAStableCodeWithoutDetail() throws Exception {
    when(registration.emailStatus(anyString())).thenThrow(new IllegalStateException("db down"));

    call("POST", "/accounts", validAccount());

    assertEquals(500, status);
    assertEquals("SIGNUP_SERVER_ERROR", error().getString("code"));
    assertFalse(body.toString().contains("db down"));
  }

  @Test
  void statusReportsTheSignupState() throws Exception {
    when(signups.status(ACCOUNT_ID)).thenReturn(new SignupAccountService.Status(ACCOUNT_ID,
        "ana@empresa.es", "failed", "Organization not found"));

    call("GET", "/accounts/" + ACCOUNT_ID.toLowerCase(), "");

    assertEquals(200, status);
    assertEquals("failed", json().getString("status"));
    assertEquals("Organization not found", json().getString("error"));
  }

  @Test
  void statusOfAnAccountTheApiDidNotCreateIsNotFound() throws Exception {
    call("GET", "/accounts/" + ACCOUNT_ID, "");

    assertEquals(404, status);
    assertEquals("ACCOUNT_NOT_FOUND", error().getString("code"));
  }

  @Test
  void aLoginLinkIsReturnedAndMailedOnRequest() throws Exception {
    when(signups.issueLoginLink(ACCOUNT_ID, true, "en_US")).thenReturn(new LoginLink(
        "https://app/onboarding?loginToken=t", new Date(0), true, null));

    call("POST", "/accounts/" + ACCOUNT_ID + "/login-link",
        "{\"sendEmail\":true,\"language\":\"en_US\"}");

    assertEquals(200, status);
    assertEquals("https://app/onboarding?loginToken=t", json().getString("link"));
    assertEquals("1970-01-01T00:00:00Z", json().getString("expiresAt"));
    assertTrue(json().getBoolean("emailSent"));
  }

  @Test
  void aLoginLinkNeedsNoBodyAndMailsNothingByDefault() throws Exception {
    when(signups.issueLoginLink(eq(ACCOUNT_ID), eq(false), isNull())).thenReturn(new LoginLink(
        "l", new Date(0), false, null));

    call("POST", "/accounts/" + ACCOUNT_ID + "/login-link", "");

    assertEquals(200, status);
    assertFalse(json().getBoolean("emailSent"));
  }

  @Test
  void aLoginLinkBeforeTheEnvironmentIsReadyIsAConflict() throws Exception {
    when(signups.issueLoginLink(eq(ACCOUNT_ID), eq(false), isNull()))
        .thenReturn(new LoginLink(null, null, false, LinkRefusal.NOT_READY));

    call("POST", "/accounts/" + ACCOUNT_ID + "/login-link", "");

    assertEquals(409, status);
    assertEquals("ENVIRONMENT_NOT_READY", error().getString("code"));
  }

  @Test
  void aLoginLinkWithoutAPublicAppUrlIsUnavailable() throws Exception {
    when(signups.issueLoginLink(eq(ACCOUNT_ID), eq(false), isNull()))
        .thenReturn(new LoginLink(null, null, false, LinkRefusal.NOT_CONFIGURED));

    call("POST", "/accounts/" + ACCOUNT_ID + "/login-link", "");

    assertEquals(503, status);
    assertEquals("LOGIN_LINK_UNAVAILABLE", error().getString("code"));
  }

  @Test
  void retryRunsAFailedCreationAgain() throws Exception {
    when(signups.retry(ACCOUNT_ID)).thenReturn(RetryOutcome.STARTED);

    call("POST", "/accounts/" + ACCOUNT_ID + "/retry", "");

    assertEquals(202, status);
    assertEquals("provisioning", json().getString("status"));
  }

  @Test
  void retryOfACreationThatDidNotFailIsRefused() throws Exception {
    when(signups.retry(ACCOUNT_ID)).thenReturn(RetryOutcome.NOT_FAILED);

    call("POST", "/accounts/" + ACCOUNT_ID + "/retry", "");

    assertEquals(409, status);
    assertEquals("RETRY_NOT_ALLOWED", error().getString("code"));
  }

  @Test
  void anAccountActionNeedsAPost() throws Exception {
    call("GET", "/accounts/" + ACCOUNT_ID + "/retry", "");

    assertEquals(405, status);
  }
}
