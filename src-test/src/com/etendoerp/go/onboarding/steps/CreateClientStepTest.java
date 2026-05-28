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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStepException;

/**
 * Unit tests for {@link CreateClientStep}.
 * <p>
 * Tests focus on {@code resolveCurrencyId} (via reflection) and error paths
 * in {@code execute}. The full {@code InitialClientSetup} flow is intentionally
 * excluded because it requires a servlet context and complex dependencies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateClientStepTest {

  private CreateClientStep step;

  @Mock private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    step = new CreateClientStep();
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  // --- name ---

  @Test
  @DisplayName("name() returns createClient")
  void nameReturnsCreateClient() {
    assertEquals("createClient", step.name());
  }

  // --- resolveCurrencyId via reflection ---

  @Nested
  @DisplayName("resolveCurrencyId")
  class ResolveCurrencyId {

    private Method resolveCurrencyIdMethod;

    @BeforeEach
    void setUpReflection() throws Exception {
      resolveCurrencyIdMethod = CreateClientStep.class
          .getDeclaredMethod("resolveCurrencyId", String.class);
      resolveCurrencyIdMethod.setAccessible(true);
    }

    @Test
    @DisplayName("found currency returns its ID")
    void foundCurrencyReturnsId() throws Exception {
      Currency currency = mock(Currency.class);
      doReturn("102").when(currency).getId();

      @SuppressWarnings("unchecked")
      OBCriteria<Currency> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(currency);

      String result = (String) resolveCurrencyIdMethod.invoke(step, "USD");
      assertEquals("102", result);
    }

    @Test
    @DisplayName("currency not found throws OBException")
    void currencyNotFoundThrowsOBException() throws Exception {
      @SuppressWarnings("unchecked")
      OBCriteria<Currency> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      Exception thrown = assertThrows(Exception.class,
          () -> resolveCurrencyIdMethod.invoke(step, "XYZ"));
      // Reflection wraps the OBException in an InvocationTargetException
      Throwable cause = thrown.getCause();
      assertTrue(cause instanceof OBException);
      assertTrue(cause.getMessage().contains("Currency not found for ISO code: XYZ"));
    }
  }

  // --- execute: error paths ---

  @Nested
  @DisplayName("execute error paths")
  class ExecuteErrors {

    @Test
    @DisplayName("currency not found wraps OBException in OnboardingStepException")
    void currencyNotFoundThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setCurrencyCode("INVALID");

      @SuppressWarnings("unchecked")
      OBCriteria<Currency> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Currency not found for ISO code: INVALID"));
    }

    @Test
    @DisplayName("null currency code wraps exception in OnboardingStepException")
    void nullCurrencyCodeThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setCurrencyCode(null);

      @SuppressWarnings("unchecked")
      OBCriteria<Currency> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(Currency.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Currency not found"));
    }
  }
}
