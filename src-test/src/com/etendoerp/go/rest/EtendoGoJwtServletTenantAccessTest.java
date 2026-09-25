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

package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.onboarding.OnboardingCompanyDataService;
import com.etendoerp.go.payment.DemoDataTransferService;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.Decision;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionSecurity;
import com.etendoerp.go.session.GoSessionService;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5455, decision 2 (TL, 2026-09-23) — a commercially blocked environment's data is fully
 * inaccessible (not deleted). The account surface stays reachable (billing, upgrade, /me: an owner
 * must be able to pay), but the {@code /sws/go} endpoints that act on the SESSION's tenant —
 * onboarding company data and the demo-to-productive transfer status/retry — are tenant data, so
 * they answer the same 402 NEO does.
 *
 * <p>All of them resolve the tenant through {@code resolveTenantSession}, so the check lives there
 * once and applies to every scheme alike. The transfer case is decided on the PRODUCTIVE tenant of
 * the session (the one being paid for), never on the demo it copies from, so buying PRO after the
 * demo expired still brings its data along (ETP-5396).
 */
class EtendoGoJwtServletTenantAccessTest {

  private static final String COMPANY_DATA_PATH = "/onboarding/company-data";
  private static final String TRANSFER_PATH = "/demo-data-transfer";
  private static final String SESSION_TOKEN = "session-cookie-token";
  private static final String BEARER_JWT = "environment.jwt.token";
  private static final String ACCOUNT_ID = "account-1";
  private static final String ACCOUNT_EMAIL = "owner@example.test";
  private static final String CLIENT_ID = "client-1";
  private static final String ORG_ID = "org-1";

