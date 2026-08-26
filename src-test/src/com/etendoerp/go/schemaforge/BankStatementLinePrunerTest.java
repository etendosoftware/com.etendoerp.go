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

  @Test
  public void keepsNegativeAmountsBecauseClassicDoesToo() {
    // Classic's condition is "not both zero", not "positive", so a negative
    // amount is a real movement and must survive. Rejecting negatives would be
    // a new business rule, not a consistency fix.
    List<FIN_BankStatementLine> lines = new ArrayList<>();
    FIN_BankStatementLine negative = line(10L, "-25.00", "0");
    lines.add(negative);
    FIN_BankStatement statement = statement();

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = stubDal(obDalMock, lines);

      BankStatementLinePruner.PruneResult r =
          BankStatementLinePruner.pruneZeroAmountLines(statement);

      assertEquals(1, r.getKept());
      assertEquals(0, r.getDiscarded());
      verify(negative).setLineNo(10L);
      verify(dal, never()).remove(negative);
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
