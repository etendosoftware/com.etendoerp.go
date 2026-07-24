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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;

import java.math.BigDecimal;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;

/**
 * Pure helpers backing the reconciliation <b>reactivate</b> (undo) flow of
 * {@link ReconciliationHandler} (T8 part 1). Extracted to keep the handler class under the Sonar
 * method-count limit and to group the side helpers of the reactivate feature in one place — the
 * orchestration seams ({@code reactivate}, {@code undoReconciliation},
 * {@code normalizeReactivatedMatchGroup}, {@code loadMatchGroupLines}) stay on the handler so the
 * unit tests can stub them.
 *
 * <p>It never reimplements reconciliation logic: it composes the
 * {@code com.etendoerp.payment.removal} module and the standard DAL.
 */
final class ReactivationSupport {

  private static final Logger log = LogManager.getLogger(ReactivationSupport.class);

  /** Module extension column holding the 1:N reconciliation group id (option B). */
  static final String COL_MATCH_GROUP = "EM_ETGO_Match_Group_ID";

  /** Module extension column flagging finacc transactions auto-created by the reconcile flow. */
  static final String COL_AUTO_CREATED = "EM_ETGO_Auto_Created";

  /** Module extension column holding a bank-statement line's amount still pending to reconcile. */
  static final String COL_PENDING_AMOUNT = "EM_ETGO_Pending_Amount";

  private ReactivationSupport() {
  }

  /**
   * Resolves an extension ({@code EM_*}) DAL property by entity + column name at runtime, without a
   * dependency on the generated accessor. Returns {@code null} when the model has not yet loaded the
   * column (so callers can degrade gracefully).
   */
  static Property extensionProperty(String entityName, String columnName) {
    Entity entity = ModelProvider.getInstance().getEntity(entityName);
    return entity.getPropertyByColumnName(columnName, false);
  }

  /** Reads the ETGO 1:N split marker from the bank-statement line, or {@code null} when absent. */
  static String readMatchGroupId(FIN_BankStatementLine line) {
    try {
      Property prop = extensionProperty(FIN_BankStatementLine.ENTITY_NAME, COL_MATCH_GROUP);
      if (prop == null || line == null) {
        return null;
      }
      Object value = line.get(prop.getName());
      return value != null ? StringUtils.trimToNull(String.valueOf(value)) : null;
    } catch (Exception e) {
      log.debug("Could not read match-group id on line {}: {}",
          line != null ? line.getId() : "<null>", e.getMessage());
      return null;
    }
  }

  /**
   * Flags a finacc transaction as auto-created by the reconcile flow on the
   * {@code EM_ETGO_Auto_Created} extension column. Resolves the DAL property by column name at
   * runtime (no dependency on the generated accessor) and degrades gracefully when the model has
   * not yet loaded the column. The reactivate flow reads it back (via the handler's
   * {@code isAutoCreated} seam) to decide which movements to fully delete.
   */
  static void markAutoCreated(FIN_FinaccTransaction trx) {
    try {
      Property prop = extensionProperty(FIN_FinaccTransaction.ENTITY_NAME, COL_AUTO_CREATED);
      if (prop != null) {
        trx.set(prop.getName(), Boolean.TRUE);
      } else {
        log.warn("Column {} not yet in the model; skipping auto-created flag", COL_AUTO_CREATED);
      }
    } catch (Exception e) {
      log.warn("Could not flag transaction {} as auto-created", trx.getId(), e);
    }
  }

  /** Clears the ETGO 1:N split marker from the line so it returns to the normal pending pool. */
  static void clearMatchGroupId(FIN_BankStatementLine line) {
    try {
      Property prop = extensionProperty(FIN_BankStatementLine.ENTITY_NAME, COL_MATCH_GROUP);
      if (prop != null && line != null) {
        line.set(prop.getName(), null);
      }
    } catch (Exception e) {
      log.warn("Could not clear match-group id on line {}", line != null ? line.getId() : "<null>", e);
    }
  }

  /**
   * Re-sets a kept transaction's "not cleared" status by DIRECTION. Confirmed empirically: the
   * module's {@code reactivateAndRemoveReconciliation} leaves deposits (receipts) in {@code PWNC}
   * instead of {@code RDNC} — its {@code unMachTransactionFromReconciliation} only keeps {@code RDNC}
   * when the status is still {@code RPPC}, but {@code reactivate(rec)} already moved it off
   * {@code RPPC}. A money inflow must return to {@code RDNC} (Deposited not cleared); an outflow to
   * {@code PWNC} (Withdrawn not cleared).
   */
  static void restoreNotClearedStatus(FIN_FinaccTransaction t) {
    boolean inflow = nullSafe(t.getDepositAmount()).compareTo(nullSafe(t.getPaymentAmount())) >= 0;
    String expected = inflow ? "RDNC" : "PWNC";
    if (!expected.equals(t.getStatus())) {
      t.setStatus(expected);
      OBDal.getInstance().save(t);
    }
  }

