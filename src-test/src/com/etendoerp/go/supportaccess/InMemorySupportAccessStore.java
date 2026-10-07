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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link SupportAccessStore} reproducing the SQL semantics the service relies on: one
 * open access per tenant, single-use pass, closing without a live session. Test-only.
 */
class InMemorySupportAccessStore implements SupportAccessStore {

  final Map<String, SupportAccessRecord> rows = new HashMap<>();
  final Set<String> eligibleClients = new HashSet<>();
  final Set<String> operators = new HashSet<>();
  final Map<String, String> preferences = new HashMap<>();
  final Map<String, String> operatorNames = new HashMap<>();
  final Map<String, String> clientNames = new HashMap<>();
  /** Access ids that still have a live session, and until when. */
  final Map<String, Instant> liveSessions = new HashMap<>();
  final List<String> revokedSessionsFor = new ArrayList<>();
  boolean supportAccountReady = true;
  /** When set, the next insert loses the race as if another operator got in first. */
  SupportAccessRecord raceWinner;

  @Override
  public boolean isEligibleTarget(String clientId) {
    return eligibleClients.contains(clientId);
  }

  @Override
  public boolean holdsOperatorRole(String operatorUserId) {
    return operators.contains(operatorUserId);
  }

  @Override
  public boolean isSupportAccountReady() {
    return supportAccountReady;
  }

  @Override
  public String findSystemPreference(String property) {
    return preferences.get(property);
  }

  @Override
  public synchronized int closeStaleAccesses(String clientId, Instant now) {
    int closed = 0;
    for (SupportAccessRecord row : rows.values()) {
      if (!clientId.equals(row.getTargetClientId()) || row.getEndedAt() != null) {
        continue;
      }
      if (row.getTicketUsedAt() == null && !row.getTicketExpiresAt().isAfter(now)) {
        end(row, SupportEndReason.TICKET_EXPIRED, null, now);
        closed++;
      } else if (row.getTicketUsedAt() != null && !isLive(row.getId(), now)) {
        end(row, SupportEndReason.EXPIRED, null, now);
        liveSessions.remove(row.getId());
        closed++;
      }
    }
    return closed;
  }

  private boolean isLive(String accessId, Instant now) {
    Instant until = liveSessions.get(accessId);
    return until != null && until.isAfter(now);
  }

  @Override
  public synchronized SupportAccessRecord findOpenAccess(String clientId) {
    for (SupportAccessRecord row : rows.values()) {
      if (clientId.equals(row.getTargetClientId()) && row.getEndedAt() == null) {
        SupportAccessRecord copy = copy(row);
        copy.setOperatorName(operatorNames.get(row.getOperatorUserId()));
        copy.setSessionExpiresAt(row.getTicketUsedAt() == null ? row.getTicketExpiresAt()
            : liveSessions.get(row.getId()));
        return copy;
      }
    }
    return null;
  }

  @Override
  public synchronized boolean insert(SupportAccessRecord accessRecord) {
    if (raceWinner != null) {
      rows.put(raceWinner.getId(), raceWinner);
      raceWinner = null;
      return false;
    }
    for (SupportAccessRecord row : rows.values()) {
      boolean openConflict = row.getEndedAt() == null
          && row.getTargetClientId().equals(accessRecord.getTargetClientId());
      if (openConflict || row.getTicketHash().equals(accessRecord.getTicketHash())) {
        return false;
      }
    }
    rows.put(accessRecord.getId(), copy(accessRecord));
    return true;
  }

  @Override
  public synchronized SupportAccessRecord consumeTicket(String ticketHash, Instant now) {
    for (SupportAccessRecord row : rows.values()) {
      if (row.getTicketHash().equals(ticketHash) && row.getTicketUsedAt() == null
          && row.getTicketExpiresAt().isAfter(now) && row.getEndedAt() == null) {
        row.setTicketUsedAt(now);
        return copy(row);
      }
    }
    return null;
  }

  @Override
  public synchronized SupportAccessRecord findByTicketHash(String ticketHash) {
    for (SupportAccessRecord row : rows.values()) {
      if (row.getTicketHash().equals(ticketHash)) {
        return copy(row);
      }
    }
    return null;
  }

  @Override
  public synchronized SupportAccessRecord findById(String accessId) {
    SupportAccessRecord row = rows.get(accessId);
    return row == null ? null : copy(row);
  }

  @Override
  public synchronized void markStarted(String accessId, String sessionId, Instant startedAt) {
    SupportAccessRecord row = rows.get(accessId);
    row.setSessionId(sessionId);
    row.setStartedAt(startedAt);
  }

  @Override
  public synchronized boolean close(String accessId, SupportEndReason reason,
      String endedByUserId, Instant now) {
    SupportAccessRecord row = rows.get(accessId);
    if (row == null || row.getEndedAt() != null) {
      return false;
    }
    end(row, reason, endedByUserId, now);
    return true;
  }

  @Override
  public synchronized int revokeSessions(String accessId) {
    revokedSessionsFor.add(accessId);
    return liveSessions.remove(accessId) == null ? 0 : 1;
  }

  @Override
  public String findClientName(String clientId) {
    return clientNames.get(clientId);
  }

  private static void end(SupportAccessRecord row, SupportEndReason reason, String byUserId,
      Instant now) {
    row.setEndedAt(now);
    row.setEndReason(reason.getCode());
    row.setEndedByUserId(byUserId);
  }

  static SupportAccessRecord copy(SupportAccessRecord source) {
    SupportAccessRecord target = new SupportAccessRecord();
    target.setId(source.getId());
    target.setOperatorUserId(source.getOperatorUserId());
    target.setOperatorName(source.getOperatorName());
    target.setTargetClientId(source.getTargetClientId());
    target.setSupportUserId(source.getSupportUserId());
    target.setRoleId(source.getRoleId());
    target.setReason(source.getReason());
    target.setDurationMinutes(source.getDurationMinutes());
    target.setTicketHash(source.getTicketHash());
    target.setTicketExpiresAt(source.getTicketExpiresAt());
    target.setTicketUsedAt(source.getTicketUsedAt());
    target.setSessionId(source.getSessionId());
    target.setStartedAt(source.getStartedAt());
    target.setEndedAt(source.getEndedAt());
    target.setEndReason(source.getEndReason());
    target.setEndedByUserId(source.getEndedByUserId());
    target.setIpHash(source.getIpHash());
    target.setUserAgent(source.getUserAgent());
    target.setSessionExpiresAt(source.getSessionExpiresAt());
    return target;
  }
}
