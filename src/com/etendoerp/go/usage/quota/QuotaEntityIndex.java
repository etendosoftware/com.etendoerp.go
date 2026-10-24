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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usage.IsolatedDalRead;
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
 * <p>A failed rebuild keeps the previous index and is retried on the next create after the TTL.
 * While one create rebuilds, the others are served the previous index instead of waiting. A first
 * build that fails leaves the index empty — no create is quota-checked, it fails open — and is
 * retried after {@link #RETRY_AFTER_FAILED_FIRST_BUILD_MS} ms. The rebuild runs in its own session
 * on the write path's own pool ({@link IsolatedDalRead#callOnWritePath}), so it can never break the
 * create that triggered it, and a busy report pool never makes it wait.
 */
public class QuotaEntityIndex {

  private static final Logger log = LogManager.getLogger(QuotaEntityIndex.class);

  /**
   * How soon a build is retried while none has succeeded yet. Until one succeeds no create is quota-checked
   * (fails open, open-and-notable-topics.md §4.19); shorter than {@link #TTL_MS} so that window
   * stays short, long enough that a database in trouble is not asked again on every create.
   */
  public static final long RETRY_AFTER_FAILED_FIRST_BUILD_MS = 10_000L;

  /** How long a built index is trusted. */
  public static final long TTL_MS = 60_000L;

  private static final String INDEX_HQL = "select distinct r.id, r.countedEntity, r.hQLRestriction"
      + " from ETGO_PLAN_QUOTA q join q.billingResource r"
      + " where q.active = true and q.enforcementMode = :mode"
      + " and r.countingMode = :countingMode and r.countedEntity is not null";

  private final Supplier<List<IndexedResource>> loader;
  private final LongSupplier clock;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
  private final ReentrantLock rebuildLock = new ReentrantLock();

  /**
   * Production wiring: reads the catalog in its own session on the write path's pool
   * ({@link IsolatedDalRead#callOnWritePath}), so a failing rebuild can never break the create that
   * triggered it, on the wall clock.
   */
  public QuotaEntityIndex() {
    this(() -> IsolatedDalRead.callOnWritePath(QuotaEntityIndex::loadFromDatabase),
        System::currentTimeMillis);
  }

  /**
   * Creates an index over the given loader and time source.
   *
   * @param loader reads the indexed resources; called at most once per {@link #TTL_MS}
   * @param clock the time source, in milliseconds
   */
  public QuotaEntityIndex(Supplier<List<IndexedResource>> loader, LongSupplier clock) {
    this.loader = loader;
    this.clock = clock;
  }

  /**
   * The resources that may refuse a create of this entity: those that count it under an active
   * {@code block} quota. Rebuilds the index first when it is older than {@link #TTL_MS}.
   *
   * @param entityName the DAL entity name of a created row
   * @return the resources that count that entity under a {@code block} quota; empty for every
   *     other entity
   */
  public List<IndexedResource> resourcesFor(String entityName) {
    if (entityName == null) {
      return Collections.emptyList();
    }
    return current().byEntity.getOrDefault(entityName, Collections.emptyList());
  }

  private Snapshot current() {
    Snapshot built = snapshot.get();
    if (isFresh(built)) {
      return built;
    }
    if (built == null) {
      // Cold start: nothing to serve meanwhile, so wait for the one rebuild.
      rebuildLock.lock();
    } else if (!rebuildLock.tryLock()) {
      // Another create is rebuilding: serve the previous index instead of queueing behind it.
      return built;
    }
    try {
      built = snapshot.get();
      if (isFresh(built)) {
        return built;
      }
      Snapshot rebuilt = rebuild(built);
      snapshot.set(rebuilt);
      return rebuilt;
    } finally {
      rebuildLock.unlock();
    }
  }

  private boolean isFresh(Snapshot built) {
    return built != null && clock.getAsLong() - built.builtAt < built.trustedForMs;
  }

  private Snapshot rebuild(Snapshot previous) {
    try {
      return new Snapshot(group(loader.get()), clock.getAsLong(), TTL_MS, true);
    } catch (RuntimeException e) {
      if (previous != null && previous.loaded) {
        log.error("[QUOTA] Could not rebuild the quota entity index; keeping the previous one "
            + "for {} ms", TTL_MS, e);
        return new Snapshot(previous.byEntity, clock.getAsLong(), TTL_MS, true);
      }
      log.error("[QUOTA] Could not build the quota entity index yet; no create is "
          + "quota-checked until it builds (fails open), retrying in {} ms",
          RETRY_AFTER_FAILED_FIRST_BUILD_MS, e);
      return new Snapshot(Collections.emptyMap(), clock.getAsLong(),
          RETRY_AFTER_FAILED_FIRST_BUILD_MS, false);
    }
  }

  /**
   * One index, when it was built, for how long it is trusted, and whether it was ever loaded (false
   * while no build has succeeded yet). Immutable.
   */
  private static final class Snapshot {
    private final Map<String, List<IndexedResource>> byEntity;
    private final long builtAt;
    private final long trustedForMs;
    private final boolean loaded;

    private Snapshot(Map<String, List<IndexedResource>> byEntity, long builtAt,
        long trustedForMs, boolean loaded) {
      this.byEntity = byEntity;
      this.builtAt = builtAt;
      this.trustedForMs = trustedForMs;
      this.loaded = loaded;
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
     * Creates one indexed resource.
     *
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
