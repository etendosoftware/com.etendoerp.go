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
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Why core disabled a document for accounting ({@code STATUS_DocumentDisabled}), told only when
 * it is certain (ETP-5591). Core sets that status for a different reason in each accounting class
 * and keeps no trace of which, so a reason is named here only after re-checking the very condition
 * core's {@code getDocumentConfirmation} checked:
 * <ul>
 *   <li>a financial account transaction with no payment, whose account has no deposit (BP deposit)
 *       or withdrawal (BP withdrawal, bank fee) account in its accounting
 *       ({@code DocFINFinAccTransaction});</li>
 *   <li>an invoice whose lines and taxes are all zero ({@code DocInvoice}, core's own
 *       {@code TotalGrossIsZero}).</li>
 * </ul>
 * Anything else, or a re-check that does not confirm (core may have hit its catch branch), names
 * the generic {@code DocumentDisabled}: a wrong reason is worse than a vague one.
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

  /** The AD message naming the reason, and the values its text interpolates. */
  record Reason(String messageKey, Map<String, Object> messageParams) {
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

  private static Reason transactionReason(FIN_FinaccTransaction trx) {
    // A payment-linked transaction depends on the payment method's "upon use" setup: not re-checked.
    if (trx == null || trx.getFinPayment() != null || trx.getAccount() == null) {
      return null;
    }
    String type = trx.getTransactionType();
    boolean deposit = TRXTYPE_BP_DEPOSIT.equals(type);
    if (!deposit && !TRXTYPE_BP_WITHDRAWAL.equals(type) && !TRXTYPE_BANK_FEE.equals(type)) {
      return null;
    }
    List<FIN_FinancialAccountAccounting> rows = trx.getAccount().getFINFinancialAccountAcctList();
    boolean configured = rows.stream().anyMatch(row -> deposit
        ? row.getDepositAccount() != null
        : row.getWithdrawalAccount() != null);
    if (configured) {
      return null;
    }
    return new Reason(deposit ? MSG_NO_DEPOSIT_ACCOUNT : MSG_NO_WITHDRAWAL_ACCOUNT,
        Map.of(MESSAGE_PARAMS_FINANCIAL_ACCOUNT, trx.getAccount().getName()));
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
