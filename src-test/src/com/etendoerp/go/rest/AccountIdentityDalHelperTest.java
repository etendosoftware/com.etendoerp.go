/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.AccountIdentity;

/**
 * Unit tests for {@link AccountIdentityDalHelper}, focused on the lazy migration off the legacy
 * inline identity columns (ETP-5115). There is no backfill script, so the fallback in the helper is
 * the only thing that moves an existing account onto {@code ETGO_Account_Identity}; these tests pin
 * the decisions the class javadoc states rather than the shape of its implementation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountIdentityDalHelperTest {

  private static final String PROVIDER = "google";
  private static final String SUBJECT = "sub-123";
  private static final String EXTERNAL_EMAIL = "person@gmail.com";

  /** Marks the child-table lookup by (provider, subject). */
  private static final String BY_IDENTITY = "identity.authProvider";
  /** Marks the child-table lookup of every row of one account. */
  private static final String BY_ACCOUNT = "identity.account = :account";

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private OBQuery<AccountIdentity> byIdentityQuery;
  @Mock private OBQuery<AccountIdentity> byAccountQuery;
  @Mock private OBQuery<Account> legacyQuery;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

    when(obDal.createQuery(eq(AccountIdentity.class), contains(BY_IDENTITY)))
        .thenReturn(byIdentityQuery);
    when(obDal.createQuery(eq(AccountIdentity.class), contains(BY_ACCOUNT)))
        .thenReturn(byAccountQuery);
    when(obDal.createQuery(eq(Account.class), anyString())).thenReturn(legacyQuery);
    when(byAccountQuery.list()).thenReturn(Collections.emptyList());
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obProviderMock != null) {
      obProviderMock.close();
    }
  }

  /** An account whose identity still lives only in the four inline columns. */
  private Account legacyAccount(Date lastLogin) {
    Account account = mock(Account.class);
    when(account.get(Account.PROPERTY_AUTHPROVIDER)).thenReturn(PROVIDER);
    when(account.get(Account.PROPERTY_EXTERNALSUBJECT)).thenReturn(SUBJECT);
    when(account.get(Account.PROPERTY_EXTERNALEMAIL)).thenReturn(EXTERNAL_EMAIL);
    when(account.get(Account.PROPERTY_LASTSSOLOGIN)).thenReturn(lastLogin);
    return account;
  }

  /** An account with neither a child row nor legacy columns. */
  private Account bareAccount() {
    return mock(Account.class);
  }

  /** The row {@code OBProvider} hands the helper for a new identity. */
  private AccountIdentity newRowFromProvider() {
    AccountIdentity row = mock(AccountIdentity.class);
    when(obProvider.get(AccountIdentity.class)).thenReturn(row);
    return row;
  }

  private AccountIdentity existingRow(String provider, String subject, Account account) {
    AccountIdentity row = mock(AccountIdentity.class);
    when(row.getAuthProvider()).thenReturn(provider);
    when(row.getExternalSubject()).thenReturn(subject);
    when(row.getAccount()).thenReturn(account);
    return row;
  }

  private AccountIdentity savedRow() {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(obDal).save(captor.capture());
    Object saved = captor.getValue();
    assertTrue(saved instanceof AccountIdentity, "the helper must save an AccountIdentity row");
    return (AccountIdentity) saved;
  }

  private void assertNothingCommitted() {
    verify(obDal, never()).commitAndClose();
    verify(obDal, never()).rollbackAndClose();
  }

  @Nested
  @DisplayName("findAccountByIdentity")
  class FindAccountByIdentity {

    @Test
    @DisplayName("returns null and touches nothing when provider or subject is blank")
    void returnsNullOnBlankInput() {
      assertNull(AccountIdentityDalHelper.findAccountByIdentity(null, SUBJECT));
      assertNull(AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, "  "));
      assertNull(AccountIdentityDalHelper.findAccountByIdentity("", ""));

      verify(obDal, never()).createQuery(any(Class.class), anyString());
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("resolves through the child row without reading the legacy columns")
    void resolvesThroughChildRow() {
      Account account = mock(Account.class);
      AccountIdentity row = existingRow(PROVIDER, SUBJECT, account);
      when(byIdentityQuery.uniqueResult()).thenReturn(row);

      Account result = AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, SUBJECT);

      assertSame(account, result);
      verify(obDal, never()).createQuery(eq(Account.class), anyString());
      verify(obDal, never()).save(any());
      assertNothingCommitted();
    }

    @Test
    @DisplayName("resolves a legacy-only account and materialises its child row on the way")
    void resolvesLegacyOnlyAccountAndMigratesIt() {
      Date lastLogin = new Date(1_700_000_000_000L);
      Account account = legacyAccount(lastLogin);
      when(byIdentityQuery.uniqueResult()).thenReturn(null);
      when(legacyQuery.uniqueResult()).thenReturn(account);
      AccountIdentity row = newRowFromProvider();

      Account result = AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, SUBJECT);

      assertSame(account, result);
      assertSame(row, savedRow());
      verify(row).setAccount(account);
      verify(row).setAuthProvider(PROVIDER);
      verify(row).setExternalSubject(SUBJECT);
      verify(row).setExternalEmail(EXTERNAL_EMAIL);
      verify(row).setLastSSOLogin(lastLogin);
      assertNothingCommitted();
    }

    @Test
    @DisplayName("migrates once: the second read reuses the child row and inserts nothing")
    void migratesOnlyOnce() {
      Account account = legacyAccount(new Date());
      AccountIdentity row = newRowFromProvider();
      when(row.getAccount()).thenReturn(account);
      when(byIdentityQuery.uniqueResult()).thenReturn(null, row);
      when(legacyQuery.uniqueResult()).thenReturn(account);

      Account first = AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, SUBJECT);
      Account second = AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, SUBJECT);

      assertSame(account, first);
      assertSame(account, second);
      verify(obProvider, times(1)).get(AccountIdentity.class);
      verify(obDal, times(1)).save(any());
      verify(legacyQuery, times(1)).uniqueResult();
      assertNothingCommitted();
    }

    @Test
    @DisplayName("returns null and writes nothing when the identity is unknown everywhere")
    void returnsNullWhenUnknown() {
      when(byIdentityQuery.uniqueResult()).thenReturn(null);
      when(legacyQuery.uniqueResult()).thenReturn(null);

      assertNull(AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, SUBJECT));

      verify(obProvider, never()).get(AccountIdentity.class);
      verify(obDal, never()).save(any());
      verify(obDal, never()).flush();
      assertNothingCommitted();
    }
  }

  @Nested
  @DisplayName("identitiesFor")
  class IdentitiesFor {

    @Test
    @DisplayName("returns empty for a null account without touching the DAL")
    void returnsEmptyForNullAccount() {
      assertTrue(AccountIdentityDalHelper.identitiesFor(null).isEmpty());
      verify(obDal, never()).createQuery(any(Class.class), anyString());
    }

    @Test
    @DisplayName("returns the existing child rows and does not migrate")
    void returnsExistingRows() {
      Account account = legacyAccount(new Date());
      AccountIdentity row = existingRow(PROVIDER, SUBJECT, account);
      when(byAccountQuery.list()).thenReturn(Collections.singletonList(row));

      List<AccountIdentity> result = AccountIdentityDalHelper.identitiesFor(account);

      assertEquals(Collections.singletonList(row), result);
      verify(obProvider, never()).get(AccountIdentity.class);
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("migrates a legacy-only account and returns the materialised row")
    void migratesLegacyOnlyAccount() {
      Date lastLogin = new Date(1_600_000_000_000L);
      Account account = legacyAccount(lastLogin);
      AccountIdentity row = newRowFromProvider();

      List<AccountIdentity> result = AccountIdentityDalHelper.identitiesFor(account);

      assertEquals(1, result.size());
      assertSame(row, result.get(0));
      assertSame(row, savedRow());
      verify(row).setAuthProvider(PROVIDER);
      verify(row).setExternalSubject(SUBJECT);
      verify(row).setLastSSOLogin(lastLogin);
      assertNothingCommitted();
    }

    @Test
    @DisplayName("returns empty and writes nothing when there is no identity at all")
    void returnsEmptyWhenNoIdentityAnywhere() {
      List<AccountIdentity> result = AccountIdentityDalHelper.identitiesFor(bareAccount());

      assertTrue(result.isEmpty());
      verify(obProvider, never()).get(AccountIdentity.class);
      verify(obDal, never()).save(any());
      verify(obDal, never()).flush();
      assertNothingCommitted();
    }

    @Test
    @DisplayName("treats blank legacy columns as no identity")
    void treatsBlankLegacyColumnsAsNoIdentity() {
      Account account = mock(Account.class);
      when(account.get(Account.PROPERTY_AUTHPROVIDER)).thenReturn("   ");
      when(account.get(Account.PROPERTY_EXTERNALSUBJECT)).thenReturn(SUBJECT);

      assertTrue(AccountIdentityDalHelper.identitiesFor(account).isEmpty());
      verify(obDal, never()).save(any());
    }
  }

  @Nested
  @DisplayName("LINKED")
  class Linked {

    @Test
    @DisplayName("is null on a migrated row: the account never recorded when the user linked")
    void isNullOnMigratedRow() {
      AccountIdentity row = newRowFromProvider();

      AccountIdentityDalHelper.identitiesFor(legacyAccount(new Date()));

      ArgumentCaptor<Date> linked = ArgumentCaptor.forClass(Date.class);
      verify(row).setLinked(linked.capture());
      assertNull(linked.getValue(), "a migrated row must not invent a link date");
    }

    @Test
    @DisplayName("is the link instant on a row created through link(...)")
    void isSetOnExplicitLink() {
      Date linkedAt = new Date(1_650_000_000_000L);
      AccountIdentity row = newRowFromProvider();
      Account account = bareAccount();

      AccountIdentity result =
          AccountIdentityDalHelper.link(account, PROVIDER, SUBJECT, EXTERNAL_EMAIL, linkedAt);

      assertSame(row, result);
      verify(row).setLinked(linkedAt);
      verify(row).setLastSSOLogin(linkedAt);
      verify(row).setAccount(account);
      verify(row).setExternalEmail(EXTERNAL_EMAIL);
      verify(obDal).save(row);
      assertNothingCommitted();
    }
  }

  @Nested
  @DisplayName("concurrent migration")
  class ConcurrentMigration {

    @Test
    @DisplayName("swallows the losing insert and returns the row the winner wrote")
    void returnsTheWinnerWhenTheInsertLoses() {
      Account account = legacyAccount(new Date());
      AccountIdentity loser = newRowFromProvider();
      AccountIdentity winner = existingRow(PROVIDER, SUBJECT, account);
      when(byIdentityQuery.uniqueResult()).thenReturn(null, winner);
      when(legacyQuery.uniqueResult()).thenReturn(account);
      org.mockito.Mockito.doThrow(new IllegalStateException("unique violation"))
          .when(obDal).flush();

      Account result = AccountIdentityDalHelper.findAccountByIdentity(PROVIDER, SUBJECT);

      assertSame(account, result);
      assertNotNull(winner.getAccount());
      verify(obDal).save(loser);
      assertNothingCommitted();
    }

    @Test
    @DisplayName("returns the winner from identitiesFor rather than the row it failed to insert")
    void identitiesForReturnsTheWinner() {
      Account account = legacyAccount(new Date());
      newRowFromProvider();
      AccountIdentity winner = existingRow(PROVIDER, SUBJECT, account);
      when(byIdentityQuery.uniqueResult()).thenReturn(winner);
      org.mockito.Mockito.doThrow(new IllegalStateException("unique violation"))
          .when(obDal).flush();

      List<AccountIdentity> result = AccountIdentityDalHelper.identitiesFor(account);

      assertEquals(Collections.singletonList(winner), result);
      assertNothingCommitted();
    }

    @Test
    @DisplayName("yields no identity when the losing insert finds no winner either")
    void returnsEmptyWhenTheRereadFindsNothing() {
      Account account = legacyAccount(new Date());
      newRowFromProvider();
      when(byIdentityQuery.uniqueResult()).thenReturn(null);
      org.mockito.Mockito.doThrow(new IllegalStateException("unique violation"))
          .when(obDal).flush();

      assertTrue(AccountIdentityDalHelper.identitiesFor(account).isEmpty());
      assertNothingCommitted();
    }
  }

  @Nested
  @DisplayName("linkIfCompatible")
  class LinkIfCompatible {

    @Test
    @DisplayName("links when the account carries no identity")
    void linksWhenAccountHasNone() {
      AccountIdentity row = newRowFromProvider();

      assertTrue(AccountIdentityDalHelper.linkIfCompatible(bareAccount(), PROVIDER, SUBJECT,
          EXTERNAL_EMAIL));

      verify(obDal).save(row);
      verify(row).setAuthProvider(PROVIDER);
      assertNothingCommitted();
    }

    @Test
    @DisplayName("accepts the same identity again as a no-op")
    void acceptsTheSameIdentityAgain() {
      Account account = bareAccount();
      AccountIdentity row = existingRow(PROVIDER, SUBJECT, account);
      when(byAccountQuery.list()).thenReturn(Collections.singletonList(row));

      assertTrue(
          AccountIdentityDalHelper.linkIfCompatible(account, PROVIDER, SUBJECT, EXTERNAL_EMAIL));

      verify(obProvider, never()).get(AccountIdentity.class);
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("refuses a second subject on the provider already linked")
    void refusesADifferentSubjectOnTheSameProvider() {
      Account account = bareAccount();
      AccountIdentity row = existingRow(PROVIDER, SUBJECT, account);
      when(byAccountQuery.list()).thenReturn(Collections.singletonList(row));

      assertFalse(AccountIdentityDalHelper.linkIfCompatible(account, PROVIDER, "other-subject",
          EXTERNAL_EMAIL));

      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("refuses an entirely different provider")
    void refusesADifferentProvider() {
      Account account = bareAccount();
      AccountIdentity row = existingRow(PROVIDER, SUBJECT, account);
      when(byAccountQuery.list()).thenReturn(Collections.singletonList(row));

      assertFalse(
          AccountIdentityDalHelper.linkIfCompatible(account, "microsoft", SUBJECT, EXTERNAL_EMAIL));

      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("migrates a legacy-only account and then refuses the different identity")
    void migratesThenRefusesOnALegacyOnlyAccount() {
      Account account = legacyAccount(new Date());
      AccountIdentity migrated = newRowFromProvider();
      when(migrated.getAuthProvider()).thenReturn(PROVIDER);
      when(migrated.getExternalSubject()).thenReturn(SUBJECT);

      assertFalse(
          AccountIdentityDalHelper.linkIfCompatible(account, "microsoft", "other", EXTERNAL_EMAIL));

      verify(obDal, times(1)).save(migrated);
      assertNothingCommitted();
    }
  }

  @Nested
  @DisplayName("identityForProvider, soleIdentityOf and recordLogin")
  class Accessors {

    @Test
    @DisplayName("identityForProvider returns null for a null account or a blank provider")
    void identityForProviderGuardsItsInput() {
      assertNull(AccountIdentityDalHelper.identityForProvider(null, PROVIDER));
      assertNull(AccountIdentityDalHelper.identityForProvider(bareAccount(), " "));
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("identityForProvider picks the row of the provider asked for")
    void identityForProviderPicksTheMatchingRow() {
      Account account = bareAccount();
      AccountIdentity google = existingRow(PROVIDER, SUBJECT, account);
      AccountIdentity microsoft = existingRow("microsoft", "ms-sub", account);
      when(byAccountQuery.list()).thenReturn(java.util.Arrays.asList(google, microsoft));

      assertSame(microsoft, AccountIdentityDalHelper.identityForProvider(account, "microsoft"));
      assertNull(AccountIdentityDalHelper.identityForProvider(account, "apple"));
    }

    @Test
    @DisplayName("identityForProvider sees the row the migration just materialised")
    void identityForProviderSeesTheMigratedRow() {
      Account account = legacyAccount(new Date());
      AccountIdentity row = newRowFromProvider();
      when(row.getAuthProvider()).thenReturn(PROVIDER);

      assertSame(row, AccountIdentityDalHelper.identityForProvider(account, PROVIDER));
    }

    @Test
    @DisplayName("soleIdentityOf returns null when there is no identity, the row when there is")
    void soleIdentityOfReturnsTheSingleRow() {
      assertNull(AccountIdentityDalHelper.soleIdentityOf(bareAccount()));

      Account account = bareAccount();
      AccountIdentity row = existingRow(PROVIDER, SUBJECT, account);
      when(byAccountQuery.list()).thenReturn(Collections.singletonList(row));

      assertSame(row, AccountIdentityDalHelper.soleIdentityOf(account));
    }

    @Test
    @DisplayName("recordLogin refreshes the asserted address and the last login, without committing")
    void recordLoginRefreshesTheRow() {
      Date loginAt = new Date(1_710_000_000_000L);
      AccountIdentity row = mock(AccountIdentity.class);

      AccountIdentityDalHelper.recordLogin(row, "new@gmail.com", loginAt);

      verify(row).setExternalEmail("new@gmail.com");
      verify(row).setLastSSOLogin(loginAt);
      verify(obDal).save(row);
      assertNothingCommitted();
    }

    @Test
    @DisplayName("recordLogin ignores a null identity")
    void recordLoginIgnoresNull() {
      AccountIdentityDalHelper.recordLogin(null, EXTERNAL_EMAIL, new Date());
      verify(obDal, never()).save(any());
    }
  }
}
