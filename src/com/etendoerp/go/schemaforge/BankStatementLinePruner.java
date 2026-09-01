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
 * All portions are Copyright © 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Post-parse sanitisation of imported bank statement lines.
 *
 * <p>Ports the sanitising half of Etendo Classic's
 * {@code org.openbravo.advpaymentmngt.utility.FIN_BankStatementImport#saveFINBankStatementLines}:
 * a statement line whose credit AND debit amounts are both zero carries no
 * financial information, so Classic removes it and renumbers the survivors
 * {@code (counter + 1) * 10} — leaving no gap in {@code lineno}. It then reports
 * how many rows were dropped through the {@code APRM_ZeroAmountNotInserted}
 * message.
 *
 * <p>It deliberately operates on the already-persisted lines of a statement
 * rather than inside a specific parser, exactly like Classic does: the rule
 * lives above the importer so it applies to every format (generic CSV and
 * Cuaderno 43 alike).
 *
 * <p>The manual-creation flow does NOT go through here — there a zero/zero line
 * is a validation error the user must fix, not something to silently drop. See
 * {@link BankStatementsHandler} {@code createLines}.
 */
final class BankStatementLinePruner {

  private BankStatementLinePruner() {
  }

  /**
   * Outcome of a prune pass: how many lines survived and how many were dropped
   * for carrying no amount at all.
   */
  static final class PruneResult {
    private final int kept;
    private final int discarded;

    PruneResult(int kept, int discarded) {
      this.kept = kept;
      this.discarded = discarded;
    }

    /** @return number of lines left attached to the statement */
    int getKept() {
      return kept;
    }

    /** @return number of lines removed because both amounts were zero */
    int getDiscarded() {
      return discarded;
    }
  }

  /**
   * Removes every line of {@code statement} whose {@code cramount} and
   * {@code dramount} are both zero, then renumbers the survivors 10, 20, 30…
   * in line-number order so the discarded rows leave no gap. Mirrors Classic's
   * behaviour, including the fact that a partially pruned import is still a
   * successful import.
   *
   * <p>The caller must have flushed the parsed lines first — this reads them
   * back from the DB.
   *
   * @param statement the statement whose lines were just parsed and saved
   * @return the kept / discarded counts
   */
  static PruneResult pruneZeroAmountLines(FIN_BankStatement statement) {
    List<FIN_BankStatementLine> lines = readLines(statement);
    if (lines.isEmpty()) {
      return new PruneResult(0, 0);
    }

    List<FIN_BankStatementLine> discarded = new ArrayList<>();
    long counter = 0L;
    for (FIN_BankStatementLine line : lines) {
      if (hasNoAmount(line)) {
        discarded.add(line);
        continue;
      }
      counter++;
      line.setLineNo(counter * 10L);
      OBDal.getInstance().save(line);
    }

    for (FIN_BankStatementLine line : discarded) {
      OBDal.getInstance().remove(line);
    }
    OBDal.getInstance().flush();

    return new PruneResult((int) counter, discarded.size());
  }

  /**
   * Reads the statement's lines straight from the DB, ordered by line number.
   *
   * <p>Deliberately NOT {@code statement.getFINBankStatementLineList()}: neither
   * parser refreshes that collection after its own {@code OBDal.save(line)}, so
   * it can come back empty even though the rows are physically there — the same
   * trap {@code BankStatementsHandler#handlePreview} documents for the preview
   * payload. Reading it back would make a perfectly valid file look like it had
   * no lines at all.
   *
   * @param statement the statement whose lines were just parsed and flushed
   * @return the persisted lines, oldest line number first (never {@code null})
   */
  private static List<FIN_BankStatementLine> readLines(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    crit.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_BANKSTATEMENT, statement));
    crit.addOrderBy(FIN_BankStatementLine.PROPERTY_LINENO, true);
    crit.setFilterOnReadableOrganization(false);
    List<FIN_BankStatementLine> lines = crit.list();
    return lines == null ? new ArrayList<>() : lines;
  }

  private static boolean hasNoAmount(FIN_BankStatementLine line) {
    return isZero(line.getCramount()) && isZero(line.getDramount());
  }

  private static boolean isZero(BigDecimal amount) {
    return amount == null || amount.compareTo(BigDecimal.ZERO) == 0;
  }
}
