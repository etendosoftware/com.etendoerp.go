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

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.data.Account;
/**
 * Unit tests for {@link EtendoGoAccountProvisioning} — the public cross-package entry point
 * (ETP-4829) that {@code com.etendoerp.go.schemaforge.handlers.UserRoleAssignmentHandler} calls
 * without depending on the package-private {@link EtendoGoJwtDalHelper} directly.
 */
class EtendoGoAccountProvisioningTest {

  @Test
  void ensurePendingAccountDelegatesToDalHelper() {
    try (MockedStatic<EtendoGoJwtDalHelper> dalHelperMock =
        mockStatic(EtendoGoJwtDalHelper.class)) {
      EtendoGoAccountProvisioning.ensurePendingAccount("new.user@test.com", "New User");

      dalHelperMock.verify(
          () -> EtendoGoJwtDalHelper.createPendingAccount("new.user@test.com", "New User"));
    }
  }

  @Test
  void ensureAccountForCreatedUserCreatesPendingAccountWhenPasswordIsBlank() {
    try (MockedStatic<EtendoGoJwtDalHelper> dalHelperMock =
        mockStatic(EtendoGoJwtDalHelper.class)) {
      EtendoGoAccountProvisioning.ensureAccountForCreatedUser("new.user@test.com", "New User",
          "   ");

      dalHelperMock.verify(
          () -> EtendoGoJwtDalHelper.createPendingAccount("new.user@test.com", "New User"));
      dalHelperMock.verify(
          () -> EtendoGoJwtDalHelper.createActiveAccount(
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any()),
          never());
    }
  }

  @Test
  void ensureAccountForCreatedUserCreatesActiveAccountWithHashedPasswordWhenPresent() {
    try (MockedStatic<EtendoGoJwtDalHelper> dalHelperMock =
        mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PasswordHasher> hasherMock = mockStatic(PasswordHasher.class)) {
      hasherMock.when(() -> PasswordHasher.hash("Str0ng!Pass")).thenReturn("salt:hash");

      EtendoGoAccountProvisioning.ensureAccountForCreatedUser("new.user@test.com", "New User",
          "Str0ng!Pass");

      dalHelperMock.verify(() -> EtendoGoJwtDalHelper.createActiveAccount("new.user@test.com",
          "salt:hash", "New User"));
      dalHelperMock.verify(
          () -> EtendoGoJwtDalHelper.createPendingAccount(
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()),
          never());
    }
  }

  @Test
  void ensurePendingAccountSendsPasswordSetupInvitationForNewPendingAccount() {
    Account account = org.mockito.Mockito.mock(Account.class);
    when(account.get("status")).thenReturn("pending");
    when(account.getEmail()).thenReturn("new.user@test.com");

    try (MockedStatic<EtendoGoJwtDalHelper> dalHelperMock = mockStatic(EtendoGoJwtDalHelper.class);
        MockedStatic<PublicUrlResolver> publicUrlMock = mockStatic(PublicUrlResolver.class);
        MockedStatic<EtendoGoAuthLinkBuilder> linkBuilderMock =
            mockStatic(EtendoGoAuthLinkBuilder.class);
        MockedConstruction<TransactionalAuthEmailSender> senderConstruction =
            org.mockito.Mockito.mockConstruction(TransactionalAuthEmailSender.class,
                (sender, context) -> when(sender.sendPasswordReset(
                    org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq("https://app.test/onboarding?resetToken=token")))
                    .thenReturn(true))) {
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.createPendingAccount(
          "new.user@test.com", "New User")).thenReturn(account);
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.hasPasswordResetToken(account)).thenReturn(false);
      dalHelperMock.when(() -> EtendoGoJwtDalHelper.capturePasswordResetToken(account)).thenReturn(null);
      publicUrlMock.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.test");
      linkBuilderMock.when(() -> EtendoGoAuthLinkBuilder.resetPasswordLink(
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("https://app.test")))
          .thenReturn("https://app.test/onboarding?resetToken=token");

      EtendoGoAccountProvisioning.ensurePendingAccount("new.user@test.com", "New User");

      verify(senderConstruction.constructed().get(0)).sendPasswordReset(
          org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.eq("https://app.test/onboarding?resetToken=token"));
      dalHelperMock.verify(() -> EtendoGoJwtDalHelper.storePasswordResetToken(
          org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.any(Date.class)));
    }
  }
}
