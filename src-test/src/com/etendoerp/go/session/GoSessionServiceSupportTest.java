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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * ETP-5351 — {@link GoSessionService} for support sessions: an absolute lifetime of its own, the
 * environment set at creation, and a support mark that survives rotation.
 */
public class GoSessionServiceSupportTest {

  private final GoSessionStore store = mock(GoSessionStore.class);
  private final GoSessionService service = new GoSessionService(store);

  private static GoSessionRecord supportEnvironment() {
    GoSessionRecord environment = new GoSessionRecord();
    environment.setUserId("U1");
    environment.setRoleId("R1");
    environment.setCtxClientId("C1");
    environment.setCtxOrgId("O1");
    environment.setWarehouseId("W1");
    environment.setSupportAccessId("ACCESS1");
    return environment;
  }

  @Test
  public void createWithLifetimeCapsTheSessionAndCarriesTheEnvironment() {
    Instant before = Instant.now();

    IssuedGoSession issued = service.create("SUPPORT_ACC", "support", "UA", "ip",
        Duration.ofMinutes(90), supportEnvironment());

    GoSessionRecord saved = issued.getRecord();
    verify(store).save(saved);
    assertEquals("support", saved.getAuthMethod());
    assertEquals("ACCESS1", saved.getSupportAccessId());
    assertEquals("U1", saved.getUserId());
    assertEquals("C1", saved.getCtxClientId());
    assertEquals("O1", saved.getCtxOrgId());
    Instant absolute = saved.getAbsoluteExpiresAt();
    assertFalse(absolute.isBefore(before.plus(Duration.ofMinutes(90))));
    assertTrue(absolute.isBefore(before.plus(Duration.ofMinutes(91))));
    assertTrue("idle window is 30 minutes",
        saved.getExpiresAt().isBefore(before.plus(Duration.ofMinutes(31))));
  }

  @Test
  public void aLifetimeShorterThanTheIdleWindowBoundsTheIdleExpiry() {
    IssuedGoSession issued = service.create("SUPPORT_ACC", "support", null, null,
        Duration.ofMinutes(10), supportEnvironment());

    assertEquals(issued.getRecord().getAbsoluteExpiresAt(), issued.getRecord().getExpiresAt());
  }

  @Test(expected = IllegalArgumentException.class)
  public void aNonPositiveLifetimeIsRejected() {
    service.create("SUPPORT_ACC", "support", null, null, Duration.ZERO, null);
  }

  @Test
  public void theDefaultCreateLeavesNoSupportMark() {
    IssuedGoSession issued = service.create("ACC1", "password", null, null);
    assertNull(issued.getRecord().getSupportAccessId());
  }

  @Test
  public void rotationKeepsTheSupportMarkAndTheAbsoluteCap() {
    GoSessionRecord current = supportEnvironment();
    current.setId("S1");
    current.setAccountId("SUPPORT_ACC");
    current.setAuthMethod("support");
    current.setAbsoluteExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
    when(store.rotateAtomically(any(), any())).thenReturn(true);

    IssuedGoSession rotated = service.rotate(current);

    ArgumentCaptor<GoSessionRecord> successor = ArgumentCaptor.forClass(GoSessionRecord.class);
    verify(store).rotateAtomically(any(), successor.capture());
    GoSessionRecord next = successor.getValue();
    assertEquals("ACCESS1", next.getSupportAccessId());
    assertEquals("ACCESS1", rotated.getRecord().getSupportAccessId());
    assertEquals("support", next.getAuthMethod());
    assertEquals(current.getAbsoluteExpiresAt(), next.getAbsoluteExpiresAt());
    assertEquals("the idle expiry never passes the absolute cap",
        current.getAbsoluteExpiresAt(), next.getExpiresAt());
  }
}
