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

import java.util.Collection;

/**
 * ETP-5051 — reads one tenant's consumption of one quota's resource over a period. The seam the
 * evaluator's tests replace: every consumption query in the quota code goes through an
 * implementation of this, so "no query ran" is "this was never called".
 */
@FunctionalInterface
public interface ConsumptionReader {

  /**
   * Reads the consumed quantity of one quota's resource for one tenant over a period.
   *
   * @param quota the quota, carrying the resource descriptor, its aggregation and the source
   * @param clientId the tenant
   * @param period the current period; its day-aligned window is what is counted
   * @return the consumed quantity
   */
  long read(QuotaDefinition quota, String clientId, QuotaPeriod period);

  /**
   * The consumption <b>before</b> a create, for the NEO write check: the rows the create just
   * inserted (flushed, in the same transaction) are left out of a count that would see them. The
   * default is {@link #read}, right for a source that cannot see them — the stored aggregate.
   *
   * @param quota the quota
   * @param clientId the tenant
   * @param period the current period
   * @param createdIds the ids of the rows the create inserted; never empty
   * @return the consumed quantity without the created rows
   */
  default long readBeforeCreate(QuotaDefinition quota, String clientId, QuotaPeriod period,
      Collection<String> createdIds) {
    return read(quota, clientId, period);
  }
}
