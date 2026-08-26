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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;

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
  /** The document link, however the layout chooses to present it. */
  private static final String LINK_ANCHOR = DOWNLOAD_LINK;
  /**
   * The default copy, as it appears inside the shared layout (ETP-5003). Asserted as a fragment
   * rather than as the whole body: the layout owns the markup around it, and pinning that here
   * would make every layout tweak fail a contract test. {@code EmailLayoutTest} pins the markup.
   */
  private static final String DEFAULT_COPY = "Le enviamos su Documento "
      + "<strong>" + DOCUMENT_NUMBER + "</strong>.";

  @Test
  public void noMessageEditsKeepsDefaultBodyUnchanged() throws Exception {
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}"));

    // Regression guard: an untouched send must stay byte-identical to pre-fix behavior.
    assertTrue("expected the default copy in body: " + body, body.contains(DEFAULT_COPY));
    assertTrue("expected the document link in body: " + body, body.contains(DOWNLOAD_LINK));
  }

  @Test
  public void subjectOnlyEditFallsBackToDefaultBody() throws Exception {
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID
        + "\",\"messageEdits\":{\"subject\":\"Asunto nuevo\"}}"));

    // Subject and message overrides are independent; editing only the subject must not touch body.
    assertTrue("expected the default copy in body: " + body, body.contains(DEFAULT_COPY));
    assertTrue("expected the document link in body: " + body, body.contains(DOWNLOAD_LINK));
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

  @Test
  public void operatorMarkersRenderAsEmphasis() throws Exception {
    // ETP-5003 — emphasis is expressed as **markers** in the message itself, so an operator who
    // edits the text can still bold a name or a number. Before this, editing dropped every bold
    // run and there was no way to put one back.
    JSONObject edits = new JSONObject();
    edits.put("message", "Hola **Cliente**, adjuntamos la **10000016**.");
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);
    commandBody.put("messageEdits", edits);

    String body = resolveBody(commandBody);

    assertTrue("operator markers must render as <strong>: " + body,
        body.contains("Hola <strong>Cliente</strong>, adjuntamos la <strong>10000016</strong>."));
    assertFalse("the raw markers must not survive into the email: " + body, body.contains("**"));
  }

  @Test
  public void operatorMessageReplacesTheGreetingInsteadOfStackingWithIt() throws Exception {
    // The greeting now lives inside the editable message, so the module must not compose a second
    // one — the customer would be greeted twice.
    JSONObject edits = new JSONObject();
    edits.put("message", "Buenas tardes, aqui va el documento.");
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);
    commandBody.put("messageEdits", edits);

    String body = resolveBody(commandBody);

    assertTrue("expected the operator greeting: " + body,
        body.contains("Buenas tardes, aqui va el documento."));
    assertFalse("the catalog greeting must not be added on top: " + body,
        body.contains("Hola, <strong>Cliente</strong>:"));
  }

  @Test
  public void aDefaultSendStillGreetsTheCustomer() throws Exception {
    // The catalog greeting remains the path for a command that carries no message at all.
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}"));

    assertTrue("expected the catalog greeting: " + body,
        body.contains("Hola, <strong>Cliente</strong>:"));
  }

  @Test
  public void documentSendCarriesTheOperatorAddressAsReplyTo() throws Exception {
    // ETP-5003 — the gateway sends from a verified noreply@ address, so the operator's own
    // address has to travel in Reply-To or the customer cannot answer the invoice.
    User user = mock(User.class);
    when(user.getEmail()).thenReturn("operator@example.com");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getUser()).thenReturn(user);

    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

      assertEquals("operator@example.com", resolveRequest(commandBody).getReplyTo());
    }
  }

  @Test
  public void documentSendOmitsReplyToWhenTheOperatorHasNoAddress() throws Exception {
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      assertNull(resolveRequest(commandBody).getReplyTo());
    }
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

  private EmailProviderRequest resolveRequest(JSONObject commandBody) {
    EmailDocumentRecordResolver resolver = recordId -> Optional.of(
        new EmailDocumentRecord("Cliente", RECIPIENT_EMAIL, RECORD_ID, DOCUMENT_NUMBER, null,
            DOWNLOAD_LINK, "client-1"));
    DefaultDocumentSendEmailContract contract =
        new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", resolver);

    EmailContractResolution resolution = contract.resolve(
        new EmailContractCommand(CONTRACT_NAME, commandBody),
        EmailRecipientResolution.serverResolved(RECIPIENT_EMAIL));
    assertTrue("expected a ready resolution, got: " + resolution.getMessage(),
        resolution.isReady());

    return resolution.getProviderRequest();
  }

  @Test
  public void opensTheSummaryBlockWithTheDocumentNumber() throws JSONException {
    String body = resolveBodyWithDetails(new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}"),
        Arrays.asList(EmailDocumentDetail.date("document.detail.date", august26()),
            EmailDocumentDetail.text("document.detail.total", "133,10 EUR")));

    // The first row labels the number with the document type, the way a reader would name it,
    // rather than with a generic "Number".
    assertTrue(body.contains(">Documento</td>"));
    assertTrue(body.contains(">" + DOCUMENT_NUMBER + "</td>"));
    assertTrue(body.contains(">Fecha</td>"));
    assertTrue(body.contains(">26/08/2026</td>"));
    assertTrue(body.contains(">Total</td>"));
    assertTrue(body.contains(">133,10 EUR</td>"));
  }

  @Test
  public void skipsTheSummaryBlockWhenTheResolverContributesNoRows() throws JSONException {
    // A table whose only line repeats the number already stated in the sentence above it is noise.
    // This is also how a document type opts out of the block entirely.
    String body = resolveBody(new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}"));

    assertFalse(body.contains("border-collapse:collapse"));
  }

  @Test
  public void keepsTheSummaryBlockWhenTheOperatorWritesTheirOwnMessage() throws JSONException {
    JSONObject commandBody = new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}");
    commandBody.put("messageEdits", new JSONObject()
        .put("subject", "Asunto propio")
        .put("message", "Texto escrito por el operador"));

    String body = resolveBodyWithDetails(commandBody, Arrays.asList(
        EmailDocumentDetail.date("document.detail.date", august26())));

    // The rows are facts about the record, not part of the copy, so personalising the message must
    // not take them away.
    assertTrue(body.contains("Texto escrito por el operador"));
    assertTrue(body.contains(">26/08/2026</td>"));
  }

  private static Date august26() {
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(2026, Calendar.AUGUST, 26);
    return calendar.getTime();
  }

  private String resolveBodyWithDetails(JSONObject commandBody,
      List<EmailDocumentDetail> details) throws JSONException {
    EmailDocumentRecordResolver resolver = recordId -> Optional.of(
        new EmailDocumentRecord("Cliente", RECIPIENT_EMAIL, RECORD_ID, DOCUMENT_NUMBER, null,
            DOWNLOAD_LINK, "client-1", details));
    DefaultDocumentSendEmailContract contract =
        new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", resolver);

    EmailContractResolution resolution = contract.resolve(
        new EmailContractCommand(CONTRACT_NAME, commandBody),
        EmailRecipientResolution.serverResolved(RECIPIENT_EMAIL));
    assertTrue("expected a ready resolution, got: " + resolution.getMessage(),
        resolution.isReady());

    return resolution.getProviderRequest().getData().getString("body");
  }

  @Test
  public void closesWithTheReplyInviteWhenThereIsSomeoneToAnswer() throws Exception {
    User user = mock(User.class);
    when(user.getEmail()).thenReturn("operator@example.com");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getUser()).thenReturn(user);

    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

      String body = resolveRequest(commandBody).getData().getString("body");

      assertTrue(body.contains("Puedes responder a este correo"));
      // The generic team signature gives way to it: two closings would contradict each other.
      assertFalse(body.contains("Equipo de Etendo"));
    }
  }

  @Test
  public void keepsTheSignatureWhenNobodyCanBeAnswered() throws Exception {
    // With no Reply-To resolved a reply lands on the unattended noreply@ mailbox, so inviting the
    // customer to write back would be a promise the email cannot keep.
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      String body = resolveRequest(commandBody).getData().getString("body");

      assertFalse(body.contains("Puedes responder a este correo"));
      assertTrue(body.contains("Equipo de Etendo"));
    }
  }
}
