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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-5715 — the storing half of the attachment upload, reached with a file materializer instead of
 * an {@code HttpServletRequest}: the policy (10 MB, extension allow-list, magic bytes), the order of
 * the checks, the main-document replacement and the temp-file clean-up are the same ones
 * {@code handleUpload} always applied, whoever calls. The real {@link NeoAttachmentPolicy} runs;
 * only the DAL and the core storage are mocked.
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentStore
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentUpload
 */
@DisplayName("ETP-5715 — NeoAttachmentStore")
class NeoAttachmentStoreTest {

  private static final String TABLE = "C_Order";
  private static final String TABLE_ID = "TABLE1";
  private static final String TAB_ID = "TAB1";
  private static final String RECORD = "order-1";
  private static final String ORG = "org-1";
  private static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);

  private MockedStatic<NeoAttachmentsHelper> helper;
  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> contextMock;
  private AttachImplementationManager manager;
  private OBDal dal;
  private final AtomicInteger materialized = new AtomicInteger();

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentOrganization()).thenReturn(org);
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    manager = mock(AttachImplementationManager.class);
    helper = mockStatic(NeoAttachmentsHelper.class, CALLS_REAL_METHODS);
    helper.when(() -> NeoAttachmentsHelper.resolveTableId(TABLE)).thenReturn(TABLE_ID);
    helper.when(() -> NeoAttachmentsHelper.resolveTabId(TABLE_ID, TAB_ID)).thenReturn(TAB_ID);
    helper.when(() -> NeoAttachmentsHelper.recordExists(TABLE_ID, RECORD)).thenReturn(true);
    helper.when(NeoAttachmentsHelper::getAttachManager).thenReturn(manager);
    // The store looks the uploaded row up after every upload; default: nothing found.
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(anyString(), anyString(),
        anyString())).thenReturn(null);
  }

  @AfterEach
  void tearDown() {
    helper.close();
    contextMock.close();
    dalMock.close();
  }

  /** A materializer that writes {@code bytes} into a directory of its own, as the MCP link does. */
  private NeoAttachmentUpload.FileMaterializer writing(byte[] bytes) {
    return name -> {
      materialized.incrementAndGet();
      Path directory = Files.createTempDirectory("etgo-store-test-");
      File file = new File(directory.toFile(), name);
      Files.write(file.toPath(), bytes);
      return file;
    };
  }

  private NeoAttachmentUpload upload(String fileName, long declaredSize,
      NeoAttachmentUpload.FileMaterializer materializer, boolean main) {
    return new NeoAttachmentUpload(TABLE, RECORD, TAB_ID, fileName, declaredSize, materializer,
        main);
  }

  private static String messageOf(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  @Test
  @DisplayName("a PDF is stored through the core manager under the record's tab and organization")
  void pdfIsStored() throws Exception {
    NeoResponse response = NeoAttachmentStore.store(
        upload("factura.pdf", PDF.length, writing(PDF), false));

    assertEquals(201, response.getHttpStatus());
    assertEquals("factura.pdf", response.getBody().getString("name"));
    assertEquals("uploaded", response.getBody().getString("message"));
    verify(manager).upload(any(), eq(TAB_ID), eq(RECORD), eq(ORG), any(File.class));
    helper.verify(() -> NeoAttachmentsHelper.markAsMain(any()), never());
  }

  @Test
  @DisplayName("as MAIN it marks the new attachment, replacing the previous one, and reports its id")
  void pdfAsMain() throws Exception {
    Attachment stored = mock(Attachment.class);
    when(stored.getId()).thenReturn("ATT1");
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(TABLE_ID, RECORD,
        "factura.pdf")).thenReturn(stored);
    helper.when(() -> NeoAttachmentsHelper.markAsMain(stored)).then(invocation -> null);

    NeoResponse response = NeoAttachmentStore.store(
        upload("factura.pdf", PDF.length, writing(PDF), true));

    assertEquals(201, response.getHttpStatus());
    assertEquals("ATT1", response.getBody().getString("id"));
    assertTrue(response.getBody().getBoolean("isMain"));
    helper.verify(() -> NeoAttachmentsHelper.markAsMain(stored));
  }

  @Test
  @DisplayName("a declared size over 10 MB is refused before a single byte is written")
  void tooLargeDeclared() throws Exception {
    NeoResponse response = NeoAttachmentStore.store(
        upload("big.pdf", 10L * 1024 * 1024 + 1, writing(PDF), false));

    assertEquals(400, response.getHttpStatus());
    assertEquals("File exceeds the maximum allowed size of 10 MB", messageOf(response));
    assertEquals(0, materialized.get());
    verify(manager, never()).upload(any(), anyString(), anyString(), anyString(), any(File.class));
  }

  @Test
  @DisplayName("an unknown declared size does not let an oversized body through")
  void tooLargeMaterialized() throws Exception {
    NeoAttachmentUpload.FileMaterializer huge = name -> {
      Path directory = Files.createTempDirectory("etgo-store-test-");
      File file = new File(directory.toFile(), name);
      try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
        raf.write(PDF);
        raf.setLength(10L * 1024 * 1024 + 1);
      }
      return file;
    };

    NeoResponse response = NeoAttachmentStore.store(upload("big.pdf", -1, huge, false));

    assertEquals(400, response.getHttpStatus());
    assertEquals("File exceeds the maximum allowed size of 10 MB", messageOf(response));
  }

  @Test
  @DisplayName("a disallowed extension is refused, naming the allowed ones, with nothing written")
  void disallowedType() throws Exception {
    NeoResponse response = NeoAttachmentStore.store(
        upload("virus.exe", 100, writing(PDF), false));

    assertEquals(400, response.getHttpStatus());
    assertTrue(messageOf(response).startsWith("File type not allowed: .exe. Allowed types: "),
        messageOf(response));
    assertEquals(0, materialized.get());
  }

  @Test
  @DisplayName("bytes that do not match the extension's signature are refused (spoofed type)")
  void badMagicBytes() throws Exception {
    byte[] notAPdf = "<html>surprise</html>".getBytes(StandardCharsets.UTF_8);

    NeoResponse response = NeoAttachmentStore.store(
        upload("factura.pdf", notAPdf.length, writing(notAPdf), false));

    assertEquals(400, response.getHttpStatus());
    assertEquals("File content does not match its .pdf extension", messageOf(response));
    verify(manager, never()).upload(any(), anyString(), anyString(), anyString(), any(File.class));
  }

  @Test
  @DisplayName("a missing record is a 404 and nothing is written")
  void missingRecord() throws Exception {
    helper.when(() -> NeoAttachmentsHelper.recordExists(TABLE_ID, RECORD)).thenReturn(false);

    NeoResponse response = NeoAttachmentStore.store(
        upload("factura.pdf", PDF.length, writing(PDF), false));

    assertEquals(404, response.getHttpStatus());
    assertEquals(0, materialized.get());
  }

  @Test
  @DisplayName("a table with no tab at all is a 400")
  void noTab() throws Exception {
    helper.when(() -> NeoAttachmentsHelper.resolveTabId(TABLE_ID, TAB_ID)).thenReturn(null);

    assertEquals(400, NeoAttachmentStore.store(
        upload("factura.pdf", PDF.length, writing(PDF), false)).getHttpStatus());
  }

  @Test
  @DisplayName("the temp file and its private directory are removed, on success and on rejection")
  void tempFilesAreCleanedUp() throws Exception {
    File[] seen = new File[2];
    NeoAttachmentUpload.FileMaterializer capturing = name -> {
      File file = writing(PDF).materialize(name);
      seen[0] = file;
      return file;
    };
    NeoAttachmentStore.store(upload("factura.pdf", PDF.length, capturing, false));
    assertFalse(seen[0].exists());
    assertFalse(seen[0].getParentFile().exists());

    NeoAttachmentUpload.FileMaterializer rejected = name -> {
      File file = writing("nope".getBytes(StandardCharsets.UTF_8)).materialize(name);
      seen[1] = file;
      return file;
    };
    NeoAttachmentStore.store(upload("factura.pdf", 4, rejected, false));
    assertFalse(seen[1].exists());
    assertFalse(seen[1].getParentFile().exists());
  }

  @Test
  @DisplayName("a failure in the core storage rolls back and answers a 500, never a success")
  void coreFailureRollsBack() throws Exception {
    doThrow(new IllegalStateException("disk full")).when(manager)
        .upload(any(), anyString(), anyString(), anyString(), any(File.class));

    NeoResponse response = NeoAttachmentStore.store(
        upload("factura.pdf", PDF.length, writing(PDF), false));

    assertEquals(500, response.getHttpStatus());
    verify(dal).rollbackAndClose();
  }

  @Test
  @DisplayName("an unreadable body (the materializer fails) rolls back and answers a 500")
  void materializerFailure() throws Exception {
    NeoAttachmentUpload.FileMaterializer failing = name -> {
      throw new IOException("connection reset");
    };

    assertEquals(500, NeoAttachmentStore.store(
        upload("factura.pdf", PDF.length, failing, false)).getHttpStatus());
    verify(dal).rollbackAndClose();
  }

  // ── ETP-5735: the stored MIME type is corrected after the core upload ────────

  private Attachment storedAs(String name, String dataType) {
    Attachment stored = mock(Attachment.class);
    when(stored.getId()).thenReturn("ATT1");
    when(stored.getName()).thenReturn(name);
    when(stored.getDataType()).thenReturn(dataType);
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(TABLE_ID, RECORD, name))
        .thenReturn(stored);
    return stored;
  }

  private static final byte[] WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 "
      .getBytes(StandardCharsets.ISO_8859_1);

  private void stubUpdateDataType(boolean result) {
    helper.when(() -> NeoAttachmentsHelper.updateDataType(anyString(), anyString()))
        .thenReturn(result);
  }

  /** The entity must never be dirtied: that is what failed the DAL write check at flush. */
  private void verifyEntityUntouched(Attachment stored) {
    verify(stored, never()).setDataType(any());
    verify(dal, never()).save(any());
  }

  @Test
  @DisplayName("an octet-stream data type stored for a WEBP is corrected to image/webp by a native update")
  void octetStreamIsCorrected() throws Exception {
    Attachment stored = storedAs("foto.webp", "application/octet-stream");
    stubUpdateDataType(true);

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    helper.verify(() -> NeoAttachmentsHelper.updateDataType("ATT1", "image/webp"));
    verifyEntityUntouched(stored);
  }

  @Test
  @DisplayName("a blank stored data type is corrected too")
  void blankIsCorrected() throws Exception {
    Attachment stored = storedAs("foto.webp", "  ");
    stubUpdateDataType(true);

    NeoAttachmentStore.store(upload("foto.webp", WEBP.length, writing(WEBP), false));

    helper.verify(() -> NeoAttachmentsHelper.updateDataType("ATT1", "image/webp"));
    verifyEntityUntouched(stored);
  }

  @Test
  @DisplayName("a null stored data type is corrected too")
  void nullIsCorrected() throws Exception {
    Attachment stored = storedAs("foto.webp", null);
    stubUpdateDataType(true);

    NeoAttachmentStore.store(upload("foto.webp", WEBP.length, writing(WEBP), false));

    helper.verify(() -> NeoAttachmentsHelper.updateDataType("ATT1", "image/webp"));
    verifyEntityUntouched(stored);
  }

  @Test
  @DisplayName("a failed native update (false) still leaves the upload successful")
  void updateReturningFalseIsHarmless() throws Exception {
    Attachment stored = storedAs("foto.webp", "application/octet-stream");
    stubUpdateDataType(false);

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    verifyEntityUntouched(stored);
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("an already-correct data type is left untouched and nothing is saved")
  void correctDataTypeIsKept() throws Exception {
    Attachment stored = storedAs("foto.webp", "image/png");

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    helper.verify(() -> NeoAttachmentsHelper.updateDataType(any(), any()), never());
    verifyEntityUntouched(stored);
  }

  @Test
  @DisplayName("when the core lookup finds nothing the upload still succeeds")
  void lookupReturningNullIsHarmless() throws Exception {
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(TABLE_ID, RECORD,
        "foto.webp")).thenReturn(null);

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    verify(dal, never()).save(any());
  }

  @Test
  @DisplayName("an unresolvable type leaves the stored value as it is and the upload succeeds")
  void unresolvedTypeLeavesTheValue() throws Exception {
    // the stored name carries an extension the policy does not know, so nothing resolves
    Attachment stored = mock(Attachment.class);
    when(stored.getId()).thenReturn("ATT1");
    when(stored.getName()).thenReturn("foto.unknown");
    when(stored.getDataType()).thenReturn("application/octet-stream");
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(TABLE_ID, RECORD,
        "foto.webp")).thenReturn(stored);

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    helper.verify(() -> NeoAttachmentsHelper.updateDataType(any(), any()), never());
    verifyEntityUntouched(stored);
  }

  @Test
  @DisplayName("a RuntimeException inside the correction is swallowed and the upload succeeds")
  void correctionFailureIsSwallowed() throws Exception {
    storedAs("foto.webp", "application/octet-stream");
    helper.when(() -> NeoAttachmentsHelper.updateDataType(anyString(), anyString()))
        .thenThrow(new IllegalStateException("boom"));

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    assertEquals("foto.webp", response.getBody().getString("name"));
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("combined with markAsMain the response still carries id and isMain")
  void correctionAndMainTogether() throws Exception {
    Attachment stored = storedAs("foto.webp", "application/octet-stream");
    stubUpdateDataType(true);
    helper.when(() -> NeoAttachmentsHelper.markAsMain(stored)).then(invocation -> null);

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), true));

    assertEquals(201, response.getHttpStatus());
    assertEquals("ATT1", response.getBody().getString("id"));
    assertTrue(response.getBody().getBoolean("isMain"));
    helper.verify(() -> NeoAttachmentsHelper.updateDataType("ATT1", "image/webp"));
    verifyEntityUntouched(stored);
    helper.verify(() -> NeoAttachmentsHelper.markAsMain(stored));
  }

  @Test
  @DisplayName("a lookup failure on a plain upload skips the correction; the upload is not rolled back")
  void lookupFailureOnPlainUploadIsSwallowed() throws Exception {
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(TABLE_ID, RECORD,
        "foto.webp")).thenThrow(new IllegalStateException("query failed"));

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), false));

    assertEquals(201, response.getHttpStatus());
    verify(dal, never()).save(any());
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("a lookup failure on a markAsMain upload still fails the request, as before")
  void lookupFailureOnMainUploadFails() throws Exception {
    helper.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment(TABLE_ID, RECORD,
        "foto.webp")).thenThrow(new IllegalStateException("query failed"));

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), true));

    assertEquals(500, response.getHttpStatus());
    verify(dal).rollbackAndClose();
    helper.verify(() -> NeoAttachmentsHelper.markAsMain(any()), never());
  }

  @Test
  @DisplayName("a correction failure does not fail a markAsMain upload; it is still marked")
  void correctionFailureOnMainUploadIsSwallowed() throws Exception {
    Attachment stored = storedAs("foto.webp", "application/octet-stream");
    helper.when(() -> NeoAttachmentsHelper.updateDataType(anyString(), anyString()))
        .thenThrow(new IllegalStateException("boom"));
    helper.when(() -> NeoAttachmentsHelper.markAsMain(stored)).then(invocation -> null);

    NeoResponse response = NeoAttachmentStore.store(
        upload("foto.webp", WEBP.length, writing(WEBP), true));

    assertEquals(201, response.getHttpStatus());
    assertEquals("ATT1", response.getBody().getString("id"));
    assertTrue(response.getBody().getBoolean("isMain"));
    helper.verify(() -> NeoAttachmentsHelper.markAsMain(stored));
  }
}
