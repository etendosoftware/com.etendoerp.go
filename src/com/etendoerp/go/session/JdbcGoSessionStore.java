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
package com.etendoerp.go.session;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.openbravo.dal.service.OBDal;

/**
 * JDBC-backed {@link GoSessionStore} over {@code ETGO_GO_SESSION} (ETP-4575).
 *
 * <p>Runs on the Hibernate session's JDBC connection (the one established by the request's
 * {@code DalRequestFilter}), mirroring the {@code OAuth2Filter} pattern — it neither opens nor
 * closes connections and participates in the current transaction. Session rows are system-owned
 * technical records ({@code ad_client_id = '0'}, {@code ad_org_id = '0'}).
 */
public class JdbcGoSessionStore implements GoSessionStore {

  private static final String INSERT_SQL =
      "INSERT INTO etgo_go_session "
      + "(etgo_go_session_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, "
      + "etgo_account_id, session_token_hash, csrf_token, refresh_token_hash, auth_method, "
      + "ad_user_id, ad_role_id, ctx_client_id, ctx_org_id, m_warehouse_id, "
      + "expires_at, absolute_expires_at, is_revoked, rotated_from_id, user_agent, ip_hash) "
      + "VALUES (?, '0', '0', 'Y', now(), '0', now(), '0', "
      + "?, ?, ?, ?, ?, "
      + "?, ?, ?, ?, ?, "
      + "?, ?, ?, ?, ?, ?)";

  private static final String UPDATE_SQL =
      "UPDATE etgo_go_session SET "
      + "session_token_hash = ?, csrf_token = ?, refresh_token_hash = ?, auth_method = ?, "
      + "ad_user_id = ?, ad_role_id = ?, ctx_client_id = ?, ctx_org_id = ?, m_warehouse_id = ?, "
      + "expires_at = ?, absolute_expires_at = ?, is_revoked = ?, rotated_from_id = ?, "
      + "user_agent = ?, ip_hash = ?, updated = now(), updatedby = '0' "
      + "WHERE etgo_go_session_id = ?";

  private static final String REVOKE_ACTIVE_SQL =
      "UPDATE etgo_go_session SET is_revoked = 'Y', updated = now(), updatedby = '0' "
      + "WHERE etgo_go_session_id = ? AND is_revoked = 'N'";

  private static final String SELECT_COLUMNS =
      "SELECT etgo_go_session_id, etgo_account_id, session_token_hash, csrf_token, refresh_token_hash, "
      + "auth_method, ad_user_id, ad_role_id, ctx_client_id, ctx_org_id, m_warehouse_id, "
      + "expires_at, absolute_expires_at, is_revoked, rotated_from_id, user_agent, ip_hash "
      + "FROM etgo_go_session ";

  private static final String FIND_BY_TOKEN_HASH_SQL = SELECT_COLUMNS + "WHERE session_token_hash = ?";
  private static final String FIND_BY_REFRESH_HASH_SQL = SELECT_COLUMNS + "WHERE refresh_token_hash = ?";
  private static final String FIND_BY_ROTATED_FROM_SQL = SELECT_COLUMNS + "WHERE rotated_from_id = ?";

