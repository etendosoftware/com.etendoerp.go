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
import java.util.Collections;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Tests for editable To/CC recipients in the document-send contract family (ETP-4226).
 * Covers proposal section 9 edge cases 3-8, 12, and 13b.
 */
public class DocumentSendRecipientEditsTest {

  private static final String CONTRACT = "doc-send-test";

  @Test
  public void noEditsResolvesTrustedBaseRecipient() throws Exception {
    // Edge case 3.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(null));

    assertSent(response);
    assertEquals("contact@x.com", adapter.getLastRequest().getRecipient());
    assertTrue(adapter.getLastRequest().getRecipients().getCc().isEmpty());
  }

  @Test
  public void addsToAndCcRecipients() throws Exception {
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"to\":{\"add\":[\"ap@x.com\"]},\"cc\":{\"add\":[\"pm@x.com\"]}}"));

    assertSent(response);
    EmailRecipientSet sent = adapter.getLastRequest().getRecipients();
    assertEquals(java.util.Arrays.asList("contact@x.com", "ap@x.com"), sent.getTo());
    assertEquals(Collections.singletonList("pm@x.com"), sent.getCc());
  }

  @Test
  public void removeAllBaseWithoutReplacementReturnsNoRecipient() throws Exception {
    // Edge case 5.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"to\":{\"remove\":[\"contact@x.com\"]}}"));

    JSONObject data = responseData(response);
    assertEquals(422, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_NO_RECIPIENT, data.getString("status"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void ccOnlyAfterRemovingBaseReturnsNoRecipient() throws Exception {
    // Edge case 6.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"to\":{\"remove\":[\"contact@x.com\"]},\"cc\":{\"add\":[\"pm@x.com\"]}}"));

    JSONObject data = responseData(response);
    assertEquals(422, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_NO_RECIPIENT, data.getString("status"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void invalidEmailInEditsReturnsValidationFailed() throws Exception {
    // Edge case 7.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"to\":{\"add\":[\"not-an-email\"]}}"));

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void crossChannelDedupKeepsToPrecedence() throws Exception {
    // Edge case 8.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"cc\":{\"add\":[\"contact@x.com\",\"pm@x.com\"]}}"));

    assertSent(response);
    EmailRecipientSet sent = adapter.getLastRequest().getRecipients();
    assertEquals(Collections.singletonList("contact@x.com"), sent.getTo());
    assertEquals(Collections.singletonList("pm@x.com"), sent.getCc());
  }

  @Test
  public void baseContactWithoutEmailButValidAdditionsResolves() throws Exception {
    // Edge case 4 (with valid additions): empty base, valid edits -> resolves additions.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contractWithoutBaseEmail(true), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"to\":{\"add\":[\"ap@x.com\"]}}"));

    assertSent(response);
    assertEquals("ap@x.com", adapter.getLastRequest().getRecipient());
  }

  @Test
  public void exceedingMaxRecipientsReturnsValidationFailed() throws Exception {
    // Edge case 12.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contractWithMax(2), adapter);

    NeoResponse response = service.send(CONTRACT, command(
        "{\"to\":{\"add\":[\"a@x.com\",\"b@x.com\"]}}"));

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertTrue(data.getString("message").contains("maximum"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void editingDisabledRejectsRecipientEdits() throws Exception {
    // Edge case 13b.
    FakeProviderAdapter adapter = capableAdapter();
    TransactionalEmailService service = service(contract(false), adapter);

    NeoResponse response = service.send(CONTRACT, command("{\"to\":{\"add\":[\"ap@x.com\"]}}"));

    JSONObject data = responseData(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_VALIDATION_FAILED, data.getString("status"));
    assertTrue(data.getString("message").contains("recipientEdits"));
    assertFalse(adapter.wasSendCalled());
  }

  @Test
  public void callerIdempotencyKeyIsIgnoredAndIdenticalSendDuplicates() throws Exception {
    // Case 10: same record + same final set -> DUPLICATE, even with differing caller keys.
    FakeProviderAdapter adapter = capableAdapter();
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(contract(true)), adapter, store);

    JSONObject first = command("{\"to\":{\"add\":[\"ap@x.com\"]}}");
    first.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "caller-key-1");
    JSONObject second = command("{\"to\":{\"add\":[\"ap@x.com\"]}}");
    second.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY, "caller-key-2");

    NeoResponse firstResponse = service.send(CONTRACT, first);
    NeoResponse secondResponse = service.send(CONTRACT, second);

    assertSent(firstResponse);
    assertEquals(TransactionalEmailService.STATUS_DUPLICATE,
        responseData(secondResponse).getString("status"));
  }

  @Test
  public void differentFinalRecipientSetUsesDifferentIdempotencyKey() throws Exception {
    // Case 11: same record, different final set -> not a duplicate.
    FakeProviderAdapter adapter = capableAdapter();
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    TransactionalEmailService service = new TransactionalEmailService(
        new SingleContractRegistry(contract(true)), adapter, store);

    NeoResponse firstResponse = service.send(CONTRACT, command("{\"to\":{\"add\":[\"ap@x.com\"]}}"));
    NeoResponse secondResponse = service.send(CONTRACT,
        command("{\"to\":{\"add\":[\"other@x.com\"]}}"));

    assertSent(firstResponse);
    assertSent(secondResponse);
  }

  @Test
  public void downloadLinkTokenKeyStaysStablePerRecordIndependentOfRecipientEdits()
      throws Exception {
    // The download-token key is the per-record key (no recipient hash, no ":send:"), so re-sends
    // with edited recipients reuse the same token. The send idempotency key, by contrast, embeds
    // the recipient-set hash (verified by the duplicate/non-duplicate tests above).
    String downloadTokenKey = EmailContractCommandSupport.idempotencyKey(CONTRACT, "tenant-1",
        "order-1");

    assertEquals(CONTRACT + ":tenant-1:order-1:v1", downloadTokenKey);
    assertFalse(downloadTokenKey.contains(":send:"));
  }

  private static TransactionalEmailService service(EmailContract contract,
      FakeProviderAdapter adapter) {
    return new TransactionalEmailService(new SingleContractRegistry(contract), adapter,
        new InMemoryEmailSafetyStore());
  }

  private static JSONObject command(String recipientEditsJson) throws Exception {
    JSONObject command = new JSONObject();
    command.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
    command.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    command.put(EmailContractCommandSupport.FIELD_RECORD_ID, "order-1");
    if (recipientEditsJson != null) {
      command.put(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS,
          new JSONObject(recipientEditsJson));
    }
    return command;
  }

  private static void assertSent(NeoResponse response) throws Exception {
    JSONObject data = responseData(response);
    assertEquals(200, response.getHttpStatus());
    assertEquals(TransactionalEmailService.STATUS_SENT, data.getString("status"));
  }

  private static JSONObject responseData(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("response").getJSONObject("data");
  }

  private static FakeProviderAdapter capableAdapter() {
    return new FakeProviderAdapter();
  }

  private static DefaultDocumentSendEmailContract contract(boolean editingEnabled) {
    return new TestDocumentContract("contact@x.com", editingEnabled, 10);
  }

  private static DefaultDocumentSendEmailContract contractWithoutBaseEmail(boolean editing) {
    return new TestDocumentContract(null, editing, 10);
  }

  private static DefaultDocumentSendEmailContract contractWithMax(int max) {
    return new TestDocumentContract("contact@x.com", true, max);
  }

  private static final class TestDocumentContract extends DefaultDocumentSendEmailContract {
    private final boolean editingEnabled;
    private final int maxTotal;

    TestDocumentContract(String baseEmail, boolean editingEnabled, int maxTotal) {
      super(CONTRACT, "Sales Order",
          recordId -> Optional.of(new EmailDocumentRecord("Empresa SRL", baseEmail, "order-1",
              "SO-1", "10.00 USD", "https://app.example.test/doc/order-1", "tenant-1")));
      this.editingEnabled = editingEnabled;
      this.maxTotal = maxTotal;
    }

    @Override
    protected boolean isRecipientEditingEnabled() {
      return editingEnabled;
    }

    @Override
    protected int maxRecipientsTotal() {
      return maxTotal;
    }
  }

  private static final class SingleContractRegistry implements EmailContractRegistry {
    private final EmailContract contract;

    SingleContractRegistry(EmailContract contract) {
      this.contract = contract;
    }

    @Override
    public Optional<EmailContract> find(String contractName) {
      return contract.getName().equals(contractName) ? Optional.of(contract) : Optional.empty();
    }
  }

  private static final class FakeProviderAdapter implements EmailProviderAdapter {
    private EmailProviderRequest lastRequest;
    private boolean sendCalled;

    @Override
    public boolean isConfigured() {
      return true;
    }

    @Override
    public boolean supportsMultipleRecipients() {
      return true;
    }

    @Override
    public boolean supportsCcChannel() {
      return true;
    }

    @Override
    public EmailProviderResponse send(EmailProviderRequest request) throws IOException {
      this.lastRequest = request;
      this.sendCalled = true;
      return new EmailProviderResponse(202, "{}");
    }

    EmailProviderRequest getLastRequest() {
      return lastRequest;
    }

    boolean wasSendCalled() {
      return sendCalled;
    }
  }
}
