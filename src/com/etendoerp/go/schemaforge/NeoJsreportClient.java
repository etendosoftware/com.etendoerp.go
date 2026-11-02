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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.PublicUrlResolver;

/**
 * The backend's client of the jsreport service the browser renders printables with (ETP-6013).
 *
 * <p><b>Where jsreport is.</b> The browser POSTs to {@code /jsreport/api/report} on the app's own
 * origin; the deployment routes that path to the jsreport container. The backend reaches the very
 * same service through the public app URL it already knows per environment
 * ({@link PublicUrlResolver#resolveConfiguredAppBaseUrl()} + {@code /jsreport}). A local setup, where
 * the app URL is the Vite dev server, overrides it with {@value #URL_PROPERTY} (Openbravo.properties
 * or a system property) or the {@value #URL_ENV} environment variable — e.g.
 * {@code http://localhost:5488}.</p>
 *
 * <p><b>Bounded.</b> A render is a network call to another container. It must never hold a request
 * (or a document completion) for long, so both the connection and the whole exchange have a hard
 * timeout, and any failure surfaces as {@link RenderException} for the caller to log and skip.</p>
 *
 * <p>Jasper is not an option here (ETP-4255: no Jasper at runtime in NEO/MCP), and the Node report
 * server only accepts the SPA session cookie, not an MCP token. jsreport is what the browser uses, so
 * it is the only renderer that yields the same PDF.</p>
 */
public class NeoJsreportClient {

  /** Openbravo/system property overriding the jsreport base URL (no trailing {@code /api}). */
  public static final String URL_PROPERTY = "etgo.jsreport.url";
  /** Environment variable overriding the jsreport base URL. */
  public static final String URL_ENV = "JSREPORT_URL";
  /** Path of jsreport under the public app URL, as the browser reaches it. */
  static final String APP_PATH = "jsreport";
  static final String REPORT_PATH = "api/report";

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration RENDER_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient http;

  /** The production client. */
  public NeoJsreportClient() {
    this(buildHttpClient());
  }

  /**
   * The production client. HTTP/1.1, as the manifest fetch: the default HTTP/2 sends an h2c Upgrade
   * over plain http, which a dev server may swallow (see {@code NeoPrintableTemplates}).
   */
  static HttpClient buildHttpClient() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  NeoJsreportClient(HttpClient http) {
    this.http = http;
  }

  /** A render that produced no PDF: unreachable service, non-2xx answer, timeout or a non-PDF body. */
  public static class RenderException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int httpStatus;

    RenderException(String message) {
      this(message, 0);
    }

    RenderException(String message, int httpStatus) {
      super(message);
      this.httpStatus = httpStatus;
    }

    RenderException(String message, Throwable cause) {
      super(message, cause);
      this.httpStatus = 0;
    }

    /** @return jsreport's HTTP status, or 0 when no answer came back */
    public int getHttpStatus() {
      return httpStatus;
    }
  }

  /**
   * The jsreport base URL: the explicit override, otherwise the public app URL + {@code /jsreport}.
   *
   * @return the base URL without a trailing slash, or {@code null} when neither is configured
   */
  public static String baseUrl() {
    String override = NeoPrintableTemplates.configured(URL_PROPERTY, URL_ENV);
    if (override != null) {
      return override;
    }
    return PublicUrlResolver.appendPath(PublicUrlResolver.resolveConfiguredAppBaseUrl(), APP_PATH);
  }

  /**
   * Renders one payload — the exact shape the browser POSTs — into PDF bytes.
   *
   * @param payload {@code {template:{content, engine, recipe, helpers, chrome}, data:{css, ...}}}
   * @return the PDF bytes
   * @throws RenderException when no PDF came back
   */
  public byte[] render(JSONObject payload) throws RenderException {
    String base = baseUrl();
    if (base == null) {
      throw new RenderException("jsreport is not configured: set " + URL_ENV + " or the public "
          + "app URL (" + PublicUrlResolver.APP_BASE_URL_ENV + ")");
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(PublicUrlResolver.appendPath(base,
        REPORT_PATH)))
        .timeout(RENDER_TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
        .build();
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new RenderException("jsreport unreachable: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RenderException("jsreport render interrupted", e);
    }
    byte[] body = response.body();
    if (response.statusCode() / 100 != 2) {
      // The status only: jsreport's error body echoes the template and the document's data.
      throw new RenderException("jsreport answered " + response.statusCode(),
          response.statusCode());
    }
    if (!isPdf(body)) {
      throw new RenderException("jsreport answered something that is not a PDF");
    }
    return body;
  }

  /** The {@code %PDF-} magic: a proxy answering an HTML page with 200 must not become a "PDF". */
  static boolean isPdf(byte[] body) {
    return body != null && body.length > 5 && body[0] == '%' && body[1] == 'P' && body[2] == 'D'
        && body[3] == 'F' && body[4] == '-';
  }
}
