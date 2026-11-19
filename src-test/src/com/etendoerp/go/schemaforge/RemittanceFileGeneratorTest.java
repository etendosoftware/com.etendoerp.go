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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;
import org.openbravo.module.remittance.utility.REM_RemittanceCreateFile;
import org.openbravo.sepa.iso20022.util.SEPA_RemittanceCreateFile;

import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Unit tests for {@link RemittanceFileGenerator}, which runs a remittance type's Classic SEPA
 * generator and stores its output:
 * <ul>
 *   <li>refusals: no class name, a class that does not exist or is not a generator
 *       ({@code ETGO_RemittanceFileNoGenerator}), the generator's own error with its line detail
 *       and a generator that throws ({@code ETGO_RemittanceFileGenerationFailed});</li>
 *   <li>the bytes: the one-byte-per-char buffer Classic marshals into is read back as UTF-8
 *       through ISO-8859-1;</li>
 *   <li>the validation: a SEPA generator whose version has no XSD next to it nor bundled with GO
 *       is refused ({@code ETGO_RemittanceFileSchemaMissing}, {@code -} without a version); a
 *       version GO ships is validated against GO's copy ({@code ETGO_RemittanceFileInvalid}).
 *       {@code RemittanceSepaSchemaTest} specifies the validation itself;</li>
 *   <li>the file name: Classic's {@code <documentNo>_<name>} restricted to safe characters, and
 *       {@code _2}, {@code _3}... while the record already has that name;</li>
 *   <li>the storage: uploaded, made the main attachment keeping the earlier ones; an
 *       {@code IOException} when the record has no tab or the upload cannot be found.</li>
 * </ul>
 *
 * <p>The generators are tiny public nested classes, instantiated by class name exactly as a
 * remittance type names its generator. The DAL and the attachment helper are mocked.
 * {@code RemittanceFileGeneratorIntegrationTest} runs the real Classic generators.
 *
 * <p>A class of its own: {@code RemittanceFileGenerator} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileGenerator
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaRules
 */
class RemittanceFileGeneratorTest {

  /** What the generators below hand back, set per test. */
  static StringBuffer output;
  static OBError error;
  /** ETP-6247: a minimal pain.001 whose company account the file's IBAN replaces. */
  static final String PAYMENT_XML = "<Document><PmtInf><DbtrAcct><Id><IBAN>ES0000000000000000000000"
      + "</IBAN></Id></DbtrAcct></PmtInf></Document>";
  /** A valid IBAN: the remittance's own copy. */
  static final String IBAN = "ES9121000418450200051332";
  static String lineDetail;
  static RuntimeException failure;

  private MockedStatic<NeoMessageTranslator> translator;
  private MockedStatic<RemittanceSepaFileSetup> setup;
  private MockedStatic<RemittanceCreditorId> creditorId;
  private Remittance remittance;
  private RemittanceType type;

