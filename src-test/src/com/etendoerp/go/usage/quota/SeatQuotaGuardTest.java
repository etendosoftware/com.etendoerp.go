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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import com.etendoerp.go.usage.TenantSeatCounter;

/**
 * The quota check of a new seat: refused only when the plan's {@code block} quota on the
 * {@code tenantSeats} resource is already used up in the current window.
 *
 * <p>{@link SeatQuotaGuard} builds its own collaborators, so the {@link DalQuotaLookup} and the
 * {@link TenantSeatCounter} are replaced by construction mocks; the {@link QuotaEvaluator} is the
 * real one, so the verdict is the production rule. Its clock is the system clock, hence a billing
 * period around the real now.
 *
 * @covers com.etendoerp.go.usage.quota.SeatQuotaGuard
 */
class SeatQuotaGuardTest {

  private static final long DAY_MS = 86_400_000L;

  private static QuotaDefinition seats(String mode, long included) {
    return quota("SEATS", mode, included, 80L).strategyQualifier(TenantSeatCounter.QUALIFIER)
        .build();
  }

  /** A tenant whose monthly billing period, and subscription year, contain the real now. */
  private static TenantQuotas tenantNow(QuotaDefinition... quotas) {
    long now = System.currentTimeMillis();
    return new TenantQuotas(CLIENT, "month", new Date(now - DAY_MS), new Date(now + 29 * DAY_MS),
        false, new Date(now - 10 * DAY_MS), Arrays.asList(quotas));
  }

