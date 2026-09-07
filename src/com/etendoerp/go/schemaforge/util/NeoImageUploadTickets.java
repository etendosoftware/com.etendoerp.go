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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;

/**
 * In-memory store of pending one-shot image-upload tickets.
 *
 * <p><b>Single-node only, by decision (2026-09-07).</b> Tickets live in this JVM's heap:
 * they are lost on restart/redeploy and are invisible to any other node. That is
 * acceptable while Etendo GO runs on a single node, because the TTL is 10 minutes and a
 * lost ticket surfaces as the same self-correctable error as an expired one — the agent
 * just requests another.
 *
 * <p><b>If Etendo GO ever runs multi-node, or behind a load balancer that does not pin a
 * client to a node, this class MUST be replaced</b> — a PUT can land on a node that never
 * saw the ticket, and the upload fails with no useful diagnosis. The replacement is a
 * small AD table (ETGO_SF_IMG_UPLOAD) keyed by the token, with the same fields and the
 * same TTL. Nothing else in the design changes: callers only need
 * "resolve token -&gt; pending upload".
 */
@ApplicationScoped
public class NeoImageUploadTickets {

  private static final Logger log = LogManager.getLogger(NeoImageUploadTickets.class);

  /** Time a ticket stays usable, and how long a completed one stays readable. */
  public static final Duration TTL = Duration.ofMinutes(10);
  /**
   * Concurrent tickets one session may hold. A loop that requests tickets and never uploads is the
   * only way this map grows, so the cap — not the sweep — is what actually bounds it.
   */
  public static final int MAX_PENDING_PER_SESSION = 10;
  /** 192 bits of {@link SecureRandom}, base64url-encoded: the token is the credential. */
  private static final int TOKEN_BYTES = 24;

  private final SecureRandom random = new SecureRandom();
  private final Map<String, PendingUpload> byToken = new ConcurrentHashMap<>();
  /**
   * Time source. Injectable so a test can age a ticket past its TTL for real instead of asserting
   * the sweep alone — "expired" and "swept" are different states and both have to be exercised.
   */
  private final Clock clock;

  /** CDI constructor: the system clock. */
  public NeoImageUploadTickets() {
    this(Clock.systemUTC());
  }

  public NeoImageUploadTickets(Clock clock) {
    this.clock = clock;
  }

  /** Lifecycle of a ticket. {@code UPLOADING} reports to a caller as {@code PENDING}. */
  public enum Status { PENDING, UPLOADING, COMPLETED }

  /** One ticket. Immutable except for {@link #status}/{@link #imageId}, which the PUT advances. */
  public static final class PendingUpload {
    private final String clientId;
    private final String organizationId;
    private final String userId;
    private final String name;
    private final String mimeType;
    private final Instant createdAt;
    private volatile Status status;
    private volatile String imageId;

    private PendingUpload(String clientId, String organizationId, String userId, String name,
        String mimeType, Instant createdAt) {
      this.clientId = clientId;
      this.organizationId = organizationId;
      this.userId = userId;
      this.name = name;
      this.mimeType = mimeType;
      this.createdAt = createdAt;
      this.status = Status.PENDING;
    }

    public String getClientId() {
      return clientId;
    }

    public String getOrganizationId() {
      return organizationId;
    }

    public String getUserId() {
      return userId;
    }

    public String getName() {
      return name;
    }

    public String getMimeType() {
      return mimeType;
    }

    public Instant getCreatedAt() {
      return createdAt;
    }

    public Status getStatus() {
      return status;
    }

    public String getImageId() {
      return imageId;
    }

    /** @return the instant after which this ticket is no longer usable. */
    public Instant getExpiresAt() {
      return createdAt.plus(TTL);
    }

    boolean isExpiredAt(Instant now) {
      return now.isAfter(getExpiresAt());
    }

    /** @return {@code true} when this ticket belongs to the given MCP session. */
    public boolean belongsTo(String otherClientId, String otherOrgId, String otherUserId) {
      return java.util.Objects.equals(clientId, otherClientId)
          && java.util.Objects.equals(organizationId, otherOrgId)
          && java.util.Objects.equals(userId, otherUserId);
    }
  }

