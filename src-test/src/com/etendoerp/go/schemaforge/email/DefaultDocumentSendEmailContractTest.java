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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link DefaultDocumentSendEmailContract#resolve}, covering ETP-4717's reopened
 * regression: "si se agrega un mensaje se pierde el link del adjunto" — an operator-authored
 * message must not drop the document download link from the rendered body. Exercised through the
 * public {@link DefaultDocumentSendEmailContract#resolve} entry point since {@code resolveBody}/
 * {@code buildTemplateData} are private.
 */
public class DefaultDocumentSendEmailContractTest {

  private static final String CONTRACT_NAME = "test-document-send";
  private static final String RECORD_ID = "doc-1";
  private static final String DOCUMENT_NUMBER = "doc-1";
  private static final String DOWNLOAD_LINK = "https://example.test/download/doc-1";
  private static final String RECIPIENT_EMAIL = "customer@example.com";
  private static final String LINK_ANCHOR =
      "<a href=\"" + DOWNLOAD_LINK + "\">" + DOWNLOAD_LINK + "</a>";
  private static final String DEFAULT_BODY =
      "<p>Le enviamos su Documento " + DOCUMENT_NUMBER + ".</p>"
          + "<p>Puede descargarlo desde este enlace: " + LINK_ANCHOR + "</p>";

  @Test
  public void noMessageEditsKeepsDefaultBodyUnchanged() throws Exception {
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}"));

    // Regression guard: an untouched send must stay byte-identical to pre-fix behavior.
    assertEquals(DEFAULT_BODY, body);
  }

  @Test
  public void subjectOnlyEditFallsBackToDefaultBody() throws Exception {
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID
        + "\",\"messageEdits\":{\"subject\":\"Asunto nuevo\"}}"));

    // Subject and message overrides are independent; editing only the subject must not touch body.
    assertEquals(DEFAULT_BODY, body);
  }

  @Test
  public void messageOnlyEditKeepsDownloadLink() throws Exception {
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID
        + "\",\"messageEdits\":{\"message\":\"Aqui tiene su pedido\"}}"));

    assertTrue("expected operator message in body: " + body,
        body.contains("Aqui tiene su pedido"));
    assertTrue("expected download link to survive a message edit: " + body,
        body.contains(LINK_ANCHOR));
  }

  @Test
  public void messageWithHtmlSpecialCharsIsEscapedButLinkIsNot() throws Exception {
    JSONObject edits = new JSONObject();
    edits.put("message", "<b>Hola</b> & \"saludos\"");
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);
    commandBody.put("messageEdits", edits);

    String body = resolveBody(commandBody);

    assertTrue("operator text must be escaped: " + body,
        body.contains("&lt;b&gt;Hola&lt;/b&gt; &amp; &quot;saludos&quot;"));
    assertTrue("download link must still be appended: " + body, body.contains(LINK_ANCHOR));
    assertFalse("the appended link markup must never be escaped: " + body,
        body.contains("&lt;a href"));
  }

  @Test
  public void multiLineMessageAppendsLinkParagraphAfterBrConversion() throws Exception {
    JSONObject edits = new JSONObject();
    edits.put("message", "Primera linea\r\nSegunda linea");
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);
    commandBody.put("messageEdits", edits);

    String body = resolveBody(commandBody);

    assertTrue("newlines in the operator message must become <br>: " + body,
        body.contains("Primera linea<br>Segunda linea"));
    assertTrue("download link must still be appended: " + body, body.contains(LINK_ANCHOR));
    // The newline-to-<br> pass must run only over the operator's message, never over the
    // appended link paragraph.
    assertFalse("the link paragraph must not be swallowed by the <br> conversion: " + body,
        body.contains("</a></p><br>"));
  }

  private String resolveBody(JSONObject commandBody) throws JSONException {
    EmailDocumentRecordResolver resolver = recordId -> Optional.of(
        new EmailDocumentRecord("Cliente", RECIPIENT_EMAIL, RECORD_ID, DOCUMENT_NUMBER, null,
            DOWNLOAD_LINK, "client-1"));
    DefaultDocumentSendEmailContract contract =
        new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", resolver);

    EmailContractCommand command = new EmailContractCommand(CONTRACT_NAME, commandBody);
    EmailRecipientResolution recipient = EmailRecipientResolution.serverResolved(RECIPIENT_EMAIL);

    EmailContractResolution resolution = contract.resolve(command, recipient);
    assertTrue("expected a ready resolution, got: " + resolution.getMessage(),
        resolution.isReady());

    return resolution.getProviderRequest().getData().getString("body");
  }
}
