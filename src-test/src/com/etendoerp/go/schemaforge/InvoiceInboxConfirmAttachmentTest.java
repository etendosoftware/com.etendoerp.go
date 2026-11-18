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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The original file of an inbox document attached to the invoice created from it, as its main
 * document, through the store the upload uses: a copy (the row keeps its own), a missing original or
 * a refusing store stops the confirmation, and the temporary copy is always removed.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmAttachment
 */
class InvoiceInboxConfirmAttachmentTest {

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<NeoAttachmentsHelper> helper;
  private MockedStatic<NeoAttachmentStore> store;
  private MockedStatic<NeoServletSupport> support;
  private OBDal dal;
  private File copy;

  @BeforeEach
  void setUp() throws Exception {
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    messages = mockStatic(OBMessageUtils.class);
    helper = mockStatic(NeoAttachmentsHelper.class);
    store = mockStatic(NeoAttachmentStore.class);
    support = mockStatic(NeoServletSupport.class);
    copy = File.createTempFile("inbox-confirm-test", ".pdf");
    copy.deleteOnExit();
    helper.when(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(anyString(), anyString()))
        .thenReturn(copy);
    support.when(() -> NeoServletSupport.findSpec("purchase-invoice")).thenReturn(null);
  }

  @AfterEach
  void tearDown() {
    support.close();
    store.close();
    helper.close();
    messages.close();
    dalStatic.close();
    copy.delete();
  }

  private void storeAnswers(int status) {
    store.when(() -> NeoAttachmentStore.store(any())).thenReturn(NeoResponse.error(status, "x"));
  }

  @Test
  @DisplayName("the main attachment of the inbox row is copied to the invoice as its main document")
  void attachesTheCopyAsTheMainDocument() throws Exception {
    store.when(() -> NeoAttachmentStore.store(any())).thenReturn(
        new NeoResponse(201, new org.codehaus.jettison.json.JSONObject()));

    InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1");

    ArgumentCaptor<NeoAttachmentUpload> upload = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    store.verify(() -> NeoAttachmentStore.store(upload.capture()));
    assertEquals("C_Invoice", upload.getValue().getTableName());
    assertEquals("INV-1", upload.getValue().getRecordId());
    assertEquals(copy.getName(), upload.getValue().getFileName());
    assertTrue(upload.getValue().isMarkAsMain());
    assertSame(copy, upload.getValue().getMaterializer().materialize(copy.getName()));
    helper.verify(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(
        IncomingDocument.TABLE_NAME, "ROW-1"));
    helper.verify(() -> NeoAttachmentsHelper.cleanupTempFile(copy));
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("an original that is gone stops the confirmation and nothing is stored")
  void aMissingOriginalStops() {
    helper.when(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(anyString(), anyString()))
        .thenReturn(null);

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1"));

    assertTrue(stop.getMessage().contains("no longer available"), stop.getMessage());
    store.verifyNoInteractions();
  }

  @Test
  @DisplayName("a store that answers 4xx or 5xx rolls the session back, stops, and still removes the copy")
  void aRefusingStoreStops() {
    for (int status : new int[] { 400, 500 }) {
      storeAnswers(status);

      InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
          () -> InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1"));

      assertTrue(stop.getMessage().contains("could not be attached"), stop.getMessage());
    }
    verify(dal, org.mockito.Mockito.times(2)).rollbackAndClose();
    helper.verify(() -> NeoAttachmentsHelper.cleanupTempFile(copy), org.mockito.Mockito.times(2));
  }

  @Test
  @DisplayName("an exception of the store still removes the temporary copy")
  void aThrowingStoreStillCleansUp() {
    store.when(() -> NeoAttachmentStore.store(any())).thenThrow(new IllegalStateException("boom"));

    assertThrows(IllegalStateException.class,
        () -> InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1"));

    helper.verify(() -> NeoAttachmentsHelper.cleanupTempFile(copy));
  }

  @Test
  @DisplayName("a copy that cannot be made is an IOException for the caller")
  void aCopyFailureIsAnIoException() {
    helper.when(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(anyString(), anyString()))
        .thenThrow(new IOException("disk"));

    assertThrows(IOException.class, () -> InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1"));
    store.verifyNoInteractions();
  }

  @Test
  @DisplayName("the tab hint is the tab of the purchase invoice header entity when the spec has one")
  @SuppressWarnings("unchecked")
  void theHeaderTabIsHinted() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC-1");
    support.when(() -> NeoServletSupport.findSpec("purchase-invoice")).thenReturn(spec);
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    SFEntity entity = mock(SFEntity.class);
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn("TAB-1");
    when(entity.getADTab()).thenReturn(tab);
    when(criteria.uniqueResult()).thenReturn(entity);
    store.when(() -> NeoAttachmentStore.store(any())).thenReturn(
        new NeoResponse(201, new org.codehaus.jettison.json.JSONObject()));

    InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1");

    ArgumentCaptor<NeoAttachmentUpload> upload = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    store.verify(() -> NeoAttachmentStore.store(upload.capture()));
    assertEquals("TAB-1", upload.getValue().getTabIdHint());
  }

  @Test
  @DisplayName("a spec without a header entity or tab leaves the hint empty for the store to resolve")
  @SuppressWarnings("unchecked")
  void noTabLeavesTheHintEmpty() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC-1");
    support.when(() -> NeoServletSupport.findSpec("purchase-invoice")).thenReturn(spec);
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(null);
    store.when(() -> NeoAttachmentStore.store(any())).thenReturn(
        new NeoResponse(201, new org.codehaus.jettison.json.JSONObject()));

    InvoiceInboxConfirmAttachment.attach("ROW-1", "INV-1");

    ArgumentCaptor<NeoAttachmentUpload> upload = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    store.verify(() -> NeoAttachmentStore.store(upload.capture()));
    assertEquals(null, upload.getValue().getTabIdHint());
  }
}
