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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * The account rules shared by {@code /register} and the signup API (ETP-5847): the address states
 * the signup API reports and the mailed-token helpers. The verification issue/revert logic itself
 * is covered through {@code EtendoGoJwtServletTest}, which drives it via {@code /register}.
 *
 * @covers com.etendoerp.go.rest.AccountRegistrationService
 */
class AccountRegistrationServiceTest {

  private static final String EMAIL = "ana@empresa.es";
  private static final String VERIFY_LINK = "https://app.example.test/onboarding?verifyToken=t";

  private final TransactionalAuthEmailSender emailSender = mock(TransactionalAuthEmailSender.class);
  private final AccountRegistrationService service = new AccountRegistrationService(emailSender);
  private final Account account = mock(Account.class);

  private MockedStatic<EtendoGoJwtDalHelper> dal;
  private MockedStatic<EmailVerificationDalHelper> verification;
  private MockedStatic<EtendoGoAuthLinkBuilder> links;

  @BeforeEach
  void mockStatics() {
    dal = mockStatic(EtendoGoJwtDalHelper.class);
    verification = mockStatic(EmailVerificationDalHelper.class);
    links = mockStatic(EtendoGoAuthLinkBuilder.class);
    links.when(() -> EtendoGoAuthLinkBuilder.verifyEmailLink(anyString())).thenReturn(VERIFY_LINK);
  }

  @AfterEach
  void closeStatics() {
    links.close();
    verification.close();
    dal.close();
  }

  @Test
  void anUnusedAddressIsAvailable() {
    assertEquals(AccountRegistrationService.EmailStatus.AVAILABLE, service.emailStatus(EMAIL));
  }

  @Test
  void anAccountOwingItsConfirmationIsPending() {
    dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(account);
    verification.when(() -> EmailVerificationDalHelper.isEmailVerificationPending(account))
        .thenReturn(true);

    assertEquals(AccountRegistrationService.EmailStatus.PENDING_VERIFICATION,
        service.emailStatus(EMAIL));
  }

  @Test
  void anAccountOwingNothingIsRegistered() {
    dal.when(() -> EtendoGoJwtDalHelper.findActiveAccountByEmail(EMAIL)).thenReturn(account);

    assertEquals(AccountRegistrationService.EmailStatus.REGISTERED, service.emailStatus(EMAIL));
  }

  @Test
  void theSupportAddressIsRegisteredEvenThoughItsAccountIsHidden() {
    assertEquals(AccountRegistrationService.EmailStatus.REGISTERED,
        service.emailStatus(SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL));
  }

  @Test
  void aMailedTokenIsUrlSafeAndHashesTheSameEveryTime() {
    String token = AccountRegistrationService.generateSecureUrlToken();

    // 32 random bytes, base64url without padding: nothing a URL would have to escape.
    assertEquals(43, token.length());
    assertEquals(true, token.matches("[A-Za-z0-9_-]+"));
    assertEquals(AccountRegistrationService.hashAuthToken(token),
        AccountRegistrationService.hashAuthToken(token));
  }
}
