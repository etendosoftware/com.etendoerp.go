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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.etendoerp.go.usage.UsageResourceValidator;

/**
 * ETP-5051 — the write-relevant quota state of each client, kept in memory for {@link #TTL_MS}.
 *
 * <p><b>What an entry holds.</b> The client's open subscription (period, closed flag, plan
 * interval) and its plan's active {@code block} quotas on declarative resources, grouped by the
 * DAL entity each resource counts. A client with no such quota gets an <b>empty</b> entry, cached
 * like any other: its creates then cost a map lookup and no query.
 *
 * <p><b>Keyed by client id</b>, not by HTTP session: bearer, batch and MCP requests have none.
 *
 * <p><b>Built on the request's own session</b>, through one {@link QuotaLookup#forTenant} call (the
 * open subscription and its plan's active quota rows). A failure is not caught here: the caller —
 * the NEO write gate — fails the create closed.
 *
 * <p><b>Freshness.</b> A quota switched on or off, a plan change or a renewal reaches a client's
 * creates within {@link #TTL_MS}.
 *
 * <p><b>Bounded.</b> Expired entries are swept when an entry is stored, at most once per
 * {@link #TTL_MS}; if the map still holds {@link #MAX_ENTRIES} entries after a sweep, it is cleared,
 * and every client simply rebuilds its entry on its next create. So the map holds the clients that
 * created something in about the last minute, never more than {@link #MAX_ENTRIES}.
 *
 * <p>Thread-safe. Two concurrent creates of the same client may both build its entry; the second
 * simply replaces the first.
 */
public class QuotaClientCache {

  /** How long a client's entry is trusted. */
  public static final long TTL_MS = 60_000L;
  /** The most entries kept at once. */
  static final int MAX_ENTRIES = 10_000;

  private final QuotaLookup lookup;
  private final LongSupplier clock;
  private final int maxEntries;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicLong lastSweep = new AtomicLong();

  /**
   * A cache over the given lookup and clock, holding at most {@link #MAX_ENTRIES} clients.
   *
   * @param lookup builds a client's entry; called at most once per client per {@link #TTL_MS}
   * @param clock the time source, in milliseconds
   */
  public QuotaClientCache(QuotaLookup lookup, LongSupplier clock) {
    this(lookup, clock, MAX_ENTRIES);
  }

  QuotaClientCache(QuotaLookup lookup, LongSupplier clock, int maxEntries) {
    this.lookup = lookup;
    this.clock = clock;
    this.maxEntries = maxEntries;
  }

  /**
   * The client's entry, built when it is missing or older than {@link #TTL_MS}.
   *
   * @param clientId the client
   * @return its entry; never null
   * @throws RuntimeException whatever building the entry threw; nothing is cached then
   */
  public Entry entryFor(String clientId) {
    long now = clock.getAsLong();
    Entry entry = entries.get(clientId);
    if (entry != null && now - entry.builtAt < TTL_MS) {
      return entry;
    }
    Entry built = Entry.of(lookup.forTenant(clientId), now);
    store(clientId, built, now);
    return built;
  }

  /** @return how many clients are cached; for tests */
  int size() {
    return entries.size();
  }

  private void store(String clientId, Entry entry, long now) {
    long swept = lastSweep.get();
    if (now - swept >= TTL_MS && lastSweep.compareAndSet(swept, now)) {
      evictExpired(now);
    }
    if (entries.size() >= maxEntries && !entries.containsKey(clientId)) {
      evictExpired(now);
      if (entries.size() >= maxEntries) {
        entries.clear();
      }
    }
    entries.put(clientId, entry);
  }

  private void evictExpired(long now) {
    entries.values().removeIf(cached -> now - cached.builtAt >= TTL_MS);
  }

  /** One client's write-relevant quota state. Immutable. */
  public static final class Entry {
    private final TenantQuotas tenant;
    private final Map<String, List<QuotaDefinition>> blockQuotasByEntity;
    private final long builtAt;

    private Entry(TenantQuotas tenant, Map<String, List<QuotaDefinition>> blockQuotasByEntity,
        long builtAt) {
      this.tenant = tenant;
      this.blockQuotasByEntity = blockQuotasByEntity;
      this.builtAt = builtAt;
    }

    static Entry of(TenantQuotas looked, long builtAt) {
      Map<String, List<QuotaDefinition>> byEntity = new HashMap<>();
      List<QuotaDefinition> kept = new ArrayList<>();
      for (QuotaDefinition quota : looked.getQuotas()) {
        if (QuotaRules.MODE_BLOCK.equals(quota.getEnforcementMode())
            && UsageResourceValidator.MODE_DECLARATIVE.equals(quota.getCountingMode())
            && quota.getCountedEntity() != null) {
          kept.add(quota);
          byEntity.computeIfAbsent(quota.getCountedEntity(), k -> new ArrayList<>()).add(quota);
        }
      }
      return new Entry(looked.withQuotas(kept), byEntity.isEmpty() ? Collections.emptyMap()
          : Collections.unmodifiableMap(byEntity), builtAt);
    }

    /**
     * The client's subscription period and its block quotas only.
     *
     * @return the tenant view the write check evaluates
     */
    public TenantQuotas getTenant() {
      return tenant;
    }

    /**
     * The client's block quotas whose resource counts this entity.
     *
     * @param entityName the DAL entity name of a created row
     * @return the quotas; empty when no block quota of this client counts the entity
     */
    public List<QuotaDefinition> blockQuotasFor(String entityName) {
      if (entityName == null) {
        return Collections.emptyList();
      }
      return blockQuotasByEntity.getOrDefault(entityName, Collections.emptyList());
    }
  }
}
