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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  /** Per-run cache: without it this is one preference read per tenant, resource and day. */
  private final Map<String, Integer> settlingWindowCache = new HashMap<>();
  /** Accumulates what this run did; written once at the end. See {@link UsageRunLedger}. */
  private final UsageRunLedger ledger = new UsageRunLedger();

  /** The id shared by every run-log row this run writes. */
  public String getRunId() {
    return ledger.getRunId();
  }

  /**
   * Recomputes every day still inside the settling window, ending with today.
   *
   * @return what the run did
   */
  public UsageAggregationResult runForSettlingWindow() {
    int window = UsageSettings.getMaxSettlingWindowDays();
    Date today = UsageDayRange.startOfDay(new Date());
    // One day further back than the window, because a day only becomes final once it is
    // OUTSIDE the window: isFinal is `day < today - window`, so the oldest day of the window
    // itself is still open. Without this extra day nothing would ever revisit a day after it
    // became final, and IS_SETTLED would stay 'N' on every row forever. That extra day is a
    // sealing pass -- it stamps the day final without recomputing it.
    Date from = UsageDayRange.minusDays(today, window + 1);
    log.info("Usage aggregation over the settling window: {} .. {} ({} day(s), the oldest"
        + " being the sealing pass)", from, today, window + 2);
    return run(from, today, false);
  }

  /**
   * Recomputes an explicit inclusive day range. Used by the backfill, which may reach days
   * that are already final — a backfill is a deliberate recomputation, unlike the scheduled
   * run, which never touches a final day.
   *
   * @param fromDay inclusive first day
   * @param toDay inclusive last day
   * @return what the run did: days, resources, rows and failures
   */
  public UsageAggregationResult run(Date fromDay, Date toDay) {
    // An explicit range is a backfill: a deliberate recomputation, so it may rewrite a day
    // that is already final. The scheduled run never may.
    return run(fromDay, toDay, true);
  }

  /**
   * Recomputes an explicit inclusive day range, choosing whether final days may be rewritten.
   *
   * @param fromDay inclusive first day
   * @param toDay inclusive last day
   * @param rewriteFinalDays true only for an explicit backfill. The scheduled run passes
   *     false so that a day which has left its tenant's settling window is never rewritten —
   *     "once final, it never changes" is the one normative invariant of the design, and the
   *     run iterates the LARGEST window configured anywhere, so it necessarily revisits days
   *     that are already final for a tenant with a shorter window.
   * @return what the run did: days, resources, rows and failures
   */
  public UsageAggregationResult run(Date fromDay, Date toDay, boolean rewriteFinalDays) {
    // Admin mode belongs here rather than only in the process: this reads across every
    // tenant, and a future caller (a NEO handler, a data-fix, the report) that forgot it
    // would silently under-count rather than fail.
    OBContext.setAdminMode(false);
    try {
      return aggregate(fromDay, toDay, rewriteFinalDays);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private UsageAggregationResult aggregate(Date fromDay, Date toDay, boolean rewriteFinalDays) {
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
    ledger.covering(day, last);

    while (!day.after(last)) {
      result.addDay();
      for (String resourceId : resources) {
        aggregateResourceDay(resourceId, day, today, rewriteFinalDays, result);
      }
      day = UsageDayRange.nextDay(day);
    }
    // After the day loop, so the log survives whatever happened inside it: each resource-day
    // commits or rolls back on its own, and a row written inside a failing unit would be rolled
    // back with it -- losing precisely the failure worth recording.
    ledger.write();
    log.info("Usage aggregation finished. {}", result);
    return result;
  }

  /**
   * Counts one resource for one day in its own transaction, recording either the rows written
   * or the failure. Never propagates: one misconfigured resource must not abort the run.
   *
   * @param resourceId id of the billing resource to count
   * @param day the day being recomputed
   * @param today start of the current day, used to decide whether the day is still settling
   * @param rewriteFinalDays whether days already sealed should be rewritten
   * @param result run totals, updated in place
   */
  private void aggregateResourceDay(String resourceId, Date day, Date today,
      boolean rewriteFinalDays, UsageAggregationResult result) {
    // Each resource-day is its own transaction. Without the commit, the rollback below
    // would discard every day and resource the run had already written, because nothing
    // else commits before DalBaseProcess finishes.
    BillingResource resource = null;
    try {
      resource = OBDal.getInstance().get(BillingResource.class, resourceId);
      if (resource == null) {
        // Deactivated or deleted between the scan and its turn. Not a failure.
        return;
      }
      result.addResource();
      int rows = recomputeDay(resource, day, today, rewriteFinalDays);
      OBDal.getInstance().commitAndClose();
      result.addRows(rows);
    } catch (Exception e) {
      result.addFailure(resource != null ? resource.getSearchKey() : resourceId,
          e.getMessage());
      ledger.resourceDayFailed(resource, resourceId, e.getMessage());
      // The search key, not the id: when the failure is "this resource is misconfigured",
      // the search key is what an operator can act on without a database lookup first.
      log.error("Resource '{}' failed for day {}: {}",
          resource != null ? resource.getSearchKey() : resourceId, day, e.getMessage(), e);
      OBDal.getInstance().rollbackAndClose();
    }
  }

  /**
   * Recomputes one resource for one day and upserts the resulting rows.
   *
   * @return how many usage rows were written
   */
  private int recomputeDay(BillingResource resource, Date day, Date today,
      boolean rewriteFinalDays) {
    List<DailyCount> counts = count(resource, day);
    Set<String> countedTenants = new HashSet<>();
    int written = 0;

    for (DailyCount count : counts) {
      countedTenants.add(count.getClientId());
      // Finality is per tenant: the window is a preference and a tenant may override it, so
      // the same day can still be open for one tenant and final for another.
      boolean settled = UsageDayRange.isFinal(day, today,
          settlingWindowFor(count.getClientId()));
      boolean wrote = writeRow(resource, count, settled, rewriteFinalDays);
      if (wrote) {
        written++;
      }
      // The day counts as processed for this tenant either way: a day left alone because it is
      // already final was still looked at, and reporting it as not processed would read as a
      // gap in coverage.
      ledger.tenantDay(resource, count.getClientId(), wrote ? 1 : 0);
    }

    // A tenant that dropped to zero produces no group at all, so without this its previous
    // non-zero value would stand forever and the day would not in fact have been recomputed
    // "from scratch". Absence of a row means zero only until a row exists.
    written += zeroOutTenantsThatNoLongerCount(resource, day, today, countedTenants,
        rewriteFinalDays);
    return written;
  }

  /**
   * Writes one row unless the stored day is already final and this is not a backfill.
   *
   * <p>A day that has just left the tenant's window is <b>sealed, not recomputed</b>: the
   * stored value is stamped final and left exactly as it is. Recomputing it here would let
   * the number move after the day was already reported as closed, which is the one thing the
   * settling window exists to prevent. A day with no stored row has nothing to protect, so it
   * is computed and written final in one go.
   *
   * @return whether a row was actually written
   */
  private boolean writeRow(BillingResource resource, DailyCount count, boolean settled,
      boolean rewriteFinalDays) {
    UsageDaily row = find(resource, count.getClientId(), count.getDay());
    if (isFrozen(row, rewriteFinalDays)) {
      return false;
    }
    if (settled && row != null && !rewriteFinalDays) {
      return seal(row);
    }
    upsert(resource, count, settled, row);
    return true;
  }

  /**
   * Stamps a stored row final without touching its value, so the number reported while the
   * day was still open is the number that stands.
   *
   * @return always true; the row was written
   */
  private boolean seal(UsageDaily row) {
    row.setSettled(true);
    OBDal.getInstance().save(row);
    return true;
  }

  /**
   * Sets to zero any stored row for this resource and day whose tenant the recount no longer
   * reports. A final row is left alone outside a backfill, exactly as a counted one is.
   *
   * @return how many rows were zeroed
   */
  private int zeroOutTenantsThatNoLongerCount(BillingResource resource, Date day, Date today,
      Set<String> countedTenants, boolean rewriteFinalDays) {
    int written = 0;
    for (UsageDaily stored : storedRows(resource, UsageDayRange.startOfDay(day))) {
      written += zeroOutStoredRow(resource, stored, day, today, countedTenants, rewriteFinalDays);
    }
    return written;
  }

  /**
   * Applies the zero-out decision to one stored row.
   *
   * @param resource the billing resource being recomputed
   * @param stored the stored row under consideration
   * @param day the day being recomputed
   * @param today start of the current day, used to decide whether the day has settled
   * @param countedTenants tenants the recount still reports
   * @param rewriteFinalDays whether days already sealed may be rewritten
   * @return 1 when the row was written or sealed, 0 when it was left untouched
   */
  private int zeroOutStoredRow(BillingResource resource, UsageDaily stored, Date day, Date today,
      Set<String> countedTenants, boolean rewriteFinalDays) {
    String clientId = stored.getTenantClient().getId();
    if (countedTenants.contains(clientId) || isFrozen(stored, rewriteFinalDays)) {
      return 0;
    }
    boolean settled = UsageDayRange.isFinal(day, today, settlingWindowFor(clientId));
    if (settled && !rewriteFinalDays) {
      // The sealing pass reaches tenants that stopped counting too. Zeroing here would
      // revise a day downward after it closed -- checked before the zero-quantity guard
      // below, so a row that is already zero still gets stamped.
      return seal(stored) ? 1 : 0;
    }
    if (stored.getQuantity() != null && stored.getQuantity() == 0L) {
      return 0;
    }
    upsert(resource, new DailyCount(clientId, UsageDayRange.startOfDay(day), 0L), settled,
        stored);
    return 1;
  }

  /** A day that has left its tenant's settling window may only be rewritten by a backfill. */
  private boolean isFrozen(UsageDaily row, boolean rewriteFinalDays) {
    return row != null && Boolean.TRUE.equals(row.isSettled()) && !rewriteFinalDays;
  }

  /**
   * The settling window for a tenant, cached for the duration of the run. Without the cache
   * this is one preference lookup per tenant per resource per day.
   */
  private int settlingWindowFor(String clientId) {
    return settlingWindowCache.computeIfAbsent(clientId,
        UsageSettings::getSettlingWindowDays);
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
  private void upsert(BillingResource resource, DailyCount count, boolean settled,
      UsageDaily existing) {
    UsageDaily row = existing;
    if (row == null) {
      row = OBProvider.getInstance().get(UsageDaily.class);
      row.setNewOBObject(true);
      row.setClient(OBDal.getInstance().get(Client.class, SYSTEM_CLIENT));
      row.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ZERO));
      row.setTenantClient(OBDal.getInstance().get(Client.class, count.getClientId()));
      row.setBillingResource(resource);
      row.setUsageDay(count.getDay());
    }
    row.setQuantity(count.getQuantity());
    row.setComputedAt(new Date());
    row.setSettled(settled);
    OBDal.getInstance().save(row);
  }

  private UsageDaily find(BillingResource resource, String clientId, Date day) {
    OBCriteria<UsageDaily> criteria = OBDal.getInstance().createCriteria(UsageDaily.class);
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_BILLINGRESOURCE, resource));
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_USAGEDAY, day));
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_TENANTCLIENT + ".id", clientId));
    // These rows are System-owned data about other tenants, so the readable-client and
    // readable-organisation filters must be off or the lookup misses the existing row and
    // the insert then violates the unique key.
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.setMaxResults(1);
    return (UsageDaily) criteria.uniqueResult();
  }

  /** Every stored row for a resource on a day, across tenants. */
  private List<UsageDaily> storedRows(BillingResource resource, Date day) {
    OBCriteria<UsageDaily> criteria = OBDal.getInstance().createCriteria(UsageDaily.class);
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_BILLINGRESOURCE, resource));
    criteria.add(Restrictions.eq(UsageDaily.PROPERTY_USAGEDAY, day));
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria.list();
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
