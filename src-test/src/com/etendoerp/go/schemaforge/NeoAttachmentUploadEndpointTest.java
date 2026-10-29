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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Session;
import com.etendoerp.go.schemaforge.NeoAttachmentUploadTickets.Subject;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5715 — the unauthenticated PUT behind the MCP attachment upload link. The token is its only
 * credential, so what is asserted here is what makes that safe: one answer for unknown, used and
 * expired links, the upload running as the session that issued the link (never as an administrator),
 * the document re-authorized at upload time, single use, and a rejected file not burning the link.
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentUploadEndpoint
 * @covers com.etendoerp.go.schemaforge.NeoStagedUpload
 */
@DisplayName("ETP-5715 — NeoAttachmentUploadEndpoint")
class NeoAttachmentUploadEndpointTest {

  private static final String USER = "USER1";
  private static final String ROLE = "ROLE1";
  private static final String CLIENT = "CLIENT1";
  private static final String ORG = "ORG1";
  private static final String WAREHOUSE = "WH1";

  @TempDir
  Path stagedRoot;

  private MockedStatic<NeoAttachmentUploadTickets> ticketsMock;
  private MockedStatic<OBContext> contextMock;
  private MockedStatic<SecureWebServicesUtils> secureMock;
  private MockedStatic<OBDal> dalMock;
  private MockedStatic<NeoAttachmentTarget> targetMock;
  private MockedStatic<NeoAttachmentStore> storeMock;
  private MockedStatic<NeoAttachmentsHelper> helperMock;
  private NeoAttachmentUploadTickets tickets;
  private OBContext previousContext;
  private OBContext sessionContext;
  private OBDal dal;
  private NeoAttachmentTarget target;
  private SFSpec activeSpec;
  private StringWriter body;
  private HttpServletResponse response;

