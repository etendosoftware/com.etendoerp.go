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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BankAccount;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.module.remittance.Instruction;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.REM_RemittanceCreateFile;

/**
 * Real-database acceptance of the SEPA files (ETP-6247), including a collection or payment whose
 * own values differ from its account's (creditor identifier, IBAN, B2B scheme, financed prefix):
 * the generated XML is parsed and validated against the XSD, and the copies the REM trigger makes
 * on the remittance are what a regeneration writes. See the tests named after those behaviors.
 *
 * <p>Original scope: a minimal remittance of each scheme is
 * built and processed by REM inside the test transaction, then its file is generated with the
 * Classic generator its GO-provisioned type names ({@link RemittanceSepaFileSetup}) and checked
 * against the XSD of its schema version — pain.008.001.08 for the collection (CORE), pain.001.001.09
 * for the payment (SCT). Two ways: the Classic generator run directly and validated here with the
 * JDK's own schema validator, and GO's {@link RemittanceFileGenerator#generate}, which validates
 * the file itself.
 *
 * <p><b>Fixtures.</b> The local database has no partner bank account and the F&B sample client no
 * open sales due, so:
 * <ul>
 *   <li><b>payment (SCT)</b> — F&B sample client: purchase invoice 10001723's due (EUR 133.10,
 *       partner «SI Rapid, SA»), remitted from F&B España's EUR «Cuenta de Banco», given a valid
 *       IBAN here;</li>
 *   <li><b>collection (CORE)</b> — GOClient (the Etendo GO tenant of the local database, already
 *       provisioned): sales invoice FV1000045's due (EUR 40.91, «Laura Morat», who has a mandate),
 *       remitted from «Cuenta Remesa ETP-6148»; the test runs as GOClient's admin role;</li>
 *   <li>both: the partner gets an IBAN bank account and the organization a structured address,
 *       created or completed here.</li>
 * </ul>
 * Everything is written in the test transaction and rolled back after each test.
 *
 * <p>A class of its own: {@code RemittanceFileGeneratorTest} mocks the generators;
 * {@code RemittanceProcessActionPartialAmountIntegrationTest} is about the line amount.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileGenerator
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaFileSetup
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorId
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorIdRewriter
 * @covers com.etendoerp.go.schemaforge.RemittanceIban
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaOptions
 * @covers com.etendoerp.go.schemaforge.RemittanceFileReadiness
 */
public class RemittanceFileGeneratorIntegrationTest extends WeldBaseTest {

  /** The IBAN of the partners' bank accounts (the lines' debtors); a test may change it. */
  private String partnerIban = PARTNER_IBAN;

  private static final String EURO_ID = "102";
  private static final String SPAIN_ID = "106";
  private static final String COMPANY_IBAN = "ES9121000418450200051332";
  private static final String PARTNER_IBAN = "ES7921000813610123456789";
  /** Valid IBANs and creditor identifiers that differ from the account's own. */
  private static final String OWN_IBAN = "ES6000491500051234567892";
  private static final String OTHER_IBAN = "ES4801825900431234567890";
  private static final String OWN_CREDITOR_ID = "ES97000B12345678";
  private static final String ACCOUNT_CREDITOR_ID = "ES39001B87654321";

  // Payment (SCT): F&B sample client.
  private static final String FB_EUR_ACCOUNT_ID = "DEDDE613C5314ACD8DCC60C474D1A107";
  private static final String FB_PURCHASE_DUE_DETAIL_ID = "99EE208461314B4EAB1DDFC2D014816A";

  // Collection (CORE): GOClient.
  private static final String GO_CLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";
  private static final String GO_ORG_ID = "61849243BE89460EB70866880A545D50";
  private static final String GO_ADMIN_ROLE_ID = "9B8D736190724807AB256DC95F20EC5E";
  private static final String GO_ACCOUNT_ID = "1728F0AC26EF478287FE8F9210052AC8";
  private static final String GO_SALES_DUE_DETAIL_ID = "A2324465AF674B82AD63A43AA0703A25";
  /** FV1000046: same partner and due date as FV1000045. */
  private static final String GO_SALES_DUE_DETAIL_B_ID = "48B9DA0B195244EDA1D75BA5706052E6";

  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    OBContext.setAdminMode(true);
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  // ---------------------------------------------------------------- payment (SCT)

