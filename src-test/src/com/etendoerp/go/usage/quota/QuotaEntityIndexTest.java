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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.usage.quota.QuotaEntityIndex.IndexedResource;

/**
 * The per-JVM index of the entities a {@code block} quota counts: what a NEO create consults
 * before deciding whether to check a row at all. It holds block quotas only, and it is trusted for
 * {@link QuotaEntityIndex#TTL_MS}: a quota switched on or off takes effect once that has elapsed.
 *
 * @covers com.etendoerp.go.usage.quota.QuotaEntityIndex
 */
class QuotaEntityIndexTest {

  private static final IndexedResource INVOICES =
      new IndexedResource("RES-INV", "Invoice", "e.salesTransaction = true");
  private static final IndexedResource ORDERS = new IndexedResource("RES-ORD", "Order", null);
  private static final IndexedResource CREDIT_NOTES =
      new IndexedResource("RES-CN", "Invoice", "e.documentType.return = true");

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final AtomicInteger loads = new AtomicInteger();
  private final AtomicReference<List<IndexedResource>> catalog =
      new AtomicReference<>(Collections.emptyList());
  private QuotaEntityIndex index;

  @BeforeEach
  void setUp() {
    index = new QuotaEntityIndex(() -> {
      loads.incrementAndGet();
      return catalog.get();
    }, now::get);
  }

  @Test
  void resourcesAreGroupedByTheEntityTheyCount() {
    catalog.set(List.of(INVOICES, ORDERS, CREDIT_NOTES));

    assertEquals(List.of(INVOICES, CREDIT_NOTES), index.resourcesFor("Invoice"));
    assertEquals(List.of(ORDERS), index.resourcesFor("Order"));
    assertTrue(index.resourcesFor("BusinessPartner").isEmpty());
  }

  @Test
  void theCatalogIsReadOnceWithinTheTtl() {
    catalog.set(List.of(INVOICES));

    index.resourcesFor("Invoice");
    now.addAndGet(QuotaEntityIndex.TTL_MS - 1);
    index.resourcesFor("Order");
    index.resourcesFor("Invoice");

    assertEquals(1, loads.get());
  }

  @Test
  void theCatalogIsReloadedOnceTheTtlHasElapsed() {
    index.resourcesFor("Invoice");
    now.addAndGet(QuotaEntityIndex.TTL_MS);
    index.resourcesFor("Invoice");

    assertEquals(2, loads.get());
  }

  @Test
  void aQuotaSwitchedToBlockTakesEffectAfterTheTtl() {
    assertTrue(index.resourcesFor("Invoice").isEmpty());

    catalog.set(List.of(INVOICES));
    now.addAndGet(QuotaEntityIndex.TTL_MS - 1);
    assertTrue(index.resourcesFor("Invoice").isEmpty(), "still the cached index");

    now.addAndGet(1);
    assertEquals(List.of(INVOICES), index.resourcesFor("Invoice"));
  }

  @Test
  void aQuotaSwitchedOffStopsBeingCheckedAfterTheTtl() {
    catalog.set(List.of(INVOICES));
    assertEquals(List.of(INVOICES), index.resourcesFor("Invoice"));

    catalog.set(Collections.emptyList());
    now.addAndGet(QuotaEntityIndex.TTL_MS - 1);
    assertEquals(List.of(INVOICES), index.resourcesFor("Invoice"), "still the cached index");

    now.addAndGet(1);
    assertTrue(index.resourcesFor("Invoice").isEmpty());
  }

  @Test
  void aFailedReloadKeepsThePreviousIndexUntilTheNextTtl() {
    catalog.set(List.of(INVOICES));
    index.resourcesFor("Invoice");
    List<IndexedResource> failing = new ArrayList<>() {
      @Override
      public java.util.Iterator<IndexedResource> iterator() {
        throw new IllegalStateException("catalog unavailable");
      }
    };
    catalog.set(failing);
    now.addAndGet(QuotaEntityIndex.TTL_MS);

    TestLogCapture logs = TestLogCapture.attachTo(QuotaEntityIndex.class, Level.ERROR);
    try {
      assertEquals(List.of(INVOICES), index.resourcesFor("Invoice"));
      assertEquals(List.of(INVOICES), index.resourcesFor("Invoice"));
      assertEquals(2, loads.get(), "a failure is not retried on every call");
      assertEquals(1, logs.messagesAt(Level.ERROR).size());
      assertTrue(logs.messagesAt(Level.ERROR).get(0).startsWith("[QUOTA]"));
    } finally {
      logs.detach();
    }
  }

