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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.UUID;

import org.openbravo.dal.service.OBDal;

/**
 * JDBC-backed {@link SupportUserStore} (ETP-5351).
 *
 * <p>Native SQL on the request's Hibernate connection, like {@code OwnerSupport} and
 * {@code JdbcGoSessionStore}: {@code EM_ETGO_IS_SUPPORT} has no generated DAL property until the
 * entities are regenerated, and the insert has to be {@code ON CONFLICT DO NOTHING} so a lost race
 * against another operator never poisons the transaction. No DAL event handler runs for these
 * rows, which is intended: the support user must not get an invitation, a personal role or any
 * other side effect of a user created from the Users window. Every value is a bind parameter.</p>
 */
public class JdbcSupportUserStore implements SupportUserStore {

  private static final String SYSTEM_USER_ID = "0";
  private static final String STAR_ORG_ID = "0";

  private static final String ADMIN_ROLE_SQL = "SELECT r.ad_role_id FROM ad_role r "
      + "WHERE r.ad_client_id = ? AND r.is_client_admin = 'Y' AND r.isactive = 'Y' "
      + "ORDER BY r.created, r.ad_role_id LIMIT 1";

  private static final String ADMIN_REFERENCE_SQL = "SELECT u.default_ad_org_id, "
      + "u.default_m_warehouse_id, u.default_ad_language FROM ad_user u "
      + "JOIN ad_user_roles ur ON ur.ad_user_id = u.ad_user_id AND ur.isactive = 'Y' "
      + "JOIN ad_org o ON o.ad_org_id = u.default_ad_org_id AND o.isactive = 'Y' "
      + "WHERE u.ad_client_id = ? AND ur.ad_role_id = ? AND u.isactive = 'Y' "
      + "AND u.em_etgo_is_support = 'N' AND u.default_ad_org_id <> ? "
      + "ORDER BY CASE WHEN u.em_etgo_is_owner = 'Y' THEN 0 ELSE 1 END, u.created LIMIT 1";

  private static final String FIRST_ORG_SQL = "SELECT o.ad_org_id, (SELECT w.m_warehouse_id "
      + "FROM m_warehouse w WHERE w.ad_org_id = o.ad_org_id AND w.isactive = 'Y' "
      + "ORDER BY w.created LIMIT 1) AS m_warehouse_id FROM ad_org o "
      + "WHERE o.ad_client_id = ? AND o.ad_org_id <> ? AND o.isactive = 'Y' "
      + "ORDER BY o.created LIMIT 1";

