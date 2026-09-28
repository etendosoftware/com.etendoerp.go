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

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

/**
 * ETP-5468 — keeps the Etendo GO reconciliation actions from silently finalizing a draft
 * reconciliation they did not build.
 *
 * <p><b>Why a draft can be "foreign".</b> No Etendo GO action leaves a draft behind on success:
 * {@link ReconciliationFlowSupport#compose} creates a fresh draft, matches into it and processes it
 * in the same request (rolling back on error), and {@code applySuggestions} does the same with one
 * shared document. So a draft that already holds transactions when an action STARTS was made by
 * somebody else: Core's APRM flows (Classic "Match Statement", or the hidden "Add Transaction" /
 * "Find Transactions" buttons, which were reachable through NEO and MCP until ETP-5468 curated
 * those two out — "Match Statement" itself is still exposed, unchanged), or the pre-ETP-4951
 * "Reactivar", which put a reconciliation back to draft on purpose while keeping its
 * line-to-transaction links. Nobody confirmed those matches, so processing them is not this
 * action's call to make.</p>
 *
 * <p><b>The rule</b>, consistent with {@code compose}'s "process only what I matched" design:</p>
 * <ul>
 *   <li>An action that NEEDS a document to match into ({@code applySuggestions}) may adopt an
 *       EMPTY draft — Classic's Match Statement popup leaves one on open, and adopting it is what
 *       Core itself does — but never one that already holds transactions: it takes a fresh draft
 *       instead, so it processes exactly the matches it made. See {@link #isReusable}.</li>
 *   <li>An action that must process every draft to proceed ({@code undoReconciliation}: Core
 *       refuses to reactivate while any draft exists, {@code APRM_DraftReconciliationExists})
 *       refuses when a draft other than its own target holds transactions, with
 *       {@link #MSG_FOREIGN_DRAFT_PREFIX}. It can neither finalize those matches nor discard them
 *       without a human decision. Empty drafts keep being processed as before. See
 *       {@link #requireNoForeignDraft}.</li>
 * </ul>
 */
final class ReconciliationDraftGuard {

  private static final Logger log = LogManager.getLogger(ReconciliationDraftGuard.class);

  /**
   * Refusal text. Split around the document number so the SPA can translate it with a
   * parameterized matcher ({@code backendError.foreignDraftReconciliation} in
   * {@code tools/app-shell/src/lib/backendErrors.js}) — keep both halves in sync with it.
   */
  static final String MSG_FOREIGN_DRAFT_PREFIX = "Reconciliation ";
  static final String MSG_FOREIGN_DRAFT_SUFFIX =
      " is an unconfirmed draft that already holds matched movements."
          + " Review it before undoing a reconciliation on this account.";

  private ReconciliationDraftGuard() {
  }

  /**
   * Whether {@code draft} may be adopted as the document an action matches into: only when it
   * exists and holds no transaction yet.
   *
   * @param draft the account's latest draft reconciliation, or {@code null}
   * @return {@code true} when {@code draft} is non-null and empty
   */
  static boolean isReusable(FIN_Reconciliation draft) {
    if (draft == null) {
      return false;
    }
    if (holdsTransactions(draft)) {
      log.warn("Draft reconciliation {} ({}) already holds {} transaction(s) nobody confirmed in "
          + "Etendo GO; not adopting it, a fresh draft is used instead (ETP-5468).",
          draft.getId(), draft.getDocumentNo(), draft.getFINFinaccTransactionList().size());
      return false;
    }
    return true;
  }

  /**
   * Refuses when any draft other than {@code target} already holds transactions.
   *
   * @param drafts the account's draft reconciliations (may be {@code null})
   * @param target the reconciliation the caller is about to undo; excluded from the check, since
   *               processing it and immediately removing it finalizes nothing
   * @throws OBException naming the first foreign draft found
   */
  static void requireNoForeignDraft(List<FIN_Reconciliation> drafts, FIN_Reconciliation target) {
    if (drafts == null) {
      return;
    }
    String targetId = target != null ? target.getId() : null;
    for (FIN_Reconciliation draft : drafts) {
      if (draft == null || draft.getId().equals(targetId) || !holdsTransactions(draft)) {
        continue;
      }
      log.warn("Refusing to process foreign draft reconciliation {} ({}) with {} transaction(s) "
          + "(ETP-5468).", draft.getId(), draft.getDocumentNo(),
          draft.getFINFinaccTransactionList().size());
      throw new OBException(foreignDraftMessage(draft.getDocumentNo()));
    }
  }

  /**
   * The refusal message for a foreign draft.
   *
   * @param documentNo the draft's document number
   * @return the full message, as {@link #MSG_FOREIGN_DRAFT_PREFIX} + number +
   *         {@link #MSG_FOREIGN_DRAFT_SUFFIX}
   */
  static String foreignDraftMessage(String documentNo) {
    return MSG_FOREIGN_DRAFT_PREFIX + documentNo + MSG_FOREIGN_DRAFT_SUFFIX;
  }

  private static boolean holdsTransactions(FIN_Reconciliation rec) {
    List<?> transactions = rec.getFINFinaccTransactionList();
    return transactions != null && !transactions.isEmpty();
  }
}
