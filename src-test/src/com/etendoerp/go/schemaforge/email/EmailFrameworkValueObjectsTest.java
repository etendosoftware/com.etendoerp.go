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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

/**
 * Unit tests for transactional email framework value objects.
 */
public class EmailFrameworkValueObjectsTest {

  @Test
  public void documentRecordNormalizesBlankFields() {
    EmailDocumentRecord document = new EmailDocumentRecord(" Customer ", " customer@example.com ",
        " record-1 ", " SO-1 ", " 10.00 USD ", " https://app.example.test/doc ",
        " tenant-1 ");
    EmailDocumentRecord blankRecord = new EmailDocumentRecord(" ", " ", " ", " ", " ", " ",
        " ");

    assertEquals("Customer", document.getRecipientName());
    assertEquals("customer@example.com", document.getRecipientEmail());
    assertEquals("record-1", document.getRecordId());
    assertEquals("SO-1", document.getDocumentNumber());
    assertEquals("10.00 USD", document.getAmount());
    assertEquals("https://app.example.test/doc", document.getDownloadLink());
    assertEquals("tenant-1", document.getClientId());
    assertNull(blankRecord.getRecipientName());
    assertNull(blankRecord.getRecipientEmail());
    assertNull(blankRecord.getRecordId());
    assertNull(blankRecord.getDocumentNumber());
    assertNull(blankRecord.getAmount());
    assertNull(blankRecord.getDownloadLink());
    assertNull(blankRecord.getClientId());
  }

  @Test
  public void providerRequestBuildsDefensivePayloadWithReplyTo() throws Exception {
    JSONObject data = new JSONObject();
    data.put("name", "Lucas");
    EmailProviderRequest request = new EmailProviderRequest(" user@example.com ",
        " reset-password ", data, " support@example.com ");

    JSONObject payload = request.toProviderPayload();
    payload.getJSONObject("data").put("name", "Changed");

    assertEquals("user@example.com", request.getRecipient());
    assertEquals("reset-password", request.getTemplate());
    assertEquals("support@example.com", request.getReplyTo());
    assertEquals("user@example.com", payload.getString("to"));
    assertEquals("reset-password", payload.getString("template"));
    assertEquals("support@example.com", payload.getString("replyTo"));
    assertEquals("Lucas", request.getData().getString("name"));
    assertNotSame(request.getData(), payload.getJSONObject("data"));
  }

  @Test
  public void providerRequestDefaultsMissingDataAndRejectsMissingMandatoryFields()
      throws Exception {
    EmailProviderRequest request = new EmailProviderRequest("user@example.com", "template", null,
        null);

    JSONObject payload = request.toProviderPayload();

    assertEquals(0, payload.getJSONObject("data").length());
    assertFalse(payload.has("replyTo"));
    assertNullPointerException(() -> new EmailProviderRequest(" ", "template", null, null));
    assertNullPointerException(() -> new EmailProviderRequest("user@example.com", " ", null, null));
  }

  @Test
  public void providerResponseClassifiesHttpStatusCodes() {
    assertTrue(new EmailProviderResponse(200, null).isSuccessful());
    assertTrue(new EmailProviderResponse(299, "{}").isSuccessful());
    assertFalse(new EmailProviderResponse(199, "{}").isSuccessful());
    assertFalse(new EmailProviderResponse(300, "{}").isSuccessful());
    assertEquals("", new EmailProviderResponse(202, null).getBody());
  }

  @Test
  public void recipientResolutionNormalizesSourcesAndRejectsBlankRecipients() {
    EmailRecipientResolution server = EmailRecipientResolution.serverResolved(
        " server@example.com ");
    EmailRecipientResolution caller = EmailRecipientResolution.callerProvided(
        " caller@example.com ");
    EmailRecipientResolution rejected = EmailRecipientResolution.rejected(404, "Missing");

    assertTrue(server.isResolved());
    assertEquals("server@example.com", server.getRecipient());
    assertEquals(EmailRecipientResolution.SOURCE_SERVER, server.getSource());
    assertFalse(server.isCallerProvided());
    assertTrue(caller.isCallerProvided());
    assertEquals("caller@example.com", caller.getRecipient());
    assertFalse(rejected.isResolved());
    assertEquals(404, rejected.getHttpStatus());
    assertEquals("Missing", rejected.getMessage());
    assertOBException(() -> EmailRecipientResolution.serverResolved(" "));
    assertOBException(() -> EmailRecipientResolution.callerProvided(null));
  }

