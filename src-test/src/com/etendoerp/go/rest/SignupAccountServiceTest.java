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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * The signup API's account lifecycle (ETP-5847): creation, the background provisioning and its
 * outcome, retry, and the one-time login link. The worker runs on the test thread so every static
 * mock reaches it.
 *
 * @covers com.etendoerp.go.rest.SignupAccountService
 */
class SignupAccountServiceTest {

  private static final String ACCOUNT_ID = "0123456789ABCDEF0123456789ABCDEF";
  private static final String CLIENT_ID = "FEDCBA9876543210FEDCBA9876543210";
  private static final String LINK = "https://app.example.test/onboarding?loginToken=t";
  private static final String DRAFT =
      "{\"step\":2,\"form\":{\"clientName\":\"Empresa SL\",\"language\":\"es_ES\"}}";

  private final TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
  private final EtendoGoJwtServlet provisioner = mock(EtendoGoJwtServlet.class);
  private final List<Runnable> queued = new ArrayList<>();
  private final SignupAccountService service =
      new SignupAccountService(emailSender, queued::add, () -> provisioner);
  private final Account account = mock(Account.class);

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<SessionHandler> sessionHandler;
  private MockedStatic<SignupAccountDalHelper> signupDal;
  private MockedStatic<EtendoGoJwtDalHelper> accountDal;
  private MockedStatic<EtendoGoAuthLinkBuilder> links;

