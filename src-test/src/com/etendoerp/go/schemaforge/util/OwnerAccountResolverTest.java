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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.schemaforge.data.Account;

/**
 * ETP-6228: the account that owns a client, not the one of whoever is signed in.
 *
 * @covers com.etendoerp.go.schemaforge.util.OwnerAccountResolver
 */
class OwnerAccountResolverTest {

  @BeforeEach
  void freshCache() {
    OwnerAccountResolver.resetForTest(null);
  }

  /** ETP-6386: an answer is cached for the TTL, then looked up again. */
  @Test
  void theAnswerIsCachedForTheTtlOnly() {
    AtomicLong now = new AtomicLong(1_000L);
    OwnerAccountResolver.resetForTest(now::get);
    try (Fixture f = new Fixture("OWNER-USER", "owner@acme.test", "ACCOUNT-OWNER")) {
      assertEquals("ACCOUNT-OWNER", OwnerAccountResolver.findOwnerAccountId("C1"));
      assertEquals("ACCOUNT-OWNER", OwnerAccountResolver.findOwnerAccountId("C1"));
      verify(f.query, times(1)).uniqueResult();

      now.addAndGet(OwnerAccountResolver.TTL_MILLIS + 1);
      OwnerAccountResolver.findOwnerAccountId("C1");
      verify(f.query, times(2)).uniqueResult();
    }
  }

  /** ETP-6386: "no owner" is an answer too: it is cached for the TTL, not looked up per call. */
  @Test
  void aNullAnswerIsCachedForTheTtl() {
    AtomicLong now = new AtomicLong(1_000L);
    OwnerAccountResolver.resetForTest(now::get);
    try (Fixture f = new Fixture(null, null, null)) {
      assertNull(OwnerAccountResolver.findOwnerAccountId("C1"));
      assertNull(OwnerAccountResolver.findOwnerAccountId("C1"));
      f.owner.verify(() -> OwnerSupport.findOwnerUserId("C1"), times(1));

      now.addAndGet(OwnerAccountResolver.TTL_MILLIS + 1);
      assertNull(OwnerAccountResolver.findOwnerAccountId("C1"));
      f.owner.verify(() -> OwnerSupport.findOwnerUserId("C1"), times(2));
    }
  }

  /** ETP-6386: a failed lookup throws and is not cached: the next call looks up again. */
  @Test
  void aFailedLookupIsNotCached() {
    try (Fixture f = new Fixture("OWNER-USER", "owner@acme.test", null)) {
      Account account = mock(Account.class);
      when(account.getId()).thenReturn("ACCOUNT-OWNER");
      when(f.query.uniqueResult()).thenThrow(new IllegalStateException("db down"))
          .thenReturn(account);

      org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
          () -> OwnerAccountResolver.findOwnerAccountId("C1"));

      assertEquals("ACCOUNT-OWNER", OwnerAccountResolver.findOwnerAccountId("C1"));
      f.obContext.verify(OBContext::restorePreviousMode, times(2));
    }
  }

  /** ETP-6386: 256 entries at most, least recently used out first. */
  @Test
  void theCacheIsBoundedAndEvictsTheLeastRecentlyUsed() {
    try (Fixture f = new Fixture(null, null, null)) {
      int max = OwnerAccountResolver.MAX_ENTRIES;
      for (int i = 0; i < max; i++) {
        OwnerAccountResolver.findOwnerAccountId("E" + i);
      }
      OwnerAccountResolver.findOwnerAccountId("E0"); // a hit: E0 is now the most recent
      OwnerAccountResolver.findOwnerAccountId("E" + max); // overflows: evicts E1
      f.owner.verify(() -> OwnerSupport.findOwnerUserId(anyString()), times(max + 1));

      OwnerAccountResolver.findOwnerAccountId("E0");
      f.owner.verify(() -> OwnerSupport.findOwnerUserId(anyString()), times(max + 1));
      OwnerAccountResolver.findOwnerAccountId("E1");
      f.owner.verify(() -> OwnerSupport.findOwnerUserId(anyString()), times(max + 2));
    }
  }

  /** The owner flag decides: the account of the owner user, whoever else belongs to the client. */
  @Test
  void returnsTheAccountOfTheFlaggedOwner() {
    try (Fixture f = new Fixture("OWNER-USER", "owner@acme.test", "ACCOUNT-OWNER")) {
      assertEquals("ACCOUNT-OWNER", OwnerAccountResolver.findOwnerAccountId("C1"));
      // The lookup is by the owner's email, never by the signed-in member's.
      verify(f.query).setNamedParameter("email", "owner@acme.test");
    }
  }

  @Test
  void aLegacyClientWithoutAnOwnerFlagHasNoOwnerAccount() {
    try (Fixture f = new Fixture(null, null, null)) {
      assertNull(OwnerAccountResolver.findOwnerAccountId("C1"));
    }
  }

  @Test
  void anOwnerWithoutAnActiveAccountHasNoOwnerAccount() {
    try (Fixture f = new Fixture("OWNER-USER", "owner@acme.test", null)) {
      assertNull(OwnerAccountResolver.findOwnerAccountId("C1"));
    }
  }

  @Test
  void anOwnerWithoutAnEmailHasNoOwnerAccount() {
    try (Fixture f = new Fixture("OWNER-USER", " ", "ACCOUNT-OWNER")) {
      assertNull(OwnerAccountResolver.findOwnerAccountId("C1"));
    }
  }

  /** The session restore does not run in admin mode, so the lookup enters it itself. */
  @Test
  void runsInAdminModeAndRestoresIt() {
    try (Fixture f = new Fixture("OWNER-USER", "owner@acme.test", "ACCOUNT-OWNER")) {
      OwnerAccountResolver.findOwnerAccountId("C1");

      f.obContext.verify(() -> OBContext.setAdminMode(true));
      f.obContext.verify(OBContext::restorePreviousMode);
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final MockedStatic<OwnerSupport> owner =
        org.mockito.Mockito.mockStatic(OwnerSupport.class);
    private final MockedStatic<OBDal> dalStatic = org.mockito.Mockito.mockStatic(OBDal.class);
    private final MockedStatic<OBContext> obContext = org.mockito.Mockito.mockStatic(OBContext.class);
    @SuppressWarnings("unchecked")
    private final OBQuery<Account> query = mock(OBQuery.class);

    Fixture(String ownerUserId, String ownerEmail, String accountId) {
      owner.when(() -> OwnerSupport.findOwnerUserId("C1")).thenReturn(ownerUserId);
      OBDal dal = mock(OBDal.class);
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      User user = mock(User.class);
      when(user.getEmail()).thenReturn(ownerEmail);
      when(dal.get(User.class, ownerUserId)).thenReturn(user);
      when(dal.createQuery(org.mockito.ArgumentMatchers.eq(Account.class), anyString()))
          .thenReturn(query);
      Account account = null;
      if (accountId != null) {
        account = mock(Account.class);
        when(account.getId()).thenReturn(accountId);
      }
      when(query.uniqueResult()).thenReturn(account);
    }

    @Override
    public void close() {
      obContext.close();
      dalStatic.close();
      owner.close();
    }
  }
}
