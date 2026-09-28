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

package com.etendoerp.go.usage;

import java.util.List;

/**
 * Counts a usage resource whose rule is not a plain row count over an entity — a stock
 * (a point-in-time snapshot, where the value does not come from counting rows dated that
 * day) or a distinct count. Resources that <em>are</em> a row count need no class at all:
 * they are a catalog row in declarative mode.
 *
 * <p><b>Implementations must be annotated {@code @Named("qualifier")} and nothing else.</b>
 * Never {@code @ApplicationScoped} or any other normal scope: lookup matches on the bean
 * name, and a normal-scoped bean is served through a Weld client proxy whose subclass does
 * not carry the (non-{@code @Inherited}) qualifier, so the counter would be silently
 * skipped. {@code @Named} alone defaults to {@code @Dependent}, which is not proxied.
 * This regressed before in ETP-4244 for {@code NeoHandler}; the same trap applies here.
 *
 * <p>The qualifier is the value of {@code ETGO_BILLING_RESOURCE.Strategy_Qualifier}.
 */
public interface UsageResourceCounter {

  /**
   * Counts the resource over the requested range.
   *
   * @param request the resource, the half-open day range and optionally a single tenant
   * @return one entry per tenant per day that has a value; days omitted count as zero.
   *     Never null; return an empty list when there is nothing to record.
   */
  List<DailyCount> count(UsageCountRequest request);
}
