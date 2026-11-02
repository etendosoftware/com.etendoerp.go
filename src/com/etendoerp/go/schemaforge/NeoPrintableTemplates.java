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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.ConfigPropertyReader;
import com.etendoerp.go.common.NeoCurrencyFormatConfig;
import com.etendoerp.go.common.PublicUrlResolver;

/**
 * The document printables the frontend build publishes, as the backend reads them (ETP-6013).
 *
 * <h2>One template source</h2>
 * <p>The backend holds <b>no copy</b> of any printable's template. The frontend build publishes, next
 * to the SPA bundle, one JSON manifest per document — {@code <app>/printables/<document>.json},
 * written by {@code tools/app-shell/vite-plugins/printables-publish.js} from
 * {@code printableSources.js}, the very module the browser renders from. The manifest carries
 * exactly what the browser sends to jsreport except the record data: the Handlebars template, the
 * CSS, the {@code helpers} string, the chrome-pdf options and the labels resolved per locale. A
 * server-rendered PDF therefore uses the design of the deployed app, and a template change ships to
 * both renderers in the same deploy, with nothing to keep in step by hand.</p>
 *
 * <h2>Three values filled at render time</h2>
 * <p>The browser bakes the instance's thousands/decimal separators ({@code /sws/neo/currency-format},
 * here {@link NeoCurrencyFormatConfig}) and the exchange-rate precision into the helpers. The
 * manifest leaves them as placeholders it names in {@code helperPlaceholders}; {@link #fillHelpers}
 * replaces them the way {@code printableSources.vitest.js} proves reproduces the browser's string.</p>
 *
 * <h2>Where the manifests are</h2>
 * <p>{@value #URL_PROPERTY} / {@value #URL_ENV} when set (local dev points it at the Vite dev
 * server, {@code http://localhost:3100/printables}, which serves the same payloads), otherwise the
 * public app URL + {@code /printables}.</p>
 *
 * <h2>Cache, and its limit</h2>
 * <p>In memory, per document, for {@link #TTL}; a fetch failure is never cached. <b>Known limit:</b>
 * for up to that minute after a frontend deploy, a server render may still use the previous
 * manifest. The file it stores is written after the new bundle's build instant, so the SPA's
 * bundle-identity check (D16/D17) reads it as fresh and keeps serving the previous design until the
 * record (or the branding) changes again. The short TTL keeps that window small; it does not close
 * it.</p>
 */
public class NeoPrintableTemplates {

  /** Openbravo/system property overriding where the manifests are (the directory URL). */
  public static final String URL_PROPERTY = "etgo.printables.url";
  /** Environment variable overriding where the manifests are. */
  public static final String URL_ENV = "ETGO_PRINTABLES_URL";
  /** The directory the build publishes the manifests to, under the app's root. */
  static final String APP_PATH = "printables";
  /** The manifest shape this backend understands ({@code PRINTABLE_MANIFEST_VERSION} in JS). */
  static final int MANIFEST_VERSION = 1;
  static final Duration TTL = Duration.ofMinutes(1);
  static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
  /** The locale used when neither the requested one nor its language is published. */
  static final String FALLBACK_LOCALE = "en_US";

  private static final NeoPrintableTemplates INSTANCE = new NeoPrintableTemplates(
      NeoPrintableTemplates::fetchOverHttp, Clock.systemUTC());

  private final Function<String, String> fetcher;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  NeoPrintableTemplates(Function<String, String> fetcher, Clock clock) {
    this.fetcher = fetcher;
    this.clock = clock;
  }

  /** @return the production instance (HTTP fetch, wall clock) */
  public static NeoPrintableTemplates getInstance() {
    return INSTANCE;
  }

  private static final class Cached {
    private final Template template;
    private final Instant fetchedAt;

    private Cached(Template template, Instant fetchedAt) {
      this.template = template;
      this.fetchedAt = fetchedAt;
    }
  }

  /** A manifest that cannot be used: unreachable, not JSON, an unknown version or incomplete. */
  public static class ManifestException extends Exception {
    private static final long serialVersionUID = 1L;

    ManifestException(String message) {
      super(message);
    }

