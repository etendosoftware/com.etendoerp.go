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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.usage.DatabaseFailures;
import com.etendoerp.go.usage.IsolatedDalRead;
import com.etendoerp.go.usage.IsolatedReader;

/**
 * ETP-5051 (contract §4b) — the {@code quotas} of every environment of
 * {@code GET /sws/go/environments}: the WARN and DENY {@code QuotaItem}s of each tenant, evaluated
 * with {@link QuotaEvaluator.Scope#ENFORCEMENT}, built once per request from the subscriptions the
 * list already loaded.
 *
 * <p><b>Cost.</b> The quota rows of every listed plan are read in <b>one</b> query, and only the
 * rows that are enforced (mode other than {@code off}); none when no listed tenant has a plan. A
 * tenant whose plan has no such row is never evaluated, so the common no-quota case runs no
 * consumption query at all. Consumption is measured only for a tenant with an enforced quota and a
 * current period.
 *
 * <p><b>Never fails the list.</b> Any error — the batched read or one tenant's evaluation — leaves
 * that scope with {@code []} and a {@code [QUOTA]} log line.
 *
 * <p><b>One isolated hop.</b> The subscriptions are snapshotted on the request's thread, then the
 * batched read and every evaluation run together in one session of their own
 * ({@link IsolatedReader#DAL}), never on the request's. A failed SQL statement aborts that session
 * only: the remaining tenants are not evaluated (they report {@code []}), with one log line saying
 * how many. A hop whose caller stopped waiting stops before its next tenant.
 *
 * <p>Built per request and passed down, never cached: the next request on a pooled thread belongs
 * to another account.
 */
public final class EnvironmentQuotaReport {

  private static final Logger log = LogManager.getLogger(EnvironmentQuotaReport.class);

  private static final EnvironmentQuotaReport EMPTY =
      new EnvironmentQuotaReport(Collections.emptyMap());

  private final Map<String, List<QuotaEvaluation>> reportableByClient;

  private EnvironmentQuotaReport(Map<String, List<QuotaEvaluation>> reportableByClient) {
    this.reportableByClient = reportableByClient;
  }

  /**
   * A report with nothing to say for any tenant.
   *
   * @return the empty report
   */
  public static EnvironmentQuotaReport empty() {
    return EMPTY;
  }

  /**
   * Builds the report with the production wiring: the enforced quota rows come from
   * {@link DalQuotaLookup#enforcedQuotasByPlan}, and that read plus every consumption read run as
   * one hop in their own session ({@link IsolatedReader#DAL}), so a failing read cannot poison the
   * request's transaction.
   *
   * @param subscriptionsByClient the subscription of each listed tenant (open, else the latest
   *     closed one — a closed row has no current period and so reports nothing)
   * @param evaluator the quota evaluator; {@link QuotaEvaluator#forReports()} in production
   * @return the report; empty on any failure
   */
  public static EnvironmentQuotaReport build(Map<String, Subscription> subscriptionsByClient,
      QuotaEvaluator evaluator) {
    return build(subscriptionsByClient, evaluator, DalQuotaLookup::enforcedQuotasByPlan,
        IsolatedReader.DAL);
  }

  /**
   * Builds the report over an explicit loader of the enforced quota rows, on the caller's thread.
   *
   * @param subscriptionsByClient the subscription of each listed tenant
   * @param evaluator the quota evaluator
   * @param enforcedQuotasByPlan reads the enforced active quota rows of the given plan ids, as
   *     plain definitions keyed by plan id; called at most once, and never with an empty set
   * @return the report; empty on any failure
   */
  public static EnvironmentQuotaReport build(Map<String, Subscription> subscriptionsByClient,
      QuotaEvaluator evaluator,
      Function<Collection<String>, Map<String, List<QuotaDefinition>>> enforcedQuotasByPlan) {
    return build(subscriptionsByClient, evaluator, enforcedQuotasByPlan, IsolatedReader.INLINE);
  }

  /**
   * Builds the report. The subscriptions are snapshotted to plain values on the caller's thread
   * first — they belong to the caller's session — and the loader and the evaluations then run as
   * one {@code reads} call.
   *
   * @param subscriptionsByClient the subscription of each listed tenant
   * @param evaluator the quota evaluator
   * @param enforcedQuotasByPlan reads the enforced active quota rows of the given plan ids;
   *     called at most once, and never with an empty set
   * @param reads how the loader and the evaluations run
   * @return the report; empty on any failure
   */
  public static EnvironmentQuotaReport build(Map<String, Subscription> subscriptionsByClient,
      QuotaEvaluator evaluator,
      Function<Collection<String>, Map<String, List<QuotaDefinition>>> enforcedQuotasByPlan,
      IsolatedReader reads) {
    Map<String, Tenant> tenants = snapshot(subscriptionsByClient);
    Set<String> planIds = new LinkedHashSet<>();
    for (Tenant tenant : tenants.values()) {
      planIds.add(tenant.planId);
    }
    if (planIds.isEmpty()) {
      return EMPTY;
    }
    try {
      return reads.call(() -> evaluateAll(tenants, planIds, enforcedQuotasByPlan, evaluator));
    } catch (RuntimeException e) {
      log.error("[QUOTA] Could not run the environment quota report; reporting none", e);
      return EMPTY;
    }
  }

