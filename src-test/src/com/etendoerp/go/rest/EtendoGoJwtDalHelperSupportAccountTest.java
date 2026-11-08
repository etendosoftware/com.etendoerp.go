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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountIdentity;
import com.etendoerp.go.supportaccess.SupportAccessGuard;

/**
 * ETP-5351 (T5) — the credential lookups never hand out the technical support account: password
 * login ({@code POST /session}, legacy {@code POST /login}), password reset request and confirm,
 * legacy bearer tokens and SSO identities. Only the by-id lookup the support sessions use returns
 * it. No database: {@link OBDal} is mocked.
 */
class EtendoGoJwtDalHelperSupportAccountTest {

  private OBDal obDal;
  private OBQuery<Account> query;
  private MockedStatic<OBDal> obDalMock;
  private Account supportAccount;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obDal = mock(OBDal.class);
    query = mock(OBQuery.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
    supportAccount = mock(Account.class);
    when(supportAccount.getId()).thenReturn(SupportAccessGuard.SUPPORT_ACCOUNT_ID);
    when(supportAccount.isActive()).thenReturn(true);
    when(query.uniqueResult()).thenReturn(supportAccount);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  @Test
  void passwordLoginAndResetRequestNeverFindTheSupportAccountByEmail() {
    assertNull(EtendoGoJwtDalHelper.findActiveAccountByEmail(
        SupportAccessGuard.SUPPORT_ACCOUNT_EMAIL));
  }

  @Test
  void aLegacySessionTokenNeverResolvesToTheSupportAccount() {
    assertNull(EtendoGoJwtDalHelper.findActiveAccountByToken("leaked-token"));
    assertNull(EtendoGoJwtDalHelper.findActiveAccountByPlatformToken("leaked-token"));
    assertNull(EtendoGoJwtDalHelper.findActiveAccountByBearerToken("leaked-token"));
  }

  @Test
  void aResetTokenNeverResolvesToTheSupportAccount() {
    assertNull(EtendoGoJwtDalHelper.findActiveAccountByResetTokenHash("hash", new Date()));
  }

  @Test
  void anSsoIdentityNeverResolvesToTheSupportAccount() {
    try (MockedStatic<AccountIdentityDalHelper> identities =
        mockStatic(AccountIdentityDalHelper.class)) {
      identities.when(() -> AccountIdentityDalHelper.findAccountByIdentity("google", "sub"))
          .thenReturn(supportAccount);
      assertNull(EtendoGoJwtDalHelper.findActiveAccountBySsoIdentity("google", "sub"));
    }
  }

  @Test
  void theSupportSessionsStillResolveTheirAccountById() {
    when(obDal.get(Account.class, SupportAccessGuard.SUPPORT_ACCOUNT_ID))
        .thenReturn(supportAccount);
    assertSame(supportAccount,
        EtendoGoJwtDalHelper.findActiveAccountById(SupportAccessGuard.SUPPORT_ACCOUNT_ID));
  }

  @Test
  void anIdentityRowPointingAtTheSupportAccountIsIgnored() {
    @SuppressWarnings("unchecked")
    OBQuery<AccountIdentity> identityQuery = mock(OBQuery.class);
    AccountIdentity identity = mock(AccountIdentity.class);
    when(identity.getAccount()).thenReturn(supportAccount);
    when(obDal.createQuery(eq(AccountIdentity.class), anyString())).thenReturn(identityQuery);
    when(identityQuery.uniqueResult()).thenReturn(identity);
    when(identityQuery.list()).thenReturn(java.util.List.of(identity));

    assertNull(AccountIdentityDalHelper.findAccountByIdentity("google", "sub"));
  }

  @Test
  void theSupportAccountNeverGetsAnSsoIdentity() {
    try (MockedStatic<OBProvider> provider = mockStatic(OBProvider.class)) {
      assertThrows(IllegalArgumentException.class, () -> AccountIdentityDalHelper.link(
          supportAccount, "google", "sub", "x@example.test", new Date()));
      provider.verify(OBProvider::getInstance, never());
    }
    assertFalse(AccountIdentityDalHelper.linkIfCompatible(supportAccount, "google", "sub",
        "x@example.test"));
  }
}
