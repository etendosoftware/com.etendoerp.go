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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.Query;
import org.openbravo.dal.service.OBDal;

/**
 * A deployed {@link UsageResourceCounter} that exists so the strategy path can be exercised
 * on a real instance, end to end, without inventing a billing rule first.
 *
 * <p><b>This is a diagnostic, not a billing definition.</b> It counts active users per tenant,
 * which is plausible enough to be verifiable by hand but is deliberately NOT the
 * {@code activeUsers} resource the design calls for: that one still needs a decision about its
 * data source, and {@code AD_SESSION} is explicitly ruled out. Do not seed a customer-facing
 * catalog row against this qualifier.
 *
 * <p>It is shaped as a <b>stock</b> rather than a flow, because that is the case the SPI
 * exists for. A flow ("how many invoices were dated that day") is a row count over a date
 * column and needs no class at all -- it is a declarative catalog row. A stock ("how many
 * users existed that day") cannot be expressed that way: the value does not come from rows
 * dated that day, so every day in the range gets the same point-in-time snapshot. That is
 * also this counter's honest limitation: asked about a past day, it answers with today's
 * figure, because the model keeps no history of when a user was deactivated. A real stock
 * resource has to solve that; this one only has to prove the wiring.
 *
 * <p><b>{@code @Named} and nothing else.</b> Not {@code @ApplicationScoped}, not any normal
 * scope: {@link UsageCounterLookup} matches on the bean name, and a normal-scoped bean is
 * served through a Weld client proxy whose subclass does not carry the non-{@code @Inherited}
 * qualifier, so it would be silently skipped -- the counter would never run and the resource
 * would report nothing, which is indistinguishable from genuinely zero usage.
 *
 * <p>To use it: create a catalog row with Counting Mode = Named strategy and Strategy
 * Qualifier = {@value #QUALIFIER}, leaving Counted Entity, Date Property and HQL Restriction
 * empty. Then check the result against
 * {@code select ad_client_id, count(*) from ad_user where isactive = 'Y' group by 1}.
 */
@Named(UsageSelfTestCounter.QUALIFIER)
public class UsageSelfTestCounter implements UsageResourceCounter {

  /** The value to put in {@code ETGO_BILLING_RESOURCE.Strategy_Qualifier}. */
  public static final String QUALIFIER = "etgo-usage-selftest";

  private static final Logger log = LogManager.getLogger(UsageSelfTestCounter.class);

  /**
   * Counts active users per tenant and reports that snapshot for every day in the range.
   *
   * <p>Goes through the raw Hibernate session, not {@code OBQuery}, because the DAL's
   * readable-client filter would otherwise narrow the count to the context's own tenant.
   * Attribution comes from the {@code group by}. Admin mode is the caller's responsibility,
   * exactly as for {@link DeclarativeHqlCounter}.
   */
  @Override
  public List<DailyCount> count(UsageCountRequest request) {
    StringBuilder hql = new StringBuilder(
        "select u.client.id, count(*) from ADUser u where u.active = true");
    if (!request.isAllTenants()) {
      hql.append(" and u.client.id = :clientId");
    }
    hql.append(" group by u.client.id");

    Query<Object[]> query =
        OBDal.getInstance().getSession().createQuery(hql.toString(), Object[].class);
    if (!request.isAllTenants()) {
      query.setParameter("clientId", request.getClientId());
    }

    List<Object[]> snapshot = query.list();
    List<DailyCount> counts = new ArrayList<>();
    // The half-open range means `to` is the first day NOT counted, so the loop stops before it.
    for (Date day = UsageDayRange.startOfDay(request.getFrom()); day.before(request.getTo());
        day = UsageDayRange.nextDay(day)) {
      for (Object[] row : snapshot) {
        long value = toLong(row[1]);
        if (value > 0) {
          counts.add(new DailyCount((String) row[0], day, value));
        }
      }
    }
    log.debug("Self-test counter reported {} tenant-days from {} to {}", counts.size(),
        request.getFrom(), request.getTo());
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
