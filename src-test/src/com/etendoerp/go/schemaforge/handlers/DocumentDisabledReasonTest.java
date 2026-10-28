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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.financialmgmt.accounting.FIN_FinancialAccountAccounting;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

/**
 * A specific reason only when the re-check confirms core's condition, the generic
 * {@code DocumentDisabled} otherwise.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.DocumentDisabledReason
 */
public class DocumentDisabledReasonTest {

  private static final String TRX_TABLE = "FIN_Finacc_Transaction";

  private static AcctServer acct(String table) {
    AcctServer acct = mock(AcctServer.class);
    acct.tableName = table;
    acct.Record_ID = "rec-1";
    return acct;
  }

  private static FIN_FinaccTransaction transaction(String type, FIN_FinancialAccountAccounting... rows) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getName()).thenReturn("Caja");
    when(account.getFINFinancialAccountAcctList()).thenReturn(List.of(rows));
    FIN_FinaccTransaction trx = mock(FIN_FinaccTransaction.class);
    when(trx.getAccount()).thenReturn(account);
    when(trx.getTransactionType()).thenReturn(type);
    return trx;
  }

  private static FIN_FinancialAccountAccounting accountingRow(boolean deposit, boolean withdrawal) {
    FIN_FinancialAccountAccounting row = mock(FIN_FinancialAccountAccounting.class);
    when(row.getDepositAccount()).thenReturn(deposit ? mock(AccountingCombination.class) : null);
    when(row.getWithdrawalAccount()).thenReturn(withdrawal ? mock(AccountingCombination.class) : null);
    return row;
  }

  private static <T extends BaseOBObject> DocumentDisabledReason.Reason reasonFor(AcctServer acct,
      Class<T> entity, T found) {
    OBDal dal = mock(OBDal.class);
    when(dal.get(entity, "rec-1")).thenReturn(found);
    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      return DocumentDisabledReason.of(acct);
    }
  }

  @Test
  public void depositWithNoDepositAccountNamesTheFinancialAccount() {
    DocumentDisabledReason.Reason reason = reasonFor(acct(TRX_TABLE), FIN_FinaccTransaction.class,
        transaction("BPD", accountingRow(false, true)));

    assertEquals(DocumentDisabledReason.MSG_NO_DEPOSIT_ACCOUNT, reason.messageKey());
    assertEquals(Map.of("financialAccount", "Caja"), reason.messageParams());
    assertEquals("Account Caja", reason.render("Account @financialAccount@"));
  }

  @Test
  public void bankFeeWithNoAccountingRowsNamesTheWithdrawalAccount() {
    DocumentDisabledReason.Reason reason = reasonFor(acct(TRX_TABLE), FIN_FinaccTransaction.class,
        transaction("BF"));

    assertEquals(DocumentDisabledReason.MSG_NO_WITHDRAWAL_ACCOUNT, reason.messageKey());
  }

  @Test
  public void withdrawalWithTheAccountConfiguredStaysGeneric() {
    DocumentDisabledReason.Reason reason = reasonFor(acct(TRX_TABLE), FIN_FinaccTransaction.class,
        transaction("BPW", accountingRow(false, false), accountingRow(false, true)));

    assertEquals(DocumentDisabledReason.MSG_DOCUMENT_DISABLED, reason.messageKey());
    assertTrue(reason.messageParams().isEmpty());
  }

  @Test
  public void paymentLinkedTransactionIsNotReChecked() {
    FIN_FinaccTransaction trx = transaction("BPD");
    when(trx.getFinPayment()).thenReturn(mock(FIN_Payment.class));

    DocumentDisabledReason.Reason reason = reasonFor(acct(TRX_TABLE), FIN_FinaccTransaction.class, trx);

    assertEquals(DocumentDisabledReason.MSG_DOCUMENT_DISABLED, reason.messageKey());
  }

  @Test
  public void otherTransactionTypeStaysGeneric() {
    DocumentDisabledReason.Reason reason = reasonFor(acct(TRX_TABLE), FIN_FinaccTransaction.class,
        transaction("BPC"));

    assertEquals(DocumentDisabledReason.MSG_DOCUMENT_DISABLED, reason.messageKey());
  }

  private static Invoice invoice(BigDecimal lineNet, BigDecimal tax) {
    InvoiceLine line = mock(InvoiceLine.class);
    when(line.getLineNetAmount()).thenReturn(lineNet);
    InvoiceTax invoiceTax = mock(InvoiceTax.class);
    when(invoiceTax.getTaxAmount()).thenReturn(tax);
    Invoice invoice = mock(Invoice.class);
    when(invoice.getGrandTotalAmount()).thenReturn(BigDecimal.ZERO);
    when(invoice.getInvoiceLineList()).thenReturn(List.of(line));
    when(invoice.getInvoiceTaxList()).thenReturn(List.of(invoiceTax));
    return invoice;
  }

  @Test
  public void zeroInvoiceUsesCoreTotalGrossIsZero() {
    DocumentDisabledReason.Reason reason = reasonFor(acct("C_Invoice"), Invoice.class,
        invoice(new BigDecimal("0.00"), BigDecimal.ZERO));

    assertEquals(DocumentDisabledReason.MSG_TOTAL_GROSS_IS_ZERO, reason.messageKey());
  }

  @Test
  public void invoiceWithANonZeroTaxStaysGeneric() {
    DocumentDisabledReason.Reason reason = reasonFor(acct("C_Invoice"), Invoice.class,
        invoice(BigDecimal.ZERO, BigDecimal.ONE));

    assertEquals(DocumentDisabledReason.MSG_DOCUMENT_DISABLED, reason.messageKey());
  }

  @Test
  public void failedReCheckStaysGeneric() {
    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenThrow(new IllegalStateException("no session"));

      assertEquals(DocumentDisabledReason.MSG_DOCUMENT_DISABLED,
          DocumentDisabledReason.of(acct(TRX_TABLE)).messageKey());
    }
  }
}
