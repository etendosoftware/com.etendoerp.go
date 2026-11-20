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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

/** ETP-4571 (SEC-09b/SEC-12): download responses are inert and never cached. */
public class NeoDownloadHeadersTest {

  @Test
  public void htmlStoredTypeIsNeverEchoed() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoDownloadHeaders.apply(response, "x.html");
    verify(response).setContentType("application/octet-stream");
    verify(response).setHeader("X-Content-Type-Options", "nosniff");
    verify(response).setHeader("Cache-Control", "no-store");
  }

  @Test
  public void storedTypeCannotOverrideExtensionType() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoDownloadHeaders.apply(response, "logo.svg");
    verify(response).setContentType("application/octet-stream");
  }

  @Test
  public void pdfKeepsItsTypeAndIsAttachmentWithSandboxCsp() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoDownloadHeaders.apply(response, "a.pdf");
    verify(response).setContentType("application/pdf");
    verify(response).setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
    verify(response).setHeader("Content-Disposition",
        NeoDownloadHeaders.contentDisposition("a.pdf"));
  }

  @Test
  public void dispositionIsAlwaysAttachmentAndHasNoInjectedLines() {
    String header = NeoDownloadHeaders.contentDisposition("a\r\nSet-Cookie: x=1\".pdf");
    assertTrue(header.startsWith("attachment;"));
    assertFalse(header.contains("\r"));
    assertFalse(header.contains("\n"));
    assertEquals(1, header.split("filename=\"", -1).length - 1);
  }

  @Test
  public void markPrivateJsonSetsNoStoreAndNosniff() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoDownloadHeaders.markPrivateJson(response);
    verify(response).setHeader("Cache-Control", "no-store");
    verify(response).setHeader("X-Content-Type-Options", "nosniff");
  }

  @Test
  public void blankNameFallsBackToDownload() {
    assertTrue(NeoDownloadHeaders.contentDisposition(null).contains("filename=\"download\""));
  }
}
