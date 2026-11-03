/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Unit tests for {@link BankStatementAggregates}.
 *
 * <p>Exercises the full recount ({@code recompute}) and the in-flight delta
 * recount ({@code recomputeWithDelta}) entirely offline: {@link OBDal},
 * {@link ModelProvider} and the criteria are mocked, so the tests assert exactly
 * which aggregate columns get written and what status is derived, without a DB.
 *
 * <p>A discarded line (PSD2 flag {@code EM_PSD2_Is_Discarded}) is resolved, not matched: it is
 * counted in {@code EM_ETGO_DISCARDED_COUNT}, the status treats it as resolved, and the totals
 * still include it. By default neither the flag column nor the discarded-count column is in the
 * mocked model; the tests that need them call {@link #withDiscardFlagColumn()} and
 * {@link #withDiscardedCountColumn()}.
 *
 * @covers com.etendoerp.go.schemaforge.BankStatementAggregates
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankStatementAggregatesTest {

  private MockedStatic<ModelProvider> mockedModelProvider;
  private MockedStatic<OBDal> mockedOBDal;

  private static final String DISCARD_FLAG = "psd2IsDiscarded";
  private static final String DISCARDED_COUNT = "discardedCount";

  private OBDal obDal;
  private ModelProvider modelProvider;
  private Entity bsEntity;
  private Property lineCountProp;
  private Property matchedCountProp;
  private Property totalInProp;
  private Property totalOutProp;
  private Property statusProp;

  @BeforeEach
  void setUp() {
    bsEntity = mock(Entity.class);
    lineCountProp = propNamed("lineCount");
    matchedCountProp = propNamed("matchedCount");
    totalInProp = propNamed("totalIn");
    totalOutProp = propNamed("totalOut");
    statusProp = propNamed("status");

    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_LINE_COUNT)).thenReturn(lineCountProp);
    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_MATCHED_COUNT)).thenReturn(matchedCountProp);
    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_TOTAL_IN)).thenReturn(totalInProp);
    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_TOTAL_OUT)).thenReturn(totalOutProp);
    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_STATUS)).thenReturn(statusProp);

    modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntity(FIN_BankStatement.ENTITY_NAME)).thenReturn(bsEntity);
    mockedModelProvider = mockStatic(ModelProvider.class);
    mockedModelProvider.when(ModelProvider::getInstance).thenReturn(modelProvider);

    obDal = mock(OBDal.class);
    mockedOBDal = mockStatic(OBDal.class);
    mockedOBDal.when(OBDal::getInstance).thenReturn(obDal);
  }

  @AfterEach
  void tearDown() {
    mockedModelProvider.close();
    mockedOBDal.close();
    Mockito.framework().clearInlineMocks();
  }

  // ── recompute ──────────────────────────────────────────────────────────────

  @Test
  void recomputeNoOpOnNullStatement() {
    BankStatementAggregates.recompute(null);
    verify(obDal, never()).save(any());
  }

  @Test
  void recomputeDraftStatementIsDraftRegardlessOfMatching() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.FALSE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true),
        line("0", "40.00", false)));

    BankStatementAggregates.recompute(st);

    verify(st).set("lineCount", 2L);
    verify(st).set("matchedCount", 1L);
    verify(st).set("totalIn", new BigDecimal("100.00"));
    verify(st).set("totalOut", new BigDecimal("40.00"));
    verify(st).set("status", "DRAFT");
    verify(obDal).save(st);
  }

  @Test
  void recomputeProcessedPartialWhenSomeMatched() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true),
        line("0", "40.00", false)));

    BankStatementAggregates.recompute(st);

    verify(st).set("matchedCount", 1L);
    verify(st).set("status", "PARTIAL");
  }

  @Test
  void recomputeProcessedReconciledWhenAllMatched() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true),
        line("0", "40.00", true)));

    BankStatementAggregates.recompute(st);

    verify(st).set("matchedCount", 2L);
    verify(st).set("status", "RECONCILED");
  }

  @Test
  void recomputeProcessedPendingWhenNoneMatched() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Collections.singletonList(line("0", "40.00", false)));

    BankStatementAggregates.recompute(st);

    verify(st).set("status", "PENDING");
  }

  @Test
  void recomputeNoOpWhenAggregateColumnsAbsentFromModel() {
    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_STATUS)).thenReturn(null);
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Collections.singletonList(line("10.00", "0", true)));

    BankStatementAggregates.recompute(st);

    verify(st, never()).set(any(), any());
    verify(obDal, never()).save(any());
  }

  // ── recomputeWithDelta ───────────────────────────────────────────────────────

  @Test
  void recomputeWithDeltaFoldsInFlightNewLine() {
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    when(parent.isProcessed()).thenReturn(Boolean.TRUE);
    // One other already-persisted, matched line (100 in).
    stubLineQuery(Collections.singletonList(line("100.00", "0", true)));

    FIN_BankStatementLine inFlight = mock(FIN_BankStatementLine.class);
    when(inFlight.getId()).thenReturn("new-line");
    EntityPersistenceEvent event = deltaEvent("0", "60.00", false);

    BankStatementAggregates.recomputeWithDelta(parent, inFlight, BankStatementAggregates.Op.NEW, event);

    // other (100 in, matched) + in-flight (60 out, unmatched) → 2 lines, 1 matched, partial.
    verify(parent).set("lineCount", 2L);
    verify(parent).set("matchedCount", 1L);
    verify(parent).set("totalIn", new BigDecimal("100.00"));
    verify(parent).set("totalOut", new BigDecimal("60.00"));
    verify(parent).set("status", "PARTIAL");
    verify(obDal).save(parent);
  }

  @Test
  void recomputeWithDeltaExcludesDeletedLine() {
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    when(parent.isProcessed()).thenReturn(Boolean.TRUE);
    // The remaining (other) line is matched; the deleted one is not folded in.
    stubLineQuery(Collections.singletonList(line("100.00", "0", true)));

    FIN_BankStatementLine deleted = mock(FIN_BankStatementLine.class);
    when(deleted.getId()).thenReturn("gone");
    EntityPersistenceEvent event = mock(EntityPersistenceEvent.class);

    BankStatementAggregates.recomputeWithDelta(parent, deleted, BankStatementAggregates.Op.DELETE, event);

    verify(parent).set("lineCount", 1L);
    verify(parent).set("matchedCount", 1L);
    verify(parent).set("status", "RECONCILED");
  }

  @Test
  void recomputeWithDeltaNoOpOnNullParent() {
    BankStatementAggregates.recomputeWithDelta(null, mock(FIN_BankStatementLine.class),
        BankStatementAggregates.Op.UPDATE, mock(EntityPersistenceEvent.class));
    verify(obDal, never()).save(any());
  }

  // ── discarded lines are resolved, not matched ───────────────────────────────

  @Test
  void recomputeWritesMatchedDiscardedCountsAndStatusAndKeepsDiscardedLinesInTotals() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true, false),
        line("0", "40.00", false, true),
        line("40.00", "0", false, true),
        line("0", "25.00", false, false)));

    BankStatementAggregates.recompute(st);

    verify(st).set("lineCount", 4L);
    verify(st).set("matchedCount", 1L);
    verify(st).set(DISCARDED_COUNT, 2L);
    verify(st).set("totalIn", new BigDecimal("140.00"));
    verify(st).set("totalOut", new BigDecimal("65.00"));
    verify(st).set("status", "PARTIAL");
    verify(obDal).save(st);
  }

  @Test
  void recomputeIsReconciledWhenEveryLineIsMatchedOrDiscarded() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true, false),
        line("0", "40.00", false, true),
        line("40.00", "0", false, true)));

    BankStatementAggregates.recompute(st);

    verify(st).set("matchedCount", 1L);
    verify(st).set(DISCARDED_COUNT, 2L);
    verify(st).set("status", "RECONCILED");
  }

  @Test
  void recomputeCountsALineWithATransactionAsMatchedEvenWhenItIsFlaggedDiscarded() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true, true),
        line("0", "40.00", false, false)));

    BankStatementAggregates.recompute(st);

    verify(st).set("lineCount", 2L);
    verify(st).set("matchedCount", 1L);
    verify(st).set(DISCARDED_COUNT, 0L);
    verify(st).set("status", "PARTIAL");
  }

  @Test
  void recomputeWithoutTheDiscardedCountColumnWritesNothingForItAndIgnoresDiscardedLines() {
    withDiscardFlagColumn();
    // EM_ETGO_DISCARDED_COUNT is not in the model (a model older than the column).
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true, false),
        line("0", "40.00", false, true)));

    BankStatementAggregates.recompute(st);

    // Only the five original columns are written; the discarded line is not resolved.
    verify(st, times(5)).set(anyString(), any());
    verify(st, never()).set(eq(DISCARDED_COUNT), any());
    verify(st).set("lineCount", 2L);
    verify(st).set("matchedCount", 1L);
    verify(st).set("totalOut", new BigDecimal("40.00"));
    verify(st).set("status", "PARTIAL");
    verify(obDal).save(st);
  }

  @Test
  void recomputeWithDeltaDiscardOfTheInFlightLineTurnsPartialIntoReconciled() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    when(parent.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Collections.singletonList(line("100.00", "0", true, false)));
    FIN_BankStatementLine inFlight = mock(FIN_BankStatementLine.class);
    when(inFlight.getId()).thenReturn("bounced");

    BankStatementAggregates.recomputeWithDelta(parent, inFlight, BankStatementAggregates.Op.UPDATE,
        deltaEvent("0", "60.00", false, false));
    BankStatementAggregates.recomputeWithDelta(parent, inFlight, BankStatementAggregates.Op.UPDATE,
        deltaEvent("0", "60.00", false, true));

    InOrder order = inOrder(parent);
    order.verify(parent).set(DISCARDED_COUNT, 0L);
    order.verify(parent).set("status", "PARTIAL");
    order.verify(parent).set(DISCARDED_COUNT, 1L);
    order.verify(parent).set("status", "RECONCILED");
    verify(parent, times(2)).set("matchedCount", 1L);
    verify(parent, times(2)).set("totalOut", new BigDecimal("60.00"));
  }

  @Test
  void recomputeWithDeltaRestoreOfTheInFlightLineTurnsReconciledBackIntoPartial() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    when(parent.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Collections.singletonList(line("100.00", "0", true, false)));
    FIN_BankStatementLine inFlight = mock(FIN_BankStatementLine.class);
    when(inFlight.getId()).thenReturn("bounced");

    BankStatementAggregates.recomputeWithDelta(parent, inFlight, BankStatementAggregates.Op.UPDATE,
        deltaEvent("0", "60.00", false, true));
    BankStatementAggregates.recomputeWithDelta(parent, inFlight, BankStatementAggregates.Op.UPDATE,
        deltaEvent("0", "60.00", false, false));

    InOrder order = inOrder(parent);
    order.verify(parent).set(DISCARDED_COUNT, 1L);
    order.verify(parent).set("status", "RECONCILED");
    order.verify(parent).set(DISCARDED_COUNT, 0L);
    order.verify(parent).set("status", "PARTIAL");
  }

  @Test
  void recomputeWithDeltaCountsAnInFlightLineWithATransactionAsMatchedEvenWhenDiscarded() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    when(parent.isProcessed()).thenReturn(Boolean.TRUE);
    stubLineQuery(Collections.singletonList(line("0", "40.00", false, false)));
    FIN_BankStatementLine inFlight = mock(FIN_BankStatementLine.class);
    when(inFlight.getId()).thenReturn("in-flight");

    BankStatementAggregates.recomputeWithDelta(parent, inFlight, BankStatementAggregates.Op.UPDATE,
        deltaEvent("100.00", "0", true, true));

    verify(parent).set("matchedCount", 1L);
    verify(parent).set(DISCARDED_COUNT, 0L);
    verify(parent).set("status", "PARTIAL");
  }

  @Test
  void recomputeWithDeltaDeleteOfADiscardedLineDropsItFromEveryCount() {
    withDiscardFlagColumn();
    withDiscardedCountColumn();
    FIN_BankStatement parent = mock(FIN_BankStatement.class);
    when(parent.isProcessed()).thenReturn(Boolean.TRUE);
    // What remains: one matched line and the other, still discarded, line of the pair.
    stubLineQuery(Arrays.asList(
        line("100.00", "0", true, false),
        line("40.00", "0", false, true)));
    FIN_BankStatementLine deleted = mock(FIN_BankStatementLine.class);
    when(deleted.getId()).thenReturn("gone");
    EntityPersistenceEvent event = deltaEvent("0", "40.00", false, true);

    BankStatementAggregates.recomputeWithDelta(parent, deleted, BankStatementAggregates.Op.DELETE,
        event);

    verify(parent).set("lineCount", 2L);
    verify(parent).set("matchedCount", 1L);
    verify(parent).set(DISCARDED_COUNT, 1L);
    verify(parent).set("totalIn", new BigDecimal("140.00"));
    verify(parent).set("totalOut", BigDecimal.ZERO);
    verify(parent).set("status", "RECONCILED");
    verify(event, never()).getCurrentState(any());
  }

  @Test
  void isDiscardedInEventReadsTheInFlightFlag() {
    Property flag = withDiscardFlagColumn();
    EntityPersistenceEvent discarded = mock(EntityPersistenceEvent.class);
    when(discarded.getCurrentState(flag)).thenReturn(Boolean.TRUE);
    EntityPersistenceEvent restored = mock(EntityPersistenceEvent.class);
    when(restored.getCurrentState(flag)).thenReturn(Boolean.FALSE);
    EntityPersistenceEvent unset = mock(EntityPersistenceEvent.class);

    assertTrue(BankStatementAggregates.isDiscardedInEvent(discarded));
    assertFalse(BankStatementAggregates.isDiscardedInEvent(restored));
    assertFalse(BankStatementAggregates.isDiscardedInEvent(unset));
  }

  @Test
  void isDiscardedInEventIsFalseWhenThePsd2ColumnIsMissing() {
    Entity lineModel = mock(Entity.class);
    when(modelProvider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(lineModel);
    EntityPersistenceEvent event = mock(EntityPersistenceEvent.class);
    when(event.getCurrentState(any())).thenReturn(Boolean.TRUE);

    assertFalse(BankStatementAggregates.isDiscardedInEvent(event));
    verify(event, never()).getCurrentState(any());
  }

  @Test
  void isDiscardedInEventIsFalseWhenTheLineEntityIsMissing() {
    EntityPersistenceEvent event = mock(EntityPersistenceEvent.class);
    when(event.getCurrentState(any())).thenReturn(Boolean.TRUE);

    assertFalse(BankStatementAggregates.isDiscardedInEvent(event));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /**
   * Maps the PSD2 discard flag on the {@code FIN_BankStatementLine} model, the way the running
   * model does when PSD2 is installed.
   *
   * @return the flag's property
   */
  private Property withDiscardFlagColumn() {
    Entity lineModel = mock(Entity.class);
    Property flag = propNamed(DISCARD_FLAG);
    when(lineModel.getPropertyByColumnName(StatementLineDiscard.COL_IS_DISCARDED, false))
        .thenReturn(flag);
    when(modelProvider.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(lineModel);
    return flag;
  }

  /** Maps {@code EM_ETGO_DISCARDED_COUNT} on the {@code FIN_BankStatement} model. */
  private void withDiscardedCountColumn() {
    Property discardedCountProp = propNamed(DISCARDED_COUNT);
    when(bsEntity.getPropertyByColumnName(BankStatementAggregates.COL_DISCARDED_COUNT, false))
        .thenReturn(discardedCountProp);
  }

  private static Property propNamed(String name) {
    Property p = mock(Property.class);
    when(p.getName()).thenReturn(name);
    return p;
  }

  /** Stubs the active-lines criteria so {@code list()} returns {@code lines}. */
  @SuppressWarnings("unchecked")
  private void stubLineQuery(List<FIN_BankStatementLine> lines) {
    OBCriteria<FIN_BankStatementLine> crit = mock(OBCriteria.class);
    when(obDal.createCriteria(FIN_BankStatementLine.class)).thenReturn(crit);
    when(crit.list()).thenReturn(lines);
  }

  private static FIN_BankStatementLine line(String cr, String dr, boolean matched) {
    return line(cr, dr, matched, false);
  }

  /** A persisted line whose PSD2 discard flag (read through the DAL property) is {@code discarded}. */
  private static FIN_BankStatementLine line(String cr, String dr, boolean matched,
      boolean discarded) {
    FIN_BankStatementLine l = mock(FIN_BankStatementLine.class);
    when(l.get(DISCARD_FLAG)).thenReturn(discarded);
    when(l.getCramount()).thenReturn(new BigDecimal(cr));
    when(l.getDramount()).thenReturn(new BigDecimal(dr));
    when(l.getFinancialAccountTransaction())
        .thenReturn(matched ? mock(FIN_FinaccTransaction.class) : null);
    return l;
  }

  /** Builds a persistence event whose current state is the in-flight line's amounts. */
  private static EntityPersistenceEvent deltaEvent(String cr, String dr, boolean matched) {
    return deltaEvent(cr, dr, matched, false);
  }

  /**
   * Builds a persistence event whose current state is the in-flight line's amounts and discard
   * flag. The flag is answered for any property named {@link #DISCARD_FLAG}, so it reaches the
   * code only when the flag column is mapped ({@link #withDiscardFlagColumn()}).
   */
  private static EntityPersistenceEvent deltaEvent(String cr, String dr, boolean matched,
      boolean discarded) {
    Entity lineEntity = mock(Entity.class);
    Property crProp = mock(Property.class);
    Property drProp = mock(Property.class);
    Property txProp = mock(Property.class);
    when(lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_CRAMOUNT)).thenReturn(crProp);
    when(lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_DRAMOUNT)).thenReturn(drProp);
    when(lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION)).thenReturn(txProp);

    FIN_BankStatementLine target = mock(FIN_BankStatementLine.class);
    when(target.getEntity()).thenReturn(lineEntity);

    EntityPersistenceEvent event = mock(EntityPersistenceEvent.class);
    when(event.getTargetInstance()).thenReturn(target);
    when(event.getCurrentState(crProp)).thenReturn(new BigDecimal(cr));
    when(event.getCurrentState(drProp)).thenReturn(new BigDecimal(dr));
    when(event.getCurrentState(txProp)).thenReturn(matched ? mock(FIN_FinaccTransaction.class) : null);
    when(event.getCurrentState(argThat(p -> p != null && DISCARD_FLAG.equals(p.getName()))))
        .thenReturn(discarded);
    return event;
  }
}
