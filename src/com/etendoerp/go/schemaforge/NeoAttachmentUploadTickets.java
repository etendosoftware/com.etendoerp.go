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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.enterprise.context.ApplicationScoped;

import org.openbravo.base.weld.WeldUtils;

/**
 * In-memory store of pending one-shot attachment-upload links, the attachment twin of
 * {@code NeoImageUploadTickets} (ETP-5715).
 *
 * <p>A link is bound to the MCP session that asked for it (client, organization, user, role,
 * warehouse) and to ONE document (spec, entity, record), one file name and the "main document"
 * flag, all fixed when the link is issued. Whoever holds the file PUTs it to the link without any
 * credential of its own; the upload then runs as the session that issued the link, so the role is
 * re-checked at upload time rather than trusted from the moment of issue.</p>
 *
 * <p><b>Single-node only, by decision — exactly as the image tickets.</b> Links live in this JVM's
 * heap: they are lost on restart/redeploy and invisible to any other node. That is acceptable while
 * Etendo GO runs on a single node, because the TTL is 10 minutes and a lost link surfaces as the
 * same self-correctable answer as an expired one — the agent just requests another. <b>If Etendo GO
 * ever runs multi-node, or behind a balancer that does not pin a client to a node, this class MUST
 * be replaced</b> by a small AD table keyed by the token (same fields, same TTL); callers only need
 * "resolve token to pending upload".</p>
 *
 * <p>The two stores are separate classes on purpose: an image ticket is image-shaped (MIME type,
 * {@code AD_Image} id) and the cap on pending links is counted per kind, so unifying them would
 * change the image tool's behaviour for a saving of lines.</p>
 */
@ApplicationScoped
public class NeoAttachmentUploadTickets {

  /** Time a link stays usable, and how long a completed one stays readable. */
  public static final Duration TTL = Duration.ofMinutes(10);
  /**
   * Concurrent links one session may hold; a loop that requests links and never uploads is the
   * only way the map grows.
   */
  public static final int MAX_PENDING_PER_SESSION = 10;
  /** 192 bits of {@link SecureRandom}, base64url-encoded: the token is the credential. */
  private static final int TOKEN_BYTES = 24;

  private final SecureRandom random = new SecureRandom();
  private final Map<String, PendingUpload> byToken = new ConcurrentHashMap<>();
  private final Clock clock;

  /** CDI constructor: the system clock. */
  public NeoAttachmentUploadTickets() {
    this(Clock.systemUTC());
  }

  /**
   * Constructor taking an explicit time source, so a test can age a link past its {@link #TTL} for
   * real. Production uses the no-arg CDI constructor.
   *
   * @param clock the time source every expiry decision in this store reads
   */
  public NeoAttachmentUploadTickets(Clock clock) {
    this.clock = clock;
  }

  /** Lifecycle of a link. {@code UPLOADING} reports to a caller as pending. */
  public enum Status { PENDING, UPLOADING, COMPLETED }

  /** The MCP session a link is bound to: who the upload runs as. */
  public static final class Session {
    private final String clientId;
    private final String organizationId;
    private final String userId;
    private final String roleId;
    private final String warehouseId;

    /**
     * Describes the session that requests the link.
     *
     * @param clientId       client of the session
     * @param organizationId organization of the session
     * @param userId         user of the session
     * @param roleId         role of the session — the upload re-checks access under it
     * @param warehouseId    warehouse of the session, or {@code null}
     */
    public Session(String clientId, String organizationId, String userId, String roleId,
        String warehouseId) {
      this.clientId = clientId;
      this.organizationId = organizationId;
      this.userId = userId;
      this.roleId = roleId;
      this.warehouseId = warehouseId;
    }

    /** Returns the client id. */
    public String getClientId() {
      return clientId;
    }

    /** Returns the organization id. */
    public String getOrganizationId() {
      return organizationId;
    }

    /** Returns the user id. */
    public String getUserId() {
      return userId;
    }

    /** Returns the role id. */
    public String getRoleId() {
      return roleId;
    }

    /** Returns the warehouse id. */
    public String getWarehouseId() {
      return warehouseId;
    }

    boolean sameSessionAs(Session other) {
      return Objects.equals(clientId, other.clientId)
          && Objects.equals(organizationId, other.organizationId)
          && Objects.equals(userId, other.userId);
    }
  }

  /** What a link is for: one file onto one document. */
  public static final class Subject {
    private final String specId;
    private final String entityId;
    private final String recordId;
    private final String fileName;
    private final boolean markAsMain;

    /**
     * Describes the upload the link authorizes.
     *
     * @param specId     id of the {@code ETGO_SF_SPEC} the document belongs to
     * @param entityId   id of the {@code ETGO_SF_ENTITY} (the spec's root entity)
     * @param recordId   the document's id
     * @param fileName   the file name the attachment will carry; reduced to its last path segment
     * @param markAsMain whether the file becomes the document's main attachment
     */
    public Subject(String specId, String entityId, String recordId, String fileName,
        boolean markAsMain) {
      this.specId = specId;
      this.entityId = entityId;
      this.recordId = recordId;
      this.fileName = NeoAttachmentPolicy.sanitizeFileName(fileName);
      this.markAsMain = markAsMain;
    }

