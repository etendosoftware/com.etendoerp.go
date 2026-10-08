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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;
import java.util.zip.ZipInputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementation;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Unit tests for {@link NeoAttachmentsHelper}.
 *
 * <p>Covers the deterministic helpers (file-name sanitization, content-disposition
 * formatting, date formatting, temp-file cleanup) and the endpoint handlers. The
 * handlers run without a live DAL/CDI container: {@code OBDal}, {@code ModelProvider},
 * {@code OBContext} and {@code WeldUtils} are replaced with Mockito static mocks.</p>
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentsHelper
 */
public class NeoAttachmentsHelperTest {

  @After
  public void clearCacheAfterEachTest() {
    NeoAttachmentsHelper.clearTableIdCache();
  }

  private static StringWriter stubWriter(HttpServletResponse response) throws Exception {
    StringWriter sink = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(sink));
    return sink;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void stubTableLookup(OBDal dal, String tableId) {
    Session session = mock(Session.class);
    NativeQuery query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(Collections.singletonList(tableId));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void stubUnknownTableLookup(OBDal dal) {
    Session session = mock(Session.class);
    NativeQuery query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());
  }

  /**
   * Stubs both the table-id resolution query (matches SQL containing
   * {@code "ad_table"}) and the main-attachment-ids lookup (matches SQL
   * containing {@code "C_File"}), returning distinct results for each so
   * tests can assert exclusion behavior precisely.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static NativeQuery stubTableAndMainLookup(OBDal dal, String tableId,
      String... mainAttachmentIds) {
    Session session = mock(Session.class);
    NativeQuery tableQuery = mock(NativeQuery.class);
    NativeQuery mainQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("ad_table"))))
        .thenReturn(tableQuery);
    when(tableQuery.setParameter(anyString(), any())).thenReturn(tableQuery);
    when(tableQuery.list()).thenReturn(Collections.singletonList(tableId));
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("C_File"))))
        .thenReturn(mainQuery);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(Arrays.asList(mainAttachmentIds));
    return mainQuery;
  }

  /**
   * Wires {@code response.getOutputStream()} to this test's {@link #captured} sink so the
   * zip bytes a handler streams can be read back and inspected.
   *
   * @param response the mocked response to wire
   * @throws Exception when the mock cannot be stubbed
   */
  private void stubOutputStream(HttpServletResponse response) throws Exception {
    final java.io.ByteArrayOutputStream sink = captured;
    javax.servlet.ServletOutputStream out = new javax.servlet.ServletOutputStream() {
      @Override public boolean isReady() { return true; }
      @Override public void setWriteListener(javax.servlet.WriteListener l) {
        // Sync-only test double: these tests never use the async servlet API.
      }
      @Override public void write(int b) { sink.write(b); }
    };
    when(response.getOutputStream()).thenReturn(out);
  }

