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
