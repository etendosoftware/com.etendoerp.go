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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * ETP-5715 — which stored file is the PDF of a document, and whether the SPA would still serve it.
 * The decision is pure ({@code decide}) and tested on its own; {@code resolve} is tested only for
 * what it gathers (the main attachment the SPA reads, the record's {@code updated}).
 *
 * @covers com.etendoerp.go.schemaforge.NeoDocumentPdf
 */
@DisplayName("ETP-5715 — NeoDocumentPdf")
class NeoDocumentPdfTest {

  private static final String RECORD = "BF1D5905BBF344B1BC7CB8FEB0DF9CB4";
  private static final String PREVIEW_NAME = RECORD + ".pdf";
  private static final String SUPPLIER_CONFIG =
      "{\"pdf\":{\"source\":\"supplier\",\"reason\":\"no printable: the PDF is uploaded\"}}";

  private static Date at(String iso) {
    return Date.from(Instant.parse(iso));
  }

  /** The metadata the attachments endpoint gives for the main file (what the SPA reads). */
  private static JSONObject main(String name, String updatedAt) throws Exception {
    JSONObject main = new JSONObject();
    main.put("id", "FILE1");
    main.put("name", name);
    main.put("size", 4321);
    main.put("dataType", "application/pdf");
    main.put("uploadedAt", "2026-10-01T08:00:00Z");
    if (updatedAt != null) {
      main.put("updatedAt", updatedAt);
    }
    return main;
  }

  private static NeoDocumentPdf.Result rendered(JSONObject main, Date recordUpdated, Date branding) {
    return NeoDocumentPdf.decide(main, NeoPdfSourceConfig.Source.RENDERED, recordUpdated, branding);
  }

  private static NeoDocumentPdf.Result supplier(JSONObject main, Date recordUpdated, Date branding) {
    return NeoDocumentPdf.decide(main, NeoPdfSourceConfig.Source.SUPPLIER, recordUpdated, branding);
  }

  @Test
  @DisplayName("no main attachment: a rendered entity has no PDF yet, a supplier entity no supplier document")
  void noMainAttachment() throws Exception {
    assertEquals(NeoDocumentPdf.Status.MISSING,
        rendered(null, at("2026-10-02T00:00:00Z"), null).getStatus());
    assertEquals(NeoDocumentPdf.Status.MISSING,
        rendered(new JSONObject(), at("2026-10-02T00:00:00Z"), null).getStatus());
    assertEquals(NeoDocumentPdf.Status.NO_SUPPLIER_DOCUMENT, supplier(null, null, null).getStatus());
    assertEquals(NeoDocumentPdf.Status.NO_SUPPLIER_DOCUMENT,
        supplier(new JSONObject(), null, null).getStatus());
  }

  @Test
  @DisplayName("rendered: a main file written after the last edit of the record is served")
  void freshMainIsFound() throws Exception {
    JSONObject cached = main(PREVIEW_NAME, "2026-10-02T10:00:00Z");

    NeoDocumentPdf.Result result = rendered(cached, at("2026-10-02T09:59:59Z"), null);

    assertEquals(NeoDocumentPdf.Status.FOUND, result.getStatus());
    assertSame(cached, result.getFile());
  }

  @Test
  @DisplayName("rendered: a main file written BEFORE the record's last edit is stale, as in the SPA")
  void staleMainIsStale() throws Exception {
    assertEquals(NeoDocumentPdf.Status.STALE, rendered(main(PREVIEW_NAME, "2026-10-02T10:00:00Z"),
        at("2026-10-02T10:00:01Z"), null).getStatus());
  }

  @Test
  @DisplayName("rendered: the SPA judges the main file whatever its name, so a user's file is judged too")
  void freshnessDoesNotLookAtTheName() throws Exception {
    // fetchCachedBlob takes the main attachment's id and runs isCachedRenderingStale on it: no name test.
    assertEquals(NeoDocumentPdf.Status.STALE, rendered(main("mi-factura.pdf", "2026-10-02T10:00:00Z"),
        at("2026-10-03T00:00:00Z"), null).getStatus());
    assertEquals(NeoDocumentPdf.Status.FOUND, rendered(main("mi-factura.pdf", "2026-10-04T10:00:00Z"),
        at("2026-10-03T00:00:00Z"), null).getStatus());
  }

  @Test
  @DisplayName("the same second is fresh: the comparison is strict and whole-second, like the SPA")
  void sameSecondIsFresh() throws Exception {
    assertEquals(NeoDocumentPdf.Status.FOUND, rendered(main(PREVIEW_NAME, "2026-10-02T10:00:00Z"),
        at("2026-10-02T10:00:00Z"), null).getStatus());
  }

  @Test
  @DisplayName("milliseconds of the record's updated do not make a file written that second stale")
  void subSecondRecordUpdateIsTruncated() throws Exception {
    assertEquals(NeoDocumentPdf.Status.FOUND, rendered(main(PREVIEW_NAME, "2026-10-02T10:00:00Z"),
        Date.from(Instant.parse("2026-10-02T10:00:00.900Z")), null).getStatus());
  }

  @Test
  @DisplayName("rendered: a company branding change after the file was written makes it stale (ETP-5541)")
  void brandingNewerThanFileIsStale() throws Exception {
    assertEquals(NeoDocumentPdf.Status.STALE, rendered(main(PREVIEW_NAME, "2026-10-02T10:00:00Z"),
        at("2026-10-02T09:00:00Z"), at("2026-10-02T11:00:00Z")).getStatus());
  }

  @Test
  @DisplayName("supplier: the uploaded document is never stale, whatever changed after it")
  void supplierDocumentIsNeverStale() throws Exception {
    // Real case: the invoice was completed 13 minutes AFTER the supplier's PDF was attached.
    JSONObject upload = main("factura_blanquiceleste_iva_variado.pdf", "2026-10-14T18:33:51Z");

    NeoDocumentPdf.Result result = supplier(upload, at("2026-10-14T18:46:52Z"),
        at("2026-10-20T00:00:00Z"));

    assertEquals(NeoDocumentPdf.Status.FOUND, result.getStatus());
    assertSame(upload, result.getFile());
  }

  @Test
  @DisplayName("supplier: a file that merely looks like a render (purchase-invoice-x.pdf, <id>.pdf) is not misjudged")
  void supplierFileNamedLikeARenderIsNotStale() throws Exception {
    for (String name : new String[] {"purchase-invoice-x.pdf", PREVIEW_NAME}) {
      assertEquals(NeoDocumentPdf.Status.FOUND, supplier(main(name, "2026-10-02T10:00:00Z"),
          at("2026-10-03T00:00:00Z"), at("2026-10-04T00:00:00Z")).getStatus(), name);
    }
  }

  @Test
  @DisplayName("unknown beats wrong: no record timestamp, or an unreadable file timestamp, reads as fresh")
  void failsOpenLikeTheSpa() throws Exception {
    assertEquals(NeoDocumentPdf.Status.FOUND,
        rendered(main(PREVIEW_NAME, "2026-10-02T10:00:00Z"), null, at("2030-01-01T00:00:00Z"))
            .getStatus());
    JSONObject unreadable = main(PREVIEW_NAME, "not-a-date");
    unreadable.remove("uploadedAt");
    assertEquals(NeoDocumentPdf.Status.FOUND,
        rendered(unreadable, at("2026-10-03T00:00:00Z"), null).getStatus());
  }

  @Test
  @DisplayName("an unreadable updatedAt falls through to uploadedAt, as the SPA's attachmentWrittenAtMs does")
  void unreadableUpdatedAtFallsThrough() throws Exception {
    assertEquals(NeoDocumentPdf.Status.STALE,
        rendered(main(PREVIEW_NAME, "not-a-date"), at("2026-10-03T00:00:00Z"), null).getStatus());
  }

  @Test
  @DisplayName("without updatedAt the file's own creation time is the fallback")
  void fallsBackToUploadedAt() throws Exception {
    assertEquals(NeoDocumentPdf.Status.STALE,
        rendered(main(PREVIEW_NAME, null), at("2026-10-02T00:00:00Z"), null).getStatus());
  }

  // ── resolve: what it gathers ────────────────────────────────────────────

  private static SFEntity entityWith(String mcpConfig) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.getMCPConfig()).thenReturn(mcpConfig);
    return entity;
  }

  @Test
  @DisplayName("resolve of a supplier entity serves the uploaded file without reading the record at all")
  void supplierEntityNeverReadsTheRecord() throws Exception {
    NeoAttachmentTarget target = mock(NeoAttachmentTarget.class);
    when(target.getTableName()).thenReturn("C_Invoice");
    when(target.getRecordId()).thenReturn(RECORD);
    // No OBDal / ModelProvider / OBContext mocked: touching the record or the branding would fail.
    try (MockedStatic<NeoAttachmentsHelper> helperMock = mockStatic(NeoAttachmentsHelper.class)) {
      helperMock.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", RECORD))
          .thenReturn(NeoResponse.ok(main("segundo-documento.pdf", "2026-10-21T14:20:40Z")));

      NeoDocumentPdf.Result result = NeoDocumentPdf.resolve(target, entityWith(SUPPLIER_CONFIG));

      assertEquals(NeoDocumentPdf.Status.FOUND, result.getStatus());
    }
  }

  @Test
  @DisplayName("resolve of a supplier entity with no main attachment says there is no supplier document")
  void supplierEntityWithoutMain() {
    NeoAttachmentTarget target = mock(NeoAttachmentTarget.class);
    when(target.getTableName()).thenReturn("C_Invoice");
    when(target.getRecordId()).thenReturn(RECORD);
    try (MockedStatic<NeoAttachmentsHelper> helperMock = mockStatic(NeoAttachmentsHelper.class)) {
      helperMock.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", RECORD))
          .thenReturn(NeoResponse.ok(new JSONObject()));

      assertEquals(NeoDocumentPdf.Status.NO_SUPPLIER_DOCUMENT,
          NeoDocumentPdf.resolve(target, entityWith(SUPPLIER_CONFIG)).getStatus());
      assertEquals(NeoDocumentPdf.Status.MISSING,
          NeoDocumentPdf.resolve(target, entityWith(null)).getStatus());
    }
  }

  @Test
  @DisplayName("resolve reads the SPA's main attachment of the target and the record's updated")
  void resolveGathersTheSpasInputs() throws Exception {
    NeoAttachmentTarget target = mock(NeoAttachmentTarget.class);
    when(target.getTableName()).thenReturn("C_Invoice");
    when(target.getTableId()).thenReturn("TABLE1");
    when(target.getRecordId()).thenReturn(RECORD);
    OBDal dal = mock(OBDal.class);
    ModelProvider model = mock(ModelProvider.class);
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getName()).thenReturn("Invoice");
    when(model.getEntityByTableId("TABLE1")).thenReturn(dalEntity);
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.get("updated")).thenReturn(at("2026-10-02T10:00:05Z"));
    when(dal.get("Invoice", RECORD)).thenReturn(row);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelMock = mockStatic(ModelProvider.class);
        MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
        MockedStatic<NeoAttachmentsHelper> helperMock = mockStatic(NeoAttachmentsHelper.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      modelMock.when(ModelProvider::getInstance).thenReturn(model);
      OBContext context = mock(OBContext.class);
      contextMock.when(OBContext::getOBContext).thenReturn(context);
      helperMock.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", RECORD))
          .thenReturn(NeoResponse.ok(main(PREVIEW_NAME, "2026-10-02T10:00:00Z")));

      NeoDocumentPdf.Result result = NeoDocumentPdf.resolve(target, entityWith(null));

      assertEquals(NeoDocumentPdf.Status.STALE, result.getStatus(),
          "edited 5 seconds after the cache was written");
      contextMock.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
      contextMock.verify(OBContext::restorePreviousMode, atLeastOnce());
    }
  }

  @Test
  @DisplayName("a failing attachment lookup is a FAILED result with the helper's message, not a PDF answer")
  void helperFailureIsReported() {
    NeoAttachmentTarget target = mock(NeoAttachmentTarget.class);
    when(target.getTableName()).thenReturn("C_Invoice");
    when(target.getRecordId()).thenReturn(RECORD);
    try (MockedStatic<NeoAttachmentsHelper> helperMock = mockStatic(NeoAttachmentsHelper.class)) {
      helperMock.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", RECORD))
          .thenReturn(NeoResponse.error(500, "Internal error resolving main attachment"));

      NeoDocumentPdf.Result result = NeoDocumentPdf.resolve(target, entityWith(null));

      assertEquals(NeoDocumentPdf.Status.FAILED, result.getStatus());
      assertEquals("Internal error resolving main attachment", result.getMessage());
    }
  }
}
