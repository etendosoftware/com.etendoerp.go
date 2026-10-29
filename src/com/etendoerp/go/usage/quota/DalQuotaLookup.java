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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.SubscriptionService;
import com.etendoerp.go.schemaforge.data.PlanQuota;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5051 — the production {@link QuotaLookup}: the tenant's open {@code ETGO_SUBSCRIPTION} row
 * ({@link SubscriptionService#findOpen}) and the active quota rows of its plan, read exactly as
 * {@code GET /sws/go/billing/subscription} reads them ({@link PlanCatalogService#listActiveQuotas}),
 * so the account page and the enforcement can never disagree about which rows exist (§5.1).
 */
public class DalQuotaLookup implements QuotaLookup {

  private final SubscriptionService subscriptionService;
  private final PlanCatalogService planCatalogService;

  /** Production wiring: a lookup over new subscription and plan catalog services. */
  public DalQuotaLookup() {
    this(new SubscriptionService(), new PlanCatalogService());
  }

  /**
   * A lookup over the given services.
   *
   * @param subscriptionService finds the open subscription row
   * @param planCatalogService lists the plan's active quota rows
   */
  public DalQuotaLookup(SubscriptionService subscriptionService,
      PlanCatalogService planCatalogService) {
    this.subscriptionService = subscriptionService;
    this.planCatalogService = planCatalogService;
  }

  @Override
  public TenantQuotas forTenant(String clientId) {
    Optional<Subscription> open = subscriptionService.findOpen(clientId);
    if (!open.isPresent() || open.get().getPlan() == null) {
      return TenantQuotas.none(clientId);
    }
    Subscription subscription = open.get();
    return TenantQuotas.of(clientId, subscription,
        planCatalogService.listActiveQuotas(subscription.getPlan()));
  }

  /**
   * ETP-5051 (§4b) — the active quota rows of several plans in one query, keeping only the rows
   * that are enforced (mode other than {@code off}): the only ones an enforcement report can show.
   * The same active-row reading as {@link PlanCatalogService#listActiveQuotas}, in the same
   * resource order.
   *
   * <p>The rows are snapshotted as {@link QuotaDefinition}s while the session is open, so the
   * result can outlive it (the environment list reads it in an isolated session).
   *
   * @param planIds the plan ids; null or empty reads nothing and runs no query
   * @return the definitions keyed by plan id; plans with no enforced row are absent
   */
  public static Map<String, List<QuotaDefinition>> enforcedQuotasByPlan(
      Collection<String> planIds) {
    if (planIds == null || planIds.isEmpty()) {
      return Collections.emptyMap();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<PlanQuota> query = OBDal.getInstance().createQuery(PlanQuota.class,
          "as quota where quota." + PlanQuota.PROPERTY_PLAN + ".id in (:planIds)"
              + " and quota." + PlanQuota.PROPERTY_ACTIVE + " = true"
              + " and quota." + PlanQuota.PROPERTY_ENFORCEMENTMODE + " <> :off"
              + " order by quota." + PlanQuota.PROPERTY_BILLINGRESOURCE + ".searchKey");
      query.setNamedParameter("planIds", planIds);
      query.setNamedParameter("off", QuotaRules.MODE_OFF);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      Map<String, List<QuotaDefinition>> byPlan = new LinkedHashMap<>();
      for (PlanQuota quota : query.list()) {
        byPlan.computeIfAbsent(quota.getPlan().getId(), k -> new ArrayList<>())
            .add(QuotaDefinition.of(quota));
      }
      return byPlan;
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
