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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.dao.TransactionsDao;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;

/**
 * Unit tests for {@link ReconciliationDraftGuard} (ETP-5468, phase 1) and its two call sites in
 * {@link ReconciliationHandler}: {@code getOrCreateDraftReconciliation} (adopt only an EMPTY draft)
 * and {@code undoReconciliation} (refuse while a foreign draft holds transactions).
 */
@SuppressWarnings("java:S2187")
@DisplayName("ReconciliationDraftGuard (ETP-5468)")
class ReconciliationDraftGuardTest {

  private static final String DOC_NO = "REC-1000123";

  @AfterEach
  void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  private static FIN_Reconciliation draft(String id, String documentNo, int transactions) {
    FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
    when(rec.getId()).thenReturn(id);
    when(rec.getDocumentNo()).thenReturn(documentNo);
    List<FIN_FinaccTransaction> list = new ArrayList<>();
    for (int i = 0; i < transactions; i++) {
      list.add(mock(FIN_FinaccTransaction.class));
    }
    when(rec.getFINFinaccTransactionList()).thenReturn(list);
    return rec;
  }

  @Nested
  @DisplayName("isReusable")
  class IsReusable {

    @Test
    @DisplayName("null → not reusable (a fresh draft is created)")
    void nullIsNotReusable() {
      assertFalse(ReconciliationDraftGuard.isReusable(null));
    }

    @Test
    @DisplayName("an empty draft is reusable")
    void emptyDraftIsReusable() {
      assertTrue(ReconciliationDraftGuard.isReusable(draft("d1", DOC_NO, 0)));
    }

    @Test
    @DisplayName("a draft whose transaction list is null is treated as empty")
    void nullListIsReusable() {
      FIN_Reconciliation rec = mock(FIN_Reconciliation.class);
      when(rec.getFINFinaccTransactionList()).thenReturn(null);
      assertTrue(ReconciliationDraftGuard.isReusable(rec));
    }

    @Test
    @DisplayName("a draft already holding transactions is NOT reusable")
    void nonEmptyDraftIsNotReusable() {
      assertFalse(ReconciliationDraftGuard.isReusable(draft("d1", DOC_NO, 2)));
    }
  }

  @Nested
  @DisplayName("requireNoForeignDraft")
  class RequireNoForeignDraft {

    @Test
    @DisplayName("null / empty draft list passes")
    void nullOrEmptyPasses() {
      FIN_Reconciliation target = draft("t", "T", 3);
      assertDoesNotThrow(() -> ReconciliationDraftGuard.requireNoForeignDraft(null, target));
      assertDoesNotThrow(() -> ReconciliationDraftGuard.requireNoForeignDraft(
          Collections.emptyList(), target));
    }

    @Test
    @DisplayName("only empty drafts (and null entries) pass")
    void emptyDraftsPass() {
      List<FIN_Reconciliation> drafts = Arrays.asList(draft("d1", "A", 0), null,
          draft("d2", "B", 0));
      assertDoesNotThrow(() -> ReconciliationDraftGuard.requireNoForeignDraft(drafts,
          draft("t", "T", 1)));
    }

    @Test
    @DisplayName("the target itself is excluded even when it holds transactions")
    void targetExcluded() {
      FIN_Reconciliation target = draft("t", "T", 4);
      List<FIN_Reconciliation> drafts = List.of(draft("t", "T", 4), draft("d1", "A", 0));
      assertDoesNotThrow(() -> ReconciliationDraftGuard.requireNoForeignDraft(drafts, target));
    }

    @Test
    @DisplayName("a foreign draft holding transactions is refused, naming its document number")
    void foreignDraftRefused() {
      List<FIN_Reconciliation> drafts = List.of(draft("d0", "EMPTY", 0),
          draft("d1", DOC_NO, 2), draft("d2", "OTHER", 5));
      OBException ex = assertThrows(OBException.class,
          () -> ReconciliationDraftGuard.requireNoForeignDraft(drafts, draft("t", "T", 1)));
      assertEquals(ReconciliationDraftGuard.foreignDraftMessage(DOC_NO), ex.getMessage(),
          "the FIRST foreign draft is named");
    }

    @Test
    @DisplayName("with a null target every non-empty draft is foreign")
    void nullTarget() {
      List<FIN_Reconciliation> drafts = List.of(draft("d1", DOC_NO, 1));
      assertThrows(OBException.class,
          () -> ReconciliationDraftGuard.requireNoForeignDraft(drafts, null));
    }

    @Test
    @DisplayName("the message is prefix + documentNo + suffix, the skeleton the SPA matches")
    void messageShape() {
      String msg = ReconciliationDraftGuard.foreignDraftMessage(DOC_NO);
      assertEquals("Reconciliation " + DOC_NO + " is an unconfirmed draft that already holds "
          + "matched movements. Review it before undoing a reconciliation on this account.", msg);
      assertTrue(msg.startsWith(ReconciliationDraftGuard.MSG_FOREIGN_DRAFT_PREFIX));
      assertTrue(msg.endsWith(ReconciliationDraftGuard.MSG_FOREIGN_DRAFT_SUFFIX));
    }
  }

