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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.openbravo.dal.service.OBDal;

/**
 * JDBC-backed {@link SupportAccessStore} (ETP-5351).
 *
 * <p>Runs on the Hibernate session's JDBC connection, like {@code JdbcGoSessionStore}: it neither
 * opens nor closes connections and takes part in the request's transaction. Native SQL on purpose:
 * the ETP-5351 columns have no generated DAL properties until the entities are regenerated, and
 * the race-sensitive statements (one open access per tenant, one redemption per pass) need
 * {@code ON CONFLICT} and {@code UPDATE ... RETURNING}, which HQL does not offer. Every value is a
 * bind parameter.</p>
 *
 * <p>Rows are System-owned technical records ({@code ad_client_id = '0'}, {@code ad_org_id = '0'});
 * the tenant they concern is {@code target_client_id}.</p>
 */
public class JdbcSupportAccessStore implements SupportAccessStore {

  /** {@code AD_Client_ID} of the onboarding template tenant, excluded like in ETGO_SUPPORT_CLIENT_V. */
  static final String TEMPLATE_CLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";
  private static final String SYSTEM_ID = "0";

  private static final String UPDATE_ACCESS = "UPDATE etgo_support_access ";
  private static final String SELECT = "SELECT ";
  private static final String ACCESS_COLUMNS = "a.etgo_support_access_id, a.operator_user_id, "
      + "a.target_client_id, a.support_user_id, a.ad_role_id, a.reason, a.duration_minutes, "
      + "a.ticket_hash, a.ticket_expires_at, a.ticket_used_at, a.etgo_go_session_id, a.started_at, "
      + "a.ended_at, a.end_reason, a.ended_by_user_id, a.ip_hash, a.user_agent";

  private static final String ELIGIBLE_TARGET_SQL = "SELECT 1 FROM ad_client c "
      + "WHERE c.ad_client_id = ? AND c.isactive = 'Y' AND c.ad_client_id <> ? "
      + "AND c.ad_client_id <> ? AND NOT EXISTS (SELECT 1 FROM etgo_tenant_pool tp "
      + "WHERE tp.pool_client_id = c.ad_client_id AND tp.status <> 'CLAIMED')";

  private static final String OPERATOR_ROLE_SQL = "SELECT 1 FROM ad_user_roles ur "
      + "JOIN ad_user u ON u.ad_user_id = ur.ad_user_id "
      + "JOIN ad_role r ON r.ad_role_id = ur.ad_role_id "
      + "WHERE ur.ad_user_id = ? AND ur.ad_role_id = ? AND ur.isactive = 'Y' "
      + "AND u.isactive = 'Y' AND r.isactive = 'Y'";

  private static final String SUPPORT_ACCOUNT_READY_SQL = "SELECT 1 FROM etgo_account "
      + "WHERE etgo_account_id = ? AND isactive = 'Y' AND is_support_account = 'Y'";

  private static final String SYSTEM_PREFERENCE_SQL = "SELECT p.value FROM ad_preference p "
      + "WHERE p.property = ? AND p.isactive = 'Y' AND p.ad_client_id = ? "
      + "ORDER BY p.updated DESC LIMIT 1";

  private static final String CLOSE_UNREDEEMED_SQL = UPDATE_ACCESS
      + "SET ended_at = ?, end_reason = ?, updated = now(), updatedby = ? "
      + "WHERE target_client_id = ? AND ended_at IS NULL AND ticket_used_at IS NULL "
      + "AND ticket_expires_at <= ?";

  private static final String CLOSE_WITHOUT_LIVE_SESSION_SQL = "UPDATE etgo_support_access a "
      + "SET ended_at = ?, end_reason = ?, updated = now(), updatedby = ? "
      + "WHERE a.target_client_id = ? AND a.ended_at IS NULL AND a.ticket_used_at IS NOT NULL "
      + "AND NOT EXISTS (SELECT 1 FROM etgo_go_session s "
      + "WHERE s.etgo_support_access_id = a.etgo_support_access_id AND s.is_revoked = 'N' "
      + "AND s.expires_at > ? AND s.absolute_expires_at > ?)";

  private static final String REVOKE_CLOSED_SESSIONS_SQL = "UPDATE etgo_go_session "
      + "SET is_revoked = 'Y', updated = now(), updatedby = '0' "
      + "WHERE is_revoked = 'N' AND etgo_support_access_id IN ("
      + "SELECT a.etgo_support_access_id FROM etgo_support_access a "
      + "WHERE a.target_client_id = ? AND a.ended_at IS NOT NULL)";

