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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
      Account account = (Account) OBDal.getInstance().createCriteria(Account.class)
          .setMaxResults(1)
          .uniqueResult();
      assertNotNull("Test fixture must contain at least one ETGO_ACCOUNT to own the session",
          account);
      String accountId = account.getId();

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
}
