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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.oauth2.OAuth2Utils;
import com.etendoerp.go.session.GoSessionRecord;

/**
 * Unit tests for {@link SupportAccessService} (ETP-5351, T3/T4): issuing, redeeming and ending
 * support accesses over in-memory stores. No database.
 */
class SupportAccessServiceTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String OPERATOR = "OPERATOR000000000000000000000001";
  private static final String OTHER_OPERATOR = "OPERATOR000000000000000000000002";
  private static final String ADMIN_ROLE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String REASON = "Ticket SUP-123: invoice numbering broken";
  private static final String APP_BASE_URL = "https://go.example.test/app/";

  private InMemorySupportAccessStore store;
  private MutableClock clock;
  private SupportAccessService service;

  @BeforeEach
  void setUp() {
    store = new InMemorySupportAccessStore();
    store.eligibleClients.add(CLIENT);
    store.operators.add(OPERATOR);
    store.operators.add(OTHER_OPERATOR);
    store.operatorNames.put(OPERATOR, "Ana Soporte");
    store.preferences.put(SupportAccessService.PREF_DEFAULT_MINUTES, "60");
    store.preferences.put(SupportAccessService.PREF_MAX_MINUTES, "480");
    store.clientNames.put(CLIENT, "Acme SL");
    InMemorySupportUserStore userStore = new InMemorySupportUserStore();
    userStore.adminRoles.put(CLIENT, ADMIN_ROLE);
    userStore.references.put(CLIENT,
        new SupportUserContext(CLIENT, null, ADMIN_ROLE, "ORG", "WH", null));
    clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
    service = new SupportAccessService(store, new SupportUserProvisioner(userStore), clock);
  }

  private IssuedSupportTicket issue() {
    return service.issue(OPERATOR, CLIENT, REASON, null, "10.0.0.1", "JUnit");
  }

  // ------------------------------------------------------------------ issue

  @Test
  void issueReturnsAUrlSafeSingleUsePassAndStoresOnlyItsHash() {
    IssuedSupportTicket issued = issue();

    assertEquals(43, issued.getTicket().length(), "256 bits in base64url without padding");
    assertTrue(issued.getTicket().matches("[A-Za-z0-9_-]+"), "no '+', '/' nor '='");
    SupportAccessRecord row = store.rows.get(issued.getAccessId());
    assertEquals(OAuth2Utils.hashToken(issued.getTicket()), row.getTicketHash());
    assertNotEquals(issued.getTicket(), row.getTicketHash());
    assertEquals(clock.instant().plusSeconds(60), row.getTicketExpiresAt());
    assertEquals(60, row.getDurationMinutes(), "the default preference applies");
    assertEquals(SupportAccessGuard.supportUserIdFor(CLIENT), row.getSupportUserId());
    assertEquals(ADMIN_ROLE, row.getRoleId());
    assertEquals(SupportAccessService.hashIp("10.0.0.1"), row.getIpHash());
    assertFalse(issued.toString().contains(issued.getTicket()), "toString must not leak it");
  }

  @Test
  void handoffUrlCarriesThePassInTheFragment() {
    assertEquals("https://go.example.test/app/support-access#t=abc_-1",
        SupportAccessService.handoffUrl("abc_-1", APP_BASE_URL));
    assertNull(SupportAccessService.handoffUrl("abc", null));
  }

  @Test
  void issueRejectsADurationOverTheConfiguredMaximum() {
    SupportAccessException e = assertThrows(SupportAccessException.class,
        () -> service.issue(OPERATOR, CLIENT, REASON, 481, null, null));
    assertEquals(SupportAccessException.CODE_DURATION_INVALID, e.getCode());
    assertTrue(store.rows.isEmpty());
  }

  @Test
  void issueAcceptsTheMaximumAndHonoursALowerConfiguredMaximum() {
    assertEquals(480, service.issue(OPERATOR, CLIENT, REASON, 480, null, null)
        .getDurationMinutes());
    store.preferences.put(SupportAccessService.PREF_MAX_MINUTES, "120");
    assertEquals(120, service.getMaxDurationMinutes());
    assertEquals(60, service.getDefaultDurationMinutes());
  }

  @Test
  void issueRejectsANonPositiveDuration() {
    assertThrows(SupportAccessException.class,
        () -> service.issue(OPERATOR, CLIENT, REASON, 0, null, null));
  }

  @Test
  void issueRequiresAReason() {
    SupportAccessException e = assertThrows(SupportAccessException.class,
        () -> service.issue(OPERATOR, CLIENT, "  short ", null, null, null));
    assertEquals(SupportAccessException.CODE_REASON_REQUIRED, e.getCode());
  }

  @Test
  void issueRequiresTheOperatorRole() {
    SupportAccessException e = assertThrows(SupportAccessException.class,
        () -> service.issue("NOT_AN_OPERATOR", CLIENT, REASON, null, null, null));
    assertEquals(SupportAccessException.CODE_OPERATOR_NOT_ALLOWED, e.getCode());
  }

  @Test
  void issueRefusesAnIneligibleTenant() {
    SupportAccessException e = assertThrows(SupportAccessException.class,
        () -> service.issue(OPERATOR, "POOL_CLIENT", REASON, null, null, null));
    assertEquals(SupportAccessException.CODE_TARGET_NOT_ELIGIBLE, e.getCode());
  }

  @Test
  void issueFailsClosedWithoutTheTechnicalAccount() {
    store.supportAccountReady = false;
    SupportAccessException e = assertThrows(SupportAccessException.class, this::issue);
    assertEquals(SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING, e.getCode());
  }

  @Test
  void aBusyTenantReportsWhoIsInsideAndUntilWhen() {
    IssuedSupportTicket first = issue();
    service.redeem(first.getTicket());
    Instant sessionEnd = clock.instant().plus(Duration.ofMinutes(60));
    store.liveSessions.put(first.getAccessId(), sessionEnd);

    SupportTenantBusyException e = assertThrows(SupportTenantBusyException.class,
        () -> service.issue(OTHER_OPERATOR, CLIENT, REASON, null, null, null));

    assertEquals(SupportTenantBusyException.CODE_TENANT_BUSY, e.getCode());
    assertEquals(first.getAccessId(), e.getAccessId());
    assertEquals(OPERATOR, e.getOperatorUserId());
    assertEquals("Ana Soporte", e.getOperatorName());
    assertEquals(sessionEnd, e.getBusyUntil());
  }

  @Test
  void anUnredeemedPassHoldsTheTenantOnlyUntilItExpires() {
    IssuedSupportTicket first = issue();
    assertThrows(SupportTenantBusyException.class, this::issue);

    clock.advance(Duration.ofSeconds(61));
    IssuedSupportTicket second = issue();

    assertEquals(SupportEndReason.TICKET_EXPIRED.getCode(),
        store.rows.get(first.getAccessId()).getEndReason());
    assertNotNull(store.rows.get(second.getAccessId()));
  }

  @Test
  void aRedeemedAccessWhoseSessionIsGoneIsClosedAsExpired() {
    IssuedSupportTicket first = issue();
    service.redeem(first.getTicket());
    store.liveSessions.put(first.getAccessId(), clock.instant().plus(Duration.ofMinutes(5)));
    clock.advance(Duration.ofMinutes(6));

    issue();

    SupportAccessRecord closed = store.rows.get(first.getAccessId());
    assertEquals(SupportEndReason.EXPIRED.getCode(), closed.getEndReason());
    assertNotNull(closed.getEndedAt());
  }

  @Test
  void losingTheInsertRaceReportsTheWinner() {
    SupportAccessRecord winner = new SupportAccessRecord();
    winner.setId("WINNER");
    winner.setOperatorUserId(OTHER_OPERATOR);
    winner.setTargetClientId(CLIENT);
    winner.setTicketHash("other-hash");
    winner.setTicketExpiresAt(clock.instant().plusSeconds(60));
    store.raceWinner = winner;

    SupportTenantBusyException e = assertThrows(SupportTenantBusyException.class, this::issue);

    assertEquals("WINNER", e.getAccessId());
  }

  // ------------------------------------------------------------------ redeem

  @Test
  void aValidPassRedeemsOnce() {
    IssuedSupportTicket issued = issue();

    SupportAccessRecord redeemed = service.redeem(issued.getTicket());

    assertEquals(issued.getAccessId(), redeemed.getId());
    assertEquals(clock.instant(), store.rows.get(issued.getAccessId()).getTicketUsedAt());
  }

  @Test
  void aReusedPassIsRefusedAsUsed() {
    IssuedSupportTicket issued = issue();
    service.redeem(issued.getTicket());

    SupportTicketException e = assertThrows(SupportTicketException.class,
        () -> service.redeem(issued.getTicket()));

    assertEquals(SupportTicketException.Reason.USED, e.getReason());
    assertEquals("support_ticket_used", e.getReason().getErrorCode());
  }

  @Test
  void anExpiredPassIsRefusedAsExpired() {
    IssuedSupportTicket issued = issue();
    clock.advance(Duration.ofSeconds(60));

    SupportTicketException e = assertThrows(SupportTicketException.class,
        () -> service.redeem(issued.getTicket()));

    assertEquals(SupportTicketException.Reason.EXPIRED, e.getReason());
    assertNull(store.rows.get(issued.getAccessId()).getTicketUsedAt());
  }

  @Test
  void aTamperedPassIsRefusedAsInvalid() {
    IssuedSupportTicket issued = issue();
    String ticket = issued.getTicket();
    String tampered = (ticket.charAt(0) == 'A' ? 'B' : 'A') + ticket.substring(1);

    SupportTicketException e = assertThrows(SupportTicketException.class,
        () -> service.redeem(tampered));

    assertEquals(SupportTicketException.Reason.INVALID, e.getReason());
    assertNull(store.rows.get(issued.getAccessId()).getTicketUsedAt(),
        "a wrong pass must not burn the real one");
  }

  @Test
  void aBlankPassIsRefusedAsInvalid() {
    SupportTicketException e = assertThrows(SupportTicketException.class,
        () -> service.redeem("  "));
    assertEquals(SupportTicketException.Reason.INVALID, e.getReason());
  }

  @Test
  void aPassOfARevokedAccessCannotBeRedeemed() {
    IssuedSupportTicket issued = issue();
    service.revoke(issued.getAccessId(), OTHER_OPERATOR);

    SupportTicketException e = assertThrows(SupportTicketException.class,
        () -> service.redeem(issued.getTicket()));

    assertEquals(SupportTicketException.Reason.EXPIRED, e.getReason());
  }

  // ------------------------------------------------------------------ end

  @Test
  void revokeClosesTheAccessRecordsWhoAndRevokesItsSessions() {
    IssuedSupportTicket issued = issue();
    service.redeem(issued.getTicket());
    store.liveSessions.put(issued.getAccessId(), clock.instant().plus(Duration.ofHours(1)));

    assertTrue(service.revoke(issued.getAccessId(), OTHER_OPERATOR));

    SupportAccessRecord row = store.rows.get(issued.getAccessId());
    assertEquals(SupportEndReason.REVOKED.getCode(), row.getEndReason());
    assertEquals(OTHER_OPERATOR, row.getEndedByUserId());
    assertTrue(store.revokedSessionsFor.contains(issued.getAccessId()));
    assertFalse(store.liveSessions.containsKey(issued.getAccessId()));
    assertNull(service.findOpenAccess(CLIENT), "the tenant is free again");
    assertFalse(service.revoke(issued.getAccessId(), OTHER_OPERATOR), "already closed");
  }

  @Test
  void closeRefusesTheRevokedReason() {
    assertThrows(IllegalArgumentException.class,
        () -> service.close("ANY", SupportEndReason.REVOKED));
  }

  @Test
  void logoutOfASupportSessionClosesItsAccess() {
    IssuedSupportTicket issued = issue();
    service.redeem(issued.getTicket());
    GoSessionRecord session = new GoSessionRecord();
    session.setSupportAccessId(issued.getAccessId());

    service.closeOnLogout(session);

    assertEquals(SupportEndReason.LOGOUT.getCode(),
        store.rows.get(issued.getAccessId()).getEndReason());
  }

  @Test
  void logoutOfAnOrdinarySessionTouchesNothing() {
    IssuedSupportTicket issued = issue();
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("ACC1");

    service.closeOnLogout(session);

    assertNull(store.rows.get(issued.getAccessId()).getEndedAt());
  }

  // ------------------------------------------------------------------ /me block

  @Test
  void describesASupportSessionWithIsoExpiry() throws Exception {
    GoSessionRecord session = new GoSessionRecord();
    session.setSupportAccessId("ACCESS");
    session.setCtxClientId(CLIENT);
    session.setAbsoluteExpiresAt(Instant.parse("2026-10-05T18:00:00.123456Z"));

    JSONObject block = service.describeSupportSession(session);

    assertEquals(CLIENT, block.getString("clientId"));
    assertEquals("Acme SL", block.getString("clientName"));
    assertEquals("2026-10-05T18:00:00Z", block.getString("expiresAt"));
  }

  @Test
  void anOrdinarySessionHasNoSupportBlock() throws Exception {
    GoSessionRecord session = new GoSessionRecord();
    session.setAccountId("ACC1");
    assertNull(service.describeSupportSession(session));
  }

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
