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

package com.etendoerp.go.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Account;

class GoAccountResolverTest {

  /**
   * Drives the resolver with a stubbed DAL that returns {@code account} only for the exact email
   * given, so a test asserts which email the resolver actually looked up.
   */
  private Optional<Account> resolveWithAccountRegisteredFor(String registeredEmail, Account account,
      String username) {
    OBDal dal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<Account> query = mock(OBQuery.class);
    when(dal.createQuery(eq(Account.class), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenAnswer(invocation -> null);

    try (var dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      // The resolver sets the email parameter before reading the result, so the stub can decide
      // based on what was actually requested.
      when(query.setNamedParameter(eq("email"), anyString())).thenAnswer(invocation -> {
        String requested = invocation.getArgument(1);
        when(query.uniqueResult())
            .thenReturn(registeredEmail.toLowerCase().equals(requested) ? account : null);
        return query;
      });
      return GoAccountResolver.findAccountByUsername(username);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   ", "\t" })
  void aBlankUsernameResolvesToNothing(String username) {
    assertFalse(GoAccountResolver.findAccountByUsername(username).isPresent());
  }

  @Test
  void resolvesAFirstEnvironmentUsernameByExactEmail() {
    Account account = mock(Account.class);
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "user@example.com", account, "user@example.com");
    assertTrue(resolved.isPresent());
    assertSame(account, resolved.get());
  }

  @Test
  void resolvesALaterEnvironmentUsernameByStrippingTheClientSuffix() {
    Account account = mock(Account.class);
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "user@example.com", account, "user@example.com+acmeltd");
    assertTrue(resolved.isPresent());
    assertSame(account, resolved.get());
  }

  /**
   * The case that makes splitting on the FIRST '+' wrong. Onboarding's suffix alphabet is [a-z0-9],
   * so the last '+' is always the separator and a plus-addressed account still resolves.
   */
  @Test
  void resolvesAPlusAddressedEmailCarryingAClientSuffix() {
    Account account = mock(Account.class);
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "user+tag@example.com", account, "user+tag@example.com+acmeltd");
    assertTrue(resolved.isPresent());
    assertSame(account, resolved.get());
  }

  @Test
  void resolvesAPlusAddressedEmailWithNoClientSuffix() {
    Account account = mock(Account.class);
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "user+tag@example.com", account, "user+tag@example.com");
    assertTrue(resolved.isPresent());
    assertSame(account, resolved.get());
  }

  @Test
  void matchesTheEmailCaseInsensitively() {
    Account account = mock(Account.class);
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "user@example.com", account, "User@Example.COM");
    assertTrue(resolved.isPresent());
  }

  @Test
  void aUsernameWithNoMatchingAccountResolvesToNothing() {
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "someone@example.com", mock(Account.class), "stranger@example.com");
    assertFalse(resolved.isPresent());
  }

  @Test
  void aHandCreatedErpUsernameResolvesToNothing() {
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "user@example.com", mock(Account.class), "warehouse-operator");
    assertFalse(resolved.isPresent());
  }

  @Test
  void aUsernameStartingWithPlusIsNotTreatedAsASuffix() {
    Optional<Account> resolved = resolveWithAccountRegisteredFor(
        "", mock(Account.class), "+acmeltd");
    assertFalse(resolved.isPresent());
  }

  /**
   * Identity enrichment must never break the caller that asked for it, so a DAL failure degrades to
   * "unknown identity" rather than propagating.
   */
  @Test
  void aDalFailureResolvesToNothingInsteadOfThrowing() {
    OBDal dal = mock(OBDal.class);
    when(dal.createQuery(eq(Account.class), anyString()))
        .thenThrow(new IllegalStateException("no session"));

    try (var dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      assertFalse(GoAccountResolver.findAccountByUsername("user@example.com").isPresent());
    }
  }

  @Test
  void queriesOnlyActiveAccounts() {
    OBDal dal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<Account> query = mock(OBQuery.class);
    when(dal.createQuery(eq(Account.class), anyString())).thenReturn(query);
    when(query.setNamedParameter(anyString(), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(null);

    try (var dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      GoAccountResolver.findAccountByUsername("user@example.com");
    }

    org.mockito.ArgumentCaptor<String> hql = org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(dal).createQuery(eq(Account.class), hql.capture());
    assertTrue(hql.getValue().contains("account.active = true"));
    assertEquals(true, hql.getValue().contains("lower(account.email)"));
  }
}
