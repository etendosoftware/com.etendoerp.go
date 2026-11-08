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
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.NOW;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.OTHER_CLIENT;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_END;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.PERIOD_START;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.midnight;
import static com.etendoerp.go.usage.quota.QuotaTestFixtures.quota;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.usage.DailyCount;
import com.etendoerp.go.usage.UsageCountRequest;
import com.etendoerp.go.usage.UsageCounterLookup;
import com.etendoerp.go.usage.UsageFreshness;
import com.etendoerp.go.usage.UsageQueryComposer;
import com.etendoerp.go.usage.UsageResourceCounter;

/**
 * Reading a quota's consumption over the subscription period, from either source: the stored
 * daily aggregate (combined by the resource's aggregation, and lag-checked) or a live count from
 * the same catalog descriptor the usage job uses. Both are bound to the period's day window,
 * {@code [start day, end day)}.
 *
 * @covers com.etendoerp.go.usage.quota.DalConsumptionReader
 */
@SuppressWarnings("unchecked")
class DalConsumptionReaderTest {

  private final QuotaPeriod period = QuotaPeriod.current(PERIOD_START, PERIOD_END, false, NOW);
  private final UsageFreshness freshness = mock(UsageFreshness.class);
  private final DalConsumptionReader reader = new DalConsumptionReader(freshness);
  private final OBDal dal = mock(OBDal.class);
  private final Session session = mock(Session.class);
  private MockedStatic<OBDal> obDal;

  @BeforeEach
  void setUp() {
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    when(dal.getSession()).thenReturn(session);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
  }

  private Query<Object[]> storedDays(Object[]... rows) {
    Query<Object[]> query = mock(Query.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
    when(query.list()).thenReturn(List.of(rows));
    return query;
  }

  @Test
  void theAggregateCombinesTheStoredDaysOfThePeriodWindow() {
    Query<Object[]> query = storedDays(
        new Object[] { midnight(2026, 10, 17), new BigDecimal("4") },
        new Object[] { midnight(2026, 10, 18), 9L },
        new Object[] { midnight(2026, 10, 19), null });
    QuotaDefinition definition = quota("SEATS", "block", 10L, null)
        .aggregation(UsageAggregation.MAX).build();

    assertEquals(9L, reader.read(definition, CLIENT, period));

    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    verify(session).createQuery(hql.capture(), eq(Object[].class));
    assertTrue(hql.getValue().contains("u.usageDay >= :dayFrom"), hql.getValue());
    assertTrue(hql.getValue().contains("u.usageDay < :dayTo"), hql.getValue());
    verify(query).setParameter("resourceId", "RES-SEATS");
    verify(query).setParameter("clientId", CLIENT);
    verify(query).setParameter("dayFrom", midnight(2026, 10, 17));
    verify(query).setParameter("dayTo", midnight(2026, 11, 17));
    verify(freshness).checkLag();
  }

  /**
   * ETP-5051 redesign: the write-path reader (a create) reads the stored aggregate without the
   * usage-lag check; that WARN belongs to the reports and the usage job.
   */
  @Test
  void theWritePathReaderReadsTheAggregateWithoutTheLagCheck() {
    storedDays(new Object[] { midnight(2026, 10, 17), 4L });

    try (MockedStatic<UsageFreshness> shared = mockStatic(UsageFreshness.class)) {
      assertEquals(4L, DalConsumptionReader.withoutLagCheck()
          .read(quota("DOCS", "block", 10L, null).build(), CLIENT, period));
      shared.verifyNoInteractions();
    }
    verify(freshness, never()).checkLag();
  }

  /**
   * ETP-5051 re-review W2: on the write path the live count leaves the created rows out, so it is
   * the consumption before the create whatever the rows' dates.
   */
  @Test
  @SuppressWarnings("unchecked")
  void theCountBeforeACreateLeavesTheCreatedRowsOut() {
    Query<Object> query = mock(Query.class);
    when(session.createQuery(anyString())).thenReturn((Query) query);
    when(query.uniqueResult()).thenReturn(7L);
    QuotaDefinition definition = quota("DOCS", "block", 10L, null)
        .hqlRestriction("e.salesTransaction = true").source(ConsumptionSource.LIVE).build();
    List<String> created = List.of("ROW-1", "ROW-2");

    assertEquals(7L, reader.readBeforeCreate(definition, CLIENT, period, created));

    verify(session).createQuery(UsageQueryComposer.composePerTenantCountExcluding("Invoice",
        "invoiceDate", "e.salesTransaction = true"));
    verify(query).setParameterList(UsageQueryComposer.PARAM_EXCLUDED_IDS, created);
    verify(query).setParameter(UsageQueryComposer.PARAM_DAY_START, midnight(2026, 10, 17));
  }

  @Test
  void theAggregateBeforeACreateIsTheAggregate() {
    storedDays(new Object[] { midnight(2026, 10, 17), 4L });

    assertEquals(4L, reader.readBeforeCreate(quota("DOCS", "block", 10L, null).build(), CLIENT,
        period, List.of("ROW-1")));
  }

  @Test
  void theAggregateSumsByDefault() {
    storedDays(new Object[] { midnight(2026, 10, 17), 4L },
        new Object[] { midnight(2026, 10, 18), 6L });

    assertEquals(10L, reader.read(quota("DOCS", "block", 10L, null).build(), CLIENT, period));
  }

  @Test
  void theLiveSourceCountsTheWholePeriodWithTheCatalogDescriptor() {
    Query<Object> query = mock(Query.class);
    when(session.createQuery(anyString())).thenReturn((Query) query);
    when(query.uniqueResult()).thenReturn(12L);
    QuotaDefinition definition = quota("DOCS", "block", 10L, null)
        .hqlRestriction("e.salesTransaction = true").source(ConsumptionSource.LIVE).build();

    assertEquals(12L, reader.read(definition, CLIENT, period));

    verify(session).createQuery(UsageQueryComposer.composePerTenantCount("Invoice", "invoiceDate",
        "e.salesTransaction = true"));
    verify(query).setParameter(UsageQueryComposer.PARAM_CLIENT_ID, CLIENT);
    verify(query).setParameter(UsageQueryComposer.PARAM_DAY_START, midnight(2026, 10, 17));
    verify(query).setParameter(UsageQueryComposer.PARAM_DAY_END, midnight(2026, 11, 17));
    verify(freshness, never()).checkLag();
  }

  @Test
  void anUnknownCountingModeFailsLoudlyRatherThanReadingZero() {
    QuotaDefinition definition = quota("DOCS", "block", 10L, null).countingMode("X")
        .source(ConsumptionSource.LIVE).build();

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> reader.read(definition, CLIENT, period));
    assertTrue(thrown.getMessage().contains("unknown counting mode 'X'"), thrown.getMessage());
  }

