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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;

/**
 * Unit tests for {@link AnonymousAttachmentStreamer}, the session-less lookup and streaming that the
 * signed email download link and the customer portal's PDF share (ETP-6209 QA BUG-1).
 *
 * <p>The file-name tests moved here from {@code NeoDocumentDownloadServiceTest} together with the
 * methods they exercise.
 *
 * @covers com.etendoerp.go.schemaforge.AnonymousAttachmentStreamer
 */
public class AnonymousAttachmentStreamerTest {

  private static final byte[] FILE_BYTES = "PDF-BYTES".getBytes(StandardCharsets.UTF_8);

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  private MockedStatic<OBDal> obDalStatic;
  private OBDal dal;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @After
  public void tearDown() {
    obDalStatic.close();
    NeoAttachmentsHelper.clearTableIdCache();
  }

  @Test
  public void findsATenantsMainAttachmentUnderASystemContextAndRefusesAnotherClients() {
    // A portal or email-link request runs as SYSTEM: a tenant-ownership check would read every
    // tenant's attachment as "none". The client check against the caller's credential is the
    // boundary instead.
    Client system = mock(Client.class);
    when(system.getId()).thenReturn("0");
    OBContext systemContext = mock(OBContext.class);
    when(systemContext.getCurrentClient()).thenReturn(system);
    stubTableAndMainLookup("TABLE1", "ATT1");
    Attachment attachment = attachment("CLIENT-1");
    when(dal.get(Attachment.class, "ATT1")).thenReturn(attachment);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<TenantOwnership> ownership = mockStatic(TenantOwnership.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(systemContext);

      assertSame(attachment, AnonymousAttachmentStreamer.findMainAttachmentOfClient("C_Invoice",
          "INV-1", "CLIENT-1"));
      assertNull("another client's attachment", AnonymousAttachmentStreamer
          .findMainAttachmentOfClient("C_Invoice", "INV-1", "CLIENT-2"));

      ownership.verifyNoInteractions();
    }
  }

  @Test
  public void noTableNoMainAttachmentOrAMissingArgumentFindsNothing() {
    assertNull(AnonymousAttachmentStreamer.findMainAttachmentOfClient(" ", "INV-1", "CLIENT-1"));
    assertNull(AnonymousAttachmentStreamer.findMainAttachmentOfClient("C_Invoice", "INV-1", null));
    verify(dal, never()).getSession();

    stubLookup(Collections.emptyList(), Collections.emptyList());
    assertNull("unknown table", AnonymousAttachmentStreamer.findMainAttachmentOfClient("X_Nope",
        "INV-1", "CLIENT-1"));
    NeoAttachmentsHelper.clearTableIdCache();
    stubTableAndMainLookup("TABLE1");
    assertNull("nothing marked as main", AnonymousAttachmentStreamer
        .findMainAttachmentOfClient("C_Invoice", "INV-1", "CLIENT-1"));
  }

  @Test
  public void streamAnswersTrueOnlyWhenTheFileWasWritten() throws Exception {
    File attachRoot = tempFolder.newFolder("attachments");
    File dir = new File(attachRoot, "318");
    Files.createDirectories(dir.toPath());
    Files.write(new File(dir, "stored.pdf").toPath(), FILE_BYTES);
    // Columns: file name, attach.path configured, expected.
    Object[][] cases = { { "stored.pdf", true, true }, { "missing.pdf", true, false },
        { "stored.pdf", false, false } };
    for (Object[] c : cases) {
      Attachment attachment = attachment("CLIENT-1");
      when(attachment.getName()).thenReturn((String) c[0]);
      when(attachment.getPath()).thenReturn("318");
      Properties properties = new Properties();
      if ((Boolean) c[1]) {
        properties.setProperty("attach.path", attachRoot.getAbsolutePath());
      }
      OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
      when(provider.getOpenbravoProperties()).thenReturn(properties);
      HttpServletResponse response = mock(HttpServletResponse.class);
      ServletOutputStream body = mock(ServletOutputStream.class);
      when(response.getOutputStream()).thenReturn(body);
      when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

      boolean written;
      try (MockedStatic<OBPropertiesProvider> props = mockStatic(OBPropertiesProvider.class)) {
        props.when(OBPropertiesProvider::getInstance).thenReturn(provider);
        written = AnonymousAttachmentStreamer.stream(attachment, response, "test");
      }

      assertEquals(Arrays.toString(c), c[2], written);
      if (written) {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(body).write(bytes.capture());
        assertArrayEquals(FILE_BYTES, bytes.getValue());
      } else {
        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        verify(body, never()).write(any(byte[].class));
      }
    }
  }

  /** ETP-4571 — a legacy HTML type is never echoed; a blank name keeps the old {@code document.pdf}. */
  @Test
  public void streamSendsHardenedHeadersForLegacyActiveAndBlankNamedRecords() throws Exception {
    File attachRoot = tempFolder.newFolder("attachments2");
    File dir = new File(attachRoot, "318");
    Files.createDirectories(dir.toPath());
    Files.write(new File(dir, "page.html").toPath(), FILE_BYTES);
    Files.write(new File(dir, "document.pdf").toPath(), FILE_BYTES);
    String[][] cases = { { "page.html", "text/html", "application/octet-stream", "page.html" },
        { "", "text/html", "application/pdf", "document.pdf" } };
    for (String[] c : cases) {
      Attachment attachment = attachment("CLIENT-1");
      when(attachment.getName()).thenReturn(c[0]);
      when(attachment.getDataType()).thenReturn(c[1]);
      when(attachment.getPath()).thenReturn("318");
      Properties properties = new Properties();
      properties.setProperty("attach.path", attachRoot.getAbsolutePath());
      OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
      when(provider.getOpenbravoProperties()).thenReturn(properties);
      HttpServletResponse response = mock(HttpServletResponse.class);
      when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

      try (MockedStatic<OBPropertiesProvider> props = mockStatic(OBPropertiesProvider.class)) {
        props.when(OBPropertiesProvider::getInstance).thenReturn(provider);
        AnonymousAttachmentStreamer.stream(attachment, response, "test");
      }

      verify(response).setContentType(c[2]);
      verify(response).setHeader("X-Content-Type-Options", "nosniff");
      verify(response).setHeader("Cache-Control", "no-store");
      verify(response).setHeader("Content-Disposition",
          NeoDownloadHeaders.contentDisposition(c[3]));
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static Attachment attachment(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn("TABLE1");
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn("ATT1");
    when(attachment.getClient()).thenReturn(client);
    when(attachment.getTable()).thenReturn(table);
    when(attachment.getRecord()).thenReturn("INV-1");
    return attachment;
  }

  private void stubTableAndMainLookup(String tableId, String... mainAttachmentIds) {
    stubLookup(Collections.singletonList(tableId), Arrays.asList(mainAttachmentIds));
  }

  /** The ad_table and C_File native lookups, as {@code NeoDocumentDownloadServiceTest} stubs them. */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  private void stubLookup(List<String> tableIds, List<String> mainAttachmentIds) {
    Session session = mock(Session.class);
    NativeQuery tableQuery = mock(NativeQuery.class);
    NativeQuery mainQuery = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("ad_table"))))
        .thenReturn(tableQuery);
    when(tableQuery.setParameter(anyString(), any())).thenReturn(tableQuery);
    when(tableQuery.list()).thenReturn(tableIds);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("C_File"))))
        .thenReturn(mainQuery);
    when(mainQuery.setParameter(anyString(), any())).thenReturn(mainQuery);
    when(mainQuery.list()).thenReturn(mainAttachmentIds);
  }
}