  @BeforeEach
  void mockStatics() {
    obContext = mockStatic(OBContext.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
    sessionHandler = mockStatic(SessionHandler.class);
    signupDal = mockStatic(SignupAccountDalHelper.class);
    accountDal = mockStatic(EtendoGoJwtDalHelper.class);
    links = mockStatic(EtendoGoAuthLinkBuilder.class);
    links.when(() -> EtendoGoAuthLinkBuilder.loginLink(anyString())).thenReturn(LINK);
    when(account.getId()).thenReturn(ACCOUNT_ID);
    when(account.getEmail()).thenReturn("ana@empresa.es");
    when(account.getName()).thenReturn("Ana");
    when(account.getOnboardingDraft()).thenReturn(DRAFT);
  }

  @AfterEach
  void closeStatics() {
    links.close();
    accountDal.close();
    signupDal.close();
    sessionHandler.close();
    obDal.close();
    obContext.close();
  }

  private void accountIs(String status) {
    signupDal.when(() -> SignupAccountDalHelper.findSignupAccount(ACCOUNT_ID)).thenReturn(account);
    signupDal.when(() -> SignupAccountDalHelper.status(account)).thenReturn(status);
  }

  @Test
  void createStoresTheAccountAndQueuesItsEnvironment() {
    signupDal.when(() -> SignupAccountDalHelper.createSignupAccount(eq("ana@empresa.es"),
        eq("Ana"), eq(DRAFT), any())).thenReturn(account);

    String id = service.create(new SignupAccountService.NewAccount("ana@empresa.es", "Ana", DRAFT,
        "crm", false, "{\"utm_source\":\"x\"}", true));

    assertEquals(ACCOUNT_ID, id);
    assertEquals(1, queued.size());
    signupDal.verify(() -> SignupAccountDalHelper.createSignupAccount(eq("ana@empresa.es"),
        eq("Ana"), eq(DRAFT), argThat(data -> "crm".equals(data.channel()) && !data.sendEmails()
            && data.termsAcceptedAt() != null && "{\"utm_source\":\"x\"}".equals(data.source()))));
  }

  @Test
  void statusOfAnAccountTheApiDidNotCreateIsNull() {
    assertNull(service.status(ACCOUNT_ID));
  }

  @Test
  void statusReportsStateAndError() {
    accountIs("failed");
    signupDal.when(() -> SignupAccountDalHelper.error(account)).thenReturn("boom");

    SignupAccountService.Status status = service.status(ACCOUNT_ID);

    assertEquals("failed", status.status());
    assertEquals("boom", status.error());
    assertEquals("ana@empresa.es", status.email());
  }

  @Test
  void retryOnlyRunsAFailedCreationAgain() {
    assertEquals(SignupAccountService.RetryOutcome.NOT_FOUND, service.retry(ACCOUNT_ID));

    accountIs("provisioning");
    assertEquals(SignupAccountService.RetryOutcome.NOT_FAILED, service.retry(ACCOUNT_ID));
    assertTrue(queued.isEmpty());

    accountIs("failed");
    assertEquals(SignupAccountService.RetryOutcome.STARTED, service.retry(ACCOUNT_ID));
    signupDal.verify(() -> SignupAccountDalHelper.markProvisioning(account));
    assertEquals(1, queued.size());
  }

  @Test
  void noLoginLinkBeforeTheEnvironmentIsReady() {
    accountIs("provisioning");

    SignupAccountService.LoginLink link = service.issueLoginLink(ACCOUNT_ID, true, null);

    assertEquals(SignupAccountService.LinkRefusal.NOT_READY, link.refusal());
    verifyNoInteractions(emailSender);
  }

  @Test
  void noLoginLinkWithoutAPublicAppUrl() {
    accountIs("ready");
    links.when(() -> EtendoGoAuthLinkBuilder.loginLink(anyString())).thenReturn(null);

    assertEquals(SignupAccountService.LinkRefusal.NOT_CONFIGURED,
        service.issueLoginLink(ACCOUNT_ID, false, null).refusal());
    signupDal.verify(() -> SignupAccountDalHelper.storeLoginLink(any(), anyString(), any()),
        never());
  }

  @Test
  void aLoginLinkStoresOnlyItsHashForSevenDaysAndIsMailedOnRequest() {
    accountIs("ready");
    signupDal.when(() -> SignupAccountDalHelper.environment(account)).thenReturn(CLIENT_ID);
    accountDal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
        .thenReturn(account);
    when(emailSender.sendEnvironmentReady(account, CLIENT_ID, "en_US", LINK)).thenReturn(true);

    SignupAccountService.LoginLink link = service.issueLoginLink(ACCOUNT_ID, true, " en_US ");

    assertEquals(LINK, link.link());
    assertTrue(link.emailSent());
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Date> expires = ArgumentCaptor.forClass(Date.class);
    signupDal.verify(() -> SignupAccountDalHelper.storeLoginLink(eq(account), hash.capture(),
        expires.capture()));
    assertFalse(LINK.contains(hash.getValue()));
    Duration validity = Duration.between(Instant.now(), expires.getValue().toInstant());
    assertTrue(validity.toHours() >= 167 && validity.toHours() <= 168);
  }

  @Test
  void aLoginLinkWithoutMailSendsNothing() {
    accountIs("ready");

    assertFalse(service.issueLoginLink(ACCOUNT_ID, false, null).emailSent());
    verifyNoInteractions(emailSender);
  }

  @Test
  void aCreatedEnvironmentMarksTheAccountReadyAndMailsTheLink() {
    accountIs("ready");
    signupDal.when(() -> SignupAccountDalHelper.sendsEmails(account)).thenReturn(true);
    signupDal.when(() -> SignupAccountDalHelper.environment(account)).thenReturn(CLIENT_ID);
    accountDal.when(() -> EtendoGoJwtDalHelper.findActiveAccountById(ACCOUNT_ID))
        .thenReturn(account);
    when(provisioner.provisionSignupEnvironment(eq(ACCOUNT_ID), eq("ana@empresa.es"), eq("Ana"),
        any(JSONObject.class), any())).thenReturn(
            new EtendoGoJwtServlet.SignupProvisioningResult(CLIENT_ID, null));

    service.provisionInBackground(ACCOUNT_ID);

    signupDal.verify(() -> SignupAccountDalHelper.markReady(account, CLIENT_ID));
    verify(emailSender).sendEnvironmentReady(account, CLIENT_ID, "es_ES", LINK);
  }

  @Test
  void aCreatedEnvironmentWithMailsOffMailsNothing() {
    accountIs("ready");
    when(provisioner.provisionSignupEnvironment(anyString(), anyString(), anyString(),
        any(JSONObject.class), any())).thenReturn(
            new EtendoGoJwtServlet.SignupProvisioningResult(CLIENT_ID, null));

    service.provisionInBackground(ACCOUNT_ID);

    signupDal.verify(() -> SignupAccountDalHelper.markReady(account, CLIENT_ID));
    verifyNoInteractions(emailSender);
  }

  @Test
  void aFailedEnvironmentKeepsTheReason() {
    accountIs("provisioning");
    when(provisioner.provisionSignupEnvironment(anyString(), anyString(), anyString(),
        any(JSONObject.class), any())).thenReturn(
            new EtendoGoJwtServlet.SignupProvisioningResult(null, "Organization not found"));

    service.provisionInBackground(ACCOUNT_ID);

    signupDal.verify(() -> SignupAccountDalHelper.markFailed(account, "Organization not found"));
    verifyNoInteractions(emailSender);
  }

  @Test
  void anExceptionInTheWorkerEndsAsAFailedAccount() {
    accountIs("provisioning");
    when(provisioner.provisionSignupEnvironment(anyString(), anyString(), anyString(),
        any(JSONObject.class), any())).thenThrow(new IllegalStateException("db down"));

    service.provisionInBackground(ACCOUNT_ID);

    signupDal.verify(() -> SignupAccountDalHelper.markFailed(account, "db down"));
  }

  @Test
  void anAccountWithoutCompanyDataFailsWithoutProvisioning() {
    accountIs("provisioning");
    when(account.getOnboardingDraft()).thenReturn(null);

    service.provisionInBackground(ACCOUNT_ID);

    verifyNoInteractions(provisioner);
    signupDal.verify(() -> SignupAccountDalHelper.markFailed(eq(account), anyString()));
  }
}
