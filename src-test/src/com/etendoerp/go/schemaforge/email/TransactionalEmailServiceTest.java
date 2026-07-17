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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    TransactionalEmailService service = service(new FixtureContract(), adapter);

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
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");

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
    TransactionalEmailService service = service(new FixtureContract(), adapter);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");

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
    TransactionalEmailService service = service(new UnauthorizedContract(), adapter);

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
    TransactionalEmailService service = service(new FixtureContract(), adapter);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECIPIENT, "external@example.com");

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
    TransactionalEmailService service = service(new MissingRecipientContract(), adapter);

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
    TransactionalEmailService service = service(new RejectedRecipientContract(), adapter);

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
    TransactionalEmailService service = service(new SupportRecipientContract(), adapter);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECIPIENT, "customer@example.com");

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
    TransactionalEmailService service = service(new RecipientMismatchContract(), adapter);

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
    TransactionalEmailService service = service(new FixtureContract(), adapter);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");

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
  public void safetyStoreIgnoresNullContextWhenCheckingDuplicateKeys() {
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();

    Optional<EmailAuditRecord> result = safetyStore.findSentByIdempotencyKey(null,
        "duplicate-key");

    assertFalse(result.isPresent());
  }

  @Test
  public void recordsRedactedObservabilityForSuccessfulSend() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{\"id\":\"provider-id\"}"));
    RecordingObservabilitySink observabilitySink = new RecordingObservabilitySink();
    TransactionalEmailService service = service(new FixtureContract(), adapter,
        new InMemoryEmailSafetyStore(), observabilitySink);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
    command.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    command.put(EmailContractCommandSupport.FIELD_USER_ID, "user-1");
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");

    NeoResponse response = service.send("fixture-contract", command);

    EmailObservabilityEvent event = observabilitySink.single();
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, event.getStatus());
    assertEquals("fixture-contract", event.getContractName());
    assertEquals("v1", event.getVersion());
    assertEquals("tenant-1", event.getTenantId());
    assertEquals("user-1", event.getUserId());
    assertEquals("ABC123", event.getRecordId());
    assertEquals("fixture-template", event.getTemplate());
    assertEquals("example.com", event.getRecipientDomain());
    assertNotNull("Recipient hash should not be null", event.getRecipientHash());
    assertFalse(event.getRecipientHash().contains("server-resolved"));
    assertNotNull("Provider status should not be null", event.getProviderStatus());
    assertEquals(202, event.getProviderStatus().intValue());
    assertTrue(event.getProviderDurationMillis() >= 0);
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_SEND_TOTAL));
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_PROVIDER_DURATION_SECONDS));
  }

  @Test
  public void mapsProviderRejectionToProviderFailed() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(500, "{\"error\":\"bad\"}"));
    TransactionalEmailService service = service(new FixtureContract(), adapter);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");

    NeoResponse response = service.send("fixture-contract", command);

    JSONObject data = responseData(response);
    assertEquals(502, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_PROVIDER_FAILED, data.getString("status"));
    assertEquals("Transactional email provider rejected the request", data.getString("message"));
  }

  @Test
  public void mapsNullProviderResponseToProviderFailed() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true, null);
    TransactionalEmailService service = service(new FixtureContract(), adapter);

    NeoResponse response = service.send("fixture-contract", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(502, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_PROVIDER_FAILED, data.getString("status"));
    assertEquals("Transactional email provider is unavailable", data.getString("message"));
  }

  @Test
  public void recordsProviderFailureObservability() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(500, "{\"error\":\"bad\"}"));
    RecordingObservabilitySink observabilitySink = new RecordingObservabilitySink();
    TransactionalEmailService service = service(new FixtureContract(), adapter,
        new InMemoryEmailSafetyStore(), observabilitySink);

    NeoResponse response = service.send("fixture-contract", new JSONObject());

    EmailObservabilityEvent event = observabilitySink.single();
    assertEquals(502, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_PROVIDER_FAILED, event.getStatus());
    assertNotNull("Provider status should not be null", event.getProviderStatus());
    assertEquals(500, event.getProviderStatus().intValue());
    assertEquals("ProviderRejected", event.getErrorClass());
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_PROVIDER_ERROR_TOTAL));
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_PROVIDER_DURATION_SECONDS));
  }

  @Test
  public void returnsDuplicateForRepeatedIdempotencyKey() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{\"id\":\"provider-id\"}"));
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = service(new FixtureContract(), adapter, safetyStore);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");
    command.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "fixture:ABC123:v1");

    NeoResponse firstResponse = service.send("fixture-contract", command);
    NeoResponse duplicateResponse = service.send("fixture-contract", command);

    JSONObject duplicateData = responseData(duplicateResponse);
    assertEquals(200, firstResponse.getHttpStatus());
    assertEquals(200, duplicateResponse.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_DUPLICATE,
        duplicateData.getString("status"));
    assertTrue(duplicateData.getBoolean("duplicate"));
    assertEquals(202, duplicateData.getInt("providerStatus"));
    assertEquals(1, adapter.getSendCount());
    assertEquals(2, safetyStore.getAuditRecords().size());
    assertEquals(TransactionalEmailService.STATUS_SENT,
        safetyStore.getAuditRecords().get(0).getStatus());
    assertEquals(TransactionalEmailService.STATUS_DUPLICATE,
        safetyStore.getAuditRecords().get(1).getStatus());
  }

  @Test
  public void suppressesConcurrentDuplicateBeforeSecondProviderSend() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{\"id\":\"provider-id\"}")).withDelayMillis(150);
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = service(new FixtureContract(), adapter, safetyStore);
    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");
    command.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "fixture:ABC123:v1");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<NeoResponse> first = executor.submit(() -> sendAfterStart(service, command, ready,
          start));
      Future<NeoResponse> second = executor.submit(() -> sendAfterStart(service, command, ready,
          start));
      assertTrue("Both workers should be ready", ready.await(1, TimeUnit.SECONDS));
      start.countDown();

      NeoResponse firstResponse = first.get(2, TimeUnit.SECONDS);
      NeoResponse secondResponse = second.get(2, TimeUnit.SECONDS);

      List<String> statuses = Arrays.asList(responseData(firstResponse).getString("status"),
          responseData(secondResponse).getString("status"));
      assertTrue(statuses.contains(TransactionalEmailService.STATUS_SENT));
      assertTrue(statuses.contains(TransactionalEmailService.STATUS_DUPLICATE));
      assertEquals(1, adapter.getSendCount());
      assertEquals(2, safetyStore.getAuditRecords().size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void recordsDuplicateObservability() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{\"id\":\"provider-id\"}"));
    RecordingObservabilitySink observabilitySink = new RecordingObservabilitySink();
    TransactionalEmailService service = service(new FixtureContract(), adapter,
        new InMemoryEmailSafetyStore(), observabilitySink);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "fixture:ABC123:v1");

    service.send("fixture-contract", command);
    service.send("fixture-contract", command);

    assertEquals(2, observabilitySink.getEvents().size());
    EmailObservabilityEvent event = observabilitySink.getEvents().get(1);
    assertEquals(TransactionalEmailService.STATUS_DUPLICATE, event.getStatus());
    assertTrue(event.isDuplicate());
    assertNotNull("Provider status should not be null", event.getProviderStatus());
    assertEquals(202, event.getProviderStatus().intValue());
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_DUPLICATE_TOTAL));
  }

  @Test
  public void scopesIdempotencyByContract() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = new TransactionalEmailService(
        new MultiContractRegistry(new FixtureContract(), new AlternateFixtureContract()),
        adapter, safetyStore);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    command.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "record-1:v1");

    NeoResponse firstResponse = service.send("fixture-contract", command);
    NeoResponse secondResponse = service.send("alternate-fixture", command);

    assertEquals(200, firstResponse.getHttpStatus());
    assertEquals(200, secondResponse.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT,
        responseData(firstResponse).getString("status"));
    assertEquals(TransactionalEmailService.STATUS_SENT,
        responseData(secondResponse).getString("status"));
    assertEquals(2, adapter.getSendCount());
  }

  @Test
  public void throttlesByRecipientLimit() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = service(new RecipientThrottleContract(), adapter,
        safetyStore);

    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "ABC123");

    NeoResponse firstResponse = service.send("recipient-throttle", command);
    NeoResponse throttledResponse = service.send("recipient-throttle", command);

    JSONObject throttledData = responseData(throttledResponse);
    assertEquals(200, firstResponse.getHttpStatus());
    assertEquals(TransactionalEmailService.HTTP_TOO_MANY_REQUESTS,
        throttledResponse.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_THROTTLED,
        throttledData.getString("status"));
    assertEquals(EmailThrottleRule.SCOPE_RECIPIENT,
        throttledData.getString("throttleScope"));
    assertTrue(throttledData.getInt("retryAfterSeconds") > 0);
    assertEquals(1, adapter.getSendCount());
    assertEquals(TransactionalEmailService.STATUS_THROTTLED,
        safetyStore.getAuditRecords().get(1).getStatus());
  }

  @Test
  public void recordsThrottleObservability() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    RecordingObservabilitySink observabilitySink = new RecordingObservabilitySink();
    TransactionalEmailService service = service(new RecipientThrottleContract(), adapter,
        new InMemoryEmailSafetyStore(), observabilitySink);

    service.send("recipient-throttle", new JSONObject());
    service.send("recipient-throttle", new JSONObject());

    assertEquals(2, observabilitySink.getEvents().size());
    EmailObservabilityEvent event = observabilitySink.getEvents().get(1);
    assertEquals(TransactionalEmailService.STATUS_THROTTLED, event.getStatus());
    assertEquals(EmailThrottleRule.SCOPE_RECIPIENT, event.getThrottleScope());
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_THROTTLE_TOTAL));
  }

  @Test
  public void suppressesSendWhenTemplateKillSwitchIsActive() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    safetyStore.disableTemplate("fixture-template");
    TransactionalEmailService service = service(new FixtureContract(), adapter, safetyStore);

    NeoResponse response = service.send("fixture-contract", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SUPPRESSED, data.getString("status"));
    assertEquals(EmailThrottleRule.SCOPE_TEMPLATE, data.getString("killSwitchScope"));
    assertFalse(adapter.wasSendCalled());
    assertEquals(1, safetyStore.getAuditRecords().size());
    assertEquals(TransactionalEmailService.STATUS_SUPPRESSED,
        safetyStore.getAuditRecords().get(0).getStatus());
  }

  @Test
  public void recordsKillSwitchObservability() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    safetyStore.disableTemplate("fixture-template");
    RecordingObservabilitySink observabilitySink = new RecordingObservabilitySink();
    TransactionalEmailService service = service(new FixtureContract(), adapter, safetyStore,
        observabilitySink);

    service.send("fixture-contract", new JSONObject());

    EmailObservabilityEvent event = observabilitySink.single();
    assertEquals(TransactionalEmailService.STATUS_SUPPRESSED, event.getStatus());
    assertEquals(EmailThrottleRule.SCOPE_TEMPLATE, event.getKillSwitchScope());
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_SUPPRESSION_TOTAL));
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_KILL_SWITCH_TOTAL));
  }

  @Test
  public void recordsValidationFailureObservabilityWithoutProviderSecrets() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    RecordingObservabilitySink observabilitySink = new RecordingObservabilitySink();
    TransactionalEmailService service = service(new FixtureContract(), adapter,
        new InMemoryEmailSafetyStore(), observabilitySink);

    JSONObject command = new JSONObject();
    command.put("from", "attacker@example.com");
    command.put("apiKey", "must-not-appear");

    NeoResponse response = service.send("fixture-contract", command);

    EmailObservabilityEvent event = observabilitySink.single();
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, event.getStatus());
    assertEquals("fixture-contract", event.getContractName());
    assertFalse(adapter.wasSendCalled());
    assertTrue(event.hasMetric(EmailObservabilityEvent.METRIC_SEND_TOTAL));
    assertFalse(String.valueOf(event.getMessage()).contains("must-not-appear"));
  }

  @Test
  public void resolvesThrottleKeysForSupportedScopes() throws Exception {
    JSONObject commandBody = new JSONObject();
    commandBody.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    commandBody.put(EmailContractCommandSupport.FIELD_USER_ID, "user-1");
    commandBody.put(EmailContractCommandSupport.FIELD_RECORD_ID, "record-1");
    EmailContractCommand command = new EmailContractCommand("fixture-contract", commandBody);
    EmailRecipientResolution recipient = EmailRecipientResolution.serverResolved(
        "person@example.com");
    EmailProviderRequest providerRequest = new EmailProviderRequest("person@example.com",
        "fixture-template", new JSONObject(), null);
    EmailSendContext context = new EmailSendContext(command, recipient, providerRequest);

    assertEquals("global", EmailThrottleRule.global(1, 60).resolveKey(context));
    assertEquals("tenant-1", EmailThrottleRule.perTenant(1, 60).resolveKey(context));
    assertEquals("user-1", EmailThrottleRule.perUser(1, 60).resolveKey(context));
    assertEquals("fixture-template", EmailThrottleRule.perTemplate(1, 60).resolveKey(context));
    assertEquals("person@example.com", EmailThrottleRule.perRecipient(1, 60)
        .resolveKey(context));
    assertEquals("example.com", EmailThrottleRule.perDomain(1, 60).resolveKey(context));
    assertEquals("record-1", EmailThrottleRule.perRecord(1, 60).resolveKey(context));
  }

  @Test
  public void returnsNoRecipientWhenContractResolvesNoRecipient() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = service(new NoRecipientContract(), adapter);

    NeoResponse response = service.send("no-recipient", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(TransactionalEmailService.HTTP_UNPROCESSABLE_ENTITY, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_NO_RECIPIENT, data.getString("status"));
    assertEquals("Final recipient list is empty", data.getString("message"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void rejectsMultiRecipientSendWhenAdapterLacksCapability() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}"));
    TransactionalEmailService service = service(new MultiRecipientContract(), adapter);

    NeoResponse response = service.send("multi-recipient", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertTrue(data.getString("message").contains("multiple recipients"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void sendsMultiRecipientSetWhenAdapterIsCapable() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}")).withMultiRecipientCapabilities();
    TransactionalEmailService service = service(new MultiRecipientContract(), adapter);

    NeoResponse response = service.send("multi-recipient", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, data.getString("status"));
    EmailRecipientSet sent = adapter.getLastRequest().getRecipients();
    assertEquals(Arrays.asList("ap@example.com", "billing@example.com"), sent.getTo());
    assertEquals(java.util.Collections.singletonList("pm@example.com"), sent.getCc());
  }

  @Test
  public void suppressesSendWhenCcAddressIsSuppressed() throws Exception {
    FakeProviderAdapter adapter = new FakeProviderAdapter(true,
        new EmailProviderResponse(202, "{}")).withMultiRecipientCapabilities();
    InMemoryEmailSafetyStore safetyStore = new InMemoryEmailSafetyStore();
    safetyStore.suppressAddress("pm@example.com");
    TransactionalEmailService service = service(new MultiRecipientContract(), adapter, safetyStore);

    NeoResponse response = service.send("multi-recipient", new JSONObject());

    JSONObject data = responseData(response);
    assertEquals(403, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SUPPRESSED, data.getString("status"));
    assertFalse(adapter.wasSendCalled());
    assertEquals(TransactionalEmailService.STATUS_SUPPRESSED,
        safetyStore.getAuditRecords().get(0).getStatus());
  }

  private static JSONObject responseData(NeoResponse response) throws JSONException {
    assertNotNull("Response body should not be null", response.getBody());
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private static TransactionalEmailService service(EmailContract contract,
      FakeProviderAdapter adapter) {
    return new TransactionalEmailService(new SingleContractRegistry(contract), adapter);
  }

  private static TransactionalEmailService service(EmailContract contract,
      FakeProviderAdapter adapter, InMemoryEmailSafetyStore safetyStore) {
    return new TransactionalEmailService(new SingleContractRegistry(contract), adapter,
        safetyStore);
  }

  private static TransactionalEmailService service(EmailContract contract,
      FakeProviderAdapter adapter, InMemoryEmailSafetyStore safetyStore,
      EmailObservabilitySink observabilitySink) {
    return new TransactionalEmailService(new SingleContractRegistry(contract), adapter,
        safetyStore, observabilitySink);
  }

  private static NeoResponse sendAfterStart(TransactionalEmailService service, JSONObject command,
      CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    start.await(1, TimeUnit.SECONDS);
    return service.send("fixture-contract", new JSONObject(command.toString()));
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

  private static class MultiContractRegistry implements EmailContractRegistry {
    private final List<EmailContract> contracts;

    MultiContractRegistry(EmailContract... contracts) {
      this.contracts = Arrays.asList(contracts);
    }

    @Override
    public Optional<EmailContract> find(String contractName) {
      for (EmailContract contract : contracts) {
        if (contract.getName().equals(contractName)) {
          return Optional.of(contract);
        }
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

  private static class AlternateFixtureContract extends FixtureContract {
    @Override
    public String getName() {
      return "alternate-fixture";
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
      return null;
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
      return EmailRecipientResolution.callerProvided(
          EmailContractCommandSupport.text(command, EmailContractCommandSupport.FIELD_RECIPIENT));
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

  private static class RecipientThrottleContract extends FixtureContract {
    @Override
    public String getName() {
      return "recipient-throttle";
    }

    @Override
    public EmailDeliveryPolicy deliveryPolicy(EmailContractCommand command,
        EmailRecipientResolution recipient, EmailProviderRequest providerRequest) {
      return EmailDeliveryPolicy.of(null, Arrays.asList(EmailThrottleRule.perRecipient(1, 60)));
    }
  }

  private static class NoRecipientContract implements EmailContract {
    @Override
    public String getName() {
      return "no-recipient";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.noRecipient("Final recipient list is empty");
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipient(),
          "fixture-template", new JSONObject(), null));
    }
  }

  private static class MultiRecipientContract implements EmailContract {
    @Override
    public String getName() {
      return "multi-recipient";
    }

    @Override
    public EmailAuthorizationResult authorize(EmailContractCommand command) {
      return EmailAuthorizationResult.allowed();
    }

    @Override
    public EmailRecipientResolution resolveRecipient(EmailContractCommand command) {
      return EmailRecipientResolution.serverResolved(EmailRecipientSet.of(
          Arrays.asList("ap@example.com", "billing@example.com"),
          Arrays.asList("pm@example.com")));
    }

    @Override
    public EmailContractResolution resolve(EmailContractCommand command,
        EmailRecipientResolution recipient) {
      return EmailContractResolution.ready(new EmailProviderRequest(recipient.getRecipientSet(),
          "fixture-template", new JSONObject(), null));
    }
  }

  private static class FakeProviderAdapter implements EmailProviderAdapter {
    private final boolean configured;
    private final EmailProviderResponse response;
    private boolean sendCalled;
    private int sendCount;
    private EmailProviderRequest lastRequest;
    private boolean supportsMultipleRecipients;
    private boolean supportsCcChannel;
    private long delayMillis;

    FakeProviderAdapter(boolean configured, EmailProviderResponse response) {
      this.configured = configured;
      this.response = response;
    }

    FakeProviderAdapter withMultiRecipientCapabilities() {
      this.supportsMultipleRecipients = true;
      this.supportsCcChannel = true;
      return this;
    }

    FakeProviderAdapter withDelayMillis(long delayMillis) {
      this.delayMillis = delayMillis;
      return this;
    }

    @Override
    public boolean isConfigured() {
      return configured;
    }

    @Override
    public boolean supportsMultipleRecipients() {
      return supportsMultipleRecipients;
    }

    @Override
    public boolean supportsCcChannel() {
      return supportsCcChannel;
    }

    @Override
    public synchronized EmailProviderResponse send(EmailProviderRequest request)
        throws IOException {
      sendCalled = true;
      sendCount++;
      lastRequest = request;
      if (delayMillis > 0L) {
        try {
          new CountDownLatch(1).await(delayMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while sending fake email", e);
        }
      }
      return response;
    }

    synchronized boolean wasSendCalled() {
      return sendCalled;
    }

    synchronized EmailProviderRequest getLastRequest() {
      return lastRequest;
    }

    synchronized int getSendCount() {
      return sendCount;
    }
  }

  private static class RecordingObservabilitySink implements EmailObservabilitySink {
    private final List<EmailObservabilityEvent> events = new ArrayList<>();

    @Override
    public void emit(EmailObservabilityEvent event) {
      events.add(event);
    }

    List<EmailObservabilityEvent> getEvents() {
      return events;
    }

    EmailObservabilityEvent single() {
      assertEquals(1, events.size());
      return events.get(0);
    }
  }
}
