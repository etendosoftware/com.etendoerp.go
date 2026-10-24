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

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.OTHER_CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.usage.quota.QuotaTestFixtures.MutableClock;

/**
 * The per-request quota report of the environment list: one batched read of the listed plans'
 * enforced quota rows, consumption only for a tenant that has one, and only the WARN and DENY
 * items reported. A failure never fails the list: it reports nothing.
 *
 * @covers com.etendoerp.go.usage.quota.EnvironmentQuotaReport
 */
class EnvironmentQuotaReportTest {

  private ConsumptionReader reader;
  private QuotaEvaluator evaluator;
  private List<Collection<String>> loaderCalls;

  @BeforeEach
  void setUp() {
    reader = mock(ConsumptionReader.class);
    evaluator = new QuotaEvaluator(mock(QuotaLookup.class), reader, new MutableClock(NOW));
    loaderCalls = new ArrayList<>();
  }

  private Function<Collection<String>, Map<String, List<PlanQuota>>> loader(
      Map<String, List<PlanQuota>> rows) {
    return planIds -> {
      loaderCalls.add(new ArrayList<>(planIds));
      return rows;
    };
  }

  private static Plan plan(String id) {
    Plan plan = mock(Plan.class);
    when(plan.getId()).thenReturn(id);
    when(plan.getBillingInterval()).thenReturn("month");
    return plan;
  }

  private static Subscription subscription(Plan plan) {
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);
    return subscription;
  }

  private static PlanQuota row(String key, String mode, long included) {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getId()).thenReturn("RES-" + key);
    when(resource.getSearchKey()).thenReturn(key);
    when(resource.getName()).thenReturn("Name of " + key);
    PlanQuota quota = mock(PlanQuota.class);
    when(quota.getBillingResource()).thenReturn(resource);
    when(quota.getEnforcementMode()).thenReturn(mode);
    when(quota.getIncludedQty()).thenReturn(included);
    when(quota.getWarningThreshold()).thenReturn(80L);
    return quota;
  }

  private void consumed(String key, long quantity) {
    when(reader.read(org.mockito.ArgumentMatchers.argThat(
        quota -> quota != null && key.equals(quota.getResourceKey())), anyString(), any()))
        .thenReturn(quantity);
  }

  @Test
  void noListedTenantHasAPlanSoTheLoaderIsNeverCalled() {
    Map<String, Subscription> subscriptions = new HashMap<>();
    subscriptions.put(CLIENT, null);
    subscriptions.put(OTHER_CLIENT, subscription(null));

    EnvironmentQuotaReport report = EnvironmentQuotaReport.build(subscriptions, evaluator,
        loader(Collections.emptyMap()));

    assertSame(EnvironmentQuotaReport.empty(), report);
    assertTrue(loaderCalls.isEmpty());
    verifyNoInteractions(reader);
  }

  @Test
  void noListedTenantAtAllReportsNothing() {
    assertSame(EnvironmentQuotaReport.empty(),
        EnvironmentQuotaReport.build(Collections.emptyMap(), evaluator, loader(null)));
    assertSame(EnvironmentQuotaReport.empty(),
        EnvironmentQuotaReport.build(null, evaluator, loader(null)));
    assertTrue(loaderCalls.isEmpty());
  }

  @Test
  void theLoaderIsCalledOnceWithTheDistinctPlans() {
    Plan shared = plan("PLAN-1");
    Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    subscriptions.put(CLIENT, subscription(shared));
    subscriptions.put(OTHER_CLIENT, subscription(shared));

    EnvironmentQuotaReport.build(subscriptions, evaluator, loader(Collections.emptyMap()));

    assertEquals(List.of(List.of("PLAN-1")), loaderCalls);
    verifyNoInteractions(reader);
  }

  @Test
  void aLoaderFailureGivesAnEmptyReport() throws Exception {
    EnvironmentQuotaReport report = EnvironmentQuotaReport.build(
        Map.of(CLIENT, subscription(plan("PLAN-1"))), evaluator, planIds -> {
          throw new IllegalStateException("database unavailable");
        });

    assertSame(EnvironmentQuotaReport.empty(), report);
    assertEquals(0, report.quotasJsonFor(CLIENT).length());
    verifyNoInteractions(reader);
  }

  @Test
  void aTenantWhosePlanHasNoEnforcedRowIsNotEvaluated() {
    Plan enforced = plan("PLAN-ENFORCED");
    Plan unenforced = plan("PLAN-OFF-ONLY");
    Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    subscriptions.put(CLIENT, subscription(enforced));
    subscriptions.put(OTHER_CLIENT, subscription(unenforced));
    consumed("DOCS", 100L);

    EnvironmentQuotaReport report = EnvironmentQuotaReport.build(subscriptions, evaluator,
        loader(Map.of("PLAN-ENFORCED", List.of(row("DOCS", "block", 100L)))));

    assertEquals(1, report.reportableFor(CLIENT).size());
    assertTrue(report.reportableFor(OTHER_CLIENT).isEmpty());
    verify(reader, never()).read(any(), eq(OTHER_CLIENT), any());
  }

  @Test
  void onlyWarnAndDenyItemsAreReported() throws Exception {
    Map<String, Subscription> subscriptions = Map.of(CLIENT, subscription(plan("PLAN-1")));
    consumed("ALLOWED", 1L);
    consumed("WARNED", 85L);
    consumed("DENIED", 100L);

    EnvironmentQuotaReport report = EnvironmentQuotaReport.build(subscriptions, evaluator,
        loader(Map.of("PLAN-1", List.of(row("ALLOWED", "block", 100L),
            row("DENIED", "block", 100L), row("WARNED", "warn", 100L)))));

    JSONArray quotas = report.quotasJsonFor(CLIENT);
    assertEquals(2, quotas.length());
    assertEquals("DENIED", quotas.getJSONObject(0).getString("resourceKey"));
    assertEquals("DENY", quotas.getJSONObject(0).getString("verdict"));
    assertEquals(100L, quotas.getJSONObject(0).getLong("consumed"));
    assertEquals("WARNED", quotas.getJSONObject(1).getString("resourceKey"));
    assertEquals("WARN", quotas.getJSONObject(1).getString("verdict"));
    assertEquals(0, report.quotasJsonFor("UNLISTED").length());
  }

  @Test
  void aTenantWithOnlyAllowedItemsHasNothingToReport() {
    Map<String, Subscription> subscriptions = Map.of(CLIENT, subscription(plan("PLAN-1")));
    consumed("DOCS", 1L);

    EnvironmentQuotaReport report = EnvironmentQuotaReport.build(subscriptions, evaluator,
        loader(Map.of("PLAN-1", List.of(row("DOCS", "block", 100L)))));

    assertSame(EnvironmentQuotaReport.empty(), report);
  }

  @Test
  void anEvaluationFailureForOneTenantReportsNothingForItOnly() {
    Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    subscriptions.put(CLIENT, subscription(plan("PLAN-1")));
    subscriptions.put(OTHER_CLIENT, subscription(plan("PLAN-1")));
    when(reader.read(any(), eq(CLIENT), any())).thenThrow(new IllegalStateException("boom"));
    when(reader.read(any(), eq(OTHER_CLIENT), any())).thenReturn(100L);

    EnvironmentQuotaReport report = EnvironmentQuotaReport.build(subscriptions, evaluator,
        loader(Map.of("PLAN-1", List.of(row("DOCS", "block", 100L)))));

    assertTrue(report.reportableFor(CLIENT).isEmpty());
    assertEquals(1, report.reportableFor(OTHER_CLIENT).size());
  }
}
