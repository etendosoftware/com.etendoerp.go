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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.PreviewFile;

/**
 * Unit tests for {@link NeoPreviewFileService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Validation: invalid JSON body, blank required fields — no DB calls needed.</li>
 *   <li>{@code getPreviewFile}: cache miss (empty 200) and cache hit (200 with data).</li>
 *   <li>{@code savePreviewFile}: INSERT path (new record) and UPDATE path (existing record).</li>
 *   <li>{@code deletePreviewFile}: miss (404) and hit (200).</li>
 * </ul>
 *
 * <p>Uses the same {@code MockedStatic<OBDal> + MockedStatic<OBContext>} pattern as
 * {@link RejectQuotationHandlerTest} — no live DB required.
 */
public class NeoPreviewFileServiceTest {

  private static final String SPEC_NAME  = "purchase-invoice";
  private static final String RECORD_ID  = "rec-001";
  private static final String CLIENT_ID  = "client-001";
  private static final String ORG_ID     = "org-001";
  private static final String USER_ID    = "user-001";
  private static final String MIME_PDF   = "application/pdf";
  private static final String FILE_PDF   = "test.pdf";

  // ── savePreviewFile — validation (no mocks needed) ────────────────────────

  /**
   * A body that is not valid JSON must produce HTTP 400 with a message about
   * invalid JSON — the service must never attempt a DB call in this case.
   */
  @Test
  public void testSaveInvalidJsonBodyReturns400() throws JSONException {
    NeoResponse r = NeoPreviewFileService.savePreviewFile("not-a-json-string");
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
    assertTrue(r.getBody().getJSONObject("error").getString("message")
        .toLowerCase().contains("invalid json"));
  }

