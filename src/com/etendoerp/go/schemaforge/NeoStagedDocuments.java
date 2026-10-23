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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.weld.WeldUtils;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;

/**
 * In-memory index of the documents staged for data extraction (ETP-5715): a PDF an agent uploaded
 * through a single-use link so its content can be read, before any record exists for it.
 *
 * <p>A staged document belongs to the session that reserved it (client, organization and user) and
 * to ONE spec; no other session can find it, and a lookup that fails for any reason answers the same
 * "not found". Its bytes live in a directory of their own under the system temp directory and are
 * deleted when the document expires, {@link #TTL} (60 minutes) after it was reserved, when it is
 * attached to its record, or by the sweep every reservation and lookup runs. Directories a previous
 * run left behind, if older than that, are deleted when the store is first created.</p>
 *
 * <p>The extraction result is cached on the document, so asking again — or recomputing the review for
 * another vendor — does not read the PDF, and does not pay for it, a second time.</p>
 *
 * <p><b>Single-node only, as {@link NeoAttachmentUploadTickets}.</b> The index is in this JVM's heap:
 * a restart loses it (the files are purged at startup), and the agent just uploads again.</p>
 */
@ApplicationScoped
public class NeoStagedDocuments {

  /** How long a staged document stays available. */
  public static final Duration TTL = Duration.ofMinutes(60);
  /** Live staged documents one session may hold: a guard for the disk, not a usage limit. */
  public static final int MAX_PER_SESSION = 50;
  /**
   * Staged documents WITH A FILE the whole server may hold (at most 10 MB each): the per-session cap
   * alone is no bound across many users. A reservation holds no disk, so it does not count. A guard
   * for the disk, not a usage limit.
   */
  public static final int MAX_TOTAL = 200;
  /** Name of the maintenance thread. */
  static final String MAINTENANCE_THREAD = "etgo-staged-documents-maintenance";
  /**
   * How long a reservation whose PDF has not arrived is kept: the life of its upload link. Only a
   * stored file stays for {@link #TTL}.
   */
  public static final Duration RESERVATION_TTL = Duration.ofMinutes(10);
  /** Live (reserved or stored) staged documents one client may hold across all its users. */
  public static final int MAX_PER_CLIENT = 100;
  /** How often the clean-up runs even when nobody uses the feature. */
  static final Duration MAINTENANCE_PERIOD = Duration.ofMinutes(10);

  private static final Logger log = LogManager.getLogger(NeoStagedDocuments.class);
  private static final int ID_BYTES = 16;

  private final SecureRandom random = new SecureRandom();
  private final Map<String, Staged> byId = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Path root;
  private final Object reserveLock = new Object();
  private ScheduledExecutorService maintenance;

  /**
   * CDI constructor: the system clock and the system temp directory. It has no side effect at all:
   * Weld's client proxy runs it too, so the disk purge and the timer start in {@link #start()},
   * which the container calls once, on the real instance only.
   */
  public NeoStagedDocuments() {
    this(Clock.systemUTC(), Path.of(System.getProperty("java.io.tmpdir"), "etgo-staged"));
  }

  /**
   * Constructor taking the time source and the directory, so a test can age a document and inspect
   * its files. It does nothing but store them.
   *
   * @param clock the time source every expiry decision reads
   * @param root  the directory the staged files live in
   */
  public NeoStagedDocuments(Clock clock, Path root) {
    this.clock = clock;
    this.root = root;
  }