  private static Optional<QuotaEvaluation> refusal(TenantQuotas tenant, long seatsInUse) {
    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class,
        (mock, context) -> when(mock.forTenant(CLIENT)).thenReturn(tenant));
        MockedConstruction<TenantSeatCounter> counter = mockConstruction(TenantSeatCounter.class,
            (mock, context) -> when(mock.seatsOf(CLIENT)).thenReturn(seatsInUse))) {
      return SeatQuotaGuard.refusalForNewSeat(CLIENT);
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "  ", "0" })
  void noTenantOrTheSystemClientIsNeverLookedUp(String clientId) {
    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class);
        MockedConstruction<TenantSeatCounter> counter =
            mockConstruction(TenantSeatCounter.class)) {
      assertFalse(SeatQuotaGuard.refusalForNewSeat(clientId).isPresent());
      assertTrue(lookup.constructed().isEmpty());
      assertTrue(counter.constructed().isEmpty());
    }
  }

  @Test
  void aTenantWithNoCurrentPeriodIsNotCounted() {
    TenantQuotas periodless = new TenantQuotas(CLIENT, "month", null, null, false,
        Arrays.asList(seats("block", 3L)));

    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class,
        (mock, context) -> when(mock.forTenant(CLIENT)).thenReturn(periodless));
        MockedConstruction<TenantSeatCounter> counter =
            mockConstruction(TenantSeatCounter.class)) {
      assertFalse(SeatQuotaGuard.refusalForNewSeat(CLIENT).isPresent());
      assertTrue(counter.constructed().isEmpty(), "no seat is counted");
    }
  }

  @Test
  void aPlanWithoutASeatQuotaLetsTheSeatThroughUncounted() {
    TenantQuotas tenant = tenantNow(quota("DOCS", "block", 0L, null).build());

    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class,
        (mock, context) -> when(mock.forTenant(CLIENT)).thenReturn(tenant));
        MockedConstruction<TenantSeatCounter> counter =
            mockConstruction(TenantSeatCounter.class)) {
      assertFalse(SeatQuotaGuard.refusalForNewSeat(CLIENT).isPresent());
      counter.constructed().forEach(c -> verify(c, never()).seatsOf(anyString()));
    }
  }

  @Test
  void aSeatIsAllowedWhileTheTenantUsesFewerSeatsThanItsPlan() {
    assertFalse(refusal(tenantNow(seats("block", 3L)), 2L).isPresent());
  }

  @Test
  void aSeatIsRefusedOnceEverySeatOfThePlanIsTaken() {
    QuotaDefinition seatQuota = seats("block", 3L);

    Optional<QuotaEvaluation> refusal = refusal(tenantNow(seatQuota), 3L);

    assertTrue(refusal.isPresent());
    assertEquals(QuotaVerdict.DENY, refusal.get().getVerdict());
    assertEquals(Long.valueOf(3L), refusal.get().getConsumed());
    assertEquals("SEATS", refusal.get().getQuota().getResourceKey());
    assertEquals("year", refusal.get().getPlanInterval());
  }

  @Test
  void theSeatQuotaIsFoundAmongThePlansOtherQuotas() {
    TenantQuotas tenant = tenantNow(quota("DOCS", "block", 0L, null).build(),
        seats("block", 3L));

    Optional<QuotaEvaluation> refusal = refusal(tenant, 5L);

    assertTrue(refusal.isPresent());
    assertEquals("SEATS", refusal.get().getQuota().getResourceKey());
  }

  @ParameterizedTest
  @ValueSource(strings = { "warn", "off" })
  void aSeatQuotaThatDoesNotBlockNeverRefuses(String mode) {
    assertFalse(refusal(tenantNow(seats(mode, 3L)), 10L).isPresent());
  }

  // ─── refusalForReactivation: only a user that is a seat once active takes one ──

  private static final String USER = "USER-1";

  private static Optional<QuotaEvaluation> reactivationRefusal(boolean seatOnceActive,
      long seatsInUse) {
    TenantQuotas tenant = tenantNow(seats("block", 3L));
    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class,
        (mock, context) -> when(mock.forTenant(CLIENT)).thenReturn(tenant));
        MockedConstruction<TenantSeatCounter> counter = mockConstruction(TenantSeatCounter.class,
            (mock, context) -> {
              when(mock.isSeatOnceActive(USER)).thenReturn(seatOnceActive);
              when(mock.seatsOf(CLIENT)).thenReturn(seatsInUse);
            })) {
      return SeatQuotaGuard.refusalForReactivation(CLIENT, USER);
    }
  }

  @Test
  void reactivatingASeatIsRefusedOnceEverySeatOfThePlanIsTaken() {
    Optional<QuotaEvaluation> refusal = reactivationRefusal(true, 3L);

    assertTrue(refusal.isPresent());
    assertEquals("SEATS", refusal.get().getQuota().getResourceKey());
  }

  @Test
  void reactivatingASeatIsAllowedWhileASeatIsFree() {
    assertFalse(reactivationRefusal(true, 2L).isPresent());
  }

  @Test
  void reactivatingAUserThatIsNoSeatOnceActiveIsNeverRefusedNorCounted() {
    TenantQuotas tenant = tenantNow(seats("block", 3L));

    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class,
        (mock, context) -> when(mock.forTenant(CLIENT)).thenReturn(tenant));
        MockedConstruction<TenantSeatCounter> counter = mockConstruction(TenantSeatCounter.class,
            (mock, context) -> when(mock.seatsOf(CLIENT)).thenReturn(10L))) {
      assertFalse(SeatQuotaGuard.refusalForReactivation(CLIENT, USER).isPresent());
      counter.constructed().forEach(c -> verify(c, never()).seatsOf(anyString()));
      assertTrue(lookup.constructed().isEmpty(), "no quota is looked up");
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "  ", "0" })
  void reactivatingInNoTenantOrTheSystemClientQueriesNothing(String clientId) {
    try (MockedConstruction<DalQuotaLookup> lookup = mockConstruction(DalQuotaLookup.class);
        MockedConstruction<TenantSeatCounter> counter =
            mockConstruction(TenantSeatCounter.class)) {
      assertFalse(SeatQuotaGuard.refusalForReactivation(clientId, USER).isPresent());
      assertTrue(lookup.constructed().isEmpty());
      assertTrue(counter.constructed().isEmpty());
    }
  }
}
