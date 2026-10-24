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

package com.etendoerp.go.usage.quota;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.query.Query;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.usage.DailyCount;
import com.etendoerp.go.usage.UsageCountRequest;
import com.etendoerp.go.usage.UsageCounterLookup;
import com.etendoerp.go.usage.UsageFreshness;
import com.etendoerp.go.usage.UsageQueryComposer;
import com.etendoerp.go.usage.UsageResourceCounter;
import com.etendoerp.go.usage.UsageResourceValidator;

/**
 * ETP-5051 — the production {@link ConsumptionReader}. Data-driven: it dispatches on the
 * quota's {@link ConsumptionSource} and the resource's counting mode, never on a resource name.
 *
 * <ul>
 *   <li><b>Aggregate</b> — the tenant's {@code ETGO_USAGE_DAILY} rows of the window, combined with
 *       the resource's {@link UsageAggregation}. Lagged by the usage job, so a report also checks
 *       the job's freshness ({@link UsageFreshness}); a create does not.</li>
 *   <li><b>Live, declarative</b> — one {@link UsageQueryComposer#composePerTenantCount} over the
 *       whole window: the same entity, date property and validated HQL restriction the job counts
 *       with, so the two sources can only differ by lag.</li>
 *   <li><b>Live, named strategy</b> — the strategy counter over the window for this tenant, its
 *       daily values combined with the resource's aggregation.</li>
 * </ul>
 *
 * <p>Queries go through the raw Hibernate session, as {@code DeclarativeHqlCounter} does, so no
 * readable-client filter narrows a System-owned usage row away; tenant attribution comes from the
 * explicit client parameter. They select scalars only, so they need no admin mode; only the
 * strategy path, which loads an entity and runs a counter, takes it.
 */
public class DalConsumptionReader implements ConsumptionReader {

  private static final String PARAM_RESOURCE_ID = "resourceId";
  private static final String PARAM_CLIENT = "clientId";
  private static final String PARAM_FROM = "dayFrom";
  private static final String PARAM_TO = "dayTo";

  private static final String STORED_DAYS_HQL = "select u.usageDay, u.quantity"
      + " from ETGO_USAGE_DAILY u"
      + " where u.billingResource.id = :" + PARAM_RESOURCE_ID
      + " and u.tenantClient.id = :" + PARAM_CLIENT
      + " and u.usageDay >= :" + PARAM_FROM
      + " and u.usageDay < :" + PARAM_TO
      + " order by u.usageDay";

  private final UsageFreshness freshness;

  /** Report wiring: the shared usage-freshness check runs when the stored aggregate is read. */
  public DalConsumptionReader() {
    this(UsageFreshness.getDefault());
  }

  /**
   * A reader over an explicit freshness check.
   *
   * @param freshness checks the usage job's lag when the stored aggregate is read; null checks
   *     nothing (the NEO write path: a create never pays for the lag check)
   */
  public DalConsumptionReader(UsageFreshness freshness) {
    this.freshness = freshness;
  }

  /**
   * The write-path reader: no lag check, so a create reads the consumption and nothing else.
   *
   * @return a reader that never checks the usage lag
   */
  public static DalConsumptionReader withoutLagCheck() {
    return new DalConsumptionReader(null);
  }

  @Override
  public long read(QuotaDefinition quota, String clientId, QuotaPeriod period) {
    if (quota.getSource() == ConsumptionSource.LIVE) {
      return readLive(quota, clientId, period);
    }
    if (freshness != null) {
      freshness.checkLag();
    }
    return readStored(quota, clientId, period);
  }

  /**
   * The live declarative count leaves the created rows out ({@code id not in (:excludedIds)}), so
   * it is the consumption before the create whatever the created rows' dates. Every other source
   * cannot see them and reads as {@link #read} does.
   */
  @Override
  public long readBeforeCreate(QuotaDefinition quota, String clientId, QuotaPeriod period,
      Collection<String> createdIds) {
    if (quota.getSource() == ConsumptionSource.LIVE
        && UsageResourceValidator.MODE_DECLARATIVE.equals(quota.getCountingMode())
        && createdIds != null && !createdIds.isEmpty()) {
      String hql = UsageQueryComposer.composePerTenantCountExcluding(quota.getCountedEntity(),
          quota.getDateProperty(), quota.getHqlRestriction());
      Query<?> query = OBDal.getInstance().getSession().createQuery(hql);
      query.setParameter(UsageQueryComposer.PARAM_CLIENT_ID, clientId);
      query.setParameter(UsageQueryComposer.PARAM_DAY_START, period.getWindowFrom());
      query.setParameter(UsageQueryComposer.PARAM_DAY_END, period.getWindowTo());
      query.setParameterList(UsageQueryComposer.PARAM_EXCLUDED_IDS, createdIds);
      return toLong(query.uniqueResult());
    }
    return read(quota, clientId, period);
  }

