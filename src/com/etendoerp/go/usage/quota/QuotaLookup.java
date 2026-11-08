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

/**
 * ETP-5051 — finds the tenant's subscription and its plan's active quota rows. The seam the
 * evaluator's tests replace with a fixture.
 */
@FunctionalInterface
public interface QuotaLookup {

  /**
   * The quotas that are enforced on a tenant now: those of the plan of its <b>open</b>
   * subscription row. A tenant with no open row has none (unlimited).
   *
   * @param clientId the tenant
   * @return never null; {@link TenantQuotas#none(String)} when there is nothing to enforce
   */
  TenantQuotas forTenant(String clientId);
}
