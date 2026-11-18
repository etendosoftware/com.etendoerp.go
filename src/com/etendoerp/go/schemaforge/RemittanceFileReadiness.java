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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.sepa.iso20022.util.SEPAUtil;

/**
 * ETP-6247 — {@code fileReadiness}: whether the SEPA file of a remittance can be generated, and if
 * not, why. A read: it writes nothing.
 *
 * <p>The checks mirror what Classic's SEPA generators refuse ({@code SEPA_RemittanceCreateFile
 * #initializeAndVerify}, {@code CoreDirectDebit}, {@code CreditTransfer}) with the parameters
 * {@link RemittanceSepaFileSetup} gives the provisioned types:
 * <ul>
 *   <li><b>organization</b> — the remittance organization's own information (no parent fallback,
 *       as the generator reads it): an address with locality, country, street name and building
 *       number (both schemes ask for the structured creditor/debtor address), and a tax ID — on a
 *       collection, one that forms the SEPA creditor identifier;</li>
 *   <li><b>account</b> — a valid IBAN for its country, a well-formed BIC when it has one, euros;
 *       and a remittance type GO provisioned (one with a SEPA generator);</li>
 *   <li><b>lines</b> — {@link RemittanceFileLineCheck}.</li>
 * </ul>
 *
 * <p>The answer also carries the {@code summary} the confirm dialog shows (distinct invoices,
 * lines, total, currency, due date) and the {@code latestFile}: the record's main {@code .xml}
 * attachment, when there is one.
 */
final class RemittanceFileReadiness {

  static final String ACTION = "fileReadiness";

  static final String MSG_ORG_NO_TAX_ID = "ETGO_RemittanceFileOrgNoTaxId";
  static final String MSG_ORG_NO_ADDRESS = "ETGO_RemittanceFileOrgNoAddress";
  static final String MSG_ORG_NO_LOCALITY_COUNTRY = "ETGO_RemittanceFileOrgNoLocalityCountry";
  static final String MSG_ORG_NO_LOCALITY = "ETGO_RemittanceFileOrgNoLocality";
  static final String MSG_ORG_NO_COUNTRY = "ETGO_RemittanceFileOrgNoCountry";
  static final String MSG_ORG_NO_STRUCTURED_ADDRESS =
      "ETGO_RemittanceFileOrgNoStructuredAddress";
  static final String MSG_ACCOUNT_NO_IBAN = "ETGO_RemittanceFileAccountNoIban";
  static final String MSG_ACCOUNT_INVALID_BIC = "ETGO_RemittanceFileAccountInvalidBic";
  static final String MSG_ACCOUNT_NOT_EUR = "ETGO_RemittanceFileAccountNotEur";
  static final String MSG_NO_GENERATOR = "ETGO_RemittanceFileNoGenerator";

  static final String KEY_READY = "ready";
  static final String KEY_SCHEME = "scheme";
  static final String KEY_PROBLEMS = "problems";
  static final String KEY_SUMMARY = "summary";
  static final String KEY_LATEST_FILE = "latestFile";
  static final String KEY_ATTACHMENT_ID = "attachmentId";
  static final String KEY_FILE_NAME = "fileName";

  /** {@code C_Currency_ID} of the euro, the only currency SEPA files carry. */
  static final String EUR_ID = "102";
  private static final String XML_SUFFIX = ".xml";

  private RemittanceFileReadiness() {
  }

  /** The outcome of {@link #evaluate}: the problems and the figures of the summary. */
  static final class Result {
    final String scheme;
    final RemittanceFileProblems problems;
    final int lineCount;
    final int invoiceCount;
    final BigDecimal totalAmount;

    Result(String scheme, RemittanceFileProblems problems, int lineCount, int invoiceCount,
        BigDecimal totalAmount) {
      this.scheme = scheme;
      this.problems = problems;
      this.lineCount = lineCount;
      this.invoiceCount = invoiceCount;
      this.totalAmount = totalAmount;
    }

    boolean isReady() {
      return problems.isEmpty();
    }

    boolean isCollection() {
      return RemittanceSepaProvisioning.SCHEME_CORE.equals(scheme);
    }
  }

  /**
   * Runs every check on {@code remittance}. The caller runs in admin mode.
   *
   * @param remittance the remittance
   * @return the problems and the summary figures
   */
  static Result evaluate(Remittance remittance) throws JSONException {
    String scheme = RemittanceSepaFileSetup.schemeOf(remittance.getRemittanceType());
    boolean collection = RemittanceSepaProvisioning.SCHEME_CORE.equals(scheme);
    RemittanceFileProblems problems = new RemittanceFileProblems();
    checkOrganization(remittance.getOrganization(), collection, problems);
    checkAccount(remittance, scheme, problems);
    boolean checkAmount = !Boolean.TRUE.equals(remittance.isProcessed());
    Set<String> invoices = new HashSet<>();
    BigDecimal total = BigDecimal.ZERO;
    Map<String, DueTotal> dues = new LinkedHashMap<>();
    List<RemittanceLine> lines = activeLines(remittance);
    for (RemittanceLine line : lines) {
      RemittanceFileLineCheck.check(line, collection, checkAmount, problems);
      String invoiceId = invoiceIdOf(line);
      if (invoiceId != null) {
        invoices.add(invoiceId);
      }
      if (line.getAmount() != null) {
        total = total.add(line.getAmount());
      }
      addToDue(dues, line);
    }
    if (checkAmount) {
      checkDueTotals(dues, problems);
    }
    return new Result(scheme, problems, lines.size(), invoices.size(), total);
  }

