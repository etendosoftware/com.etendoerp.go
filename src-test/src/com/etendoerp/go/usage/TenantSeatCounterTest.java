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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
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

/**
 * The seats a tenant uses: one native count per tenant of the active users that are its owner or
 * hold a non-revoked invitation, returned as a stock dated at the start of the requested range.
 *
 * <p>The native query is mocked: these cases pin how its rows are mapped and how it is scoped,
 * not what the database answers.
 *
 * @covers com.etendoerp.go.usage.TenantSeatCounter
 */
class TenantSeatCounterTest {

  private static final Date FROM = new Date(1_790_000_000_000L);
  private static final Date TO = new Date(1_790_864_000_000L);

  private MockedStatic<OBDal> obDal;
  private Session session;
  private NativeQuery<Object[]> query;
  private final TenantSeatCounter counter = new TenantSeatCounter();

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

  private String executedSql() {
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(session).createNativeQuery(sql.capture());
    return sql.getValue();
  }

  private static UsageCountRequest request(String clientId) {
    return new UsageCountRequest(mock(BillingResource.class), FROM, TO, clientId);
  }

  @Test
  void theCounterIsBoundToTheSeatsQualifier() {
    assertEquals("tenantSeats", TenantSeatCounter.QUALIFIER);
    assertEquals(TenantSeatCounter.QUALIFIER,
        TenantSeatCounter.class.getAnnotation(Named.class).value());
  }

  @Test
  void anAllTenantsCountGivesOneStockPerTenantDatedAtTheStartOfTheRange() {
    when(query.list()).thenReturn(Arrays.asList(new Object[] { "TENANT-A", 3L },
        new Object[] { "TENANT-B", BigInteger.valueOf(7L) }));

    List<DailyCount> counts = counter.count(request(null));

    assertEquals(2, counts.size());
    assertEquals("TENANT-A", counts.get(0).getClientId());
    assertEquals(3L, counts.get(0).getQuantity());
    assertEquals(FROM, counts.get(0).getDay());
    assertEquals("TENANT-B", counts.get(1).getClientId());
    assertEquals(7L, counts.get(1).getQuantity());
    assertEquals(FROM, counts.get(1).getDay());
  }

  @Test
  void anAllTenantsCountIsNotScopedToAClient() {
    when(query.list()).thenReturn(Collections.emptyList());

    assertTrue(counter.count(request(null)).isEmpty());

    String sql = executedSql();
    assertFalse(sql.contains(":clientId"), sql);
    assertTrue(sql.endsWith(" group by u.ad_client_id"), sql);
    verify(query, never()).setParameter(anyString(), any());
  }

  @Test
  void aOneTenantCountIsScopedToThatClient() {
    when(query.list()).thenReturn(Collections.singletonList(new Object[] { "TENANT-A", 2L }));

    List<DailyCount> counts = counter.count(request("TENANT-A"));

    assertEquals(1, counts.size());
    assertEquals(2L, counts.get(0).getQuantity());
    assertTrue(executedSql().contains("u.ad_client_id = :clientId"));
    verify(query).setParameter("clientId", "TENANT-A");
  }

  @Test
  void theSeatQueryCountsActiveOwnersAndNonRevokedInvitationsOutsideTheSystemClient() {
    when(query.list()).thenReturn(Collections.emptyList());

    counter.seatsOf("TENANT-A");

    String sql = executedSql();
    assertTrue(sql.contains("u.isactive = 'Y'"), sql);
    assertTrue(sql.contains("u.ad_client_id <> '0'"), sql);
    assertTrue(sql.contains("u.em_etgo_is_owner = 'Y'"), sql);
    assertTrue(sql.contains("i.status <> 'REVOKED'"), sql);
  }

  @Test
  void theSeatsOfATenantAreItsCount() {
    when(query.list()).thenReturn(Collections.singletonList(
        new Object[] { "TENANT-A", BigInteger.valueOf(4L) }));

    assertEquals(4L, counter.seatsOf("TENANT-A"));
    verify(query).setParameter("clientId", "TENANT-A");
  }

  @Test
  void aTenantWithNoSeatUsesZero() {
    when(query.list()).thenReturn(Collections.emptyList());

    assertEquals(0L, counter.seatsOf("TENANT-A"));
  }

  // ─── isSeatOnceActive: whether reactivating a user takes a seat (ETP-5051) ──

  @Test
  void aUserIsASeatOnceActiveByTheSameRuleAsTheCountWhateverItsCurrentActiveFlag() {
    doReturn(BigInteger.ONE).when(query).uniqueResult();

    assertTrue(counter.isSeatOnceActive("USER-1"));

    String sql = executedSql();
    assertTrue(sql.contains("u.ad_user_id = :userId"), sql);
    assertTrue(sql.contains("u.ad_client_id <> '0'"), sql);
    assertTrue(sql.contains("u.em_etgo_is_owner = 'Y'"), sql);
    assertTrue(sql.contains("i.status <> 'REVOKED'"), sql);
    assertFalse(sql.contains("u.isactive"), sql);
    verify(query).setParameter("userId", "USER-1");
  }

  @Test
  void aUserNeitherOwnerNorHoldingALiveInvitationIsNoSeatOnceActive() {
    doReturn(BigInteger.ZERO).when(query).uniqueResult();

    assertFalse(counter.isSeatOnceActive("CONTACT-1"));
  }

  @Test
  void noUserIsNoSeatAndIsNotQueried() {
    assertFalse(counter.isSeatOnceActive(null));
    verify(session, never()).createNativeQuery(anyString());
  }
}