  @Test
  void aLiveStrategyCountKeepsOnlyTheTenantsDays() {
    UsageResourceCounter counter = mock(UsageResourceCounter.class);
    BillingResource resource = mock(BillingResource.class);
    when(dal.get(BillingResource.class, "RES-SEATS")).thenReturn(resource);
    when(counter.count(any(UsageCountRequest.class))).thenReturn(List.of(
        new DailyCount(CLIENT, midnight(2026, 10, 17), 3L),
        new DailyCount(OTHER_CLIENT, midnight(2026, 10, 17), 50L),
        new DailyCount(CLIENT, midnight(2026, 10, 18), 4L)));
    QuotaDefinition definition = quota("SEATS", "block", 10L, null).countingMode("S")
        .strategyQualifier("seats").source(ConsumptionSource.LIVE).build();

    long consumed;
    try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      lookup.when(() -> UsageCounterLookup.byQualifier("seats")).thenReturn(counter);
      consumed = reader.read(definition, CLIENT, period);
      obContext.verify(() -> OBContext.setAdminMode(false));
      obContext.verify(OBContext::restorePreviousMode);
    }

    assertEquals(7L, consumed);
    ArgumentCaptor<UsageCountRequest> request = ArgumentCaptor.forClass(UsageCountRequest.class);
    verify(counter).count(request.capture());
    assertEquals(midnight(2026, 10, 17), request.getValue().getFrom());
    assertEquals(midnight(2026, 11, 17), request.getValue().getTo());
    assertEquals(CLIENT, request.getValue().getClientId());
  }

  @Test
  void aMissingStrategyCounterFailsLoudly() {
    QuotaDefinition definition = quota("SEATS", "block", 10L, null).countingMode("S")
        .strategyQualifier("gone").source(ConsumptionSource.LIVE).build();

    try (MockedStatic<UsageCounterLookup> lookup = mockStatic(UsageCounterLookup.class);
        MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      lookup.when(() -> UsageCounterLookup.byQualifier("gone")).thenReturn(null);
      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          () -> reader.read(definition, CLIENT, period));
      assertTrue(thrown.getMessage().contains("'gone'"), thrown.getMessage());
      obContext.verify(OBContext::restorePreviousMode);
    }
  }
}
