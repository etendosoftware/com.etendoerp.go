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

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.*;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import com.etendoerp.go.schemaforge.NeoResponse;

/** Executes the real contract and shared email pipeline with an in-memory provider only. */
public class InternalAlertEmailContractTest {
  private static InternalAlertEvent event(InternalAlertEvent.Status status, String attempt) {
    return new InternalAlertEvent("environment-provisioning", status, attempt, "PRODUCTIVE",
        "client-1", "POOL", "pooled_dataset", "OBException");
  }
  private static InternalAlertConfig config(String language) {
    return new InternalAlertConfig(true, List.of("ops@example.test", "builds@example.test"),
        EnumSet.allOf(InternalAlertEvent.Status.class), language);
  }
  private static TransactionalEmailService service(FakeProvider provider) {
    return new TransactionalEmailService(name -> InternalAlertEmailContract.NAME.equals(name)
        ? Optional.of(new InternalAlertEmailContract()) : Optional.empty(), provider,
        new InMemoryEmailSafetyStore());
  }
  @Test public void serializedHttpForgeryIsRejectedEvenWithTrustedLookingFields() throws Exception {
    FakeProvider provider = new FakeProvider();
    JSONObject httpBody = new JSONObject(InternalAlertEmailContract.body(
        event(InternalAlertEvent.Status.ERROR, "attempt-1"), config("es_ES")).toString());
    httpBody.put("event", "environment-provisioning"); httpBody.put("status", "ERROR");
    httpBody.put("internal", true); httpBody.put("trusted", true);
    assertEquals(403, service(provider).send(InternalAlertEmailContract.NAME, httpBody).getHttpStatus());
    assertEquals(0, provider.requests.size());
  }
  @Test public void validServerEventUsesConfiguredRecipientsSharedLayoutAndBothCatalogs() throws Exception {
    for (String language : List.of("es_ES", "en_US")) {
      FakeProvider provider = new FakeProvider();
      NeoResponse response = service(provider).send(InternalAlertEmailContract.NAME,
          InternalAlertEmailContract.body(event(InternalAlertEvent.Status.ERROR, "attempt-1"), config(language)));
      assertEquals(response.getBody().toString(), 200, response.getHttpStatus()); assertEquals(1, provider.requests.size());
      EmailProviderRequest request = provider.requests.get(0);
      assertEquals("custom", request.getTemplate());
      assertEquals(List.of("ops@example.test", "builds@example.test"), request.getRecipients().getTo());
      String body = request.getData().getString("body");
      assertTrue(body.contains("<!DOCTYPE html>")); assertTrue(body.contains("environment-provisioning"));
      assertTrue(body.contains("POOL")); assertTrue(body.contains("pooled_dataset"));
      assertFalse(body.contains("internal-alert.")); assertFalse(body.contains("paymentToken"));
      assertFalse(body.contains("password")); assertFalse(body.contains("apiKey"));
      assertTrue(body.contains(language.equals("es_ES") ? "Resultado" : "Result"));
    }
  }
  @Test public void duplicateAttemptAndStatusSendOnceButRetryOrDifferentResultSendAgain() throws Exception {
    FakeProvider provider = new FakeProvider(); TransactionalEmailService service = service(provider);
    JSONObject error = InternalAlertEmailContract.body(event(InternalAlertEvent.Status.ERROR, "attempt-1"), config("es_ES"));
    assertEquals(200, service.send(InternalAlertEmailContract.NAME, error).getHttpStatus());
    service.send(InternalAlertEmailContract.NAME, error);
    assertEquals(1, provider.requests.size());
    service.send(InternalAlertEmailContract.NAME, InternalAlertEmailContract.body(event(InternalAlertEvent.Status.OK, "attempt-1"), config("es_ES")));
    service.send(InternalAlertEmailContract.NAME, InternalAlertEmailContract.body(event(InternalAlertEvent.Status.ERROR, "attempt-2"), config("es_ES")));
    assertEquals(3, provider.requests.size());
  }
  @Test public void providerFailureIsRetryableWithoutClaimingDelivery() throws Exception {
    FakeProvider provider = new FakeProvider(); provider.fail = true;
    TransactionalEmailService service = service(provider);
    JSONObject body = InternalAlertEmailContract.body(event(InternalAlertEvent.Status.ERROR, "attempt-1"), config("es_ES"));
    assertTrue(service.send(InternalAlertEmailContract.NAME, body).getHttpStatus() >= 400);
    provider.fail = false;
    assertEquals(200, service.send(InternalAlertEmailContract.NAME, body).getHttpStatus());
    assertEquals(2, provider.requests.size());
  }
  @Test public void filteredSuccessAndBrowserRecipientInjectionDoNotReachProvider() throws Exception {
    FakeProvider provider = new FakeProvider();
    InternalAlertConfig errors = new InternalAlertConfig(true, List.of("ops@example.test"),
        EnumSet.of(InternalAlertEvent.Status.ERROR), "es_ES");
    assertEquals(403, service(provider).send(InternalAlertEmailContract.NAME,
        InternalAlertEmailContract.body(event(InternalAlertEvent.Status.OK, "attempt-1"), errors)).getHttpStatus());
    JSONObject forged = new JSONObject(); forged.put("to", "attacker@example.test");
    assertEquals(400, service(provider).send(InternalAlertEmailContract.NAME, forged).getHttpStatus());
    assertEquals(0, provider.requests.size());
  }
  @Test public void freeformErrorsAndHtmlCannotEnterOperationalMetadata() {
    for (String bad : List.of("<script>alert(1)</script>", "password=secret", "API key: secret", "broken\nheader")) {
      assertThrows(IllegalArgumentException.class, () -> new InternalAlertEvent(
          "environment-provisioning", InternalAlertEvent.Status.ERROR, "attempt-1", "DEMO",
          null, "CLASSIC", "dataset", bad));
    }
  }
  private static class FakeProvider implements EmailProviderAdapter {
    private final List<EmailProviderRequest> requests = new ArrayList<>(); private boolean fail;
    @Override public boolean isConfigured() { return true; }
    @Override public boolean supportsMultipleRecipients() { return true; }
    @Override public EmailProviderResponse send(EmailProviderRequest request) {
      requests.add(request); return new EmailProviderResponse(fail ? 503 : 202, "{}");
    }
  }
}
