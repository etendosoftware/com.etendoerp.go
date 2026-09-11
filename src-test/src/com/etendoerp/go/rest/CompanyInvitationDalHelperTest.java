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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

/**
 * Unit tests for {@link CompanyInvitationDalHelper}, focused on {@link
 * CompanyInvitationDalHelper#findInviterHomeUser(String)} — the ETP-4999 fix (Mystery #1) for
 * cross-client invite resolution, previously only exercised end-to-end by a slow, known-flaky
 * integration test. See that method's Javadoc for the full contract this class asserts against.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyInvitationDalHelperTest {

  @Mock private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
  }

  @Nested
  @DisplayName("findInviterHomeUser (ETP-4999 fix, Mystery #1)")
  class FindInviterHomeUser {

    @Mock private OBQuery<User> usernameQuery;
    @Mock private OBQuery<User> emailQuery;

    private final List<String> capturedHql = new ArrayList<>();

    /**
     * Routes {@code OBDal#createQuery(User.class, hql)} to the right mock based on which branch
     * of {@code findInviterHomeUser} built the HQL (username-exact-match first, email-fallback
     * second), mirroring the two-query shape of the method under test.
     */
    private void stubQueries() {
      when(obDal.createQuery(eq(User.class), anyString())).thenAnswer(invocation -> {
        String hql = invocation.getArgument(1);
        capturedHql.add(hql);
        return hql.contains("u.username") ? usernameQuery : emailQuery;
      });
    }

    @Test
    @DisplayName("matches by exact home username, even when an email-match row exists in a "
        + "different client (the exact scenario that was previously nondeterministic)")
    void usernameMatchWinsOverEmailMatchInDifferentClient() {
      stubQueries();
      User homeUser = mock(User.class);
      when(usernameQuery.uniqueResult()).thenReturn(homeUser);

      User result = CompanyInvitationDalHelper.findInviterHomeUser("User@Example.com");

      assertEquals(homeUser, result);
      verify(usernameQuery).setNamedParameter("email", "user@example.com");
      verify(usernameQuery).setMaxResult(1);
      verify(usernameQuery).setFilterOnReadableClients(false);
      verify(usernameQuery).setFilterOnReadableOrganization(false);
      // The email-fallback branch must never even be evaluated once the home username matches.
      verify(emailQuery, never()).uniqueResult();
    }

    @Test
    @DisplayName("falls back to an email match when there is genuinely no home-username row")
    void fallsBackToEmailMatchWhenNoUsernameMatch() {
      stubQueries();
      when(usernameQuery.uniqueResult()).thenReturn(null);
      User emailUser = mock(User.class);
      when(emailQuery.uniqueResult()).thenReturn(emailUser);

      User result = CompanyInvitationDalHelper.findInviterHomeUser("teammate@example.com");

      assertEquals(emailUser, result);
      verify(emailQuery).setNamedParameter("email", "teammate@example.com");
      verify(emailQuery).setMaxResult(1);
      verify(emailQuery).setFilterOnReadableClients(false);
      verify(emailQuery).setFilterOnReadableOrganization(false);
    }

    @Test
    @DisplayName("resolves multiple email-only matches deterministically via creationDate asc, "
        + "capped at one result (e.g. 2+ non-home AD_User rows sharing the same email across "
        + "different clients)")
    void resolvesMultipleEmailMatchesDeterministically() {
      stubQueries();
      when(usernameQuery.uniqueResult()).thenReturn(null);
      User oldest = mock(User.class);
      // uniqueResult() stands in for what the DB returns once the HQL below has already ordered
      // and capped the result set to a single row — the oldest AD_User by creationDate.
      when(emailQuery.uniqueResult()).thenReturn(oldest);

      User result = CompanyInvitationDalHelper.findInviterHomeUser("shared@example.com");

      assertEquals(oldest, result);
      String emailHql = capturedHql.stream()
          .filter(hql -> hql.contains("u.email"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("email-fallback query was never built"));
      // The mechanism that makes a multi-match tie-break deterministic: ascending creationDate
      // order plus a hard cap of one row, so the oldest AD_User always wins regardless of engine
      // return order.
      assertTrue(emailHql.contains("order by u.creationDate asc"));
      verify(emailQuery).setMaxResult(1);
    }

    @Test
    @DisplayName("returns null when neither the home username nor any email matches")
    void returnsNullWhenNoMatchAtAll() {
      stubQueries();
      when(usernameQuery.uniqueResult()).thenReturn(null);
      when(emailQuery.uniqueResult()).thenReturn(null);

      assertNull(CompanyInvitationDalHelper.findInviterHomeUser("nobody@example.com"));
    }

    @Test
    @DisplayName("lower-cases the account email before binding either query")
    void lowerCasesEmailBeforeBinding() {
      stubQueries();
      when(usernameQuery.uniqueResult()).thenReturn(mock(User.class));

      CompanyInvitationDalHelper.findInviterHomeUser("MixedCase@Example.COM");

      verify(usernameQuery).setNamedParameter("email", "mixedcase@example.com");
    }

    @Test
    @DisplayName("excludes root/system-client (id '0') users from both branches")
    void excludesRootClientUsers() {
      stubQueries();
      when(usernameQuery.uniqueResult()).thenReturn(null);
      when(emailQuery.uniqueResult()).thenReturn(null);

      CompanyInvitationDalHelper.findInviterHomeUser("someone@example.com");

      assertTrue(capturedHql.stream().allMatch(hql -> hql.contains("u.client.id <> '0'")));
    }
  }
}
