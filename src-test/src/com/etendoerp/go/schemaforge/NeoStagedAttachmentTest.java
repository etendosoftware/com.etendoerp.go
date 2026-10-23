/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.NeoStagedDocuments.Staged;

/**
 * Attaching a staged PDF to the record created from it: it is the SPA's upload (the same store), fed
 * from a copy of the staged file so the staged document survives until the caller removes it.
 *
 * @covers com.etendoerp.go.schemaforge.NeoStagedAttachment
 */
@DisplayName("ETP-5715 — NeoStagedAttachment")
class NeoStagedAttachmentTest {

  @TempDir
  Path root;

  private MockedStatic<NeoAttachmentStore> storeMock;
  private MockedStatic<NeoAttachmentsHelper> helperMock;
  private NeoAttachmentTarget target;
  private Staged staged;
  private Path stagedFile;

  @BeforeEach
  void setUp() throws Exception {
    storeMock = mockStatic(NeoAttachmentStore.class);
    helperMock = mockStatic(NeoAttachmentsHelper.class);
    target = mock(NeoAttachmentTarget.class);
    when(target.getTableName()).thenReturn("C_Invoice");
    when(target.getTableId()).thenReturn("TABLE1");
    when(target.getTabId()).thenReturn("TAB1");
    when(target.getRecordId()).thenReturn("INV1");
    NeoStagedDocuments documents = new NeoStagedDocuments(Clock.systemUTC(), root);
    Session session = new Session("C", "O", "U", "R", null);
    String id = documents.reserve(session, "purchase-invoice", "factura.pdf");
    stagedFile = documents.directoryFor(id).resolve("factura.pdf");
    Files.writeString(stagedFile, "%PDF-1.4 staged");
    documents.markStored(id, stagedFile);
    staged = documents.find(id, session).orElseThrow();
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn("ATT1");
    helperMock.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment("TABLE1", "INV1",
        "factura.pdf")).thenReturn(attachment);
  }

  @AfterEach
  void tearDown() {
    helperMock.close();
    storeMock.close();
  }

  private void storeAnswers(NeoResponse answer) {
    storeMock.when(() -> NeoAttachmentStore.store(any())).thenReturn(answer);
  }

  private static NeoResponse created() throws Exception {
    return NeoResponse.created(new JSONObject().put("name", "factura.pdf").put("message", "uploaded"));
  }

  @Test
  @DisplayName("the upload the store receives names the record's table, tab and id, the file and the main flag")
  void storeReceivesTheStagedFile() throws Exception {
    storeAnswers(created());

    NeoStagedAttachment.attach(target, staged, true);

    ArgumentCaptor<NeoAttachmentUpload> captor = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    storeMock.verify(() -> NeoAttachmentStore.store(captor.capture()));
    NeoAttachmentUpload upload = captor.getValue();
    assertEquals("C_Invoice", upload.getTableName());
    assertEquals("INV1", upload.getRecordId());
    assertEquals("TAB1", upload.getTabIdHint());
    assertEquals("factura.pdf", upload.getFileName());
    assertEquals(Files.size(stagedFile), upload.getDeclaredSize());
    assertTrue(upload.isMarkAsMain());
  }

  @Test
  @DisplayName("the store is fed a COPY in a directory of its own: the staged file is not handed over")
  void theStagedFileIsCopiedNotHandedOver() throws Exception {
    storeAnswers(created());
    NeoStagedAttachment.attach(target, staged, true);
    ArgumentCaptor<NeoAttachmentUpload> captor = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    storeMock.verify(() -> NeoAttachmentStore.store(captor.capture()));

    File copy = captor.getValue().getMaterializer().materialize("factura.pdf");

    assertEquals("%PDF-1.4 staged", Files.readString(copy.toPath()));
    assertFalse(copy.toPath().startsWith(root), "outside the staging directory");
    assertTrue(Files.exists(stagedFile), "the staged file is still there");
    Files.deleteIfExists(copy.toPath());
    Files.deleteIfExists(copy.getParentFile().toPath());
  }

  @Test
  @DisplayName("success answers 201 with the attachment id and whether it is the main one")
  void successCarriesTheAttachmentId() throws Exception {
    storeAnswers(created());

    NeoResponse answer = NeoStagedAttachment.attach(target, staged, true);

    assertEquals(201, answer.getHttpStatus());
    assertEquals("ATT1", answer.getBody().getString("id"));
    assertTrue(answer.getBody().getBoolean("isMain"));
    assertEquals("factura.pdf", answer.getBody().getString("name"));
  }

  @Test
  @DisplayName("without main it is a plain attachment")
  void notMain() throws Exception {
    storeAnswers(created());

    NeoResponse answer = NeoStagedAttachment.attach(target, staged, false);

    assertFalse(answer.getBody().getBoolean("isMain"));
  }

  @Test
  @DisplayName("a store failure (policy, missing record) is returned as it is and no id is looked up")
  void storeFailureIsReturned() {
    storeAnswers(NeoResponse.error(404, "Record 'INV1' does not exist"));

    NeoResponse answer = NeoStagedAttachment.attach(target, staged, true);

    assertEquals(404, answer.getHttpStatus());
    helperMock.verifyNoInteractions();
  }

  @Test
  @DisplayName("a staged document whose bytes never arrived cannot be attached: 409, nothing stored")
  void documentWithoutBytes() {
    NeoStagedDocuments documents = new NeoStagedDocuments(Clock.systemUTC(), root.resolve("x"));
    Session session = new Session("C", "O", "U", "R", null);
    Staged empty = documents.find(documents.reserve(session, "s", "a.pdf"), session).orElseThrow();

    NeoResponse answer = NeoStagedAttachment.attach(target, empty, true);

    assertEquals(409, answer.getHttpStatus());
    storeMock.verifyNoInteractions();
  }
}
