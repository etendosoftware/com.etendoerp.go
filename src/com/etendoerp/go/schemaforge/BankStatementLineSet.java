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

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Set operations over the {@link FIN_BankStatementLine} rows of one {@link FIN_BankStatement}:
 * count them, find the highest line number, delete all or only the unmatched ones.
 *
 * <p>Extracted from {@code BankStatementsHandler} (ETP-4921), which had grown past Sonar's
 * 35-method ceiling (java:S1448) once the reactivation work added four of these. They are a
 * genuine unit rather than an arbitrary slice: every one of them builds the same
 * "lines of THIS statement" criteria and differs only in the extra restriction and what it does
 * with the result — so the boilerplate that was copy-pasted five times now lives once, in
 * {@link #linesOf}.
 *
 * <p>"Matched" throughout means the line has a linked {@code financialAccountTransaction}. Core's
 * {@code APRM_FIN_BNKSTM_LINE_CHECK_TRG} trigger rejects any insert, update or delete of such a
 * line — for every caller, and independently of the parent statement's Processed flag — which is
 * why several of these methods exist at all.
 */
final class BankStatementLineSet {

  private BankStatementLineSet() {
  }

  /** Criteria over every line of {@code statement}; callers narrow it further. */
  private static OBCriteria<FIN_BankStatementLine> linesOf(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    crit.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_BANKSTATEMENT, statement));
    return crit;
  }

  /** Narrows {@code crit} to the ACTIVE lines already matched to a transaction. */
  private static OBCriteria<FIN_BankStatementLine> activeMatched(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit = linesOf(statement);
    crit.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_ACTIVE, true));
    crit.add(Restrictions.isNotNull(
        FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION));
    crit.setFilterOnReadableOrganization(false);
    return crit;
  }

  /**
   * Whether {@code statement} has at least one active matched line. Guards
   * {@code ?action=delete}: deleting the whole statement would take those lines with it, which
   * the core trigger never allows. {@code ?action=reactivate} deliberately does NOT use this.
   */
  static boolean hasMatched(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit = activeMatched(statement);
    crit.setMaxResults(1);
    return !crit.list().isEmpty();
  }

  /**
   * How many active matched lines {@code statement} has. {@code ?action=update} needs the real
   * count — the lines it keeps, added to the ones it rebuilt — so unlike {@link #hasMatched}
   * this has no early exit.
   */
  static int countMatched(FIN_BankStatement statement) {
    return activeMatched(statement).list().size();
  }

  /**
   * Removes EVERY line of {@code statement}. Only for {@code ?action=delete}, whose caller has
   * already established via {@link #hasMatched} that none of them is matched. Do not reuse it
   * for an update — see {@link #deleteUnmatched}.
   */
  static void deleteAll(FIN_BankStatement statement) {
    remove(linesOf(statement).list());
  }

  /**
   * Removes only the UNMATCHED lines, leaving the matched ones physically untouched. This is
   * what makes {@code ?action=update} safe on a reactivated statement that still carries matched
   * lines: they never become a candidate for deletion, so the core trigger has nothing to reject.
   * Deleting them and recreating them would fail loudly, and mid-batch at that — the removes are
   * queued and flushed once at the end.
   */
  static void deleteUnmatched(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit = linesOf(statement);
    crit.add(Restrictions.isNull(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION));
    remove(crit.list());
  }

  /**
   * Highest {@code LineNo} currently attached to {@code statement}, or 0 when it has none.
   * {@code handleUpdate} numbers rebuilt lines past it so one cannot take a number a kept
   * matched line already holds. The create path has nothing to collide with and passes 0
   * instead of calling this, which would be a wasted query on every create.
   */
  static long maxLineNo(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit = linesOf(statement);
    crit.addOrderBy(FIN_BankStatementLine.PROPERTY_LINENO, false);
    crit.setMaxResults(1);
    List<FIN_BankStatementLine> top = crit.list();
    return top.isEmpty() ? 0L : top.get(0).getLineNo();
  }

  /** Queues the removals and flushes once, as the original per-method loops did. */
  private static void remove(List<FIN_BankStatementLine> lines) {
    for (FIN_BankStatementLine line : lines) {
      OBDal.getInstance().remove(line);
    }
    OBDal.getInstance().flush();
  }
}