  @Test
  void companyDataOfABlockedEnvironmentIs402UnderTheCookie() throws Exception {
    Fixture fixture = new Fixture(Decision.SUBSCRIPTION_REQUIRED);

    ResponseCapture resp = fixture.cookieGet(COMPANY_DATA_PATH);

    assertEquals(402, resp.status);
    assertTrue(resp.body().contains("SUBSCRIPTION_REQUIRED"), resp.body());
    verifyNoInteractions(fixture.companyDataService);
    verify(fixture.lifecycle).evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class));
  }

  /** Same client, same decision, other scheme: same answer. */
  @Test
  void companyDataOfABlockedEnvironmentIs402UnderTheBearerToo() throws Exception {
    Fixture fixture = new Fixture(Decision.DEMO_TRIAL_EXPIRED);

    ResponseCapture resp = fixture.bearerGet(COMPANY_DATA_PATH);

    assertEquals(402, resp.status);
    assertTrue(resp.body().contains("DEMO_TRIAL_EXPIRED"), resp.body());
    verifyNoInteractions(fixture.companyDataService);
  }

  @Test
  void companyDataOfAnAllowedEnvironmentIsServed() throws Exception {
    Fixture fixture = new Fixture(Decision.ALLOWED);

    assertEquals(200, fixture.cookieGet(COMPANY_DATA_PATH).status);
    verify(fixture.companyDataService).read(CLIENT_ID, ORG_ID);
  }

  /** A tenant that predates lifecycle metadata is the legacy transition, not a refusal. */
  @Test
  void companyDataOfALegacyTenantIsServed() throws Exception {
    Fixture fixture = new Fixture(null);

    assertEquals(200, fixture.cookieGet(COMPANY_DATA_PATH).status);
  }

  @Test
  void theDemoTransferStatusOfABlockedProductiveIs402() throws Exception {
    Fixture fixture = new Fixture(Decision.SUBSCRIPTION_REQUIRED);

    ResponseCapture resp = fixture.cookieGet(TRANSFER_PATH);

    assertEquals(402, resp.status);
    verifyNoInteractions(fixture.transferService);
  }

  @Test
  void theDemoTransferStatusOfAnAllowedProductiveIsServed() throws Exception {
    Fixture fixture = new Fixture(Decision.ALLOWED);
    // Unrelated develop change picked up by the merge: status() now takes the demo-tenant-id
    // supplier as a second, lazily-evaluated argument; the mock never invokes it.
    when(fixture.transferService.status(eq(CLIENT_ID), any())).thenReturn(new JSONObject());

    ResponseCapture resp = fixture.cookieGet(TRANSFER_PATH);

    assertEquals(200, resp.status);
    verify(fixture.transferService).status(eq(CLIENT_ID), any());
  }

  // ===================== fixture =====================

  private static final class Fixture {
    final GoSessionService goSessionService = mock(GoSessionService.class);
    final EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(
        mock(TransactionalAuthEmailSender.class), mock(EtendoGoSsoProviderRegistry.class),
        goSessionService);
    final OnboardingCompanyDataService companyDataService =
        mock(OnboardingCompanyDataService.class);
    final DemoDataTransferService transferService = mock(DemoDataTransferService.class);
    final TenantEnvironmentLifecycleService lifecycle =
        mock(TenantEnvironmentLifecycleService.class);
    final Account account = mock(Account.class);

    Fixture(Decision decision) {
      servlet.onboardingCompanyDataService = companyDataService;
      servlet.demoDataTransferService = transferService;
      servlet.tenantEnvironmentLifecycleService = lifecycle;
      when(lifecycle.evaluateAccess(eq(CLIENT_ID), eq(true), any(Instant.class)))
          .thenReturn(decision);
      when(account.getId()).thenReturn(ACCOUNT_ID);
      when(account.getEmail()).thenReturn(ACCOUNT_EMAIL);
      GoSessionRecord session = new GoSessionRecord();
      session.setAccountId(ACCOUNT_ID);
      session.setCtxClientId(CLIENT_ID);
      session.setCtxOrgId(ORG_ID);
      when(goSessionService.resolve(SESSION_TOKEN)).thenReturn(session);
    }

    ResponseCapture cookieGet(String path) throws Exception {
      HttpServletRequest req = request(path);
      when(req.getCookies()).thenReturn(
          new Cookie[] { new Cookie(GoSessionSecurity.COOKIE_NAME, SESSION_TOKEN) });
      return run(req, null);
    }

    ResponseCapture bearerGet(String path) throws Exception {
      HttpServletRequest req = request(path);
      when(req.getHeader("Authorization")).thenReturn("Bearer " + BEARER_JWT);
      return run(req, jwt());
    }

    private ResponseCapture run(HttpServletRequest req, DecodedJWT jwt) throws Exception {
      ResponseCapture resp = mockResponse();
      try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
          MockedStatic<EtendoGoJwtDalHelper> dal = mockStatic(EtendoGoJwtDalHelper.class);
          MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
        dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID)).thenReturn(account);
        dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(BEARER_JWT))
            .thenReturn(account);
        dal.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT_ID, ACCOUNT_EMAIL))
            .thenReturn(true);
        sws.when(() -> SecureWebServicesUtils.decodeToken(BEARER_JWT)).thenReturn(jwt);
        servlet.doGet(req, resp.response);
      }
      return resp;
    }
  }

  private static HttpServletRequest request(String path) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getPathInfo()).thenReturn(path);
    return req;
  }

  private static DecodedJWT jwt() {
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim client = mock(Claim.class);
    when(client.asString()).thenReturn(CLIENT_ID);
    Claim org = mock(Claim.class);
    when(org.asString()).thenReturn(ORG_ID);
    when(jwt.getClaim("client")).thenReturn(client);
    when(jwt.getClaim("organization")).thenReturn(org);
    return jwt;
  }

  private static ResponseCapture mockResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    ResponseCapture capture = new ResponseCapture(response, body);
    doAnswer(inv -> {
      capture.status = inv.getArgument(0);
      return null;
    }).when(response).setStatus(anyInt());
    when(response.getWriter()).thenReturn(new PrintWriter(body));
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
