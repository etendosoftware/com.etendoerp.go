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

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.usage.TenantSeatCounter;

/**
 * The quota check of a new seat (a user created, or reactivated, in the Users window). A strategy
 * resource is never refused by the NEO write path, so the seat check runs here, at the user
 * paths, and still takes its verdict from {@link QuotaEvaluator}.
 *
 * <p>The quota checked is the plan's {@code block} quota whose resource is counted by
 * {@link TenantSeatCounter}; no such quota, no current period or another mode lets the seat
 * through, as every other quota does.
 */
public final class SeatQuotaGuard {

  private SeatQuotaGuard() {
  }

  /**
   * Whether a tenant may take one more seat.
   *
   * @param clientId the tenant
   * @return the DENY evaluation when the tenant already uses every seat of its plan, else empty
   */
  public static Optional<QuotaEvaluation> refusalForNewSeat(String clientId) {
    if (!isTenant(clientId)) {
      return Optional.empty();
    }
    TenantQuotas tenant = new DalQuotaLookup().forTenant(clientId);
    QuotaEvaluator evaluator = new QuotaEvaluator();
    QuotaPeriod period = evaluator.writeCheckPeriod(tenant);
    if (period == null) {
      return Optional.empty();
    }
    TenantSeatCounter counter = new TenantSeatCounter();
    for (QuotaDefinition quota : tenant.getQuotas()) {
      if (TenantSeatCounter.QUALIFIER.equals(quota.getStrategyQualifier())) {
        Optional<QuotaEvaluation> refusal = evaluator.refusalForCreate(tenant, quota, period,
            counter.seatsOf(clientId));
        if (refusal.isPresent()) {
          return refusal;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * ETP-5051 — whether a tenant may take back the seat of a user being reactivated. Only a user
   * that is a seat once active ({@link TenantSeatCounter#isSeatOnceActive}) takes one; any other
   * reactivation is never refused.
   *
   * @param clientId the tenant of the user
   * @param userId the user being reactivated
   * @return the DENY evaluation when the user takes a seat and every seat of the plan is taken,
   *     else empty
   */
  public static Optional<QuotaEvaluation> refusalForReactivation(String clientId,
      String userId) {
    if (!isTenant(clientId) || !new TenantSeatCounter().isSeatOnceActive(userId)) {
      return Optional.empty();
    }
    return refusalForNewSeat(clientId);
  }

  private static boolean isTenant(String clientId) {
    return !StringUtils.isBlank(clientId) && !"0".equals(clientId);
  }
}
