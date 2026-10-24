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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ETP-5051 — the quota check of the NEO write path, structural and driven by catalog data only:
 * no entity or spec name appears here or in its callers.
 *
 * <p>For a created row of entity {@code E}:
 * <ol>
 *   <li>the {@link QuotaEntityIndex} names the declarative resources whose {@code COUNTED_ENTITY}
 *       is {@code E} under some active {@code block} quota — none, and the check ends with no
 *       query at all;</li>
 *   <li>each such resource's HQL restriction is tested against the new row
 *       ({@link CreatedRowMatcher}), which also names the row's tenant;</li>
 *   <li>the tenant's quotas are looked up and the {@link QuotaEvaluator} decides.</li>
 * </ol>
 *
 * <p><b>What it cannot see:</b> a resource that grows through a state change rather than a create
 * — {@code POSTED_SALES_INVOICES} grows when an invoice is posted, and the row is created
 * unposted — does not match its restriction at create time, so it is never refused here. Accepted
 * (Martin Taal, 2026-10-24); such a quota is reported at login and on the account page.
 */
public class QuotaWriteGuard {

  private final QuotaEntityIndex index;
  private final CreatedRowMatcher matcher;
  private final QuotaLookup lookup;
  private final QuotaEvaluator evaluator;

  /**
   * A guard over explicit seams.
   *
   * @param index which entities can be refused at all
   * @param matcher tests a created row against a resource's restriction
   * @param lookup finds the tenant's quotas
   * @param evaluator decides
   */
  public QuotaWriteGuard(QuotaEntityIndex index, CreatedRowMatcher matcher, QuotaLookup lookup,
      QuotaEvaluator evaluator) {
    this.index = index;
    this.matcher = matcher;
    this.lookup = lookup;
    this.evaluator = evaluator;
  }

  /**
   * The shared guard; its index is per JVM.
   *
   * @return the shared instance
   */
  public static QuotaWriteGuard getDefault() {
    return DefaultHolder.INSTANCE;
  }

  /** Built on first use, so loading this class wires no service. */
  private static final class DefaultHolder {
    private static final QuotaWriteGuard INSTANCE = new QuotaWriteGuard(new QuotaEntityIndex(),
        CreatedRowMatcher.dal(), new DalQuotaLookup(), new QuotaEvaluator());
  }

  /**
   * Whether creates of this entity must be checked. Cheap: a map lookup, plus one catalog query a
   * minute at most. Callers ask before the insert, to decide how to run it.
   *
   * @param entityName the DAL entity name
   * @return {@code true} when some active {@code block} quota counts this entity
   */
  public boolean appliesTo(String entityName) {
    return !index.resourcesFor(entityName).isEmpty();
  }

  /**
   * Checks created rows, after the insert and before the commit.
   *
   * @param entityName the DAL entity name of the rows
   * @param recordIds the ids of the created rows
   * @return the first DENY evaluation, or empty when every row may stand
   */
  public Optional<QuotaEvaluation> refusalFor(String entityName, Collection<String> recordIds) {
    List<QuotaEntityIndex.IndexedResource> resources = index.resourcesFor(entityName);
    if (resources.isEmpty() || recordIds == null || recordIds.isEmpty()) {
      return Optional.empty();
    }
    Map<String, TenantQuotas> tenants = new HashMap<>();
    for (String recordId : recordIds) {
      for (QuotaEntityIndex.IndexedResource resource : resources) {
        String clientId = matcher.tenantIfCounted(resource, recordId);
        if (clientId == null) {
          continue;
        }
        TenantQuotas tenant = tenants.computeIfAbsent(clientId, lookup::forTenant);
        Optional<QuotaEvaluation> refusal =
            evaluator.refusalForCreate(tenant, resource.getResourceId());
        if (refusal.isPresent()) {
          return refusal;
        }
      }
    }
    return Optional.empty();
  }
}
