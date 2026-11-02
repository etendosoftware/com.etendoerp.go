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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.BankBounceDetector.AccountData;
import com.etendoerp.go.schemaforge.BankBounceDetector.OpenTransaction;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;
import com.etendoerp.go.schemaforge.BankBouncePaymentGuard.Attribution;

/**
 * Unit tests for {@link BankBouncePaymentGuard}: when discarding a bounce pair would drop the bank
 * line of a real ERP payment. Pure over {@link AccountData}, which is built by hand here (no DAL):
 * the pending lines, the PSD2 end-to-end ids and the open payment movements of one account.
 *
 * @covers com.etendoerp.go.schemaforge.BankBouncePaymentGuard
 */
class BankBouncePaymentGuardTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
  private static final int DATE_TOLERANCE = 3;

  // ── attribute ────────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("attribute — which ERP payment a pending line belongs to")
  class Attribute {

    @Test
    @DisplayName("a PSD2 end-to-end id names the payment, even when the amount does not fit")
    void thePisLinkFindsThePayment() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "TRANSFERENCIA CONCEPTO 10000268 10000268-2", "", "-50");
      data.lines.add(line);
      data.pisPaymentByEndToEnd.put("10000268-2", "PAY-1");
      data.openTransactions.add(movement("T-1", "PAY-1", DAY.plusDays(20), "-75", "", 1));

      Attribution attribution = BankBouncePaymentGuard.attribute(data, line);

      assertEquals(new BankBouncePaymentGuard.PaymentRef("PAY-1", "T-1"), attribution.ref());
      assertFalse(attribution.unattributable());
    }

    @Test
    @DisplayName("the only open movement with the same amount within the tolerance is the payment")
    void aSingleFittingMovementIsThePayment() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "CARGO", "", "-50");
      data.lines.add(line);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY.plusDays(DATE_TOLERANCE), "-50", "", 1));
      data.openTransactions.add(movement("T-2", "PAY-2", DAY, "-49.99", "", 1));

      assertEquals(new BankBouncePaymentGuard.PaymentRef("PAY-1", "T-1"),
          BankBouncePaymentGuard.attribute(data, line).ref());
    }

    @Test
    @DisplayName("a movement outside the date tolerance, or of the opposite sign, is no payment")
    void aMovementThatDoesNotFitIsNone() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "CARGO", "", "-50");
      data.lines.add(line);
      data.openTransactions.add(
          movement("T-1", "PAY-1", DAY.plusDays(DATE_TOLERANCE + 1L), "-50", "", 1));
      data.openTransactions.add(movement("T-2", "PAY-2", DAY, "50", "", 1));

      assertSame(Attribution.NONE, BankBouncePaymentGuard.attribute(data, line));
    }

    @Test
    @DisplayName("a payment another line reaches through its PSD2 link is not the fallback's")
    void aPaymentClaimedByAnotherLinesPisLinkIsNotAttributed() {
      AccountData data = data();
      StatementLine plain = line("L1", DAY, "CARGO", "", "-50");
      StatementLine linked = line("L2", DAY, "TRANSFERENCIA 10000268-2", "", "-50");
      data.lines.add(plain);
      data.lines.add(linked);
      data.pisPaymentByEndToEnd.put("10000268-2", "PAY-1");
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-50", "", 1));

      assertSame(Attribution.NONE, BankBouncePaymentGuard.attribute(data, plain));
      assertEquals("PAY-1", BankBouncePaymentGuard.attribute(data, linked).ref().paymentId());
    }

    @Test
    @DisplayName("a movement another pending line also fits is contested: it cannot be attributed")
    void aContestedMovementCannotBeAttributed() {
      AccountData data = data();
      StatementLine first = line("L1", DAY, "CARGO", "", "-50");
      StatementLine second = line("L2", DAY.plusDays(1), "CARGO", "", "-50");
      data.lines.add(first);
      data.lines.add(second);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-50", "", 1));

      assertSame(Attribution.CANNOT_ATTRIBUTE, BankBouncePaymentGuard.attribute(data, first));
      assertSame(Attribution.CANNOT_ATTRIBUTE, BankBouncePaymentGuard.attribute(data, second));
    }

    @Test
    @DisplayName("two open movements that both fit cannot be told apart")
    void twoFittingMovementsCannotBeAttributed() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "CARGO", "", "-50");
      data.lines.add(line);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-50", "", 1));
      data.openTransactions.add(movement("T-2", "PAY-2", DAY.minusDays(1), "-50", "", 1));

      Attribution attribution = BankBouncePaymentGuard.attribute(data, line);

      assertNull(attribution.ref());
      assertTrue(attribution.unattributable());
    }
  }

  // ── ambiguousPayment ─────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ambiguousPayment — taking a line whose payment cannot be attributed")
  class AmbiguousPayment {

    /**
     * Three 0,61 attempts, ONE payment, two bounces: one attempt stays free for the one payment, so
     * taking the other two (each in its own pair) is safe.
     */
    @Test
    @DisplayName("3 attempts, 1 payment, 2 pairs: one attempt stays free, so the pairs are safe")
    void threeAttemptsOnePaymentLeavesOneFree() {
      AccountData data = data();
      StatementLine a1 = line("A1", DAY, "PAGO", "", "-0.61");
      StatementLine a2 = line("A2", DAY, "PAGO", "", "-0.61");
      StatementLine a3 = line("A3", DAY, "PAGO", "", "-0.61");
      StatementLine b1 = line("B1", DAY.plusDays(1), "DEVOLUCION", "", "0.61");
      StatementLine b2 = line("B2", DAY.plusDays(1), "DEVOLUCION", "", "0.61");
      data.lines.addAll(java.util.List.of(a1, a2, a3, b1, b2));
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-0.61", "", 1));

      assertTrue(data.attributionOf(a2).unattributable(), "precondition: contested");
      assertFalse(BankBouncePaymentGuard.ambiguousPayment(data, a2, Set.of("A1", "B1", "A2", "B2")));
      assertNull(BankBouncePaymentGuard.blocker(data, Set.of("A1", "B1"), a2, b2));
      assertNull(BankBouncePaymentGuard.blocker(data, Set.of(), a1, b1));
    }

    /** Two outflows, TWO payments, one bounce: one line stays free for two payments — refused. */
    @Test
    @DisplayName("2 outflows, 2 payments, 1 bounce: taking one leaves a payment without its line")
    void twoOutflowsTwoPaymentsIsRefused() {
      AccountData data = data();
      StatementLine o1 = line("O1", DAY, "PAGO", "", "-0.61");
      StatementLine o2 = line("O2", DAY, "PAGO", "", "-0.61");
      StatementLine bounce = line("B1", DAY.plusDays(1), "DEVOLUCION", "", "0.61");
      data.lines.addAll(java.util.List.of(o1, o2, bounce));
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-0.61", "", 1));
      data.openTransactions.add(movement("T-2", "PAY-2", DAY, "-0.61", "", 1));

      assertTrue(BankBouncePaymentGuard.ambiguousPayment(data, o1, Set.of("O1", "B1")));
      assertEquals(BankBounceErrors.CODE_HAS_PAYMENT,
          BankBouncePaymentGuard.blocker(data, Set.of(), o1, bounce));
    }

    @Test
    @DisplayName("a line whose payment is attributable, or absent, is never ambiguous")
    void anAttributableOrAbsentPaymentIsNotAmbiguous() {
      AccountData data = data();
      StatementLine paid = line("L1", DAY, "PAGO", "", "-10");
      StatementLine free = line("L2", DAY, "PAGO", "", "-20");
      data.lines.add(paid);
      data.lines.add(free);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-10", "", 1));

      assertFalse(BankBouncePaymentGuard.ambiguousPayment(data, paid, Set.of("L1")));
      assertFalse(BankBouncePaymentGuard.ambiguousPayment(data, free, Set.of("L2")));
    }
  }

  // ── containedInLargerPayment ─────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("containedInLargerPayment — one invoice of a multi-invoice payment")
  class ContainedInLargerPayment {

    @Test
    @DisplayName("a larger multi-invoice movement of the same counterparty contains the line")
    void aLargerMultiInvoiceMovementOfTheSameCounterpartyContainsTheLine() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "PAGO", "Müller GmbH", "-30");
      data.lines.add(line);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY.plusDays(2), "-100", "MUELLER GMBH", 2));

      assertTrue(BankBouncePaymentGuard.containedInLargerPayment(data, line));
      assertEquals(BankBounceErrors.CODE_HAS_PAYMENT, BankBouncePaymentGuard.blocker(data,
          Set.of(), line, line("B1", DAY.plusDays(3), "DEVOLUCION", "", "30")));
    }

    @Test
    @DisplayName("a line that names no counterparty is contained in any such movement")
    void aLineWithoutCounterpartyIsContained() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "PAGO", "", "-30");
      data.lines.add(line);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-100", "Anyone S.L.", 3));

      assertTrue(BankBouncePaymentGuard.containedInLargerPayment(data, line));
    }

    @Test
    @DisplayName("a single-invoice, smaller, opposite, distant, foreign or PSD2-claimed movement does not")
    void movementsThatCannotContainTheLine() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "PAGO", "Müller GmbH", "-30");
      StatementLine linked = line("L2", DAY, "TRANSFERENCIA 10000268-2", "", "-500");
      data.lines.add(line);
      data.lines.add(linked);
      data.pisPaymentByEndToEnd.put("10000268-2", "PAY-CLAIMED");
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-100", "Müller GmbH", 1));
      data.openTransactions.add(movement("T-2", "PAY-2", DAY, "-30", "Müller GmbH", 2));
      data.openTransactions.add(movement("T-3", "PAY-3", DAY, "100", "Müller GmbH", 2));
      data.openTransactions.add(
          movement("T-4", "PAY-4", DAY.minusDays(DATE_TOLERANCE + 1L), "-100", "Müller GmbH", 2));
      data.openTransactions.add(movement("T-5", "PAY-5", DAY, "-100", "Otra S.A.", 2));
      data.openTransactions.add(movement("T-6", "PAY-CLAIMED", DAY, "-100", "Müller GmbH", 2));

      assertFalse(BankBouncePaymentGuard.containedInLargerPayment(data, line));
    }

    @Test
    @DisplayName("a line that has its own payment is never 'contained' in another")
    void aLineWithItsOwnPaymentIsNotContained() {
      AccountData data = data();
      StatementLine line = line("L1", DAY, "PAGO", "", "-30");
      data.lines.add(line);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-30", "", 1));
      data.openTransactions.add(movement("T-2", "PAY-2", DAY, "-100", "", 2));

      assertFalse(BankBouncePaymentGuard.containedInLargerPayment(data, line));
    }
  }

  // ── blocker ──────────────────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("blocker — both lines of the pair are checked")
  class Blocker {

    @Test
    @DisplayName("a pair with no ERP payment on either line may be discarded")
    void aPairWithoutPaymentsHasNoBlocker() {
      AccountData data = data();
      StatementLine original = line("O1", DAY, "PAGO", "", "-50");
      StatementLine bounce = line("B1", DAY.plusDays(1), "DEVOLUCION", "", "50");
      data.lines.add(original);
      data.lines.add(bounce);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-80", "", 1));

      assertNull(BankBouncePaymentGuard.blocker(data, Set.of(), original, bounce));
    }

    @Test
    @DisplayName("a payment on the bounce side blocks too: the caller's roles are not trusted")
    void aPaymentOnEitherSideBlocks() {
      AccountData data = data();
      StatementLine original = line("O1", DAY, "PAGO", "", "-50");
      StatementLine bounce = line("B1", DAY.plusDays(1), "DEVOLUCION", "", "50");
      data.lines.add(original);
      data.lines.add(bounce);
      data.openTransactions.add(movement("T-1", "PAY-1", DAY.plusDays(1), "50", "", 1));

      assertEquals(BankBounceErrors.CODE_HAS_PAYMENT,
          BankBouncePaymentGuard.blocker(data, Set.of(), original, bounce));
      assertEquals(BankBounceErrors.CODE_HAS_PAYMENT,
          BankBouncePaymentGuard.blocker(data, Set.of(), bounce, original));
    }

    @Test
    @DisplayName("the original reached through its PSD2 link blocks the pair")
    void aPisLinkedOriginalBlocks() {
      AccountData data = data();
      StatementLine original = line("O1", DAY, "TRANSFERENCIA 10000268 10000268-2", "", "-50");
      StatementLine bounce = line("B1", DAY.plusDays(1), "RETROCESION", "", "50");
      data.lines.add(original);
      data.lines.add(bounce);
      data.pisPaymentByEndToEnd.put("10000268-2", "PAY-1");
      data.openTransactions.add(movement("T-1", "PAY-1", DAY, "-50", "", 1));

      assertEquals(BankBounceErrors.CODE_HAS_PAYMENT,
          BankBouncePaymentGuard.blocker(data, Set.of(), original, bounce));
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static AccountData data() {
    return new AccountData(null, DATE_TOLERANCE);
  }

  private static StatementLine line(String id, LocalDate date, String description,
      String partnerName, String amount) {
    return new StatementLine(id, date, 10, description, partnerName, new BigDecimal(amount), true);
  }

  private static OpenTransaction movement(String id, String paymentId, LocalDate date,
      String amount, String partnerName, int detailCount) {
    return new OpenTransaction(id, paymentId, date, new BigDecimal(amount), partnerName,
        detailCount);
  }
}
