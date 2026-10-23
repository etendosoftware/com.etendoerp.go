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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usage.UsageResourceValidator;

/**
 * ETP-5051 — which created entities can be refused by a quota at all: the {@code COUNTED_ENTITY} of
 * every declarative billing resource that some <b>active</b> quota row puts in mode
 * {@code block}. Kept in memory and rebuilt at most every {@link #TTL_MS} ms, so enabling or
 * disabling a quota takes effect within a minute and without a restart.
 *
 * <p>This is what makes "no quota rows, zero queries" hold on the write path: a create of an entity
 * outside the index costs a map lookup and nothing else. Only the rebuild itself queries, once a
 * minute at most, and only while creates arrive.
 *
 * <p>Only {@code block} rows are indexed: on the write path a {@code warn} or {@code off} quota
 * can never change the outcome (write warnings are not returned, contract §3), so indexing them
 * would only buy queries whose answer nothing reads. A named-strategy resource has no counted
 * entity and is never write-checked.
 *
 * <p>A failed rebuild keeps the previous index (empty on the first build) and is retried on the next
 * create after the TTL: availability over enforcement, as everywhere in this check.
 */
public class QuotaEntityIndex {

  private static final Logger log = LogManager.getLogger(QuotaEntityIndex.class);

  /** How long a built index is trusted. */
  public static final long TTL_MS = 60_000L;

  private static final String INDEX_HQL = "select distinct r.id, r.countedEntity, r.hQLRestriction"
      + " from ETGO_PLAN_QUOTA q join q.billingResource r"
      + " where q.active = true and q.enforcementMode = :mode"
      + " and r.countingMode = :countingMode and r.countedEntity is not null";

  private final Supplier<List<IndexedResource>> loader;
  private final LongSupplier clock;
  private volatile Map<String, List<IndexedResource>> byEntity = Collections.emptyMap();
  private volatile long builtAt;
  private volatile boolean built;

  /** Production wiring: reads the catalog, on the wall clock. */
  public QuotaEntityIndex() {
    this(QuotaEntityIndex::loadFromDatabase, System::currentTimeMillis);
  }

  /**
   * @param loader reads the indexed resources; called at most once per {@link #TTL_MS}
   * @param clock the time source, in milliseconds
   */
  public QuotaEntityIndex(Supplier<List<IndexedResource>> loader, LongSupplier clock) {
    this.loader = loader;
    this.clock = clock;
  }

  /**
   * @param entityName the DAL entity name of a created row
   * @return the resources that count that entity under a {@code block} quota; empty for every
   *     other entity
   */
  public List<IndexedResource> resourcesFor(String entityName) {
    if (entityName == null) {
      return Collections.emptyList();
    }
    refreshIfStale();
    return byEntity.getOrDefault(entityName, Collections.emptyList());
  }

  private void refreshIfStale() {
    long now = clock.getAsLong();
    if (built && now - builtAt < TTL_MS) {
      return;
    }
    synchronized (this) {
      if (built && clock.getAsLong() - builtAt < TTL_MS) {
        return;
      }
      try {
        byEntity = group(loader.get());
      } catch (RuntimeException e) {
        log.error("[QUOTA] Could not rebuild the quota entity index; keeping the previous one "
            + "for {} ms", TTL_MS, e);
      }
      builtAt = clock.getAsLong();
      built = true;
    }
  }

  private static Map<String, List<IndexedResource>> group(List<IndexedResource> resources) {
    Map<String, List<IndexedResource>> grouped = new HashMap<>();
    if (resources != null) {
      for (IndexedResource resource : resources) {
        grouped.computeIfAbsent(resource.getCountedEntity(), k -> new ArrayList<>())
            .add(resource);
      }
    }
    return Collections.unmodifiableMap(grouped);
  }

  /**
   * Reads the indexed resources as stored now. Scalars only, on the raw Hibernate session: no
   * readable-client filter narrows the System-owned catalog away and no entity is loaded, so no
   * admin mode is needed (and none can be left behind by a failing call).
   *
   * @return the indexed resources
   */
  static List<IndexedResource> loadFromDatabase() {
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery(INDEX_HQL, Object[].class)
        .setParameter("mode", QuotaRules.MODE_BLOCK)
        .setParameter("countingMode", UsageResourceValidator.MODE_DECLARATIVE)
        .list();
    List<IndexedResource> resources = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      resources.add(new IndexedResource((String) row[0], (String) row[1], (String) row[2]));
    }
    return resources;
  }

  /** One declarative resource a created row may be counted by. */
  public static final class IndexedResource {
    private final String resourceId;
    private final String countedEntity;
    private final String hqlRestriction;

    /**
     * @param resourceId {@code ETGO_BILLING_RESOURCE_ID}
     * @param countedEntity the DAL entity it counts
     * @param hqlRestriction its restriction fragment, or null
     */
    public IndexedResource(String resourceId, String countedEntity, String hqlRestriction) {
      this.resourceId = resourceId;
      this.countedEntity = countedEntity;
      this.hqlRestriction = hqlRestriction;
    }

    public String getResourceId() {
      return resourceId;
    }

    public String getCountedEntity() {
      return countedEntity;
    }

    public String getHqlRestriction() {
      return hqlRestriction;
    }
  }
}