  // ── call sites in ReconciliationHandler ────────────────────────────────

  @Nested
  @DisplayName("ReconciliationHandler.getOrCreateDraftReconciliation")
  class GetOrCreateDraft {

    @Test
    @DisplayName("an EMPTY last draft is adopted (no new draft)")
    void adoptsEmptyDraft() {
      ReconciliationHandler handler = spy(new ReconciliationHandler());
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      FIN_Reconciliation empty = draft("d1", "A", 0);
      try (MockedStatic<TransactionsDao> dao = Mockito.mockStatic(TransactionsDao.class)) {
        dao.when(() -> TransactionsDao.getLastReconciliation(account, "N")).thenReturn(empty);
        assertSame(empty, handler.getOrCreateDraftReconciliation(account));
      }
      verify(handler, never()).addNewDraftReconciliation(any());
    }

    @Test
    @DisplayName("a NON-empty last draft is not adopted: a fresh draft is created (applySuggestions)")
    void nonEmptyDraftGetsFreshOne() {
      ReconciliationHandler handler = spy(new ReconciliationHandler());
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      FIN_Reconciliation foreign = draft("d1", DOC_NO, 3);
      FIN_Reconciliation fresh = mock(FIN_Reconciliation.class);
      doReturn(fresh).when(handler).addNewDraftReconciliation(account);
      try (MockedStatic<TransactionsDao> dao = Mockito.mockStatic(TransactionsDao.class)) {
        dao.when(() -> TransactionsDao.getLastReconciliation(account, "N")).thenReturn(foreign);
        assertSame(fresh, handler.getOrCreateDraftReconciliation(account));
      }
      verify(handler).addNewDraftReconciliation(account);
    }
  }

  @Nested
  @DisplayName("ReconciliationHandler.undoReconciliation")
  class UndoReconciliation {

    @Test
    @DisplayName("refuses before ANY write when another draft holds transactions")
    void refusesWithForeignDraft() {
      ReconciliationHandler handler = spy(new ReconciliationHandler());
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      FIN_Reconciliation target = draft("t", "T", 1);
      List<FIN_Reconciliation> drafts = List.of(target, draft("d1", DOC_NO, 2));
      try (MockedStatic<ReconciliationRemovalUtil> recUtil =
          mockStatic(ReconciliationRemovalUtil.class)) {
        recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(account))
            .thenReturn(drafts);

        OBException ex = assertThrows(OBException.class,
            () -> handler.undoReconciliation(account, target, target.getFINFinaccTransactionList()));
        assertEquals(ReconciliationDraftGuard.foreignDraftMessage(DOC_NO), ex.getMessage());

        recUtil.verify(() -> ReconciliationRemovalUtil.processAllReconciliationInDraft(any()),
            never());
        recUtil.verify(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(any()),
            never());
      }
    }

    @Test
    @DisplayName("empty drafts and the target itself keep being processed as before")
    void proceedsWithOnlyEmptyOrTargetDrafts() throws Exception {
      ReconciliationHandler handler = spy(new ReconciliationHandler());
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      FIN_Reconciliation target = draft("t", "T", 0);
      List<FIN_Reconciliation> drafts = List.of(target, draft("d1", "EMPTY", 0));
      try (MockedStatic<ReconciliationRemovalUtil> recUtil =
          mockStatic(ReconciliationRemovalUtil.class)) {
        recUtil.when(() -> ReconciliationRemovalUtil.getDraftReconciliation(account))
            .thenReturn(drafts);

        handler.undoReconciliation(account, target, Collections.emptyList());

        recUtil.verify(() -> ReconciliationRemovalUtil.processAllReconciliationInDraft(drafts));
        recUtil.verify(() -> ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(target));
      }
    }
  }

  @Nested
  @DisplayName("the refusal reaches the caller as a 400 through the shared POST envelope")
  class RefusalMapping {

    @Test
    @DisplayName("reactivate throwing the guard's OBException → 400 with the exact message + rollback")
    void mappedTo400() throws Exception {
      ReconciliationHandler handler = spy(new ReconciliationHandler());
      doNothing().when(handler).doRollbackAndClose();
      doThrow(new OBException(ReconciliationDraftGuard.foreignDraftMessage(DOC_NO)))
          .when(handler).reactivate(any());
      NeoContext ctx = NeoContext.builder()
          .specName("bank-reconciliation")
          .httpMethod("POST")
          .requestBody(new JSONObject().put("financialAccountId", "acc-1")
              .put("statementLineId", "line-1"))
          .build();

      NeoResponse response;
      try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
        response = ReconciliationHandlerSupport.handleReactivate(handler, ctx);
      }

      assertEquals(400, response.getHttpStatus());
      assertEquals(ReconciliationDraftGuard.foreignDraftMessage(DOC_NO),
          response.getBody().getJSONObject("error").getString("message"));
      verify(handler).doRollbackAndClose();
    }
  }
}
