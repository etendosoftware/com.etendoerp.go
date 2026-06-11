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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.email.contracts.CoreEmailContractProvider;
import com.etendoerp.go.schemaforge.email.contracts.SalesInvoiceSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesOrderSendEmailContract;
import com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract;

/**
 * Tests the built-in transactional email contracts.
 */
public class InitialEmailContractsTest {

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
    assertEquals("reset-password", adapter.getLastRequest().getTemplate());
    assertEquals("Lucas", adapter.getLastRequest().getData().getString("name"));
    assertEquals("https://app.example.test/reset?token=abc123",
        adapter.getLastRequest().getData().getString("link"));
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
    assertEquals("Welcome to Etendo Go",
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
    assertEquals("Your Etendo Go environment is ready",
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
        .contains("Abre este enlace para acceder a tu panel"));
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
    assertEquals("Your Etendo Go password was changed",
        adapter.getLastRequest().getData().getString("subject"));
    assertTrue(adapter.getLastRequest().getData().getString("body")
        .contains("contact support"));
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
    assertEquals("login-alert", adapter.getLastRequest().getTemplate());
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
    assertEquals("invoice", adapter.getLastRequest().getTemplate());
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
  public void documentContractRejectsInvalidResolvedRecipient() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-invalid-email");

    NeoResponse response = service.send("sales-order-send", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email recipient is invalid", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
  }

  @Test
  public void documentContractRejectsMissingDownloadLink() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-invalid-link");

    NeoResponse response = service.send("sales-order-send", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Document download link is not configured", data.getString("message"));
    assertEquals(0, adapter.getSendCount());
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
