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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceLineCancel;
import org.openbravo.module.remittance.RemittanceLineReturn;

/**
 * ETP-6247 — what a remittance's lines are, as far as returns go: {@code returnState} answers it
 * and {@code returnLines} validates against it, so the UI never guesses.
 *
 * <p>A line is returnable only after «Cobrada» ({@link RemittanceStatusAction}, ETP-6247): until the
 * remittance is collected there is no settlement row, so every line is {@value #PROCESSED}
 * ({@value #NOT_SETTLED}) whether the remittance is {@code PR} or {@code SB}. Nothing here reads the
 * remittance status or {@code EM_Etar_Automaticprocess}: the settlement rows are the truth.
 *
 * <p><b>States</b> of a line: {@value #PENDING} (the remittance is not processed),
 * {@value #PROCESSED} (processed but the payment is not settled: no settlement row),
 * {@value #SETTLED} (settled, so collected: a processed {@code REM_RemittanceLine_Cancel} row on its
 * payment) and {@value #RETURNED} (its payment is void, {@code RPVOID}). The Return table cannot say
 * it: a return row points to the NEGATIVE payment, not to the one of the line.
 *
 * <p><b>Returnable</b> is true for a {@value #SETTLED} line of a collection remittance that nothing
 * blocks. {@code reasonCode} says what blocks otherwise: {@value #NOT_PROCESSED},
 * {@value #NOT_SETTLED}, {@value #ALREADY_RETURNED}, {@value #NOT_A_COLLECTION} (a payment
 * remittance: the module's return is a collection one), {@value #POSTED} (the settlement or its
 * bank transaction is posted: accounting is out of scope here) or {@value #RECONCILED} (the bank
 * transaction of the settlement is reconciled). A payment shared by several lines (grouping
 * {@code GGPD}) is returned whole: {@code paymentLineIds} lists the lines that share it and a
 * return must include all of them.
 *
 * <p>{@code returnDate} of a {@value #RETURNED} line is the accounting date of the return row
 * created closest in time to the void of its payment (a return row does not point to the original
 * payment); {@code null} when there is none.
 */
final class RemittanceReturnState {

  /** The HQL parameter of the remittance id in the queries below. */
  private static final String P_REMITTANCE = "remittance";

  static final String PENDING = "PENDING";
  static final String PROCESSED = "PROCESSED";
  static final String SETTLED = "SETTLED";
  static final String RETURNED = "RETURNED";

  static final String NOT_PROCESSED = "NOT_PROCESSED";
  static final String NOT_SETTLED = "NOT_SETTLED";
  static final String ALREADY_RETURNED = "ALREADY_RETURNED";
  static final String NOT_A_COLLECTION = "NOT_A_COLLECTION";
  static final String POSTED = "POSTED";
  static final String RECONCILED = "RECONCILED";

  /** {@code FIN_Payment.Status} of a voided payment. */
  static final String PAYMENT_VOID = "RPVOID";

  private RemittanceReturnState() {
  }

  /** The state of one line. */
  static final class LineState {
    final String lineId;
    final String paymentId;
    final String state;
    final String reasonCode;
    final String returnDate;
    final List<String> paymentLineIds;

    LineState(String lineId, String paymentId, String state, String reasonCode, String returnDate,
        List<String> paymentLineIds) {
      this.lineId = lineId;
      this.paymentId = paymentId;
      this.state = state;
      this.reasonCode = reasonCode;
      this.returnDate = returnDate;
      this.paymentLineIds = paymentLineIds;
    }

    boolean returnable() {
      return reasonCode == null && SETTLED.equals(state);
    }
  }

  /**
   * The state of every active line, in line order. The caller runs in admin mode.
   *
   * @param remittance the remittance
   * @return line id to state
   */
  static Map<String, LineState> of(Remittance remittance) {
    List<RemittanceLine> lines = RemittanceFileReadiness.activeLines(remittance);
    Map<String, List<String>> linesOfPayment = new HashMap<>();
    for (RemittanceLine line : lines) {
      if (line.getPayment() != null) {
        linesOfPayment.computeIfAbsent(line.getPayment().getId(), k -> new ArrayList<>())
            .add(line.getId());
      }
    }
    boolean collection = isCollection(remittance);
    boolean processed = Boolean.TRUE.equals(remittance.isProcessed());
    Map<String, RemittanceLineCancel> cancels = settlementRows(remittance);
    Map<String, LineState> states = new LinkedHashMap<>();
    for (RemittanceLine line : lines) {
      states.put(line.getId(), stateOf(line, remittance, collection, processed, cancels,
          linesOfPayment));
    }
    return states;
  }

