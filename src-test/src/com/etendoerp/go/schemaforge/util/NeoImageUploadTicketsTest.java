/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoImageUploadTickets} (ETP-5184).
 *
 * <p>Covers the security-relevant properties of the ticket, since the token is the only credential
 * the upload endpoint has: single use, TTL, cross-session isolation, and the store hygiene that
 * stops a loop from growing the map.
 */
class NeoImageUploadTicketsTest {

  private static final String CLIENT = "CLIENT1";
  private static final String ORG = "ORG1";
  private static final String USER = "USER1";
  private static final String PNG = "image/png";

  /** A clock the test advances by hand, so a ticket can really age past its TTL. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-09-07T10:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    void advance(Duration amount) {
      now = now.plus(amount);
    }
  }

  private MutableClock clock;
  private NeoImageUploadTickets tickets;

  private String issue() {
    return tickets.issue(CLIENT, ORG, USER, "photo.png", PNG);
  }

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    clock = new MutableClock();
    tickets = new NeoImageUploadTickets(clock);
  }

  @Nested
  @DisplayName("token")
  class TokenProperties {

    @Test
    @DisplayName("is opaque, url-safe and at least 128 bits of entropy")
    void tokenIsOpaqueAndLongEnough() {
      String token = issue();
      // 24 random bytes base64url-encoded without padding = 32 chars, i.e. 192 bits.
      assertEquals(32, token.length());
      assertTrue(token.matches("[A-Za-z0-9_-]+"),
          "token must be url-safe so it can sit in a path segment: " + token);
    }

    @Test
    @DisplayName("carries no session identifier")
    void tokenLeaksNothing() {
      String token = issue();
      assertFalse(token.contains(CLIENT));
      assertFalse(token.contains(ORG));
      assertFalse(token.contains(USER));
    }

    @Test
    @DisplayName("is never repeated")
    void tokensAreUnique() {
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < NeoImageUploadTickets.MAX_PENDING_PER_SESSION; i++) {
        assertTrue(seen.add(issue()), "issued a duplicate token");
      }
      assertEquals(NeoImageUploadTickets.MAX_PENDING_PER_SESSION, seen.size());
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("a ticket can be claimed exactly once")
    void claimIsSingleUse() {
      String token = issue();
      assertTrue(tickets.claim(token).isPresent(), "first claim must win");
      assertTrue(tickets.claim(token).isEmpty(), "a second claim must be refused");
    }

    @Test
    @DisplayName("a completed ticket cannot be claimed again — the second PUT is rejected")
    void completedTicketRefusesASecondUpload() {
      String token = issue();
      tickets.claim(token);
      tickets.complete(token, "IMAGE1");
      assertTrue(tickets.claim(token).isEmpty());
    }

    @Test
    @DisplayName("release returns a claimed ticket so a fixable rejection does not burn it")
    void releaseAllowsARetry() {
      String token = issue();
      tickets.claim(token);
      tickets.release(token);
      assertTrue(tickets.claim(token).isPresent(),
          "after release the same URL must accept a corrected upload");
    }

    @Test
    @DisplayName("release does not resurrect a completed ticket")
    void releaseDoesNotUndoCompletion() {
      String token = issue();
      tickets.claim(token);
      tickets.complete(token, "IMAGE1");
      tickets.release(token);
      assertTrue(tickets.claim(token).isEmpty());
      assertEquals(NeoImageUploadTickets.Status.COMPLETED,
          tickets.peek(token).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a completed ticket keeps its imageId readable until it expires")
    void completedTicketKeepsTheImageId() {
      String token = issue();
      tickets.claim(token);
      tickets.complete(token, "IMAGE1");
      assertEquals("IMAGE1", tickets.peek(token).orElseThrow().getImageId());
    }
  }

  @Nested
  @DisplayName("expiry")
  class Expiry {

    @Test
    @DisplayName("a ticket past its TTL can no longer be claimed")
    void expiredTicketCannotBeClaimed() {
      String token = issue();
      clock.advance(NeoImageUploadTickets.TTL.plusSeconds(1));
      assertTrue(tickets.claim(token).isEmpty());
    }

    @Test
    @DisplayName("a ticket past its TTL is invisible to peek")
    void expiredTicketIsInvisible() {
      String token = issue();
      clock.advance(NeoImageUploadTickets.TTL.plusSeconds(1));
      assertTrue(tickets.peek(token).isEmpty());
    }

    @Test
    @DisplayName("an unknown token is indistinguishable from an expired one")
    void unknownTokenLooksExactlyLikeAnExpiredOne() {
      String expired = issue();
      clock.advance(NeoImageUploadTickets.TTL.plusSeconds(1));
      // Same answer for both, which is what makes a Tomcat restart look like an expiry.
      assertEquals(tickets.peek("a-token-that-never-existed"), tickets.peek(expired));
      assertEquals(tickets.claim("a-token-that-never-existed"), tickets.claim(expired));
    }

    @Test
    @DisplayName("a null token is refused rather than throwing")
    void nullTokenIsRefused() {
      assertTrue(tickets.peek(null).isEmpty());
      assertTrue(tickets.claim(null).isEmpty());
    }

    @Test
    @DisplayName("expiresAt is TTL after issue")
    void expiresAtIsTtlAfterIssue() {
      String token = issue();
      assertEquals(clock.instant().plus(NeoImageUploadTickets.TTL),
          tickets.peek(token).orElseThrow().getExpiresAt());
    }
  }

  @Nested
  @DisplayName("session binding")
  class SessionBinding {

    @Test
    @DisplayName("a ticket belongs only to the client/org/user it was issued to")
    void ticketIsBoundToItsSession() {
      NeoImageUploadTickets.PendingUpload ticket = tickets.peek(issue()).orElseThrow();
      assertTrue(ticket.belongsTo(CLIENT, ORG, USER));
      assertFalse(ticket.belongsTo("OTHERCLIENT", ORG, USER), "another client must not match");
      assertFalse(ticket.belongsTo(CLIENT, "OTHERORG", USER), "another org must not match");
      assertFalse(ticket.belongsTo(CLIENT, ORG, "OTHERUSER"), "another user must not match");
    }

    @Test
    @DisplayName("the ticket records the scope the image row will be created in")
    void ticketCarriesItsScope() {
      NeoImageUploadTickets.PendingUpload ticket = tickets.peek(issue()).orElseThrow();
      assertEquals(CLIENT, ticket.getClientId());
      assertEquals(ORG, ticket.getOrganizationId());
      assertEquals(USER, ticket.getUserId());
      assertEquals("photo.png", ticket.getName());
      assertEquals(PNG, ticket.getMimeType());
    }
  }

  @Nested
  @DisplayName("store hygiene")
  class StoreHygiene {

    @Test
    @DisplayName("the per-session pending cap is enforced, so a loop cannot grow the map")
    void perSessionCapIsEnforced() {
      for (int i = 0; i < NeoImageUploadTickets.MAX_PENDING_PER_SESSION; i++) {
        issue();
      }
      NeoImageUploadTickets.TooManyPendingTicketsException thrown = assertThrows(
          NeoImageUploadTickets.TooManyPendingTicketsException.class, this::issueOneMore);
      assertTrue(thrown.getMessage().contains("maximum"),
          "the refusal must say what the limit is: " + thrown.getMessage());
      assertEquals(NeoImageUploadTickets.MAX_PENDING_PER_SESSION, tickets.size());
    }

    private void issueOneMore() {
      tickets.issue(CLIENT, ORG, USER, "photo.png", PNG);
    }

    @Test
    @DisplayName("the cap is per session, not global")
    void capIsPerSession() {
      for (int i = 0; i < NeoImageUploadTickets.MAX_PENDING_PER_SESSION; i++) {
        issue();
      }
      // A different user must not be blocked by this session's backlog.
      String other = tickets.issue(CLIENT, ORG, "USER2", "photo.png", PNG);
      assertNotEquals(null, other);
    }

    @Test
    @DisplayName("a completed ticket does not count against the cap")
    void completedTicketsFreeUpTheCap() {
      for (int i = 0; i < NeoImageUploadTickets.MAX_PENDING_PER_SESSION; i++) {
        String token = issue();
        tickets.claim(token);
        tickets.complete(token, "IMAGE" + i);
      }
      assertNotEquals(null, issue(), "completed tickets must not block new ones");
    }

    @Test
    @DisplayName("expired entries are swept, so the map does not grow unbounded")
    void expiredEntriesAreSwept() {
      for (int i = 0; i < NeoImageUploadTickets.MAX_PENDING_PER_SESSION; i++) {
        issue();
      }
      assertEquals(NeoImageUploadTickets.MAX_PENDING_PER_SESSION, tickets.size());
      clock.advance(NeoImageUploadTickets.TTL.plusSeconds(1));
      // Issuing sweeps: the cap would otherwise refuse this call.
      issue();
      assertEquals(1, tickets.size(), "every expired entry should have been dropped");
    }

    @Test
    @DisplayName("sweep reports how many entries it dropped and leaves live ones alone")
    void sweepIsSelective() {
      issue();
      clock.advance(NeoImageUploadTickets.TTL.dividedBy(2));
      issue();
      clock.advance(NeoImageUploadTickets.TTL.dividedBy(2).plusSeconds(1));
      assertEquals(1, tickets.sweep(clock.instant()), "only the older ticket has expired");
      assertEquals(1, tickets.size());
    }
  }
}
