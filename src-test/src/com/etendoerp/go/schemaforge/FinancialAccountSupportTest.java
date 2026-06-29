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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

/**
 * Mockito-driven unit tests for {@link FinancialAccountSupport}, the helper that creates
 * {@link FIN_FinancialAccount} records programmatically for the PSD2 "connect first, create after"
 * flow (case 2) and resolves a {@link Currency} from its ISO code.
 *
 * <p>Strategy: both methods are pure DAL orchestration, so the static {@link OBProvider},
 * {@link OBDal} entry points are stubbed with {@code mockStatic} and the built / queried entities
 * are Mockito mocks. No live OBContext or database is required.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>createAccount: builds the FA with the given client/org/currency/name/type, zeroes the
 *       balances and credit limit, assigns a default matching algorithm and persists (save+flush).</li>
 *   <li>createAccount: when no matching algorithm exists the FA is still built and persisted.</li>
 *   <li>findCurrencyByIsoCode: blank / null input short-circuits to null without the DAL;
 *       a found code returns the currency (uppercased, active filter); a missing code returns null.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountSupportTest {

  private static final String NAME = "Banco Santander - Cuenta corriente";
  private static final String TYPE_BANK = "B";

  /** Clears the inline mock cache after each test to keep the single-JVM suite heap flat. */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /**
   * Verifies {@code createAccount} builds a {@link FIN_FinancialAccount} from the supplied
   * client/org/currency/name/type, defaults the balances and credit limit to zero, marks it
   * non-default, attaches the first active {@link MatchingAlgorithm} and persists it.
   */
  @Test
  public void testCreateAccountBuildsAndPersistsWithDefaults() {
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Currency currency = mock(Currency.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    MatchingAlgorithm algorithm = mock(MatchingAlgorithm.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinancialAccount.class)).thenReturn(account);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchingAlgorithm> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchingAlgorithm.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(algorithm);

      FIN_FinancialAccount result =
          FinancialAccountSupport.createAccount(client, org, currency, NAME, TYPE_BANK);

      assertSame(account, result);
      verify(account).setNewOBObject(true);
      verify(account).setClient(client);
      verify(account).setOrganization(org);
      verify(account).setName(NAME);
      verify(account).setCurrency(currency);
      verify(account).setType(TYPE_BANK);
      verify(account).setCurrentBalance(BigDecimal.ZERO);
      verify(account).setInitialBalance(BigDecimal.ZERO);
      verify(account).setCreditLimit(BigDecimal.ZERO);
      verify(account).setDefault(false);
      verify(account).setMatchingAlgorithm(algorithm);
      verify(dal).save(account);
      verify(dal).flush();
    }
  }

  /**
   * Verifies {@code createAccount} still builds and persists the account when no active
   * {@link MatchingAlgorithm} exists, leaving the algorithm unset.
   */
  @Test
  public void testCreateAccountWithoutMatchingAlgorithmStillPersists() {
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    Currency currency = mock(Currency.class);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);

    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinancialAccount.class)).thenReturn(account);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchingAlgorithm> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchingAlgorithm.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      FIN_FinancialAccount result =
          FinancialAccountSupport.createAccount(client, org, currency, NAME, TYPE_BANK);

      assertSame(account, result);
      verify(account, never()).setMatchingAlgorithm(any());
      verify(dal).save(account);
      verify(dal).flush();
    }
  }

  /**
   * Verifies the default matching-algorithm lookup orders by name ascending and filters on active
   * records — captured via the criteria stub the create path drives.
   */
  @Test
  public void testCreateAccountOrdersMatchingAlgorithmByName() {
    try (MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FIN_FinancialAccount.class)).thenReturn(mock(FIN_FinancialAccount.class));

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchingAlgorithm> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchingAlgorithm.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(mock(MatchingAlgorithm.class));

      FinancialAccountSupport.createAccount(mock(Client.class), mock(Organization.class),
          mock(Currency.class), NAME, TYPE_BANK);

      verify(criteria).addOrderBy(MatchingAlgorithm.PROPERTY_NAME, true);
      verify(criteria).setMaxResults(1);
    }
  }

  /** A blank ISO code resolves to null without ever touching the DAL. */
  @Test
  public void testFindCurrencyByIsoCodeBlankReturnsNullWithoutDal() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountSupport.findCurrencyByIsoCode("  "));
      obDal.verifyNoInteractions();
    }
  }

  /** A null ISO code resolves to null without ever touching the DAL. */
  @Test
  public void testFindCurrencyByIsoCodeNullReturnsNullWithoutDal() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountSupport.findCurrencyByIsoCode(null));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * Verifies a known ISO code is uppercased and queried with the readable client/org filters
   * disabled and the active filter on, returning the criteria's unique result.
   */
  @Test
  public void testFindCurrencyByIsoCodeFoundReturnsCurrency() {
    Currency currency = mock(Currency.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Currency> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Currency.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(currency);

      // Lower-case input must still match (the body uppercases to "EUR").
      assertSame(currency, FinancialAccountSupport.findCurrencyByIsoCode("eur"));

      verify(criteria).setFilterOnReadableClients(false);
      verify(criteria).setFilterOnReadableOrganization(false);
      verify(criteria).setMaxResults(1);
      // isoCode (uppercased) + active.
      verify(criteria, times(2)).add(any());
    }
  }

  /** An unknown ISO code returns null (no matching currency). */
  @Test
  public void testFindCurrencyByIsoCodeNotFoundReturnsNull() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Currency> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Currency.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(null);

      assertNull(FinancialAccountSupport.findCurrencyByIsoCode("ZZZ"));
    }
  }
}
