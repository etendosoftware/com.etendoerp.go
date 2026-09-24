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

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.UsageRunLog;

/**
 * Accumulates what a run did, then writes it to {@code ETGO_USAGE_RUN_LOG} when the run ends.
 *
 * <p><b>Accumulated in memory, written at the end, on purpose.</b> Each resource-day commits or
 * rolls back on its own, so a log row written inside a unit that later fails would be rolled
 * back with it -- and the failure is exactly the thing worth recording. Collecting first and
 * writing once means the log survives regardless of what happened during the run.
 *
 * <p>Two shapes of row come out of this, which is what the nullable tenant column is for:
 * <ul>
 *   <li><b>per tenant</b> -- one row per (run, resource, tenant) that was counted, saying how
 *       many days were processed for it and how many usage rows were written;
 *   <li><b>per resource</b> -- one row with <b>no tenant</b>, written only when something
 *       failed. The common failures happen before any tenant is known: a fragment that will
 *       not compile, a qualifier no counter carries. There is nowhere else to put them.
 * </ul>
 *
 * <p>Granularity is per run, not per run-day. The engine works per resource-day, but a row per
 * day would make this table as large as the aggregate it describes: a seventeen-year backfill
 * of one resource wrote 74,520 usage rows and would have written as many log rows beside them.
 * {@code DATE_FROM}/{@code DATE_TO} carry the range instead.
 */
public class UsageRunLedger {

  private static final Logger log = LogManager.getLogger(UsageRunLedger.class);

  /** Everything the run asked for completed. */
  static final String STATUS_SUCCESS = "S";
  /** Some resource-days completed and some failed. */
  static final String STATUS_PARTIAL = "P";
  /** Nothing completed for this resource. */
  static final String STATUS_ERROR = "E";

  private static final String SYSTEM_CLIENT = "0";
  private static final String ORG_ZERO = "0";

  private final String runId = SequenceIdData.getUUID();
  private final Date startedAt = new Date();
  private final Map<String, ResourceTally> tallies = new LinkedHashMap<>();
  private Date from;
  private Date to;

  /** The id shared by every row this run writes, so one execution can be read back as a unit. */
  public String getRunId() {
    return runId;
  }

  /**
   * Records the range the run was asked for; the log is meaningless without it.
   *
   * @param rangeFrom inclusive first day of the run
   * @param rangeTo inclusive last day of the run
   */
  public void covering(Date rangeFrom, Date rangeTo) {
    this.from = rangeFrom;
    this.to = rangeTo;
  }

  /**
   * Records that one day was processed for one tenant of one resource.
   *
   * @param resource the billing resource being counted
   * @param clientId the tenant the day was counted for
   * @param rowsWritten usage rows actually written; zero is normal for a day that was already
   *     final, and the day still counts as processed
   */
  public void tenantDay(BillingResource resource, String clientId, int rowsWritten) {
    ResourceTally tally = tallyFor(resource);
    TenantTally tenant = tally.tenants.computeIfAbsent(clientId, k -> new TenantTally());
    tenant.daysSucceeded++;
    tenant.rowsWritten += rowsWritten;
  }

  /**
   * Records that a resource failed for one day.
   *
   * @param resource the failing billing resource; may be null when it could not be read
   * @param resourceId id of the failing resource, used when the row itself is unavailable
   * @param reason kept only the first time: a resource failing on four days of a six-day window
   *     has one thing wrong with it, and the first failure is the one that explains it
   */
  public void resourceDayFailed(BillingResource resource, String resourceId, String reason) {
    ResourceTally tally = resource != null ? tallyFor(resource) : tallyForId(resourceId);
    tally.daysFailed++;
    if (tally.firstError == null) {
      tally.firstError = reason == null ? "no reason reported" : reason;
    }
  }

  /**
   * Writes every accumulated row and commits.
   *
   * <p>Never lets a logging failure break the run: the usage rows are already committed and
   * correct, and losing the log is worse reported than pretended away, so it is logged loudly
   * and swallowed.
   *
   * @return how many log rows were written
   */
  public int write() {
    if (tallies.isEmpty()) {
      return 0;
    }
    Date finishedAt = new Date();
    int written = 0;
    try {
      for (Map.Entry<String, ResourceTally> entry : tallies.entrySet()) {
        ResourceTally tally = entry.getValue();
        BillingResource resource = OBDal.getInstance().get(BillingResource.class, entry.getKey());
        if (resource == null) {
          continue;
        }
        String tenantStatus = tally.daysFailed > 0 ? STATUS_PARTIAL : STATUS_SUCCESS;
        for (Map.Entry<String, TenantTally> t : tally.tenants.entrySet()) {
          TenantTally tenant = t.getValue();
          UsageRunLog row = newRow(resource, finishedAt);
          row.setTenantClient(OBDal.getInstance().get(Client.class, t.getKey()));
          row.setStatus(tenantStatus);
          row.setDaysSucceeded(tenant.daysSucceeded);
          row.setRowsWritten(tenant.rowsWritten);
          row.setDaysFailed(0L);
          OBDal.getInstance().save(row);
          written++;
        }
        if (tally.daysFailed > 0) {
          // No tenant: this is the resource's own failure, and for the usual causes -- a
          // fragment that will not compile, a missing counter -- no tenant was ever reached.
          UsageRunLog row = newRow(resource, finishedAt);
          row.setStatus(tally.tenants.isEmpty() ? STATUS_ERROR : STATUS_PARTIAL);
          row.setDaysSucceeded(0L);
          row.setDaysFailed(tally.daysFailed);
          row.setRowsWritten(0L);
          row.setErrorMessage(UsageMessages.atSafe(tally.firstError));
          OBDal.getInstance().save(row);
          written++;
        }
      }
      OBDal.getInstance().commitAndClose();
      log.debug("Run {} wrote {} log row(s)", runId, written);
      return written;
    } catch (Exception e) {
      log.error("Could not write the usage run log for run {}: {}", runId, e.getMessage(), e);
      OBDal.getInstance().rollbackAndClose();
      return 0;
    }
  }

  private UsageRunLog newRow(BillingResource resource, Date finishedAt) {
    UsageRunLog row = OBProvider.getInstance().get(UsageRunLog.class);
    row.setNewOBObject(true);
    row.setClient(OBDal.getInstance().get(Client.class, SYSTEM_CLIENT));
    row.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ZERO));
    row.setBillingResource(resource);
    row.setRun(runId);
    row.setDateFrom(from);
    row.setDateTo(to);
    row.setStartedAt(startedAt);
    row.setFinishedAt(finishedAt);
    return row;
  }

  private ResourceTally tallyFor(BillingResource resource) {
    return tallies.computeIfAbsent(resource.getId(), k -> new ResourceTally());
  }

  private ResourceTally tallyForId(String resourceId) {
    return tallies.computeIfAbsent(resourceId, k -> new ResourceTally());
  }

  /** What one resource did across the whole run. */
  private static final class ResourceTally {
    private final Map<String, TenantTally> tenants = new LinkedHashMap<>();
    private long daysFailed;
    private String firstError;
  }

  /** What one tenant of one resource did across the whole run. */
  private static final class TenantTally {
    private long daysSucceeded;
    private long rowsWritten;
  }
}
