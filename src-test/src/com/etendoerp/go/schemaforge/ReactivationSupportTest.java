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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * Mockito-driven unit tests for {@link ReactivationSupport}, the stateless helper bundle backing the
 * reconciliation reactivate flow extracted from {@link ReconciliationHandler}. Targets the branches
 * not already exercised through {@code ReconciliationHandlerTest} (so the new file clears the 80%
 * line-coverage gate): the linked branch of {@code unmatchBankStatementLine}, the not-found branch of
 * {@code anchorOf}, the cross-statement guard of {@code canCollapse}, the both-sided amount netting of
 * {@code collapseSiblings}, the non-empty / error branches of {@code currentBalance}, and the
 * model-not-loaded branches of {@code extensionProperty} / {@code markAutoCreated}.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ReactivationSupportTest {

  private static final String ACC_ID = "acc-1";

  /** Stubs {@code ModelProvider.getInstance().getEntity(entity).getPropertyByColumnName(col,false)}. */
  private void stubProperty(MockedStatic<ModelProvider> mp, String entityName, String column,
      String propName) {
    ModelProvider provider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    mp.when(ModelProvider::getInstance).thenReturn(provider);
    when(provider.getEntity(entityName)).thenReturn(entity);
    when(entity.getPropertyByColumnName(eq(column), eq(false))).thenReturn(prop);
    when(prop.getName()).thenReturn(propName);
  }

  // ── currentBalance ────────────────────────────────────────────────────────

  /** With remaining reconciliations, the balance is the ending balance of the most recent one. */
  @Test
  public void testCurrentBalanceReturnsEndingBalance() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getEndingBalance()).thenReturn(new BigDecimal("123.45"));

    try (MockedStatic<ReconciliationRemovalUtil> util =
        mockStatic(ReconciliationRemovalUtil.class)) {
      util.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(account))
          .thenReturn(Collections.singletonList(rec));

      assertEquals(0,
          new BigDecimal("123.45").compareTo(ReactivationSupport.currentBalance(account)));
    }
  }

  /** No remaining reconciliations → zero balance. */
  @Test
  public void testCurrentBalanceEmptyReturnsZero() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    try (MockedStatic<ReconciliationRemovalUtil> util =
        mockStatic(ReconciliationRemovalUtil.class)) {
      util.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(account))
          .thenReturn(Collections.emptyList());

      assertEquals(0, BigDecimal.ZERO.compareTo(ReactivationSupport.currentBalance(account)));
    }
  }

  /** currentBalance is decorative: a lookup failure is swallowed and zero is returned. */
  @Test
  public void testCurrentBalanceSwallowsErrors() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(ACC_ID);
    try (MockedStatic<ReconciliationRemovalUtil> util =
        mockStatic(ReconciliationRemovalUtil.class)) {
      util.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(account))
          .thenThrow(new RuntimeException("boom"));

      assertEquals(0, BigDecimal.ZERO.compareTo(ReactivationSupport.currentBalance(account)));
    }
  }

  // ── restoreNotClearedStatus ─────────────────────────────────────────────────

  private FIN_FinaccTransaction trx(BigDecimal deposit, BigDecimal payment, String status) {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    when(t.getDepositAmount()).thenReturn(deposit);
    when(t.getPaymentAmount()).thenReturn(payment);
    when(t.getStatus()).thenReturn(status);
    return t;
  }

  /** An inflow (deposit ≥ payment) wrongly left in PWNC is restored to RDNC and saved. */
  @Test
  public void testRestoreNotClearedStatusInflowToRdnc() {
    FIN_FinaccTransaction t = trx(new BigDecimal("25.30"), BigDecimal.ZERO, "PWNC");
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      ReactivationSupport.restoreNotClearedStatus(t);
      verify(t).setStatus("RDNC");
      verify(dal).save(t);
    }
  }

  /** An outflow wrongly left in RDNC is restored to PWNC. */
  @Test
  public void testRestoreNotClearedStatusOutflowToPwnc() {
    FIN_FinaccTransaction t = trx(BigDecimal.ZERO, new BigDecimal("10.00"), "RDNC");
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      ReactivationSupport.restoreNotClearedStatus(t);
      verify(t).setStatus("PWNC");
    }
  }

  /** A transaction already in the correct status is not rewritten (idempotent). */
  @Test
  public void testRestoreNotClearedStatusIdempotent() {
    FIN_FinaccTransaction t = trx(new BigDecimal("5.00"), BigDecimal.ZERO, "RDNC");
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      ReactivationSupport.restoreNotClearedStatus(t);
      verify(t, never()).setStatus(any());
      verify(dal, never()).save(any());
    }
  }

  // ── unmatchBankStatementLine ────────────────────────────────────────────────

  /** When a statement line still points at the transaction, its link is cleared and saved. */
  @Test
  public void testUnmatchBankStatementLineClearsLink() {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    FIN_BankStatementLine bsl = mock(FIN_BankStatementLine.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<FIN_BankStatementLine> crit = mock(OBCriteria.class);
      when(dal.createCriteria(FIN_BankStatementLine.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setMaxResults(eq(1))).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(bsl);

      ReactivationSupport.unmatchBankStatementLine(t);

      verify(bsl).setFinancialAccountTransaction(null);
      verify(dal).save(bsl);
    }
  }

  // ── anchorOf ─────────────────────────────────────────────────────────────────

  private FIN_BankStatementLine lineWithId(String id) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    return line;
  }

  /** The selected line is returned when it is among the siblings. */
  @Test
  public void testAnchorOfFindsSelectedLine() {
    FIN_BankStatementLine a = lineWithId("A");
    FIN_BankStatementLine b = lineWithId("B");
    assertSame(b, ReactivationSupport.anchorOf(b, Arrays.asList(a, b)));
  }

  /** When the selected line is not in the siblings, the line itself is the anchor. */
  @Test
  public void testAnchorOfFallsBackToLine() {
    FIN_BankStatementLine a = lineWithId("A");
    FIN_BankStatementLine other = lineWithId("Z");
    assertSame(other, ReactivationSupport.anchorOf(other, Collections.singletonList(a)));
  }

  // ── canCollapse ──────────────────────────────────────────────────────────────

  private FIN_BankStatementLine groupedLine(String id, FIN_BankStatement statement,
      FIN_FinaccTransaction matched) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getBankStatement()).thenReturn(statement);
    when(line.getFinancialAccountTransaction()).thenReturn(matched);
    return line;
  }

  /** All siblings in the same statement and unmatched → collapsible. */
  @Test
  public void testCanCollapseAllUnmatchedSameStatement() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.getId()).thenReturn("BST-1");
    FIN_BankStatementLine a = groupedLine("A", st, null);
    FIN_BankStatementLine b = groupedLine("B", st, null);
    org.junit.Assert.assertTrue(ReactivationSupport.canCollapse(a, Arrays.asList(a, b)));
  }

  /** A sibling belonging to another statement blocks the collapse. */
  @Test
  public void testCanCollapseRejectsForeignStatement() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.getId()).thenReturn("BST-1");
    FIN_BankStatement other = mock(FIN_BankStatement.class);
    when(other.getId()).thenReturn("BST-2");
    FIN_BankStatementLine a = groupedLine("A", st, null);
    FIN_BankStatementLine b = groupedLine("B", other, null);
    org.junit.Assert.assertFalse(ReactivationSupport.canCollapse(a, Arrays.asList(a, b)));
  }

  /** A sibling still linked to a transaction blocks the collapse. */
  @Test
  public void testCanCollapseRejectsLinkedSibling() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.getId()).thenReturn("BST-1");
    FIN_FinaccTransaction linked = mock(FIN_FinaccTransaction.class);
    when(linked.getId()).thenReturn("T-1");
    FIN_BankStatementLine a = groupedLine("A", st, null);
    FIN_BankStatementLine b = groupedLine("B", st, linked);
    org.junit.Assert.assertFalse(ReactivationSupport.canCollapse(a, Arrays.asList(a, b)));
  }

  // ── collapseSiblings (drives applyBankStatementAmounts both-sided branch) ─────

  /**
   * Collapsing a group whose summed credit and debit are both non-zero nets them into a single side:
   * 30 credit − 10 debit → 20 credit on the anchor; the non-anchor sibling is removed and the marker
   * cleared.
   */
  @Test
  public void testCollapseSiblingsNetsMixedAmounts() {
    FIN_BankStatement st = mock(FIN_BankStatement.class);
    when(st.isProcessed()).thenReturn(Boolean.FALSE);
    FIN_BankStatementLine anchor = mock(FIN_BankStatementLine.class);
    when(anchor.getId()).thenReturn("A");
    when(anchor.getBankStatement()).thenReturn(st);
    when(anchor.getCramount()).thenReturn(new BigDecimal("30"));
    when(anchor.getDramount()).thenReturn(BigDecimal.ZERO);
    FIN_BankStatementLine sib = mock(FIN_BankStatementLine.class);
    when(sib.getId()).thenReturn("B");
    when(sib.getCramount()).thenReturn(BigDecimal.ZERO);
    when(sib.getDramount()).thenReturn(new BigDecimal("10"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubProperty(mp, FIN_BankStatementLine.ENTITY_NAME, "EM_ETGO_Match_Group_ID", "matchGroupId");

      ReactivationSupport.collapseSiblings(anchor, Arrays.asList(anchor, sib));

      verify(dal).remove(sib);
      verify(anchor).setCramount(new BigDecimal("20"));
      verify(anchor).setDramount(BigDecimal.ZERO);
      verify(anchor).setFinancialAccountTransaction(null);
      verify(anchor).set("matchGroupId", null);
      // setProcessed(false) is called twice: once to unlock, once to restore (wasProcessed=false).
      verify(st, org.mockito.Mockito.atLeastOnce()).setProcessed(false);
    }
  }

  // ── extensionProperty / markAutoCreated (model-not-loaded branches) ───────────

  /** When the column is not in the model, extensionProperty returns null. */
  @Test
  public void testExtensionPropertyAbsentReturnsNull() {
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      ModelProvider provider = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      mp.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(FIN_FinaccTransaction.ENTITY_NAME)).thenReturn(entity);
      when(entity.getPropertyByColumnName(eq("EM_ETGO_Auto_Created"), eq(false))).thenReturn(null);

      assertNull(ReactivationSupport.extensionProperty(
          FIN_FinaccTransaction.ENTITY_NAME, "EM_ETGO_Auto_Created"));
    }
  }

  /** markAutoCreated degrades gracefully (no set) when the flag column is not in the model. */
  @Test
  public void testMarkAutoCreatedColumnAbsentDoesNothing() {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      ModelProvider provider = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      mp.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(FIN_FinaccTransaction.ENTITY_NAME)).thenReturn(entity);
      when(entity.getPropertyByColumnName(eq("EM_ETGO_Auto_Created"), eq(false))).thenReturn(null);

      ReactivationSupport.markAutoCreated(t);

      verify(t, never()).set(any(), any());
    }
  }

  /** markAutoCreated sets the resolved property to TRUE when the column exists. */
  @Test
  public void testMarkAutoCreatedSetsFlag() {
    FIN_FinaccTransaction t = mock(FIN_FinaccTransaction.class);
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      stubProperty(mp, FIN_FinaccTransaction.ENTITY_NAME, "EM_ETGO_Auto_Created", "eTGOAutoCreated");
      ReactivationSupport.markAutoCreated(t);
      verify(t).set("eTGOAutoCreated", Boolean.TRUE);
    }
  }

  // ── readMatchGroupId / clearMatchGroupId ─────────────────────────────────────

  /** A null line short-circuits readMatchGroupId to null. */
  @Test
  public void testReadMatchGroupIdNullLine() {
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      stubProperty(mp, FIN_BankStatementLine.ENTITY_NAME, "EM_ETGO_Match_Group_ID", "matchGroupId");
      assertNull(ReactivationSupport.readMatchGroupId(null));
    }
  }

  /** readMatchGroupId returns the trimmed marker value when present. */
  @Test
  public void testReadMatchGroupIdReturnsValue() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.get("matchGroupId")).thenReturn("GRP-1");
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      stubProperty(mp, FIN_BankStatementLine.ENTITY_NAME, "EM_ETGO_Match_Group_ID", "matchGroupId");
      assertEquals("GRP-1", ReactivationSupport.readMatchGroupId(line));
    }
  }

  /** clearMatchGroupId resets the marker to null on the resolved property. */
  @Test
  public void testClearMatchGroupId() {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      stubProperty(mp, FIN_BankStatementLine.ENTITY_NAME, "EM_ETGO_Match_Group_ID", "matchGroupId");
      ReactivationSupport.clearMatchGroupId(line);
      verify(line).set("matchGroupId", null);
    }
  }
}
