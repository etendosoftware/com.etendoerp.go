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
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.etendoerp.go.oauth2.OAuth2Utils;

/**
 * Red-first unit tests for {@link GoSessionService} (ETP-4575) using an in-memory {@link GoSessionStore}.
 *
 * <p>Covers rotation, refresh replay, logout invalidation and expiry acceptance criteria without a
 * database.
 */
public class GoSessionServiceTest {

  private static final String ACCOUNT_ID = "ACC0000000000000000000000000001";

  private InMemoryGoSessionStore store;
  private GoSessionService service;

  @Before
  public void setUp() {
    store = new InMemoryGoSessionStore();
    service = new GoSessionService(store);
  }

  @Test
  public void createStoresHashNotPlaintextAndReturnsRawToken() {
    IssuedGoSession issued = service.create(ACCOUNT_ID, "password", "UA", "iphash");

    assertNotNull(issued.getSessionToken());
    assertNotNull(issued.getRefreshToken());
    assertNotNull(issued.getCsrfToken());
    // The persisted record holds only the hash — never the plaintext token.
    assertNotEquals(issued.getSessionToken(), issued.getRecord().getSessionTokenHash());
    assertEquals(OAuth2Utils.hashToken(issued.getSessionToken()),
        issued.getRecord().getSessionTokenHash());
    assertEquals(OAuth2Utils.hashToken(issued.getRefreshToken()),
        issued.getRecord().getRefreshTokenHash());
  }

  @Test
  public void resolveReturnsActiveSession() {
    IssuedGoSession issued = service.create(ACCOUNT_ID, "password", null, null);

    GoSessionRecord resolved = service.resolve(issued.getSessionToken());

    assertNotNull(resolved);
    assertEquals(issued.getRecord().getId(), resolved.getId());
  }

  @Test
  public void resolveRejectsUnknownToken() {
    service.create(ACCOUNT_ID, "password", null, null);

    assertNull(service.resolve("not-a-real-token"));
  }

  @Test
  public void resolveRejectsRevokedSession() {
    IssuedGoSession issued = service.create(ACCOUNT_ID, "password", null, null);

    service.revoke(issued.getRecord());

    assertNull(service.resolve(issued.getSessionToken()));
  }

  @Test
  public void resolveRejectsIdleExpiredSession() {
    IssuedGoSession issued = service.create(ACCOUNT_ID, "password", null, null);
    issued.getRecord().setExpiresAt(Instant.now().minusSeconds(1));

    assertNull(service.resolve(issued.getSessionToken()));
  }

  @Test
  public void resolveRejectsAbsoluteExpiredSession() {
    IssuedGoSession issued = service.create(ACCOUNT_ID, "password", null, null);
    issued.getRecord().setAbsoluteExpiresAt(Instant.now().minusSeconds(1));

    assertNull(service.resolve(issued.getSessionToken()));
  }

  @Test
  public void rotateInvalidatesOldTokenAndIssuesNew() {
    IssuedGoSession original = service.create(ACCOUNT_ID, "password", null, null);

    IssuedGoSession rotated = service.rotate(original.getRecord());

    assertNull("old session token must stop working", service.resolve(original.getSessionToken()));
    assertNotNull("new session token must work", service.resolve(rotated.getSessionToken()));
    assertTrue(original.getRecord().isRevoked());
    assertEquals(original.getRecord().getId(), rotated.getRecord().getRotatedFromId());
    // Rotation must not extend the absolute cap.
    assertEquals(original.getRecord().getAbsoluteExpiresAt(),
        rotated.getRecord().getAbsoluteExpiresAt());
  }

  @Test
  public void refreshRotatesSession() {
    IssuedGoSession original = service.create(ACCOUNT_ID, "password", null, null);

    IssuedGoSession refreshed = service.refresh(original.getRefreshToken());

    assertNotNull(refreshed);
    assertNull(service.resolve(original.getSessionToken()));
    assertNotNull(service.resolve(refreshed.getSessionToken()));
  }

  @Test
  public void refreshReplayRevokesWholeFamily() {
    IssuedGoSession original = service.create(ACCOUNT_ID, "password", null, null);
    IssuedGoSession refreshed = service.refresh(original.getRefreshToken());
    assertNotNull(service.resolve(refreshed.getSessionToken()));

    // Replay the already-consumed original refresh token.
    IssuedGoSession replay = service.refresh(original.getRefreshToken());

    assertNull("replay must be rejected", replay);
    assertNull("the active descendant session must be revoked on replay",
        service.resolve(refreshed.getSessionToken()));
  }

  @Test
  public void refreshWithUnknownTokenReturnsNull() {
    service.create(ACCOUNT_ID, "password", null, null);

    assertNull(service.refresh("unknown-refresh"));
  }

  @Test
  public void logoutRevokesSession() {
    IssuedGoSession issued = service.create(ACCOUNT_ID, "password", null, null);

    service.revoke(issued.getRecord());

    assertNull(service.resolve(issued.getSessionToken()));
  }

  /**
   * Minimal in-memory {@link GoSessionStore} for unit tests.
   */
  private static final class InMemoryGoSessionStore implements GoSessionStore {

    private final List<GoSessionRecord> records = new ArrayList<>();

    @Override
    public void save(GoSessionRecord sessionRecord) {
      records.add(sessionRecord);
    }

    @Override
    public void update(GoSessionRecord sessionRecord) {
      // Records are held by reference, so mutations are already visible; no-op for the fake.
    }

    @Override
    public GoSessionRecord findByTokenHash(String sessionTokenHash) {
      return records.stream()
          .filter(r -> sessionTokenHash != null && sessionTokenHash.equals(r.getSessionTokenHash()))
          .findFirst()
          .orElse(null);
    }

    @Override
    public GoSessionRecord findByRefreshTokenHash(String refreshTokenHash) {
      return records.stream()
          .filter(r -> refreshTokenHash != null && refreshTokenHash.equals(r.getRefreshTokenHash()))
          .findFirst()
          .orElse(null);
    }

    @Override
    public GoSessionRecord findByRotatedFromId(String sessionId) {
      return records.stream()
          .filter(r -> sessionId != null && sessionId.equals(r.getRotatedFromId()))
          .findFirst()
          .orElse(null);
    }
  }
}
