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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.BillingResource;

/**
 * The AI spend of a tenant per day, in cents: the native sum of {@code COST_USD} is mocked, so
 * these cases pin the rounding, the row mapping and the scoping, not what the database answers.
 *
 * @covers com.etendoerp.go.usage.AiSpendCounter
 */
class AiSpendCounterTest {

  private static final Date FROM = new Date(1_790_000_000_000L);
  private static final Date TO = new Date(1_790_864_000_000L);
  private static final Timestamp DAY = new Timestamp(UsageDayRange.startOfDay(FROM).getTime());

  private MockedStatic<OBDal> obDal;
  private Session session;
  private NativeQuery<Object[]> query;
  private final AiSpendCounter counter = new AiSpendCounter();

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

  @Test
  void aDaysSpendIsRoundedHalfUpToWholeCents() {
    assertEquals(0L, AiSpendCounter.toCents(null));
    assertEquals(0L, AiSpendCounter.toCents(new BigDecimal("0.004999")));
    assertEquals(1L, AiSpendCounter.toCents(new BigDecimal("0.005")));
    assertEquals(1234L, AiSpendCounter.toCents(new BigDecimal("12.3449")));
    assertEquals(5000L, AiSpendCounter.toCents(50.0));
  }

  @Test
  void everyTenantDayWithSpendBecomesOneCountAndSubCentDaysAreSkipped() {
    when(query.list()).thenReturn(Arrays.asList(
        new Object[] { "T1", DAY, new BigDecimal("0.42") },
        new Object[] { "T2", DAY, new BigDecimal("0.0001") }));

    List<DailyCount> counts = counter.count(new UsageCountRequest(mock(BillingResource.class),
        FROM, TO, null));

    assertEquals(1, counts.size());
    assertEquals("T1", counts.get(0).getClientId());
    assertEquals(DAY.getTime(), counts.get(0).getDay().getTime());
    assertEquals(42L, counts.get(0).getQuantity());
  }

  @Test
  void allTenantsIsOneGroupedQueryOverTheHalfOpenRange() {
    when(query.list()).thenReturn(List.of());

    counter.count(new UsageCountRequest(mock(BillingResource.class), FROM, TO, null));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    assertFalse(sql.getValue().contains(":clientId"));
    assertTrue(sql.getValue().contains("group by e.ad_client_id"));
    assertTrue(sql.getValue().contains("'support'"), "support-session spend is not the tenant's");
    verify(query).setParameter("dayFrom", UsageDayRange.startOfDay(FROM));
    verify(query).setParameter("dayTo", UsageDayRange.startOfDay(TO));
    verify(query, never()).setParameter("clientId", null);
  }

  @Test
  void oneTenantIsBoundAndNeverWidened() {
    when(query.list()).thenReturn(List.of());

    counter.count(new UsageCountRequest(mock(BillingResource.class), FROM, TO, "T1"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    assertTrue(sql.getValue().contains("e.ad_client_id = :clientId"));
    verify(query).setParameter("clientId", "T1");
  }
}
