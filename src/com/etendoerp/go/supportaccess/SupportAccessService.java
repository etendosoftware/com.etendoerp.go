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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.oauth2.OAuth2Utils;
import com.etendoerp.go.session.GoSessionRecord;

/**
 * ETP-5351 — issues, redeems and ends support accesses: the audit row in
 * {@code ETGO_SUPPORT_ACCESS} and the one-time pass that turns into a GO support session.
 *
 * <p>Shared by the Classic "Acceder como soporte" process (T3: {@link #issue}, {@link #revoke},
 * {@link #findOpenAccess}) and the GO handoff endpoint (T4: {@link #redeem}, {@link #close}).
 * Callers own the transaction: nothing here commits.</p>
 *
 * <p>The pass is 256 random bits, base64url without padding (URL-safe: no {@code +}, {@code /}
 * nor {@code =}), valid for {@value #TICKET_TTL_SECONDS} seconds and single-use. Only its SHA-256
 * is stored.</p>
 */
public class SupportAccessService {

  private static final Logger log = LogManager.getLogger(SupportAccessService.class);

  /** Lifetime of a pass, in seconds. */
  public static final long TICKET_TTL_SECONDS = 60;
  /** Preference holding the default session duration, in minutes. */
  public static final String PREF_DEFAULT_MINUTES = "ETGO_SupportSessionDefaultMinutes";
  /** Preference holding the maximum session duration, in minutes. */
  public static final String PREF_MAX_MINUTES = "ETGO_SupportSessionMaxMinutes";
  /** Fallback default duration when the preference is unset or unreadable. */
  public static final int FALLBACK_DEFAULT_MINUTES = 60;
  /** Fallback maximum duration when the preference is unset or unreadable. */
  public static final int FALLBACK_MAX_MINUTES = 480;
  /** Minimum length of the operator's reason, after trimming. */
  public static final int MIN_REASON_LENGTH = 10;
  /** {@code ETGO_SUPPORT_ACCESS.REASON} column size. */
  public static final int MAX_REASON_LENGTH = 2000;
  /** {@code ETGO_SUPPORT_ACCESS.USER_AGENT} column size. */
  private static final int MAX_USER_AGENT_LENGTH = 500;
  /** App route the PWA serves the handoff from; the pass travels in the fragment. */
  public static final String HANDOFF_APP_PATH = "/support-access";
  /** Fragment key of the pass. */
  public static final String HANDOFF_FRAGMENT_KEY = "t";
  private static final int TICKET_BYTES = 32;

