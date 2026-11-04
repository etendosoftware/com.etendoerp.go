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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.servlet.http.Part;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Per-file ingestion of the invoice inbox: which files are refused before the DAL is touched, how
 * the file name is cleaned, the row that is created, how every outcome of the attachment store is
 * mapped (and when an orphan-bytes warning is logged), and that the temporary file is always
 * removed and one file's failure never reaches the next one. The DAL, the entity provider and the
 * attachment store are mocked; the real database round trip is in
 * {@code InvoiceInboxIntakeIntegrationTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxIntake
 */
class InvoiceInboxIntakeTest {

  private static final String CLIENT_ID = "CLIENT-ID-0000000000000000000001";
  private static final String ORG_ID = "ORG-ID-000000000000000000000001";
  private static final String ORPHAN_WARNING = "failed after the upload may have reached";

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBProvider> providerStatic;
  private MockedStatic<NeoAttachmentStore> store;
  private MockedStatic<OBMessageUtils> messages;
  private OBDal dal;
  private OBProvider provider;
  private Client client;
  private Organization organization;
  private Logger intakeLogger;
  private Level previousLevel;
  private AbstractAppender capture;
  private final List<String> warnings = new ArrayList<>();
  private final List<NeoAttachmentUpload> uploads = new ArrayList<>();
  private final List<File> materialized = new ArrayList<>();

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    provider = mock(OBProvider.class);
    client = mock(Client.class);
    organization = mock(Organization.class);
    when(dal.get(Client.class, CLIENT_ID)).thenReturn(client);
    when(dal.get(Organization.class, ORG_ID)).thenReturn(organization);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    providerStatic = mockStatic(OBProvider.class);
    providerStatic.when(OBProvider::getInstance).thenReturn(provider);
    store = mockStatic(NeoAttachmentStore.class);
    // Unstubbed messageBD answers null: every text falls back to its English default.
    messages = mockStatic(OBMessageUtils.class);

    intakeLogger = (Logger) LogManager.getLogger(InvoiceInboxIntake.class);
    capture = new AbstractAppender("capture-invoice-inbox-intake", null, null, true,
        Property.EMPTY_ARRAY) {
      @Override
      public void append(LogEvent event) {
        if (event.getLevel() == Level.WARN) {
          warnings.add(event.getMessage().getFormattedMessage());
        }
      }
    };
    capture.start();
    intakeLogger.addAppender(capture);
    // A test logging configuration may be quieter than WARN: the warnings are part of the contract.
    previousLevel = intakeLogger.getLevel();
    intakeLogger.setLevel(Level.WARN);
  }

  @AfterEach
  void tearDown() {
    intakeLogger.setLevel(previousLevel);
    intakeLogger.removeAppender(capture);
    capture.stop();
    messages.close();
    store.close();
    providerStatic.close();
    dalStatic.close();
    for (File file : materialized) {
      file.delete();
      file.getParentFile().delete();
    }
  }

  // ---------------------------------------------------------------- refused before the DAL

  @ParameterizedTest
  @ValueSource(strings = { "photo.webp", "setup.exe", "no-extension", "ends-with-dot.", "doc.pdf.exe" })
  void anUnsupportedExtensionIsRefusedWithoutTouchingTheDalOrTheStore(String name) {
    Part part = part(1024);

    InvoiceInboxIntake.Result result = InvoiceInboxIntake.ingest(part, name, CLIENT_ID, ORG_ID);

    assertFalse(result.isAccepted());
    assertEquals("unsupported_type", result.getCode());
    assertTrue(result.getMessage().contains(name), result.getMessage());
    dalStatic.verifyNoInteractions();
    providerStatic.verifyNoInteractions();
    store.verifyNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(strings = { "invoice.pdf", "scan.JPG", "scan.jpeg", "scan.PNG" })
  void theSupportedExtensionsAreNotRefusedAsUnsupported(String name) {
    Part part = part(10);
    IncomingDocument row = rowWithId("ROW-1");
    providerReturns(row);
    storeAnswers(upload -> created());

    InvoiceInboxIntake.Result result = InvoiceInboxIntake.ingest(part, name, CLIENT_ID, ORG_ID);

    assertTrue(result.isAccepted(), String.valueOf(result.getCode()));
  }

  @ParameterizedTest
  @ValueSource(longs = { 0L, -1L })
  void aFileWithNoBytesIsRefusedAsEmptyWithoutTouchingTheDal(long size) {
    Part part = part(size);

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part, "invoice.pdf", CLIENT_ID, ORG_ID);

    assertFalse(result.isAccepted());
    assertEquals("empty_file", result.getCode());
    assertTrue(result.getMessage().contains("invoice.pdf"), result.getMessage());
    dalStatic.verifyNoInteractions();
    store.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- cleanFileName

  @Test
  void cleanFileNameRemovesControlCharactersCrLfAndLineSeparators() {
    assertEquals("invoice.pdf", InvoiceInboxIntake.cleanFileName("inv\r\noi\u0000ce\u0007.pdf"));
    assertEquals("invoice.pdf", InvoiceInboxIntake.cleanFileName("inv oi ce.pdf"));
    assertEquals("invoice.pdf", InvoiceInboxIntake.cleanFileName("  invoice.pdf \t"));
  }

  @Test
  void cleanFileNameFallsBackToAttachmentForEmptyAndDotNames() {
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName(null));
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName(""));
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName("   "));
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName("."));
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName(".."));
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName("\r\n\u0000"));
  }

  @Test
  void cleanFileNameKeepsOnlyTheLastPathSegment() {
    assertEquals("passwd.pdf", InvoiceInboxIntake.cleanFileName("../../etc/passwd.pdf"));
    assertEquals("evil.png", InvoiceInboxIntake.cleanFileName("..\\..\\evil.png"));
    assertEquals("a.pdf", InvoiceInboxIntake.cleanFileName("/var/tmp/a.pdf"));
    assertEquals("attachment", InvoiceInboxIntake.cleanFileName("some/dir/.."));
  }

  @Test
  void cleanFileNameKeepsANameOfExactlyTheMaximumBytes() {
    String name = "a".repeat(236) + ".pdf";

    assertEquals(240, utf8Length(name));
    assertEquals(name, InvoiceInboxIntake.cleanFileName(name));
  }

  @Test
  void cleanFileNameTruncatesALongNameKeepingItsExtension() {
    String name = "a".repeat(300) + ".pdf";

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertEquals(240, utf8Length(clean));
    assertTrue(clean.endsWith(".pdf"), clean);
    assertEquals("a".repeat(236) + ".pdf", clean);
  }

  @Test
  void cleanFileNameDropsAnExtensionLongerThanSixteenCharactersWhenTruncating() {
    String name = "a".repeat(300) + "." + "b".repeat(20);

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertEquals(240, utf8Length(clean));
    assertEquals(name.substring(0, 240), clean);
  }

  @Test
  void cleanFileNameTreatsASixteenCharacterExtensionAsAnExtension() {
    String extension = "." + "b".repeat(15);
    String name = "a".repeat(300) + extension;

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertEquals(240, utf8Length(clean));
    assertTrue(clean.endsWith(extension), clean);
  }

  @Test
  void cleanFileNameTruncatesFourByteCharactersWithoutSplittingASurrogatePair() {
    String emoji = new String(Character.toChars(0x1F600));
    String name = emoji.repeat(200) + ".pdf";

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertEquals(emoji.repeat(59) + ".pdf", clean);
    assertEquals(240, utf8Length(clean));
    assertNoLoneSurrogate(clean);
  }

  @Test
  void cleanFileNameTruncatesTwoByteCharactersAtTheByteCap() {
    String name = "\u00e9".repeat(150) + ".pdf";

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertEquals("\u00e9".repeat(118) + ".pdf", clean);
    assertEquals(240, utf8Length(clean));
  }

  @Test
  void cleanFileNameStopsBeforeACharacterThatDoesNotFitTheRemainingBytes() {
    // 235 ASCII bytes leave 1 byte of the 236-byte base budget: the 2-byte e-acute does not fit.
    String name = "a".repeat(235) + "\u00e9".repeat(5) + ".pdf";

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertEquals("a".repeat(235) + ".pdf", clean);
    assertEquals(239, utf8Length(clean));
  }

  @Test
  void cleanFileNameKeepsThreeByteCjkCharactersWithinTheCapAndKeepsTheExtension() {
    String name = "\u6f22".repeat(100) + ".pdf";

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertTrue(utf8Length(clean) <= 240, "bytes: " + utf8Length(clean));
    assertTrue(clean.endsWith(".pdf"), clean);
    assertEquals("\u6f22".repeat(78) + ".pdf", clean);
    assertNoLoneSurrogate(clean);
  }

  @Test
  void cleanFileNameCapsBytesNotCharacters() {
    // 134 characters, far fewer than 240, but 264 bytes: it must still be truncated.
    String name = "\u00e9".repeat(130) + ".pdf";

    String clean = InvoiceInboxIntake.cleanFileName(name);

    assertTrue(name.length() < 240);
    assertTrue(utf8Length(name) > 240);
    assertEquals(240, utf8Length(clean));
    assertEquals("\u00e9".repeat(118) + ".pdf", clean);
  }

  // ---------------------------------------------------------------- the created row

  @Test
  void anAcceptedFileCreatesAProcessingRowAndStoresItAsTheMainAttachment() {
    IncomingDocument row = rowWithId("ROW-1");
    providerReturns(row);
    storeAnswers(upload -> created());
    Part part = part(2048);

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part, "dir/Invoice 7.pdf", CLIENT_ID, ORG_ID);

    assertTrue(result.isAccepted());
    assertEquals("ROW-1", result.getIncomingDocumentId());
    assertEquals("Invoice 7.pdf", result.getFileName());
    assertNull(result.getCode());

    verify(row).setClient(client);
    verify(row).setOrganization(organization);
    verify(row).setActive(true);
    verify(row).setFileName("Invoice 7.pdf");
    verify(row).setSource("UPLOAD");
    verify(row).setTargetType("PURCHASE_INVOICE");
    verify(row).setStatus("PROCESSING");
    verify(row).setAdjusted(false);
    verify(row).setReviewed(false);
    // ETP-6048: a new row is waiting for the worker, never claimed (no lease) and not processed yet.
    verify(row).setProcessingAttempts(0L);
    verify(row, never()).setProcessingLease(any());
    verify(row, never()).setProcessedAt(any());
    verify(row, never()).setErrorCode(any());
    verify(row, never()).setCreatedBy(any());
    verify(row, never()).setUpdatedBy(any());
    verify(dal).save(row);
    verify(dal).flush();

    assertEquals(1, uploads.size());
    NeoAttachmentUpload upload = uploads.get(0);
    assertEquals(IncomingDocument.TABLE_NAME, upload.getTableName());
    assertEquals("ROW-1", upload.getRecordId());
    assertNull(upload.getTabIdHint());
    assertEquals("Invoice 7.pdf", upload.getFileName());
    assertEquals(2048L, upload.getDeclaredSize());
    assertTrue(upload.isMarkAsMain());

    verify(dal).commitAndClose();
    verify(dal, never()).rollbackAndClose();
    assertTrue(warnings.isEmpty(), warnings.toString());
    assertTempFileRemoved();
  }

  // ---------------------------------------------------------------- the attachment store answers

  @ParameterizedTest
  @ValueSource(ints = { 400, 422 })
  void aContentRefusalOfTheStoreIsRejectedAsInvalidContentWithItsMessageAndNoOrphanWarning(
      int status) {
    IncomingDocument row = rowWithId("ROW-1");
    providerReturns(row);
    storeAnswers(upload -> NeoResponse.error(status, "The file content is not a PDF"));

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part(10), "fake.pdf", CLIENT_ID, ORG_ID);

    assertFalse(result.isAccepted());
    assertEquals("invalid_content", result.getCode());
    assertEquals("The file content is not a PDF", result.getMessage());
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
    assertTrue(warnings.isEmpty(), "no orphan warning for a refusal before the copy: " + warnings);
    assertTempFileRemoved();
  }

  @Test
  void aNotFoundAnswerOfTheStoreIsRejectedAsStoreFailedWithoutAnOrphanWarning() {
    IncomingDocument row = rowWithId("ROW-1");
    providerReturns(row);
    storeAnswers(upload -> NeoResponse.error(404, "Record does not exist"));

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part(10), "invoice.pdf", CLIENT_ID, ORG_ID);

    assertEquals("store_failed", result.getCode());
    assertTrue(result.getMessage().contains("invoice.pdf"), result.getMessage());
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
    assertFalse(hasOrphanWarning("ROW-1", "invoice.pdf"), warnings.toString());
    assertTempFileRemoved();
  }

  @Test
  void aServerErrorOfTheStoreIsRejectedAsStoreFailedAndWarnsAboutAPossibleOrphanFile() {
    IncomingDocument row = rowWithId("ROW-9");
    providerReturns(row);
    storeAnswers(upload -> NeoResponse.error(500, "Internal error uploading attachment"));

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part(10), "invoice.pdf", CLIENT_ID, ORG_ID);

    assertEquals("store_failed", result.getCode());
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
    assertTrue(hasOrphanWarning("ROW-9", "invoice.pdf"), warnings.toString());
    assertTempFileRemoved();
  }

  @Test
  void anExceptionFromTheStoreNeverPropagatesAndWarnsAboutAPossibleOrphanFile() {
    IncomingDocument row = rowWithId("ROW-2");
    providerReturns(row);
    storeThrows(new IllegalStateException("boom"));

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part(10), "invoice.pdf", CLIENT_ID, ORG_ID);

    assertFalse(result.isAccepted());
    assertEquals("store_failed", result.getCode());
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
    assertTrue(hasOrphanWarning("ROW-2", "invoice.pdf"), warnings.toString());
    assertTempFileRemoved();
  }

  @Test
  void anExceptionBeforeTheStoreStartsIsRejectedWithoutAnOrphanWarning() {
    IncomingDocument row = rowWithId("ROW-3");
    providerReturns(row);
    doThrow(new IllegalStateException("flush failed")).when(dal).flush();

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part(10), "invoice.pdf", CLIENT_ID, ORG_ID);

    assertEquals("store_failed", result.getCode());
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
    store.verifyNoInteractions();
    assertFalse(hasOrphanWarning("ROW-3", "invoice.pdf"), warnings.toString());
  }

  @Test
  void aFailingCommitIsRejectedAsStoreFailedAndWarnsAboutAPossibleOrphanFile() {
    IncomingDocument row = rowWithId("ROW-4");
    providerReturns(row);
    storeAnswers(upload -> created());
    doThrow(new IllegalStateException("commit failed")).when(dal).commitAndClose();

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part(10), "invoice.pdf", CLIENT_ID, ORG_ID);

    assertFalse(result.isAccepted());
    assertEquals("store_failed", result.getCode());
    verify(dal).rollbackAndClose();
    assertTrue(hasOrphanWarning("ROW-4", "invoice.pdf"), warnings.toString());
    assertTempFileRemoved();
  }

  @Test
  void aPartThatCannotBeReadIsRejectedAsStoreFailedAndLeavesNoTempFile() throws Exception {
    IncomingDocument row = rowWithId("ROW-5");
    providerReturns(row);
    storeAnswers(upload -> created());
    Part part = part(10);
    when(part.getInputStream()).thenThrow(new IOException("client aborted"));

    InvoiceInboxIntake.Result result =
        InvoiceInboxIntake.ingest(part, "invoice.pdf", CLIENT_ID, ORG_ID);

    assertEquals("store_failed", result.getCode());
    verify(dal).rollbackAndClose();
    verify(dal, never()).commitAndClose();
  }

  // ---------------------------------------------------------------- isolation between files

  @Test
  void aFailureOnOneFileDoesNotAffectTheNextOne() {
    IncomingDocument first = rowWithId("ROW-A");
    IncomingDocument second = rowWithId("ROW-B");
    when(provider.get(IncomingDocument.class)).thenReturn(first, second);
    List<NeoResponse> answers = List.of(NeoResponse.error(500, "down"), created());
    int[] call = {0};
    storeAnswers(upload -> answers.get(call[0]++));

    InvoiceInboxIntake.Result failed =
        InvoiceInboxIntake.ingest(part(10), "one.pdf", CLIENT_ID, ORG_ID);
    InvoiceInboxIntake.Result ok =
        InvoiceInboxIntake.ingest(part(10), "two.pdf", CLIENT_ID, ORG_ID);

    assertEquals("store_failed", failed.getCode());
    assertTrue(ok.isAccepted());
    assertEquals("ROW-B", ok.getIncomingDocumentId());
    verify(dal).rollbackAndClose();
    verify(dal).commitAndClose();
    assertEquals(2, materialized.size());
    assertTempFileRemoved();
  }

  // ---------------------------------------------------------------- fixtures

  private static int utf8Length(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  private static void assertNoLoneSurrogate(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertTrue(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)),
            "lone high surrogate at " + i);
        i++;
      } else {
        assertFalse(Character.isLowSurrogate(c), "lone low surrogate at " + i);
      }
    }
  }

  private static Part part(long size) {
    Part part = mock(Part.class);
    when(part.getSize()).thenReturn(size);
    try {
      when(part.getInputStream()).thenReturn(new ByteArrayInputStream("%PDF-1.4 test".getBytes()));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return part;
  }

  private static IncomingDocument rowWithId(String id) {
    IncomingDocument row = mock(IncomingDocument.class);
    when(row.getId()).thenReturn(id);
    return row;
  }

  private void providerReturns(IncomingDocument row) {
    when(provider.get(IncomingDocument.class)).thenReturn(row);
  }

  private static NeoResponse created() {
    return NeoResponse.created(new org.codehaus.jettison.json.JSONObject());
  }

  /**
   * The store answers with {@code answer}; before that it runs the upload's materializer, as the
   * real store does once its cheap checks pass, so the temporary file exists to be cleaned up.
   */
  private void storeAnswers(Function<NeoAttachmentUpload, NeoResponse> answer) {
    store.when(() -> NeoAttachmentStore.store(any())).thenAnswer(invocation -> {
      NeoAttachmentUpload upload = invocation.getArgument(0);
      uploads.add(upload);
      materialized.add(upload.getMaterializer().materialize(upload.getFileName()));
      return answer.apply(upload);
    });
  }

  private void storeThrows(RuntimeException failure) {
    store.when(() -> NeoAttachmentStore.store(any())).thenAnswer(invocation -> {
      NeoAttachmentUpload upload = invocation.getArgument(0);
      uploads.add(upload);
      materialized.add(upload.getMaterializer().materialize(upload.getFileName()));
      throw failure;
    });
  }

  private boolean hasOrphanWarning(String rowId, String fileName) {
    return warnings.stream().anyMatch(message -> message.contains(ORPHAN_WARNING)
        && message.contains(rowId) && message.contains(fileName));
  }

  /** The copy the intake made for the store, and the directory it made it in, are gone. */
  private void assertTempFileRemoved() {
    assertFalse(materialized.isEmpty(), "the store never ran the materializer");
    for (File file : materialized) {
      assertFalse(file.exists(), "temp file left behind: " + file);
      assertFalse(file.getParentFile().exists(), "temp directory left behind: " + file.getParentFile());
    }
  }
}
