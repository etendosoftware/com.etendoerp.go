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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

/**
 * Brings the "Reactivar" of a <b>reconciled</b> payment (status {@code RPPC}) in line with Core's
 * {@code APRM_MatchingUtility.unmatch} (ETP-5547). The reactivation itself is still delegated,
 * unchanged, to {@code com.etendoerp.payment.removal} ({@code PaymentRemovalUtil.reactivate}, action
 * {@code RE}); this class only adds what that module leaves out, split in two halves around the
 * delegation:
 *
 * <ol>
 *   <li>{@link #prepare(String)} — BEFORE delegating. Captures the bank-statement line matched to the
 *       payment's transaction (the detach clears the transaction&rarr;line pointer, so it cannot be
 *       found afterwards) and, when the payment method marks invoices paid at the "deposited /
 *       withdrawn, not cleared" level ({@code RDNC} / {@code PWNC}), moves the payment from
 *       {@code RPPC} to that status. See below for why.</li>
 *   <li>{@link #finish(Prepared)} — AFTER delegating. When the reactivation went through, clears the
 *       matching leftovers of the statement line ({@code matchingtype} / {@code matchedDocument},
 *       which Core's unmatch clears and the module does not) and re-collapses a split 1:N line by
 *       reusing {@link ReconciliationHandler#normalizeReactivatedMatchGroup} — the same cleanup
 *       {@code FinancialAccountTransactionsHandler#handleReactivate} runs (ETP-4951). When it did
 *       NOT go through, undoes the status change so a still-reconciled payment is not left
 *       labelled "not cleared".</li>
 * </ol>
 *
 * <p><b>Why the status step.</b> Core's {@code FIN_TransactionProcess} "R" gives the invoice its
 * outstanding back only when
 * {@code seqnumberpaymentstatus(payment.status) == seqnumberpaymentstatus(invoicePaidStatus)}, and
 * then forces the payment to {@code RPR}/{@code PPM}; the payment's own {@code RE} applies the same
 * equality with the payment's status at that point. With the module's flow the payment reaches the
 * transaction step still in {@code RPPC} (seq 60), so:
 * <ul>
 *   <li>paid status {@code RPR}/{@code PPM} (seq 40): the transaction step does not restore, the
 *       {@code RE} does (payment now {@code RPR}) — correct without help;</li>
 *   <li>paid status {@code RPPC} (seq 60): the transaction step restores — correct without help;</li>
 *   <li>paid status {@code RDNC}/{@code PWNC} (seq 50): NEITHER step matches (60 &ne; 50, then
 *       40 &ne; 50) — the payment returns to draft while its invoice still reads as paid. Core's own
 *       unmatch avoids this by setting the payment to {@code RDNC}/{@code PWNC} first; so does
 *       {@link #prepare(String)}, and only in this case, so the other two keep restoring exactly
 *       once.</li>
 * </ul>
 * Setting it to {@code FIN_Utility.invoicePaymentStatus(payment)} rather than a literal is the same
 * idea as {@code ReactivatePaymentHandler#clearTransferErrorFlag} (ETP-4895): it is by definition
 * the value Core is about to compare against.
 *
 * <p><b>Not atomic.</b> The module commits mid-flow (unposting runs {@code ResetAccounting}, which
 * commits and clears the session), so nothing here assumes a rollback undoes an earlier step: the
 * failure branch of {@link #finish(Prepared)} re-reads the real state and compensates explicitly,
 * and every entity is re-read by id rather than held across the delegation.
 *
 * <p><b>Never poisons the request transaction.</b> Every best-effort write here (the status change
 * and its revert, the statement-line cleanup) runs under a {@link JdbcSavepoints} savepoint and is
 * rolled back to it on failure, so a swallowed failure cannot leave PostgreSQL's transaction
 * aborted under a 2xx answer — the very pattern behind ETP-5547. The status is written with raw
 * JDBC precisely so that no Hibernate flush runs inside that savepoint.
 */
public final class ReconciledPaymentReactivation {

  private static final Logger log = LogManager.getLogger(ReconciledPaymentReactivation.class);

  static final String STATUS_RECONCILED = "RPPC";
  static final String STATUS_DEPOSITED_NOT_CLEARED = "RDNC";
  static final String STATUS_WITHDRAWN_NOT_CLEARED = "PWNC";

  private ReconciledPaymentReactivation() {
  }

  /**
   * What {@link #prepare(String)} captured and changed, handed back to {@link #finish(Prepared)}.
   *
   * @param paymentId       the payment being reactivated
   * @param statementLineId the bank-statement line matched to its transaction, or {@code null}
   * @param appliedStatus   the status {@link #prepare(String)} moved the payment to, or {@code null}
   *                        when it left the status alone
   */
  public record Prepared(String paymentId, String statementLineId, String appliedStatus) {
  }