  /**
   * REPRO (ETP-5051 re-review W1): a first build that fails (the write-path pool timed out or was
   * full) leaves no create quota-checked. It fails open, but it is retried after
   * {@link QuotaEntityIndex#RETRY_AFTER_FAILED_FIRST_BUILD_MS}, not after the full TTL — and it
   * stays on that short retry for as long as no build has succeeded.
   */
  @Test
  void aFailedFirstBuildFailsOpenAndIsRetriedSoonUntilOneSucceeds() {
    AtomicReference<RuntimeException> failure =
        new AtomicReference<>(new IllegalStateException("Isolated read timed out after 5000 ms"));
    QuotaEntityIndex coldIndex = new QuotaEntityIndex(() -> {
      loads.incrementAndGet();
      if (failure.get() != null) {
        throw failure.get();
      }
      return List.of(INVOICES);
    }, now::get);
    TestLogCapture logs = TestLogCapture.attachTo(QuotaEntityIndex.class, Level.ERROR);
    try {
      assertTrue(coldIndex.resourcesFor("Invoice").isEmpty(), "fails open");
      now.addAndGet(QuotaEntityIndex.RETRY_AFTER_FAILED_FIRST_BUILD_MS - 1);
      assertTrue(coldIndex.resourcesFor("Invoice").isEmpty());
      assertEquals(1, loads.get(), "not retried on every create");

      now.addAndGet(1);
      assertTrue(coldIndex.resourcesFor("Invoice").isEmpty());
      assertEquals(2, loads.get(), "a second failure keeps the short retry");

      failure.set(null);
      now.addAndGet(QuotaEntityIndex.RETRY_AFTER_FAILED_FIRST_BUILD_MS);
      assertEquals(List.of(INVOICES), coldIndex.resourcesFor("Invoice"));
      assertEquals(3, loads.get());
      assertTrue(QuotaEntityIndex.RETRY_AFTER_FAILED_FIRST_BUILD_MS < QuotaEntityIndex.TTL_MS);
      assertEquals(2, logs.messagesAt(Level.ERROR).size());
      assertTrue(logs.messagesAt(Level.ERROR).get(0).contains("fails open"),
          logs.messagesAt(Level.ERROR).get(0));
    } finally {
      logs.detach();
    }
  }

  /**
   * REPRO (ETP-5051 re-review W1): while one create rebuilds an expired index, another create is
   * served the previous index at once instead of queueing behind the rebuild's read.
   */
  @Test
  void aCreateDuringARebuildIsServedThePreviousIndexWithoutWaiting() throws Exception {
    CountDownLatch rebuildStarted = new CountDownLatch(1);
    CountDownLatch releaseRebuild = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    QuotaEntityIndex slowIndex = new QuotaEntityIndex(() -> {
      if (calls.incrementAndGet() == 1) {
        return List.of(INVOICES);
      }
      rebuildStarted.countDown();
      awaitQuietly(releaseRebuild);
      return List.of(ORDERS);
    }, now::get);
    slowIndex.resourcesFor("Invoice");
    now.addAndGet(QuotaEntityIndex.TTL_MS);
    ExecutorService rebuilder = Executors.newSingleThreadExecutor();
    try {
      Future<List<IndexedResource>> rebuilding = rebuilder.submit(
          () -> slowIndex.resourcesFor("Order"));
      assertTrue(rebuildStarted.await(10, TimeUnit.SECONDS));

      assertEquals(List.of(INVOICES), slowIndex.resourcesFor("Invoice"),
          "served the previous index while the rebuild is still running");

      releaseRebuild.countDown();
      assertEquals(List.of(ORDERS), rebuilding.get(10, TimeUnit.SECONDS));
      assertTrue(slowIndex.resourcesFor("Invoice").isEmpty(), "the rebuilt index from now on");
      assertEquals(2, calls.get());
    } finally {
      releaseRebuild.countDown();
      rebuilder.shutdownNow();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void aNullEntityReadsNothing() {
    assertTrue(index.resourcesFor(null).isEmpty());
    assertEquals(0, loads.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  void theDatabaseLoaderReadsActiveBlockQuotasOnDeclarativeResourcesOnly() {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    Query<Object[]> query = mock(Query.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
    when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    when(query.list()).thenReturn(List.<Object[]>of(
        new Object[] { "RES-INV", "Invoice", "e.salesTransaction = true" },
        new Object[] { "RES-ORD", "Order", null }));

    List<IndexedResource> resources;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      resources = QuotaEntityIndex.loadFromDatabase();
    }

    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(session).createQuery(hql.capture(), eq(Object[].class));
    assertTrue(hql.getValue().contains("q.active = true"), hql.getValue());
    assertTrue(hql.getValue().contains("q.enforcementMode = :mode"), hql.getValue());
    assertTrue(hql.getValue().contains("r.countingMode = :countingMode"), hql.getValue());
    verify(query).setParameter("mode", "block");
    verify(query).setParameter("countingMode", "D");
    assertEquals(2, resources.size());
    assertEquals("RES-INV", resources.get(0).getResourceId());
    assertEquals("Invoice", resources.get(0).getCountedEntity());
    assertEquals("e.salesTransaction = true", resources.get(0).getHqlRestriction());
    assertEquals("Order", resources.get(1).getCountedEntity());
  }
}
