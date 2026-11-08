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

import org.apache.commons.lang3.StringUtils;

/**
 * ETP-5051 — where a quota's consumption is read from, {@code ETGO_PLAN_QUOTA.CONSUMPTION_SOURCE}
 * ({@code open-and-notable-topics.md} §5.7).
 */
public enum ConsumptionSource {
  /**
   * The stored daily usage ({@code ETGO_USAGE_DAILY}) of the period, combined with the resource's
   * aggregation. Lags by up to one usage-job cadence. The default: a null column means this.
   */
  AGGREGATE("aggregate"),
  /**
   * Counted at evaluation time from the same catalog descriptor the usage job uses: one
   * per-tenant count over the whole period for a declarative resource, the strategy counter over
   * the period for a named strategy.
   */
  LIVE("live");

  private final String value;

  ConsumptionSource(String value) {
    this.value = value;
  }

  /** @return the stored value, as the check constraint spells it */
  public String value() {
    return value;
  }

  /**
   * Reads the stored column. Null or blank means {@link #AGGREGATE}, and so does the legacy value
   * {@code sum}: ETP-5046 shipped {@code sum} as the column's only list value, so the check
   * constraint still accepts it for rows written before ETP-5051 — a module script cannot rewrite
   * them before the constraint is applied, because module scripts run after the model update —
   * while the AD list offers only {@code aggregate} and {@code live}.
   *
   * @param raw the stored {@code CONSUMPTION_SOURCE}
   * @return the source to read from
   */
  public static ConsumptionSource fromColumn(String raw) {
    return LIVE.value.equalsIgnoreCase(StringUtils.trimToEmpty(raw)) ? LIVE : AGGREGATE;
  }
}
