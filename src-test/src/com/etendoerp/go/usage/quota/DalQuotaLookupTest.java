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
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Finding the quotas enforced on a tenant: the active rows of the plan of its open subscription,
 * and, for the environment list, the enforced active rows of several plans in one query.
 *
 * @covers com.etendoerp.go.usage.quota.DalQuotaLookup
 */
class DalQuotaLookupTest {

  private final SubscriptionService subscriptions = mock(SubscriptionService.class);
  private final PlanCatalogService catalog = mock(PlanCatalogService.class);
  private final DalQuotaLookup lookup = new DalQuotaLookup(subscriptions, catalog);

  /** A subscription start in the year before NOW. */
  private static final Date ANCHOR = QuotaTestFixtures.local(2026, 3, 3, 8, 0);

  private static Plan plan(String id) {
    Plan plan = mock(Plan.class);
    when(plan.getId()).thenReturn(id);
    when(plan.getBillingInterval()).thenReturn("month");
    return plan;
  }

  private static PlanQuota activeQuota(Plan plan, String key) {
    BillingResource resource = mock(BillingResource.class);
    when(resource.getSearchKey()).thenReturn(key);
    PlanQuota quota = mock(PlanQuota.class);
    when(quota.getPlan()).thenReturn(plan);
    when(quota.getBillingResource()).thenReturn(resource);
    when(quota.getEnforcementMode()).thenReturn("block");
    return quota;
  }

  @Test
  void aTenantWithoutAnOpenSubscriptionHasNoQuotas() {
    when(subscriptions.findOpen(CLIENT)).thenReturn(Optional.empty());

    TenantQuotas tenant = lookup.forTenant(CLIENT);

    assertTrue(tenant.getQuotas().isEmpty());
    assertEquals(CLIENT, tenant.getClientId());
    verifyNoInteractions(catalog);
  }

  @Test
  void anOpenSubscriptionWithoutAPlanHasNoQuotas() {
    Subscription subscription = mock(Subscription.class);
    when(subscriptions.findOpen(CLIENT)).thenReturn(Optional.of(subscription));

    assertTrue(lookup.forTenant(CLIENT).getQuotas().isEmpty());
    verifyNoInteractions(catalog);
  }

  @Test
  void theOpenSubscriptionsPlanGivesTheActiveRowsAndThePeriod() {
    Plan plan = plan("PLAN-1");
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);
    when(subscriptions.findOpen(CLIENT)).thenReturn(Optional.of(subscription));
    PlanQuota docs = activeQuota(plan, "DOCS");
    when(catalog.listActiveQuotas(plan)).thenReturn(List.of(docs));

    TenantQuotas tenant = lookup.forTenant(CLIENT);

    assertEquals(1, tenant.getQuotas().size());
    assertEquals("DOCS", tenant.getQuotas().get(0).getResourceKey());
    assertEquals("month", tenant.getPlanInterval());
    assertEquals(PERIOD_START, tenant.currentPeriod(NOW).getStart());
  }

  /** ETP-5051: the subscription's START_DATE anchors the yearly quota window. */
  @Test
  void theSubscriptionStartAnchorsTheYearlyWindow() {
    Plan plan = plan("PLAN-1");
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscription.getStartDate()).thenReturn(ANCHOR);
    when(subscription.getCurrentPeriodStart()).thenReturn(PERIOD_START);
    when(subscription.getCurrentPeriodEnd()).thenReturn(PERIOD_END);
    when(subscriptions.findOpen(CLIENT)).thenReturn(Optional.of(subscription));
    when(catalog.listActiveQuotas(plan)).thenReturn(Collections.emptyList());

    TenantQuotas tenant = lookup.forTenant(CLIENT);

    assertEquals("year", tenant.getPlanInterval());
    assertEquals(ANCHOR, tenant.currentPeriod(NOW).getStart());
  }

  @Test
  void aPlanWhoseRowsAreAllInactiveGivesNoQuotas() {
    Plan plan = plan("PLAN-1");
    Subscription subscription = mock(Subscription.class);
    when(subscription.getPlan()).thenReturn(plan);
    when(subscriptions.findOpen(CLIENT)).thenReturn(Optional.of(subscription));
    when(catalog.listActiveQuotas(plan)).thenReturn(Collections.emptyList());

    assertTrue(lookup.forTenant(CLIENT).getQuotas().isEmpty());
  }

  @Test
  void enforcedRowsOfNoPlansRunNoQuery() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertTrue(DalQuotaLookup.enforcedQuotasByPlan(null).isEmpty());
      assertTrue(DalQuotaLookup.enforcedQuotasByPlan(Collections.emptyList()).isEmpty());
      obDal.verifyNoInteractions();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void enforcedRowsAreReadInOneQueryAndGroupedByPlan() {
    Plan first = plan("PLAN-1");
    Plan second = plan("PLAN-2");
    PlanQuota a = activeQuota(first, "A");
    PlanQuota b = activeQuota(second, "B");
    PlanQuota c = activeQuota(first, "C");
    OBDal dal = mock(OBDal.class);
    OBQuery<PlanQuota> query = mock(OBQuery.class);
    when(dal.createQuery(eq(PlanQuota.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(a, b, c));

    Map<String, List<QuotaDefinition>> byPlan;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      byPlan = DalQuotaLookup.enforcedQuotasByPlan(List.of("PLAN-1", "PLAN-2"));
      obContext.verify(() -> OBContext.setAdminMode(true));
      obContext.verify(OBContext::restorePreviousMode);
    }

    // Detached while the session is open: plain definitions, in the query's order.
    assertEquals(List.of("A", "C"), keysOf(byPlan.get("PLAN-1")));
    assertEquals(List.of("B"), keysOf(byPlan.get("PLAN-2")));
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(dal).createQuery(eq(PlanQuota.class), hql.capture());
    assertTrue(hql.getValue().contains(".id in (:planIds)"), hql.getValue());
    assertTrue(hql.getValue().contains("active = true"), hql.getValue());
    assertTrue(hql.getValue().contains("<> :off"), hql.getValue());
    verify(query).setNamedParameter("planIds", List.of("PLAN-1", "PLAN-2"));
    verify(query).setNamedParameter("off", "off");
    verify(query).setFilterOnReadableClients(false);
    verify(query).setFilterOnReadableOrganization(false);
  }

  private static List<String> keysOf(List<QuotaDefinition> definitions) {
    List<String> keys = new java.util.ArrayList<>();
    for (QuotaDefinition definition : definitions) {
      keys.add(definition.getResourceKey());
    }
    return keys;
  }
}
