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

import java.time.Instant;

/**
 * ETP-5351 — persistence port of {@link SupportAccessService} over {@code ETGO_SUPPORT_ACCESS}
 * and the few neighbouring reads it needs. Kept behind an interface, like
 * {@code GoSessionStore}, so the service logic is unit-testable with an in-memory fake; the
 * production implementation is {@link JdbcSupportAccessStore}.
 *
 * <p>Every time passed in comes from the JVM clock, the same clock {@code ETGO_GO_SESSION}'s expiry
 * columns are written with, so comparisons never mix time zones.</p>
 */
public interface SupportAccessStore {

  /**
   * Whether the tenant may be entered: it exists, is active, and is neither System, the
   * onboarding template nor a pool tenant nobody claimed.
   *
   * @param clientId the tenant's {@code AD_Client_ID}
   * @return {@code true} when support may enter it
   */
  boolean isEligibleTarget(String clientId);

  /**
   * Whether the operator holds the active "Soporte Etendo GO" role.
   *
   * @param operatorUserId the operator's {@code AD_User_ID}
   * @return {@code true} when the operator may request an access
   */
  boolean holdsOperatorRole(String operatorUserId);

  /**
   * Whether the technical support account exists, is active and is flagged as such.
   *
   * @return {@code true} when support sessions can be opened
   */
  boolean isSupportAccountReady();

  /**
   * Reads a System-level preference value.
   *
   * @param property the {@code AD_Preference.Property}
   * @return its active value, or {@code null} when unset
   */
  String findSystemPreference(String property);

  /**
   * Closes the tenant's open accesses that no longer hold it: an unredeemed pass past its expiry
   * ({@code ticket_expired}), or a redeemed one without a live session ({@code expired}). Revokes
   * any session still attached to a closed access, so a refresh cannot bring it back.
   *
   * @param clientId the tenant's {@code AD_Client_ID}
   * @param now      the current time
   * @return the number of accesses closed
   */
  int closeStaleAccesses(String clientId, Instant now);

  /**
   * The tenant's open access, with the operator's name and the derived
   * {@link SupportAccessRecord#getSessionExpiresAt() session expiry}.
   *
   * @param clientId the tenant's {@code AD_Client_ID}
   * @return the open access, or {@code null}
   */
  SupportAccessRecord findOpenAccess(String clientId);

  /**
   * Inserts a new open access. Never throws on a uniqueness conflict.
   *
   * @param accessRecord the row to insert
   * @return {@code false} when a unique constraint (one open access per tenant, ticket hash)
   *     refused it
   */
  boolean insert(SupportAccessRecord accessRecord);

  /**
   * Atomically marks the pass used: only an unused, unexpired pass of an open access matches.
   * Concurrent calls with the same pass see exactly one winner.
   *
   * @param ticketHash the pass hash
   * @param now        the current time, stored as {@code TICKET_USED_AT}
   * @return the access the pass belongs to, or {@code null} when nothing was updated
   */
  SupportAccessRecord consumeTicket(String ticketHash, Instant now);

  /**
   * Looks an access up by its pass hash.
   *
   * @param ticketHash the pass hash
   * @return the access, or {@code null}
   */
  SupportAccessRecord findByTicketHash(String ticketHash);

  /**
   * Looks an access up by id.
   *
   * @param accessId the {@code ETGO_SUPPORT_ACCESS_ID}
   * @return the access, or {@code null}
   */
  SupportAccessRecord findById(String accessId);

  /**
   * Records the support session the pass opened.
   *
   * @param accessId  the access
   * @param sessionId the first {@code ETGO_GO_SESSION_ID}
   * @param startedAt when it started
   */
  void markStarted(String accessId, String sessionId, Instant startedAt);

  /**
   * Closes an open access. A no-op on an access already closed.
   *
   * @param accessId      the access
   * @param reason        why it ended
   * @param endedByUserId who closed it, for {@link SupportEndReason#REVOKED}; otherwise
   *                      {@code null}
   * @param now           the current time
   * @return {@code true} when this call closed it
   */
  boolean close(String accessId, SupportEndReason reason, String endedByUserId, Instant now);

  /**
   * Revokes every live session opened for the access.
   *
   * @param accessId the access
   * @return the number of sessions revoked
   */
  int revokeSessions(String accessId);

  /**
   * The tenant's display name.
   *
   * @param clientId the {@code AD_Client_ID}
   * @return its name, or {@code null}
   */
  String findClientName(String clientId);
}
