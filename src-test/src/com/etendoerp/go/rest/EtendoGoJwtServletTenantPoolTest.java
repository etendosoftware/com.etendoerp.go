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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.InitialClientSetup;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingDatasetImportService;
import com.etendoerp.go.onboarding.OnboardingOrgInfoService;
import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.onboarding.OnboardingWarehouseAddressService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-5389 — the servlet chooses between a claimed pooled tenant and the classic path, and the
 * pooled branch runs only the per-request steps.
 */
public class EtendoGoJwtServletTenantPoolTest {

  private static final String BODY = "{\"clientName\":\"Acme\",\"currency\":\"EUR\","
      + "\"language\":\"es_ES\",\"countryCode\":\"ES\",\"address\":\"Calle Mayor 1\"}";

  @Test
  public void aClaimedPooledTenantSkipsClientCreationAndTheDatasetChain() throws Exception {
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(mock(TransactionalAuthEmailSender.class));
    StubClaimService claim = new StubClaimService("POOLED-CLIENT");
    servlet.pooledTenantClaimService = claim;
    OnboardingDatasetImportService dataset = mock(OnboardingDatasetImportService.class);
    OnboardingOrgInfoService orgInfo = mock(OnboardingOrgInfoService.class);
    OnboardingWarehouseAddressService warehouse = mock(OnboardingWarehouseAddressService.class);
    OnboardingCostingScheduleService costing = mock(OnboardingCostingScheduleService.class);
    TenantEnvironmentLifecycleService lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.markDemoReady(eq("POOLED-CLIENT"), any(Instant.class))).thenReturn(true);
    servlet.onboardingDatasetImportService = dataset;
    servlet.onboardingOrgInfoService = orgInfo;
    servlet.onboardingWarehouseAddressService = warehouse;
    servlet.onboardingCostingScheduleService = costing;
    servlet.tenantEnvironmentLifecycleService = lifecycle;
    StringWriter body = new StringWriter();
    HttpServletResponse response = response(body);

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
         MockedStatic<OBDal> dal = mockStatic(OBDal.class);
         MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
         MockedStatic<EtendoGoJwtDalHelper> dalHelper = mockStatic(EtendoGoJwtDalHelper.class);
         var setup = mockConstruction(InitialClientSetup.class)) {
      dal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
      stubAuthenticationAndCurrency(dalHelper);
      UserRoles adminRole = adminRole();
      dalHelper.when(() -> EtendoGoJwtDalHelper.findClientAdminUserRole("POOLED-CLIENT"))
          .thenReturn(adminRole);
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG-1");
      dalHelper.when(() -> EtendoGoJwtDalHelper.findFirstOrganization("POOLED-CLIENT"))
          .thenReturn(org);
      support.when(() -> EtendoGoJwtSupport.findStarOrgId("POOLED-CLIENT")).thenReturn("STAR");

      servlet.doPost(request(), response);

      assertTrue(setup.constructed().isEmpty(), "InitialClientSetup must not run");
    }

    String ndjson = body.toString();
    assertTrue(ndjson.contains("\"success\":true"), ndjson);
    assertNotNull(claim.request);
    assertEquals("Acme", claim.request.clientName());
    assertEquals("es_ES", claim.request.language());
    assertFalse(ndjson.contains("\"step\":\"dataset\""));
    assertFalse(ndjson.contains("\"step\":\"organization\""));
    assertTrue(ndjson.contains("\"step\":\"orgInfo\""));
    assertTrue(ndjson.contains("\"step\":\"warehouseAddress\""));
    verify(dataset, never()).importDataset(anyString(), anyString());
    verify(orgInfo).ensureOrgInfo("POOLED-CLIENT", "ORG-1", "ADMIN-USER", "ADMIN-ROLE", "ES",
        "Calle Mayor 1", "");
    verify(warehouse).alignDefaultWarehouseAddress("POOLED-CLIENT", "ORG-1", "ADMIN-USER",
        "ADMIN-ROLE");
    verify(lifecycle).markDemoReady(eq("POOLED-CLIENT"), any(Instant.class));
    verify(costing).activateSchedule("POOLED-CLIENT");
  }

  @Test
  public void noClaimMeansTheClassicPathRunsUnchanged() throws Exception {
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet(mock(TransactionalAuthEmailSender.class));
    StubClaimService claim = new StubClaimService(null);
    servlet.pooledTenantClaimService = claim;
    StringWriter body = new StringWriter();
    OBError failure = new OBError();
    failure.setType("Error");
    failure.setMessage("@CreateClientFailed@");

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
         MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
         MockedStatic<EtendoGoJwtDalHelper> dalHelper = mockStatic(EtendoGoJwtDalHelper.class);
         var setup = mockConstruction(InitialClientSetup.class, (mocked, context) ->
             when(mocked.createClient(any(VariablesSecureApp.class), anyString(), anyString(),
                 anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(),
                 isNull(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                 .thenReturn(failure))) {
      stubAuthenticationAndCurrency(dalHelper);
      support.when(() -> EtendoGoJwtSupport.buildClientUsername("user@test.com", "Acme"))
          .thenReturn("user@test.com");

      servlet.doPost(request(), response(body));

      assertEquals(1, setup.constructed().size(), "the classic path creates the client");
    }

    assertNotNull(claim.request, "the pool is always asked first");
    assertTrue(body.toString().contains("CLIENT_CREATION_FAILED"));
  }

  private static void stubAuthenticationAndCurrency(MockedStatic<EtendoGoJwtDalHelper> dalHelper) {
    Account account = mock(Account.class);
    when(account.getEmail()).thenReturn("user@test.com");
    dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountByBearerToken("valid-token"))
        .thenReturn(account);
    dalHelper.when(() -> EtendoGoJwtDalHelper.findActiveAccountByToken("valid-token"))
        .thenReturn(account);
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn("EUR-ID");
    dalHelper.when(() -> EtendoGoJwtDalHelper.findCurrencyByIsoCode("EUR")).thenReturn(currency);
  }

  private static UserRoles adminRole() {
    UserRoles userRole = mock(UserRoles.class);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("ADMIN-ROLE");
    User user = mock(User.class);
    when(user.getId()).thenReturn("ADMIN-USER");
    when(userRole.getRole()).thenReturn(role);
    when(userRole.getUserContact()).thenReturn(user);
    return userRole;
  }

  private static HttpServletRequest request() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn("/onboarding");
    when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
    when(request.getContentType()).thenReturn("application/json");
    when(request.getReader()).thenReturn(new BufferedReader(new StringReader(BODY)));
    return request;
  }

  private static HttpServletResponse response(StringWriter body) throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    doAnswer(inv -> null).when(response).setStatus(anyInt());
    return response;
  }

  private static final class StubClaimService extends PooledTenantClaimService {
    private final String clientId;
    ClaimRequest request;

    StubClaimService(String clientId) {
      this.clientId = clientId;
    }

    @Override
    public String claim(OnboardingProgressSink sink, ClaimRequest request) {
      this.request = request;
      return clientId;
    }
  }
}
