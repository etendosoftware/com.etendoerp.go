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

import static com.etendoerp.go.schemaforge.ReconciliationSupport.formatDate;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.nullSafe;
import static com.etendoerp.go.schemaforge.ReconciliationSupport.signedAmount;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.advpaymentmngt.dao.TransactionsDao;
import org.openbravo.advpaymentmngt.process.FIN_TransactionProcess;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentDetail;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FIN_Reconciliation;

import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * Stateless helpers backing {@link CashCloseHandler} — the dispatch wrappers (OBContext admin mode
 * + rollback boilerplate, identical in shape to {@link ReconciliationHandlerSupport}), the pending
 * transactions query, and the close/confirm business logic. Extracted so the handler itself stays a
 * thin router plus its DAL/Classic-layer test seams.
 */
final class CashCloseSupport {

  private static final Logger log = LogManager.getLogger(CashCloseSupport.class);

  private CashCloseSupport() {
    // utility class — no instances
  }

  /**
   * Not-yet-cleared transactions of a cash account (panel left): either free (unreconciled, not
   * already cleared) or currently linked to the account's own draft reconciliation — mirrors the
   * bracket used by {@code ReconciliationHandler.CANDIDATES_SQL} so a re-opened draft comes back
   * with its ticked movements pre-selected.
   */
  private static final String PENDING_TRANSACTIONS_SQL =
      "SELECT ft.fin_finacc_transaction_id,"
          // The DAL property `transactionDate` maps to the physical column STATEMENTDATE (DATEACCT
          // is the separate accounting date) — same column CANDIDATES_SQL and TRANSACTIONS_SQL read.
          + "       ft.statementdate,"
          + "       COALESCE(fp.documentno, '') AS document_no,"
          + "       COALESCE(bp.name, '') AS partner_name,"
          + "       COALESCE(ft.description, '') AS description,"
          + "       COALESCE(ft.depositamt, 0) - COALESCE(ft.paymentamt, 0) AS amount,"
          + "       ft.fin_reconciliation_id"
          + "  FROM fin_finacc_transaction ft"
          + "  LEFT JOIN fin_payment fp ON fp.fin_payment_id = ft.fin_payment_id"
          + "  LEFT JOIN c_bpartner bp ON bp.c_bpartner_id = COALESCE(ft.c_bpartner_id, fp.c_bpartner_id)"
          + " WHERE ft.isactive = 'Y'"
          + "   AND ft.processed = 'Y'"
          + "   AND ft.fin_financial_account_id = ?"
          + "   AND ft.ad_client_id = ?"
          + "   AND ft.ad_org_id = ANY (?)"
          + "   AND ((ft.fin_reconciliation_id IS NULL AND ft.status <> 'RPPC')"
          + "        OR ft.fin_reconciliation_id = ?)"
          + " ORDER BY ft.statementdate ASC, ft.line ASC";

  // ---------------------------------------------------------------------------
  // GET pending — dispatch wrapper
  // ---------------------------------------------------------------------------

