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

import java.util.List;

import org.hibernate.query.NativeQuery;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Database-backed {@link GoSessionRoleReconciler.RoleDirectory}.
 *
 * <p>The lookups are native SQL so they do not depend on the {@link OBContext} of the request
 * being authenticated — that context is exactly what cannot be trusted yet. Only
 * {@link #deriveContext} needs DAL objects, and it runs them under a temporary system context.
 */
class DalRoleDirectory implements GoSessionRoleReconciler.RoleDirectory {

  private static final String SQL_IS_ELIGIBLE =
      "SELECT count(*) FROM ad_user_roles ur "
      + "JOIN ad_role r ON r.ad_role_id = ur.ad_role_id "
      + "WHERE ur.ad_user_id = :userId AND ur.ad_role_id = :roleId AND ur.isactive = 'Y' "
      + "AND r.isactive = 'Y' AND r.ad_client_id = :clientId";

  private static final String SQL_DEFAULT_ROLE =
      "SELECT default_ad_role_id FROM ad_user WHERE ad_user_id = :userId";

  // Same order as EtendoGoJwtSupport's role list, so the fallback is the role shown first.
  private static final String SQL_ACTIVE_ROLES =
      "SELECT ur.ad_role_id FROM ad_user_roles ur "
      + "JOIN ad_role r ON r.ad_role_id = ur.ad_role_id "
      + "WHERE ur.ad_user_id = :userId AND ur.isactive = 'Y' AND r.isactive = 'Y' "
      + "ORDER BY r.created";

  private static final String SQL_HAS_ORG_ACCESS =
      "SELECT count(*) FROM ad_role_orgaccess "
      + "WHERE ad_role_id = :roleId AND ad_org_id = :orgId AND isactive = 'Y'";

  private static final String PARAM_USER_ID = "userId";
  private static final String PARAM_ROLE_ID = "roleId";

  @Override
  public boolean isEligible(String userId, String roleId, String clientId) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(SQL_IS_ELIGIBLE);
    query.setParameter(PARAM_USER_ID, userId);
    query.setParameter(PARAM_ROLE_ID, roleId);
    query.setParameter("clientId", clientId);
    return ((Number) query.uniqueResult()).longValue() > 0;
  }

  @Override
  public String findDefaultRoleId(String userId) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(SQL_DEFAULT_ROLE);
    query.setParameter(PARAM_USER_ID, userId);
    List<?> rows = query.list();
    return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
  }

  @Override
  public List<String> findActiveRoleIds(String userId) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(SQL_ACTIVE_ROLES);
    query.setParameter(PARAM_USER_ID, userId);
    return query.list().stream().map(String::valueOf).toList();
  }

  @Override
  public boolean hasOrgAccess(String roleId, String orgId) {
    if (orgId == null) {
      return false;
    }
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(SQL_HAS_ORG_ACCESS);
    query.setParameter(PARAM_ROLE_ID, roleId);
    query.setParameter("orgId", orgId);
    return ((Number) query.uniqueResult()).longValue() > 0;
  }

  /**
   * Derives the organization and warehouse the same way environment entry does: mint a token for
   * the user and role and read its claims, so both paths always agree.
   */
  @Override
  public GoSessionRoleReconciler.RoleContext deriveContext(String userId, String roleId) {
    OBContext previous = OBContext.getOBContext();
    try {
      OBContext.setOBContext("0", "0", "0", "0");
      OBContext.setAdminMode(true);
      try {
        User user = OBDal.getInstance().get(User.class, userId);
        Role role = OBDal.getInstance().get(Role.class, roleId);
        DecodedJWT claims = SecureWebServicesUtils.decodeToken(
            SecureWebServicesUtils.generateToken(user, role));
        return new GoSessionRoleReconciler.RoleContext(
            claims.getClaim("organization").asString(), claims.getClaim("warehouse").asString());
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      throw e;
    } catch (Exception e) {
      throw new OBException("Unable to derive the session context for role " + roleId, e);
    } finally {
      OBContext.setOBContext(previous);
    }
  }
}