  /**
   * Purges what a previous run left on disk past the TTL and starts the daemon timer that runs
   * {@link #runMaintenance()} every {@link #MAINTENANCE_PERIOD}, so files go even if nobody uses the
   * feature. Idempotent: a second call starts no second timer.
   */
  @PostConstruct
  public synchronized void start() {
    purgeLeftovers();
    if (maintenance != null) {
      return;
    }
    maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, MAINTENANCE_THREAD);
      thread.setDaemon(true);
      return thread;
    });
    maintenance.scheduleWithFixedDelay(this::tickSafely, MAINTENANCE_PERIOD.toMinutes(),
        MAINTENANCE_PERIOD.toMinutes(), TimeUnit.MINUTES);
  }

  /**
   * Stops the timer when the application shuts down or the webapp is reloaded, so its thread does
   * not outlive (and pin) the classloader.
   */
  @PreDestroy
  public synchronized void shutdown() {
    if (maintenance != null) {
      maintenance.shutdownNow();
      maintenance = null;
    }
  }

  /** Whether the maintenance timer is running. */
  synchronized boolean isRunning() {
    return maintenance != null;
  }

  /**
   * One timer tick. It never throws: a scheduled task that throws is cancelled silently by the
   * executor, and the clean-up would stop for good. A failed tick is logged (class name only) and
   * retried at the next period.
   */
  void tickSafely() {
    try {
      runMaintenance();
    } catch (Exception e) {
      log.warn("Staged documents maintenance failed: {}", e.getClass().getSimpleName());
    }
  }

  /** Raised when a session already holds {@link #MAX_PER_SESSION} live staged documents. */
  public static class TooManyStagedDocumentsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Builds the refusal.
     *
     * @param message what the caller is told, including how to recover
     */
    public TooManyStagedDocumentsException(String message) {
      super(message);
    }
  }

  /** One staged document. Mutable only through the store, and through its cached extraction. */
  public static final class Staged {
    private final String id;
    private final Session session;
    private final String specName;
    private final String fileName;
    private final Instant createdAt;
    private final AtomicReference<Path> file = new AtomicReference<>();
    private volatile Instant storedAt;
    private volatile String extraction;
    private volatile boolean nothingExtracted;
    private volatile boolean consumed;
    private final ReentrantLock lock = new ReentrantLock();

    private Staged(String id, Session session, String specName, String fileName, Instant createdAt) {
      this.id = id;
      this.session = session;
      this.specName = specName;
      this.fileName = fileName;
      this.createdAt = createdAt;
    }

    /** Returns the id. */
    public String getId() {
      return id;
    }

    /**
     * The lock that serializes the uses of the file: an extraction holds it while it reads the PDF
     * (so two concurrent extractions read it once), an attach holds it while it stores the file
     * (so it can neither run twice nor delete the file under a reading).
     *
     * @return the document's lock
     */
    public ReentrantLock getLock() {
      return lock;
    }

    /** Returns whether the document was already attached and removed: it can serve no one else. */
    public boolean isConsumed() {
      return consumed;
    }

    /** Marks the document as attached; the caller removes it from the index. */
    public void markConsumed() {
      this.consumed = true;
    }

    /** Returns the spec the document was staged for. */
    public String getSpecName() {
      return specName;
    }

    /** Returns the file name the document will carry when attached. */
    public String getFileName() {
      return fileName;
    }

    /**
     * Returns the instant after which the document is gone: {@link #RESERVATION_TTL} after it was
     * reserved while its PDF has not arrived, {@link #TTL} after the PDF arrived.
     */
    public Instant getExpiresAt() {
      Instant stored = storedAt;
      return stored == null ? createdAt.plus(RESERVATION_TTL) : stored.plus(TTL);
    }

    /** Returns whether the bytes have arrived. */
    public boolean isStored() {
      return file.get() != null;
    }

    /** Returns the file holding the bytes, {@code null} until they arrive. */
    public Path getFile() {
      return file.get();
    }

    /**
     * The cached extraction.
     *
     * @return a copy of the sanitized extraction, {@code null} when none was cached or when the
     *         document yielded nothing (see {@link #isNothingExtracted})
     * @throws JSONException when the cached text cannot be read back
     */
    public JSONObject getExtraction() throws JSONException {
      String cached = extraction;
      return cached == null ? null : new JSONObject(cached);
    }

    /** Returns whether the extraction ran and found nothing in the document. */
    public boolean isNothingExtracted() {
      return nothingExtracted;
    }

    /**
     * Tells whether an extraction result, empty or not, is cached.
     *
     * @return {@code true} when an extraction or an empty-extraction marker is cached
     */
    public boolean hasExtractionResult() {
      return extraction != null || nothingExtracted;
    }

    /**
     * Caches a successful extraction.
     *
     * @param sanitized the sanitized payload
     */
    public void cacheExtraction(JSONObject sanitized) {
      this.extraction = sanitized.toString();
    }

    /** Caches that the document was read and nothing usable was found. */
    public void cacheNothingExtracted() {
      this.nothingExtracted = true;
    }

    private boolean isExpiredAt(Instant now) {
      return now.isAfter(getExpiresAt());
    }
  }

  /**
   * Reserves an id for a document about to be uploaded.
   *
   * @param session  the session that asks
   * @param specName the spec the document is staged for
   * @param fileName the file name it will carry
   * @return the staged document id
   * @throws TooManyStagedDocumentsException when the session already holds the maximum
   */
  public String reserve(Session session, String specName, String fileName) {
    synchronized (reserveLock) {
      Instant now = clock.instant();
      sweep(now);
      refuseWhenFull(session);
      byte[] bytes = new byte[ID_BYTES];
      random.nextBytes(bytes);
      String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      byId.put(id, new Staged(id, session, specName, fileName, now));
      return id;
    }
  }

  /** The caps, checked and acted on under the same lock so concurrent reservations cannot overshoot. */
  private void refuseWhenFull(Session session) {
    if (storedCount() >= MAX_TOTAL) {
      throw new TooManyStagedDocumentsException("The server holds the maximum of staged documents "
          + "right now. They are removed " + TTL.toMinutes() + " minutes after being uploaded; "
          + "try again in a few minutes.");
    }
    long ofClient = byId.values().stream()
        .filter(s -> Objects.equals(s.session.getClientId(), session.getClientId())).count();
    if (ofClient >= MAX_PER_CLIENT) {
      throw new TooManyStagedDocumentsException("This company already holds " + ofClient
          + " staged documents, which is the maximum. They are removed after "
          + TTL.toMinutes() + " minutes; wait for some to expire.");
    }
    long live = byId.values().stream().filter(s -> s.session.sameSessionAs(session)).count();
    if (live >= MAX_PER_SESSION) {
      throw new TooManyStagedDocumentsException("This session already holds " + live
          + " staged documents, which is the maximum. They are removed " + TTL.toMinutes()
          + " minutes after being uploaded; wait for some to expire.");
    }
  }

  private long storedCount() {
    return byId.values().stream().filter(Staged::isStored).count();
  }

  /**
   * A staged document of this session.
   *
   * @param id      the id from {@link #reserve}; {@code null} yields empty
   * @param session the session that asks
   * @return the document, empty when unknown, expired or another session's — the caller cannot
   *     tell these apart, on purpose
   */
  public Optional<Staged> find(String id, Session session) {
    if (id == null) {
      return Optional.empty();
    }
    sweep(clock.instant());
    Staged staged = byId.get(id);
    return staged != null && staged.session.sameSessionAs(session) ? Optional.of(staged)
        : Optional.empty();
  }

  /**
   * The directory a document's bytes are written to, created on demand.
   *
   * @param id the id from {@link #reserve}
   * @return the directory
   * @throws UncheckedIOException when it cannot be created
   */
  public Path directoryFor(String id) {
    try {
      return Files.createDirectories(root.resolve(id));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Records that the bytes of a document have arrived.
   *
   * @param id   the id from {@link #reserve}
   * @param file the file written under {@link #directoryFor}
   * @return {@code false} when the document is unknown or already expired (the file is deleted)
   */
  public boolean markStored(String id, Path file) {
    Staged staged = byId.get(id);
    if (staged == null || staged.isExpiredAt(clock.instant())) {
      delete(root.resolve(id));
      return false;
    }
    synchronized (reserveLock) {
      if (storedCount() >= MAX_TOTAL) {
        byId.remove(id);
        delete(root.resolve(id));
        return false;
      }
      staged.file.set(file);
      staged.storedAt = clock.instant();
    }
    sweep(clock.instant());
    return true;
  }

  /**
   * The periodic clean-up: drops expired documents and deletes what a previous run (or another
   * instance of this store) left on disk past the TTL. Runs on a timer, so files do not stay when
   * nobody uses the feature.
   */
  public void runMaintenance() {
    sweep(clock.instant());
    purgeLeftovers();
  }

  /**
   * Removes a document and its bytes.
   *
   * @param id the id from {@link #reserve}; unknown is a no-op
   */
  public void discard(String id) {
    if (id != null && byId.remove(id) != null) {
      delete(root.resolve(id));
    }
  }

  /**
   * Drops every document past its TTL, deleting its bytes.
   *
   * @param now the instant to judge expiry against
   * @return how many documents were removed
   */
  public int sweep(Instant now) {
    int removed = 0;
    for (Staged staged : byId.values()) {
      if (staged.isExpiredAt(now) && removeUnlessInUse(staged)) {
        removed++;
      }
    }
    return removed;
  }

  /** Removes an expired document, unless a reading or an attach holds it (the next sweep gets it). */
  private boolean removeUnlessInUse(Staged staged) {
    if (!staged.lock.tryLock()) {
      return false;
    }
    try {
      if (byId.remove(staged.id) == null) {
        return false;
      }
      delete(root.resolve(staged.id));
      return true;
    } finally {
      staged.lock.unlock();
    }
  }

  /**
   * Live document count, for tests asserting the index does not grow unbounded.
   *
   * @return the number of documents held
   */
  public int size() {
    return byId.size();
  }

  private void purgeLeftovers() {
    if (!Files.isDirectory(root)) {
      return;
    }
    Instant limit = clock.instant().minus(TTL);
    try (Stream<Path> children = Files.list(root)) {
      children.filter(child -> isOlderThan(child, limit)).forEach(NeoStagedDocuments::delete);
    } catch (IOException e) {
      log.warn("Could not purge the staged documents left by a previous run: {}", e.getMessage());
    }
  }

  private static boolean isOlderThan(Path path, Instant limit) {
    try {
      return Files.getLastModifiedTime(path).toInstant().isBefore(limit);
    } catch (IOException e) {
      return false;
    }
  }

  private static void delete(Path directory) {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          log.warn("Could not delete a staged file: {}", e.getMessage());
        }
      });
    } catch (IOException e) {
      log.warn("Could not delete a staged directory: {}", e.getMessage());
    }
  }

  /**
   * The application-scoped instance, resolved through Weld so the MCP tools and the servlet
   * endpoint share one index. Tests instantiate the class directly.
   *
   * @return the shared store
   */
  public static NeoStagedDocuments getInstance() {
    return WeldUtils.getInstanceFromStaticBeanManager(NeoStagedDocuments.class);
  }
}
