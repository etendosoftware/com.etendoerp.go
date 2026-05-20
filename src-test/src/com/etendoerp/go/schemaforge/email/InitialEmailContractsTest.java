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

import java.io.IOException;
import java.util.Optional;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Tests the built-in transactional email contracts.
 */
public class InitialEmailContractsTest {

  @Test
  public void registersInitialContractsButNotCustomPayloads() {
    DefaultEmailContractRegistry registry = DefaultEmailContractRegistry.create(
        new FixtureDataResolver());

    assertTrue(registry.find("reset-password").isPresent());
    assertTrue(registry.find("new-account").isPresent());
    assertTrue(registry.find("login-alert").isPresent());
    assertTrue(registry.find("sales-invoice-send").isPresent());
    assertFalse(registry.find("custom").isPresent());
    assertFalse(registry.find("support-custom-email").isPresent());
  }

  @Test
  public void resetPasswordUsesAccountRecipientAndTemplateVariables() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put("accountId", "account-1");
    command.put("link", "https://app.example.test/reset?token=abc123");

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
    command.put("accountId", "account-1");
    command.put("link", "https://app.example.test/welcome");

    NeoResponse response = service.send("new-account", command);

    assertSent(response);
    assertEquals("new-account", adapter.getLastRequest().getTemplate());
    assertEquals("account@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Lucas", adapter.getLastRequest().getData().getString("name"));
    assertEquals("https://app.example.test/welcome",
        adapter.getLastRequest().getData().getString("link"));
  }

  @Test
  public void loginAlertUsesUserRecipientAndEventMetadata() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put("userId", "user-1");
    command.put("loginEventId", "login-1");
    command.put("ip", "190.123.45.67");
    command.put("date", "2026-04-13 10:32");

    NeoResponse response = service.send("login-alert", command);

    assertSent(response);
    assertEquals("login-alert", adapter.getLastRequest().getTemplate());
    assertEquals("user@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Ana", adapter.getLastRequest().getData().getString("name"));
    assertEquals("190.123.45.67", adapter.getLastRequest().getData().getString("ip"));
    assertEquals("2026-04-13 10:32", adapter.getLastRequest().getData().getString("date"));
  }

  @Test
  public void salesInvoiceSendUsesDocumentRecipientAndInvoiceVariables() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put("recordId", "invoice-1");

    NeoResponse response = service.send("sales-invoice-send", command);

    assertSent(response);
    assertEquals("invoice", adapter.getLastRequest().getTemplate());
    assertEquals("billing@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("Empresa SRL", adapter.getLastRequest().getData().getString("name"));
    assertEquals("0001-00042",
        adapter.getLastRequest().getData().getString("invoice_number"));
    assertEquals("1500.00 USD", adapter.getLastRequest().getData().getString("amount"));
    assertEquals("https://app.example.test/doc/sales-invoice/invoice-1",
        adapter.getLastRequest().getData().getString("download_link"));
  }

  @Test
  public void rejectsUnsupportedVersionAsValidationFailure() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter();
    TransactionalEmailService service = service(adapter);

    JSONObject command = baseCommand();
    command.put("version", "v2");
    command.put("accountId", "account-1");
    command.put("link", "https://app.example.test/reset?token=abc123");

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
    command.put("accountId", "account-1");
    command.put("link", "javascript:alert(1)");

    NeoResponse response = service.send("reset-password", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email contract link must be an absolute HTTP URL", data.getString("message"));
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

  private static TransactionalEmailService service(FakeProviderAdapter adapter) {
    return new TransactionalEmailService(
        DefaultEmailContractRegistry.create(new FixtureDataResolver()), adapter,
        new InMemoryEmailSafetyStore());
  }

  private static JSONObject baseCommand() throws JSONException {
    JSONObject command = new JSONObject();
    command.put("version", "v1");
    command.put("tenantId", "tenant-1");
    return command;
  }

  private static void assertSent(NeoResponse response) throws JSONException {
    JSONObject data = responseData(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, data.getString("status"));
  }

  private static JSONObject responseData(NeoResponse response) throws JSONException {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private static final class FixtureDataResolver implements EmailContractDataResolver {
    @Override
    public Optional<EmailContactRecord> findAccountContact(String accountId) {
      if ("account-1".equals(accountId)) {
        return Optional.of(new EmailContactRecord("Lucas", "account@example.com"));
      }
      return Optional.empty();
    }

    @Override
    public Optional<EmailContactRecord> findUserContact(String userId) {
      if ("user-1".equals(userId)) {
        return Optional.of(new EmailContactRecord("Ana", "user@example.com"));
      }
      return Optional.empty();
    }

    @Override
    public Optional<EmailDocumentRecord> findSalesInvoice(String invoiceId) {
      if ("invoice-1".equals(invoiceId)) {
        return Optional.of(new EmailDocumentRecord("Empresa SRL", "billing@example.com",
            "0001-00042", "1500.00 USD",
            "https://app.example.test/doc/sales-invoice/invoice-1"));
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
