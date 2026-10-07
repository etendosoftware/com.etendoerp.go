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
 * In-memory view of one {@code ETGO_SUPPORT_ACCESS} row (ETP-5351): who entered which tenant, why,
 * as which support user and role, through which one-time pass, and how it ended.
 *
 * <p>Only the SHA-256 of the pass is ever held here; the plaintext pass exists once, in the
 * {@link IssuedSupportTicket} returned to the operator.</p>
 */
public class SupportAccessRecord {

  private String id;
  private String operatorUserId;
  private String operatorName;
  private String targetClientId;
  private String supportUserId;
  private String roleId;
  private String reason;
  private int durationMinutes;
  private String ticketHash;
  private Instant ticketExpiresAt;
  private Instant ticketUsedAt;
  private String sessionId;
  private Instant startedAt;
  private Instant endedAt;
  private String endReason;
  private String endedByUserId;
  private String ipHash;
  private String userAgent;
  private Instant sessionExpiresAt;

  /** @return the {@code ETGO_SUPPORT_ACCESS_ID} */
  public String getId() {
    return id;
  }

  /** @param id the {@code ETGO_SUPPORT_ACCESS_ID} */
  public void setId(String id) {
    this.id = id;
  }

  /** @return the {@code AD_User_ID} (client 0) of the operator who asked for the access */
  public String getOperatorUserId() {
    return operatorUserId;
  }

  /** @param operatorUserId the operator's {@code AD_User_ID} */
  public void setOperatorUserId(String operatorUserId) {
    this.operatorUserId = operatorUserId;
  }

  /** @return the operator's display name, when the query joined it; otherwise {@code null} */
  public String getOperatorName() {
    return operatorName;
  }

  /** @param operatorName the operator's display name */
  public void setOperatorName(String operatorName) {
    this.operatorName = operatorName;
  }

  /** @return the {@code AD_Client_ID} of the tenant entered */
  public String getTargetClientId() {
    return targetClientId;
  }

  /** @param targetClientId the tenant's {@code AD_Client_ID} */
  public void setTargetClientId(String targetClientId) {
    this.targetClientId = targetClientId;
  }

  /** @return the tenant's "Soporte Etendo" {@code AD_User_ID} */
  public String getSupportUserId() {
    return supportUserId;
  }

  /** @param supportUserId the tenant's support {@code AD_User_ID} */
  public void setSupportUserId(String supportUserId) {
    this.supportUserId = supportUserId;
  }

  /** @return the {@code AD_Role_ID} the support user acts with */
  public String getRoleId() {
    return roleId;
  }

  /** @param roleId the {@code AD_Role_ID} the support user acts with */
  public void setRoleId(String roleId) {
    this.roleId = roleId;
  }

  /** @return the reason the operator gave */
  public String getReason() {
    return reason;
  }

  /** @param reason the reason the operator gave */
  public void setReason(String reason) {
    this.reason = reason;
  }

  /** @return the requested total session duration, in minutes */
  public int getDurationMinutes() {
    return durationMinutes;
  }

  /** @param durationMinutes the requested total session duration, in minutes */
  public void setDurationMinutes(int durationMinutes) {
    this.durationMinutes = durationMinutes;
  }

  /** @return the SHA-256 (hex) of the one-time pass */
  public String getTicketHash() {
    return ticketHash;
  }

  /** @param ticketHash the SHA-256 (hex) of the one-time pass */
  public void setTicketHash(String ticketHash) {
    this.ticketHash = ticketHash;
  }

  /** @return when the pass stops being redeemable */
  public Instant getTicketExpiresAt() {
    return ticketExpiresAt;
  }

  /** @param ticketExpiresAt when the pass stops being redeemable */
  public void setTicketExpiresAt(Instant ticketExpiresAt) {
    this.ticketExpiresAt = ticketExpiresAt;
  }

  /** @return when the pass was redeemed, or {@code null} */
  public Instant getTicketUsedAt() {
    return ticketUsedAt;
  }

  /** @param ticketUsedAt when the pass was redeemed */
  public void setTicketUsedAt(Instant ticketUsedAt) {
    this.ticketUsedAt = ticketUsedAt;
  }

  /** @return the first {@code ETGO_GO_SESSION_ID} opened with the pass, or {@code null} */
  public String getSessionId() {
    return sessionId;
  }

  /** @param sessionId the first {@code ETGO_GO_SESSION_ID} opened with the pass */
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  /** @return when the support session started, or {@code null} */
  public Instant getStartedAt() {
    return startedAt;
  }

  /** @param startedAt when the support session started */
  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  /** @return when the access ended, or {@code null} while open */
  public Instant getEndedAt() {
    return endedAt;
  }

  /** @param endedAt when the access ended */
  public void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }

  /** @return the {@link SupportEndReason} code, or {@code null} while open */
  public String getEndReason() {
    return endReason;
  }

  /** @param endReason the {@link SupportEndReason} code */
  public void setEndReason(String endReason) {
    this.endReason = endReason;
  }

  /** @return the {@code AD_User_ID} that revoked the access, when it was revoked */
  public String getEndedByUserId() {
    return endedByUserId;
  }

  /** @param endedByUserId the {@code AD_User_ID} that revoked the access */
  public void setEndedByUserId(String endedByUserId) {
    this.endedByUserId = endedByUserId;
  }

  /** @return the SHA-256 of the operator's IP when the pass was issued, or {@code null} */
  public String getIpHash() {
    return ipHash;
  }

  /** @param ipHash the SHA-256 of the operator's IP */
  public void setIpHash(String ipHash) {
    this.ipHash = ipHash;
  }

  /** @return the operator's user agent when the pass was issued, or {@code null} */
  public String getUserAgent() {
    return userAgent;
  }

  /** @param userAgent the operator's user agent */
  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  /**
   * Derived, not a column: until the pass is redeemed, the pass expiry; afterwards, the absolute
   * expiry of the live support session. Filled by the "open access" lookups only.
   *
   * @return until when the tenant is held by this access, or {@code null} when unknown
   */
  public Instant getSessionExpiresAt() {
    return sessionExpiresAt;
  }

  /** @param sessionExpiresAt until when the tenant is held by this access */
  public void setSessionExpiresAt(Instant sessionExpiresAt) {
    this.sessionExpiresAt = sessionExpiresAt;
  }
}
