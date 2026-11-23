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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.model.common.businesspartner.BankAccount;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.utility.RemittanceUtils;
import org.openbravo.sepa.iso20022.util.SEPAUtil;

import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * Unit tests for {@link RemittanceFileLineCheck}, the checks of one remittance line before its SEPA
 * file: the bank account resolved as REM resolves it (invoice, then order, then the partner's
 * default or latest) and its account number (accountNo, the IBAN of an IBAN-format account, REM's
 * zeros), the IBAN (missing, or not as the schema takes it verbatim: a space), the BIC format, the
 * mandate (collections only), the partner's tax ID and address (as {@link SEPAUtil} words it),
 * and the amount against the due's outstanding amount (skipped once the remittance is processed).
 *
 * <p>{@link SEPAUtil} and {@link RemittanceUtils} are mocked statically: their own rules are
 * Classic's; this class pins which of them each line check asks and what it reports.
 *
 * <p>A class of its own: {@code RemittanceFileLineCheck} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileLineCheck
 */
class RemittanceFileLineCheckTest {

  private static final String VALID_IBAN = "ES9121000418450200051332";

  private MockedStatic<SEPAUtil> sepa;
  private MockedStatic<RemittanceUtils> remUtils;
  private MockedStatic<NeoLanguage> language;

  private RemittanceLine line;
  private FIN_PaymentScheduleDetail psd;
  private FIN_PaymentSchedule due;
  private Invoice invoice;
  private BusinessPartner partner;
  private BankAccount bankAccount;

  @BeforeEach
  void setUp() {
    sepa = mockStatic(SEPAUtil.class, Mockito.CALLS_REAL_METHODS);
    remUtils = mockStatic(RemittanceUtils.class);
    language = mockStatic(NeoLanguage.class);
    language.when(NeoLanguage::requestCode).thenReturn("en_US");

    partner = mock(BusinessPartner.class);
    when(partner.getId()).thenReturn("BP-1");
    when(partner.getName()).thenReturn("Acme");
    when(partner.getTaxID()).thenReturn("B12345678");
    when(partner.getObsepadMandateIdentifier()).thenReturn("MANDATE-1");
    when(partner.getObsepadMandateDate()).thenReturn(new Date());
    invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn("INV-1");
    when(invoice.getDocumentNo()).thenReturn("F-1");
    when(invoice.getBusinessPartner()).thenReturn(partner);
    bankAccount = mock(BankAccount.class);
    when(bankAccount.getAccountNo()).thenReturn("ACCNO-1");
    when(invoice.getETABAMBankaccount()).thenReturn(bankAccount);
    due = mock(FIN_PaymentSchedule.class);
    when(due.getInvoice()).thenReturn(invoice);
    when(due.getOutstandingAmount()).thenReturn(new BigDecimal("100"));
    psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getInvoicePaymentSchedule()).thenReturn(due);
    line = mock(RemittanceLine.class);
    when(line.getId()).thenReturn("L1");
    when(line.getPaymentScheduleDetail()).thenReturn(psd);
    when(line.getAmount()).thenReturn(new BigDecimal("40"));

