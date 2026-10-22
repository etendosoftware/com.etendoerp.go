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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * The signup columns of {@code ETGO_ACCOUNT} (ETP-5847): how a signup account is created, its
 * state recorded, and its one-time login link spent.
 *
 * @covers com.etendoerp.go.rest.SignupAccountDalHelper
 */
class SignupAccountDalHelperTest {

  private final OBDal obDal = mock(OBDal.class);
  private final Account account = mock(Account.class);
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<EtendoGoJwtDalHelper> accountDal;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    accountDal = mockStatic(EtendoGoJwtDalHelper.class);
  }

  @AfterEach
  void tearDown() {
    accountDal.close();
    obDalMock.close();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aSignupAccountHasNoPasswordAndIsBornProvisioning() {
    ArgumentCaptor<Consumer<Account>> extra = ArgumentCaptor.forClass(Consumer.class);
    accountDal.when(() -> EtendoGoJwtDalHelper.createAccount(eq("ana@x.es"), isNull(), eq("Ana"),
        isNull(), eq("{draft}"), extra.capture())).thenReturn(account);
    Date accepted = new Date();

    Account created = SignupAccountDalHelper.createSignupAccount("ana@x.es", "Ana", "{draft}",
        new SignupAccountDalHelper.SignupData("crm", false, "{\"utm\":1}", accepted));

    assertEquals(account, created);
    Account fresh = mock(Account.class);
    extra.getValue().accept(fresh);
    verify(fresh).set(SignupAccountDalHelper.PROPERTY_SIGNUP_CHANNEL, "crm");
    verify(fresh).set(SignupAccountDalHelper.PROPERTY_SIGNUP_SEND_EMAILS, false);
    verify(fresh).set(SignupAccountDalHelper.PROPERTY_SIGNUP_SOURCE, "{\"utm\":1}");
    verify(fresh).set(SignupAccountDalHelper.PROPERTY_TERMS_ACCEPTED_AT, accepted);
    verify(fresh).set(SignupAccountDalHelper.PROPERTY_SIGNUP_STATUS,
        SignupAccountDalHelper.STATUS_PROVISIONING);
  }

  @Test
  void readyRecordsTheEnvironmentAndClearsAnEarlierError() {
    SignupAccountDalHelper.markReady(account, "CLIENT1");

    verify(account).set(SignupAccountDalHelper.PROPERTY_SIGNUP_STATUS,
        SignupAccountDalHelper.STATUS_READY);
    verify(account).set(SignupAccountDalHelper.PROPERTY_SIGNUP_ENVIRONMENT, "CLIENT1");
    verify(account).set(SignupAccountDalHelper.PROPERTY_SIGNUP_ERROR, null);
    verify(obDal).commitAndClose();
  }

  @Test
  void aFailureReasonIsKeptWithinItsColumn() {
    ArgumentCaptor<Object> reason = ArgumentCaptor.forClass(Object.class);

    SignupAccountDalHelper.markFailed(account, "x".repeat(2500));

    verify(account).set(eq(SignupAccountDalHelper.PROPERTY_SIGNUP_ERROR), reason.capture());
    assertEquals(2000, reason.getValue().toString().length());
  }

  @Test
  void aBlankFailureReasonStillSaysSomething() {
    SignupAccountDalHelper.markFailed(account, " ");

    verify(account).set(SignupAccountDalHelper.PROPERTY_SIGNUP_ERROR,
        "Environment creation failed");
  }

  @Test
  void spendingTheLinkClearsItAndConfirmsTheAddressOnce() {
    Date usedAt = new Date();

    SignupAccountDalHelper.consumeLoginLink(account, usedAt);

    verify(account).set(SignupAccountDalHelper.PROPERTY_LOGIN_LINK_HASH, null);
    verify(account).set(SignupAccountDalHelper.PROPERTY_LOGIN_LINK_EXPIRES, null);
    verify(account).set(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED, usedAt);
  }

  @Test
  void spendingTheLinkKeepsAnEarlierConfirmation() {
    when(account.get(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED)).thenReturn(new Date(0));

    SignupAccountDalHelper.consumeLoginLink(account, new Date());

    verify(account, never()).set(eq(EtendoGoJwtDalHelper.PROPERTY_EMAIL_VERIFIED), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aLinkIsFoundOnlyByAnUnexpiredHash() {
    OBQuery<Account> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(account);
    Date now = new Date();

    assertEquals(account, SignupAccountDalHelper.findAccountByLoginLinkHash("h", now));

    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(Account.class), hql.capture());
    assertTrue(hql.getValue().contains(SignupAccountDalHelper.PROPERTY_LOGIN_LINK_EXPIRES + " > "));
    verify(query).setNamedParameter("hash", "h");
    verify(query).setNamedParameter("now", now);
  }

  @Test
  void theEnvironmentUserIsTheAccountsUserInTheSignupClient() {
    when(account.get(SignupAccountDalHelper.PROPERTY_SIGNUP_ENVIRONMENT)).thenReturn("C2");
    when(account.getEmail()).thenReturn("ana@x.es");
    User other = userIn("C1", "U1");
    User mine = userIn("C2", "U2");
    accountDal.when(() -> EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail("ana@x.es"))
        .thenReturn(List.of(other, mine));

    assertEquals("U2", SignupAccountDalHelper.environmentUserId(account));
  }

  @Test
  void noEnvironmentMeansNoUser() {
    assertNull(SignupAccountDalHelper.environmentUserId(account));
  }

  private static User userIn(String clientId, String userId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    User user = mock(User.class);
    when(user.getClient()).thenReturn(client);
    when(user.getId()).thenReturn(userId);
    return user;
  }
}
