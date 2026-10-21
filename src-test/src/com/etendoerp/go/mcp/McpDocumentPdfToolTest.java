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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Properties;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoDocumentPdf;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;

/**
 * ETP-5715 — {@code etendo_get_document_pdf}: the PDF the application already stored for a document,
 * as metadata plus a short-lived signed link. The document is authorized by a mocked
 * {@link NeoAttachmentTarget} and the stored-file decision by a mocked {@link NeoDocumentPdf} (both
 * have their own tests); what is asserted here is the tier, the answer shapes, the two distinct
 * "no PDF" errors, the bulk contract and that no bytes ever travel.
 *
 * @covers com.etendoerp.go.mcp.McpDocumentPdfTool
 * @covers com.etendoerp.go.mcp.McpDocumentPdfToolDefinition
 */
@SuppressWarnings("java:S2187")
class McpDocumentPdfToolTest {

  private static final String CLIENT = "CLIENT1";
  private static final String SPEC = "sales-invoice";
  private static final String SECRET_PROP = DocumentDownloadTokenService.PROP_TOKEN_SECRET;

  private MockedStatic<OBContext> contextMock;
  private MockedStatic<McpToolRouterSupport> supportMock;
  private MockedStatic<NeoAttachmentTarget> targetMock;
  private MockedStatic<NeoDocumentPdf> pdfMock;
  private MockedStatic<OBPropertiesProvider> propsMock;
  private SFSpec spec;
  private SFEntity header;

