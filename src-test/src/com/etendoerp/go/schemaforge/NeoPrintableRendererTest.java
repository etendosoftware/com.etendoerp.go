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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-6013 — the server-side render and store of a printable, and the file name it stores under.
 * Collaborators (template source, jsreport, builders) are injected; the attachment store and the
 * role checks are static and mocked.
 *
 * @covers com.etendoerp.go.schemaforge.NeoPrintableRenderer
 */
@DisplayName("ETP-6013 — NeoPrintableRenderer")
class NeoPrintableRendererTest {

  private static final String RECORD = "BF1D5905BBF344B1BC7CB8FEB0DF9CB4";
  private static final String PRINTED = "{\"pdf\":{\"source\":\"rendered\","
      + "\"print\":{\"document\":\"sales-invoice\"},\"reason\":\"server-side render\"}}";

  private NeoPrintableTemplates templates;
  private NeoJsreportClient jsreport;
  private NeoPrintableDataBuilder builder;
  private SFSpec spec;
  private SFEntity entity;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty(NeoJsreportClient.URL_PROPERTY, "http://localhost:5488");
    System.setProperty(NeoPrintableTemplates.URL_PROPERTY, "http://localhost:3100/printables");
    templates = mock(NeoPrintableTemplates.class);
    jsreport = mock(NeoJsreportClient.class);
    builder = mock(NeoPrintableDataBuilder.class);
    when(builder.document()).thenReturn("sales-invoice");
    when(builder.storesFor("CO")).thenReturn(true);
    when(builder.documentNo(any())).thenReturn("FV/2026 001");
    spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn("sales-invoice");
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("318");
    when(table.getDBTableName()).thenReturn("C_Invoice");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    when(tab.getId()).thenReturn("263");
    entity = mock(SFEntity.class);
    when(entity.getMCPConfig()).thenReturn(PRINTED);
    when(entity.getADTab()).thenReturn(tab);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(NeoJsreportClient.URL_PROPERTY);
    System.clearProperty(NeoPrintableTemplates.URL_PROPERTY);
  }

  private NeoPrintableRenderer renderer() {
    return renderer(null);
  }

  /** A renderer that sees {@code mainName} as the record's current main attachment (null: none). */
  private NeoPrintableRenderer renderer(String mainName) {
    return new NeoPrintableRenderer(templates, jsreport, () -> List.of(builder),
        (tableId, recordId) -> mainName);
  }

  private static NeoPrintableTemplates.Template template() throws Exception {
    JSONObject m = new JSONObject().put("manifestVersion", 1).put("document", "sales-invoice")
        .put("template", new JSONObject().put("content", "<html/>").put("recipe", "chrome-pdf"))
        .put("helpers", "function f() {}")
        .put("helperPlaceholders", new JSONObject().put("thousandsSeparator", "@@T@@")
            .put("decimalSeparator", "@@D@@").put("rateDecimals", "@@R@@"))
        .put("labels", new JSONObject().put("es_ES", new JSONObject().put("title", "Factura")));
    return NeoPrintableTemplates.Template.parse("sales-invoice", m.toString());
  }

  @Test
  @DisplayName("an entity without pdf.print, or a declared key no builder fills, is not printable")
  void notPrintable() {
    SFEntity undeclared = mock(SFEntity.class);
    assertEquals(NeoPrintableRenderer.Outcome.NOT_PRINTABLE,
        renderer().renderAndStore(spec, undeclared, RECORD, "CO", "es_ES"));
    when(builder.document()).thenReturn("sales-order");
    assertEquals(NeoPrintableRenderer.Outcome.NOT_PRINTABLE,
        renderer().renderAndStore(spec, entity, RECORD, "CO", "es_ES"));
  }

  @Test
  @DisplayName("a status the builder stores nothing for (a draft) renders nothing")
  void notForStatus() throws Exception {
    assertEquals(NeoPrintableRenderer.Outcome.NOT_FOR_STATUS,
        renderer().renderAndStore(spec, entity, RECORD, "DR", "es_ES"));
    verify(templates, never()).get(anyString());
  }

  @Test
  @DisplayName("D23: a role that cannot write the document's attachments never stores")
  void readOnlyTierNeverStores() throws Exception {
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      access.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "POST")).thenReturn(false);

      assertEquals(NeoPrintableRenderer.Outcome.NOT_ALLOWED,
          renderer().renderAndStore(spec, entity, RECORD, "CO", "es_ES"));
      verify(templates, never()).get(anyString());
    }
  }

  @Test
  @DisplayName("a render is stored as the main attachment, named like the SPA's send names it")
  void storedAsMainAttachment() throws Exception {
    NeoPrintableTemplates.Template template = template();
    when(templates.get("sales-invoice")).thenReturn(template);
    when(builder.build(RECORD)).thenReturn(new JSONObject().put("documentNo", "FV/2026 001"));
    when(jsreport.render(any())).thenReturn("%PDF-1.7".getBytes());
    NeoResponse created = NeoResponse.created(new JSONObject());
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
        MockedStatic<NeoAttachmentAuthorizer> authorizer = mockStatic(NeoAttachmentAuthorizer.class);
        MockedStatic<NeoAttachmentStore> store = mockStatic(NeoAttachmentStore.class)) {
      access.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "POST")).thenReturn(true);
      authorizer.when(() -> NeoAttachmentAuthorizer.checkWrite("318")).thenReturn(null);
      store.when(() -> NeoAttachmentStore.store(any())).thenReturn(created);

      assertEquals(NeoPrintableRenderer.Outcome.STORED,
          renderer().renderAndStore(spec, entity, RECORD, "CO", "es_ES"));

      store.verify(() -> NeoAttachmentStore.store(org.mockito.ArgumentMatchers.argThat(
          upload -> "C_Invoice".equals(upload.getTableName()) && RECORD.equals(upload.getRecordId())
              && "sales-invoice-FV-2026-001.pdf".equals(upload.getFileName())
              && upload.isMarkAsMain())));
    }
  }

  @Test
  @DisplayName("the current main attachment: none or an Etendo-named PDF is replaced, a person's own file never")
  void neverReplacesAForeignMainAttachment() throws Exception {
    when(templates.get("sales-invoice")).thenReturn(template());
    when(builder.build(RECORD)).thenReturn(new JSONObject().put("documentNo", "FV/2026 001"));
    when(jsreport.render(any())).thenReturn("%PDF-1.7".getBytes());
    NeoResponse created = NeoResponse.created(new JSONObject());
    Object[][] cases = {
        // current main file name                    expected outcome                          stores?
        {null,                                       NeoPrintableRenderer.Outcome.STORED,       true},
        {"sales-invoice-FV-2026-001.pdf",            NeoPrintableRenderer.Outcome.STORED,       true},
        {"sales-invoice-" + RECORD + ".pdf",         NeoPrintableRenderer.Outcome.STORED,       true},
        {RECORD + ".pdf",                            NeoPrintableRenderer.Outcome.STORED,       true},
        {"devolucion_firmada_cliente.pdf",           NeoPrintableRenderer.Outcome.FOREIGN_MAIN, false},
        {"sales-invoice-FV-2026-002.pdf",            NeoPrintableRenderer.Outcome.FOREIGN_MAIN, false},
    };
    for (Object[] row : cases) {
      String mainName = (String) row[0];
      try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
          MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
          MockedStatic<NeoAttachmentAuthorizer> authorizer = mockStatic(NeoAttachmentAuthorizer.class);
          MockedStatic<NeoAttachmentStore> store = mockStatic(NeoAttachmentStore.class)) {
        access.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "POST")).thenReturn(true);
        store.when(() -> NeoAttachmentStore.store(any())).thenReturn(created);

        assertEquals(row[1], renderer(mainName).renderAndStore(spec, entity, RECORD, "CO", "es_ES"),
            String.valueOf(mainName));

        store.verify(() -> NeoAttachmentStore.store(any()),
            Boolean.TRUE.equals(row[2]) ? org.mockito.Mockito.times(1) : never());
      }
    }
    // a skipped document is not even rendered: jsreport ran once per stored case only
    verify(jsreport, org.mockito.Mockito.times(4)).render(any());
  }

  @Test
  @DisplayName("a failed render stores nothing and says FAILED, never throws")
  void failedRenderStoresNothing() throws Exception {
    when(templates.get("sales-invoice")).thenReturn(template());
    when(builder.build(RECORD)).thenReturn(new JSONObject());
    when(jsreport.render(any())).thenThrow(new NeoJsreportClient.RenderException("jsreport answered 502"));
    try (MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
        MockedStatic<NeoAttachmentAuthorizer> authorizer = mockStatic(NeoAttachmentAuthorizer.class);
        MockedStatic<NeoAttachmentStore> store = mockStatic(NeoAttachmentStore.class)) {
      access.when(() -> NeoAccessHelper.hasWindowAccessForSpec(spec, "POST")).thenReturn(true);

      assertEquals(NeoPrintableRenderer.Outcome.FAILED,
          renderer().renderAndStore(spec, entity, RECORD, "CO", "es_ES"));
      store.verify(() -> NeoAttachmentStore.store(any()), never());
    }
  }

  @Test
  @DisplayName("previewFileName mirrors buildPreviewFileName: safe characters, collapsed dashes, fallbacks")
  void previewFileName() {
    assertEquals("sales-invoice-FV-2026-001.pdf",
        NeoPrintableRenderer.previewFileName("sales-invoice", "FV/2026 001", RECORD));
    assertEquals("sales-invoice-" + RECORD + ".pdf",
        NeoPrintableRenderer.previewFileName("sales-invoice", null, RECORD));
    assertEquals("sales-invoice-" + RECORD + ".pdf",
        NeoPrintableRenderer.previewFileName("sales-invoice", "///", RECORD));
    assertEquals("document-A.B_1.pdf", NeoPrintableRenderer.previewFileName("  ", "A.B_1", RECORD));
  }

  @Test
  @DisplayName("the builder whose declared key matches is chosen; none matches is null")
  void builderFor() {
    assertEquals(builder, renderer().builderFor("sales-invoice"));
    assertNull(renderer().builderFor("goods-shipment"));
  }
}
