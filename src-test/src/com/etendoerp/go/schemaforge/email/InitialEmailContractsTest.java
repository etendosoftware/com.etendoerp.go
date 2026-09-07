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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.contracts.CoreEmailContractProvider;
import com.etendoerp.go.schemaforge.email.contracts.GoodsShipmentSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.PurchaseOrderSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.ReturnToVendorSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesInvoiceSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesOrderSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract;

/**
 * Tests the built-in transactional email contracts.
 */
public class InitialEmailContractsTest {

  /**
   * Templates the provider gateway exposes, taken verbatim from its own rejection message
   * (ETP-4786). Anything outside this set is answered with HTTP 400.
   */
  private static final List<String> GATEWAY_TEMPLATE_ALLOWLIST =
      Arrays.asList("reset-password", "login-alert", "invoice", "custom");

  @After
  public void clearProperties() {
    System.clearProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY);
    System.clearProperty("etgo.app.url");
  }

  @Test
  public void registersInitialContractsButNotCustomPayloads() {
    DefaultEmailContractRegistry registry = DefaultEmailContractRegistry.create(
        fixtureProviders());

    assertTrue(registry.find("reset-password").isPresent());
    assertTrue(registry.find("new-account").isPresent());
    assertTrue(registry.find("environment-ready").isPresent());
    assertTrue(registry.find("password-changed").isPresent());
    assertTrue(registry.find("login-alert").isPresent());
    assertTrue(registry.find("sales-invoice-send").isPresent());
    assertTrue(registry.find("sales-order-send").isPresent());
    assertTrue(registry.find("sales-quotation-send").isPresent());
    assertFalse(registry.find("custom").isPresent());
    assertFalse(registry.find("support-custom-email").isPresent());
  }

  // ── ETP-5069: the readable-history gate, contract by contract ───────────────

  @Test
  public void everyDocumentSendContractLogsReadableHistoryUnderItsOwnSpec() {
    // The six windows whose send button an operator can press. The recipients are the tenant's
    // own business partners and the copy is the tenant's own, so the readable table is the right
    // trade — and the document's window is where the history has to show up.
    EmailDocumentRecordResolver resolver = recordId -> Optional.empty();

    assertLogsHistoryUnderSpec(new SalesInvoiceSendEmailContract(resolver), "sales-invoice");
    assertLogsHistoryUnderSpec(new SalesOrderSendEmailContract(resolver), "sales-order");
    assertLogsHistoryUnderSpec(new SalesQuotationSendEmailContract(resolver), "sales-quotation");
    assertLogsHistoryUnderSpec(new PurchaseOrderSendEmailContract(resolver), "purchase-order");
    assertLogsHistoryUnderSpec(new GoodsShipmentSendEmailContract(resolver), "goods-shipment");
    // Known naming quirk: the window is return-to-vendor-shipment, so the derived spec resolves
    // no window. The column is nullable and best effort, and the history row is still written.
    assertLogsHistoryUnderSpec(new ReturnToVendorSendEmailContract(resolver), "return-to-vendor");
  }

  @Test
  public void accountAndAuthContractsNeverReachTheReadableHistory() {
    // Their recipients are platform users and their copy carries single-use links, so they keep
    // the interface default. The anti-abuse ledger still records every one of them.
    DefaultEmailContractRegistry registry = DefaultEmailContractRegistry.create(
        fixtureProviders());

    for (String contractName : Arrays.asList("reset-password", "new-account", "set-password",
        "environment-ready", "password-changed", "login-alert", "company-invitation")) {
      EmailContract contract = registry.find(contractName).orElseThrow(
          () -> new AssertionError("contract not registered: " + contractName));
      assertFalse(contractName + " must not log readable history", contract.logsSendHistory());
      assertNull(contractName + " must not declare a spec", contract.getSpecName());
    }
  }

  private static void assertLogsHistoryUnderSpec(EmailContract contract, String expectedSpec) {
    assertTrue(contract.getName() + " must log readable history", contract.logsSendHistory());
    assertEquals(expectedSpec, contract.getSpecName());
  }

  @Test
  public void resetPasswordUsesAccountRecipientAndTemplateVariables() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK,
        "https://app.example.test/reset?token=abc123");

    NeoResponse response = service.send("reset-password", command);

    assertSent(response);
    assertEquals("account@example.com", adapter.getLastRequest().getRecipient());
    // ETP-5003 — migrated off the provider-branded template onto the shared layout.
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    assertEquals("Lucas", adapter.getLastRequest().getData().getString("name"));
    assertEquals("https://app.example.test/reset?token=abc123",
        adapter.getLastRequest().getData().getString("link"));
    assertEquals("Restablece tu contraseña de Etendo Go",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("https://app.example.test/reset?token=abc123"));
  }

  @Test
  public void newAccountUsesAccountRecipientAndTemplateVariables() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/welcome");

    NeoResponse response = service.send("new-account", command);

    assertSent(response);
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    assertEquals("account@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Lucas", adapter.getLastRequest().getData().getString("name"));
    assertEquals("https://app.example.test/welcome",
        adapter.getLastRequest().getData().getString("link"));
    // ETP-5003 — a command with no language now falls back to Spanish, the product's default,
    // instead of English.
    assertEquals("Bienvenido a Etendo Go",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("https://app.example.test/welcome"));
  }

  @Test
  public void newAccountPassesSelectedLanguageToProviderTemplateData() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/welcome");
    command.put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES");

    NeoResponse response = service.send("new-account", command);

    assertSent(response);
    assertEquals("es_ES", adapter.getLastRequest().getData().getString("language"));
    assertEquals("Bienvenido a Etendo Go",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("Tu cuenta de Etendo Go fue creada correctamente"));
  }

  @Test
  public void newAccountOmitsLanguageWhenCommandDoesNotProvideIt() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/welcome");

    NeoResponse response = service.send("new-account", command);

    assertSent(response);
    assertFalse(adapter.getLastRequest().getData().has("language"));
  }

  @Test
  public void environmentReadyUsesDistinctWelcomeTemplateAndRecordIdIdempotency()
      throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "https://app.example.test");
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = service(adapter, safetyStore);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "client-1");

    NeoResponse response = service.send("environment-ready", command);

    assertSent(response);
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    assertEquals("account@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Lucas", adapter.getLastRequest().getData().getString("name"));
    assertEquals("https://app.example.test/dashboard",
        adapter.getLastRequest().getData().getString("link"));
    assertEquals("Tu entorno de Etendo Go está listo",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("https://app.example.test/dashboard"));
    assertEquals("environment-ready:tenant-1:client-1:v1",
        safetyStore.getAuditRecords().get(0).getIdempotencyKey());
  }

  @Test
  public void environmentReadyRejectsMissingConfiguredAppBaseUrl() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "/");
    System.setProperty("etgo.app.url", "/");
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "client-1");

    NeoResponse response = service.send("environment-ready", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Configured app base URL is required for this email contract",
        data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void environmentReadyUsesSelectedLanguageForCustomContent() throws Exception {
    System.setProperty(PublicUrlResolver.APP_BASE_URL_PROPERTY, "https://app.example.test");
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "client-1");
    command.put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES");

    NeoResponse response = service.send("environment-ready", command);

    assertSent(response);
    assertEquals("es_ES", adapter.getLastRequest().getData().getString("language"));
    assertEquals("Tu entorno de Etendo Go está listo",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("Haz clic en el siguiente botón para acceder a tu panel"));
  }

  @Test
  public void passwordChangedUsesAccountRecipientAndNoticePayload() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_DATE, "2026-05-29T10:00:00Z");

    NeoResponse response = service.send("password-changed", command);

    assertSent(response);
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    assertEquals("account@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Lucas", adapter.getLastRequest().getData().getString("name"));
    assertEquals("2026-05-29T10:00:00Z",
        adapter.getLastRequest().getData().getString("date"));
    assertEquals("Tu contraseña de Etendo Go fue modificada",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("contacta a soporte"));
    assertFalse(adapter.getLastRequest().getData().has("link"));
  }

  @Test
  public void passwordChangedUsesSelectedLanguageForCustomContent() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_DATE, "2026-05-29T10:00:00Z");
    command.put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES");

    NeoResponse response = service.send("password-changed", command);

    assertSent(response);
    assertEquals("es_ES", adapter.getLastRequest().getData().getString("language"));
    assertEquals("Tu contraseña de Etendo Go fue modificada",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("contacta a soporte"));
  }

  @Test
  public void accountLinkDefaultsBlankContactNameToUser() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-blank-name");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/welcome");

    NeoResponse response = service.send("new-account", command);

    assertSent(response);
    assertEquals("blank-name@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("User", adapter.getLastRequest().getData().getString("name"));
  }

  @Test
  public void loginAlertUsesUserRecipientAndEventMetadata() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_USER_ID, "user-1");
    command.put(EmailContractCommandSupport.FIELD_LOGIN_EVENT_ID, "login-1");
    command.put(EmailContractCommandSupport.FIELD_IP, "190.123.45.67");
    command.put(EmailContractCommandSupport.FIELD_DATE, "2026-04-13 10:32");

    NeoResponse response = service.send("login-alert", command);

    assertSent(response);
    // ETP-5003 — migrated off the provider-branded template onto the shared layout.
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    assertEquals("user@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Ana", adapter.getLastRequest().getData().getString("name"));
    assertEquals("190.123.45.67", adapter.getLastRequest().getData().getString("ip"));
    assertEquals("2026-04-13 10:32", adapter.getLastRequest().getData().getString("date"));
  }

  @Test
  public void loginAlertDefaultsMissingIpAndDate() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_USER_ID, "user-1");

    NeoResponse response = service.send("login-alert", command);

    assertSent(response);
    assertEquals("unknown", adapter.getLastRequest().getData().getString("ip"));
    assertTrue(adapter.getLastRequest().getData().getString("date").contains("T"));
  }

  @Test
  public void loginAlertRejectsInvalidResolvedRecipient() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_USER_ID, "user-invalid-email");

    NeoResponse response = service.send("login-alert", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email recipient is invalid", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void salesInvoiceSendUsesDocumentRecipientAndInvoiceVariables() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "invoice-1");

    NeoResponse response = service.send("sales-invoice-send", command);

    assertSent(response);
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    assertEquals("billing@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Empresa SRL", adapter.getLastRequest().getData().getString("name"));
    assertEquals("0001-00042",
        adapter.getLastRequest().getData().getString("invoice_number"));
    assertEquals("0001-00042",
        adapter.getLastRequest().getData().getString("document_number"));
    assertEquals("Sales Invoice",
        adapter.getLastRequest().getData().getString("document_type"));
    assertEquals("1500.00 USD", adapter.getLastRequest().getData().getString("amount"));
    assertEquals("https://app.example.test/doc/sales-invoice/invoice-1",
        adapter.getLastRequest().getData().getString("download_link"));
  }

  @Test
  public void salesOrderSendUsesDefaultDocumentPayload() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");

    NeoResponse response = service.send("sales-order-send", command);

    assertSent(response);
    assertEquals(DefaultDocumentSendEmailContract.DEFAULT_TEMPLATE,
        adapter.getLastRequest().getTemplate());
    assertEquals("orders@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Empresa SRL", adapter.getLastRequest().getData().getString("name"));
    assertEquals("SO-0007", adapter.getLastRequest().getData().getString("document_number"));
    assertEquals("Sales Order", adapter.getLastRequest().getData().getString("document_type"));
    assertFalse(adapter.getLastRequest().getData().has("order_number"));
    assertFalse(adapter.getLastRequest().getData().has("amount"));
    assertEquals("https://app.example.test/doc/sales-order/order-1",
        adapter.getLastRequest().getData().getString("download_link"));
  }

  @Test
  public void salesQuotationSendUsesDefaultDocumentPayload() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "quotation-1");

    NeoResponse response = service.send("sales-quotation-send", command);

    assertSent(response);
    assertEquals(DefaultDocumentSendEmailContract.DEFAULT_TEMPLATE,
        adapter.getLastRequest().getTemplate());
    assertEquals("quotes@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Empresa SRL", adapter.getLastRequest().getData().getString("name"));
    assertEquals("SQ-0009", adapter.getLastRequest().getData().getString("document_number"));
    assertEquals("Sales Quotation",
        adapter.getLastRequest().getData().getString("document_type"));
    assertFalse(adapter.getLastRequest().getData().has("quotation_number"));
    assertFalse(adapter.getLastRequest().getData().has("amount"));
    assertEquals("https://app.example.test/doc/sales-quotation/quotation-1",
        adapter.getLastRequest().getData().getString("download_link"));
  }

  /**
   * ETP-4786 regression guard.
   * <p>
   * The document-send family used to emit the template {@code "document"}, which the provider
   * gateway does not expose. Every send answered
   * {@code 400 {"error": "Unknown template 'document'. Available: ['reset-password',
   * 'login-alert', 'invoice', 'custom']"}} and surfaced as PROVIDER_FAILED in the UI. The
   * pre-existing assertions compared the emitted template against
   * {@link DefaultDocumentSendEmailContract#DEFAULT_TEMPLATE} itself, so they kept passing with
   * an unroutable value. This pins the gateway's set instead, verified by direct probe on
   * 2026-08-06.
   */
  @Test
  public void documentSendDefaultTemplateIsExposedByTheProviderGateway() {
    assertTrue("DEFAULT_TEMPLATE must be a template the provider gateway exposes "
            + GATEWAY_TEMPLATE_ALLOWLIST + " but was '"
            + DefaultDocumentSendEmailContract.DEFAULT_TEMPLATE + "'",
        GATEWAY_TEMPLATE_ALLOWLIST.contains(DefaultDocumentSendEmailContract.DEFAULT_TEMPLATE));
  }

  @Test
  public void documentSendEmitsAllowlistedTemplateWithContractOwnedSubjectAndBody()
      throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");

    NeoResponse response = service.send("sales-order-send", command);

    assertSent(response);
    String template = adapter.getLastRequest().getTemplate();
    assertTrue("Emitted template '" + template + "' is not exposed by the provider gateway "
        + GATEWAY_TEMPLATE_ALLOWLIST, GATEWAY_TEMPLATE_ALLOWLIST.contains(template));

    // The content template renders caller-supplied copy, so the contract must provide it.
    JSONObject data = adapter.getLastRequest().getData();
    assertEquals("Pedido de Venta #SO-0007 — Empresa SRL", data.getString("subject"));
    assertTrue(data.getString("body").contains("SO-0007"));
    assertTrue(data.getString("body").contains(
        "https://app.example.test/doc/sales-order/order-1"));
    // The provider variables the document payload already carried stay untouched.
    assertEquals("Sales Order", data.getString("document_type"));
  }

  /**
   * ETP-4717 + ETP-4786 — option B: an edited send swaps the contract's branded template for the
   * content template, for that send only. Sales invoice is the case that matters, because it is the
   * only contract with branded copy today.
   */
  @Test
  public void editedSalesInvoiceSendKeepsTheSharedLayout() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "invoice-1");
    JSONObject edits = new JSONObject();
    edits.put("subject", "Su factura corregida");
    edits.put("message", "Adjuntamos la factura\ncon el importe corregido.");
    command.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, edits);

    NeoResponse response = service.send("sales-invoice-send", command);

    assertSent(response);
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    JSONObject data = adapter.getLastRequest().getData();
    assertEquals("Su factura corregida", data.getString("subject"));
    // Newlines become <br>, and the operator message is always followed by the document link
    // (ETP-4717 reopened) so the edited send never drops it.
    String body = data.getString("body");
    assertTrue(body.contains("Adjuntamos la factura<br>con el importe corregido."));
    assertTrue(body.contains("https://app.example.test/doc/sales-invoice/invoice-1"));
    // ETP-5003 — an edited send is no longer a downgrade: it carries the same shared layout an
    // untouched one does.
    assertTrue(body.startsWith("<!DOCTYPE html>"));
    assertEquals("0001-00042", data.getString("invoice_number"));
  }

  @Test
  public void untouchedSalesInvoiceSendUsesTheSharedLayout() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "invoice-1");

    NeoResponse response = service.send("sales-invoice-send", command);

    assertSent(response);
    // ETP-5003 — migrated off the provider's branded "invoice" template, so the contract now
    // supplies the subject and the rendered document itself.
    assertEquals("custom", adapter.getLastRequest().getTemplate());
    JSONObject data = adapter.getLastRequest().getData();
    assertEquals("Factura de Venta #0001-00042 — Empresa SRL", data.getString("subject"));
    assertTrue(data.getString("body").startsWith("<!DOCTYPE html>"));
    assertTrue(data.getString("body").contains("Descargar documento"));
  }

  @Test
  public void editedDocumentSendEscapesOperatorMarkup() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");
    JSONObject edits = new JSONObject();
    edits.put("message", "<script>alert('x')</script> & listo");
    command.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, edits);

    NeoResponse response = service.send("sales-order-send", command);

    assertSent(response);
    String body = adapter.getLastRequest().getData().getString("body");
    // The operator's markup is inert, and the document link still follows it (ETP-4717 reopened),
    // now as the shared layout's button plus its fallback (ETP-5003).
    assertFalse(body.contains("<script>"));
    assertTrue(body.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; listo"));
    assertTrue(body.contains("https://app.example.test/doc/sales-order/order-1"));
  }

  @Test
  public void malformedMessageEditsAreRejectedBeforeReachingTheProvider() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");
    JSONObject edits = new JSONObject();
    edits.put("bodyHtml", "<b>nope</b>");
    command.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, edits);

    NeoResponse response = service.send("sales-order-send", command);

    JSONObject data = responseData(response);
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals(0, adapter.getSendCount());
  }

  /**
   * Without the content hash in the send key, correcting the message and re-sending to the same
   * recipients collides with the previous send and is answered DUPLICATE, so nothing is delivered.
   */
  @Test
  public void editingTheMessageAndResendingIsNotTreatedAsADuplicate() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = service(adapter, safetyStore);

    JSONObject first = baseCommand();
    first.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");
    JSONObject firstEdits = new JSONObject();
    firstEdits.put("message", "Primer intento");
    first.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, firstEdits);
    assertSent(service.send("sales-order-send", first));

    JSONObject second = baseCommand();
    second.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");
    JSONObject secondEdits = new JSONObject();
    secondEdits.put("message", "Texto corregido");
    second.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, secondEdits);

    NeoResponse response = service.send("sales-order-send", second);

    assertSent(response);
    assertEquals(2, adapter.getSendCount());
    // The message is wrapped and followed by the download-link paragraph (ETP-4717 reopened).
    assertBodyCarries("Texto corregido", "https://app.example.test/doc/sales-order/order-1",
        adapter.getLastRequest().getData().getString("body"));
  }

  @Test
  public void authContractsRejectMessageEdits() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/reset?token=a");
    JSONObject edits = new JSONObject();
    edits.put("subject", "intento de override");
    command.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, edits);

    NeoResponse response = service.send("reset-password", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals("messageEdits is not accepted by this contract", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void documentTemplateCanBeOverriddenByConfiguration() {
    assertEquals(DefaultDocumentSendEmailContract.DEFAULT_TEMPLATE,
        DefaultDocumentSendEmailContract.resolveDefaultTemplate());
    System.setProperty(DefaultDocumentSendEmailContract.PROP_DOCUMENT_TEMPLATE, "document");
    try {
      assertEquals("document", DefaultDocumentSendEmailContract.resolveDefaultTemplate());
    } finally {
      System.clearProperty(DefaultDocumentSendEmailContract.PROP_DOCUMENT_TEMPLATE);
    }
  }

  @Test
  public void documentContractDefaultsBlankRecipientNameToCustomer() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-blank-name");

    NeoResponse response = service.send("sales-order-send", command);

    assertSent(response);
    assertEquals("blank-document@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Customer", adapter.getLastRequest().getData().getString("name"));
  }

  @Test
  public void documentContractRejectsMissingDocument() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "missing-document");

    NeoResponse response = service.send("sales-order-send", command);

    JSONObject data = responseData(response);
    assertEquals(404, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email document record was not found", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void documentContractReturnsNoRecipientForInvalidBaseEmailWithoutEdits() throws Exception {
    // Edge case 4 (ETP-4226): with editable recipients, a base contact without a usable email
    // and no caller edits now resolves to NO_RECIPIENT instead of VALIDATION_FAILED.
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-invalid-email");

    NeoResponse response = service.send("sales-order-send", command);

    JSONObject data = responseData(response);
    assertEquals(422, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_NO_RECIPIENT, data.getString("status"));
    assertEquals("Document has no recipient email", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void documentContractRejectsMissingDownloadLink() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-invalid-link");

    // Make the test hermetic: the download link is resolved via System property -> env var ->
    // Openbravo.properties. Clear the System properties for this test and mock
    // OBPropertiesProvider so the Openbravo.properties fallback never supplies the keys, forcing
    // the "missing download link" path regardless of the ambient Openbravo.properties contents.
    withoutDocumentDownloadConfig(() -> {
      NeoResponse response = service.send("sales-order-send", command);

      JSONObject data = responseData(response);
      assertEquals(400, response.getHttpStatus());
      assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
      assertEquals("Document download link is not configured", data.getString("message"));
      assertEquals(0, adapter.getSendCount());
    });
  }

  @Test
  public void documentContractGeneratesSignedDownloadLinkWhenConfigured() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-generated-link");

    withDocumentDownloadProperties("https://go.example.test/etendo/sws/neo/document-download",
        "download-secret", () -> {
          NeoResponse response = service.send("sales-order-send", command);

          assertSent(response);
          String downloadLink = adapter.getLastRequest().getData().optString("download_link");
          assertTrue(downloadLink.startsWith(
              "https://go.example.test/etendo/sws/neo/document-download/"));
          String path = URI.create(downloadLink).getPath();
          String token = path.substring(path.lastIndexOf('/') + 1);
          DocumentDownloadTokenService.Claims claims = DocumentDownloadTokenService.validate(token)
              .orElseThrow(() -> new AssertionError("Token claims should be valid"));
          assertEquals("sales-order-send", claims.getContractName());
          assertEquals("sales-order", claims.getSpecName());
          assertEquals("order-generated-link", claims.getRecordId());
          assertEquals("tenant-1", claims.getClientId());
          assertEquals("sales-order-send:tenant-1:order-generated-link:v1",
              claims.getIdempotencyKey());
        });
  }

  @Test
  public void rejectsUnsupportedVersionAsValidationFailure() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_VERSION, "v2");
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK,
        "https://app.example.test/reset?token=abc123");

    NeoResponse response = service.send("reset-password", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Unsupported email contract version", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void rejectsInvalidContractLink() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
    command.put(EmailContractCommandSupport.FIELD_LINK, "javascript:alert(1)");

    NeoResponse response = service.send("reset-password", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email contract link must be an absolute HTTP URL", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void rejectsUnknownAccountForAccountLinkContract() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "missing-account");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/welcome");

    NeoResponse response = service.send("new-account", command);

    JSONObject data = responseData(response);
    assertEquals(404, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email account record was not found", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void rejectsInvalidAccountRecipient() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-invalid-email");
    command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/welcome");

    NeoResponse response = service.send("new-account", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email recipient is invalid", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void accountLinkContractRejectsRecipientEdits() throws Exception {
    assertNonDocumentContractRejectsRecipientEdits("reset-password", command -> {
      command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1");
      command.put(EmailContractCommandSupport.FIELD_LINK, "https://app.example.test/reset");
    });
  }

  @Test
  public void accountNoticeContractRejectsRecipientEdits() throws Exception {
    assertNonDocumentContractRejectsRecipientEdits("password-changed", command ->
        command.put(EmailContractCommandSupport.FIELD_ACCOUNT_ID, "account-1"));
  }

  @Test
  public void loginAlertContractRejectsRecipientEdits() throws Exception {
    assertNonDocumentContractRejectsRecipientEdits("login-alert", command ->
        command.put(EmailContractCommandSupport.FIELD_USER_ID, "user-1"));
  }

  @Test
  public void keepsCustomContractDisabledByDefault() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    NeoResponse response = service.send("custom", baseCommand());

    JSONObject data = responseData(response);
    assertEquals(404, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Unknown email contract", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void emailContactRecordUsesNormalizedValueEquality() {
    EmailContactRecord first = new EmailContactRecord(" Lucas ", " account@example.com ");
    EmailContactRecord second = new EmailContactRecord("Lucas", "account@example.com");
    EmailContactRecord different = new EmailContactRecord("Lucas", "other@example.com");

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, different);
    assertNotEquals(first, "Lucas");
  }

  private static void assertNonDocumentContractRejectsRecipientEdits(String contractName,
      CommandCustomizer customizer) throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    customizer.apply(command);
    command.put(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS,
        new JSONObject("{\"to\":{\"add\":[\"external@example.com\"]}}"));

    NeoResponse response = service.send(contractName, command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("recipientEdits is not accepted by this contract", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @FunctionalInterface
  private interface CommandCustomizer {
    void apply(JSONObject command) throws JSONException;
  }

  private static TransactionalEmailService service(FakeProviderAdapter adapter) {
    return new TransactionalEmailService(
        DefaultEmailContractRegistry.create(fixtureProviders()), adapter,
        new InMemoryEmailSafetyStore());
  }

  private static TransactionalEmailService service(FakeProviderAdapter adapter,
      InMemoryEmailSafetyStore safetyStore) {
    return new TransactionalEmailService(
        DefaultEmailContractRegistry.create(fixtureProviders()), adapter, safetyStore);
  }

  private static List<EmailContractProvider> fixtureProviders() {
    FixtureDataResolver dataResolver = new FixtureDataResolver();
    return Arrays.asList(new CoreEmailContractProvider(dataResolver),
        new FixtureSalesDocumentEmailContractProvider());
  }

  private static JSONObject baseCommand() throws JSONException {
    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
    command.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    return command;
  }

  /**
   * Asserts a document email carries the operator's copy and still offers the document link.
   *
   * <p>Deliberately not an equality check on the whole body: the shared layout owns the markup
   * around it (ETP-5003), and pinning that here would make every layout tweak fail a contract
   * test. {@code EmailLayoutTest} pins the markup itself.</p>
   */
  private static void assertBodyCarries(String copy, String documentLink, String body) {
    assertTrue("copy missing from body", body.contains(copy));
    assertTrue("document link missing from body", body.contains(documentLink));
  }

  private static void assertSent(NeoResponse response) throws JSONException {
    JSONObject data = responseData(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, data.getString("status"));
  }

  private static void withDocumentDownloadProperties(String baseUrl, String secret,
      ThrowingRunnable runnable) throws Exception {
    String previousBaseUrl = System.getProperty(
        DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL);
    String previousSecret = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    try {
      System.setProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL, baseUrl);
      System.setProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, secret);
      runnable.run();
    } finally {
      restoreProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL, previousBaseUrl);
      restoreProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, previousSecret);
    }
  }

  /**
   * Runs the given block with no document-download configuration resolvable. Clears the System
   * properties and mocks {@link OBPropertiesProvider} so the Openbravo.properties fallback returns
   * empty properties, making the "missing download link" assertions deterministic regardless of the
   * ambient Openbravo.properties. Environment variables cannot be set in-process and are assumed
   * absent in the test environment.
   */
  private static void withoutDocumentDownloadConfig(ThrowingRunnable runnable) throws Exception {
    String previousBaseUrl = System.getProperty(
        DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL);
    String previousSecret = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    try {
      System.clearProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL);
      System.clearProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
      OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
      when(provider.getOpenbravoProperties()).thenReturn(new Properties());
      try (MockedStatic<OBPropertiesProvider> propertiesMock =
          mockStatic(OBPropertiesProvider.class)) {
        propertiesMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
        runnable.run();
      }
    } finally {
      restoreProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL, previousBaseUrl);
      restoreProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, previousSecret);
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static JSONObject responseData(NeoResponse response) throws JSONException {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  /**
   * Resolver that provides fixture data for built-in email contract tests.
   */
  private static final class FixtureDataResolver implements EmailContractDataResolver {
    @Override
    public Optional<EmailContactRecord> findAccountContact(String accountId) {
      if ("account-1".equals(accountId)) {
        return Optional.of(new EmailContactRecord("Lucas", "account@example.com"));
      }
      if ("account-blank-name".equals(accountId)) {
        return Optional.of(new EmailContactRecord(" ", "blank-name@example.com"));
      }
      if ("account-invalid-email".equals(accountId)) {
        return Optional.of(new EmailContactRecord("Lucas", "not-an-email"));
      }
      return Optional.empty();
    }

    @Override
    public Optional<EmailContactRecord> findUserContact(String userId) {
      if ("user-1".equals(userId)) {
        return Optional.of(new EmailContactRecord("Ana", "user@example.com"));
      }
      if ("user-invalid-email".equals(userId)) {
        return Optional.of(new EmailContactRecord("Ana", "not-an-email"));
      }
      return Optional.empty();
    }

  }

  private static final class FixtureSalesDocumentEmailContractProvider
      implements EmailContractProvider {

    @Override
    public Collection<EmailContract> getContracts() {
      return Arrays.asList(
          new SalesInvoiceSendEmailContract(recordId -> resolveDocument(recordId, "invoice-1",
              "billing@example.com", "0001-00042", "1500.00 USD",
              "https://app.example.test/doc/sales-invoice/invoice-1")),
          new SalesOrderSendEmailContract(recordId -> resolveDocument(recordId, "order-1",
              "orders@example.com", "SO-0007", "2600.50 USD",
              "https://app.example.test/doc/sales-order/order-1")),
          new SalesQuotationSendEmailContract(recordId -> resolveDocument(recordId, "quotation-1",
              "quotes@example.com", "SQ-0009", "950.25 USD",
              "https://app.example.test/doc/sales-quotation/quotation-1")));
    }

    private Optional<EmailDocumentRecord> resolveDocument(String recordId, String expectedId,
        String recipientEmail, String documentNo, String amount, String downloadLink) {
      if ("order-blank-name".equals(recordId)) {
        return Optional.of(new EmailDocumentRecord(" ", "blank-document@example.com",
            "SO-BLANK", "10.00 USD", "https://app.example.test/doc/sales-order/order-blank-name",
            "tenant-1"));
      }
      if ("order-invalid-email".equals(recordId)) {
        return Optional.of(new EmailDocumentRecord("Empresa SRL", "not-an-email",
            "SO-BAD-EMAIL", "10.00 USD",
            "https://app.example.test/doc/sales-order/order-invalid-email", "tenant-1"));
      }
      if ("order-invalid-link".equals(recordId)) {
        return Optional.of(new EmailDocumentRecord("Empresa SRL", "orders@example.com",
            "SO-BAD-LINK", "10.00 USD", null, "tenant-1"));
      }
      if ("order-generated-link".equals(recordId)) {
        return Optional.of(new EmailDocumentRecord("Empresa SRL", "orders@example.com",
            "SO-GENERATED-LINK", "10.00 USD", null, "tenant-1"));
      }
      if (expectedId.equals(recordId)) {
        return Optional.of(new EmailDocumentRecord("Empresa SRL", recipientEmail, documentNo,
            amount, downloadLink, "tenant-1"));
      }
      return Optional.empty();
    }
  }

  private static final class FakeProviderAdapter implements EmailProviderAdapter {
    private EmailProviderRequest lastRequest;
    private int sendCount;

    @Override
    public boolean isConfigured() {
      return true;
    }

    @Override
    public EmailProviderResponse send(EmailProviderRequest request) throws IOException {
      lastRequest = request;
      sendCount++;
      return new EmailProviderResponse(202, "{}");
    }

    EmailProviderRequest getLastRequest() {
      return lastRequest;
    }

    int getSendCount() {
      return sendCount;
    }
  }
}
