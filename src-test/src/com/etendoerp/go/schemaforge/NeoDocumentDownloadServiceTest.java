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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementation;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.ad.utility.AttachmentConfig;
import org.openbravo.model.ad.utility.AttachmentMethod;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Unit tests for {@link NeoDocumentDownloadService}.
 *
 * <p>Covers the signed-link resolution flow introduced by ETP-4315: token
 * validation, mapping the token's spec to a physical table, resolving the
 * attachment currently marked as "main" for that (table, record), the
 * client-scoping security check, and the binary streaming/error paths.</p>
 *
 * <p>ETP-5304 reworked how the bytes are obtained: the service no longer calls
 * {@code AttachImplementationManager#download} (whose session-bound checks can
 * never pass under this anonymous endpoint). It now resolves an on-disk
 * {@link File} — composed from {@code attach.path} for default storage, or
 * produced by the attachment's own handler when an {@code AttachmentConf} is
 * set — and reads it directly. The streaming tests therefore exercise real
 * files in a temporary directory rather than a stubbed output stream.</p>
 */
public class NeoDocumentDownloadServiceTest {

  private static final String TOKEN = "tok";
  private static final String ERR_INVALID_FILE = "Stored document file is invalid";
  private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
  private static final byte[] FILE_BYTES = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);

  /**
   * Nested directory mirroring the layout the core records in {@code Attachment.path} for
   * filesystem-stored attachments — the very layout ETP-5304 restored: the pre-fix code
   * silently fell back to the flat one and looked for the file in a directory that does
   * not exist.
   */
  private static final String NESTED_PATH =
      "318" + File.separator + "AB0" + File.separator + "9AA";

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

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
   * Stubs the fields the default (filesystem) storage resolution reads.
   *
   * <p>{@code table} and {@code record} are stubbed even when {@code path} is provided:
   * the service builds the flat fallback as an argument to
   * {@code StringUtils.defaultIfBlank}, so Java evaluates {@code getTable().getId()}
   * unconditionally — leaving it unstubbed would blow up with an NPE on the happy path.</p>
   */
  private static void stubStorageLayout(Attachment attachment, String path, String tableId,
      String recordId) {
    when(attachment.getPath()).thenReturn(path);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(tableId);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn(recordId);
  }

  /**
   * Binds the attachment to an explicit storage method, which routes resolution through the
   * corresponding {@link AttachImplementation} instead of the default filesystem layout.
   */
  private static void stubAttachmentConf(Attachment attachment, String attachmentMethod) {
    AttachmentMethod method = mock(AttachmentMethod.class);
    when(method.getValue()).thenReturn(attachmentMethod);
    AttachmentConfig conf = mock(AttachmentConfig.class);
    when(conf.getAttachmentMethod()).thenReturn(method);
    when(attachment.getAttachmentConf()).thenReturn(conf);
  }

  /**
   * Builds an {@link OBPropertiesProvider} exposing the given {@code attach.path} root, or an
   * empty properties set when {@code attachRoot} is {@code null} (unconfigured instance).
   */
  private static OBPropertiesProvider stubAttachRoot(String attachRoot) {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    Properties properties = new Properties();
    if (attachRoot != null) {
      properties.setProperty("attach.path", attachRoot);
    }
    when(provider.getOpenbravoProperties()).thenReturn(properties);
    return provider;
  }

  /** Writes a real file under {@code <root>/<relativeDir>/<fileName>} so the service can read it. */
  private static File writeStoredFile(File root, String relativeDir, String fileName,
      byte[] content) throws IOException {
    File dir = new File(root, relativeDir);
    Files.createDirectories(dir.toPath());
    File file = new File(dir, fileName);
    Files.write(file.toPath(), content);
    return file;
  }

  /**
   * Creates a sparse file of the requested length. {@code setLength} only moves the file's
   * end-of-file marker, so an oversized fixture costs no disk and no time — writing 12MB of
   * real bytes just to trip a size check would not.
   */
  private static File createSparseFile(File dir, String fileName, long length) throws IOException {
    Files.createDirectories(dir.toPath());
    File file = new File(dir, fileName);
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.setLength(length);
    }
    return file;
  }

  /**
   * Runs {@code handle} with the four statics the flow touches mocked. A {@code null}
   * {@code provider} or {@code aim} means the test asserts that branch is never reached.
   */
  private static void runHandle(HttpServletResponse response, Claims claims, OBDal dal,
      OBPropertiesProvider provider, AttachImplementationManager aim) throws IOException {
    try (MockedStatic<DocumentDownloadTokenService> tokenMock =
        Mockito.mockStatic(DocumentDownloadTokenService.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBPropertiesProvider> propsMock =
            Mockito.mockStatic(OBPropertiesProvider.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      tokenMock.when(() -> DocumentDownloadTokenService.validate(TOKEN))
          .thenReturn(Optional.of(claims));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      if (provider != null) {
        propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      }
      if (aim != null) {
        weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
            .thenReturn(aim);
      }

      NeoDocumentDownloadService.handle(TOKEN, response);
    }
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
   * Happy path: the main attachment belongs to the token's client, so the file stored under
   * {@code attach.path} + the attachment's recorded nested path is read and streamed back with
   * the expected content type and disposition.
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
    stubStorageLayout(attachment, NESTED_PATH, "TABLE1", "REC1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    File attachRoot = tempFolder.newFolder("attachments");
    writeStoredFile(attachRoot, NESTED_PATH, "invoice final.pdf", FILE_BYTES);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    runHandle(response, claims, dal, stubAttachRoot(attachRoot.getAbsolutePath()), aim);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType("application/xml");
    verify(response).setContentLength(FILE_BYTES.length);
    ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq("Content-Disposition"), headerCaptor.capture());
    assertTrue(headerCaptor.getValue().contains("invoice final.pdf"));

    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(sos).write(bodyCaptor.capture());
    assertArrayEquals(FILE_BYTES, bodyCaptor.getValue());

    // ETP-5304's core claim: default-stored attachments never go through the attachment
    // manager, whose session-bound checks this anonymous endpoint can never satisfy.
    verifyNoInteractions(aim);
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
    stubStorageLayout(attachment, NESTED_PATH, "TABLE1", "REC1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    File attachRoot = tempFolder.newFolder("attachments");
    writeStoredFile(attachRoot, NESTED_PATH, "invoice.pdf", FILE_BYTES);

    runHandle(response, claims, dal, stubAttachRoot(attachRoot.getAbsolutePath()), null);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    verify(response).setContentType("application/pdf");
  }

  /**
   * Attachments predating the nested-path layout have no {@code path} recorded, so the service
   * must reproduce the core's flat {@code <tableId>-<recordId>} fallback instead of resolving a
   * file under a blank directory (which would silently land on the attach root).
   */
  @Test
  public void handleResolvesFlatLayoutWhenAttachmentHasNoRecordedPath() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream sos = mock(ServletOutputStream.class);
    when(response.getOutputStream()).thenReturn(sos);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "legacy.pdf", "application/pdf", "CLIENT1");
    stubStorageLayout(attachment, null, "TABLE1", "REC1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    File attachRoot = tempFolder.newFolder("attachments");
    writeStoredFile(attachRoot, "TABLE1-REC1", "legacy.pdf", FILE_BYTES);

    runHandle(response, claims, dal, stubAttachRoot(attachRoot.getAbsolutePath()), null);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(sos).write(bodyCaptor.capture());
    assertArrayEquals(FILE_BYTES, bodyCaptor.getValue());
  }

  /**
   * A row can outlive its file (manual cleanup, restore from a partial backup). The resolved
   * path must be checked before reading, so the recipient gets a controlled 500 instead of an
   * unhandled {@code NoSuchFileException} escaping the servlet.
   */
  @Test
  public void handleReturnsServerErrorWhenStoredFileIsMissingOnDisk() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "vanished.pdf", "application/pdf", "CLIENT1");
    stubStorageLayout(attachment, NESTED_PATH, "TABLE1", "REC1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    // The directory exists but the file was never written.
    File attachRoot = tempFolder.newFolder("attachments");
    Files.createDirectories(new File(attachRoot, NESTED_PATH).toPath());

    runHandle(response, claims, dal, stubAttachRoot(attachRoot.getAbsolutePath()), null);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals(ERR_INVALID_FILE, sink.toString());
  }

  /**
   * Without {@code attach.path} there is no root to resolve against; the misconfiguration must
   * surface as a 500 rather than a path built from a {@code null} root.
   */
  @Test
  public void handleReturnsServerErrorWhenAttachPathPropertyIsNotConfigured() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "CLIENT1");
    stubStorageLayout(attachment, NESTED_PATH, "TABLE1", "REC1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    runHandle(response, claims, dal, stubAttachRoot(null), null);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals(ERR_INVALID_FILE, sink.toString());
  }

  /**
   * Verifies files above the 12MB cap are rejected with 500 rather than partially streamed —
   * the check is on the file's declared length, so a sparse fixture proves it without the test
   * ever touching 12MB of data.
   */
  @Test
  public void handleReturnsServerErrorWhenFileExceedsSizeCap() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "huge.pdf", "application/pdf", "CLIENT1");
    stubStorageLayout(attachment, NESTED_PATH, "TABLE1", "REC1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    File attachRoot = tempFolder.newFolder("attachments");
    createSparseFile(new File(attachRoot, NESTED_PATH), "huge.pdf", MAX_DOWNLOAD_BYTES + 1L);

    runHandle(response, claims, dal, stubAttachRoot(attachRoot.getAbsolutePath()), null);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals("Stored document file is too large", sink.toString());
  }

  /**
   * Attachments bound to an explicit {@code AttachmentConf} live in a backend that does not use
   * the filesystem layout at all, so resolution must delegate to that method's handler.
   */
  @Test
  public void handleDelegatesToTheConfiguredHandlerWhenAttachmentConfIsPresent() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream sos = mock(ServletOutputStream.class);
    when(response.getOutputStream()).thenReturn(sos);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "CLIENT1");
    stubAttachmentConf(attachment, "S3");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    File handlerFile = writeStoredFile(tempFolder.newFolder("s3-cache"), "bucket", "invoice.pdf",
        FILE_BYTES);
    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenReturn(handlerFile);
    when(handler.isTempFile()).thenReturn(false);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    when(aim.getHandler("S3")).thenReturn(handler);

    runHandle(response, claims, dal, null, aim);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(sos).write(bodyCaptor.capture());
    assertArrayEquals(FILE_BYTES, bodyCaptor.getValue());
    verify(aim).getHandler("S3");
    assertTrue("a non-temporary handler file must be left in place", handlerFile.exists());
  }

  /**
   * Calling the handler directly means this service inherits the cleanup the attachment manager
   * used to do: a handler that materializes a temporary copy would otherwise leak one file (and
   * its directory) per emailed download.
   */
  @Test
  public void handleDeletesTemporaryHandlerFileAfterStreaming() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream sos = mock(ServletOutputStream.class);
    when(response.getOutputStream()).thenReturn(sos);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "CLIENT1");
    stubAttachmentConf(attachment, "S3");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    File tempFile = writeStoredFile(tempFolder.newFolder("handler-temp"), "scratch", "invoice.pdf",
        FILE_BYTES);
    File tempDir = tempFile.getParentFile();
    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenReturn(tempFile);
    when(handler.isTempFile()).thenReturn(true);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    when(aim.getHandler("S3")).thenReturn(handler);

    runHandle(response, claims, dal, null, aim);

    verify(response).setStatus(HttpServletResponse.SC_OK);
    assertFalse("the temporary file must be removed once served", tempFile.exists());
    assertFalse("the handler's temporary directory must be removed too", tempDir.exists());
  }

  /**
   * An attachment whose recorded method has no handler installed (module uninstalled since
   * upload) must resolve to a 500, not a {@code NullPointerException} on the missing handler.
   */
  @Test
  public void handleReturnsServerErrorWhenNoHandlerIsRegisteredForTheMethod() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "CLIENT1");
    stubAttachmentConf(attachment, "RetiredMethod");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    when(aim.getHandler("RetiredMethod")).thenReturn(null);

    runHandle(response, claims, dal, null, aim);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals(ERR_INVALID_FILE, sink.toString());
  }

  /**
   * Verifies a storage-layer failure raised by the handler itself is surfaced as 500 rather
   * than propagating the underlying exception out of the servlet.
   */
  @Test
  public void handleReturnsServerErrorWhenHandlerDownloadThrowsObException() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter sink = stubWriter(response);

    Claims claims = stubClaims("sales-invoice", "REC1", "CLIENT1");
    OBDal dal = mock(OBDal.class);
    stubTableAndMainLookup(dal, "TABLE1", "ATT1");
    Attachment attachment = stubAttachment("ATT1", "invoice.pdf", "application/pdf", "CLIENT1");
    stubAttachmentConf(attachment, "S3");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    AttachImplementation handler = mock(AttachImplementation.class);
    when(handler.downloadFile(attachment)).thenThrow(new OBException("storage backend unavailable"));
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    when(aim.getHandler("S3")).thenReturn(handler);

    runHandle(response, claims, dal, null, aim);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertEquals(ERR_INVALID_FILE, sink.toString());
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