  private final SupportAccessStore store;
  private final SupportUserProvisioner provisioner;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /** Production wiring: JDBC stores and the system clock. */
  public SupportAccessService() {
    this(new JdbcSupportAccessStore(), new SupportUserProvisioner(), Clock.systemUTC());
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param store       the access persistence
   * @param provisioner creates or reactivates the tenant's support user
   * @param clock       the time source
   */
  public SupportAccessService(SupportAccessStore store, SupportUserProvisioner provisioner,
      Clock clock) {
    this.store = store;
    this.provisioner = provisioner;
    this.clock = clock;
  }

  /**
   * Opens a support access to a tenant and returns its one-time pass, in one transaction:
   * closes the tenant's stale accesses, validates the request, provisions the support user and
   * inserts the audit row.
   *
   * @param operatorUserId  the operator's {@code AD_User_ID} (client 0, "Soporte Etendo GO" role)
   * @param clientId        the tenant to enter
   * @param reason          why; required, at least {@value #MIN_REASON_LENGTH} characters
   * @param durationMinutes total session duration; {@code null} takes
   *                        {@code ETGO_SupportSessionDefaultMinutes}
   * @param ip              the operator's IP, stored hashed; may be null
   * @param userAgent       the operator's user agent, stored truncated; may be null
   * @return the pass, shown once
   * @throws SupportTenantBusyException if another access already holds the tenant
   * @throws SupportAccessException     for any other refusal, see its codes
   */
  public IssuedSupportTicket issue(String operatorUserId, String clientId, String reason,
      Integer durationMinutes, String ip, String userAgent) {
    Instant now = clock.instant();
    String trimmedReason = StringUtils.trimToEmpty(reason);
    if (trimmedReason.length() < MIN_REASON_LENGTH) {
      throw new SupportAccessException(SupportAccessException.CODE_REASON_REQUIRED,
          "A reason of at least " + MIN_REASON_LENGTH + " characters is required");
    }
    int minutes = resolveDuration(durationMinutes);
    if (StringUtils.isBlank(operatorUserId) || !store.holdsOperatorRole(operatorUserId)) {
      throw new SupportAccessException(SupportAccessException.CODE_OPERATOR_NOT_ALLOWED,
          "The operator does not hold the support role");
    }
    if (StringUtils.isBlank(clientId) || !store.isEligibleTarget(clientId)) {
      throw new SupportAccessException(SupportAccessException.CODE_TARGET_NOT_ELIGIBLE,
          "The tenant cannot be entered by support");
    }
    if (!store.isSupportAccountReady()) {
      throw new SupportAccessException(SupportAccessException.CODE_SUPPORT_ACCOUNT_MISSING,
          "The technical support account is missing; run update.database");
    }

    store.closeStaleAccesses(clientId, now);
    SupportAccessRecord holder = store.findOpenAccess(clientId);
    if (holder != null) {
      throw new SupportTenantBusyException(holder);
    }

    SupportUserContext supportUser = provisioner.findOrCreate(clientId);
    String ticket = newTicket();
    SupportAccessRecord accessRecord = new SupportAccessRecord();
    accessRecord.setId(newId());
    accessRecord.setOperatorUserId(operatorUserId);
    accessRecord.setTargetClientId(clientId);
    accessRecord.setSupportUserId(supportUser.getUserId());
    accessRecord.setRoleId(supportUser.getRoleId());
    accessRecord.setReason(StringUtils.left(trimmedReason, MAX_REASON_LENGTH));
    accessRecord.setDurationMinutes(minutes);
    accessRecord.setTicketHash(OAuth2Utils.hashToken(ticket));
    accessRecord.setTicketExpiresAt(now.plusSeconds(TICKET_TTL_SECONDS));
    accessRecord.setIpHash(hashIp(ip));
    accessRecord.setUserAgent(StringUtils.left(userAgent, MAX_USER_AGENT_LENGTH));
    if (!store.insert(accessRecord)) {
      // Lost the race against another operator between the lookup and the insert: the unique
      // index ETGO_SUPACC_OPEN_UQ refused the second open row.
      throw new SupportTenantBusyException(store.findOpenAccess(clientId));
    }
    log.info("Support access {} issued by operator {} for client {} ({} min)",
        accessRecord.getId(), operatorUserId, clientId, minutes);
    return new IssuedSupportTicket(accessRecord.getId(), ticket, handoffUrl(ticket),
        accessRecord.getTicketExpiresAt(), minutes);
  }

  /**
   * Redeems a pass: marks it used atomically and returns its access. A pass redeems at most once,
   * whatever the concurrency.
   *
   * @param ticket the plaintext pass from the URL fragment
   * @return the access the pass opens, with {@code TICKET_USED_AT} set
   * @throws SupportTicketException {@code INVALID} (unknown or tampered), {@code EXPIRED} (past its
   *     60 seconds, or its access was closed) or {@code USED}
   */
  public SupportAccessRecord redeem(String ticket) {
    String trimmed = StringUtils.trimToNull(ticket);
    if (trimmed == null) {
      throw new SupportTicketException(SupportTicketException.Reason.INVALID);
    }
    Instant now = clock.instant();
    String ticketHash = OAuth2Utils.hashToken(trimmed);
    SupportAccessRecord redeemed = store.consumeTicket(ticketHash, now);
    if (redeemed != null) {
      return redeemed;
    }
    SupportAccessRecord known = store.findByTicketHash(ticketHash);
    if (known == null) {
      throw new SupportTicketException(SupportTicketException.Reason.INVALID);
    }
    if (known.getTicketUsedAt() != null) {
      throw new SupportTicketException(SupportTicketException.Reason.USED);
    }
    throw new SupportTicketException(SupportTicketException.Reason.EXPIRED);
  }

  /**
   * Records the support session a redeemed pass opened.
   *
   * @param accessId  the access
   * @param sessionId the {@code ETGO_GO_SESSION_ID} just created
   */
  public void markStarted(String accessId, String sessionId) {
    store.markStarted(accessId, sessionId, clock.instant());
  }

  /**
   * Closes an access from Classic ("Cerrar sesión de soporte") and revokes its sessions, so the
   * support tab is logged out on its next request and the tenant is free again.
   *
   * @param accessId     the access
   * @param byUserId     the operator closing it
   * @return {@code true} when this call closed it; {@code false} when it was already closed
   */
  public boolean revoke(String accessId, String byUserId) {
    return end(accessId, SupportEndReason.REVOKED, byUserId);
  }

  /**
   * Closes an access for a non-operator reason (logout, expiry) and revokes its sessions.
   * Idempotent.
   *
   * @param accessId the access; {@code null} is ignored
   * @param reason   {@link SupportEndReason#LOGOUT}, {@link SupportEndReason#EXPIRED} or
   *                 {@link SupportEndReason#TICKET_EXPIRED}
   * @return {@code true} when this call closed it
   * @throws IllegalArgumentException for {@link SupportEndReason#REVOKED}: use {@link #revoke}
   */
  public boolean close(String accessId, SupportEndReason reason) {
    if (reason == SupportEndReason.REVOKED) {
      throw new IllegalArgumentException("Use revoke() to record who revoked the access");
    }
    return end(accessId, reason, null);
  }

  private boolean end(String accessId, SupportEndReason reason, String byUserId) {
    if (StringUtils.isBlank(accessId)) {
      return false;
    }
    boolean closed = store.close(accessId, reason, byUserId, clock.instant());
    // Revoked even when it was closed before: a session must never outlive its access.
    store.revokeSessions(accessId);
    if (closed) {
      log.info("Support access {} closed: {}", accessId, reason.getCode());
    }
    return closed;
  }

  /**
   * The access currently holding the tenant, after closing the stale ones: for T3's "active
   * support session" column and its busy message.
   *
   * @param clientId the tenant
   * @return the open access with operator name and expiry, or {@code null} when the tenant is free
   */
  public SupportAccessRecord findOpenAccess(String clientId) {
    store.closeStaleAccesses(clientId, clock.instant());
    return store.findOpenAccess(clientId);
  }

  /**
   * Whether the technical support account is ready to own sessions.
   *
   * @return {@code true} when it exists, is active and is flagged
   */
  public boolean isSupportAccountReady() {
    return store.isSupportAccountReady();
  }

  /**
   * Closes the access of a support session that logged out. A no-op for any other session.
   *
   * @param sessionRecord the session being logged out, may be null
   */
  public void closeOnLogout(GoSessionRecord sessionRecord) {
    if (sessionRecord != null && StringUtils.isNotBlank(sessionRecord.getSupportAccessId())) {
      close(sessionRecord.getSupportAccessId(), SupportEndReason.LOGOUT);
    }
  }

  /**
   * The {@code supportSession} block of {@code GET /me} and {@code GET /session}:
   * {@code {clientId, clientName, expiresAt}} with {@code expiresAt} the session's absolute
   * expiry in ISO-8601 UTC (for example {@code 2026-10-05T18:00:00Z}).
   *
   * @param sessionRecord the authenticated session, may be null
   * @return the block, or {@code null} for an ordinary session
   * @throws JSONException never in practice
   */
  public JSONObject describeSupportSession(GoSessionRecord sessionRecord) throws JSONException {
    if (!SupportAccessGuard.isSupportSession(sessionRecord)) {
      return null;
    }
    JSONObject block = new JSONObject();
    block.put("clientId", sessionRecord.getCtxClientId());
    block.put("clientName", sessionRecord.getCtxClientId() == null ? JSONObject.NULL
        : StringUtils.defaultString(store.findClientName(sessionRecord.getCtxClientId())));
    Instant expiresAt = sessionRecord.getAbsoluteExpiresAt();
    block.put("expiresAt", expiresAt == null ? JSONObject.NULL
        : expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
    return block;
  }

  /**
   * Looks an access up by id.
   *
   * @param accessId the access
   * @return the access, or {@code null}
   */
  public SupportAccessRecord findById(String accessId) {
    return store.findById(accessId);
  }

  /**
   * The default session duration offered to the operator ({@code ETGO_SupportSessionDefaultMinutes},
   * capped at the maximum).
   *
   * @return minutes
   */
  public int getDefaultDurationMinutes() {
    return Math.min(readMinutes(PREF_DEFAULT_MINUTES, FALLBACK_DEFAULT_MINUTES),
        getMaxDurationMinutes());
  }

  /**
   * The maximum session duration ({@code ETGO_SupportSessionMaxMinutes}).
   *
   * @return minutes
   */
  public int getMaxDurationMinutes() {
    return readMinutes(PREF_MAX_MINUTES, FALLBACK_MAX_MINUTES);
  }

  /**
   * The GO URL that redeems a pass: {@code <appBaseUrl>/support-access#t=<pass>}. The pass goes in
   * the fragment so it never reaches a server or an access log.
   *
   * @param ticket the plaintext pass
   * @return the URL, or {@code null} when no public app base URL is configured
   */
  public static String handoffUrl(String ticket) {
    return handoffUrl(ticket, PublicUrlResolver.resolveConfiguredAppBaseUrl());
  }

  /**
   * Same as {@link #handoffUrl(String)} with an explicit app base URL.
   *
   * @param ticket     the plaintext pass
   * @param appBaseUrl the public GO app base URL
   * @return the URL, or {@code null} when either is blank
   */
  public static String handoffUrl(String ticket, String appBaseUrl) {
    String page = PublicUrlResolver.appendPath(appBaseUrl, HANDOFF_APP_PATH);
    if (page == null || StringUtils.isBlank(ticket)) {
      return null;
    }
    // base64url without padding is already URL-safe: no encoding needed.
    return page + "#" + HANDOFF_FRAGMENT_KEY + "=" + ticket;
  }

  /**
   * SHA-256 of a client IP, the form {@code IP_HASH} columns keep.
   *
   * @param ip the IP, may be null
   * @return its hash, or {@code null}
   */
  public static String hashIp(String ip) {
    String trimmed = StringUtils.trimToNull(ip);
    return trimmed == null ? null : OAuth2Utils.hashToken(trimmed);
  }

  /**
   * The total duration of a session opened by the access.
   *
   * @param accessRecord the access
   * @return its duration
   */
  public static Duration sessionLifetime(SupportAccessRecord accessRecord) {
    return Duration.ofMinutes(accessRecord.getDurationMinutes());
  }

  private int resolveDuration(Integer requested) {
    int max = getMaxDurationMinutes();
    int minutes = requested == null ? getDefaultDurationMinutes() : requested;
    if (minutes <= 0 || minutes > max) {
      throw new SupportAccessException(SupportAccessException.CODE_DURATION_INVALID,
          "The duration must be between 1 and " + max + " minutes");
    }
    return minutes;
  }

  private int readMinutes(String preference, int fallback) {
    String value = StringUtils.trimToNull(store.findSystemPreference(preference));
    if (value == null) {
      return fallback;
    }
    try {
      int minutes = Integer.parseInt(value);
      return minutes > 0 ? minutes : fallback;
    } catch (NumberFormatException e) {
      log.warn("Preference {} is not a number ({}); using {}", preference, value, fallback);
      return fallback;
    }
  }

  private String newTicket() {
    byte[] bytes = new byte[TICKET_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }
}
