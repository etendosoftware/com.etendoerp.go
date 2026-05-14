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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import java.util.Collections;
import java.util.Date;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Attachment;

/**
 * Unit tests for private utility methods in {@link NeoAttachmentsHelper}.
 *
 * <p>These tests focus on deterministic helpers (file-name sanitization,
 * content-disposition formatting, date formatting and temp-file cleanup)
 * to keep coverage fast and independent from DAL/CDI infrastructure.</p>
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
   * Verifies upload endpoint validation for blank table/record identifiers.
   */
  @Test
  public void handleUploadRejectsBlankInputs() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);

    NeoResponse response = NeoAttachmentsHelper.handleUpload("", " ", request);

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

    NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request);

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

    NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request);

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
    stubTableLookup(dal, "TABLE1");
    when(dal.createCriteria(Tab.class)).thenReturn(tabCriteria);
    when(tabCriteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = NeoAttachmentsHelper.handleUpload("C_Order", "REC1", request);

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
   * Verifies bulk-download returns bad request when no standard tab is found.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void handleDownloadAllReturnsBadRequestWhenNoStandardTabFound() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    OBDal dal = mock(OBDal.class);
    OBCriteria<Tab> tabCriteria = mock(OBCriteria.class);

    stubTableLookup(dal, "TABLE1");
    when(dal.createCriteria(Tab.class)).thenReturn(tabCriteria);
    when(tabCriteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoAttachmentsHelper.handleDownloadAll("C_Order", "REC1", response);

      verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
      assertTrue(sink.toString().contains("Could not resolve a standard tab"));
    }
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
}
