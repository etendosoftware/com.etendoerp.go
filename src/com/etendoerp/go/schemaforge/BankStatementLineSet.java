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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  /**
   * What an unmatched line carries that a delete-and-recreate must not lose (ETP-5581): the bank's
   * transaction id (without it the next PSD2 synchronization imports the movement a second time),
   * the PSD2 discard flag and button, and the bounce-pair link of this module. Plain values only:
   * the line it was read from is deleted before they are written back.
   *
   * @param transactionIdentifier the PSD2 transaction id, or {@code null}
   * @param discarded             the PSD2 discard flag
   * @param discardButton         the PSD2 Discard / Restore button value
   * @param pairLineId            the id of the bounce-pair counterpart, or {@code null}
   * @param bounceDiscardedAt     when the pair was discarded, or {@code null}
   * @param bounceSignal          the pair's primary signal, or {@code null}
   * @param bounceConfidence      the pair's confidence, or {@code null}
   */
  record BankIdentity(String transactionIdentifier, boolean discarded, String discardButton,
      String pairLineId, Date bounceDiscardedAt, String bounceSignal, String bounceConfidence) {

    /**
     * Whether there is nothing to keep.
     *
     * @return {@code true} for a line with no bank id, no discard and no pair
     */
    boolean isEmpty() {
      return transactionIdentifier == null && !discarded && pairLineId == null;
    }
  }

  /**
   * The {@link BankIdentity} of every UNMATCHED line of {@code statement} that has something to
   * keep, by line id — i.e. of exactly the lines {@link #deleteUnmatched} is about to remove.
   * {@code ?action=update} reads it before deleting, so a rebuilt line that carries its old
   * {@code "id"} gets it back (reused from the first iteration, commit {@code 1a5140cb9}, without
   * its ignored-transactions registry, which no longer exists).
   *
   * @param statement the statement being edited
   * @return the identities by old line id
   */
  static Map<String, BankIdentity> bankIdentitiesOfUnmatched(FIN_BankStatement statement) {
    OBCriteria<FIN_BankStatementLine> crit = linesOf(statement);
    crit.add(Restrictions.isNull(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION));
    Map<String, BankIdentity> identities = new HashMap<>();
    for (FIN_BankStatementLine line : crit.list()) {
      Object pair = StatementLineDiscard.get(line, BankBouncePairService.COL_PAIR_LINE);
      Object button = StatementLineDiscard.get(line, StatementLineDiscard.COL_DISCARD_LINE);
      BankIdentity identity = new BankIdentity(StatementLineDiscard.transactionIdentifier(line),
          StatementLineDiscard.isDiscarded(line), button != null ? button.toString() : null,
          pair instanceof FIN_BankStatementLine ? ((FIN_BankStatementLine) pair).getId() : null,
          (Date) StatementLineDiscard.get(line, BankBouncePairService.COL_DISCARDED_AT),
          (String) StatementLineDiscard.get(line, BankBouncePairService.COL_SIGNAL),
          (String) StatementLineDiscard.get(line, BankBouncePairService.COL_CONFIDENCE));
      if (!identity.isEmpty()) {
        identities.put(line.getId(), identity);
      }
    }
    return identities;
  }

  /**
   * Gives a rebuilt line back what the line it replaces carried: the PSD2 transaction id, the
   * discard flag and button (written as captured, so they stay in sync as PSD2 keeps them) and the
   * bounce signal, confidence and discard time. The pair link itself is restored afterwards by
   * {@link #relinkBouncePairs}, once every line has its new id. No-op when {@code identity} is
   * {@code null} (the body line is new, or its old line had nothing to keep). Not saved.
   *
   * @param line     the rebuilt line
   * @param identity what its old line carried, or {@code null}
   */
  static void restoreBankIdentity(FIN_BankStatementLine line, BankIdentity identity) {
    if (identity == null) {
      return;
    }
    if (identity.transactionIdentifier() != null) {
      StatementLineDiscard.set(line, StatementLineDiscard.COL_TRANSACTION_IDENTIFIER,
          identity.transactionIdentifier());
    }
    if (identity.discarded() && StatementLineDiscard.isSupported()) {
      StatementLineDiscard.set(line, StatementLineDiscard.COL_IS_DISCARDED, true);
      StatementLineDiscard.set(line, StatementLineDiscard.COL_DISCARD_LINE,
          identity.discardButton() != null ? identity.discardButton()
              : StatementLineDiscard.BUTTON_DISCARDED);
    }
    if (identity.pairLineId() != null) {
      StatementLineDiscard.set(line, BankBouncePairService.COL_DISCARDED_AT,
          identity.bounceDiscardedAt());
      StatementLineDiscard.set(line, BankBouncePairService.COL_SIGNAL, identity.bounceSignal());
      StatementLineDiscard.set(line, BankBouncePairService.COL_CONFIDENCE,
          identity.bounceConfidence());
    }
  }

  /**
   * Restores the bounce-pair links of the rebuilt lines, now that each has its new id. A pair whose
   * two lines were both rebuilt is linked between the new lines. A pair whose counterpart lives in
   * another statement (still there, its link set to null by the foreign key when the old line was
   * deleted) is linked again in both directions. A counterpart that no longer exists leaves the
   * rebuilt line discarded with no link, as a deleted counterpart always does. Saved, not flushed.
   *
   * @param identities    what the deleted lines carried, by old id
   * @param rebuiltByOldId the rebuilt lines, by the old id their body line carried
   */
  static void relinkBouncePairs(Map<String, BankIdentity> identities,
      Map<String, FIN_BankStatementLine> rebuiltByOldId) {
    for (Map.Entry<String, FIN_BankStatementLine> rebuilt : rebuiltByOldId.entrySet()) {
      BankIdentity identity = identities.get(rebuilt.getKey());
      if (identity != null && identity.pairLineId() != null) {
        relinkOne(rebuilt.getValue(), identity.pairLineId(), rebuiltByOldId);
      }
    }
  }

  /**
   * Links one rebuilt line to its counterpart: the rebuilt line its old counterpart became, or —
   * when the counterpart was not rebuilt (it lives in another statement) — that line again, in
   * both directions. A counterpart that no longer exists leaves the line unlinked. Saved, not
   * flushed.
   *
   * @param rebuilt        the rebuilt line
   * @param pairLineId     the old id of its counterpart
   * @param rebuiltByOldId the rebuilt lines, by the old id their body line carried
   */
  private static void relinkOne(FIN_BankStatementLine rebuilt, String pairLineId,
      Map<String, FIN_BankStatementLine> rebuiltByOldId) {
    FIN_BankStatementLine counterpart = rebuiltByOldId.get(pairLineId);
    boolean counterpartKept = counterpart == null;
    if (counterpartKept) {
      // tenant-ok: the id was read off a line of the caller's own, tenant-resolved statement
      counterpart = OBDal.getInstance().get(FIN_BankStatementLine.class, pairLineId);
    }
    if (counterpart == null) {
      return;
    }
    StatementLineDiscard.set(rebuilt, BankBouncePairService.COL_PAIR_LINE, counterpart);
    OBDal.getInstance().save(rebuilt);
    if (counterpartKept) {
      StatementLineDiscard.set(counterpart, BankBouncePairService.COL_PAIR_LINE, rebuilt);
      OBDal.getInstance().save(counterpart);
    }
  }

  /** Queues the removals and flushes once, as the original per-method loops did. */
  private static void remove(List<FIN_BankStatementLine> lines) {
    for (FIN_BankStatementLine line : lines) {
      OBDal.getInstance().remove(line);
    }
    OBDal.getInstance().flush();
  }
}