    /** Returns the spec id. */
    public String getSpecId() {
      return specId;
    }

    /** Returns the entity id. */
    public String getEntityId() {
      return entityId;
    }

    /** Returns the record id. */
    public String getRecordId() {
      return recordId;
    }

    /** Returns the file name. */
    public String getFileName() {
      return fileName;
    }

    /** Returns whether the file becomes the document's main attachment. */
    public boolean isMarkAsMain() {
      return markAsMain;
    }
  }

  /** One link. Immutable except for {@link #status}/{@link #attachmentId}, which the PUT advances. */
  public static final class PendingUpload {
    private final Session session;
    private final Subject subject;
    private final Instant createdAt;
    private volatile Status status = Status.PENDING;
    private volatile String attachmentId;

    private PendingUpload(Session session, Subject subject, Instant createdAt) {
      this.session = session;
      this.subject = subject;
      this.createdAt = createdAt;
    }

    /** Returns the session. */
    public Session getSession() {
      return session;
    }

    /** Returns the subject. */
    public Subject getSubject() {
      return subject;
    }

    /** Returns the status. */
    public Status getStatus() {
      return status;
    }

    /** Returns the attachment id. */
    public String getAttachmentId() {
      return attachmentId;
    }

    /** @return the instant after which this link is no longer usable. */
    public Instant getExpiresAt() {
      return createdAt.plus(TTL);
    }

    boolean isExpiredAt(Instant now) {
      return now.isAfter(getExpiresAt());
    }
  }

  /** Raised when a session already holds {@link #MAX_PENDING_PER_SESSION} unused links. */
  public static class TooManyPendingTicketsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Builds the refusal of a session that already holds the maximum of pending links.
     *
     * @param message what the caller is told, including how to recover
     */
    public TooManyPendingTicketsException(String message) {
      super(message);
    }
  }

  /**
   * Issues a link bound to one session and one document.
   *
   * <p>Sweeps expired entries first — that is the whole garbage collection, as in the image store.</p>
   *
   * @param session the session the upload will run as
   * @param subject the document, file name and main flag the link authorizes
   * @return the opaque token; it carries no JWT and is meaningless outside this store
   * @throws TooManyPendingTicketsException when the session's pending cap is already reached
   */
  public String issue(Session session, Subject subject) {
    Instant now = clock.instant();
    sweep(now);
    long pending = byToken.values().stream()
        .filter(t -> t.status != Status.COMPLETED && !t.isExpiredAt(now))
        .filter(t -> t.session.sameSessionAs(session))
        .count();
    if (pending >= MAX_PENDING_PER_SESSION) {
      throw new TooManyPendingTicketsException("This session already has " + pending
          + " attachment uploads waiting, which is the maximum. Complete or abandon one (they "
          + "expire " + TTL.toMinutes() + " minutes after being requested) before requesting "
          + "another.");
    }
    String token = newToken();
    byToken.put(token, new PendingUpload(session, subject, now));
    return token;
  }

  /**
   * Reads a link without changing it.
   *
   * @param token the token returned by {@link #issue}; {@code null} yields empty
   * @return the link in whatever state it is in, or empty when unknown or expired — the caller
   *     cannot tell the two apart, on purpose
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
   * Claims a link for an upload about to run: {@code PENDING → UPLOADING}, atomically, so two
   * simultaneous PUTs cannot both store a file.
   *
   * @param token the token returned by {@link #issue}; {@code null} yields empty
   * @return the claimed link, or empty when unknown, expired, already uploading or completed
   */
  public Optional<PendingUpload> claim(String token) {
    if (token == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
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
   * Returns a claimed link to {@code PENDING} after a rejected payload: "these bytes are too large"
   * is self-correctable, and the remedy is to PUT the right file to the same link.
   *
   * @param token the token whose link goes back to pending; a no-op unless it is uploading
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
   * Consumes a claimed link, recording the attachment it produced.
   *
   * @param token        the claimed link's token; {@code null} or unknown is a no-op
   * @param attachmentId id of the stored attachment, or {@code null} to consume the link without one
   */
  public void complete(String token, String attachmentId) {
    if (token == null) {
      return;
    }
    byToken.computeIfPresent(token, (key, ticket) -> {
      ticket.status = Status.COMPLETED;
      ticket.attachmentId = attachmentId;
      return ticket;
    });
  }

  /**
   * Drops every entry past its TTL, completed ones included.
   *
   * @param now the instant to judge expiry against
   * @return how many entries were removed
   */
  public int sweep(Instant now) {
    int before = byToken.size();
    byToken.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now));
    return before - byToken.size();
  }

  /**
   * Live entry count, for tests asserting the map does not grow unbounded.
   *
   * @return the number of entries held, in any status
   */
  public int size() {
    return byToken.size();
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The application-scoped instance, resolved through Weld so the MCP tool that issues a link and
   * the servlet endpoint that consumes it share one store. Tests instantiate the class directly.
   *
   * @return the shared store
   */
  public static NeoAttachmentUploadTickets getInstance() {
    return WeldUtils.getInstanceFromStaticBeanManager(NeoAttachmentUploadTickets.class);
  }
}
