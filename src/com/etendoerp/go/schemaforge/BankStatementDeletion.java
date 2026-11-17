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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;

/**
 * The guard of {@code ?action=delete} on a bank statement, kept out of
 * {@link BankStatementsHandler} because that class sits on Sonar's per-class method limit
 * (java:S1448).
 *
 * <p>With the {@code psd2-statement-import-settings} flag off (per account, see
 * {@link Psd2ImportSettingsFlag}) the rule is the one before ETP-6151: only drafts, and a 409 on a
 * bank-connected account. The rest of this description is the flag-on rule.
 *
 * <p>The rule depends on whether the statement's financial account is connected to its bank
 * through PSD2 / Salt Edge ({@link BankStatementsSupport#isBankConnected}):
 * <ul>
 *   <li><b>Not connected</b> — unchanged: only a draft can be deleted (a processed statement answers
 *       400 {@code BankStatementsHandler.MSG_NOT_DRAFT}), and never one with matched lines.</li>
 *   <li><b>Connected</b> — a draft is deleted as above. A processed statement is deleted too, unless
 *       it is posted ({@link BankStatementsHandler#MSG_STATEMENT_POSTED_NOT_DELETABLE}) or has
 *       matched lines ({@code BankStatementsHandler.MSG_HAS_MATCHED_LINES}); otherwise it is first
 *       reactivated with the very logic of {@code ?action=reactivate}
 *       ({@link BankStatementsHandler#reactivateStatement}), so the caller can then remove its lines
 *       and header in the same transaction. A connected account's statements are produced by the
 *       bank sync, which leaves them processed, so without this step none of them could ever be
 *       deleted. Whether the deleted movements come back is decided only by the account's
 *       "import from" date on the next synchronization; nothing records what was deleted.</li>
 * </ul>
 *
 * <p>Branches on structure only (the account's connection status, the statement's processed and
 * posted flags), never on an entity identity.
 */
final class BankStatementDeletion {

  /**
   * Business rejection for {@code ?action=delete} on a statement of a bank-connected account with
   * the ETP-6151 flag off (the ETP-5111 rule). Kept in ENGLISH and byte-for-byte in sync with the
   * frontend's {@code BACKEND_ERROR_MAP} (lib/backendErrors.js), which matches it by EXACT text.
   */
  static final String MSG_STATEMENT_BANK_CONNECTED =
      "Statements from a bank-connected account cannot be deleted.";

  private BankStatementDeletion() {
  }

  /**
   * Loads the statement named by a delete request, without any state check: the state rule depends
   * on the account's connection and is applied by {@link #prepare}.
   *
   * @param id the statement id from the request body, possibly blank
   * @return the statement, never {@code null}
   * @throws OBException when the id is blank, or the statement does not exist or belongs to another
   *     tenant (both mapped to 400 by the caller, indistinguishably)
   */
  static FIN_BankStatement load(String id) {
    if (StringUtils.isBlank(id)) {
      throw new OBException(BankStatementsHandler.MSG_MISSING_FIELD + BankStatementsHandler.FIELD_ID);
    }
    FIN_BankStatement statement = TenantOwnership.loadOwned(FIN_BankStatement.class, id);
    if (statement == null) {
      throw new OBException(BankStatementsHandler.MSG_STATEMENT_NOT_FOUND + id);
    }
    return statement;
  }

  /**
   * Applies the delete rule to {@code statement} and, when the statement is a processed one of a
   * bank-connected account that may be deleted, reactivates it so its lines can be removed.
   *
   * <p>Every rejection happens before anything is written, so an early return needs no rollback.
   * The matched-lines probe and the reactivation go through the handler's own package-private
   * methods, the same seams the handler's tests already stub.
   *
   * @param handler   the handler serving the request; supplies {@code hasMatchedLines} and
   *                  {@code reactivateStatement}
   * @param statement the statement to delete, as returned by {@link #load}
   * @return {@code null} when the caller may go on and delete the (now draft) statement, or the 400
   *     response that rejects the delete
   * @throws OBException with {@code BankStatementsHandler.MSG_NOT_DRAFT} for a processed statement
   *     of an account that is not bank-connected (unchanged behaviour, mapped to 400 by the caller)
   */
  static NeoResponse prepare(BankStatementsHandler handler, FIN_BankStatement statement,
      boolean importSettingsOn) {
    boolean processed = Boolean.TRUE.equals(statement.isProcessed());
    if (!importSettingsOn) {
      return prepareLegacy(handler, statement, processed);
    }
    if (processed && !BankStatementsSupport.isBankConnected(statement.getAccount())) {
      throw new OBException(BankStatementsHandler.MSG_NOT_DRAFT);
    }
    if (processed && "Y".equals(statement.getPosted())) {
      return NeoResponse.error(400, BankStatementsHandler.MSG_STATEMENT_POSTED_NOT_DELETABLE);
    }
    // A draft can still carry matched lines (ETP-4921 — reactivation no longer requires them to
    // be unreconciled first), and so can a processed statement. Deleting the whole statement would
    // delete those lines too, which core's APRM_FIN_BNKSTM_LINE_CHECK_TRG never allows — reject up
    // front with a clear reason instead of surfacing whatever the trigger raises mid-delete.
    if (handler.hasMatchedLines(statement)) {
      return NeoResponse.error(400, BankStatementsHandler.MSG_HAS_MATCHED_LINES);
    }
    if (processed) {
      handler.reactivateStatement(statement);
      OBDal.getInstance().flush();
    }
    return null;
  }

  /**
   * The rule before ETP-6151, kept for an account the flag does not target: only a draft can be
   * deleted (a processed statement answers 400 {@link BankStatementsHandler#MSG_NOT_DRAFT}), a
   * draft of a bank-connected account answers 409 {@link #MSG_STATEMENT_BANK_CONNECTED}, and a
   * statement with matched lines answers 400. Nothing is reactivated.
   *
   * @param handler   the handler, for its matched-lines check
   * @param statement the statement to delete
   * @param processed whether the statement is processed
   * @return the rejection to answer, or {@code null} when the delete may go on
   */
  private static NeoResponse prepareLegacy(BankStatementsHandler handler,
      FIN_BankStatement statement, boolean processed) {
    if (processed) {
      throw new OBException(BankStatementsHandler.MSG_NOT_DRAFT);
    }
    if (BankStatementsSupport.isBankConnected(statement.getAccount())) {
      return NeoResponse.error(409, MSG_STATEMENT_BANK_CONNECTED);
    }
    if (handler.hasMatchedLines(statement)) {
      return NeoResponse.error(400, BankStatementsHandler.MSG_HAS_MATCHED_LINES);
    }
    return null;
  }
}