  /**
   * The {@code fileReadiness} answer.
   *
   * @param remittance the remittance
   * @return {@code {ready, scheme, problems, summary, latestFile}}
   */
  static JSONObject body(Remittance remittance) throws JSONException {
    Result result = evaluate(remittance);
    JSONObject body = new JSONObject();
    body.put(KEY_READY, result.isReady());
    body.put(KEY_SCHEME, result.scheme != null ? result.scheme : JSONObject.NULL);
    body.put(KEY_PROBLEMS, result.problems.toJson());
    body.put(KEY_SUMMARY, summary(remittance, result));
    JSONObject latest = latestFile(remittance);
    body.put(KEY_LATEST_FILE, latest != null ? latest : JSONObject.NULL);
    return body;
  }

  /**
   * The record's main {@code .xml} attachment, as {@code {attachmentId, fileName}}.
   *
   * @param remittance the remittance
   * @return the file, or {@code null} when the remittance has no generated file
   */
  static JSONObject latestFile(Remittance remittance) throws JSONException {
    Attachment main = NeoAttachmentsHelper.findMainAttachment(
        RemittanceSepaProvisioning.REMITTANCE_TABLE_ID, remittance.getId());
    if (main == null || main.getName() == null
        || !main.getName().toLowerCase(Locale.ROOT).endsWith(XML_SUFFIX)) {
      return null;
    }
    JSONObject file = new JSONObject();
    file.put(KEY_ATTACHMENT_ID, main.getId());
    file.put(KEY_FILE_NAME, main.getName());
    return file;
  }

  // ---------------------------------------------------------------------------
  // Organization and account
  // ---------------------------------------------------------------------------

  private static void checkOrganization(Organization organization, boolean collection,
      RemittanceFileProblems problems) throws JSONException {
    List<OrganizationInformation> infos = organization.getOrganizationInformationList();
    OrganizationInformation info = infos == null || infos.isEmpty() ? null : infos.get(0);
    Location location = info != null ? info.getLocationAddress() : null;
    String name = organization.getName();
    if (location == null) {
      problems.org(organization.getId(), MSG_ORG_NO_ADDRESS, name);
    } else if (!SEPAUtil.hasLocalityAndCountry(location)) {
      problems.org(organization.getId(), localityCountryMessage(location), name);
    } else if (!SEPAUtil.hasStructuredAddress(location)) {
      problems.org(organization.getId(), MSG_ORG_NO_STRUCTURED_ADDRESS, name);
    }
    String taxId = info != null ? StringUtils.trimToNull(info.getTaxID()) : null;
    String country = location != null && location.getCountry() != null
        ? location.getCountry().getISOCountryCode()
        : null;
    boolean creditorIdMissing = collection && country != null && taxId != null
        && SepaCreditorId.build(country, RemittanceSepaFileData.suffixOf(
            RemittanceSepaProvisioning.creditorBusinessCode(organization.getClient())), taxId)
            == null;
    if (taxId == null || creditorIdMissing) {
      problems.org(organization.getId(), MSG_ORG_NO_TAX_ID, name);
    }
  }

  /**
   * The message for an address that fails {@link SEPAUtil#hasLocalityAndCountry}: it names what is
   * missing, the city, the country or both ({@value #MSG_ORG_NO_LOCALITY_COUNTRY}).
   */
  private static String localityCountryMessage(Location location) {
    boolean noCity = StringUtils.isBlank(location.getCityName());
    boolean noCountry = location.getCountry() == null;
    if (noCity && !noCountry) {
      return MSG_ORG_NO_LOCALITY;
    }
    return noCountry && !noCity ? MSG_ORG_NO_COUNTRY : MSG_ORG_NO_LOCALITY_COUNTRY;
  }

  private static void checkAccount(Remittance remittance, String scheme,
      RemittanceFileProblems problems) throws JSONException {
    FIN_FinancialAccount account = remittance.getFinancialAccount();
    if (scheme == null) {
      problems.account(account.getId(), MSG_NO_GENERATOR,
          remittance.getRemittanceType() != null ? remittance.getRemittanceType().getName()
              : null);
    }
    if (!RemittanceHeaderHandler.hasValidIban(account)) {
      problems.account(account.getId(), MSG_ACCOUNT_NO_IBAN, account.getName());
    }
    String bic = StringUtils.trimToNull(account.getSwiftCode());
    if (bic != null && !SEPAUtil.validateSwiftCode(bic)) {
      problems.account(account.getId(), MSG_ACCOUNT_INVALID_BIC, account.getName(), bic);
    }
    if (account.getCurrency() == null || !EUR_ID.equals(account.getCurrency().getId())) {
      problems.account(account.getId(), MSG_ACCOUNT_NOT_EUR, account.getName());
    }
  }

