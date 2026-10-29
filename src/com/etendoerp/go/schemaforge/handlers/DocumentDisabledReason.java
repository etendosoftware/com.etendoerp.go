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

package com.etendoerp.go.schemaforge.handlers;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

/**
 * Why core disabled a document for accounting ({@code STATUS_DocumentDisabled}), told only when
 * it is certain (ETP-5591). Core sets that status for a different reason in each accounting class
 * and keeps no trace of which, so a reason is named here only after re-checking the very condition
 * core's {@code getDocumentConfirmation} checked:
 * <ul>
 *   <li>a financial account transaction with no payment, whose account has no deposit (BP deposit)
 *       or withdrawal (BP withdrawal, bank fee) account in its accounting
 *       ({@code DocFINFinAccTransaction});</li>
 *   <li>a payment-linked transaction whose payment method, on that account, posts receipts to the
 *       deposit account ({@code DEP}) or payments to the withdrawal account ({@code WIT}), and that
 *       account is missing. The in-transit and cleared variants stay generic.</li>
 *   <li>an invoice whose lines and taxes are all zero ({@code DocInvoice}, core's own
 *       {@code TotalGrossIsZero}).</li>
 * </ul>
 * Anything else, or a re-check that does not confirm (core may have hit its catch branch), names
 * the generic {@code DocumentDisabled}: a wrong reason is worse than a vague one.
 *
 * <p>A missing account is a configuration error, not a document without accounting, so for those
 * two reasons {@link #markInvalidAccount} turns core's {@code D} into {@code i} ("Invalid account"):
 * the document stays among the posting errors instead of leaving the Not Posted Documents list.</p>
 */
final class DocumentDisabledReason {

  private static final Logger log = LogManager.getLogger(DocumentDisabledReason.class);

  /** Core's generic "Document disabled for accounting". */
  static final String MSG_DOCUMENT_DISABLED = "DocumentDisabled";
  /** Core's zero-total invoice message, the one {@code DocInvoice} sets itself. */
  static final String MSG_TOTAL_GROSS_IS_ZERO = "TotalGrossIsZero";
  static final String MSG_NO_DEPOSIT_ACCOUNT = "ETGO_PostingDisabledNoDepositAccount";
  static final String MSG_NO_WITHDRAWAL_ACCOUNT = "ETGO_PostingDisabledNoWithdrawalAccount";
  /** {@code messageParams} entry (and {@code @financialAccount@} token) naming the account. */
  static final String MESSAGE_PARAMS_FINANCIAL_ACCOUNT = "financialAccount";

  private static final String TABLE_FIN_TRANSACTION = "FIN_Finacc_Transaction";
  private static final String TABLE_INVOICE = "C_Invoice";
  private static final String TRXTYPE_BP_DEPOSIT = "BPD";
  private static final String TRXTYPE_BP_WITHDRAWAL = "BPW";
  private static final String TRXTYPE_BANK_FEE = "BF";
  private static final String UPON_USE_DEPOSIT = "DEP";
  private static final String UPON_USE_WITHDRAWAL = "WIT";

  /** The AD message naming the reason, and the values its text interpolates. */
  record Reason(String messageKey, Map<String, Object> messageParams) {
    /** Whether the reason is a financial account with an accounting account missing. */
    boolean accountMissing() {
      return MSG_NO_DEPOSIT_ACCOUNT.equals(messageKey) || MSG_NO_WITHDRAWAL_ACCOUNT.equals(messageKey);
    }

    /** The AD message text with its {@code @param@} tokens filled in. */
    String render(String text) {
      String out = text;
      for (Map.Entry<String, Object> param : messageParams.entrySet()) {
        out = out.replace("@" + param.getKey() + "@", String.valueOf(param.getValue()));
      }
      return out;
    }
  }

  private static final Reason GENERIC = new Reason(MSG_DOCUMENT_DISABLED, Map.of());

  private DocumentDisabledReason() {
  }

