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

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.usage.IsolatedDalRead;
import com.etendoerp.go.usage.KeyedLogThrottle;

/**
 * ETP-5051 — the one quota evaluator. Generic and data-driven: no switch over resource names, no
 * class per resource. Every enforcement point — the account page, the environment entry, the NEO
 * write path — takes its verdict from here, and the only comparison is {@link QuotaRules#verdict}.
 *
 * <p>The rules it applies, all from {@code open-and-notable-topics.md}:
 * <ul>
 *   <li><b>No quota row is unlimited, with no consumption query</b> (§5.1). An inactive row is no
 *       row (the lookup reads only active rows).</li>
 *   <li><b>The window is the subscription period</b>, never the calendar month (§5.5); on a yearly
 *       plan a quota is a yearly quota (§5.8).</li>
 *   <li><b>No current period: ALLOW, with no consumption query</b> (§5.9, decided 2026-10-24), and
 *       one WARN per tenant per hour while the row is open.</li>
 *   <li><b>Mode {@code off}</b>: no verdict; measured only on the account page.</li>
 * </ul>
 *
 * <p><b>Seams for tests:</b> the {@link QuotaLookup} (tenant → quotas and period), the
 * {@link ConsumptionReader} (every consumption query) and the {@link Clock}.
 */
public class QuotaEvaluator {

  private static final Logger log = LogManager.getLogger(QuotaEvaluator.class);

  private static final long PERIODLESS_WARN_INTERVAL_MS = 3_600_000L;
  /**
   * Shared by every evaluator — the environment list, environment entry and the account page, the
   * write guard — so the §5.9 WARN is one line per tenant per hour, not one per evaluator. Each
   * evaluator passes its own clock's time.
   */
  private static final KeyedLogThrottle PERIODLESS_WARNINGS =
      new KeyedLogThrottle(PERIODLESS_WARN_INTERVAL_MS, System::currentTimeMillis);

  /** What a caller needs measured. */
  public enum Scope {
    /**
     * The account page: every active quota is measured, mode {@code off} included (its verdict
     * stays null). Contract §1.
     */
    ACCOUNT,
    /**
     * An enforcement point: a quota in mode {@code off} is neither measured nor evaluated, so it
     * costs no query. Contract §2.
     */
    ENFORCEMENT
  }

  private final QuotaLookup lookup;
  private final ConsumptionReader reader;
  private final Clock clock;
  private final QuotaLock lock;

  /**
   * The write-check evaluator: reads on the request's own session, so a live count sees the row
   * just inserted, and serialises live {@code block} checks with the advisory {@link QuotaLock}.
   * It never checks the usage lag: that WARN belongs to the reports and the usage job.
   */
  public QuotaEvaluator() {
    this(new DalQuotaLookup(), DalConsumptionReader.withoutLagCheck(), Clock.systemUTC(),
        QuotaLock.advisory());
  }

  /**
   * An evaluator with no lock, for reports and tests: {@link #consumptionBeforeCreate} then takes none.
   *
   * @param lookup finds a tenant's quotas and period
   * @param reader reads consumption; the only place a consumption query runs
   * @param clock decides "now", hence which period is current
   */
  public QuotaEvaluator(QuotaLookup lookup, ConsumptionReader reader, Clock clock) {
    this(lookup, reader, clock, QuotaLock.NONE);
  }

  /**
   * An evaluator over explicit seams.
   *
   * @param lookup finds a tenant's quotas and period
   * @param reader reads consumption; the only place a consumption query runs
   * @param clock decides "now", hence which period is current
   * @param lock serialises the live write check of one tenant and resource
   */
  public QuotaEvaluator(QuotaLookup lookup, ConsumptionReader reader, Clock clock,
      QuotaLock lock) {
    this.lookup = lookup;
    this.reader = reader;
    this.clock = clock;
    this.lock = lock;
  }

