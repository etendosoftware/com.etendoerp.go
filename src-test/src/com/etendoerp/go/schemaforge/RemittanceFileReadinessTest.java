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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceType;

import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * Unit tests for {@link RemittanceFileReadiness}, {@code fileReadiness}: the organization checks
 * (address, locality and country, street and number, tax ID — on a collection one that forms the
 * creditor identifier), the account checks (a GO-provisioned type with a generator, a valid IBAN,
 * a well-formed BIC, euros), the line checks it delegates per line, the sum of the lines of one
 * due against its outstanding amount (reported once per due, only while unprocessed, only when
 * every line fits alone), the {@code summary} (distinct
 * invoices, lines, total, currency, due date) and the {@code latestFile} (the main {@code .xml}
 * attachment only).
 *
 * <p>The organization and account rules run for real on mocked entities (SEPAUtil's address
 * predicates, the account form's IBAN pair check, the creditor identifier); the scheme, the
 * creditor business code, the lines, the per-line check and the main attachment are static seams
 * mocked here. {@code RemittanceFileLineCheckTest} specifies the line checks.
 *
 * <p>A class of its own: {@code RemittanceFileReadiness} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileReadiness
 */
class RemittanceFileReadinessTest {

  private static final String VALID_IBAN = "ES9121000418450200051332";

  private MockedStatic<RemittanceSepaFileSetup> setup;
  private MockedStatic<RemittanceSepaProvisioning> provisioning;
  private MockedStatic<RemittanceFileReadiness> readiness;
  private MockedStatic<RemittanceFileLineCheck> lineCheck;
  private MockedStatic<NeoAttachmentsHelper> attachments;
  private MockedStatic<NeoLanguage> language;

  private Remittance remittance;
  private Organization org;
  private OrganizationInformation info;
  private Location location;
  private FIN_FinancialAccount account;
  private final List<RemittanceLine> lines = new ArrayList<>();

  @BeforeEach
  void setUp() {
    setup = mockStatic(RemittanceSepaFileSetup.class, Mockito.CALLS_REAL_METHODS);
    provisioning = mockStatic(RemittanceSepaProvisioning.class, Mockito.CALLS_REAL_METHODS);
    readiness = mockStatic(RemittanceFileReadiness.class, Mockito.CALLS_REAL_METHODS);
    lineCheck = mockStatic(RemittanceFileLineCheck.class, Mockito.CALLS_REAL_METHODS);
    attachments = mockStatic(NeoAttachmentsHelper.class);
    language = mockStatic(NeoLanguage.class);
    language.when(NeoLanguage::requestCode).thenReturn("en_US");

    Country spain = mock(Country.class);
    when(spain.getISOCountryCode()).thenReturn("ES");
    when(spain.getIBANCode()).thenReturn("ES");
    when(spain.getIBANLength()).thenReturn(24L);
    when(spain.getName()).thenReturn("Spain");
    location = mock(Location.class);
    when(location.getCityName()).thenReturn("Pamplona");
    when(location.getCountry()).thenReturn(spain);
    when(location.getOBSEPAStreetName()).thenReturn("Calle Mayor");
    when(location.getOBSEPABuildingNumber()).thenReturn("1");
    info = mock(OrganizationInformation.class);
    when(info.getLocationAddress()).thenReturn(location);
    when(info.getTaxID()).thenReturn("B12345678");
    org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    when(org.getName()).thenReturn("Norte");
    when(org.getClient()).thenReturn(mock(Client.class));
    when(org.getOrganizationInformationList()).thenReturn(List.of(info));
    Currency euro = mock(Currency.class);
    when(euro.getId()).thenReturn(RemittanceFileReadiness.EUR_ID);
    when(euro.getISOCode()).thenReturn("EUR");
    account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("ACC-1");
    when(account.getName()).thenReturn("BBVA");
    when(account.getIBAN()).thenReturn(VALID_IBAN);
    when(account.getCountry()).thenReturn(spain);
    when(account.getCurrency()).thenReturn(euro);
    RemittanceType type = mock(RemittanceType.class);
    when(type.getName()).thenReturn("SEPA Core");
    remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn("REM-1");
    when(remittance.getOrganization()).thenReturn(org);
    when(remittance.getFinancialAccount()).thenReturn(account);
    when(remittance.getRemittanceType()).thenReturn(type);

    givenScheme(RemittanceSepaProvisioning.SCHEME_CORE);
    provisioning.when(() -> RemittanceSepaProvisioning.creditorBusinessCode(any()))
        .thenReturn("000");
    readiness.when(() -> RemittanceFileReadiness.activeLines(remittance)).thenReturn(lines);
    lineCheck.when(() -> RemittanceFileLineCheck.check(any(), anyBoolean(), anyBoolean(), any()))
        .then(inv -> null);
  }

  @AfterEach
  void tearDown() {
    language.close();
    attachments.close();
    lineCheck.close();
    readiness.close();
    provisioning.close();
    setup.close();
    Mockito.framework().clearInlineMocks();
  }

  @Test
  void aCompleteRemittanceIsReady() throws Exception {
    RemittanceFileReadiness.Result result = RemittanceFileReadiness.evaluate(remittance);

    assertTrue(result.isReady(), result.problems.toJson().toString());
    assertTrue(result.isCollection());
    assertEquals(RemittanceSepaProvisioning.SCHEME_CORE, result.scheme);
  }

  // ---------------------------------------------------------------- organization

  @Test
  void anOrganizationWithoutAddressIsReported() throws Exception {
    when(org.getOrganizationInformationList()).thenReturn(List.of());
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_ADDRESS,
        RemittanceFileReadiness.MSG_ORG_NO_TAX_ID), codes(), "no info: no address, no tax ID");

    when(org.getOrganizationInformationList()).thenReturn(List.of(info));
    when(info.getLocationAddress()).thenReturn(null);
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_ADDRESS), codes());
  }

  @Test
  void anOrganizationAddressWithoutOnlyTheCityNamesTheCity() throws Exception {
    when(location.getCityName()).thenReturn(" ");
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_LOCALITY), codes());
  }

  @Test
  void anOrganizationAddressWithoutOnlyTheCountryNamesTheCountry() throws Exception {
    when(location.getCountry()).thenReturn(null);
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_COUNTRY), codes());
  }

  @Test
  void anOrganizationAddressWithoutCityAndCountryKeepsTheCombinedMessage() throws Exception {
    when(location.getCityName()).thenReturn(null);
    when(location.getCountry()).thenReturn(null);
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_LOCALITY_COUNTRY), codes());
  }

  @Test
  void anOrganizationAddressWithoutStreetOrNumberIsReported() throws Exception {
    when(location.getOBSEPAStreetName()).thenReturn(null);
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_STRUCTURED_ADDRESS), codes());

    when(location.getOBSEPAStreetName()).thenReturn("Calle Mayor");
    when(location.getOBSEPABuildingNumber()).thenReturn("");
    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_STRUCTURED_ADDRESS), codes());
  }

  @Test
  void anOrganizationWithoutTaxIdIsReported() throws Exception {
    when(info.getTaxID()).thenReturn("  ");

    JSONArray problems = problems();

    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_TAX_ID), codes(problems));
    assertEquals("ORG-1", problems.getJSONObject(0).getString("organizationId"));
  }

  /** On a collection the tax ID must form the creditor identifier; on a payment it need not. */
  @Test
  void onACollectionATaxIdThatCannotFormTheCreditorIdIsReported() throws Exception {
    when(info.getTaxID()).thenReturn("B123456789012345678901234567890");

    assertEquals(List.of(RemittanceFileReadiness.MSG_ORG_NO_TAX_ID), codes());

    givenScheme(RemittanceSepaProvisioning.SCHEME_SCT);
    assertEquals(List.of(), codes(), "a payment file does not use the creditor identifier");
  }

  // ---------------------------------------------------------------- account

  @Test
  void aTypeGoDidNotProvisionHasNoGenerator() throws Exception {
    givenScheme(null);

    JSONArray problems = problems();

    assertEquals(List.of(RemittanceFileReadiness.MSG_NO_GENERATOR), codes(problems));
    assertEquals("[\"SEPA Core\"]", problems.getJSONObject(0).getJSONArray("params").toString());
    assertEquals("ACC-1", problems.getJSONObject(0).getString("accountId"));
  }

  @Test
  void anAccountWithoutAValidIbanIsReported() throws Exception {
    when(account.getIBAN()).thenReturn("ES0021000418450200051332");
    assertEquals(List.of(RemittanceFileReadiness.MSG_ACCOUNT_NO_IBAN), codes());

    when(account.getIBAN()).thenReturn(null);
    assertEquals(List.of(RemittanceFileReadiness.MSG_ACCOUNT_NO_IBAN), codes());
  }

  @Test
  void aMalformedBicIsReportedAndAMissingOneIsNot() throws Exception {
    when(account.getSwiftCode()).thenReturn("NOT A BIC");
    JSONArray problems = problems();
    assertEquals(List.of(RemittanceFileReadiness.MSG_ACCOUNT_INVALID_BIC), codes(problems));
    assertEquals("[\"BBVA\",\"NOT A BIC\"]",
        problems.getJSONObject(0).getJSONArray("params").toString());

    when(account.getSwiftCode()).thenReturn("CAIXESBBXXX");
    assertEquals(List.of(), codes());
  }

  @Test
  void anAccountNotInEurosIsReported() throws Exception {
    Currency dollar = mock(Currency.class);
    when(dollar.getId()).thenReturn("100");
    when(account.getCurrency()).thenReturn(dollar);

    assertEquals(List.of(RemittanceFileReadiness.MSG_ACCOUNT_NOT_EUR), codes());
  }

  // ---------------------------------------------------------------- lines and summary

  @Test
  void everyLineIsCheckedAndTheAmountOnlyWhileUnprocessed() throws Exception {
    RemittanceLine first = line("L1", "INV-1", "10");
    lines.add(first);

    RemittanceFileReadiness.evaluate(remittance);
    lineCheck.verify(() -> RemittanceFileLineCheck.check(eq(first), eq(true), eq(true), any()));

    when(remittance.isProcessed()).thenReturn(true);
    givenScheme(RemittanceSepaProvisioning.SCHEME_SCT);
    RemittanceFileReadiness.evaluate(remittance);
    lineCheck.verify(() -> RemittanceFileLineCheck.check(eq(first), eq(false), eq(false), any()));
  }

  @Test
  void theSummaryCountsDistinctInvoicesAndTotalsTheLines() throws Exception {
    lines.add(line("L1", "INV-1", "10.50"));
    lines.add(line("L2", "INV-1", "4.50"));
    lines.add(line("L3", "INV-2", "20"));
    lines.add(line("L4", null, "5"));
    when(remittance.getDueDate()).thenReturn(Date.from(LocalDate.of(2026, 12, 15)
        .atStartOfDay(ZoneId.systemDefault()).toInstant()));

    JSONObject body = RemittanceFileReadiness.body(remittance);

    assertTrue(body.getBoolean("ready"));
    assertEquals("CORE", body.getString("scheme"));
    JSONObject summary = body.getJSONObject("summary");
    assertEquals(2, summary.getInt("invoiceCount"), "distinct invoices, a line without one aside");
    assertEquals(4, summary.getInt("lineCount"));
    assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(
        summary.get("totalAmount").toString())));
    assertEquals("EUR", summary.getString("currency"));
    assertEquals("2026-12-15", summary.getString("dueDate"));
    assertTrue(body.isNull("latestFile"));
  }

  @Test
  void theLatestFileIsTheMainXmlAttachmentOnly() throws Exception {
    Attachment main = mock(Attachment.class);
    when(main.getId()).thenReturn("ATT-1");
    when(main.getName()).thenReturn("REM-001_Cobros.XML");
    attachments.when(() -> NeoAttachmentsHelper.findMainAttachment(
        RemittanceSepaProvisioning.REMITTANCE_TABLE_ID, "REM-1")).thenReturn(main);

    JSONObject latest = RemittanceFileReadiness.latestFile(remittance);
    assertEquals("ATT-1", latest.getString("attachmentId"));
    assertEquals("REM-001_Cobros.XML", latest.getString("fileName"));

    when(main.getName()).thenReturn("contract.pdf");
    assertNull(RemittanceFileReadiness.latestFile(remittance), "not a SEPA file");
    when(main.getName()).thenReturn(null);
    assertNull(RemittanceFileReadiness.latestFile(remittance));
    attachments.when(() -> NeoAttachmentsHelper.findMainAttachment(
        RemittanceSepaProvisioning.REMITTANCE_TABLE_ID, "REM-1")).thenReturn(null);
    assertNull(RemittanceFileReadiness.latestFile(remittance));
  }

  @Test
  void aRemittanceWithProblemsIsNotReadyAndListsThem() throws Exception {
    when(info.getTaxID()).thenReturn(null);

    JSONObject body = RemittanceFileReadiness.body(remittance);

    assertFalse(body.getBoolean("ready"));
    assertEquals(1, body.getJSONArray("problems").length());
  }

  // ---------------------------------------------------------------- helpers

  // ---------------------------------------------------------------- the sum of a due

  /**
   * REM refuses lines of one due whose amounts add up to more than its outstanding amount even
   * when each fits alone: reported once, named by the first line, carrying every line.
   */
  @Test
  void linesThatFitAloneButExceedTheirDueTogetherAreReportedOnceForTheDue() throws Exception {
    FIN_PaymentSchedule due = due("DUE-1", "100");
    lines.add(lineOf("L1", due, "60"));
    lines.add(lineOf("L2", due, "50"));
    lines.add(lineOf("L3", due, "0.01"));

    JSONArray problems = problems();

    assertEquals(1, problems.length(), problems.toString());
    JSONObject problem = problems.getJSONObject(0);
    assertEquals(RemittanceFileLineCheck.MSG_AMOUNT_INVALID, problem.getString("code"));
    assertEquals("L1", problem.getString("lineId"));
    assertEquals("[\"L1\",\"L2\",\"L3\"]", problem.getJSONArray("lineIds").toString());
    assertEquals("BP-DUE-1", problem.getString("businessPartnerId"));
    assertEquals("INV-DUE-1", problem.getString("invoiceId"));
    assertEquals("[\"Acme\",\"F-DUE-1\",\"100\"]", problem.getJSONArray("params").toString(),
        "partner name, document number and the outstanding amount");
  }

  @Test
  void linesThatAddUpToExactlyTheOutstandingAreReady() throws Exception {
    FIN_PaymentSchedule due = due("DUE-1", "100");
    lines.add(lineOf("L1", due, "60"));
    lines.add(lineOf("L2", due, "40"));

    assertEquals(0, problems().length());
  }

  /** A line that fails on its own is the line check's to report; the sum adds nothing. */
  @Test
  void aDueWithALineThatFailsAloneIsNotReportedAgainForItsSum() throws Exception {
    FIN_PaymentSchedule due = due("DUE-1", "100");
    lines.add(lineOf("L1", due, "150"));
    lines.add(lineOf("L2", due, "10"));
    FIN_PaymentSchedule other = due("DUE-2", "100");
    lines.add(lineOf("L3", other, "0"));
    lines.add(lineOf("L4", other, "60"));
    lines.add(lineOf("L5", other, "60"));

    assertEquals(0, problems().length());
  }

  @Test
  void onlyTheDueThatIsExceededIsReportedAndASingleLineNever() throws Exception {
    FIN_PaymentSchedule fits = due("DUE-1", "100");
    lines.add(lineOf("L1", fits, "30"));
    lines.add(lineOf("L2", fits, "30"));
    FIN_PaymentSchedule exceeded = due("DUE-2", "50");
    lines.add(lineOf("L3", exceeded, "30"));
    lines.add(lineOf("L4", exceeded, "30"));
    lines.add(lineOf("L5", due("DUE-3", "10"), "10"));

    JSONArray problems = problems();

    assertEquals(1, problems.length(), problems.toString());
    assertEquals("[\"L3\",\"L4\"]", problems.getJSONObject(0).getJSONArray("lineIds")
        .toString());
  }

  @Test
  void theSumIsNotCheckedOnceProcessed() throws Exception {
    FIN_PaymentSchedule due = due("DUE-1", "100");
    lines.add(lineOf("L1", due, "60"));
    lines.add(lineOf("L2", due, "50"));
    when(remittance.isProcessed()).thenReturn(true);

    assertEquals(0, problems().length());
  }

  /** A due whose outstanding amount is unknown is not summed. */
  @Test
  void aDueWithoutOutstandingIsNotSummed() throws Exception {
    FIN_PaymentSchedule due = due("DUE-1", "100");
    when(due.getOutstandingAmount()).thenReturn(null);
    lines.add(lineOf("L1", due, "60"));
    lines.add(lineOf("L2", due, "50"));

    assertEquals(0, problems().length());
  }

  private void givenScheme(String scheme) {
    setup.when(() -> RemittanceSepaFileSetup.schemeOf(any())).thenReturn(scheme);
  }

  private JSONArray problems() throws Exception {
    return RemittanceFileReadiness.evaluate(remittance).problems.toJson();
  }

  private List<String> codes() throws Exception {
    return codes(problems());
  }

  private static List<String> codes(JSONArray problems) throws Exception {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < problems.length(); i++) {
      codes.add(problems.getJSONObject(i).getString("code"));
    }
    return codes;
  }

  private static RemittanceLine line(String id, String invoiceId, String amount) {
    RemittanceLine line = mock(RemittanceLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getAmount()).thenReturn(new BigDecimal(amount));
    if (invoiceId != null) {
      Invoice invoice = mock(Invoice.class);
      when(invoice.getId()).thenReturn(invoiceId);
      FIN_PaymentSchedule due = mock(FIN_PaymentSchedule.class);
      when(due.getInvoice()).thenReturn(invoice);
      FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
      when(psd.getInvoicePaymentSchedule()).thenReturn(due);
      when(line.getPaymentScheduleDetail()).thenReturn(psd);
    }
    return line;
  }

  /** A due of {@code outstanding}, of invoice {@code INV-<id>} of «Acme». */
  private static FIN_PaymentSchedule due(String id, String outstanding) {
    BusinessPartner partner = mock(BusinessPartner.class);
    when(partner.getId()).thenReturn("BP-" + id);
    when(partner.getName()).thenReturn("Acme");
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn("INV-" + id);
    when(invoice.getDocumentNo()).thenReturn("F-" + id);
    when(invoice.getBusinessPartner()).thenReturn(partner);
    FIN_PaymentSchedule due = mock(FIN_PaymentSchedule.class);
    when(due.getId()).thenReturn(id);
    when(due.getInvoice()).thenReturn(invoice);
    when(due.getOutstandingAmount()).thenReturn(new BigDecimal(outstanding));
    return due;
  }

  private static RemittanceLine lineOf(String id, FIN_PaymentSchedule due, String amount) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getInvoicePaymentSchedule()).thenReturn(due);
    RemittanceLine line = mock(RemittanceLine.class);
    when(line.getId()).thenReturn(id);
    when(line.getAmount()).thenReturn(new BigDecimal(amount));
    when(line.getPaymentScheduleDetail()).thenReturn(psd);
    return line;
  }
}