  @Test
  public void serverResolvedStringCarriesSingleToRecipientSet() {
    EmailRecipientResolution resolution = EmailRecipientResolution.serverResolved("a@x.com");

    assertEquals(Collections.singletonList("a@x.com"), resolution.getRecipientSet().getTo());
    assertTrue(resolution.getRecipientSet().getCc().isEmpty());
    assertFalse(resolution.isNoRecipient());
  }

  @Test
  public void serverResolvedSetKeepsFirstToAsLegacyRecipient() {
    EmailRecipientSet set = EmailRecipientSet.of(
        Arrays.asList("ap@x.com", "billing@x.com"), Collections.singletonList("pm@x.com"));
    EmailRecipientResolution resolution = EmailRecipientResolution.serverResolved(set);

    assertTrue(resolution.isResolved());
    assertEquals("ap@x.com", resolution.getRecipient());
    assertEquals(set.recipientSetHash(), resolution.getRecipientSet().recipientSetHash());
    assertOBException(() -> EmailRecipientResolution.serverResolved(
        EmailRecipientSet.of(Collections.emptyList(), Collections.singletonList("cc@x.com"))));
  }

  @Test
  public void noRecipientResolutionSignalsDedicatedStatus() {
    EmailRecipientResolution resolution = EmailRecipientResolution.noRecipient("no recipient");

    assertFalse(resolution.isResolved());
    assertTrue(resolution.isNoRecipient());
    assertEquals(422, resolution.getHttpStatus());
    assertEquals("no recipient", resolution.getMessage());
  }

  @Test
  public void providerRequestEmitsArraysForMultipleRecipientsAndCc() throws Exception {
    EmailRecipientSet set = EmailRecipientSet.of(
        Arrays.asList("ap@x.com", "billing@x.com"), Collections.singletonList("pm@x.com"));
    EmailProviderRequest request = new EmailProviderRequest(set, "invoice", new JSONObject(), null);

    JSONObject payload = request.toProviderPayload();
    JSONArray to = payload.getJSONArray("to");
    JSONArray cc = payload.getJSONArray("cc");

    assertEquals("ap@x.com", request.getRecipient());
    assertEquals(2, to.length());
    assertEquals("ap@x.com", to.getString(0));
    assertEquals("billing@x.com", to.getString(1));
    assertEquals(1, cc.length());
    assertEquals("pm@x.com", cc.getString(0));
  }

  @Test
  public void authorizationAndResolutionExposeRejectionMetadata() {
    EmailAuthorizationResult allowed = EmailAuthorizationResult.allowed();
    EmailAuthorizationResult rejectedAuthorization = EmailAuthorizationResult.rejected(403,
        "Forbidden");
    EmailContractResolution rejectedResolution = EmailContractResolution.rejected(422,
        TransactionalEmailService.STATUS_VALIDATION_FAILED, "Bad command");

    assertTrue(allowed.isAllowed());
    assertEquals(200, allowed.getHttpStatus());
    assertNull(allowed.getMessage());
    assertFalse(rejectedAuthorization.isAllowed());
    assertEquals(403, rejectedAuthorization.getHttpStatus());
    assertEquals("Forbidden", rejectedAuthorization.getMessage());
    assertFalse(rejectedResolution.isReady());
    assertEquals(422, rejectedResolution.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED,
        rejectedResolution.getStatus());
    assertEquals("Bad command", rejectedResolution.getMessage());
    assertNull(rejectedResolution.getProviderRequest());
    assertNullPointerException(() -> EmailContractResolution.ready(null));
  }

  @Test
  public void providerConfigNormalizesExplicitValuesAndRuntimeProperties() {
    EmailProviderConfig disabledConfig = new EmailProviderConfig(" ", " ", false, -1);

    assertFalse(disabledConfig.isConfigured());
    assertFalse(disabledConfig.isEnabled());
    assertNull(disabledConfig.getBaseUrl());
    assertNull(disabledConfig.getApiKey());
    assertEquals(EmailProviderConfig.DEFAULT_TIMEOUT_MS, disabledConfig.getTimeoutMs());

    withProviderProperties(" https://provider.example/send ", " secret ", "Y", "2500",
        () -> {
          EmailProviderConfig config = EmailProviderConfig.fromRuntime();

          assertTrue(config.isConfigured());
          assertTrue(config.isEnabled());
          assertEquals("https://provider.example/send", config.getBaseUrl());
          assertEquals("secret", config.getApiKey());
          assertEquals(2500, config.getTimeoutMs());
        });
  }

