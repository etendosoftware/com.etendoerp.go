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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hibernate.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.go.schemaforge.BankBounceDetector.AccountData;
import com.etendoerp.go.schemaforge.BankBounceDetector.OpenTransaction;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;
import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Bounce-pair detection over the pending lines of one account: the signals, the confidence rules,
 * the date window, the same-day roles, ambiguous groups, which lines are candidates and the
 * preference for originals without an ERP payment.
 *
 * <p>The pairing runs in memory over {@link AccountData}, so most cases build that data directly;
 * only {@link BankBounceDetector#load} and {@link BankBounceDetector#toStatementLine} touch the DAL,
 * and those are mocked.
 *
 * @covers com.etendoerp.go.schemaforge.BankBounceDetector
 */
@DisplayName("BankBounceDetector — bounce pairs among pending statement lines")
class BankBounceDetectorTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 3, 2);
  private static final String ACME = "Acme Ltd";

  private static LocalDate day(int offset) {
    return DAY0.plusDays(offset);
  }

  private static StatementLine line(String id, int dayOffset, String description, String partner,
      String amount) {
    return new StatementLine(id, day(dayOffset), dayOffset * 10L + id.length(), description,
        partner, new BigDecimal(amount), true);
  }

  private static AccountData data(int dateTolDays, StatementLine... lines) {
    AccountData data = new AccountData(null, dateTolDays);
    data.lines.addAll(Arrays.asList(lines));
    return data;
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("signals")
  class Signals {

    @Test
    @DisplayName("pis_link: an outflow whose description ends with a PSD2 end-to-end id")
    void pisLink() {
      StatementLine o = line("O", 0, "Transferencia a proveedor e2e-77.", "", "-100");
      StatementLine r = line("R", 5, "Abono", "Someone else", "100");
      AccountData data = data(0, o, r);
      data.pisPaymentByEndToEnd.put("E2E-77", "PAY-1");

      assertEquals("PAY-1", BankBounceDetector.pisPaymentId(data, o),
          "the last token, trailing punctuation stripped and upper-cased, is the end-to-end id");
      assertEquals(List.of(BankBounceDetector.SIGNAL_PIS_LINK),
          BankBounceDetector.signals(data, o, r));
    }

    @Test
    @DisplayName("pis_link never comes from an inflow, even with a known end-to-end id")
    void pisLinkNeedsAnOutflow() {
      StatementLine inflow = line("I", 0, "Cobro E2E-77", "", "100");
      AccountData data = data(0, inflow);
      data.pisPaymentByEndToEnd.put("E2E-77", "PAY-1");

      assertNull(BankBounceDetector.pisPaymentId(data, inflow));
    }

    @Test
    @DisplayName("return_text: bounce wording on the bounce line, not on the original")
    void returnTextIsReadOnTheBounce() {
      StatementLine o = line("O", 0, "Recibo luz", "", "-100");
      StatementLine r = line("R", 3, "DEVOLUCION recibo luz", "", "100");
      AccountData data = data(0, o, r);

      assertEquals(List.of(BankBounceDetector.SIGNAL_RETURN_TEXT),
          BankBounceDetector.signals(data, o, r));

      StatementLine wordedOriginal = line("O2", 0, "DEVOLUCION recibo", "", "-100");
      StatementLine plainBounce = line("R2", 3, "Abono", "", "100");
      assertTrue(BankBounceDetector.signals(data(0, wordedOriginal, plainBounce), wordedOriginal,
          plainBounce).isEmpty(), "wording on the original is not a bounce signal");
    }

    @Test
    @DisplayName("counterparty: both lines name the same counterparty, normalized")
    void counterparty() {
      StatementLine o = line("O", 0, "Pago", "Proveedor Ñandú, S.A.", "-100");
      StatementLine r = line("R", 3, "Abono", "PROVEEDOR NANDU S A", "100");

      assertEquals(List.of(BankBounceDetector.SIGNAL_COUNTERPARTY),
          BankBounceDetector.signals(data(0, o, r), o, r));

      StatementLine blank = line("B", 3, "Abono", "", "100");
      StatementLine blankO = line("BO", 0, "Pago", "", "-100");
      assertTrue(BankBounceDetector.signals(data(0, blankO, blank), blankO, blank).isEmpty(),
          "two blank counterparties are not the same counterparty");
    }

    @Test
    @DisplayName("all three signals come in priority order, pis_link primary")
    void allSignalsInPriorityOrder() {
      StatementLine o = line("O", 0, "Pago E2E9", ACME, "-100");
      StatementLine r = line("R", 0, "Retrocesion pago", ACME, "100");
      AccountData data = data(0, o, r);
      data.pisPaymentByEndToEnd.put("E2E9", "PAY-9");

      List<String> signals = BankBounceDetector.signals(data, o, r);

      assertEquals(BankBounceDetector.SIGNAL_PRIORITY, signals);
      assertEquals(BankBounceDetector.SIGNAL_PIS_LINK,
          BankBounceDetector.primarySignalOf(signals));
      assertEquals(BankBounceDetector.SIGNAL_COUNTERPARTY,
          BankBounceDetector.primarySignalOf(List.of(BankBounceDetector.SIGNAL_COUNTERPARTY)));
      assertNull(BankBounceDetector.primarySignalOf(List.of()));
    }

    @Test
    @DisplayName("amount and opposite sign alone never propose a pair")
    void amountAloneIsNotASignal() {
      StatementLine o = line("O", 0, "Pago", "Alpha", "-100");
      StatementLine r = line("R", 1, "Abono", "Beta", "100");

      assertTrue(BankBounceDetector.detect(data(5, o, r)).isEmpty());
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("confidence")
  class Confidence {

    private String confidenceOf(AccountData data, StatementLine o, StatementLine r) {
      List<Pair> pairs = BankBounceDetector.detect(data);
      assertEquals(1, pairs.size(), "exactly one pair expected");
      Pair pair = pairs.get(0);
      assertSame(o, pair.original());
      assertSame(r, pair.bounce());
      assertEquals(BankBounceDetector.confidence(data, o, r, pair.signals()), pair.confidence(),
          "detect applies the confidence rule to a non-ambiguous pair");
      return pair.confidence();
    }

    @Test
    @DisplayName("bounce wording on the same day → high")
    void wordingSameDayIsHigh() {
      StatementLine o = line("O", 0, "Transferencia", "", "-100");
      StatementLine r = line("R", 0, "Devolucion transferencia", "", "100");

      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, confidenceOf(data(0, o, r), o, r));
    }

    @Test
    @DisplayName("bounce wording on a later day, no PIS link → review")
    void wordingLaterDayIsReview() {
      StatementLine o = line("O", 0, "Transferencia", "", "-100");
      StatementLine r = line("R", 1, "Devolucion transferencia", "", "100");

      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, confidenceOf(data(5, o, r), o, r));
    }

    @Test
    @DisplayName("PIS link with the bounce within the date tolerance → high")
    void pisLinkCloseIsHigh() {
      StatementLine o = line("O", 0, "Pago E2E1", "", "-100");
      StatementLine r = line("R", 3, "Abono", "", "100");
      AccountData data = data(3, o, r);
      data.pisPaymentByEndToEnd.put("E2E1", "PAY-1");

      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, confidenceOf(data, o, r));
    }

    @Test
    @DisplayName("PIS link alone with the bounce weeks later → review")
    void pisLinkFarIsReview() {
      StatementLine o = line("O", 0, "Pago E2E1", "", "-100");
      StatementLine r = line("R", 30, "Abono", "", "100");
      AccountData data = data(3, o, r);
      data.pisPaymentByEndToEnd.put("E2E1", "PAY-1");

      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, confidenceOf(data, o, r));
    }

    @Test
    @DisplayName("PIS link backed by wording, however late → high")
    void pisLinkWithWordingIsHigh() {
      StatementLine o = line("O", 0, "Pago E2E1", "", "-100");
      StatementLine r = line("R", 30, "Rechazo pago", "", "100");
      AccountData data = data(3, o, r);
      data.pisPaymentByEndToEnd.put("E2E1", "PAY-1");

      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, confidenceOf(data, o, r));
    }

    @Test
    @DisplayName("counterparty alone, even on the same day → review")
    void counterpartyAloneIsReview() {
      StatementLine o = line("O", 0, "Pago", ACME, "-100");
      StatementLine r = line("R", 0, "Abono", ACME, "100");

      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, confidenceOf(data(5, o, r), o, r));
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("pairing rules: amount, sign and the 60-day window")
  class Rules {

    @Test
    @DisplayName("withinWindow: 0 and 60 days in, 61 out, a bounce before the original out")
    void windowEdges() {
      assertEquals(60, BankBounceDetector.WINDOW_DAYS);
      assertTrue(BankBounceDetector.withinWindow(DAY0, DAY0));
      assertTrue(BankBounceDetector.withinWindow(DAY0, day(60)));
      assertFalse(BankBounceDetector.withinWindow(DAY0, day(61)));
      assertFalse(BankBounceDetector.withinWindow(day(1), DAY0));
    }

    @Test
    @DisplayName("detect pairs a bounce 60 days later and not one 61 days later")
    void windowEdgesInDetection() {
      StatementLine o = line("O", 0, "Pago", ACME, "-100");
      StatementLine in = line("R", 60, "Abono", ACME, "100");
      List<Pair> pairs = BankBounceDetector.detect(data(0, o, in));
      assertEquals(1, pairs.size());
      assertSame(o, pairs.get(0).original());

      StatementLine o2 = line("O", 0, "Pago", ACME, "-100");
      StatementLine out = line("R", 61, "Abono", ACME, "100");
      assertTrue(BankBounceDetector.detect(data(0, o2, out)).isEmpty());
    }

    @Test
    @DisplayName("same sign, different amount, a zero amount or the same line never pair")
    void onlyOppositeSignAndExactAmount() {
      StatementLine o = line("O", 0, "Pago", ACME, "-100.00");
      AccountData data = data(0, o);

      assertTrue(BankBounceDetector.isOriginalOf(data, o, line("R", 1, "x", ACME, "100")),
          "scale does not matter, the amount is compared exactly");
      assertFalse(BankBounceDetector.isOriginalOf(data, o, line("S", 1, "x", ACME, "-100")));
      assertFalse(BankBounceDetector.isOriginalOf(data, o, line("D", 1, "x", ACME, "100.01")));
      assertFalse(BankBounceDetector.isOriginalOf(data, o, o));
      StatementLine zero = line("Z", 0, "x", ACME, "0");
      assertFalse(BankBounceDetector.isOriginalOf(data, zero, line("Z2", 1, "x", ACME, "0")));
    }

    @Test
    @DisplayName("an original dated after its bounce is not its original")
    void originalMustNotBeLater() {
      StatementLine o = line("O", 5, "Pago", ACME, "-100");
      StatementLine r = line("R", 2, "Abono", ACME, "100");

      assertFalse(BankBounceDetector.isOriginalOf(data(0, o, r), o, r));
      List<Pair> pairs = BankBounceDetector.detect(data(0, o, r));
      assertEquals(1, pairs.size(), "the earlier line is the original instead");
      assertSame(r, pairs.get(0).original());
      assertSame(o, pairs.get(0).bounce());
    }

    @Test
    @DisplayName("an incoming line reversed by a later outflow is a pair too")
    void incomingOriginal() {
      StatementLine o = line("O", 0, "Cobro cliente", ACME, "250");
      StatementLine r = line("R", 4, "Retrocesion cobro", ACME, "-250");

      List<Pair> pairs = BankBounceDetector.detect(data(0, o, r));

      assertEquals(1, pairs.size());
      assertSame(o, pairs.get(0).original());
      assertEquals(List.of(BankBounceDetector.SIGNAL_RETURN_TEXT,
          BankBounceDetector.SIGNAL_COUNTERPARTY), pairs.get(0).signals());
    }

    @Test
    @DisplayName("each line belongs to at most one pair: two bounces take two originals")
    void eachLineInOnePair() {
      StatementLine o1 = line("O1", 0, "Pago", ACME, "-100");
      StatementLine o2 = line("O2", 5, "Pago", ACME, "-100");
      StatementLine r1 = line("R1", 10, "Abono", ACME, "100");
      StatementLine r2 = line("R2", 10, "Abono", ACME, "100");

      List<Pair> pairs = BankBounceDetector.detect(data(0, o1, o2, r1, r2));

      assertEquals(2, pairs.size());
      assertFalse(pairs.get(0).ambiguous(), "as many originals as bounces is not ambiguous");
      assertFalse(pairs.get(1).ambiguous());
      assertSame(r1, pairs.get(0).bounce());
      assertSame(o2, pairs.get(0).original(), "the first bounce takes the closest original");
      assertSame(r2, pairs.get(1).bounce());
      assertSame(o1, pairs.get(1).original());
      assertEquals("bounce-O2-R1", pairs.get(0).groupKey());
      assertEquals(List.of("R1", "O2"), pairs.get(0).lineIds());
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("same-day roles")
  class SameDayRoles {

    @Test
    @DisplayName("the line with bounce wording is the bounce, whatever its sign")
    void wordingDecides() {
      StatementLine outflow = line("O", 0, "Transferencia", "", "-100");
      StatementLine worded = line("R", 0, "Devolucion transferencia", "", "100");
      AccountData data = data(0, outflow, worded);
      assertTrue(BankBounceDetector.originalFirstOnSameDay(data, outflow, worded));
      assertFalse(BankBounceDetector.originalFirstOnSameDay(data, worded, outflow));

      StatementLine inflow = line("I", 0, "Cobro", "", "100");
      StatementLine wordedOut = line("W", 0, "Retrocesion cobro", "", "-100");
      AccountData reversed = data(0, inflow, wordedOut);
      assertTrue(BankBounceDetector.originalFirstOnSameDay(reversed, inflow, wordedOut),
          "wording beats the outflow rule: the worded outflow is the bounce");
    }

    @Test
    @DisplayName("without wording, the outflow with a PSD2 link is the original")
    void pisLinkDecides() {
      StatementLine outflow = line("O", 0, "Pago E2E5", "", "-100");
      StatementLine inflow = line("R", 0, "Abono", "", "100");
      AccountData data = data(0, outflow, inflow);
      data.pisPaymentByEndToEnd.put("E2E5", "PAY-5");

      assertTrue(BankBounceDetector.originalFirstOnSameDay(data, outflow, inflow));
      assertFalse(BankBounceDetector.originalFirstOnSameDay(data, inflow, outflow));
    }

    @Test
    @DisplayName("with neither (or wording on both), the outflow is the original")
    void outflowDecides() {
      StatementLine outflow = line("O", 0, "Pago", ACME, "-100");
      StatementLine inflow = line("R", 0, "Abono", ACME, "100");
      AccountData data = data(0, outflow, inflow);
      assertTrue(BankBounceDetector.originalFirstOnSameDay(data, outflow, inflow));
      assertFalse(BankBounceDetector.originalFirstOnSameDay(data, inflow, outflow));

      StatementLine wordedOut = line("WO", 0, "Devolucion", "", "-100");
      StatementLine wordedIn = line("WI", 0, "Devolucion", "", "100");
      assertTrue(BankBounceDetector.originalFirstOnSameDay(data(0, wordedOut, wordedIn),
          wordedOut, wordedIn));
    }

    @Test
    @DisplayName("detect assigns same-day roles regardless of statement order")
    void sameDayInDetection() {
      StatementLine worded = line("R", 0, "Devolucion transferencia", "", "100");
      StatementLine outflow = line("O", 0, "Transferencia", "", "-100");

      List<Pair> pairs = BankBounceDetector.detect(data(0, worded, outflow));

      assertEquals(1, pairs.size());
      assertSame(outflow, pairs.get(0).original());
      assertSame(worded, pairs.get(0).bounce());
      assertTrue(pairs.get(0).high());
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ambiguous groups")
  class Ambiguous {

    @Test
    @DisplayName("more eligible originals than bounces → one review pair with its candidates")
    void ambiguousGroupIsForReview() {
      StatementLine o1 = line("O1", 0, "Pago", ACME, "-100");
      StatementLine o2 = line("O2", 3, "Pago", ACME, "-100");
      StatementLine r = line("R", 10, "Abono", ACME, "100");

      List<Pair> pairs = BankBounceDetector.detect(data(0, o1, o2, r));

      assertEquals(1, pairs.size());
      Pair pair = pairs.get(0);
      assertTrue(pair.ambiguous());
      assertNull(pair.original(), "an ambiguous pair names no original");
      assertSame(r, pair.bounce());
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, pair.confidence());
      assertEquals(2, pair.candidates().size());
      assertSame(o2, pair.candidates().get(0).line(), "closest original first");
      assertSame(o1, pair.candidates().get(1).line());
      assertEquals(List.of(BankBounceDetector.SIGNAL_COUNTERPARTY),
          pair.candidates().get(0).signals());
      assertEquals(pair.candidates().get(0).signals(), pair.signals());
      assertEquals("bounce-R", pair.groupKey());
      assertEquals(List.of("R", "O2", "O1"), pair.lineIds());
    }

    @Test
    @DisplayName("an ambiguous pair is review even when its signals would make it high")
    void ambiguousIsDowngraded() {
      StatementLine o1 = line("O1", 0, "Transferencia", "", "-100");
      StatementLine o2 = line("O2", 0, "Transferencia", "", "-100");
      StatementLine r = line("R", 0, "Devolucion transferencia", "", "100");

      List<Pair> pairs = BankBounceDetector.detect(data(0, o1, o2, r));

      assertEquals(1, pairs.size());
      assertTrue(pairs.get(0).ambiguous());
      assertFalse(pairs.get(0).high());
      assertEquals(BankBounceDetector.SIGNAL_RETURN_TEXT, pairs.get(0).primarySignal());
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("payments and the payment guard")
  class Payments {

    private OpenTransaction movement(String id, String paymentId, int dayOffset, String amount) {
      return new OpenTransaction(id, paymentId, day(dayOffset), new BigDecimal(amount), ACME, 1);
    }

    @Test
    @DisplayName("an original without an ERP payment is preferred over a closer one with a payment")
    void originalWithoutPaymentPreferred() {
      StatementLine free = line("FREE", 0, "Pago", ACME, "-100");
      StatementLine paid = line("PAID", 8, "Pago", ACME, "-100");
      StatementLine r = line("R", 10, "Abono", ACME, "100");
      AccountData data = data(1, free, paid, r);
      data.openTransactions.add(movement("T1", "PAY-1", 8, "-100"));

      assertTrue(data.attributionOf(paid).ref() != null, "the closer original has a payment");
      assertNull(data.attributionOf(free).ref());

      List<Pair> pairs = BankBounceDetector.detect(data);

      assertEquals(1, pairs.size());
      Pair pair = pairs.get(0);
      assertSame(free, pair.original(), "the attempt the ERP can still reconcile stays free");
      assertFalse(pair.ambiguous(), "one unblocked candidate left makes it a plain pair");
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, pair.confidence(),
          "a pair that came out of an ambiguous group stays for review");
    }

    @Test
    @DisplayName("a pair whose only original has an ERP payment is not proposed")
    void originalWithPaymentDropsThePair() {
      StatementLine paid = line("PAID", 0, "Pago", ACME, "-100");
      StatementLine r = line("R", 0, "Devolucion", ACME, "100");
      AccountData data = data(1, paid, r);
      data.openTransactions.add(movement("T1", "PAY-1", 0, "-100"));

      assertTrue(BankBounceDetector.detect(data).isEmpty());
    }
  }

  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("candidate lines")
  class Candidates {

    @Test
    @DisplayName("a non-candidate line pairs neither as original nor as bounce")
    void nonCandidatesNeverPair() {
      StatementLine o = line("O", 0, "Transferencia", ACME, "-100");
      StatementLine nonCandidateBounce = new StatementLine("R", day(0), 2L,
          "Devolucion transferencia", ACME, new BigDecimal("100"), false);
      assertTrue(BankBounceDetector.detect(data(0, o, nonCandidateBounce)).isEmpty());

      StatementLine nonCandidateOriginal = new StatementLine("O", day(0), 1L, "Transferencia",
          ACME, new BigDecimal("-100"), false);
      StatementLine r = line("R", 0, "Devolucion transferencia", ACME, "100");
      assertTrue(BankBounceDetector.detect(data(0, nonCandidateOriginal, r)).isEmpty());
    }

    @Test
    @DisplayName("toStatementLine: candidate only with a PSD2 id and outside any match group")
    void candidateFlag() {
      FIN_BankStatementLine psd2 = dalLine("L1", "10.00", "0", "  Pago  ", " ACME ", 30L);
      FIN_BankStatementLine noPsd2 = dalLine("L2", "0", "40", "Pago", "", null);
      FIN_BankStatementLine grouped = dalLine("L3", "5", "0", "Pago", "", 50L);

      try (MockedStatic<StatementLineDiscard> discard = mockStatic(StatementLineDiscard.class);
          MockedStatic<ReactivationSupport> groups = mockStatic(ReactivationSupport.class)) {
        discard.when(() -> StatementLineDiscard.transactionIdentifier(psd2)).thenReturn("TX-1");
        discard.when(() -> StatementLineDiscard.transactionIdentifier(noPsd2)).thenReturn(null);
        discard.when(() -> StatementLineDiscard.transactionIdentifier(grouped)).thenReturn("TX-3");
        groups.when(() -> ReactivationSupport.readMatchGroupId(grouped)).thenReturn("G-1");

        StatementLine a = BankBounceDetector.toStatementLine(psd2);
        StatementLine b = BankBounceDetector.toStatementLine(noPsd2);
        StatementLine c = BankBounceDetector.toStatementLine(grouped);

        assertTrue(a.candidate());
        assertEquals(0, a.amount().compareTo(new BigDecimal("10")), "cramount - dramount");
        assertEquals("Pago", a.description());
        assertEquals("ACME", a.partnerName());
        assertEquals(30L, a.lineNo());
        assertEquals(DAY0, a.date());
        assertFalse(b.candidate(), "no PSD2 transaction id: not a line PSD2 lets discard");
        assertEquals(0, b.amount().compareTo(new BigDecimal("-40")));
        assertEquals(0L, b.lineNo(), "a missing line number orders first");
        assertTrue(b.outflow());
        assertFalse(c.candidate(), "a line in a split match group is never a candidate");
      }
    }

    @Test
    @DisplayName("load runs no query when no line is a candidate")
    void loadWithoutCandidatesReadsNothing() {
      FIN_BankStatementLine noPsd2 = dalLine("L1", "0", "40", "Pago", "", 1L);

      try (MockedStatic<StatementLineDiscard> discard = mockStatic(StatementLineDiscard.class);
          MockedStatic<ReactivationSupport> groups = mockStatic(ReactivationSupport.class);
          MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        AccountData data = BankBounceDetector.load(mock(FIN_FinancialAccount.class),
            List.of(noPsd2), 2);

        assertEquals(1, data.lines.size());
        assertEquals(2, data.dateTolDays);
        assertTrue(BankBounceDetector.detect(data).isEmpty());
        dal.verifyNoInteractions();
      }
    }

    @Test
    @DisplayName("load reads the PSD2 payments, the open movements and their detail counts")
    @SuppressWarnings("unchecked")
    void loadReadsTheAccount() {
      FIN_BankStatementLine l1 = dalLine("L1", "0", "100", "Pago E2E1", "ACME", 1L);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      when(account.getId()).thenReturn("ACC-1");
      OBDal obDal = mock(OBDal.class);

      OBQuery<PisPayment> pisQuery = mock(OBQuery.class);
      Query<Object[]> pisRows = mock(Query.class);
      when(obDal.createQuery(eq(PisPayment.class), anyString())).thenReturn(pisQuery);
      when(pisQuery.createQuery(Object[].class)).thenReturn(pisRows);
      when(pisRows.list()).thenReturn(rows(new Object[] { " e2e1 ", "PAY-1" },
          new Object[] { "  ", "PAY-2" }));

      OBQuery<FIN_FinaccTransaction> ftQuery = mock(OBQuery.class);
      Query<Object[]> ftRows = mock(Query.class);
      when(obDal.createQuery(eq(FIN_FinaccTransaction.class), anyString())).thenReturn(ftQuery);
      when(ftQuery.createQuery(Object[].class)).thenReturn(ftRows);
      when(ftRows.list()).thenReturn(rows(new Object[] { "T1", "PAY-1",
          Timestamp.valueOf(DAY0.atStartOfDay()), new BigDecimal("0"), new BigDecimal("100"),
          "ACME" }));

      OBQuery<FIN_PaymentScheduleDetail> psdQuery = mock(OBQuery.class);
      Query<String> psdRows = mock(Query.class);
      when(obDal.createQuery(eq(FIN_PaymentScheduleDetail.class), anyString()))
          .thenReturn(psdQuery);
      when(psdQuery.createQuery(String.class)).thenReturn(psdRows);
      when(psdRows.list()).thenReturn(List.of("PAY-1", "PAY-1"));

      try (MockedStatic<StatementLineDiscard> discard = mockStatic(StatementLineDiscard.class);
          MockedStatic<ReactivationSupport> groups = mockStatic(ReactivationSupport.class);
          MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        dal.when(OBDal::getInstance).thenReturn(obDal);
        discard.when(() -> StatementLineDiscard.transactionIdentifier(l1)).thenReturn("TX-1");

        AccountData data = BankBounceDetector.load(account, List.of(l1), 1);

        assertEquals(1, data.pisPaymentByEndToEnd.size(), "a blank end-to-end id is skipped");
        assertEquals("PAY-1", data.pisPaymentByEndToEnd.get("E2E1"));
        assertEquals(1, data.openTransactions.size());
        OpenTransaction t = data.openTransactions.get(0);
        assertEquals("T1", t.id());
        assertEquals("PAY-1", t.paymentId());
        assertEquals(DAY0, t.date());
        assertEquals(0, t.amount().compareTo(new BigDecimal("-100")), "deposit - payment");
        assertEquals(2, t.detailCount());

        ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
        verify(obDal).createQuery(eq(FIN_FinaccTransaction.class), hql.capture());
        assertTrue(hql.getValue().contains("fp.id in (:etgoPisPayments)"),
            "the PSD2 payments' movements are read by their link, not only by date");
        assertTrue(hql.getValue().contains(":etgoAccountId"), "scoped by the account");
      }
    }

    @Test
    @DisplayName("detectQuietly answers no pair when the reads fail")
    void detectQuietlySwallowsFailures() {
      FIN_BankStatementLine l1 = dalLine("L1", "0", "100", "Pago", "ACME", 1L);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      when(account.getId()).thenReturn("ACC-1");

      try (MockedStatic<StatementLineDiscard> discard = mockStatic(StatementLineDiscard.class);
          MockedStatic<ReactivationSupport> groups = mockStatic(ReactivationSupport.class);
          MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
        discard.when(() -> StatementLineDiscard.transactionIdentifier(l1)).thenReturn("TX-1");
        dal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));

        assertTrue(BankBounceDetector.detectQuietly(account, List.of(l1), 1).isEmpty());
      }
    }

    private List<Object[]> rows(Object[]... rows) {
      return new ArrayList<>(Arrays.asList(rows));
    }

    private FIN_BankStatementLine dalLine(String id, String cr, String dr, String description,
        String partner, Long lineNo) {
      FIN_BankStatementLine l = mock(FIN_BankStatementLine.class);
      when(l.getId()).thenReturn(id);
      when(l.getCramount()).thenReturn(new BigDecimal(cr));
      when(l.getDramount()).thenReturn(new BigDecimal(dr));
      when(l.getDescription()).thenReturn(description);
      when(l.getBpartnername()).thenReturn(partner);
      when(l.getLineNo()).thenReturn(lineNo);
      when(l.getTransactionDate()).thenReturn(Timestamp.valueOf(DAY0.atStartOfDay()));
      return l;
    }
  }
}
