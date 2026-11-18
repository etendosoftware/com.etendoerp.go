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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.utility.REM_RemittanceCreateFile;

/**
 * Real-database acceptance of the SEPA files (ETP-6247): a minimal remittance of each scheme is
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
 */
public class RemittanceFileGeneratorIntegrationTest extends WeldBaseTest {

  private static final String EURO_ID = "102";
  private static final String SPAIN_ID = "106";
  private static final String COMPANY_IBAN = "ES9121000418450200051332";
  private static final String PARTNER_IBAN = "ES7921000813610123456789";

  // Payment (SCT): F&B sample client.
  private static final String FB_EUR_ACCOUNT_ID = "DEDDE613C5314ACD8DCC60C474D1A107";
  private static final String FB_PURCHASE_DUE_DETAIL_ID = "99EE208461314B4EAB1DDFC2D014816A";

  // Collection (CORE): GOClient.
  private static final String GO_CLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";
  private static final String GO_ORG_ID = "61849243BE89460EB70866880A545D50";
  private static final String GO_ADMIN_ROLE_ID = "9B8D736190724807AB256DC95F20EC5E";
  private static final String GO_ACCOUNT_ID = "1728F0AC26EF478287FE8F9210052AC8";
  private static final String GO_SALES_DUE_DETAIL_ID = "A2324465AF674B82AD63A43AA0703A25";

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
    Remittance remittance = processedCollection();

    byte[] file = goFile(remittance);

    assertTrue(new String(file, StandardCharsets.UTF_8).contains("pain.008.001.08"));
  }

  // ---------------------------------------------------------------- fixtures

  private Remittance processedPayment() throws Exception {
    Client client = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
    RemittanceSepaProvisioning.ensureClient(client);
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        FB_EUR_ACCOUNT_ID);
    giveIban(account);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
    FIN_PaymentScheduleDetail due = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class,
        FB_PURCHASE_DUE_DETAIL_ID);
    assertNotNull("the sample purchase due", due);
    return processed(client, account, RemittanceSepaProvisioning.SCHEME_SCT, due);
  }

  private Remittance processedCollection() throws Exception {
    OBContext.setOBContext("100", GO_ADMIN_ROLE_ID, GO_CLIENT_ID, GO_ORG_ID);
    OBContext.setAdminMode(true);
    Client client = OBDal.getInstance().get(Client.class, GO_CLIENT_ID);
    RemittanceSepaProvisioning.ensureClient(client);
    FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
        GO_ACCOUNT_ID);
    assertNotNull("GOClient's remittance account", account);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
    FIN_PaymentScheduleDetail due = OBDal.getInstance().get(FIN_PaymentScheduleDetail.class,
        GO_SALES_DUE_DETAIL_ID);
    assertNotNull("GOClient's sales due", due);
    BusinessPartner partner = due.getInvoicePaymentSchedule().getInvoice().getBusinessPartner();
    if (partner.getObsepadMandateIdentifier() == null) {
      partner.setObsepadMandateIdentifier("MANDATE-IT-1");
    }
    if (partner.getObsepadMandateDate() == null) {
      partner.setObsepadMandateDate(day(LocalDate.now().minusDays(30)));
    }
    OBDal.getInstance().save(partner);
    return processed(client, account, RemittanceSepaProvisioning.SCHEME_CORE, due);
  }

  /** A remittance of {@code scheme} with one line for all of {@code due}, processed by REM. */
  private Remittance processed(Client client, FIN_FinancialAccount account, String scheme,
      FIN_PaymentScheduleDetail due) throws Exception {
    completeOrganization(account.getOrganization());
    BusinessPartner partner = due.getInvoicePaymentSchedule().getInvoice().getBusinessPartner();
    giveBankAccount(partner);
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
    OBDal.getInstance().save(remittance);
    RemittanceLine line = OBProvider.getInstance().get(RemittanceLine.class);
    line.setClient(client);
    line.setOrganization(remittance.getOrganization());
    line.setRemittance(remittance);
    line.setPaymentScheduleDetail(due);
    line.setLineNo(10L);
    line.setAmount(due.getAmount());
    remittance.getREMRemittanceLineList().add(line);
    OBDal.getInstance().save(line);
    OBDal.getInstance().flush();

    NeoResponse processed = RemittanceProcessAction.process(remittance,
        new JSONObject().put(RemittanceProcessAction.P_GROUPING,
            RemittanceProcessAction.GROUPING_NONE));
    assertEquals(String.valueOf(processed.getBody()), 200, processed.getHttpStatus());
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
  private static void giveBankAccount(BusinessPartner partner) {
    BankAccount bankAccount = OBProvider.getInstance().get(BankAccount.class);
    bankAccount.setClient(partner.getClient());
    bankAccount.setOrganization(partner.getOrganization());
    bankAccount.setBusinessPartner(partner);
    bankAccount.setBankFormat("IBAN");
    bankAccount.setIBAN(PARTNER_IBAN);
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

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }
}
