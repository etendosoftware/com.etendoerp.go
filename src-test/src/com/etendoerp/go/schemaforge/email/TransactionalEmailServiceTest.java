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
 * Unit tests for {@link TransactionalEmailService}.
 */
public class TransactionalEmailServiceTest {

  @Test
  public void rejectsProviderPassthroughPayload() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new FixtureContract()), adapter);

    JSONObject command = new JSONObject();
    command.put("to", "user@example.com");
    command.put("template", "reset-password");
    command.put("data", new JSONObject());

    NeoResponse response = service.send("fixture-contract", command);

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertTrue(data.getString("message").contains("provider field"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void returnsValidationFailureForUnknownContract() throws Exception {
    TransactionalEmailService service = new TransactionalEmailService(EmailContractRegistry.empty(),
        new FakeProviderAdapter(true, new EmailProviderResponse(202, "{}")));

    JSONObject command = new JSONObject();
    command.put("recordId", "ABC123");

    NeoResponse response = service.send("missing-contract", command);

    JSONObject data = responseData(response);
    assertEquals(404, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("missing-contract", data.getString("contract"));
    assertEquals("Unknown email contract", data.getString("message"));
  }

  @Test
  public void providerMustBeConfiguredBeforeSend() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(false,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new FixtureContract()), adapter);

    JSONObject command = new JSONObject();
    command.put("recordId", "ABC123");

    NeoResponse response = service.send("fixture-contract", command);

    JSONObject data = responseData(response);
    assertEquals(503, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_PROVIDER_FAILED, data.getString("status"));
    assertEquals("Transactional email provider is not configured", data.getString("message"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void rejectsUnauthorizedContractCommand() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new UnauthorizedContract()), adapter);

    NeoResponse response = service.send("unauthorized-contract", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_UNAUTHORIZED, data.getString("status"));
    assertEquals("User cannot send this contract for the requested record",
        data.getString("message"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void rejectsCallerRecipientForRegularContracts() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new FixtureContract()), adapter);

    JSONObject command = new JSONObject();
    command.put("recipient", "external@example.com");

    NeoResponse response = service.send("fixture-contract", command);

    JSONObject data = responseData(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_UNAUTHORIZED, data.getString("status"));
    assertTrue(data.getString("message").contains("caller-provided recipient"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void rejectsContractThatDoesNotResolveRecipient() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new MissingRecipientContract()), adapter);

    NeoResponse response = service.send("missing-recipient", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email contract did not resolve a recipient", data.getString("message"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void returnsExplicitRecipientResolutionRejection() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new RejectedRecipientContract()), adapter);

    NeoResponse response = service.send("rejected-recipient", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(404, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email recipient record was not found", data.getString("message"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void allowsCallerRecipientForExplicitSupportContract() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new SupportRecipientContract()), adapter);

    JSONObject command = new JSONObject();
    command.put("recipient", "customer@example.com");

    NeoResponse response = service.send("support-recipient", command);

    JSONObject data = responseData(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, data.getString("status"));
    assertEquals("customer@example.com", adapter.getLastRequest().getRecipient());
  }

  @Test
  public void rejectsProviderRequestWithDifferentRecipient() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new RecipientMismatchContract()), adapter);

    NeoResponse response = service.send("recipient-mismatch", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertEquals("Email contract provider request recipient must match recipient resolution",
        data.getString("message"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void sendsProviderRequestResolvedByContract() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{\"id\":\"provider-id\"}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new FixtureContract()), adapter);

    JSONObject command = new JSONObject();
    command.put("recordId", "ABC123");

    NeoResponse response = service.send("fixture-contract", command);

    JSONObject data = responseData(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, data.getString("status"));
    assertEquals("fixture-contract", data.getString("contract"));
    assertEquals(202, data.getInt("providerStatus"));
    assertEquals("server-resolved@example.com", adapter.getLastRequest().getRecipient());
    assertEquals("fixture-template", adapter.getLastRequest().getTemplate());
    assertFalse(data.has("to"));
  }

  @Test
  public void mapsProviderRejectionToProviderFailed() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(500, "{\"error\":\"bad\"}"));
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(new FixtureContract()), adapter);

    JSONObject command = new JSONObject();
    command.put("recordId", "ABC123");

    NeoResponse response = service.send("fixture-contract", command);

    JSONObject data = responseData(response);
    assertEquals(502, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_PROVIDER_FAILED, data.getString("status"));
    assertEquals("Transactional email provider rejected the request", data.getString("message"));
  }

  private static JSONObject responseData(NeoResponse response) throws JSONException {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private static class SingleContractRegistry implements EmailContractRegistry {
    private final EmailContract contract;

    SingleContractRegistry(EmailContract contract) {
      this.contract = contract;
    }

    @Override
    public Optional<EmailContract> find(String contractName) {
      if (contract.getName().equals(contractName)) {
        return Optional.of(contract);
      }
      return Optional.empty();
    }
  }

  private static class FixtureContract implements EmailContract {
    @Override
    public String getName() {
      return "fixture-contract";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.serverResolved("server-resolved@example.com");
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      try {
        JSONObject data = new JSONObject();
        data.put("name", "Server Resolved");
        data.put("link", "https://app.example.test/reset?token=server-generated");
        return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
            "fixture-template", data, null));
      } catch (JSONException e) {
        throw new IllegalStateException("Could not build fixture email data", e);
      }
    }
  }

  private static class MissingRecipientContract implements EmailContract {
    @Override
    public String getName() {
      return "missing-recipient";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.serverResolved(null);
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          "fixture-template", new JSONObject(), null));
    }
  }

  private static class UnauthorizedContract implements EmailContract {
    @Override
    public String getName() {
      return "unauthorized-contract";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.rejected(403,
          "User cannot send this contract for the requested record");
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.serverResolved("server-resolved@example.com");
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          "fixture-template", new JSONObject(), null));
    }
  }

  private static class RejectedRecipientContract implements EmailContract {
    @Override
    public String getName() {
      return "rejected-recipient";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.rejected(404, "Email recipient record was not found");
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          "fixture-template", new JSONObject(), null));
    }
  }

  private static class SupportRecipientContract implements EmailContract {
    @Override
    public String getName() {
      return "support-recipient";
    }

    @Override
    public boolean allowsCallerProvidedRecipients() {
      return true;
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.callerProvided(command.getBody().optString("recipient"));
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          "support-template", new JSONObject(), null));
    }
  }

  private static class RecipientMismatchContract implements EmailContract {
    @Override
    public String getName() {
      return "recipient-mismatch";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.serverResolved("server-resolved@example.com");
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest("other@example.com",
          "fixture-template",
          new JSONObject(), null));
    }
  }

  private static class FakeProviderAdapter implements EmailProviderAdapter {
    private final boolean configured;
    private final EmailProviderResponse response;
    private boolean sendCalled;
    private EmailProviderRequest lastRequest;

    FakeProviderAdapter(boolean configured, EmailProviderResponse response) {
      this.configured = configured;
      this.response = response;
    }

    @Override
    public boolean isConfigured() {
      return configured;
    }

    @Override
    public EmailProviderResponse send(EmailProviderRequest request) throws IOException {
      sendCalled = true;
      lastRequest = request;
      return response;
    }

    boolean wasSendCalled() {
      return sendCalled;
    }

    EmailProviderRequest getLastRequest() {
      return lastRequest;
    }
  }
}