  private static final String FIND_OPEN_SQL = SELECT + ACCESS_COLUMNS
      + ", u.name AS operator_name, CASE WHEN a.ticket_used_at IS NULL THEN a.ticket_expires_at "
      + "ELSE (SELECT max(s.absolute_expires_at) FROM etgo_go_session s "
      + "WHERE s.etgo_support_access_id = a.etgo_support_access_id AND s.is_revoked = 'N') "
      + "END AS session_expires_at "
      + "FROM etgo_support_access a LEFT JOIN ad_user u ON u.ad_user_id = a.operator_user_id "
      + "WHERE a.target_client_id = ? AND a.ended_at IS NULL "
      + "ORDER BY a.created DESC LIMIT 1";

  private static final String INSERT_SQL = "INSERT INTO etgo_support_access "
      + "(etgo_support_access_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, "
      + "updatedby, operator_user_id, target_client_id, support_user_id, ad_role_id, reason, "
      + "duration_minutes, ticket_hash, ticket_expires_at, ip_hash, user_agent) "
      + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
      + "ON CONFLICT DO NOTHING";

  private static final String CONSUME_TICKET_SQL = "UPDATE etgo_support_access a "
      + "SET ticket_used_at = ?, updated = now() "
      + "WHERE a.ticket_hash = ? AND a.ticket_used_at IS NULL AND a.ticket_expires_at > ? "
      + "AND a.ended_at IS NULL RETURNING " + ACCESS_COLUMNS;

  private static final String FIND_BY_TICKET_SQL = SELECT + ACCESS_COLUMNS
      + " FROM etgo_support_access a WHERE a.ticket_hash = ?";

  private static final String FIND_BY_ID_SQL = SELECT + ACCESS_COLUMNS
      + " FROM etgo_support_access a WHERE a.etgo_support_access_id = ?";

  private static final String MARK_STARTED_SQL = UPDATE_ACCESS
      + "SET etgo_go_session_id = ?, started_at = ?, updated = now() "
      + "WHERE etgo_support_access_id = ?";

  private static final String CLOSE_SQL = UPDATE_ACCESS
      + "SET ended_at = ?, end_reason = ?, ended_by_user_id = ?, updated = now(), "
      + "updatedby = COALESCE(?, updatedby) "
      + "WHERE etgo_support_access_id = ? AND ended_at IS NULL";

  private static final String REVOKE_SESSIONS_SQL = "UPDATE etgo_go_session "
      + "SET is_revoked = 'Y', updated = now(), updatedby = '0' "
      + "WHERE etgo_support_access_id = ? AND is_revoked = 'N'";

  private static final String CLIENT_NAME_SQL = "SELECT name FROM ad_client WHERE ad_client_id = ?";

  @Override
  public boolean isEligibleTarget(String clientId) {
    return exists(ELIGIBLE_TARGET_SQL, clientId, SYSTEM_ID, TEMPLATE_CLIENT_ID);
  }

  @Override
  public boolean holdsOperatorRole(String operatorUserId) {
    return exists(OPERATOR_ROLE_SQL, operatorUserId, SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID);
  }

  @Override
  public boolean isSupportAccountReady() {
    return exists(SUPPORT_ACCOUNT_READY_SQL, SupportAccessGuard.SUPPORT_ACCOUNT_ID);
  }

