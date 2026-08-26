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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Tests server-side auth transactional email command construction.
 */
public class TransactionalAuthEmailSenderTest {

  private static final String TEST_APP_BASE_URL = "https://app.example.test";

  @After
  public void clearProperties() {
    System.clearProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY);
  }

  @Test
  public void sendNewAccountBuildsServerSideWelcomeCommand() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendNewAccount(account),
        "new-account");

    assertBaseCommand(command);
    assertEquals("https://app.example.test/onboarding",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
    assertFalse(command.has(EmailContractCommandSupport.FIELD_RECORD_ID));
  }

  @Test
  public void sendNewAccountIncludesSelectedLanguage() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendNewAccount(account, "es_ES"),
        "new-account");

    assertBaseCommand(command);
    assertEquals("es_ES", command.getString(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void sendNewAccountOmitsBlankLanguage() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendNewAccount(account, "  "),
        "new-account");

    assertBaseCommand(command);
    assertFalse(command.has(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void sendNewAccountOmitsNullLanguage() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendNewAccount(account, null),
        "new-account");

    assertBaseCommand(command);
    assertFalse(command.has(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void sendEnvironmentReadyUsesServerLinkAndClientIdIdempotency() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "https://app.example.test");
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendEnvironmentReady(account, "client-1"),
        "environment-ready");

    assertBaseCommand(command, "client-1");
    assertEquals("https://app.example.test/dashboard",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
    assertEquals("client-1", command.getString(EmailContractCommandSupport.FIELD_RECORD_ID));
  }

  @Test
  public void sendEnvironmentReadyIncludesSelectedLanguage() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "https://app.example.test");
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendEnvironmentReady(account, "client-1", "es_ES"),
        "environment-ready");

    assertBaseCommand(command, "client-1");
    assertEquals("es_ES", command.getString(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void resetPasswordLinkBuilderEncodesToken() {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "https://app.example.test");

    assertEquals("https://app.example.test/onboarding?resetToken=abc+123%2B%2F%3D",
        EtendoGoAuthLinkBuilder.resetPasswordLink("abc 123+/="));
  }

  @Test
  public void sendPasswordResetUsesProvidedLinkAndHashIdempotency() throws Exception {
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendPasswordReset(account, "reset-hash",
            "https://app.example.test/onboarding?resetToken=abc+123%2B%2F%3D"),
        "reset-password");

    assertBaseCommand(command);
    assertEquals("https://app.example.test/onboarding?resetToken=abc+123%2B%2F%3D",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
    assertEquals("reset-hash", command.getString(EmailContractCommandSupport.FIELD_RECORD_ID));
    assertFalse(command.has(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void sendPasswordResetUsesProvidedLink() throws Exception {
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendPasswordReset(account, "reset-hash",
            "https://go.experimental.etendo.cloud/onboarding?resetToken=reset-token"),
        "reset-password");

    assertBaseCommand(command);
    assertEquals("https://go.experimental.etendo.cloud/onboarding?resetToken=reset-token",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
    assertEquals("reset-hash", command.getString(EmailContractCommandSupport.FIELD_RECORD_ID));
  }

  @Test
  public void sendPasswordChangedBuildsNoticeWithoutProviderPayloadFields() throws Exception {
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendPasswordChanged(account), "password-changed");

    assertBaseCommand(command);
    assertFalse(command.has(EmailContractCommandSupport.FIELD_LINK));
    assertFalse(command.has("to"));
    assertFalse(command.has("template"));
    assertFalse(command.has("data"));
    assertFalse(command.getString(EmailContractCommandSupport.FIELD_RECORD_ID).isEmpty());
    assertFalse(command.getString(EmailContractCommandSupport.FIELD_DATE).isEmpty());
  }

  @Test
  public void sendPasswordChangedIncludesSelectedLanguage() throws Exception {
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendPasswordChanged(account, "es_ES"), "password-changed");

    assertBaseCommand(command);
    assertEquals("es_ES", command.getString(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void sendBestEffortRollsBackAndRestoresContextWhenProviderFails() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "https://app.example.test");
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    when(emailService.send(eq("new-account"), any(JSONObject.class)))
        .thenThrow(new RuntimeException("provider unavailable"));
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    OBContext previousContext = mock(OBContext.class);

    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<EtendoGoDalHelper> dalHelperMock = mockStatic(EtendoGoDalHelper.class)) {
      contextMock.when(OBContext::getOBContext).thenReturn(previousContext);
      sender.sendNewAccount(account("account-1"));

      dalHelperMock.verify(() -> EtendoGoDalHelper.rollbackDalChanges(
          eq("transactional auth email"), any(RuntimeException.class), any()));
      contextMock.verify(OBContext::restorePreviousMode);
      contextMock.verify(() -> OBContext.setOBContext(previousContext));
    }
  }

  @Test
  public void sendNewAccountCarriesTheVerificationLinkWhenOneIsAvailable() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendNewAccount(account, "es_ES",
            "https://app.example.test/onboarding?verifyToken=abc"),
        "new-account");

    assertBaseCommand(command);
    // ETP-4798: the welcome mail's single call to action becomes the confirmation link.
    assertEquals("https://app.example.test/onboarding?verifyToken=abc",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
  }

  @Test
  public void sendNewAccountFallsBackToThePlainOnboardingLinkWithoutAVerificationLink()
      throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendNewAccount(account, null, "  "),
        "new-account");

    assertBaseCommand(command);
    assertEquals("https://app.example.test/onboarding",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
  }

  @Test
  public void sendVerifyEmailBuildsTheVerifyEmailCommand() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, TEST_APP_BASE_URL);
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);
    Account account = account("account-1");

    JSONObject command = sendAndCaptureCommand(emailService,
        () -> sender.sendVerifyEmail(account, "verify-hash",
            "https://app.example.test/onboarding?verifyToken=abc", "es_ES"),
        "verify-email");

    assertBaseCommand(command);
    assertEquals("https://app.example.test/onboarding?verifyToken=abc",
        command.getString(EmailContractCommandSupport.FIELD_LINK));
    // The token hash doubles as the idempotency key, exactly as reset-password does.
    assertEquals("verify-hash", command.getString(EmailContractCommandSupport.FIELD_RECORD_ID));
    assertEquals("es_ES", command.getString(EmailContractCommandSupport.FIELD_LANGUAGE));
  }

  @Test
  public void sendVerifyEmailRefusesIncompleteInput() {
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);

    assertFalse(sender.sendVerifyEmail(null, "verify-hash", "https://app.example.test/x"));
    assertFalse(sender.sendVerifyEmail(account("account-1"), null, "https://app.example.test/x"));
    assertFalse(sender.sendVerifyEmail(account("account-1"), " ", "https://app.example.test/x"));
    assertFalse(sender.sendVerifyEmail(account("account-1"), "verify-hash", null));
    assertFalse(sender.sendVerifyEmail(account("account-1"), "verify-hash", " "));

    verify(emailService, never()).send(any(), any());
  }

  @Test
  public void nullAccountOrMissingLinkDoesNotSendEmail() {
    TransactionalEmailService emailService = mock(TransactionalEmailService.class);
    TransactionalAuthEmailSender sender = new TransactionalAuthEmailSender(emailService);

    sender.sendNewAccount(null);
    sender.sendPasswordReset(null, "reset-hash", "https://app.example.test/reset");
    sender.sendPasswordReset(account("account-1"), null, "https://app.example.test/reset");
    sender.sendPasswordReset(account("account-1"), "reset-hash", null);
    sender.sendPasswordReset(account("account-1"), "reset-hash", " ");
    sender.sendPasswordChanged(null);

    verify(emailService, never()).send(any(), any());
  }

  private static JSONObject sendAndCaptureCommand(TransactionalEmailService emailService,
      SendAction action, String contractName) throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(emailService.send(eq(contractName), any(JSONObject.class)))
        .thenReturn(NeoResponse.ok(new JSONObject()));
    ArgumentCaptor<JSONObject> commandCaptor = ArgumentCaptor.forClass(JSONObject.class);
    OBContext previousContext = mock(OBContext.class);

    try (MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      contextMock.when(OBContext::getOBContext).thenReturn(previousContext);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      action.send();

      contextMock.verify(() -> OBContext.setOBContext("0", "0", "0", "0"));
      contextMock.verify(() -> OBContext.setAdminMode(true));
      contextMock.verify(OBContext::restorePreviousMode);
      contextMock.verify(() -> OBContext.setOBContext(previousContext));
      verify(obDal).flush();
      verify(obDal).commitAndClose();
    }

    verify(emailService).send(eq(contractName), commandCaptor.capture());
    return commandCaptor.getValue();
  }

  private static void assertBaseCommand(JSONObject command) throws Exception {
    assertBaseCommand(command, "account-1");
  }

  private static void assertBaseCommand(JSONObject command, String expectedTenantId)
      throws Exception {
    assertEquals(EmailContractCommandSupport.VERSION,
        command.getString(EmailContractCommandSupport.FIELD_VERSION));
    assertEquals("account-1", command.getString(EmailContractCommandSupport.FIELD_ACCOUNT_ID));
    assertEquals(expectedTenantId, command.getString(EmailContractCommandSupport.FIELD_TENANT_ID));
  }

  private static Account account(String id) {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn(id);
    return account;
  }

  private interface SendAction {
    void send() throws Exception;
  }
}
