/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Unit tests for {@link BankStatementLinePruner}.
 *
 * <p>Locks in the rule ported from Classic's
 * {@code FIN_BankStatementImport.saveFINBankStatementLines}: an imported line
 * with no amount on either side is dropped and the survivors are renumbered
 * 10, 20, 30… leaving no gap.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class BankStatementLinePrunerTest {

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  private static FIN_BankStatementLine line(long lineNo, String cr, String dr) {
    FIN_BankStatementLine l = mock(FIN_BankStatementLine.class);
    when(l.getLineNo()).thenReturn(lineNo);
    when(l.getCramount()).thenReturn(cr == null ? null : new BigDecimal(cr));
    when(l.getDramount()).thenReturn(dr == null ? null : new BigDecimal(dr));
    return l;
  }

  /**
   * A bare statement mock. Its lines are NOT set on the entity: the pruner reads
   * them through OBDal (see {@link #stubDal}), not through the statement's lazy
   * collection, which is unreliable right after the parser's saves.
   */
  private static FIN_BankStatement statement() {
    return mock(FIN_BankStatement.class);
  }

  /**
   * Wires the OBDal criteria the pruner uses to read the statement's lines back
   * from the DB (the entity's own lazy collection is unreliable right after the
   * parser's saves — see the pruner's readLines javadoc).
   */
  @SuppressWarnings("unchecked")
  private static OBDal stubDal(MockedStatic<OBDal> obDalMock, List<FIN_BankStatementLine> lines) {
    OBDal dal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    OBCriteria<FIN_BankStatementLine> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_BankStatementLine.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(lines);
    return dal;
  }

  @Test
  public void dropsTheZeroZeroLineAndRenumbersWithoutGaps() {
    // Mirrors the CSV used to verify the behaviour against Classic: the first
    // row has Amount OUT = 0 and Amount IN = 0, the rest carry an amount.
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine zero = line(10L, "0", "0");
    FIN_BankStatementLine credit = line(20L, "3500.00", "0");
    FIN_BankStatementLine debit = line(30L, "0", "98.00");
    lines.add(zero);
    lines.add(credit);
    lines.add(debit);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(2, r.getKept());
      assertEquals(1, r.getDiscarded());
      // No gap: the surviving lines take 10 and 20, not 20 and 30.
      verify(credit).setLineNo(10L);
      verify(debit).setLineNo(20L);
      verify(dal).remove(zero);
    }
  }

  @Test
  public void treatsNullAmountsAsZeroAndDropsTheLine() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine blank = line(10L, null, null);
    lines.add(blank);
    lines.add(line(20L, "10.00", "0"));
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(1, r.getKept());
      assertEquals(1, r.getDiscarded());
      verify(dal).remove(blank);
    }
  }

  /**
   * ETP-4954 (QA retest): the reported failure. A CSV line with BOTH amounts negative was imported
   * instead of rejected, and the stored pair then read back as a nonsensical positive: the row holds
   * {@code (cr = -20, dr = -50)} and {@code BankStatementsSupport#mapLineRow} collapses it to
   * {@code amount = cr - dr = +30}, which the grid renders as an Entrada of 30. The manual form
   * always rejected the same combination, so the two flows disagreed AND the import persisted junk.
   */
  @Test
  public void dropsALineWithBothAmountsNegative() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine bothNegative = line(10L, "-20.00", "-50.00");
    lines.add(bothNegative);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(0, r.getKept());
      assertEquals(1, r.getDiscarded());
      verify(dal).remove(bothNegative);
    }
  }

  /**
   * ETP-4954 (QA retest) — DELIBERATE DIVERGENCE FROM CLASSIC, decided with product.
   *
   * <p>This test replaces {@code keepsNegativeAmountsBecauseClassicDoesToo}, which asserted the
   * opposite and whose comment read: "Classic's condition is 'not both zero', not 'positive', so a
   * negative amount is a real movement and must survive. Rejecting negatives would be a new business
   * rule, not a consistency fix." That IS the new business rule now: a statement line never carries a
   * negative amount — a negative Salida is conceptually an Entrada — so any negative is rejected in
   * BOTH flows rather than silently netted into the opposite column. Verified safe against real data:
   * 0 of the 2.965 statement lines in the reference database carry a negative amount, across the CSV,
   * Cuaderno 43 and PSD2-sync origins.
   */
  @Test
  public void dropsALineWithASingleNegativeAmount() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine negative = line(10L, "-25.00", "0");
    lines.add(negative);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(0, r.getKept());
      assertEquals(1, r.getDiscarded());
      verify(dal).remove(negative);
    }
  }

  /**
   * The opposite-signs case QA documented separately: Salida positive, Entrada negative. Both flows
   * used to "net" it into a single value (net = Entrada − Salida, sign picking the column), which QA
   * flagged as consistent-but-undefined. Under the new rule the negative side alone rejects the line,
   * so nothing is netted anywhere.
   */
  @Test
  public void dropsALineWithOppositeSignsInsteadOfNettingIt() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine mixed = line(10L, "-20.00", "50.00");
    lines.add(mixed);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(0, r.getKept());
      assertEquals(1, r.getDiscarded());
      verify(dal).remove(mixed);
    }
  }

  /** A line with one positive amount and the other side at zero is the normal, valid shape. */
  @Test
  public void keepsALineWhoseOnlyAmountIsPositive() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine credit = line(10L, "150.00", "0");
    lines.add(credit);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(1, r.getKept());
      assertEquals(0, r.getDiscarded());
      verify(credit).setLineNo(10L);
      verify(dal, never()).remove(credit);
    }
  }

  /**
   * ETP-4954 (product decision) — the third clause of {@code hasUnusableAmounts}: EXACTLY ONE
   * SIDE. The predicate was renamed from {@code hasNoUsableAmount} for this reason: it no longer
   * only answers "is there an amount", it answers "are the amounts usable".
   *
   * <p>The clause arrived with no coverage at all — the whole suite passed with zero failures
   * when it was added, which means no existing case had ever fed the pruner a line with both
   * sides filled. It is trivially reachable on THIS path: the generic CSV importer fills
   * {@code cramount} and {@code dramount} from two independent columns
   * ({@code GenericCsvBankStatementImporter.saveLine}), so any file that populates both columns
   * on one row produces such a line.
   *
   * <p>What it used to do: {@code BankStatementsSupport#mapLineRow} collapses the pair into
   * {@code amount = cr - dr}, so {@code (cr = 30, dr = 100)} imported and then DISPLAYED as a
   * Salida of 70 — a movement the bank never reported.
   * {@code ReactivationSupport.applyBankStatementAmounts} already refuses to leave a line in that
   * state (it nets them onto one side, under Classic's sign normalization); the import path drops
   * the line instead, because a row a file arrived with is bad input rather than two records
   * being merged.
   */
  @Test
  public void dropsALineFilledOnBothSides() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine bothSides = line(10L, "30.00", "100.00");
    lines.add(bothSides);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(0, r.getKept());
      // Counted in the discarded total, so the import still reports what it left behind
      // through APRM_ZeroAmountNotInserted instead of dropping the row invisibly.
      assertEquals(1, r.getDiscarded());
      verify(dal).remove(bothSides);
      verify(bothSides, never()).setLineNo(any());
    }
  }

  /**
   * The case that MOTIVATED the clause. Two EQUAL sides clear every other rule — neither side is
   * zero, neither is negative — so the line was imported, and {@code mapLineRow} then collapsed
   * it to {@code 50 - 50 = 0}: a statement line displaying zero, which is exactly the state
   * {@link #dropsTheZeroZeroLineAndRenumbersWithoutGaps} rejects at the front door. Nothing else
   * in this class reaches it.
   */
  @Test
  public void dropsALineWithTwoEqualSidesThatWouldHaveReadBackAsZero() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine equalSides = line(10L, "50.00", "50.00");
    lines.add(equalSides);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(0, r.getKept());
      assertEquals(1, r.getDiscarded());
      verify(dal).remove(equalSides);
    }
  }

  /**
   * The DISCRIMINATOR. Without it the clause could just as well read "drop any line whose two
   * amount columns were both written", which would drop every ordinary line a CSV exports with
   * an explicit 0 in the unused column — the shape the import template itself ships
   * ({@code 150,00} out / {@code 0,00} in). A zero is not an amount, on either side.
   */
  @Test
  public void keepsALineWithOneAmountAndAnExplicitZeroOnTheOtherSide() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine credit = line(10L, "150.00", "0");
    FIN_BankStatementLine debit = line(20L, "0", "98.00");
    lines.add(credit);
    lines.add(debit);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(2, r.getKept());
      assertEquals(0, r.getDiscarded());
      verify(credit).setLineNo(10L);
      verify(debit).setLineNo(20L);
      verify(dal, never()).remove(credit);
      verify(dal, never()).remove(debit);
    }
  }

  /**
   * The renumbering half, with the both-filled line dropped from the MIDDLE of the file — the
   * position where a gap would actually show. Classic's contract is that the survivors come out
   * 10, 20, 30… with no hole, and a partially pruned import is still a successful import.
   */
  @Test
  public void renumbersContiguouslyWhenABothFilledLineIsDroppedInTheMiddle() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine first = line(10L, "100.00", "0");
    FIN_BankStatementLine bothSides = line(20L, "30.00", "100.00");
    FIN_BankStatementLine third = line(30L, "0", "75.00");
    lines.add(first);
    lines.add(bothSides);
    lines.add(third);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(2, r.getKept());
      assertEquals(1, r.getDiscarded());
      // 10, 20 — NOT 10, 30: the dropped middle line leaves no gap behind.
      verify(first).setLineNo(10L);
      verify(third).setLineNo(20L);
      verify(third, never()).setLineNo(30L);
      verify(dal).remove(bothSides);
      verify(dal, never()).remove(first);
      verify(dal, never()).remove(third);
    }
  }

  @Test
  public void reportsZeroKeptWhenEveryLineIsAmountLess() {
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine first = line(10L, "0", "0");
    FIN_BankStatementLine second = line(20L, "0", "0");
    lines.add(first);
    lines.add(second);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(0, r.getKept());
      assertEquals(2, r.getDiscarded());
      verify(dal).remove(first);
      verify(dal).remove(second);
    }
  }

  @Test
  public void reportsZeroKeptForAFileThatParsedNoLines() {
    // A CSV with only its header row: the parser saved nothing.
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatement statement = statement();
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubDal(obDalMock, lines);
      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);
      assertEquals(0, r.getKept());
      assertEquals(0, r.getDiscarded());
    }
  }

  @Test
  public void toleratesANullQueryResult() {
    List<FIN_BankStatementLine> lines = null;
    FIN_BankStatement statement = statement();
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      stubDal(obDalMock, lines);
      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);
      assertEquals(0, r.getKept());
      assertEquals(0, r.getDiscarded());
    }
  }
}