  @Override
  public String findSystemPreference(String property) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(SYSTEM_PREFERENCE_SQL)) {
        ps.setString(1, property);
        ps.setString(2, SYSTEM_ID);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    });
  }

  @Override
  public int closeStaleAccesses(String clientId, Instant now) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      int closed;
      try (PreparedStatement ps = connection.prepareStatement(CLOSE_UNREDEEMED_SQL)) {
        ps.setTimestamp(1, toTimestamp(now));
        ps.setString(2, SupportEndReason.TICKET_EXPIRED.getCode());
        ps.setString(3, SYSTEM_ID);
        ps.setString(4, clientId);
        ps.setTimestamp(5, toTimestamp(now));
        closed = ps.executeUpdate();
      }
      try (PreparedStatement ps = connection.prepareStatement(CLOSE_WITHOUT_LIVE_SESSION_SQL)) {
        ps.setTimestamp(1, toTimestamp(now));
        ps.setString(2, SupportEndReason.EXPIRED.getCode());
        ps.setString(3, SYSTEM_ID);
        ps.setString(4, clientId);
        ps.setTimestamp(5, toTimestamp(now));
        ps.setTimestamp(6, toTimestamp(now));
        closed += ps.executeUpdate();
      }
      try (PreparedStatement ps = connection.prepareStatement(REVOKE_CLOSED_SESSIONS_SQL)) {
        ps.setString(1, clientId);
        ps.executeUpdate();
      }
      return closed;
    });
  }

  @Override
  public SupportAccessRecord findOpenAccess(String clientId) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(FIND_OPEN_SQL)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return null;
          }
          SupportAccessRecord accessRecord = mapRow(rs);
          accessRecord.setOperatorName(rs.getString("operator_name"));
          accessRecord.setSessionExpiresAt(toInstant(rs.getTimestamp("session_expires_at")));
          return accessRecord;
        }
      }
    });
  }

  @Override
  public boolean insert(SupportAccessRecord accessRecord) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
        int i = 1;
        ps.setString(i++, accessRecord.getId());
        ps.setString(i++, SYSTEM_ID);
        ps.setString(i++, SYSTEM_ID);
        ps.setString(i++, accessRecord.getOperatorUserId());
        ps.setString(i++, accessRecord.getOperatorUserId());
        ps.setString(i++, accessRecord.getOperatorUserId());
        ps.setString(i++, accessRecord.getTargetClientId());
        ps.setString(i++, accessRecord.getSupportUserId());
        ps.setString(i++, accessRecord.getRoleId());
        ps.setString(i++, accessRecord.getReason());
        ps.setInt(i++, accessRecord.getDurationMinutes());
        ps.setString(i++, accessRecord.getTicketHash());
        ps.setTimestamp(i++, toTimestamp(accessRecord.getTicketExpiresAt()));
        ps.setString(i++, accessRecord.getIpHash());
        ps.setString(i, accessRecord.getUserAgent());
        return ps.executeUpdate() == 1;
      }
    });
  }

  @Override
  public SupportAccessRecord consumeTicket(String ticketHash, Instant now) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(CONSUME_TICKET_SQL)) {
        ps.setTimestamp(1, toTimestamp(now));
        ps.setString(2, ticketHash);
        ps.setTimestamp(3, toTimestamp(now));
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? mapRow(rs) : null;
        }
      }
    });
  }

  @Override
  public SupportAccessRecord findByTicketHash(String ticketHash) {
    return findOne(FIND_BY_TICKET_SQL, ticketHash);
  }

  @Override
  public SupportAccessRecord findById(String accessId) {
    return findOne(FIND_BY_ID_SQL, accessId);
  }

  @Override
  public void markStarted(String accessId, String sessionId, Instant startedAt) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(MARK_STARTED_SQL)) {
        ps.setString(1, sessionId);
        ps.setTimestamp(2, toTimestamp(startedAt));
        ps.setString(3, accessId);
        ps.executeUpdate();
      }
    });
  }

  @Override
  public boolean close(String accessId, SupportEndReason reason, String endedByUserId,
      Instant now) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(CLOSE_SQL)) {
        ps.setTimestamp(1, toTimestamp(now));
        ps.setString(2, reason.getCode());
        ps.setString(3, endedByUserId);
        ps.setString(4, endedByUserId);
        ps.setString(5, accessId);
        return ps.executeUpdate() == 1;
      }
    });
  }

  @Override
  public int revokeSessions(String accessId) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(REVOKE_SESSIONS_SQL)) {
        ps.setString(1, accessId);
        return ps.executeUpdate();
      }
    });
  }

  @Override
  public String findClientName(String clientId) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(CLIENT_NAME_SQL)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    });
  }

  private SupportAccessRecord findOne(String sql, String key) {
    if (key == null) {
      return null;
    }
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, key);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? mapRow(rs) : null;
        }
      }
    });
  }

  private static boolean exists(String sql, String... params) {
    return OBDal.getInstance().getSession().doReturningWork(
        (Connection connection) -> {
          try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
              ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        });
  }

  private static SupportAccessRecord mapRow(ResultSet rs) throws SQLException {
    SupportAccessRecord accessRecord = new SupportAccessRecord();
    accessRecord.setId(rs.getString("etgo_support_access_id"));
    accessRecord.setOperatorUserId(rs.getString("operator_user_id"));
    accessRecord.setTargetClientId(rs.getString("target_client_id"));
    accessRecord.setSupportUserId(rs.getString("support_user_id"));
    accessRecord.setRoleId(rs.getString("ad_role_id"));
    accessRecord.setReason(rs.getString("reason"));
    accessRecord.setDurationMinutes(rs.getInt("duration_minutes"));
    accessRecord.setTicketHash(rs.getString("ticket_hash"));
    accessRecord.setTicketExpiresAt(toInstant(rs.getTimestamp("ticket_expires_at")));
    accessRecord.setTicketUsedAt(toInstant(rs.getTimestamp("ticket_used_at")));
    accessRecord.setSessionId(rs.getString("etgo_go_session_id"));
    accessRecord.setStartedAt(toInstant(rs.getTimestamp("started_at")));
    accessRecord.setEndedAt(toInstant(rs.getTimestamp("ended_at")));
    accessRecord.setEndReason(rs.getString("end_reason"));
    accessRecord.setEndedByUserId(rs.getString("ended_by_user_id"));
    accessRecord.setIpHash(rs.getString("ip_hash"));
    accessRecord.setUserAgent(rs.getString("user_agent"));
    return accessRecord;
  }

  private static Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
