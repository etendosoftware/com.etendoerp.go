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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.Query;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * Counts a declarative resource: rows of an entity, bucketed by a date property, narrowed by
 * an optional HQL restriction.
 *
 * <p>Runs one query per day covering every tenant at once, grouped by client. Counting one
 * tenant at a time would be {@code tenants x resources x windowDays} queries per run, which
 * with a few hundred tenants is tens of thousands of round trips nightly.
 *
 * <p>Queries go through the raw Hibernate session rather than {@code OBQuery}, because the
 * DAL's readable-client and readable-organisation filters would otherwise restrict the count
 * to the context's own tenant. Tenant attribution comes from the {@code group by} instead,
 * which no restriction fragment can subvert. Callers are responsible for admin mode.
 */
public final class DeclarativeHqlCounter {

  private static final Logger log = LogManager.getLogger(DeclarativeHqlCounter.class);

  private DeclarativeHqlCounter() {
  }

  /**
   * Counts one day across every tenant.
   *
   * @param resource a resource in declarative mode
   * @param day the day to count; the half-open range {@code [day, day+1)} is used
   * @return one entry per tenant that has a non-zero count; tenants absent from the result
   *     counted zero that day
   */
  public static List<DailyCount> countDay(BillingResource resource, Date day) {
    String hql = UsageQueryComposer.composeGroupedCount(resource.getCountedEntity(),
        resource.getDateProperty(), resource.getHQLRestriction());
    Date start = UsageDayRange.startOfDay(day);
    Date end = UsageDayRange.nextDay(start);

    Query<Object[]> query = OBDal.getInstance().getSession().createQuery(hql, Object[].class);
    query.setParameter(UsageQueryComposer.PARAM_DAY_START, start);
    query.setParameter(UsageQueryComposer.PARAM_DAY_END, end);

    List<DailyCount> counts = new ArrayList<>();
    for (Object[] row : query.list()) {
      String clientId = (String) row[0];
      long value = toLong(row[1]);
      if (value > 0) {
        counts.add(new DailyCount(clientId, start, value));
      }
    }
    log.debug("Resource '{}' counted {} tenants on {}", resource.getSearchKey(), counts.size(),
        start);
    return counts;
  }

  private static long toLong(Object value) {
    if (value instanceof BigInteger) {
      return ((BigInteger) value).longValue();
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return 0L;
  }
}
