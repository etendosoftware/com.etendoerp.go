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

import java.util.Optional;

import com.etendoerp.go.payment.PlanCatalogService;
import com.etendoerp.go.payment.SubscriptionService;
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

  /** Production wiring. */
  public DalQuotaLookup() {
    this(new SubscriptionService(), new PlanCatalogService());
  }

  /**
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
}