  /**
   * Clears the {@code financialAccountTransaction} link of the bank-statement line matched to
   * {@code trx}, returning the line to "not reconciled". Mirrors the module's private
   * {@code removeTransactionFromBankStatementLine} (not exposed publicly), which the
   * reconciliation-level undo does not run.
   */
  static void unmatchBankStatementLine(FIN_FinaccTransaction trx) {
    OBCriteria<FIN_BankStatementLine> c =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    c.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION, trx));
    c.setMaxResults(1);
    FIN_BankStatementLine bsl = (FIN_BankStatementLine) c.uniqueResult();
    if (bsl != null) {
      bsl.setFinancialAccountTransaction(null);
      OBDal.getInstance().save(bsl);
    }
  }

  /**
   * Current balance of the account after the reactivation: the ending balance of its most recent
   * remaining reconciliation, or {@code 0} when none remains. Decorative — never fails the response.
   */
  static BigDecimal currentBalance(FIN_FinancialAccount account) {
    try {
      List<FIN_Reconciliation> remaining = ReconciliationRemovalUtil.getDraftReconciliation(account);
      if (remaining != null && !remaining.isEmpty()) {
        return nullSafe(remaining.get(0).getEndingBalance());
      }
    } catch (Exception e) {
      log.debug("Could not compute updated balance for account {}: {}", account.getId(),
          e.getMessage());
    }
    return BigDecimal.ZERO;
  }

  /**
   * Returns the line acting as anchor of a reactivated split group: the selected {@code line} itself
   * when it is one of the {@code siblings}, otherwise the first sibling.
   */
  static FIN_BankStatementLine anchorOf(FIN_BankStatementLine line,
      List<FIN_BankStatementLine> siblings) {
    for (FIN_BankStatementLine sibling : siblings) {
      if (line.getId().equals(sibling.getId())) {
        return sibling;
      }
    }
    return line;
  }

  /**
   * True when an ETGO split group can be physically collapsed back into one line: every sibling must
   * live in the same bank statement and be unmatched. Logs and returns {@code false} on the first
   * blocking sibling (still linked to a transaction, or belonging to another statement).
   */
  static boolean canCollapse(FIN_BankStatementLine line, List<FIN_BankStatementLine> siblings) {
    for (FIN_BankStatementLine sibling : siblings) {
      if (sibling.getBankStatement() == null
          || !line.getBankStatement().getId().equals(sibling.getBankStatement().getId())) {
        log.warn("Skipping match-group normalization for line {}: sibling {} belongs to another statement",
            line.getId(), sibling.getId());
        return false;
      }
      if (sibling.getFinancialAccountTransaction() != null) {
        log.warn("Skipping match-group normalization for line {}: sibling {} is still linked to transaction {}",
            line.getId(), sibling.getId(), sibling.getFinancialAccountTransaction().getId());
        return false;
      }
    }
    return true;
  }

  /**
   * Collapses every unmatched sibling of an ETGO split group back into the {@code anchor} line: the
   * summed amounts are applied to the anchor, the other siblings are removed, and the anchor returns
   * to the normal pending pool (cleared transaction link / matching metadata / group marker). The
   * owning statement is briefly unprocessed so its lines can be mutated, then restored.
   */
  static void collapseSiblings(FIN_BankStatementLine anchor, List<FIN_BankStatementLine> siblings) {
    FIN_BankStatement statement = anchor.getBankStatement();
    boolean wasProcessed = Boolean.TRUE.equals(statement.isProcessed());
    statement.setProcessed(false);
    OBDal.getInstance().save(statement);
    OBDal.getInstance().flush();

    BigDecimal totalCredit = BigDecimal.ZERO;
    BigDecimal totalDebit = BigDecimal.ZERO;
    for (FIN_BankStatementLine sibling : siblings) {
      totalCredit = totalCredit.add(nullSafe(sibling.getCramount()));
      totalDebit = totalDebit.add(nullSafe(sibling.getDramount()));
    }
    for (FIN_BankStatementLine sibling : siblings) {
      if (!anchor.getId().equals(sibling.getId())) {
        OBDal.getInstance().remove(sibling);
      }
    }

    applyBankStatementAmounts(anchor, totalCredit, totalDebit);
    anchor.setFinancialAccountTransaction(null);
    anchor.setMatchingtype(null);
    anchor.setMatchedDocument(null);
    clearMatchGroupId(anchor);
    OBDal.getInstance().save(anchor);
    OBDal.getInstance().flush();

    statement.setProcessed(wasProcessed);
    OBDal.getInstance().save(statement);
    OBDal.getInstance().flush();
  }

  /** Applies the summed credit/debit back into a single line using Classic's sign normalization. */
  private static void applyBankStatementAmounts(FIN_BankStatementLine line, BigDecimal totalCredit,
      BigDecimal totalDebit) {
    if (totalCredit.compareTo(BigDecimal.ZERO) != 0 && totalDebit.compareTo(BigDecimal.ZERO) != 0) {
      BigDecimal total = totalCredit.subtract(totalDebit);
      if (total.compareTo(BigDecimal.ZERO) < 0) {
        line.setCramount(BigDecimal.ZERO);
        line.setDramount(total.abs());
      } else {
        line.setCramount(total);
        line.setDramount(BigDecimal.ZERO);
      }
    } else {
      line.setCramount(totalCredit);
      line.setDramount(totalDebit);
    }
  }
}