  @Test
  public void providerConfigFallsBackForDisabledOrInvalidRuntimeValues() {
    withProviderProperties("https://provider.example/send", "secret", "false", "not-a-number",
        () -> {
          EmailProviderConfig config = EmailProviderConfig.fromRuntime();

          assertFalse(config.isConfigured());
          assertFalse(config.isEnabled());
          assertEquals(EmailProviderConfig.DEFAULT_TIMEOUT_MS, config.getTimeoutMs());
        });
  }

  @Test
  public void documentDownloadTokenCreatesSignedExpiringLinks() {
    withDocumentDownloadProperties("https://go.example.test/etendo/sws/neo/document-download",
        "download-secret", "300", () -> {
          String link = DocumentDownloadTokenService.createDownloadLink("sales-order-send",
              "sales-order", "order-1", "tenant-1",
              "sales-order-send:tenant-1:order-1:v1")
              .orElseThrow(() -> new AssertionError("Download link should be present"));

          assertTrue(link.startsWith(
              "https://go.example.test/etendo/sws/neo/document-download/"));
          String token = link.substring(link.lastIndexOf('/') + 1);
          DocumentDownloadTokenService.Claims claims = DocumentDownloadTokenService.validate(token)
              .orElseThrow(() -> new AssertionError("Token claims should be valid"));
          assertEquals("sales-order-send", claims.getContractName());
          assertEquals("sales-order", claims.getSpecName());
          assertEquals("order-1", claims.getRecordId());
          assertEquals("tenant-1", claims.getClientId());
          assertEquals("sales-order-send:tenant-1:order-1:v1", claims.getIdempotencyKey());
        });
  }

  @Test
  public void documentDownloadTokenRejectsTamperedOrExpiredTokens() {
    withDocumentDownloadProperties("https://go.example.test/etendo/sws/neo/document-download",
        "download-secret", "300", () -> {
          long nowSeconds = System.currentTimeMillis() / 1000L;
          String token = DocumentDownloadTokenService.createToken("sales-order-send",
              "sales-order", "order-1", "tenant-1",
              "sales-order-send:tenant-1:order-1:v1", nowSeconds + 60L);
          String expired = DocumentDownloadTokenService.createToken("sales-order-send",
              "sales-order", "order-1", "tenant-1",
              "sales-order-send:tenant-1:order-1:v1", nowSeconds - 1L);

          assertFalse(DocumentDownloadTokenService.validate(token + "tampered").isPresent());
          assertFalse(DocumentDownloadTokenService.validate(expired).isPresent());
        });
  }