  // ---------------------------------------------------------------------------
  // Lines and summary
  // ---------------------------------------------------------------------------

  /**
   * The remittance's active lines in line order. Seam for tests.
   *
   * @param remittance the remittance
   * @return its active lines
   */
  static List<RemittanceLine> activeLines(Remittance remittance) {
    return OBDal.getInstance().getSession().createQuery(
        "select rl from " + RemittanceLine.ENTITY_NAME + " as rl"
            + " where rl.remittance.id = :remittance and rl.active = true"
            + " order by rl.lineNo, rl.id", RemittanceLine.class)
        .setParameter("remittance", remittance.getId())
        .list();
  }

  /**
   * The lines of one due ({@code FIN_Payment_Schedule}) and what they remit together. REM refuses
   * to process lines whose amounts add up to more than the due's outstanding amount even when each
   * line alone fits, so the sum is checked too.
   */
  static final class DueTotal {
    final RemittanceFileProblems.LineRef first;
    final BigDecimal outstanding;
    final List<String> lineIds = new ArrayList<>();
    BigDecimal sum = BigDecimal.ZERO;
    boolean everyLineFits = true;

    DueTotal(RemittanceFileProblems.LineRef first, BigDecimal outstanding) {
      this.first = first;
      this.outstanding = outstanding;
    }
  }

  private static void addToDue(Map<String, DueTotal> dues, RemittanceLine line) {
    FIN_PaymentScheduleDetail psd = line.getPaymentScheduleDetail();
    FIN_PaymentSchedule due = psd != null ? psd.getInvoicePaymentSchedule() : null;
    if (due == null || due.getOutstandingAmount() == null) {
      return;
    }
    DueTotal totalOfDue = dues.computeIfAbsent(due.getId(),
        id -> new DueTotal(refOf(line, due.getInvoice()), due.getOutstandingAmount()));
    BigDecimal amount = line.getAmount();
    totalOfDue.lineIds.add(line.getId());
    totalOfDue.everyLineFits &= amount != null && amount.signum() > 0
        && RemittanceLineAmount.isValid(amount, totalOfDue.outstanding);
    totalOfDue.sum = totalOfDue.sum.add(amount != null ? amount : BigDecimal.ZERO);
  }

  private static RemittanceFileProblems.LineRef refOf(RemittanceLine line, Invoice invoice) {
    if (invoice == null) {
      return new RemittanceFileProblems.LineRef(line.getId(), null, null, null, null);
    }
    BusinessPartner partner = invoice.getBusinessPartner();
    return new RemittanceFileProblems.LineRef(line.getId(),
        partner != null ? partner.getId() : null, partner != null ? partner.getName() : null,
        invoice.getId(), invoice.getDocumentNo());
  }

  /**
   * Reports, once per due, the dues with several lines that each fit but together exceed the
   * outstanding amount; a line that does not fit on its own is already reported by
   * {@link RemittanceFileLineCheck}.
   */
  private static void checkDueTotals(Map<String, DueTotal> dues, RemittanceFileProblems problems)
      throws JSONException {
    for (Map.Entry<String, DueTotal> entry : dues.entrySet()) {
      DueTotal due = entry.getValue();
      if (due.lineIds.size() > 1 && due.everyLineFits
          && !RemittanceLineAmount.isValid(due.sum, due.outstanding)) {
        problems.due(due.first, entry.getKey(), due.lineIds,
            RemittanceFileLineCheck.MSG_AMOUNT_INVALID, due.first.businessPartnerName,
            due.first.documentNo, due.outstanding.toPlainString());
      }
    }
  }

  static String invoiceIdOf(RemittanceLine line) {
    FIN_PaymentScheduleDetail psd = line.getPaymentScheduleDetail();
    FIN_PaymentSchedule due = psd != null ? psd.getInvoicePaymentSchedule() : null;
    return due != null && due.getInvoice() != null ? due.getInvoice().getId() : null;
  }

  private static JSONObject summary(Remittance remittance, Result result) throws JSONException {
    FIN_FinancialAccount account = remittance.getFinancialAccount();
    JSONObject summary = new JSONObject();
    summary.put("invoiceCount", result.invoiceCount);
    summary.put("lineCount", result.lineCount);
    summary.put("totalAmount", result.totalAmount);
    summary.put("currency", account != null && account.getCurrency() != null
        ? account.getCurrency().getISOCode()
        : JSONObject.NULL);
    summary.put("dueDate", remittance.getDueDate() != null
        ? new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(remittance.getDueDate())
        : JSONObject.NULL);
    return summary;
  }
}