  private static LineState stateOf(RemittanceLine line, Remittance remittance,
      boolean collection, boolean processed, Map<String, RemittanceLineCancel> cancels,
      Map<String, List<String>> linesOfPayment) {
    FIN_Payment payment = line.getPayment();
    String paymentId = payment != null ? payment.getId() : null;
    List<String> shared = paymentId != null ? linesOfPayment.get(paymentId) : List.of(line.getId());
    if (!processed) {
      return new LineState(line.getId(), paymentId, PENDING, NOT_PROCESSED, null, shared);
    }
    if (payment != null && PAYMENT_VOID.equals(payment.getStatus())) {
      return new LineState(line.getId(), paymentId, RETURNED, ALREADY_RETURNED,
          returnDateOf(remittance, payment), shared);
    }
    RemittanceLineCancel cancel = paymentId != null ? cancels.get(paymentId) : null;
    if (cancel == null) {
      return new LineState(line.getId(), paymentId, PROCESSED, NOT_SETTLED, null, shared);
    }
    String reason = !collection ? NOT_A_COLLECTION : blockOf(cancel, payment);
    return new LineState(line.getId(), paymentId, SETTLED, reason, null, shared);
  }

  /** What blocks returning a settled payment, or {@code null}. */
  private static String blockOf(RemittanceLineCancel cancel, FIN_Payment payment) {
    if ("Y".equals(cancel.getPosted()) || "Y".equals(payment.getPosted())) {
      return POSTED;
    }
    for (FIN_FinaccTransaction transaction : payment.getFINFinaccTransactionList()) {
      if (transaction.getReconciliation() != null) {
        return RECONCILED;
      }
      if ("Y".equals(transaction.getPosted())) {
        return POSTED;
      }
    }
    return null;
  }

  /** Processed, active settlement rows of the remittance, by payment id. */
  static Map<String, RemittanceLineCancel> settlementRows(Remittance remittance) {
    List<RemittanceLineCancel> rows = OBDal.getInstance().getSession().createQuery(
        "select c from " + RemittanceLineCancel.ENTITY_NAME + " as c"
            + " where c.remittance.id = :remittance and c.processed = true and c.active = true"
            + " order by c.lineNo", RemittanceLineCancel.class)
        .setParameter(P_REMITTANCE, remittance.getId())
        .list();
    Map<String, RemittanceLineCancel> byPayment = new HashMap<>();
    for (RemittanceLineCancel row : rows) {
      if (row.getPayment() != null) {
        byPayment.putIfAbsent(row.getPayment().getId(), row);
      }
    }
    return byPayment;
  }

  /**
   * Whether any line of the remittance was returned: it has a processed return row.
   *
   * @param remittance the remittance
   * @return {@code true} when it has returns
   */
  static boolean hasReturns(Remittance remittance) {
    Long count = OBDal.getInstance().getSession().createQuery(
        "select count(r.id) from " + RemittanceLineReturn.ENTITY_NAME + " as r"
            + " where r.remittance.id = :remittance and r.processed = true and r.active = true",
        Long.class)
        .setParameter(P_REMITTANCE, remittance.getId())
        .uniqueResult();
    return count != null && count > 0;
  }

  static boolean isCollection(Remittance remittance) {
    return remittance.getRemittanceType() != null
        && remittance.getRemittanceType().getPaymentMethod() != null
        && Boolean.TRUE.equals(remittance.getRemittanceType().getPaymentMethod().isPayinAllow());
  }

  /**
   * The accounting date of the return row created closest in time to the last update of
   * {@code payment} (its void), as {@code yyyy-MM-dd}, or {@code null}.
   */
  private static String returnDateOf(Remittance remittance, FIN_Payment payment) {
    List<RemittanceLineReturn> rows = OBDal.getInstance().getSession().createQuery(
        "select r from " + RemittanceLineReturn.ENTITY_NAME + " as r"
            + " where r.remittance.id = :remittance and r.processed = true and r.active = true",
        RemittanceLineReturn.class)
        .setParameter(P_REMITTANCE, remittance.getId())
        .list();
    RemittanceLineReturn nearest = null;
    long best = Long.MAX_VALUE;
    Date voided = payment.getUpdated();
    for (RemittanceLineReturn row : rows) {
      if (row.getAccountingDate() == null || row.getCreationDate() == null || voided == null) {
        continue;
      }
      long distance = Math.abs(row.getCreationDate().getTime() - voided.getTime());
      if (distance < best) {
        best = distance;
        nearest = row;
      }
    }
    return nearest != null
        ? new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(nearest.getAccountingDate())
        : null;
  }
}
