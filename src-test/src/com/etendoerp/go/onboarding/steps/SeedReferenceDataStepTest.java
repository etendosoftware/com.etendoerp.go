/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.onboarding.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.uom.UOM;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Unit tests for {@link SeedReferenceDataStep}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeedReferenceDataStepTest {

  private SeedReferenceDataStep step;

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private Client client;
  @Mock private Organization org;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;

  @BeforeEach
  void setUp() {
    step = new SeedReferenceDataStep();
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obProviderMock.close();
  }

  // ─── name ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("name() returns seedReferenceData")
  void nameReturnsSeedReferenceData() {
    assertEquals("seedReferenceData", step.name());
  }

  // ─── execute: error paths ───────────────────────────────────────────

  @Nested
  @DisplayName("execute error paths")
  class ExecuteErrors {

    @Test
    void nullClientThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("bad-client");
      ctx.setOrgId("org-1");
      when(obDal.get(Client.class, "bad-client")).thenReturn(null);
      when(obDal.get(Organization.class, "org-1")).thenReturn(mock(Organization.class));

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Client not found"));
    }

    @Test
    void nullOrgThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("client-1");
      ctx.setOrgId("bad-org");
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "bad-org")).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Organization not found"));
    }

    @Test
    void missingCurrencyThrowsOnboardingStepException() {
      OnboardingContext ctx = buildValidContext();
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "org-1")).thenReturn(org);

      OBCriteria<Currency> currCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(currCrit);
      when(currCrit.uniqueResult()).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Currency not found"));
    }

    @Test
    void missingCountryThrowsOnboardingStepException() {
      OnboardingContext ctx = buildValidContext();
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "org-1")).thenReturn(org);

      // Currency succeeds
      OBCriteria<Currency> currCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(currCrit);
      Currency currency = mock(Currency.class);
      when(currCrit.uniqueResult()).thenReturn(currency);

      // Country fails
      OBCriteria<Country> countryCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Country.class)).thenReturn(countryCrit);
      when(countryCrit.uniqueResult()).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Country not found"));
    }

    @Test
    void missingUomThrowsOnboardingStepException() {
      OnboardingContext ctx = buildValidContext();
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "org-1")).thenReturn(org);

      // Currency + Country succeed
      OBCriteria<Currency> currCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(currCrit);
      when(currCrit.uniqueResult()).thenReturn(mock(Currency.class));

      OBCriteria<Country> countryCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Country.class)).thenReturn(countryCrit);
      when(countryCrit.uniqueResult()).thenReturn(mock(Country.class));

      // UOM fails
      OBCriteria<UOM> uomCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(UOM.class)).thenReturn(uomCrit);
      when(uomCrit.uniqueResult()).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("UOM"));
    }
  }

  // ─── execute: happy path ────────────────────────────────────────────

  @Nested
  @DisplayName("execute happy path")
  class ExecuteHappyPath {

    @Test
    void fullExecutionSetsContextIds() throws Exception {
      OnboardingContext ctx = buildValidContext();
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "org-1")).thenReturn(org);

      // Resolve shared entities
      OBCriteria<Currency> currCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(currCrit);
      Currency currency = mock(Currency.class);
      when(currCrit.uniqueResult()).thenReturn(currency);

      OBCriteria<Country> countryCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(Country.class)).thenReturn(countryCrit);
      Country country = mock(Country.class);
      when(countryCrit.uniqueResult()).thenReturn(country);

      OBCriteria<UOM> uomCrit = mock(OBCriteria.class);
      when(obDal.createCriteria(UOM.class)).thenReturn(uomCrit);
      UOM uom = mock(UOM.class);
      when(uomCrit.uniqueResult()).thenReturn(uom);

      // OBProvider returns mocks for all entity creations
      when(obProvider.get(any(Class.class))).thenAnswer(inv -> {
        Class<?> clazz = inv.getArgument(0);
        return mock(clazz);
      });

      step.execute(ctx);

      // Verify the full flow completed: entities were saved and session flushed
      verify(obDal, atLeastOnce()).save(any());
      verify(obDal).flush();
      // Verify org was updated with calendar, currency and period control
      verify(org).setCalendar(any());
      verify(org).setCurrency(any());
      verify(org).setAllowPeriodControl(true);
    }
  }

  // ─── getMonthName ───────────────────────────────────────────────────

  @Nested
  @DisplayName("getMonthName")
  class GetMonthName {

    @ParameterizedTest
    @CsvSource({
        "1, January",
        "2, February",
        "3, March",
        "4, April",
        "5, May",
        "6, June",
        "7, July",
        "8, August",
        "9, September",
        "10, October",
        "11, November",
        "12, December"
    })
    void mapsAllMonths(int month, String expected) throws Exception {
      Method m = SeedReferenceDataStep.class.getDeclaredMethod("getMonthName", int.class);
      m.setAccessible(true);
      assertEquals(expected, m.invoke(step, month));
    }

    @Test
    void invalidMonthReturnsUnknown() throws Exception {
      Method m = SeedReferenceDataStep.class.getDeclaredMethod("getMonthName", int.class);
      m.setAccessible(true);
      assertEquals("Unknown", m.invoke(step, 0));
      assertEquals("Unknown", m.invoke(step, 13));
      assertEquals("Unknown", m.invoke(step, -1));
    }
  }

  // ─── Helpers ────────────────────────────────────────────────────────

  private OnboardingContext buildValidContext() {
    OnboardingContext ctx = new OnboardingContext();
    ctx.setClientId("client-1");
    ctx.setOrgId("org-1");
    ctx.setCurrencyCode("USD");
    ctx.setCountryCode("US");
    return ctx;
  }
}