  /**
   * Reads back every entry name of the zip this test's {@link #captured} sink holds.
   *
   * @return the entry names, in no particular order
   * @throws Exception when the captured bytes are not a readable zip
   */
  private java.util.Set<String> capturedZipEntryNames() throws Exception {
    java.util.Set<String> names = new java.util.HashSet<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(captured.toByteArray()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  private static Object invokePrivateStatic(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
    Method method = NeoAttachmentsHelper.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  /**
   * Verifies that path prefixes are removed from submitted names.
   */
  @Test
  public void sanitizeFileNameRemovesPathPrefixes() throws Exception {
    String sanitized = (String) invokePrivateStatic("sanitizeFileName", new Class<?>[]{ String.class },
        "C:\\tmp\\invoice.pdf");
    assertEquals("invoice.pdf", sanitized);
  }

  /**
   * Verifies that empty/blank names fall back to the default attachment name.
   */
  @Test
  public void sanitizeFileNameFallsBackWhenOnlyWhitespace() throws Exception {
    String sanitized = (String) invokePrivateStatic("sanitizeFileName", new Class<?>[]{ String.class }, "   ");
    assertEquals("attachment", sanitized);
  }

  /**
   * Verifies default binary content type fallback when metadata is blank.
   */
  @Test
  public void resolveContentTypeReturnsDefaultForBlankValues() throws Exception {
    String fromNull = (String) invokePrivateStatic("resolveContentType", new Class<?>[]{ String.class }, (Object) null);
    String fromBlank = (String) invokePrivateStatic("resolveContentType", new Class<?>[]{ String.class }, "   ");
    String fromValue = (String) invokePrivateStatic("resolveContentType", new Class<?>[]{ String.class }, "text/plain");

    assertEquals("application/octet-stream", fromNull);
    assertEquals("application/octet-stream", fromBlank);
    assertEquals("text/plain", fromValue);
  }

  /**
   * Verifies that content disposition includes both ASCII and RFC 5987 entries.
   */
  @Test
  public void buildContentDispositionIncludesAsciiAndUtf8Filename() throws Exception {
    String disposition = (String) invokePrivateStatic("buildContentDisposition", new Class<?>[]{ String.class },
        "invoice 2026.pdf");

    assertTrue(disposition.contains("filename=\"invoice 2026.pdf\""));
    assertTrue(disposition.contains("filename*=UTF-8''invoice%202026.pdf"));
  }

  /**
   * Verifies that dangerous quotes in file names are normalized.
   */
  @Test
  public void buildContentDispositionReplacesQuotesInFilename() throws Exception {
    String disposition = (String) invokePrivateStatic("buildContentDisposition", new Class<?>[]{ String.class },
        "in\"voice\".pdf");

    assertTrue(disposition.contains("filename=\"in_voice_.pdf\""));
  }

  /**
   * Verifies that dates are formatted in UTC ISO-8601 format.
   */
  @Test
  public void formatDateReturnsUtcIsoString() throws Exception {
    Object formatted = invokePrivateStatic("formatDate", new Class<?>[]{ Date.class }, new Date(0L));
    assertEquals("1970-01-01T00:00:00Z", formatted);
  }

  /**
   * Verifies that null dates are represented as JSON null.
   */
  @Test
  public void formatDateReturnsJsonNullForNullInput() throws Exception {
    Object formatted = invokePrivateStatic("formatDate", new Class<?>[]{ Date.class }, (Object) null);
    assertSame(JSONObject.NULL, formatted);
  }

  /**
   * Verifies that submitted file name takes precedence over header fallback.
   */
  @Test
  public void resolveFileNameUsesSubmittedNameFirst() throws Exception {
    Part part = mock(Part.class);
    when(part.getSubmittedFileName()).thenReturn("/home/user/orders/order-1.pdf");

    String resolved = (String) invokePrivateStatic("resolveFileName", new Class<?>[]{ Part.class }, part);
    assertEquals("order-1.pdf", resolved);
  }

  /**
   * Verifies filename extraction from Content-Disposition when submitted name is missing.
   */
  @Test
  public void resolveFileNameFallsBackToHeaderFilename() throws Exception {
    Part part = mock(Part.class);
    when(part.getSubmittedFileName()).thenReturn(null);
    when(part.getHeader("Content-Disposition")).thenReturn(
        "form-data; name=\"file\"; filename=\"dir/report final.pdf\"");

    String resolved = (String) invokePrivateStatic("resolveFileName", new Class<?>[]{ Part.class }, part);
    assertEquals("report final.pdf", resolved);
  }

  /**
   * Verifies default filename when both submitted name and header are missing.
   */
  @Test
  public void resolveFileNameFallsBackToAttachmentWhenMissing() throws Exception {
    Part part = mock(Part.class);
    when(part.getSubmittedFileName()).thenReturn(null);
    when(part.getHeader("Content-Disposition")).thenReturn(null);

    String resolved = (String) invokePrivateStatic("resolveFileName", new Class<?>[]{ Part.class }, part);
    assertEquals("attachment", resolved);
  }

  /**
   * Verifies that temporary files are removed after upload handling.
   */
  @Test
  public void cleanupTempFileDeletesExistingTempFile() throws Exception {
    File tempFile = File.createTempFile("neo-attachments-test", ".tmp");
    assertTrue(tempFile.exists());

    invokePrivateStatic("cleanupTempFile", new Class<?>[]{ File.class }, tempFile);

    assertFalse(tempFile.exists());
  }

  /**
   * Verifies cleanup is safe for null or non-existing files.
   */
  @Test
  public void cleanupTempFileIgnoresNullAndMissingFile() throws Exception {
    invokePrivateStatic("cleanupTempFile", new Class<?>[]{ File.class }, (Object) null);

    File missing = new File(System.getProperty("java.io.tmpdir"), "neo-attachments-test-missing-file.tmp");
    if (missing.exists()) {
      missing.delete();
    }
    invokePrivateStatic("cleanupTempFile", new Class<?>[]{ File.class }, missing);

    assertFalse(missing.exists());
  }

  /**
   * Verifies list endpoint validation for blank table/record values.
   */
  @Test
  public void handleListRejectsBlankInputs() throws Exception {
    NeoResponse response = NeoAttachmentsHelper.handleList(" ", null);
    assertEquals(400, response.getHttpStatus());
    assertEquals("tableName and recordId are required", errorMessage(response));
  }

  /**
   * Verifies list endpoint maps unknown-table errors to HTTP 404.
   */
  @Test
  public void handleListReturnsNotFoundWhenTableCannotBeResolved() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubUnknownTableLookup(dal);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleList("C_Order", "REC1");

      assertEquals(404, response.getHttpStatus());
      assertEquals("Unknown table: C_Order", errorMessage(response));
    }
  }

  /**
   * Verifies list endpoint returns projected attachment items.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleListReturnsItemsWhenAttachmentExists() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubTableLookup(dal, "TABLE1");

    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);

    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn("ATT1");
    when(attachment.getName()).thenReturn("invoice.pdf");
    when(attachment.getPath()).thenReturn(null);
    when(attachment.getDataType()).thenReturn("application/pdf");
    when(attachment.getText()).thenReturn("Invoice");
    when(attachment.getCreationDate()).thenReturn(new Date(0L));
    when(attachment.getUpdated()).thenReturn(new Date(0L));
    when(attachment.getCreatedBy()).thenReturn(null);
    when(criteria.list()).thenReturn(Collections.singletonList(attachment));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleList("C_Order", "REC1");

      assertEquals(200, response.getHttpStatus());
      assertEquals(1, response.getBody().getJSONArray("items").length());
      assertEquals("ATT1", response.getBody().getJSONArray("items").getJSONObject(0).getString("id"));
    }
  }

  /**
   * Verifies the attachment marked as the record's main document is included
   * in the generic "Adjuntos" list alongside every other attachment — the
   * Attachments tab and the sidebar/preview must show the same underlying
   * files (ETP-4855 Error 4: a file attached from the preview must also be
   * visible in the Attachments tab).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleListIncludesAttachmentMarkedAsMain() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT-MAIN");

    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);

    Attachment mainAttachment = stubAttachment("ATT-MAIN", "supplier-invoice.pdf");
    Attachment regularAttachment = stubAttachment("ATT-OTHER", "note.pdf");
    when(criteria.list()).thenReturn(Arrays.asList(mainAttachment, regularAttachment));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleList("C_Order", "REC1");

      assertEquals(200, response.getHttpStatus());
      assertEquals(2, response.getBody().getJSONArray("items").length());
      assertEquals("ATT-MAIN", response.getBody().getJSONArray("items").getJSONObject(0).getString("id"));
      assertEquals("ATT-OTHER", response.getBody().getJSONArray("items").getJSONObject(1).getString("id"));
    }
  }

  /**
   * ETP-5526 — the count endpoint validates its inputs exactly like the list, before any DB access.
   */
  @Test
  public void handleCountRejectsBlankInputs() throws Exception {
    String[][] cases = { { " ", "REC1" }, { "C_Order", null }, { null, "" } };
    for (String[] c : cases) {
      NeoResponse response = NeoAttachmentsHelper.handleCount(c[0], c[1]);
      assertEquals(c[0] + "/" + c[1], 400, response.getHttpStatus());
      assertEquals("tableName and recordId are required", errorMessage(response));
    }
  }

  /**
   * ETP-5526 — an unknown table maps to 404, like the list.
   */
  @Test
  public void handleCountReturnsNotFoundWhenTableCannotBeResolved() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubUnknownTableLookup(dal);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleCount("C_Order", "REC1");

      assertEquals(404, response.getHttpStatus());
      assertEquals("Unknown table: C_Order", errorMessage(response));
    }
  }

  /**
   * ETP-5526 — any other failure while counting maps to a generic 500.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleCountReturnsInternalErrorOnUnexpectedFailure() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubTableLookup(dal, "TABLE1");
    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);
    when(criteria.count()).thenThrow(new RuntimeException("db down"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleCount("C_Order", "REC1");

      assertEquals(500, response.getHttpStatus());
      assertEquals("Internal error counting attachments", errorMessage(response));
    }
  }

  /**
   * ETP-5526 — success returns {count: N} from a COUNT query (the attachments are never
   * loaded) with the organization filter off, as the list does, so N equals the list length.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleCountReturnsCountWithoutLoadingAttachments() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubTableLookup(dal, "TABLE1");
    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);
    when(criteria.count()).thenReturn(3);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleCount("C_Order", "REC1");

      assertEquals(200, response.getHttpStatus());
      assertEquals(3, response.getBody().getInt("count"));
      verify(criteria).setFilterOnReadableOrganization(false);
      verify(criteria).count();
      verify(criteria, never()).list();
    }
  }

  private static Attachment stubAttachment(String id, String name) {
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn(id);
    when(attachment.getName()).thenReturn(name);
    when(attachment.getPath()).thenReturn(null);
    when(attachment.getDataType()).thenReturn("application/pdf");
    when(attachment.getText()).thenReturn(null);
    when(attachment.getCreationDate()).thenReturn(new Date(0L));
    when(attachment.getUpdated()).thenReturn(new Date(0L));
    when(attachment.getCreatedBy()).thenReturn(null);
    return attachment;
  }

  /**
   * Verifies upload endpoint validation for blank table/record identifiers.
   */
  @Test
  public void handleUploadRejectsBlankInputs() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);

    NeoResponse response = NeoAttachmentsHelper.handleUpload("", " ", request, false);

    assertEquals(400, response.getHttpStatus());
    assertEquals("tableName and recordId are required", errorMessage(response));
  }

  /**
   * Verifies upload endpoint validation for non-multipart requests.
   */
  @Test
  public void handleUploadRejectsNonMultipartRequest() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/json");

    NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

    assertEquals(400, response.getHttpStatus());
    assertEquals("Expected multipart/form-data", errorMessage(response));
  }

  /**
   * Verifies upload endpoint validation for missing file part.
   */
  @Test
  public void handleUploadRejectsMissingFilePart() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("file")).thenReturn(null);

    NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

    assertEquals(400, response.getHttpStatus());
    assertEquals("Missing 'file' part", errorMessage(response));
  }

  /**
   * Verifies upload endpoint returns bad request when no standard tab is available.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleUploadReturnsBadRequestWhenNoStandardTabFound() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Part part = mock(Part.class);
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> tabCriteria = mock(OBCriteria.class);

    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("file")).thenReturn(part);
    when(request.getParameter("tabId")).thenReturn(null);
    // ETP-5038 moved the size/type policy AHEAD of tab resolution, so a bare Part mock (no
    // submitted name, no headers) is now rejected earlier with "File type not allowed" and the
    // tab path below is never reached. Still a 400, which is why only the message assertion
    // caught it. Give the part an allowed name so the policy passes and this test keeps
    // exercising what it is about.
    when(part.getSubmittedFileName()).thenReturn("order-1.pdf");
    stubTableLookup(dal, "TABLE1");
    when(dal.createCriteria(Tab.class)).thenReturn(tabCriteria);
    when(tabCriteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

      assertEquals(400, response.getHttpStatus());
      assertTrue(errorMessage(response).contains("Could not resolve a standard tab"));
    }
  }


  /**
   * Verifies single-download endpoint validation for blank attachment id.
   */
  @Test
  public void handleDownloadRejectsBlankAttachmentId() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    NeoAttachmentsHelper.handleDownload(" ", response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(sink.toString().contains("attachmentId is required"));
  }

  /**
   * Verifies single-download endpoint returns 404 when attachment is missing.
   */
  @Test
  public void handleDownloadReturnsNotFoundWhenAttachmentMissing() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoAttachmentsHelper.handleDownload("ATT1", response);

      verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
      assertTrue(sink.toString().contains("Attachment not found"));
    }
  }


  /**
   * Verifies bulk-download endpoint validation for blank inputs.
   */
  @Test
  public void handleDownloadAllRejectsBlankInputs() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    NeoAttachmentsHelper.handleDownloadAll("", " ", response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(sink.toString().contains("tableName and recordId are required"));
  }


  /**
   * Verifies the zip built by bulk-download includes whichever attachment is
   * marked as the record's main document alongside every other attachment —
   * omitting it would silently download fewer files than the Attachments
   * tab's own list shows (ETP-4855 Error 4).
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleDownloadAllIncludesAttachmentMarkedAsMain() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    stubOutputStream(response);
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT-MAIN");

    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);
    Attachment mainAttachment = stubAttachment("ATT-MAIN", "supplier-invoice.pdf");
    Attachment regularAttachment = stubAttachment("ATT-OTHER", "note.pdf");
    when(criteria.list()).thenReturn(Arrays.asList(mainAttachment, regularAttachment));

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoAttachmentsHelper.handleDownloadAll("C_Order", "REC1", response);

      verify(aim, times(1)).download(eq("ATT-OTHER"), any());
      verify(aim, times(1)).download(eq("ATT-MAIN"), any());
      verify(response).setStatus(HttpServletResponse.SC_OK);

      assertEquals(
          new java.util.HashSet<>(Arrays.asList("supplier-invoice.pdf", "note.pdf")),
          capturedZipEntryNames());
    }
  }

  private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();

  /**
   * ETP-5526 — the zip endpoint accepts an optional {@code ids} subset so the
   * Attachments tab's selection bar can download only the ticked rows. Verifies the
   * happy path: exactly the requested attachment is fetched and zipped, and the one
   * that was not asked for is never even read from storage.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleDownloadAllWithIdsZipsOnlyTheRequestedSubset() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    stubOutputStream(response);
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1");

    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);
    Attachment wanted = stubAttachment("ATT-B", "delivery-note.pdf");
    Attachment other = stubAttachment("ATT-A", "purchase-order.pdf");
    when(criteria.list()).thenReturn(Arrays.asList(other, wanted));

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoAttachmentsHelper.handleDownloadAll("C_Order", "REC1", "ATT-B", response);

      verify(aim, times(1)).download(eq("ATT-B"), any());
      verify(aim, never()).download(eq("ATT-A"), any());
      verify(response).setStatus(HttpServletResponse.SC_OK);
      assertEquals(Collections.singleton("delivery-note.pdf"), capturedZipEntryNames());
    }
  }

  /**
   * ETP-5526 — the authorization half of the same feature: an id the caller supplied is
   * only served when it actually belongs to the record in the URL. A foreign (or
   * non-existent) id answers 404 and NOTHING is streamed — no partial archive, no
   * single-file leak. Without this the endpoint would read any attachment in the
   * instance from any record's URL.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleDownloadAllRejectsAnIdThatDoesNotBelongToTheRecord() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1");

    OBCriteria<Attachment> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Attachment.class)).thenReturn(criteria);
    Attachment own = stubAttachment("ATT-A", "purchase-order.pdf");
    when(criteria.list()).thenReturn(Collections.singletonList(own));

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoAttachmentsHelper.handleDownloadAll("C_Order", "REC1", "ATT-A,ATT-SOMEONE-ELSE", response);

      verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
      assertTrue(sink.toString().contains("Attachment not found"));
      // Not even the legitimately-owned id is served: the request is refused whole.
      verify(aim, never()).download(anyString(), any());
      verify(response, never()).setStatus(HttpServletResponse.SC_OK);
    }
  }

  /**
   * ETP-5526 — supplying {@code ids} with nothing usable in it is a client bug, so it
   * answers 400 rather than falling back to "every attachment of the record": silently
   * zipping everything would turn an empty selection into a surprise bulk download.
   */
  @Test
  public void handleDownloadAllRejectsAnEmptyIdsParameter() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    NeoAttachmentsHelper.handleDownloadAll("C_Order", "REC1", " , ", response);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertTrue(sink.toString().contains("ids must name at least one attachment"));
  }


  /**
   * Verifies delete endpoint validation for blank attachment id.
   */
  @Test
  public void handleDeleteRejectsBlankAttachmentId() throws Exception {
    NeoResponse response = NeoAttachmentsHelper.handleDelete(" ");
    assertEquals(400, response.getHttpStatus());
    assertEquals("attachmentId is required", errorMessage(response));
  }

  /**
   * Verifies delete endpoint returns 404 when attachment does not exist.
   */
  @Test
  public void handleDeleteReturnsNotFoundWhenAttachmentMissing() throws Exception {
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(404, response.getHttpStatus());
      assertEquals("Attachment not found", errorMessage(response));
    }
  }

  /**
   * Verifies deletes of attachments belonging to any table OTHER than a fiscal declaration are
   * completely unaffected by the ETP-5432 guard — the very first check
   * ({@code table.getDBTableName()} mismatch) short-circuits before any DAL lookup of a
   * declaration happens at all.
   */
  @Test
  public void handleDeleteAllowsAttachmentOfNonFiscalDeclTable() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("C_Invoice");
    when(attachment.getTable()).thenReturn(table);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(204, response.getHttpStatus());
      verify(aim).delete(attachment);
    }
  }

  /**
   * Verifies deletes of an attachment with no owning table at all (never wired to any AD_Table)
   * are unaffected — {@code table == null} short-circuits the guard the same way a table
   * mismatch does.
   */
  @Test
  public void handleDeleteAllowsAttachmentWithNoTable() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    when(attachment.getTable()).thenReturn(null);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(204, response.getHttpStatus());
      verify(aim).delete(attachment);
    }
  }

  /**
   * ETP-5432: verifies the delete is REJECTED (409) when the attachment belongs to a fiscal
   * declaration whose {@code declarationStatus} is anything other than draft — the live scenario
   * the guard was written for (a justificante of an already-presented declaration).
   */
  @Test
  public void handleDeleteRejectsAttachmentOfNonDraftFiscalDecl() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn("DECL1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn("presented");
    when(dal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "DECL1")).thenReturn(decl);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(409, response.getHttpStatus());
      assertEquals(
          "Cannot delete an attachment of a fiscal declaration that is not in draft status: DECL1",
          errorMessage(response));
    }
  }

  /**
   * ETP-5432: verifies the delete PROCEEDS when the owning fiscal declaration is still a draft —
   * the guard must not block the ordinary case of removing a justificante before presentation.
   */
  @Test
  public void handleDeleteAllowsAttachmentOfDraftFiscalDecl() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn("DECL1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS))
        .thenReturn(FiscalDeclCrudHandler.DEFAULT_STATUS);
    when(dal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "DECL1")).thenReturn(decl);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(204, response.getHttpStatus());
      verify(aim).delete(attachment);
    }
  }

  /**
   * ETP-5432 edge case: the owning declaration record id is blank (should not normally happen,
   * but {@code Attachment.getRecord()} is a free-text AD_Record_ID) — the guard must not attempt
   * a DAL lookup with a blank id and must let the delete proceed.
   */
  @Test
  public void handleDeleteAllowsAttachmentWhenRecordIdIsBlank() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn(" ");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(204, response.getHttpStatus());
      verify(aim).delete(attachment);
      verify(dal, never()).get(eq(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL), any());
    }
  }

  /**
   * ETP-5432 edge case: the owning declaration record no longer exists (already deleted) — the
   * guard has nothing left to enforce, so the attachment delete must still proceed.
   */
  @Test
  public void handleDeleteAllowsAttachmentWhenOwningDeclNotFound() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn("DECL1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);
    when(dal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "DECL1")).thenReturn(null);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(204, response.getHttpStatus());
      verify(aim).delete(attachment);
    }
  }

  /**
   * ETP-5432 edge case: the owning declaration exists but has no {@code declarationStatus} value
   * at all (blank) — the guard's {@code StringUtils.isNotBlank(statusStr)} check must treat a
   * blank status the same as "no status recorded" and let the delete proceed, rather than
   * defaulting to "not draft" and blocking a perfectly deletable attachment.
   */
  @Test
  public void handleDeleteAllowsAttachmentWhenDeclStatusIsBlank() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn("DECL1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    BaseOBObject decl = mock(BaseOBObject.class);
    when(decl.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_STATUS)).thenReturn(null);
    when(dal.get(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL, "DECL1")).thenReturn(decl);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleDelete("ATT1");

      assertEquals(204, response.getHttpStatus());
      verify(aim).delete(attachment);
    }
  }

  /**
   * Verifies main-lookup validation for blank table/record identifiers.
   */
  @Test
  public void handleGetMainRejectsBlankInputs() throws Exception {
    NeoResponse response = NeoAttachmentsHelper.handleGetMain(" ", null);
    assertEquals(400, response.getHttpStatus());
    assertEquals("tableName and recordId are required", errorMessage(response));
  }

  /**
   * Verifies main-lookup returns an empty object when no attachment is marked.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleGetMainReturnsEmptyObjectWhenNoneMarked() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleGetMain("C_Invoice", "REC1");

      assertEquals(200, response.getHttpStatus());
      assertEquals(0, response.getBody().length());
    }
  }

  /**
   * Verifies main-lookup returns the marked attachment's projection when one exists.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleGetMainReturnsMarkedAttachment() throws Exception {
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT-MAIN");
    Attachment mainAttachment = stubAttachment("ATT-MAIN", "supplier-invoice.pdf");
    when(dal.get(Attachment.class, "ATT-MAIN")).thenReturn(mainAttachment);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleGetMain("C_Invoice", "REC1");

      assertEquals(200, response.getHttpStatus());
      assertEquals("ATT-MAIN", response.getBody().getString("id"));
      assertEquals("supplier-invoice.pdf", response.getBody().getString("name"));
    }
  }

  /**
   * Verifies mark-main validation for blank attachment id.
   */
  @Test
  public void handleMarkMainRejectsBlankAttachmentId() throws Exception {
    NeoResponse response = NeoAttachmentsHelper.handleMarkMain(" ", true);
    assertEquals(400, response.getHttpStatus());
    assertEquals("attachmentId is required", errorMessage(response));
  }

  /**
   * Verifies mark-main returns 404 when the target attachment does not exist.
   */
  @Test
  public void handleMarkMainReturnsNotFoundWhenAttachmentMissing() throws Exception {
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleMarkMain("ATT1", true);

      assertEquals(404, response.getHttpStatus());
      assertEquals("Attachment not found", errorMessage(response));
    }
  }

  /**
   * Verifies marking a new attachment as main deletes the previously-marked one
   * (in the same transaction) and sets the flag on the new one — at most one
   * main attachment per (table, record) at any time.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleMarkMainDeletesPreviousHolderThenMarksNewOne() throws Exception {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery mainQuery = mock(NativeQuery.class);
    NativeQuery updateQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("SELECT"))))
        .thenReturn(mainQuery);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(Collections.singletonList("ATT-OLD"));
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("UPDATE"))))
        .thenReturn(updateQuery);
    when(updateQuery.setParameter(anyString(), any())).thenReturn(updateQuery);
    when(updateQuery.executeUpdate()).thenReturn(1);

    Attachment newAttachment = mock(Attachment.class);
    when(newAttachment.getId()).thenReturn("ATT-NEW");
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("TABLE1");
    when(newAttachment.getTable()).thenReturn(table);
    when(newAttachment.getRecord()).thenReturn("REC1");
    when(dal.get(Attachment.class, "ATT-NEW")).thenReturn(newAttachment);

    Attachment oldAttachment = mock(Attachment.class);
    when(oldAttachment.getId()).thenReturn("ATT-OLD");
    when(dal.get(Attachment.class, "ATT-OLD")).thenReturn(oldAttachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleMarkMain("ATT-NEW", true);

      assertEquals(200, response.getHttpStatus());
      assertEquals("ATT-NEW", response.getBody().getString("id"));
      assertTrue(response.getBody().getBoolean("isMain"));
      verify(aim).delete(oldAttachment);
      verify(updateQuery).setParameter("flag", "Y");
      verify(updateQuery).setParameter("id", "ATT-NEW");
    }
  }

  /**
   * Verifies unmarking just clears the flag — no delete is ever triggered.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleMarkMainUnmarkClearsFlagWithoutDeleting() throws Exception {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery updateQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(updateQuery);
    when(updateQuery.setParameter(anyString(), any())).thenReturn(updateQuery);
    when(updateQuery.executeUpdate()).thenReturn(1);

    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn("ATT1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleMarkMain("ATT1", false);

      assertEquals(200, response.getHttpStatus());
      assertFalse(response.getBody().getBoolean("isMain"));
      verify(aim, never()).delete(any());
      verify(updateQuery).setParameter("flag", "N");
    }
  }

  /**
   * Verifies update-description endpoint success flow.
   */
  @Test
  public void handleUpdateDescriptionPersistsAndReturnsBodyOnSuccess() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);
    when(attachment.getId()).thenReturn("ATT1");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleUpdateDescription("ATT1", null);

      assertEquals(200, response.getHttpStatus());
      assertEquals("ATT1", response.getBody().getString("id"));
      assertTrue(response.getBody().isNull("description"));
      verify(attachment).setText(null);
      verify(dal).save(attachment);
      verify(dal).flush();
    }
  }

  /**
   * Verifies update-description endpoint validation for blank attachment id.
   */
  @Test
  public void handleUpdateDescriptionRejectsBlankAttachmentId() throws Exception {
    NeoResponse response = NeoAttachmentsHelper.handleUpdateDescription(" ", "text");
    assertEquals(400, response.getHttpStatus());
    assertEquals("attachmentId is required", errorMessage(response));
  }

  /**
   * Verifies update-description endpoint returns 404 when attachment is missing.
   */
  @Test
  public void handleUpdateDescriptionReturnsNotFoundWhenAttachmentMissing() throws Exception {
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleUpdateDescription("ATT1", "desc");

      assertEquals(404, response.getHttpStatus());
      assertEquals("Attachment not found", errorMessage(response));
    }
  }

  /**
   * Verifies update-description endpoint rolls back when persistence fails.
   */
  @Test
  public void handleUpdateDescriptionReturnsServerErrorWhenPersistenceFails() throws Exception {
    OBDal dal = mock(OBDal.class);
    Attachment attachment = mock(Attachment.class);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);
    doThrow(new RuntimeException("save boom")).when(dal).save(attachment);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleUpdateDescription("ATT1", "desc");

      assertEquals(500, response.getHttpStatus());
      assertEquals("Internal error updating description", errorMessage(response));
      verify(dal).rollbackAndClose();
    }
  }

  /**
   * Verifies table-id cache behavior in table resolution helper.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTableIdUsesCacheAfterFirstLookup() throws Exception {
    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(Collections.singletonList("TABLE1"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String first = (String) invokePrivateStatic("resolveTableId", new Class<?>[]{ String.class }, "C_Order");
      String second = (String) invokePrivateStatic("resolveTableId", new Class<?>[]{ String.class }, "c_order");

      assertEquals("TABLE1", first);
      assertEquals("TABLE1", second);
      verify(session, times(1)).createNativeQuery(anyString());
    }
  }

  /**
   * Verifies tab-id hint bypasses criteria lookup helper logic.
   */
  @Test
  public void resolveTabIdReturnsHintWithoutLookup() throws Exception {
    String tabId = (String) invokePrivateStatic("resolveTabId", new Class<?>[]{ String.class, String.class },
        "TABLE1", "TAB_HINT");
    assertEquals("TAB_HINT", tabId);
  }

  /**
   * Regression guard: a table that genuinely has a {@code STD} tab (e.g.
   * {@code C_Invoice}, {@code TBAI_SyncInvoice}) must keep resolving to it,
   * exactly as before this fix, without falling back or issuing a second query.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTabIdPrefersStdTabWhenAvailable() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> stdCriteria = mock(OBCriteria.class);
    Tab stdTab = mock(Tab.class);
    when(stdTab.getId()).thenReturn("STD_TAB");
    when(dal.createCriteria(Tab.class)).thenReturn(stdCriteria);
    when(stdCriteria.list()).thenReturn(Collections.singletonList(stdTab));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String tabId = NeoAttachmentsHelper.resolveTabId("TABLE1", null);

      assertEquals("STD_TAB", tabId);
      verify(dal, times(1)).createCriteria(Tab.class);
    }
  }

  /**
   * Covers the reported bug: a table whose only {@code AD_Tab} rows are non-STD
   * (e.g. {@code RO}) read-only sub-tabs — such as {@code etvfac_c_invoice_verifactu}
   * or {@code aeatsii_facturas} — must now resolve to one of them instead of {@code null}.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTabIdFallsBackToAnyActiveTabWhenNoStdTabExists() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> stdCriteria = mock(OBCriteria.class);
    OBCriteria<Tab> anyCriteria = mock(OBCriteria.class);
    Tab roTab = mock(Tab.class);
    when(roTab.getId()).thenReturn("RO_TAB");
    when(dal.createCriteria(Tab.class)).thenReturn(stdCriteria, anyCriteria);
    when(stdCriteria.list()).thenReturn(Collections.emptyList());
    when(anyCriteria.list()).thenReturn(Collections.singletonList(roTab));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String tabId = NeoAttachmentsHelper.resolveTabId("TABLE1", null);

      assertEquals("RO_TAB", tabId);
      verify(dal, times(2)).createCriteria(Tab.class);
    }
  }

  /**
   * Degenerate case: a table with literally zero active tabs must still return
   * {@code null} (not throw, not loop) after both the STD-only and the fallback
   * queries come back empty.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTabIdReturnsNullWhenTableHasNoActiveTabsAtAll() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> stdCriteria = mock(OBCriteria.class);
    OBCriteria<Tab> anyCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(Tab.class)).thenReturn(stdCriteria, anyCriteria);
    when(stdCriteria.list()).thenReturn(Collections.emptyList());
    when(anyCriteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String tabId = NeoAttachmentsHelper.resolveTabId("TABLE1", null);

      assertNull(tabId);
      verify(dal, times(2)).createCriteria(Tab.class);
    }
  }

  /**
   * Edge case: a table with MULTIPLE active non-{@code STD} tabs at different
   * {@code tabLevel}/{@code sequenceNumber} combinations (e.g. {@code aeatsii_facturas},
   * which has 6 {@code RO} tabs at different levels). The fallback query must request
   * the exact same deterministic ordering as the {@code STD}-only query (lowest
   * {@code tabLevel}, then lowest {@code sequenceNumber}) and the same single-row cap,
   * so the "first" tab picked is never left to whatever order Postgres/the mock
   * happens to hand back.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTabIdFallbackOrdersByTabLevelThenSequenceNumber() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> stdCriteria = mock(OBCriteria.class);
    OBCriteria<Tab> anyCriteria = mock(OBCriteria.class);
    Tab winner = mock(Tab.class);
    when(winner.getId()).thenReturn("RO_TAB_LEVEL0_SEQ10");
    when(dal.createCriteria(Tab.class)).thenReturn(stdCriteria, anyCriteria);
    when(stdCriteria.list()).thenReturn(Collections.emptyList());
    // Represents the DB already applying the requested order (lowest tabLevel,
    // then lowest sequenceNumber) with setMaxResults(1) trimming to one row —
    // out of the 6 RO tabs a table like aeatsii_facturas would actually have.
    when(anyCriteria.list()).thenReturn(Collections.singletonList(winner));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String tabId = NeoAttachmentsHelper.resolveTabId("TABLE1", null);

      assertEquals("RO_TAB_LEVEL0_SEQ10", tabId);
      InOrder inOrder = Mockito.inOrder(anyCriteria);
      inOrder.verify(anyCriteria).addOrderBy(Tab.PROPERTY_TABLEVEL, true);
      inOrder.verify(anyCriteria).addOrderBy(Tab.PROPERTY_SEQUENCENUMBER, true);
      verify(anyCriteria).setMaxResults(1);
    }
  }

  /**
   * Sharpest edge case: proves the {@code STD} preference is ABSOLUTE, not merely
   * incidental to level/sequence ordering. Models a table with a mix of active
   * {@code STD} and {@code RO} tabs where the {@code STD} tab has a HIGHER
   * {@code tabLevel} (3) than an RO tab would (0) — meaning a single merged query
   * ordered by level/seqNo across both types would put the RO tab first. The
   * two-separate-query design must still return the {@code STD} tab, and must
   * never even issue the fallback ("any active tab") query, proving {@code STD}
   * wins by construction rather than by a lucky ordering coincidence.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTabIdPrefersStdTabEvenWhenRoTabWouldSortFirstByLevel() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> stdCriteria = mock(OBCriteria.class);
    Tab stdTab = mock(Tab.class);
    when(stdTab.getId()).thenReturn("STD_TAB_LEVEL3");
    when(stdTab.getTabLevel()).thenReturn(3L);
    when(dal.createCriteria(Tab.class)).thenReturn(stdCriteria);
    when(stdCriteria.list()).thenReturn(Collections.singletonList(stdTab));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String tabId = NeoAttachmentsHelper.resolveTabId("TABLE1", null);

      assertEquals("STD_TAB_LEVEL3", tabId);
      // The fallback ("any active tab") query is never issued: STD wins outright,
      // regardless of what tabLevel/sequenceNumber a coexisting RO tab might have.
      verify(dal, times(1)).createCriteria(Tab.class);
    }
  }

  /**
   * An INACTIVE {@code STD} tab coexisting with an ACTIVE {@code RO} tab: the
   * {@code STD}-only query restricts on {@code active = true} so it correctly
   * excludes the inactive {@code STD} tab (returns empty), and the fallback must
   * then return the active {@code RO} tab — never {@code null}, never the
   * inactive {@code STD} tab.
   */
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void resolveTabIdIgnoresInactiveStdTabAndFallsBackToActiveRoTab() throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> stdCriteria = mock(OBCriteria.class);
    OBCriteria<Tab> anyCriteria = mock(OBCriteria.class);
    Tab roTab = mock(Tab.class);
    when(roTab.getId()).thenReturn("RO_TAB_ACTIVE");
    when(dal.createCriteria(Tab.class)).thenReturn(stdCriteria, anyCriteria);
    // The inactive STD tab never reaches this list: the "active = true"
    // restriction (added unconditionally by findFirstActiveTabId) excludes it
    // at the DB level, regardless of the stdOnly flag.
    when(stdCriteria.list()).thenReturn(Collections.emptyList());
    when(anyCriteria.list()).thenReturn(Collections.singletonList(roTab));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      String tabId = NeoAttachmentsHelper.resolveTabId("TABLE1", null);

      assertEquals("RO_TAB_ACTIVE", tabId);
      // Both queries unconditionally restrict on table + active; only the
      // STD-only query additionally restricts on uipattern. Call-count check
      // guards against a future refactor silently dropping the active filter.
      verify(stdCriteria, times(3)).add(any());
      verify(anyCriteria, times(2)).add(any());
    }
  }

  /**
   * End-to-end regression on {@code handleUpload}: when a table has zero active
   * tabs at all (not just zero STD tabs), the 400 "no standard tab" error path
   * still triggers after both resolution attempts come back empty.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleUploadReturnsBadRequestWhenNoActiveTabExistsAtAll() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Part part = mock(Part.class);
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> tabCriteria = mock(OBCriteria.class);

    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("file")).thenReturn(part);
    when(request.getParameter("tabId")).thenReturn(null);
    // ETP-5038 moved the size/type policy AHEAD of tab resolution, so a bare Part mock (no
    // submitted name, no headers) is now rejected earlier with "File type not allowed" and the
    // tab path below is never reached. Still a 400, which is why only the message assertion
    // caught it. Give the part an allowed name so the policy passes and this test keeps
    // exercising what it is about.
    when(part.getSubmittedFileName()).thenReturn("order-1.pdf");
    stubTableLookup(dal, "TABLE1");
    when(dal.createCriteria(Tab.class)).thenReturn(tabCriteria);
    when(tabCriteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

      assertEquals(400, response.getHttpStatus());
      assertTrue(errorMessage(response).contains("Could not resolve a standard tab"));
      verify(dal, times(2)).createCriteria(Tab.class);
    }
  }

  /**
   * ETP-5309 repro: uploading against a record that does not exist (the SPA's unsaved
   * literal id {@code "new"}) answers a clean 404 before the core is reached, instead of
   * the core's raw OBSecurityException surfacing as a 500.
   */
  @Test
  public void handleUploadReturnsNotFoundWhenOwningRecordDoesNotExist() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Part part = mock(Part.class);
    OBDal dal = mock(OBDal.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);

    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("file")).thenReturn(part);
    when(request.getParameter("tabId")).thenReturn("TAB1");
    when(part.getSubmittedFileName()).thenReturn("order-1.pdf");
    stubTableLookup(dal, "TABLE1");
    when(modelProvider.getEntityByTableId("TABLE1")).thenReturn(entity);
    when(entity.getName()).thenReturn("Order");
    when(dal.get("Order", "new")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelMock = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<OBContext> contextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      modelMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "new", request, false);

      assertEquals(404, response.getHttpStatus());
      assertEquals("Record 'new' does not exist in table 'C_Order'. Save it before attaching files.",
          errorMessage(response));
      contextMock.verify(() -> OBContext.setAdminMode(true));
      contextMock.verify(OBContext::restorePreviousMode);
      weldMock.verifyNoInteractions();
      // The check runs before the payload is materialized: no temp file is ever written.
      verify(part, never()).getInputStream();
    }
  }

  /** A minimal valid PDF payload, so {@link NeoAttachmentPolicy#validateContent} accepts it. */
  private static Part stubPdfPart(String fileName) throws Exception {
    Part part = mock(Part.class);
    when(part.getSubmittedFileName()).thenReturn(fileName);
    when(part.getInputStream()).thenAnswer(inv -> new ByteArrayInputStream(
        "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)));
    return part;
  }

  private static HttpServletRequest stubUploadRequest(Part part) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("file")).thenReturn(part);
    when(request.getParameter("tabId")).thenReturn("TAB1");
    return request;
  }

  private static void stubCurrentOrganization(MockedStatic<OBContext> contextMock, String orgId) {
    OBContext context = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(context.getCurrentOrganization()).thenReturn(org);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
  }

  /**
   * ETP-5309: when the owning record exists, the existence check lets the upload through to
   * the core — looked up by the table's DAL entity, in admin mode, which is then restored.
   */
  @Test
  public void handleUploadReachesCoreWhenOwningRecordExists() throws Exception {
    Part part = stubPdfPart("etp5309-existing-" + System.nanoTime() + ".pdf");
    HttpServletRequest request = stubUploadRequest(part);
    OBDal dal = mock(OBDal.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    stubTableLookup(dal, "TABLE1");
    when(modelProvider.getEntityByTableId("TABLE1")).thenReturn(entity);
    when(entity.getName()).thenReturn("Order");
    when(dal.get("Order", "REC1")).thenReturn(mock(BaseOBObject.class));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelMock = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<OBContext> contextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      modelMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      stubCurrentOrganization(contextMock, "ORG1");
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

      assertEquals(201, response.getHttpStatus());
      verify(dal).get("Order", "REC1");
      verify(aim).upload(any(), eq("TAB1"), eq("REC1"), eq("ORG1"), any(File.class));
      contextMock.verify(() -> OBContext.setAdminMode(true));
      contextMock.verify(OBContext::restorePreviousMode);
    }
  }

  /**
   * ETP-5309: a table with no DAL entity is not checked — exactly as the core skips it — so
   * the upload proceeds without a lookup and without touching admin mode.
   */
  @Test
  public void handleUploadSkipsRecordCheckWhenTableHasNoEntity() throws Exception {
    Part part = stubPdfPart("etp5309-noentity-" + System.nanoTime() + ".pdf");
    HttpServletRequest request = stubUploadRequest(part);
    OBDal dal = mock(OBDal.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    stubTableLookup(dal, "TABLE1");
    when(modelProvider.getEntityByTableId("TABLE1")).thenReturn(null);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelMock = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<OBContext> contextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      modelMock.when(ModelProvider::getInstance).thenReturn(modelProvider);
      stubCurrentOrganization(contextMock, "ORG1");
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

      assertEquals(201, response.getHttpStatus());
      verify(dal, never()).get(anyString(), any());
      verify(aim).upload(any(), eq("TAB1"), eq("REC1"), eq("ORG1"), any(File.class));
      contextMock.verify(() -> OBContext.setAdminMode(true), never());
    }
  }

  /**
   * ETP-5309: admin mode entered for the existence lookup is restored even when the lookup
   * throws; the failure surfaces as the handler's 500 and nothing reaches the core.
   */
  @Test
  public void handleUploadRestoresAdminModeWhenRecordLookupThrows() throws Exception {
    Part part = stubPdfPart("etp5309-throws-" + System.nanoTime() + ".pdf");
    HttpServletRequest request = stubUploadRequest(part);
    OBDal dal = mock(OBDal.class);
    ModelProvider modelProvider = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    stubTableLookup(dal, "TABLE1");
    when(modelProvider.getEntityByTableId("TABLE1")).thenReturn(entity);
    when(entity.getName()).thenReturn("Order");
    when(dal.get("Order", "REC1")).thenThrow(new OBException("lookup failed"));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<ModelProvider> modelMock = Mockito.mockStatic(ModelProvider.class);
        MockedStatic<OBContext> contextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      modelMock.when(ModelProvider::getInstance).thenReturn(modelProvider);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request, false);

      assertEquals(500, response.getHttpStatus());
      assertEquals("lookup failed", errorMessage(response));
      contextMock.verify(() -> OBContext.setAdminMode(true));
      contextMock.verify(OBContext::restorePreviousMode);
      verify(dal).rollbackAndClose();
      verify(part, never()).getInputStream();
      weldMock.verifyNoInteractions();
    }
  }

  /**
   * Verifies file-size resolution when payload exists on disk.
   */
  @Test
  public void computeFileSizeReturnsExistingFileLength() throws Exception {
    File root = Files.createTempDirectory("neo-attach-root").toFile();
    File subdir = new File(root, "sub");
    File file = new File(subdir, "payload.bin");
    subdir.mkdirs();
    Files.write(file.toPath(), "12345".getBytes(StandardCharsets.UTF_8));

    Attachment attachment = mock(Attachment.class);
    when(attachment.getPath()).thenReturn("sub");
    when(attachment.getName()).thenReturn("payload.bin");

    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    Properties properties = new Properties();
    properties.setProperty("attach.path", root.getAbsolutePath());
    when(provider.getOpenbravoProperties()).thenReturn(properties);

    try (MockedStatic<OBPropertiesProvider> propsMock = Mockito.mockStatic(OBPropertiesProvider.class)) {
      propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);

      long size = (Long) invokePrivateStatic("computeFileSize", new Class<?>[]{ Attachment.class }, attachment);
      assertEquals(5L, size);
    } finally {
      file.delete();
      subdir.delete();
      root.delete();
    }
  }

  /**
   * Verifies multipart materialization helper writes bytes to temp file.
   */
  @Test
  public void materializeTempFileWritesPartBytes() throws Exception {
    Part part = mock(Part.class);
    String name = "neo-materialize-" + System.nanoTime() + ".txt";
    when(part.getSubmittedFileName()).thenReturn(name);
    when(part.getInputStream()).thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

    File temp = (File) invokePrivateStatic("materializeTempFile", new Class<?>[]{ Part.class }, part);
    try {
      assertTrue(temp.exists());
      assertEquals(4L, temp.length());
    } finally {
      temp.delete();
    }
  }

  /**
   * Verifies streaming error helper writes JSON payload to response.
   */
  @Test
  public void writeErrorWritesJsonBodyToResponse() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    invokePrivateStatic("writeError", new Class<?>[]{ HttpServletResponse.class, int.class, String.class },
        response, 422, "unprocessable");

    verify(response).setStatus(422);
    verify(response).setContentType("application/json");
    verify(response).setCharacterEncoding(StandardCharsets.UTF_8.name());
    assertTrue(sink.toString().contains("unprocessable"));
  }

  /**
   * Stubs {@link WeldUtils} so {@code NeoAttachmentsHelper.getAttachManager()}
   * resolves to a manager handing back {@code handler} for any attach method.
   */
  private static void stubAttachManager(MockedStatic<WeldUtils> weldMock,
      AttachImplementation handler) {
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    when(aim.getHandler(anyString())).thenReturn(handler);
    weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
        .thenReturn(aim);
  }

  /**
   * ETP-5526 (CP-16) — the size column rendered "0 B" for every attachment in
   * every window because the size was computed from {@code c_file.path}, which
   * is {@code NULL} for attachments stored the "old way". The size must now come
   * from the attach implementation, exactly like the download does, so a
   * {@code null} path no longer zeroes it.
   */
  @Test
  public void computeFileSizeUsesAttachImplementationWhenPathIsNull() throws Exception {
    File payload = File.createTempFile("neo-attachment-size", ".bin");
    Files.write(payload.toPath(), new byte[2048]);

    Attachment attachment = stubAttachment("ATT1", "invoice.pdf");
    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenReturn(payload);
    when(handler.isTempFile()).thenReturn(false);

    try (MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      stubAttachManager(weldMock, handler);

      long size = ((Long) invokePrivateStatic("computeFileSize", new Class<?>[]{ Attachment.class },
          attachment)).longValue();

      assertEquals(2048L, size);
      // The payload is the backend's own file, not a temp copy: it must survive.
      assertTrue(payload.exists());
    } finally {
      payload.delete();
    }
  }

  /**
   * ETP-5526 (CP-16) — backends that materialize a temporary copy must not leak
   * one file per listed attachment: the copy is removed once its length is read.
   */
  @Test
  public void computeFileSizeRemovesTemporaryCopyFromTempFileBackends() throws Exception {
    File payload = File.createTempFile("neo-attachment-size-temp", ".bin");
    Files.write(payload.toPath(), new byte[16]);

    Attachment attachment = stubAttachment("ATT1", "invoice.pdf");
    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenReturn(payload);
    when(handler.isTempFile()).thenReturn(true);

    try (MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      stubAttachManager(weldMock, handler);

      long size = ((Long) invokePrivateStatic("computeFileSize", new Class<?>[]{ Attachment.class },
          attachment)).longValue();

      assertEquals(16L, size);
      assertFalse(payload.exists());
    } finally {
      payload.delete();
    }
  }

  /**
   * ETP-5526 (CP-16) — {@code computeFileSize} runs inside the list response, so
   * a backend failure must degrade to 0 instead of breaking the whole listing.
   */
  @Test
  public void computeFileSizeReturnsZeroWhenBackendFails() throws Exception {
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf");
    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenThrow(new OBException("backend down"));

    try (MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      stubAttachManager(weldMock, handler);

      long size = ((Long) invokePrivateStatic("computeFileSize", new Class<?>[]{ Attachment.class },
          attachment)).longValue();

      assertEquals(0L, size);
    }
  }

  /**
   * ETP-5526 (CP-16) — a missing file on disk still yields 0, as before.
   */
  @Test
  public void computeFileSizeReturnsZeroWhenFileDoesNotExist() throws Exception {
    File missing = new File(System.getProperty("java.io.tmpdir"),
        "neo-attachment-size-missing-" + System.nanoTime() + ".bin");

    Attachment attachment = stubAttachment("ATT1", "invoice.pdf");
    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenReturn(missing);
    when(handler.isTempFile()).thenReturn(false);

    try (MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      stubAttachManager(weldMock, handler);

      long size = ((Long) invokePrivateStatic("computeFileSize", new Class<?>[]{ Attachment.class },
          attachment)).longValue();

      assertEquals(0L, size);
    }
  }
}
