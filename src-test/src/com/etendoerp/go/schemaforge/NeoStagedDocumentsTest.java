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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.NeoStagedDocuments.Staged;

/**
 * The documents staged for extraction: bound to the session that reserved them, gone after 60
 * minutes (bytes included), never visible to another session, and carrying the cached extraction.
 *
 * @covers com.etendoerp.go.schemaforge.NeoStagedDocuments
 */
@DisplayName("ETP-5715 — NeoStagedDocuments")
class NeoStagedDocumentsTest {

  private static final String CLIENT = "CLIENT1";
  private static final String ORG = "ORG1";
  private static final String USER = "USER1";

  /** A clock the test advances by hand, so a document can really age past its TTL. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-22T10:00:00Z");

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

  @TempDir
  Path root;

  private MutableClock clock;
  private NeoStagedDocuments staged;

  private static Session session(String user) {
    return new Session(CLIENT, ORG, user, "ROLE1", null);
  }

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    staged = new NeoStagedDocuments(clock, root);
  }

  private String reserveAndStore(Session session, String content) throws IOException {
    String id = staged.reserve(session, "purchase-invoice", "factura.pdf");
    Path file = staged.directoryFor(id).resolve("factura.pdf");
    Files.writeString(file, content);
    assertTrue(staged.markStored(id, file));
    return id;
  }

  private static long filesUnder(Path directory) throws IOException {
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile).count();
    }
  }

  @Test
  @DisplayName("a reserved document is found by its session with its spec and file name, not yet stored")
  void reserveAndFind() {
    String id = staged.reserve(session(USER), "purchase-invoice", "factura.pdf");

    Staged found = staged.find(id, session(USER)).orElseThrow();

    assertEquals(id, found.getId());
    assertEquals("purchase-invoice", found.getSpecName());
    assertEquals("factura.pdf", found.getFileName());
    assertFalse(found.isStored());
    assertNull(found.getFile());
    assertEquals(Instant.parse("2026-10-22T10:10:00Z"), found.getExpiresAt(),
        "a reservation lives as long as its upload link");
  }

  @Test
  @DisplayName("ids are unguessable: distinct and 128 bits of randomness")
  void idsAreUnguessable() {
    String a = staged.reserve(session(USER), "s", "a.pdf");
    String b = staged.reserve(session(USER), "s", "a.pdf");

    assertNotEquals(a, b);
    assertTrue(a.length() >= 22, a);
  }

  @Test
  @DisplayName("another user, organization or client finds nothing: the same answer as an unknown id")
  void otherSessionsFindNothing() {
    String id = staged.reserve(session(USER), "s", "a.pdf");

    assertTrue(staged.find(id, session("OTHER")).isEmpty());
    assertTrue(staged.find(id, new Session("CLIENT2", ORG, USER, "ROLE1", null)).isEmpty());
    assertTrue(staged.find(id, new Session(CLIENT, "ORG2", USER, "ROLE1", null)).isEmpty());
    assertTrue(staged.find("unknown", session(USER)).isEmpty());
    assertTrue(staged.find(null, session(USER)).isEmpty());
    assertTrue(staged.find(id, session(USER)).isPresent());
  }

  @Test
  @DisplayName("the bytes are stored under the document's own directory and flagged as stored")
  void storedBytes() throws IOException {
    String id = reserveAndStore(session(USER), "%PDF-1.4");

    Staged found = staged.find(id, session(USER)).orElseThrow();

    assertTrue(found.isStored());
    assertEquals("%PDF-1.4", Files.readString(found.getFile()));
    assertTrue(found.getFile().startsWith(root.resolve(id)));
  }

  @Test
  @DisplayName("after 60 minutes the document is gone and so are its bytes")
  void expiresAfterSixtyMinutes() throws IOException {
    String id = reserveAndStore(session(USER), "%PDF-1.4");
    clock.advance(Duration.ofMinutes(59));
    assertTrue(staged.find(id, session(USER)).isPresent(), "still there at 59 minutes");
    assertEquals(1, filesUnder(root));

    clock.advance(Duration.ofMinutes(2));

    assertTrue(staged.find(id, session(USER)).isEmpty());
    assertEquals(0, filesUnder(root), "the file is deleted with the document");
    assertFalse(Files.exists(root.resolve(id)), "and its directory");
    assertEquals(0, staged.size());
  }

  @Test
  @DisplayName("every reservation sweeps what has expired")
  void reservationSweeps() throws IOException {
    reserveAndStore(session(USER), "a");
    clock.advance(NeoStagedDocuments.TTL.plusSeconds(1));

    staged.reserve(session(USER), "s", "b.pdf");

    assertEquals(1, staged.size());
    assertEquals(0, filesUnder(root));
  }

  @Test
  @DisplayName("bytes that arrive for an expired or unknown document are deleted, not kept")
  void lateBytesAreDeleted() throws IOException {
    String id = staged.reserve(session(USER), "s", "a.pdf");
    Path file = staged.directoryFor(id).resolve("a.pdf");
    Files.writeString(file, "late");
    clock.advance(NeoStagedDocuments.TTL.plusSeconds(1));

    assertFalse(staged.markStored(id, file));
    assertFalse(Files.exists(file));
    assertFalse(staged.markStored("unknown", file));
  }

  @Test
  @DisplayName("discarding removes the document and its file; discarding twice or unknown is harmless")
  void discard() throws IOException {
    String id = reserveAndStore(session(USER), "a");

    staged.discard(id);
    staged.discard(id);
    staged.discard(null);

    assertTrue(staged.find(id, session(USER)).isEmpty());
    assertEquals(0, filesUnder(root));
  }

  @Test
  @DisplayName("a session can hold MAX_PER_SESSION live documents; another session is unaffected")
  void perSessionCap() {
    for (int i = 0; i < NeoStagedDocuments.MAX_PER_SESSION; i++) {
      staged.reserve(session(USER), "s", "a.pdf");
    }

    assertThrows(NeoStagedDocuments.TooManyStagedDocumentsException.class,
        () -> staged.reserve(session(USER), "s", "a.pdf"));
    assertTrue(staged.reserve(session("OTHER"), "s", "a.pdf").length() > 0);
  }

  @Test
  @DisplayName("an expired document no longer counts against the cap")
  void capFreesWithExpiry() {
    for (int i = 0; i < NeoStagedDocuments.MAX_PER_SESSION; i++) {
      staged.reserve(session(USER), "s", "a.pdf");
    }
    clock.advance(NeoStagedDocuments.TTL.plusSeconds(1));

    assertTrue(staged.reserve(session(USER), "s", "a.pdf").length() > 0);
    assertEquals(1, staged.size());
  }

  @Test
  @DisplayName("the extraction is cached on the document: a copy, empty or not")
  void cachedExtraction() throws JSONException {
    Staged document = staged.find(staged.reserve(session(USER), "s", "a.pdf"), session(USER))
        .orElseThrow();
    assertFalse(document.hasExtractionResult());
    assertNull(document.getExtraction());

    document.cacheExtraction(new JSONObject().put("vendor_name", "ACME"));

    assertTrue(document.hasExtractionResult());
    assertFalse(document.isNothingExtracted());
    JSONObject first = document.getExtraction();
    first.put("vendor_name", "tampered");
    assertEquals("ACME", document.getExtraction().getString("vendor_name"));
  }

  @Test
  @DisplayName("a document that yielded nothing remembers it, so a retry does not pay again")
  void cachedNothing() throws JSONException {
    Staged document = staged.find(staged.reserve(session(USER), "s", "a.pdf"), session(USER))
        .orElseThrow();

    document.cacheNothingExtracted();

    assertTrue(document.hasExtractionResult());
    assertTrue(document.isNothingExtracted());
    assertNull(document.getExtraction());
  }

  @Test
  @DisplayName("what a previous run left behind past the TTL is purged at startup; fresh files are kept")
  void purgesLeftoversAtStartup() throws IOException {
    Path old = Files.createDirectories(root.resolve("old"));
    Files.writeString(old.resolve("a.pdf"), "x");
    Files.setLastModifiedTime(old, FileTime.from(clock.instant().minus(Duration.ofHours(3))));
    Path fresh = Files.createDirectories(root.resolve("fresh"));
    Files.writeString(fresh.resolve("b.pdf"), "y");
    Files.setLastModifiedTime(fresh, FileTime.from(clock.instant().minus(Duration.ofMinutes(5))));

    NeoStagedDocuments restarted = new NeoStagedDocuments(clock, root);
    assertTrue(Files.exists(old), "constructing has no side effect: the container starts it");
    restarted.start();
    restarted.shutdown();

    assertFalse(Files.exists(old));
    assertTrue(Files.exists(fresh.resolve("b.pdf")));
  }

  @Test
  @DisplayName("the maintenance removes expired documents and old leftovers although nobody asks for anything")
  void maintenanceCleansWithoutUse() throws IOException {
    String id = reserveAndStore(session(USER), "a");
    Path stray = Files.createDirectories(root.resolve("stray"));
    Files.writeString(stray.resolve("x.pdf"), "x");
    Files.setLastModifiedTime(stray, FileTime.from(clock.instant().minus(Duration.ofHours(2))));
    clock.advance(NeoStagedDocuments.TTL.plusSeconds(1));

    staged.runMaintenance();

    assertEquals(0, staged.size());
    assertFalse(Files.exists(root.resolve(id)));
    assertFalse(Files.exists(stray));
  }

  @Test
  @DisplayName("storing a file also removes what expired meanwhile")
  void markStoredSweeps() throws IOException {
    String old = reserveAndStore(session(USER), "a");
    clock.advance(NeoStagedDocuments.TTL.plusSeconds(1));

    reserveAndStore(session("OTHER"), "b");

    assertFalse(Files.exists(root.resolve(old)));
    assertEquals(1, staged.size());
  }

  @Test
  @DisplayName("a document is usable once: consumed is a flag and each document has its own lock")
  void consumedAndLock() {
    Staged one = staged.find(staged.reserve(session(USER), "s", "a.pdf"), session(USER)).orElseThrow();
    Staged two = staged.find(staged.reserve(session(USER), "s", "b.pdf"), session(USER)).orElseThrow();
    assertFalse(one.isConsumed());

    one.markConsumed();

    assertTrue(one.isConsumed());
    assertFalse(two.isConsumed());
    assertTrue(one.getLock() != two.getLock());
  }

  // ── lifecycle: one timer, stopped on destroy, surviving a failing tick ─────────────────────

  private static long timers() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> NeoStagedDocuments.MAINTENANCE_THREAD.equals(t.getName()) && t.isAlive())
        .count();
  }

  private static void awaitTimers(long expected) throws InterruptedException {
    for (int i = 0; i < 100 && timers() != expected; i++) {
      Thread.sleep(20);
    }
  }

  @Test
  @DisplayName("constructing starts nothing; start is idempotent and shutdown stops the one timer")
  void oneTimerStoppedOnShutdown() throws InterruptedException {
    awaitTimers(0);
    NeoStagedDocuments first = new NeoStagedDocuments(clock, root);
    NeoStagedDocuments second = new NeoStagedDocuments();
    assertEquals(0, timers(), "constructors (also the one Weld's proxy runs) start no timer");
    assertFalse(first.isRunning());

    first.start();
    first.start();
    assertTrue(first.isRunning());
    awaitTimers(1);
    assertEquals(1, timers());
    second.shutdown();
    assertEquals(1, timers(), "an instance that never started stops nothing");

    first.shutdown();
    assertFalse(first.isRunning());
    awaitTimers(0);
    assertEquals(0, timers());
    first.shutdown();
  }

  @Test
  @DisplayName("a tick that throws is logged and swallowed, so the periodic task is not cancelled")
  void tickSurvivesAnException() {
    int[] calls = new int[1];
    NeoStagedDocuments failing = new NeoStagedDocuments(clock, root) {
      @Override
      public void runMaintenance() {
        calls[0]++;
        throw new java.io.UncheckedIOException(new IOException("disk"));
      }
    };

    failing.tickSafely();
    failing.tickSafely();

    assertEquals(2, calls[0]);
  }

  // ── caps ──────────────────────────────────────────────────────────────────

  private static Session session(String client, String user) {
    return new Session(client, ORG, user, "ROLE1", null);
  }

  private void storedBy(Session who) throws IOException {
    String id = staged.reserve(who, "s", "a.pdf");
    Path file = staged.directoryFor(id).resolve("a.pdf");
    Files.writeString(file, "x");
    assertTrue(staged.markStored(id, file));
  }

  @Test
  @DisplayName("a reservation that never gets its PDF is gone after the link's 10 minutes; a stored file lives 60 from arrival")
  void reservationVersusStoredLifetime() throws IOException {
    String reserved = staged.reserve(session(USER), "s", "a.pdf");
    clock.advance(Duration.ofMinutes(9));
    String late = staged.reserve(session("OTHER"), "s", "b.pdf");
    Path file = staged.directoryFor(late).resolve("b.pdf");
    Files.writeString(file, "x");
    staged.markStored(late, file);
    clock.advance(Duration.ofMinutes(2));

    assertTrue(staged.find(reserved, session(USER)).isEmpty(), "10 minutes, unstored");
    clock.advance(Duration.ofMinutes(55));
    assertTrue(staged.find(late, session("OTHER")).isPresent(), "60 minutes counted from the PDF");
    clock.advance(Duration.ofMinutes(5));
    assertTrue(staged.find(late, session("OTHER")).isEmpty());
  }

  @Test
  @DisplayName("reservations hold no disk: many of them do not block another company")
  void reservationsDoNotCountTowardsTheGlobalCap() {
    for (int c = 0; c < 4; c++) {
      for (int u = 0; u < 2; u++) {
        for (int i = 0; i < NeoStagedDocuments.MAX_PER_SESSION; i++) {
          staged.reserve(session("CLIENT" + c, "U" + u), "s", "a.pdf");
        }
      }
    }

    assertTrue(staged.reserve(session("OTHER_CLIENT", "U"), "s", "a.pdf").length() > 0);
  }

  @Test
  @DisplayName("one company cannot hold more than MAX_PER_CLIENT documents across its users")
  void perClientCap() {
    for (int u = 0; u < NeoStagedDocuments.MAX_PER_CLIENT / NeoStagedDocuments.MAX_PER_SESSION; u++) {
      for (int i = 0; i < NeoStagedDocuments.MAX_PER_SESSION; i++) {
        staged.reserve(session("CLIENT1", "U" + u), "s", "a.pdf");
      }
    }

    assertThrows(NeoStagedDocuments.TooManyStagedDocumentsException.class,
        () -> staged.reserve(session("CLIENT1", "NEW_USER"), "s", "a.pdf"));
    assertTrue(staged.reserve(session("CLIENT2", "U"), "s", "a.pdf").length() > 0);
  }

  @Test
  @DisplayName("the global cap counts stored files: with MAX_TOTAL of them nobody can reserve")
  void globalCapCountsStoredFiles() throws IOException {
    int perClient = NeoStagedDocuments.MAX_PER_CLIENT;
    for (int c = 0; c < NeoStagedDocuments.MAX_TOTAL / perClient; c++) {
      for (int i = 0; i < perClient; i++) {
        storedBy(session("CLIENT" + c, "U" + (i / NeoStagedDocuments.MAX_PER_SESSION)));
      }
    }

    assertThrows(NeoStagedDocuments.TooManyStagedDocumentsException.class,
        () -> staged.reserve(session("CLIENT_NEW", "U"), "s", "a.pdf"));
  }

  @Test
  @DisplayName("concurrent reservations of one session never overshoot its cap")
  void reserveIsAtomic() throws Exception {
    for (int round = 0; round < 30; round++) {
      NeoStagedDocuments fresh = new NeoStagedDocuments(clock, root.resolve("round" + round));
      int count = NeoStagedDocuments.MAX_PER_SESSION * 3;
      java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(count);
      java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
      Thread[] threads = new Thread[count];
      for (int i = 0; i < count; i++) {
        threads[i] = new Thread(() -> {
          try {
            start.await();
            fresh.reserve(session(USER), "s", "a.pdf");
            granted.incrementAndGet();
          } catch (NeoStagedDocuments.TooManyStagedDocumentsException e) {
            // refused, as expected past the cap
          } catch (InterruptedException | java.util.concurrent.BrokenBarrierException e) {
            Thread.currentThread().interrupt();
          }
        });
        threads[i].start();
      }
      for (Thread thread : threads) {
        thread.join();
      }
      assertEquals(NeoStagedDocuments.MAX_PER_SESSION, granted.get(), "round " + round);
    }
  }

  @Test
  @DisplayName("an expired document that is being read or attached is left alone until it is free")
  void sweepSkipsALockedDocument() throws Exception {
    String id = reserveAndStore(session(USER), "a");
    Staged held = staged.find(id, session(USER)).orElseThrow();
    java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Thread user = new Thread(() -> {
      held.getLock().lock();
      locked.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        held.getLock().unlock();
      }
    });
    user.start();
    locked.await();
    clock.advance(NeoStagedDocuments.TTL.plusSeconds(1));
    try {
      assertEquals(0, staged.sweep(clock.instant()));
      assertTrue(Files.exists(root.resolve(id)), "the file is not deleted under the reader");
    } finally {
      release.countDown();
      user.join();
    }

    assertEquals(1, staged.sweep(clock.instant()));
    assertFalse(Files.exists(root.resolve(id)));
  }
}
