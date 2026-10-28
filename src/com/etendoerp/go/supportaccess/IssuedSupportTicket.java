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
 * ETP-5351 — the result of {@link SupportAccessService#issue}: the only moment the plaintext pass
 * exists. Show {@link #getUrl()} to the operator once; it is not stored anywhere.
 */
public final class IssuedSupportTicket {

  private final String accessId;
  private final String ticket;
  private final String url;
  private final Instant ticketExpiresAt;
  private final int durationMinutes;

  /**
   * Creates the result.
   *
   * @param accessId        the new {@code ETGO_SUPPORT_ACCESS_ID}
   * @param ticket          the plaintext pass (base64url, no padding)
   * @param url             the GO URL carrying the pass in its fragment, or {@code null} when no
   *                        public app base URL is configured
   * @param ticketExpiresAt when the pass stops being redeemable
   * @param durationMinutes the total session duration granted
   */
  public IssuedSupportTicket(String accessId, String ticket, String url, Instant ticketExpiresAt,
      int durationMinutes) {
    this.accessId = accessId;
    this.ticket = ticket;
    this.url = url;
    this.ticketExpiresAt = ticketExpiresAt;
    this.durationMinutes = durationMinutes;
  }

  /** @return the new {@code ETGO_SUPPORT_ACCESS_ID} */
  public String getAccessId() {
    return accessId;
  }

  /** @return the plaintext pass; never log it */
  public String getTicket() {
    return ticket;
  }

  /** @return the URL to open in a new tab, or {@code null} when the app URL is not configured */
  public String getUrl() {
    return url;
  }

  /** @return when the pass stops being redeemable */
  public Instant getTicketExpiresAt() {
    return ticketExpiresAt;
  }

  /** @return the total session duration granted, in minutes */
  public int getDurationMinutes() {
    return durationMinutes;
  }

  @Override
  public String toString() {
    // The pass is a credential: never let it reach a log line through toString().
    return "IssuedSupportTicket[accessId=" + accessId + ", expiresAt=" + ticketExpiresAt + "]";
  }
}
