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
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
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
 */
class RemittanceFileGeneratorTest {

  /** What the generators below hand back, set per test. */
  static StringBuffer output;
  static OBError error;
  static String lineDetail;
  static RuntimeException failure;

  private MockedStatic<NeoMessageTranslator> translator;
  private Remittance remittance;
  private RemittanceType type;

  @BeforeEach
  void setUp() {
    output = new StringBuffer("<x/>");
    error = null;
    lineDetail = null;
    failure = null;
    translator = mockStatic(NeoMessageTranslator.class);
    translator.when(() -> NeoMessageTranslator.safeParseTranslation(any()))
        .thenAnswer(inv -> inv.getArgument(0));
    type = mock(RemittanceType.class);
    when(type.getName()).thenReturn("SEPA Core");
    remittance = mock(Remittance.class);
    when(remittance.getId()).thenReturn("REM-1");
    when(remittance.getRemittanceType()).thenReturn(type);
  }

  @AfterEach
  void tearDown() {
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
    String utf8AsChars = new String("<Nm>Peña</Nm>".getBytes(StandardCharsets.UTF_8),
        StandardCharsets.ISO_8859_1);
    output = new StringBuffer(utf8AsChars);

    byte[] bytes = RemittanceFileGenerator.generate(remittance);

    assertEquals("<Nm>Peña</Nm>", new String(bytes, StandardCharsets.UTF_8));
  }

  /** A SEPA generator whose schema has no XSD next to it nor bundled with GO: refused. */
  @Test
  void aSchemaWithNoXsdAnywhereIsRefusedNotStoredUnvalidated() throws Exception {
    useGenerator(SepaGeneratorWithoutXsd.class);
    output = new StringBuffer("not even xml");

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
        + "<CstmrDrctDbtInitn/></Document>");

    RemittanceFileGenerator.Refusal refusal = assertThrows(RemittanceFileGenerator.Refusal.class,
        () -> RemittanceFileGenerator.generate(remittance));

    assertEquals(RemittanceFileGenerator.MSG_INVALID, refusal.getKey());
    assertEquals("pain.008.001.08", refusal.getArgs()[0]);
    assertTrue(String.valueOf(refusal.getArgs()[1]).contains("GrpHdr"),
        String.valueOf(refusal.getArgs()[1]));
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
