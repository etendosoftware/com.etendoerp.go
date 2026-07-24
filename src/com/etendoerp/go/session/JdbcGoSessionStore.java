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

  private static final String SELECT_COLUMNS =
      "SELECT etgo_go_session_id, etgo_account_id, session_token_hash, csrf_token, refresh_token_hash, "
      + "auth_method, ad_user_id, ad_role_id, ctx_client_id, ctx_org_id, m_warehouse_id, "
      + "expires_at, absolute_expires_at, is_revoked, rotated_from_id, user_agent, ip_hash "
      + "FROM etgo_go_session ";

  private static final String FIND_BY_TOKEN_HASH_SQL = SELECT_COLUMNS + "WHERE session_token_hash = ?";
  private static final String FIND_BY_REFRESH_HASH_SQL = SELECT_COLUMNS + "WHERE refresh_token_hash = ?";
  private static final String FIND_BY_ROTATED_FROM_SQL = SELECT_COLUMNS + "WHERE rotated_from_id = ?";

  @Override
  public void save(GoSessionRecord record) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
        ps.setString(1, record.getId());
        ps.setString(2, record.getAccountId());
        ps.setString(3, record.getSessionTokenHash());
        ps.setString(4, record.getCsrfToken());
        ps.setString(5, record.getRefreshTokenHash());
        ps.setString(6, record.getAuthMethod());
        ps.setString(7, record.getUserId());
        ps.setString(8, record.getRoleId());
        ps.setString(9, record.getCtxClientId());
        ps.setString(10, record.getCtxOrgId());
        ps.setString(11, record.getWarehouseId());
        ps.setTimestamp(12, toTimestamp(record.getExpiresAt()));
        ps.setTimestamp(13, toTimestamp(record.getAbsoluteExpiresAt()));
        ps.setString(14, record.isRevoked() ? "Y" : "N");
        ps.setString(15, record.getRotatedFromId());
        ps.setString(16, record.getUserAgent());
        ps.setString(17, record.getIpHash());
        ps.executeUpdate();
      }
    });
  }

  @Override
  public void update(GoSessionRecord record) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
        ps.setString(1, record.getSessionTokenHash());
        ps.setString(2, record.getCsrfToken());
        ps.setString(3, record.getRefreshTokenHash());
        ps.setString(4, record.getAuthMethod());
        ps.setString(5, record.getUserId());
        ps.setString(6, record.getRoleId());
        ps.setString(7, record.getCtxClientId());
        ps.setString(8, record.getCtxOrgId());
        ps.setString(9, record.getWarehouseId());
        ps.setTimestamp(10, toTimestamp(record.getExpiresAt()));
        ps.setTimestamp(11, toTimestamp(record.getAbsoluteExpiresAt()));
        ps.setString(12, record.isRevoked() ? "Y" : "N");
        ps.setString(13, record.getRotatedFromId());
        ps.setString(14, record.getUserAgent());
        ps.setString(15, record.getIpHash());
        ps.setString(16, record.getId());
        ps.executeUpdate();
      }
    });
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
    GoSessionRecord record = new GoSessionRecord();
    record.setId(rs.getString("etgo_go_session_id"));
    record.setAccountId(rs.getString("etgo_account_id"));
    record.setSessionTokenHash(rs.getString("session_token_hash"));
    record.setCsrfToken(rs.getString("csrf_token"));
    record.setRefreshTokenHash(rs.getString("refresh_token_hash"));
    record.setAuthMethod(rs.getString("auth_method"));
    record.setUserId(rs.getString("ad_user_id"));
    record.setRoleId(rs.getString("ad_role_id"));
    record.setCtxClientId(rs.getString("ctx_client_id"));
    record.setCtxOrgId(rs.getString("ctx_org_id"));
    record.setWarehouseId(rs.getString("m_warehouse_id"));
    record.setExpiresAt(toInstant(rs.getTimestamp("expires_at")));
    record.setAbsoluteExpiresAt(toInstant(rs.getTimestamp("absolute_expires_at")));
    record.setRevoked("Y".equals(rs.getString("is_revoked")));
    record.setRotatedFromId(rs.getString("rotated_from_id"));
    record.setUserAgent(rs.getString("user_agent"));
    record.setIpHash(rs.getString("ip_hash"));
    return record;
  }

  private static Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
