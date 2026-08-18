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

import java.util.ArrayList;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.gl.GLJournalLine;
import org.openbravo.model.financialmgmt.payment.BankFileException;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentProposal;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

import com.etendoerp.go.schemaforge.data.MatchRule;
import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.PSD2FinaccLog;

/**
 * Delete-blocker logic for {@link FinancialAccountHandler#deleteAccount} (ETP-4871): every FK from
 * another table into {@code FIN_Financial_Account} is {@code RESTRICT} (no cascade), so a hard
 * delete is only allowed once {@link #findDeleteBlockers} proves nothing depends on the account.
 * Extracted out of {@code FinancialAccountHandler} purely to keep that class under the Sonar
 * method-count threshold (java:S1448) — no behavior change; every check here does exactly what it
 * did as an instance method on the handler, just relocated and made {@code static}.
 *
 * <p>The account's own auto-created configuration rows (accounting setup, default payment
 * methods, matching rules, PSD2 sync log) are swept first via {@link #sweepOwnConfig} — those are
 * not blockers, every account has them from creation.
 */
final class FinancialAccountDeleteSupport {

  /* ---------------------------------------------------------------------------
   * Delete-blocker reason wording (ETP-4871). Package-private (not private) so
   * FinancialAccountsPageHandler's batched loader can reuse the exact same strings for the
   * `deleteBlockedReason` list field — the DELETE 409 message and the list-row tooltip must never
   * drift apart.
   * --------------------------------------------------------------------------- */
  static final String REASON_TRANSACTIONS = "This account has registered transactions.";
  static final String REASON_RECONCILIATIONS = "This account has reconciliations recorded.";
  static final String REASON_BANK_STATEMENTS = "This account has bank statements recorded.";
  static final String REASON_PAYMENTS = "This account has payments recorded.";
  static final String REASON_PAYMENT_PROPOSALS = "This account has payment proposals recorded.";
  static final String REASON_JOURNAL_LINES = "This account has GL journal entries recorded.";
  static final String REASON_BANK_FILE_EXCEPTIONS = "This account has bank file exceptions recorded.";
  static final String REASON_BPARTNER_DEFAULT =
      "This account is set as a business partner's default financial account.";
  static final String REASON_BANK_CONNECTION =
      "This account is connected to a bank — disconnect the bank first.";

  private FinancialAccountDeleteSupport() {
  }

  /**
   * Collects every reason a hard delete would fail, instead of stopping at the first one, so the
   * 409 message (and the batched list-view {@code deleteBlockedReason} built from the same
   * reasons — see {@code FinancialAccountsPageHandler#loadDeleteBlockersByAccount}) can name
   * everything blocking the account at once.
   *
   * <p>Unlike {@link FinancialAccountHandler#hasTransactions} (reused as-is here) and
   * {@link FinancialAccountHandler#hasOpenReconciliations} (a narrower, "open only" check used
   * solely by the archive guard), every other check here deliberately does NOT filter on
   * {@code active}: a {@code RESTRICT} FK blocks a hard delete regardless of whether the
   * referencing row is soft-deleted, so a delete must be refused even when the only reference left
   * is, say, an inactive bank connection or a closed reconciliation.
   *
   * @param hasTransactions
   *     the result of {@link FinancialAccountHandler#hasTransactions}, computed by the caller
   *     ({@code deleteAccount}) before invoking this static method. {@code hasTransactions} stays
   *     an instance method on {@code FinancialAccountHandler} (it is reused, narrower-scoped, by
   *     the currency-lock GET flag too), so the boolean is threaded through as a parameter instead
   *     of this method calling back into the handler — that keeps {@code deleteAccount}'s own
   *     {@code hasTransactions(account)} call going through {@code this} exactly as before the
   *     extraction, so a spy's {@code doReturn(...).when(handler).hasTransactions(account)} stub
   *     still works.
   */
  static List<String> findDeleteBlockers(FIN_FinancialAccount account, boolean hasTransactions) {
    List<String> reasons = new ArrayList<>();
    if (hasTransactions) {
      reasons.add(REASON_TRANSACTIONS);
    }
    if (hasAnyReconciliation(account)) {
      reasons.add(REASON_RECONCILIATIONS);
    }
    if (hasBankStatements(account)) {
      reasons.add(REASON_BANK_STATEMENTS);
    }
    if (hasPayments(account)) {
      reasons.add(REASON_PAYMENTS);
    }
    if (hasPaymentProposals(account)) {
      reasons.add(REASON_PAYMENT_PROPOSALS);
    }
    if (hasJournalLines(account)) {
      reasons.add(REASON_JOURNAL_LINES);
    }
    if (hasBankFileExceptions(account)) {
      reasons.add(REASON_BANK_FILE_EXCEPTIONS);
    }
    if (isDefaultBpartnerAccount(account)) {
      reasons.add(REASON_BPARTNER_DEFAULT);
    }
    if (hasBankConnection(account)) {
      reasons.add(REASON_BANK_CONNECTION);
    }
    return reasons;
  }