  /**
   * Runs the pre-delegation half. Returns {@code null} — and changes nothing — when the payment
   * does not exist or is not reconciled (none of its transactions belongs to a reconciliation).
   * Never throws: a failure here must not block the reactivation, which then simply behaves as it
   * did before ETP-5547. It cannot poison the transaction either: the only write is the status
   * change, which runs under a savepoint and is rolled back to it on failure ({@link #writeStatus}).
   *
   * @param paymentId the payment about to be reactivated
   * @return the captured state, or {@code null} when there is nothing to do
   */
  public static Prepared prepare(String paymentId) {
    if (StringUtils.isBlank(paymentId)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, paymentId);
      if (payment == null) {
        return null;
      }
      FIN_FinaccTransaction trx = reconciledTransaction(payment);
      if (trx == null) {
        return null;
      }
      String statementLineId = matchedStatementLineId(trx);
      String appliedStatus = alignStatusWithPaidLevel(payment);
      return new Prepared(paymentId, statementLineId, appliedStatus);
    } catch (Exception e) {
      log.warn("[ETP-5547] Could not prepare the reconciled reactivation of payment {}: {}",
          paymentId, e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Runs the post-delegation half after the delegated call RETURNED (successfully or with an
   * error response). Decides success from the database, not from the response: the reactivation
   * went through when the payment is no longer processed.
   *
   * <p><b>Transaction assumption.</b> Here the transaction is usable: on its own failures the
   * module's {@code ReactivatePayment} action catches the exception and calls
   * {@code OBDal.rollbackAndClose()}, so the next DAL access starts a fresh transaction. When the
   * delegated call THROWS, that is not guaranteed — use {@link #finishAfterFailure(Prepared)}.
   *
   * <p>The pending writes are flushed FIRST and a failure of that flush is NOT swallowed: it means
   * the reactivation's own writes cannot be persisted, so the request must fail rather than answer
   * 2xx over a transaction that will roll back. Everything after it is best-effort, contained by
   * savepoints, and never throws.
   *
   * @param prepared what {@link #prepare(String)} returned; {@code null} is a no-op
   * @throws RuntimeException when flushing the reactivation's pending writes fails
   */
  public static void finish(Prepared prepared) {
    if (prepared == null) {
      return;
    }
    OBDal.getInstance().flush();
    OBContext.setAdminMode(true);
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, prepared.paymentId());
      if (payment != null && Boolean.TRUE.equals(payment.isProcessed())) {
        revertStatusIfStillReconciled(payment, prepared.appliedStatus());
        return;
      }
      cleanUpStatementLine(prepared.statementLineId());
    } catch (Exception e) {
      log.warn("[ETP-5547] Could not finish the reconciled reactivation of payment {}: {}",
          prepared.paymentId(), e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Compensation for the path where the delegated call THREW (or {@link #finish(Prepared)} could
   * not flush). The request transaction may then be aborted, and on PostgreSQL an aborted
   * transaction refuses every statement, so compensating inside it would silently do nothing. It
   * is rolled back first — the request already answers an error, and nothing of a failed
   * reactivation is worth committing — and the status revert then runs on the fresh transaction
   * the next DAL access opens, from whatever the module committed mid-flow. Never throws.
   *
   * @param prepared what {@link #prepare(String)} returned; {@code null} is a no-op (and the
   *                 transaction is then left alone, as before ETP-5547)
   */
  public static void finishAfterFailure(Prepared prepared) {
    if (prepared == null) {
      return;
    }
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (Exception e) {
      log.warn("[ETP-5547] Could not roll back after the failed reactivation of payment {}: {}",
          prepared.paymentId(), e.getMessage());
    }
    OBContext.setAdminMode(true);
    try {
      FIN_Payment payment = OBDal.getInstance().get(FIN_Payment.class, prepared.paymentId());
      if (payment != null && Boolean.TRUE.equals(payment.isProcessed())) {
        revertStatusIfStillReconciled(payment, prepared.appliedStatus());
      }
    } catch (Exception e) {
      log.warn("[ETP-5547] Could not compensate the failed reactivation of payment {}: {}",
          prepared.paymentId(), e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The payment's transaction that belongs to a reconciliation, or {@code null}. */
  static FIN_FinaccTransaction reconciledTransaction(FIN_Payment payment) {
    OBCriteria<FIN_FinaccTransaction> criteria =
        OBDal.getInstance().createCriteria(FIN_FinaccTransaction.class);
    criteria.add(Restrictions.eq(FIN_FinaccTransaction.PROPERTY_FINPAYMENT, payment));
    criteria.add(Restrictions.isNotNull(FIN_FinaccTransaction.PROPERTY_RECONCILIATION));
    criteria.setMaxResults(1);
    return (FIN_FinaccTransaction) criteria.uniqueResult();
  }

  private static String matchedStatementLineId(FIN_FinaccTransaction trx) {
    List<FIN_BankStatementLine> lines = trx.getFINBankStatementLineList();
    return lines.isEmpty() ? null : lines.get(0).getId();
  }

  /**
   * Moves an {@code RPPC} payment to its method's paid status when that status is
   * {@code RDNC}/{@code PWNC} (see class javadoc). Returns the status applied, or {@code null}
   * when nothing was changed.
   */
  static String alignStatusWithPaidLevel(FIN_Payment payment) throws SQLException {
    if (!STATUS_RECONCILED.equals(payment.getStatus())) {
      return null;
    }
    String paidStatus = FIN_Utility.invoicePaymentStatus(payment);
    if (!STATUS_DEPOSITED_NOT_CLEARED.equals(paidStatus)
        && !STATUS_WITHDRAWN_NOT_CLEARED.equals(paidStatus)) {
      return null;
    }
    writeStatus(payment, paidStatus);
    log.info("[ETP-5547] Payment {} moved {} -> {} before reactivating, so Core restores the"
        + " invoice's paid amounts", payment.getId(), STATUS_RECONCILED, paidStatus);
    return paidStatus;
  }

  /**
   * Writes {@code FIN_Payment.Status} with a raw UPDATE under a savepoint, then mirrors the value on
   * the managed instance. On failure the savepoint is rolled back (the transaction stays usable),
   * the managed instance is left untouched and the {@link SQLException} propagates to the caller.
   *
   * <p>Raw JDBC instead of {@code OBDal.save()+flush()} so no Hibernate flush runs inside the
   * savepoint (see {@link JdbcSavepoints}). Mirroring the value afterwards keeps any later flush of
   * this instance consistent with the row: it rewrites the same status, never the old one. That
   * next flush therefore re-issues a same-value UPDATE on {@code fin_payment}, firing its triggers
   * a second time — harmless, since nothing about the row changes.
   */
  static void writeStatus(FIN_Payment payment, String status) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    String userId = OBContext.getOBContext().getUser().getId();
    String paymentId = payment.getId();
    JdbcSavepoints.run(conn, () -> {
      String sql = "UPDATE fin_payment SET status = ?, updated = NOW(), updatedby = ?"
          + " WHERE fin_payment_id = ?";
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, status);
        ps.setString(2, userId);
        ps.setString(3, paymentId);
        ps.executeUpdate();
      }
    });
    payment.setStatus(status);
  }

  /**
   * Failure branch: the payment is still processed. If {@link #prepare(String)} changed its status
   * and it is still reconciled, put {@code RPPC} back — a reconciled payment must not read as
   * "not cleared". The module may have committed part of its work, so the check reads the real
   * state instead of assuming a rollback.
   */
  private static void revertStatusIfStillReconciled(FIN_Payment payment, String appliedStatus)
      throws SQLException {
    if (appliedStatus == null || !appliedStatus.equals(payment.getStatus())
        || reconciledTransaction(payment) == null) {
      return;
    }
    writeStatus(payment, STATUS_RECONCILED);
    log.info("[ETP-5547] Reactivation of payment {} did not go through; status restored to {}",
        payment.getId(), STATUS_RECONCILED);
  }

  /**
   * Success branch: clears the matching leftovers of the (now unmatched) statement line and
   * re-collapses its split group, under a savepoint. The work flushes Hibernate, so on failure the
   * savepoint is rolled back AND the session is cleared (see {@link JdbcSavepoints}); that only
   * discards this cleanup because {@link #finish(Prepared)} flushed the reactivation's own writes
   * before calling it. Never throws.
   */
  static void cleanUpStatementLine(String statementLineId) {
    if (StringUtils.isBlank(statementLineId)) {
      return;
    }
    try {
      Connection conn = OBDal.getInstance().getConnection();
      JdbcSavepoints.run(conn, () -> {
        FIN_BankStatementLine line =
            OBDal.getInstance().get(FIN_BankStatementLine.class, statementLineId);
        if (line == null || line.getFinancialAccountTransaction() != null) {
          // Deleted by a collapse, or matched again to something else: not ours to touch.
          return;
        }
        // Mirrors APRM_MatchingUtility.unmatch, which the payment-removal module does not run.
        line.setMatchingtype(null);
        line.setMatchedDocument(null);
        OBDal.getInstance().save(line);
        OBDal.getInstance().flush();
        new ReconciliationHandler().normalizeReactivatedMatchGroup(line);
        OBDal.getInstance().flush();
      });
    } catch (Exception e) {
      OBDal.getInstance().getSession().clear();
      log.warn("[ETP-5547] Could not clean up statement line {} after reactivating its payment: {}",
          statementLineId, e.getMessage());
    }
  }
}