  @Test
  public void sendContextExposesCommandProviderAndRecipientMetadata() throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, " tenant-1 ");
    body.put(EmailContractCommandSupport.FIELD_CLIENT_ID, " client-1 ");
    body.put(EmailContractCommandSupport.FIELD_USER_ID, " user-1 ");
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, " record-1 ");
    EmailSendContext context = new EmailSendContext(
        new EmailContractCommand("fixture-contract", body),
        EmailRecipientResolution.serverResolved("person@example.com"),
        new EmailProviderRequest(" Person@Example.COM ", " fixture-template ",
            new JSONObject(), null));

    JSONObject clientOnlyBody = new JSONObject();
    clientOnlyBody.put(EmailContractCommandSupport.FIELD_CLIENT_ID, " client-2 ");
    EmailSendContext clientOnlyContext = new EmailSendContext(
        new EmailContractCommand("fixture-contract", clientOnlyBody),
        EmailRecipientResolution.serverResolved("person@example.com"),
        new EmailProviderRequest("no-domain", "fixture-template", new JSONObject(), null));

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      assertEquals("fixture-contract", context.getContractName());
      assertEquals("tenant-1", context.getTenantId());
      assertEquals("user-1", context.getUserId());
      assertEquals("record-1", context.getRecordId());
      assertEquals("fixture-template", context.getTemplate());
      assertEquals("Person@Example.COM", context.getRecipientAddress());
      assertEquals("example.com", context.getRecipientDomain());
      assertEquals("client-2", clientOnlyContext.getTenantId());
    }
    assertNull(clientOnlyContext.getRecipientDomain());
  }

  @Test
  public void sendContextUsesCommandTenantWhenSystemContextIsActive() throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "account-tenant");
    EmailSendContext context = new EmailSendContext(
        new EmailContractCommand("fixture-contract", body),
        EmailRecipientResolution.serverResolved("person@example.com"),
        new EmailProviderRequest("person@example.com", "fixture-template",
            new JSONObject(), null));

    OBContext systemContext = contextWithClient("0");
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(systemContext);

      assertEquals("account-tenant", context.getTenantId());
    }
  }

  @Test
  public void sendContextPrefersRealContextClientOverCommandTenant() throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "command-tenant");
    EmailSendContext context = new EmailSendContext(
        new EmailContractCommand("fixture-contract", body),
        EmailRecipientResolution.serverResolved("person@example.com"),
        new EmailProviderRequest("person@example.com", "fixture-template",
            new JSONObject(), null));

    OBContext realClientContext = contextWithClient("client-1");
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(realClientContext);

      assertEquals("client-1", context.getTenantId());
    }
  }

  @Test
  public void auditRecordCapturesResolvedSendContext() throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    body.put(EmailContractCommandSupport.FIELD_USER_ID, "user-1");
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, "record-1");
    EmailSendContext context = new EmailSendContext(
        new EmailContractCommand("fixture-contract", body),
        EmailRecipientResolution.serverResolved("person@example.com"),
        new EmailProviderRequest("person@example.com", "fixture-template",
            new JSONObject(), null));

    EmailAuditRecord auditRecord = EmailAuditRecord.create(context,
        "fixture-contract:tenant-1:record-1:v1", 200, TransactionalEmailService.STATUS_SENT,
        "Sent", Integer.valueOf(202), true);

    assertEquals("fixture-contract", auditRecord.getContractName());
    assertEquals("fixture-contract:tenant-1:record-1:v1", auditRecord.getIdempotencyKey());
    assertEquals("tenant-1", auditRecord.getTenantId());
    assertEquals("user-1", auditRecord.getUserId());
    assertEquals("record-1", auditRecord.getRecordId());
    assertEquals("fixture-template", auditRecord.getTemplate());
    assertEquals("person@example.com", auditRecord.getRecipient());
    assertEquals("example.com", auditRecord.getRecipientDomain());
    assertEquals(200, auditRecord.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, auditRecord.getStatus());
    assertEquals("Sent", auditRecord.getMessage());
    assertEquals(Integer.valueOf(202), auditRecord.getProviderStatus());
    assertTrue(auditRecord.isDuplicate());
    assertTrue(auditRecord.getCreatedAtMillis() > 0);
  }

  @Test
  public void auditRecordCapturesMultiChannelHashListsWithoutRawAddresses() throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    EmailRecipientSet set = EmailRecipientSet.of(
        Arrays.asList("ap@example.com", "billing@example.com"),
        Collections.singletonList("pm@partner.com"));
    EmailSendContext context = new EmailSendContext(
        new EmailContractCommand("sales-invoice-send", body),
        EmailRecipientResolution.serverResolved(set),
        new EmailProviderRequest(set, "invoice", new JSONObject(), null));

    EmailAuditRecord auditRecord = EmailAuditRecord.create(context,
        "sales-invoice-send:tenant-1:record-1:send:v1:hash", 200,
        TransactionalEmailService.STATUS_SENT, null, Integer.valueOf(202), false);

    assertEquals(2, auditRecord.getFinalToRecipientHashes().size());
    assertEquals(1, auditRecord.getFinalCcRecipientHashes().size());
    assertTrue(auditRecord.getFinalRecipientDomains().contains("example.com"));
    assertTrue(auditRecord.getFinalRecipientDomains().contains("partner.com"));
    for (String hash : auditRecord.getFinalToRecipientHashes()) {
      assertFalse(hash.contains("@"));
      assertFalse(hash.contains("example.com"));
    }
  }

  @Test
  public void deliveryPolicyServerDerivedIgnoresCallerKey() throws Exception {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "caller-key");
    EmailSendContext context = new EmailSendContext(
        new EmailContractCommand("sales-invoice-send", body),
        EmailRecipientResolution.serverResolved("person@example.com"),
        new EmailProviderRequest("person@example.com", "invoice", new JSONObject(), null));

    EmailDeliveryPolicy serverDerived = EmailDeliveryPolicy.serverDerived("server-key",
        Collections.emptyList());
    EmailDeliveryPolicy serverDerivedNoKey = EmailDeliveryPolicy.serverDerived(null,
        Collections.emptyList());

    assertTrue(serverDerived.isServerDerivedIdempotency());
    assertEquals("server-key", serverDerived.resolveIdempotencyKey(context));
    assertNull(serverDerivedNoKey.resolveIdempotencyKey(context));
  }

  @Test
  public void throttleAndKillSwitchResultsExposeMetadata() {
    EmailThrottleResult allowedThrottle = EmailThrottleResult.allowed();
    EmailThrottleResult throttled = EmailThrottleResult.throttled(
        EmailThrottleRule.SCOPE_RECIPIENT, "person@example.com", 0);
    EmailKillSwitchResult allowedKillSwitch = EmailKillSwitchResult.allowed();
    EmailKillSwitchResult suppressed = EmailKillSwitchResult.suppressed(
        EmailThrottleRule.SCOPE_TEMPLATE, "fixture-template", "Template disabled");
    EmailThrottleRule rule = EmailThrottleRule.perTenant(0, 0);

    assertTrue(allowedThrottle.isAllowed());
    assertNull(allowedThrottle.getScope());
    assertFalse(throttled.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_RECIPIENT, throttled.getScope());
    assertEquals("person@example.com", throttled.getKey());
    assertEquals(1, throttled.getRetryAfterSeconds());
    assertTrue(allowedKillSwitch.isAllowed());
    assertNull(allowedKillSwitch.getMessage());
    assertFalse(suppressed.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_TEMPLATE, suppressed.getScope());
    assertEquals("fixture-template", suppressed.getKey());
    assertEquals("Template disabled", suppressed.getMessage());
    assertEquals(EmailThrottleRule.SCOPE_TENANT, rule.getScope());
    assertEquals(1, rule.getMaxAttempts());
    assertEquals(1, rule.getWindowSeconds());
  }

  private static void withProviderProperties(String baseUrl, String apiKey, String enabled,
      String timeoutMs, Runnable runnable) {
    String previousBaseUrl = System.getProperty(EmailProviderConfig.PROP_BASE_URL);
    String previousApiKey = System.getProperty(EmailProviderConfig.PROP_API_KEY);
    String previousEnabled = System.getProperty(EmailProviderConfig.PROP_ENABLED);
    String previousTimeoutMs = System.getProperty(EmailProviderConfig.PROP_TIMEOUT_MS);
    try {
      System.setProperty(EmailProviderConfig.PROP_BASE_URL, baseUrl);
      System.setProperty(EmailProviderConfig.PROP_API_KEY, apiKey);
      System.setProperty(EmailProviderConfig.PROP_ENABLED, enabled);
      System.setProperty(EmailProviderConfig.PROP_TIMEOUT_MS, timeoutMs);
      runnable.run();
    } finally {
      restoreProperty(EmailProviderConfig.PROP_BASE_URL, previousBaseUrl);
      restoreProperty(EmailProviderConfig.PROP_API_KEY, previousApiKey);
      restoreProperty(EmailProviderConfig.PROP_ENABLED, previousEnabled);
      restoreProperty(EmailProviderConfig.PROP_TIMEOUT_MS, previousTimeoutMs);
    }
  }

  private static void withDocumentDownloadProperties(String baseUrl, String secret,
      String ttlSeconds, Runnable runnable) {
    String previousBaseUrl = System.getProperty(
        DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL);
    String previousSecret = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET);
    String previousTtl = System.getProperty(DocumentDownloadTokenService.PROP_TOKEN_TTL_SECONDS);
    try {
      System.setProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL, baseUrl);
      System.setProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, secret);
      System.setProperty(DocumentDownloadTokenService.PROP_TOKEN_TTL_SECONDS, ttlSeconds);
      runnable.run();
    } finally {
      restoreProperty(DocumentDownloadTokenService.PROP_DOWNLOAD_BASE_URL, previousBaseUrl);
      restoreProperty(DocumentDownloadTokenService.PROP_TOKEN_SECRET, previousSecret);
      restoreProperty(DocumentDownloadTokenService.PROP_TOKEN_TTL_SECONDS, previousTtl);
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private static void assertNullPointerException(Runnable runnable) {
    try {
      runnable.run();
      fail("Expected NullPointerException");
    } catch (NullPointerException expected) {
      assertTrue(expected.getMessage().length() > 0);
    }
  }

  private static OBContext contextWithClient(String clientId) {
    OBContext context = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(context.getCurrentClient()).thenReturn(client);
    return context;
  }

  private static void assertOBException(Runnable runnable) {
    try {
      runnable.run();
      fail("Expected OBException");
    } catch (OBException expected) {
      assertTrue(expected.getMessage().length() > 0);
    }
  }
}