  private long readStored(QuotaDefinition quota, String clientId, QuotaPeriod period) {
    Query<Object[]> query = OBDal.getInstance().getSession()
        .createQuery(STORED_DAYS_HQL, Object[].class);
    query.setParameter(PARAM_RESOURCE_ID, quota.getResourceId());
    query.setParameter(PARAM_CLIENT, clientId);
    query.setParameter(PARAM_FROM, period.getWindowFrom());
    query.setParameter(PARAM_TO, period.getWindowTo());
    List<DailyCount> days = new ArrayList<>();
    for (Object[] row : query.list()) {
      days.add(new DailyCount(clientId, (Date) row[0], toLong(row[1])));
    }
    return quota.getAggregation().combine(days);
  }

  private long readLive(QuotaDefinition quota, String clientId, QuotaPeriod period) {
    String mode = quota.getCountingMode();
    if (UsageResourceValidator.MODE_STRATEGY.equals(mode)) {
      return readLiveStrategy(quota, clientId, period);
    }
    if (UsageResourceValidator.MODE_DECLARATIVE.equals(mode)) {
      return readLiveDeclarative(quota, clientId, period);
    }
    throw new IllegalStateException("Resource '" + quota.getResourceKey()
        + "' has unknown counting mode '" + mode + "'");
  }

  private static long readLiveDeclarative(QuotaDefinition quota, String clientId,
      QuotaPeriod period) {
    String hql = UsageQueryComposer.composePerTenantCount(quota.getCountedEntity(),
        quota.getDateProperty(), quota.getHqlRestriction());
    Query<?> query = OBDal.getInstance().getSession().createQuery(hql);
    query.setParameter(UsageQueryComposer.PARAM_CLIENT_ID, clientId);
    query.setParameter(UsageQueryComposer.PARAM_DAY_START, period.getWindowFrom());
    query.setParameter(UsageQueryComposer.PARAM_DAY_END, period.getWindowTo());
    return toLong(query.uniqueResult());
  }

  /**
   * The strategy path loads the resource entity and runs code that is not ours, so it runs in
   * admin mode, as the usage job runs its counters. The scalar queries above need none.
   */
  private static long readLiveStrategy(QuotaDefinition quota, String clientId,
      QuotaPeriod period) {
    OBContext.setAdminMode(false);
    try {
      return countWithStrategy(quota, clientId, period);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static long countWithStrategy(QuotaDefinition quota, String clientId,
      QuotaPeriod period) {
    String qualifier = quota.getStrategyQualifier();
    UsageResourceCounter counter = UsageCounterLookup.byQualifier(qualifier);
    if (counter == null) {
      // Loudly, as the usage job does: a missing counter must never read as zero consumption.
      throw new IllegalStateException("No UsageResourceCounter deployed with qualifier '"
          + qualifier + "' for resource '" + quota.getResourceKey() + "'");
    }
    BillingResource resource = OBDal.getInstance().get(BillingResource.class,
        quota.getResourceId());
    if (resource == null) {
      throw new IllegalStateException("Billing resource '" + quota.getResourceKey()
          + "' no longer exists");
    }
    List<DailyCount> counted = counter.count(new UsageCountRequest(resource,
        period.getWindowFrom(), period.getWindowTo(), clientId));
    List<DailyCount> ours = new ArrayList<>();
    if (counted != null) {
      for (DailyCount count : counted) {
        if (StringUtils.equals(clientId, count.getClientId())) {
          ours.add(count);
        }
      }
    }
    return quota.getAggregation().combine(ours);
  }

  private static long toLong(Object value) {
    return value instanceof Number ? ((Number) value).longValue() : 0L;
  }
}
