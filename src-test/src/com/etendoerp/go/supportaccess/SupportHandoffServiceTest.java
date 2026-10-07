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
package com.etendoerp.go.supportaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.etendoerp.go.session.GoSessionRecord;
import com.etendoerp.go.session.GoSessionService;
import com.etendoerp.go.session.IssuedGoSession;

/**
 * Unit tests for {@link SupportHandoffService} (ETP-5351, T4): pass to session.
 */
class SupportHandoffServiceTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String TICKET = "pass-value";

  private final SupportAccessService accessService = mock(SupportAccessService.class);
  private final SupportUserProvisioner provisioner = mock(SupportUserProvisioner.class);
  private final GoSessionService sessionService = mock(GoSessionService.class);
  private final SupportHandoffService handoff =
      new SupportHandoffService(accessService, provisioner, sessionService);
  private SupportAccessRecord access;
  private SupportUserContext supportUser;

  @BeforeEach
  void setUp() {
    access = new SupportAccessRecord();
    access.setId("ACCESS1");
    access.setTargetClientId(CLIENT);
    access.setRoleId("ROLE1");
    access.setDurationMinutes(90);
    supportUser = new SupportUserContext(CLIENT, SupportAccessGuard.supportUserIdFor(CLIENT),
        "ROLE1", "ORG1", "WH1", null);
    when(accessService.redeem(TICKET)).thenReturn(access);
    when(accessService.isSupportAccountReady()).thenReturn(true);
    when(provisioner.findOrCreate(CLIENT)).thenReturn(supportUser);
  }

  @Test
  void opensASupportSessionInsideTheTenantForTheRequestedDuration() {
    GoSessionRecord created = new GoSessionRecord();
    created.setId("SESSION1");
    IssuedGoSession issued = new IssuedGoSession("tok", "ref", "csrf", created);
    ArgumentCaptor<GoSessionRecord> environment = ArgumentCaptor.forClass(GoSessionRecord.class);
    when(sessionService.create(eq(SupportAccessGuard.SUPPORT_ACCOUNT_ID),
        eq(SupportAccessGuard.AUTH_METHOD_SUPPORT), eq("UA"), anyString(),
        eq(Duration.ofMinutes(90)), environment.capture())).thenReturn(issued);

    SupportHandoffService.SupportHandoff result = handoff.open(TICKET, "UA", "10.0.0.9");

    assertSame(issued, result.getSession());
    assertSame(access, result.getAccess());
    GoSessionRecord env = environment.getValue();
    assertEquals(supportUser.getUserId(), env.getUserId());
    assertEquals("ROLE1", env.getRoleId());
    assertEquals(CLIENT, env.getCtxClientId());
    assertEquals("ORG1", env.getCtxOrgId());
    assertEquals("WH1", env.getWarehouseId());
    assertEquals("ACCESS1", env.getSupportAccessId());
    verify(accessService).markStarted("ACCESS1", "SESSION1");
  }

  @Test
  void aRefusedPassOpensNoSession() {
    when(accessService.redeem("bad"))
        .thenThrow(new SupportTicketException(SupportTicketException.Reason.USED));

    assertThrows(SupportTicketException.class, () -> handoff.open("bad", null, null));

    verify(sessionService, never()).create(anyString(), anyString(), any(), any(), any(), any());
  }

  @Test
  void aMissingTechnicalAccountOpensNoSession() {
    when(accessService.isSupportAccountReady()).thenReturn(false);

    SupportAccessException e = assertThrows(SupportAccessException.class,
        () -> handoff.open(TICKET, null, null));

    assertEquals(SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING, e.getCode());
    verify(sessionService, never()).create(anyString(), anyString(), any(), any(), any(), any());
  }

  @Test
  void theSessionTheBrowserStillHeldIsRevokedNotReused() {
    GoSessionRecord previous = new GoSessionRecord();
    previous.setSupportAccessId("OLD_ACCESS");
    when(sessionService.resolve("old-cookie")).thenReturn(previous);

    handoff.retireBrowserSession("old-cookie");

    verify(sessionService).revoke(previous);
    verify(accessService).closeOnLogout(previous);
  }

  @Test
  void aDeadBrowserSessionIsIgnored() {
    handoff.retireBrowserSession(null);
    verify(sessionService, never()).revoke(any());
  }
}