  static NeoResponse handlePending(CashCloseHandler handler, NeoContext context) {
    Map<String, String> qp = context.getQueryParams();
    String accountId = qp != null ? qp.get(CashCloseHandler.PARAM_ACCOUNT_ID) : null;
    if (StringUtils.isBlank(accountId)) {
      return missingParam(CashCloseHandler.PARAM_ACCOUNT_ID);
    }
    try {
      OBContext.setAdminMode(true);
      return handler.pending(accountId);
    } catch (Exception e) {
      log.error("Error building cash-close pending list for account {}", accountId, e);
      return internalError();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------------------
  // POST saveDraft / confirm / discardDraft — dispatch wrappers
  // ---------------------------------------------------------------------------

  static NeoResponse handleSaveDraft(CashCloseHandler handler, NeoContext context) {
    return runPostAction(handler, context, "saveDraft");
  }

  static NeoResponse handleConfirm(CashCloseHandler handler, NeoContext context) {
    return runPostAction(handler, context, "confirm");
  }

  static NeoResponse handleDiscardDraft(CashCloseHandler handler, NeoContext context) {
    return runPostAction(handler, context, "discardDraft");
  }

  /**
   * Shared dispatch envelope for the mutating POST actions: rejects an empty body, runs the action
   * in admin mode, maps a business {@link OBException} to 400 (+rollback) and any other failure to
   * 500 (+rollback), and always restores the previous OBContext mode.
   */
  private static NeoResponse runPostAction(CashCloseHandler handler, NeoContext context,
      String action) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, CashCloseHandler.MSG_BODY_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      switch (action) {
        case "saveDraft":
          return handler.saveDraft(body);
        case "confirm":
          return handler.confirm(body);
        case "discardDraft":
          return handler.discardDraft(body);
        default:
          return null;
      }
    } catch (OBException e) {
      log.warn("{} business error: {}", action, e.getMessage());
      handler.doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("{} failed", action, e);
      handler.doRollbackAndClose();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          CashCloseHandler.MSG_INTERNAL_SERVER_ERROR);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static NeoResponse missingParam(String param) {
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        CashCloseHandler.MSG_MISSING_PARAM + param);
  }