  @BeforeEach
  void setUp() {
    output = new StringBuffer(PAYMENT_XML);
    error = null;
    lineDetail = null;
    failure = null;
    translator = mockStatic(NeoMessageTranslator.class);
    translator.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> inv.getArgument(0));
    // ETP-6247: a payment (SCT) file has no creditor identifier to apply; the tests of the
    // collection case say CORE themselves.
    setup = mockStatic(RemittanceSepaFileSetup.class, Mockito.CALLS_REAL_METHODS);
    setup.when(() -> RemittanceSepaFileSetup.schemeOf(any()))
        .thenReturn(RemittanceSepaProvisioning.SCHEME_SCT);
    creditorId = mockStatic(RemittanceCreditorId.class, Mockito.CALLS_REAL_METHODS);
    type = mock(RemittanceType.class);
    when(type.getName()).thenReturn("SEPA Core");
    remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn("REM-1");
    when(remittance.getRemittanceType()).thenReturn(type);
    when(remittance.get(RemittanceIban.PROPERTY)).thenReturn(IBAN);
  }

  @AfterEach
  void tearDown() {
    creditorId.close();
    setup.close();
    translator.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- refusals

  @Test
  void aBlankUnknownOrForeignClassNameHasNoGenerator() {
    for (String className : new String[] { null, "  ", "com.example.NoSuchGenerator",
        "java.lang.String" }) {
      when(type.getJavaClassName()).thenReturn(className);

      RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
          () -> RemittanceFileGenerator.generate(remittance), String.valueOf(className));

      assertEquals(RemittanceFileGenerator.MSG_NO_GENERATOR, refusal.getKey());
      assertArrayEquals(new Object[] { "SEPA Core" }, refusal.getArgs());
    }
    when(remittance.getRemittanceType()).thenReturn(null);
    assertEquals(RemittanceFileGenerator.MSG_NO_GENERATOR, assertThrows(
        RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance)).getKey());
  }

  @Test
  void theGeneratorsErrorIsGenerationFailedWithTheLineDetailFirst() {
    useGenerator(PlainGenerator.class);
    error = new OBError();
    error.setType("Error");
    error.setMessage("@OBSEPA_NoIBAN@");
    lineDetail = "Line 10:";

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_GENERATION_FAILED, refusal.getKey());
    assertArrayEquals(new Object[] { "Line 10: @OBSEPA_NoIBAN@" }, refusal.getArgs());
  }

  @Test
  void anErrorWithoutMessageUsesItsTitleAndNoOutputIsAFailureToo() {
    useGenerator(PlainGenerator.class);
    error = new OBError();
    error.setTitle("Error title");
    assertArrayEquals(new Object[] { "Error title" }, assertThrows(
        RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance)).getArgs());

    error = null;
    output = null;
    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));
    assertEquals(RemittanceFileGenerator.MSG_GENERATION_FAILED, refusal.getKey());
    assertArrayEquals(new Object[] { "-" }, refusal.getArgs());
  }

  @Test
  void aGeneratorThatThrowsIsCaughtAsGenerationFailed() {
    useGenerator(PlainGenerator.class);
    failure = new IllegalStateException("No location for the organization");

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_GENERATION_FAILED, refusal.getKey());
    assertArrayEquals(new Object[] { "No location for the organization" }, refusal.getArgs());

    failure = new NullPointerException();
    assertArrayEquals(new Object[] { "NullPointerException" }, assertThrows(
        RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance)).getArgs());
  }

  // ---------------------------------------------------------------- bytes and validation

  /** Classic marshals one byte per char: the UTF-8 of «Peña» comes back as «Peña». */
  @Test
  void theOneBytePerCharBufferIsReadBackAsUtf8() throws Exception {
    useGenerator(PlainGenerator.class);
    String utf8AsChars = new String(("<Document><Nm>Peña</Nm>" + PAYMENT_XML.substring(10))
        .getBytes(StandardCharsets.UTF_8),
        StandardCharsets.ISO_8859_1);
    output = new StringBuffer(utf8AsChars);

    byte[] bytes = RemittanceFileGenerator.generate(remittance);

    assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("<Nm>Peña</Nm>"));
  }

  // ---------------------------------------------------------------- creditor identifier (ETP-6247)

  private static final String CI = "ES82000B87654323";

  private static final String COLLECTION_XML = "<Document><CstmrDrctDbtInitn>"
      + "<GrpHdr><InitgPty><Id><OrgId><Othr><Id>ES00000B11111111</Id></Othr></OrgId></Id>"
      + "</InitgPty></GrpHdr><PmtInf><CdtrAcct><Id><IBAN>ES0000000000000000000000</IBAN></Id>"
      + "</CdtrAcct><CdtrSchmeId><Id><PrvtId><Othr><Id>ES00000B11111111</Id>"
      + "<SchmeNm><Prtry>SEPA</Prtry></SchmeNm></Othr></PrvtId></Id></CdtrSchmeId>"
      + "<DrctDbtTxInf><DbtrAcct><Id><IBAN>ES1111111111111111111111</IBAN></Id></DbtrAcct>"
      + "<Dbtr><Id><PrvtId><Othr><Id>ES00000B99999999</Id></Othr></PrvtId></Id>"
      + "</Dbtr></DrctDbtTxInf></PmtInf></CstmrDrctDbtInitn></Document>";

  /** A collection's file carries the identifier saved on the account, not the computed one. */
  @Test
  void aCollectionFileCarriesTheCreditorIdSavedOnTheAccount() throws Exception {
    useGenerator(PlainGenerator.class);
    output = new StringBuffer(COLLECTION_XML);
    setup.when(() -> RemittanceSepaFileSetup.schemeOf(any()))
        .thenReturn(RemittanceSepaProvisioning.SCHEME_CORE);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(IBAN);
    when(remittance.getFinancialAccount()).thenReturn(account);
    creditorId.when(() -> RemittanceCreditorId.forFile(remittance)).thenReturn(CI);

    String xml = new String(RemittanceFileGenerator.generate(remittance), StandardCharsets.UTF_8);

    assertTrue(xml.contains("<Id>" + CI + "</Id>"), xml);
    assertFalse(xml.contains("ES00000B11111111"), xml);
    assertTrue(xml.contains("ES00000B99999999"), "the debtor identifier is untouched: " + xml);
    assertTrue(xml.contains("<CdtrAcct><Id><IBAN>" + IBAN + "</IBAN>"), xml);
    assertTrue(xml.contains("ES1111111111111111111111"), "the debtor IBAN is untouched: " + xml);
  }

  /** A collection whose account has no valid identifier is refused, even if readiness was skipped. */
  @Test
  void aCollectionWithoutASavedCreditorIdIsRefused() {
    useGenerator(PlainGenerator.class);
    output = new StringBuffer(COLLECTION_XML);
    setup.when(() -> RemittanceSepaFileSetup.schemeOf(any()))
        .thenReturn(RemittanceSepaProvisioning.SCHEME_CORE);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(IBAN);
    when(account.getName()).thenReturn("BBVA");
    when(remittance.getFinancialAccount()).thenReturn(account);
    creditorId.when(() -> RemittanceCreditorId.forFile(remittance)).thenReturn(null);

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_NO_CREDITOR_ID, refusal.getKey());
    assertArrayEquals(new Object[] { "BBVA" }, refusal.getArgs());
  }

  /** A payment (SCT) file takes no creditor identifier: only the company's IBAN is replaced. */
  @Test
  void aPaymentFileTakesOnlyTheRemittanceIban() throws Exception {
    useGenerator(PlainGenerator.class);
    output = new StringBuffer(COLLECTION_XML.replace("CdtrAcct", "DbtrAcct")
        .replace("<DbtrAcct><Id><IBAN>ES1111", "<CdtrAcct><Id><IBAN>ES1111")
        .replace("</DbtrAcct><Dbtr>", "</CdtrAcct><Dbtr>"));

    String xml = new String(RemittanceFileGenerator.generate(remittance), StandardCharsets.UTF_8);

    assertTrue(xml.contains("<IBAN>" + IBAN + "</IBAN>"), xml);
    assertTrue(xml.contains("ES00000B11111111"), "no creditor id on a payment: " + xml);
    assertTrue(xml.contains("ES1111111111111111111111"), "the line's IBAN is untouched: " + xml);
  }

  /** No valid IBAN on the remittance (nor its account): refused, whatever the scheme. */
  @Test
  void aRemittanceWithoutAValidIbanIsRefused() {
    useGenerator(PlainGenerator.class);
    when(remittance.get(RemittanceIban.PROPERTY)).thenReturn("ES0000");

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_NO_IBAN, refusal.getKey());
  }

  /** A file with no company account to replace is never silently left with the account's IBAN. */
  @Test
  void aFileWithoutTheCompanyAccountIsAGenerationFailure() {
    useGenerator(PlainGenerator.class);
    output = new StringBuffer("<Document><PmtInf/></Document>");

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_GENERATION_FAILED, refusal.getKey());
  }

  /** A collection file with no creditor scheme identification cannot silently keep another id. */
  @Test
  void aCollectionFileWithoutACreditorSchemeIdIsAGenerationFailure() {
    useGenerator(PlainGenerator.class);
    output = new StringBuffer("<Document><PmtInf/></Document>");
    setup.when(() -> RemittanceSepaFileSetup.schemeOf(any()))
        .thenReturn(RemittanceSepaProvisioning.SCHEME_CORE);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(IBAN);
    when(remittance.getFinancialAccount()).thenReturn(account);
    creditorId.when(() -> RemittanceCreditorId.forFile(remittance)).thenReturn(CI);

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_GENERATION_FAILED, refusal.getKey());
  }

  /** A SEPA generator whose schema has no XSD next to it nor bundled with GO: refused. */
  @Test
  void aSchemaWithNoXsdAnywhereIsRefusedNotStoredUnvalidated() throws Exception {
    useGenerator(SepaGeneratorWithoutXsd.class);
    output = new StringBuffer(PAYMENT_XML);

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_SCHEMA_MISSING, refusal.getKey());
    assertArrayEquals(new Object[] { "no.such.schema" }, refusal.getArgs());
  }

  @Test
  void aSepaGeneratorWithoutASchemaVersionIsRefusedWithADash() throws Exception {
    useGenerator(SepaGeneratorWithoutVersion.class);

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_SCHEMA_MISSING, refusal.getKey());
    assertArrayEquals(new Object[] { "-" }, refusal.getArgs());
  }

  /**
   * A generator with no XSD of its own whose version GO ships is validated against GO's copy: an
   * empty pain.008 initiation is refused as invalid, naming the version and the parser's message.
   */
  @Test
  void aVersionGoShipsIsValidatedAgainstTheBundledXsd() throws Exception {
    useGenerator(SepaGeneratorOfABundledVersion.class);
    output = new StringBuffer("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.008.001.08\">"
        + "<CstmrDrctDbtInitn/><PmtInf><DbtrAcct><Id><IBAN>ES00</IBAN></Id></DbtrAcct></PmtInf>"
        + "</Document>");

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_INVALID, refusal.getKey());
    assertEquals("pain.008.001.08", refusal.getArgs()[0]);
    assertTrue(String.valueOf(refusal.getArgs()[1]).contains("GrpHdr"),
        String.valueOf(refusal.getArgs()[1]));
  }

  private static final String BIC = "<BICFI>CAIXESBBXXX</BICFI>";
  private static final String UNKNOWN_AGENT = "<Othr><Id>NOTPROVIDED</Id></Othr>";
  private static final String COLLECTION_NS = "urn:iso:std:iso:20022:tech:xsd:pain.008.001.08";

  /** Two one-debit blocks allow a correct message total to hide an incorrect block total. */
  private static String collectionBlocks() {
    String block = "<PmtInf><PmtInfId>block</PmtInfId><PmtMtd>DD</PmtMtd><NbOfTxs>01</NbOfTxs>"
        + "<CtrlSum>1.00</CtrlSum><PmtTpInf><LclInstrm><Cd>CORE</Cd></LclInstrm></PmtTpInf>"
        + "<ReqdColltnDt>2027-01-19</ReqdColltnDt>"
        + "<Cdtr><Nm>Creditor</Nm></Cdtr><CdtrAcct><Id><IBAN>" + IBAN + "</IBAN></Id></CdtrAcct>"
        + "<CdtrAgt><FinInstnId>" + BIC + "</FinInstnId></CdtrAgt>"
        + "<CdtrSchmeId><Id><PrvtId><Othr><Id>" + CI + "</Id></Othr></PrvtId></Id></CdtrSchmeId>"
        + "<DrctDbtTxInf><PmtId><EndToEndId>debit</EndToEndId></PmtId><InstdAmt Ccy=\"EUR\">1.0"
        + "</InstdAmt><DbtrAgt><FinInstnId/></DbtrAgt><Dbtr><Nm>Debtor</Nm></Dbtr>"
        + "<DbtrAcct><Id><IBAN>" + IBAN + "</IBAN></Id></DbtrAcct></DrctDbtTxInf></PmtInf>";
    return "<Document xmlns=\"" + COLLECTION_NS + "\"><CstmrDrctDbtInitn><GrpHdr><MsgId>message"
        + "</MsgId><CreDtTm>2027-01-15T12:00:00</CreDtTm><NbOfTxs>2</NbOfTxs>"
        + "<CtrlSum>2.00</CtrlSum><InitgPty><Nm>Initiator</Nm></InitgPty></GrpHdr>"
        + block + block.replace(">block<", ">block2<").replace(">debit<", ">debit2<")
        + "</CstmrDrctDbtInitn></Document>";
  }

  private byte[] generateCollection(String xml) throws Exception {
    useGenerator(SepaGeneratorOfABundledVersion.class);
    output = new StringBuffer(new String(xml.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    setup.when(() -> RemittanceSepaFileSetup.schemeOf(any()))
        .thenReturn(RemittanceSepaProvisioning.SCHEME_CORE);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(IBAN);
    when(remittance.getFinancialAccount()).thenReturn(account);
    creditorId.when(() -> RemittanceCreditorId.forFile(remittance)).thenReturn(CI);
    return RemittanceFileGenerator.generate(remittance);
  }

  @Test
  void ibanOnlyCollectionsWithAvailableBicsStillUseNotProvidedInEveryBlock() throws Exception {
    String xml = new String(generateCollection(collectionBlocks()), StandardCharsets.UTF_8);
    assertEquals(4, xml.split(UNKNOWN_AGENT, -1).length - 1);
    assertFalse(xml.contains(BIC), xml);
  }

  @Test
  void aProfileRequiringBicsPreservesEachBicAndRefusesMissingBankData() throws Exception {
    RemittanceTypeParameter parameter = mock(RemittanceTypeParameter.class);
    when(parameter.isActive()).thenReturn(true);
    when(parameter.getName()).thenReturn("BIC_SWIFT_MANDATORY");
    when(parameter.getValue()).thenReturn("Y");
    when(type.getREMRemittanceTypeParamList()).thenReturn(List.of(parameter));
    String blocks = collectionBlocks();

    assertRuleRefusal(blocks, RemittanceSepaRules.MSG_BIC_REQUIRED,
        "/PmtInf[1]/DrctDbtTxInf/DbtrAgt/FinInstnId");
    String debtorBic = "<BICFI>BBVAESMMXXX</BICFI>";
    String populated = blocks.replace("<FinInstnId/>", "<FinInstnId>" + debtorBic + "</FinInstnId>");
    String file = new String(generateCollection(populated), StandardCharsets.UTF_8);
    assertEquals(2, file.split(BIC, -1).length - 1);
    assertEquals(2, file.split(debtorBic, -1).length - 1);
    assertFalse(file.contains(UNKNOWN_AGENT), file);
  }

  @Test
  void aB2bFileKeepsItsOwnAgentPolicy() throws Exception {
    String b2b = collectionBlocks().replace("<Cd>CORE</Cd>", "<Cd>B2B</Cd>");
    String xml = new String(generateCollection(b2b), StandardCharsets.UTF_8);
    assertTrue(xml.contains("<DbtrAgt><FinInstnId/></DbtrAgt>"), xml);
    assertFalse(xml.contains(UNKNOWN_AGENT), xml);
  }

  @Test
  void inconsistentControlFieldsAreRefusedAtTheAffectedLevel() {
    String blocks = collectionBlocks();
    assertRuleRefusal(blocks.replace("<NbOfTxs>2", "<NbOfTxs>3"), RemittanceSepaRules.MSG_COUNT,
        "/GrpHdr/NbOfTxs");
    assertRuleRefusal(blocks.replace("<CtrlSum>2.00", "<CtrlSum>2.01"), RemittanceSepaRules.MSG_TOTAL,
        "/GrpHdr/CtrlSum");
    assertRuleRefusal(blocks.replaceFirst("<CtrlSum>1.00", "<CtrlSum>2.00"), RemittanceSepaRules.MSG_TOTAL,
        "/PmtInf[1]/CtrlSum");
    assertRuleRefusal(blocks.replaceFirst("<NbOfTxs>01", "<NbOfTxs>02"), RemittanceSepaRules.MSG_COUNT,
        "/PmtInf[1]/NbOfTxs");
    assertRuleRefusal(blocks.replaceFirst("<CtrlSum>1.00</CtrlSum>", ""), RemittanceSepaRules.MSG_REQUIRED,
        "/PmtInf[1]/CtrlSum");
  }

  @Test
  void unsupportedAgentIdentificationAndLongPartyNamesIdentifyTheXmlElement() {
    String blocks = collectionBlocks();
    for (String identification : new String[] { BIC + UNKNOWN_AGENT, "<Othr><Id>unknown</Id></Othr>" }) {
      assertRuleRefusal(blocks.replace(BIC, identification), RemittanceSepaRules.MSG_AGENT,
          "/PmtInf[1]/CdtrAgt/FinInstnId");
    }
    assertRuleRefusal(blocks.replace("Debtor", "X".repeat(71)), RemittanceSepaRules.MSG_NAME,
        "/PmtInf[1]/DrctDbtTxInf/Dbtr/Nm");
  }

  private static final String DEBTOR_ID =
      "<Id><OrgId><Othr><Id>ES31ZZZK01927367</Id></Othr></OrgId></Id>";

  /** The debtor identification is optional and Classic invents it: a CORE file drops it, everywhere. */
  @Test
  void aCoreFileDropsEveryDebtorIdentificationAndKeepsTheRest() throws Exception {
    String withIds = collectionBlocks().replace("<Nm>Debtor</Nm>", "<Nm>Debtor</Nm>" + DEBTOR_ID);
    assertEquals(2, withIds.split("ES31ZZZK01927367", -1).length - 1);

    String xml = new String(generateCollection(withIds), StandardCharsets.UTF_8);

    assertFalse(xml.contains("ES31ZZZK01927367"), xml);
    assertEquals(2, xml.split("<Dbtr><Nm>Debtor</Nm></Dbtr>", -1).length - 1, xml);
    assertTrue(xml.contains("<Id>" + CI + "</Id>"), "the creditor id stays: " + xml);
  }

  @Test
  void aB2bFileKeepsItsDebtorIdentification() throws Exception {
    String b2b = collectionBlocks().replace("<Cd>CORE</Cd>", "<Cd>B2B</Cd>")
        .replace("<Nm>Debtor</Nm>", "<Nm>Debtor</Nm>" + DEBTOR_ID);

    assertTrue(new String(generateCollection(b2b), StandardCharsets.UTF_8)
        .contains("ES31ZZZK01927367"));
  }

  /** EPC130-08 2025 v1.0, 1.4: no reference starts or ends with a slash nor holds two. */
  @Test
  void aReferenceBreakingTheSlashRuleIsRefusedAtItsElement() {
    String blocks = collectionBlocks();
    assertRuleRefusal(blocks.replace(">message<", ">/message<"), RemittanceSepaRules.MSG_SLASH,
        "/GrpHdr/MsgId");
    assertRuleRefusal(blocks.replaceFirst(">block<", ">bl//ock<"), RemittanceSepaRules.MSG_SLASH,
        "/PmtInf[1]/PmtInfId");
    assertRuleRefusal(blocks.replaceFirst(">debit<", ">debit/<"), RemittanceSepaRules.MSG_SLASH,
        "/PmtInf[1]/DrctDbtTxInf/PmtId/EndToEndId");
    assertRuleRefusal(blocks.replaceFirst("<DbtrAgt>",
        "<DrctDbtTx><MndtRltdInf><MndtId>A//B</MndtId></MndtRltdInf></DrctDbtTx><DbtrAgt>"),
        RemittanceSepaRules.MSG_SLASH, "/PmtInf[1]/DrctDbtTxInf/DrctDbtTx/MndtRltdInf/MndtId");
  }

  @Test
  void aSingleSlashInsideAReferenceIsAllowed() throws Exception {
    String xml = new String(generateCollection(collectionBlocks().replaceFirst(">debit<",
        ">de/bit<")), StandardCharsets.UTF_8);

    assertTrue(xml.contains("<EndToEndId>de/bit</EndToEndId>"), xml);
  }

  // ---------------------------------------------------------------- payments (pain.001.001.09)

  private static final String PAYMENT_NS = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";
  private static final String EMPTY_AGENT = "<FinInstnId/>";
  private static final String EMPTY_CREDITOR_AGENT = "<CdtrAgt><FinInstnId/></CdtrAgt>";
  private static final String DEBTOR_BIC = "<BICFI>BBVAESMMXXX</BICFI>";

  /** One payment block with {@code transactions} transfers of 1.00, agents as the generator writes. */
  private static String paymentBlock(String id, int transactions, String debtorAgent,
      String creditorAgent) {
    StringBuilder block = new StringBuilder("<PmtInf><PmtInfId>" + id + "</PmtInfId><PmtMtd>TRF"
        + "</PmtMtd><NbOfTxs>" + transactions + "</NbOfTxs><CtrlSum>" + transactions + ".00"
        + "</CtrlSum><PmtTpInf><InstrPrty>NORM</InstrPrty><SvcLvl><Cd>SEPA</Cd></SvcLvl></PmtTpInf>"
        + "<ReqdExctnDt><Dt>2026-12-21</Dt></ReqdExctnDt><Dbtr><Nm>Company</Nm><PstlAdr>"
        + "<StrtNm> Calle Organizacion</StrtNm><Ctry>ES</Ctry></PstlAdr><Id><OrgId><Othr><Id>"
        + "A39200019</Id></Othr></OrgId></Id></Dbtr><DbtrAcct><Id><IBAN>ES0000000000000000000000"
        + "</IBAN></Id></DbtrAcct><DbtrAgt>" + debtorAgent + "</DbtrAgt><ChrgBr>SLEV</ChrgBr>");
    for (int i = 1; i <= transactions; i++) {
      block.append("<CdtTrfTxInf><PmtId><InstrId>").append(id).append("</InstrId><EndToEndId>")
          .append(id).append("-").append(i).append("</EndToEndId></PmtId><Amt><InstdAmt Ccy=\"EUR\">"
              + "1.00</InstdAmt></Amt>").append(creditorAgent).append("<Cdtr><Nm>Payee</Nm></Cdtr>"
              + "<CdtrAcct><Id><IBAN>").append(IBAN).append("</IBAN></Id></CdtrAcct><RmtInf><Ustrd>"
              + "Invoice</Ustrd></RmtInf></CdtTrfTxInf>");
    }
    return block.append("</PmtInf>").toString();
  }

  private static String payment(String... blocks) {
    int transactions = 0;
    for (String block : blocks) {
      transactions += block.split("<CdtTrfTxInf>", -1).length - 1;
    }
    return "<Document xmlns=\"" + PAYMENT_NS + "\"><CstmrCdtTrfInitn><GrpHdr><MsgId>message</MsgId>"
        + "<CreDtTm>2026-11-19T14:20:52</CreDtTm><NbOfTxs>" + transactions + "</NbOfTxs><CtrlSum>"
        + transactions + ".00</CtrlSum><InitgPty><Nm>Company</Nm></InitgPty></GrpHdr>"
        + String.join("", blocks) + "</CstmrCdtTrfInitn></Document>";
  }

  private byte[] generatePayment(String xml) throws Exception {
    useGenerator(SepaGeneratorOfPayments.class);
    output = new StringBuffer(new String(xml.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.ISO_8859_1));
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(IBAN);
    when(remittance.getFinancialAccount()).thenReturn(account);
    return RemittanceFileGenerator.generate(remittance);
  }

  private void requireBics() {
    RemittanceTypeParameter parameter = mock(RemittanceTypeParameter.class);
    when(parameter.isActive()).thenReturn(true);
    when(parameter.getName()).thenReturn("BIC_SWIFT_MANDATORY");
    when(parameter.getValue()).thenReturn("Y");
    when(type.getREMRemittanceTypeParamList()).thenReturn(List.of(parameter));
  }

  /** EPC132-08 2025 v1.0, 2.53-2.61 and 2.114: NOTPROVIDED for the debtor agent, no creditor agent. */
  @Test
  void aPaymentWithoutBicsIdentifiesTheDebtorAgentAndOmitsTheCreditorAgent() throws Exception {
    String xml = new String(generatePayment(payment(
        paymentBlock("b1", 1, EMPTY_AGENT, EMPTY_CREDITOR_AGENT))), StandardCharsets.UTF_8);

    assertTrue(xml.contains("<DbtrAgt><FinInstnId><Othr><Id>NOTPROVIDED</Id></Othr></FinInstnId>"
        + "</DbtrAgt>"), xml);
    assertFalse(xml.contains("CdtrAgt"), xml);
    assertFalse(xml.contains("<FinInstnId/>"), xml);
    assertTrue(xml.contains("<StrtNm>Calle Organizacion</StrtNm>"), "trimmed: " + xml);
  }

  /** Without a BIC requirement the debtor BIC is not sent; a valid creditor BIC is kept. */
  @Test
  void anIbanOnlyPaymentReplacesTheDebtorBicButKeepsTheCreditorBic() throws Exception {
    String xml = new String(generatePayment(payment(paymentBlock("b1", 1,
        "<FinInstnId>" + DEBTOR_BIC + "</FinInstnId>", "<CdtrAgt><FinInstnId>" + BIC
            + "</FinInstnId></CdtrAgt>"))), StandardCharsets.UTF_8);

    assertTrue(xml.contains("<DbtrAgt><FinInstnId><Othr><Id>NOTPROVIDED</Id>"), xml);
    assertFalse(xml.contains(DEBTOR_BIC), xml);
    assertTrue(xml.contains("<CdtrAgt><FinInstnId>" + BIC + "</FinInstnId></CdtrAgt>"), xml);
  }

  @Test
  void aProfileRequiringBicsKeepsThemAndRefusesAMissingOne() throws Exception {
    requireBics();
    String creditorAgent = "<CdtrAgt><FinInstnId>" + BIC + "</FinInstnId></CdtrAgt>";
    String debtorAgent = "<FinInstnId>" + DEBTOR_BIC + "</FinInstnId>";

    String xml = new String(generatePayment(payment(
        paymentBlock("b1", 1, debtorAgent, creditorAgent))), StandardCharsets.UTF_8);
    assertTrue(xml.contains(DEBTOR_BIC) && xml.contains(BIC), xml);
    assertFalse(xml.contains("NOTPROVIDED"), xml);

    assertPaymentRefusal(payment(paymentBlock("b1", 1, EMPTY_AGENT, creditorAgent)),
        RemittanceSepaRules.MSG_BIC_REQUIRED, "/PmtInf/DbtrAgt/FinInstnId");
    assertPaymentRefusal(payment(paymentBlock("b1", 1, debtorAgent, EMPTY_CREDITOR_AGENT)),
        RemittanceSepaRules.MSG_BIC_REQUIRED, "/PmtInf/CdtTrfTxInf/CdtrAgt");
  }

  /** Several blocks and transfers: each block is tidied, counted and totalled on its own. */
  @Test
  void aPaymentWithSeveralBlocksAndTransfersIsTidiedEverywhereAndKeepsItsTotals() throws Exception {
    String xml = new String(generatePayment(payment(
        paymentBlock("b1", 2, EMPTY_AGENT, EMPTY_CREDITOR_AGENT),
        paymentBlock("b2", 1, EMPTY_AGENT, "<CdtrAgt><FinInstnId>" + BIC
            + "</FinInstnId></CdtrAgt>"))), StandardCharsets.UTF_8);

    assertEquals(2, xml.split("<DbtrAgt><FinInstnId><Othr><Id>NOTPROVIDED</Id>", -1).length - 1, xml);
    assertEquals(1, xml.split("<CdtrAgt>", -1).length - 1, xml);
    assertEquals(3, xml.split("<CdtTrfTxInf>", -1).length - 1, xml);
    assertFalse(xml.contains("<FinInstnId/>"), xml);
    assertFalse(xml.contains("> Calle"), xml);
  }

  @Test
  void aPaymentRefusesWhatTheCreditorAgentOrTheControlsGetWrong() {
    String block = paymentBlock("b1", 1, EMPTY_AGENT, EMPTY_CREDITOR_AGENT);
    assertPaymentRefusal(payment(block).replace("<CdtTrfTxInf><PmtId>",
        "<CdtTrfTxInf><PmtId>").replace("</Amt>", "</Amt><CdtrAgt><FinInstnId>" + UNKNOWN_AGENT
            + "</FinInstnId></CdtrAgt>"),
        RemittanceSepaRules.MSG_CREDITOR_AGENT, "/PmtInf/CdtTrfTxInf/CdtrAgt");
    assertPaymentRefusal(payment(block).replaceFirst("<NbOfTxs>1</NbOfTxs><CtrlSum>1.00",
        "<NbOfTxs>1</NbOfTxs><CtrlSum>2.00"), RemittanceSepaRules.MSG_TOTAL, "/GrpHdr/CtrlSum");
    assertPaymentRefusal(payment(block).replace("<PmtMtd>TRF</PmtMtd><NbOfTxs>1",
        "<PmtMtd>TRF</PmtMtd><NbOfTxs>2"), RemittanceSepaRules.MSG_COUNT, "/PmtInf/NbOfTxs");
    assertPaymentRefusal(payment(block).replace("<Nm>Payee</Nm>", "<Nm>" + "X".repeat(71) + "</Nm>"),
        RemittanceSepaRules.MSG_NAME, "/PmtInf/CdtTrfTxInf/Cdtr/Nm");
  }

  /** EPC132-08 2025 v1.0, 1.4 applies to a payment's references and identifiers too. */
  @Test
  void theSlashRuleAlsoAppliesToAPayment() {
    String block = paymentBlock("b1", 1, EMPTY_AGENT, EMPTY_CREDITOR_AGENT);
    assertPaymentRefusal(payment(block).replace(">message<", ">/message<"),
        RemittanceSepaRules.MSG_SLASH, "/GrpHdr/MsgId");
    assertPaymentRefusal(payment(block).replace("b1-1", "b1//1"), RemittanceSepaRules.MSG_SLASH,
        "/PmtInf/CdtTrfTxInf/PmtId/EndToEndId");
    assertPaymentRefusal(payment(block).replace(">A39200019<", ">A392/<"),
        RemittanceSepaRules.MSG_SLASH, "/PmtInf/Dbtr/Id/OrgId/Othr/Id");
  }

  private void assertPaymentRefusal(String xml, String key, String location) {
    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> generatePayment(xml));
    assertEquals(key, refusal.getKey());
    assertEquals("/Document/CstmrCdtTrfInitn" + location, refusal.getArgs()[0]);
  }

  private void assertRuleRefusal(String xml, String key, String location) {
    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> generateCollection(xml));
    assertEquals(key, refusal.getKey());
    assertEquals("/Document/CstmrDrctDbtInitn" + location, refusal.getArgs()[0]);
  }

  // ---------------------------------------------------------------- file name

  @Test
  void theBaseNameKeepsOnlySafeCharacters() {
    when(remittance.getDocumentNo()).thenReturn("REM/001");
    when(remittance.getName()).thenReturn("Cobros ñandú: enero  2026.");
    assertEquals("REM_001_Cobros_nandu_enero_2026", RemittanceFileGenerator.baseName(remittance));

    when(remittance.getDocumentNo()).thenReturn(null);
    when(remittance.getName()).thenReturn(null);
    assertEquals("remittance", RemittanceFileGenerator.baseName(remittance));

    when(remittance.getDocumentNo()).thenReturn("R");
    when(remittance.getName()).thenReturn("x".repeat(300));
    assertEquals(120, RemittanceFileGenerator.baseName(remittance).length());
  }

  @Test
  void aTakenNameGetsTheNextFreeSuffix() {
    when(remittance.getDocumentNo()).thenReturn("REM-001");
    when(remittance.getName()).thenReturn("Cobros");
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      Deque<Long> taken = new ArrayDeque<>(java.util.List.of(1L, 1L, 0L));
      OBDal dal = dalCounting(taken);
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      assertEquals("REM-001_Cobros_3.xml", RemittanceFileGenerator.freeFileName(remittance));

      taken.add(0L);
      assertEquals("REM-001_Cobros.xml", RemittanceFileGenerator.freeFileName(remittance));
    }
  }

  // ---------------------------------------------------------------- storage

  @Test
  void storeUploadsAndMakesItTheMainAttachmentKeepingTheEarlierOnes() throws Exception {
    givenStorableRemittance();
    Attachment stored = mock(Attachment.class);
    when(stored.getId()).thenReturn("ATT-9");
    AttachImplementationManager manager = mock(AttachImplementationManager.class);
    try (MockedStatic<NeoAttachmentsHelper> helper = mockStatic(NeoAttachmentsHelper.class);
        MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      OBDal dal = dalCounting(new ArrayDeque<>());
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      helper.when(() -> NeoAttachmentsHelper.resolveTabId(anyString(), any())).thenReturn("TAB");
      helper.when(NeoAttachmentsHelper::getAttachManager).thenReturn(manager);
      helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(
          RemittanceSepaProvisioning.REMITTANCE_TABLE_ID, "REM-1", "REM-001_Cobros.xml"))
          .thenReturn(stored);

      RemittanceFileGenerator.Stored result = RemittanceFileGenerator.store(remittance,
          "<xml/>".getBytes(StandardCharsets.UTF_8));

      assertEquals("ATT-9", result.attachmentId);
      assertEquals("REM-001_Cobros.xml", result.fileName);
      verify(manager).upload(any(), eq("TAB"), eq("REM-1"), eq("ORG-1"), any(File.class));
      helper.verify(() -> NeoAttachmentsHelper.markAsMain(stored, true));
      helper.verify(() -> NeoAttachmentsHelper.markAsMain(any(), eq(false)), never());
    }
  }

  @Test
  void storeFailsWhenTheUploadCannotBeFoundOrTheRecordHasNoTab() throws Exception {
    givenStorableRemittance();
    try (MockedStatic<NeoAttachmentsHelper> helper = mockStatic(NeoAttachmentsHelper.class);
        MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      OBDal dal = dalCounting(new ArrayDeque<>());
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      helper.when(() -> NeoAttachmentsHelper.resolveTabId(anyString(), any())).thenReturn("TAB");
      AttachImplementationManager manager = mock(AttachImplementationManager.class);
      helper.when(NeoAttachmentsHelper::getAttachManager).thenReturn(manager);
      helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(anyString(),
          anyString(), anyString())).thenReturn(null);

      assertThrows(IOException.class, () -> RemittanceFileGenerator.store(remittance,
          new byte[] { 1 }));
      helper.verify(() -> NeoAttachmentsHelper.markAsMain(any(), anyBoolean()), never());

      helper.when(() -> NeoAttachmentsHelper.resolveTabId(anyString(), any())).thenReturn(null);
      assertThrows(IOException.class, () -> RemittanceFileGenerator.store(remittance,
          new byte[] { 1 }));
    }
  }

  // ---------------------------------------------------------------- generators

  /** A plain (non-SEPA) generator answering the fields of the test. */
  public static class PlainGenerator extends REM_RemittanceCreateFile {
    @Override
    public StringBuffer generate(Remittance remittance) {
      if (failure != null) {
        throw failure;
      }
      if (error != null) {
        setMyError(error);
      }
      if (lineDetail != null) {
        setLineErrorDetail(lineDetail);
      }
      return output;
    }
  }

  /** A SEPA generator whose schema version has no XSD next to it. */
  public static class SepaGeneratorWithoutXsd extends SEPA_RemittanceCreateFile {
    public SepaGeneratorWithoutXsd() {
      schema_version = "no.such.schema";
    }

    @Override
    public StringBuffer generate(Remittance remittance) {
      return output;
    }
  }

  /** A SEPA generator with no schema version. */
  public static class SepaGeneratorWithoutVersion extends SEPA_RemittanceCreateFile {
    public SepaGeneratorWithoutVersion() {
      schema_version = null;
    }

    @Override
    public StringBuffer generate(Remittance remittance) {
      return output;
    }
  }

  /** A SEPA generator of pain.008.001.08 with no XSD next to it: GO's bundled copy applies. */
  public static class SepaGeneratorOfABundledVersion extends SEPA_RemittanceCreateFile {
    public SepaGeneratorOfABundledVersion() {
      schema_version = "pain.008.001.08";
    }

    @Override
    public StringBuffer generate(Remittance remittance) {
      return output;
    }
  }

  /** A SEPA generator of pain.001.001.09 (SCT), GO's bundled XSD applies. */
  public static class SepaGeneratorOfPayments extends SEPA_RemittanceCreateFile {
    public SepaGeneratorOfPayments() {
      schema_version = "pain.001.001.09";
    }

    @Override
    public StringBuffer generate(Remittance remittance) {
      return output;
    }
  }

  // ---------------------------------------------------------------- helpers

  private void useGenerator(Class<?> generator) {
    when(type.getJavaClassName()).thenReturn(generator.getName());
  }

  private void givenStorableRemittance() {
    when(remittance.getDocumentNo()).thenReturn("REM-001");
    when(remittance.getName()).thenReturn("Cobros");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    when(remittance.getOrganization()).thenReturn(org);
  }

  /** A DAL whose attachment-name count answers {@code taken} in turn, then 0. */
  @SuppressWarnings("unchecked")
  private static OBDal dalCounting(Deque<Long> taken) {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    Query<Long> query = mock(Query.class, RETURNS_SELF);
    when(query.uniqueResult()).thenAnswer(inv -> taken.isEmpty() ? 0L : taken.poll());
    when(session.createQuery(anyString(), eq(Long.class))).thenReturn(query);
    return dal;
  }
}
