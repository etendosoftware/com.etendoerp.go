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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
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

import com.etendoerp.go.schemaforge.RemittanceFileProblems.LineRef;

/**
 * ETP-6247 — the checks of one remittance line, mirroring what Classic's SEPA generators
 * ({@code CoreDirectDebit} pain.008.001.08, {@code CreditTransfer} pain.001.001.09) refuse for the
 * instruction the line becomes, so the user hears about it before «Confirmar» instead of as a
 * rolled-back process.
 *
 * <p><b>The bank account</b> is resolved exactly as {@code REMRemittanceProcess} resolves the one
 * it writes on the instruction — the invoice's bank account, else the order's, else the partner's
 * default or latest one ({@link RemittanceUtils#getDefaultOrLastBA}) — and turned into the account
 * number the instruction carries; the IBAN and BIC are then looked up from that number with the
 * generator's own {@link SEPAUtil} methods. BIC is optional ({@code BIC_SWIFT_MANDATORY=N}) but its
 * format is checked when present.
 *
 * <p><b>The mandate</b> (reference and signature date) is required on collections only.
 * <b>The amount</b> is checked against the due's outstanding amount only while the remittance is
 * not processed: once processed, its payments have settled the dues.
 */
final class RemittanceFileLineCheck {

  static final String MSG_NO_PARTNER = "ETGO_RemittanceFileLineNoPartner";
  static final String MSG_NO_BANK_ACCOUNT = "ETGO_RemittanceFileLineNoBankAccount";
  static final String MSG_NO_IBAN = "ETGO_RemittanceFileLineNoIban";
  static final String MSG_INVALID_IBAN = "ETGO_RemittanceFileLineInvalidIban";
  static final String MSG_INVALID_BIC = "ETGO_RemittanceFileLineInvalidBic";
  static final String MSG_NO_MANDATE = "ETGO_RemittanceFileLineNoMandate";
  static final String MSG_NO_MANDATE_DATE = "ETGO_RemittanceFileLineNoMandateDate";
  static final String MSG_NO_TAX_ID = "ETGO_RemittanceFileLineNoTaxId";
  static final String MSG_NO_ADDRESS = "ETGO_RemittanceFileLineNoAddress";
  static final String MSG_NO_LOCALITY_COUNTRY = "ETGO_RemittanceFileLineNoLocalityCountry";
  static final String MSG_NO_STRUCTURED_ADDRESS = "ETGO_RemittanceFileLineNoStructuredAddress";
  static final String MSG_AMOUNT_INVALID = "ETGO_RemittanceFileLineAmountInvalid";

  /** {@code SEPAUtil#getBusinessPartnerAddressError} answers these two codes. */
  private static final String SEPA_NO_LOCALITY_COUNTRY = "OBSEPA_BP_NOLOCALITYCOUNTRY";
  private static final String SEPA_NO_STRUCTURED_ADDRESS = "OBSEPA_BP_NOSTRUCTUREDADDR";
  /** The account number REM writes when the partner has no bank account. */
  static final String DEFAULT_ACCOUNT_NO = "00000000000000000000";
  private static final String BANK_FORMAT_IBAN = "IBAN";
  private static final int IBAN_MIN_LENGTH = 15;

  private RemittanceFileLineCheck() {
  }

  /**
   * Checks {@code line} into {@code problems}.
   *
   * @param line        an active line of the remittance
   * @param collection  whether the remittance collects (CORE) rather than pays (SCT)
   * @param checkAmount whether to check the amount against the due's outstanding amount
   * @param problems    where the problems go
   */
  static void check(RemittanceLine line, boolean collection, boolean checkAmount,
      RemittanceFileProblems problems) throws JSONException {
    FIN_PaymentScheduleDetail psd = line.getPaymentScheduleDetail();
    Invoice invoice = invoiceOf(psd);
    BusinessPartner partner = partnerOf(line, psd, invoice);
    LineRef ref = new LineRef(line.getId(), partner != null ? partner.getId() : null,
        partner != null ? partner.getName() : null, invoice != null ? invoice.getId() : null,
        invoice != null ? invoice.getDocumentNo() : null);
    if (checkAmount) {
      checkAmount(line, psd, ref, problems);
    }
    if (partner == null) {
      problems.lineItself(ref, MSG_NO_PARTNER, ref.businessPartnerName, ref.documentNo);
      return;
    }
    checkBankAccount(partner, bankAccountOf(psd, invoice, partner), ref, problems);
    if (collection) {
      checkMandate(partner, ref, problems);
    }
    if (StringUtils.isBlank(partner.getTaxID())) {
      problems.line(ref, MSG_NO_TAX_ID, partner.getName(), ref.documentNo);
    }
    checkAddress(partner, ref, problems);
  }

  // ---------------------------------------------------------------------------
  // The checks
  // ---------------------------------------------------------------------------

  private static void checkAmount(RemittanceLine line, FIN_PaymentScheduleDetail psd, LineRef ref,
      RemittanceFileProblems problems) throws JSONException {
    BigDecimal amount = line.getAmount();
    FIN_PaymentSchedule due = psd != null ? psd.getInvoicePaymentSchedule() : null;
    BigDecimal outstanding = due != null ? due.getOutstandingAmount() : null;
    boolean positive = amount != null && amount.signum() > 0;
    boolean withinDue = outstanding == null || RemittanceLineAmount.isValid(amount, outstanding);
    if (!positive || !withinDue) {
      problems.lineItself(ref, MSG_AMOUNT_INVALID, ref.businessPartnerName, ref.documentNo,
          outstanding != null ? outstanding.toPlainString() : "-");
    }
  }

