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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import java.time.LocalDate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.onboarding.CalendarReviewService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.util.OwnerSupport;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5577 — who may confirm or regenerate a tenant's accounting calendar: the owner of THAT
 * tenant. Regenerate deletes the calendar, so owning another environment of the same account
 * (what {@code requireBillingOwner} accepts) must not be enough.
 *
 * @covers com.etendoerp.go.rest.EtendoGoJwtServlet
 */
class EtendoGoJwtServletCalendarReviewTest {

  private OBDal dal;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<OwnerSupport> owners;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    owners = mockStatic(OwnerSupport.class);
  }

  @AfterEach
  void tearDown() {
    owners.close();
    obDal.close();
  }

  @Test
  void theOwnerOfThisTenantMayAct() {
    user("OWNER", "TENANT", true);
    assertEquals("OWNER", EtendoGoJwtServlet.ownerOfTenant("OWNER", "TENANT"));
  }

  @Test
  void aMemberOfThisTenantMayNot() {
    user("MEMBER", "TENANT", false);
    assertNull(EtendoGoJwtServlet.ownerOfTenant("MEMBER", "TENANT"));
  }

  @Test
  void theOwnerOfAnotherTenantOfTheAccountMayNot() {
    user("OWNER-ELSEWHERE", "OTHER-TENANT", true);
    assertNull(EtendoGoJwtServlet.ownerOfTenant("OWNER-ELSEWHERE", "TENANT"));
  }

  @Test
  void noUserMeansNoOwner() {
    assertNull(EtendoGoJwtServlet.ownerOfTenant(null, "TENANT"));
    assertNull(EtendoGoJwtServlet.ownerOfTenant("UNKNOWN", "TENANT"));
  }

  // ===================== POST confirm / regenerate: status mapping =====================

  private static final String CLIENT = "TENANT";
  private static final String EMAIL = "owner@example.test";
  private static final String TOKEN = "bearer.jwt";

  @Test
  void confirmByANonOwnerIs403AndNothingIsConfirmed() throws Exception {
    user("MEMBER", CLIENT, false);
    CalendarReviewService service = mock(CalendarReviewService.class);

    Result r = post("/onboarding/calendar-review/confirm", "{}", "MEMBER", service);

    assertEquals(403, r.status);
    verifyNoInteractions(service);
  }

  @Test
  void regenerateByANonOwnerIs403AndNothingIsDeleted() throws Exception {
    user("MEMBER", CLIENT, false);
    CalendarReviewService service = mock(CalendarReviewService.class);

    Result r = post("/onboarding/calendar-review/regenerate", "{\"fiscalYearStart\":\"JULY\"}",
        "MEMBER", service);

    assertEquals(403, r.status);
    verifyNoInteractions(service);
  }

  @Test
  void regenerateRefusedBecauseTheCalendarIsInUseIs409() throws Exception {
    user("OWNER", CLIENT, true);
    CalendarReviewService service = mock(CalendarReviewService.class);
    CalendarReviewService.Refusal inUse = refusal(409, "The calendar is already in use (x.y)");
    when(service.regenerate(eq(CLIENT), eq("JULY"), eq("OWNER"), any(LocalDate.class)))
        .thenReturn(inUse);

    Result r = post("/onboarding/calendar-review/regenerate", "{\"fiscalYearStart\":\"JULY\"}",
        "OWNER", service);

    assertEquals(409, r.status);
    assertTrue(r.body.contains("already in use"), r.body);
    verify(service, never()).confirm(anyString(), anyString());
  }

  @Test
  void confirmByTheOwnerIs200() throws Exception {
    user("OWNER", CLIENT, true);
    CalendarReviewService service = mock(CalendarReviewService.class);
    when(service.state(eq(CLIENT), any(LocalDate.class))).thenReturn(new JSONObject());

    Result r = post("/onboarding/calendar-review/confirm", "{}", "OWNER", service);

    assertEquals(200, r.status);
    verify(service).confirm(CLIENT, "OWNER");
  }

  private static CalendarReviewService.Refusal refusal(int status, String message)
      throws Exception {
    java.lang.reflect.Constructor<CalendarReviewService.Refusal> c =
        CalendarReviewService.Refusal.class.getDeclaredConstructor(int.class, String.class);
    c.setAccessible(true);
    return c.newInstance(status, message);
  }

  private record Result(int status, String body) {
  }

  /** POST under a legacy bearer: the owner check reads the {@code user} claim. */
  private Result post(String path, String json, String userId, CalendarReviewService service)
      throws Exception {
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
    servlet.calendarReviewService = service;
    servlet.tenantEnvironmentLifecycleService = mock(TenantEnvironmentLifecycleService.class);
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn(EMAIL);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getPathInfo()).thenReturn(path);
    when(req.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);
    when(req.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    int[] status = new int[1];
    doAnswer(inv -> {
      status[0] = inv.getArgument(0);
      return null;
    }).when(resp).setStatus(org.mockito.ArgumentMatchers.anyInt());
    when(resp.getWriter()).thenReturn(new PrintWriter(body));
    DecodedJWT jwt = mock(DecodedJWT.class);
    Claim clientClaim = claim(CLIENT);
    Claim orgClaim = claim("ORG");
    Claim userClaim = claim(userId);
    when(jwt.getClaim("client")).thenReturn(clientClaim);
    when(jwt.getClaim("organization")).thenReturn(orgClaim);
    when(jwt.getClaim("user")).thenReturn(userClaim);
    try (MockedStatic<org.openbravo.dal.core.OBContext> ctx =
            mockStatic(org.openbravo.dal.core.OBContext.class);
        MockedStatic<EtendoGoJwtDalHelper> helper = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<EtendoGoDalHelper> dalHelper = mockStatic(EtendoGoDalHelper.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      helper.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken(TOKEN))
          .thenReturn(account);
      helper.when(() -> EtendoGoJwtDalHelper.clientBelongsToAccountEmail(CLIENT, EMAIL))
          .thenReturn(true);
      sws.when(() -> SecureWebServicesUtils.decodeToken(TOKEN)).thenReturn(jwt);
      servlet.doPost(req, resp);
    }
    return new Result(status[0], body.toString());
  }

  private static Claim claim(String value) {
    Claim claim = mock(Claim.class);
    when(claim.asString()).thenReturn(value);
    return claim;
  }

  private void user(String userId, String clientId, boolean owner) {
    User user = mock(User.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(user.getClient()).thenReturn(client);
    when(dal.get(User.class, userId)).thenReturn(user);
    owners.when(() -> OwnerSupport.isOwner(userId)).thenReturn(owner);
  }
}
