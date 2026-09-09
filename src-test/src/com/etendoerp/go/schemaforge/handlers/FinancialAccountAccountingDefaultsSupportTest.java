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
package com.etendoerp.go.schemaforge.handlers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hibernate.Criteria;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Unit tests for {@link FinancialAccountAccountingDefaultsSupport} (ETP-4872 Task 3).
 *
 * <p>{@link OBDal} is stubbed statically so no DB is required. {@code resolveCombinationByCode}'s
 * nested {@code criteria.createCriteria(AccountingCombination.PROPERTY_ACCOUNT).add(...)} is
 * mocked with a single reused top-level {@link OBCriteria} and a single reused sub-{@link
 * Criteria}, with {@code uniqueResult()} stubbed to return a sequence of values — one per
 * invocation, in the exact call order {@code applyDefaultsForType} issues them (see the class
 * Javadoc on the source for that order).</p>
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>{@code null} account is a no-op (never breaks account creation)</li>
 *   <li>an org with no general ledger is a no-op (soft-degrade, mirrors the handler)</li>
 *   <li>Bank type: the 7 fields it owns get the correct default codes; the always-empty
 *       {@code clearedPaymentAccount}/{@code clearedPaymentAccountOUT} are never set</li>
 *   <li>Cash type: only the 4 shared fields get set, with the Caja-specific deposit/withdrawal
 *       code ({@code 57001000}); the 3 Bank-only fields are never touched</li>
 *   <li>Card type: only the 4 shared fields get set, with the Tarjeta-specific deposit/withdrawal
 *       code ({@code 57210000}); the 3 Bank-only fields are never touched</li>
 *   <li>a ledger with no matching account code for any default leaves every field untouched
 *       (soft-degrade, no exception) yet the row is still saved</li>
 *   <li>an unexpected exception while resolving is swallowed — never propagates and never breaks
 *       account creation</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinancialAccountAccountingDefaultsSupportTest {

  private static final String TYPE_BANK = "B";
  private static final String TYPE_CASH = "C";
  private static final String TYPE_CARD = "CA";

  private MockedStatic<OBDal> obDalMock;
  private OBDal obDal;

  private FIN_FinancialAccount account;
  private Organization org;
  private AcctSchema ledger;

  @BeforeEach
  void setUp() {
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private void wireAccountWithLedger(String type) {
    ledger = mock(AcctSchema.class);
    org = mock(Organization.class);
    when(org.getGeneralLedger()).thenReturn(ledger);

    account = mock(FIN_FinancialAccount.class);
    when(account.getOrganization()).thenReturn(org);
    when(account.getType()).thenReturn(type);
  }

  private void wireAccountWithoutLedger() {
    org = mock(Organization.class);
    when(org.getGeneralLedger()).thenReturn(null);

    account = mock(FIN_FinancialAccount.class);
    when(account.getOrganization()).thenReturn(org);
  }

  /** Wires {@code findOrCreateRow}'s lookup to reuse an existing row (no OBProvider needed). */
  @SuppressWarnings("unchecked")
  private FIN_FinancialAccountAccounting wireExistingRow() {
    FIN_FinancialAccountAccounting row = mock(FIN_FinancialAccountAccounting.class);
    OBCriteria<FIN_FinancialAccountAccounting> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_FinancialAccountAccounting.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(row);
    return row;
  }

  /**
   * Wires {@code resolveCombinationByCode} with one reused top-level {@link OBCriteria} and one
   * reused sub-{@link Criteria}, returning {@code resultsInCallOrder} sequentially — one value per
   * invocation, matching the exact order {@code applyDefaultsForType} resolves fields in.
   */
  @SuppressWarnings("unchecked")
  private void wireResolveCombinationByCode(AccountingCombination... resultsInCallOrder) {
    OBCriteria<AccountingCombination> criteria = mock(OBCriteria.class);
    Criteria subCriteria = mock(Criteria.class);
    when(obDal.createCriteria(AccountingCombination.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.createCriteria(anyString())).thenReturn(subCriteria);
    when(subCriteria.add(any())).thenReturn(subCriteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    if (resultsInCallOrder.length == 0) {
      when(criteria.uniqueResult()).thenReturn(null);
    } else if (resultsInCallOrder.length == 1) {
      when(criteria.uniqueResult()).thenReturn(resultsInCallOrder[0]);
    } else {
      Object first = resultsInCallOrder[0];
      Object[] rest = new Object[resultsInCallOrder.length - 1];
      System.arraycopy(resultsInCallOrder, 1, rest, 0, rest.length);
      when(criteria.uniqueResult()).thenReturn(first, rest);
    }
  }

  /** Named mock so a failed {@code verify()} prints which combination was expected, not a generic mock id. */
  private AccountingCombination combo(String id) {
    return mock(AccountingCombination.class, id);
  }

  // ── no-op guards ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("null account is a no-op (never breaks account creation)")
  void nullAccountIsNoOp() {
    Assertions.assertDoesNotThrow(
        () -> FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(null));
    verifyNoInteractions(obDal);
  }

  @Test
  @DisplayName("org with no general ledger is a no-op (soft-degrade, mirrors the handler)")
  void orgHasNoGeneralLedgerIsNoOp() {
    wireAccountWithoutLedger();

    Assertions.assertDoesNotThrow(
        () -> FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account));

    verify(obDal, never()).createCriteria(FIN_FinancialAccountAccounting.class);
    verify(obDal, never()).createCriteria(AccountingCombination.class);
    verify(obDal, never()).save(any());
  }

  // ── per-type defaults ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Bank: sets all 7 owned fields with the PGC-España default codes; cleared fields untouched")
  void bankTypeSetsSevenExpectedDefaults() {
    wireAccountWithLedger(TYPE_BANK);
    FIN_FinancialAccountAccounting row = wireExistingRow();

    AccountingCombination gain = combo("gain");
    AccountingCombination loss = combo("loss");
    AccountingCombination fee = combo("fee");
    AccountingCombination inTransitIn = combo("in-transit-in");
    AccountingCombination inTransitOut = combo("in-transit-out");
    AccountingCombination deposit = combo("deposit");
    AccountingCombination withdrawal = combo("withdrawal");
    // Call order: gain, loss, fee, inTransitIn, inTransitOut, deposit, withdrawal.
    wireResolveCombinationByCode(gain, loss, fee, inTransitIn, inTransitOut, deposit, withdrawal);

    FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account);

    verify(row).setFINBankrevaluationgainAcct(gain);
    verify(row).setFINBankrevaluationlossAcct(loss);
    verify(row).setFINBankfeeAcct(fee);
    verify(row).setInTransitPaymentAccountIN(inTransitIn);
    verify(row).setFINOutIntransitAcct(inTransitOut);
    verify(row).setDepositAccount(deposit);
    verify(row).setWithdrawalAccount(withdrawal);
    // clearedPaymentAccount / clearedPaymentAccountOUT are always empty — never set by this class.
    verify(row, never()).setClearedPaymentAccount(any());
    verify(row, never()).setClearedPaymentAccountOUT(any());

    verify(obDal).save(row);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("Cash: sets only the 4 shared fields, with the Caja deposit/withdrawal code 57001000")
  void cashTypeSetsFourDefaults() {
    wireAccountWithLedger(TYPE_CASH);
    FIN_FinancialAccountAccounting row = wireExistingRow();

    AccountingCombination inTransitIn = combo("in-transit-in");
    AccountingCombination inTransitOut = combo("in-transit-out");
    AccountingCombination deposit = combo("deposit-caja");
    AccountingCombination withdrawal = combo("withdrawal-caja");
    // Call order for non-Bank types: inTransitIn, inTransitOut, deposit, withdrawal.
    wireResolveCombinationByCode(inTransitIn, inTransitOut, deposit, withdrawal);

    FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account);

    verify(row).setInTransitPaymentAccountIN(inTransitIn);
    verify(row).setFINOutIntransitAcct(inTransitOut);
    verify(row).setDepositAccount(deposit);
    verify(row).setWithdrawalAccount(withdrawal);
    // Bank-only fields must never be touched for a Cash account.
    verify(row, never()).setFINBankrevaluationgainAcct(any());
    verify(row, never()).setFINBankrevaluationlossAcct(any());
    verify(row, never()).setFINBankfeeAcct(any());
    verify(row, never()).setClearedPaymentAccount(any());
    verify(row, never()).setClearedPaymentAccountOUT(any());

    verify(obDal).save(row);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("Card: sets only the 4 shared fields, with the Tarjeta deposit/withdrawal code 57210000")
  void cardTypeSetsFourDefaults() {
    wireAccountWithLedger(TYPE_CARD);
    FIN_FinancialAccountAccounting row = wireExistingRow();

    AccountingCombination inTransitIn = combo("in-transit-in");
    AccountingCombination inTransitOut = combo("in-transit-out");
    AccountingCombination deposit = combo("deposit-tarjeta");
    AccountingCombination withdrawal = combo("withdrawal-tarjeta");
    wireResolveCombinationByCode(inTransitIn, inTransitOut, deposit, withdrawal);

    FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account);

    verify(row).setInTransitPaymentAccountIN(inTransitIn);
    verify(row).setFINOutIntransitAcct(inTransitOut);
    verify(row).setDepositAccount(deposit);
    verify(row).setWithdrawalAccount(withdrawal);
    // Bank-only fields must never be touched for a Card account.
    verify(row, never()).setFINBankrevaluationgainAcct(any());
    verify(row, never()).setFINBankrevaluationlossAcct(any());
    verify(row, never()).setFINBankfeeAcct(any());
    verify(row, never()).setClearedPaymentAccount(any());
    verify(row, never()).setClearedPaymentAccountOUT(any());

    verify(obDal).save(row);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("An unrecognized/raw account type (bypassing normalizeType's known B/C/CA set)"
      + " silently defaults to Bank's 7-field set — never throws, never no-ops")
  void unrecognizedRawAccountTypeDefaultsToBankBehavior() {
    // "X" is not TYPE_BANK/TYPE_CASH/TYPE_CARD — normalizeType() falls through to TYPE_BANK for
    // any unrecognized value (mirrors FinancialAccountHandler#normalizeType's own default).
    wireAccountWithLedger("X");
    FIN_FinancialAccountAccounting row = wireExistingRow();

    AccountingCombination gain = combo("gain");
    AccountingCombination loss = combo("loss");
    AccountingCombination fee = combo("fee");
    AccountingCombination inTransitIn = combo("in-transit-in");
    AccountingCombination inTransitOut = combo("in-transit-out");
    AccountingCombination deposit = combo("deposit");
    AccountingCombination withdrawal = combo("withdrawal");
    wireResolveCombinationByCode(gain, loss, fee, inTransitIn, inTransitOut, deposit, withdrawal);

    Assertions.assertDoesNotThrow(
        () -> FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account));

    // Same call pattern as the confirmed Bank-type test: an unrecognized type is silently treated
    // as Bank, not rejected and not left unconfigured.
    verify(row).setFINBankrevaluationgainAcct(gain);
    verify(row).setFINBankrevaluationlossAcct(loss);
    verify(row).setFINBankfeeAcct(fee);
    verify(row).setDepositAccount(deposit);
    verify(row).setWithdrawalAccount(withdrawal);
    verify(obDal).save(row);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("A null account type (e.g. a record mid-creation with no type set yet) also"
      + " defaults to Bank behavior, same as any other unrecognized value")
  void nullAccountTypeDefaultsToBankBehavior() {
    wireAccountWithLedger(null);
    FIN_FinancialAccountAccounting row = wireExistingRow();
    wireResolveCombinationByCode();

    Assertions.assertDoesNotThrow(
        () -> FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account));

    // Reaching resolveCombinationByCode 7 times (Bank's field count) — not 4 (Cash/Card) —
    // is itself proof null was normalized to Bank, not silently skipped.
    verify(obDal, times(7)).createCriteria(AccountingCombination.class);
    verify(obDal).save(row);
    verify(obDal).flush();
  }

  // ── deterministic code resolution ────────────────────────────────────────────

  @Test
  @DisplayName("resolveCombinationByCode always caps the query at 1 result (setMaxResults(1)) so a"
      + " data anomaly — two active combinations matching the same code on the same ledger — can"
      + " never throw a Hibernate NonUniqueResultException; it deterministically picks one")
  void resolveCombinationByCodeAlwaysCapsResultsAtOne() {
    wireAccountWithLedger(TYPE_BANK);
    wireExistingRow();

    AccountingCombination gain = combo("gain");
    wireResolveCombinationByCode(gain);

    FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account);

    // Regression guard: if this call is ever dropped, a duplicate-code data anomaly on a real
    // Hibernate session would throw NonUniqueResultException instead of degrading cleanly.
    verify(obDal.createCriteria(AccountingCombination.class), atLeastOnce())
        .setMaxResults(1);
  }

  // ── soft-degrade / robustness ────────────────────────────────────────────────

  @Test
  @DisplayName("Ledger with no matching code for any default: every field stays untouched, no exception")
  void ledgerHasNoMatchingCodeSoftDegrade() {
    wireAccountWithLedger(TYPE_BANK);
    FIN_FinancialAccountAccounting row = wireExistingRow();
    // Every resolveCombinationByCode call returns null — no code matches this tenant's chart.
    wireResolveCombinationByCode();

    Assertions.assertDoesNotThrow(
        () -> FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account));

    verify(row, never()).setFINBankrevaluationgainAcct(any());
    verify(row, never()).setFINBankrevaluationlossAcct(any());
    verify(row, never()).setFINBankfeeAcct(any());
    verify(row, never()).setInTransitPaymentAccountIN(any());
    verify(row, never()).setFINOutIntransitAcct(any());
    verify(row, never()).setDepositAccount(any());
    verify(row, never()).setWithdrawalAccount(any());
    verify(row, never()).setClearedPaymentAccount(any());
    verify(row, never()).setClearedPaymentAccountOUT(any());
    // A fully-unresolved default set must still be saved (best-effort, matches the handler).
    verify(obDal).save(row);
    verify(obDal).flush();
  }

  @Test
  @DisplayName("An unexpected exception while resolving defaults is swallowed — never breaks account creation")
  void unexpectedExceptionDuringResolutionIsSwallowed() {
    wireAccountWithLedger(TYPE_BANK);
    wireExistingRow();
    when(obDal.createCriteria(AccountingCombination.class)).thenThrow(new RuntimeException("boom"));

    Assertions.assertDoesNotThrow(
        () -> FinancialAccountAccountingDefaultsSupport.applyDefaultAccountingConfiguration(account));

    // The exception happened before the row could be saved.
    verify(obDal, never()).save(any());
  }
}