  private static void checkBankAccount(BusinessPartner partner, BankAccount bankAccount,
      LineRef ref, RemittanceFileProblems problems) throws JSONException {
    if (bankAccount == null) {
      problems.line(ref, MSG_NO_BANK_ACCOUNT, partner.getName(), ref.documentNo);
      return;
    }
    String accountNo = accountNumberOf(bankAccount);
    String iban = SEPAUtil.getBusinessPartnerIBAN(partner, accountNo, false);
    if (StringUtils.isBlank(iban)) {
      problems.line(ref, MSG_NO_IBAN, partner.getName(), ref.documentNo);
    } else if (!isWellFormedIban(iban)) {
      problems.line(ref, MSG_INVALID_IBAN, partner.getName(), ref.documentNo, iban);
    }
    String bic = SEPAUtil.getBusinessPartnerBIC(partner, accountNo);
    if (StringUtils.isNotBlank(bic) && !SEPAUtil.validateSwiftCode(bic)) {
      problems.line(ref, MSG_INVALID_BIC, partner.getName(), ref.documentNo, bic);
    }
  }

  private static void checkMandate(BusinessPartner partner, LineRef ref,
      RemittanceFileProblems problems) throws JSONException {
    if (StringUtils.isBlank(partner.getObsepadMandateIdentifier())) {
      problems.line(ref, MSG_NO_MANDATE, partner.getName(), ref.documentNo);
    }
    if (partner.getObsepadMandateDate() == null) {
      problems.line(ref, MSG_NO_MANDATE_DATE, partner.getName(), ref.documentNo);
    }
  }

  private static void checkAddress(BusinessPartner partner, LineRef ref,
      RemittanceFileProblems problems) throws JSONException {
    Location location = SEPAUtil.getBPBillToLocation(partner);
    org.openbravo.model.common.geography.Location address =
        location != null ? location.getLocationAddress() : null;
    if (address == null) {
      problems.line(ref, MSG_NO_ADDRESS, partner.getName(), ref.documentNo);
      return;
    }
    String error = SEPAUtil.getBusinessPartnerAddressError(address);
    if (SEPA_NO_LOCALITY_COUNTRY.equals(error)) {
      problems.line(ref, MSG_NO_LOCALITY_COUNTRY, partner.getName(), ref.documentNo);
    } else if (SEPA_NO_STRUCTURED_ADDRESS.equals(error)) {
      problems.line(ref, MSG_NO_STRUCTURED_ADDRESS, partner.getName(), ref.documentNo);
    }
  }

  // ---------------------------------------------------------------------------
  // Resolution, as REMRemittanceProcess does it
  // ---------------------------------------------------------------------------

  /**
   * The bank account REM writes on the instruction of this line.
   *
   * @return the account, or {@code null} when the partner has none
   */
  static BankAccount bankAccountOf(FIN_PaymentScheduleDetail psd, Invoice invoice,
      BusinessPartner partner) {
    if (invoice != null && invoice.getETABAMBankaccount() != null) {
      return invoice.getETABAMBankaccount();
    }
    Order order = psd != null && psd.getOrderPaymentSchedule() != null
        ? psd.getOrderPaymentSchedule().getOrder()
        : null;
    if (order != null && order.getETABAMBankaccount() != null) {
      return order.getETABAMBankaccount();
    }
    return RemittanceUtils.getDefaultOrLastBA(partner.getId());
  }

  /**
   * The account number REM writes on the instruction for {@code bankAccount}: its account number,
   * else its IBAN when its format is IBAN, else REM's placeholder of zeros.
   */
  static String accountNumberOf(BankAccount bankAccount) {
    if (bankAccount.getAccountNo() != null) {
      return bankAccount.getAccountNo();
    }
    return BANK_FORMAT_IBAN.equals(bankAccount.getBankFormat()) ? bankAccount.getIBAN()
        : DEFAULT_ACCOUNT_NO;
  }

  /**
   * Whether {@code iban} is what the schema accepts verbatim: the generator writes the stored
   * value as is, so a separator (a space) fails the file even when the digits are right.
   */
  static boolean isWellFormedIban(String iban) {
    String trimmed = iban.trim();
    String normalized = FinancialAccountCountrySupport.normalizeIban(trimmed);
    return normalized.equals(trimmed) && normalized.length() >= IBAN_MIN_LENGTH
        && FinancialAccountCountrySupport.isChecksumValid(normalized);
  }

  private static Invoice invoiceOf(FIN_PaymentScheduleDetail psd) {
    FIN_PaymentSchedule due = psd != null ? psd.getInvoicePaymentSchedule() : null;
    return due != null ? due.getInvoice() : null;
  }

  private static BusinessPartner partnerOf(RemittanceLine line, FIN_PaymentScheduleDetail psd,
      Invoice invoice) {
    if (invoice != null) {
      return invoice.getBusinessPartner();
    }
    if (psd != null && psd.getOrderPaymentSchedule() != null) {
      return psd.getOrderPaymentSchedule().getOrder().getBusinessPartner();
    }
    FIN_Payment payment = line.getPayment();
    if (payment != null && payment.getBusinessPartner() != null) {
      return payment.getBusinessPartner();
    }
    return psd != null ? psd.getBusinessPartner() : null;
  }
}
