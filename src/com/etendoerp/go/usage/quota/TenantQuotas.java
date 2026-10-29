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
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5051 — what the evaluator needs to know about one tenant: its subscription's period bounds
 * and its plan's active quota rows. Plain values, so a test builds one without a database.
 *
 * <p>An empty quota list means <b>unlimited</b> ({@code open-and-notable-topics.md} §5.1), and the
 * evaluator then runs no consumption query at all. The rows are exactly those
 * {@code PlanCatalogService.listActiveQuotas} returns, so an inactive row is no row — the same
 * reading as {@code GET /sws/go/billing/subscription}.
 */
public final class TenantQuotas {

  private final String clientId;
  private final String planInterval;
  private final Date periodStart;
  private final Date periodEnd;
  private final boolean closed;
  private final Date anchor;
  private final List<QuotaDefinition> quotas;

  /** Plan limits are yearly whatever the billing interval: the window every quota reports. */
  public static final String QUOTA_WINDOW = "year";

  /**
   * Creates a tenant view from plain values.
   *
   * @param clientId the tenant
   * @param planInterval the plan's billing interval ({@code month} / {@code year}), or null
   * @param periodStart {@code CURRENT_PERIOD_START}, or null
   * @param periodEnd {@code CURRENT_PERIOD_END}, or null
   * @param closed whether the subscription row is closed ({@code END_DATE} set)
   * @param quotas the plan's active quota rows; null reads as none
   */
  public TenantQuotas(String clientId, String planInterval, Date periodStart, Date periodEnd,
      boolean closed, List<QuotaDefinition> quotas) {
    this(clientId, planInterval, periodStart, periodEnd, closed, null, quotas);
  }

  /**
   * Creates a tenant view whose quotas count over the subscription year.
   *
   * @param clientId the tenant
   * @param planInterval the plan's billing interval ({@code month} / {@code year}), or null
   * @param periodStart {@code CURRENT_PERIOD_START}, or null
   * @param periodEnd {@code CURRENT_PERIOD_END}, or null
   * @param closed whether the subscription row is closed ({@code END_DATE} set)
   * @param anchor {@code START_DATE}, the anniversary of the yearly quota window; null counts over
   *     the billing period instead
   * @param quotas the plan's active quota rows; null reads as none
   */
  public TenantQuotas(String clientId, String planInterval, Date periodStart, Date periodEnd,
      boolean closed, Date anchor, List<QuotaDefinition> quotas) {
    this.clientId = clientId;
    this.anchor = anchor == null ? null : new Date(anchor.getTime());
    this.planInterval = StringUtils.trimToNull(planInterval);
    this.periodStart = periodStart == null ? null : new Date(periodStart.getTime());
    this.periodEnd = periodEnd == null ? null : new Date(periodEnd.getTime());
    this.closed = closed;
    this.quotas = quotas == null ? Collections.emptyList()
        : Collections.unmodifiableList(new ArrayList<>(quotas));
  }

  /**
   * A tenant with no subscription row: no plan, so no quota, so unlimited.
   *
   * @param clientId the tenant
   * @return a tenant with no quotas
   */
  public static TenantQuotas none(String clientId) {
    return new TenantQuotas(clientId, null, null, null, false, null);
  }

  /**
   * Snapshots a subscription row and the active quota rows of its plan.
   *
   * @param clientId the tenant
   * @param subscription the tenant's subscription row
   * @param activeQuotas {@code PlanCatalogService.listActiveQuotas(subscription.getPlan())}
   * @return the detached view
   */
  public static TenantQuotas of(String clientId, Subscription subscription,
      List<PlanQuota> activeQuotas) {
    List<QuotaDefinition> definitions = new ArrayList<>();
    if (subscription != null && activeQuotas != null) {
      for (PlanQuota quota : activeQuotas) {
        definitions.add(QuotaDefinition.of(quota));
      }
    }
    return ofDefinitions(clientId, subscription, definitions);
  }

  /**
   * Snapshots a subscription row with quota definitions already detached from the DAL — the
   * environment list reads them for every listed plan at once.
   *
   * @param clientId the tenant
   * @param subscription the tenant's subscription row
   * @param definitions the enforced quota definitions of its plan
   * @return the detached view
   */
  public static TenantQuotas ofDefinitions(String clientId, Subscription subscription,
      List<QuotaDefinition> definitions) {
    if (subscription == null) {
      return none(clientId);
    }
    Plan plan = subscription.getPlan();
    return new TenantQuotas(clientId, plan == null ? null : plan.getBillingInterval(),
        subscription.getCurrentPeriodStart(), subscription.getCurrentPeriodEnd(),
        subscription.getEndDate() != null, subscription.getStartDate(), definitions);
  }

  /**
   * The same tenant and period with other quota rows: the environment list snapshots the period
   * on the request's thread and adds the rows it reads in its own session.
   *
   * @param definitions the quota definitions; null reads as none
   * @return a new view
   */
  public TenantQuotas withQuotas(List<QuotaDefinition> definitions) {
    return new TenantQuotas(clientId, planInterval, periodStart, periodEnd, closed, anchor,
        definitions);
  }

  public String getClientId() {
    return clientId;
  }

  /**
   * @return the window a quota is consumed over: {@link #QUOTA_WINDOW} when the subscription year
   *     is known, else the plan's billing interval ({@code month}, {@code year} or null)
   */
  public String getPlanInterval() {
    return anchor != null ? QUOTA_WINDOW : planInterval;
  }

  /** @return whether the subscription row is closed */
  public boolean isClosed() {
    return closed;
  }

  public List<QuotaDefinition> getQuotas() {
    return quotas;
  }

  /**
   * The quota window in effect at {@code now}: the subscription year containing it. A billing
   * period in effect is still required, so a subscription with none is not measured (§5.9).
   *
   * @param now the evaluation instant
   * @return the current window, or null when there is no billing period in effect (§5.9)
   */
  public QuotaPeriod currentPeriod(Date now) {
    QuotaPeriod billing = QuotaPeriod.current(periodStart, periodEnd, closed, now);
    if (billing == null) {
      return null;
    }
    QuotaPeriod year = QuotaPeriod.yearContaining(anchor, now);
    return year != null ? year : billing;
  }
}