  private static final String INSERT_USER_SQL = "INSERT INTO ad_user (ad_user_id, ad_client_id, "
      + "ad_org_id, isactive, created, createdby, updated, updatedby, name, username, password, "
      + "email, default_ad_client_id, default_ad_role_id, default_ad_org_id, "
      + "default_m_warehouse_id, default_ad_language, em_smfsws_default_ws_role_id, "
      + "em_etgo_is_owner, em_etgo_is_support) "
      + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, 'N', 'Y') "
      + "ON CONFLICT DO NOTHING";

  private static final String ALIGN_USER_SQL = "UPDATE ad_user SET isactive = 'Y', "
      + "password = NULL, email = NULL, em_etgo_is_owner = 'N', em_etgo_is_support = 'Y', "
      + "default_ad_client_id = ?, default_ad_role_id = ?, default_ad_org_id = ?, "
      + "default_m_warehouse_id = ?, em_smfsws_default_ws_role_id = ?, "
      + "updated = now(), updatedby = ? WHERE ad_user_id = ? AND ad_client_id = ?";

  private static final String INSERT_USER_ROLE_SQL = "INSERT INTO ad_user_roles "
      + "(ad_user_roles_id, ad_user_id, ad_role_id, ad_client_id, ad_org_id, isactive, created, "
      + "createdby, updated, updatedby, is_role_admin) "
      + "VALUES (?, ?, ?, ?, ?, 'Y', now(), ?, now(), ?, 'N') ON CONFLICT DO NOTHING";

  private static final String ACTIVATE_USER_ROLE_SQL = "UPDATE ad_user_roles SET isactive = 'Y', "
      + "updated = now(), updatedby = ? "
      + "WHERE ad_user_id = ? AND ad_role_id = ? AND isactive = 'N'";

  private static final String DEACTIVATE_OTHER_ROLES_SQL = "UPDATE ad_user_roles "
      + "SET isactive = 'N', updated = now(), updatedby = ? "
      + "WHERE ad_user_id = ? AND ad_role_id <> ? AND isactive = 'Y'";

  @Override
  public String findClientAdminRoleId(String clientId) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(ADMIN_ROLE_SQL)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    });
  }

  @Override
  public SupportUserContext findReferenceContext(String clientId, String adminRoleId) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(ADMIN_REFERENCE_SQL)) {
        ps.setString(1, clientId);
        ps.setString(2, adminRoleId);
        ps.setString(3, STAR_ORG_ID);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return new SupportUserContext(clientId, null, adminRoleId, rs.getString(1),
                rs.getString(2), rs.getString(3));
          }
        }
      }
      try (PreparedStatement ps = connection.prepareStatement(FIRST_ORG_SQL)) {
        ps.setString(1, clientId);
        ps.setString(2, STAR_ORG_ID);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next()
              ? new SupportUserContext(clientId, null, adminRoleId, rs.getString(1),
                  rs.getString(2), null)
              : null;
        }
      }
    });
  }

  @Override
  public boolean insertUser(SupportUserContext context, String name, String username) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(INSERT_USER_SQL)) {
        int i = 1;
        ps.setString(i++, context.getUserId());
        ps.setString(i++, context.getClientId());
        ps.setString(i++, STAR_ORG_ID);
        ps.setString(i++, SYSTEM_USER_ID);
        ps.setString(i++, SYSTEM_USER_ID);
        ps.setString(i++, name);
        ps.setString(i++, username);
        ps.setString(i++, context.getClientId());
        ps.setString(i++, context.getRoleId());
        ps.setString(i++, context.getOrgId());
        ps.setString(i++, context.getWarehouseId());
        ps.setString(i++, context.getLanguage());
        ps.setString(i, context.getRoleId());
        return ps.executeUpdate() == 1;
      }
    });
  }

  @Override
  public boolean alignUser(SupportUserContext context) {
    return OBDal.getInstance().getSession().doReturningWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(ALIGN_USER_SQL)) {
        int i = 1;
        ps.setString(i++, context.getClientId());
        ps.setString(i++, context.getRoleId());
        ps.setString(i++, context.getOrgId());
        ps.setString(i++, context.getWarehouseId());
        ps.setString(i++, context.getRoleId());
        ps.setString(i++, SYSTEM_USER_ID);
        ps.setString(i++, context.getUserId());
        ps.setString(i, context.getClientId());
        return ps.executeUpdate() == 1;
      }
    });
  }

  @Override
  public void ensureSingleRole(SupportUserContext context) {
    OBDal.getInstance().getSession().doWork(connection -> {
      try (PreparedStatement ps = connection.prepareStatement(INSERT_USER_ROLE_SQL)) {
        ps.setString(1, newId());
        ps.setString(2, context.getUserId());
        ps.setString(3, context.getRoleId());
        ps.setString(4, context.getClientId());
        ps.setString(5, STAR_ORG_ID);
        ps.setString(6, SYSTEM_USER_ID);
        ps.setString(7, SYSTEM_USER_ID);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = connection.prepareStatement(ACTIVATE_USER_ROLE_SQL)) {
        ps.setString(1, SYSTEM_USER_ID);
        ps.setString(2, context.getUserId());
        ps.setString(3, context.getRoleId());
        ps.executeUpdate();
      }
      try (PreparedStatement ps = connection.prepareStatement(DEACTIVATE_OTHER_ROLES_SQL)) {
        ps.setString(1, SYSTEM_USER_ID);
        ps.setString(2, context.getUserId());
        ps.setString(3, context.getRoleId());
        ps.executeUpdate();
      }
    });
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }
}
