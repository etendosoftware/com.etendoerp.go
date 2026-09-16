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
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.UsageDaily;

/**
 * Recomputes usage for the days still inside the settling window, for every active resource
 * in the catalog.
 *
 * <p>Nothing here is incremental and nothing is diffed: each day is recomputed from scratch
 * and the value replaces whatever was there, so a run is a pure function of database state
 * and re-running or backfilling is idempotent. Idempotency comes from the unique key
 * (measured client, resource, day) — there is no "already processed" marker.
 *
 * <p><b>Writes only to {@code ETGO_USAGE_DAILY}</b>, never to a business table, and calls no
 * external service. This is the property that makes it safe to run in a real environment on
 * day one.
 *
 * <p>The job iterates the catalog rather than any hardcoded list, which is what lets a new
 * resource be added by inserting a row.
 */
public class UsageAggregationService {

  private static final Logger log = LogManager.getLogger(UsageAggregationService.class);

  private static final String SYSTEM_CLIENT = "0";
  private static final String ORG_ZERO = "0";

  /**
   * Recomputes every day still inside the settling window, ending with today.
   *
   * @return what the run did
   */
  public UsageAggregationResult runForSettlingWindow() {
    int window = UsageSettings.getMaxSettlingWindowDays();
    Date today = UsageDayRange.startOfDay(new Date());
    Date from = UsageDayRange.minusDays(today, window);
    log.info("Usage aggregation over the settling window: {} .. {} ({} day(s))", from, today,
        window + 1);
    return run(from, today);
  }

  /**
   * Recomputes an explicit inclusive day range. Used by the backfill, which may reach days
   * that are already final — a backfill is a deliberate recomputation, unlike the scheduled
   * run, which never touches a final day.
   *
   * @param fromDay inclusive first day
   * @param toDay inclusive last day
   */
  public UsageAggregationResult run(Date fromDay, Date toDay) {
    UsageAggregationResult result = new UsageAggregationResult();
    // Ids, not entities: each resource-day commits, which closes the session and would detach
    // any entity held across the loop. The resource is re-read fresh inside each unit.
    List<String> resources = activeResourceIds();
    if (resources.isEmpty()) {
      log.warn("No active billing resources in the catalog; nothing to count");
      return result;
    }

    Date day = UsageDayRange.startOfDay(fromDay);
    Date last = UsageDayRange.startOfDay(toDay);
    Date today = UsageDayRange.startOfDay(new Date());

    while (!day.after(last)) {
      result.addDay();
      for (String resourceId : resources) {
        // Each resource-day is its own transaction. Without the commit, the rollback below
        // would discard every day and resource the run had already written, because nothing
        // else commits before DalBaseProcess finishes.
        BillingResource resource = null;
        try {
          resource = OBDal.getInstance().get(BillingResource.class, resourceId);
          if (resource == null) {
            // Deactivated or deleted between the scan and its turn. Not a failure.
            continue;
          }
          result.addResource();
          int rows = recomputeDay(resource, day, today);
          OBDal.getInstance().commitAndClose();
          result.addRows(rows);
        } catch (Exception e) {
          result.addFailure();
          // The search key, not the id: when the failure is "this resource is misconfigured",
          // the search key is what an operator can act on without a database lookup first.
          log.error("Resource '{}' failed for day {}: {}",
              resource != null ? resource.getSearchKey() : resourceId, day, e.getMessage(), e);
          OBDal.getInstance().rollbackAndClose();
        }
      }
      day = UsageDayRange.nextDay(day);
    }
    log.info("Usage aggregation finished. {}", result);
    return result;
  }

  /**
   * Recomputes one resource for one day and upserts the resulting rows.
   *
   * @return how many usage rows were written
   */
  private int recomputeDay(BillingResource resource, Date day, Date today) {
    List<DailyCount> counts = count(resource, day);
    for (DailyCount count : counts) {
      // Finality is per tenant: the window is a preference and a tenant may override it, so
      // the same day can still be open for one tenant and final for another.
      boolean settled = UsageDayRange.isFinal(day, today,
          UsageSettings.getSettlingWindowDays(count.getClientId()));
      upsert(resource, count, settled);
    }
    return counts.size();
  }

  private List<DailyCount> count(BillingResource resource, Date day) {
    String mode = resource.getCountingMode();
    if (UsageResourceValidator.MODE_DECLARATIVE.equals(mode)) {
      return DeclarativeHqlCounter.countDay(resource, day);
    }
    if (UsageResourceValidator.MODE_STRATEGY.equals(mode)) {
      return countByStrategy(resource, day);
    }
    throw new IllegalStateException(
        "Resource '" + resource.getSearchKey() + "' has unknown counting mode '" + mode + "'");
  }

  private List<DailyCount> countByStrategy(BillingResource resource, Date day) {
    String qualifier = resource.getStrategyQualifier();
    UsageResourceCounter counter = UsageCounterLookup.byQualifier(qualifier);
    if (counter == null) {
      // Fail loudly. A missing counter would otherwise record nothing, which is
      // indistinguishable from genuinely zero usage.
      throw new IllegalStateException("No UsageResourceCounter deployed with @Named(\""
          + qualifier + "\") for resource '" + resource.getSearchKey() + "'. Deployed: "
          + UsageCounterLookup.deployedQualifiers());
    }
    Date start = UsageDayRange.startOfDay(day);
    List<DailyCount> counts =
        counter.count(new UsageCountRequest(resource, start, UsageDayRange.nextDay(start), null));
    return counts == null ? new ArrayList<>() : counts;
  }

  /**
   * Replaces the stored value for (tenant, resource, day), or inserts it when absent. The
   * unique key makes this the whole of the idempotency story.
   */
  private void upsert(BillingResource resource, DailyCount count, boolean settled) {
    UsageDaily row = find(resource, count);
    if (row == null) {
      row = OBProvider.getInstance().get(UsageDaily.class);
      row.setNewOBObject(true);
      row.setClient(OBDal.getInstance().get(Client.class, SYSTEM_CLIENT));
      row.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ZERO));
      row.setMeasuredClient(OBDal.getInstance().get(Client.class, count.getClientId()));
      row.setBillingResource(resource);
      row.setUsageDay(count.getDay());
    }
    row.setQuantity(count.getQuantity());
    row.setComputedAt(new Date());
    row.setSettled(settled);
    OBDal.getInstance().save(row);
  }

  private UsageDaily find(BillingResource resource, DailyCount count) {
    OBCriteria<UsageDaily> criteria = OBDal.getInstance().createCriteria(UsageDaily.class);
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_BILLINGRESOURCE, resource));
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_USAGEDAY, count.getDay()));
    criteria.add(
        Restrictions.eq(UsageDaily.PROPERTY_MEASUREDCLIENT + ".id", count.getClientId()));
    // These rows are System-owned data about other tenants, so the readable-client and
    // readable-organisation filters must be off or the lookup misses the existing row and
    // the insert then violates the unique key.
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return (UsageDaily) criteria.uniqueResult();
  }

  private List<String> activeResourceIds() {
    OBCriteria<BillingResource> criteria =
        OBDal.getInstance().createCriteria(BillingResource.class);
    criteria.add(Restrictions.eq(BillingResource.PROPERTY_ACTIVE, true));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.addOrderBy(BillingResource.PROPERTY_SEARCHKEY, true);
    List<String> ids = new ArrayList<>();
    for (BillingResource resource : criteria.list()) {
      ids.add(resource.getId());
    }
    return ids;
  }

}
