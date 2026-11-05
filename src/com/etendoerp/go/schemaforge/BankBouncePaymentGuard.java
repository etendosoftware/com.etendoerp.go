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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.etendoerp.go.schemaforge.BankBounceDetector.AccountData;
import com.etendoerp.go.schemaforge.BankBounceDetector.OpenTransaction;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;

/**
 * Whether discarding a bounce pair would drop the bank line of a real ERP payment (ETP-5581). If a
 * line of the pair is the bank movement of a payment registered in the ERP, discarding it would
 * leave that payment — and the invoice it settles, which reads as paid — without the bank movement
 * that proves it, and its movement could never be reconciled. Such a pair is never proposed and
 * never discarded.
 *
 * <p>Trimmed from the first iteration's {@code ReturnPaymentAttribution} (commit
 * {@code 1a5140cb9}): only the pending side of the attribution and the {@code nettingBlocker}
 * remain; the reconciled originals, the reversal fields and the payment display are gone with the
 * resolutions that needed them. Its {@code PARTIAL_NOT_SUPPORTED} refusal (a line that is one
 * invoice of a larger multi-invoice payment) is answered here as {@code HAS_PAYMENT} too, since
 * that line also belongs to a real payment.
 *
 * <p>Pure, over {@link AccountData}: the caller has loaded the account's pending lines and its open
 * payment movements.
 */
final class BankBouncePaymentGuard {

  /** Utility class: static members only, never instantiated. */
  private BankBouncePaymentGuard() {
  }

  /**
   * The ERP payment a pending line belongs to, or the movement the line would be reconciled
   * against.
   *
   * @param paymentId         the payment
   * @param transactionId     the movement that carries it
   */
  record PaymentRef(String paymentId, String transactionId) {
  }

  /**
   * What the ERP payment of a line resolves to: a payment ({@code ref}), none, or a payment that
   * exists but cannot be attributed to this line ({@code unattributable}: several open payment
   * movements fit it, or another pending line could claim the one that does).
   *
   * @param ref            the attributed payment, or {@code null}
   * @param unattributable whether a payment exists that cannot be told apart
   */
  record Attribution(PaymentRef ref, boolean unattributable) {

    static final Attribution NONE = new Attribution(null, false);
    static final Attribution CANNOT_ATTRIBUTE = new Attribution(null, true);
  }

  /**
   * The refusal code when discarding {@code a} and {@code b} would drop a real payment's line, else
   * {@code null}. Both lines are checked: the caller's roles are not trusted.
   *
   * @param data   the loaded account data
   * @param paired the lines of the other pairs detected in the same run (they are not free to take
   *               a payment); {@code a} and {@code b} are added to it here
   * @param a      one line of the pair
   * @param b      the other line
   * @return {@link BankBounceErrors#CODE_HAS_PAYMENT}, or {@code null}
   */
  static String blocker(AccountData data, Set<String> paired, StatementLine a, StatementLine b) {
    Set<String> taken = new HashSet<>(paired);
    taken.add(a.id());
    taken.add(b.id());
    for (StatementLine l : new StatementLine[] { a, b }) {
      if (data.attributionOf(l).ref() != null || ambiguousPayment(data, l, taken)
          || containedInLargerPayment(data, l)) {
        return BankBounceErrors.CODE_HAS_PAYMENT;
      }
    }
    return null;
  }

  /**
   * The ERP payment {@code line} would be reconciled against: the PSD2 payment its description
   * links to, when that payment's movement is still unreconciled; otherwise the ONLY unreconciled
   * payment movement of the account with the same signed amount, dated within the account's date
   * tolerance — excluding payments another line reaches through its PSD2 link. A payment is
   * attributed to at most one line, and a PSD2 link takes precedence. Two or more such movements,
   * or another pending line that could claim the one that fits, make the payment
   * {@link Attribution#CANNOT_ATTRIBUTE}: it exists, the detection just cannot say whose it is.
   *
   * @param data the loaded account data
   * @param line a pending line
   * @return its attribution
   */
  static Attribution attribute(AccountData data, StatementLine line) {
    String pisPayment = BankBounceDetector.pisPaymentId(data, line);
    if (pisPayment != null) {
      for (OpenTransaction t : data.openTransactions) {
        if (pisPayment.equals(t.paymentId())) {
          return new Attribution(new PaymentRef(t.paymentId(), t.id()), false);
        }
      }
    }
    List<OpenTransaction> fitting = fittingMovements(data, line);
    if (fitting.isEmpty()) {
      return Attribution.NONE;
    }
    OpenTransaction only = fitting.get(0);
    if (fitting.size() > 1 || contested(data, only, line)) {
      return Attribution.CANNOT_ATTRIBUTE;
    }
    return new Attribution(new PaymentRef(only.paymentId(), only.id()), false);
  }

