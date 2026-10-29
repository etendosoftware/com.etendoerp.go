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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;

import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * The seats a tenant uses now: a stock, so every call returns today's value, dated at the start
 * of the requested range, one entry per tenant.
 *
 * <p>A seat is an active {@code AD_User} of the tenant that the Users window lists: the owner
 * ({@code EM_ETGO_Is_Owner}) or a user created through the Go SPA, which always gets an
 * {@code ETGO_INVITATION} row at creation (see {@code UserRoleAssignmentHandler}). A pending
 * invitation already takes its seat; a revoked one does not. Bootstrap users, the client admin
 * and business-partner contacts have neither, so they never count.
 *
 * <p>{@code ETGO_ACCOUNT} cannot be the source: it is global (client 0, one row per email) and is
 * only created when an invitation is accepted, so an admin could invite without limit.
 */
@Named(TenantSeatCounter.QUALIFIER)
public class TenantSeatCounter implements UsageResourceCounter {

  /** The {@code ETGO_BILLING_RESOURCE.Strategy_Qualifier} of the seats resource. */
  public static final String QUALIFIER = "tenantSeats";

  private static final String PARAM_CLIENT = "clientId";
  private static final String PARAM_USER = "userId";
  /** What makes an active tenant user a seat; the one definition both queries share. */
  private static final String SEAT_CONDITION = " and u.ad_client_id <> '0'"
      + " and (u.em_etgo_is_owner = 'Y' or exists (select 1 from etgo_invitation i"
      + "   where i.ad_user_id = u.ad_user_id and i.status <> 'REVOKED'))";
  private static final String SEATS_SQL = "select u.ad_client_id, count(*) from ad_user u"
      + " where u.isactive = 'Y'" + SEAT_CONDITION;
  private static final String USER_SEAT_SQL = "select count(*) from ad_user u"
      + " where u.ad_user_id = :" + PARAM_USER + SEAT_CONDITION;
  private static final String ONE_CLIENT = " and u.ad_client_id = :" + PARAM_CLIENT;
  private static final String GROUP_BY = " group by u.ad_client_id";

  @Override
  public List<DailyCount> count(UsageCountRequest request) {
    List<DailyCount> counts = new ArrayList<>();
    for (Object[] row : query(request.getClientId()).list()) {
      counts.add(new DailyCount((String) row[0], request.getFrom(),
          ((Number) row[1]).longValue()));
    }
    return counts;
  }

  /**
   * The seats a tenant uses now.
   *
   * @param clientId the tenant
   * @return its seats; 0 when it has none
   */
  public long seatsOf(String clientId) {
    List<Object[]> rows = query(clientId).list();
    return rows.isEmpty() ? 0L : ((Number) rows.get(0)[1]).longValue();
  }

  /**
   * ETP-5051 — whether a user counts as a seat once active, whatever its current active flag: the
   * seat rule of {@link #seatsOf} applied to that one user. Reactivating a user that is not (a
   * business-partner contact, a user whose invitations were all revoked) takes no seat.
   *
   * @param userId the {@code AD_User} to reactivate
   * @return true when the user, once active, is a seat; false for a null id
   */
  public boolean isSeatOnceActive(String userId) {
    if (userId == null) {
      return false;
    }
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(USER_SEAT_SQL);
    query.setParameter(PARAM_USER, userId);
    Object count = query.uniqueResult();
    return count != null && ((Number) count).longValue() > 0;
  }

  @SuppressWarnings("unchecked")
  private static NativeQuery<Object[]> query(String clientId) {
    String sql = SEATS_SQL + (clientId == null ? "" : ONE_CLIENT) + GROUP_BY;
    NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    if (clientId != null) {
      query.setParameter(PARAM_CLIENT, clientId);
    }
    return query;
  }
}
