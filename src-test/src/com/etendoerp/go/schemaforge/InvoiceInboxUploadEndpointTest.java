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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.roles.TemplateRoleWindowAccess;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * The invoice inbox upload endpoint: the order of its whole-request refusals (flag, window,
 * multipart, organization, size), how the multipart body is read and filtered, the file-count
 * rules, and how the per-file results are aggregated. The per-file work ({@link
 * InvoiceInboxIntake#ingest}) is mocked here; its own behavior is covered by
 * {@code InvoiceInboxIntakeTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxUploadEndpoint
 */
class InvoiceInboxUploadEndpointTest {

  private static final String CLIENT_ID = "CLIENT-ID-0000000000000000000001";
  private static final String ORG_ID = "ORG-ID-000000000000000000000001";
  private static final String MULTIPART = "multipart/form-data; boundary=x";

  private MockedStatic<InvoiceInboxFlag> flag;
  private MockedStatic<NeoAccessHelper> access;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<InvoiceInboxIntake> intake;
  private MockedStatic<OBMessageUtils> messages;
  /** The background worker: never started for real from a unit test. */
  private MockedStatic<InvoiceInboxProcessor> processor;

  @BeforeEach
  void openStaticMocks() {
    flag = mockStatic(InvoiceInboxFlag.class);
    access = mockStatic(NeoAccessHelper.class);
    obContext = mockStatic(OBContext.class);
    intake = mockStatic(InvoiceInboxIntake.class);
    // Unstubbed messageBD answers null: every text falls back to its English default.
    messages = mockStatic(OBMessageUtils.class);
    processor = mockStatic(InvoiceInboxProcessor.class);
  }

  @AfterEach
  void closeStaticMocks() {
    processor.close();
    messages.close();
    intake.close();
    obContext.close();
    access.close();
    flag.close();
  }

  // ---------------------------------------------------------------- whole-request refusals

  @Test
  void flagOffAnswers403FeatureDisabledWithoutReadingTheRequestOrCheckingAccess() throws Exception {
    flag.when(InvoiceInboxFlag::isEnabled).thenReturn(false);
    HttpServletRequest request = request(MULTIPART, 100L);

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertRefusal(response, 403, "feature_disabled");
    verify(request, never()).getParts();
    verify(request, never()).getContentType();
    access.verifyNoInteractions();
    obContext.verifyNoInteractions();
    intake.verifyNoInteractions();
  }

  @Test
  void noWindowAccessAnswers403AccessDeniedBeforeReadingTheBody() throws Exception {
    flag.when(InvoiceInboxFlag::isEnabled).thenReturn(true);
    access.when(() -> NeoAccessHelper.hasWindowAccess(
        TemplateRoleWindowAccess.INVOICE_INBOX_WINDOW_ID, "POST")).thenReturn(false);
    HttpServletRequest request = request(MULTIPART, 100L);

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertRefusal(response, 403, "access_denied");
    access.verify(() -> NeoAccessHelper.hasWindowAccess(
        TemplateRoleWindowAccess.INVOICE_INBOX_WINDOW_ID, "POST"));
    verify(request, never()).getParts();
    obContext.verifyNoInteractions();
    intake.verifyNoInteractions();
  }

  @Test
  void aNonMultipartRequestAnswers400NotMultipart() throws Exception {
    allowed();
    for (String contentType : new String[] { "application/json", null }) {
      HttpServletRequest request = request(contentType, 100L);

      assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 400, "not_multipart");

      verify(request, never()).getParts();
    }
    intake.verifyNoInteractions();
  }

  @Test
  void theMultipartContentTypeIsMatchedCaseInsensitively() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request("Multipart/Form-Data; boundary=x", 100L);
    when(request.getParts()).thenReturn(List.of());

    // Past the multipart check the next refusal is the empty one.
    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 400, "no_files");
  }

  @Test
  void theStarOrganizationIsRefused403OrgNotAllowed() throws Exception {
    allowed();
    contextOf("0", Set.of("0", ORG_ID));
    HttpServletRequest request = request(MULTIPART, 100L);

    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 403, "org_not_allowed");

    verify(request, never()).getParts();
    intake.verifyNoInteractions();
  }

  @Test
  void anOrganizationOutsideTheWritableOnesIsRefused403OrgNotAllowed() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of("SOME-OTHER-ORG"));
    HttpServletRequest request = request(MULTIPART, 100L);

    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 403, "org_not_allowed");

    verify(request, never()).getParts();
    intake.verifyNoInteractions();
  }

  @Test
  void aDeclaredSizeOverTheRequestLimitAnswers413WithoutReadingTheBody() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request(MULTIPART, NeoAttachmentPolicy.MAX_REQUEST_BYTES + 1);

    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 413, "request_too_large");

    verify(request, never()).getParts();
    intake.verifyNoInteractions();
  }

  @Test
  void aDeclaredSizeExactlyAtTheRequestLimitIsStillRead() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request(MULTIPART, NeoAttachmentPolicy.MAX_REQUEST_BYTES);
    when(request.getParts()).thenReturn(List.of());

    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 400, "no_files");

    verify(request).getParts();
  }

  @Test
  void aContainerLimitRefusalWhileReadingThePartsAnswers413() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request(MULTIPART, -1L);
    when(request.getParts()).thenThrow(new IllegalStateException("too large"));

    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 413, "request_too_large");

    intake.verifyNoInteractions();
  }

  @Test
  void anUnreadableMultipartBodyAnswers400NotMultipart() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest unreadable = request(MULTIPART, -1L);
    when(unreadable.getParts()).thenThrow(new IOException("broken pipe"));
    HttpServletRequest malformed = request(MULTIPART, -1L);
    when(malformed.getParts()).thenThrow(new ServletException("bad boundary"));

    assertRefusal(InvoiceInboxUploadEndpoint.handle(unreadable), 400, "not_multipart");
    assertRefusal(InvoiceInboxUploadEndpoint.handle(malformed), 400, "not_multipart");

    intake.verifyNoInteractions();
  }

  // ---------------------------------------------------------------- the files of the request

  @Test
  void onlyPartsNamedFilesWithASubmittedFileNameCount() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    Part counted = part("files", "invoice.pdf");
    Part otherName = part("attachments", "other.pdf");
    Part noFileName = part("files", null);
    Part blankFileName = part("files", "   ");
    HttpServletRequest request = request(MULTIPART, 100L);
    when(request.getParts()).thenReturn(List.of(otherName, noFileName, counted, blankFileName));
    ingestAcceptsEverything();

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertEquals(200, response.getHttpStatus());
    intake.verify(() -> InvoiceInboxIntake.ingest(counted, "invoice.pdf", CLIENT_ID, ORG_ID));
    intake.verify(() -> InvoiceInboxIntake.ingest(any(), any(), any(), any()), times(1));
    assertEquals(1, response.getBody().getInt("accepted"));
  }

  @Test
  void noFilePartsAnswer400NoFilesCreatesNothingAndStillDiscardsTheParts() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    Part field = part("description", null);
    HttpServletRequest request = request(MULTIPART, 100L);
    when(request.getParts()).thenReturn(List.of(field));

    assertRefusal(InvoiceInboxUploadEndpoint.handle(request), 400, "no_files");

    intake.verifyNoInteractions();
    verify(field).delete();
  }

  @Test
  void moreThanTwentyFilesAnswer400TooManyFilesCreatesNothingAndDiscardsEveryPart()
      throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    List<Part> parts = parts(21);
    HttpServletRequest request = request(MULTIPART, 100L);
    when(request.getParts()).thenReturn(parts);

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertRefusal(response, 400, "too_many_files");
    intake.verifyNoInteractions();
    for (Part part : parts) {
      verify(part).delete();
    }
  }

  @Test
  void exactlyTwentyFilesAreAllAccepted() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    List<Part> parts = parts(20);
    HttpServletRequest request = request(MULTIPART, 100L);
    when(request.getParts()).thenReturn(parts);
    ingestAcceptsEverything();

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertEquals(200, response.getHttpStatus());
    assertEquals(20, response.getBody().getInt("accepted"));
    assertEquals(0, response.getBody().getInt("rejected"));
    assertEquals(20, response.getBody().getJSONArray("results").length());
    intake.verify(() -> InvoiceInboxIntake.ingest(any(), any(), eq(CLIENT_ID), eq(ORG_ID)),
        times(20));
  }

  @Test
  void aMixedBatchAnswers200WithCountsAndResultsInTheOrderOfTheParts() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    Part first = part("files", "a.pdf");
    Part second = part("files", "b.exe");
    Part third = part("files", "c.png");
    HttpServletRequest request = request(MULTIPART, 100L);
    when(request.getParts()).thenReturn(List.of(first, second, third));
    intake.when(() -> InvoiceInboxIntake.ingest(any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          String name = invocation.getArgument(1);
          return name.endsWith(".exe")
              ? InvoiceInboxIntake.Result.rejected(name, "unsupported_type", "not supported")
              : InvoiceInboxIntake.Result.accepted(name, "ID-" + name);
        });

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(2, body.getInt("accepted"));
    assertEquals(1, body.getInt("rejected"));
    JSONArray results = body.getJSONArray("results");
    assertEquals(3, results.length());
    assertEquals("a.pdf", results.getJSONObject(0).getString("fileName"));
    assertEquals("accepted", results.getJSONObject(0).getString("status"));
    assertEquals("ID-a.pdf", results.getJSONObject(0).getString("incomingDocumentId"));
    assertEquals("b.exe", results.getJSONObject(1).getString("fileName"));
    assertEquals("rejected", results.getJSONObject(1).getString("status"));
    assertEquals("unsupported_type", results.getJSONObject(1).getString("code"));
    assertEquals("c.png", results.getJSONObject(2).getString("fileName"));
    assertEquals("accepted", results.getJSONObject(2).getString("status"));
  }

  @Test
  void everyPartIsDiscardedAfterProcessingEvenWhenADeleteFails() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    Part first = part("files", "a.pdf");
    Part second = part("files", "b.pdf");
    doThrow(new IOException("locked")).when(first).delete();
    HttpServletRequest request = request(MULTIPART, 100L);
    when(request.getParts()).thenReturn(List.of(first, second));
    ingestAcceptsEverything();

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, response.getBody().getInt("accepted"));
    verify(first).delete();
    verify(second).delete();
  }

  // ---------------------------------------------------------------- fixtures

  /** The flag is on and the caller has write access to the window. */
  private void allowed() {
    flag.when(InvoiceInboxFlag::isEnabled).thenReturn(true);
    access.when(() -> NeoAccessHelper.hasWindowAccess(
        TemplateRoleWindowAccess.INVOICE_INBOX_WINDOW_ID, "POST")).thenReturn(true);
  }

  /** Installs a current context on the given organization with the given writable ones. */
  private void contextOf(String orgId, Set<String> writableOrgs) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(orgId);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getCurrentOrganization()).thenReturn(organization);
    when(context.getWritableOrganizations()).thenReturn(writableOrgs);
    obContext.when(OBContext::getOBContext).thenReturn(context);
  }

  private void ingestAcceptsEverything() {
    intake.when(() -> InvoiceInboxIntake.ingest(any(), any(), any(), any()))
        .thenAnswer(invocation -> InvoiceInboxIntake.Result.accepted(
            invocation.getArgument(1), "ID-" + invocation.getArgument(1)));
  }

  private static HttpServletRequest request(String contentType, long contentLength) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn(contentType);
    when(request.getContentLengthLong()).thenReturn(contentLength);
    return request;
  }

  private static Part part(String name, String submittedFileName) {
    Part part = mock(Part.class);
    when(part.getName()).thenReturn(name);
    when(part.getSubmittedFileName()).thenReturn(submittedFileName);
    return part;
  }

  private static List<Part> parts(int count) {
    List<Part> parts = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      parts.add(part("files", "file-" + i + ".pdf"));
    }
    return parts;
  }

  private static void assertRefusal(NeoResponse response, int status, String code)
      throws Exception {
    assertNotNull(response);
    assertEquals(status, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(code, error.getString("code"));
    assertNotNull(error.optString("message", null));
  }

  // ---------------------------------------------------------------- hand-off to the worker (ETP-6048)

  @Test
  void onlyTheAcceptedRowsAreSubmittedToTheWorkerAndOnlyOnce() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request(MULTIPART, 100L);
    List<Part> files = List.of(part("files", "a.pdf"), part("files", "b.exe"),
        part("files", "c.png"));
    when(request.getParts()).thenReturn(files);
    intake.when(() -> InvoiceInboxIntake.ingest(any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          String name = invocation.getArgument(1);
          return name.endsWith(".exe")
              ? InvoiceInboxIntake.Result.rejected(name, "unsupported_type", "not supported")
              : InvoiceInboxIntake.Result.accepted(name, "ID-" + name);
        });

    InvoiceInboxUploadEndpoint.handle(request);

    processor.verify(() -> InvoiceInboxProcessor.submit("ID-a.pdf"), times(1));
    processor.verify(() -> InvoiceInboxProcessor.submit("ID-c.png"), times(1));
    processor.verify(() -> InvoiceInboxProcessor.submit(anyString()), times(2));
  }

  @Test
  void nothingIsSubmittedWhenEveryFileIsRejectedOrTheRequestIsRefused() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request(MULTIPART, 100L);
    List<Part> rejectedFile = List.of(part("files", "b.exe"));
    when(request.getParts()).thenReturn(rejectedFile);
    intake.when(() -> InvoiceInboxIntake.ingest(any(), any(), any(), any())).thenReturn(
        InvoiceInboxIntake.Result.rejected("b.exe", "unsupported_type", "not supported"));
    InvoiceInboxUploadEndpoint.handle(request);

    HttpServletRequest tooMany = request(MULTIPART, 100L);
    List<Part> twentyOne = parts(21);
    when(tooMany.getParts()).thenReturn(twentyOne);
    InvoiceInboxUploadEndpoint.handle(tooMany);

    processor.verifyNoInteractions();
  }

  @Test
  void aWorkerThatFailsToQueueDoesNotChangeTheAnswerOfACommittedUpload() throws Exception {
    allowed();
    contextOf(ORG_ID, Set.of(ORG_ID));
    HttpServletRequest request = request(MULTIPART, 100L);
    List<Part> files = List.of(part("files", "a.pdf"), part("files", "b.pdf"));
    when(request.getParts()).thenReturn(files);
    ingestAcceptsEverything();
    processor.when(() -> InvoiceInboxProcessor.submit("ID-a.pdf"))
        .thenThrow(new IllegalStateException("executor gone"));
    processor.when(() -> InvoiceInboxProcessor.submit("ID-b.pdf"))
        .thenThrow(new NoClassDefFoundError("worker could not initialize"));

    NeoResponse response = InvoiceInboxUploadEndpoint.handle(request);

    assertEquals(200, response.getHttpStatus());
    assertEquals(2, response.getBody().getInt("accepted"));
    assertEquals(0, response.getBody().getInt("rejected"));
    assertEquals("ID-a.pdf", response.getBody().getJSONArray("results").getJSONObject(0)
        .getString("incomingDocumentId"));
    processor.verify(() -> InvoiceInboxProcessor.submit(anyString()), times(2));
  }
}
