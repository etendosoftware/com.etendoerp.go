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

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link ApiGatewayEmailProviderAdapter}.
 */
public class ApiGatewayEmailProviderAdapterTest {

  @Test
  public void sendsResolvedProviderPayloadWithServerSideConfig() throws Exception {
    CapturingTransport transport = new CapturingTransport(new EmailProviderResponse(202, "{}"));
    EmailProviderConfig config = new EmailProviderConfig("https://provider.example/send", "secret",
        true, 1200);
    ApiGatewayEmailProviderAdapter adapter = new ApiGatewayEmailProviderAdapter(config, transport);

    JSONObject data = new JSONObject();
    data.put("name", "Lucas");
    EmailProviderResponse response = adapter.send(new EmailProviderRequest("user@example.com",
        "reset-password", data, null));

    JSONObject payload = new JSONObject(transport.body);
    assertEquals(202, response.getStatusCode());
    assertEquals("https://provider.example/send", transport.endpoint);
    assertEquals("secret", transport.apiKey);
    assertEquals(1200, transport.timeoutMs);
    assertEquals("user@example.com", payload.getString("to"));
    assertEquals("reset-password", payload.getString("template"));
    assertEquals("Lucas", payload.getJSONObject("data").getString("name"));
    assertFalse(payload.has("from"));
    assertFalse(payload.has("sender"));
  }

  @Test
  public void rejectsSendWhenProviderConfigIsIncomplete() throws Exception {
    CapturingTransport transport = new CapturingTransport(new EmailProviderResponse(202, "{}"));
    EmailProviderConfig config = new EmailProviderConfig(null, null, true, 1200);
    ApiGatewayEmailProviderAdapter adapter = new ApiGatewayEmailProviderAdapter(config, transport);

    try {
      adapter.send(new EmailProviderRequest("user@example.com", "reset-password",
          new JSONObject(), null));
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("not properly configured"));
      assertEquals(null, transport.endpoint);
      return;
    }
    throw new AssertionError("Expected incomplete provider configuration to fail");
  }

  private static class CapturingTransport implements ApiGatewayEmailProviderAdapter.EmailTransport {
    private final EmailProviderResponse response;
    private String endpoint;
    private String apiKey;
    private String body;
    private int timeoutMs;

    CapturingTransport(EmailProviderResponse response) {
      this.response = response;
    }

    @Override
    public EmailProviderResponse post(String endpoint, String apiKey, String body, int timeoutMs)
        throws IOException {
      this.endpoint = endpoint;
      this.apiKey = apiKey;
      this.body = body;
      this.timeoutMs = timeoutMs;
      return response;
    }
  }
}