  private static NeoResponse internalError() {
    return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        CashCloseHandler.MSG_INTERNAL_SERVER_ERROR);
  }

  private static NeoResponse requireCashAccount(FIN_FinancialAccount account) {
    if (!CashCloseHandler.TYPE_CASH.equals(account.getType())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, CashCloseHandler.MSG_NOT_CASH_ACCOUNT);
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // GET pending — business logic
  // ---------------------------------------------------------------------------

  static NeoResponse buildPending(CashCloseHandler handler, String accountId) throws Exception {
    FIN_FinancialAccount account = handler.loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          CashCloseHandler.MSG_ACCOUNT_NOT_FOUND + accountId);
    }
    NeoResponse typeError = requireCashAccount(account);
    if (typeError != null) {
      return typeError;
    }

    FIN_Reconciliation lastProcessed = handler.findLastProcessed(account);
    BigDecimal openingBalance = lastProcessed != null ? nullSafe(lastProcessed.getEndingBalance())
        : nullSafe(account.getInitialBalance());
    FIN_Reconciliation draft = handler.findDraft(account);

    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    Set<String> orgs = ReconciliationSupport.accessibleOrgs(
        OBContext.getOBContext().getCurrentOrganization().getId());

    JSONArray movements = new JSONArray();
    List<String> markedIds = new ArrayList<>();
    // Connection is managed by the DAL's Hibernate Session; don't close it.
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(PENDING_TRANSACTIONS_SQL)) {
      int idx = 1;
      ps.setString(idx++, accountId);
      ps.setString(idx++, clientId);
      ps.setArray(idx++, conn.createArrayOf("varchar", orgs.toArray(new String[0])));
      ps.setString(idx++, draft != null ? draft.getId() : null);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String id = rs.getString("fin_finacc_transaction_id");
          JSONObject row = new JSONObject();
          row.put(CashCloseHandler.KEY_ID, id);
          // JSON key stays `transactionDate` (the DAL/API name the frontend reads); the result-set
          // column is the physical STATEMENTDATE it maps to.
          row.put("transactionDate", formatDate(rs.getTimestamp("statementdate")));
          row.put("documentNo", StringUtils.trimToEmpty(rs.getString("document_no")));
          row.put("partnerName", StringUtils.trimToEmpty(rs.getString("partner_name")));
          row.put("description", StringUtils.trimToEmpty(rs.getString("description")));
          row.put("amount", nullSafe(rs.getBigDecimal("amount")));
          movements.put(row);
          String recId = rs.getString("fin_reconciliation_id");
          if (draft != null && draft.getId().equals(recId)) {
            markedIds.add(id);
          }
        }
      }
    }

    JSONObject data = new JSONObject();
    JSONObject accountJson = new JSONObject();
    accountJson.put(CashCloseHandler.KEY_ID, account.getId());
    accountJson.put("name", account.getName());
    accountJson.put("currencyIso", account.getCurrency() != null ? account.getCurrency().getISOCode() : "");
    data.put("account", accountJson);
    data.put("openingBalance", openingBalance);
    data.put("lastCloseDate", lastProcessed != null ? formatIsoDate(lastProcessed.getEndingDate())
        : JSONObject.NULL);

    GLItem glItem = account.getAprmGlitemDiff();
    if (glItem != null) {
      JSONObject glJson = new JSONObject();
      glJson.put(CashCloseHandler.KEY_ID, glItem.getId());
      glJson.put("name", glItem.getName());
      data.put("glItemDifference", glJson);
    } else {
      data.put("glItemDifference", JSONObject.NULL);
    }

    if (draft != null) {
      JSONObject draftJson = new JSONObject();
      draftJson.put(CashCloseHandler.KEY_ID, draft.getId());
      draftJson.put(CashCloseHandler.KEY_STATEMENT_DATE, formatIsoDate(draft.getEndingDate()));
      draftJson.put(CashCloseHandler.KEY_DECLARED_BALANCE, nullSafe(draft.getEndingBalance()));
      draftJson.put("markedIds", new JSONArray(markedIds));
      data.put("draft", draftJson);
    } else {
      data.put("draft", JSONObject.NULL);
    }
    data.put("movements", movements);
    return ReconciliationSupport.envelope(data);
  }

  // ---------------------------------------------------------------------------
  // POST saveDraft / confirm — business logic
  // ---------------------------------------------------------------------------

  static NeoResponse applyDraft(CashCloseHandler handler, JSONObject body, boolean confirm)
      throws Exception {
    String accountId = body.optString(CashCloseHandler.KEY_ACCOUNT_ID, null);
    String statementDateStr = body.optString(CashCloseHandler.KEY_STATEMENT_DATE, null);
    if (StringUtils.isBlank(accountId)) {
      return missingParam(CashCloseHandler.KEY_ACCOUNT_ID);
    }
    if (StringUtils.isBlank(statementDateStr)) {
      return missingParam(CashCloseHandler.KEY_STATEMENT_DATE);
    }

    FIN_FinancialAccount account = handler.loadAccount(accountId);
    if (account == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          CashCloseHandler.MSG_ACCOUNT_NOT_FOUND + accountId);
    }
    NeoResponse typeError = requireCashAccount(account);
    if (typeError != null) {
      return typeError;
    }

    BigDecimal declaredBalance = new BigDecimal(body.optString(CashCloseHandler.KEY_DECLARED_BALANCE, "0"));
    List<String> movementIds = readMovementIds(body);
    Date closeDate = java.sql.Date.valueOf(statementDateStr);

    FIN_Reconciliation lastProcessed = handler.findLastProcessed(account);
    BigDecimal openingBalance = lastProcessed != null ? nullSafe(lastProcessed.getEndingBalance())
        : nullSafe(account.getInitialBalance());

    FIN_Reconciliation draft = handler.findDraft(account);
    if (draft == null) {
      draft = handler.createDraft(account, closeDate, openingBalance);
    }

    if (hasBankStatementLines(draft)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "This reconciliation already has bank-statement lines linked to it; cash close and "
              + "bank reconciliation cannot share the same document.");
    }

    syncMarkedMovements(handler, account, draft, movementIds);

    draft.setEndingBalance(declaredBalance);
    draft.setTransactionDate(closeDate);
    draft.setEndingDate(closeDate);
    draft.setDocumentStatus("DR");
    draft.setProcessed(false);
    OBDal.getInstance().save(draft);
    OBDal.getInstance().flush();

    if (!confirm) {
      return envelopeDraft(draft, false);
    }
    return confirmDraft(handler, account, draft, openingBalance, declaredBalance);
  }

  /**
   * The confirm half of {@link #applyDraft}: validations, the difference transaction, the
   * post-dated date rewrite and completing the document. Package-private so the unit tests can
   * drive it directly with a stubbed handler, without going through the draft-creation flow.
   */
  static NeoResponse confirmDraft(CashCloseHandler handler, FIN_FinancialAccount account,
      FIN_Reconciliation draft, BigDecimal openingBalance, BigDecimal declaredBalance)
      throws Exception {
    FIN_Reconciliation lastProcessed = handler.findLastProcessed(account);
    Date closeDate = draft.getEndingDate();

    if (isBeforeLastClose(closeDate, lastProcessed)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "The close date cannot be earlier than the last confirmed close ("
              + formatIsoDate(lastProcessed.getEndingDate()) + ").");
    }
    if (isInFuture(closeDate)) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, "The close date cannot be in the future.");
    }
    try {
      handler.checkPeriod(account.getClient().getId(), account.getOrganization().getId(),
          draft.getEntity().getTableId(), closeDate);
    } catch (OBException e) {
      log.warn("cash close confirm blocked by closed period for account {}: {}", account.getId(),
          e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, e.getMessage());
    }
    // The check above only covers the CLOSE date. A movement dated before the close keeps its own
    // accounting date (only post-dated ones get pushed forward, further down), so it can sit in a
    // period that is already closed even when the close date's period is open — that would pass
    // here and then blow up at posting time. Classic guards the same case with
    // linesInNotAvailablePeriod (@APRM_PeriodNotAvailableClearedItem@).
    String blockedLine = handler.findLineInClosedPeriod(draft);
    if (blockedLine != null) {
      log.warn("cash close confirm blocked: line {} of reconciliation {} is in a closed period",
          blockedLine, draft.getId());
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "The movement \"" + blockedLine + "\" has an accounting date in a closed period. "
              + "Reopen that period or unmark the movement before confirming the close.");
    }

    BigDecimal diff = difference(openingBalance, clearedNet(handler, draft), declaredBalance);
    boolean balanced = isBalanced(diff);
    if (!balanced && account.getAprmGlitemDiff() == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "There is a difference of " + diff.toPlainString() + " and this account has no "
              + "accounting concept configured for it. Configure a GL Item Difference in Edit "
              + "account before confirming the close.");
    }
    if (!balanced) {
      createDifferenceTransaction(account, draft, diff);
    }

    rewriteDatesAndSettleInvoices(handler, draft);

    draft.setDocumentStatus("CO");
    draft.setProcessed(true);
    draft.setAPRMProcessReconciliation("R");
    draft.setAprmProcessRec("R");
    OBDal.getInstance().save(draft);
    OBDal.getInstance().flush();

    return envelopeDraft(draft, true);
  }

  // ---------------------------------------------------------------------------
  // Pure close arithmetic / date guards (package-private so they are unit-tested
  // directly, without standing up the DAL statics the surrounding flow needs).
  // The frontend mirrors these exactly in `cashCloseMath.js`.
  // ---------------------------------------------------------------------------

  /**
   * The residual of a close: {@code declared - (opening + clearedNet)}. POSITIVE when the drawer
   * holds more cash than the books say (a surplus, posted as a deposit), NEGATIVE when it holds
   * less (a shortage, posted as a withdrawal).
   */
  static BigDecimal difference(BigDecimal openingBalance, BigDecimal clearedNet,
      BigDecimal declaredBalance) {
    return nullSafe(declaredBalance).subtract(nullSafe(openingBalance).add(nullSafe(clearedNet)));
  }

  /** A residual under half a cent needs no adjustment transaction. */
  static boolean isBalanced(BigDecimal difference) {
    return nullSafe(difference).abs().compareTo(CashCloseHandler.DIFF_TOLERANCE) < 0;
  }

  /**
   * True when the close date predates the account's last confirmed close — Classic's
   * {@code @APRM_ReconcileInFutureOrPast@} guard. Always false when there is no previous close.
   */
  static boolean isBeforeLastClose(Date closeDate, FIN_Reconciliation lastProcessed) {
    if (closeDate == null || lastProcessed == null || lastProcessed.getEndingDate() == null) {
      return false;
    }
    return closeDate.before(lastProcessed.getEndingDate());
  }

  /**
   * True when the close date is past tomorrow. Same bound as Classic: closing "today" is normal
   * and closing "tomorrow" is tolerated (timezone slack), anything beyond is rejected.
   */
  static boolean isInFuture(Date closeDate) {
    if (closeDate == null) {
      return false;
    }
    Calendar tomorrow = Calendar.getInstance();
    tomorrow.add(Calendar.DATE, 1);
    tomorrow.setTime(DateUtils.truncate(tomorrow.getTime(), Calendar.DATE));
    return closeDate.after(tomorrow.getTime());
  }

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  private static List<String> readMovementIds(JSONObject body) throws JSONException {
    List<String> ids = new ArrayList<>();
    JSONArray arr = body.optJSONArray(CashCloseHandler.KEY_MOVEMENT_IDS);
    if (arr == null) {
      return ids;
    }
    for (int i = 0; i < arr.length(); i++) {
      if (arr.isNull(i)) {
        continue;
      }
      String id = arr.optString(i, null);
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * True when the draft already has a transaction backed by a bank-statement line — banking and
   * cash reconciliations are mutually exclusive per document (mirrors Classic's {@code
   * isAutomaticReconciliation} guard in {@code Reconciliation.java}).
   */
  private static boolean hasBankStatementLines(FIN_Reconciliation draft) {
    // OBDal.createQuery takes the condition WITHOUT a leading "where" (see AgingReportHandler).
    OBQuery<FIN_BankStatementLine> q = OBDal.getInstance()
        .createQuery(FIN_BankStatementLine.class,
            "financialAccountTransaction.reconciliation.id = :draftId")
        .setNamedParameter("draftId", draft.getId())
        .setMaxResult(1);
    return q.uniqueResult() != null;
  }

  /**
   * Links every newly-ticked movement to the draft and unlinks every movement that was ticked
   * before but no longer is — the incremental equivalent of Classic's per-checkbox {@code
   * updateTransactionStatus}.
   */
  private static void syncMarkedMovements(CashCloseHandler handler, FIN_FinancialAccount account,
      FIN_Reconciliation draft, List<String> movementIds) {
    Set<String> wanted = new HashSet<>(movementIds);
    Set<String> current = new HashSet<>();
    for (FIN_FinaccTransaction t : handler.linkedTransactions(draft)) {
      current.add(t.getId());
    }
    for (String id : wanted) {
      if (current.contains(id)) {
        continue;
      }
      FIN_FinaccTransaction trx = OBDal.getInstance().get(FIN_FinaccTransaction.class, id);
      boolean eligible = trx != null
          && trx.getAccount() != null
          && account.getId().equals(trx.getAccount().getId())
          && trx.getReconciliation() == null
          && !CashCloseHandler.STATUS_CLEARED.equals(trx.getStatus());
      if (eligible) {
        linkTransaction(trx, draft);
      }
    }
    for (String id : current) {
      if (!wanted.contains(id)) {
        FIN_FinaccTransaction trx = OBDal.getInstance().get(FIN_FinaccTransaction.class, id);
        if (trx != null) {
          unlinkTransaction(trx);
        }
      }
    }
  }

  private static void linkTransaction(FIN_FinaccTransaction trx, FIN_Reconciliation draft) {
    trx.setReconciliation(draft);
    trx.setStatus(CashCloseHandler.STATUS_CLEARED);
    OBDal.getInstance().save(trx);
    FIN_Payment payment = trx.getFinPayment();
    if (payment != null) {
      payment.setStatus(CashCloseHandler.STATUS_CLEARED);
      OBDal.getInstance().save(payment);
    }
  }

  private static void unlinkTransaction(FIN_FinaccTransaction trx) {
    trx.setReconciliation(null);
    OBDal.getInstance().save(trx);
    ReactivationSupport.restoreNotClearedStatus(trx);
    FIN_Payment payment = trx.getFinPayment();
    if (payment != null) {
      boolean inflow = Boolean.TRUE.equals(payment.isReceipt());
      payment.setStatus(inflow ? "RDNC" : "PWNC");
      OBDal.getInstance().save(payment);
    }
  }

  /**
   * Sum of {@code depositAmount - paymentAmount} over every transaction linked to the draft.
   *
   * <p>Reads the links through {@link CashCloseHandler#linkedTransactions} — see the warning there
   * about why the entity's own one-to-many list cannot be trusted on a first close.</p>
   */
  private static BigDecimal clearedNet(CashCloseHandler handler, FIN_Reconciliation draft) {
    BigDecimal net = BigDecimal.ZERO;
    for (FIN_FinaccTransaction t : handler.linkedTransactions(draft)) {
      net = net.add(signedAmount(t));
    }
    return net;
  }

  /**
   * Posts the residual difference against the account's GL Item Difference, mirroring Classic's
   * {@code createTransaction(account, BP_WITHDRAWAL, ..., account.getAprmGlitemDiff(), ...)} and
   * Etendo GO's own rule-origin template ({@code ReconciliationHandler.createTransactionForRule}).
   * Unlike either of those, the transaction is linked directly to {@code draft} and set {@code
   * RPPC} on creation — it is born already cleared, as it belongs to a reconciliation that is
   * about to be completed.
   */
  private static void createDifferenceTransaction(FIN_FinancialAccount account,
      FIN_Reconciliation draft, BigDecimal diff) throws Exception {
    GLItem glItem = account.getAprmGlitemDiff();
    BigDecimal absAmount = diff.abs();
    boolean isDeposit = diff.signum() > 0;

    FIN_FinaccTransaction trx = OBProvider.getInstance().get(FIN_FinaccTransaction.class);
    trx.setClient(account.getClient());
    trx.setOrganization(account.getOrganization());
    trx.setActive(true);
    trx.setAccount(account);
    trx.setCurrency(account.getCurrency());
    trx.setTransactionType(isDeposit ? CashCloseHandler.TRX_TYPE_DEPOSIT
        : CashCloseHandler.TRX_TYPE_WITHDRAWAL);
    trx.setTransactionDate(draft.getEndingDate());
    trx.setDateAcct(draft.getEndingDate());
    trx.setDescription(CashCloseHandler.MSG_DIFFERENCE_TRANSACTION_DESCRIPTION);
    trx.setLineNo(AutoMatchSupport.nextTransactionLineNo(account.getId()));
    trx.setDepositAmount(isDeposit ? absAmount : BigDecimal.ZERO);
    trx.setPaymentAmount(isDeposit ? BigDecimal.ZERO : absAmount);
    trx.setGLItem(glItem);
    trx.setReconciliation(draft);
    trx.setStatus(CashCloseHandler.STATUS_CLEARED);
    ReactivationSupport.markAutoCreated(trx);
    OBDal.getInstance().save(trx);
    OBDal.getInstance().flush();

    FIN_TransactionProcess.doTransactionProcess(CashCloseHandler.PROCESS_ACTION, trx);
    OBDal.getInstance().flush();
  }

  /**
   * Two things Classic always does on {@code processReconciliation}: (1) push forward the date of
   * every linked transaction dated after the close, so nothing in the ledger looks like it happened
   * after the cash was counted; (2) settle the invoices of every linked payment that reached its
   * "paid" status. Mirrors {@code Reconciliation.java}'s inline loop verbatim.
   */
  private static void rewriteDatesAndSettleInvoices(CashCloseHandler handler,
      FIN_Reconciliation draft) {
    Date endingDate = draft.getEndingDate();
    for (FIN_FinaccTransaction trx : handler.linkedTransactions(draft)) {
      if (endingDate.compareTo(trx.getTransactionDate()) < 0) {
        pushForwardTransactionDate(trx, endingDate);
      }
      settleInvoicesOfPayment(trx.getFinPayment(), draft);
    }
  }

  /**
   * Moves a transaction dated after the close back onto the close date.
   *
   * <p>The un-post / un-process / re-date / re-process / re-post dance and its flush after every
   * step are Classic's, not ours: {@code FIN_FinaccTransaction} refuses a date change while it is
   * processed, and the posted flag has to be dropped first so the accounting entry is regenerated.
   * Kept step by step on purpose so it stays diffable against {@code Reconciliation.java}.</p>
   */
  private static void pushForwardTransactionDate(FIN_FinaccTransaction trx, Date endingDate) {
    boolean posted = "Y".equals(trx.getPosted());
    if (posted) {
      trx.setPosted("N");
      OBDal.getInstance().save(trx);
      OBDal.getInstance().flush();
    }
    trx.setProcessed(false);
    OBDal.getInstance().save(trx);
    OBDal.getInstance().flush();
    trx.setTransactionDate(endingDate);
    trx.setDateAcct(endingDate);
    OBDal.getInstance().save(trx);
    OBDal.getInstance().flush();
    trx.setProcessed(true);
    OBDal.getInstance().save(trx);
    OBDal.getInstance().flush();
    if (posted) {
      trx.setPosted("Y");
      OBDal.getInstance().save(trx);
      OBDal.getInstance().flush();
    }
    TransactionsDao.updateAccountingDate(trx);
  }

  /** Settles the invoices behind a linked payment. No-op when the transaction has no payment. */
  private static void settleInvoicesOfPayment(FIN_Payment payment, FIN_Reconciliation draft) {
    if (payment == null) {
      return;
    }
    for (FIN_PaymentDetail pd : payment.getFINPaymentDetailList()) {
      for (FIN_PaymentScheduleDetail psd : pd.getFINPaymentScheduleDetailList()) {
        settleScheduleDetail(psd, payment, draft);
      }
      FIN_Utility.updateBusinessPartnerCredit(payment);
    }
  }

  /**
   * Marks one schedule detail paid when its payment reached the "paid" status for this account,
   * then refreshes the derived amounts. Already-paid details are left alone.
   */
  private static void settleScheduleDetail(FIN_PaymentScheduleDetail psd, FIN_Payment payment,
      FIN_Reconciliation draft) {
    if (Boolean.TRUE.equals(psd.isInvoicePaid())) {
      return;
    }
    if (FIN_Utility.invoicePaymentStatus(payment.getPaymentMethod(), draft.getAccount(),
        payment.isReceipt()).equals(payment.getStatus())) {
      psd.setInvoicePaid(true);
    }
    if (Boolean.TRUE.equals(psd.isInvoicePaid())) {
      FIN_Utility.updatePaymentAmounts(psd);
    }
  }

  private static NeoResponse envelopeDraft(FIN_Reconciliation draft, boolean confirmed)
      throws JSONException {
    JSONObject data = new JSONObject();
    data.put(CashCloseHandler.KEY_ID, draft.getId());
    data.put("reconciliationId", draft.getId());
    data.put("documentNo", draft.getDocumentNo());
    data.put(CashCloseHandler.KEY_STATEMENT_DATE, formatIsoDate(draft.getEndingDate()));
    data.put("updatedBalance", nullSafe(draft.getEndingBalance()));
    data.put("confirmed", confirmed);
    return ReconciliationSupport.envelope(data);
  }

  /** Canonical NEO wire date (day only) in the server's own zone; see {@link NeoDateFormat} (ETP-5100). */
  private static String formatIsoDate(Date date) {
    return NeoDateFormat.toWireDate(date);
  }
}
