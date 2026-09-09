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

package com.etendoerp.go.schemaforge.handlers;

import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * ETP-4872 — auto-defaults the {@code accountingConfiguration} row (PGC España baseline) right
 * after a new {@link FIN_FinancialAccount} is created, so a Bank/Cash/Card account is
 * accounting-ready without the user having to open the "Contabilidad" tab and fill in every
 * account manually.
 *
 * <p>Invoked from {@code FinancialAccountHandler.afterHandle}'s POST branch, alongside (and after)
 * {@code FinancialAccountSupport.assignDefaultPaymentMethods(account)} — same "never break account
 * creation" contract: every failure here is best-effort and silently degrades (a missing ledger, or
 * a chart that lacks one of the default codes below, simply leaves the corresponding field null).
 *
 * <p><b>Deliberately duplicates</b> {@code FinancialAccountAccountingHandler#findOrCreateRow}'s
 * ~10 lines locally instead of extracting a shared helper: this class and that handler are two
 * independently-dispatchable ETP-4872 tasks (Task 1 vs Task 3) with disjoint file scopes, and this
 * class must not start touching {@code FinancialAccountAccountingHandler.java}. If the duplication
 * bothers review, propose the extraction as a follow-up cleanup once both tasks have merged.
 *
 * <p><b>Default account codes</b> (PGC España, verified directly against
 * {@code referencedata/sampledata/GOClient/C_ELEMENTVALUE.xml} on the live element chain,
 * {@code C_ELEMENT_ID = BB9B64C5B6534A40A36F7C0F45C2CC0B} — do NOT assume a uniform zero-padding
 * rule, two of these needed correction from the plan's naive guess):
 *
 * <ul>
 *   <li>{@code fINBankrevaluationgainAcct} (Banco only) → {@code 76800000} ("Diferencias positivas
 *       de cambio")</li>
 *   <li>{@code fINBankrevaluationlossAcct} (Banco only) → {@code 66800000} ("Diferencias negativas
 *       de cambio")</li>
 *   <li>{@code fINBankfeeAcct} (Banco only) → {@code 62600000} ("Servicios bancarios y
 *       similares")</li>
 *   <li>{@code inTransitPaymentAccountIN} / {@code fINOutIntransitAcct} (all types) →
 *       {@code 55500000} ("Partidas pendientes de aplicación")</li>
 *   <li>{@code depositAccount} / {@code withdrawalAccount}:
 *     <ul>
 *       <li>Banco → {@code 57200000} ("Bancos e instituciones de crédito c/c vista euros")</li>
 *       <li>Caja → <b>{@code 57001000}</b> ("Caja euros") — <b>not</b> {@code 5700} nor
 *           {@code 57000000}: {@code 5700} is a summary/group node ({@code ISSUMMARY='Y'}, no
 *           {@code C_VALIDCOMBINATION} row) with a single postable child leaf, {@code 57001000}
 *           ({@code C_VALIDCOMBINATION.COMBINATION = 57001}). Using {@code 5700} directly would
 *           silently resolve to nothing on every Caja account.</li>
 *       <li>Tarjeta → {@code 57210000} ("Tarjetas de crédito, euros") — provisioned by the sibling
 *           ETP-4872 Task 5 (branch {@code feat/ledger-account-57210}, not yet merged as of this
 *           task); this code will resolve to {@code null} until that branch lands and a tenant's
 *           chart actually carries the account — expected, not a bug in this class.</li>
 *     </ul>
 *   </li>
 *   <li>{@code clearedPaymentAccount} / {@code clearedPaymentAccountOUT} → always empty for every
 *       account type (confirmed in the ticket's own default tables) — never set here.</li>
 * </ul>
 */
public final class FinancialAccountAccountingDefaultsSupport {

  private static final Logger log = LogManager.getLogger(FinancialAccountAccountingDefaultsSupport.class);

  private static final String TYPE_BANK = "B";
  private static final String TYPE_CASH = "C";
  private static final String TYPE_CARD = "CA";

  private static final String CODE_BANK_REVAL_GAIN = "76800000";
  private static final String CODE_BANK_REVAL_LOSS = "66800000";
  private static final String CODE_BANK_FEE = "62600000";
  private static final String CODE_IN_TRANSIT = "55500000";
  private static final String CODE_DEPOSIT_WITHDRAWAL_BANK = "57200000";
  private static final String CODE_DEPOSIT_WITHDRAWAL_CASH = "57001000";
  private static final String CODE_DEPOSIT_WITHDRAWAL_CARD = "57210000";

  private FinancialAccountAccountingDefaultsSupport() {
    // static utility class
  }

  /**
   * Best-effort: resolves the account's own-org ledger and, when present, finds-or-creates its
   * {@code accountingConfiguration} row and sets every default field the account's type owns.
   * A field whose default code does not resolve to an active {@link AccountingCombination} on this
   * tenant's ledger (e.g. a non-PGC-España chart) is simply left {@code null} — this must never
   * throw or otherwise interrupt account creation.
   *
   * @param account the newly created (or updated) financial account to default; {@code null} is a no-op
   */
  public static void applyDefaultAccountingConfiguration(FIN_FinancialAccount account) {
    if (account == null) {
      return;
    }
    try {
      AcctSchema ledger = resolveOwnLedger(account);
      if (ledger == null) {
        return;
      }
      FIN_FinancialAccountAccounting row = findOrCreateRow(account, ledger);
      applyDefaultsForType(row, ledger, normalizeType(account.getType()));
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      log.error("financial-account: failed to auto-default accounting configuration for account {}",
          account.getId(), e);
    }
  }

  private static void applyDefaultsForType(FIN_FinancialAccountAccounting row, AcctSchema ledger,
      String type) {
    if (TYPE_BANK.equals(type)) {
      applyIfResolved(row::setFINBankrevaluationgainAcct, CODE_BANK_REVAL_GAIN, ledger);
      applyIfResolved(row::setFINBankrevaluationlossAcct, CODE_BANK_REVAL_LOSS, ledger);
      applyIfResolved(row::setFINBankfeeAcct, CODE_BANK_FEE, ledger);
    }
    applyIfResolved(row::setInTransitPaymentAccountIN, CODE_IN_TRANSIT, ledger);
    applyIfResolved(row::setFINOutIntransitAcct, CODE_IN_TRANSIT, ledger);

    String depositWithdrawalCode = depositWithdrawalCodeForType(type);
    applyIfResolved(row::setDepositAccount, depositWithdrawalCode, ledger);
    applyIfResolved(row::setWithdrawalAccount, depositWithdrawalCode, ledger);
    // clearedPaymentAccount / clearedPaymentAccountOUT: always empty per the ticket's own default
    // tables, for every account type — intentionally never set here.
  }

  private static String depositWithdrawalCodeForType(String type) {
    if (TYPE_CASH.equals(type)) {
      return CODE_DEPOSIT_WITHDRAWAL_CASH;
    }
    if (TYPE_CARD.equals(type)) {
      return CODE_DEPOSIT_WITHDRAWAL_CARD;
    }
    return CODE_DEPOSIT_WITHDRAWAL_BANK;
  }

  /** Resolves {@code code} and sets it via {@code setter} only when a matching, active
   *  {@link AccountingCombination} exists on {@code ledger}; otherwise the field is left untouched
   *  (i.e. null on a freshly-created row). */
  private static void applyIfResolved(Consumer<AccountingCombination> setter, String code, AcctSchema ledger) {
    AccountingCombination combo = resolveCombinationByCode(code, ledger);
    if (combo != null) {
      setter.accept(combo);
    }
  }

  /**
   * Resolves the active {@link AccountingCombination} for a plain account code
   * ({@code C_ElementValue.VALUE} / {@link ElementValue#PROPERTY_SEARCHKEY}) on the given ledger,
   * or {@code null} when none exists — a missing default must never fail account creation.
   */
  private static AccountingCombination resolveCombinationByCode(String code, AcctSchema ledger) {
    OBCriteria<AccountingCombination> criteria = OBDal.getInstance().createCriteria(AccountingCombination.class);
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNTINGSCHEMA, ledger));
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACTIVE, true));
    criteria.createCriteria(AccountingCombination.PROPERTY_ACCOUNT)
        .add(Restrictions.eq(ElementValue.PROPERTY_SEARCHKEY, code));
    criteria.setMaxResults(1);
    return (AccountingCombination) criteria.uniqueResult();
  }

  /**
   * Mirrors {@code FinancialAccountAccountingHandler#resolveOwnLedger}: the accounting
   * configuration belongs to the account's own organization tree, not the caller's session org.
   */
  private static AcctSchema resolveOwnLedger(FIN_FinancialAccount account) {
    Organization org = account.getOrganization();
    return org != null ? org.getGeneralLedger() : null;
  }

  /**
   * Duplicated locally from {@code FinancialAccountAccountingHandler#findOrCreateRow} — see the
   * class Javadoc for why this is not extracted into a shared helper.
   */
  private static FIN_FinancialAccountAccounting findOrCreateRow(FIN_FinancialAccount account, AcctSchema ledger) {
    OBCriteria<FIN_FinancialAccountAccounting> criteria =
        OBDal.getInstance().createCriteria(FIN_FinancialAccountAccounting.class);
    criteria.add(Restrictions.eq(FIN_FinancialAccountAccounting.PROPERTY_ACCOUNT, account));
    criteria.add(Restrictions.eq(FIN_FinancialAccountAccounting.PROPERTY_ACCOUNTINGSCHEMA, ledger));
    criteria.setMaxResults(1);
    FIN_FinancialAccountAccounting row = (FIN_FinancialAccountAccounting) criteria.uniqueResult();
    if (row != null) {
      return row;
    }
    row = OBProvider.getInstance().get(FIN_FinancialAccountAccounting.class);
    row.setNewOBObject(true);
    row.setClient(account.getClient());
    row.setOrganization(account.getOrganization());
    row.setAccount(account);
    row.setAccountingSchema(ledger);
    return row;
  }

  /** Mirrors {@code FinancialAccountHandler#normalizeType}: unknown/blank types default to Bank. */
  private static String normalizeType(String type) {
    if (TYPE_CASH.equals(type)) {
      return TYPE_CASH;
    }
    if (TYPE_CARD.equals(type)) {
      return TYPE_CARD;
    }
    return TYPE_BANK;
  }
}