  @Test
  public void aProcessedPaymentRemittanceGivesAValidPain001File() throws Exception {
    Remittance remittance = processedPayment();

    String xml = classicFile(remittance);

    assertTrue(xml, xml.contains("urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"));
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_SCT, "pain.001.001.09"),
        xml));
  }

  @Test
  public void goGeneratesAndValidatesThePain001File() throws Exception {
    Remittance remittance = processedPayment();

    byte[] file = goFile(remittance);

    assertTrue(new String(file, StandardCharsets.UTF_8).contains("pain.001.001.09"));
  }

  // ---------------------------------------------------------------- collection (CORE)

  @Test
  public void aProcessedCollectionRemittanceGivesAValidPain008File() throws Exception {
    Remittance remittance = processedCollection();

    String xml = classicFile(remittance);

    assertTrue(xml, xml.contains("urn:iso:std:iso:20022:tech:xsd:pain.008.001.08"));
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_CORE, "pain.008.001.08"),
        xml));
  }

  @Test
  public void goGeneratesAndValidatesThePain008File() throws Exception {
    Remittance remittance = processedCollection(r -> r.getFinancialAccount().setSwiftCode("CAIXESBBXXX"),
        RemittanceProcessAction.GROUPING_NONE, GO_SALES_DUE_DETAIL_ID);
    Location location = remittance.getOrganization().getOrganizationInformationList().get(0)
        .getLocationAddress();
    location.setOBSEPAStreetName(" Calle  Mayor ");
    OBDal.getInstance().flush();

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    Document document = dom(xml);
    assertEquals(List.of("NOTPROVIDED"), values(document, "CdtrAgt", "FinInstnId", "Othr", "Id"));
    assertEquals(List.of("NOTPROVIDED"), values(document, "DbtrAgt", "FinInstnId", "Othr", "Id"));
    assertEquals("Calle  Mayor",
        document.getElementsByTagNameNS("*", "StrtNm").item(0).getTextContent());
    assertNull(jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_CORE, "pain.008.001.08"), xml));
  }

  // ---------------------------------------------------------------- the remittance's own values

  @Test
  public void aB2bFinancedCollectionCarriesTheRemittanceOwnValuesAndValidatesAgainstTheXsd()
      throws Exception {
    Remittance remittance = processedCollection(r -> {
      differentAccountValues(r);
      r.setCreditorIdentifier(OWN_CREDITOR_ID);
      r.setIBAN(OWN_IBAN);
      r.setDirectDebitScheme(RemittanceSepaOptions.SCHEME_B2B);
      r.setFinanced(true);
    }, RemittanceProcessAction.GROUPING_NONE, GO_SALES_DUE_DETAIL_ID);

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    Document doc = dom(xml);
    assertEquals(xml, List.of(OWN_CREDITOR_ID), values(doc, "InitgPty", "Id", "OrgId", "Othr", "Id"));
    List<String> schemeIds = values(doc, "CdtrSchmeId", "Id", "PrvtId", "Othr", "Id");
    assertFalse(xml, schemeIds.isEmpty());
    for (String id : schemeIds) {
      assertEquals(OWN_CREDITOR_ID, id);
    }
    assertEquals("the creditor account is the remittance's, not the account's",
        List.of(OWN_IBAN), values(doc, "PmtInf", "CdtrAcct", "Id", "IBAN"));
    assertEquals("the line's debtor IBAN is untouched", List.of(PARTNER_IBAN),
        values(doc, "DrctDbtTxInf", "DbtrAcct", "Id", "IBAN"));
    assertEquals(List.of("B2B"), values(doc, "PmtInf", "PmtTpInf", "LclInstrm", "Cd"));
    String msgId = values(doc, "GrpHdr", "MsgId").get(0);
    assertTrue(msgId, msgId.startsWith(RemittanceSepaOptions.financedPrefix(remittance)));
    assertTrue(msgId, msgId.length() <= RemittanceSepaOptions.MSG_ID_MAX_LENGTH);
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_CORE, "pain.008.001.08"),
        xml));
  }

  @Test
  public void everyPaymentBlockOfAB2bCollectionCarriesTheOwnValuesWhateverTheDebtorIban()
      throws Exception {
    partnerIban = COMPANY_IBAN; // a debtor whose IBAN equals the company's account IBAN
    Remittance remittance = processedCollection(r -> {
      differentAccountValues(r);
      r.setCreditorIdentifier(OWN_CREDITOR_ID);
      r.setIBAN(OWN_IBAN);
      r.setDirectDebitScheme(RemittanceSepaOptions.SCHEME_B2B);
      r.setFinanced(true);
    }, RemittanceProcessAction.GROUPING_NONE, GO_SALES_DUE_DETAIL_ID,
        GO_SALES_DUE_DETAIL_B_ID);
    // The generator opens a new PmtInf block when the collection date changes.
    List<Instruction> instructions = new ArrayList<>(remittance.getREMInstructionList());
    assertTrue("two instructions", instructions.size() >= 2);
    instructions.get(instructions.size() - 1).setDueDate(
        day(LocalDate.now().plusDays(11)));
    OBDal.getInstance().save(instructions.get(instructions.size() - 1));
    OBDal.getInstance().flush();

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    Document doc = dom(xml);
    List<String> instruments = values(doc, "PmtInf", "PmtTpInf", "LclInstrm", "Cd");
    assertEquals(xml, 2, instruments.size());
    for (String instrument : instruments) {
      assertEquals("B2B", instrument);
    }
    List<String> creditorAccounts = values(doc, "PmtInf", "CdtrAcct", "Id", "IBAN");
    assertEquals(2, creditorAccounts.size());
    for (String iban : creditorAccounts) {
      assertEquals(OWN_IBAN, iban);
    }
    List<String> debtorAccounts = values(doc, "DrctDbtTxInf", "DbtrAcct", "Id", "IBAN");
    assertFalse(debtorAccounts.isEmpty());
    for (String iban : debtorAccounts) {
      assertEquals("a debtor whose IBAN is the company's is not rewritten", COMPANY_IBAN, iban);
    }
    for (String id : values(doc, "CdtrSchmeId", "Id", "PrvtId", "Othr", "Id")) {
      assertEquals(OWN_CREDITOR_ID, id);
    }
    assertTrue(values(doc, "GrpHdr", "MsgId").get(0)
        .startsWith(RemittanceSepaOptions.financedPrefix(remittance)));
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_CORE, "pain.008.001.08"),
        xml));
  }

  @Test
  public void aGroupedCollectionIsOneBlockWithTheOwnValuesAndValidates() throws Exception {
    Remittance remittance = processedCollection(r -> {
      differentAccountValues(r);
      r.setCreditorIdentifier(OWN_CREDITOR_ID);
      r.setIBAN(OWN_IBAN);
      r.setDirectDebitScheme(RemittanceSepaOptions.SCHEME_B2B);
    }, RemittanceProcessAction.GROUPING_BP_DUE_DATE, GO_SALES_DUE_DETAIL_ID,
        GO_SALES_DUE_DETAIL_B_ID);

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    Document doc = dom(xml);
    assertEquals("GGPD merges both lines into one payment", 1, remittance.getREMInstructionList()
        .size());
    assertEquals(List.of("B2B"), values(doc, "PmtInf", "PmtTpInf", "LclInstrm", "Cd"));
    assertEquals(List.of(OWN_IBAN), values(doc, "PmtInf", "CdtrAcct", "Id", "IBAN"));
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_CORE, "pain.008.001.08"),
        xml));
  }

  @Test
  public void aCoreNonFinancedCollectionOnlyTakesTheRemittanceIdAndIban() throws Exception {
    Remittance remittance = processedCollection(r -> {
      differentAccountValues(r);
      r.setCreditorIdentifier(OWN_CREDITOR_ID);
      r.setIBAN(OWN_IBAN);
    }, RemittanceProcessAction.GROUPING_NONE, GO_SALES_DUE_DETAIL_ID);
    assertEquals("the trigger defaults a collection to CORE", "CORE",
        remittance.getDirectDebitScheme());
    assertFalse(remittance.isFinanced());

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    Document doc = dom(xml);
    assertEquals(List.of("CORE"), values(doc, "PmtInf", "PmtTpInf", "LclInstrm", "Cd"));
    assertEquals(List.of(OWN_CREDITOR_ID),
        values(doc, "InitgPty", "Id", "OrgId", "Othr", "Id"));
    assertEquals(List.of(OWN_IBAN), values(doc, "PmtInf", "CdtrAcct", "Id", "IBAN"));
    String msgId = values(doc, "GrpHdr", "MsgId").get(0);
    assertFalse(msgId, msgId.startsWith(RemittanceSepaOptions.FINANCED_PREFIX));
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_CORE, "pain.008.001.08"),
        xml));
  }

  @Test
  public void aPaymentFileTakesTheRemittanceIbanAndIgnoresTheCollectionOptions() throws Exception {
    Remittance remittance = processedPayment(r -> {
      r.setIBAN(OWN_IBAN);
      r.setDirectDebitScheme(RemittanceSepaOptions.SCHEME_B2B);
      r.setFinanced(true);
      r.setCreditorIdentifier(OWN_CREDITOR_ID);
    });

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    Document doc = dom(xml);
    assertEquals(xml, List.of(OWN_IBAN), values(doc, "PmtInf", "DbtrAcct", "Id", "IBAN"));
    assertEquals("the payee's IBAN is untouched", List.of(PARTNER_IBAN),
        values(doc, "CdtTrfTxInf", "CdtrAcct", "Id", "IBAN"));
    assertTrue(values(doc, "PmtInf", "PmtTpInf", "LclInstrm", "Cd").isEmpty());
    assertFalse(xml, xml.contains("B2B"));
    assertFalse(xml, xml.contains(OWN_CREDITOR_ID));
    assertFalse(values(doc, "GrpHdr", "MsgId").get(0)
        .startsWith(RemittanceSepaOptions.FINANCED_PREFIX));
    assertNull(xml, jdkSchemaFailure(xsdOf(RemittanceSepaFileSetup.CLASS_SCT, "pain.001.001.09"),
        xml));
  }

  // ---------------------------------------------------------------- the copies on the remittance

  @Test
  public void editingTheAccountAfterProcessingDoesNotChangeARegeneratedFile() throws Exception {
    goContext();
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        GO_ACCOUNT_ID);
    account.set(RemittanceCreditorId.PROPERTY, ACCOUNT_CREDITOR_ID);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    Remittance remittance = processedCollection();
    assertEquals("the trigger copied the account's creditor identifier", ACCOUNT_CREDITOR_ID,
        remittance.getCreditorIdentifier());
    assertEquals("the trigger copied the account's IBAN", COMPANY_IBAN, remittance.getIBAN());
    String before = new String(goFile(remittance), StandardCharsets.UTF_8);

    account.set(RemittanceCreditorId.PROPERTY, OWN_CREDITOR_ID);
    account.setIBAN(OTHER_IBAN);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
    String after = new String(goFile(remittance), StandardCharsets.UTF_8);

    assertEquals(List.of(ACCOUNT_CREDITOR_ID),
        values(dom(after), "InitgPty", "Id", "OrgId", "Othr", "Id"));
    assertEquals(List.of(COMPANY_IBAN), values(dom(after), "PmtInf", "CdtrAcct", "Id", "IBAN"));
    assertEquals("the regenerated creditor ids are those of the first file",
        values(dom(before), "CdtrSchmeId", "Id", "PrvtId", "Othr", "Id"),
        values(dom(after), "CdtrSchmeId", "Id", "PrvtId", "Othr", "Id"));
  }

  @Test
  public void withNoCreditorIdAnywhereTheComputedOneIsKeptOnTheRemittanceNeverOnTheAccount()
      throws Exception {
    Remittance remittance = processedCollection();
    FIN_FinancialAccount account = remittance.getFinancialAccount();
    assertNull("neither the remittance nor its account has one", remittance.getCreditorIdentifier());
    assertNull(RemittanceCreditorId.stored(account));
    String computed = RemittanceCreditorId.suggested(remittance.getOrganization());
    assertNotNull("the organization can form one", computed);
    assertTrue(SepaCreditorId.isValid(computed));

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    assertEquals(List.of(computed), values(dom(xml), "InitgPty", "Id", "OrgId", "Othr", "Id"));
    assertEquals("the computed one is the remittance's copy", computed,
        remittance.getCreditorIdentifier());
    OBDal.getInstance().flush();
    assertNull("and never the account's", RemittanceCreditorId.stored(account));
    assertNull(nativeValue("select em_rem_creditor_identifier from fin_financial_account"
        + " where fin_financial_account_id = ?", account.getId()));
  }

  @Test
  public void anOrganizationThatCannotFormACreditorIdIsAReadinessProblemWithNoSuggestion()
      throws Exception {
    Remittance remittance = processedCollection();
    OrganizationInformation info = remittance.getOrganization().getOrganizationInformationList()
        .get(0);
    info.setTaxID("---"); // nothing the creditor identifier can be built from
    OBDal.getInstance().save(info);
    OBDal.getInstance().flush();
    assertNull(remittance.getCreditorIdentifier());

    JSONObject problem = problemOf(remittance,
        RemittanceFileReadiness.MSG_ACCOUNT_NO_CREDITOR_ID);

    assertNotNull("ETGO_RemittanceFileAccountNoCreditorId is reported", problem);
    assertTrue(problem.toString(),
        problem.isNull(RemittanceFileReadiness.KEY_SUGGESTED_CREDITOR_ID));
    assertFalse(RemittanceFileReadiness.evaluate(remittance).isReady());
    try {
      RemittanceFileGenerator.generate(remittance);
      throw new AssertionError("the generation must be refused");
    } catch (RemittanceFileGenerator.Refusal refusal) {
      // Classic's generator itself cannot write an organization without a usable tax ID.
      assertEquals(RemittanceFileGenerator.MSG_GENERATION_FAILED, refusal.getKey());
    }
  }

  @Test
  public void anInvalidOwnCreditorIdStaysAProblemEvenWhenTheAccountHasAValidOne() throws Exception {
    Remittance remittance = processedCollection(r -> {
      r.getFinancialAccount().set(RemittanceCreditorId.PROPERTY, ACCOUNT_CREDITOR_ID);
      r.setCreditorIdentifier("ES00000B12345678"); // wrong check digits
    }, RemittanceProcessAction.GROUPING_NONE, GO_SALES_DUE_DETAIL_ID);
    assertEquals("the trigger respects a provided value", "ES00000B12345678",
        remittance.getCreditorIdentifier());

    JSONObject problem = problemOf(remittance,
        RemittanceFileReadiness.MSG_ACCOUNT_INVALID_CREDITOR_ID);

    assertNotNull(problem);
    assertEquals("the computed one is offered", RemittanceCreditorId.suggested(
        remittance.getOrganization()),
        problem.getString(RemittanceFileReadiness.KEY_SUGGESTED_CREDITOR_ID));
    try {
      RemittanceFileGenerator.generate(remittance);
      throw new AssertionError("an invalid own identifier is never replaced");
    } catch (RemittanceFileGenerator.Refusal refusal) {
      assertEquals(RemittanceFileGenerator.MSG_NO_CREDITOR_ID, refusal.getKey());
    }
  }

  @Test
  public void theIbanStandInLeavesTheAccountWithoutIbanAndNothingIsWritten() throws Exception {
    Remittance remittance = processedCollection(r -> r.setIBAN(OWN_IBAN),
        RemittanceProcessAction.GROUPING_NONE, GO_SALES_DUE_DETAIL_ID);
    FIN_FinancialAccount account = remittance.getFinancialAccount();
    account.setIBAN(null);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();

    String xml = new String(goFile(remittance), StandardCharsets.UTF_8);

    assertEquals("the file carries the remittance's IBAN", List.of(OWN_IBAN),
        values(dom(xml), "PmtInf", "CdtrAcct", "Id", "IBAN"));
    assertNull("the account entity is restored", account.getIBAN());
    OBDal.getInstance().flush();
    assertNull("and nothing about the account reached the database", nativeValue(
        "select iban from fin_financial_account where fin_financial_account_id = ?",
        account.getId()));
  }

  // ---------------------------------------------------------------- fixtures

  private Remittance processedPayment() throws Exception {
    return processedPayment(r -> { });
  }

  private Remittance processedPayment(Consumer<Remittance> customize) throws Exception {
    Client client = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
    RemittanceSepaProvisioning.ensureClient(client);
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        FB_EUR_ACCOUNT_ID);
    giveIban(account);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
    FIN_PaymentScheduleDetail due = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class,
        FB_PURCHASE_DUE_DETAIL_ID);
    assertNotNull("the sample purchase due", due);
    return processed(client, account, RemittanceSepaProvisioning.SCHEME_SCT,
        RemittanceProcessAction.GROUPING_NONE, customize, due);
  }

  private Remittance processedCollection() throws Exception {
    return processedCollection(r -> { }, RemittanceProcessAction.GROUPING_NONE,
        GO_SALES_DUE_DETAIL_ID);
  }

  private Remittance processedCollection(Consumer<Remittance> customize, String grouping,
      String... dueDetailIds) throws Exception {
    goContext();
    Client client = OBDal.getInstance().get(Client.class, GO_CLIENT_ID);
    RemittanceSepaProvisioning.ensureClient(client);
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        GO_ACCOUNT_ID);
    assertNotNull("GOClient's remittance account", account);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
    FIN_PaymentScheduleDetail[] dues = new FIN_PaymentScheduleDetail[dueDetailIds.length];
    for (int i = 0; i < dueDetailIds.length; i++) {
      dues[i] = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class, dueDetailIds[i]);
      assertNotNull("GOClient's sales due " + dueDetailIds[i], dues[i]);
      BusinessPartner partner = dues[i].getInvoicePaymentSchedule().getInvoice()
          .getBusinessPartner();
      if (partner.getObsepadMandateIdentifier() == null) {
        partner.setObsepadMandateIdentifier("MANDATE-IT-1");
      }
      if (partner.getObsepadMandateDate() == null) {
        partner.setObsepadMandateDate(day(LocalDate.now().minusDays(30)));
      }
      OBDal.getInstance().save(partner);
    }
    return processed(client, account, RemittanceSepaProvisioning.SCHEME_CORE, grouping, customize,
        dues);
  }

  private Remittance processed(Client client, FIN_FinancialAccount account, String scheme,
      FIN_PaymentScheduleDetail due) throws Exception {
    return processed(client, account, scheme, RemittanceProcessAction.GROUPING_NONE, r -> { },
        due);
  }

  private static void goContext() {
    OBContext.setOBContext("100", GO_ADMIN_ROLE_ID, GO_CLIENT_ID, GO_ORG_ID);
    OBContext.setAdminMode(true);
  }

  /**
   * A remittance of {@code scheme} with one line for all of each of {@code dues}, processed by REM.
   * {@code customize} runs before it is saved (its own IBAN, creditor identifier, B2B, financed).
   */
  private Remittance processed(Client client, FIN_FinancialAccount account, String scheme,
      String grouping, Consumer<Remittance> customize, FIN_PaymentScheduleDetail... dues)
      throws Exception {
    completeOrganization(account.getOrganization());
    Set<String> bankAccountGiven = new HashSet<>();
    for (FIN_PaymentScheduleDetail due : dues) {
      BusinessPartner partner = due.getInvoicePaymentSchedule().getInvoice().getBusinessPartner();
      if (bankAccountGiven.add(partner.getId())) {
        giveBankAccount(partner);
      }
    }
    RemittanceType type = RemittanceSepaProvisioning.findType(client, scheme);
    assertNotNull("the " + scheme + " type", type);
    // A type created in this session keeps the empty parameter list it was built with; read it
    // back as the request after the provisioning one does.
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(type);
    assertEquals("the type was given its generator", RemittanceSepaFileSetup.generatorOf(scheme),
        type.getJavaClassName());

    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setClient(client);
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(type);
    remittance.setDocumentType(remittanceDocumentType(client));
    remittance.setDocumentNo("SF-SEPA-IT-" + scheme);
    remittance.setName("SF SEPA IT " + scheme);
    remittance.setTransactionDate(day(LocalDate.now()));
    remittance.setDueDate(day(LocalDate.now().plusDays(10)));
    remittance.setProcessed(false);
    customize.accept(remittance);
    OBDal.getInstance().save(remittance);
    long lineNo = 10L;
    for (FIN_PaymentScheduleDetail due : dues) {
      RemittanceLine line = OBProvider.getInstance().get(RemittanceLine.class);
      line.setClient(client);
      line.setOrganization(remittance.getOrganization());
      line.setRemittance(remittance);
      line.setPaymentScheduleDetail(due);
      line.setLineNo(lineNo);
      line.setAmount(due.getAmount());
      remittance.getREMRemittanceLineList().add(line);
      OBDal.getInstance().save(line);
      lineNo += 10L;
    }
    OBDal.getInstance().flush();

    NeoResponse processed = RemittanceProcessAction.process(remittance,
        new JSONObject().put(RemittanceProcessAction.P_GROUPING, grouping));
    assertEquals(String.valueOf(processed.getBody()), 200, processed.getHttpStatus());
    // REM may clear the session (grouped payments): read the remittance again by id.
    remittance = OBDal.getInstance().get(Remittance.class, remittance.getId());
    // The trigger fills the IBAN, the creditor identifier and the scheme the DAL did not set.
    OBDal.getInstance().refresh(remittance);
    assertTrue(Boolean.TRUE.equals(remittance.isProcessed()));
    return remittance;
  }

  /** The organization's own information with a structured address, as both schemes need. */
  private static void completeOrganization(Organization organization) {
    List<OrganizationInformation> infos = organization.getOrganizationInformationList();
    assertTrue("the organization has its information", !infos.isEmpty());
    OrganizationInformation info = infos.get(0);
    Location location = info.getLocationAddress();
    if (location == null) {
      location = OBProvider.getInstance().get(Location.class);
      location.setClient(organization.getClient());
      location.setOrganization(organization);
      info.setLocationAddress(location);
    }
    if (location.getCityName() == null) {
      location.setCityName("Madrid");
    }
    if (location.getCountry() == null) {
      location.setCountry(OBDal.getInstance().get(Country.class, SPAIN_ID));
    }
    location.setOBSEPAStreetName("Calle Mayor");
    location.setOBSEPABuildingNumber("1");
    OBDal.getInstance().save(location);
    OBDal.getInstance().save(info);
    OBDal.getInstance().flush();
  }

  private static void giveIban(FIN_FinancialAccount account) {
    account.setCountry(OBDal.getInstance().get(Country.class, SPAIN_ID));
    account.setIBAN(COMPANY_IBAN);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
  }

  /** A default IBAN bank account for {@code partner}, the one REM writes on the instruction. */
  private void giveBankAccount(BusinessPartner partner) {
    BankAccount bankAccount = OBProvider.getInstance().get(BankAccount.class);
    bankAccount.setClient(partner.getClient());
    bankAccount.setOrganization(partner.getOrganization());
    bankAccount.setBusinessPartner(partner);
    bankAccount.setBankFormat("IBAN");
    bankAccount.setIBAN(partnerIban);
    bankAccount.setCountry(OBDal.getInstance().get(Country.class, SPAIN_ID));
    bankAccount.setETABAMIsdefault(true);
    OBDal.getInstance().save(bankAccount);
    OBDal.getInstance().flush();
  }

  private static DocumentType remittanceDocumentType(Client client) {
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType as d where d.client.id = :client and d.documentCategory = 'REM_REM'"
            + " and d.active = true")
        .setParameter("client", client.getId())
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("the remittance document type", docType);
    return docType;
  }

  /** GO's generation; a refusal fails the test with its key and arguments. */
  private static byte[] goFile(Remittance remittance) {
    try {
      return RemittanceFileGenerator.generate(remittance);
    } catch (RemittanceFileGenerator.Refusal refusal) {
      throw new AssertionError(refusal.getKey() + " " + Arrays.toString(
          refusal.getArgs()), refusal);
    }
  }

  /** The Classic generator the type names, run as Classic's «Create File» runs it. */
  private static String classicFile(Remittance remittance) throws Exception {
    REM_RemittanceCreateFile generator = (REM_RemittanceCreateFile) Class.forName(
        remittance.getRemittanceType().getJavaClassName()).getDeclaredConstructor().newInstance();
    generator.init(remittance);
    StringBuffer xml = generator.generate(remittance);
    assertNull(generator.getLineErrorDetail() + " "
        + (generator.getMyError() != null ? generator.getMyError().getMessage() : ""),
        generator.getMyError());
    assertNotNull(xml);
    return new String(xml.toString().getBytes(StandardCharsets.ISO_8859_1),
        StandardCharsets.UTF_8);
  }

  private static URL xsdOf(String generatorClass, String version) throws Exception {
    URL xsd = Class.forName(generatorClass).getResource(version + ".xsd");
    assertNotNull("the XSD " + version + " next to " + generatorClass, xsd);
    return xsd;
  }

  /** The JDK's own validator: independent of the JAXP implementation on the class path. */
  private static String jdkSchemaFailure(URL xsd, String xml) {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI,
          "com.sun.org.apache.xerces.internal.jaxp.validation.XMLSchemaFactory", null);
      factory.newSchema(xsd).newValidator().validate(new StreamSource(
          new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
      return null;
    } catch (Exception e) {
      return e.getMessage();
    }
  }

  /** The account's own creditor identifier and IBAN, which the remittance's values must beat. */
  private static void differentAccountValues(Remittance remittance) {
    FIN_FinancialAccount account = remittance.getFinancialAccount();
    account.set(RemittanceCreditorId.PROPERTY, ACCOUNT_CREDITOR_ID);
    OBDal.getInstance().save(account);
  }

  /** The problem of {@code code} in the remittance's readiness, or {@code null}. */
  private static JSONObject problemOf(Remittance remittance, String code) throws Exception {
    org.codehaus.jettison.json.JSONArray problems =
        RemittanceFileReadiness.evaluate(remittance).problems.toJson();
    for (int i = 0; i < problems.length(); i++) {
      if (code.equals(problems.getJSONObject(i).getString(RemittanceFileProblems.KEY_CODE))) {
        return problems.getJSONObject(i);
      }
    }
    return null;
  }

  /** The first column of the first row of a native query with one id parameter. */
  private static Object nativeValue(String sql, String id) {
    return OBDal.getInstance().getSession().createNativeQuery(sql).setParameter(1, id)
        .uniqueResult();
  }

  private static Document dom(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  /** The text of every element named {@code path[last]} whose ancestors end with {@code path}. */
  private static List<String> values(Document doc, String... path) {
    List<String> out = new ArrayList<>();
    NodeList all = doc.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Node node = all.item(i);
      if (!path[path.length - 1].equals(node.getLocalName())) {
        continue;
      }
      Node parent = node.getParentNode();
      boolean matches = true;
      for (int k = path.length - 2; k >= 0 && matches; k--) {
        matches = parent != null && path[k].equals(parent.getLocalName());
        parent = parent != null ? parent.getParentNode() : null;
      }
      if (matches) {
        out.add(node.getTextContent().trim());
      }
    }
    return out;
  }

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
