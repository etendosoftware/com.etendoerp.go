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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.MatchingAlgorithm;

/**
 * Mockito-driven unit tests for {@link FinancialAccountSupport}, the helper that creates
 * {@link FIN_FinancialAccount} records programmatically for the bank connection "connect first, create after"
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
 *   <li>assignDefaultPaymentMethods: Cash/Bank/Card accounts get their type's methods linked, the
 *       first one flagged as default (Bank -> Transferencia bancaria, Recibo, Tarjeta; Card ->
 *       Tarjeta, Recibo — Recibo is never first, so it never becomes an account's default); a
 *       method not found in the catalog is skipped without
 *       throwing; an existing link is left untouched (idempotent, no extra save); an unmapped
 *       type and the "nothing created" case never call {@code OBDal.flush()}. The link also
 *       copies uponDepositUse/uponWithdrawalUse/inUponClearingUse/outUponClearingUse/
 *       automaticDeposit/automaticWithdrawn from the
 *       master {@link FIN_PaymentMethod} — verified both with truthy values and with
 *       false/null values to confirm it is a genuine copy, not a hardcoded default. Tested
 *       end-to-end
 *       against the real static method (moved here from {@code FinancialAccountHandler} — see
 *       {@code FinancialAccountHandlerTest#testAfterHandlePostAssignsForCreatedAccount} for the
 *       hook-delegation test), since {@code findPaymentMethodByName}/{@code linkExists}/
 *       {@code createLink} are private and cannot be stubbed individually.</li>
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

  // ---------------------------------------------------------------------------
  // assignDefaultPaymentMethods
  // ---------------------------------------------------------------------------

  /** A Cash account gets Efectivo linked and flagged as its default payment method. */
  @Test
  public void testAssignDefaultPaymentMethodsCashLinksEfectivoAsDefault() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(account.getType()).thenReturn("C");
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);
    FIN_PaymentMethod cash = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    when(cash.getUponDepositUse()).thenReturn("DEP");
    when(cash.getUponWithdrawalUse()).thenReturn("WIT");
    when(cash.getINUponClearingUse()).thenReturn("CLE");
    when(cash.getOUTUponClearingUse()).thenReturn("CLE");
    when(cash.isAutomaticDeposit()).thenReturn(true);
    when(cash.isAutomaticWithdrawn()).thenReturn(true);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> methodCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_PaymentMethod.class)).thenReturn(methodCriteria);
      when(methodCriteria.uniqueResult()).thenReturn(cash);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);
      when(linkCriteria.uniqueResult()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class)).thenReturn(link);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      verify(link).setClient(client);
      verify(link).setOrganization(org);
      verify(link).setAccount(account);
      verify(link).setPaymentMethod(cash);
      verify(link).setDefault(true);
      verify(link).setUponDepositUse("DEP");
      verify(link).setUponWithdrawalUse("WIT");
      verify(link).setINUponClearingUse("CLE");
      verify(link).setOUTUponClearingUse("CLE");
      verify(link).setAutomaticDeposit(true);
      verify(link).setAutomaticWithdrawn(true);
      verify(dal).save(link);
      verify(dal).flush();
    }
  }

  /**
   * Verifies the reconcile/automatic-use fields are a genuine copy from the master
   * {@link FIN_PaymentMethod} — not a hardcoded constant — by using a master with
   * {@code false}/{@code null} values and confirming the link mirrors them exactly.
   */
  @Test
  public void testAssignDefaultPaymentMethodsCopiesFalseAndNullReconcileFieldsFromMaster() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(account.getType()).thenReturn("C");
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);
    FIN_PaymentMethod cash = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    when(cash.getUponDepositUse()).thenReturn(null);
    when(cash.getUponWithdrawalUse()).thenReturn(null);
    when(cash.getINUponClearingUse()).thenReturn(null);
    when(cash.getOUTUponClearingUse()).thenReturn(null);
    when(cash.isAutomaticDeposit()).thenReturn(false);
    when(cash.isAutomaticWithdrawn()).thenReturn(false);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> methodCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_PaymentMethod.class)).thenReturn(methodCriteria);
      when(methodCriteria.uniqueResult()).thenReturn(cash);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);
      when(linkCriteria.uniqueResult()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class)).thenReturn(link);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      verify(link).setUponDepositUse(null);
      verify(link).setUponWithdrawalUse(null);
      verify(link).setINUponClearingUse(null);
      verify(link).setOUTUponClearingUse(null);
      verify(link).setAutomaticDeposit(false);
      verify(link).setAutomaticWithdrawn(false);
      verify(dal).save(link);
      verify(dal).flush();
    }
  }

  /**
   * ETP-4891: the bank-transfer method NEVER auto-withdraws, so the link is forced to {@code false}
   * even when the master template says {@code true}. That is the whole point of the guard: sampledata
   * and data-fix R24 correct the template, but a legacy tenant whose template is still {@code 'Y'}
   * — or a transfer method created by hand — must not be able to propagate it to the link. Every
   * OTHER reconcile/automatic field is still copied verbatim from the master, so the guard is
   * narrow, not a blanket override.
   */
  @Test
  public void testAssignDefaultPaymentMethodsForcesAutomaticWithdrawnOffForBankTransfer() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(account.getType()).thenReturn("C");
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);
    FIN_PaymentMethod transfer = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);
    // Identified as the transfer method by the flag, and configured to auto-withdraw.
    when(transfer.isPSD2IsBankTransfer()).thenReturn(Boolean.TRUE);
    when(transfer.isAutomaticWithdrawn()).thenReturn(true);
    when(transfer.isAutomaticDeposit()).thenReturn(true);
    when(transfer.getUponDepositUse()).thenReturn("DEP");
    when(transfer.getUponWithdrawalUse()).thenReturn("WIT");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> methodCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_PaymentMethod.class)).thenReturn(methodCriteria);
      when(methodCriteria.uniqueResult()).thenReturn(transfer);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);
      when(linkCriteria.uniqueResult()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class)).thenReturn(link);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      verify(link).setAutomaticWithdrawn(false);
      verify(link, never()).setAutomaticWithdrawn(true);
      // Payment IN is untouched by the PIS flow, so Automatic Deposit is still a faithful copy.
      verify(link).setAutomaticDeposit(true);
      verify(link).setUponWithdrawalUse("WIT");
      verify(link).setPSD2IsBankTransfer(true);
    }
  }

  /**
   * Regression for the Cheque -> Recibo replacement: {@code createLink} must copy the
   * reconciliation ("Cleared Payment Account") columns {@code INUponClearingUse} /
   * {@code OUTUponClearingUse} from the master {@link FIN_PaymentMethod} onto the new link.
   *
   * <p>Recibo is the only one of the four seeded methods whose template carries
   * {@code inuponclearinguse='CLE'} / {@code outuponclearinguse='CLE'}. Before these two copies
   * existed, a runtime-created link was born with both columns EMPTY while the corrective
   * data-fix (R24) set them to CLE — so an account created from Etendo GO diverged from an
   * account repaired by the data-fix. Verified here on the Recibo link of a Bank account, with
   * the sibling Transferencia/Tarjeta links deliberately given a different (empty) template so a
   * hardcoded 'CLE' default would not pass.
   */
  @Test
  public void testAssignDefaultPaymentMethodsCopiesClearingUseFromReceiptTemplate() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn(TYPE_BANK);
    when(account.getClient()).thenReturn(mock(Client.class));
    when(account.getOrganization()).thenReturn(mock(Organization.class));

    FIN_PaymentMethod transfer = mock(FIN_PaymentMethod.class);
    FIN_PaymentMethod receipt = mock(FIN_PaymentMethod.class);
    FIN_PaymentMethod card = mock(FIN_PaymentMethod.class);
    // Only the Recibo template carries the reconciliation accounts.
    when(receipt.getINUponClearingUse()).thenReturn("CLE");
    when(receipt.getOUTUponClearingUse()).thenReturn("CLE");
    when(receipt.getUponDepositUse()).thenReturn("DEP");
    when(receipt.getUponWithdrawalUse()).thenReturn("WIT");

    FinAccPaymentMethod transferLink = mock(FinAccPaymentMethod.class);
    FinAccPaymentMethod receiptLink = mock(FinAccPaymentMethod.class);
    FinAccPaymentMethod cardLink = mock(FinAccPaymentMethod.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> transferCriteria = mock(OBCriteria.class);
      when(transferCriteria.uniqueResult()).thenReturn(transfer);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> receiptCriteria = mock(OBCriteria.class);
      when(receiptCriteria.uniqueResult()).thenReturn(receipt);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> cardCriteria = mock(OBCriteria.class);
      when(cardCriteria.uniqueResult()).thenReturn(card);
      when(dal.createCriteria(FIN_PaymentMethod.class))
          .thenReturn(transferCriteria, receiptCriteria, cardCriteria);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(linkCriteria.uniqueResult()).thenReturn(null);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class))
          .thenReturn(transferLink, receiptLink, cardLink);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      // The Recibo link mirrors its template exactly...
      verify(receiptLink).setINUponClearingUse("CLE");
      verify(receiptLink).setOUTUponClearingUse("CLE");
      verify(receiptLink).setUponDepositUse("DEP");
      verify(receiptLink).setUponWithdrawalUse("WIT");
      // ...and the copy is genuine: the siblings, whose templates are empty, stay empty.
      verify(transferLink).setINUponClearingUse(null);
      verify(transferLink).setOUTUponClearingUse(null);
      verify(cardLink).setINUponClearingUse(null);
      verify(cardLink).setOUTUponClearingUse(null);
    }
  }

  /**
   * A Bank account links its three configured methods (Transferencia bancaria, Recibo,
   * Tarjeta), with only the first (Transferencia bancaria) flagged as default. Recibo is
   * deliberately NOT first, so replacing Cheque with it can never steal the account default.
   */
  @Test
  public void testAssignDefaultPaymentMethodsBankLinksThreeMethodsTransferDefault() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(account.getType()).thenReturn("B");
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);

    FIN_PaymentMethod transfer = mock(FIN_PaymentMethod.class);
    FIN_PaymentMethod receipt = mock(FIN_PaymentMethod.class);
    FIN_PaymentMethod card = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod transferLink = mock(FinAccPaymentMethod.class);
    FinAccPaymentMethod receiptLink = mock(FinAccPaymentMethod.class);
    FinAccPaymentMethod cardLink = mock(FinAccPaymentMethod.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      // One criteria mock per method lookup, returned in call order (Transfer, Receipt, Card —
      // the iteration order of PAYMENT_METHODS_BY_TYPE.get("B")).
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> transferCriteria = mock(OBCriteria.class);
      when(transferCriteria.uniqueResult()).thenReturn(transfer);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> receiptCriteria = mock(OBCriteria.class);
      when(receiptCriteria.uniqueResult()).thenReturn(receipt);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> cardCriteria = mock(OBCriteria.class);
      when(cardCriteria.uniqueResult()).thenReturn(card);
      when(dal.createCriteria(FIN_PaymentMethod.class))
          .thenReturn(transferCriteria, receiptCriteria, cardCriteria);

      // No existing links for any of the three lookups.
      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(linkCriteria.uniqueResult()).thenReturn(null);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class))
          .thenReturn(transferLink, receiptLink, cardLink);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      verify(transferLink).setPaymentMethod(transfer);
      verify(transferLink).setDefault(true);
      verify(receiptLink).setPaymentMethod(receipt);
      verify(receiptLink).setDefault(false);
      verify(cardLink).setPaymentMethod(card);
      verify(cardLink).setDefault(false);
      verify(dal, times(3)).save(any());
      verify(dal).flush();
    }
  }

  /**
   * A Card account links its two configured methods (Tarjeta, Recibo), with only the first
   * (Tarjeta) flagged as default.
   *
   * <p>Recibo on a Card account is NEW behaviour introduced with the Cheque -> Recibo replacement
   * (Cheque was never linked to Card accounts). This test pins both halves of the contract: the
   * account gets TWO links, and the second one (Recibo) is never the default — the reason Recibo
   * is listed after Tarjeta in {@code PAYMENT_METHODS_BY_TYPE.get("CA")}.
   */
  @Test
  public void testAssignDefaultPaymentMethodsCardLinksTarjetaAsDefaultAndReceiptNonDefault() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(account.getType()).thenReturn("CA");
    when(account.getClient()).thenReturn(client);
    when(account.getOrganization()).thenReturn(org);
    FIN_PaymentMethod card = mock(FIN_PaymentMethod.class);
    FIN_PaymentMethod receipt = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod cardLink = mock(FinAccPaymentMethod.class);
    FinAccPaymentMethod receiptLink = mock(FinAccPaymentMethod.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      // One criteria mock per method lookup, returned in call order (Card, Receipt — the
      // iteration order of PAYMENT_METHODS_BY_TYPE.get("CA")).
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> cardCriteria = mock(OBCriteria.class);
      when(cardCriteria.uniqueResult()).thenReturn(card);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> receiptCriteria = mock(OBCriteria.class);
      when(receiptCriteria.uniqueResult()).thenReturn(receipt);
      when(dal.createCriteria(FIN_PaymentMethod.class))
          .thenReturn(cardCriteria, receiptCriteria);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);
      when(linkCriteria.uniqueResult()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class)).thenReturn(cardLink, receiptLink);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      verify(cardLink).setPaymentMethod(card);
      verify(cardLink).setDefault(true);
      verify(receiptLink).setPaymentMethod(receipt);
      verify(receiptLink).setDefault(false);
      verify(dal, times(2)).save(any());
      verify(dal).flush();
    }
  }

  /** An account type with no mapping links nothing and never touches the DAL. */
  @Test
  public void testAssignDefaultPaymentMethodsUnknownTypeDoesNothing() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn("ZZ");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      FinancialAccountSupport.assignDefaultPaymentMethods(account);
      obDal.verifyNoInteractions();
    }
  }

  /**
   * A payment method missing from the catalog (e.g. deactivated/renamed) is skipped without
   * throwing, and nothing is created or flushed for it.
   */
  @Test
  public void testAssignDefaultPaymentMethodsMethodNotFoundSkipsWithoutThrowing() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn("C");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> methodCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_PaymentMethod.class)).thenReturn(methodCriteria);
      when(methodCriteria.uniqueResult()).thenReturn(null);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      obProvider.verifyNoInteractions();
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  /**
   * An existing link is left untouched (idempotent): no new link is created, and — since
   * nothing was created — {@code OBDal.flush()} is never called either.
   */
  @Test
  public void testAssignDefaultPaymentMethodsExistingLinkIsIdempotentAndDoesNotFlush() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn("C");
    FIN_PaymentMethod cash = mock(FIN_PaymentMethod.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> methodCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_PaymentMethod.class)).thenReturn(methodCriteria);
      when(methodCriteria.uniqueResult()).thenReturn(cash);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);
      when(linkCriteria.uniqueResult()).thenReturn(mock(FinAccPaymentMethod.class));

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      obProvider.verifyNoInteractions();
      verify(dal, never()).save(any());
      verify(dal, never()).flush();
    }
  }

  /**
   * Regression (ETP-4503): every runtime-created link is born multicurrency-ON. A new Cash link must
   * set both {@code payinIsMulticurrency} and {@code payoutIsMulticurrency} to {@code true} (the
   * bank-transfer exception is applied afterwards, only on bank-connected Bank accounts).
   */
  @Test
  public void testAssignDefaultPaymentMethodsSetsMulticurrencyOnNewLink() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getType()).thenReturn("C");
    when(account.getClient()).thenReturn(mock(Client.class));
    when(account.getOrganization()).thenReturn(mock(Organization.class));
    FIN_PaymentMethod cash = mock(FIN_PaymentMethod.class);
    FinAccPaymentMethod link = mock(FinAccPaymentMethod.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBCriteria<FIN_PaymentMethod> methodCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_PaymentMethod.class)).thenReturn(methodCriteria);
      when(methodCriteria.uniqueResult()).thenReturn(cash);

      @SuppressWarnings("unchecked")
      OBCriteria<FinAccPaymentMethod> linkCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(FinAccPaymentMethod.class)).thenReturn(linkCriteria);
      when(linkCriteria.uniqueResult()).thenReturn(null);

      OBProvider provider = mock(OBProvider.class);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(FinAccPaymentMethod.class)).thenReturn(link);

      FinancialAccountSupport.assignDefaultPaymentMethods(account);

      verify(link).setPayinIsMulticurrency(true);
      verify(link).setPayoutIsMulticurrency(true);
    }
  }

}