    givenIban(VALID_IBAN);
    sepa.when(() -> SEPAUtil.getBusinessPartnerBIC(any(), anyString())).thenReturn(null);
    givenAddress(null);
  }

  @AfterEach
  void tearDown() {
    language.close();
    remUtils.close();
    sepa.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- bank account resolution

  @Test
  void theBankAccountIsTheInvoicesThenTheOrdersThenTheDefaultOrLast() {
    assertSame(bankAccount, RemittanceFileLineCheck.bankAccountOf(psd, invoice, partner));

    when(invoice.getETABAMBankaccount()).thenReturn(null);
    BankAccount orderAccount = mock(BankAccount.class);
    Order order = mock(Order.class);
    when(order.getETABAMBankaccount()).thenReturn(orderAccount);
    FIN_PaymentSchedule orderDue = mock(FIN_PaymentSchedule.class);
    when(orderDue.getOrder()).thenReturn(order);
    when(psd.getOrderPaymentSchedule()).thenReturn(orderDue);
    assertSame(orderAccount, RemittanceFileLineCheck.bankAccountOf(psd, invoice, partner));

    when(order.getETABAMBankaccount()).thenReturn(null);
    BankAccount fallback = mock(BankAccount.class);
    remUtils.when(() -> RemittanceUtils.getDefaultOrLastBA("BP-1")).thenReturn(fallback);
    assertSame(fallback, RemittanceFileLineCheck.bankAccountOf(psd, invoice, partner));
    assertSame(fallback, RemittanceFileLineCheck.bankAccountOf(null, null, partner));
  }

  @Test
  void theAccountNumberIsTheAccountNoElseTheIbanOfAnIbanAccountElseZeros() {
    BankAccount account = mock(BankAccount.class);
    when(account.getAccountNo()).thenReturn("0049-1234");
    assertEquals("0049-1234", RemittanceFileLineCheck.accountNumberOf(account));

    when(account.getAccountNo()).thenReturn(null);
    when(account.getBankFormat()).thenReturn("IBAN");
    when(account.getIBAN()).thenReturn(VALID_IBAN);
    assertEquals(VALID_IBAN, RemittanceFileLineCheck.accountNumberOf(account));

    when(account.getBankFormat()).thenReturn("GENERIC");
    assertEquals(RemittanceFileLineCheck.DEFAULT_ACCOUNT_NO,
        RemittanceFileLineCheck.accountNumberOf(account));
  }

  // ---------------------------------------------------------------- the checks

  @Test
  void aCompleteCollectionLineHasNoProblem() throws Exception {
    assertEquals(0, check(true, true).length());
  }

  @Test
  void aPartnerWithoutBankAccountIsReported() throws Exception {
    when(invoice.getETABAMBankaccount()).thenReturn(null);
    remUtils.when(() -> RemittanceUtils.getDefaultOrLastBA("BP-1")).thenReturn(null);

    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_BANK_ACCOUNT), codes(check(true, true)));
  }

  @Test
  void aMissingIbanIsReportedAndTheIbanIsLookedUpFromTheAccountNumber() throws Exception {
    givenIban("  ");

    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_IBAN), codes(check(false, true)));
    sepa.verify(() -> SEPAUtil.getBusinessPartnerIBAN(partner, "ACCNO-1", false));
  }

  /** The generator writes the stored IBAN verbatim: with a space it fails the schema. */
  @Test
  void anIbanWithSpacesIsReportedInvalid() throws Exception {
    givenIban("ES91 2100 0418 4502 0005 1332");

    JSONArray problems = check(false, true);

    assertEquals(List.of(RemittanceFileLineCheck.MSG_INVALID_IBAN), codes(problems));
    assertEquals("ES91 2100 0418 4502 0005 1332",
        problems.getJSONObject(0).getJSONArray("params").getString(2));
    assertTrue(RemittanceFileLineCheck.isWellFormedIban(VALID_IBAN));
    assertFalse(RemittanceFileLineCheck.isWellFormedIban("ES0021000418450200051332"),
        "a broken checksum");
    assertFalse(RemittanceFileLineCheck.isWellFormedIban("ES91"), "too short");
  }

  @Test
  void aMalformedBicIsReportedAndAMissingOneIsNot() throws Exception {
    sepa.when(() -> SEPAUtil.getBusinessPartnerBIC(any(), anyString())).thenReturn("BAD");
    assertEquals(List.of(RemittanceFileLineCheck.MSG_INVALID_BIC), codes(check(false, true)));

    sepa.when(() -> SEPAUtil.getBusinessPartnerBIC(any(), anyString())).thenReturn("CAIXESBBXXX");
    assertEquals(0, check(false, true).length());
  }

  @Test
  void theMandateIsCheckedOnCollectionsOnly() throws Exception {
    when(partner.getObsepadMandateIdentifier()).thenReturn(" ");
    when(partner.getObsepadMandateDate()).thenReturn(null);

    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_MANDATE,
        RemittanceFileLineCheck.MSG_NO_MANDATE_DATE), codes(check(true, true)));
    assertEquals(0, check(false, true).length(), "a payment needs no mandate");
  }

  /** EPC130-08 1.4: the mandate reference, as the generator writes it, obeys the slash rule. */
  @Test
  void aMandateReferenceBreakingTheSlashRuleIsReportedAsWritten() throws Exception {
    for (String bad : new String[] {"/M1", "M1/", "M//1", "M\\\\1", "M1\\"}) {
      when(partner.getObsepadMandateIdentifier()).thenReturn(bad);
      assertEquals(List.of(RemittanceFileLineCheck.MSG_MANDATE_SLASH), codes(check(true, true)), bad);
    }
    // cut to 35 characters the reference ends with a slash
    when(partner.getObsepadMandateIdentifier()).thenReturn("A".repeat(34) + "/B");
    assertEquals(List.of(RemittanceFileLineCheck.MSG_MANDATE_SLASH), codes(check(true, true)));

    when(partner.getObsepadMandateIdentifier()).thenReturn("M/1");
    assertEquals(0, check(true, true).length(), "one inner slash is allowed");
    assertEquals(0, check(false, true).length(), "a payment needs no mandate");
  }

  @Test
  void aPartnerWithoutTaxIdIsReported() throws Exception {
    when(partner.getTaxID()).thenReturn(null);

    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_TAX_ID), codes(check(false, true)));
  }

  @Test
  void theAddressErrorsAreTheOnesSepaUtilWords() throws Exception {
    sepa.when(() -> SEPAUtil.getBPBillToLocation(partner)).thenReturn(null);
    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_ADDRESS), codes(check(false, true)));

    givenAddress("OBSEPA_BP_NOLOCALITYCOUNTRY");
    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_LOCALITY_COUNTRY),
        codes(check(false, true)));

    givenAddress("OBSEPA_BP_NOSTRUCTUREDADDR");
    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_STRUCTURED_ADDRESS),
        codes(check(false, true)));

    givenAddress("SOMETHING_ELSE");
    assertEquals(0, check(false, true).length(), "an unknown code is not reported");
  }

  @Test
  void anAmountOfZeroOrAboveTheOutstandingIsReportedWithTheOutstanding() throws Exception {
    for (String amount : new String[] { "0", "-5", "100.01" }) {
      when(line.getAmount()).thenReturn(new BigDecimal(amount));

      JSONArray problems = check(false, true);

      assertEquals(List.of(RemittanceFileLineCheck.MSG_AMOUNT_INVALID), codes(problems), amount);
      assertEquals("100", problems.getJSONObject(0).getJSONArray("params").getString(2));
    }
    when(line.getAmount()).thenReturn(null);
    assertEquals(List.of(RemittanceFileLineCheck.MSG_AMOUNT_INVALID), codes(check(false, true)));
    when(line.getAmount()).thenReturn(new BigDecimal("100"));
    assertEquals(0, check(false, true).length(), "the whole outstanding is fine");
  }

  /** Once processed, the payments have settled the dues: the amount is not checked. */
  @Test
  void theAmountIsNotCheckedOnAProcessedRemittance() throws Exception {
    when(line.getAmount()).thenReturn(new BigDecimal("500"));

    assertEquals(0, check(false, false).length());
  }

  @Test
  void aLineWithoutAPartnerReportsOnlyThat() throws Exception {
    when(invoice.getBusinessPartner()).thenReturn(null);
    sepa.clearInvocations();

    JSONArray problems = check(true, true);

    assertEquals(List.of(RemittanceFileLineCheck.MSG_NO_PARTNER), codes(problems));
    assertEquals("INV-1", problems.getJSONObject(0).getString("invoiceId"));
    sepa.verify(() -> SEPAUtil.getBusinessPartnerIBAN(any(), any(), anyBoolean()),
        Mockito.never());
  }

  /** A line of an existing payment (no due) names the payment's partner. */
  @Test
  void aPaymentLineNamesThePaymentsPartner() throws Exception {
    when(line.getPaymentScheduleDetail()).thenReturn(null);
    FIN_Payment payment = mock(FIN_Payment.class);
    when(payment.getBusinessPartner()).thenReturn(partner);
    when(line.getPayment()).thenReturn(payment);
    remUtils.when(() -> RemittanceUtils.getDefaultOrLastBA("BP-1")).thenReturn(bankAccount);

    JSONArray problems = check(false, true);

    assertEquals(0, problems.length(), problems.toString());
  }

  // ---------------------------------------------------------------- helpers

  private JSONArray check(boolean collection, boolean checkAmount) throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    RemittanceFileLineCheck.check(line, collection, checkAmount, problems);
    return problems.toJson();
  }

  private static List<String> codes(JSONArray problems) throws Exception {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < problems.length(); i++) {
      JSONObject problem = problems.getJSONObject(i);
      codes.add(problem.getString("code"));
    }
    return codes;
  }

  private void givenIban(String iban) {
    sepa.when(() -> SEPAUtil.getBusinessPartnerIBAN(any(), any(), anyBoolean()))
        .thenReturn(iban);
  }

  /** A bill-to address whose SEPA error code is {@code error} ({@code null}: none). */
  private void givenAddress(String error) {
    Location location = mock(Location.class);
    org.openbravo.model.common.geography.Location address =
        mock(org.openbravo.model.common.geography.Location.class);
    when(location.getLocationAddress()).thenReturn(address);
    sepa.when(() -> SEPAUtil.getBPBillToLocation(partner)).thenReturn(location);
    sepa.when(() -> SEPAUtil.getBusinessPartnerAddressError(address)).thenReturn(error);
  }
}
