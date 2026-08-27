/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * All portions are Copyright © 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.psd2;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/** Client for the private Salt Edge proxy provisioning endpoint. */
final class SaltEdgeProvisioningClient {

  private static final Logger log = LogManager.getLogger(SaltEdgeProvisioningClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private final HttpClient httpClient;

  SaltEdgeProvisioningClient() {
    this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
  }

  SaltEdgeProvisioningClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  String provision(String clientId, String email) {
    String adminKey = StringUtils.trimToNull(Psd2ApiKeyConfiguration.provisioningAdminKey());
    if (adminKey == null) {
      throw new IllegalStateException("PSD2 Salt Edge provisioning credential is not configured");
    }

    try {
      JSONObject body = new JSONObject();
      body.put("client_id", clientId);
      body.put("identifier", clientId);
      if (StringUtils.isNotBlank(email)) {
        body.put("email", email);
      }

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(Psd2ApiKeyConfiguration.provisioningUrl()))
          .timeout(REQUEST_TIMEOUT)
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .header("X-Admin-Key", adminKey)
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
          .build();
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn("Salt Edge provisioning failed for client {} with HTTP {}", clientId,
            response.statusCode());
        throw new IllegalStateException("Salt Edge provisioning failed with HTTP "
            + response.statusCode());
      }

      String apiKey = new JSONObject(response.body()).optString("api_key", null);
      if (StringUtils.isBlank(apiKey)) {
        throw new IllegalStateException("Salt Edge provisioning returned no API key");
      }
      return apiKey;
    } catch (IOException e) {
      log.warn("Salt Edge provisioning connection failed for client {}", clientId);
      throw new IllegalStateException("Salt Edge provisioning service is unavailable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Salt Edge provisioning was interrupted", e);
    } catch (Exception e) {
      if (e instanceof IllegalStateException) {
        throw (IllegalStateException) e;
      }
      throw new IllegalStateException("Invalid Salt Edge provisioning response", e);
    }
  }
}
