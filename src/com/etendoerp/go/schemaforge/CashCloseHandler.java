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

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.dao.AdvPaymentMngtDao;
import org.openbravo.advpaymentmngt.dao.TransactionsDao;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;
import org.openbravo.model.financialmgmt.payment.FIN_ReconciliationLine_v;

import com.etendoerp.payment.removal.util.ReconciliationRemovalUtil;
import com.etendoerp.payment.removal.util.Utilities;

/**
 * NeoHandler that powers the "Cierre de caja" (cash close) screen shown instead of the bank
 * reconciliation split panel when the financial account is of type {@code Cash} (ETP-4795). It is
 * registered against the {@code cash-close} W spec via {@code ETGO_SF_ENTITY.Java_Qualifier =
 * "cashClose"}.
 *
 * <p>A cash account has no bank statements, so it cannot reuse {@code
 * APRM_MatchingUtility.matchBankStatementLine} (it requires a {@code FIN_BankStatementLine}) nor
 * {@code APRM_MatchingUtility.processReconciliation} (it recomputes and overwrites the ending
 * balance, but a cash close's declared balance is exactly the value the user typed and must be
 * preserved). Instead this handler mirrors the manual reconciliation flow that Classic's own
 * {@code org.openbravo.advpaymentmngt.ad_actionbutton.Reconciliation} servlet uses for the same
 * case: a transaction is attached to the reconciliation directly ({@code
 * trx.setReconciliation(draft)} + {@code trx.setStatus("RPPC")}), and the document is completed by
 * flipping its flags in place rather than delegating to {@code FIN_ReconciliationProcess}.
 *
 * <table border="1">
 *   <caption>Routes</caption>
 *   <tr><th>Method + action</th><th>Behaviour</th></tr>
 *   <tr>
 *     <td>{@code GET ?action=pending&accountId=X}</td>
 *     <td>Account info, opening balance, the account's GL Item Difference, the current draft (if
 *         any) and every not-yet-cleared transaction of the account.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST action=saveDraft}</td>
 *     <td>Creates or updates the account's draft reconciliation with the ticked movements and the
 *         declared balance, without completing it. Body: {@code { accountId, statementDate,
 *         declaredBalance, movementIds:[...] }}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST action=confirm}</td>
 *     <td>Same as {@code saveDraft}, then validates the close, posts the difference (if any)
 *         against the account's GL Item Difference, and completes the reconciliation.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST action=discardDraft}</td>
 *     <td>Removes the account's current draft so the user can start over. Body: {@code {
 *         accountId }}.</td>
 *   </tr>
 * </table>
 */
@Named("cashClose")
public class CashCloseHandler implements NeoHandler {

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";

  private static final String PARAM_ACTION = "action";
  static final String PARAM_ACCOUNT_ID = "accountId";

  private static final String ACTION_PENDING = "pending";
  private static final String ACTION_SAVE_DRAFT = "saveDraft";
  private static final String ACTION_CONFIRM = "confirm";
  private static final String ACTION_DISCARD_DRAFT = "discardDraft";

  /** Only accounts with this {@code FIN_Financial_Account.Type} may use this handler. */
  static final String TYPE_CASH = "C";

  static final String KEY_ACCOUNT_ID = "accountId";
  static final String KEY_STATEMENT_DATE = "statementDate";
  static final String KEY_DECLARED_BALANCE = "declaredBalance";
  static final String KEY_MOVEMENT_IDS = "movementIds";
  static final String KEY_ID = "id";

  static final String MSG_MISSING_PARAM = "Missing required parameter: ";
  static final String MSG_BODY_REQUIRED = "Request body is required";
  static final String MSG_ACCOUNT_NOT_FOUND = "Financial account not found: ";
  static final String MSG_NOT_CASH_ACCOUNT =
      "Cash close is only available for cash-type financial accounts";
  static final String MSG_INTERNAL_SERVER_ERROR = "Internal Server Error";
  static final String MSG_DIFFERENCE_TRANSACTION_DESCRIPTION = "GL Item: Differences";

  /** GL-item transaction types: BP Deposit (cash surplus) and BP Withdrawal (cash shortage). */
  static final String TRX_TYPE_DEPOSIT = "BPD";
  static final String TRX_TYPE_WITHDRAWAL = "BPW";
  static final String STATUS_CLEARED = "RPPC";
  /** Action code for {@link org.openbravo.advpaymentmngt.process.FIN_TransactionProcess}. */
  static final String PROCESS_ACTION = "P";
  /** A difference smaller than this is treated as a perfect close (no adjustment transaction). */
  static final BigDecimal DIFF_TOLERANCE = new BigDecimal("0.005");

  /** One route of the {@code ?action=} dispatcher. */
  @FunctionalInterface
  private interface ActionRoute {
    NeoResponse apply(CashCloseHandler handler, NeoContext context);
  }

  private static final Map<String, ActionRoute> ROUTES = Map.of(
      METHOD_GET + " " + ACTION_PENDING, CashCloseSupport::handlePending,
      METHOD_POST + " " + ACTION_SAVE_DRAFT, CashCloseSupport::handleSaveDraft,
      METHOD_POST + " " + ACTION_CONFIRM, CashCloseSupport::handleConfirm,
      METHOD_POST + " " + ACTION_DISCARD_DRAFT, CashCloseSupport::handleDiscardDraft);

  @Override
  public NeoResponse handle(NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String action = qp != null ? qp.get(PARAM_ACTION) : null;
    ActionRoute route = ROUTES.get(context.getHttpMethod() + " " + action);
    // Any other request (generic list / getById of the W spec) flows through.
    return route != null ? route.apply(this, context) : null;
  }

