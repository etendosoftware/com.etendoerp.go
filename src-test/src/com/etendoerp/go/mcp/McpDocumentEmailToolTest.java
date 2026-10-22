/*
 **************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoDocumentPdf;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.email.EmailContract;
import com.etendoerp.go.schemaforge.email.EmailContractRegistry;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecord;
import com.etendoerp.go.schemaforge.email.EmailDocumentRecordResolver;
import com.etendoerp.go.schemaforge.email.EmailSendDefaults;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.email.contracts.GoodsShipmentSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.PurchaseOrderSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.ReturnMaterialReceiptSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.ReturnToVendorSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.ReturnToVendorShipmentSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesInvoiceSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesOrderSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract;

/**
 * ETP-5715 — {@code etendo_send_document_email}: the application's "Enviar", through the SAME server
 * path (the {@code TransactionalEmailService}, mocked here because it has its own tests). What is
 * asserted: the documents offered are derived from the registered contracts, the command posted is
 * what the send modal posts (defaults included), every argument is validated before anything is
 * sent, the stored PDF gates the send, and every non-SENT outcome is reported as not sent.
 *
 * @covers com.etendoerp.go.mcp.McpDocumentEmailTool
 * @covers com.etendoerp.go.mcp.McpDocumentEmailRequest
 * @covers com.etendoerp.go.mcp.McpDocumentEmailCommand
 * @covers com.etendoerp.go.mcp.McpDocumentEmailOutcome
 * @covers com.etendoerp.go.mcp.McpDocumentEmailContracts
 * @covers com.etendoerp.go.mcp.McpDocumentEmailToolDefinition
 * @covers com.etendoerp.go.mcp.McpDocumentPdfTool
 */
@SuppressWarnings("java:S2187")
class McpDocumentEmailToolTest {

  private static final String SPEC = "purchase-order";
  private static final String RECORD = "PO1";
  private static final String CONTRACT = "purchase-order-send";
  private static final String BASE = "compras@proveedor.example";

  private MockedStatic<OBContext> contextMock;
  private MockedStatic<McpToolRouterSupport> supportMock;
  private MockedStatic<NeoAttachmentTarget> targetMock;
  private MockedStatic<NeoDocumentPdf> pdfMock;
  private MockedStatic<McpDocumentEmailContracts> contractsMock;
  private TransactionalEmailService service;
  private SFSpec spec;
  private SFEntity header;
  private NeoAttachmentTarget target;

