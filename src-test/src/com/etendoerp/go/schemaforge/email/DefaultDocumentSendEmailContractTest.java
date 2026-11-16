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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.portal.PortalDocumentKind;
import com.etendoerp.go.portal.PortalLinkService;

/**
 * Unit tests for {@link DefaultDocumentSendEmailContract#resolve}, covering ETP-4717's reopened
 * regression: "si se agrega un mensaje se pierde el link del adjunto" — an operator-authored
 * message must not drop the document download link from the rendered body. Exercised through the
 * public {@link DefaultDocumentSendEmailContract#resolve} entry point since {@code resolveBody}/
 * {@code buildTemplateData} are private. Also covers the customer portal link that a contract
 * declaring a {@code portalDocumentKind()} mints pending and confirms on delivery (ETP-6209).
 *
 * @covers com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract
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

  // ── ETP-5293: machine-readable reason code for messageEdits validation errors ──

  @Test
  public void invalidMessageEditsRejectionCarriesReasonCodeAndLimits() throws Exception {
    // Neither subject nor message set -> REASON_MISSING_SUBJECT_OR_MESSAGE.
    JSONObject commandBody = new JSONObject("{\"recordId\":\"" + RECORD_ID + "\"}");
    commandBody.put("messageEdits", new JSONObject().put("subject", " ").put("message", "  "));

    EmailDocumentRecordResolver resolver = recordId -> Optional.of(
        new EmailDocumentRecord("Cliente", RECIPIENT_EMAIL, RECORD_ID, DOCUMENT_NUMBER, null,
            DOWNLOAD_LINK, "client-1"));
    DefaultDocumentSendEmailContract contract =
        new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", resolver);

    EmailContractResolution resolution = contract.resolve(
        new EmailContractCommand(CONTRACT_NAME, commandBody),
        EmailRecipientResolution.serverResolved(RECIPIENT_EMAIL));

    assertFalse("expected a rejected resolution", resolution.isReady());
    assertTrue("expected a non-null extra payload", resolution.getExtra() != null);
    assertEquals(EmailMessageEdits.REASON_MISSING_SUBJECT_OR_MESSAGE,
        resolution.getExtra().getString("reasonCode"));
    assertEquals(EmailMessageEdits.MAX_SUBJECT_LENGTH,
        resolution.getExtra().getInt("maxSubjectLength"));
    assertEquals(EmailMessageEdits.MAX_MESSAGE_LENGTH,
        resolution.getExtra().getInt("maxMessageLength"));
  }

  // ── ETP-5069: readable per-document send history gate ───────────────────────

  @Test
  public void everyDocumentSendContractOptsIntoTheReadableHistory() {
    // The gate lives here rather than in a contract-name list inside TransactionalEmailService,
    // so a new document contract inherits it without anyone remembering to register it.
    assertTrue(contract(CONTRACT_NAME).logsSendHistory());
  }

  @Test
  public void specNameIsDerivedByStrippingTheSendSuffix() {
    // Inverts the frontend's `${windowName}-send` convention, so one rule serves both sides.
    assertEquals("test-document", contract(CONTRACT_NAME).getSpecName());
    assertEquals("sales-invoice", contract("sales-invoice-send").getSpecName());
  }

  @Test
  public void specNameIsNullWhenTheContractNameDoesNotFollowTheConvention() {
    // The column is nullable and best effort; a name outside the convention must not guess.
    assertNull(contract("company-invitation").getSpecName());
  }

  // ── ETP-5715: the prefill the send modal shows, composed by the server ──────

  @Test
  public void sendDefaultsAreTheSubjectAndMessageTheSendModalPrefills() {
    EmailSendDefaults defaults = contract(CONTRACT_NAME).sendDefaults(RECORD_ID, "es_ES")
        .orElseThrow(() -> new AssertionError("document not resolved"));

    assertEquals("Documento #doc-1 — Cliente", defaults.getSubject());
    assertEquals("Hola, **Cliente**:\n\nLe enviamos su Documento **doc-1**.",
        defaults.getMessage());
    assertEquals(RECIPIENT_EMAIL, defaults.getBaseRecipient());
    assertEquals(10, defaults.getMaxRecipients());
  }

  @Test
  public void sendDefaultsFollowTheRequestedLanguage() {
    EmailSendDefaults defaults = contract(CONTRACT_NAME).sendDefaults(RECORD_ID, "en_US")
        .orElseThrow(() -> new AssertionError("document not resolved"));

    assertEquals("Hello **Cliente**,\n\nPlease find your Documento **doc-1** below.",
        defaults.getMessage());
  }

  @Test
  public void sendDefaultsWithoutRecipientNameDropTheGreetingAndTheSubjectSuffix() {
    DefaultDocumentSendEmailContract anonymous = new DefaultDocumentSendEmailContract(
        CONTRACT_NAME, "Documento", recordId -> Optional.of(new EmailDocumentRecord(null,
            "not-an-address", RECORD_ID, DOCUMENT_NUMBER, null, DOWNLOAD_LINK, "client-1")));

    EmailSendDefaults defaults = anonymous.sendDefaults(RECORD_ID, "es_ES").orElseThrow(
        () -> new AssertionError("document not resolved"));

    assertEquals("Documento #doc-1", defaults.getSubject());
    assertEquals("Le enviamos su Documento **doc-1**.", defaults.getMessage());
    assertNull("an invalid contact address is not a base recipient", defaults.getBaseRecipient());
  }

  @Test
  public void sendDefaultsOfAnUnknownDocumentAreEmpty() {
    DefaultDocumentSendEmailContract none = new DefaultDocumentSendEmailContract(CONTRACT_NAME,
        "Documento", recordId -> Optional.empty());

    assertFalse(none.sendDefaults("missing", "es_ES").isPresent());
  }

  @Test
  public void sendingTheDefaultsAsEditsRendersTheEmailTheModalWouldSend() throws Exception {
    EmailSendDefaults defaults = contract(CONTRACT_NAME).sendDefaults(RECORD_ID, "es_ES")
        .orElseThrow(() -> new AssertionError("document not resolved"));
    JSONObject commandBody = new JSONObject();
    commandBody.put("recordId", RECORD_ID);
    commandBody.put("messageEdits", new JSONObject().put("subject", defaults.getSubject())
        .put("message", defaults.getMessage()));

    EmailProviderRequest request = resolveRequest(commandBody);

    assertEquals("Documento #doc-1 — Cliente", request.getData().getString("subject"));
    String body = request.getData().getString("body");
    assertTrue(body, body.contains("Hola, <strong>Cliente</strong>:<br><br>Le enviamos su "
        + "Documento <strong>doc-1</strong>."));
    assertTrue(body, body.contains(DOWNLOAD_LINK));
  }

  private static DefaultDocumentSendEmailContract contract(String contractName) {
    return new DefaultDocumentSendEmailContract(contractName, "Documento",
        recordId -> Optional.of(new EmailDocumentRecord("Cliente", RECIPIENT_EMAIL, RECORD_ID,
            DOCUMENT_NUMBER, null, DOWNLOAD_LINK, "client-1")));
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
            DOWNLOAD_LINK, "client-1").withDetails(details));
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

  // ── ETP-6209: the customer portal link ──────────────────────────────────────

  @Test
  public void aPortalContractMintsOnePendingLinkAndMakesItTheEmailsButton() throws Exception {
    PortalLinkService portalLinks = mock(PortalLinkService.class);
    PortalLinkService.MintedLink minted = mock(PortalLinkService.MintedLink.class);
    when(minted.getId()).thenReturn("LINK-1");
    when(minted.getUrl()).thenReturn("https://app.example.test/portal/tok");
    when(minted.getValidity()).thenReturn(Duration.ofHours(48));
    when(portalLinks.mintForSend(any(), any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(minted));
    List<String> to = Arrays.asList(RECIPIENT_EMAIL, "billing@example.com");
    List<String> cc = Collections.singletonList("boss@example.com");

    EmailContractResolution resolution = portalContract(portalLinks).resolve(
        new EmailContractCommand(CONTRACT_NAME,
            new JSONObject().put("recordId", RECORD_ID).put("language", "es_ES")),
        EmailRecipientResolution.serverResolved(EmailRecipientSet.of(to, cc)));

    verify(portalLinks).mintForSend(PortalDocumentKind.INVOICE, RECORD_ID, to, cc, "es_ES",
        CONTRACT_NAME);
    EmailProviderRequest request = resolution.getProviderRequest();
    assertEquals("the send carries the link id onDelivered confirms", "LINK-1",
        request.getSendCorrelationId());
    String body = request.getData().getString("body");
    // The portal link is the email's one button, and it replaces the download one.
    assertTrue(body, body.contains("https://app.example.test/portal/tok"));
    assertTrue(body, body.contains("Ver documento en el portal"));
    assertFalse("no download button next to the portal one: " + body,
        body.contains("Descargar documento"));
    assertFalse("the download link is not offered: " + body, body.contains(DOWNLOAD_LINK));
    // The intro is a plain sentence; the only link is the button.
    assertTrue(body, body.contains("<strong>portal de cliente</strong>. El enlace es válido"
        + " durante 48 horas."));
    // The layout's "copy this link" fallback under the button is a link too; the intro is not.
    int intro = body.indexOf("<strong>portal de cliente</strong>");
    String introParagraph = body.substring(body.lastIndexOf("<p", intro),
        body.indexOf("</p>", intro));
    assertFalse("no inline anchor in the intro: " + introParagraph,
        introParagraph.contains("<a "));
    // Pending until the email is delivered.
    verify(portalLinks, never()).confirm(any(), any(), anyInt());
  }

  @Test
  public void onDeliveredConfirmsOnlyTheLinkOfAPortalContractsOwnSend() {
    EmailRecipientSet recipients = EmailRecipientSet.of(
        Arrays.asList(RECIPIENT_EMAIL, "billing@example.com"),
        Collections.singletonList("boss@example.com"));

    PortalLinkService portalLinks = mock(PortalLinkService.class);
    portalContract(portalLinks).onDelivered(deliveredSend(recipients, "LINK-1"), "LOG-1");
    verify(portalLinks).confirm("LINK-1", "LOG-1", 3);

    // A send that carried no link has nothing to confirm.
    PortalLinkService noLink = mock(PortalLinkService.class);
    portalContract(noLink).onDelivered(deliveredSend(recipients, null), "LOG-2");
    verifyNoInteractions(noLink);

    // A contract that declares no portal kind never confirms anything.
    PortalLinkService undeclared = mock(PortalLinkService.class);
    new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", recordId -> Optional.empty()) {
      @Override
      protected PortalLinkService portalLinkService() {
        return undeclared;
      }
    }.onDelivered(deliveredSend(recipients, "LINK-3"), "LOG-3");
    verifyNoInteractions(undeclared);
  }

  private static DefaultDocumentSendEmailContract portalContract(PortalLinkService portalLinks) {
    EmailDocumentRecordResolver resolver = recordId -> Optional.of(
        new EmailDocumentRecord("Cliente", RECIPIENT_EMAIL, RECORD_ID, DOCUMENT_NUMBER, null,
            DOWNLOAD_LINK, "client-1"));
    return new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", resolver) {
      @Override
      protected Optional<PortalDocumentKind> portalDocumentKind() {
        return Optional.of(PortalDocumentKind.INVOICE);
      }

      @Override
      protected PortalLinkService portalLinkService() {
        return portalLinks;
      }
    };
  }

  private static EmailSendContext deliveredSend(EmailRecipientSet recipients, String linkId) {
    EmailProviderRequest request = new EmailProviderRequest(recipients, "custom",
        new JSONObject(), null).withSendCorrelationId(linkId);
    return new EmailSendContext(new EmailContractCommand(CONTRACT_NAME, new JSONObject()),
        EmailRecipientResolution.serverResolved(recipients), request);
  }
}
