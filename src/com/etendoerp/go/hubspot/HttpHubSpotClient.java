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

package com.etendoerp.go.hubspot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.codehaus.jettison.json.JSONObject;

/**
 * The production {@link HubSpotClient}, on {@link HttpClient}: the CRM API updates with
 * {@code PATCH}, which {@link java.net.HttpURLConnection} cannot send.
 */
public class HttpHubSpotClient implements HubSpotClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final HubSpotConfiguration configuration;
  private final HttpClient httpClient;

  /**
   * Creates a client bounded by a 20-second timeout.
   *
   * @param configuration the API root and the token every call carries
   */
  public HttpHubSpotClient(HubSpotConfiguration configuration) {
    this(configuration, HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .version(HttpClient.Version.HTTP_1_1)
        .build());
  }

  HttpHubSpotClient(HubSpotConfiguration configuration, HttpClient httpClient) {
    this.configuration = configuration;
    this.httpClient = httpClient;
  }

  @Override
  public Response send(String method, String path, JSONObject body)
      throws HubSpotUnreachableException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(configuration.apiBaseUrl() + path))
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + configuration.token())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(response.statusCode(),
          response.body() == null ? "" : response.body());
    } catch (IOException e) {
      throw new HubSpotUnreachableException("HubSpot call " + method + " " + path
          + " did not complete", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HubSpotUnreachableException("HubSpot call " + method + " " + path
          + " was interrupted", e);
    }
  }
}