    ManifestException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The published printable of a document.
   *
   * @param document the manifest key an entity declares in {@code MCP_CONFIG.pdf.print.document}
   * @return the template, from the cache when it is younger than {@link #TTL}
   * @throws ManifestException when it cannot be fetched or is not a usable manifest
   */
  public Template get(String document) throws ManifestException {
    if (StringUtils.isBlank(document) || !document.matches("[a-z0-9-]+")) {
      throw new ManifestException("Invalid printable key");
    }
    Cached cached = cache.get(document);
    Instant now = clock.instant();
    if (cached != null && cached.fetchedAt.plus(TTL).isAfter(now)) {
      return cached.template;
    }
    String base = baseUrl();
    if (base == null) {
      throw new ManifestException("The printables location is not configured: set " + URL_ENV
          + " or the public app URL (" + PublicUrlResolver.APP_BASE_URL_ENV + ")");
    }
    String raw;
    try {
      raw = fetcher.apply(PublicUrlResolver.appendPath(base, document + ".json"));
    } catch (RuntimeException e) {
      throw new ManifestException("Printable '" + document + "' could not be fetched: "
          + e.getMessage(), e);
    }
    Template template = Template.parse(document, raw);
    cache.put(document, new Cached(template, now));
    return template;
  }

  /** Drops every cached manifest (tests, or an operator who just deployed a new frontend). */
  public void invalidate() {
    cache.clear();
  }

  /**
   * Where the manifests are: the explicit override, otherwise the public app URL + {@code /printables}.
   *
   * @return the directory URL without a trailing slash, or {@code null} when nothing is configured
   */
  public static String baseUrl() {
    String override = configured(URL_PROPERTY, URL_ENV);
    if (override != null) {
      return override;
    }
    return PublicUrlResolver.appendPath(PublicUrlResolver.resolveConfiguredAppBaseUrl(), APP_PATH);
  }

  /**
   * A URL configured as a system property, an {@code Openbravo.properties} entry or an environment
   * variable — in that order, as {@link PublicUrlResolver} reads its own.
   *
   * @return the value without a trailing slash, or {@code null}
   */
  static String configured(String property, String env) {
    String value = ConfigPropertyReader.readConfigValue(property, env, null);
    return StringUtils.trimToNull(StringUtils.stripEnd(StringUtils.trimToNull(value), "/"));
  }


  /**
   * The client that fetches the manifests. HTTP/1.1: over plain http the default HTTP/2 sends an h2c
   * Upgrade the Vite dev server swallows (its HMR websocket), so the fetch hung until the timeout.
   */
  static HttpClient buildHttpClient() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(FETCH_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  private static String fetchOverHttp(String url) {
    HttpClient http = buildHttpClient();
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(FETCH_TIMEOUT)
        .header("Accept", "application/json").GET().build();
    try {
      HttpResponse<String> response = http.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("HTTP " + response.statusCode());
      }
      return response.body();
    } catch (IOException e) {
      throw new IllegalStateException(e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  /** One published printable, validated. Immutable: the JSON it wraps is never handed out. */
  public static final class Template {
    private final String document;
    private final JSONObject manifest;

    private Template(String document, JSONObject manifest) {
      this.document = document;
      this.manifest = manifest;
    }

    /**
     * Parses and validates a manifest.
     *
     * @param document the key it was fetched for; the manifest must say the same
     * @param raw      the fetched body
     * @return the template
     * @throws ManifestException when the body is not a manifest this backend can render
     */
    static Template parse(String document, String raw) throws ManifestException {
      JSONObject manifest;
      try {
        // A static host answering its SPA fallback (index.html, 200) for a missing file lands here.
        manifest = new JSONObject(StringUtils.defaultString(raw));
      } catch (JSONException e) {
        throw new ManifestException("Printable '" + document + "' is not JSON (not deployed?)", e);
      }
      if (manifest.optInt("manifestVersion", -1) != MANIFEST_VERSION) {
        throw new ManifestException("Printable '" + document + "' has manifest version "
            + manifest.opt("manifestVersion") + ", expected " + MANIFEST_VERSION);
      }
      if (!document.equals(manifest.optString("document", null))) {
        throw new ManifestException("Printable '" + document + "' names another document");
      }
      JSONObject template = manifest.optJSONObject("template");
      JSONObject labels = manifest.optJSONObject("labels");
      if (template == null || StringUtils.isBlank(template.optString("content", null))
          || StringUtils.isBlank(manifest.optString("helpers", null))
          || manifest.optJSONObject("helperPlaceholders") == null || labels == null
          || labels.length() == 0) {
        throw new ManifestException("Printable '" + document + "' is incomplete");
      }
      return new Template(document, manifest);
    }

    /** @return the manifest key */
    public String getDocument() {
      return document;
    }

    /** @return the template family ({@code commercial}, ...) */
    public String getFamily() {
      return manifest.optString("family", null);
    }

    /** @return the build instant the manifest was published at, or {@code null} */
    public Long getBuiltAt() {
      return manifest.has("builtAt") && !manifest.isNull("builtAt")
          ? manifest.optLong("builtAt") : null;
    }

    /**
     * The labels for a locale: the exact locale, else the first published locale of the same
     * language ({@code es_AR} → {@code es_ES}), else {@value #FALLBACK_LOCALE}, else any.
     *
     * @param locale an Etendo language code ({@code es_ES}) or a BCP-47 tag ({@code es-ES})
     * @return a copy of the labels object
     */
    public JSONObject labelsFor(String locale) throws JSONException {
      JSONObject labels = manifest.getJSONObject("labels");
      String code = StringUtils.replace(StringUtils.defaultString(locale), "-", "_");
      JSONObject exact = labels.optJSONObject(code);
      if (exact != null) {
        return new JSONObject(exact.toString());
      }
      String language = StringUtils.substringBefore(code, "_").toLowerCase(Locale.ROOT);
      JSONObject sameLanguage = null;
      Iterator<?> keys = labels.keys();
      String any = null;
      while (keys.hasNext()) {
        String key = (String) keys.next();
        any = any == null ? key : any;
        if (sameLanguage == null && StringUtils.isNotBlank(language)
            && key.toLowerCase(Locale.ROOT).startsWith(language + "_")) {
          sameLanguage = labels.getJSONObject(key);
        }
      }
      JSONObject chosen = sameLanguage != null ? sameLanguage
          : labels.optJSONObject(FALLBACK_LOCALE) != null ? labels.getJSONObject(FALLBACK_LOCALE)
          : labels.getJSONObject(any);
      return new JSONObject(chosen.toString());
    }

    /**
     * The {@code helpers} string with the instance separators and the rate precision filled in —
     * byte for byte what the browser sends for the same values.
     *
     * @param separators   the instance currency format
     * @param rateDecimals the exchange-rate precision ({@code rateDecimals} of the data; negative
     *                     falls back to 4, as the browser does)
     * @return the helpers string jsreport receives
     */
    public String fillHelpers(NeoCurrencyFormatConfig separators, int rateDecimals)
        throws JSONException {
      JSONObject placeholders = manifest.getJSONObject("helperPlaceholders");
      String thousands = StringUtils.defaultIfEmpty(separators.getThousandsSeparator(), ".");
      String decimal = StringUtils.defaultIfEmpty(separators.getDecimalSeparator(), ",");
      String helpers = manifest.getString("helpers");
      // The separators sit in the source as JSON string literals: swap the quoted token for the
      // quoted value, which is what JSON.stringify produces in the browser.
      helpers = StringUtils.replace(helpers,
          JSONObject.quote(placeholders.getString("thousandsSeparator")),
          JSONObject.quote(thousands));
      helpers = StringUtils.replace(helpers,
          JSONObject.quote(placeholders.getString("decimalSeparator")),
          JSONObject.quote(decimal));
      return StringUtils.replace(helpers, placeholders.getString("rateDecimals"),
          String.valueOf(rateDecimals >= 0 ? rateDecimals : 4));
    }

    /**
     * The jsreport request for one document: the browser's payload shape, data merged after the
     * CSS exactly like {@code renderPdf} does ({@code data: {css, ...data}}).
     *
     * @param data   the document data (the browser data builder's shape), without labels
     * @param locale the language the PDF is printed in
     * @return the request body for {@link NeoJsreportClient#render}
     */
    public JSONObject payload(JSONObject data, String locale) throws JSONException {
      JSONObject template = new JSONObject(manifest.getJSONObject("template").toString());
      int rateDecimals = data.has("rateDecimals") ? data.optInt("rateDecimals", 4) : 4;
      template.put("helpers", fillHelpers(NeoCurrencyFormatConfig.fromRuntime(), rateDecimals));
      JSONObject body = new JSONObject();
      body.put("css", manifest.optString("css", ""));
      Iterator<?> keys = data.keys();
      while (keys.hasNext()) {
        String key = (String) keys.next();
        body.put(key, data.get(key));
      }
      body.put("labels", labelsFor(locale));
      JSONObject payload = new JSONObject();
      payload.put("template", template);
      payload.put("data", body);
      return payload;
    }
  }
}