  @BeforeEach
  void setUp() {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLIENT1");
    Language language = mock(Language.class);
    // The user's AD language is English on purpose: the send modal never consults it.
    when(language.getLanguage()).thenReturn("en_US");
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    when(context.getLanguage()).thenReturn(language);
    contextMock = mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC1");
    header = mock(SFEntity.class);
    Tab tab = mock(Tab.class);
    when(tab.getTabLevel()).thenReturn(0L);
    when(header.getADTab()).thenReturn(tab);
    supportMock = mockStatic(McpToolRouterSupport.class);
    supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName(anyString())).thenReturn(spec);
    supportMock.when(() -> McpToolRouterSupport.listIncludedEntities("SPEC1"))
        .thenReturn(List.of(header));
    targetMock = mockStatic(NeoAttachmentTarget.class);
    target = mock(NeoAttachmentTarget.class);
    targetMock.when(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), anyString(),
        eq(true))).thenReturn(target);
    pdfMock = mockStatic(NeoDocumentPdf.class);
    pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
        .thenReturn(NeoDocumentPdf.Result.found(pdfFile()));
    service = mock(TransactionalEmailService.class);
    EmailContractRegistry registry = realContracts();
    contractsMock = mockStatic(McpDocumentEmailContracts.class, CALLS_REAL_METHODS);
    contractsMock.when(McpDocumentEmailContracts::registry).thenReturn(registry);
    contractsMock.when(McpDocumentEmailContracts::service).thenReturn(service);
  }

  @AfterEach
  void tearDown() {
    contractsMock.close();
    pdfMock.close();
    targetMock.close();
    supportMock.close();
    contextMock.close();
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static JSONObject pdfFile() {
    try {
      return new JSONObject().put("id", "F1").put("name", "PO1.pdf").put("size", 4321)
          .put("dataType", "application/pdf").put("updatedAt", "2026-10-02T10:00:00Z");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The eight real document-send contracts over one fixed purchase-order-like record, plus one that is not. */
  private static EmailContractRegistry realContracts() {
    EmailDocumentRecordResolver resolver = id -> Optional.of(new EmailDocumentRecord(
        "Proveedor Mayorista", BASE, RECORD, "1000008", null, "https://x.test/d", "CLIENT1"));
    EmailContract notADocument = mock(EmailContract.class);
    when(notADocument.getName()).thenReturn("reset-password");
    List<EmailContract> all = Arrays.asList(
        new SalesInvoiceSendEmailContract(resolver), new SalesOrderSendEmailContract(resolver),
        new SalesQuotationSendEmailContract(resolver), new PurchaseOrderSendEmailContract(resolver),
        new ReturnToVendorSendEmailContract(resolver), new GoodsShipmentSendEmailContract(resolver),
        new ReturnMaterialReceiptSendEmailContract(resolver),
        new ReturnToVendorShipmentSendEmailContract(resolver), notADocument);
    return new EmailContractRegistry() {
      @Override
      public Optional<EmailContract> find(String name) {
        return all.stream().filter(c -> c.getName().equals(name)).findFirst();
      }

      @Override
      public Collection<EmailContract> contracts() {
        return all;
      }
    };
  }

  private static NeoResponse answer(int http, String status, String... extra) throws Exception {
    JSONObject data = new JSONObject().put("status", status).put("contract", CONTRACT);
    for (int i = 0; i < extra.length; i += 2) {
      data.put(extra[i], extra[i + 1]);
    }
    return new NeoResponse(http, new JSONObject().put("response",
        new JSONObject().put("data", data)));
  }

  private void serviceAnswers(NeoResponse response) {
    when(service.send(eq(CONTRACT), any(JSONObject.class))).thenReturn(response);
  }

  private JSONObject sentCommand() {
    ArgumentCaptor<JSONObject> body = ArgumentCaptor.forClass(JSONObject.class);
    verify(service).send(eq(CONTRACT), body.capture());
    return body.getValue();
  }

  private static JSONObject args(String... keyValues) throws Exception {
    JSONObject args = new JSONObject().put("spec", SPEC).put("id", RECORD);
    for (int i = 0; i < keyValues.length; i += 2) {
      args.put(keyValues[i], keyValues[i + 1]);
    }
    return args;
  }

  private static JSONObject argsTo(String... to) throws Exception {
    return args().put("to", new JSONArray(Arrays.asList(to)));
  }

  private void assertNotSent(JSONObject result, int status, String code) throws Exception {
    assertEquals(code, result.optString("error"), result.toString());
    assertEquals(status, result.getInt("status"), result.toString());
    verify(service, never()).send(anyString(), any(JSONObject.class));
  }

  // ── sending ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("send")
  class Sending {

    @Test
    @DisplayName("with the default recipient, subject and message it posts what the send modal posts")
    void sendsTheModalDefaults() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertFalse(result.has("error"), result.toString());
      assertTrue(result.getBoolean("sent"), result.toString());
      assertEquals("SENT", result.getString("status"));
      JSONObject command = sentCommand();
      assertEquals("v1", command.getString("version"));
      assertEquals(RECORD, command.getString("recordId"));
      assertEquals("send-document", command.getString("intent"));
      assertEquals("es_ES", command.getString("language"));
      JSONObject edits = command.getJSONObject("messageEdits");
      assertEquals("Pedido de Compra #1000008 — Proveedor Mayorista", edits.getString("subject"));
      assertEquals("Hola, **Proveedor Mayorista**:\n\nLe enviamos su Pedido de Compra **1000008**.",
          edits.getString("message"));
      assertFalse(command.has("recipientEdits"), "the contact is the recipient: nothing to edit");
      assertEquals("purchase-order-send:PO1:send:v1", command.getString("idempotencyKey"));
    }

    @Test
    @DisplayName("the command carries no field the contract forbids a caller to set")
    void commandCarriesOnlyTheAllowlistedFields() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      McpDocumentEmailTool.run(argsTo("otro@cliente.example"));

      JSONObject command = sentCommand();
      List<String> keys = new ArrayList<>();
      for (java.util.Iterator<?> it = command.keys(); it.hasNext();) {
        keys.add(String.valueOf(it.next()));
      }
      java.util.Collections.sort(keys);
      assertEquals(List.of("intent", "language", "messageEdits", "recipientEdits", "recordId",
          "version"), keys);
    }

    @Test
    @DisplayName("free recipients, cc, subject and message are sent as edits")
    void sendsCustomRecipientsSubjectAndMessage() throws Exception {
      serviceAnswers(answer(200, "SENT"));
      JSONObject call = args("subject", "Su pedido corregido", "message", "Hola **Ana**,\nadjunto.")
          .put("to", new JSONArray(List.of("ana@cliente.example", "luis@cliente.example")))
          .put("cc", new JSONArray(List.of("jefe@cliente.example")));

      JSONObject result = McpDocumentEmailTool.run(call);

      assertTrue(result.getBoolean("sent"), result.toString());
      JSONObject command = sentCommand();
      JSONObject edits = command.getJSONObject("messageEdits");
      assertEquals("Su pedido corregido", edits.getString("subject"));
      assertEquals("Hola **Ana**,\nadjunto.", edits.getString("message"));
      JSONObject recipients = command.getJSONObject("recipientEdits");
      assertEquals(List.of("ana@cliente.example", "luis@cliente.example"),
          strings(recipients.getJSONObject("to").getJSONArray("add")));
      assertEquals(List.of(BASE), strings(recipients.getJSONObject("to").getJSONArray("remove")));
      assertEquals(List.of("jefe@cliente.example"),
          strings(recipients.getJSONObject("cc").getJSONArray("add")));
      assertFalse(command.has("idempotencyKey"), "with edits the server derives the key");
    }

    @Test
    @DisplayName("the document's authorization is the write tier and the PDF is checked first")
    void authorizesAtWriteTierAndChecksThePdf() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      McpDocumentEmailTool.run(argsTo(BASE));

      targetMock.verify(() -> NeoAttachmentTarget.resolve(spec, header, RECORD, true));
      pdfMock.verify(() -> NeoDocumentPdf.resolve(target, header));
    }

    @Test
    @DisplayName("a message with markup is passed as plain text for the server to escape")
    void passesMarkupAsTextNotAsHtml() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      McpDocumentEmailTool.run(args("message", "<script>alert(1)</script> **hola**")
          .put("to", new JSONArray(List.of(BASE))));

      assertEquals("<script>alert(1)</script> **hola**",
          sentCommand().getJSONObject("messageEdits").getString("message"));
    }

    @Test
    @DisplayName("a provider failure is not reported as sent")
    void providerFailureIsNotSent() throws Exception {
      serviceAnswers(answer(502, "PROVIDER_FAILED"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertEquals("email_provider_failed", result.getString("error"));
      assertFalse(result.getBoolean("sent"));
      assertEquals(502, result.getInt("status"));
    }

    @Test
    @DisplayName("an unexpected failure says the outcome is unknown and leaks nothing")
    void unexpectedFailureSaysUnknown() throws Exception {
      when(service.send(eq(CONTRACT), any(JSONObject.class)))
          .thenThrow(new IllegalStateException("secret-address@leak.example exploded"));

      JSONObject result;
      try (LogCapture log = new LogCapture(McpDocumentEmailTool.class)) {
        result = McpDocumentEmailTool.run(argsTo(BASE));
        assertFalse(log.events().isEmpty());
        assertFalse(log.everything().contains("leak.example"), log.everything());
        assertFalse(log.anyThrown(), "the throwable (message, stack) must not be logged");
      }

      assertEquals("server_error", result.getString("error"));
      assertFalse(result.toString().contains("leak.example"), result.toString());
      assertTrue(result.getString("hint").toLowerCase().contains("history"), result.toString());
    }
  }

  // ── what is reported when it was NOT sent ───────────────────────────────

  @Nested
  @DisplayName("not sent")
  class NotSent {

    @Test
    @DisplayName("an identical second send is DUPLICATE: reported as not sent, with the earlier time")
    void duplicateIsNotSent() throws Exception {
      serviceAnswers(answer(200, "DUPLICATE", "previousSentAt", "2026-10-21T11:56:00Z"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertEquals("email_duplicate", result.getString("error"));
      assertFalse(result.getBoolean("sent"));
      assertEquals("DUPLICATE", result.getString("emailStatus"));
      assertEquals("2026-10-21T11:56:00Z", result.getString("previousSentAt"));
      assertTrue(result.getString("detail").startsWith("Not sent: an identical email was already "
          + "sent"), result.getString("detail"));
      assertTrue(result.getString("detail").contains("2026-10-21T11:56:00Z"));
    }

    @Test
    @DisplayName("THROTTLED says which limit and when to retry")
    void throttledIsReported() throws Exception {
      serviceAnswers(answer(429, "THROTTLED", "retryAfterSeconds", "1800", "throttleScope",
          "record"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertEquals("email_throttled", result.getString("error"));
      assertEquals(429, result.getInt("status"));
      assertFalse(result.getBoolean("sent"));
      assertEquals("THROTTLED", result.getString("emailStatus"));
      assertEquals("1800", result.getString("retryAfterSeconds"));
      assertEquals("record", result.getString("throttleScope"));
    }

    @Test
    @DisplayName("SUPPRESSED, NO_RECIPIENT and UNAUTHORIZED each keep their own status and are not sent")
    void otherStatusesAreTruthful() throws Exception {
      serviceAnswers(answer(403, "SUPPRESSED", "message", "Email recipient is suppressed"));
      JSONObject suppressed = McpDocumentEmailTool.run(argsTo(BASE));
      assertEquals("email_suppressed", suppressed.getString("error"));
      assertEquals("SUPPRESSED", suppressed.getString("emailStatus"));
      assertTrue(suppressed.getString("detail").contains("suppressed"));

      serviceAnswers(answer(422, "NO_RECIPIENT", "message", "Final recipient list is empty"));
      JSONObject none = McpDocumentEmailTool.run(argsTo(BASE));
      assertEquals("email_no_recipient", none.getString("error"));
      assertFalse(none.getBoolean("sent"));

      serviceAnswers(answer(403, "UNAUTHORIZED", "message", "Access denied to spec for current role"));
      JSONObject denied = McpDocumentEmailTool.run(argsTo(BASE));
      assertEquals("forbidden", denied.getString("error"));
      assertEquals(403, denied.getInt("status"));
      assertEquals("UNAUTHORIZED", denied.getString("emailStatus"));
    }

    @Test
    @DisplayName("a server-side validation failure keeps its status and reason code")
    void validationFailureIsReported() throws Exception {
      serviceAnswers(answer(400, "VALIDATION_FAILED", "message", "Recipient count exceeds the "
          + "maximum of 10", "reasonCode", "X"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertEquals("validation_error", result.getString("error"));
      assertEquals(400, result.getInt("status"));
      assertEquals("VALIDATION_FAILED", result.getString("emailStatus"));
      assertEquals("X", result.getString("reasonCode"));
      assertTrue(result.getString("detail").contains("Recipient count"));
    }

    @Test
    @DisplayName("an answer that cannot be read is a server error, never a success")
    void unreadableAnswerIsAnError() throws Exception {
      serviceAnswers(new NeoResponse(200, new JSONObject()));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertEquals("server_error", result.getString("error"));
      assertFalse(result.getBoolean("sent"));
    }
  }

  // ── refused before anything is sent ─────────────────────────────────────

  @Nested
  @DisplayName("refused before sending")
  class Refused {

    @ParameterizedTest
    @ValueSource(strings = {"sin-arroba", "a@b", "a b@c.example", "a@b.example,c@d.example",
        "<x@y.example>", "a@@b.example", "a@b..example", ".a@b.example", "a.@b.example",
        "a@-b.example", "a@b.example-", "x@y.example\r\nBcc: spam@evil.example",
        "x@y.example\nBcc: spam@evil.example", "x@y.example\u0000", "nombre@ejemplo.c"})
    @DisplayName("an invalid or header-injecting address in 'to' is refused and nothing is sent")
    void invalidAddressInTo(String address) throws Exception {
      JSONObject result = McpDocumentEmailTool.run(argsTo(address));

      assertNotSent(result, 422, "validation_error");
      assertFalse(result.toString().contains("evil.example"), "the address must not be echoed");
    }

    @Test
    @DisplayName("an invalid address in 'cc' is refused too")
    void invalidAddressInCc() throws Exception {
      JSONObject call = argsTo(BASE).put("cc", new JSONArray(List.of("x@y.example\r\nBcc: a@b.example")));

      assertNotSent(McpDocumentEmailTool.run(call), 422, "validation_error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hola\r\nBcc: spam@evil.example", "Hola\nBcc: x@y.example",
        "Hola\rX", "Hola Bcc", "Hola\u0000"})
    @DisplayName("CR, LF and control characters in the subject are refused, not silently stripped")
    void headerInjectionInSubject(String subject) throws Exception {
      JSONObject call = argsTo(BASE).put("subject", subject);

      assertNotSent(McpDocumentEmailTool.run(call), 422, "validation_error");
    }

    @Test
    @DisplayName("a subject or message over the contract's cap is refused")
    void tooLongTexts() throws Exception {
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("subject", "s".repeat(201))), 422,
          "validation_error");
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("message", "m".repeat(5001))), 422,
          "validation_error");
    }

    @Test
    @DisplayName("more recipients than the contract accepts (to and cc together) are refused")
    void tooManyRecipients() throws Exception {
      List<String> to = new ArrayList<>();
      List<String> cc = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        to.add("to" + i + "@cliente.example");
        cc.add("cc" + i + "@cliente.example");
      }
      JSONObject call = args().put("to", new JSONArray(to)).put("cc", new JSONArray(cc));

      JSONObject result = McpDocumentEmailTool.run(call);

      assertNotSent(result, 422, "validation_error");
      assertTrue(result.getString("detail").contains("10"), result.getString("detail"));
    }

    @Test
    @DisplayName("no recipient at all is refused: 'to' is required")
    void toIsRequired() throws Exception {
      assertNotSent(McpDocumentEmailTool.run(args()), 422, "validation_error");
      assertNotSent(McpDocumentEmailTool.run(args().put("to", new JSONArray())), 422,
          "validation_error");
    }

    @Test
    @DisplayName("a document with no stored PDF is refused with no_pdf, as etendo_get_document_pdf answers")
    void noPdf() throws Exception {
      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.missing(false));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertNotSent(result, 409, "no_pdf");
      assertTrue(result.getString("hint").contains("preview"), result.getString("hint"));
    }

    @Test
    @DisplayName("a stale stored PDF is refused with pdf_stale")
    void stalePdf() throws Exception {
      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.stale(pdfFile()));

      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE)), 409, "pdf_stale");
    }

    @Test
    @DisplayName("a missing or another tenant's document is the same 404 and nothing is sent")
    void foreignOrMissingDocument() throws Exception {
      targetMock.when(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), eq("OTHER"),
          eq(true))).thenThrow(new NeoAttachmentTarget.Refused(404,
              NeoAttachmentTarget.MSG_RECORD_NOT_FOUND));

      JSONObject result = McpDocumentEmailTool.run(args().put("id", "OTHER")
          .put("to", new JSONArray(List.of(BASE))));

      assertNotSent(result, 404, "not_found");
    }

    @Test
    @DisplayName("a role without write access is refused with 403 and nothing is sent")
    void roleWithoutWrite() throws Exception {
      targetMock.when(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), anyString(),
          eq(true))).thenThrow(new NeoAttachmentTarget.Refused(403, "no write access"));

      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE)), 403, "forbidden");
    }

    @Test
    @DisplayName("a purchase invoice is not offered: the application has no send for it")
    void purchaseInvoiceIsNotOffered() throws Exception {
      JSONObject call = argsTo(BASE).put("spec", "purchase-invoice");

      JSONObject result = McpDocumentEmailTool.run(call);

      assertNotSent(result, 405, "method_not_allowed");
      assertFalse(result.getString("hint").contains("purchase-invoice,"),
          "the supported list must not offer it");
      assertTrue(result.getString("hint").contains("purchase-order"), result.getString("hint"));
    }

    @Test
    @DisplayName("a document the contract cannot resolve is a 404 and nothing is sent")
    void unresolvableDocument() throws Exception {
      EmailContractRegistry unresolvable = new EmailContractRegistry() {
        @Override
        public Optional<EmailContract> find(String name) {
          return Optional.empty();
        }

        @Override
        public Collection<EmailContract> contracts() {
          return List.of(new PurchaseOrderSendEmailContract(id -> Optional.empty()));
        }
      };
      contractsMock.when(McpDocumentEmailContracts::registry).thenReturn(unresolvable);

      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE)), 404, "not_found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "\"true\"", "false ", "TRUE", "on"})
    @DisplayName("a preview that is not exactly a JSON boolean is refused, never read as 'send'")
    void previewMustBeABooleanText(String raw) throws Exception {
      JSONObject call = argsTo(BASE).put("preview", raw);

      assertNotSent(McpDocumentEmailTool.run(call), 422, "validation_error");
    }

    @Test
    @DisplayName("a preview that is a number, null or an object is refused and nothing is sent")
    void previewMustBeABoolean() throws Exception {
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("preview", 1)), 422,
          "validation_error");
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("preview", 0)), 422,
          "validation_error");
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("preview", JSONObject.NULL)), 422,
          "validation_error");
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("preview", new JSONObject())), 422,
          "validation_error");
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("preview", new JSONArray())), 422,
          "validation_error");
    }

    @Test
    @DisplayName("preview:true previews and an absent preview sends")
    void previewTrueAndAbsent() throws Exception {
      JSONObject previewed = McpDocumentEmailTool.run(argsTo(BASE).put("preview", true));
      assertTrue(previewed.getBoolean("preview"), previewed.toString());
      verify(service, never()).send(anyString(), any(JSONObject.class));

      serviceAnswers(answer(200, "SENT"));
      assertTrue(McpDocumentEmailTool.run(argsTo(BASE)).getBoolean("sent"));
      assertTrue(McpDocumentEmailTool.run(argsTo(BASE).put("preview", false)).getBoolean("sent"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hola\u0085X", "Hola\u202EX", "Hola\u2066X", "Hola\u009fX"})
    @DisplayName("C1 controls and bidi overrides are refused in the subject and the message")
    void invisibleControlsInTexts(String text) throws Exception {
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("subject", text)), 422,
          "validation_error");
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("message", text)), 422,
          "validation_error");
    }

    @Test
    @DisplayName("a missing PDF is refused with wording that does not send the user in a circle")
    void noPdfWordingForTheEmailTool() throws Exception {
      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.missing(false));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertNotSent(result, 409, "no_pdf");
      String hint = result.getString("hint");
      assertFalse(hint.contains("send the document by email"), hint);
      assertTrue(hint.contains("Open the document's preview in the application"), hint);
      assertTrue(hint.contains("ask to send again"), hint);
      assertTrue(hint.contains("first send must be done from the application"), hint);
      assertFalse(result.getBoolean("sent"));
    }

    @Test
    @DisplayName("the missing-PDF hint depends on the state: a draft, another status, unknown")
    void noPdfHintDependsOnTheDocumentState() throws Exception {
      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.missing("DR"));
      String draft = McpDocumentEmailTool.run(argsTo(BASE)).getString("hint");
      assertTrue(draft.startsWith("This document is a draft and a draft never stores a PDF: "
          + "complete it first, then open its preview in the application and ask to send again"),
          draft);
      assertFalse(draft.contains("so its PDF is stored"), draft);

      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.missing("CO"));
      String completed = McpDocumentEmailTool.run(argsTo(BASE)).getString("hint");
      assertTrue(completed.startsWith("Open the document's preview in the application so its "
          + "PDF is stored, then ask to send again."), completed);
      assertFalse(completed.contains("draft"), completed);
      assertTrue(completed.contains("first send must be done from the application"), completed);

      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.missing(false));
      assertTrue(McpDocumentEmailTool.run(argsTo(BASE)).getString("hint")
          .contains("a draft never stores a PDF: complete it first"));
    }

    @Test
    @DisplayName("a stale PDF gets the same own wording")
    void stalePdfWordingForTheEmailTool() throws Exception {
      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.stale(pdfFile()));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE));

      assertNotSent(result, 409, "pdf_stale");
      String hint = result.getString("hint");
      assertTrue(hint.contains("refreshed") && hint.contains("ask to send again"), hint);
      assertFalse(hint.contains("send the document by email"), hint);
    }

    @Test
    @DisplayName("the list of sendable documents in the refusal leaves out specs the caller cannot address")
    void notOfferedHintListsOnlyAddressableSpecs() throws Exception {
      supportMock.when(() -> McpToolRouterSupport.findActiveSpecByName("return-to-vendor"))
          .thenThrow(McpRoutingException.specNotFound("return-to-vendor"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE).put("spec", "purchase-invoice"));

      assertNotSent(result, 405, "method_not_allowed");
      String hint = result.getString("hint");
      assertTrue(hint.contains("purchase-order"), hint);
      assertFalse(hint.contains("return-to-vendor,") || hint.endsWith("return-to-vendor."), hint);
      assertTrue(hint.contains("return-to-vendor-shipment"), hint);
    }

    @Test
    @DisplayName("the registry is built once per call, however many checks use it")
    void registryIsBuiltOncePerCall() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      McpDocumentEmailTool.run(argsTo(BASE));

      contractsMock.verify(McpDocumentEmailContracts::registry, org.mockito.Mockito.times(1));
    }

    @Test
    @DisplayName("an empty spec list builds no registry at all")
    void emptySpecListShortCircuits() {
      assertTrue(McpDocumentEmailContracts.supportedSpecs(List.of()).isEmpty());

      contractsMock.verify(McpDocumentEmailContracts::registry, never());
    }

    @Test
    @DisplayName("the default language is the modal's (es_ES), whatever the user's AD language is")
    void defaultLanguageIsTheModalsNotTheUsers() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      McpDocumentEmailTool.run(argsTo(BASE));

      JSONObject command = sentCommand();
      assertEquals("es_ES", command.getString("language"));
      assertEquals("Pedido de Compra #1000008 — Proveedor Mayorista",
          command.getJSONObject("messageEdits").getString("subject"));
    }

    @Test
    @DisplayName("an explicit language is honoured for the command and for the default copy")
    void explicitLanguageIsHonoured() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      McpDocumentEmailTool.run(argsTo(BASE).put("language", "en_US"));

      JSONObject command = sentCommand();
      assertEquals("en_US", command.getString("language"));
      JSONObject edits = command.getJSONObject("messageEdits");
      assertEquals("Purchase Order #1000008 — Proveedor Mayorista", edits.getString("subject"));
      assertEquals("Hello **Proveedor Mayorista**,\n\nPlease find your Purchase Order "
          + "**1000008** below.", edits.getString("message"));
    }

    @Test
    @DisplayName("the preview shows the language it will use")
    void previewShowsTheLanguage() throws Exception {
      assertEquals("es_ES", McpDocumentEmailTool.run(args().put("preview", true))
          .getString("language"));
      JSONObject english = McpDocumentEmailTool.run(args().put("preview", true)
          .put("language", "en_US"));
      assertEquals("en_US", english.getString("language"));
      assertTrue(english.getJSONObject("defaults").getString("subject")
          .startsWith("Purchase Order #1000008"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr_FR", "es", "ES_ES", "es_ES\r\n", "", "en-US"})
    @DisplayName("a language that is not a shipped locale is refused and nothing is sent")
    void invalidLanguage(String language) throws Exception {
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("language", language)), 422,
          "validation_error");
    }

    @Test
    @DisplayName("a language that is not text is refused")
    void languageMustBeText() throws Exception {
      assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put("language", 5)), 422,
          "validation_error");
    }

    @Test
    @DisplayName("an address listed in both to and cc stays in to and the answer says so")
    void duplicateBetweenToAndCcIsReported() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE)
          .put("cc", new JSONArray(List.of(BASE.toUpperCase(), "jefe@cliente.example"))));

      assertTrue(result.getBoolean("sent"), result.toString());
      assertEquals(List.of(BASE), strings(result.getJSONArray("to")));
      assertEquals(List.of("jefe@cliente.example"), strings(result.getJSONArray("cc")));
      assertEquals(List.of(BASE.toUpperCase()), strings(result.getJSONArray("removedDuplicates")));
      assertTrue(result.getString("hint").contains("left out"), result.getString("hint"));
      assertEquals(List.of("jefe@cliente.example"), strings(sentCommand()
          .getJSONObject("recipientEdits").getJSONObject("cc").getJSONArray("add")));
    }

    @Test
    @DisplayName("an address repeated inside one list is reported too; with none repeated the list is empty")
    void repeatedAddressIsReported() throws Exception {
      serviceAnswers(answer(200, "SENT"));

      JSONObject twice = McpDocumentEmailTool.run(argsTo(BASE, BASE));
      assertEquals(List.of(BASE), strings(twice.getJSONArray("removedDuplicates")));

      JSONObject clean = McpDocumentEmailTool.run(argsTo(BASE));
      assertEquals(0, clean.getJSONArray("removedDuplicates").length());
      assertFalse(clean.getString("hint").contains("left out"));
    }

    @Test
    @DisplayName("the preview reports the duplicates it would drop")
    void previewReportsDuplicates() throws Exception {
      JSONObject result = McpDocumentEmailTool.run(argsTo(BASE)
          .put("cc", new JSONArray(List.of(BASE))).put("preview", true));

      assertEquals(List.of(BASE), strings(result.getJSONArray("removedDuplicates")));
    }

    @Test
    @DisplayName("an explicit null for subject, message or language is refused like a null preview")
    void explicitNullsAreRefused() throws Exception {
      for (String key : List.of("subject", "message", "language")) {
        assertNotSent(McpDocumentEmailTool.run(argsTo(BASE).put(key, JSONObject.NULL)), 422,
            "validation_error");
      }
    }

    @Test
    @DisplayName("an empty registry warns once per outage and warns again after it recovers")
    void emptyRegistryWarnsOncePerOutage() {
      EmailContractRegistry empty = EmailContractRegistry.empty();
      EmailContractRegistry full = realContracts();
      contractsMock.when(McpDocumentEmailContracts::registry).thenCallRealMethod();
      try (MockedStatic<com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry> created =
          mockStatic(com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry.class);
          LogCapture log = new LogCapture(McpDocumentEmailContracts.class)) {
        com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry none =
            mock(com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry.class);
        com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry some =
            mock(com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry.class);
        when(none.contracts()).thenReturn(empty.contracts());
        when(some.contracts()).thenReturn(full.contracts());
        created.when(com.etendoerp.go.schemaforge.email.DefaultEmailContractRegistry::createDefault)
            .thenReturn(some, none, none, some, none);

        McpDocumentEmailContracts.registry();
        McpDocumentEmailContracts.registry();
        assertEquals(1, log.events().size(), "first outage warns once");
        McpDocumentEmailContracts.registry();
        assertEquals(1, log.events().size(), "the same outage does not warn again");
        McpDocumentEmailContracts.registry();
        McpDocumentEmailContracts.registry();
        assertEquals(2, log.events().size(), "a later outage warns again");
      }
    }

    @Test
    @DisplayName("an id is required")
    void idIsRequired() throws Exception {
      JSONObject call = new JSONObject().put("spec", SPEC).put("to", new JSONArray(List.of(BASE)));

      assertNotSent(McpDocumentEmailTool.run(call), 422, "validation_error");
    }
  }

  // ── preview ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("preview")
  class Preview {

    @Test
    @DisplayName("shows the defaults, the recipient and the PDF that would go, and sends nothing")
    void showsWhatWouldBeSent() throws Exception {
      JSONObject result = McpDocumentEmailTool.run(args("preview", "true").put("preview", true));

      assertFalse(result.has("error"), result.toString());
      assertTrue(result.getBoolean("preview"));
      assertFalse(result.getBoolean("sent"));
      JSONObject defaults = result.getJSONObject("defaults");
      assertEquals("Pedido de Compra #1000008 — Proveedor Mayorista", defaults.getString("subject"));
      assertEquals("Hola, **Proveedor Mayorista**:\n\nLe enviamos su Pedido de Compra **1000008**.",
          defaults.getString("message"));
      assertEquals(List.of(BASE), strings(defaults.getJSONArray("to")));
      assertEquals("es_ES", result.getString("language"));
      JSONObject limits = result.getJSONObject("limits");
      assertEquals(10, limits.getInt("maxRecipients"));
      assertEquals(200, limits.getInt("maxSubjectLength"));
      assertEquals(5000, limits.getInt("maxMessageLength"));
      JSONObject pdf = result.getJSONObject("pdf");
      assertTrue(pdf.getBoolean("ready"));
      assertEquals("PO1.pdf", pdf.getJSONObject("file").getString("name"));
      verify(service, never()).send(anyString(), any(JSONObject.class));
    }

    @Test
    @DisplayName("reports a PDF that is missing or stale without failing the preview")
    void reportsPdfProblems() throws Exception {
      pdfMock.when(() -> NeoDocumentPdf.resolve(target, header))
          .thenReturn(NeoDocumentPdf.Result.stale(pdfFile()));

      JSONObject result = McpDocumentEmailTool.run(args().put("preview", true));

      assertFalse(result.has("error"), result.toString());
      JSONObject pdf = result.getJSONObject("pdf");
      assertFalse(pdf.getBoolean("ready"));
      assertEquals("pdf_stale", pdf.getString("error"));
      assertFalse(result.getBoolean("readyToSend"));
    }

    @Test
    @DisplayName("is authorized at the same write tier and refuses a foreign document the same way")
    void previewIsAuthorizedLikeTheSend() throws Exception {
      targetMock.when(() -> NeoAttachmentTarget.resolve(eq(spec), eq(header), anyString(),
          eq(true))).thenThrow(new NeoAttachmentTarget.Refused(404,
              NeoAttachmentTarget.MSG_RECORD_NOT_FOUND));

      JSONObject result = McpDocumentEmailTool.run(args().put("preview", true));

      assertEquals("not_found", result.getString("error"));
    }
  }

  // ── which documents are offered ─────────────────────────────────────────

  @Nested
  @DisplayName("offered documents")
  class Offered {

    @Test
    @DisplayName("are exactly the specs of the registered document-send contracts")
    void derivedFromTheRegisteredContracts() {
      List<String> offered = McpDocumentEmailContracts.supportedSpecs(Arrays.asList(
          "purchase-invoice", "purchase-order", "sales-order", "sales-invoice", "sales-quotation",
          "goods-shipment", "return-material-receipt", "return-to-vendor",
          "return-to-vendor-shipment", "reset-password", "business-partner"));

      assertEquals(List.of("purchase-order", "sales-order", "sales-invoice", "sales-quotation",
          "goods-shipment", "return-material-receipt", "return-to-vendor",
          "return-to-vendor-shipment"), offered);
      assertFalse(offered.contains("purchase-invoice"));
    }

    @Test
    @DisplayName("a spec the caller cannot address is not offered even if it has a contract")
    void onlyAddressableSpecs() {
      assertEquals(List.of("sales-order"),
          McpDocumentEmailContracts.supportedSpecs(List.of("sales-order", "business-partner")));
    }

    @Test
    @DisplayName("the contract of a spec is the document-send contract named for it, never another kind")
    void contractLookup() {
      EmailContractRegistry registry = McpDocumentEmailContracts.registry();
      assertTrue(McpDocumentEmailContracts.forSpec("sales-invoice", registry).isPresent());
      assertFalse(McpDocumentEmailContracts.forSpec("purchase-invoice", registry).isPresent());
      assertFalse(McpDocumentEmailContracts.forSpec("reset-password", registry).isPresent());
      assertFalse(McpDocumentEmailContracts.forSpec(null, registry).isPresent());
    }

    @Test
    @DisplayName("the tool definition lists only those specs and requires spec, id and to")
    void definitionEnumIsTheOfferedSpecs() {
      McpToolDefinition definition = McpDocumentEmailToolDefinition.definition(
          McpDocumentEmailContracts.supportedSpecs(
              List.of("purchase-invoice", "purchase-order", "sales-invoice")));

      assertEquals("etendo_send_document_email", definition.getName());
      String schema = definition.getInputSchema().toString();
      assertTrue(schema.contains("purchase-order") && schema.contains("sales-invoice"), schema);
      assertFalse(schema.contains("purchase-invoice"), schema);
      assertTrue(schema.contains("preview") && schema.contains("subject")
          && schema.contains("message") && schema.contains("cc"), schema);
      assertEquals(List.of("spec", "id"), definition.getInputSchema().get("required"));
    }

    @Test
    @DisplayName("the description states what the tool really does, so an agent cannot mistake it")
    void descriptionPinsTheKeyClaims() {
      String description = McpDocumentEmailToolDefinition.DESCRIPTION;
      assertTrue(description.contains("sends a REAL email"), description);
      assertTrue(description.contains("preview"), description);
      assertTrue(description.contains("never generates"), description);
      assertTrue(description.contains("DUPLICATE") || description.contains("identical"),
          description);
    }

    @Test
    @DisplayName("no tool is offered when no contract is registered")
    void emptyWhenNoContracts() {
      contractsMock.when(McpDocumentEmailContracts::registry)
          .thenReturn(EmailContractRegistry.empty());

      assertTrue(McpDocumentEmailContracts.supportedSpecs(List.of("sales-order")).isEmpty());
    }
  }

  // ── argument parsing on its own ─────────────────────────────────────────

  @Nested
  @DisplayName("arguments")
  class Arguments {

    @Test
    @DisplayName("addresses are trimmed, de-duplicated ignoring case, and cc drops what is in to")
    void normalizesRecipients() throws Exception {
      McpDocumentEmailRequest request = McpDocumentEmailRequest.parse(argsTo("  Ana@Cliente.example ",
          "ana@cliente.example", "luis@cliente.example").put("cc", new JSONArray(
              List.of("LUIS@cliente.example", "jefe@cliente.example", "jefe@cliente.example"))));

      assertEquals(List.of("Ana@Cliente.example", "luis@cliente.example"), request.getTo());
      assertEquals(List.of("jefe@cliente.example"), request.getCc());
    }

    @Test
    @DisplayName("a blank subject or message means 'use the default'")
    void blankMeansDefault() throws Exception {
      McpDocumentEmailRequest request = McpDocumentEmailRequest.parse(argsTo(BASE)
          .put("subject", "   ").put("message", ""));

      assertNull(request.getSubject());
      assertNull(request.getMessage());
    }

    @Test
    @DisplayName("line breaks and tabs are fine in the message, other control characters are not")
    void messageControlCharacters() throws Exception {
      assertEquals("a\r\nb\tc", McpDocumentEmailRequest.parse(argsTo(BASE)
          .put("message", "a\r\nb\tc")).getMessage());
      assertThrows(McpDocumentEmailRequest.Invalid.class, () -> McpDocumentEmailRequest.parse(
          argsTo(BASE).put("message", "a\u0000b")));
    }

    @Test
    @DisplayName("'to' must be a list of strings")
    void toMustBeAListOfStrings() throws Exception {
      assertThrows(McpDocumentEmailRequest.Invalid.class, () -> McpDocumentEmailRequest.parse(
          args().put("to", BASE)));
      assertThrows(McpDocumentEmailRequest.Invalid.class, () -> McpDocumentEmailRequest.parse(
          args().put("to", new JSONArray(List.of(5)))));
    }
  }

  private static List<String> strings(JSONArray array) throws Exception {
    List<String> list = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      list.add(array.getString(i));
    }
    return list;
  }
}
