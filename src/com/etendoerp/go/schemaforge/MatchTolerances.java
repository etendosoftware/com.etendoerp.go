/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * "How close is close enough" — the pure comparison primitives the reconciliation matcher applies
 * to a bank-statement line and a candidate transaction: the date window, the 1:N sum slack, and
 * the test for whether a candidate deviates at all.
 *
 * <p>Split out of {@link AutoMatchSupport}, which keeps the parts that reach the database, build
 * groups and classify lines. These three take only their arguments, so they read (and test) as
 * arithmetic rather than as matching policy.
 */
final class MatchTolerances {

  /**
   * The one-cent floor a 1:N sum is always allowed, independent of the account's configured
   * percentage.
   */
  private static final BigDecimal SIGNAL_MATCH_TOLERANCE = new BigDecimal("0.01");

  private MatchTolerances() {
    // utility class — no instances
  }

  /** Returns true if the difference between {@code a} and {@code b} is within {@code days}. */
  static boolean withinDateWindow(java.util.Date a, java.util.Date b, int days) {
    if (a == null || b == null) {
      return true;
    }
    long diffMs = Math.abs(a.getTime() - b.getTime());
    return diffMs <= days * 86_400_000L;
  }

  /**
   * Rounding slack for a 1:N signal-group SUM, as max(SIGNAL_MATCH_TOLERANCE, abs(target) *
   * pct/100). A zero {@code pct} yields the one-cent floor rather than disabling anything, because
   * summing several transactions legitimately drifts by a cent and nothing is POSTED on this path —
   * the group either sums to the line or it does not.
   *
   * <p><b>Not a posting threshold.</b> {@link NearMatchSupport#differenceTolerance} reads the very same
   * {@code EM_ETGO_Amount_Tolerance} column with the opposite convention (0 disables) because it
   * decides whether an accounting entry is created. Two names for two purposes: never swap them.
   */
  static BigDecimal signalGroupTolerance(BigDecimal target, BigDecimal pct) {
    if (pct == null || pct.signum() == 0) {
      return SIGNAL_MATCH_TOLERANCE;
    }
    BigDecimal derived = target.abs().multiply(pct)
        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    return derived.max(SIGNAL_MATCH_TOLERANCE);
  }

  /**
   * Whether the transaction differs from the line at all — in amount or in calendar day. A
   * candidate that matches both exactly is a plain suggestion; only a real deviation is a
   * "Con diferencia" match, and only an AMOUNT deviation ever posts an accounting entry.
   */
  static boolean deviatesFrom(FIN_BankStatementLine line, FIN_FinaccTransaction txn) {
    BigDecimal target = ReconciliationSupport.nullSafe(line.getCramount())
        .subtract(ReconciliationSupport.nullSafe(line.getDramount()));
    if (target.subtract(AutoMatchSupport.txnSignedAmount(txn)).signum() != 0) {
      return true;
    }
    return NearMatchSupport.dayDistance(line.getTransactionDate(), txn.getTransactionDate()) != 0;
  }
}