  /**
   * The open payment movements the amount fallback could give {@code line}: same signed amount,
   * within the date tolerance, and not the payment of another line's PSD2 link.
   *
   * @param data the loaded account data
   * @param line the line
   * @return the fitting movements
   */
  private static List<OpenTransaction> fittingMovements(AccountData data, StatementLine line) {
    List<OpenTransaction> fitting = new ArrayList<>();
    for (OpenTransaction t : data.openTransactions) {
      if (fallbackMatches(data, t, line) && !data.pisClaimedPayments().contains(t.paymentId())) {
        fitting.add(t);
      }
    }
    return fitting;
  }

  /**
   * Whether taking {@code line} could swallow the line of a real payment whose owner cannot be told
   * apart. Only for a line whose payment is {@link Attribution#CANNOT_ATTRIBUTE}: the pending lines
   * that compete for the same movements (same signed amount, no PSD2 link of their own) are counted
   * against those movements, and the ones left free ({@code taken} holds the paired lines) must be
   * at least as many as the payments — otherwise some payment's line is being taken.
   *
   * <p>Three 0,61 attempts, ONE payment, two bounces: one attempt stays free for the one payment, so
   * taking the other two is safe. Two outflows, TWO payments, one bounce: one stays free for two
   * payments — refused.
   *
   * @param data  the loaded account data
   * @param line  the line about to be taken
   * @param taken the lines taken by pairs, including this one
   * @return whether taking it is unsafe
   */
  static boolean ambiguousPayment(AccountData data, StatementLine line, Set<String> taken) {
    if (!data.attributionOf(line).unattributable()) {
      return false;
    }
    Set<String> movements = new HashSet<>();
    fittingMovements(data, line).forEach(t -> movements.add(t.id()));
    int free = 0;
    for (StatementLine other : data.lines) {
      if (BankBounceDetector.pisPaymentId(data, other) == null
          && other.amount().compareTo(line.amount()) == 0
          && competes(data, other, movements)) {
        fittingMovements(data, other).forEach(t -> movements.add(t.id()));
        if (!other.id().equals(line.id()) && !taken.contains(other.id())) {
          free++;
        }
      }
    }
    return free < movements.size();
  }

  /**
   * Whether {@code line} could claim one of {@code movementIds} through the amount fallback.
   *
   * @param data        the loaded account data
   * @param line        the line
   * @param movementIds the movements
   * @return whether it competes for them
   */
  private static boolean competes(AccountData data, StatementLine line, Set<String> movementIds) {
    for (OpenTransaction t : data.openTransactions) {
      if (movementIds.contains(t.id()) && fallbackMatches(data, t, line)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a pending line with no payment of its own could be PART of a larger open payment: an
   * open payment movement of the same direction for MORE than the line, dated within the account's
   * date tolerance, settling two or more schedule details (several invoices), of the line's
   * counterparty when the line names one, and not claimed by another line's PSD2 link. Dropping
   * such a line would leave that payment's movement unreconcilable.
   *
   * @param data the loaded account data
   * @param line the line
   * @return whether it may be one invoice of a multi-invoice payment
   */
  static boolean containedInLargerPayment(AccountData data, StatementLine line) {
    if (data.attributionOf(line).ref() != null) {
      return false;
    }
    for (OpenTransaction t : data.openTransactions) {
      if (t.amount().signum() == line.amount().signum()
          && t.amount().abs().compareTo(line.amount().abs()) > 0 && t.detailCount() > 1
          && t.date() != null
          && Math.abs(ChronoUnit.DAYS.between(t.date(), line.date())) <= data.dateTolDays
          && !data.pisClaimedPayments().contains(t.paymentId())
          && (line.partnerName().isEmpty()
              || BankBounceLineText.sameCounterparty(line.partnerName(), t.partnerName()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Same signed amount as the line, dated within the account's date tolerance of it.
   *
   * @param data the loaded account data
   * @param t    the movement
   * @param line the line
   * @return whether the movement fits the line
   */
  private static boolean fallbackMatches(AccountData data, OpenTransaction t, StatementLine line) {
    return t.amount().compareTo(line.amount()) == 0 && t.date() != null
        && Math.abs(ChronoUnit.DAYS.between(t.date(), line.date())) <= data.dateTolDays;
  }

  /**
   * Whether another pending line could claim the same movement through the amount fallback. Then
   * the fallback cannot tell which line the payment belongs to and names it for none. A line
   * resolved through its own PSD2 link does not compete.
   *
   * @param data the loaded account data
   * @param t    the movement
   * @param line the line it fits
   * @return whether another line contests it
   */
  private static boolean contested(AccountData data, OpenTransaction t, StatementLine line) {
    for (StatementLine other : data.lines) {
      if (!other.id().equals(line.id()) && fallbackMatches(data, t, other)
          && BankBounceDetector.pisPaymentId(data, other) == null) {
        return true;
      }
    }
    return false;
  }
}
