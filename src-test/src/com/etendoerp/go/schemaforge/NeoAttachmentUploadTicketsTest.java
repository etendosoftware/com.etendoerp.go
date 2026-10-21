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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.PendingUpload;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Status;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Subject;

/**
 * The MCP attachment upload link store: the token is the only credential of the unauthenticated
 * PUT, so single use, TTL, per-session cap and the binding of the link to its session and document
 * are what protect it.
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets
 */
class NeoAttachmentUploadTicketsTest {

  private static final String CLIENT = "CLIENT1";
  private static final String ORG = "ORG1";
  private static final String USER = "USER1";
  private static final String ROLE = "ROLE1";

  /** A clock the test advances by hand, so a link can really age past its TTL. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-21T10:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    void advance(Duration amount) {
      now = now.plus(amount);
    }
  }

  private MutableClock clock;
  private NeoAttachmentUploadTickets tickets;

  private static Session session(String user) {
    return new Session(CLIENT, ORG, user, ROLE, null);
  }

  private static Subject subject(String fileName, boolean main) {
    return new Subject("SPEC1", "ENTITY1", "RECORD1", fileName, main);
  }

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    tickets = new NeoAttachmentUploadTickets(clock);
  }

  @Test
  void theLinkIsBoundToTheSessionAndTheDocumentItWasIssuedFor() {
    String token = tickets.issue(session(USER), subject("factura.pdf", true));

    PendingUpload ticket = tickets.peek(token).orElseThrow();

    assertEquals(USER, ticket.getSession().getUserId());
    assertEquals(ROLE, ticket.getSession().getRoleId());
    assertEquals("SPEC1", ticket.getSubject().getSpecId());
    assertEquals("RECORD1", ticket.getSubject().getRecordId());
    assertEquals("factura.pdf", ticket.getSubject().getFileName());
    assertTrue(ticket.getSubject().isMarkAsMain());
    assertEquals(Status.PENDING, ticket.getStatus());
  }

  @Test
  void aFileNameCarryingAPathIsReducedToItsLastSegment() {
    assertEquals("passwd", subject("../../etc/passwd", false).getFileName());
    assertEquals("a.pdf", subject("C:\\temp\\a.pdf", false).getFileName());
    assertEquals("attachment", subject("   ", false).getFileName());
  }

  @Test
  void theTokenIsOpaqueUrlSafeAndNeverRepeated() {
    String first = tickets.issue(session(USER), subject("a.pdf", false));
    String second = tickets.issue(session(USER), subject("a.pdf", false));

    assertEquals(32, first.length());
    assertTrue(first.matches("[A-Za-z0-9_-]+"), first);
    assertFalse(first.contains(USER));
    assertNotEquals(first, second);
  }

  @Test
  void aLinkWorksExactlyOnce() {
    String token = tickets.issue(session(USER), subject("a.pdf", false));

    assertTrue(tickets.claim(token).isPresent());
    tickets.complete(token, "ATT1");

    assertTrue(tickets.claim(token).isEmpty(), "a used link must not be claimable again");
    assertEquals("ATT1", tickets.peek(token).orElseThrow().getAttachmentId());
  }

  @Test
  void twoSimultaneousClaimsCannotBothWin() {
    String token = tickets.issue(session(USER), subject("a.pdf", false));

    assertTrue(tickets.claim(token).isPresent());
    assertTrue(tickets.claim(token).isEmpty());
  }

  @Test
  void aRejectedPayloadHandsTheLinkBackSoTheRightFileCanBeSent() {
    String token = tickets.issue(session(USER), subject("a.pdf", false));
    tickets.claim(token);

    tickets.release(token);

    assertEquals(Status.PENDING, tickets.peek(token).orElseThrow().getStatus());
    assertTrue(tickets.claim(token).isPresent());
  }

  @Test
  void aLinkPastItsTtlIsIndistinguishableFromAnUnknownOne() {
    String token = tickets.issue(session(USER), subject("a.pdf", false));

    clock.advance(NeoAttachmentUploadTickets.TTL.plusSeconds(1));

    assertTrue(tickets.claim(token).isEmpty());
    assertTrue(tickets.peek(token).isEmpty());
    assertTrue(tickets.claim("never-issued").isEmpty());
    assertTrue(tickets.claim(null).isEmpty());
  }

  @Test
  void aSessionCannotHoldMoreThanTheCapAndAnotherSessionIsNotAffected() {
    for (int i = 0; i < NeoAttachmentUploadTickets.MAX_PENDING_PER_SESSION; i++) {
      tickets.issue(session(USER), subject("a.pdf", false));
    }

    assertThrows(NeoAttachmentUploadTickets.TooManyPendingTicketsException.class,
        () -> tickets.issue(session(USER), subject("a.pdf", false)));
    assertTrue(tickets.issue(session("OTHER"), subject("a.pdf", false)).length() > 0);
  }

  @Test
  void aCompletedLinkNoLongerCountsAgainstTheCap() {
    String first = tickets.issue(session(USER), subject("a.pdf", false));
    for (int i = 1; i < NeoAttachmentUploadTickets.MAX_PENDING_PER_SESSION; i++) {
      tickets.issue(session(USER), subject("a.pdf", false));
    }
    tickets.claim(first);
    tickets.complete(first, "ATT1");

    assertTrue(tickets.issue(session(USER), subject("a.pdf", false)).length() > 0);
  }

  @Test
  void expiredLinksAreSweptSoTheMapDoesNotGrowForever() {
    tickets.issue(session(USER), subject("a.pdf", false));
    clock.advance(NeoAttachmentUploadTickets.TTL.plusSeconds(1));

    tickets.issue(session(USER), subject("b.pdf", false));

    assertEquals(1, tickets.size());
  }

  @Test
  void completingWithoutAnIdStillSpendsTheLink() {
    String token = tickets.issue(session(USER), subject("a.pdf", false));
    tickets.claim(token);

    tickets.complete(token, null);

    assertNull(tickets.peek(token).orElseThrow().getAttachmentId());
    assertEquals(Status.COMPLETED, tickets.peek(token).orElseThrow().getStatus());
  }
}