  /**
   * A valid JSON body with {@code specName} omitted must be rejected before any
   * DB interaction — the service validates all five required fields upfront.
   */
  @Test
  public void testSaveMissingSpecNameReturns400() throws JSONException {
    String body = new JSONObject()
        .put("recordId", RECORD_ID)
        .put("fileName", FILE_PDF)
        .put("mimeType", MIME_PDF)
        .put("fileData", "abc123")
        .toString();
    NeoResponse r = NeoPreviewFileService.savePreviewFile(body);
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /**
   * A valid JSON body with {@code recordId} omitted must produce HTTP 400.
   */
  @Test
  public void testSaveMissingRecordIdReturns400() throws JSONException {
    String body = new JSONObject()
        .put("specName", SPEC_NAME)
        .put("fileName", FILE_PDF)
        .put("mimeType", MIME_PDF)
        .put("fileData", "abc123")
        .toString();
    NeoResponse r = NeoPreviewFileService.savePreviewFile(body);
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  /**
   * A valid JSON body with {@code fileData} omitted must produce HTTP 400 —
   * storing a row without file content would be a silent data loss.
   */
  @Test
  public void testSaveMissingFileDataReturns400() throws JSONException {
    String body = new JSONObject()
        .put("specName", SPEC_NAME)
        .put("recordId", RECORD_ID)
        .put("fileName", FILE_PDF)
        .put("mimeType", MIME_PDF)
        .toString();
    NeoResponse r = NeoPreviewFileService.savePreviewFile(body);
    assertNotNull(r);
    assertEquals(400, r.getHttpStatus());
  }

  // ── getPreviewFile ────────────────────────────────────────────────────────

  /**
   * When no row exists for the (clientId, specName, recordId) tuple,
   * {@code getPreviewFile} must return HTTP 200 with an empty JSON object.
   * The React hook interprets an empty body as "no cached file" and shows
   * the drop zone instead of a cached document.
   */
  @Test
  public void testGetMissReturns200WithEmptyBody() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      setupContextMock(ctxMock);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBQuery<PreviewFile> query = mock(OBQuery.class);
      when(dal.createQuery(eq(PreviewFile.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      NeoResponse r = NeoPreviewFileService.getPreviewFile(SPEC_NAME, RECORD_ID);

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      assertFalse("Empty miss response must not include fileData",
          r.getBody().has("fileData"));
    }
  }

  /**
   * When a cached row exists, {@code getPreviewFile} must return HTTP 200
   * with the stored {@code fileName}, {@code mimeType}, and {@code fileData}.
   * The React hook uses these three fields to reconstruct the Blob URL.
   */
  @Test
  public void testGetHitReturns200WithFileData() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      setupContextMock(ctxMock);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      PreviewFile pf = mock(PreviewFile.class);
      when(pf.getFileName()).thenReturn("receipt.pdf");
      when(pf.getMIMEType()).thenReturn(MIME_PDF);
      when(pf.getFileData()).thenReturn("base64encodedpdfdata");

      @SuppressWarnings("unchecked")
      OBQuery<PreviewFile> query = mock(OBQuery.class);
      when(dal.createQuery(eq(PreviewFile.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(pf);

      NeoResponse r = NeoPreviewFileService.getPreviewFile(SPEC_NAME, RECORD_ID);

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      assertEquals("receipt.pdf",          r.getBody().getString("fileName"));
      assertEquals(MIME_PDF,      r.getBody().getString("mimeType"));
      assertEquals("base64encodedpdfdata", r.getBody().getString("fileData"));
    }
  }

  // ── savePreviewFile — INSERT path ─────────────────────────────────────────

  /**
   * When no existing row is found for the tuple, {@code savePreviewFile} must
   * execute an INSERT and return HTTP 200 with a non-empty {@code id} field.
   * The id is a newly generated 32-char uppercase UUID (no hyphens).
   */
  @Test
  public void testSaveInsertPathReturns200WithNewId() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      setupContextMock(ctxMock);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      @SuppressWarnings("unchecked")
      OBQuery<PreviewFile> query = mock(OBQuery.class);
      when(dal.createQuery(eq(PreviewFile.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(null);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("unchecked")
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.executeUpdate()).thenReturn(1);

      String body = new JSONObject()
          .put("specName", SPEC_NAME)
          .put("recordId", RECORD_ID)
          .put("fileName", "invoice.pdf")
          .put("mimeType", MIME_PDF)
          .put("fileData", "base64encodeddata")
          .toString();

      NeoResponse r = NeoPreviewFileService.savePreviewFile(body);

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      assertTrue("INSERT response must include a non-empty id", r.getBody().has("id"));
      assertFalse(r.getBody().getString("id").isEmpty());
    }
  }

  /**
   * When a row already exists for the tuple, {@code savePreviewFile} must
   * execute an UPDATE and return HTTP 200 with the existing row's id — not a
   * newly generated one. This confirms the upsert branch correctly reuses the PK.
   */
  @Test
  public void testSaveUpdatePathReturns200WithExistingId() throws JSONException {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      setupContextMock(ctxMock);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      PreviewFile existing = mock(PreviewFile.class);
      when(existing.getId()).thenReturn("EXISTINGID001");

      @SuppressWarnings("unchecked")
      OBQuery<PreviewFile> query = mock(OBQuery.class);
      when(dal.createQuery(eq(PreviewFile.class), anyString())).thenReturn(query);
      when(query.uniqueResult()).thenReturn(existing);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("unchecked")
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.executeUpdate()).thenReturn(1);

      String body = new JSONObject()
          .put("specName", SPEC_NAME)
          .put("recordId", RECORD_ID)
          .put("fileName", "invoice-v2.pdf")
          .put("mimeType", MIME_PDF)
          .put("fileData", "updatedencodeddata")
          .toString();

      NeoResponse r = NeoPreviewFileService.savePreviewFile(body);

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
      assertEquals("EXISTINGID001", r.getBody().getString("id"));
    }
  }

  // ── deletePreviewFile ─────────────────────────────────────────────────────

  /**
   * When the DELETE affects zero rows (file was never stored or already removed),
   * the service must return HTTP 404 so the client can distinguish "deleted now"
   * from "was already gone."
   */
  @Test
  public void testDeleteMissReturns404() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      setupContextMock(ctxMock);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("unchecked")
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.executeUpdate()).thenReturn(0);

      NeoResponse r = NeoPreviewFileService.deletePreviewFile(SPEC_NAME, RECORD_ID);

      assertNotNull(r);
      assertEquals(404, r.getHttpStatus());
    }
  }

  /**
   * When the DELETE removes exactly one row, the service must return HTTP 200
   * with an empty body — the React hook uses this to show the drop zone again.
   */
  @Test
  public void testDeleteHitReturns200() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      setupContextMock(ctxMock);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);

      @SuppressWarnings("unchecked")
      NativeQuery<Object> nq = mock(NativeQuery.class);
      when(session.createNativeQuery(anyString())).thenReturn(nq);
      when(nq.setParameter(anyString(), any())).thenReturn(nq);
      when(nq.executeUpdate()).thenReturn(1);

      NeoResponse r = NeoPreviewFileService.deletePreviewFile(SPEC_NAME, RECORD_ID);

      assertNotNull(r);
      assertEquals(200, r.getHttpStatus());
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void setupContextMock(MockedStatic<OBContext> ctxMock) {
    OBContext ctx = mock(OBContext.class);
    ctxMock.when(() -> OBContext.getOBContext()).thenReturn(ctx);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(ctx.getCurrentClient()).thenReturn(client);

    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(ctx.getCurrentOrganization()).thenReturn(org);

    User user = mock(User.class);
    when(user.getId()).thenReturn(USER_ID);
    when(ctx.getUser()).thenReturn(user);
  }
}