  /** Raised when a session already holds {@link #MAX_PENDING_PER_SESSION} unused tickets. */
  public static class TooManyPendingTicketsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TooManyPendingTicketsException(String message) {
      super(message);
    }
  }

  /**
   * Issues a ticket bound to one MCP session.
   *
   * <p>Sweeps expired entries first — that is the whole garbage collection strategy, and it is
   * deliberate: a timer thread would have to be owned, shut down and tested, for a map whose size is
   * already bounded by {@link #MAX_PENDING_PER_SESSION}.
   *
   * @return the opaque token; it carries no session JWT and is meaningless outside this store
   * @throws TooManyPendingTicketsException when the session's pending cap is already reached
   */
  public String issue(String clientId, String organizationId, String userId, String name,
      String mimeType) {
    Instant now = clock.instant();
    sweep(now);
    long pending = byToken.values().stream()
        .filter(t -> t.status != Status.COMPLETED && !t.isExpiredAt(now))
        .filter(t -> t.belongsTo(clientId, organizationId, userId))
        .count();
    if (pending >= MAX_PENDING_PER_SESSION) {
      throw new TooManyPendingTicketsException("This session already has " + pending
          + " image uploads waiting, which is the maximum. Complete or abandon one (they expire "
          + TTL.toMinutes() + " minutes after being requested) before requesting another.");
    }
    String token = newToken();
    byToken.put(token, new PendingUpload(clientId, organizationId, userId, name, mimeType, now));
    return token;
  }

  /**
   * Reads a ticket without changing it, for the status lookup.
   *
   * <p>Returns empty for an unknown token and for an expired one alike — the caller cannot tell the
   * two apart, which is the point: a token that never existed must not be distinguishable from one
   * that did, and a Tomcat restart must look exactly like an expiry.
   */
  public Optional<PendingUpload> peek(String token) {
    if (token == null) {
      return Optional.empty();
    }
    PendingUpload ticket = byToken.get(token);
    if (ticket == null || ticket.isExpiredAt(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(ticket);
  }

  /**
   * Claims a ticket for an upload that is about to run: {@code PENDING → UPLOADING}, atomically.
   *
   * <p>The atomic flip is what makes the ticket single-use under concurrency. Two simultaneous PUTs
   * cannot both create an image, because only one wins the {@code compute}.
   *
   * @return the claimed ticket, or empty when the token is unknown, expired, already uploading or
   *     already completed
   */
  public Optional<PendingUpload> claim(String token) {
    if (token == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    // The flag records whether THIS call did the flip. Reading the status back afterwards would not
    // do: a ticket another thread already flipped is also UPLOADING, and returning it here is
    // exactly the double-upload the single-use rule forbids.
    AtomicBoolean won = new AtomicBoolean();
    PendingUpload claimed = byToken.computeIfPresent(token, (key, ticket) -> {
      if (ticket.status == Status.PENDING && !ticket.isExpiredAt(now)) {
        ticket.status = Status.UPLOADING;
        won.set(true);
      }
      return ticket;
    });
    return won.get() ? Optional.of(claimed) : Optional.empty();
  }

  /**
   * Returns a claimed ticket to {@code PENDING} after a validation failure.
   *
   * <p>A rejected payload must not burn the ticket: "these bytes are not a PNG" is a self-correctable
   * error, and the agent's remedy is to PUT the right file to the same URL. Only a successful
   * {@link #complete} consumes the ticket.
   */
  public void release(String token) {
    if (token == null) {
      return;
    }
    byToken.computeIfPresent(token, (key, ticket) -> {
      if (ticket.status == Status.UPLOADING) {
        ticket.status = Status.PENDING;
      }
      return ticket;
    });
  }

  /**
   * Consumes a claimed ticket, recording the image it produced.
   *
   * <p>The entry is kept (until its TTL) rather than removed, so an agent that lost the PUT's own
   * response can still recover the id through the status lookup.
   */
  public void complete(String token, String imageId) {
    if (token == null) {
      return;
    }
    byToken.computeIfPresent(token, (key, ticket) -> {
      ticket.status = Status.COMPLETED;
      ticket.imageId = imageId;
      return ticket;
    });
  }

  /** Drops every entry past its TTL. Exposed for tests; production calls it from {@link #issue}. */
  public int sweep(Instant now) {
    AtomicInteger removed = new AtomicInteger();
    byToken.entrySet().removeIf(entry -> {
      boolean expired = entry.getValue().isExpiredAt(now);
      if (expired) {
        removed.incrementAndGet();
      }
      return expired;
    });
    if (removed.get() > 0) {
      log.debug("Swept {} expired image-upload ticket(s)", removed.get());
    }
    return removed.get();
  }

  /** Live entry count, for tests asserting the map does not grow unbounded. */
  public int size() {
    return byToken.size();
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The application-scoped instance.
   *
   * <p>Resolved through Weld rather than a static field so the store really is one per application,
   * shared by the MCP tool that issues a ticket and the servlet endpoint that consumes it — two
   * entirely separate request paths. Tests instantiate the class directly instead.
   */
  public static NeoImageUploadTickets getInstance() {
    return WeldUtils.getInstanceFromStaticBeanManager(NeoImageUploadTickets.class);
  }
}