  private static EnvironmentQuotaReport evaluateAll(Map<String, Tenant> tenants,
      Set<String> planIds,
      Function<Collection<String>, Map<String, List<QuotaDefinition>>> enforcedQuotasByPlan,
      QuotaEvaluator evaluator) {
    Map<String, List<QuotaDefinition>> quotasByPlan = load(enforcedQuotasByPlan, planIds);
    if (quotasByPlan.isEmpty()) {
      return EMPTY;
    }
    List<Map.Entry<String, Tenant>> measured = measuredTenants(tenants, quotasByPlan);
    Map<String, List<QuotaEvaluation>> reportable = new HashMap<>();
    for (int i = 0; i < measured.size(); i++) {
      // The caller may have stopped waiting (timeout, shutdown): stop reading for it.
      IsolatedDalRead.checkNotAbandoned();
      Map.Entry<String, Tenant> entry = measured.get(i);
      if (!evaluateInto(reportable, entry, quotasByPlan, evaluator)) {
        // The failed statement aborted this session's transaction: every later read would fail.
        logSkipped(measured.size() - i - 1);
        break;
      }
    }
    return reportable.isEmpty() ? EMPTY : new EnvironmentQuotaReport(reportable);
  }

  /** The tenants whose plan has an enforced quota row: the only ones that are measured. */
  private static List<Map.Entry<String, Tenant>> measuredTenants(Map<String, Tenant> tenants,
      Map<String, List<QuotaDefinition>> quotasByPlan) {
    List<Map.Entry<String, Tenant>> measured = new ArrayList<>();
    for (Map.Entry<String, Tenant> entry : tenants.entrySet()) {
      List<QuotaDefinition> rows = quotasByPlan.get(entry.getValue().planId);
      if (rows != null && !rows.isEmpty()) {
        measured.add(entry);
      }
    }
    return measured;
  }

  /**
   * Evaluates one tenant into {@code reportable}. A failure reports nothing for it.
   *
   * @return {@code false} when the failure came from the database, so the hop must stop
   */
  private static boolean evaluateInto(Map<String, List<QuotaEvaluation>> reportable,
      Map.Entry<String, Tenant> entry, Map<String, List<QuotaDefinition>> quotasByPlan,
      QuotaEvaluator evaluator) {
    try {
      List<QuotaEvaluation> evaluations = evaluateOne(entry.getValue(),
          quotasByPlan.get(entry.getValue().planId), evaluator);
      if (!evaluations.isEmpty()) {
        reportable.put(entry.getKey(), evaluations);
      }
      return true;
    } catch (CancellationException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("[QUOTA] Could not evaluate the quotas of tenant {} for the environment list; "
          + "reporting none", entry.getKey(), e);
      return !DatabaseFailures.isDatabaseFailure(e);
    }
  }

  private static void logSkipped(int remaining) {
    if (remaining > 0) {
      log.error("[QUOTA] The environment list's read session failed on the database; not "
          + "evaluating the quotas of the {} remaining tenant(s), reporting none for them",
          remaining);
    }
  }

  /** The tenants that have a plan, as plain values: nothing of the caller's session leaves. */
  private static Map<String, Tenant> snapshot(Map<String, Subscription> subscriptionsByClient) {
    Map<String, Tenant> tenants = new LinkedHashMap<>();
    if (subscriptionsByClient == null) {
      return tenants;
    }
    for (Map.Entry<String, Subscription> entry : subscriptionsByClient.entrySet()) {
      try {
        String planId = planIdOf(entry.getValue());
        if (planId != null) {
          tenants.put(entry.getKey(), new Tenant(planId,
              TenantQuotas.ofDefinitions(entry.getKey(), entry.getValue(), null)));
        }
      } catch (RuntimeException e) {
        log.error("[QUOTA] Could not read the subscription of tenant {} for the environment list; "
            + "reporting none", entry.getKey(), e);
      }
    }
    return tenants;
  }

  private static String planIdOf(Subscription subscription) {
    Plan plan = subscription == null ? null : subscription.getPlan();
    return plan == null ? null : StringUtils.trimToNull(plan.getId());
  }

  private static Map<String, List<QuotaDefinition>> load(
      Function<Collection<String>, Map<String, List<QuotaDefinition>>> loader,
      Set<String> planIds) {
    try {
      Map<String, List<QuotaDefinition>> loaded = loader.apply(planIds);
      return loaded == null ? Collections.emptyMap() : loaded;
    } catch (RuntimeException e) {
      log.error("[QUOTA] Could not read the quota rows of the listed plans; reporting none", e);
      return Collections.emptyMap();
    }
  }

  private static List<QuotaEvaluation> evaluateOne(Tenant tenant, List<QuotaDefinition> rows,
      QuotaEvaluator evaluator) {
    List<QuotaEvaluation> reportable = new ArrayList<>();
    for (QuotaEvaluation evaluation : evaluator.evaluate(tenant.quotas.withQuotas(rows),
        QuotaEvaluator.Scope.ENFORCEMENT)) {
      if (evaluation.isReportable()) {
        reportable.add(evaluation);
      }
    }
    return reportable;
  }

  /**
   * The WARN and DENY evaluations of one tenant.
   *
   * @param clientId the tenant
   * @return its WARN and DENY evaluations, possibly empty
   */
  public List<QuotaEvaluation> reportableFor(String clientId) {
    return reportableByClient.getOrDefault(clientId, Collections.emptyList());
  }

  /**
   * The {@code quotas} array of one tenant's environment entries.
   *
   * @param clientId the tenant
   * @return the {@code quotas} array of its environment entries; {@code []} when nothing to report
   */
  public JSONArray quotasJsonFor(String clientId) {
    try {
      return QuotaItemJson.ofAll(reportableFor(clientId));
    } catch (JSONException e) {
      log.error("[QUOTA] Could not render the quotas of tenant {}; reporting none", clientId, e);
      return new JSONArray();
    }
  }

  /** One listed tenant: its plan id and its subscription period, with no quota rows yet. */
  private static final class Tenant {
    private final String planId;
    private final TenantQuotas quotas;

    private Tenant(String planId, TenantQuotas quotas) {
      this.planId = planId;
      this.quotas = quotas;
    }
  }
}