  // ---------------------------------------------------------------------------
  // Business methods
  // ---------------------------------------------------------------------------

  NeoResponse pending(String accountId) throws Exception {
    return CashCloseSupport.buildPending(this, accountId);
  }

  NeoResponse saveDraft(JSONObject body) throws Exception {
    return CashCloseSupport.applyDraft(this, body, false);
  }

  NeoResponse confirm(JSONObject body) throws Exception {
    return CashCloseSupport.applyDraft(this, body, true);
  }

  NeoResponse discardDraft(JSONObject body) throws Exception {
    String accountId = body.optString(KEY_ACCOUNT_ID, null);
    if (StringUtils.isBlank(accountId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          MSG_MISSING_PARAM + KEY_ACCOUNT_ID);
    }
    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, MSG_ACCOUNT_NOT_FOUND + accountId);
    }
    FIN_Reconciliation draft = findDraft(account);
    if (draft != null) {
      removeDraft(draft);
    }
    return ReconciliationSupport.envelope(new JSONObject());
  }

  // ---------------------------------------------------------------------------
  // Seams (package-private to allow unit tests to stub the DAL / Classic layer)
  // ---------------------------------------------------------------------------

  FIN_FinancialAccount loadAccount(String accountId) {
    return OBDal.getInstance().get(FIN_FinancialAccount.class, accountId);
  }

  /** The account's editable (unprocessed) reconciliation, or {@code null} when there is none. */
  FIN_Reconciliation findDraft(FIN_FinancialAccount account) {
    return TransactionsDao.getLastReconciliation(account, "N");
  }

  /** The account's most recent completed reconciliation, or {@code null} when there is none. */
  FIN_Reconciliation findLastProcessed(FIN_FinancialAccount account) {
    return TransactionsDao.getLastReconciliation(account, "Y");
  }

  /**
   * The transactions currently linked to {@code rec}, read with a query.
   *
   * <p>Deliberately NOT {@code rec.getFINFinaccTransactionList()}. A draft created earlier in the
   * SAME request comes from {@code OBProvider} (see {@code
   * AdvPaymentMngtDao#getNewReconciliation}), so that one-to-many list is a plain in-memory
   * collection which is never populated from the database — while linking a movement sets the FK on
   * the OWNING side ({@code trx.setReconciliation(draft)}), which never shows up in it. Reading the
   * list on a FIRST close therefore reported zero cleared movements, so the whole counted amount
   * looked like a discrepancy: the close was rejected for a missing GL Item Difference it did not
   * need, or — worse, when one was configured — posted a spurious adjustment for the full amount.
   * A query is authoritative for a freshly created and for a reloaded draft alike.</p>
   */
  List<FIN_FinaccTransaction> linkedTransactions(FIN_Reconciliation rec) {
    return OBDal.getInstance()
        .createQuery(FIN_FinaccTransaction.class, "reconciliation.id = :reconciliationId")
        .setNamedParameter("reconciliationId", rec.getId())
        .list();
  }

  /**
   * Creates a brand-new draft reconciliation for the account, mirroring Classic's manual
   * reconciliation servlet ({@code Reconciliation.java#printPage}).
   */
  FIN_Reconciliation createDraft(FIN_FinancialAccount account, Date closeDate,
      BigDecimal openingBalance) {
    DocumentType docType = FIN_Utility.getDocumentType(account.getOrganization(), "REC");
    if (docType == null) {
      throw new OBException(
          "No 'REC' document type configured for organization " + account.getOrganization().getId());
    }
    String docNumber = FIN_Utility.getDocumentNo(account.getOrganization(), "REC",
        "DocumentNo_FIN_Reconciliation");
    return new AdvPaymentMngtDao().getNewReconciliation(account.getOrganization(), account,
        docNumber, docType, closeDate, closeDate, openingBalance, BigDecimal.ZERO, "DR");
  }

  void removeDraft(FIN_Reconciliation draft) {
    ReconciliationRemovalUtil.reactivateAndRemoveReconciliation(draft);
  }

  /**
   * Accounting-period guard, delegated to {@code com.etendoerp.payment.removal}'s {@link
   * Utilities#checkPeriod(String, String, String, Date)}: throws an {@link OBException} when the
   * period of {@code date} is closed for the given client/org/table.
   */
  void checkPeriod(String clientId, String orgId, String tableId, Date date) {
    Utilities.checkPeriod(clientId, orgId, tableId, date);
  }

  /**
   * The identifier of the first line of {@code draft} whose accounting date falls in a closed
   * period, or {@code null} when every line is postable. Mirrors Classic's
   * {@code Reconciliation.linesInNotAvailablePeriod} verbatim — same view, same
   * {@code c_chk_open_period} DB function, same {@code 'REC'} document category — so the two
   * flows accept and reject exactly the same closes.
   */
  String findLineInClosedPeriod(FIN_Reconciliation draft) {
    OBQuery<FIN_ReconciliationLine_v> query = OBDal.getInstance()
        .createQuery(FIN_ReconciliationLine_v.class,
            " as rl where rl.reconciliation.id = :reconciliationId"
                + "   and c_chk_open_period(rl.organization, rl.transactionDate, 'REC', null) = 0"
                + " order by rl.transactionDate")
        .setNamedParameter("reconciliationId", draft.getId())
        .setMaxResult(1);
    List<FIN_ReconciliationLine_v> blocked = query.list();
    return blocked.isEmpty() ? null : blocked.get(0).getIdentifier();
  }

  void doRollbackAndClose() {
    OBDal.getInstance().rollbackAndClose();
  }
}
