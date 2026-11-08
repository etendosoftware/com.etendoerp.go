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

import static com.etendoerp.go.usage.quota.QuotaTestFixtures.CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.quota;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.tenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-client cache of the NEO write check: one lookup per client per TTL, the empty entry of a
 * client with no block quota cached too, only active block quotas on declarative resources kept,
 * a failed build never cached, and the map bounded.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaClientCache
 */
class QuotaClientCacheTest {

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private QuotaLookup lookup;
  private QuotaClientCache cache;

  @BeforeEach
  void setUp() {
    lookup = mock(QuotaLookup.class);
    when(lookup.forTenant(anyString()))
        .thenAnswer(call -> TenantQuotas.none(call.getArgument(0)));
    cache = new QuotaClientCache(lookup, now::get);
  }

  @Test
  void anEntryIsBuiltOnceAndReusedWithinTheTtl() {
    QuotaClientCache.Entry first = cache.entryFor(CLIENT);
    now.addAndGet(QuotaClientCache.TTL_MS - 1);

    assertSame(first, cache.entryFor(CLIENT));
    verify(lookup, times(1)).forTenant(CLIENT);
  }

  @Test
  void aClientWithNoBlockQuotaIsCachedAsAnEmptyEntry() {
    assertTrue(cache.entryFor(CLIENT).blockQuotasFor("Invoice").isEmpty());
    assertTrue(cache.entryFor(CLIENT).blockQuotasFor("Invoice").isEmpty());

    verify(lookup, times(1)).forTenant(CLIENT);
  }

  @Test
  void anExpiredEntryIsRebuilt() {
    cache.entryFor(CLIENT);
    now.addAndGet(QuotaClientCache.TTL_MS);
    cache.entryFor(CLIENT);

    verify(lookup, times(2)).forTenant(CLIENT);
  }

  @Test
  void aFailedBuildPropagatesAndIsNotCached() {
    when(lookup.forTenant(CLIENT)).thenThrow(new IllegalStateException("aborted"))
        .thenReturn(TenantQuotas.none(CLIENT));

    assertThrows(IllegalStateException.class, () -> cache.entryFor(CLIENT));
    cache.entryFor(CLIENT);

    verify(lookup, times(2)).forTenant(CLIENT);
  }

  @Test
  void onlyBlockQuotasOnDeclarativeResourcesAreKeptByCountedEntity() {
    QuotaDefinition invoices = quota("DOCS", "block", 10L, null).build();
    QuotaDefinition warned = quota("WARNED", "warn", 10L, null).build();
    QuotaDefinition off = quota("OFF", "off", 10L, null).build();
    QuotaDefinition strategy = quota("SEATS", "block", 10L, null).countingMode("S")
        .countedEntity(null).build();
    QuotaDefinition orders = quota("ORDERS", "block", 10L, null).countedEntity("Order").build();
    when(lookup.forTenant(CLIENT))
        .thenReturn(tenant(CLIENT, invoices, warned, off, strategy, orders));

    QuotaClientCache.Entry entry = cache.entryFor(CLIENT);

    assertEquals(List.of(invoices), entry.blockQuotasFor("Invoice"));
    assertEquals(List.of(orders), entry.blockQuotasFor("Order"));
    assertTrue(entry.blockQuotasFor(null).isEmpty());
    assertEquals(List.of(invoices, orders), entry.getTenant().getQuotas());
    assertEquals("month", entry.getTenant().getPlanInterval());
  }

  @Test
  void expiredEntriesAreSweptAsNewClientsArrive() {
    cache.entryFor("CLIENT-1");
    cache.entryFor("CLIENT-2");
    now.addAndGet(QuotaClientCache.TTL_MS);

    cache.entryFor("CLIENT-3");

    assertEquals(1, cache.size());
  }

  @Test
  void theMapNeverHoldsMoreThanItsCap() {
    QuotaClientCache small = new QuotaClientCache(lookup, now::get, 3);
    for (int client = 0; client < 10; client++) {
      small.entryFor("CLIENT-" + client);
      assertTrue(small.size() <= 3, "size " + small.size());
    }
    small.entryFor("CLIENT-9");
    assertTrue(small.size() >= 1);
  }
}