  /**
   * The evaluator for quota <em>reports</em> — environment entry, the environment list, the account
   * page. It reads on whatever session the thread has and takes no lock; its callers run each
   * report as one {@link com.etendoerp.go.usage.IsolatedDalRead} hop, so a failing report read can
   * never poison the request's transaction and silently roll back what the request writes
   * afterwards. It must not run the write check: in that hop it cannot see uncommitted rows.
   *
   * @return a report evaluator
   */
  public static QuotaEvaluator forReports() {
    return new QuotaEvaluator(new DalQuotaLookup(), new DalConsumptionReader(), Clock.systemUTC(),
        QuotaLock.NONE);
  }

  /** Forgets which tenants were warned about: for tests, whose clocks jump. */
  static void resetPeriodlessWarnings() {
    PERIODLESS_WARNINGS.reset();
  }

  /**
   * Evaluates the quotas enforced on a tenant now (its open subscription).
   *
   * @param clientId the tenant
   * @param scope what to measure
   * @return one evaluation per active quota row, in catalog order; empty when unlimited
   */
  public List<QuotaEvaluation> evaluateTenant(String clientId, Scope scope) {
    if (StringUtils.isBlank(clientId)) {
      return Collections.emptyList();
    }
    return evaluate(lookup.forTenant(clientId), scope);
  }

  /**
   * Evaluates the quotas of an already looked-up tenant, for a caller that holds the subscription
   * row itself (the account page reads the latest row, open or closed).
   *
   * @param tenant the tenant's quotas and period
   * @param scope what to measure
   * @return one evaluation per quota, in the given order; empty when there is no quota
   * @throws java.util.concurrent.CancellationException when the thread is interrupted between two
   *     quotas: an isolated worker whose caller stopped waiting
   */
  public List<QuotaEvaluation> evaluate(TenantQuotas tenant, Scope scope) {
    if (tenant == null || tenant.getQuotas().isEmpty()) {
      return Collections.emptyList();
    }
    QuotaPeriod period = currentPeriodOrWarn(tenant, Date.from(clock.instant()));
    List<QuotaEvaluation> evaluations = new ArrayList<>(tenant.getQuotas().size());
    for (QuotaDefinition quota : tenant.getQuotas()) {
      // On an isolated worker whose caller gave up, stop before the next consumption query.
      IsolatedDalRead.checkNotAbandoned();
      evaluations.add(evaluateOne(tenant, quota, period, scope));
    }
    return evaluations;
  }

  /**
   * The current period of the NEO write check, or null when the tenant has none — then nothing is
   * checked (§5.9), with the throttled WARN of a periodless open row.
   *
   * @param tenant the tenant of the created rows
   * @return the current period, or null
   */
  public QuotaPeriod writeCheckPeriod(TenantQuotas tenant) {
    return tenant == null ? null : currentPeriodOrWarn(tenant, Date.from(clock.instant()));
  }

  /**
   * The consumption of a {@code block} quota's resource <b>before</b> a create, read once per quota
   * for all the rows of that create: {@link ConsumptionReader#readBeforeCreate} — a
   * {@link ConsumptionSource#LIVE live} count that leaves out the rows the create inserted, or the
   * {@link ConsumptionSource#AGGREGATE stored aggregate}, lagged and blind to them. A live read
   * first takes the {@link QuotaLock} of the tenant and resource, held to commit, so concurrent
   * creates are counted one after the other; the aggregate's cap is approximate by its lag and
   * takes none. A quota that is not in mode {@code block} is neither locked nor read.
   *
   * @param tenant the tenant the rows belong to
   * @param quota the quota whose resource counts the rows
   * @param period the current period, from {@link #writeCheckPeriod}
   * @param createdIds the ids of every row the create inserted
   * @return the consumption before the create; 0 for a quota that cannot refuse
   */
  public long consumptionBeforeCreate(TenantQuotas tenant, QuotaDefinition quota,
      QuotaPeriod period, Collection<String> createdIds) {
    if (!canRefuse(tenant, quota, period)) {
      return 0L;
    }
    if (quota.getSource() == ConsumptionSource.LIVE) {
      // Serialise with every concurrent create of this tenant and resource until commit, so two
      // creates cannot both count the same consumption and both slip under the quota.
      lock.acquire(tenant.getClientId(), quota.getResourceId());
    }
    return reader.readBeforeCreate(quota, tenant.getClientId(), period, createdIds);
  }