  @Override
  public void save(GoSessionRecord sessionRecord) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
        ps.setString(1, sessionRecord.getId());
        ps.setString(2, sessionRecord.getAccountId());
        bindMutableFields(ps, 3, sessionRecord);
        ps.executeUpdate();
      }
    });
  }

  @Override
  public void update(GoSessionRecord sessionRecord) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
        int nextIndex = bindMutableFields(ps, 1, sessionRecord);
        ps.setString(nextIndex, sessionRecord.getId());
        ps.executeUpdate();
      }
    });
  }

  @Override
  public boolean rotateAtomically(GoSessionRecord current, GoSessionRecord successor) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement revoke = connection.prepareStatement(REVOKE_ACTIVE_SQL)) {
        revoke.setString(1, current.getId());
        if (revoke.executeUpdate() != 1) {
          return false;
        }
      }
      try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
        insert.setString(1, successor.getId());
        insert.setString(2, successor.getAccountId());
        bindMutableFields(insert, 3, successor);
        insert.executeUpdate();
      }
      current.setRevoked(true);
      return true;
    });
  }

  /**
   * Bind the mutable session columns (everything but the id/account, which differ between insert
   * and update) starting at {@code startIndex}, shared by {@link #save} and {@link #update}.
   *
   * @return the next unused parameter index
   */
  private static int bindMutableFields(PreparedStatement ps, int startIndex,
      GoSessionRecord sessionRecord) throws SQLException {
    int i = startIndex;
    ps.setString(i++, sessionRecord.getSessionTokenHash());
    ps.setString(i++, sessionRecord.getCsrfToken());
    ps.setString(i++, sessionRecord.getRefreshTokenHash());
    ps.setString(i++, sessionRecord.getAuthMethod());
    ps.setString(i++, sessionRecord.getUserId());
    ps.setString(i++, sessionRecord.getRoleId());
    ps.setString(i++, sessionRecord.getCtxClientId());
    ps.setString(i++, sessionRecord.getCtxOrgId());
    ps.setString(i++, sessionRecord.getWarehouseId());
    ps.setTimestamp(i++, toTimestamp(sessionRecord.getExpiresAt()));
    ps.setTimestamp(i++, toTimestamp(sessionRecord.getAbsoluteExpiresAt()));
    ps.setString(i++, sessionRecord.isRevoked() ? "Y" : "N");
    ps.setString(i++, sessionRecord.getRotatedFromId());
    ps.setString(i++, sessionRecord.getUserAgent());
    ps.setString(i++, sessionRecord.getIpHash());
    return i;
  }

  @Override
  public GoSessionRecord findByTokenHash(String sessionTokenHash) {
    return findBy(FIND_BY_TOKEN_HASH_SQL, sessionTokenHash);
  }

  @Override
  public GoSessionRecord findByRefreshTokenHash(String refreshTokenHash) {
    return findBy(FIND_BY_REFRESH_HASH_SQL, refreshTokenHash);
  }

  @Override
  public GoSessionRecord findByRotatedFromId(String sessionId) {
    return findBy(FIND_BY_ROTATED_FROM_SQL, sessionId);
  }

  private GoSessionRecord findBy(String sql, String key) {
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

  private static GoSessionRecord mapRow(ResultSet rs) throws java.sql.SQLException {
    GoSessionRecord sessionRecord = new GoSessionRecord();
    sessionRecord.setId(rs.getString("etgo_go_session_id"));
    sessionRecord.setAccountId(rs.getString("etgo_account_id"));
    sessionRecord.setSessionTokenHash(rs.getString("session_token_hash"));
    sessionRecord.setCsrfToken(rs.getString("csrf_token"));
    sessionRecord.setRefreshTokenHash(rs.getString("refresh_token_hash"));
    sessionRecord.setAuthMethod(rs.getString("auth_method"));
    sessionRecord.setUserId(rs.getString("ad_user_id"));
    sessionRecord.setRoleId(rs.getString("ad_role_id"));
    sessionRecord.setCtxClientId(rs.getString("ctx_client_id"));
    sessionRecord.setCtxOrgId(rs.getString("ctx_org_id"));
    sessionRecord.setWarehouseId(rs.getString("m_warehouse_id"));
    sessionRecord.setExpiresAt(toInstant(rs.getTimestamp("expires_at")));
    sessionRecord.setAbsoluteExpiresAt(toInstant(rs.getTimestamp("absolute_expires_at")));
    sessionRecord.setRevoked("Y".equals(rs.getString("is_revoked")));
    sessionRecord.setRotatedFromId(rs.getString("rotated_from_id"));
    sessionRecord.setUserAgent(rs.getString("user_agent"));
    sessionRecord.setIpHash(rs.getString("ip_hash"));
    return sessionRecord;
  }

  private static Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