  @BeforeEach
  void setUp() {
    System.setProperty(SECRET_PROP, "unit-test-secret");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC1");
    header = mock(SFEntity.class);
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(0L);
    when(header.getADTab()).thenReturn(tab);
    supportMock = mockStatic(McpToolRouterSupport.class);
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(SPEC)).thenReturn(spec);
    supportMock.when(() -> McpToolRouterSupport.listIncludedEntities("SPEC1"))
        .thenReturn(List.of(header));
    targetMock = mockStatic(NeoAttachmentTarget.class);
    pdfMock = mockStatic(NeoDocumentPdf.class);
    Properties properties = new Properties();
    properties.setProperty("context.url", "http://host:8080/etendo");
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(properties);
    propsMock = mockStatic(OBPropertiesProvider.class);
    propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
  }

  @AfterEach
  void tearDown() {
    System.clearProperty(SECRET_PROP);
    propsMock.close();
    pdfMock.close();
    targetMock.close();
    supportMock.close();
    contextMock.close();
  }

  /** A record the caller may read: resolves to a target, and its stored-file decision is {@code result}. */
  private NeoAttachmentTarget document(String recordId, NeoDocumentPdf.Result result) {
    NeoAttachmentTarget target = mock(NeoAttachmentTarget.class);
    when(target.getSpecName()).thenReturn(SPEC);
    when(target.getRecordId()).thenReturn(recordId);
    targetMock.when(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq(recordId), eq(false)))
        .thenReturn(target);
    pdfMock.when(() -> NeoDocumentPdf.resolve(target, header)).thenReturn(result);
    return target;
  }

  private void refuse(String recordId, int status, String message) {
    targetMock.when(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq(recordId), anyBoolean()))
        .thenThrow(new NeoAttachmentTarget.Refused(status, message));
  }

  private static JSONObject stored(String attachmentId, String name) throws Exception {
    JSONObject file = new JSONObject();
    file.put("id", attachmentId);
    file.put("name", name);
    file.put("size", 4321);
    file.put("dataType", "application/pdf");
    file.put("updatedAt", "2026-10-02T10:00:00Z");
    return file;
  }

  private static JSONObject args(String... keyValues) throws Exception {
    JSONObject args = new JSONObject();
    args.put("spec", SPEC);
    for (int i = 0; i < keyValues.length; i += 2) {
      args.put(keyValues[i], keyValues[i + 1]);
    }
    return args;
  }

  private static JSONObject argsWithIds(String... ids) throws Exception {
    JSONObject args = args();
    args.put("ids", new JSONArray(List.of(ids)));
    return args;
  }

  // ── a single document ────────────────────────────────────────────────────

  @Nested
  @DisplayName("one document")
  class SingleDocument {

    @Test
    @DisplayName("a stored PDF comes back as metadata and a signed link bound to that file")
    void returnsMetadataAndASignedLink() throws Exception {
      document("INV1", NeoDocumentPdf.Result.found(stored("F1", "INV1.pdf")));

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      assertFalse(result.has("error"), result.toString());
      assertEquals(SPEC, result.getString("spec"));
      assertEquals("INV1", result.getString("id"));
      JSONObject file = result.getJSONObject("file");
      assertEquals("INV1.pdf", file.getString("name"));
      assertEquals(4321L, file.getLong("size"));
      assertEquals("application/pdf", file.getString("type"));
      assertEquals("2026-10-02T10:00:00Z", file.getString("updated"));
      String url = result.getString("downloadUrl");
      assertTrue(url.startsWith("http://host:8080/etendo/sws/neo/document-download/"), url);
      DocumentDownloadTokenService.Claims claims = DocumentDownloadTokenService
          .validate(url.substring(url.lastIndexOf('/') + 1)).orElseThrow();
      assertEquals("F1", claims.getAttachmentId());
      assertEquals("INV1", claims.getRecordId());
      assertEquals(CLIENT, claims.getClientId());
      assertEquals(SPEC, claims.getSpecName());
      assertEquals(10, result.getInt("linkExpiresInMinutes"));
    }

    @Test
    @DisplayName("the answer never carries the file's content, only its description and a link")
    void neverCarriesBytes() throws Exception {
      document("INV1", NeoDocumentPdf.Result.found(stored("F1", "INV1.pdf")));

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      for (String forbidden : new String[] {"content", "data", "base64", "bytes", "data_base64"}) {
        assertFalse(result.has(forbidden), forbidden);
        assertFalse(result.getJSONObject("file").has(forbidden), forbidden);
      }
    }

    @Test
    @DisplayName("the document is authorized for READING, never for writing")
    void authorizesForReading() throws Exception {
      document("INV1", NeoDocumentPdf.Result.found(stored("F1", "INV1.pdf")));

      McpDocumentPdfTool.get(args("id", "INV1"));

      targetMock.verify(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq("INV1"), eq(false)));
      targetMock.verify(() -> NeoAttachmentTarget.resolve(any(), any(), any(), eq(true)), never());
    }

    @Test
    @DisplayName("an unconfigured signing secret is said so: no link, metadata kept")
    void missingSecretIsSaid() throws Exception {
      System.clearProperty(SECRET_PROP);
      document("INV1", NeoDocumentPdf.Result.found(stored("F1", "INV1.pdf")));

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      assertFalse(result.has("error"), result.toString());
      assertFalse(result.has("downloadUrl"));
      assertEquals("INV1.pdf", result.getJSONObject("file").getString("name"));
      assertTrue(result.getString("hint").contains("signing secret"), result.getString("hint"));
    }

    @Test
    @DisplayName("no PDF yet is a 409 no_pdf, apart from the 404 of a missing document")
    void noPdfYetIsAConflict() throws Exception {
      document("INV1", NeoDocumentPdf.Result.missing(false));

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      assertEquals(409, result.getInt("status"));
      assertEquals("no_pdf", result.getString("error"));
      assertEquals("This document has no PDF yet.", result.getString("detail"));
      String hint = result.getString("hint");
      assertTrue(hint.contains("document's preview in the application"), hint);
      assertTrue(hint.contains("by email"), "a return receipt's preview stores nothing: " + hint);
      assertTrue(hint.contains("draft"), "a draft never stores a PDF: " + hint);
      assertFalse(hint.contains("supplier"), "no supplier talk on a rendered document: " + hint);
    }

    @Test
    @DisplayName("a supplier-document entity with no upload says so, same 409 no_pdf, and tells how to attach it")
    void noSupplierDocument() throws Exception {
      document("PINV1", NeoDocumentPdf.Result.missing(true));

      JSONObject result = McpDocumentPdfTool.get(args("id", "PINV1"));

      assertEquals(409, result.getInt("status"));
      assertEquals("no_pdf", result.getString("error"));
      assertEquals("This document has no supplier document attached.", result.getString("detail"));
      String hint = result.getString("hint");
      assertTrue(hint.contains("etendo_request_attachment_upload"), hint);
      assertTrue(hint.contains("main:true"), hint);
      assertFalse(hint.contains("preview"), "a supplier document has no preview to open: " + hint);
    }

    @Test
    @DisplayName("a supplier-document entity with its upload returns the file and a link like any other")
    void supplierDocumentIsServed() throws Exception {
      document("PINV1", NeoDocumentPdf.Result.found(stored("F9", "segundo-documento.pdf")));

      JSONObject result = McpDocumentPdfTool.get(args("id", "PINV1"));

      assertFalse(result.has("error"), result.toString());
      assertEquals("segundo-documento.pdf", result.getJSONObject("file").getString("name"));
      assertTrue(result.getString("downloadUrl").contains("/document-download/"));
    }

    @Test
    @DisplayName("a PDF older than the document is not handed out as current: 409 pdf_stale")
    void stalePdfIsNotHandedOut() throws Exception {
      document("INV1", NeoDocumentPdf.Result.stale(stored("F1", "INV1.pdf")));

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      assertEquals(409, result.getInt("status"));
      assertEquals("pdf_stale", result.getString("error"));
      assertFalse(result.has("downloadUrl"));
      assertTrue(result.getString("hint").contains("open the document's preview in the application"),
          result.getString("hint"));
      assertTrue(result.getString("hint").contains("by email"), result.getString("hint"));
    }

    @Test
    @DisplayName("a missing or foreign record is the 404 not_found of the other tools, with no PDF talk")
    void missingOrForeignRecordIs404() throws Exception {
      refuse("NOPE", 404, NeoAttachmentTarget.MSG_RECORD_NOT_FOUND);

      JSONObject result = McpDocumentPdfTool.get(args("id", "NOPE"));

      assertEquals(404, result.getInt("status"));
      assertEquals("not_found", result.getString("error"));
      assertEquals(NeoAttachmentTarget.MSG_RECORD_NOT_FOUND, result.getString("detail"));
      pdfMock.verify(() -> NeoDocumentPdf.resolve(any(), any()), never());
    }

    @Test
    @DisplayName("a window without Attachments tab (payments, fiscal monitors) answers the 405 of the app")
    void windowsWithoutAttachmentsAreRefused() throws Exception {
      refuse("PAY1", 405, "Attachments are not available for this document type, as in the application.");

      JSONObject result = McpDocumentPdfTool.get(args("id", "PAY1"));

      assertEquals(405, result.getInt("status"));
      assertEquals("method_not_allowed", result.getString("error"));
      assertTrue(result.getString("hint").contains("PDF"), result.getString("hint"));
    }

    @Test
    @DisplayName("a role without access is a 403 forbidden that talks about reading")
    void forbiddenRole() throws Exception {
      refuse("INV1", 403, "Access denied");

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      assertEquals(403, result.getInt("status"));
      assertEquals("forbidden", result.getString("error"));
      assertFalse(result.getString("hint").contains("change"), result.getString("hint"));
    }

    @Test
    @DisplayName("a failing lookup is a 500 server_error, not a PDF verdict")
    void lookupFailureIsAServerError() throws Exception {
      document("INV1", NeoDocumentPdf.Result.failed("Internal error resolving main attachment"));

      JSONObject result = McpDocumentPdfTool.get(args("id", "INV1"));

      assertEquals(500, result.getInt("status"));
      assertEquals("server_error", result.getString("error"));
    }

    @Test
    @DisplayName("neither id nor ids is a 422 that says which to pass")
    void anIdIsRequired() throws Exception {
      JSONObject result = McpDocumentPdfTool.get(args());

      assertEquals(422, result.getInt("status"));
      assertTrue(result.getString("detail").contains("'id'"), result.getString("detail"));
    }

    @Test
    @DisplayName("id and ids together are ambiguous: 422")
    void idAndIdsAreExclusive() throws Exception {
      JSONObject both = argsWithIds("A", "B");
      both.put("id", "A");

      assertEquals(422, McpDocumentPdfTool.get(both).getInt("status"));
    }
  }

  // ── several documents ────────────────────────────────────────────────────

  @Nested
  @DisplayName("ids[] — one result per document")
  class SeveralDocuments {

    @Test
    @DisplayName("mixed ids answer one result each, in order, and one failure stops nobody")
    void mixedIdsKeepPerDocumentResults() throws Exception {
      document("OK1", NeoDocumentPdf.Result.found(stored("F1", "OK1.pdf")));
      document("STALE1", NeoDocumentPdf.Result.stale(stored("F2", "STALE1.pdf")));
      document("NONE1", NeoDocumentPdf.Result.missing(false));
      refuse("FOREIGN1", 404, NeoAttachmentTarget.MSG_RECORD_NOT_FOUND);
      document("OK2", NeoDocumentPdf.Result.found(stored("F3", "OK2.pdf")));
      document("NOSUP1", NeoDocumentPdf.Result.missing(true));

      JSONObject result = McpDocumentPdfTool.get(
          argsWithIds("OK1", "STALE1", "NONE1", "FOREIGN1", "OK2", "NOSUP1"));

      assertFalse(result.has("error"), result.toString());
      assertEquals(6, result.getInt("count"));
      assertEquals(2, result.getInt("found"));
      assertEquals(4, result.getInt("failed"));
      JSONArray results = result.getJSONArray("results");
      assertEquals("OK1", results.getJSONObject(0).getString("id"));
      assertTrue(results.getJSONObject(0).getBoolean("ok"));
      assertTrue(results.getJSONObject(0).getString("downloadUrl").contains("/document-download/"));
      assertEquals("pdf_stale", results.getJSONObject(1).getString("error"));
      assertFalse(results.getJSONObject(1).getBoolean("ok"));
      assertEquals("no_pdf", results.getJSONObject(2).getString("error"));
      assertEquals(404, results.getJSONObject(3).getInt("status"));
      assertEquals("not_found", results.getJSONObject(3).getString("error"));
      assertTrue(results.getJSONObject(4).getBoolean("ok"));
      assertEquals("OK2.pdf", results.getJSONObject(4).getJSONObject("file").getString("name"));
      assertEquals("This document has no supplier document attached.",
          results.getJSONObject(5).getString("detail"));
    }

    @Test
    @DisplayName("an unexpected failure on one document is a per-document 500 and the others still answer")
    void aCrashingDocumentDoesNotLoseTheOthers() throws Exception {
      document("OK1", NeoDocumentPdf.Result.found(stored("F1", "OK1.pdf")));
      NeoAttachmentTarget boom = document("BOOM", NeoDocumentPdf.Result.missing(false));
      pdfMock.when(() -> NeoDocumentPdf.resolve(boom, header))
          .thenThrow(new IllegalStateException("secret internal detail"));
      document("OK2", NeoDocumentPdf.Result.found(stored("F3", "OK2.pdf")));

      JSONObject result = McpDocumentPdfTool.get(argsWithIds("OK1", "BOOM", "OK2"));

      assertFalse(result.has("error"), result.toString());
      assertEquals(3, result.getInt("count"));
      assertEquals(2, result.getInt("found"));
      JSONArray results = result.getJSONArray("results");
      assertTrue(results.getJSONObject(0).getBoolean("ok"));
      JSONObject failed = results.getJSONObject(1);
      assertFalse(failed.getBoolean("ok"));
      assertEquals(500, failed.getInt("status"));
      assertEquals("server_error", failed.getString("error"));
      assertFalse(failed.toString().contains("secret internal detail"), failed.toString());
      assertTrue(results.getJSONObject(2).getBoolean("ok"));
    }

    @Test
    @DisplayName("every document is authorized on its own, for reading")
    void everyDocumentIsAuthorized() throws Exception {
      document("A", NeoDocumentPdf.Result.found(stored("F1", "A.pdf")));
      document("B", NeoDocumentPdf.Result.found(stored("F2", "B.pdf")));

      McpDocumentPdfTool.get(argsWithIds("A", "B"));

      targetMock.verify(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq("A"), eq(false)));
      targetMock.verify(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq("B"), eq(false)));
    }

    @Test
    @DisplayName("a repeated id is answered once")
    void duplicatesAreAnsweredOnce() throws Exception {
      document("A", NeoDocumentPdf.Result.found(stored("F1", "A.pdf")));

      JSONObject result = McpDocumentPdfTool.get(argsWithIds("A", "A", "A"));

      assertEquals(1, result.getInt("count"));
    }

    @Test
    @DisplayName("50 documents are accepted and 51 are refused up front, naming the cap")
    void theCapIsFifty() throws Exception {
      String[] fifty = new String[50];
      for (int i = 0; i < 50; i++) {
        fifty[i] = "D" + i;
        document(fifty[i], NeoDocumentPdf.Result.missing(false));
      }
      JSONObject accepted = McpDocumentPdfTool.get(argsWithIds(fifty));
      assertEquals(50, accepted.getInt("count"));

      String[] fiftyOne = new String[51];
      System.arraycopy(fifty, 0, fiftyOne, 0, 50);
      fiftyOne[50] = "D50";
      JSONObject refused = McpDocumentPdfTool.get(argsWithIds(fiftyOne));

      assertEquals(422, refused.getInt("status"));
      assertTrue(refused.getString("detail").contains("50"), refused.getString("detail"));
    }

    @Test
    @DisplayName("a blank entry is a per-document 422, the rest are still answered")
    void blankEntryIsAPerDocumentError() throws Exception {
      document("A", NeoDocumentPdf.Result.found(stored("F1", "A.pdf")));

      JSONObject result = McpDocumentPdfTool.get(argsWithIds("A", "  "));

      JSONArray results = result.getJSONArray("results");
      assertTrue(results.getJSONObject(0).getBoolean("ok"));
      assertEquals(422, results.getJSONObject(1).getInt("status"));
    }

    @Test
    @DisplayName("an empty ids list is a 422")
    void emptyListIsRefused() throws Exception {
      assertEquals(422, McpDocumentPdfTool.get(argsWithIds()).getInt("status"));
    }

    @Test
    @DisplayName("without a signing secret the bulk answer keeps the metadata and says why there are no links")
    void bulkWithoutSecret() throws Exception {
      System.clearProperty(SECRET_PROP);
      document("A", NeoDocumentPdf.Result.found(stored("F1", "A.pdf")));

      JSONObject result = McpDocumentPdfTool.get(argsWithIds("A"));

      assertFalse(result.getJSONArray("results").getJSONObject(0).has("downloadUrl"));
      assertTrue(result.getString("hint").contains("signing secret"), result.getString("hint"));
    }
  }

  // ── the tool definition ──────────────────────────────────────────────────

  @Test
  @DisplayName("the definition takes spec and id or ids, and says it never generates a PDF")
  void definitionDescribesTheContract() {
    McpToolDefinition definition = McpDocumentPdfToolDefinition.definition(List.of(SPEC));

    assertEquals(McpConstants.TOOL_NEO_GET_DOCUMENT_PDF, definition.getName());
    String description = definition.getDescription();
    assertTrue(description.contains("PDF"), description);
    assertTrue(description.contains("never generates"), description);
    assertTrue(description.contains("supplier"), description);
    Object props = definition.getInputSchema().get(McpConstants.KEY_PROPERTIES);
    assertTrue(props.toString().contains("ids"), props.toString());
    assertTrue(props.toString().contains("spec"), props.toString());
  }
}
