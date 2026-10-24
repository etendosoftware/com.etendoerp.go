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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.usage.IsolatedDalRead;

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
   * {@link DalQuotaLookup#enforcedQuotasByPlan}, read in their own session
   * ({@link IsolatedDalRead}) so a failing read cannot poison the request's transaction.
   *
   * @param subscriptionsByClient the subscription of each listed tenant (open, else the latest
   *     closed one — a closed row has no current period and so reports nothing)
   * @param evaluator the quota evaluator; {@link QuotaEvaluator#forReports()} in production
   * @return the report; empty on any failure
   */
  public static EnvironmentQuotaReport build(Map<String, Subscription> subscriptionsByClient,
      QuotaEvaluator evaluator) {
    return build(subscriptionsByClient, evaluator,
        planIds -> IsolatedDalRead.call(() -> DalQuotaLookup.enforcedQuotasByPlan(planIds)));
  }

  /**
   * Builds the report over an explicit loader of the enforced quota rows.
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
    Set<String> planIds = planIdsOf(subscriptionsByClient);
    if (planIds.isEmpty()) {
      return EMPTY;
    }
    Map<String, List<QuotaDefinition>> quotasByPlan = load(enforcedQuotasByPlan, planIds);
    if (quotasByPlan.isEmpty()) {
      return EMPTY;
    }
    Map<String, List<QuotaEvaluation>> reportable = new HashMap<>();
    for (Map.Entry<String, Subscription> entry : subscriptionsByClient.entrySet()) {
      List<QuotaEvaluation> evaluations = evaluateOne(entry.getKey(), entry.getValue(),
          quotasByPlan, evaluator);
      if (!evaluations.isEmpty()) {
        reportable.put(entry.getKey(), evaluations);
      }
    }
    return reportable.isEmpty() ? EMPTY : new EnvironmentQuotaReport(reportable);
  }

  private static Set<String> planIdsOf(Map<String, Subscription> subscriptionsByClient) {
    Set<String> planIds = new LinkedHashSet<>();
    if (subscriptionsByClient == null) {
      return planIds;
    }
    for (Subscription subscription : subscriptionsByClient.values()) {
      String planId = planIdOf(subscription);
      if (planId != null) {
        planIds.add(planId);
      }
    }
    return planIds;
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

  private static List<QuotaEvaluation> evaluateOne(String clientId, Subscription subscription,
      Map<String, List<QuotaDefinition>> quotasByPlan, QuotaEvaluator evaluator) {
    String planId = planIdOf(subscription);
    List<QuotaDefinition> rows = planId == null ? null : quotasByPlan.get(planId);
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      List<QuotaEvaluation> reportable = new ArrayList<>();
      for (QuotaEvaluation evaluation : evaluator.evaluate(
          TenantQuotas.ofDefinitions(clientId, subscription, rows),
          QuotaEvaluator.Scope.ENFORCEMENT)) {
        if (evaluation.isReportable()) {
          reportable.add(evaluation);
        }
      }
      return reportable;
    } catch (RuntimeException e) {
      log.error("[QUOTA] Could not evaluate the quotas of tenant {} for the environment list; "
          + "reporting none", clientId, e);
      return Collections.emptyList();
    }
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
}