  /**
   * Removes the account's own auto-created configuration rows before the account itself is
   * deleted. These are NOT delete blockers: {@code FIN_FINANCIAL_ACCOUNT_ACCT} is inserted by the
   * {@code FIN_FINANCIAL_ACCOUNT_TRG} trigger on every account creation and
   * {@code FIN_FINACC_PAYMENTMETHOD} by {@code afterHandle}'s own POST branch — every account has
   * at least one row in both from the moment it is created, so treating either as a blocker would
   * make no account ever deletable. Matching rules and the PSD2 sync log are account-owned history
   * (not cross-entity references) and are swept the same way.
   */
  static void sweepOwnConfig(FIN_FinancialAccount account) {
    removeAll(FIN_FinancialAccountAccounting.class, FIN_FinancialAccountAccounting.PROPERTY_ACCOUNT, account);
    removeAll(FinAccPaymentMethod.class, FinAccPaymentMethod.PROPERTY_ACCOUNT, account);
    removeAll(MatchRule.class, MatchRule.PROPERTY_FINANCIALACCOUNT, account);
    removeAll(PSD2FinaccLog.class, PSD2FinaccLog.PROPERTY_FINANCIALACCOUNT, account);
  }

  // ---------------------------------------------------------------------------
  // Individual delete-blocker checks (package-private, one per FK, so unit tests can stub each
  // independently — same seam convention as hasTransactions/hasOpenReconciliations above).
  // ---------------------------------------------------------------------------

  static boolean hasAnyReconciliation(FIN_FinancialAccount account) {
    return hasAnyRow(FIN_Reconciliation.class, FIN_Reconciliation.PROPERTY_ACCOUNT, account);
  }

  static boolean hasBankStatements(FIN_FinancialAccount account) {
    return hasAnyRow(FIN_BankStatement.class, FIN_BankStatement.PROPERTY_ACCOUNT, account);
  }

  static boolean hasPayments(FIN_FinancialAccount account) {
    return hasAnyRow(FIN_Payment.class, FIN_Payment.PROPERTY_ACCOUNT, account);
  }

  static boolean hasPaymentProposals(FIN_FinancialAccount account) {
    return hasAnyRow(FIN_PaymentProposal.class, FIN_PaymentProposal.PROPERTY_ACCOUNT, account);
  }

  static boolean hasJournalLines(FIN_FinancialAccount account) {
    return hasAnyRow(GLJournalLine.class, GLJournalLine.PROPERTY_FINANCIALACCOUNT, account);
  }

  static boolean hasBankFileExceptions(FIN_FinancialAccount account) {
    return hasAnyRow(BankFileException.class, BankFileException.PROPERTY_FINANCIALACCOUNT, account);
  }

  /** A business partner can default to this account either as its regular or its PO account. */
  static boolean isDefaultBpartnerAccount(FIN_FinancialAccount account) {
    return hasAnyRow(BusinessPartner.class, BusinessPartner.PROPERTY_ACCOUNT, account)
        || hasAnyRow(BusinessPartner.class, BusinessPartner.PROPERTY_POFINANCIALACCOUNT, account);
  }

  static boolean hasBankConnection(FIN_FinancialAccount account) {
    return hasAnyRow(FinaccConnection.class, FinaccConnection.PROPERTY_FINANCIALACCOUNT, account);
  }

  /** Shared {@code OBCriteria} probe reused by every blocker check above: does at least one row
   *  of {@code entityClass} reference {@code account} through {@code fkProperty}? */
  private static <T extends BaseOBObject> boolean hasAnyRow(Class<T> entityClass, String fkProperty,
      FIN_FinancialAccount account) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(entityClass);
    criteria.add(Restrictions.eq(fkProperty, account));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  /** Deletes every row of {@code entityClass} referencing {@code account} through
   *  {@code fkProperty}. Same list-then-remove idiom as
   *  {@code PopulateSpecHelper.deleteExistingChildren}. */
  private static <T extends BaseOBObject> void removeAll(Class<T> entityClass, String fkProperty,
      FIN_FinancialAccount account) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(entityClass);
    criteria.add(Restrictions.eq(fkProperty, account));
    for (T row : criteria.list()) {
      OBDal.getInstance().remove(row);
    }
  }
}
