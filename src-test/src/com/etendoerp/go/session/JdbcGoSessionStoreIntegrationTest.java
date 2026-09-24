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
package com.etendoerp.go.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.After;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.oauth2.OAuth2Utils;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * Integration test for {@link JdbcGoSessionStore} against the real {@code ETGO_GO_SESSION} table
 * (ETP-4575). Exercises the full session lifecycle through {@link GoSessionService} so the SQL,
 * column names, constraints and {@code ResultSet} mapping are verified end-to-end.
 *
 * <p>It reuses an <em>existing</em> committed {@code ETGO_ACCOUNT} as the session owner (like the
 * other DB-backed tests in this module reuse existing fixture rows) so the {@code etgo_account_id}
 * foreign key resolves against a committed parent. The session rows it creates are rolled back.
 */
public class JdbcGoSessionStoreIntegrationTest extends OBBaseTest {

  private final GoSessionStore store = new JdbcGoSessionStore();
  private final GoSessionService service = new GoSessionService(store);

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void fullSessionLifecycleAgainstRealTable() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      String accountId = existingAccountId();

      // --- create: row persisted, only the hash stored, resolvable by the raw token ---
      IssuedGoSession issued = service.create(accountId, "password", "IT-UA", "ip-hash");
      assertNotNull(issued.getSessionToken());
      assertNotEquals(issued.getSessionToken(), issued.getRecord().getSessionTokenHash());

      GoSessionRecord persisted = store.findByTokenHash(
          OAuth2Utils.hashToken(issued.getSessionToken()));
      assertNotNull("session row must be persisted", persisted);
      assertEquals(accountId, persisted.getAccountId());
      assertNotNull(service.resolve(issued.getSessionToken()));

      // --- rotate: old token dies, new token works ---
      IssuedGoSession rotated = service.rotate(issued.getRecord());
      assertNull(service.resolve(issued.getSessionToken()));
      assertNotNull(service.resolve(rotated.getSessionToken()));

      // --- refresh: rotates the session ---
      IssuedGoSession refreshed = service.refresh(rotated.getRefreshToken());
      assertNotNull(refreshed);
      assertNull(service.resolve(rotated.getSessionToken()));
      assertNotNull(service.resolve(refreshed.getSessionToken()));

      // --- refresh replay: reusing the consumed refresh revokes the whole family ---
      assertNull("replayed refresh must be rejected", service.refresh(rotated.getRefreshToken()));
      assertNull("active descendant must be revoked on replay",
          service.resolve(refreshed.getSessionToken()));

      // --- logout: server-side invalidation ---
      IssuedGoSession another = service.create(accountId, "sso", null, null);
      assertNotNull(service.resolve(another.getSessionToken()));
      service.revoke(another.getRecord());
      assertNull(service.resolve(another.getSessionToken()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5465 — the renewal UPDATE against the real table: an active session near its idle expiry
   * gets {@code expires_at} slid forward and keeps resolving.
   */
  @Test
  public void renewIdleExpirySlidesExpiresAtInTheRealTable() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      IssuedGoSession issued = service.create(existingAccountId(), "password", null, null);
      GoSessionRecord nearExpiry = persistedWithIdleExpiry(issued, Instant.now().plusSeconds(60));

      service.renewIdleExpiry(nearExpiry);

      GoSessionRecord reloaded = reload(issued);
      assertTrue("expires_at must be slid forward",
          reloaded.getExpiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(20))));
      assertNotNull(service.resolve(issued.getSessionToken()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** ETP-5465 — the compare-and-set: a stale observed expiry must not overwrite a newer one. */
  @Test
  public void touchExpiresAtRejectsAStaleExpectedExpiry() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      IssuedGoSession issued = service.create(existingAccountId(), "password", null, null);
      GoSessionRecord persisted = reload(issued);
      Instant stale = persisted.getExpiresAt().minusSeconds(5);

      assertFalse(store.touchExpiresAt(persisted.getId(), stale,
          Instant.now().plus(Duration.ofMinutes(30))));
      assertEquals(persisted.getExpiresAt(), reload(issued).getExpiresAt());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** ETP-5465 — revoked sessions and sessions past their absolute cap are never renewed. */
  @Test
  public void touchExpiresAtIgnoresRevokedAndAbsolutelyExpiredSessions() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      String accountId = existingAccountId();
      Instant next = Instant.now().plus(Duration.ofMinutes(30));

      IssuedGoSession revoked = service.create(accountId, "password", null, null);
      service.revoke(revoked.getRecord());
      GoSessionRecord revokedRow = reload(revoked);
      assertFalse(store.touchExpiresAt(revokedRow.getId(), revokedRow.getExpiresAt(), next));

      IssuedGoSession capped = service.create(accountId, "password", null, null);
      GoSessionRecord cappedRow = reload(capped);
      cappedRow.setAbsoluteExpiresAt(Instant.now().minusSeconds(1));
      store.update(cappedRow);
      assertFalse(store.touchExpiresAt(cappedRow.getId(), reload(capped).getExpiresAt(), next));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String existingAccountId() {
    Account account = (Account) OBDal.getInstance().createCriteria(Account.class)
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("Test fixture must contain at least one ETGO_ACCOUNT to own the session",
        account);
    return account.getId();
  }

  /** Re-reads the row so comparisons use the column's real precision, not the in-memory value. */
  private GoSessionRecord reload(IssuedGoSession issued) {
    return store.findByTokenHash(OAuth2Utils.hashToken(issued.getSessionToken()));
  }

  private GoSessionRecord persistedWithIdleExpiry(IssuedGoSession issued, Instant expiresAt) {
    GoSessionRecord persisted = reload(issued);
    assertTrue(store.touchExpiresAt(persisted.getId(), persisted.getExpiresAt(), expiresAt));
    return reload(issued);
  }
}