  @BeforeEach
  void setUp() throws Exception {
    tickets = new NeoAttachmentUploadTickets();
    ticketsMock = mockStatic(NeoAttachmentUploadTickets.class);
    ticketsMock.when(NeoAttachmentUploadTickets::getInstance).thenReturn(tickets);
    previousContext = mock(OBContext.class);
    sessionContext = mock(OBContext.class);
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(previousContext);
    secureMock = mockStatic(SecureWebServicesUtils.class);
    secureMock.when(() -> SecureWebServicesUtils.createContext(USER, ROLE, ORG, WAREHOUSE, CLIENT))
        .thenReturn(sessionContext);
    dal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    activeSpec = mock(SFSpec.class);
    when(activeSpec.isActive()).thenReturn(true);
    when(activeSpec.isShowInMCP()).thenReturn(true);
    when(dal.get(SFSpec.class, "SPEC1")).thenReturn(activeSpec);
    SFEntity entity = mock(SFEntity.class);
    when(entity.isActive()).thenReturn(true);
    when(entity.isIncluded()).thenReturn(true);
    when(dal.get(SFEntity.class, "ENTITY1")).thenReturn(entity);
    target = mock(NeoAttachmentTarget.class);
    when(target.getTableName()).thenReturn("C_Order");
    when(target.getTableId()).thenReturn("TABLE1");
    when(target.getTabId()).thenReturn("TAB1");
    when(target.getRecordId()).thenReturn("RECORD1");
    targetMock = mockStatic(NeoAttachmentTarget.class);
    targetMock.when(() -> NeoAttachmentTarget.resolve(any(), any(), eq("RECORD1"), eq(true)))
        .thenReturn(target);
    storeMock = mockStatic(NeoAttachmentStore.class);
    helperMock = mockStatic(NeoAttachmentsHelper.class);
    Attachment stored = mock(Attachment.class);
    when(stored.getId()).thenReturn("ATT1");
    helperMock.when(() -> NeoAttachmentsHelper.findLatestUploadedAttachment("TABLE1", "RECORD1",
        "factura.pdf")).thenReturn(stored);
    body = new StringWriter();
    response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body, true));
  }

  @AfterEach
  void tearDown() {
    helperMock.close();
    storeMock.close();
    targetMock.close();
    dalMock.close();
    secureMock.close();
    contextMock.close();
    ticketsMock.close();
  }

  private String issue(boolean main) {
    return tickets.issue(new Session(CLIENT, ORG, USER, ROLE, WAREHOUSE),
        new Subject("SPEC1", "ENTITY1", "RECORD1", "factura.pdf", main));
  }

  private static HttpServletRequest putTo(String token) throws IOException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(NeoAttachmentUploadEndpoint.PATH + token);
    when(request.getContentLengthLong()).thenReturn(12L);
    when(request.getInputStream()).thenReturn(streamOf("%PDF-1.4 body".getBytes(StandardCharsets.US_ASCII)));
    return request;
  }

  private static ServletInputStream streamOf(byte[] bytes) {
    ByteArrayInputStream in = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      @Override
      public int read() {
        return in.read();
      }

      @Override
      public boolean isFinished() {
        return in.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        // blocking reads only
      }
    };
  }

  private void storeAnswers(NeoResponse answer) {
    storeMock.when(() -> NeoAttachmentStore.store(any())).thenReturn(answer);
  }

  private static NeoResponse created() throws Exception {
    JSONObject stored = new JSONObject();
    stored.put("name", "factura.pdf");
    stored.put("message", "uploaded");
    return NeoResponse.created(stored);
  }

  @Test
  @DisplayName("only a PUT-style path with a token addresses an upload link")
  void matchesOnlyTheLinkPath() {
    assertTrue(NeoAttachmentUploadEndpoint.matches("/attachment/upload/abc"));
    assertFalse(NeoAttachmentUploadEndpoint.matches("/attachment/upload/"));
    assertFalse(NeoAttachmentUploadEndpoint.matches("/attachments/C_Order/1"));
    assertFalse(NeoAttachmentUploadEndpoint.matches("/image/upload/abc"));
    assertFalse(NeoAttachmentUploadEndpoint.matches(null));
  }

  @Test
  @DisplayName("an unknown, used or expired link is ONE 403 and stores nothing")
  void unknownLinkIsForbidden() throws Exception {
    NeoAttachmentUploadEndpoint.handle(putTo("never-issued"), response);

    verify(response).setStatus(403);
    assertTrue(body.toString().contains(NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE),
        body.toString());
    storeMock.verify(() -> NeoAttachmentStore.store(any()), never());
  }

  @Test
  @DisplayName("the upload runs AS the session that issued the link, then the context is restored")
  void runsAsTheIssuingSession() throws Exception {
    String token = issue(false);
    storeAnswers(created());

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    secureMock.verify(() -> SecureWebServicesUtils.createContext(USER, ROLE, ORG, WAREHOUSE,
        CLIENT));
    contextMock.verify(() -> OBContext.setOBContext(sessionContext));
    contextMock.verify(() -> OBContext.setOBContext(previousContext));
    contextMock.verify(OBContext::restorePreviousMode);
  }

  @Test
  @DisplayName("the document is authorized again at upload time, for WRITING")
  void documentIsReauthorizedForWriting() throws Exception {
    String token = issue(false);
    storeAnswers(created());

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    targetMock.verify(() -> NeoAttachmentTarget.resolve(any(), any(), eq("RECORD1"), eq(true)));
  }

  @Test
  @DisplayName("success: 201 with the attachment id, the file name and the main flag; the link is spent")
  void successSpendsTheLink() throws Exception {
    String token = issue(true);
    storeAnswers(created());

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(201);
    JSONObject answer = new JSONObject(body.toString());
    assertEquals("ATT1", answer.getString("id"));
    assertEquals("factura.pdf", answer.getString("name"));
    assertTrue(answer.getBoolean("isMain"));
    verify(dal).commitAndClose();
    assertEquals("ATT1", tickets.peek(token).orElseThrow().getAttachmentId());

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);
    verify(response).setStatus(403);
  }

  @Test
  @DisplayName("the store receives the target's table, record and tab, the file name and the main flag")
  void storeReceivesTheTicketsUpload() throws Exception {
    String token = issue(true);
    storeAnswers(created());

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    ArgumentCaptor<NeoAttachmentUpload> captor = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    storeMock.verify(() -> NeoAttachmentStore.store(captor.capture()));
    NeoAttachmentUpload upload = captor.getValue();
    assertEquals("C_Order", upload.getTableName());
    assertEquals("RECORD1", upload.getRecordId());
    assertEquals("TAB1", upload.getTabIdHint());
    assertEquals("factura.pdf", upload.getFileName());
    assertEquals(12L, upload.getDeclaredSize());
    assertTrue(upload.isMarkAsMain());
  }

  @Test
  @DisplayName("a role that lost access (or a record that vanished) since the link was issued is refused and the link is spent")
  void refusedAtUploadTime() throws Exception {
    String token = issue(false);
    targetMock.when(() -> NeoAttachmentTarget.resolve(any(), any(), eq("RECORD1"), anyBoolean()))
        .thenThrow(new NeoAttachmentTarget.Refused(403, NeoAttachmentAuthorizer.ACCESS_DENIED));

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(403);
    storeMock.verify(() -> NeoAttachmentStore.store(any()), never());
    assertEquals(NeoAttachmentUploadTickets.Status.COMPLETED,
        tickets.peek(token).orElseThrow().getStatus());
  }

  @Test
  @DisplayName("a rejected file (too large, wrong type) answers the policy's message and does NOT burn the link")
  void rejectedFileKeepsTheLink() throws Exception {
    String token = issue(false);
    storeAnswers(NeoResponse.error(400, "File exceeds the maximum allowed size of 10 MB"));

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(400);
    assertTrue(body.toString().contains("File exceeds the maximum allowed size of 10 MB"));
    assertTrue(tickets.claim(token).isPresent(), "the same URL must accept a corrected file");
    verify(dal).rollbackAndClose();
  }

  @Test
  @DisplayName("an unexpected failure is a 500, rolls back and hands the link back")
  void unexpectedFailureReleasesTheLink() throws Exception {
    String token = issue(false);
    storeMock.when(() -> NeoAttachmentStore.store(any())).thenThrow(new IllegalStateException("x"));

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(500);
    verify(dal).rollbackAndClose();
    assertTrue(tickets.claim(token).isPresent());
    contextMock.verify(() -> OBContext.setOBContext(previousContext));
  }

  @Test
  @DisplayName("the body is copied into a directory of its own and never past the size cap by more than a buffer")
  void bodyIsCopiedBounded() throws Exception {
    String token = issue(false);
    ArgumentCaptor<NeoAttachmentUpload> captor = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    storeAnswers(created());
    HttpServletRequest endless = putTo(token);
    when(endless.getInputStream()).thenReturn(new ServletInputStream() {
      @Override
      public int read() {
        return 'x';
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        Arrays.fill(buffer, offset, offset + length, (byte) 'x');
        return length;
      }

      @Override
      public boolean isFinished() {
        return false;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        // blocking reads only
      }
    });
    NeoAttachmentUploadEndpoint.handle(endless, response);
    storeMock.verify(() -> NeoAttachmentStore.store(captor.capture()));

    File file = captor.getValue().getMaterializer().materialize("factura.pdf");
    try {
      long cap = NeoAttachmentPolicy.MAX_SIZE_MB * 1024L * 1024L;
      assertTrue(file.length() > cap, "one byte over the cap is what lets the policy refuse it");
      assertTrue(file.length() <= cap + 8192, "but never an unbounded body: " + file.length());
      Path parent = file.getParentFile().toPath();
      assertFalse(parent.equals(Path.of(System.getProperty("java.io.tmpdir"))),
          "own directory, so two uploads of the same name cannot overwrite each other");
    } finally {
      Files.deleteIfExists(file.toPath());
      Files.deleteIfExists(file.getParentFile().toPath());
    }
  }

  @Test
  @DisplayName("a link whose spec or entity no longer exists answers the generic invalid-link 403")
  void vanishedSpecIsTheGenericAnswer() throws Exception {
    String token = issue(false);
    when(dal.get(SFSpec.class, "SPEC1")).thenReturn(null);

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(403);
    assertTrue(body.toString().contains(NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE));
    assertEquals(Optional.of(NeoAttachmentUploadTickets.Status.COMPLETED),
        tickets.peek(token).map(NeoAttachmentUploadTickets.PendingUpload::getStatus));
  }

  @Test
  @DisplayName("W4: a spec switched off (inactive or hidden from the MCP) within the TTL is refused")
  void specSwitchedOffIsRefused() throws Exception {
    String token = issue(false);
    when(activeSpec.isShowInMCP()).thenReturn(false);

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(403);
    assertTrue(body.toString().contains(NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE));
    storeMock.verify(() -> NeoAttachmentStore.store(any()), never());

    String second = issue(false);
    when(activeSpec.isShowInMCP()).thenReturn(true);
    when(activeSpec.isActive()).thenReturn(false);
    NeoAttachmentUploadEndpoint.handle(putTo(second), response);
    storeMock.verify(() -> NeoAttachmentStore.store(any()), never());
  }

  private static long attachmentTempDirs() throws IOException {
    try (java.util.stream.Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return entries.filter(p -> p.getFileName().toString().startsWith("etgo-attachment-")).count();
    }
  }

  @Test
  @DisplayName("W2: a body copy that fails (client abort) leaves no temp file or directory behind")
  void abortedCopyLeavesNothing() throws Exception {
    String token = issue(false);
    ArgumentCaptor<NeoAttachmentUpload> captor = ArgumentCaptor.forClass(NeoAttachmentUpload.class);
    storeAnswers(created());
    HttpServletRequest aborting = putTo(token);
    when(aborting.getInputStream()).thenReturn(new ServletInputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("client aborted");
      }

      @Override
      public boolean isFinished() {
        return false;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        // blocking reads only
      }
    });
    NeoAttachmentUploadEndpoint.handle(aborting, response);
    storeMock.verify(() -> NeoAttachmentStore.store(captor.capture()));
    long before = attachmentTempDirs();

    org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
        () -> captor.getValue().getMaterializer().materialize("factura.pdf"));

    assertEquals(before, attachmentTempDirs());
  }

  @Test
  @DisplayName("N2: a failure writing the answer after a successful commit does not roll back again")
  void noRollbackAfterCommit() throws Exception {
    String token = issue(false);
    storeAnswers(created());
    when(response.getWriter()).thenThrow(new IOException("socket closed"));

    org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
        () -> NeoAttachmentUploadEndpoint.handle(putTo(token), response));

    verify(dal).commitAndClose();
    verify(dal, never()).rollbackAndClose();
  }

  @Test
  @DisplayName("a document type flagged as attachment-less AFTER the link was issued is refused 405 and the link is spent")
  void flagSetAfterIssueIsRefused() throws Exception {
    String token = issue(false);
    targetMock.when(() -> NeoAttachmentTarget.resolve(any(), any(), eq("RECORD1"), anyBoolean()))
        .thenThrow(new NeoAttachmentTarget.Refused(405, "Attachments are not available for this "
            + "document type, as in the application"));

    NeoAttachmentUploadEndpoint.handle(putTo(token), response);

    verify(response).setStatus(405);
    storeMock.verify(() -> NeoAttachmentStore.store(any()), never());
    assertEquals(NeoAttachmentUploadTickets.Status.COMPLETED,
        tickets.peek(token).orElseThrow().getStatus());
  }

  // ── a link whose file is staged for data extraction (ETP-5715) ─────────────

  private static HttpServletRequest putBytes(String token, byte[] content, long declaredLength)
      throws IOException {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getPathInfo()).thenReturn(NeoAttachmentUploadEndpoint.PATH + token);
    when(request.getContentLengthLong()).thenReturn(declaredLength);
    when(request.getInputStream()).thenReturn(streamOf(content));
    return request;
  }

  private static final byte[] PDF_BYTES = "%PDF-1.4 body".getBytes(StandardCharsets.US_ASCII);

  /** Runs the body with the staged store resolving to a real one under the temp directory. */
  private interface StagedScenario {
    void run(NeoStagedDocuments staged) throws Exception;
  }

  private void withStaged(StagedScenario scenario) throws Exception {
    NeoStagedDocuments staged = new NeoStagedDocuments(java.time.Clock.systemUTC(), stagedRoot);
    try (MockedStatic<NeoStagedDocuments> stagedMock = mockStatic(NeoStagedDocuments.class)) {
      stagedMock.when(NeoStagedDocuments::getInstance).thenReturn(staged);
      scenario.run(staged);
    }
  }

  private String issueStaged(NeoStagedDocuments staged, String fileName) {
    Session session = new Session(CLIENT, ORG, USER, ROLE, WAREHOUSE);
    String stagedId = staged.reserve(session, "purchase-invoice", fileName);
    return tickets.issue(session, Subject.staged("SPEC1", "ENTITY1", fileName, stagedId));
  }

  @Test
  @DisplayName("staged: the PDF is kept for the session, answered 201 with its staged id, nothing is attached")
  void stagedPdfIsKept() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");
      String stagedId = tickets.peek(token).orElseThrow().getSubject().getStagedDocumentId();

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(201);
      JSONObject answer = new JSONObject(body.toString());
      assertEquals(stagedId, answer.getString("stagedDocumentId"));
      assertEquals("factura.pdf", answer.getString("name"));
      assertEquals(PDF_BYTES.length, answer.getLong("size"));
      NeoStagedDocuments.Staged kept = staged.find(stagedId,
          new Session(CLIENT, ORG, USER, ROLE, WAREHOUSE)).orElseThrow();
      assertTrue(kept.isStored());
      assertEquals("%PDF-1.4 body", Files.readString(kept.getFile()));
      storeMock.verify(() -> NeoAttachmentStore.store(any()), never());
      assertEquals(stagedId, tickets.peek(token).orElseThrow().getAttachmentId());
      assertEquals(NeoAttachmentUploadTickets.Status.COMPLETED,
          tickets.peek(token).orElseThrow().getStatus());
      verify(dal).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    });
  }

  @Test
  @DisplayName("staged: runs as the session that asked for the link, authorized for WRITING the spec with no record")
  void stagedRunsAsTheSessionAndChecksWriteAccess() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      secureMock.verify(() -> SecureWebServicesUtils.createContext(USER, ROLE, ORG, WAREHOUSE,
          CLIENT));
      contextMock.verify(() -> OBContext.setOBContext(sessionContext));
      contextMock.verify(() -> OBContext.setOBContext(previousContext));
      targetMock.verify(() -> NeoAttachmentTarget.requireWriteAccess(any(), any()));
      targetMock.verify(() -> NeoAttachmentTarget.resolve(any(), any(), any(), anyBoolean()),
          never());
    });
  }

  @Test
  @DisplayName("staged: the link works once; a second PUT is the generic 403")
  void stagedLinkIsSingleUse() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");
      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);
      verify(response).setStatus(201);

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(403);
      assertTrue(body.toString().contains(NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE));
    });
  }

  @Test
  @DisplayName("staged: content that is not a PDF is refused with the policy's text, kept nowhere, link not burned")
  void stagedNonPdfContentIsRefused() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");
      String stagedId = tickets.peek(token).orElseThrow().getSubject().getStagedDocumentId();
      byte[] exe = "MZ this is an executable".getBytes(StandardCharsets.US_ASCII);

      NeoAttachmentUploadEndpoint.handle(putBytes(token, exe, exe.length), response);

      verify(response).setStatus(400);
      assertTrue(body.toString().contains("File content does not match its .pdf extension"),
          body.toString());
      assertFalse(staged.find(stagedId, new Session(CLIENT, ORG, USER, ROLE, WAREHOUSE))
          .orElseThrow().isStored());
      try (java.util.stream.Stream<Path> files = Files.walk(stagedRoot)) {
        assertEquals(0, files.filter(Files::isRegularFile).count(), "the rejected file is deleted");
      }
      assertTrue(tickets.claim(token).isPresent(), "the same URL accepts the corrected file");
    });
  }

  @Test
  @DisplayName("staged: a declared size over 10 MB is refused before a byte is read, link not burned")
  void stagedOversizedIsRefused() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, 11L * 1024 * 1024), response);

      verify(response).setStatus(400);
      assertTrue(body.toString().contains("File exceeds the maximum allowed size of 10 MB"),
          body.toString());
      assertTrue(tickets.claim(token).isPresent());
    });
  }

  private static final byte[] PNG_BYTES = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A,
      0x1A, 0x0A, 0x00 };
    @Test
  @DisplayName("staged: WEBP bytes named .webp are kept, answered 201")
  void stagedWebpIsKept() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "foto.webp");
      String stagedId = tickets.peek(token).orElseThrow().getSubject().getStagedDocumentId();

      NeoAttachmentUploadEndpoint.handle(putBytes(token, NeoAttachmentTestFixtures.WEBP, NeoAttachmentTestFixtures.WEBP.length), response);

      verify(response).setStatus(201);
      JSONObject answer = new JSONObject(body.toString());
      assertEquals("foto.webp", answer.getString("name"));
      assertTrue(staged.find(stagedId, new Session(CLIENT, ORG, USER, ROLE, WAREHOUSE))
          .orElseThrow().isStored());
    });
  }

  @Test
  @DisplayName("staged: PNG bytes named .pdf are refused as a mismatch, link not burned")
  void stagedPngNamedPdfIsAMismatch() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "x.pdf");

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PNG_BYTES, PNG_BYTES.length), response);

      verify(response).setStatus(400);
      assertTrue(body.toString().contains("File content does not match its .pdf extension"),
          body.toString());
      assertTrue(tickets.claim(token).isPresent());
    });
  }

  @Test
  @DisplayName("staged: a .docx is refused with the PDF-or-image message")
  void stagedDocxIsRefused() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "x.docx");

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(400);
      assertTrue(body.toString().contains("Only a PDF or an image (pdf, png, jpg, jpeg, webp)"),
          body.toString());
    });
  }

  @Test
  @DisplayName("staged: a file that is not named .pdf is refused: only a PDF can be read")
  void stagedOnlyPdf() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.docx");

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(400);
      assertTrue(body.toString().contains("Only a PDF"), body.toString());
      assertTrue(tickets.claim(token).isPresent());
    });
  }

  @Test
  @DisplayName("staged: a document that expired before the bytes arrived is the generic 403, nothing kept")
  void stagedDocumentGoneIsForbidden() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");
      String stagedId = tickets.peek(token).orElseThrow().getSubject().getStagedDocumentId();
      staged.discard(stagedId);

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(403);
      assertTrue(body.toString().contains(NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE));
      assertEquals(NeoAttachmentUploadTickets.Status.COMPLETED,
          tickets.peek(token).orElseThrow().getStatus(), "a link that can never work is spent");
      try (java.util.stream.Stream<Path> files = Files.walk(stagedRoot)) {
        assertEquals(0, files.filter(Files::isRegularFile).count());
      }
    });
  }

  @Test
  @DisplayName("staged: a role that lost write access since the link was issued is refused and the link is spent")
  void stagedRoleLostAccess() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");
      targetMock.when(() -> NeoAttachmentTarget.requireWriteAccess(any(), any()))
          .thenThrow(new NeoAttachmentTarget.Refused(403, NeoAttachmentAuthorizer.ACCESS_DENIED));

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(403);
      assertEquals(NeoAttachmentUploadTickets.Status.COMPLETED,
          tickets.peek(token).orElseThrow().getStatus());
    });
  }

  @Test
  @DisplayName("staged: a spec switched off after the link was issued answers the generic 403")
  void stagedSpecSwitchedOff() throws Exception {
    withStaged(staged -> {
      String token = issueStaged(staged, "factura.pdf");
      when(activeSpec.isShowInMCP()).thenReturn(false);

      NeoAttachmentUploadEndpoint.handle(putBytes(token, PDF_BYTES, PDF_BYTES.length), response);

      verify(response).setStatus(403);
      assertTrue(body.toString().contains(NeoAttachmentUploadEndpoint.INVALID_LINK_MESSAGE));
    });
  }
}