  /**
   * The certain reason of a disabled document, or the generic one. Never throws.
   *
   * @param acct the accounting server that answered {@code STATUS_DocumentDisabled}
   * @return the reason to show
   */
  static Reason of(AcctServer acct) {
    OBContext.setAdminMode(true);
    try {
      Reason reason = null;
      if (TABLE_FIN_TRANSACTION.equalsIgnoreCase(acct.tableName)) {
        reason = transactionReason(OBDal.getInstance().get(FIN_FinaccTransaction.class, acct.Record_ID));
      } else if (TABLE_INVOICE.equalsIgnoreCase(acct.tableName)) {
        reason = invoiceReason(OBDal.getInstance().get(Invoice.class, acct.Record_ID));
      }
      return reason != null ? reason : GENERIC;
    } catch (Exception e) {
      log.debug("Could not re-check why {} {} is disabled for accounting", acct.tableName,
          acct.Record_ID, e);
      return GENERIC;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Marks the transaction core just left {@code D} as {@code i}. Written like core writes the
   * status ({@code AcctServerData.updateSave}): through the provider's own connection, after the
   * posting transaction was rolled back. Only a {@code D} row is touched. Never throws.
   *
   * @param recordId the transaction core failed to post
   * @param conn the provider the post ran on
   */
  static void markInvalidAccount(String recordId, ConnectionProvider conn) {
    String sql = "UPDATE FIN_Finacc_Transaction SET Posted = '" + AcctServer.STATUS_InvalidAccount
        + "' WHERE FIN_Finacc_Transaction_ID = ? AND Posted = '" + AcctServer.STATUS_DocumentDisabled + "'";
    try (PreparedStatement ps = conn.getConnection().prepareStatement(sql)) {
      ps.setString(1, recordId);
      ps.executeUpdate();
    } catch (Exception e) {
      log.warn("Could not mark transaction {} as invalid account; it stays disabled", recordId, e);
    }
  }

  private static Reason transactionReason(FIN_FinaccTransaction trx) {
    if (trx == null || trx.getAccount() == null) {
      return null;
    }
    if (trx.getFinPayment() != null) {
      return paymentReason(trx.getAccount(), trx.getFinPayment());
    }
    String type = trx.getTransactionType();
    boolean deposit = TRXTYPE_BP_DEPOSIT.equals(type);
    if (!deposit && !TRXTYPE_BP_WITHDRAWAL.equals(type) && !TRXTYPE_BANK_FEE.equals(type)) {
      return null;
    }
    return missingAccountReason(trx.getAccount(), deposit);
  }

  /** Core's payment branch: the account's setup of the payment method says which account it needs. */
  private static Reason paymentReason(FIN_FinancialAccount account, FIN_Payment payment) {
    FinAccPaymentMethod line = account.getFinancialMgmtFinAccPaymentMethodList().stream()
        .filter(l -> Boolean.TRUE.equals(l.isActive()) && l.getPaymentMethod() != null
            && l.getPaymentMethod().equals(payment.getPaymentMethod()))
        .findFirst().orElse(null);
    if (line == null) {
      return null;
    }
    boolean receipt = Boolean.TRUE.equals(payment.isReceipt());
    if (receipt ? !UPON_USE_DEPOSIT.equals(line.getUponDepositUse())
        : !UPON_USE_WITHDRAWAL.equals(line.getUponWithdrawalUse())) {
      return null;
    }
    return missingAccountReason(account, receipt);
  }

  private static Reason missingAccountReason(FIN_FinancialAccount account, boolean deposit) {
    List<FIN_FinancialAccountAccounting> rows = account.getFINFinancialAccountAcctList();
    boolean configured = rows.stream().anyMatch(row -> deposit
        ? row.getDepositAccount() != null
        : row.getWithdrawalAccount() != null);
    if (configured) {
      return null;
    }
    return new Reason(deposit ? MSG_NO_DEPOSIT_ACCOUNT : MSG_NO_WITHDRAWAL_ACCOUNT,
        Map.of(MESSAGE_PARAMS_FINANCIAL_ACCOUNT, account.getName()));
  }

  private static Reason invoiceReason(Invoice invoice) {
    if (invoice == null || BigDecimal.ZERO.compareTo(invoice.getGrandTotalAmount()) != 0) {
      return null;
    }
    boolean linesZero = invoice.getInvoiceLineList().stream()
        .map(InvoiceLine::getLineNetAmount)
        .allMatch(amount -> BigDecimal.ZERO.compareTo(amount) == 0);
    boolean taxesZero = invoice.getInvoiceTaxList().stream()
        .map(InvoiceTax::getTaxAmount)
        .allMatch(amount -> BigDecimal.ZERO.compareTo(amount) == 0);
    return linesZero && taxesZero ? new Reason(MSG_TOTAL_GROSS_IS_ZERO, Map.of()) : null;
  }
}
