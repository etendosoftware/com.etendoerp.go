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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.inject.Named;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.BillingResource;
import com.etendoerp.go.usageevents.PublicApiCall;

/**
 * The public API requests of a tenant per day: the native sum of {@code properties.count} is mocked,
 * so these cases pin the filters in the SQL (backend rows only, no support actor, no token-exchange
 * refusals, a null target still counted), the parameter binding in both scopes and the row mapping.
 *
 * <p>New class: no existing counter test covers this strategy; {@code AiSpendCounterTest} is the
 * model it follows, but tests another counter.</p>
 *
 * @covers com.etendoerp.go.usage.PublicApiRequestCounter
 */
class PublicApiRequestCounterTest {

  private static final Date FROM = new Date(1_790_000_000_000L);
  private static final Date TO = new Date(1_790_864_000_000L);
  private static final Timestamp DAY = new Timestamp(UsageDayRange.startOfDay(FROM).getTime());

  private MockedStatic<OBDal> obDal;
  private Session session;
  private NativeQuery<Object[]> query;
  private final PublicApiRequestCounter counter = new PublicApiRequestCounter();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    OBDal dal = mock(OBDal.class);
    session = mock(Session.class);
    query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    obDal.close();
  }

  private String runAllTenantsAndCaptureSql() {
    when(query.list()).thenReturn(List.of());
    counter.count(new UsageCountRequest(mock(BillingResource.class), FROM, TO, null));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    return sql.getValue();
  }

  @Test
  void theQualifierIsTheOneTheBillingResourceRowNames() {
    assertEquals("publicApiRequests", PublicApiRequestCounter.QUALIFIER);
    assertEquals("publicApiRequests",
        PublicApiRequestCounter.class.getAnnotation(Named.class).value());
  }

  @Test
  void theSqlSumsTheCountOfBackendRowsOfTheTypeAndNothingElse() {
    String sql = runAllTenantsAndCaptureSql();

    assertTrue(sql.contains("sum(cast(cast(e.properties as jsonb) ->> 'count' as bigint))"), sql);
    assertTrue(sql.contains("e.event_type = :eventType"), sql);
    assertTrue(sql.contains("e.source = :source"), sql);
    assertTrue(sql.contains("e.occurred_at >= :dayFrom and e.occurred_at < :dayTo"), sql);
  }

  @Test
  void theSupportActorIsExcluded() {
    assertTrue(runAllTenantsAndCaptureSql()
        .contains("coalesce(cast(e.properties as jsonb) ->> 'actor', '') <> 'support'"));
  }

  @Test
  void theTokenExchangeIsExcludedButANullTargetStillCounts() {
    String sql = runAllTenantsAndCaptureSql();

    // coalesce(kind, '') <> :unbilledKind: a row without a kind compares as '' and is kept, where a
    // bare "kind <> :unbilledKind" would drop it.
    assertTrue(sql.contains("coalesce(cast(e.properties as jsonb) ->> 'kind', '') <> :unbilledKind"),
        sql);
  }

  @Test
  void onlyTheTokenExchangeKindIsExcludedNotARequestWhoseTargetIsLiterallyTheTokenPath() {
    String sql = runAllTenantsAndCaptureSql();

    // The exclusion is on properties.kind; a request row whose path-derived target happens to read
    // like the token path has kind 'request' (or none) and is billed. No target comparison at all.
    assertFalse(sql.contains("e.target"), sql);
    assertTrue(sql.contains(":unbilledKind"), sql);
    verify(query).setParameter("unbilledKind", PublicApiCall.KIND_TOKEN_EXCHANGE);
    verify(query, never()).setParameter(org.mockito.ArgumentMatchers.eq("tokenTarget"),
        (Object) org.mockito.ArgumentMatchers.any());
  }

  @Test
  void theUnbilledKindIsBoundInTheOneTenantBranchToo() {
    when(query.list()).thenReturn(List.of());

    counter.count(new UsageCountRequest(mock(BillingResource.class), FROM, TO, "T1"));

    verify(query).setParameter("unbilledKind", "tokenExchange");
  }

  @Test
  void allTenantsIsOneGroupedQueryWithAllTheFiltersBound() {
    String sql = runAllTenantsAndCaptureSql();

    assertFalse(sql.contains(":clientId"));
    assertTrue(sql.contains("group by e.ad_client_id"));
    verify(query).setParameter("eventType", "publicapi.request");
    verify(query).setParameter("source", "backend");
    verify(query).setParameter("unbilledKind", "tokenExchange");
    verify(query).setParameter("dayFrom", UsageDayRange.startOfDay(FROM));
    verify(query).setParameter("dayTo", UsageDayRange.startOfDay(TO));
    verify(query, never()).setParameter(org.mockito.ArgumentMatchers.eq("clientId"),
        (Object) org.mockito.ArgumentMatchers.any());
  }

  @Test
  void oneTenantIsScopedAndStillBindsEveryFilter() {
    when(query.list()).thenReturn(List.of());

    counter.count(new UsageCountRequest(mock(BillingResource.class), FROM, TO, "T1"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    assertTrue(sql.getValue().contains("and e.ad_client_id = :clientId"), sql.getValue());
    verify(query).setParameter("clientId", "T1");
    verify(query).setParameter("unbilledKind", "tokenExchange");
    verify(query).setParameter("eventType", "publicapi.request");
    verify(query).setParameter("source", "backend");
  }

  @Test
  void everyTenantDayWithRequestsBecomesOneCountAndEmptyDaysAreSkipped() {
    when(query.list()).thenReturn(Arrays.asList(
        new Object[] { "T1", DAY, 1_234L },
        new Object[] { "T2", DAY, 0L },
        new Object[] { "T3", DAY, null },
        new Object[] { "T4", DAY, java.math.BigInteger.valueOf(7) }));

    List<DailyCount> counts = counter.count(new UsageCountRequest(mock(BillingResource.class),
        FROM, TO, null));

    assertEquals(2, counts.size());
    assertEquals("T1", counts.get(0).getClientId());
    assertEquals(1_234L, counts.get(0).getQuantity());
    assertEquals(DAY.getTime(), counts.get(0).getDay().getTime());
    assertEquals("T4", counts.get(1).getClientId());
    assertEquals(7L, counts.get(1).getQuantity());
  }
}