  /**
   * The NEO write check for one created row that the quota's resource counts <b>inside the
   * period's counted window</b> (contract §3); the caller has established both with
   * {@link CreatedRowMatcher}. A row dated outside the window does not consume this period and is
   * never brought here.
   *
   * <p>{@code consumedBefore} is {@link #consumptionBeforeCreate} plus the rows of the same create
   * already charged to this quota. The row is refused when that number plus one exceeds the
   * included quantity, i.e. when {@link QuotaRules#verdict} answers DENY for it; the reported
   * {@code consumed} is that same number. No query runs here.
   *
   * @param tenant the tenant the row belongs to
   * @param quota the {@code block} quota whose resource counts the row
   * @param period the current period, from {@link #writeCheckPeriod}
   * @param consumedBefore the consumption before this row
   * @return the DENY evaluation, or empty when the row may stand
   */
  public Optional<QuotaEvaluation> refusalForCreate(TenantQuotas tenant, QuotaDefinition quota,
      QuotaPeriod period, long consumedBefore) {
    if (!canRefuse(tenant, quota, period)) {
      return Optional.empty();
    }
    QuotaVerdict verdict = QuotaRules.verdict(quota.getEnforcementMode(), quota.getIncludedQty(),
        quota.getWarningThreshold(), consumedBefore);
    if (verdict != QuotaVerdict.DENY) {
      return Optional.empty();
    }
    return Optional.of(new QuotaEvaluation(quota, tenant.getPlanInterval(), period,
        consumedBefore, verdict));
  }

  private static boolean canRefuse(TenantQuotas tenant, QuotaDefinition quota,
      QuotaPeriod period) {
    return tenant != null && quota != null && period != null
        && QuotaRules.MODE_BLOCK.equals(quota.getEnforcementMode());
  }

  private QuotaEvaluation evaluateOne(TenantQuotas tenant, QuotaDefinition quota,
      QuotaPeriod period, Scope scope) {
    boolean enforced = QuotaRules.isEnforced(quota.getEnforcementMode());
    if (period == null || (!enforced && scope == Scope.ENFORCEMENT)) {
      return new QuotaEvaluation(quota, tenant.getPlanInterval(), period, null, null);
    }
    long consumed = reader.read(quota, tenant.getClientId(), period);
    QuotaVerdict verdict = QuotaRules.verdict(quota.getEnforcementMode(), quota.getIncludedQty(),
        quota.getWarningThreshold(), consumed);
    return new QuotaEvaluation(quota, tenant.getPlanInterval(), period, consumed, verdict);
  }

  /**
   * The current period, or null. An open row with quotas and no current period is the §5.9 case:
   * ALLOW without measuring, and one WARN per tenant per hour so it is seen and fixed by hand. A
   * closed row has no current period by design and is not warned about.
   */
  private QuotaPeriod currentPeriodOrWarn(TenantQuotas tenant, Date now) {
    QuotaPeriod period = tenant.currentPeriod(now);
    if (period == null && !tenant.isClosed()
        && PERIODLESS_WARNINGS.shouldLog(tenant.getClientId(), clock.millis())) {
      log.warn("[QUOTA] Tenant {} has {} active quota row(s) but its subscription has no current "
          + "billing period; its quotas are not enforced (ALLOW, nothing measured) until "
          + "CURRENT_PERIOD_START/END are filled (open-and-notable-topics.md §5.9).",
          tenant.getClientId(), tenant.getQuotas().size());
    }
    return period;
  }
}
