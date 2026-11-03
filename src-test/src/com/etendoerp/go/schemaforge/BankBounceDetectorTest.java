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
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

import com.etendoerp.go.schemaforge.BankBounceDetector.AccountData;
import com.etendoerp.go.schemaforge.BankBounceDetector.Candidate;
import com.etendoerp.go.schemaforge.BankBounceDetector.OpenTransaction;
import com.etendoerp.go.schemaforge.BankBounceDetector.Pair;
import com.etendoerp.go.schemaforge.BankBounceDetector.StatementLine;
import com.etendoerp.psd2.bank.integration.data.PisPayment;

/**
 * Bounce-pair detection over the pending lines of one account: the signals, the confidence rules,
 * the date window, the same-day roles, ambiguous groups, statement adjacency (both neighbours of a
 * bounce in its statement, never a reason for confidence), which lines are candidates and the
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

  /**
   * Statement adjacency: both neighbours of a bounce in its statement count the same; one qualifying
   * neighbour is the original, two are the only candidates of an ambiguous pair, and adjacency never
   * raises the confidence.
   */
  @Nested
  @DisplayName("statement adjacency")
  class Adjacency {

    private static final String ST = "ST-1";
    private static final String RETURN = "RETROCESIÓN TRANSFERENCIA INMEDIATA";

    /** A candidate line of statement {@code statementId} at position {@code lineNo}. */
    private StatementLine at(String id, String statementId, long lineNo, int dayOffset,
        String description, String partner, String amount) {
      return new StatementLine(id, day(dayOffset), lineNo, description, partner,
          new BigDecimal(amount), true, statementId);
    }

    private Pair onlyPair(AccountData data) {
      List<Pair> pairs = BankBounceDetector.detect(data);
      assertEquals(1, pairs.size(), "exactly one pair expected: " + pairs);
      return pairs.get(0);
    }

    private void assertPlainAdjacent(Pair pair, StatementLine original, StatementLine bounce) {
      assertSame(original, pair.original());
      assertSame(bounce, pair.bounce());
      assertFalse(pair.ambiguous());
      assertTrue(pair.adjacent(), "the original was chosen by statement adjacency");
      assertTrue(pair.candidates().isEmpty());
    }

    private List<String> candidateIds(Pair pair) {
      List<String> ids = new ArrayList<>();
      pair.candidates().forEach(c -> ids.add(c.line().id()));
      return ids;
    }

    @Test
    @DisplayName("the real bbva statement: 170↔180 high, 100/130/150 ambiguous over their neighbours")
    void realStatement() {
      LocalDate aug12 = LocalDate.of(2026, 8, 12);
      LocalDate aug24 = LocalDate.of(2026, 8, 24);
      StatementLine l90 = new StatementLine("L90", aug12, 90,
          "TRANSFERENCIA INMEDIATA A FAVOR DE Galder Romo", "", new BigDecimal("-0.30"), true, ST);
      StatementLine l100 = new StatementLine("L100", aug12, 100, RETURN, "",
          new BigDecimal("0.30"), true, ST);
      StatementLine l110 = new StatementLine("L110", aug12, 110,
          "TRANSFERENCIA INMEDIATA A FAVOR DE Distribuciones Iberia", "",
          new BigDecimal("-0.30"), true, ST);
      StatementLine l120 = new StatementLine("L120", aug24, 120, "TRANSFERENCIA INMEDIATA", "",
          new BigDecimal("-0.61"), true, ST);
      StatementLine l130 = new StatementLine("L130", aug24, 130, RETURN, "",
          new BigDecimal("0.61"), true, ST);
      StatementLine l140 = new StatementLine("L140", aug24, 140, "TRANSFERENCIA INMEDIATA", "",
          new BigDecimal("-0.61"), true, ST);
      StatementLine l150 = new StatementLine("L150", aug24, 150, RETURN, "",
          new BigDecimal("0.61"), true, ST);
      StatementLine l160 = new StatementLine("L160", aug24, 160, "TRANSFERENCIA INMEDIATA", "",
          new BigDecimal("-0.61"), true, ST);
      StatementLine l170 = new StatementLine("L170", aug24, 170, RETURN, "",
          new BigDecimal("0.15"), true, ST);
      StatementLine l180 = new StatementLine("L180", aug24, 180, "TRANSFERENCIA INMEDIATA", "",
          new BigDecimal("-0.15"), true, ST);

      List<Pair> pairs = BankBounceDetector.detect(
          data(3, l90, l100, l110, l120, l130, l140, l150, l160, l170, l180));

      assertEquals(4, pairs.size(), pairs.toString());
      List<String> bounces = new ArrayList<>();
      pairs.forEach(p -> bounces.add(p.bounce().id()));
      assertEquals(List.of("L100", "L130", "L150", "L170"), bounces,
          "in statement order of the bounce line");
      for (int i = 0; i < 3; i++) {
        Pair ambiguous = pairs.get(i);
        assertTrue(ambiguous.ambiguous(), ambiguous.bounce().id());
        assertTrue(ambiguous.adjacent(), ambiguous.bounce().id());
        assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, ambiguous.confidence());
        assertEquals(List.of(BankBounceDetector.SIGNAL_RETURN_TEXT), ambiguous.signals());
        assertEquals("bounce-" + ambiguous.bounce().id(), ambiguous.groupKey());
      }
      assertEquals(List.of("L90", "L110"), candidateIds(pairs.get(0)));
      assertEquals(List.of("L120", "L140"), candidateIds(pairs.get(1)));
      assertEquals(List.of("L140", "L160"), candidateIds(pairs.get(2)),
          "two ambiguous pairs may share a neighbour");
      Pair high = pairs.get(3);
      assertPlainAdjacent(high, l180, l170);
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, high.confidence(),
          "same-day bounce wording; the return is written before its payment");
      assertEquals(List.of(BankBounceDetector.SIGNAL_RETURN_TEXT), high.signals());
    }

    @Test
    @DisplayName("one qualifying neighbour, after or before the bounce, is the original")
    void singleNeighbourOnEitherSide() {
      StatementLine o = at("O", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine r = at("R", ST, 20, 0, "Devolucion transferencia", "", "100");
      StatementLine fee = at("F", ST, 30, 0, "Comision", "", "-7");
      assertPlainAdjacent(onlyPair(data(0, o, r, fee)), o, r);

      StatementLine fee2 = at("F2", ST, 10, 0, "Comision", "", "-7");
      StatementLine r2 = at("R2", ST, 20, 0, "Devolucion transferencia", "", "100");
      StatementLine o2 = at("O2", ST, 30, 0, "Transferencia", "", "-100");
      assertPlainAdjacent(onlyPair(data(0, fee2, r2, o2)), o2, r2);
    }

    @Test
    @DisplayName("two qualifying neighbours are the ONLY candidates, other same-day originals aside")
    void bothNeighboursAreTheOnlyCandidates() {
      StatementLine far = at("FAR", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine fee = at("F", ST, 20, 0, "Comision", "", "-7");
      StatementLine a = at("A", ST, 30, 0, "Transferencia", "", "-100");
      StatementLine r = at("R", ST, 40, 0, "Devolucion transferencia", "", "100");
      StatementLine b = at("B", ST, 50, 0, "Transferencia", "", "-100");

      Pair pair = onlyPair(data(0, far, fee, a, r, b));

      assertTrue(pair.ambiguous());
      assertTrue(pair.adjacent());
      assertSame(r, pair.bounce());
      assertEquals(List.of("A", "B"), candidateIds(pair), "FAR is an eligible original too");
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, pair.confidence());
    }

    @Test
    @DisplayName("a disqualified neighbour leaves the other one as the single original")
    void disqualifiedNeighbour() {
      StatementLine r = at("R", ST, 20, 0, "Devolucion transferencia", "", "100");
      StatementLine next = at("N", ST, 30, 0, "Transferencia", "", "-100");
      String[][] previous = {
          { "different amount", "0", "Transferencia", "", "-100.01" },
          { "same sign", "0", "Abono", "", "100" },
          { "outside the window", "-61", "Transferencia", "", "-100" } };
      for (String[] p : previous) {
        StatementLine prev = at("P", ST, 10, Integer.parseInt(p[1]), p[2], p[3], p[4]);
        Pair pair = onlyPair(data(0, prev, r, next));
        assertSame(next, pair.original(), p[0]);
        assertFalse(pair.ambiguous(), p[0]);
        assertTrue(pair.adjacent(), p[0]);
      }

      // No signal: the bounce has no wording, only the counterparty links it to N.
      StatementLine plain = at("R", ST, 20, 0, "Abono", ACME, "100");
      StatementLine stranger = at("P", ST, 10, 0, "Transferencia", "Beta", "-100");
      StatementLine sameParty = at("N", ST, 30, 0, "Transferencia", ACME, "-100");
      assertPlainAdjacent(onlyPair(data(0, stranger, plain, sameParty)), sameParty, plain);
    }

    @Test
    @DisplayName("a neighbour the payment guard holds leaves the other one as the single original")
    void neighbourWithAPaymentIsNotQualifying() {
      StatementLine paid = at("P", ST, 10, -1, "Transferencia", "", "-100");
      StatementLine r = at("R", ST, 20, 0, "Devolucion transferencia", "", "100");
      StatementLine next = at("N", ST, 30, 0, "Transferencia", "", "-100");
      AccountData data = data(0, paid, r, next);
      data.openTransactions.add(new OpenTransaction("T1", "PAY-1", day(-1),
          new BigDecimal("-100"), "", 1));
      assertTrue(data.attributionOf(paid).ref() != null, "P is the bank movement of PAY-1");

      Pair pair = onlyPair(data);

      assertPlainAdjacent(pair, next, r);
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, pair.confidence());
    }

    @Test
    @DisplayName("a reserved neighbour turns a two-neighbour bounce single, and that propagates")
    void reservationsPropagate() {
      LocalDate aug24 = LocalDate.of(2026, 8, 24);
      StatementLine l120 = new StatementLine("L120", aug24, 120, "TRANSFERENCIA E2E120", "",
          new BigDecimal("-0.61"), true, ST);
      StatementLine l130 = new StatementLine("L130", aug24, 130, RETURN, "",
          new BigDecimal("0.61"), true, ST);
      StatementLine l140 = new StatementLine("L140", aug24, 140, "TRANSFERENCIA", "",
          new BigDecimal("-0.61"), true, ST);
      StatementLine l150 = new StatementLine("L150", aug24, 150, RETURN, "",
          new BigDecimal("0.61"), true, ST);
      StatementLine l160 = new StatementLine("L160", aug24, 160, "TRANSFERENCIA", "",
          new BigDecimal("-0.61"), true, ST);
      AccountData data = data(3, l120, l130, l140, l150, l160);
      // L120 is the movement of an ERP payment: never part of a pair.
      data.pisPaymentByEndToEnd.put("E2E120", "PAY-120");
      data.openTransactions.add(new OpenTransaction("T120", "PAY-120", aug24,
          new BigDecimal("-0.61"), "", 1));

      List<Pair> pairs = BankBounceDetector.detect(data);

      assertEquals(2, pairs.size(), pairs.toString());
      assertPlainAdjacent(pairs.get(0), l140, l130);
      assertPlainAdjacent(pairs.get(1), l160, l150);
      assertTrue(pairs.get(0).high());
      assertTrue(pairs.get(1).high());
    }

    @Test
    @DisplayName("one neighbour that is the only one of two bounces: the ordinary rules decide")
    void contestedNeighbourFallsToOrdinaryRules() {
      StatementLine r1 = at("R1", ST, 10, 0, "Devolucion", "", "100");
      StatementLine o = at("O", ST, 20, 0, "Transferencia", "", "-100");
      StatementLine r2 = at("R2", ST, 30, 0, "Devolucion", "", "100");

      Pair pair = onlyPair(data(0, r1, o, r2));

      assertSame(o, pair.original());
      assertSame(r1, pair.bounce(), "the ordinary rule: first bounce of the group");
      assertFalse(pair.adjacent(), "adjacency decided neither");
    }

    @Test
    @DisplayName("both neighbours reserved: the ordinary rules over the lines left")
    void bothNeighboursReserved() {
      StatementLine ra = at("RA", ST, 10, 0, "Devolucion", "", "100");
      StatementLine o1 = at("O1", ST, 20, 0, "Transferencia", "", "-100");
      StatementLine rx = at("RX", ST, 30, 0, "Devolucion", "", "100");
      StatementLine o2 = at("O2", ST, 40, 0, "Transferencia", "", "-100");
      StatementLine rb = at("RB", ST, 50, 0, "Devolucion", "", "100");
      StatementLine o3 = at("O3", "ST-2", 10, 0, "Transferencia", "", "-100");

      List<Pair> pairs = BankBounceDetector.detect(data(0, ra, o1, rx, o2, rb, o3));

      assertEquals(3, pairs.size(), pairs.toString());
      assertPlainAdjacent(pairs.get(0), o1, ra);
      Pair ordinary = pairs.get(1);
      assertSame(rx, ordinary.bounce());
      assertSame(o3, ordinary.original());
      assertFalse(ordinary.adjacent());
      assertFalse(ordinary.ambiguous());
      assertPlainAdjacent(pairs.get(2), o2, rb);
    }

    @Test
    @DisplayName("adjacency never raises the confidence")
    void neverRaisesConfidence() {
      StatementLine o = at("O", ST, 10, 0, "Pago", ACME, "-100");
      StatementLine r = at("R", ST, 20, 0, "Abono", ACME, "100");
      Pair counterpartyOnly = onlyPair(data(5, o, r));
      assertPlainAdjacent(counterpartyOnly, o, r);
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, counterpartyOnly.confidence());

      StatementLine o2 = at("O", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine late = at("R", ST, 20, 1, "Devolucion transferencia", "", "100");
      Pair laterDay = onlyPair(data(5, o2, late));
      assertPlainAdjacent(laterDay, o2, late);
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, laterDay.confidence());

      StatementLine sameDay = at("R", ST, 20, 0, "Devolucion transferencia", "", "100");
      Pair worded = onlyPair(data(0, o2, sameDay));
      assertTrue(worded.adjacent());
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, worded.confidence());

      StatementLine pis = at("O", ST, 10, 0, "Pago E2E1", "", "-100");
      StatementLine abono = at("R", ST, 20, 2, "Abono", "", "100");
      AccountData data = data(3, pis, abono);
      data.pisPaymentByEndToEnd.put("E2E1", "PAY-1");
      Pair linked = onlyPair(data);
      assertTrue(linked.adjacent());
      assertEquals(List.of(BankBounceDetector.SIGNAL_PIS_LINK), linked.signals());
      assertEquals(BankBounceDetector.CONFIDENCE_HIGH, linked.confidence());
    }

    @Test
    @DisplayName("lines reserved by adjacency are unavailable to the ordinary rules")
    void reservedLinesAreUnavailable() {
      // R2 comes first, so without adjacency its group would take O1 before R1's.
      StatementLine r2 = at("R2", "ST-2", 10, 1, "Devolucion", "", "100");
      StatementLine o1 = at("O1", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine r1 = at("R1", ST, 20, 0, "Devolucion", "", "100");

      Pair pair = onlyPair(data(0, r2, o1, r1));

      assertPlainAdjacent(pair, o1, r1);
    }

    @Test
    @DisplayName("only the real neighbour counts; gaps do not; another or no statement never")
    void whatIsANeighbour() {
      StatementLine o = at("O", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine fee = at("F", ST, 20, 0, "Comision", "", "-7");
      StatementLine r = at("R", ST, 30, 0, "Devolucion transferencia", "", "100");
      Pair blocked = onlyPair(data(0, o, fee, r));
      assertSame(o, blocked.original());
      assertFalse(blocked.adjacent(), "a non-qualifying line in between separates them");

      StatementLine farR = at("R", ST, 500, 0, "Devolucion transferencia", "", "100");
      assertPlainAdjacent(onlyPair(data(0, o, farR)), o, farR);

      StatementLine otherStatement = at("R", "ST-2", 20, 0, "Devolucion transferencia", "", "100");
      assertFalse(onlyPair(data(0, o, otherStatement)).adjacent());

      StatementLine noStatementO = new StatementLine("O", day(0), 10, "Transferencia", "",
          new BigDecimal("-100"), true);
      StatementLine noStatementR = new StatementLine("R", day(0), 20, "Devolucion transferencia",
          "", new BigDecimal("100"), true);
      assertNull(noStatementR.statementId());
      Pair unknown = onlyPair(data(0, noStatementO, noStatementR));
      assertSame(noStatementO, unknown.original());
      assertFalse(unknown.adjacent());
    }

    @Test
    @DisplayName("final payment guard: a blocked adjacent pair is dropped, not re-paired")
    void finalGuardDropsABlockedAdjacentPair() {
      // O_A and O_B both fit the one open movement T1 (unattributable): each passes the guard
      // alone, but once both are paired no free line is left for T1, so both pairs are blocked.
      StatementLine oa = at("OA", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine r1 = at("R1", ST, 20, 0, "Devolucion", "", "100");
      StatementLine ob = at("OB", "ST-2", 10, 0, "Transferencia", "", "-100");
      StatementLine r2 = at("R2", "ST-2", 20, 0, "Devolucion", "", "100");
      StatementLine spare = at("OC", "ST-3", 10, -2, "Transferencia", "", "-100");
      AccountData data = data(0, oa, r1, ob, r2, spare);
      data.openTransactions.add(new OpenTransaction("T1", "PAY-1", day(0),
          new BigDecimal("-100"), "", 1));
      assertTrue(data.attributionOf(oa).unattributable());

      assertTrue(BankBounceDetector.detect(data).isEmpty(),
          "neither bounce is re-paired with OC, an eligible original adjacency did not choose");
    }

    @Test
    @DisplayName("final payment guard: an adjacent ambiguous pair narrowed to one stays adjacent")
    void finalGuardNarrowsAnAdjacentAmbiguousPair() {
      StatementLine oa = at("OA", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine r = at("R", ST, 20, 0, "Devolucion", "", "100");
      StatementLine oc = at("OC", ST, 30, 0, "Transferencia", "", "-100");
      AccountData data = data(0, oa, r, oc);
      data.openTransactions.add(new OpenTransaction("T1", "PAY-1", day(0),
          new BigDecimal("-100"), "", 1));

      Pair pair = onlyPair(data);

      assertPlainAdjacent(pair, oa, r);
      assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, pair.confidence(),
          "narrowed from an ambiguous pair: still for review despite same-day wording");
    }

    @Test
    @DisplayName("pairs come in loaded order of their bounce line; equal line numbers keep it")
    void orderAndTies() {
      StatementLine oq = at("OQ", "ST-3", 10, 0, "Transferencia", "", "-50");
      StatementLine rq = at("RQ", "ST-2", 10, 1, "Devolucion", "", "50");
      StatementLine o = at("O", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine r = at("R", ST, 20, 0, "Devolucion", "", "100");
      List<Pair> pairs = BankBounceDetector.detect(data(0, oq, rq, o, r));
      assertEquals(2, pairs.size());
      assertSame(rq, pairs.get(0).bounce(), "the ordinary pair's bounce is loaded first");
      assertFalse(pairs.get(0).adjacent());
      assertSame(r, pairs.get(1).bounce());
      assertTrue(pairs.get(1).adjacent());

      // All on line 0: neighbours follow the loaded order, so A (not B) is R's neighbour.
      StatementLine a = at("A", ST, 0, 0, "Transferencia", "", "-100");
      StatementLine r0 = at("R", ST, 0, 0, "Devolucion", "", "100");
      StatementLine fee = at("F", ST, 0, 0, "Comision", "", "-7");
      StatementLine b = at("B", ST, 0, 0, "Transferencia", "", "-100");
      assertPlainAdjacent(onlyPair(data(0, a, r0, fee, b)), a, r0);
    }

    @Test
    @DisplayName("ofCandidates: none → null, one → plain review, several → ambiguous; adjacent kept")
    void ofCandidates() {
      StatementLine r = at("R", ST, 20, 0, "Devolucion", "", "100");
      StatementLine a = at("A", ST, 10, 0, "Transferencia", "", "-100");
      StatementLine b = at("B", ST, 30, 0, "Transferencia", "", "-100");
      Candidate ca = new Candidate(a, List.of(BankBounceDetector.SIGNAL_RETURN_TEXT));
      Candidate cb = new Candidate(b, List.of(BankBounceDetector.SIGNAL_COUNTERPARTY));

      assertNull(BankBounceDetector.ofCandidates(r, List.of(), true));

      for (boolean adjacent : new boolean[] { true, false }) {
        Pair one = BankBounceDetector.ofCandidates(r, List.of(ca), adjacent);
        assertSame(a, one.original());
        assertFalse(one.ambiguous());
        assertTrue(one.candidates().isEmpty());
        assertEquals(ca.signals(), one.signals());
        assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, one.confidence(),
            "same-day wording, but it came out of an ambiguous pair");
        assertEquals(adjacent, one.adjacent());

        Pair several = BankBounceDetector.ofCandidates(r, List.of(ca, cb), adjacent);
        assertTrue(several.ambiguous());
        assertEquals(List.of(ca, cb), several.candidates());
        assertEquals(ca.signals(), several.signals(), "the preferred candidate's signals");
        assertEquals(BankBounceDetector.CONFIDENCE_REVIEW, several.confidence());
        assertEquals(adjacent, several.adjacent());
      }
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
    @DisplayName("toStatementLine reads the statement id; a line with no statement has none")
    void statementId() {
      FIN_BankStatementLine onStatement = dalLine("L1", "10", "0", "Pago", "", 30L);
      FIN_BankStatement statement = mock(FIN_BankStatement.class);
      when(statement.getId()).thenReturn("ST-9");
      when(onStatement.getBankStatement()).thenReturn(statement);
      FIN_BankStatementLine orphan = dalLine("L2", "10", "0", "Pago", "", 40L);

      try (MockedStatic<StatementLineDiscard> discard = mockStatic(StatementLineDiscard.class);
          MockedStatic<ReactivationSupport> groups = mockStatic(ReactivationSupport.class)) {
        assertEquals("ST-9", BankBounceDetector.toStatementLine(onStatement).statementId());
        assertNull(BankBounceDetector.toStatementLine(orphan).statementId(),
            "no statement: no neighbours, so no adjacency");
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
