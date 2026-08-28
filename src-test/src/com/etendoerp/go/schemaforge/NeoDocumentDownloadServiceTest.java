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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Unit tests for {@link NeoDocumentDownloadService}.
 *
 * <p>Covers the signed-link resolution flow introduced by ETP-4315: token
 * validation, mapping the token's spec to a physical table, resolving the
 * attachment currently marked as "main" for that (table, record), the
 * client-scoping security check, and the binary streaming/error paths.</p>
 */
public class NeoDocumentDownloadServiceTest {

  private static final String TOKEN = "tok";

  @After
  public void clearCacheAfterEachTest() {
    NeoAttachmentsHelper.clearTableIdCache();
  }

  private static StringWriter stubWriter(HttpServletResponse response) throws Exception {
    StringWriter sink = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(sink));
    return sink;
  }

  private static Claims stubClaims(String specName, String recordId, String clientId) {
    Claims claims = mock(Claims.class);
    when(claims.getSpecName()).thenReturn(specName);
    when(claims.getRecordId()).thenReturn(recordId);
    when(claims.getClientId()).thenReturn(clientId);
    return claims;
  }

  private static Attachment stubAttachment(String id, String name, String dataType, String clientId) {
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn(id);
    when(attachment.getName()).thenReturn(name);
    when(attachment.getDataType()).thenReturn(dataType);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(attachment.getClient()).thenReturn(client);
    return attachment;
  }

  /**
   * Stubs both the table-id resolution query (matches SQL containing
   * {@code "ad_table"}) and the main-attachment-ids lookup (matches SQL
   * containing {@code "C_File"}) exactly like {@link NeoAttachmentsHelperTest}
   * does, so {@link NeoAttachmentsHelper#resolveTableId} and
   * {@link NeoAttachmentsHelper#findMainAttachment} can run unmocked under a
   * mocked {@link OBDal}.
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static void stubTableAndMainLookup(OBDal dal, String tableId, String... mainAttachmentIds) {
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
  }

  private static Object invokePrivateStatic(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = NeoDocumentDownloadService.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(null, args);
  }

  /**
   * Verifies that an invalid or expired token never reaches attachment
   * resolution and is rejected with 403.
   */
  @Test
  public void handleReturnsForbiddenWhenTokenInvalid() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.empty());

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    assertEquals("Invalid or expired link", sink.toString());
  }

  /**
   * Verifies that a spec absent from {@code WINDOW_ATTACHMENT_TABLE} resolves
   * to 404 without ever attempting a table lookup.
   */
  @Test
  public void handleReturnsNotFoundWhenSpecIsNotMapped() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    Claims claims = stubClaims("unknown-window", "REC1", "CLIENT1");

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));

      NeoDocumentDownloadService.handle(TOKEN, response);

      obDalMock.verifyNoInteractions();
    }

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    assertEquals("Document file not found", sink.toString());
  }

  /**
   * Verifies that when no attachment is currently marked as main for the
   * token's (table, record), the link resolves to 404 rather than any
   * fallback file.
   */
  @Test
  public void handleReturnsNotFoundWhenNoAttachmentIsMarkedAsMain() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1");

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    assertEquals("Document file not found", sink.toString());
  }

  /**
   * Security check: a main attachment resolved via (table, record) that
   * belongs to a different client than the token's must never be served —
   * tokens are per-client and record/table ids could collide across clients.
   */
  @Test
  public void handleReturnsNotFoundWhenAttachmentBelongsToDifferentClient() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "OTHER_CLIENT");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    assertEquals("Document file not found", sink.toString());
  }

  /**
   * Defensive variant of the client-scoping check: an attachment with no
   * client at all must also be rejected, not treated as a wildcard match.
   */
  @Test
  public void handleReturnsNotFoundWhenAttachmentHasNoClient() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);
    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn("ATT1");
    when(attachment.getClient()).thenReturn(null);
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    assertEquals("Document file not found", sink.toString());
  }

  /**
   * Happy path: the main attachment belongs to the token's client, so its
   * bytes are streamed back with the expected content type and disposition.
   */
  @Test
  public void handleStreamsAttachmentWhenClientMatches() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream sos = mock(ServletOutputStream.class);
    when(response.getOutputStream()).thenReturn(sos);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice final.pdf", "application/xml", "CLIENT1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    byte[] fakeBytes = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);
    doAnswer(invocation -> {
      ByteArrayOutputStream buffer = invocation.getArgument(1);
      buffer.write(fakeBytes);
      return null;
    }).when(aim).download(eq("ATT1"), any(ByteArrayOutputStream.class));

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType("application/xml");
    ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq("Content-Disposition"), headerCaptor.capture());
    assertTrue(headerCaptor.getValue().contains("invoice final.pdf"));

    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(sos).write(bodyCaptor.capture());
    assertArrayEquals(fakeBytes, bodyCaptor.getValue());
  }

  /**
   * Verifies the {@code application/pdf} fallback is applied when the stored
   * attachment has no {@code dataType} recorded.
   */
  @Test
  public void handleFallsBackToApplicationPdfWhenDataTypeIsBlank() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream sos = mock(ServletOutputStream.class);
    when(response.getOutputStream()).thenReturn(sos);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", null, "CLIENT1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    byte[] fakeBytes = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);
    doAnswer(invocation -> {
      ByteArrayOutputStream buffer = invocation.getArgument(1);
      buffer.write(fakeBytes);
      return null;
    }).when(aim).download(eq("ATT1"), any(ByteArrayOutputStream.class));

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType("application/pdf");
  }

  /**
   * Verifies files above the 12MB streaming cap are rejected with 500 rather
   * than partially streamed.
   */
  @Test
  public void handleReturnsServerErrorWhenFileExceedsSizeCap() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "huge.pdf", "application/pdf", "CLIENT1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    byte[] oversized = new byte[12 * 1024 * 1024 + 1];
    doAnswer(invocation -> {
      ByteArrayOutputStream buffer = invocation.getArgument(1);
      buffer.write(oversized);
      return null;
    }).when(aim).download(eq("ATT1"), any(ByteArrayOutputStream.class));

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals("Stored document file is too large", sink.toString());
  }

  /**
   * Verifies a storage-layer failure during download is surfaced as 500
   * rather than propagating the underlying exception.
   */
  @Test
  public void handleReturnsServerErrorWhenDownloadThrowsObException() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "CLIENT1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    doThrow(new OBException("storage backend unavailable"))
        .when(aim).download(eq("ATT1"), any(ByteArrayOutputStream.class));

    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN)).thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoDocumentDownloadService.handle(TOKEN, response);
    }

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals("Stored document file is invalid", sink.toString());
  }

  /**
   * Verifies unsafe filename characters (path separators, quotes, wildcards)
   * are replaced rather than left to leak into the HTTP header.
   */
  @Test
  public void sanitizeFileNameReplacesUnsafeCharactersWithUnderscore() throws Exception {
    String sanitized = (String) invokePrivateStatic("sanitizeFileName", new Class<?>[]{ String.class },
        "invoice/2026:final?.pdf");
    assertEquals("invoice_2026_final_.pdf", sanitized);
  }

  /**
   * Verifies blank filenames fall back to the default document name.
   */
  @Test
  public void sanitizeFileNameFallsBackToDefaultWhenBlank() throws Exception {
    String sanitized = (String) invokePrivateStatic("sanitizeFileName", new Class<?>[]{ String.class }, "   ");
    assertEquals("document.pdf", sanitized);
  }

  /**
   * Verifies the Content-Disposition header carries both the sanitized ASCII
   * filename and its RFC 5987 UTF-8 encoded counterpart.
   */
  @Test
  public void contentDispositionIncludesSanitizedAsciiAndUtf8Filename() throws Exception {
    String disposition = (String) invokePrivateStatic("contentDisposition", new Class<?>[]{ String.class },
        "invoice/2026 final.pdf");

    assertTrue(disposition.contains("filename=\"invoice_2026 final.pdf\""));
    assertTrue(disposition.contains("filename*=UTF-8''invoice_2026%20final.pdf"));
  }
}
